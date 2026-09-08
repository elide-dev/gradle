plugins {
  id("dev.elide")
  java
  application
}

application {
  mainClass = "com.example.HelloWorld"
}

elide {
  // Use Elide instead of Gradle to resolve and download dependencies. Note that Elide uses Maven's resolver semantics
  // by default, so this may produce a different dependency graph than Gradle.
  install = true

  // Use Elide to compile the project instead of stock `javac`.
  compiler = true

  // Use the project's `elide.pkl` manifest to resolve dependencies and create tasks.
  manifest = layout.projectDirectory.file("elide.pkl")
}

dependencies {
  // Versions are mapped here so that Gradle becomes aware of the classpath needed to build and run the project. Elide
  // is used to resolve and download these dependencies, even though they are declared here.
  //
  // That's because `elide install` creates an `m2`-compatible root (a "local Maven repository") at
  // `.dev/dependencies/m2`. Gradle picks up the dependencies from there.
  implementation("com.google.guava:guava:33.4.8-jre")
}
