package dev.elide.gradle;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds the direct compiler invocation used by {@link ElideGradlePlugin}. */
final class ElideJavaCompilerInvocation {
    static final String TEST_WINDOWS_CMD_FIXTURE_PROPERTY = "dev.elide.gradle.test.windowsCmdFixture";

    private ElideJavaCompilerInvocation() {
    }

    static Invocation create(String osName, boolean enableWindowsCmdFixture, Path executable,
                             List<String> existingArguments) {
        List<String> arguments = new ArrayList<>(existingArguments.size() + 6);
        String command = executable.toString();
        if (isWindowsCmdFixture(osName, enableWindowsCmdFixture, executable)) {
            command = "cmd.exe";
            arguments.add("/d");
            arguments.add("/s");
            arguments.add("/c");
            arguments.add("call \"" + executable + "\"");
        }
        arguments.add("javac");
        arguments.add("--");
        arguments.addAll(existingArguments);
        return new Invocation(command, List.copyOf(arguments));
    }

    private static boolean isWindowsCmdFixture(String osName, boolean enabled, Path executable) {
        return enabled
                && osName.toLowerCase(Locale.ROOT).startsWith("windows")
                && executable.toString().replace('\\', '/').endsWith("/elide.cmd");
    }

    record Invocation(String executable, List<String> arguments) {
    }
}
