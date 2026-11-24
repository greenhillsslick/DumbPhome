#!/usr/bin/env python3
"""Exits 0 when a fully qualified class name is present in an APK's dex files.

    tools/dex_has_class.py app-release.apk com.dumbos.home.HomeActivity

Used by tools/audit_apk.sh so a manifest component that the compiler dropped is
caught before the APK ships (the app would install and crash on launch).
"""
import re
import sys
import zipfile

apk, class_name = sys.argv[1], sys.argv[2]
needle = ("L" + class_name.replace(".", "/") + ";").encode()

with zipfile.ZipFile(apk) as archive:
    for name in archive.namelist():
        if re.fullmatch(r"classes\d*\.dex", name) and needle in archive.read(name):
            sys.exit(0)
sys.exit(1)
