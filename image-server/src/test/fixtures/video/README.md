# QuickTime, iTunes and 3GPP videos (issue #189)

Five tiny generated videos — no real recording. One second at 10 fps, each frame four quadrants
upright: red top left, green top right, blue bottom left, yellow bottom right, so a probe of each
corner of the poster tells a wrong turn apart.

| File          | Container (`ftyp`)   | Streams                 | Size     | Dated by                                   |
| ------------- | -------------------- | ----------------------- | -------- | ------------------------------------------ |
| `clip.mov`    | QuickTime (`qt  `)   | H.264 + AAC             | 96 × 64  | `mvhd` 2024-05-17 12:30:00 UTC             |
| `clip.m4v`    | iTunes (`M4V `)      | H.264                   | 96 × 64  | `mvhd` 2024-05-17 12:31:00 UTC             |
| `clip.3gp`    | 3GPP (`3gp4`)        | H.263 + AMR-NB          | 128 × 96 | `mvhd` 2024-05-17 12:32:00 UTC             |
| `apple.mov`   | QuickTime (`qt  `)   | H.264                   | 96 × 64  | `com.apple.quicktime.creationdate` only    |
| `rotated.mov` | QuickTime (`qt  `)   | H.264, track matrix 90° | 64 × 96 shown | `mvhd` 2024-05-17 12:33:00 UTC        |

`apple.mov` is an iPhone movie as far as the metadata go: its `mvhd` times are 0 (1904-01-01), and
the `meta` box of the movie carries the keys an iPhone writes — `com.apple.quicktime.creationdate`
`2024-05-17T14:30:00+0200`, `com.apple.quicktime.location.ISO6709` `+48.1250+011.5700+520.000/`,
`com.apple.quicktime.make` `Apple` and `com.apple.quicktime.model` `iPhone 15`. `rotated.mov` stores
the raster on its side (96 × 64) with the track matrix of a quarter turn, which FFmpeg plays turned a
quarter anti-clockwise (green top left).

There is no HEVC fixture: the LGPL FFmpeg the server bundles has no software HEVC encoder (only
NVENC, QSV and VAAPI), and an iPhone recording is no fixture. The playback rule for a QuickTime
movie does not depend on its codec, see `originalPlaysHere` in `valbum_ui/lib/video_view.dart`.

`generate.sh` writes them with the FFmpeg program of `org.bytedeco:ffmpeg`, which JavaCPP extracts
the first time a transcode runs:

```
FFMPEG_DIR=~/.javacpp/cache/ffmpeg-5.1.2-1.5.8-linux-x86_64.jar/org/bytedeco/ffmpeg/linux-x86_64 \
  sh image-server/src/test/fixtures/video/generate.sh
```
