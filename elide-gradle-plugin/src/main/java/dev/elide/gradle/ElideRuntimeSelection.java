package dev.elide.gradle;

import java.nio.file.Path;

/** The executable selected for a build and the source from which it was selected. */
public record ElideRuntimeSelection(ElideRuntimeSource source, Path executable) {
}
