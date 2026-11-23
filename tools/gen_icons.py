#!/usr/bin/env python3
"""Render launcher icons (mipmaps + per-app drawables) from the 16x16 art.

dumbOS icons are pixel art on the phosphor screen, so they are generated at
every density with nearest-neighbour scaling and a 1px-ish chunky frame.
"""
import os
import importlib.util

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

spec = importlib.util.spec_from_file_location('ic', os.path.join(HERE, 'icon_data.py'))
ic = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ic)
ICONS = ic.ICONS

BG = (11, 20, 8, 255)
INK = (155, 255, 107, 255)

DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}

APPS = {
    'ic_app_phone': 'PHONE',
    'ic_app_sms': 'SMS',
    'ic_app_mail': 'MAIL',
    'ic_app_notes': 'NOTES',
    'ic_app_browser': 'BROWSER',
    'ic_app_whatsapp': 'WHATSAPP',
    'ic_app_radio': 'RADIO',
    'ic_app_alarm': 'ALARM',
    'ic_app_settings': 'SETTINGS',
    'ic_launcher_foreground': 'OS',
}


def make_os_icon():
    """dumbOS mark: a 3310-ish candybar phone, 16x16."""
    return [
        "................",
        "................",
        "..############..",
        "..#..........#..",
        "..#.########.#..",
        "..#.########.#..",
        "..#.########.#..",
        "..#..........#..",
        "..#..........#..",
        "..#.##.##.##.#..",
        "..#.##.##.##.#..",
        "..#..........#..",
        "..#..........#..",
        "..############..",
        "................",
        "................",
    ]


ICONS['OS'] = make_os_icon()
assert len(ICONS['OS']) == 16


def render(rows, size, ink=INK, bg=BG, frame=True):
    """Nearest-neighbour 16x16 -> size, optionally framed like an app icon."""
    art = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    px = art.load()
    for y, row in enumerate(rows):
        for x, c in enumerate(row):
            if c == '#':
                px[x, y] = ink
    if frame:
        framed = Image.new('RGBA', (16, 16), bg)
        framed.alpha_composite(art)
        # pixel border
        fp = framed.load()
        for i in range(16):
            for (x, y) in ((i, 0), (i, 15), (0, i), (15, i)):
                if fp[x, y] == bg:
                    fp[x, y] = (30, 52, 24, 255)
        art = framed
    return art.resize((size, size), Image.NEAREST)


def main():
    for dens, mult in DENSITIES.items():
        d = os.path.join(ROOT, 'app/src/main/res/mipmap-' + dens)
        os.makedirs(d, exist_ok=True)
        for name in ('ic_launcher', 'ic_launcher_round'):
            size = int(48 * mult)
            img = render(ICONS['OS'], size)
            if name.endswith('round'):
                # round mask
                mask = Image.new('L', (size, size), 0)
                from PIL import ImageDraw
                ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
                img.putalpha(mask)
            img.save(os.path.join(d, name + '.png'))

        d = os.path.join(ROOT, 'app/src/main/res/drawable-' + dens)
        os.makedirs(d, exist_ok=True)
        for res, icon in APPS.items():
            if icon == 'OS':
                continue
            size = int(32 * mult)
            render(ICONS[icon], size).save(os.path.join(d, res + '.png'))

    # adaptive icon foreground: 108dp canvas, art at 60% centred
    for dens, mult in DENSITIES.items():
        d = os.path.join(ROOT, 'app/src/main/res/mipmap-' + dens)
        size = int(108 * mult)
        art = render(ICONS['OS'], int(size * 0.52), frame=False)
        canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        canvas.alpha_composite(art, ((size - art.width) // 2, (size - art.height) // 2))
        canvas.save(os.path.join(d, 'ic_launcher_foreground.png'))

    # adaptive icon XML + web icon preview
    anydpi = os.path.join(ROOT, 'app/src/main/res/mipmap-anydpi-v26')
    os.makedirs(anydpi, exist_ok=True)
    xml = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/icon_bg" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
'''
    for name in ('ic_launcher.xml', 'ic_launcher_round.xml'):
        with open(os.path.join(anydpi, name), 'w') as f:
            f.write(xml)

    drawable = os.path.join(ROOT, 'app/src/main/res/drawable')
    os.makedirs(drawable, exist_ok=True)
    with open(os.path.join(drawable, 'bg_icon.xml'), 'w') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<shape xmlns:android="http://schemas.android.com/apk/res/android">\n    <solid android:color="@color/icon_bg" />\n</shape>\n')
    values = os.path.join(ROOT, 'app/src/main/res/values')
    os.makedirs(values, exist_ok=True)
    with open(os.path.join(values, 'colors.xml'), 'w') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="icon_bg">#0B1408</color>\n</resources>\n')

    # store icon for the README / web preview
    media = os.path.join(ROOT, 'media')
    os.makedirs(media, exist_ok=True)
    render(ICONS['OS'], 128).save(os.path.join(media, 'dumbos-icon.png'))
    print('icons rendered')


if __name__ == '__main__':
    main()
