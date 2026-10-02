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
  lossy-alpha.webp  a lossy (VP8) WebP with a losslessly compressed alpha channel (ALPH), 96 x 64:
                 the bottom right quadrant fully transparent (issue #207, the cost of its decode).
  exif.webp      a lossy WebP whose raster is stored on its side (64 x 96) with an EXIF chunk:
                 orientation 6 (turn a quarter clockwise to show), DateTimeOriginal
                 2024:05:17 14:30:00 at OffsetTimeOriginal +02:00, Make "Fixture",
                 Model "Fixture WebP", GPS 48 07'30" N 11 34'12" E; shown 96 x 64.
  animated.webp  an animated lossless WebP of two frames, 96 x 64: the quadrants, then magenta.
  still.gif      a GIF, 96 x 64, the quadrants (exact palette colours).
  animated.gif   a GIF of two frames, 96 x 64: the quadrants, then magenta.
  offset.gif     a GIF whose logical screen is 96 x 64 and whose only frame is 48 x 32 at the
                 offset (32, 16) (issue #207): the frame's own quadrants red, green, blue, and its
                 bottom right quadrant transparent (palette index 3, yellow); the background colour
                 index names magenta.  Shown: white around the frame and in its transparent corner.
  offset.webp    an animated lossless WebP whose canvas is 96 x 64 and whose first frame is the
                 48 x 32 picture of offset.gif at the same offset (32, 16), transparent corner
                 included; its second frame, magenta, at (0, 0) (issue #207).
  lossless-4mp.webp  a lossless WebP, 2400 x 1600, the quadrants with a patch of noise in the
                 middle (issue #207): a small file whose decoder holds the whole raster, about
                 19 MB of heap, for the memory rule of PictureReader.
  portrait.webp  ../faces/portrait-b.jpg (a public-domain painting, see ../faces/README.md)
                 re-encoded as a lossy WebP, for the face index.
"""

import io
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

NOISE = 128

FRAME_WIDTH = 48
FRAME_HEIGHT = 32
FRAME_LEFT = 32
FRAME_TOP = 16


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


def offset_gif():
    """A GIF whose first frame is smaller than its logical screen, see issue #207.

    Pillow writes a frame at (0, 0) of a screen of its own size, so the frame is written by Pillow
    and the logical screen descriptor and the image descriptor are patched afterwards.
    """
    frame = Image.new("P", (FRAME_WIDTH, FRAME_HEIGHT))
    palette = [RED, GREEN, BLUE, YELLOW, MAGENTA]
    frame.putpalette([c for colour in palette for c in colour] + [0] * (3 * (256 - len(palette))))
    for y in range(FRAME_HEIGHT):
        for x in range(FRAME_WIDTH):
            left = x < FRAME_WIDTH // 2
            top = y < FRAME_HEIGHT // 2
            frame.putpixel((x, y), (0 if left else 1) if top else (2 if left else 3))
    out = io.BytesIO()
    frame.save(out, "GIF", transparency=3, background=4, optimize=False)
    data = bytearray(out.getvalue())
    data[6:8] = WIDTH.to_bytes(2, "little")
    data[8:10] = HEIGHT.to_bytes(2, "little")
    data[11] = 4  # the background colour index: magenta, which is never to be seen
    flags = data[10]
    pos = 13 + (3 * 2 ** ((flags & 7) + 1) if flags & 0x80 else 0)
    while data[pos] == 0x21:  # an extension: label, then sub-blocks up to an empty one
        pos += 2
        while data[pos]:
            pos += data[pos] + 1
        pos += 1
    assert data[pos] == 0x2C, "an image descriptor"
    data[pos + 1:pos + 3] = FRAME_LEFT.to_bytes(2, "little")
    data[pos + 3:pos + 5] = FRAME_TOP.to_bytes(2, "little")
    return bytes(data)


def offset_frame_rgba():
    """The frame of offset.gif as RGBA: red, green, blue and a transparent (yellow) quadrant."""
    frame = Image.new("RGBA", (FRAME_WIDTH, FRAME_HEIGHT))
    for y in range(FRAME_HEIGHT):
        for x in range(FRAME_WIDTH):
            left = x < FRAME_WIDTH // 2
            top = y < FRAME_HEIGHT // 2
            if top:
                frame.putpixel((x, y), (RED if left else GREEN) + (255,))
            else:
                frame.putpixel((x, y), (BLUE + (255,)) if left else (YELLOW + (0,)))
    return frame


def offset_webp():
    """An animated WebP whose first frame is smaller than its canvas, see issue #207.

    Pillow writes the frames on a canvas of their own size, so the canvas of the VP8X chunk and the
    offset of the first ANMF chunk are patched afterwards.
    """
    out = io.BytesIO()
    offset_frame_rgba().save(out, "WEBP", lossless=True, exact=True, save_all=True,
                             append_images=[Image.new("RGBA", (FRAME_WIDTH, FRAME_HEIGHT), MAGENTA + (255,))],
                             duration=500, loop=0)
    data = bytearray(out.getvalue())
    pos = 12
    first = True
    while pos + 8 <= len(data):
        fourcc = bytes(data[pos:pos + 4])
        size = int.from_bytes(data[pos + 4:pos + 8], "little")
        payload = pos + 8
        if fourcc == b"VP8X":
            data[payload + 4:payload + 7] = (WIDTH - 1).to_bytes(3, "little")
            data[payload + 7:payload + 10] = (HEIGHT - 1).to_bytes(3, "little")
        elif fourcc == b"ANMF" and first:
            data[payload:payload + 3] = (FRAME_LEFT // 2).to_bytes(3, "little")
            data[payload + 3:payload + 6] = (FRAME_TOP // 2).to_bytes(3, "little")
            first = False
        pos = payload + size + (size & 1)
    assert not first, "an ANMF chunk"
    return bytes(data)


def path(name):
    return os.path.join(HERE, name)


def main():
    upright = quadrants().convert("RGB")
    magenta = Image.new("RGB", (WIDTH, HEIGHT), MAGENTA)

    upright.save(path("lossy.webp"), "WEBP", quality=90, method=6)

    quadrants(transparent_corner=True).save(path("alpha.webp"), "WEBP", lossless=True, exact=True)

    quadrants(transparent_corner=True).save(path("lossy-alpha.webp"), "WEBP", quality=90, method=6)

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

    with open(path("offset.gif"), "wb") as out:
        out.write(offset_gif())

    with open(path("offset.webp"), "wb") as out:
        out.write(offset_webp())

    large = upright.resize((2400, 1600), Image.Resampling.NEAREST)
    # A patch of noise in the middle: TwelveMonkeys refuses a WebP whose raster is more than 2048
    # times its file, and the quadrants alone compress below that.
    seed = 207
    noise = bytearray()
    for _ in range(NOISE * NOISE * 3):
        seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF
        noise.append(seed >> 23)
    large.paste(Image.frombytes("RGB", (NOISE, NOISE), bytes(noise)), ((2400 - NOISE) // 2, (1600 - NOISE) // 2))
    large.save(path("lossless-4mp.webp"), "WEBP", lossless=True, method=6)

    with Image.open(os.path.join(HERE, "..", "faces", "portrait-b.jpg")) as portrait:
        portrait.convert("RGB").save(path("portrait.webp"), "WEBP", quality=85, method=6)


if __name__ == "__main__":
    main()
