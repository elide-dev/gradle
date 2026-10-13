package dev.elide.gradle;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

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
     * ({@code v1.6.0-rc.2}), which a configured version may include or omit. Any other value is
     * used as written.
     */
    public String tag() {
        String value = version.strip();
        return RELEASE_VERSION.matcher(value).matches() ? "v" + value : value;
    }

    /** A release or release-candidate version without a tag prefix or build metadata. */
    private static final Pattern RELEASE_VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?");

    public URI checksumUri() {
        return URI.create(archiveUri() + ".sha256");
    }

    private static String ensureTrailingSlash(URI uri) {
        String value = uri.toString();
        return value.endsWith("/") ? value : value + "/";
    }
}
