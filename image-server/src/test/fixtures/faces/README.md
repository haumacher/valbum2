# Test photographs with faces (issue #124)

These four files are the fixture the face index of issue #124 is tested against. They are
**public-domain reproductions of paintings**, downscaled and re-encoded for this repository. Three
deliberate properties:

- the detector (YuNet) finds exactly one face in each of them;
- `portrait-a-1.jpg` and `portrait-a-2.jpg` are the same face, so the clustering has a group of two
  to find, and `portrait-b.jpg` and `portrait-c.jpg` are two further people, each a group of one;
- nobody living is depicted, and no photograph of a real person is checked into this repository for
  the sake of a test.

Why paintings and not the synthetic portraits issue #124 asked for first: YuNet does detect a drawn
face (a skin-coloured ellipse with two eyes and a mouth is found with a score above 0.98), but SFace
maps every such drawing into nearly the same point — three clearly different drawings come out at a
cosine similarity of 0.83 to 0.91, far above the 0.363 that means "the same person" — so a synthetic
fixture cannot carry the assertion that two faces are one person and a third is not. Painted
portraits behave like photographs: 0.96 within the pair, 0.07 to 0.29 between the people.

## Sources and licences

All four are derived from files of Wikimedia Commons whose licence is **public domain** (PD-Art,
faithful reproductions of two-dimensional works whose author has been dead for more than 100 years).
Each was fetched at 500 px width through the Commons API and then scaled and re-encoded; nothing was
retouched.

| File | Work | Source file on Wikimedia Commons | Licence |
| --- | --- | --- | --- |
| `portrait-a-1.jpg` | Leonardo da Vinci, *Mona Lisa* (c. 1503) | `File:Mona Lisa, by Leonardo da Vinci, from C2RMF retouched.jpg` | Public domain (PD-Art / PD-old-100) |
| `portrait-a-2.jpg` | the same work, centre-cropped to 72 % and re-encoded at a lower quality, so that the two files differ in bytes, in size and in hash while showing one person | as above | Public domain (PD-Art / PD-old-100) |
| `portrait-b.jpg` | Johannes Vermeer, *Girl with a Pearl Earring* (c. 1665) | `File:Meisje met de parel.jpg` | Public domain (PD-Art / PD-old-100) |
| `portrait-c.jpg` | Vincent van Gogh, *Self-Portrait* (1887) | `File:Vincent van Gogh - Self-Portrait - Google Art Project (454045).jpg` | Public domain (PD-Art / PD-old-100) |

The rotated variant the orientation test needs is not checked in: `TestFaceOrientation` makes it at
run time by turning `portrait-a-1.jpg` a quarter turn and writing an EXIF orientation of 6, so that
the picture is upright again on screen while its raster is not.
