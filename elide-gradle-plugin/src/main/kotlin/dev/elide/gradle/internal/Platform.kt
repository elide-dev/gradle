package dev.elide.gradle.internal

internal object Platform {
    val platformClassifier: String get() {
        val elideArch = when (System.getProperty("os.arch").lowercase()) {
            "x86_64", "amd64" -> "amd64"
            "arm64", "aarch64" -> "aarch64"
            else -> error("Unsupported architecture: ${System.getProperty("os.arch")}")
        }

        return when (System.getProperty("os.name").lowercase()) {
            "linux" -> "linux-$elideArch"
            "mac os x" -> "darwin-$elideArch"
            "windows" -> "windows-$elideArch"
            else -> error("Unsupported OS: ${System.getProperty("os.name")}")
        }
    }
}