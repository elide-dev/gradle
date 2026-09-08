plugins {
    `java-gradle-plugin`
    `maven-publish`
    signing
    alias(libs.plugins.plugin.publish)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

val elideVersion = findProperty("elide.version")?.toString() ?: error(
    "Please provide the 'elide.version' property in the gradle.properties file or as a command line argument."
)

group = "dev.elide.gradle"
version = findProperty("version")?.toString() ?: error(
    "Please provide the 'version' property in the gradle.properties file."
)

publishing {
    repositories {
        maven {
            url = uri(rootProject.layout.buildDirectory.dir("elide-maven"))
        }
    }
}

dependencies {
    testImplementation(gradleTestKit())
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencyLocking {
    lockAllConfigurations()
}

gradlePlugin {
    website = "https://elide.dev"
    vcsUrl = "https://github.com/elide-dev/gradle"

    plugins.create("elide") {
        id = "dev.elide"
        displayName = "Elide Gradle Plugin"
        implementationClass = "dev.elide.gradle.ElideGradlePlugin"
        description = "Use the Elide runtime and build tools from Gradle"
        tags.set(listOf("elide", "graalvm", "java", "javac", "maven", "dependencies", "resolver"))
    }

    plugins.create("elideSettings") {
        id = "dev.elide.settings"
        displayName = "Elide Gradle Settings Plugin"
        implementationClass = "dev.elide.gradle.ElideSettingsPlugin"
        description = "Configure the Elide runtime once for a Gradle build"
        tags.set(listOf("elide", "settings", "toolchain", "dependencies"))
    }
}

// Add a source set and a task for a functional test suite
val functionalTest: SourceSet by sourceSets.creating

configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[functionalTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

val compatibilityTest: SourceSet by sourceSets.creating
val currentToolchainTest: SourceSet by sourceSets.creating
val realRuntimeSmokeSourceSet = sourceSets.create("realRuntimeSmoke")
gradlePlugin.testSourceSets(functionalTest, compatibilityTest, currentToolchainTest, realRuntimeSmokeSourceSet)

configurations[compatibilityTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[compatibilityTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
compatibilityTest.compileClasspath += functionalTest.output
compatibilityTest.runtimeClasspath += functionalTest.output

configurations[currentToolchainTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[currentToolchainTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
currentToolchainTest.compileClasspath += functionalTest.output
currentToolchainTest.runtimeClasspath += functionalTest.output

configurations[realRuntimeSmokeSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[realRuntimeSmokeSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(functionalTest.implementationConfigurationName, gradleTestKit())
    add(compatibilityTest.implementationConfigurationName, gradleTestKit())
    add(currentToolchainTest.implementationConfigurationName, gradleTestKit())
    add(realRuntimeSmokeSourceSet.implementationConfigurationName, gradleTestKit())
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
}

val compatibilityTestTask = tasks.register<Test>("compatibilityTest") {
    group = "verification"
    description = "Runs consumer builds against every supported Gradle version."
    testClassesDirs = compatibilityTest.output.classesDirs
    classpath = compatibilityTest.runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
}

val requestedCurrentToolchainJava = providers.gradleProperty("elide.currentToolchain.java")
val requestedCurrentToolchainGradle = providers.gradleProperty("elide.currentToolchain.gradle")
val currentToolchainJava = requestedCurrentToolchainJava.orNull.orEmpty()
val currentToolchainGradle = requestedCurrentToolchainGradle.orNull.orEmpty()
val currentToolchainPairs = mapOf("24" to "8.14.5", "26" to "9.7.1")
val currentToolchainLauncher = javaToolchains.launcherFor {
    languageVersion.set(requestedCurrentToolchainJava.map { JavaLanguageVersion.of(it.toInt()) }
        .orElse(JavaLanguageVersion.of(17)))
}

val currentToolchainConsumerTest = tasks.register<Test>("currentToolchainConsumerTest") {
    group = "verification"
    description = "Runs the requested Gradle/JDK consumer pair outside the Java 17 compatibility baseline."
    testClassesDirs = currentToolchainTest.output.classesDirs
    classpath = currentToolchainTest.runtimeClasspath
    javaLauncher.set(currentToolchainLauncher)
    inputs.property("elide.currentToolchain.java", currentToolchainJava)
    inputs.property("elide.currentToolchain.gradle", currentToolchainGradle)
    systemProperty("elide.currentToolchain.java", currentToolchainJava)
    systemProperty("elide.currentToolchain.gradle", currentToolchainGradle)
    doFirst {
        check(currentToolchainPairs[currentToolchainJava] == currentToolchainGradle) {
            "currentToolchainConsumerTest requires one of " +
                    "-Pelide.currentToolchain.java=24 -Pelide.currentToolchain.gradle=8.14.5 or " +
                    "-Pelide.currentToolchain.java=26 -Pelide.currentToolchain.gradle=9.7.1"
        }
    }
}

val requestedSmokeRuntimeMode = providers.gradleProperty("elide.runtime.mode").orElse("MANAGED")
val managedSmokeRuntimeVersion = "1.5.1+20260903"

val realRuntimeSmoke by tasks.registering(Test::class) {
    group = "verification"
    description = "Verifies real managed Elide provisioning, dependency installation, compilation, and execution."
    testClassesDirs = realRuntimeSmokeSourceSet.output.classesDirs
    classpath = realRuntimeSmokeSourceSet.runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
    inputs.property("elide.runtime.mode", requestedSmokeRuntimeMode)
    systemProperty("elide.runtime.mode", requestedSmokeRuntimeMode.get())
    systemProperty("elide.runtime.version", elideVersion)
    val exampleDirectory = rootProject.layout.projectDirectory.dir("example-project")
    inputs.files(exampleDirectory.file("build.gradle.kts"), exampleDirectory.file("gradle.properties"))
    inputs.dir(exampleDirectory.dir("src"))
    systemProperty("elide.example.directory", exampleDirectory.asFile.absolutePath)
    doFirst {
        check(requestedSmokeRuntimeMode.get() == "MANAGED") {
            "realRuntimeSmoke requires -Pelide.runtime.mode=MANAGED"
        }
        check(elideVersion == managedSmokeRuntimeVersion) {
            "realRuntimeSmoke verifies the pinned managed runtime $managedSmokeRuntimeVersion"
        }
    }
}

tasks.check {
    dependsOn(functionalTestTask)
    dependsOn(compatibilityTestTask)
}
