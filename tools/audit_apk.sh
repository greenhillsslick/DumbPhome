#!/usr/bin/env bash
# Audits a built APK with the SDK's own tooling. Run in CI (needs build-tools).
#
#   tools/audit_apk.sh app/build/outputs/apk/release/app-release.apk
set -euo pipefail

APK="${1:?usage: tools/audit_apk.sh path/to.apk}"
BT="${ANDROID_SDK_ROOT:-${ANDROID_HOME}}/build-tools/34.0.0"

echo "================ APK AUDIT: $APK"
ls -l "$APK"

echo
echo "---- badging ----"
"$BT/aapt2" dump badging "$APK" |
  grep -E "^(package|sdkVersion|targetSdkVersion|application-label|launchable-activity)" || true

echo
echo "---- signer ----"
"$BT/apksigner" verify --verbose --print-certs "$APK" | head -25

echo
echo "---- signature schemes ----"
"$BT/apksigner" verify --verbose "$APK" | grep -iE "scheme|verified using" || true

echo
echo "---- components ----"
"$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK" |
  grep -oE '(activity|activity-alias|receiver|service|provider) android:name\(0x01010003\)="[^"]+"' |
  sed 's/ android:name(0x01010003)=//' | sort -u || true

echo
echo "---- dex / resources ----"
unzip -l "$APK" | tail -20
echo "classes.dex methods (estimated from size):"
unzip -p "$APK" classes.dex | wc -c

echo
echo "---- resources.arsc presence ----"
unzip -l "$APK" | grep -cE "resources.arsc|AndroidManifest.xml" || true

echo
echo "---- zipalign ----"
"$BT/zipalign" -c -v 4 "$APK" | tail -3

echo
echo "================ AUDIT OK"
