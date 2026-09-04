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

configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[functionalTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

val compatibilityTest: SourceSet by sourceSets.creating
val realRuntimeSmokeSourceSet = sourceSets.create("realRuntimeSmoke")
gradlePlugin.testSourceSets(functionalTest, compatibilityTest, realRuntimeSmokeSourceSet)

configurations[compatibilityTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[compatibilityTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
compatibilityTest.compileClasspath += functionalTest.output
compatibilityTest.runtimeClasspath += functionalTest.output

configurations[realRuntimeSmokeSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[realRuntimeSmokeSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(functionalTest.implementationConfigurationName, gradleTestKit())
    add(compatibilityTest.implementationConfigurationName, gradleTestKit())
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

val requestedSmokeRuntimeMode = providers.gradleProperty("elide.runtime.mode").orElse("MANAGED")
val managedSmokeRuntimeVersion = "1.5.1+20260903"

val realRuntimeSmoke by tasks.registering(Test::class) {
    group = "verification"
    description = "Verifies download, checksum, extraction, and caching through the managed plugin runtime."
    testClassesDirs = realRuntimeSmokeSourceSet.output.classesDirs
    classpath = realRuntimeSmokeSourceSet.runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
    inputs.property("elide.runtime.mode", requestedSmokeRuntimeMode)
    systemProperty("elide.runtime.mode", requestedSmokeRuntimeMode.get())
    systemProperty("elide.runtime.version", elideVersion)
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
