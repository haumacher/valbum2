#!/bin/sh
# Writes the video fixtures of issue #189 with the FFmpeg program the server bundles
# (org.bytedeco:ffmpeg, the LGPL build: libopenh264 for H.264, h263, aac, libopencore_amrnb).
#
#   FFMPEG_DIR=~/.javacpp/cache/ffmpeg-5.1.2-1.5.8-linux-x86_64.jar/org/bytedeco/ffmpeg/linux-x86_64 \
#     sh image-server/src/test/fixtures/video/generate.sh
#
# The directory is where JavaCPP extracted the program the first time the server (or a test) ran
# a transcode; the program needs its own libraries beside it, hence LD_LIBRARY_PATH.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
: "${FFMPEG_DIR:?name the directory of the bundled ffmpeg program}"
export LD_LIBRARY_PATH="$FFMPEG_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
FF="$FFMPEG_DIR/ffmpeg -hide_banner -loglevel error -y"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# Four quadrants, upright: red top left, green top right, blue bottom left, yellow bottom right.
quadrants() { # width/2 height/2
	echo "color=c=red:s=$1x$2:r=10:d=1[a];color=c=lime:s=$1x$2:r=10:d=1[b];color=c=blue:s=$1x$2:r=10:d=1[c];color=c=yellow:s=$1x$2:r=10:d=1[d];[a][b]hstack[t];[c][d]hstack[u];[t][u]vstack"
}
SOUND="sine=frequency=440:sample_rate=8000:duration=1"

# A QuickTime movie, H.264 + AAC, recorded (mvhd) 2024-05-17 12:30:00 UTC.
$FF -f lavfi -i "$(quadrants 48 32)" -f lavfi -i "$SOUND" -c:v libopenh264 -b:v 100k \
	-c:a aac -b:a 16k -ac 1 -shortest -metadata creation_time=2024-05-17T12:30:00Z "$HERE/clip.mov"

# An iTunes video (ftyp M4V), H.264, recorded 2024-05-17 12:31:00 UTC.
$FF -f lavfi -i "$(quadrants 48 32)" -c:v libopenh264 -b:v 100k \
	-metadata creation_time=2024-05-17T12:31:00Z -f ipod "$HERE/clip.m4v"

# A 3GPP file of an older phone (ftyp 3gp4), H.263 128 x 96 + AMR-NB, recorded 12:32:00 UTC.
$FF -f lavfi -i "$(quadrants 64 48)" -f lavfi -i "$SOUND" -c:v h263 -b:v 64k \
	-c:a libopencore_amrnb -ar 8000 -ac 1 -b:a 12.2k -shortest \
	-metadata creation_time=2024-05-17T12:32:00Z "$HERE/clip.3gp"

# An iPhone-style movie: no mvhd time (FFmpeg writes 0, which is 1904-01-01), and the keys an
# iPhone writes into the meta box of the movie.
$FF -f lavfi -i "$(quadrants 48 32)" -c:v libopenh264 -b:v 100k -movflags use_metadata_tags \
	-metadata com.apple.quicktime.creationdate=2024-05-17T14:30:00+0200 \
	-metadata com.apple.quicktime.location.ISO6709=+48.1250+011.5700+520.000/ \
	-metadata com.apple.quicktime.make=Apple \
	-metadata "com.apple.quicktime.model=iPhone 15" "$HERE/apple.mov"

# A portrait movie as a phone stores one: the raster on its side (96 x 64), the track's matrix
# turning it a quarter clockwise (FFmpeg 5.1 writes the matrix only when remuxing).
$FF -f lavfi -i "$(quadrants 48 32)" -c:v libopenh264 -b:v 100k \
	-metadata creation_time=2024-05-17T12:33:00Z "$TMP/flat.mov"
$FF -i "$TMP/flat.mov" -c copy -metadata:s:v:0 rotate=90 \
	-metadata creation_time=2024-05-17T12:33:00Z "$HERE/rotated.mov"
