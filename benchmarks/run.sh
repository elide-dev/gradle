#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export GRADLE_USER_HOME="$root/benchmarks/build/gradle-home"
: "${JAVA_HOME:?Set JAVA_HOME to a Java 17 JDK}"
variant=${1:?Usage: benchmarks/run.sh javac|elide}
case "$variant" in
  javac|elide) ;;
  *) echo "Unknown benchmark variant: $variant" >&2; exit 2 ;;
esac

# clean is deliberately inside the measurement. No downloads or cached task outputs.
exec "$root/gradlew" -p "$root/benchmarks/$variant" \
  --offline --no-daemon --no-build-cache --no-configuration-cache \
  --max-workers=2 --console=plain \
  -Porg.gradle.java.installations.auto-detect=false \
  -Porg.gradle.java.installations.auto-download=false \
  "-Porg.gradle.java.installations.paths=$JAVA_HOME" clean build
