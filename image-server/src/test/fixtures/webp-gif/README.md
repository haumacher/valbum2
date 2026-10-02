# WebP and GIF pictures (issues #190, #207)

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
| `lossy-alpha.webp` | lossy (VP8) with a losslessly compressed alpha channel (`ALPH`); the bottom right quadrant fully transparent (#207) | 96 × 64 | 96 × 64 |
| `offset.gif`    | GIF 89a whose logical screen is 96 × 64 and whose only frame is 48 × 32 at (32, 16): the frame's own quadrants red, green, blue and a transparent bottom right one; the background colour index names magenta (#207) | 96 × 64 | 96 × 64: white around the frame and in its transparent corner |
| `offset.webp`   | animated lossless, canvas 96 × 64; the first frame is the 48 × 32 frame of `offset.gif` (transparent corner included) at (32, 16), the second all magenta at (0, 0) (#207) | 96 × 64 | as `offset.gif` |
| `lossless-4mp.webp` | lossless (VP8L), the quadrants with a 128 × 128 patch of noise in the middle (so that TwelveMonkeys' limit of a raster 2048 times its file is kept); its decoder holds the whole raster, about 14 MB above the JVM (#207) | 2400 × 1600 | 2400 × 1600 |
| `portrait.webp` | `../faces/portrait-b.jpg` (a public-domain painting, see `../faces/README.md`) re-encoded lossy | 320 × 374 | 320 × 374 |

`exif.webp`'s EXIF says `DateTimeOriginal` 2024:05:17 14:30:00 at `OffsetTimeOriginal` +02:00,
`Make` "Fixture", `Model` "Fixture WebP" and a GPS position of 48°07'30" N 11°34'12" E
(48.125, 11.57). A GIF carries none of that.

`generate.py` writes them with Pillow (built with WebP support) and nothing else:

```
python3 image-server/src/test/fixtures/webp-gif/generate.py
```
