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
    void readsAVersionCarryingSurroundingText() {
        assertEquals(new ElideVersion(1, 5, 2), ElideVersion.parse("elide 1.5.2+20260908").orElseThrow());
    }

    @Test
    void defaultsAMissingPatchComponentToZero() {
        assertEquals(new ElideVersion(1, 5, 0), ElideVersion.parse("1.5").orElseThrow());
    }

    @Test
    void returnsEmptyForUnparseableOutput() {
        assertEquals(Optional.empty(), ElideVersion.parse("not a version"));
        assertEquals(Optional.empty(), ElideVersion.parse(""));
        assertEquals(Optional.empty(), ElideVersion.parse(null));
    }
}
