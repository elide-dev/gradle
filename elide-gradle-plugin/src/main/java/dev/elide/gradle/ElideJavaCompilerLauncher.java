package dev.elide.gradle;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

/** Launches the selected Elide executable as Gradle's Java compiler process. */
public final class ElideJavaCompilerLauncher {
    private ElideJavaCompilerLauncher() {
    }

    public static void main(String[] arguments) throws IOException, InterruptedException {
        if (arguments.length < 2) {
            throw new IllegalArgumentException("Expected an Elide executable and compiler arguments");
        }
        Process process = start(new ProcessBuilder(Arrays.asList(arguments)).inheritIO());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            System.exit(exitCode);
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
