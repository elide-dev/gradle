//
// Elide Gradle Plugin
//

// This is the published plugin/catalog version. It is independent from Elide's runtimeVersion.
val pluginVersion = "1.0.0"

pluginManagement {
    // Make the Elide plugin repository available to settings plugin resolution.
    repositories {
        maven {
            name = "elide"
            url = uri("https://maven.elide.dev")
        }
    }
}

dependencyResolutionManagement {
    // Make the Elide repository available to dependency resolution.
    @Suppress("UnstableApiUsage")
    repositories {
        maven {
            name = "elide"
            url = uri("https://maven.elide.dev")
        }
    }

    // Add the catalog published with this plugin release.
    versionCatalogs {
        create("elideRuntime") {
            from("dev.elide.gradle:elide-gradle-catalog:$pluginVersion")
        }
    }
}
