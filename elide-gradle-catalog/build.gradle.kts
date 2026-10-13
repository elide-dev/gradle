import com.vanniktech.maven.publish.VersionCatalog

plugins {
  `version-catalog`
  alias(libs.plugins.maven.publish)
}

val latestElide = findProperty("elide.version")?.toString() ?: error(
  "Please provide the 'elide.version' property in the gradle.properties file or as a command line argument."
)
val projectVersion = findProperty("version")?.toString() ?: error(
  "Please provide the 'version' property in the gradle.properties file."
)

group = "dev.elide.gradle"
version = projectVersion

val mainPluginId = "dev.elide"
val settingsPluginId = "dev.elide.settings"

val allLibs = listOf(
  "core",
  "base",
  "graalvm",
)

catalog {
  versionCatalog {
    version("elide", latestElide)
    version("elidePlugin", projectVersion)
    plugin("elide", mainPluginId).versionRef("elidePlugin")
    plugin("elideSettings", settingsPluginId).versionRef("elidePlugin")
    allLibs.forEach {
      library(it, "dev.elide", "elide-$it").versionRef("elide")
    }
  }
}

publishing {
  repositories {
    maven {
      url = uri(rootProject.layout.buildDirectory.dir("elide-maven"))
    }
  }
}

mavenPublishing {
  configure(VersionCatalog())
  publishToMavenCentral(automaticRelease = true)
  signAllPublications()
  pom {
    name = "Elide Gradle Catalog"
    description = "Provides mapped versions for Elide and related libraries and plugins."
    inceptionYear = "2023"
    url = "https://elide.dev"
    licenses {
      license {
        name = "MIT License"
        url = "https://opensource.org/license/mit"
      }
    }
    developers {
      developer {
        name = "Elide Technologies, Inc."
        url = "https://elide.dev"
      }
    }
    scm {
      url = "https://github.com/elide-dev/gradle"
      connection = "scm:git:https://github.com/elide-dev/gradle.git"
      developerConnection = "scm:git:ssh://git@github.com/elide-dev/gradle.git"
    }
  }
}
