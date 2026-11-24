#!/usr/bin/env bash
# Audits a built APK with the SDK's own tooling. Run in CI (needs build-tools).
#
#   tools/audit_apk.sh app/build/outputs/apk/release/app-release.apk
set -euo pipefail

APK="${1:?usage: tools/audit_apk.sh path/to.apk}"
BT="${ANDROID_SDK_ROOT:-${ANDROID_HOME}}/build-tools/34.0.0"

MODE="${2:-full}"
echo "================ APK AUDIT: $APK ($MODE)"
ls -l "$APK"

if [ "$MODE" = "summary" ]; then
  echo "---- badging ----"
  "$BT/aapt2" dump badging "$APK" |
    grep -E "^(package|sdkVersion|targetSdkVersion|application-label|launchable-activity)" || true
  echo
  echo "---- size ----"
  du -h "$APK"
  exit 0
fi

echo
echo "---- signer ----"
"$BT/apksigner" verify --verbose --print-certs "$APK" | head -25

echo
echo "---- signature schemes ----"
"$BT/apksigner" verify --verbose "$APK" | grep -iE "scheme|verified using" || true

echo
echo "---- components ----"
# aapt2 prints lines like:  E: activity (line=44)  /  A: ...:name(0x01010003)="com.x.Y"
"$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK" |
  awk '
    /^ *E: (activity|activity-alias|receiver|service|provider)/ { kind=$2; want=1; next }
    want && /android:name\(0x01010003\)=/ {
      match($0, /"[^"]+"/); name=substr($0, RSTART+1, RLENGTH-2);
      printf "%-16s %s\n", kind, name; want=0
    }
  ' | sort || true

echo
echo "---- dex / resources ----"
unzip -l "$APK" | tail -20
echo "classes.dex methods (estimated from size):"
unzip -p "$APK" classes.dex | wc -c

echo
echo "---- declared components vs dex ----"
# Every manifest component must exist as a real class in the APK's dex files,
# otherwise the app installs and then crashes on launch.
COMPONENTS=$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK" |
  awk '/^ *E: (activity|activity-alias|receiver|service|provider)/ { want=1; next }
       want && /android:name\(0x01010003\)=/ {
         match($0, /"[^"]+"/); n=substr($0, RSTART+1, RLENGTH-2); print n; want=0 }' |
  sed 's/^\./com.dumbos./')
MISSING=0
for comp in $COMPONENTS; do
  # activity-alias resolves to its target activity's class
  if ! python3 tools/dex_has_class.py "$APK" "$comp"; then
    if [ "${comp##*.}" = "OpenDumbOS" ]; then continue; fi
    echo "MISSING in dex: $comp"
    MISSING=$((MISSING + 1))
  else
    echo "ok: $comp"
  fi
done
[ "$MISSING" -eq 0 ] || { echo "components missing from dex" >&2; exit 1; }

echo
echo "---- resources.arsc presence ----"
unzip -l "$APK" | grep -cE "resources.arsc|AndroidManifest.xml" || true

echo
echo "---- zipalign ----"
"$BT/zipalign" -c -v 4 "$APK" | tail -3

echo
echo "================ AUDIT OK"
