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
    /**
     * Anchored at the start of the input: Elide's {@code --version} output always begins with the
     * semantic version. Matching anywhere in the text would let any version-shaped number in an
     * unrelated binary's usage banner be accepted as an Elide version.
     */
    private static final Pattern SEMANTIC_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    /**
     * Reads the semantic version at the start of reported text, ignoring any build metadata or
     * trailing detail that follows it.
     *
     * @return empty when the text does not begin with a version, in which case the runtime is not
     *         usable
     */
    static Optional<ElideVersion> parse(String reported) {
        if (reported == null || reported.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = SEMANTIC_VERSION.matcher(reported.strip());
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

    /**
     * Reads a version the build configured, as opposed to one a binary reported.
     *
     * <p>Kept separate from {@link #parse(String)} on purpose. Probe output must stay strictly
     * anchored so a number in a foreign binary's banner cannot be mistaken for a version, but a
     * configured version is written by a person and a leading {@code v}, as in {@code v1.6.0}, is a
     * common tag convention rather than an error.
     *
     * @return empty when the value is not a semantic version at all, such as a rich version
     */
    static Optional<ElideVersion> parseConfigured(String configured) {
        if (configured == null) {
            return Optional.empty();
        }
        String normalized = configured.strip();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        Matcher matcher = SEMANTIC_VERSION.matcher(normalized);
        if (!matcher.find()) {
            return Optional.empty();
        }
        // A dynamic version such as 1.5.+ matches its leading 1.5 and would otherwise yield a floor
        // of 1.5.0, well below whatever it actually resolves to. A complete version never continues
        // with another dot: what follows can only be prerelease or +build metadata.
        if (normalized.startsWith(".", matcher.end())) {
            return Optional.empty();
        }
        return parse(normalized);
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
