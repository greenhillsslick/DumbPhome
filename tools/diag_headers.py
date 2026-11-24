#!/usr/bin/env python3
"""One-off: find out which request shape Atresmedia serves audio to."""
import urllib.request, urllib.error, json, pathlib

URLS = [
    "https://radio-atres-live.ondacero.es/api/livestream-redirect/EFMAAC.aac",
    "https://radio-atres-live.ondacero.es/api/livestream-redirect/MELODIA_FMAAC.aac",
    "https://radio-atres-live.ondacero.es/api/livestream-redirect/OCAAC.aac",
]
MOZ = ("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36")
VARIANTS = {
    "bare": {},
    "ua_moz": {"User-Agent": MOZ},
    "ua_moz+accept": {"User-Agent": MOZ, "Accept": "audio/mpeg, audio/aac, */*;q=0.8"},
    "ua_moz+referer": {"User-Agent": MOZ, "Referer": "https://www.ondacero.es/"},
    "full_browser": {
        "User-Agent": MOZ,
        "Accept": "text/html,application/xhtml+xml,audio/*;q=0.9,*/*;q=0.8",
        "Accept-Language": "es-ES,es;q=0.9",
        "Referer": "https://www.ondacero.es/",
        "Origin": "https://www.ondacero.es",
        "Sec-Fetch-Dest": "audio", "Sec-Fetch-Mode": "no-cors", "Sec-Fetch-Site": "cross-site",
    },
    "dumbos": {"User-Agent": "dumbOS/1.1 (Android)", "Icy-MetaData": "1"},
}
out = []
for u in URLS:
    for name, h in VARIANTS.items():
        try:
            req = urllib.request.Request(u, headers=h)
            with urllib.request.urlopen(req, timeout=10) as r:
                n = len(r.read(512))
                out.append(f"{u.split('/')[-1]:22} {name:16} -> {r.status} {r.headers.get('Content-Type')} {n}B  final={r.geturl()[:70]}")
        except urllib.error.HTTPError as e:
            out.append(f"{u.split('/')[-1]:22} {name:16} -> HTTP {e.code}")
        except Exception as e:
            out.append(f"{u.split('/')[-1]:22} {name:16} -> {type(e).__name__}: {str(e)[:60]}")
print("\n".join(out))
