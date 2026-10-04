# OpenCV/FFmpeg native loading pitfalls

- **No GUI libraries**: `FaceDetection.loadOpenCv` loads the 28 libraries of `OPENCV_JAVA_CLOSURE`
  one by one instead of `Loader.load(opencv_java.class)`, which would pull in GTK via `highgui`.
  `TestFaceDetectionLoadsNoGui` guards it; the Docker image has no GTK.
- **YuNet 2023mar does not work** on the bundled OpenCV 4.7 — use 2022mar.
- **FFmpeg child process**: `VideoRenditions.program` sets `LD_LIBRARY_PATH` (ARM uses `DT_RUNPATH`)
  and creates soname symlinks (`libva.so` → `libva.so.2`), or the child fails to start.
- `libopenh264` needs `-slices` on FFmpeg 6.0 or it encodes single-threaded.
