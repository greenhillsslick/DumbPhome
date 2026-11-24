#!/usr/bin/env python3
"""Posts the radio stream table as a commit comment.

Runner logs live on a blob store that not every client can read, but the REST
API always is: a commit comment is the one place where "do the stations still
answer?" can be answered later, including from the release page.

    GITHUB_TOKEN=... GITHUB_REPOSITORY=owner/repo GITHUB_SHA=abc \
        tools/post_streams.py /tmp/streams.md
"""
import json
import os
import sys
import urllib.request

table = open(sys.argv[1], encoding="utf-8", errors="replace").read().strip()[-4000:]

body = (
    "**Radio en directo** — comprobación de las emisoras\n\n"
    "Esto no es una prueba de que suenen, es una prueba de que responden: que el\n"
    "servidor conteste con datos de audio. Solo un móvil puede confirmar el sonido.\n\n"
    + table
)

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
    print("posted stream comment:", response.status)
