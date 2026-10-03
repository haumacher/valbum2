# AVIF pictures (issue #193)

Five tiny generated AVIF files — no real photograph. Upright, each shows four quadrants: red top
left, green top right, blue bottom left, yellow bottom right, so a probe of each corner tells a
wrongly stitched grid, a wrong crop and a wrong turn apart.

| File           | Coded as                                                          | Raw raster | Shown     |
| -------------- | ----------------------------------------------------------------- | ---------- | --------- |
| `single.avif`  | one AV1 picture, 8 bit                                            | 96 × 64    | 96 × 64   |
| `grid.avif`    | a 3 × 2 `grid` of 64 × 64 tiles, cut to the output size            | 180 × 120  | 180 × 120 |
| `rotated.avif` | the grid on its side, `irot` 1 (a quarter turn anti-clockwise); its EXIF says orientation 8, which must not be applied on top | 180 × 120 | 120 × 180 |
| `alpha.avif`   | `single.avif` with an alpha item (`auxl`, `auxC` `urn:mpeg:mpegB:cicp:systems:auxiliary:alpha`, a monochrome AV1 picture): the bottom right quadrant fully transparent, its colour channels yellow | 96 × 64 | 96 × 64, white bottom right |
| `ten-bit.avif` | `single.avif` coded at 10 bits (`yuv420p10`)                        | 96 × 64    | 96 × 64   |

Every file carries EXIF `DateTimeOriginal` 2024:05:17 14:30:00 at `OffsetTimeOriginal` +02:00,
`Make` "Fixture", `Model` "Fixture AVIF" and a GPS position of 48°07'30" N 11°34'12" E
(48.125, 11.57). The padding of the grid beyond its output size is magenta.

`generate.py` writes them: the AV1 bitstreams with an FFmpeg program that has the `libaom-av1`
encoder, the HEIF container box by box. It needs such a program and Pillow; the `ffmpeg` of the
JavaCPP platform jar `org.bytedeco:ffmpeg:6.0-1.5.9-linux-x86_64`, unpacked anywhere, is one
(the script puts its directory on `LD_LIBRARY_PATH`):

```
FFMPEG=/path/to/ffmpeg python3 image-server/src/test/fixtures/avif/generate.py
```

libheif (ImageMagick's `convert x.avif -background white -alpha remove x.png`) reads all five as
described, which is the independent check that the container is what it claims. The tests decode
them with the bundled FFmpeg 6.0 (JavaCPP presets 1.5.9, `libaom-av1`, issue #210), the program
the server runs, and fail with the server's own sentence on a machine whose program has no
software AV1 decoder.
