#!/usr/bin/env python3
"""Writes the release body for a tag from the repository itself.

    python3 tools/release_notes.py v1.0.0 > notes.md

Numbers (glyph counts, app count, icon count) come from the generators, so the
notes cannot drift from the code.
"""
import re
import subprocess
import sys

TAG = sys.argv[1] if len(sys.argv) > 1 else None


def sh(*args: str) -> str:
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout.strip()


def commit_subjects() -> list[str]:
    try:
        raw = sh("git", "log", "--no-merges", "--pretty=%s", "main..HEAD")
    except subprocess.CalledProcessError:
        raw = sh("git", "log", "--no-merges", "--pretty=%s", "-20")
    # Maintenance commits (ci:, docs:, chore:) are not user-facing changes.
    noise = ("ci:", "docs:", "chore:", "test:", "style:")
    return [
        line for line in raw.splitlines()
        if line.strip() and not line.lower().startswith(noise)
    ]


sys.path.insert(0, "tools")
import font_data  # noqa: E402
import icon_data  # noqa: E402

# Only <activity> labels; the notification listener also uses an app_* label.
manifest = open("app/src/main/AndroidManifest.xml", encoding="utf-8").read()
apps = re.findall(r'<activity[^>]*?android:label="@string/app_(\w+)"', manifest, re.S)
labels = dict(re.findall(
    r'<string name="app_(\w+)">([^<]+)</string>',
    open("app/src/main/res/values/strings.xml", encoding="utf-8").read(),
))
# The drawer alias to the home screen is not a separate app.
# Activities that are entry points, not separate apps in the drawer.
app_labels = [labels.get(a, a) for a in apps if a != "home"]
version = re.search(
    r'versionName = "([^"]+)"', open("app/build.gradle.kts", encoding="utf-8").read()
).group(1)

TAG = TAG or f"v{version}"
lines = [
    f"# dumbOS {version}",
    "",
    "Instala, elige dumbOS como aplicación de inicio y el móvil se queda con lo justo.",
    "",
    "## Qué incluye",
    "",
    f"- **El motor de dibujo**: fuente de mapa de bits "
    f"({len(font_data.BIG)} glifos de 5×7, {len(font_data.SMALL)} de 4×6) y "
    f"**{len(icon_data.ICONS)} iconos** de 16×16 dibujados píxel a píxel.",
    f"- **{len(app_labels)} apps**: " + ", ".join(app_labels[:-1]) + " y " + app_labels[-1] + ".",
    "- Launcher completo: intent HOME, lista de apps del sistema y búsqueda.",
    "- Cinco paletas monocromas (fósforo, ámbar, hielo, papel y monocromo).",
    "- Tres modos de notificación: SILENCIO, NO MOLESTAR y TODO.",
    "- La radio suena de verdad: 14 emisoras por internet, decodificadas por la propia app.",
    "- Cero dependencias: solo APIs de Android.",
    "",
    "## Instalación",
    "",
    "1. Descarga `dumbOS-%s.apk` de los ficheros de abajo." % TAG,
    "2. Ábrelo en el móvil (Android 8 o superior) y acepta instalar desde el navegador.",
    "3. **Ajustes → Aplicaciones → Aplicación de inicio → dumbOS**.",
    "",
    "La clave de firma está en el repositorio (`signing/`), así que las versiones futuras "
    "se instalan encima sin desinstalar.",
    "",
    "## La radio, sin mentir",
    "",
    "Android no deja que una app encienda la antena de FM: no hay API pública ni clases en "
    "`android.jar`, así que ninguna APK puede sintonizar FM de verdad. dumbOS usa el dial para "
    "sintonizar emisoras reales por internet y las decodifica él mismo "
    "(`HttpURLConnection` → `MediaCodec` → `AudioTrack`). Si una emisora no responde, la pantalla "
    "dice **SIN SEÑAL** y no suena nada: no hay estática grabada para aparentar señal.",
    "",
    "## Comprobación",
    "",
    "```",
    "sha256sum -c SHA256SUMS.txt",
    "```",
    "",
    "## Cambios",
    "",
]
subjects = commit_subjects()
lines += [f"- {s}" for s in subjects] or ["- Versión inicial."]
lines += [
    "",
    f"**Changelog completo**: "
    f"https://github.com/greenhillsslick/DumbPhome/commits/{TAG}",
]
print("\n".join(lines))
