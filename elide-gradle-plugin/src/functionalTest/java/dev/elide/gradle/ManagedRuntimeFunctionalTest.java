package dev.elide.gradle;

import com.sun.net.httpserver.HttpServer;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedRuntimeFunctionalTest {
    private static final String VERSION = "fixture-1.0";

    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path temporaryDirectory;

    @Test
    void downloadsVerifiesExtractsAndReusesTheManagedRuntime() throws Exception {
        FixturePlatform platform = currentPlatform();
        Path projectDirectory = temporaryDirectory.resolve("project");
        Path gradleUserHome = temporaryDirectory.resolve("gradle-user-home");
        Path invocationLog = temporaryDirectory.resolve("invocations.log");
        byte[] archive = fixtureArchive(platform, invocationLog);
        try (FixtureServer server = FixtureServer.start(VERSION, platform.assetName(), archive, sha256(archive))) {
            writeProject(projectDirectory, gradleUserHome, platform, invocationLog);

            BuildResult first = runner(projectDirectory, gradleUserHome)
                    .withArguments("--gradle-user-home", gradleUserHome.toString(),
                            "--configuration-cache",
                            "verifyManagedRuntime", releaseBaseUriArgument(server))
                    .build();

            Path runtime = runtimeDirectory(gradleUserHome, platform);
            Path executable = runtime.resolve("bin").resolve(platform.executableName());
            assertTrue(first.getOutput().contains("BUILD SUCCESSFUL"));
            assertTrue(first.getOutput().contains("Configuration cache entry stored"));
            assertTrue(Files.isRegularFile(executable));
            assertEquals(List.of(
                    "/releases/" + VERSION + "/" + platform.assetName() + ".sha256",
                    "/releases/" + VERSION + "/" + platform.assetName()),
                    server.requests());
            assertEquals(sha256(archive), Files.readString(runtime.resolve(".complete")).trim());

            BuildResult second = runner(projectDirectory, gradleUserHome)
                    .withArguments("--gradle-user-home", gradleUserHome.toString(),
                            "--configuration-cache",
                            "verifyManagedRuntime", releaseBaseUriArgument(server))
                    .build();

            assertTrue(second.getOutput().contains("BUILD SUCCESSFUL"));
            assertTrue(second.getOutput().contains("Configuration cache entry reused"));
            assertEquals(2, server.requests().size());

            if (!platform.os().equals("windows")) {
                runner(projectDirectory, gradleUserHome)
                        .withArguments("--gradle-user-home", gradleUserHome.toString(),
                                "invokeFixtureElide", releaseBaseUriArgument(server))
                        .build();
                assertEquals("called\n", Files.readString(invocationLog));
                assertEquals(2, server.requests().size());
            }
        }
    }

    @Test
    void rejectsAnArchiveWithABadChecksumWithoutCompletingTheCacheEntry() throws Exception {
        FixturePlatform platform = currentPlatform();
        Path projectDirectory = temporaryDirectory.resolve("bad-checksum-project");
        Path gradleUserHome = temporaryDirectory.resolve("bad-checksum-gradle-user-home");
        byte[] archive = fixtureArchive(platform, temporaryDirectory.resolve("unused.log"));
        try (FixtureServer server = FixtureServer.start(VERSION, platform.assetName(), archive,
                "0000000000000000000000000000000000000000000000000000000000000000")) {
            writeProject(projectDirectory, gradleUserHome, platform, temporaryDirectory.resolve("unused.log"));

            BuildResult result = runner(projectDirectory, gradleUserHome)
                    .withArguments("--gradle-user-home", gradleUserHome.toString(),
                            "verifyManagedRuntime", releaseBaseUriArgument(server))
                    .buildAndFail();

            assertTrue(result.getOutput().contains("SHA-256 mismatch"));
            assertFalse(Files.exists(runtimeDirectory(gradleUserHome, platform).resolve(".complete")));
            assertEquals(2, server.requests().size());
        }
    }

    @Test
    void offlineModeUsesACompletedCacheEntryWithoutSendingARequest() throws Exception {
        FixturePlatform platform = currentPlatform();
        Path projectDirectory = temporaryDirectory.resolve("offline-hit-project");
        Path gradleUserHome = temporaryDirectory.resolve("offline-hit-gradle-user-home");
        byte[] archive = fixtureArchive(platform, temporaryDirectory.resolve("offline-hit.log"));
        try (FixtureServer server = FixtureServer.start(VERSION, platform.assetName(), archive, sha256(archive))) {
            writeProject(projectDirectory, gradleUserHome, platform, temporaryDirectory.resolve("offline-hit.log"));
            runner(projectDirectory, gradleUserHome)
                    .withArguments("--gradle-user-home", gradleUserHome.toString(),
                            "verifyManagedRuntime", releaseBaseUriArgument(server))
                    .build();

            BuildResult offline = runner(projectDirectory, gradleUserHome)
                    .withArguments("--gradle-user-home", gradleUserHome.toString(), "--offline",
                            "verifyManagedRuntime", releaseBaseUriArgument(server))
                    .build();

            assertTrue(offline.getOutput().contains("BUILD SUCCESSFUL"));
            assertEquals(2, server.requests().size());
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void repairsLostUnixExecutablePermissionOnACompletedCacheHit() throws Exception {
        FixturePlatform platform = currentPlatform();
        Path projectDirectory = temporaryDirectory.resolve("permission-repair-project");
        Path gradleUserHome = temporaryDirectory.resolve("permission-repair-gradle-user-home");
        byte[] archive = fixtureArchive(platform, temporaryDirectory.resolve("permission-repair.log"));
        try (FixtureServer server = FixtureServer.start(VERSION, platform.assetName(), archive, sha256(archive))) {
            writeProject(projectDirectory, gradleUserHome, platform, temporaryDirectory.resolve("permission-repair.log"));
            runner(projectDirectory, gradleUserHome)
                    .withArguments("--gradle-user-home", gradleUserHome.toString(),
                            "verifyManagedRuntime", releaseBaseUriArgument(server))
                    .build();

            Path executable = runtimeDirectory(gradleUserHome, platform)
                    .resolve("bin")
                    .resolve(platform.executableName());
            Files.setPosixFilePermissions(executable, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_READ));
            assertFalse(Files.isExecutable(executable));

            BuildResult repaired = runner(projectDirectory, gradleUserHome)
                    .withArguments("--gradle-user-home", gradleUserHome.toString(),
                            "verifyManagedRuntime", releaseBaseUriArgument(server))
                    .build();

            assertTrue(repaired.getOutput().contains("BUILD SUCCESSFUL"), repaired.getOutput());
            assertTrue(Files.isExecutable(executable));
            assertEquals(2, server.requests().size());
        }
    }

    @Test
    void offlineModeNamesTheExactVersionAndCachePathWhenTheRuntimeIsMissing() throws IOException {
        FixturePlatform platform = currentPlatform();
        Path projectDirectory = temporaryDirectory.resolve("offline-miss-project");
        Path gradleUserHome = temporaryDirectory.resolve("offline-miss-gradle-user-home");
        writeProject(projectDirectory, gradleUserHome, platform, temporaryDirectory.resolve("offline-miss.log"));

        BuildResult result = runner(projectDirectory, gradleUserHome)
                .withArguments("--gradle-user-home", gradleUserHome.toString(), "--offline",
                        "verifyManagedRuntime", "-Ddev.elide.gradle.test.releaseBaseUri=http://127.0.0.1:1/releases")
                .buildAndFail();

        Path expectedCachePath = runtimeDirectory(temporaryDirectory.toRealPath()
                .resolve("offline-miss-gradle-user-home"), platform);
        assertTrue(result.getOutput().contains("Elide runtime version " + VERSION
                        + " is not cached at " + expectedCachePath),
                result.getOutput());
    }

    @Test
    void parallelProjectsShareOneManagedRuntimePreparation() throws Exception {
        FixturePlatform platform = currentPlatform();
        Path projectDirectory = temporaryDirectory.resolve("parallel-projects");
        Path gradleUserHome = temporaryDirectory.resolve("parallel-gradle-user-home");
        byte[] archive = fixtureArchive(platform, temporaryDirectory.resolve("parallel.log"));
        try (FixtureServer server = FixtureServer.start(VERSION, platform.assetName(), archive, sha256(archive))) {
            Files.createDirectories(projectDirectory.resolve("one"));
            Files.createDirectories(projectDirectory.resolve("two"));
            Files.writeString(projectDirectory.resolve("settings.gradle.kts"), """
                    import dev.elide.gradle.ElideRuntimeMode

                    plugins { id("dev.elide.settings") }
                    elide {
                        runtime {
                            mode = ElideRuntimeMode.MANAGED
                            version = "%s"
                        }
                    }
                    include("one", "two")
                    """.formatted(VERSION));
            String projectBuild = """
                    plugins { id("dev.elide") }
                    elide { compiler = false }
                    """;
            Files.writeString(projectDirectory.resolve("one/build.gradle.kts"), projectBuild);
            Files.writeString(projectDirectory.resolve("two/build.gradle.kts"), projectBuild);

            BuildResult result = runner(projectDirectory, gradleUserHome)
                    .withArguments("--gradle-user-home", gradleUserHome.toString(), "--parallel",
                            ":one:prepareElideRuntime", ":two:prepareElideRuntime", releaseBaseUriArgument(server))
                    .build();

            assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());
            assertEquals(2, server.requests().size());
            assertTrue(Files.isRegularFile(runtimeDirectory(gradleUserHome, platform).resolve(".complete")));
        }
    }

    private static void writeProject(Path projectDirectory, Path gradleUserHome, FixturePlatform platform,
                                     Path invocationLog) throws IOException {
        Path executable = runtimeDirectory(gradleUserHome, platform).resolve("bin").resolve(platform.executableName());
        Files.createDirectories(projectDirectory);
        Files.writeString(projectDirectory.resolve("settings.gradle"), "");
        Files.writeString(projectDirectory.resolve("build.gradle"), """
                plugins {
                    id 'dev.elide'
                }

                elide {
                    runtimeMode = dev.elide.gradle.ElideRuntimeMode.MANAGED
                    runtimeVersion = '%s'
                    enableJavaCompiler = false
                }

                tasks.register('verifyManagedRuntime') {
                    dependsOn 'prepareElideRuntime'
                }
                %s
                """.formatted(
                VERSION,
                platform.os().equals("windows") ? "" : """
                        tasks.register('invokeFixtureElide', Exec) {
                            dependsOn 'prepareElideRuntime'
                            executable file('%s')
                        }
                        """.formatted(groovyQuote(executable))));
    }

    private static GradleRunner runner(Path projectDirectory, Path gradleUserHome) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("GRADLE_USER_HOME", gradleUserHome.toString());
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(projectDirectory.toFile())
                .withTestKitDir(projectDirectory.resolve("test-kit").toFile())
                .withEnvironment(environment);
    }

    private static String releaseBaseUriArgument(FixtureServer server) {
        return "-Ddev.elide.gradle.test.releaseBaseUri=" + server.baseUri();
    }

    private static Path runtimeDirectory(Path gradleUserHome, FixturePlatform platform) {
        return gradleUserHome.resolve("caches/dev.elide/runtimes").resolve(VERSION).resolve(platform.key());
    }

    private static FixturePlatform currentPlatform() {
        String osName = System.getProperty("os.name").toLowerCase();
        String architecture = System.getProperty("os.arch").toLowerCase();
        String arch = switch (architecture) {
            case "x86_64", "amd64" -> "amd64";
            case "arm64", "aarch64" -> "arm64";
            default -> throw new IllegalStateException("Unsupported test architecture: " + architecture);
        };
        if (osName.equals("linux")) {
            return new FixturePlatform("linux", arch, "tgz", "elide");
        }
        if (osName.equals("mac os x")) {
            return new FixturePlatform("macos", arch, "tgz", "elide");
        }
        if (osName.startsWith("windows") && arch.equals("amd64")) {
            return new FixturePlatform("windows", arch, "zip", "elide.exe");
        }
        throw new IllegalStateException("Unsupported test platform: " + osName + "/" + architecture);
    }

    private static String groovyQuote(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "\\\\").replace("'", "\\'");
    }

    private static byte[] fixtureArchive(FixturePlatform platform, Path invocationLog) throws IOException {
        String executable = platform.os().equals("windows")
                ? "fixture executable"
                : "#!/bin/sh\nprintf 'called\\n' >> '" + shellQuote(invocationLog) + "'\n";
        return platform.archiveExtension().equals("zip")
                ? zipArchive("bin/" + platform.executableName(), executable)
                : tarGzipArchive("bin/" + platform.executableName(), executable);
    }

    private static byte[] zipArchive(String path, String content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(path));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] tarGzipArchive(String path, String content) throws IOException {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        byte[] header = new byte[512];
        writeTarString(header, 0, 100, path);
        writeTarOctal(header, 100, 8, 0755);
        writeTarOctal(header, 108, 8, 0);
        writeTarOctal(header, 116, 8, 0);
        writeTarOctal(header, 124, 12, data.length);
        writeTarOctal(header, 136, 12, 0);
        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = '0';
        writeTarString(header, 257, 6, "ustar");
        writeTarString(header, 263, 2, "00");
        long checksum = 0;
        for (byte value : header) {
            checksum += Byte.toUnsignedInt(value);
        }
        writeTarOctal(header, 148, 8, checksum);
        tar.write(header);
        tar.write(data);
        tar.write(new byte[(512 - data.length % 512) % 512]);
        tar.write(new byte[1024]);

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(tar.toByteArray());
        }
        return compressed.toByteArray();
    }

    private static void writeTarString(byte[] header, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length));
    }

    private static void writeTarOctal(byte[] header, int offset, int length, long value) {
        String octal = String.format("%0" + (length - 1) + "o", value);
        writeTarString(header, offset, length - 1, octal);
        header[offset + length - 1] = 0;
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private static String shellQuote(Path path) {
        return path.toAbsolutePath().toString().replace("'", "'\"'\"'");
    }

    private static final class FixtureServer implements AutoCloseable {
        private final HttpServer server;
        private final List<String> requests;

        private FixtureServer(HttpServer server, List<String> requests) {
            this.server = server;
            this.requests = requests;
        }

        static FixtureServer start(String version, String assetName, byte[] archive, String checksum) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());
            server.createContext("/releases/" + version + "/" + assetName, exchange -> {
                requests.add(exchange.getRequestURI().getPath());
                exchange.sendResponseHeaders(200, archive.length);
                exchange.getResponseBody().write(archive);
                exchange.close();
            });
            byte[] checksumBytes = (checksum + "  " + assetName + "\n").getBytes(StandardCharsets.UTF_8);
            server.createContext("/releases/" + version + "/" + assetName + ".sha256", exchange -> {
                requests.add(exchange.getRequestURI().getPath());
                exchange.sendResponseHeaders(200, checksumBytes.length);
                exchange.getResponseBody().write(checksumBytes);
                exchange.close();
            });
            server.start();
            return new FixtureServer(server, requests);
        }

        String baseUri() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/releases";
        }

        List<String> requests() {
            synchronized (requests) {
                return List.copyOf(requests);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private record FixturePlatform(String os, String arch, String archiveExtension, String executableName) {
        String key() {
            return os + "-" + arch;
        }

        String assetName() {
            return "elide." + key() + "." + archiveExtension;
        }
    }
}
