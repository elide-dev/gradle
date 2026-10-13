package dev.elide.gradle;

import java.net.URI;
import java.util.Objects;

/** Locations of the release assets for one Elide version and platform. */
public record ElideRelease(URI baseUri, String version, ElidePlatform platform) {
    public ElideRelease {
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(platform, "platform");
        if (version.isBlank()) {
            throw new IllegalArgumentException("Elide runtime version must not be blank");
        }
    }

    public URI archiveUri() {
        return baseUri.resolve(ensureTrailingSlash(baseUri) + tag() + "/" + platform.assetName());
    }

    /**
     * The release tag that publishes {@link #version}. Nightlies are tagged with the version itself
     * ({@code 1.5.1+20260903}); releases and release candidates carry a leading {@code v}
     * ({@code v1.6.0-rc.2}), which a configured version may include or omit.
     */
    public String tag() {
        String value = version.strip();
        if (value.startsWith("v") || value.startsWith("V") || value.contains("+")) {
            return value;
        }
        return "v" + value;
    }

    public URI checksumUri() {
        return URI.create(archiveUri() + ".sha256");
    }

    private static String ensureTrailingSlash(URI uri) {
        String value = uri.toString();
        return value.endsWith("/") ? value : value + "/";
    }
}
