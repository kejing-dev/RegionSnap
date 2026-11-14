#!/usr/bin/env python3
"""Sample camera frames for the emulator (design verification: whiteboard / A4 / nameplate, each with a known quad).

    python -I tools/make_samples.py   -> app/src/main/assets/samples/<id>.jpg + <id>.txt (TL TR BR BL pixels)

Each picture is a flat card with asymmetric marks (an "F" and an up arrow, D10: catches mirroring and upside-down output)
warped into the frame by a known homography, on a plain room-like backdrop. 640 x 480 like the default capture size.
"""
import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "samples")
W, H = 640, 480


def font(size):
    for f in ("C:/Windows/Fonts/arialbd.ttf", "C:/Windows/Fonts/arial.ttf"):
        if os.path.exists(f):
            return ImageFont.truetype(f, size)
    return ImageFont.load_default()


def card(kind):
    if kind == "whiteboard":
        im = Image.new("RGB", (900, 600), (246, 247, 244))
        d = ImageDraw.Draw(im)
        d.rectangle([0, 0, 899, 599], outline=(120, 124, 128), width=14)
        d.text((60, 50), "F", fill=(200, 40, 40), font=font(150))
        d.polygon([(780, 60), (840, 160), (720, 160)], fill=(30, 90, 200))
        d.rectangle([765, 160, 795, 260], fill=(30, 90, 200))
        for i, line in enumerate(["Q3 plan", "- ship v1", "- user tests", "- 20 pins max"]):
            d.text((240, 80 + i * 110), line, fill=(30, 30, 30), font=font(70))
        return im
    if kind == "a4":
        im = Image.new("RGB", (595, 842), (252, 252, 250))
        d = ImageDraw.Draw(im)
        d.text((40, 30), "F", fill=(200, 40, 40), font=font(110))
        d.polygon([(520, 40), (560, 110), (480, 110)], fill=(30, 90, 200))
        for i in range(14):
            d.rectangle([60, 190 + i * 44, 60 + (480 if i % 4 else 300), 210 + i * 44], fill=(60, 60, 60))
        return im
    im = Image.new("RGB", (800, 360), (190, 194, 198))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 799, 359], outline=(90, 94, 98), width=10)
    d.text((40, 30), "F", fill=(200, 40, 40), font=font(120))
    d.text((200, 60), "MODEL RS-42", fill=(20, 20, 20), font=font(64))
    d.text((200, 170), "SN 2026-1110", fill=(20, 20, 20), font=font(52))
    d.polygon([(720, 40), (760, 110), (680, 110)], fill=(30, 90, 200))
    return im


def perspective_coeffs(out_pts, in_pts):
    a = []
    b = []
    for (x, y), (u, v) in zip(out_pts, in_pts):
        a.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        a.append([0, 0, 0, x, y, 1, -v * x, -v * y])
        b += [u, v]
    # Gaussian elimination
    n = 8
    m = [row + [bb] for row, bb in zip(a, b)]
    for c in range(n):
        p = max(range(c, n), key=lambda r: abs(m[r][c]))
        m[c], m[p] = m[p], m[c]
        for r in range(n):
            if r != c:
                f = m[r][c] / m[c][c]
                for k in range(c, n + 1):
                    m[r][k] -= f * m[c][k]
    return [m[i][n] / m[i][i] for i in range(n)]


QUADS = {
    "whiteboard": [(120, 92), (538, 120), (520, 372), (104, 392)],
    "a4": [(228, 70), (432, 86), (446, 420), (214, 410)],
    "plate": [(150, 170), (496, 150), (508, 318), (158, 322)],
}


def main():
    os.makedirs(OUT, exist_ok=True)
    for kind, quad in QUADS.items():
        bg = Image.new("RGB", (W, H), (126, 118, 104))
        d = ImageDraw.Draw(bg)
        for y in range(H):
            t = y / H
            d.line([(0, y), (W, y)], fill=(int(150 - 40 * t), int(140 - 38 * t), int(124 - 34 * t)))
        c = card(kind)
        cw, ch = c.size
        src = [(0, 0), (cw, 0), (cw, ch), (0, ch)]
        k = perspective_coeffs(quad, src)
        warped = c.transform((W, H), Image.PERSPECTIVE, k, Image.BICUBIC)
        mask = Image.new("L", (cw, ch), 255).transform((W, H), Image.PERSPECTIVE, k, Image.BICUBIC)
        bg.paste(warped, (0, 0), mask)
        bg.save(os.path.join(OUT, kind + ".jpg"), quality=92)
        with open(os.path.join(OUT, kind + ".txt"), "w") as f:
            f.write(" ".join("%d,%d" % p for p in quad) + "\n")
        print(kind, quad)


if __name__ == "__main__":
    main()
