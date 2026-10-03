#!/usr/bin/env python3
"""Writes the AVIF fixtures of issue #193.

Every picture is generated: four coloured quadrants (top left red, top right green, bottom left
blue, bottom right yellow) of the picture *as it is to be seen*, so that a corner probe tells a
wrongly stitched grid, a wrong crop and a wrong turn apart.  The AV1 bitstreams are made by an
FFmpeg program with the `libaom-av1` encoder (low-overhead OBUs, `-still-picture 1`); the HEIF
container (ISO/IEC 23008-12, AVIF = AV1 Image File Format) is written here, box by box, as the
HEIC fixtures of #186 are, because no tool on hand writes a grid, an `irot` or an alpha item the
way a test needs them.

Needs an `ffmpeg` with `libaom-av1` (`FFMPEG=/path/to/ffmpeg` overrides the PATH; the directory
of the program is put on LD_LIBRARY_PATH, so the program of a JavaCPP FFmpeg jar >= 6.0-1.5.9
unpacked anywhere works) and Pillow (for the EXIF block).  Run from anywhere:

    FFMPEG=/path/to/ffmpeg python3 image-server/src/test/fixtures/avif/generate.py

What is written (all carry EXIF DateTimeOriginal 2024:05:17 14:30:00, OffsetTimeOriginal +02:00,
Make "Fixture", Model "Fixture AVIF", GPS 48°07'30" N 11°34'12" E):

  single.avif   one AV1 item, 96 x 64, 8 bit, no transformation.
  grid.avif     a 3 x 2 grid of 64 x 64 tiles cropped to 180 x 120 (the padding is magenta).
  rotated.avif  the grid's raster stored on its side with `irot` angle 1 (90 degrees
                anti-clockwise), shown 120 x 180; its EXIF says orientation 8 (the same turn),
                which a reader must NOT apply on top.
  alpha.avif    single.avif with an alpha item (`auxl`, `auxC` urn:mpeg:mpegB:cicp:systems:
                auxiliary:alpha, a monochrome AV1 picture): the bottom right quadrant fully
                transparent, its colour channels still yellow.
  ten-bit.avif  single.avif coded at 10 bits (yuv420p10, `av1C` high_bitdepth).
"""

import os
import struct
import subprocess
import sys
import tempfile

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
FFMPEG = os.environ.get("FFMPEG", "ffmpeg")

RED = (255, 0, 0)
GREEN = (0, 255, 0)
BLUE = (0, 0, 255)
YELLOW = (255, 255, 0)
MAGENTA = (255, 0, 255)


def quadrants(width, height, canvas_width=None, canvas_height=None):
    """The shown picture: a function (x, y) -> RGB over the canvas."""
    cw = canvas_width or width
    ch = canvas_height or height

    def colour(x, y):
        if x >= width or y >= height:
            return MAGENTA
        left = x < width // 2
        top = y < height // 2
        if top:
            return RED if left else GREEN
        return BLUE if left else YELLOW

    return cw, ch, colour


def yuv(rgb):
    """Full-range BT.601, the nclx this file declares (matrix 6, full range)."""
    r, g, b = rgb
    y = 0.299 * r + 0.587 * g + 0.114 * b
    cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
    cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
    clamp = lambda v: max(0, min(255, int(round(v))))
    return clamp(y), clamp(cb), clamp(cr)


def obus(stream):
    """The OBUs of a low-overhead stream, each with its header and size field: [(type, bytes)]."""
    result = []
    at = 0
    while at < len(stream):
        header = stream[at]
        kind = (header >> 3) & 0xF
        extension = (header >> 2) & 1
        assert (header >> 1) & 1, "every OBU carries its size"
        start = at
        at += 1 + extension
        size = 0
        shift = 0
        while True:
            byte = stream[at]
            at += 1
            size |= (byte & 0x7F) << shift
            shift += 7
            if not byte & 0x80:
                break
        at += size
        result.append((kind, stream[start:at]))
    return result


def encode(width, height, pixel, bits=8, monochrome=False):
    """One AV1 still picture: (the sequence header OBU, the item data without temporal delimiter).

    pixel(x, y) answers an RGB triple, or for a monochrome picture a value 0..255.
    """
    scale = (1 << bits) - 1
    pack = (lambda v: struct.pack("<H", round(v * scale / 255))) if bits > 8 else (lambda v: bytes([v]))
    ys = bytearray()
    us = bytearray()
    vs = bytearray()
    for y in range(height):
        for x in range(width):
            ys += pack(pixel(x, y) if monochrome else yuv(pixel(x, y))[0])
    if not monochrome:
        for y in range(0, height, 2):
            for x in range(0, width, 2):
                _, u, v = yuv(pixel(x, y))
                us += pack(u)
                vs += pack(v)
    if monochrome:
        pix_fmt = "gray"
    else:
        pix_fmt = "yuv420p10le" if bits == 10 else "yuv420p"
    env = dict(os.environ)
    program_dir = os.path.dirname(FFMPEG)
    if program_dir:
        env["LD_LIBRARY_PATH"] = program_dir + os.pathsep + env.get("LD_LIBRARY_PATH", "")
    with tempfile.TemporaryDirectory() as tmp:
        source = os.path.join(tmp, "in.yuv")
        target = os.path.join(tmp, "out.obu")
        with open(source, "wb") as out:
            out.write(ys)
            out.write(us)
            out.write(vs)
        subprocess.run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", pix_fmt,
                        "-s", "%dx%d" % (width, height), "-i", source, "-frames:v", "1", "-c:v", "libaom-av1",
                        "-still-picture", "1", "-crf", "4", "-cpu-used", "6", "-color_range", "pc",
                        "-colorspace", "smpte170m", "-f", "obu", target], check=True, env=env)
        with open(target, "rb") as f:
            stream = f.read()
    units = obus(stream)
    sequence = [data for kind, data in units if kind == 1][0]
    data = b"".join(data for kind, data in units if kind != 2)  # no temporal delimiter
    return sequence, data


def box(kind, payload):
    return struct.pack(">I", 8 + len(payload)) + kind + payload


def full_box(kind, version, flags, payload):
    return box(kind, struct.pack(">I", (version << 24) | flags) + payload)


def av1c(sequence, bits=8, monochrome=False):
    """The AV1 codec configuration: profile and level read from the reduced still-picture header."""
    payload = sequence[2:]  # behind the OBU header and its one-byte size
    profile = payload[0] >> 5
    assert (payload[0] >> 3) & 1, "a reduced still-picture header"
    level = ((payload[0] & 0x7) << 2) | (payload[1] >> 6)
    flags = (1 << 6 if bits > 8 else 0) | (1 << 4 if monochrome else 0) | (1 << 3) | (1 << 2)
    return box(b"av1C", bytes([0x81, (profile << 5) | level, flags, 0]) + sequence)


def exif_block(orientation):
    exif = Image.Exif()
    exif[0x010F] = "Fixture"
    exif[0x0110] = "Fixture AVIF"
    exif[0x0112] = orientation
    exif[0x8769] = {0x9003: "2024:05:17 14:30:00", 0x9011: "+02:00"}
    exif[0x8825] = {0: b"\x02\x02\x00\x00", 1: "N", 2: (48.0, 7.0, 30.0), 3: "E", 4: (11.0, 34.0, 12.0)}
    data = exif.tobytes()  # "Exif\0\0" followed by the TIFF structure
    return struct.pack(">I", 6) + data


def ispe(width, height):
    return full_box(b"ispe", 0, 0, struct.pack(">II", width, height))


def colr():
    return box(b"colr", b"nclx" + struct.pack(">HHH", 1, 13, 6) + bytes([0x80]))


def pixi(channels, bits):
    return full_box(b"pixi", 0, 0, bytes([channels] + [bits] * channels))


ALPHA_URN = b"urn:mpeg:mpegB:cicp:systems:auxiliary:alpha\0"


def heif(items, primary, properties, associations, references):
    """An AVIF file.

    items: list of (id, type, data, in_idat, hidden)
    properties: list of property boxes (1-based indices in associations)
    associations: dict id -> list of (index, essential)
    references: list of (type, from, [to...])
    """
    ftyp = box(b"ftyp", b"avif" + struct.pack(">I", 0) + b"avifmif1miaf")
    hdlr = full_box(b"hdlr", 0, 0, struct.pack(">I", 0) + b"pict" + bytes(12) + b"\0")
    pitm = full_box(b"pitm", 0, 0, struct.pack(">H", primary))
    entries = b"".join(full_box(b"infe", 2, 1 if hidden else 0, struct.pack(">HH", item_id, 0) + kind + b"\0")
                       for item_id, kind, _, _, hidden in items)
    iinf = full_box(b"iinf", 0, 0, struct.pack(">H", len(items)) + entries)
    iref = full_box(b"iref", 0, 0, b"".join(
        box(kind, struct.pack(">HH", source, len(targets)) + b"".join(struct.pack(">H", t) for t in targets))
        for kind, source, targets in references)) if references else b""
    ipco = box(b"ipco", b"".join(properties))
    ipma_entries = b""
    for item_id in sorted(associations):
        assoc = associations[item_id]
        ipma_entries += struct.pack(">HB", item_id, len(assoc))
        ipma_entries += bytes((0x80 if essential else 0) | index for index, essential in assoc)
    ipma = full_box(b"ipma", 0, 0, struct.pack(">I", len(associations)) + ipma_entries)
    iprp = box(b"iprp", ipco + ipma)
    idat = b"".join(data for _, _, data, in_idat, _ in items if in_idat)
    idat_box = box(b"idat", idat) if idat else b""

    def iloc(offsets):
        body = bytes([0x44, 0x00]) + struct.pack(">H", len(items))
        for item_id, _, data, in_idat, _ in items:
            body += struct.pack(">HHHH", item_id, 1 if in_idat else 0, 0, 1)
            body += struct.pack(">II", offsets[item_id], len(data))
        return full_box(b"iloc", 1, 0, body)

    def meta(offsets):
        return full_box(b"meta", 0, 0, hdlr + pitm + iinf + iref + iprp + idat_box + iloc(offsets))

    placeholder = {item_id: 0 for item_id, _, _, _, _ in items}
    head = len(ftyp) + len(meta(placeholder)) + 8
    offsets = {}
    mdat = b""
    idat_offset = 0
    for item_id, _, data, in_idat, _ in items:
        if in_idat:
            offsets[item_id] = idat_offset
            idat_offset += len(data)
        else:
            offsets[item_id] = head + len(mdat)
            mdat += data
    return ftyp + meta(offsets) + box(b"mdat", mdat)


def single(width, height, colour, extra, orientation=1, bits=8, alpha=None):
    sequence, data = encode(width, height, colour, bits)
    items = [(1, b"av01", data, False, False), (2, b"Exif", exif_block(orientation), False, False)]
    properties = [av1c(sequence, bits), ispe(width, height), colr(), pixi(3, bits)] + [p for p, _ in extra]
    associations = {1: [(1, True), (2, False), (3, False), (4, False)]
                    + [(5 + n, True) for n in range(len(extra))]}
    references = [(b"cdsc", 2, [1])]
    if alpha is not None:
        alpha_sequence, alpha_data = encode(width, height, alpha, 8, monochrome=True)
        items.append((3, b"av01", alpha_data, False, True))
        first = len(properties) + 1
        properties += [av1c(alpha_sequence, 8, monochrome=True), full_box(b"auxC", 0, 0, ALPHA_URN), pixi(1, 8)]
        associations[3] = [(first, True), (2, False), (first + 1, True), (first + 2, False)]
        references.append((b"auxl", 3, [1]))
    return heif(items, 1, properties, associations, references)


def grid(columns, rows, tile, width, height, colour, extra, orientation=1):
    """A grid image whose stored (un-transformed) raster is given by colour over width x height."""
    items = []
    tiles = []
    for r in range(rows):
        for c in range(columns):
            tiles.append(encode(tile, tile, lambda x, y, c=c, r=r: colour(c * tile + x, r * tile + y)))
    descriptor = bytes([0, 0, rows - 1, columns - 1]) + struct.pack(">HH", width, height)
    items.append((1, b"grid", descriptor, True, False))
    for n, (_, data) in enumerate(tiles):
        items.append((10 + n, b"av01", data, False, True))
    items.append((2, b"Exif", exif_block(orientation), False, False))
    properties = [av1c(tiles[0][0]), ispe(tile, tile), ispe(width, height), colr(), pixi(3, 8)] \
        + [p for p, _ in extra]
    associations = {1: [(3, False), (4, False), (5, False)] + [(6 + n, True) for n in range(len(extra))]}
    for n in range(len(tiles)):
        associations[10 + n] = [(1, True), (2, False)]
    references = [(b"dimg", 1, [10 + n for n in range(len(tiles))]), (b"cdsc", 2, [1])]
    return heif(items, 1, properties, associations, references)


def write(name, data):
    with open(os.path.join(HERE, name), "wb") as out:
        out.write(data)
    print("wrote", name, len(data), "bytes")


def main():
    w, h, colour = quadrants(96, 64)
    write("single.avif", single(w, h, colour, []))

    gw, gh, gcolour = quadrants(180, 120, 192, 128)
    write("grid.avif", grid(3, 2, 64, 180, 120, gcolour, []))

    # Shown 120 x 180 after a quarter turn anti-clockwise: shown (u, v) = stored (W - 1 - v, u).
    _, _, shown = quadrants(120, 180)

    def stored(x, y):
        if x >= 180 or y >= 120:
            return MAGENTA
        return shown(y, 179 - x)

    write("rotated.avif", grid(3, 2, 64, 180, 120, stored, [(box(b"irot", bytes([1])), True)], orientation=8))

    write("alpha.avif", single(w, h, colour, [], alpha=lambda x, y: 0 if x >= w // 2 and y >= h // 2 else 255))

    write("ten-bit.avif", single(w, h, colour, [], bits=10))


if __name__ == "__main__":
    sys.exit(main())
