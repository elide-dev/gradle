package dev.elide.gradle;

/** Selects whether Gradle exclusively owns JVM dependency resolution. */
public enum ElideDependencyMode {
    /** Preserve the opt-in legacy Elide installer and generated Maven repository. */
    LEGACY,
    /** Gradle resolves the classpath, including locking, verification and offline policy. */
    GRADLE
}
