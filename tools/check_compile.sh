#!/usr/bin/env bash
# Compiles every Kotlin source against a real android.jar, with no Gradle and no
# Android SDK. Useful as a fast, deterministic gate in environments where the
# SDK downloads are not reachable (CI still builds the actual APK).
#
#   tools/check_compile.sh [android.jar] [kotlinc]
#
# Defaults:
#   android.jar  $ANDROID_JAR  (or /tmp/ap34/android-34/android.jar)
#   kotlinc      kotlinc       (on PATH, or a local install)
set -euo pipefail
cd "$(dirname "$0")/.."

JAR="${1:-${ANDROID_JAR:-/tmp/ap34/android-34/android.jar}}"
KOTLINC="${2:-${KOTLINC:-kotlinc}}"
OUT="${OUT:-/tmp/dumbos-check.jar}"
STDLIB="$(dirname "$(command -v "$KOTLINC")")/../lib/kotlin-stdlib.jar"

[ -f "$JAR" ] || { echo "no android.jar at $JAR"; exit 2; }
command -v "$KOTLINC" >/dev/null || { echo "no kotlinc at $KOTLINC"; exit 2; }

echo "android.jar : $JAR"
echo "kotlinc     : $KOTLINC"
echo "stdlib      : $STDLIB"

# shellcheck disable=SC2046
"$KOTLINC" -no-jdk -no-stdlib \
  -classpath "$JAR:$STDLIB" \
  -jvm-target 17 \
  -d "$OUT" \
  $(find app/src/main/java -name '*.kt')

echo
echo "OK -> $OUT"
