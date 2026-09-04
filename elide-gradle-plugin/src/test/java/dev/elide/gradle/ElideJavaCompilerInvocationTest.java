package dev.elide.gradle;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ElideJavaCompilerInvocationTest {
    @Test
    void wrapsOnlyTheTestWindowsCmdFixtureInCmdExe() {
        var invocation = ElideJavaCompilerInvocation.create(
                "Windows 11",
                true,
                Path.of("C:\\fixture\\bin\\elide.cmd"),
                List.of("-Dfixture.compiler.argument=has a space"));

        assertEquals("cmd.exe", invocation.executable());
        assertEquals(List.of(
                "/d",
                "/s",
                "/c",
                "call \"C:\\fixture\\bin\\elide.cmd\"",
                "javac",
                "--",
                "-Dfixture.compiler.argument=has a space"), invocation.arguments());
    }

    @Test
    void keepsNativeWindowsRuntimeDirect() {
        var invocation = ElideJavaCompilerInvocation.create(
                "Windows 11",
                true,
                Path.of("C:\\runtime\\bin\\elide.exe"),
                List.of());

        assertEquals("C:\\runtime\\bin\\elide.exe", invocation.executable());
        assertEquals(List.of("javac", "--"), invocation.arguments());
    }
}
