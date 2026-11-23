#!/usr/bin/env python3
"""Guards the two drawing invariants that broke the app on a real phone.

1. ALPHA_8 masks: setPixels() takes ARGB and keeps only the alpha channel, so a
   glyph pixel must be written as 0xFF000000. Writing 255 (0x000000FF) makes
   every glyph fully transparent: rectangles still draw, text silently vanishes.
2. measure() and draw() must advance the same width for every character, or
   centred and right-aligned text drifts into its neighbours.

    python3 tools/test_pixels.py
"""
import re
import sys

CORE = "app/src/main/java/com/dumbos/ui/Core.kt"
failures = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print(("ok   " if ok else "FAIL ") + name + ((" — " + detail) if detail and not ok else ""))
    if not ok:
        failures.append(name)


src = open(CORE, encoding="utf-8").read()

# 1. the ALPHA_8 mask must be written with an opaque ARGB value
mask_block = src[src.index("fun glyph("):]
mask_block = mask_block[: mask_block.index("return bmp")]
uses_opaque = "OPAQUE" in mask_block or "0xFF shl 24" in mask_block
writes_255 = re.search(r"=\s*255\b", mask_block) is not None
check("glyph mask uses an opaque ARGB value", uses_opaque and not writes_255,
      "found a literal 255 as the glyph pixel value")

# simulate the arithmetic for both variants, as a behaviour check
def visible(opaque: int) -> int:
    lit = 0
    for ch in ".###.#...#####":
        argb = opaque if ch == "#" else 0
        if ((argb >> 24) & 0xFF) > 0:
            lit += 1
    return lit

check("0xFF000000 yields visible pixels",
      visible(0xFF << 24) == ".###.#...#####".count("#"),
      "opaque value produced invisible pixels")
check("255 yields no visible pixels (the old bug)", visible(255) == 0)

# 2. measure() must not have the special-case advance that draw() no longer uses
measure_fn = src[src.index("fun measure("):]
measure_fn = measure_fn[: measure_fn.index("\n    }")]
check("measure() advances the same width as draw()",
      "size.adv - 1" not in measure_fn,
      "measure() still special-cases missing glyphs")

# 3. every glyph the renderer can emit must exist in the generated maps
sys.path.insert(0, "tools")
import font_data  # noqa: E402

have = set(font_data.BIG) | set(font_data.SMALL) | set(font_data.SYMBOL_GLYPHS)
ui_strings = []
for path in ("app/src/main/java",):
    import glob
    for f in glob.glob(path + "/**/*.kt", recursive=True):
        for m in re.finditer(r'"([A-Z0-9 .,:;!?%+\-/()=<>\[\]_\'"#*|~&@^$]+)"', open(f, encoding="utf-8").read()):
            ui_strings.append(m.group(1))
missing = {c for s in ui_strings for c in s if not c.isdigit() and c != " " and c not in have}
check("all UI literals use glyphs the font has", not missing, f"missing: {sorted(missing)}")

if failures:
    print(f"{len(failures)} check(s) failed")
    sys.exit(1)
print("all pixel checks passed")
