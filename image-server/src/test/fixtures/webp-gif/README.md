# WebP and GIF pictures (issue #190)

Tiny generated pictures — no real photograph. Upright, each of the 96 × 64 ones shows four
quadrants: red top left, green top right, blue bottom left, yellow bottom right, so a probe of each
corner tells a wrong turn, a wrong frame and a wrong background apart.

| File            | Coded as                                                              | Raw raster | Shown   |
| --------------- | --------------------------------------------------------------------- | ---------- | ------- |
| `lossy.webp`    | lossy (VP8), no metadata                                              | 96 × 64    | 96 × 64 |
| `alpha.webp`    | lossless (VP8L) with alpha; the bottom right quadrant fully transparent (its colour channels yellow) | 96 × 64 | 96 × 64 |
| `exif.webp`     | lossy, the raster on its side, an EXIF chunk with orientation 6       | 64 × 96    | 96 × 64 |
| `animated.webp` | animated lossless, two frames: the quadrants, then all magenta        | 96 × 64    | 96 × 64 |
| `still.gif`     | GIF 87a, the quadrants in a four-colour palette                       | 96 × 64    | 96 × 64 |
| `animated.gif`  | GIF 89a, two frames: the quadrants, then all magenta                  | 96 × 64    | 96 × 64 |
| `portrait.webp` | `../faces/portrait-b.jpg` (a public-domain painting, see `../faces/README.md`) re-encoded lossy | 320 × 374 | 320 × 374 |

`exif.webp`'s EXIF says `DateTimeOriginal` 2024:05:17 14:30:00 at `OffsetTimeOriginal` +02:00,
`Make` "Fixture", `Model` "Fixture WebP" and a GPS position of 48°07'30" N 11°34'12" E
(48.125, 11.57). A GIF carries none of that.

`generate.py` writes them with Pillow (built with WebP support) and nothing else:

```
python3 image-server/src/test/fixtures/webp-gif/generate.py
```
