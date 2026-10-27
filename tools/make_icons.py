"""Draws the launcher banner and the app icon (design canvas, variant A) into the app's resources.

Usage: python tools/make_icons.py      (needs Pillow and numpy; run again after changing the look)

Writes app/src/main/res/drawable-xhdpi/banner.png (the 320x180 dp Android TV banner) and
app/src/main/res/mipmap-*/ic_launcher.png. Everything is drawn 4x larger and scaled down, for clean edges.
"""
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
SS = 4  # supersampling
GRAPHITE = (33, 34, 38)
RIM = (255, 255, 255, 18)  # 7% white hairline
TEXT = (248, 250, 255)
MUTED = (163, 163, 173)
FONT = "C:/Windows/Fonts/seguisb.ttf"  # Segoe UI Semibold; the design's Inter is not on Windows by default


def gradient_disc(size):
    """The orb's core: a radial gradient lit from the upper left, #E3E9FF to #8FA4FF to #4D6BFE."""
    y, x = np.mgrid[0:size, 0:size].astype(np.float32) + 0.5
    cx, cy = size * 0.35, size * 0.30
    d = np.sqrt((x - cx) ** 2 + (y - cy) ** 2) / (size * 0.78)
    stops = [(0.0, (227, 233, 255)), (0.42, (143, 164, 255)), (1.0, (77, 107, 254))]
    rgb = np.zeros((size, size, 3), np.float32)
    for c in range(3):
        rgb[..., c] = np.interp(np.clip(d, 0, 1), [s[0] for s in stops], [s[1][c] for s in stops])
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    disc = Image.fromarray(rgb.astype(np.uint8), "RGB").convert("RGBA")
    disc.putalpha(mask)
    return disc


def draw_orb(canvas, cx, cy, diameter, stroke):
    """Core at 72% of the diameter with a blue glow, a ring at the full diameter and a fainter one 44% wider."""
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for scale, alpha in ((1.44, 36), (1.0, 89)):
        r = diameter * scale / 2
        d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=(110, 138, 255, alpha), width=stroke)
    core = int(diameter * 0.72)
    glow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    gr = core / 2 + diameter * 0.08
    ImageDraw.Draw(glow).ellipse((cx - gr, cy - gr, cx + gr, cy + gr), fill=(77, 107, 254, 140))
    glow = glow.filter(ImageFilter.GaussianBlur(diameter * 0.15))
    canvas.alpha_composite(glow)
    canvas.alpha_composite(layer)
    canvas.alpha_composite(gradient_disc(core), (int(cx - core / 2), int(cy - core / 2)))


def banner():
    w, h = 640 * SS, 360 * SS
    img = Image.new("RGBA", (w, h), GRAPHITE + (255,))
    orb = 136 * SS
    pad = 50 * SS
    draw_orb(img, pad + orb / 2, h / 2, orb, 3 * SS)
    x = pad + orb + 41 * SS
    d = ImageDraw.Draw(img)
    # The name large, what it is small under it.
    d.text((x, h / 2 - 2 * SS), "Сфера", font=ImageFont.truetype(FONT, 74 * SS), fill=TEXT, anchor="ls")
    d.text((x + 3 * SS, h / 2 + 58 * SS), "голосовой помощник", font=ImageFont.truetype(FONT, 27 * SS),
           fill=MUTED, anchor="ls")
    out = os.path.join(RES, "drawable-xhdpi", "banner.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.resize((640, 360), Image.LANCZOS).convert("RGB").save(out, optimize=True)
    return out


def icon(px):
    s = px * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    radius = int(s * 0.226)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, s - 1, s - 1), radius=radius, fill=GRAPHITE + (255,), outline=RIM,
                        width=max(SS, s // 168))
    draw_orb(img, s / 2, s / 2, s * 78 / 168, max(SS, int(s * 2 / 168)))
    # The glow and the outer ring stay inside the rounded square.
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, s - 1, s - 1), radius=radius, fill=255)
    img.putalpha(Image.fromarray(np.minimum(np.array(img.getchannel("A")), np.array(mask))))
    return img.resize((px, px), Image.LANCZOS)


def icons():
    written = []
    for folder, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        out = os.path.join(RES, "mipmap-" + folder, "ic_launcher.png")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        icon(px).save(out, optimize=True)
        written.append(out)
    return written


if __name__ == "__main__":
    for path in [banner(), *icons()]:
        print(os.path.normpath(path))
