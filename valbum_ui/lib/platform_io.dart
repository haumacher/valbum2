/// The platform pieces of the offline support on everything but the web.
library;

import 'dart:io';

import 'package:file_selector/file_selector.dart';
import 'package:flutter/foundation.dart';

import 'background.dart';
import 'client.dart';
import 'downloads.dart';
import 'background_workmanager.dart';
import 'connectivity.dart';
import 'connectivity_plugin.dart';
import 'contact_session.dart';
import 'device_code_scanner.dart';
import 'device_code_scanner_plugin.dart';
import 'offline.dart';
import 'offline_file.dart';
import 'phone_contacts.dart';
import 'photo_library.dart';
import 'notices.dart';
import 'photo_library_manager.dart';
import 'wakelock.dart';
import 'wakelock_plugin.dart';

/// The cache the app uses when it is not told otherwise: a directory on the
/// device, so that what was seen survives the app being closed.
OfflineCache defaultOfflineCache() => FileOfflineCache.underSupportDirectory();

/// Whether the given error is the network itself failing.
///
/// `package:http` wraps most of these in a `ClientException`, but not all of
/// them (a failed DNS lookup surfaces as a plain `SocketException` on some
/// platforms), so the `dart:io` type is checked as well.
bool isSocketError(Object error) => error is SocketException;

/// The browser's `Blob` behind an object URL: there is none off the web, so a
/// picked file is read through its own `openRead`, see `browser_upload.dart`
/// and issue #170.
Future<Object?> blobOfObjectUrl(String url) async => null;

/// The hash of a browser `Blob`, see `browser_upload.dart`; never asked off
/// the web, where no [UploadFile.blob] is ever set.
Future<String> sha256OfBlob(Object blob, {bool useWebCrypto = true}) =>
    throw UnimplementedError();

/// The upload of browser `Blob`s, see `browser_upload.dart`; never asked off
/// the web, where no [UploadFile.blob] is ever set.
Future<({int status, String body})> sendBlobForm(
  Uri uri,
  List<({String name, Object blob})> parts, {
  String method = "PUT",
  Map<String, String> headers = const {},
  void Function(int sent, int total)? onProgress,
  bool Function()? cancelled,
}) =>
    throw UnimplementedError();

/// The photo library of the device, see [PhotoLibrary].
///
/// Only Android and iOS have a camera roll to watch; a desktop has a file
/// system and no library, and says so rather than offering a switch that
/// would never do anything (issue #30).
///
/// A [background] library never asks the user for anything (issue #169): a
/// background run has no screen to ask on, so it reads what was granted and
/// declines where that is not enough, see [PhotoManagerLibrary.interactive].
PhotoLibrary defaultPhotoLibrary({bool background = false}) =>
    Platform.isAndroid || Platform.isIOS
        ? PhotoManagerLibrary(interactive: !background)
        : const UnavailablePhotoLibrary(NoPhotoLibraryPlatform());

/// What keeps the screen awake while an upload runs, see [Wakelock].
///
/// Only a phone locks its screen out from under a running transfer and only
/// there is the plugin asked (issue #63); a desktop keeps its network while
/// the display sleeps.
Wakelock defaultWakelock() => Platform.isAndroid || Platform.isIOS
    ? const PluginWakelock()
    : const NoWakelock();

/// What reads a device code off the camera, see [DeviceCodeScanner].
///
/// Only Android and iOS: a phone is where the typing hurts and where there is
/// a camera in the right place (issue #66). A desktop keeps the typed field —
/// its webcam points at the person, not at the other screen — and the
/// `mobile_scanner` plugin is never asked there.
///
/// `Platform.isAndroid` rather than `defaultTargetPlatform`, exactly as
/// [defaultWakelock]: this asks which *machine* the code runs on, and a widget
/// test runs on a desktop while it says it is a phone.
DeviceCodeScanner defaultDeviceCodeScanner() =>
    Platform.isAndroid || Platform.isIOS
        ? const PluginDeviceCodeScanner()
        : const NoDeviceCodeScanner();

/// The network the device is on, see [ConnectivitySource].
///
/// Only Android and iOS have a phone plan the Wi-Fi-only sync protects, and
/// only there is the plugin asked; a desktop cannot be metered in a way this
/// app knows about and answers [NetworkKind.unknown], which allows the sync
/// (issue #36).
///
/// `Platform.isAndroid` rather than `defaultTargetPlatform`, exactly as
/// [defaultPhotoLibrary]: this asks which *machine* the code runs on, and a
/// widget test runs on a desktop while it says it is a phone — a background
/// run in a test must reach no plugin channel.
ConnectivitySource defaultConnectivity() => Platform.isAndroid || Platform.isIOS
    ? PluginConnectivity()
    : const UnknownConnectivity();

/// The platform's periodic background execution, see [BackgroundScheduler].
///
/// Only Android and iOS run background work this app can use; a desktop says
/// so instead of promising a sync that never happens (issue #32).
///
/// `defaultTargetPlatform` rather than `Platform.isAndroid`: this is the same
/// question `photo_manager` is asked, and the Flutter constant is the one a
/// test can override.
BackgroundScheduler defaultBackgroundScheduler() =>
    defaultTargetPlatform == TargetPlatform.android ||
            defaultTargetPlatform == TargetPlatform.iOS
        ? WorkmanagerScheduler()
        : const UnavailableBackgroundScheduler(NoBackgroundSyncHere());

/// Runs [task] as the platform's background task, see
/// [backgroundSyncDispatcher].
///
/// Named here rather than imported from `workmanager` directly, so that the
/// web build never sees the plugin's Dart code.
void executeBackgroundTask(Future<bool> Function() task) =>
    runWorkmanagerTask(task);

/// Where a downloaded original goes off the web, see [DownloadSaver].
///
/// A phone keeps its pictures in its photo library, so that is where they go
/// (issue #164); a desktop keeps files and is asked where. `Platform.isAndroid`
/// rather than `defaultTargetPlatform`, exactly as [defaultPhotoLibrary]: this
/// asks which machine the code runs on.
DownloadSaver defaultDownloadSaver() => Platform.isAndroid || Platform.isIOS
    ? const PhotoLibraryDownloadSaver()
    : const FileDialogDownloadSaver();

/// The phone's address book to pick a recipient from (issue #201): a phone
/// has one, a desktop has none the app can reach.
PhoneContacts? defaultPhoneContacts() =>
    Platform.isAndroid || Platform.isIOS ? const PluginPhoneContacts() : null;

/// Writes [stream] into [file] chunk by chunk, as the chunks arrive (issue
/// #209): each is written before the next is taken, so no more than one
/// chunk of the download is ever held, and a slow disk slows the transfer
/// down rather than piling the answer up in memory.
///
/// [progress] is told every chunk and asked before each whether the user
/// cancelled. A transfer that breaks off — a lost connection, a cancel, a
/// full disk — leaves no partial file behind: it is deleted and the failure
/// thrown on.
Future<void> streamIntoFile(
  DownloadStream stream,
  File file, {
  DownloadProgress? progress,
}) async {
  progress?.announce(stream.length);
  var out = await file.open(mode: FileMode.write);
  try {
    await for (var chunk in stream.content) {
      progress?.check();
      await out.writeFrom(chunk);
      progress?.add(chunk.length);
    }
    await out.close();
  } catch (_) {
    await out.close();
    if (await file.exists()) {
      await file.delete();
    }
    rethrow;
  }
}

/// Saves a download into the photo library of a phone, see
/// [saveFileToPhotoLibrary].
///
/// A zip is no picture and would be a dead file on a phone, so a selection is
/// saved original by original instead ([keepsArchives]). Each original is
/// streamed into a temporary file and handed to the library from there
/// (issue #209), the temporary file removed afterwards, whatever happened.
class PhotoLibraryDownloadSaver extends DownloadSaver {
  const PhotoLibraryDownloadSaver();

  @override
  bool get keepsArchives => false;

  @override
  Future<SaveOutcome> save(DownloadSource source,
      {DownloadProgress? progress}) async {
    await askToAddToPhotoLibrary();
    var stream = await source.open();
    var directory = await Directory.systemTemp.createTemp("valbum-download");
    try {
      var file = File("${directory.path}/${source.name}");
      await streamIntoFile(stream, file, progress: progress);
      await saveFileToPhotoLibrary(source.name, file, stream.contentType);
    } finally {
      await directory.delete(recursive: true);
    }
    return SaveOutcome.saved;
  }
}

/// Where the desktop's save dialog put the file to be saved, `null` where it
/// was closed without a choice.
typedef SaveLocationChooser = Future<String?> Function(String suggestedName);

Future<String?> _saveDialog(String suggestedName) async =>
    (await getSaveLocation(suggestedName: suggestedName))?.path;

/// Saves a download where the user says, on a desktop.
///
/// The platform's own save dialog comes first, the file's name suggested; a
/// dialog closed without a choice is [SaveOutcome.cancelled] and nothing is
/// fetched. The answer is then written into the chosen file as it arrives
/// ([streamIntoFile], issue #209). [chooseLocation] is the dialog, replaced
/// by a test.
class FileDialogDownloadSaver extends DownloadSaver {
  final SaveLocationChooser chooseLocation;

  const FileDialogDownloadSaver({this.chooseLocation = _saveDialog});

  @override
  Future<SaveOutcome> save(DownloadSource source,
      {DownloadProgress? progress}) async {
    var location = await chooseLocation(source.name);
    if (location == null) {
      return SaveOutcome.cancelled;
    }
    var stream = await source.open();
    await streamIntoFile(stream, File(location), progress: progress);
    return SaveOutcome.saved;
  }
}

/// Replaces the page the app runs in by [url]: nothing, off the web.
///
/// There is no app base to leave on a phone or a desktop — an invitation URL
/// is opened in a browser there, or pasted into the server field, and the app
/// simply carries on with the token it was given, see `invitation.dart`.
void leaveForUrl(String url) {}

/// Rewrites the location of the page the app runs in: nothing, off the web.
///
/// There is no address bar on a phone or a desktop, and no redirect that could
/// have written a reason into one, see issue #88.
void rewritePageUrl(String url) {}

/// What this machine says about itself, for the header of a diagnostics log.
String platformDescription() =>
    "${Platform.operatingSystem} ${Platform.operatingSystemVersion}";

/// Resolves [host] explicitly and answers what each address family said, one
/// line each, for the entry of a failed connection test (issues #58, #184).
///
/// Dart asks IPv4 and IPv6 separately (see `staggeredLookup` in the SDK), and
/// a name that is a CNAME to an AAAA-only address fails in a way that the
/// headline of the exception does not explain: one of the two queries answers
/// and the other does not, or the OS refuses both. Each query is therefore
/// made here in its own right, and its addresses — or its error, with the
/// errno the OS gave — are a line of their own.
Future<List<String>> hostResolution(String host) async {
  var lines = <String>[];
  for (var family in const [
    ("IPv4", InternetAddressType.IPv4),
    ("IPv6", InternetAddressType.IPv6),
  ]) {
    try {
      var addresses = await InternetAddress.lookup(host, type: family.$2);
      lines.add(
        "Lookup ${family.$1} $host: "
        "${addresses.isEmpty ? "no address" : addresses.map((a) => a.address).join(", ")}",
      );
    } catch (error) {
      lines.add("Lookup ${family.$1} $host failed: $error");
    }
  }
  return lines;
}

/// Where who one is on a personal share link is kept off the web: in memory
/// (issue #202). A link is opened in a browser there, so the app never runs a
/// link session of its own and has nothing to remember.
ContactCredentialStore defaultContactCredentialStore() =>
    ContactCredentialStore.memory();
