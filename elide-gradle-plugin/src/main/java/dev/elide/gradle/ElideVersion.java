package dev.elide.gradle;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A comparable {@code major.minor.patch} version.
 *
 * <p>Elide reports versions as {@code 1.5.1+20260903.4c6cdc7}. Only the semantic version is
 * significant for compatibility, so build metadata after {@code +} is discarded. Components are
 * compared numerically, not lexically, so {@code 1.10.0} is newer than {@code 1.9.9}.
 */
record ElideVersion(int major, int minor, int patch) implements Comparable<ElideVersion> {
    private static final Pattern SEMANTIC_VERSION = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    /**
     * Extracts the first semantic version in reported text, which may carry a prefix, build
     * metadata, or trailing detail.
     *
     * @return empty when no version can be read, in which case the runtime is not usable
     */
    static Optional<ElideVersion> parse(String reported) {
        if (reported == null || reported.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = SEMANTIC_VERSION.matcher(reported);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ElideVersion(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3))));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    @Override
    public int compareTo(ElideVersion other) {
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) {
            return byMajor;
        }
        int byMinor = Integer.compare(minor, other.minor);
        return byMinor != 0 ? byMinor : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
