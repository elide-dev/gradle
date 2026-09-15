package dev.elide.gradle;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElideVersionTest {
    @Test
    void ignoresBuildMetadataAfterThePlusSign() {
        ElideVersion version = ElideVersion.parse("1.5.1+20260903.4c6cdc7").orElseThrow();

        assertEquals(new ElideVersion(1, 5, 1), version);
    }

    @Test
    void comparesComponentsNumericallyRatherThanLexically() {
        ElideVersion newer = ElideVersion.parse("1.10.0").orElseThrow();
        ElideVersion older = ElideVersion.parse("1.9.9").orElseThrow();

        assertTrue(newer.compareTo(older) > 0, "1.10.0 must sort above 1.9.9");
    }

    @Test
    void treatsAnEqualVersionAsAcceptable() {
        ElideVersion required = ElideVersion.parse("1.5.1+20260903").orElseThrow();
        ElideVersion reported = ElideVersion.parse("1.5.1+20260908.ead8d1c").orElseThrow();

        assertEquals(0, reported.compareTo(required));
    }

    @Test
    void ordersAcrossMajorVersions() {
        assertTrue(ElideVersion.parse("2.0.0").orElseThrow()
                .compareTo(ElideVersion.parse("1.99.99").orElseThrow()) > 0);
    }

    @Test
    void readsAVersionFollowedByTrailingDetail() {
        assertEquals(new ElideVersion(1, 5, 2), ElideVersion.parse("1.5.2+20260908 (release)").orElseThrow());
    }

    @Test
    void toleratesSurroundingWhitespace() {
        assertEquals(new ElideVersion(1, 5, 2), ElideVersion.parse("  1.5.2+20260908\n").orElseThrow());
    }

    @Test
    void rejectsTextThatDoesNotBeginWithAVersion() {
        // Elide's --version always starts with the semantic version. Anything else is a foreign
        // binary, and a version-shaped number inside its output must not be taken as a version.
        assertEquals(Optional.empty(), ElideVersion.parse("elide 1.5.2+20260908"));
        assertEquals(Optional.empty(), ElideVersion.parse("usage: frobnicate 2.1 [options]"));
        assertEquals(Optional.empty(), ElideVersion.parse("error: unknown flag --version (try -v 9.9.9)"));
    }

    @Test
    void defaultsAMissingPatchComponentToZero() {
        assertEquals(new ElideVersion(1, 5, 0), ElideVersion.parse("1.5").orElseThrow());
    }

    @Test
    void readsAConfiguredVersionCarryingATagStylePrefix() {
        // A configured version is written by a person, where v1.6.0 is an ordinary tag convention.
        assertEquals(new ElideVersion(1, 6, 0), ElideVersion.parseConfigured("v1.6.0").orElseThrow());
        assertEquals(new ElideVersion(1, 5, 1), ElideVersion.parseConfigured("1.5.1+20260903").orElseThrow());
    }

    @Test
    void rejectsAConfiguredVersionThatIsNotSemantic() {
        // A rich version has no single floor, and substituting the pinned default would silently
        // disagree with the version that managed provisioning actually downloads.
        assertEquals(Optional.empty(), ElideVersion.parseConfigured("[1.5,2.0)"));
        assertEquals(Optional.empty(), ElideVersion.parseConfigured("latest.release"));
        assertEquals(Optional.empty(), ElideVersion.parseConfigured(null));
        // Gradle dynamic versions: 1.5.+ must not quietly become a floor of 1.5.0.
        assertEquals(Optional.empty(), ElideVersion.parseConfigured("1.5.+"));
        assertEquals(Optional.empty(), ElideVersion.parseConfigured("1.+"));
    }

    @Test
    void probeParsingStaysStrictWhereConfiguredParsingIsLenient() {
        // The v-prefix leniency must not leak into probe output, which stays anchored.
        assertEquals(Optional.empty(), ElideVersion.parse("v1.6.0"));
    }

    @Test
    void returnsEmptyForUnparseableOutput() {
        assertEquals(Optional.empty(), ElideVersion.parse("not a version"));
        assertEquals(Optional.empty(), ElideVersion.parse(""));
        assertEquals(Optional.empty(), ElideVersion.parse(null));
    }
}
