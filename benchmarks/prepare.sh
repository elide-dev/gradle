#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export GRADLE_USER_HOME="$root/benchmarks/build/gradle-home"
: "${JAVA_HOME:?Set JAVA_HOME to a Java 17 JDK}"
cd "$root"
mkdir -p benchmarks/build

# Compile the plugin once, outside CodSpeed. The measured consumer loads only its JAR.
./gradlew --no-daemon --console=plain :elide-gradle-plugin:jar
version=$(sed -n 's/^version=//p' gradle.properties)
cp "elide-gradle-plugin/build/libs/elide-gradle-plugin-$version.jar" \
  benchmarks/build/elide-gradle-plugin.jar

# Resolve the pinned managed runtime and warm Gradle's script/dependency caches.
for variant in javac elide; do
  ./gradlew -p "benchmarks/$variant" --no-daemon --no-build-cache \
    --no-configuration-cache --max-workers=2 --console=plain \
    -Porg.gradle.java.installations.auto-detect=false \
    -Porg.gradle.java.installations.auto-download=false \
    "-Porg.gradle.java.installations.paths=$JAVA_HOME" clean build
done

bash benchmarks/verify.sh

# Keep toolchain identity with the CI artifacts for later interpretation.
{
  git rev-parse HEAD
  ./gradlew --version
  cat gradle.properties
  uname -a
} > benchmarks/build/environment.txt
