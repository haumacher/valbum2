# Face boxes: stored raw, answered upright

Face boxes (cache, tags, MWG import) are normalised 0..1 in the file's **raw raster**, before EXIF
orientation and before `ImagePart.orientation` — the one frame nothing can move. They are turned to
upright (EXIF applied, app orientation not) only when an answer is built (`Faces.toUpright` in
`FaceIndex.upright`). The app then applies `ImagePart.orientation` itself. HEIC/raw/AVIF define their
own "raw raster" (see the `*FaceFrame` tests). `PreviewCache.orientationTransform` must match `Faces`.
