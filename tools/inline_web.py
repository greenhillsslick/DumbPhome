#!/usr/bin/env python3
"""Builds a single self-contained copy of the preview.

The repo keeps the scripts as separate files (readable, diffable); this tool
folds them into one HTML so the preview also works where subresource loading is
blocked (sandboxed iframes, file:// with a strict policy, offline sharing).

    python3 tools/inline_web.py            # -> web/dumbos.html
    python3 tools/inline_web.py out.html
"""
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parent.parent / "web"
out_path = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else root / "dumbos.html"

html = (root / "index.html").read_text(encoding="utf-8")

script_re = re.compile(r'<script src="([^"]+)"></script>')


def inline(match: re.Match) -> str:
    src = match.group(1)
    code = (root / src).read_text(encoding="utf-8")
    # A literal </script> inside a string would close the tag early.
    code = code.replace("</script>", "<\\/script>")
    return "<script>\n/* ---- %s ---- */\n%s\n</script>" % (src, code)


html, count = script_re.subn(inline, html)
out_path.write_text(html, encoding="utf-8")
print("inlined %d scripts -> %s (%.1f KB)" % (count, out_path, len(html) / 1024))
