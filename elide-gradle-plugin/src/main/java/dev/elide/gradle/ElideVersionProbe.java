package dev.elide.gradle;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Reports the version of a candidate Elide executable.
 *
 * <p>Injected so runtime selection stays pure and unit-testable: the production implementation
 * starts a process, which must not happen merely because the plugin was applied.
 */
@FunctionalInterface
public interface ElideVersionProbe {
    /**
     * @return the executable's reported version text, or empty when it cannot be determined
     */
    Optional<String> version(Path executable);
}
