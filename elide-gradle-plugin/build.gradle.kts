import de.undercouch.gradle.tasks.download.Download

plugins {
    `maven-publish`
    `java-gradle-plugin`
    signing
    id("com.gradle.plugin-publish") version "1.2.1"
    id("de.undercouch.download") version "5.6.0"
}

val elideVersion = findProperty("elide.version")?.toString() ?: error(
    "Please provide the 'elide.version' property in the gradle.properties file or as a command line argument."
)

group = "dev.elide.gradle"
version = elideVersion

publishing {
    repositories {
        maven {
            url = uri(rootProject.layout.buildDirectory.dir("elide-maven"))
        }
    }
}

val elideArch = when (System.getProperty("os.arch").lowercase()) {
    "x86_64", "amd64" -> "amd64"
    "arm64", "aarch64" -> "arm64"
    else -> error("Unsupported architecture: ${System.getProperty("os.arch")}")
}
val elidePlatform = when (System.getProperty("os.name").lowercase()) {
    "linux" -> "linux-$elideArch"
    "mac os x" -> "darwin-$elideArch"
    "windows" -> "windows-$elideArch"
    else -> error("Unsupported OS: ${System.getProperty("os.name")}")
}

repositories {
    mavenCentral()
}

val elideRuntime: Configuration by configurations.creating {
    isCanBeResolved = true
}

dependencies {
    elideRuntime(files(zipTree(rootProject.layout.buildDirectory.dir("elide-runtime"))))

    // Use JUnit test framework for unit tests
    testImplementation("junit:junit:4.13.1")
}

gradlePlugin {
    website = "https://elide.dev"
    vcsUrl = "https://github.com/elide-dev/gradle"

    val elide by plugins.creating {
        id = "dev.elide"
        displayName = "Elide Gradle Plugin"
        implementationClass = "dev.elide.gradle.ElideGradlePlugin"
        description = "Use the Elide runtime and build tools from Gradle"
        tags.set(listOf("elide", "graalvm", "java", "javac", "maven", "dependencies", "resolver"))
    }
}

// Add a source set and a task for a functional test suite
val functionalTest: SourceSet by sourceSets.creating
gradlePlugin.testSourceSets(functionalTest)

configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())

val runtimeHome = layout.buildDirectory.dir("elide-runtime/elide-$elideVersion-$elidePlatform")

val functionalTestTask = tasks.register<Test>("functionalTest") {
    testClassesDirs = functionalTest.output.classesDirs
    classpath = configurations[functionalTest.runtimeClasspathConfigurationName] + functionalTest.output
}

val downloadElide by tasks.registering(Download::class) {
    src("https://elide.zip/cli/v1/snapshot/$elidePlatform/$elideVersion/elide.tgz")
    dest(layout.buildDirectory.dir("elide-runtime"))
    outputs.file(layout.buildDirectory.file("elide-runtime/elide.tgz"))
}

val extractElide by tasks.registering(Copy::class) {
    from(tarTree(layout.buildDirectory.file("elide-runtime/elide.tgz")))
    into(layout.buildDirectory.dir("elide-runtime"))
    inputs.file(layout.buildDirectory.file("elide-runtime/elide.tgz"))
    dependsOn(downloadElide)
}

val prepareElide by tasks.registering {
    group = "build"
    description = "Prepare the Elide runtime"
    dependsOn(downloadElide, extractElide)
}

val checkElide by tasks.registering(Exec::class) {
    executable = runtimeHome.get().file("elide").asFile.absolutePath
    args("--version")
    dependsOn(downloadElide, extractElide, prepareElide)
}

listOf(
    tasks.build,
    tasks.test,
    tasks.check,
).forEach {
    it.configure {
        dependsOn(downloadElide, extractElide, prepareElide, checkElide)
    }
}

tasks.check {
    dependsOn(functionalTestTask)
}
