package dev.elide.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Platform-native fake executables used by functional tests. */
final class PlatformFixture {
    private PlatformFixture() {
    }

    static boolean isWindows() {
        return isWindows(System.getProperty("os.name"));
    }

    static String executableName(String basename) {
        return isWindows() ? basename + ".cmd" : basename;
    }

    static String executableNameFor(String basename, String osName) {
        return isWindows(osName) ? basename + ".cmd" : basename;
    }

    static String recordingScriptFor(String osName, Path invocationDirectory) {
        return isWindows(osName)
                ? windowsRecordingScript(invocationDirectory)
                : unixRecordingScript(invocationDirectory);
    }

    static Path writeRecordingExecutable(Path directory, String basename, Path invocationDirectory)
            throws IOException {
        Files.createDirectories(directory);
        Files.createDirectories(invocationDirectory);
        Path executable = directory.resolve(executableName(basename));
        Files.writeString(executable, isWindows()
                ? windowsRecordingScript(invocationDirectory)
                : unixRecordingScript(invocationDirectory));
        executable.toFile().setExecutable(true);
        return executable;
    }

    /** Links the test JVM's real tools beside the fixture; no JAVA_HOME is set or created. */
    static void linkActualJavaTools(Path runtimeBin) throws IOException {
        Path javaBin = Path.of(System.getProperty("java.home")).resolve("bin");
        Files.createSymbolicLink(runtimeBin.resolve("java"), javaBin.resolve("java"));
        Files.createSymbolicLink(runtimeBin.resolve("javac"), javaBin.resolve("javac"));
    }

    static List<List<String>> readInvocations(Path invocationDirectory) throws IOException {
        try (Stream<Path> entries = Files.list(invocationDirectory)) {
            return entries
                    .filter(path -> path.getFileName().toString().endsWith(".args"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(PlatformFixture::readLines)
                    .toList();
        }
    }

    static String windowsRecordingScript(Path invocationDirectory) {
        String logDirectory = batchQuote(invocationDirectory);
        String counter = batchQuote(invocationDirectory.resolve("counter.txt"));
        return """
                @echo off
                setlocal DisableDelayedExpansion
                set "first=%%~1"
                set "counterFile=%s"
                set "logDirectory=%s"
                set "count=0"
                if exist "%%counterFile%%" set /p count=<"%%counterFile%%"
                set /a count+=1
                > "%%counterFile%%" echo %%count%%
                set "argsFile=%%logDirectory%%\\%%count%%.args"
                :record
                if "%%1"=="" goto recorded
                >> "%%argsFile%%" echo(%%~1
                shift
                goto record
                :recorded
                if /I "%%first%%"=="install" (
                  mkdir ".dev\\dependencies\\m2" 2>nul
                  > ".dev\\dependencies\\m2\\fixture-install.marker" echo installed
                )
                exit /b 0
                """.formatted(counter, logDirectory);
    }

    static String unixRecordingScript(Path invocationDirectory) {
        String logDirectory = shellQuote(invocationDirectory);
        String counter = shellQuote(invocationDirectory.resolve("counter"));
        return """
                #!/bin/sh
                counter_file=%s
                count=0
                if [ -f "$counter_file" ]; then
                  IFS= read -r count < "$counter_file"
                fi
                count=$((count + 1))
                printf '%%s' "$count" > "$counter_file"
                args_file=%s/"$count".args
                : > "$args_file"
                for argument in "$@"; do
                  printf '%%s\\n' "$argument" >> "$args_file"
                done
                if [ "${1-}" = 'install' ]; then
                  mkdir -p .dev/dependencies/m2
                  printf 'installed\\n' > .dev/dependencies/m2/fixture-install.marker
                fi
                """.formatted(counter, logDirectory);
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String batchQuote(Path path) {
        return path.toAbsolutePath().toString().replace("%", "%%");
    }

    private static String shellQuote(Path path) {
        return "'" + path.toAbsolutePath().toString().replace("'", "'\\\"'\\\"'") + "'";
    }

    private static boolean isWindows(String osName) {
        return osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
