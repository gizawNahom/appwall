"""Turns "AppWall" in Geist SemiBold into an Android vector drawable (splash branding image).

Regenerate: uv run --with fonttools python tools/wordmark.py app/src/main/res/font/geist_semibold.ttf app/src/main/res/drawable/splash_wordmark.xml
"""
import sys
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

font_path, out_path = sys.argv[1], sys.argv[2]
font = TTFont(font_path)
glyphs, cmap = font.getGlyphSet(), font.getBestCmap()
upm = font['head'].unitsPerEm
W, H, SIZE = 200, 80, 26          # drawable box in dp (Android's branding size), font size in dp
scale = SIZE / upm
text = "AppWall"
names = [cmap[ord(c)] for c in text]
advance = sum(glyphs[n].width for n in names) * scale
cap = font['OS/2'].sCapHeight * scale
x, baseline = (W - advance) / 2, (H + cap) / 2
pen = SVGPathPen(glyphs, ntos=lambda v: ('%.2f' % v).rstrip('0').rstrip('.'))
for n in names:
    glyphs[n].draw(TransformPen(pen, (scale, 0, 0, -scale, x, baseline)))
    x += glyphs[n].width * scale
with open(out_path, 'w') as f:
    f.write(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- "AppWall" wordmark in Geist SemiBold, as outlines (splash screens can't use fonts). Generated. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{W}dp"
    android:height="{H}dp"
    android:viewportWidth="{W}"
    android:viewportHeight="{H}">
    <path android:fillColor="@color/text" android:pathData="{pen.getCommands()}" />
</vector>
''')
print("advance %.1f dp, cap height %.1f dp" % (advance, cap))
