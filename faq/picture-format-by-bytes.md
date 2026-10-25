# A picture's format is its bytes; the name is the fallback

Phones and export tools write JPEGs named `.heic` and HEICs named `.jpg` (#312). Every place that
picks a reader, a decoder or a served content type for a file on disk asks
`PictureFormat.of(File)` (the first 64 bytes), through `CodedPictures.handles/read/unavailability/
contentType(File)` and `RawFile.isRaw(File)`. The `String` overloads are for what only has a name.

- **The name decides only where the bytes say nothing recognisable.** Then the name's reader is
  tried, and its failure becomes "'x.heic' is neither HEIF nor any other picture format: it starts
  with …". An ISO media file of an unknown brand counts as "nothing recognisable", so nothing a
  `.heic` reader accepted before is refused now.
- **A raw's extension stays decisive** where the bytes say TIFF (or CR3/RAF, or nothing): most raws
  are TIFF files, and which raw it is (where its preview lies, `RawFile.locate`) only the extension
  says. Only bytes of another picture format (a JPEG named `.dng`) take a raw name off the raw path.
- **Videos are decided by name**, untouched by the sniffer.
- **Names still decide what is name-shaped:** upload acceptance, the listing, the cache file names,
  raw pairing (`RawPairs`: a JPEG named `.heic` beside `x.dng` is the photograph of the pair, exactly as a real HEIC), and the app's choice of `?type=display`
  (`displayRenditionExtensions`).
- **`?type=display` on a name that asks for one, whose bytes are JPEG/PNG/GIF/WebP,** is answered
  with the original's bytes under their own type: what the app would show for the same bytes named
  rightly — no decode, no cache file, GIF animation and PNG alpha kept.
- **A HEIC named `.jpg` in the app**: the app hands the original to its codec by the name, which
  fails where the platform has no HEIF decoder (web, desktop). The server makes `?type=display` for
  it on request, but the app does not ask; telling it needs the format on the wire or an app-side
  fallback.
- Nothing is cached: one 64-byte read beside the decode or metadata read it chooses.
  `AnalysisRecord.ANALYSIS` 2 retries the failures earlier builds recorded for such files.
