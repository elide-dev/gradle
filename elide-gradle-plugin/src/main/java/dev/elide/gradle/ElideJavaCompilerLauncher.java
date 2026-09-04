package dev.elide.gradle;

import java.io.IOException;
import java.util.Arrays;

/** Launches a prepared managed Elide executable as Gradle's Java compiler process. */
public final class ElideJavaCompilerLauncher {
    private ElideJavaCompilerLauncher() {
    }

    public static void main(String[] arguments) throws IOException, InterruptedException {
        if (arguments.length < 2) {
            throw new IllegalArgumentException("Expected an Elide executable and compiler arguments");
        }
        Process process = new ProcessBuilder(Arrays.asList(arguments))
                .inheritIO()
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
