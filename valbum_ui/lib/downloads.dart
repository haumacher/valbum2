/// Taking a copy of an original out of the album, see issues #164 and #209.
///
/// The `download` right was enforced on the server long before the app could
/// use it: it decided which rendition the viewer loads (#95) and the rights
/// sentence promised "you may look and download", while Flutter web draws into
/// a canvas, so the browser's own "Save image as…" saves nothing. A file is
/// therefore described by a [DownloadSource] of the [VAlbumClient] and handed
/// to a [DownloadSaver], the seam each platform fills in `platform_*.dart` —
/// and no platform ever holds the whole file in memory (issue #209):
///
/// * the **web** hands the browser an address it downloads from by itself —
///   a signed address of issue #185 for one original, a one-time download
///   ticket for an archive — so the browser streams it to disk with its own
///   progress and its own cancel;
/// * a **phone** streams each original into a temporary file and puts it into
///   the device's photo library from there — a zip there would be a dead
///   file, so a selection is saved one original at a time
///   ([DownloadSaver.keepsArchives]);
/// * a **desktop** asks where to save it first and then writes the answer
///   into that file chunk by chunk as it arrives.
library;

import 'dart:async';

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

/// Thrown where the user cancelled a download in progress, see
/// [DownloadProgress.cancel]; what was written of the file is gone.
class DownloadCancelled implements Exception {
  const DownloadCancelled();

  @override
  String toString() => "DownloadCancelled";
}

/// How far a download has come, and the user's way to stop it (issue #209).
///
/// Filled by a [DownloadSaver] that transfers the bytes itself — a desktop,
/// a phone — and shown by [runDownload]; a browser shows its own.
class DownloadProgress extends ChangeNotifier {
  int _file = 0;
  int _files = 1;
  int _received = 0;
  int? _total;
  bool _cancelled = false;

  /// The file in transfer, counted from one.
  int get file => _file;

  /// How many files the download takes.
  int get files => _files;

  /// The bytes of the current file received so far.
  int get received => _received;

  /// How long the current file is, where the server said so.
  int? get total => _total;

  /// Whether the user asked to stop.
  bool get cancelled => _cancelled;

  /// The transfer of file [file] of [files] begins.
  void startFile(int file, int files) {
    _file = file;
    _files = files;
    _received = 0;
    _total = null;
    notifyListeners();
  }

  /// The server announced the length of the current file.
  void announce(int? length) {
    _total = length;
    notifyListeners();
  }

  /// [bytes] more bytes of the current file were written.
  void add(int bytes) {
    _received += bytes;
    notifyListeners();
  }

  /// Stops the download at the next chunk.
  void cancel() {
    _cancelled = true;
    notifyListeners();
  }

  /// Throws [DownloadCancelled] where the user asked to stop.
  void check() {
    if (_cancelled) {
      throw const DownloadCancelled();
    }
  }
}

/// Where a downloaded file goes on this platform, see the library doc.
abstract class DownloadSaver {
  const DownloadSaver();

  /// Whether a zip archive is kept as it is.
  ///
  /// `false` on a phone, whose photo library takes photographs and videos and
  /// nothing else: a selection is then fetched and saved original by original.
  bool get keepsArchives => true;

  /// Whether the platform shows the progress of a download itself — the
  /// browser's downloads do — so that the app shows none of its own.
  bool get showsProgress => false;

  /// Takes [source] by the way this platform takes files; throws where that
  /// failed, [DownloadCancelled] where [progress] was cancelled.
  Future<SaveOutcome> save(DownloadSource source, {DownloadProgress? progress});
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
  DownloadProgress? progress,
}) async {
  var target = saver ?? downloadSaver;
  if (names.length > 1 && target.keepsArchives) {
    progress?.startFile(1, 1);
    var outcome = await target.save(
      client.archiveDownload(path, names, archiveName),
      progress: progress,
    );
    return DownloadResult(
      count: names.length,
      name: archiveName,
      cancelled: outcome == SaveOutcome.cancelled,
    );
  }
  var saved = 0;
  String? last;
  for (var name in names) {
    progress?.startFile(saved + 1, names.length);
    var source = client.originalDownload("${client.baseUrl(path)}/$name");
    if (await target.save(source, progress: progress) ==
        SaveOutcome.cancelled) {
      return DownloadResult(count: saved, cancelled: true);
    }
    saved++;
    last = source.name;
  }
  return DownloadResult(count: saved, name: saved == 1 ? last : null);
}

/// Saves the one original of the image at [imageUrl] (the viewer's
/// "Download original", issue #164).
Future<DownloadResult> downloadOne(
  VAlbumClient client,
  String imageUrl, {
  DownloadSaver? saver,
  DownloadProgress? progress,
}) async {
  var source = client.originalDownload(imageUrl);
  progress?.startFile(1, 1);
  var outcome = await (saver ?? downloadSaver).save(source, progress: progress);
  return DownloadResult(
    count: outcome == SaveOutcome.saved ? 1 : 0,
    name: source.name,
    cancelled: outcome == SaveOutcome.cancelled,
  );
}

/// The file name of the archive of an album called [title].
///
/// The title as it stands, with what no file system takes replaced; an album
/// without a title is called `album`.
String archiveNameOf(String title) {
  var name = title.trim().replaceAll(RegExp(r'[/\\:*?"<>|\x00-\x1F]'), "_");
  return "${name.isEmpty ? "album" : name}.zip";
}

/// How long a download runs before its progress line is shown: a photograph
/// is saved before the line could be read.
const Duration downloadProgressDelay = Duration(milliseconds: 400);

/// Runs [download] as a user's download: refused offline, spoken afterwards.
///
/// The refusal is the app's usual one ([refuseWhileOffline]); a failure —
/// the server's own sentence, a lost connection, a photo library that said
/// no — is said in a red snack bar, and a success says what was saved. A save
/// dialog the user cancelled says nothing. While the bytes go through the app
/// (not in a browser, which shows its own, see [DownloadSaver.showsProgress])
/// a snack bar says how far it has come and offers to cancel (issue #209).
Future<void> runDownload(
  BuildContext context,
  Future<DownloadResult> Function(DownloadProgress progress) download,
) async {
  if (refuseWhileOffline(context)) {
    return;
  }
  var l10n = AppLocalizations.of(context)!;
  var messenger = ScaffoldMessenger.of(context);
  var progress = DownloadProgress();
  ScaffoldFeatureController<SnackBar, SnackBarClosedReason>? shown;
  var timer = downloadSaver.showsProgress
      ? null
      : Timer(downloadProgressDelay, () {
          shown = messenger.showSnackBar(
            SnackBar(
              key: const Key("download-progress"),
              content: ListenableBuilder(
                listenable: progress,
                builder: (context, _) => DownloadProgressLine(progress),
              ),
              duration: const Duration(days: 1),
              action: SnackBarAction(
                key: const Key("download-cancel"),
                label: l10n.cancel,
                onPressed: progress.cancel,
              ),
            ),
          );
        });
  DownloadResult result;
  try {
    result = await download(progress);
  } on DownloadCancelled {
    messenger.showSnackBar(
      SnackBar(
        content: Text(
          l10n.downloadCancelled,
          key: const Key("download-cancelled"),
        ),
      ),
    );
    return;
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
  } finally {
    timer?.cancel();
    shown?.close();
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

/// The line of a download in progress: which file, and how much of it.
class DownloadProgressLine extends StatelessWidget {
  final DownloadProgress progress;

  const DownloadProgressLine(this.progress, {super.key});

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var total = progress.total;
    var received = formatBytes(progress.received);
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          total == null
              ? l10n.downloadProgress(progress.file, progress.files, received)
              : l10n.downloadProgressOf(
                  progress.file, formatBytes(total), progress.files, received),
          key: const Key("download-progress-line"),
        ),
        // Determinate only: an endless animation would never let a screen
        // settle, and an unknown length has nothing to measure against.
        if (total != null && total > 0) ...[
          const SizedBox(height: 4),
          LinearProgressIndicator(
            value: (progress.received / total).clamp(0.0, 1.0),
          ),
        ],
      ],
    );
  }
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
