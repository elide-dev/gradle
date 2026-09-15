#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export GRADLE_USER_HOME="$root/benchmarks/build/gradle-home"
: "${JAVA_HOME:?Set JAVA_HOME to a Java 17 JDK}"
cd "$root"
mkdir -p benchmarks/build

# Capture the environment before compilation, including when runtime startup fails.
{
  git rev-parse HEAD
  ./gradlew --version
  cat gradle.properties
  uname -a
  if [[ -f /etc/os-release ]]; then
    cat /etc/os-release
    getconf GNU_LIBC_VERSION
    getconf PAGESIZE
    sed -n '1,/^$/p' /proc/cpuinfo
  fi
} > benchmarks/build/environment.txt

# Compile the plugin once, outside CodSpeed. The measured consumer loads it from a flat classpath,
# so its runtime dependencies are staged alongside the JAR rather than resolved during the build.
./gradlew --no-daemon --console=plain :elide-gradle-plugin:benchmarkClasspath
rm -rf benchmarks/build/plugin-classpath
cp -R elide-gradle-plugin/build/benchmark-classpath benchmarks/build/plugin-classpath

# Resolve the pinned managed runtime and warm Gradle's script/dependency caches.
for variant in javac elide; do
  ./gradlew -p "benchmarks/$variant" --no-daemon --no-build-cache \
    --no-configuration-cache --max-workers=2 --console=plain \
    -Porg.gradle.java.installations.auto-detect=false \
    -Porg.gradle.java.installations.auto-download=false \
    "-Porg.gradle.java.installations.paths=$JAVA_HOME" clean build
done

bash benchmarks/verify.sh
