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
canonical (issue #12). To regenerate the Java behaviour, check out commit `ebd0b2d` and run the
command above.

### The regenerated fixtures (issue #44)

The layout contract was deliberately changed for double-height row sections: within a section the
upper row now holds a *prefix* of the section's images in stored order and the lower row the
remaining *suffix*, so that a section reads row-wise — the first images in the upper row, the last
ones in the lower row. Before, the images were handed to the currently narrower of the two rows,
which made the displayed order zig-zag against the stored order and let a drag-and-drop reorder land
an image below its drop position instead of beside it.

The **16 fixtures** that disagreed with the new implementation were regenerated from Dart at that
change:

```
GOLDENS_OUT=test/fixtures/layout \
    flutter test test/tool/regenerate_layout_goldens.dart
```

They are `portrait-landscapes-portrait-*` (5 of 8), `random-60-*` (6 of 8) and `test-album-blumen-*`
(5 of 8); every one of them contains a `DoubleRow`, and every fixture without one was left
untouched. `test/tool/regenerate_layout_goldens.dart` writes the format below; run without
`GOLDENS_OUT` it writes to a temporary directory and reports which fixtures it reproduces byte for
byte, which is how the writer is kept honest (65 of the 88 came out byte-identical, 7 more differ
only in the last digit of a double — Dart accumulates the widths in a different order than Java did,
far below the `1e-9` tolerance of the golden test — and those 7 were *not* rewritten).

For the regenerated set, Dart is the reference from now on; for every other fixture the old rule
stands: do not "fix" a fixture to match Dart — where the two disagree, Dart is wrong. The invariant
the regenerated set encodes is the row-wise section order: reading a `DoubleRow` upper row first
yields the images in the order the album stores them.

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
{ "type": "Img",       "index": <int>, "unitWidth": <double> }   // index into "images"
{ "type": "DoubleRow", "unitWidth": <double>, "h1": <double>, "h2": <double>,
                       "upper": <Row>, "lower": <Row> }
{ "type": "Padding",   "unitWidth": <double> }
```

Doubles are written with Java's `Double.toString`, i.e. with full round-trip precision.
