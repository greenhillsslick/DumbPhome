#!/usr/bin/env python3
"""Posts the tail of a CI log as a commit comment.

GitHub Actions log downloads are not always reachable from every client, while
the REST API is; a commit comment is a reliable way to read a failing build.

    GITHUB_TOKEN=... GITHUB_REPOSITORY=owner/repo GITHUB_SHA=abc123 \
        tools/post_log.py /tmp/build.log [max_chars]
"""
import json
import os
import sys
import urllib.request

log_path = sys.argv[1]
limit = int(sys.argv[2]) if len(sys.argv) > 2 else 6000
token = os.environ["GITHUB_TOKEN"]
repo = os.environ["GITHUB_REPOSITORY"]
sha = os.environ["GITHUB_SHA"]

with open(log_path, "r", errors="replace") as fh:
    whole = fh.read()

# Prefer the first real error (Kotlin/AAPT/Gradle) over the noise at the tail.
interesting = [
    line for line in whole.splitlines()
    if any(token in line for token in (
        "e: file", "error:", "FAILURE:", "What went wrong", "Caused by", "> Task :", "BUILD FAILED",
    ))
]
head = "\n".join(interesting[:80])
text = (head + "\n\n--- tail ---\n" + whole[-3000:])[-limit:]
text = text.replace("```", "'''")

request = urllib.request.Request(
    f"https://api.github.com/repos/{repo}/commits/{sha}/comments",
    data=json.dumps({"body": f"Build log (tail):\n\n```\n{text}\n```"}).encode(),
    headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
    },
    method="POST",
)
with urllib.request.urlopen(request, timeout=30) as response:
    print("posted log comment:", response.status)
