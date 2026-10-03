# JPEG XL pictures (issue #193)

Tiny generated JPEG XL files — no real photograph. Upright, each shows four quadrants: red top
left, green top right, blue bottom left, yellow bottom right.

| File           | Coded as                                                                 | Raw raster | Shown     |
| -------------- | ------------------------------------------------------------------------ | ---------- | --------- |
| `lossy.jxl`    | VarDCT (`cjxl -d 1`), a plain `Exif` box and an `xml ` (XMP) box          | 96 × 64    | 96 × 64   |
| `lossless.jxl` | Modular (`cjxl -d 0`), the `Exif` box Brotli-compressed in a `brob` box   | 96 × 64    | 96 × 64   |
| `rotated.jxl`  | lossy, stored on its side with the codestream orientation 6 (a quarter turn clockwise); its EXIF says orientation 6 too, which must not be applied on top | 64 × 96 | 96 × 64 |
| `alpha.jxl`    | lossless with alpha, the bottom right quadrant fully transparent (its colour channels yellow) | 96 × 64 | 96 × 64, white bottom right |
| `bare.jxl`     | lossy, a bare codestream: no container, no metadata                       | 96 × 64    | 96 × 64   |
| `large.jxl`    | lossless, 512 × 512 in a few hundred bytes: its decode needs 32 MB, more than a decode budget of 1 MB (issue #207) | 512 × 512 | 512 × 512 |

All but `bare.jxl` carry EXIF `DateTimeOriginal` 2024:05:17 14:30:00 at `OffsetTimeOriginal`
+02:00, `Make` "Fixture", `Model` "Fixture JXL" and a GPS position of 48°07'30" N 11°34'12" E
(48.125, 11.57).

`generate.py` writes them: the codestreams with libjxl's `cjxl`, the container box by box. It
needs `cjxl` (package `libjxl-tools`), Pillow and the Python `brotli` module:

```
python3 image-server/src/test/fixtures/jxl/generate.py
```

libjxl's `djxl` and `jxlinfo` read them all as described, which is the independent check.
