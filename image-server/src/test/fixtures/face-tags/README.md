# A sidecar with face tags, written by the build of issue #125

`index.json` is an album sidecar as this server writes it once somebody has named a face:
two photographs, one with a **confirmed** tag naming a person of the space's `people.json`,
one with a **`NOT_A_FACE`** tag that pins a false detection (and therefore names nobody).

It is committed so that every later build proves it still reads it: a face tag is a human
decision and is stored for ever, so its format is a promise, not an implementation detail.
The boxes are normalised to `0..1` in the raw raster of the file, before the EXIF
orientation and before `ImagePart.orientation` — see `Faces` and issue #124.

Nothing reads the two photographs; they do not exist. The file is the fixture.
