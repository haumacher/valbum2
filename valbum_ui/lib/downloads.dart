/// Taking a copy of an original out of the album, see issue #164.
///
/// The `download` right was enforced on the server long before the app could
/// use it: it decided which rendition the viewer loads (#95) and the rights
/// sentence promised "you may look and download", while Flutter web draws into
/// a canvas, so the browser's own "Save image as…" saves nothing. The bytes are
/// therefore fetched through the [VAlbumClient] — the bearer travels in the
/// header, which no plain link could carry — and handed to a [DownloadSaver],
/// the seam each platform fills in `platform_*.dart`:
///
/// * the **web** hands a blob to the browser's download, under the file's own
///   name;
/// * a **phone** puts a photograph or a video into the device's photo library,
///   where a phone keeps its pictures — a zip there would be a dead file, so a
///   selection is saved one original at a time ([DownloadSaver.keepsArchives]);
/// * a **desktop** asks where to save it.
library;

import 'package:flutter/material.dart';

import 'client.dart';
import 'l10n/app_localizations.dart';
import 'notices.dart';
import 'offline.dart';
import 'photo_library.dart';
import 'platform.dart';

/// What became of a file handed to a [DownloadSaver].
enum SaveOutcome {
  /// The file is where the platform keeps such files.
  saved,

  /// The user declined, in a save dialog: nothing to say, nothing failed.
  cancelled,
}

/// Where a downloaded file goes on this platform, see the library doc.
abstract class DownloadSaver {
  const DownloadSaver();

  /// Whether a zip archive is kept as it is.
  ///
  /// `false` on a phone, whose photo library takes photographs and videos and
  /// nothing else: a selection is then fetched and saved original by original.
  bool get keepsArchives => true;

  /// Hands [file] to the platform; throws where that failed.
  Future<SaveOutcome> save(DownloadedFile file);
}

/// The [DownloadSaver] of this app (issue #164).
///
/// A seam, like `browserMenu` in `persons_view.dart`: a widget test runs on the
/// desktop build and would open a save dialog, so a test replaces this with a
/// double that records what it was handed.
DownloadSaver downloadSaver = defaultDownloadSaver();

/// What a download achieved, for the line said afterwards.
class DownloadResult {
  /// How many originals were saved.
  final int count;

  /// The name of the one file saved — an original or the archive — `null`
  /// where several were saved one by one.
  final String? name;

  /// Whether the user declined in a save dialog.
  final bool cancelled;

  const DownloadResult(
      {required this.count, this.name, this.cancelled = false});
}

/// Fetches the originals [names] of the album at [path] and saves them.
///
/// One name is its own original, under its own name. Several are one zip
/// archive called [archiveName] where the saver keeps archives, and one
/// original after the other where it does not. The first failure ends the
/// whole download and is thrown: what was already saved stays saved.
Future<DownloadResult> downloadOriginals(
  VAlbumClient client,
  List<String> path,
  List<String> names, {
  required String archiveName,
  DownloadSaver? saver,
}) async {
  var target = saver ?? downloadSaver;
  if (names.length > 1 && target.keepsArchives) {
    var archive = await client.downloadArchive(path, names, archiveName);
    var outcome = await target.save(archive);
    return DownloadResult(
      count: names.length,
      name: archive.name,
      cancelled: outcome == SaveOutcome.cancelled,
    );
  }
  var saved = 0;
  String? last;
  for (var name in names) {
    var file = await client.downloadOriginal("${client.baseUrl(path)}/$name");
    if (await target.save(file) == SaveOutcome.cancelled) {
      return DownloadResult(count: saved, cancelled: true);
    }
    saved++;
    last = file.name;
  }
  return DownloadResult(count: saved, name: saved == 1 ? last : null);
}

/// The file name of the archive of an album called [title].
///
/// The title as it stands, with what no file system takes replaced; an album
/// without a title is called `album`.
String archiveNameOf(String title) {
  var name = title.trim().replaceAll(RegExp(r'[/\\:*?"<>|\x00-\x1F]'), "_");
  return "${name.isEmpty ? "album" : name}.zip";
}

/// Runs [download] as a user's download: refused offline, spoken afterwards.
///
/// The refusal is the app's usual one ([refuseWhileOffline]); a failure —
/// the server's own sentence, a lost connection, a photo library that said
/// no — is said in a red snack bar, and a success says what was saved. A save
/// dialog the user cancelled says nothing.
Future<void> runDownload(
  BuildContext context,
  Future<DownloadResult> Function() download,
) async {
  if (refuseWhileOffline(context)) {
    return;
  }
  var l10n = AppLocalizations.of(context)!;
  var messenger = ScaffoldMessenger.of(context);
  DownloadResult result;
  try {
    result = await download();
  } catch (error) {
    messenger.showSnackBar(
      SnackBar(
        content: Text(
          l10n.downloadFailed(downloadErrorText(error, l10n)),
          key: const Key("download-failed"),
        ),
        backgroundColor: Colors.red.shade700,
        duration: const Duration(seconds: 8),
      ),
    );
    return;
  }
  if (result.cancelled && result.count == 0) {
    return;
  }
  var name = result.name;
  messenger.showSnackBar(
    SnackBar(
      content: Text(
        name != null
            ? l10n.downloadSaved(name)
            : l10n.downloadSavedCount(result.count),
        key: const Key("download-saved"),
      ),
    ),
  );
}

/// What a failed download says: the server's sentence where it spoke.
String downloadErrorText(Object error, AppLocalizations l10n) =>
    switch (error) {
      VAlbumException(message: var message) => message,
      PhotoLibraryException(notice: var notice) => noticeText(notice, l10n),
      _ when VAlbumClient.isTransportFailure(error) =>
        VAlbumClient.transportMessage(error),
      _ => "$error",
    };
