# A photograph that names the people in it

`two-faces.jpg` is what an older library hands this server: a small picture (400 × 300) whose XMP
carries a Metadata Working Group `mwg-rs:Regions` structure naming two people, exactly as Picasa,
digiKam and Lightroom write it. It is the fixture of issue #129 — importing those names as
confirmed face tags.

What it says:

| Name  | `mwg-rs:Type` | centre (`stArea:x`, `stArea:y`) | size (`stArea:w`, `stArea:h`) |
| ----- | ------------- | ------------------------------- | ----------------------------- |
| Alice | `Face`        | 0.30, 0.40                      | 0.20, 0.25                    |
| Bob   | `Face`        | 0.70, 0.45                      | 0.18, 0.24                    |

`mwg-rs:AppliedToDimensions` is `400 × 300` pixels — the file's own raster — and both areas are
`normalized`. An MWG area is measured from its **centre**, so Alice's stored `FaceTag` is
`0.20, 0.275, 0.20, 0.25` and Bob's is `0.61, 0.33, 0.18, 0.24`. The picture draws a circle where
it claims each face is, so that what it says can be seen.

It was written by `de.haumacher.imageServer.Xmp` (test sources), which builds the packet with the
very XMP library the server reads it with and puts it into the `APP1` segment of a freshly drawn
JPEG. To make it again, from the `image-server` module:

```
mvn test-compile
java -cp target/classes:target/test-classes:<the test class path> de.haumacher.imageServer.Xmp
```

Everything else the tests need — a stale `AppliedToDimensions`, a `Pet` region, an unnamed face, a
turned file, a broken packet — is written at run time by the same class, out of the pixels of this
one.
