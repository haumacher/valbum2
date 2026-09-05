# Album layout golden fixtures

These files are the **contract** for the album row layout algorithm. `valbum_ui/lib/album_layout.dart`
is the canonical implementation; `test/album_layout_golden_test.dart` replays every fixture here and
compares the resulting row tree structurally (tolerance `1e-9` on every double).

## Where they come from

They were produced by the **Java** reference implementation
`de.haumacher.imageServer.shared.ui.layout.AlbumLayout` (module `image-server-shared`) by the test
generator `GenerateLayoutGoldens`, run at commit `ebd0b2d`:

```
mvn -pl :image-server-shared test -Dtest=GenerateLayoutGoldens -Dgoldens.generate=true
```

The generator only wrote files when `-Dgoldens.generate=true` was given, so a normal `mvn test`
never touched them, and it was idempotent (same input, byte-identical output).

The Java layout package and the generator were **deleted** in the same change that made Dart
canonical (issue #12). These fixtures are the permanent record of the Java behaviour. To regenerate
them, check out commit `ebd0b2d` and run the command above; do not "fix" a fixture to match Dart —
where the two disagree, Dart is wrong.

## Cases

| Case | Images | Exercises |
|---|---|---|
| `all-landscape-3x2` | 12 × 900×600 | plain landscape rows |
| `all-portrait-2x3` | 9 × 600×900 | portrait pairing into double rows |
| `alternating` | 10, portrait/landscape alternating | switching between simple and double row computation |
| `panorama-middle` | 7, one 8000×1000 panorama in the middle | full-width panorama rows |
| `single-landscape` | 1 | trivial / trailing padding |
| `single-portrait` | 1 | trivial portrait |
| `two-images` | landscape + portrait | shortest non-trivial case |
| `portrait-landscapes-portrait` | portrait, 5 landscapes, portrait | `DoubleRowBuilder.split()` and its revert path |
| `rotated` | 8 images with landscape raw pixels and `ROT_L` / `ROT_R` / `ROT_180` / `FLIP_H` | display orientation via `Orientations` |
| `random-60` | 60 images, `java.util.Random(20260905)` | long sequence, varied aspect ratios (the dimensions are stored in the fixture, never re-generated) |
| `test-album-blumen` | the 7 real images of `image-server/src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen` | real album; dimensions were read with `javax.imageio`, none of the files carries an EXIF orientation tag, so all use `IDENTITY` |

Each case is generated for page widths **320, 768, 1280, 1920** and maximum row heights **250, 400**
— 11 cases × 4 × 2 = **88 fixtures**, about 500 KB in total.

## File format

```jsonc
{
  "case": "<case name>",
  "pageWidth": 1280.0,
  "maxRowHeight": 250.0,
  "images": [ { "name": "L1", "width": 900, "height": 600, "orientation": "IDENTITY" }, ... ],
  "layout": {
    "pageWidth": 1280.0,          // AlbumLayout.getPageWidth()
    "rows": [ <content>, ... ]    // top level entries are always of type "Row"
  }
}
```

A `<content>` node is one of:

```jsonc
{ "type": "Row",       "unitWidth": <double>, "contents": [ <content>, ... ] }
{ "type": "Img",       "unitWidth": <double>, "index": <int> }   // index into "images"
{ "type": "DoubleRow", "unitWidth": <double>, "h1": <double>, "h2": <double>,
                       "upper": <Row>, "lower": <Row> }
{ "type": "Padding",   "unitWidth": <double> }
```

Doubles are written with Java's `Double.toString`, i.e. with full round-trip precision.
