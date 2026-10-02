#!/usr/bin/env python3
"""Writes the WebP and GIF fixtures of issue #190.

Every picture is generated: four coloured quadrants (top left red, top right green, bottom left
blue, bottom right yellow) of the picture *as it is to be seen*, so that a corner probe tells a
wrong turn, a wrong frame and a wrong background apart.  Needs nothing but Pillow with WebP
support (`python3 -c "from PIL import features; print(features.check('webp'))"`).  Run from
anywhere:

    python3 image-server/src/test/fixtures/webp-gif/generate.py

What is written (see README.md):

  lossy.webp     a lossy (VP8) WebP, 96 x 64, no metadata.
  alpha.webp     a lossless (VP8L) WebP with alpha, 96 x 64: the bottom right quadrant is fully
                 transparent (its colour channels yellow, kept by `exact`), the other three opaque.
  exif.webp      a lossy WebP whose raster is stored on its side (64 x 96) with an EXIF chunk:
                 orientation 6 (turn a quarter clockwise to show), DateTimeOriginal
                 2024:05:17 14:30:00 at OffsetTimeOriginal +02:00, Make "Fixture",
                 Model "Fixture WebP", GPS 48 07'30" N 11 34'12" E; shown 96 x 64.
  animated.webp  an animated lossless WebP of two frames, 96 x 64: the quadrants, then magenta.
  still.gif      a GIF, 96 x 64, the quadrants (exact palette colours).
  animated.gif   a GIF of two frames, 96 x 64: the quadrants, then magenta.
  portrait.webp  ../faces/portrait-b.jpg (a public-domain painting, see ../faces/README.md)
                 re-encoded as a lossy WebP, for the face index.
"""

import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))

RED = (255, 0, 0)
GREEN = (0, 255, 0)
BLUE = (0, 0, 255)
YELLOW = (255, 255, 0)
MAGENTA = (255, 0, 255)

WIDTH = 96
HEIGHT = 64


def quadrants(transparent_corner=False):
    """The shown picture, RGBA."""
    image = Image.new("RGBA", (WIDTH, HEIGHT))
    for y in range(HEIGHT):
        for x in range(WIDTH):
            left = x < WIDTH // 2
            top = y < HEIGHT // 2
            if top:
                colour = RED if left else GREEN
            else:
                colour = BLUE if left else YELLOW
            alpha = 0 if transparent_corner and not top and not left else 255
            image.putpixel((x, y), colour + (alpha,))
    return image


def exif_block():
    exif = Image.Exif()
    exif[0x010F] = "Fixture"
    exif[0x0110] = "Fixture WebP"
    exif[0x0112] = 6
    # Assigned as dictionaries: Pillow writes a sub-IFD only where the tag holds one.
    exif[0x8769] = {0x9003: "2024:05:17 14:30:00", 0x9011: "+02:00"}
    exif[0x8825] = {0: b"\x02\x02\x00\x00", 1: "N", 2: (48.0, 7.0, 30.0), 3: "E", 4: (11.0, 34.0, 12.0)}
    return exif.tobytes()  # Pillow strips the "Exif\0\0" a WebP chunk does not carry


def path(name):
    return os.path.join(HERE, name)


def main():
    upright = quadrants().convert("RGB")
    magenta = Image.new("RGB", (WIDTH, HEIGHT), MAGENTA)

    upright.save(path("lossy.webp"), "WEBP", quality=90, method=6)

    quadrants(transparent_corner=True).save(path("alpha.webp"), "WEBP", lossless=True, exact=True)

    # Orientation 6 shows the raster turned a quarter clockwise, so the raster is the shown
    # picture turned a quarter anti-clockwise.
    upright.transpose(Image.Transpose.ROTATE_90).save(
        path("exif.webp"), "WEBP", quality=90, method=6, exif=exif_block())

    upright.save(path("animated.webp"), "WEBP", lossless=True, save_all=True,
                 append_images=[magenta], duration=500, loop=0)

    palette = [RED, GREEN, BLUE, YELLOW, MAGENTA]
    flat = [c for colour in palette for c in colour] + [0] * (3 * (256 - len(palette)))
    template = Image.new("P", (1, 1))
    template.putpalette(flat)

    def indexed(picture):
        return picture.quantize(palette=template, dither=Image.Dither.NONE)

    indexed(upright).save(path("still.gif"), "GIF")
    indexed(upright).save(path("animated.gif"), "GIF", save_all=True,
                          append_images=[indexed(magenta)], duration=500, loop=0, disposal=1)

    with Image.open(os.path.join(HERE, "..", "faces", "portrait-b.jpg")) as portrait:
        portrait.convert("RGB").save(path("portrait.webp"), "WEBP", quality=85, method=6)


if __name__ == "__main__":
    main()
