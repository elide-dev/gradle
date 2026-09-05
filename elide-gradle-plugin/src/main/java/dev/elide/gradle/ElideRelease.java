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
        return baseUri.resolve(ensureTrailingSlash(baseUri) + version + "/" + platform.assetName());
    }

    public URI checksumUri() {
        return URI.create(archiveUri() + ".sha256");
    }

    private static String ensureTrailingSlash(URI uri) {
        String value = uri.toString();
        return value.endsWith("/") ? value : value + "/";
    }
}
