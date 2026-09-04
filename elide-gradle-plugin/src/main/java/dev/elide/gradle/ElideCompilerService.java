package dev.elide.gradle;

import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Build-scoped authenticated loopback bridge from JavaCompile launchers to warm Elide workers. */
public abstract class ElideCompilerService
        implements BuildService<BuildServiceParameters.None>, AutoCloseable {
    private final List<Entry> workers = new ArrayList<>();
    private boolean closed;

    private static final class Entry {
        final String key;
        final ElideWorkerProcess process;
        boolean busy;

        Entry(String key, ElideWorkerProcess process) {
            this.key = key;
            this.process = process;
        }
    }

    private final String token = UUID.randomUUID().toString();
    private final ExecutorService clients =
            Executors.newFixedThreadPool(
                    4,
                    r -> {
                        Thread t = new Thread(r, "elide-compiler-client");
                        t.setDaemon(true);
                        return t;
                    });
    private ServerSocket server;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();

    private record Prepared(String executable, List<File> classpath) {}

    private final Map<String, Prepared> prepared = new ConcurrentHashMap<>();

    public String registerCompiler(String executable, Collection<File> classpath) {
        String id = UUID.randomUUID().toString();
        prepared.put(id, new Prepared(executable, List.copyOf(classpath)));
        return id;
    }

    public synchronized String endpoint() {
        if (closed) throw new IllegalStateException("Elide compiler service is closed");
        if (server == null) {
            try {
                server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
                Thread acceptor =
                        new Thread(
                                () -> {
                                    while (!server.isClosed()) {
                                        try {
                                            Socket socket = server.accept();
                                            connections.add(socket);
                                            clients.execute(() -> handle(socket));
                                        } catch (IOException
                                                | RejectedExecutionException exception) {
                                            if (!server.isClosed()) close();
                                            return;
                                        }
                                    }
                                },
                                "elide-compiler-accept");
                acceptor.setDaemon(true);
                acceptor.start();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return server.getLocalPort() + ":" + token;
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(300_000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            if (!MessageDigest.isEqual(
                    token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    in.readUTF().getBytes(java.nio.charset.StandardCharsets.UTF_8))) return;
            String executable = in.readUTF();
            Path cwd = Path.of(in.readUTF());
            Prepared request = prepared.remove(in.readUTF());
            if (request == null || !request.executable().equals(executable))
                throw new IOException("Unknown compiler request");
            int count = in.readInt();
            if (count < 0 || count > 100_000)
                throw new IOException("Invalid compiler argument count");
            List<String> arguments = new ArrayList<>();
            long argumentBytes = 0;
            for (int i = 0; i < count; i++) {
                String argument = in.readUTF();
                argumentBytes += argument.length() * 3L;
                if (argumentBytes > ElideWorkerProtocol.MAX_FRAME)
                    throw new IOException("Compiler arguments exceed 64 MiB");
                arguments.add(argument);
            }
            ElideWorkerProtocol.Response response;
            try {
                Map<String, byte[]> inputs = digests(request.classpath());
                Entry entry = acquire(executable, cwd);
                try {
                    response = entry.process.compile(arguments, inputs);
                } finally {
                    release(entry);
                }
            } catch (IOException exception) {
                response =
                        new ElideWorkerProtocol.Response(
                                1, "Elide compiler worker failed: " + exception.getMessage(), 0);
            }
            byte[] output = response.output().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(response.exitCode());
            out.writeInt(output.length);
            out.write(output);
            out.flush();
        } catch (IOException ignored) {
            // A disconnected launcher has no recipient for a response.
        } finally {
            connections.remove(socket);
        }
    }

    private static Map<String, byte[]> digests(List<File> classpath) throws IOException {
        Map<String, byte[]> digests = new TreeMap<>();
        for (File entry : classpath) {
            Path file = entry.toPath().toAbsolutePath().normalize();
            // Elide deliberately bypasses its warm classpath cache for directory entries.
            if (!Files.isRegularFile(file)) continue;
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
                }
                digests.put(file.toString(), digest.digest());
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        }
        return digests;
    }

    private synchronized Entry acquire(String executable, Path cwd) throws IOException {
        if (closed) throw new IOException("Elide compiler service is closed");
        String key = executable + "\n" + cwd;
        for (Entry entry : workers) {
            if (!entry.busy && entry.key.equals(key) && entry.process.isAlive()) {
                entry.busy = true;
                return entry;
            }
        }
        // Four client handlers bound simultaneous compiles and the number of retained processes.
        if (workers.size() >= 4) {
            Entry idle =
                    workers.stream()
                            .filter(entry -> !entry.busy)
                            .findFirst()
                            .orElseThrow(() -> new IOException("Elide worker pool exhausted"));
            idle.process.close();
            workers.remove(idle);
        }
        Entry entry = new Entry(key, new ElideWorkerProcess(executable, cwd));
        entry.busy = true;
        workers.add(entry);
        return entry;
    }

    private synchronized void release(Entry entry) {
        entry.busy = false;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
        clients.shutdownNow();
        connections.forEach(
                socket -> {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                });
        connections.clear();
        prepared.clear();
        workers.forEach(entry -> entry.process.close());
        workers.clear();
    }
}
