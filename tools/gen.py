#!/usr/bin/env python3
"""Generate shared widget sources for dumbOS.

  * app/src/main/java/com/dumbos/ui/PixelWidgets.kt   (Android / Kotlin)
  * web/pixel-widgets.js                              (browser preview)

Both are generated from tools/font_data.py + tools/icon_data.py so the
launcher, the bundled apps and the interactive preview always share one
bitmap font and one icon set. Run:  python3 tools/gen.py
"""
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

import importlib.util

def _load(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, name + '.py'))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod

font = _load('font_data')
icons = _load('icon_data')

BIG = font.BIG
SMALL = font.SMALL
SYM = font.SYMBOL_GLYPHS
ICONS = icons.ICONS


def kt_str(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'


def emit_big_map(name, data, indent='    '):
    lines = [f'val {name}: Map<String, Array<String>> = mapOf(']
    for ch in sorted(data.keys()):
        rows = data[ch]
        lines.append(indent + kt_str(ch) + ' to arrayOf(' + ', '.join(kt_str(r) for r in rows) + '),')
    lines.append(')')
    return '\n'.join(lines)


KT_HEADER = '''// GENERATED FILE - do not edit by hand.
// Source of truth: tools/font_data.py, tools/icon_data.py, tools/gen.py
package com.dumbos.ui

// The 5x7 dot-matrix font and the 16x16 app icons that give dumbOS its face.
'''

KT = KT_HEADER + '\n'

KT += emit_big_map('BIG_GLYPHS', BIG) + '\n\n'
KT += emit_big_map('SMALL_GLYPHS', SMALL) + '\n\n'
KT += emit_big_map('SYMBOL_GLYPHS', SYM) + '\n\n'
KT += emit_big_map('ICON_GLYPHS', ICONS) + '\n\n'

KT += r'''
/** Folds Spanish accented capitals onto the base glyph so text never breaks. */
val ACCENT_FOLD: Map<Char, Char> = mapOf(
    'Á' to 'A', 'É' to 'E', 'Í' to 'I', 'Ó' to 'O', 'Ú' to 'U', 'Ü' to 'U',
    'À' to 'A', 'È' to 'E', 'Ì' to 'I', 'Ò' to 'O', 'Ù' to 'U',
    'Â' to 'A', 'Ê' to 'E', 'Î' to 'I', 'Ô' to 'O', 'Û' to 'U',
    'Ä' to 'A', 'Ë' to 'E', 'Ï' to 'I', 'Ö' to 'O',
    'Ç' to 'C', 'Ñ' to 'N',
    'á' to 'A', 'é' to 'E', 'í' to 'I', 'ó' to 'O', 'ú' to 'U', 'ü' to 'U',
    'à' to 'A', 'è' to 'E', 'ì' to 'I', 'ò' to 'O', 'ù' to 'U', 'ñ' to 'N',
    'ç' to 'C', 'â' to 'A', 'ê' to 'E', 'î' to 'I', 'ô' to 'O', 'û' to 'U',
    'ä' to 'A', 'ë' to 'E', 'ï' to 'I', 'ö' to 'O'
)

// Symbol aliases usable inside text, e.g. "[LEFT] BACK" or "[CHECK] OK".
// U+0001..U+0018 map to SYMBOL_GLYPHS entries; the trailing space glyph keeps
// them legible inside the source while keeping them zero-width at draw time.
val GLYPH_ALIASES: Map<String, String> = mapOf(
    "LEFT" to "\u0001", "RIGHT" to "\u0002", "UP" to "\u0003", "DOWN" to "\u0004",
    "CHECK" to "\u0005", "CROSS" to "\u0006", "DOT" to "\u0007", "BULLET" to "\u0008",
    "BARS" to "\u0009", "MUSIC" to "\u000A", "PHONE" to "\u000B", "ENVELOPE" to "\u000C",
    "PENCIL" to "\u000D", "GLOBE" to "\u000E", "BELL" to "\u000F", "LOCK" to "\u0010",
    "STAR" to "\u0011", "HEART" to "\u0012", "ANTENNA" to "\u0013", "BATTERY" to "\u0014",
    "SIGNAL" to "\u0015", "RADIO" to "\u0016", "GEAR" to "\u0017", "SPEAKER" to "\u0018",
    "MOON" to "\u0019"
)

val SYMBOL_ORDER: List<String> = listOf(
    "ARROW_LEFT", "ARROW_RIGHT", "ARROW_UP", "ARROW_DOWN",
    "CHECK", "CROSS", "DOT", "BULLET", "BARS", "MUSIC",
    "PHONE", "ENVELOPE", "PENCIL", "GLOBE", "BELL", "LOCK",
    "STAR", "HEART", "ANTENNA", "BATTERY", "SIGNAL", "RADIO", "GEAR",
    "SPEAKER", "MOON"
)

fun symbolGlyph(ch: Char): Array<String>? {
    val idx = ch.code - 1
    if (idx < 0 || idx >= SYMBOL_ORDER.size) return null
    return SYMBOL_GLYPHS[SYMBOL_ORDER[idx]]
}
'''

# --------------------------------------------------------------------------
# Web (JS) twin
# --------------------------------------------------------------------------
def js_map(name, data):
    out = [f'const {name} = {{']
    for ch in sorted(data.keys()):
        key = ch
        if ch == "'":
            key = "\\'"
        elif ch == '\\':
            key = '\\\\'
        out.append(f"  '{key}': [{', '.join(repr(r) for r in data[ch])}],")
    out.append('};')
    return '\n'.join(out)


JS = '''// GENERATED FILE - do not edit by hand.
// Source of truth: tools/font_data.py, tools/icon_data.py, tools/gen.py
''' + js_map('BIG_GLYPHS', BIG) + '\n\n' + js_map('SMALL_GLYPHS', SMALL) + '\n\n' \
    + js_map('SYMBOL_GLYPHS', SYM) + '\n\n' + js_map('ICON_GLYPHS', ICONS) + r'''

const ACCENT_FOLD = {
  'Á':'A','É':'E','Í':'I','Ó':'O','Ú':'U','Ü':'U','À':'A','È':'E','Ì':'I','Ò':'O','Ù':'U',
  'Â':'A','Ê':'E','Î':'I','Ô':'O','Û':'U','Ä':'A','Ë':'E','Ï':'I','Ö':'O','Ç':'C','Ñ':'N',
  'á':'A','é':'E','í':'I','ó':'O','ú':'U','ü':'U','à':'A','è':'E','ì':'I','ò':'O','ù':'U',
  'ñ':'N','ç':'C','â':'A','ê':'E','î':'I','ô':'O','û':'U','ä':'A','ë':'E','ï':'I','ö':'O'
};

const SYMBOL_ORDER = ['ARROW_LEFT','ARROW_RIGHT','ARROW_UP','ARROW_DOWN','CHECK','CROSS','DOT',
  'BULLET','BARS','MUSIC','PHONE','ENVELOPE','PENCIL','GLOBE','BELL','LOCK','STAR','HEART',
  'ANTENNA','BATTERY','SIGNAL','RADIO','GEAR','SPEAKER','MOON'];

const GLYPH_ALIASES = {
  LEFT:'\u0001', RIGHT:'\u0002', UP:'\u0003', DOWN:'\u0004', CHECK:'\u0005', CROSS:'\u0006',
  DOT:'\u0007', BULLET:'\u0008', BARS:'\u0009', MUSIC:'\u000A', PHONE:'\u000B', ENVELOPE:'\u000C',
  PENCIL:'\u000D', GLOBE:'\u000E', BELL:'\u000F', LOCK:'\u0010', STAR:'\u0011', HEART:'\u0012',
  ANTENNA:'\u0013', BATTERY:'\u0014', SIGNAL:'\u0015', RADIO:'\u0016', GEAR:'\u0017',
  SPEAKER:'\u0018', MOON:'\u0019'
};

function symbolGlyph(ch) {
  const i = ch.charCodeAt(0) - 1;
  if (i < 0 || i >= SYMBOL_ORDER.length) return null;
  return SYMBOL_GLYPHS[SYMBOL_ORDER[i]];
}
'''

os.makedirs(os.path.join(ROOT, 'app/src/main/java/com/dumbos/ui'), exist_ok=True)
os.makedirs(os.path.join(ROOT, 'web'), exist_ok=True)
with open(os.path.join(ROOT, 'app/src/main/java/com/dumbos/ui/PixelWidgets.kt'), 'w') as f:
    f.write(KT)
with open(os.path.join(ROOT, 'web/pixel-widgets.js'), 'w') as f:
    f.write(JS)

print('big glyphs   ', len(BIG))
print('small glyphs ', len(SMALL))
print('symbols      ', len(SYM))
print('icons        ', len(ICONS))
print('wrote PixelWidgets.kt and pixel-widgets.js')
