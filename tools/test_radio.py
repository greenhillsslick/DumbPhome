#!/usr/bin/env python3
"""Static checks for the radio, because a radio that lies is worse than none.

The player itself can only be exercised on a device with a network (see
tools/check_streams.py for the stream side). What *can* be checked anywhere,
deterministically, is the part that decides what the screen claims:

  * the catalogue is well formed and the generated Kotlin/JS match the JSON;
  * "SONANDO" is emitted only after real PCM exists / the <audio> element plays;
  * every failure path in the UI says SIN SEÑAL instead of inventing audio;
  * the preview never asks for CORS on the streams (real stations do not send
    Access-Control-Allow-Origin, so asking makes them fail to load at all).

Run: python3 tools/test_radio.py
"""

import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
FAILS = []


def check(name, ok, detail=""):
    if ok:
        print(f"ok   {name}")
    else:
        print(f"FAIL {name}{(' — ' + detail) if detail else ''}")
        FAILS.append(name)


# ---------------------------------------------------------------- catalogue

def load_catalogue():
    with open(ROOT / "tools/stations.json", encoding="utf-8") as fh:
        return json.load(fh)["stations"]


def test_catalogue():
    stations = load_catalogue()
    check("the dial has stations", len(stations) >= 8, f"{len(stations)} found")

    keys = [s["key"] for s in stations]
    check("station keys are unique", len(keys) == len(set(keys)))

    freqs = [s["freq"] for s in stations]
    check("frequencies are unique", len(freqs) == len(set(freqs)))
    check("frequencies inside the FM band", all(87.5 <= f <= 108.0 for f in freqs))
    check("frequencies sorted like a dial", freqs == sorted(freqs))

    bad_url = [s["key"] for s in stations if not s["url"].startswith("https://")]
    check("every stream is https", not bad_url, ", ".join(bad_url))

    # The app only knows these three decoders; a typo would silently break one.
    bad_fmt = [s["key"] for s in stations if s["fmt"] not in ("mp3", "aac", "ogg")]
    check("formats are decodable (mp3/aac/ogg)", not bad_fmt, ", ".join(bad_fmt))

    # Two stations closer than the 0.45 snap tolerance would fight over the dial.
    too_close = [
        f"{a['key']}/{b['key']}"
        for a, b in zip(stations, stations[1:])
        if b["freq"] - a["freq"] < 0.5
    ]
    check("no two stations share a snap window", not too_close, ", ".join(too_close))


def test_generated_matches_json():
    """Stations.kt / stations.js must be exactly what the generator writes."""
    before = subprocess.run(
        [sys.executable, str(ROOT / "tools/gen_stations.py")],
        capture_output=True, text=True, cwd=ROOT,
    )
    if before.returncode != 0:
        check("generator runs", False, before.stderr.strip()[-300:])
        return
    check("generator runs", True)

    diff = subprocess.run(
        ["git", "diff", "--exit-code", "--",
         "app/src/main/java/com/dumbos/apps/radio/Stations.kt", "web/stations.js"],
        capture_output=True, text=True, cwd=ROOT,
    )
    check(
        "Stations.kt and stations.js are in sync with stations.json",
        diff.returncode == 0,
        "run python3 tools/gen_stations.py and commit the result",
    )


# ------------------------------------------------------------------- the app

def kotlin(name):
    return (ROOT / "app/src/main/java/com/dumbos/apps/radio" / name).read_text(encoding="utf-8")


def test_player_never_fakes_audio():
    player = kotlin("StreamPlayer.kt")

    # "SONANDO" may only be set next to the decoder's first PCM block.
    playing_sites = [m.start() for m in re.finditer(r'statusText\.set\("SONANDO"\)', player)]
    check("SONANDO is set exactly once", len(playing_sites) == 1, f"{len(playing_sites)} sites")

    if playing_sites:
        window = player[max(0, playing_sites[0] - 400):playing_sites[0] + 400]
        check(
            "SONANDO is emitted from the decoder, next to listener.onPlaying",
            "listener.onPlaying" in window,
            "the UI status must come from decoded audio, not from a successful HTTP connect",
        )
        check(
            "SONANDO sits inside the PCM branch",
            "track.write(pcm, 0, pcm.size)" in window,
            "a connect is not playback",
        )

    # onPlaying must not be called anywhere else: it is the "audio is real" signal.
    onplaying_sites = len(re.findall(r"listener\.onPlaying\(", player))
    check("onPlaying has a single call site", onplaying_sites == 1, f"{onplaying_sites} sites")

    check("a stalled stream is abandoned", "STALL_MS" in player)
    check("errors surface to the UI", "listener.onError(" in player)
    check("decoder is MediaCodec", "MediaCodec.createDecoderByType" in player)
    check("output is AudioTrack", "AudioTrack.Builder" in player)
    check("ID3 tags are skipped", "id3Skipped" in player)
    check("volume reaches the track", "track.setVolume(volume)" in player)


def test_activity_is_honest():
    act = kotlin("RadioActivity.kt")
    check("the screen can say SIN SEÑAL", "SIN SEÑAL" in act)
    check("errors stop playback", re.search(r"onError\(text: String\)[\s\S]{0,400}?playing = false", act) is not None)
    check("a dead station is not 'playing'", 'stateText = "SIN SEÑAL"' in act)
    check("tuning away stops the old stream", "stopPlayback(\"ENTRE EMISORAS\")" in act)
    check("leaving the screen stops audio", "if (playing) stopPlayback" in act)
    check("dial changes go through one path", "it.onChange = { f -> setFreq(f) }" in act)
    check("the menu has no filler entries", "COPIA DE SEGURIDAD" not in act)


# ----------------------------------------------------------------- the web

def test_web_audio():
    js = (ROOT / "web/audio.js").read_text(encoding="utf-8")

    check(
        "the preview does not request CORS",
        "crossOrigin" not in js.replace("NOT setting crossOrigin", ""),
        "real stations do not send Access-Control-Allow-Origin, so CORS makes them mute",
    )
    check("autoplay failure is reported, not hidden", "NotAllowedError" in js)
    check("a dying stream is detected", "'SIN SEÑAL'" in js)
    check("a finished stream is detected", "addEventListener('ended'" in js)
    check("isOn reflects real playback", "el.currentTime > 0" in js)
    check("there is a stall watchdog", "watchdog" in js)


def test_web_screen():
    apps = (ROOT / "web/apps.js").read_text(encoding="utf-8")
    check("the dial names the station under the pointer", "StationMeta(freq)" in apps)
    check("the card shows the player's own state", "RadioAudio.status()" in apps)
    check(
        "no hard-coded station table in the preview",
        "STATIONS = [" not in apps,
        "it must come from web/stations.js",
    )

    index = (ROOT / "web/index.html").read_text(encoding="utf-8")
    order = [index.find(s) for s in ("stations.js", "audio.js", "apps.js")]
    check("index.html loads the catalogue", order[0] != -1)
    check("the catalogue loads before audio.js/apps.js", order[0] < order[1] or order[0] < order[2])

    preview = (ROOT / "web/preview.js").read_text(encoding="utf-8")
    check("the preview mirrors the audio engine", "RadioAudio.isWanted()" in preview)


def main():
    test_catalogue()
    test_generated_matches_json()
    test_player_never_fakes_audio()
    test_activity_is_honest()
    test_web_audio()
    test_web_screen()

    print()
    if FAILS:
        print(f"{len(FAILS)} radio check(s) failed")
        return 1
    print("all radio checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
