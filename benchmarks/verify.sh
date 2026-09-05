#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
mkdir -p benchmarks/build
java_command=${JAVA_HOME:+$JAVA_HOME/bin/}java

# Use the exact measured commands offline, twice: every round must really compile.
for variant in javac elide; do
  for round in 1 2; do
    log="benchmarks/build/$variant-$round.log"
    bash benchmarks/run.sh "$variant" > "$log" 2>&1 || { cat "$log"; exit 1; }
    for task in compileJava compileTestJava cliTest jar distTar distZip; do
      if ! grep -qx "> Task :$task" "$log"; then
        echo "$variant round $round did not execute $task" >&2
        cat "$log"
        exit 1
      fi
    done
    actual=$("$java_command" -cp "benchmarks/$variant/build/libs/hello-cli.jar" \
      dev.elide.benchmark.Main Ada Lovelace)
    if [[ "$actual" != 'Hello, Ada Lovelace!' ]]; then
      echo "Unexpected CLI output for $variant: $actual" >&2
      exit 1
    fi
  done
done
echo 'Both compiler variants rebuilt, tested, packaged, and ran successfully in two offline rounds.'
