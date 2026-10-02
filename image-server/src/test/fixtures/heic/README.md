# HEIC photographs (issue #186)

Four tiny generated HEIC files — no real photograph. Upright, each shows four quadrants: red top
left, green top right, blue bottom left, yellow bottom right, so a probe of each corner tells a
wrongly stitched grid, a wrong crop and a wrong turn apart.

| File            | Coded as                                                   | Raw raster | Shown     |
| --------------- | ---------------------------------------------------------- | ---------- | --------- |
| `single.heic`   | one HEVC picture                                           | 96 × 64    | 96 × 64   |
| `grid.heic`     | a 3 × 2 `grid` of 64 × 64 tiles, cut to the output size     | 180 × 120  | 180 × 120 |
| `rotated.heic`  | the grid on its side, `irot` 1 (a quarter turn anti-clockwise); its EXIF says orientation 8, which must not be applied on top | 180 × 120 | 120 × 180 |
| `mirrored.heic` | one picture, `imir` mode 1 (left and right exchanged)      | 96 × 64    | 96 × 64   |

Every file carries EXIF `DateTimeOriginal` 2024:05:17 14:30:00 at `OffsetTimeOriginal` +02:00,
`Make` "Fixture", `Model` "Fixture HEIC" and a GPS position of 48°07'30" N 11°34'12" E
(48.125, 11.57). The padding of the grid beyond its output size is magenta.

`generate.py` writes them: the HEVC bitstreams with the `x265` command-line encoder, the HEIF
container box by box (no tool on hand writes a grid, an `irot` or an `imir`). It needs `x265`
(package `x265`) and Pillow:

```
X265=/path/to/x265 python3 image-server/src/test/fixtures/heic/generate.py
```

libheif (ImageMagick's `convert 'grid.heic[0]' ...`) reads all four as described, which is the
independent check that the container is what it claims.
