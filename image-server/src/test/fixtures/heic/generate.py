#!/usr/bin/env python3
"""Writes the HEIC fixtures of issue #186.

Every picture is generated: four coloured quadrants (top left red, top right green, bottom left
blue, bottom right yellow) of the picture *as it is to be seen*, so that a corner probe tells a
wrongly stitched grid, a wrong crop and a wrong turn apart.  The HEVC bitstreams are made by the
x265 command-line encoder; the HEIF container (ISO/IEC 23008-12) is written here, box by box,
because no tool on hand writes a grid, an `irot` or an `imir`.

Needs `x265` (Debian/Ubuntu package `x265`; `X265=/path/to/x265` overrides the PATH) and Pillow
(for the EXIF block).  Run from anywhere:

    python3 image-server/src/test/fixtures/heic/generate.py

What is written (all carry EXIF DateTimeOriginal 2024:05:17 14:30:00, OffsetTimeOriginal +02:00,
Make "Fixture", Model "Fixture HEIC", GPS 48°07'30" N 11°34'12" E):

  single.heic   one HEVC item, 96 x 64, no transformation.
  grid.heic     a 3 x 2 grid of 64 x 64 tiles cropped to 180 x 120 (the padding is magenta).
  rotated.heic  the grid's raster stored on its side with `irot` angle 1 (90 degrees
                anti-clockwise), shown 120 x 180 (portrait); its EXIF says orientation 8 (the same turn), which
                a reader must NOT apply on top (the container's turn is the one that counts).
  mirrored.heic single.heic's raster stored mirrored left-right with `imir` mode 1 ("the left and right parts
                are exchanged", ISO/IEC 23008-12:2022 6.5.12; mode 0 exchanges top and bottom),
                shown 96 x 64.
"""

import os
import struct
import subprocess
import sys
import tempfile

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
X265 = os.environ.get("X265", "x265")

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


def encode(width, height, colour):
    """One HEVC picture of the given pixels, as the list of its NAL units (no start codes)."""
    ys = bytearray()
    us = bytearray()
    vs = bytearray()
    for y in range(height):
        for x in range(width):
            ys.append(yuv(colour(x, y))[0])
    for y in range(0, height, 2):
        for x in range(0, width, 2):
            _, u, v = yuv(colour(x, y))
            us.append(u)
            vs.append(v)
    with tempfile.TemporaryDirectory() as tmp:
        source = os.path.join(tmp, "in.y4m")
        target = os.path.join(tmp, "out.hevc")
        with open(source, "wb") as out:
            out.write(b"YUV4MPEG2 W%d H%d F25:1 Ip A1:1 C420jpeg\n" % (width, height))
            out.write(b"FRAME\n")
            out.write(ys)
            out.write(us)
            out.write(vs)
        subprocess.run([X265, "--input", source, "--output", target, "--frames", "1", "--keyint", "1",
                        "--crf", "10", "--no-info", "--range", "full", "--colormatrix", "smpte170m",
                        "--log-level", "error"], check=True)
        with open(target, "rb") as f:
            stream = f.read()
    return split_annex_b(stream)


def split_annex_b(stream):
    nals = []
    i = 0
    starts = []
    while i < len(stream) - 3:
        if stream[i:i + 3] == b"\0\0\1":
            starts.append(i + 3)
            i += 3
        else:
            i += 1
    for n, start in enumerate(starts):
        end = starts[n + 1] - 3 if n + 1 < len(starts) else len(stream)
        nal = stream[start:end]
        while nal.endswith(b"\0"):
            nal = nal[:-1]
        nals.append(nal)
    return nals


def nal_type(nal):
    return (nal[0] >> 1) & 0x3F


def unescape(nal):
    out = bytearray()
    zeros = 0
    for b in nal:
        if zeros >= 2 and b == 3:
            zeros = 0
            continue
        out.append(b)
        zeros = zeros + 1 if b == 0 else 0
    return bytes(out)


def box(kind, payload):
    return struct.pack(">I", 8 + len(payload)) + kind + payload


def full_box(kind, version, flags, payload):
    return box(kind, struct.pack(">I", (version << 24) | flags) + payload)


def hvcc(nals):
    """The decoder configuration of a picture: the profile from its SPS, its parameter sets."""
    sps = unescape([n for n in nals if nal_type(n) == 33][0])
    ptl = sps[3:15]  # general profile/tier/level, behind the NAL header and the first byte
    data = bytearray()
    data.append(1)
    data += ptl[0:1]           # profile space, tier, profile idc
    data += ptl[1:5]           # compatibility flags
    data += ptl[5:11]          # constraint indicator flags
    data += ptl[11:12]         # level idc
    data += struct.pack(">H", 0xF000)  # min spatial segmentation
    data.append(0xFC)          # parallelism type
    data.append(0xFC | 1)      # chroma format 4:2:0
    data.append(0xF8)          # luma bit depth 8
    data.append(0xF8)          # chroma bit depth 8
    data += struct.pack(">H", 0)
    data.append(0x0F)          # one temporal layer, nested, four-byte lengths
    arrays = [t for t in (32, 33, 34) if any(nal_type(n) == t for n in nals)]
    data.append(len(arrays))
    for t in arrays:
        units = [n for n in nals if nal_type(n) == t]
        data.append(0x80 | t)
        data += struct.pack(">H", len(units))
        for unit in units:
            data += struct.pack(">H", len(unit)) + unit
    return box(b"hvcC", bytes(data))


def sample(nals):
    """The coded picture as stored in the file: its VCL units, four-byte length prefixed."""
    return b"".join(struct.pack(">I", len(n)) + n for n in nals if nal_type(n) < 32)


def exif_block(orientation):
    exif = Image.Exif()
    exif[0x010F] = "Fixture"
    exif[0x0110] = "Fixture HEIC"
    exif[0x0112] = orientation
    # Assigned as dictionaries: Pillow writes a sub-IFD only where the tag holds one.
    exif[0x8769] = {0x9003: "2024:05:17 14:30:00", 0x9011: "+02:00"}
    exif[0x8825] = {0: b"\x02\x02\x00\x00", 1: "N", 2: (48.0, 7.0, 30.0), 3: "E", 4: (11.0, 34.0, 12.0)}
    data = exif.tobytes()  # "Exif\0\0" followed by the TIFF structure
    # The HEIF Exif item: the offset of the TIFF header behind this field, then the data.
    return struct.pack(">I", 6) + data


def ispe(width, height):
    return full_box(b"ispe", 0, 0, struct.pack(">II", width, height))


def colr():
    return box(b"colr", b"nclx" + struct.pack(">HHH", 1, 13, 6) + bytes([0x80]))


def heif(items, primary, properties, associations, references):
    """A HEIF file.

    items: list of (id, type, data, in_idat)
    properties: list of property boxes (1-based indices in associations)
    associations: dict id -> list of (index, essential)
    references: list of (type, from, [to...])
    """
    ftyp = box(b"ftyp", b"heic" + struct.pack(">I", 0) + b"mif1heic")
    hdlr = full_box(b"hdlr", 0, 0, struct.pack(">I", 0) + b"pict" + bytes(12) + b"\0")
    pitm = full_box(b"pitm", 0, 0, struct.pack(">H", primary))
    # A tile of a grid is hidden (flag 1), as a phone writes it: it is no picture of its own.
    entries = b"".join(full_box(b"infe", 2, 1 if item_id >= 10 else 0,
                                struct.pack(">HH", item_id, 0) + kind + b"\0")
                       for item_id, kind, _, _ in items)
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
    idat = b"".join(data for _, _, data, in_idat in items if in_idat)
    idat_box = box(b"idat", idat) if idat else b""

    def iloc(offsets):
        body = bytes([0x44, 0x00]) + struct.pack(">H", len(items))
        for item_id, _, data, in_idat in items:
            body += struct.pack(">HHHH", item_id, 1 if in_idat else 0, 0, 1)
            body += struct.pack(">II", offsets[item_id], len(data))
        return full_box(b"iloc", 1, 0, body)

    def meta(offsets):
        return full_box(b"meta", 0, 0, hdlr + pitm + iinf + iref + iprp + idat_box + iloc(offsets))

    # The file offsets of the mdat items depend on the size of meta, which does not.
    placeholder = {item_id: 0 for item_id, _, _, _ in items}
    head = len(ftyp) + len(meta(placeholder)) + 8
    offsets = {}
    mdat = b""
    idat_offset = 0
    for item_id, _, data, in_idat in items:
        if in_idat:
            offsets[item_id] = idat_offset
            idat_offset += len(data)
        else:
            offsets[item_id] = head + len(mdat)
            mdat += data
    return ftyp + meta(offsets) + box(b"mdat", mdat)


def single(width, height, colour, extra, orientation=1):
    nals = encode(width, height, colour)
    exif = exif_block(orientation)
    items = [(1, b"hvc1", sample(nals), False), (2, b"Exif", exif, False)]
    properties = [hvcc(nals), ispe(width, height), colr()] + [p for p, _ in extra]
    assoc = [(1, True), (2, False), (3, False)] + [(4 + n, True) for n in range(len(extra))]
    return heif(items, 1, properties, {1: assoc}, [(b"cdsc", 2, [1])])


def grid(columns, rows, tile, width, height, colour, extra, orientation=1):
    """A grid image whose stored (un-transformed) raster is given by colour over width x height."""
    items = []
    tiles = []
    for r in range(rows):
        for c in range(columns):
            nals = encode(tile, tile, lambda x, y, c=c, r=r: colour(c * tile + x, r * tile + y))
            tiles.append(nals)
    descriptor = bytes([0, 0, rows - 1, columns - 1]) + struct.pack(">HH", width, height)
    items.append((1, b"grid", descriptor, True))
    for n, nals in enumerate(tiles):
        items.append((10 + n, b"hvc1", sample(nals), False))
    items.append((2, b"Exif", exif_block(orientation), False))
    # One shared decoder configuration, the way a phone writes it.
    properties = [hvcc(tiles[0]), ispe(tile, tile), ispe(width, height), colr()] + [p for p, _ in extra]
    associations = {1: [(3, False), (4, False)] + [(5 + n, True) for n in range(len(extra))]}
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
    write("single.heic", single(w, h, colour, []))

    gw, gh, gcolour = quadrants(180, 120, 192, 128)
    write("grid.heic", grid(3, 2, 64, 180, 120, gcolour, []))

    # Shown 120 x 180 after a quarter turn anti-clockwise: the stored raster is the shown one
    # turned a quarter clockwise. Shown (u, v) = stored (W - 1 - v, u) for a stored width W = 180.
    _, _, shown = quadrants(120, 180)

    def stored(x, y):
        if x >= 180 or y >= 120:
            return MAGENTA
        return shown(y, 179 - x)

    write("rotated.heic", grid(3, 2, 64, 180, 120, stored, [(box(b"irot", bytes([1])), True)], orientation=8))

    # Mirrored left-right: stored (x, y) = shown (W - 1 - x, y).
    write("mirrored.heic", single(96, 64, lambda x, y: colour(95 - x, y), [(box(b"imir", bytes([1])), True)]))


if __name__ == "__main__":
    sys.exit(main())
