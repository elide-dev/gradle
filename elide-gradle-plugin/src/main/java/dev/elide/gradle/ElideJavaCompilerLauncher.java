package dev.elide.gradle;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

/** Launches the selected Elide executable as Gradle's Java compiler process. */
public final class ElideJavaCompilerLauncher {
    private ElideJavaCompilerLauncher() {
    }

    public static void main(String[] arguments) throws IOException, InterruptedException {
        boolean gradleClasspath = arguments.length > 0 && arguments[0].equals("--gradle-classpath");
        if (gradleClasspath) arguments = Arrays.copyOfRange(arguments, 1, arguments.length);
        if (arguments.length > 0 && arguments[0].equals("--worker")) {
            runWorker(arguments);
            return;
        }
        if (arguments.length < 2) {
            throw new IllegalArgumentException("Expected an Elide executable and compiler arguments");
        }
        ProcessBuilder builder = new ProcessBuilder(Arrays.asList(arguments)).inheritIO();
        if (gradleClasspath) builder.environment().remove("CLASSPATH");
        Process process = start(builder);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
    private static void runWorker(String[] arguments) throws IOException {
        if (arguments.length < 6) throw new IOException("Missing worker compiler arguments");
        String[] endpoint = arguments[1].split(":", 2);
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(),
                    Integer.parseInt(endpoint[0])), 10_000);
            socket.setSoTimeout(310_000);
            var out = new java.io.DataOutputStream(socket.getOutputStream());
            out.writeUTF(endpoint[1]);
            out.writeUTF(arguments[3]);
            // Direct System.getProperty calls are instrumented by Gradle 7.x, but this
            // launcher runs in a standalone JVM without Gradle's instrumentation classes.
            out.writeUTF(java.nio.file.Path.of("").toAbsolutePath().toString());
            if (!arguments[2].startsWith("request=") || !arguments[4].equals("javac")) {
                throw new IOException("Malformed worker launcher arguments");
            }
            out.writeUTF(arguments[2].substring("request=".length()));
            out.writeInt(arguments.length - 5);
            for (int i = 5; i < arguments.length; i++) out.writeUTF(arguments[i]);
            out.flush();
            var in = new java.io.DataInputStream(socket.getInputStream());
            int code = in.readInt();
            int length = in.readInt();
            if (length < 0 || length > 64 * 1024 * 1024) throw new IOException("Invalid worker output length");
            byte[] output = in.readNBytes(length);
            if (output.length != length) throw new IOException("Truncated worker output");
            System.err.write(output);
            if (code != 0) System.exit(code);
        }
    }

    private static Process start(ProcessBuilder builder) throws IOException {
        try {
            // Gradle 7 instruments direct start() calls with Gradle-only classes. This launcher
            // also runs in a standalone JVM, so keep its process launch independent of Gradle.
            return (Process) ProcessBuilder.class.getMethod("start").invoke(builder);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Unable to start the Elide compiler", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IOException("Unable to start the Elide compiler", exception);
        }
    }
}
