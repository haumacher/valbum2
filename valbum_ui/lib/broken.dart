/// Files that cannot be read, the app half of issue #253.
///
/// A file with the extension of a photograph or a video that the server's
/// background work could not analyse — a recording cut off before its index, a
/// truncated download — is not left out of its album: the server answers it as
/// a *broken* part ([ImagePart.broken] carries the reason) with its name, its
/// kind as the extension says it, its size in bytes ([ImagePart.fileSize]) and
/// the file's modification time as its date — and nothing that would have to
/// be read out of the file: no size in pixels, no position, no faces.
///
/// The app shows such a part as a square tile with a broken-picture (or a
/// broken-video) icon and the file name, never asking for a thumbnail, a
/// teaser or a preview of it, and the viewer shows a page saying what the file
/// is and why it cannot be shown instead of decoding it or starting a player.
/// Its original can still be downloaded, and it can be rated as trash like
/// any other file.
library;

import 'package:flutter/material.dart';

import 'client.dart' show extensionOf;
import 'l10n/app_localizations.dart';
import 'offline.dart' show formatBytes;
import 'resource.dart';

/// The extensions (lower case) of the video containers the server takes, see
/// `uploadExtensions` in `media_picker.dart`.
///
/// The server already answers a broken part with the kind its extension says;
/// the extension is asked as well so that the icon is right even for a kind
/// an older answer left at [ImageKind.image].
const Set<String> videoExtensions = {
  "mp4",
  "mov",
  "m4v",
  "3gp",
  "mts",
  "m2ts",
  "avi",
  "mkv",
  "webm",
};

/// What an [ImagePart] says about whether there is a picture to show.
extension BrokenPart on ImagePart {
  /// Whether this is a file that cannot be read, see the library comment.
  bool get isBroken => broken.isNotEmpty;

  /// Whether there is a picture to show, ask a rendition of, turn, crop or
  /// make the album's picture: neither a photograph of a collection that is
  /// gone (issue #221) nor a file that cannot be read (issue #253).
  bool get hasPicture => !missing && broken.isEmpty;
}

/// Whether the broken [image] is a video, by its kind and its extension.
bool brokenIsVideo(ImagePart image) =>
    image.kind != ImageKind.image ||
    videoExtensions.contains(extensionOf(image.name));

/// The icon of the broken [image]: a broken picture, or a crossed-out camera
/// for a video.
IconData brokenIcon(ImagePart image) =>
    brokenIsVideo(image) ? Icons.videocam_off : Icons.broken_image;

/// The tile of the broken [image], filling a box of [width] x [height].
///
/// The layout lays a broken part out square, see `Img` in `album_layout.dart`:
/// it has no size in pixels. Nothing is fetched for it.
Widget brokenTile(ImagePart image, double width, double height) => SizedBox(
      key: ValueKey("broken-tile-${image.name}"),
      width: width,
      height: height,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: Colors.grey.shade900,
          border: Border.all(color: Colors.white24),
        ),
        child: Padding(
          padding: const EdgeInsets.all(6),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Flexible(
                child: FittedBox(
                  child: Icon(
                    brokenIcon(image),
                    key: Key(brokenIsVideo(image)
                        ? "broken-icon-video"
                        : "broken-icon-image"),
                    size: 40,
                    color: Colors.white54,
                  ),
                ),
              ),
              const SizedBox(height: 4),
              Flexible(
                child: Text(
                  image.name,
                  key: const Key("broken-tile-name"),
                  textAlign: TextAlign.center,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(color: Colors.white70, fontSize: 12),
                ),
              ),
            ],
          ),
        ),
      ),
    );

/// What the viewer shows of the broken [image] instead of its picture: the
/// icon, the file name, why it cannot be read — the server's own words — and
/// its size.
///
/// Plain text on the page and not part of the viewer's gesture surface, so it
/// can be selected and copied, see `selectable.dart`.
class BrokenFileInfo extends StatelessWidget {
  final ImagePart image;

  const BrokenFileInfo(this.image, {super.key});

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    const detail = TextStyle(color: Colors.white70, fontSize: 14);
    return Padding(
      key: const Key("broken-viewer"),
      padding: const EdgeInsets.symmetric(horizontal: 72, vertical: 64),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 520),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              brokenIcon(image),
              key: Key(brokenIsVideo(image)
                  ? "broken-viewer-icon-video"
                  : "broken-viewer-icon-image"),
              size: 96,
              color: Colors.white54,
            ),
            const SizedBox(height: 16),
            Text(
              image.name,
              key: const Key("broken-viewer-name"),
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.white, fontSize: 20),
            ),
            const SizedBox(height: 8),
            Text(
              brokenIsVideo(image)
                  ? l10n.brokenVideoExplanation
                  : l10n.brokenImageExplanation,
              textAlign: TextAlign.center,
              style: detail,
            ),
            const SizedBox(height: 16),
            Text(
              l10n.brokenFileReason(image.broken),
              key: const Key("broken-viewer-reason"),
              textAlign: TextAlign.center,
              style: detail,
            ),
            if (image.fileSize > 0) ...[
              const SizedBox(height: 4),
              Text(
                l10n.brokenFileSize(formatBytes(image.fileSize)),
                key: const Key("broken-viewer-size"),
                textAlign: TextAlign.center,
                style: detail,
              ),
            ],
          ],
        ),
      ),
    );
  }
}
