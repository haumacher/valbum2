/// The system picker of an upload, offering photographs **and videos**
/// (issue #197).
///
/// `ImagePicker.pickMultiImage` offered pictures only, so a browser — where it
/// is the only way to pick a file — could not upload a video at all. The
/// picker is now chosen per platform:
///
/// * the **web, Android and iOS** ask `ImagePicker.pickMultipleMedia`: on a
///   phone that is the system's photo picker over photographs and videos (the
///   gallery a user expects, where a document picker would show folders), in a
///   browser a file input accepting `image/*` and `video/*`, whose picked
///   files come back as `blob:` object URLs of the very `File` — the path of
///   issue #170, so a picked video is hashed by Web Crypto and sent from its
///   `Blob` and never passes through Dart. Nothing is resized: no size or
///   quality is asked for.
/// * a **desktop** opens `file_selector` directly (which is all
///   `image_picker`'s desktop implementations do), with the file types the
///   server takes, [uploadExtensions], as the first filter — the Windows
///   implementation of `pickMultipleMedia` names a fixed list without HEIC,
///   `.m4v` and `.3gp` — and "All files" as the second, so nothing the server
///   might take is out of reach.
///
/// The client filters nothing it was handed: a file the server does not take
/// is refused by the server, file by file, and named in the summary (#186).
library;

import 'package:file_selector/file_selector.dart';
import 'package:flutter/foundation.dart';
import 'package:image_picker/image_picker.dart';

import 'client.dart';
import 'l10n/app_localizations.dart';
import 'platform.dart';

/// The extensions of the files the server takes, the one place the app names
/// them (`PreviewCache.SUPPORTED_EXTENSIONS` on the server; #189 added the
/// video containers).
///
/// Only the desktop's file dialog is filtered by it, and only as its first
/// filter, see the library doc.
const List<String> uploadExtensions = [
  "jpg",
  "jpeg",
  "png",
  "heic",
  "heif",
  "mp4",
  "mov",
  "m4v",
  "3gp",
];

/// Lets the user pick the files of an upload; an empty list where they picked
/// nothing.
typedef MediaPicker = Future<List<XFile>> Function(AppLocalizations l10n);

/// The [MediaPicker] of this app (issue #197).
///
/// A seam, like `downloadSaver`: a widget test runs on the desktop build and
/// would open a file dialog, so a test replaces this with a double answering
/// the files it wants picked.
MediaPicker mediaPicker = pickPhotosAndVideos;

/// Whether this platform picks through `file_selector`, see the library doc.
bool get _isDesktop =>
    !kIsWeb &&
    (defaultTargetPlatform == TargetPlatform.linux ||
        defaultTargetPlatform == TargetPlatform.macOS ||
        defaultTargetPlatform == TargetPlatform.windows);

/// The system picker of this platform, see the library doc.
Future<List<XFile>> pickPhotosAndVideos(AppLocalizations l10n) {
  if (_isDesktop) {
    return openFiles(acceptedTypeGroups: desktopTypeGroups(l10n));
  }
  return ImagePicker().pickMultipleMedia();
}

/// The filters of the desktop's file dialog: the server's types, then
/// anything.
List<XTypeGroup> desktopTypeGroups(AppLocalizations l10n) => [
      XTypeGroup(
        label: l10n.pickerPhotosAndVideos,
        // GTK compares a pattern with the case it is given, and a camera
        // names its files `IMG_0001.JPG`; Windows and macOS ignore the case,
        // so the upper-case spelling is only added where it matters.
        extensions: [
          ...uploadExtensions,
          if (defaultTargetPlatform == TargetPlatform.linux)
            for (var extension in uploadExtensions) extension.toUpperCase(),
        ],
      ),
      XTypeGroup(label: l10n.pickerAllFiles),
    ];

/// The [UploadFile]s of the picked [files].
///
/// In a browser each carries its `Blob` ([blobOfObjectUrl]), so that it is
/// hashed and sent by the browser itself rather than through the page's one
/// Dart thread (issue #170) — a video above all, being the large file that
/// path exists for; everywhere else there is none, and the file is read
/// through `openRead`.
Future<List<UploadFile>> uploadFilesOf(List<XFile> files) async => [
      for (var file in files)
        UploadFile(
          name: file.name,
          length: await file.length(),
          openRead: file.openRead,
          blob: await blobOfObjectUrl(file.path),
        ),
    ];
