package dev.elide.gradle.configuration.features

public enum class ElideIntegrationStrategy {
    /**
     * Use Gradle's default.
     */
    NO_ELIDE,

    /**
     * Use Elide's Java compiler/Javadoc. In case of elide unavailability or an error,
     * we fall back to Gradle's default if possible.
     */
    PREFER_ELIDE,

    /**
     * Use only Elide’s Java compiler/Javadoc and fail if it is not available.
     */
    ELIDE_STRICT
}
