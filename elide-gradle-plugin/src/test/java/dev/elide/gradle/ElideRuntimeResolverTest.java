package dev.elide.gradle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElideRuntimeResolverTest {
    @Test
    void rejectsGradleDynamicVersionsWithPlusSuffix() {
        assertTrue(ElideRuntimeResolver.isDynamicVersion("1.5.+"));
        assertTrue(ElideRuntimeResolver.isDynamicVersion("1.+"));
        assertTrue(ElideRuntimeResolver.isDynamicVersion("+"));
    }

    @Test
    void rejectsGradleRangeVersions() {
        assertTrue(ElideRuntimeResolver.isDynamicVersion("[1.5,2.0)"));
        assertTrue(ElideRuntimeResolver.isDynamicVersion("(1.5,2.0]"));
        assertTrue(ElideRuntimeResolver.isDynamicVersion("[1.5,)"));
        assertTrue(ElideRuntimeResolver.isDynamicVersion("(,2.0]"));
        assertTrue(ElideRuntimeResolver.isDynamicVersion("[1.5,2.0]"));
    }

    @Test
    void rejectsLatestSelectors() {
        assertTrue(ElideRuntimeResolver.isDynamicVersion("latest.release"));
        assertTrue(ElideRuntimeResolver.isDynamicVersion("latest.integration"));
        assertTrue(ElideRuntimeResolver.isDynamicVersion("LATEST.RELEASE"));
        assertTrue(ElideRuntimeResolver.isDynamicVersion("Latest.Integration"));
    }

    @Test
    void acceptsConcreteSemanticVersions() {
        assertFalse(ElideRuntimeResolver.isDynamicVersion("1.5.1"));
        assertFalse(ElideRuntimeResolver.isDynamicVersion("1.5"));
        assertFalse(ElideRuntimeResolver.isDynamicVersion("1.6.0"));
    }

    @Test
    void acceptsConcreteVersionsWithBuildMetadata() {
        assertFalse(ElideRuntimeResolver.isDynamicVersion("1.5.1+20260903"));
        assertFalse(ElideRuntimeResolver.isDynamicVersion("1.5.1+20260903.4c6cdc7"));
    }

    @Test
    void acceptsPrereleaseAndNonSemanticConcreteTags() {
        assertFalse(ElideRuntimeResolver.isDynamicVersion("1.6.0-rc.2"));
        assertFalse(ElideRuntimeResolver.isDynamicVersion("1.5.1-alpha"));
        assertFalse(ElideRuntimeResolver.isDynamicVersion("fixture-1.0"));
        assertFalse(ElideRuntimeResolver.isDynamicVersion("v1.6.0"));
    }

    @Test
    void stripsSurroundingWhitespaceBeforeChecking() {
        assertTrue(ElideRuntimeResolver.isDynamicVersion(" 1.5.+"));
        assertTrue(ElideRuntimeResolver.isDynamicVersion("1.5.+ "));
        assertTrue(ElideRuntimeResolver.isDynamicVersion(" [1.5,2.0)"));
        assertTrue(ElideRuntimeResolver.isDynamicVersion(" latest.release "));
    }
}
