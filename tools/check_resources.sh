#!/usr/bin/env bash
# Validates resources + manifest with aapt2 (no Gradle): compiles res/, links it
# against android.jar and prints the badging of the resulting resource APK.
#
#   tools/check_resources.sh [android.jar] [aapt2]
set -euo pipefail
cd "$(dirname "$0")/.."

JAR="${1:-${ANDROID_JAR:-/tmp/ap34/android-34/android.jar}}"
AAPT2="${2:-${AAPT2:-aapt2}}"
WORK="$(mktemp -d)"

[ -f "$JAR" ] || { echo "no android.jar at $JAR"; exit 2; }
command -v "$AAPT2" >/dev/null || { echo "no aapt2 at $AAPT2"; exit 2; }

"$AAPT2" compile --dir app/src/main/res -o "$WORK/res.zip"

# AGP injects the namespace; standalone aapt2 still wants a package attribute,
# so a temporary copy gets one instead of keeping it in the repo manifest.
python3 - "$WORK" <<'PY'
import sys, pathlib
work = pathlib.Path(sys.argv[1])
src = pathlib.Path('app/src/main/AndroidManifest.xml').read_text()
marker = 'xmlns:tools="http://schemas.android.com/tools">'
assert marker in src
(work / 'AndroidManifest.xml').write_text(
    src.replace(marker, marker[:-1] + '\n    package="com.dumbos">', 1))
PY

# The same SDK levels the Gradle build uses, so the implied-permission list in
# the badging below matches the real APK.
"$AAPT2" link -I "$JAR" --manifest "$WORK/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 1 --version-name 0.1.0 \
  -o "$WORK/app-res.apk" --java "$WORK/gen" "$WORK/res.zip"

echo
"$AAPT2" dump badging "$WORK/app-res.apk" |
  grep -E "^(package|application-label|launchable-activity|uses-permission)"
echo
echo "OK -> $WORK/app-res.apk"
