package dev.elide.gradle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ElidePlatformTest {
    @Test
    void detectsLinuxAmd64Asset() {
        assertEquals("elide.linux-amd64.tgz", ElidePlatform.detect("Linux", "x86_64").assetName());
    }

    @Test
    void detectsLinuxArm64Asset() {
        assertEquals("elide.linux-arm64.tgz", ElidePlatform.detect("Linux", "aarch64").assetName());
    }

    @Test
    void detectsMacosAmd64Asset() {
        assertEquals("elide.macos-amd64.tgz", ElidePlatform.detect("Mac OS X", "amd64").assetName());
    }

    @Test
    void detectsMacosArm64Asset() {
        assertEquals("elide.macos-arm64.tgz", ElidePlatform.detect("Mac OS X", "arm64").assetName());
    }

    @Test
    void detectsWindowsAmd64AssetAndExecutable() {
        var platform = ElidePlatform.detect("Windows 11", "x86_64");
        assertEquals("elide.windows-amd64.zip", platform.assetName());
        assertEquals("elide.exe", ElidePlatform.detect("Windows 11", "amd64").executableName());
    }

    @Test
    void rejectsWindowsArm64() {
        assertThrows(IllegalArgumentException.class, () -> ElidePlatform.detect("Windows 11", "arm64"));
    }

    @Test
    void rejectsUnknownOperatingSystem() {
        assertThrows(IllegalArgumentException.class, () -> ElidePlatform.detect("Plan 9", "amd64"));
    }
}
