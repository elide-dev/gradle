package dev.elide.gradle;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ElideReleaseTest {
    @Test
    void buildsPlatformSpecificArchiveAndChecksumUris() {
        ElideRelease release = new ElideRelease(
                URI.create("https://github.com/elide-dev/elide/releases/download"),
                "1.5.1+20260903",
                ElidePlatform.detect("Mac OS X", "arm64"));

        assertEquals(
                "https://github.com/elide-dev/elide/releases/download/1.5.1+20260903/elide.macos-arm64.tgz",
                release.archiveUri().toString());
        assertEquals(
                "https://github.com/elide-dev/elide/releases/download/1.5.1+20260903/elide.macos-arm64.tgz.sha256",
                release.checksumUri().toString());
    }
}
