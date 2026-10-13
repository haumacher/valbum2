#
# Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
#
"""The inputs of the golden vectors of issue #257, shared by make_golden.py and the test.

Texts with the prefixes the server's client adds (Embedder.TextKind), and images: the photographs
of the test album, a few of its generated pictures, and three made here - a panorama, a tall strip
and a small transparent PNG - for the edges of the resize rule.
"""

import io
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ALBUM = os.path.normpath(os.path.join(HERE, "..", "..", "test", "fixtures", "test-album"))

QUERY = "task: search result | query: "
DOCUMENT = "title: none | text: "

TEXTS = [
    QUERY + "flowers",
    QUERY + "Blumen im Regen",
    QUERY + "a rainy street at night",
    QUERY + "mountains",
    QUERY + "a castle park in winter",
    QUERY + "insects on a blossom",
    QUERY + "Leaning Tower",
    QUERY + "children playing on the beach",
    QUERY + "Sonnenuntergang am Meer",
    QUERY + "fleurs jaunes",
    QUERY + "summer 2025",
    QUERY + "",
    DOCUMENT + "Schlosspark Karlsruhe; March 2002",
    DOCUMENT + "Blumen und Fliegen; August 2005",
    "title: 2025-10 Italien | text: Leaning Tower of Pisa, Pisa, Tuscany, Italy; Italien, October 2025",
    "title: Hochzeit | text: Das Brautpaar vor der Kirche; Familie, Freunde",
    DOCUMENT + "A long description of a photograph. " * 40,
    DOCUMENT + "Ümlaute, accents (é, ñ), emoji 🌸 and CJK 花",
    QUERY + "red car",
    QUERY + "screenshot of a document",
]


def album_images():
    """(name, bytes) of the photographs of the test album, and of four of its generated pictures."""
    result = []
    for folder in ("2002-03-03 Schlosspark Karlsruhe", "2005-08-24 Blumen und Fliegen"):
        directory = os.path.join(ALBUM, folder)
        for name in sorted(os.listdir(directory)):
            if name.upper().endswith(".JPG"):
                result.append((folder + "/" + name, os.path.join(directory, name)))
    for number in (0, 1, 2, 3):
        name = "generated/image-%d.jpg" % number
        result.append((name, os.path.join(ALBUM, name)))
    images = []
    for name, path in result:
        with open(path, "rb") as f:
            images.append(("fixture:" + name, f.read()))
    return images


def made_images():
    """(name, bytes) of three pictures made here, deterministically."""
    import numpy as np
    from PIL import Image

    rng = np.random.default_rng(257)
    result = []

    # A panorama, 4:1, a sky gradient over noisy ground.
    h, w = 300, 1200
    y = np.linspace(0, 1, h)[:, None, None]
    sky = np.concatenate([np.full((h, w, 1), 90.0), 150 + 60 * y * np.ones((h, w, 1)), np.full((h, w, 1), 230.0)], 2)
    ground = rng.integers(40, 120, size=(h, w, 3)).astype(np.float64)
    pixels = np.where(y > 0.6, ground, sky).astype(np.uint8)
    result.append(("made:panorama.jpg", encode(Image.fromarray(pixels), "JPEG")))

    # A tall strip, 1:5, stripes.
    h, w = 1000, 200
    stripes = (np.arange(h)[:, None] // 50 % 2) * 200 + 30
    pixels = np.stack([stripes * np.ones((h, w)), np.full((h, w), 120), 255 - stripes * np.ones((h, w))], 2)
    result.append(("made:tall.jpg", encode(Image.fromarray(pixels.astype(np.uint8)), "JPEG")))

    # A small PNG with transparency: converted to RGB as the processor does.
    pixels = rng.integers(0, 256, size=(60, 80, 4)).astype(np.uint8)
    result.append(("made:small-alpha.png", encode(Image.fromarray(pixels, "RGBA"), "PNG")))
    return result


def encode(image, kind):
    out = io.BytesIO()
    image.save(out, kind, quality=90) if kind == "JPEG" else image.save(out, kind)
    return out.getvalue()


def images():
    return album_images() + made_images()
