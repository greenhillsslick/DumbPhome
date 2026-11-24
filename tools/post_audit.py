#!/usr/bin/env python3
"""Posts the APK audit (summary + detail) as a commit comment.

    GITHUB_TOKEN=... GITHUB_REPOSITORY=owner/repo GITHUB_SHA=abc \
        tools/post_audit.py summary.log audit.log
"""
import json
import os
import sys
import urllib.request

summary = open(sys.argv[1], encoding="utf-8", errors="replace").read()[-2500:]
audit = open(sys.argv[2], encoding="utf-8", errors="replace").read()[-6000:]

fence = "`" * 3
body = (
    "APK audit\n\n"
    + fence + "\n" + summary + "\n" + fence + "\n\n"
    + fence + "\n" + audit + "\n" + fence
).replace(fence, "'''")

request = urllib.request.Request(
    f"https://api.github.com/repos/{os.environ['GITHUB_REPOSITORY']}"
    f"/commits/{os.environ['GITHUB_SHA']}/comments",
    data=json.dumps({"body": body}).encode(),
    headers={
        "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
    },
    method="POST",
)
with urllib.request.urlopen(request, timeout=30) as response:
    print("posted audit comment:", response.status)
