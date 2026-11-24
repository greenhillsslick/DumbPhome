#!/usr/bin/env bash
# Compiles every Kotlin source against a real android.jar, with no Gradle and no
# Android SDK. A fast local gate before pushing to CI.
#
# IMPORTANT: use the same compiler version the Gradle build uses, or the gate
# lies. "Nested typealiases" and `sumOf` overload ambiguity, for example, are
# hard errors in Kotlin 2.0.x but are accepted by some newer compilers.
#
#   tools/check_compile.sh [android.jar] [kotlinc]
#
# Defaults:
#   android.jar  $ANDROID_JAR  (or /tmp/ap34/android-34/android.jar)
#   kotlinc      kotlinc       (on PATH)
set -euo pipefail
cd "$(dirname "$0")/.."

JAR="${1:-${ANDROID_JAR:-/tmp/ap34/android-34/android.jar}}"
KOTLINC="${2:-${KOTLINC:-kotlinc}}"
OUT="${OUT:-/tmp/dumbos-check.jar}"
LIBDIR="$(cd "$(dirname "$KOTLINC")/../lib" && pwd)"
STDLIB="$LIBDIR/kotlin-stdlib.jar"

[ -f "$JAR" ] || { echo "no android.jar at $JAR"; exit 2; }
[ -x "$KOTLINC" ] || command -v "$KOTLINC" >/dev/null || { echo "no kotlinc at $KOTLINC"; exit 2; }
[ -f "$STDLIB" ] || { echo "no kotlin-stdlib.jar next to $KOTLINC"; exit 2; }

echo "android.jar : $JAR"
echo "kotlinc     : $KOTLINC"
# shellcheck disable=SC2046
"$KOTLINC" -no-jdk -no-stdlib \
  -classpath "$JAR:$STDLIB" \
  -jvm-target 17 \
  -d "$OUT" \
  $(find app/src/main/java -name '*.kt')

echo
echo "OK -> $OUT"
