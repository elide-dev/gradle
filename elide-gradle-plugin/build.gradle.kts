plugins {
    `maven-publish`
    `kotlin-dsl`
    `java-gradle-plugin`
    signing
    alias(libs.plugins.gradle.publish)
    alias(libs.plugins.undercouch.download)
}

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

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.pluginClasspath.undercouch.download)
    implementation(libs.sigstore)

    testImplementation(gradleTestKit())
    testImplementation(libs.kotlin.test.junit5)
}

kotlin {
    explicitApi()
}

val functionalTest: SourceSet by sourceSets.creating {
    configurations[implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
}

val functionalTestTask by tasks.register<Test>("functionalTest") {
    testClassesDirs = functionalTest.output.classesDirs
    classpath = configurations[functionalTest.runtimeClasspathConfigurationName] + functionalTest.output
}

gradlePlugin {
    website = "https://elide.dev"
    vcsUrl = "https://github.com/elide-dev/gradle"

    testSourceSets(sourceSets.test.get(), functionalTest)

    plugins.register("elide") {
        id = "dev.elide"
        displayName = "Elide Gradle Plugin"
        implementationClass = "dev.elide.gradle.ElideGradlePlugin"
        description = "Use the Elide runtime and build tools from Gradle"
        tags.set(listOf("elide", "graalvm", "java", "javac", "kotlin", "kotlinc", "javadoc", "maven", "dependencies", "resolver"))
    }
}

tasks.check {
    dependsOn(functionalTestTask)
}