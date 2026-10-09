package dev.elide.gradle;

import org.gradle.api.GradleException;

/**
 * Signals that the configured Elide runtime version could not be resolved at all, as opposed to
 * resolving to something unusable.
 *
 * <p>It exists so callers can recognise exactly this failure. {@code PATH} selection tolerates it,
 * because it never provisions the version it could not resolve, and matching on {@link
 * GradleException} would have swallowed unrelated failures such as {@code InvalidUserDataException}
 * along with it.
 */
final class ElideVersionResolutionException extends GradleException {
    ElideVersionResolutionException(String message) {
        super(message);
    }
}
