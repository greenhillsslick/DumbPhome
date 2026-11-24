#!/usr/bin/env python3
"""Probes every station in tools/stations.json and reports which ones answer.

This is the only honest way to know the radio works: a URL in a catalogue is a
claim, and stations rotate their endpoints. Run it where the network is open
(GitHub Actions) rather than trusting the file.

    python3 tools/check_streams.py [--strict] [--timeout 15]

Exit code is 0 when at least one station answers; with --strict, only when all
of them do. Prints a table suitable for a commit comment.
"""
import argparse
import json
import pathlib
import socket
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
HEADERS = {
    "User-Agent": "dumbOS/1.1 (stream check; +https://github.com/greenhillsslick/DumbPhome)",
    "Icy-MetaData": "1",
    "Accept": "*/*",
}

# Some hosts answer 403 to requests that do not look like a media client. The
# probe sends what the app sends, so a failure here is a real failure there.
APP_HEADERS = {
    "Accept": "audio/mpeg, audio/aac, audio/*;q=0.9, */*;q=0.8",
}


def probe(url: str, timeout: float) -> tuple[bool, str]:
    """Returns (ok, detail). Follows redirects because urllib does it for us."""
    started = time.time()
    try:
        request = urllib.request.Request(url, headers={**HEADERS, **APP_HEADERS})
        opener = urllib.request.build_opener()
        opener.addheaders = list({**HEADERS, **APP_HEADERS}.items())
        with opener.open(request, timeout=timeout) as response:
            status = response.status
            ctype = (response.headers.get("Content-Type") or "?").split(";")[0]
            # Some stations only answer after a while; read a little to prove
            # audio actually flows, not just headers.
            data = response.read(2048)
            elapsed = time.time() - started
            if status != 200:
                return False, f"HTTP {status}"
            if not data:
                return False, "sin datos"
            looks_audio = any(
                token in ctype for token in ("audio", "mpeg", "aac", "ogg", "octet-stream")
            ) or any(
                token in (response.headers.get("Content-Type") or "").lower()
                for token in ("audio", "mpeg", "aac", "ogg")
            )
            detail = f"{ctype} {len(data)}B {elapsed:.1f}s"
            return (looks_audio or len(data) > 512), detail
    except urllib.error.HTTPError as exc:
        return False, f"HTTP {exc.code}"
    except (urllib.error.URLError, socket.timeout) as exc:
        return False, f"red: {getattr(exc, 'reason', exc)}"
    except Exception as exc:  # noqa: BLE001 - report anything, never crash a check
        return False, f"{type(exc).__name__}: {exc}"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--strict", action="store_true", help="fail unless every station answers")
    parser.add_argument("--timeout", type=float, default=15.0)
    args = parser.parse_args()

    stations = json.loads((ROOT / "tools" / "stations.json").read_text(encoding="utf-8"))["stations"]
    rows, ok_count = [], 0
    for station in stations:
        ok, detail = probe(station["url"], args.timeout)
        ok_count += int(ok)
        rows.append((station["name"], station["freq"], station["fmt"], ok, detail))
        print(("ok   " if ok else "FAIL ") + f"{station['name']:16} {detail}", flush=True)

    total = len(stations)
    print()
    print(f"| Emisora | Dial | Formato | Estado | Detalle |")
    print("|---|---|---|---|---|")
    for name, freq, fmt, ok, detail in rows:
        print(f"| {name} | {freq:.1f} | {fmt} | {'OK' if ok else 'FALLA'} | {detail} |")
    print()
    print(f"{ok_count}/{total} emisoras responden")

    if ok_count == 0:
        print("ninguna emisora responde: revisa la red del runner")
        return 1
    if args.strict and ok_count != total:
        print("modo estricto: alguna emisora no responde")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
