# Video containers (issues #189 and #192)

Ten tiny generated videos — no real recording. One second at 10 fps, each frame four quadrants
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

## The containers only FFmpeg reads (issue #192)

metadata-extractor reads none of these (an AVI only partly), so the server asks the bundled FFmpeg
(`VideoProbe`). All are 96 × 64, four quadrants, one second.

| File                              | Container       | Streams                        | Dated by                                     |
| --------------------------------- | --------------- | ------------------------------ | -------------------------------------------- |
| `clip.mts`                        | MPEG-TS (AVCHD) | H.264 + AC-3                   | H.264 SEI `MDPM` 2024-05-17 12:34:56 +02:00  |
| `interlaced.m2ts`                 | MPEG-TS         | MPEG-2, field-coded, 25 fps    | nothing: its modification time               |
| `clip.avi`                        | AVI             | Motion JPEG + PCM 8 kHz        | INFO `ICRD` 2005-06-18 (midnight, space zone) |
| `clip.mkv`                        | Matroska        | H.264 + Opus                   | `DateUTC` 2024-05-17 12:36:00 UTC            |
| `screen-2024-05-17_12-37-00.webm` | WebM            | VP8                            | its name (#102), 12:37:00 in the space zone  |

`clip.mts` carries the recording time a Sony or Panasonic camcorder writes: an SEI
`user_data_unregistered` message (UUID `17ee8c60-f84d-11d9-8cd6-0800200c9a66`, then `MDPM`) with
tag `0x18` (zone byte, year, month) and tag `0x19` (day, hour, minute, second) in BCD, inserted by
FFmpeg's `h264_metadata` bitstream filter. The interlaced fixture is MPEG-2 (as an HDV camcorder
writes it) because `libopenh264`, the only H.264 encoder of the LGPL build, encodes progressive
frames only — an interlaced AVCHD H.264 clip cannot be generated here; FFmpeg reads this one as
"bottom first", which is what the deinterlacing rule asks. An AVI's `IDIT` chunk (what a Canon or
Nikon writes) is read by FFmpeg as `creation_time` too, but FFmpeg cannot write one, so no fixture
carries it.
