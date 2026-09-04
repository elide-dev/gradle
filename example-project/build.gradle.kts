plugins {
  id("dev.elide")
  java
  application
}

application {
  mainClass = "com.example.HelloWorld"
}

repositories {
  mavenCentral()
}

tasks.withType<JavaCompile>().configureEach {
  // Keep the packaged application runnable on Java 17+, even when Gradle runs on a newer JDK.
  options.release.set(17)
}

elide {
  // Use Elide to compile the project instead of stock `javac`.
  enableJavaCompiler.set(true)
}

dependencies {
  // Gradle resolves the complete compile/runtime classpath, including transitive dependencies.
  implementation("com.google.guava:guava:33.4.8-jre")
}
