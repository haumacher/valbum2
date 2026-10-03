#!/usr/bin/env python3
"""Writes the JPEG XL fixtures of issue #193.

Every picture is generated: four coloured quadrants (top left red, top right green, bottom left
blue, bottom right yellow) of the picture *as it is to be seen*.  The codestreams are made by
libjxl's `cjxl` from a PNG Pillow writes; the container (ISO/IEC 18181-2) is written here box by
box, so that each file carries exactly the metadata boxes a test needs.

Needs `cjxl` (Debian/Ubuntu package `libjxl-tools`; `CJXL=/path/to/cjxl` overrides the PATH),
Pillow and the Python `brotli` module.  Run from anywhere:

    python3 image-server/src/test/fixtures/jxl/generate.py

What is written (all but `bare.jxl` carry EXIF DateTimeOriginal 2024:05:17 14:30:00,
OffsetTimeOriginal +02:00, Make "Fixture", Model "Fixture JXL", GPS 48°07'30" N 11°34'12" E):

  lossy.jxl     VarDCT (cjxl -d 1), 96 x 64, a plain `Exif` box and an `xml ` (XMP) box.
  lossless.jxl  Modular (cjxl -d 0), 96 x 64, the `Exif` box Brotli-compressed in a `brob` box,
                as cjxl keeps the metadata of a JPEG it recompresses.
  rotated.jxl   lossy, the raster stored on its side (64 x 96) with the codestream orientation 6
                (90 degrees clockwise), shown 96 x 64; its EXIF says orientation 6 too, which a
                reader must NOT apply on top.
  alpha.jxl     lossless with alpha, the bottom right quadrant fully transparent (its colour
                channels yellow).
  bare.jxl      lossy, a bare codestream (no container, no metadata).
  large.jxl     lossless, 512 x 512 of the quadrants in a few hundred bytes: a picture whose
                decode needs more than a decode budget of 1 MB (issue #207).
"""

import os
import struct
import subprocess
import sys
import tempfile

import brotli
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
CJXL = os.environ.get("CJXL", "cjxl")

RED = (255, 0, 0)
GREEN = (0, 255, 0)
BLUE = (0, 0, 255)
YELLOW = (255, 255, 0)

SIGNATURE = b"\0\0\0\x0cJXL \r\n\x87\n"


def quadrant(width, height):
    def colour(x, y):
        left = x < width // 2
        top = y < height // 2
        if top:
            return RED if left else GREEN
        return BLUE if left else YELLOW
    return colour


def picture(width, height, colour, alpha=None):
    image = Image.new("RGBA" if alpha else "RGB", (width, height))
    for y in range(height):
        for x in range(width):
            image.putpixel((x, y), colour(x, y) + ((alpha(x, y),) if alpha else ()))
    return image


def exif_tiff(orientation=1):
    exif = Image.Exif()
    exif[0x010F] = "Fixture"
    exif[0x0110] = "Fixture JXL"
    exif[0x0112] = orientation
    exif[0x8769] = {0x9003: "2024:05:17 14:30:00", 0x9011: "+02:00"}
    exif[0x8825] = {0: b"\x02\x02\x00\x00", 1: "N", 2: (48.0, 7.0, 30.0), 3: "E", 4: (11.0, 34.0, 12.0)}
    data = exif.tobytes()
    assert data.startswith(b"Exif\0\0")
    return data[6:]


XMP = (b'<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
       b'<rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:CreatorTool="Fixture JXL"/>'
       b'</rdf:RDF></x:xmpmeta>')


def codestream(image, *options):
    """The bare codestream cjxl writes for the given picture."""
    with tempfile.TemporaryDirectory() as tmp:
        source = os.path.join(tmp, "in.png")
        target = os.path.join(tmp, "out.jxl")
        exif = image.info.get("exif")
        image.save(source, exif=exif) if exif else image.save(source)
        subprocess.run([CJXL, source, target, "--container=0", "--quiet"] + list(options), check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        with open(target, "rb") as f:
            data = f.read()
    if data[:2] == b"\xff\x0a":
        return data
    # A container (cjxl keeps one where the PNG carried EXIF): its jxlc or jxlp boxes.
    assert data.startswith(SIGNATURE)
    stream = b""
    at = len(SIGNATURE)
    while at < len(data):
        size, kind = struct.unpack(">I4s", data[at:at + 8])
        body = data[at + 8:at + size]
        if kind == b"jxlc":
            stream += body
        elif kind == b"jxlp":
            stream += body[4:]
        at += size
    assert stream[:2] == b"\xff\x0a", "a codestream"
    return stream


def box(kind, payload):
    return struct.pack(">I", 8 + len(payload)) + kind + payload


def container(stream, exif=None, xmp=None, compress=False):
    boxes = SIGNATURE + box(b"ftyp", b"jxl " + struct.pack(">I", 0) + b"jxl ")
    if exif is not None:
        payload = struct.pack(">I", 0) + exif  # the offset of the TIFF header behind this field
        boxes += box(b"brob", b"Exif" + brotli.compress(payload)) if compress else box(b"Exif", payload)
    if xmp is not None:
        boxes += box(b"xml ", xmp)
    return boxes + box(b"jxlc", stream)


def write(name, data):
    with open(os.path.join(HERE, name), "wb") as out:
        out.write(data)
    print("wrote", name, len(data), "bytes")


def main():
    shown = quadrant(96, 64)
    upright = picture(96, 64, shown)
    write("lossy.jxl", container(codestream(upright, "-d", "1"), exif_tiff(), XMP))
    write("lossless.jxl", container(codestream(upright, "-d", "0"), exif_tiff(), compress=True))

    # Orientation 6: shown (u, v) = stored (v, H - 1 - u) for a stored height H = 96.
    stored = picture(64, 96, lambda x, y: shown(95 - y, x))
    tagged = Image.Exif()
    tagged[0x0112] = 6
    stored.info["exif"] = tagged.tobytes()
    write("rotated.jxl", container(codestream(stored, "-d", "1"), exif_tiff(6)))

    transparent = picture(96, 64, shown, lambda x, y: 0 if x >= 48 and y >= 32 else 255)
    write("alpha.jxl", container(codestream(transparent, "-d", "0"), exif_tiff()))

    write("bare.jxl", codestream(upright, "-d", "1"))

    write("large.jxl", container(codestream(picture(512, 512, quadrant(512, 512)), "-d", "0"), exif_tiff()))


if __name__ == "__main__":
    sys.exit(main())
