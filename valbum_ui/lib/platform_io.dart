/// The platform pieces of the offline support on everything but the web.
library;

import 'dart:io';

import 'package:flutter/foundation.dart';

import 'background.dart';
import 'background_workmanager.dart';
import 'connectivity.dart';
import 'connectivity_plugin.dart';
import 'device_code_scanner.dart';
import 'device_code_scanner_plugin.dart';
import 'diagnostics.dart';
import 'offline.dart';
import 'offline_file.dart';
import 'photo_library.dart';
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

/// The photo library of the device, see [PhotoLibrary].
///
/// Only Android and iOS have a camera roll to watch; a desktop has a file
/// system and no library, and says so rather than offering a switch that
/// would never do anything (issue #30).
PhotoLibrary defaultPhotoLibrary() => Platform.isAndroid || Platform.isIOS
    ? PhotoManagerLibrary()
    : const UnavailablePhotoLibrary(
        "No photo library on this platform - camera-roll sync runs on "
        "Android and iOS.",
      );

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
        : const UnavailableBackgroundScheduler(
            "Background sync is not available on this platform; the camera "
            "roll syncs while the app is open.",
          );

/// Runs [task] as the platform's background task, see
/// [backgroundSyncDispatcher].
///
/// Named here rather than imported from `workmanager` directly, so that the
/// web build never sees the plugin's Dart code.
void executeBackgroundTask(Future<bool> Function() task) =>
    runWorkmanagerTask(task);

/// Replaces the page the app runs in by [url]: nothing, off the web.
///
/// There is no app base to leave on a phone or a desktop — an invitation URL
/// is opened in a browser there, or pasted into the server field, and the app
/// simply carries on with the token it was given, see `invitation.dart`.
void leaveForUrl(String url) {}

/// What this machine says about itself, for the header of a diagnostics log.
String platformDescription() =>
    "${Platform.operatingSystem} ${Platform.operatingSystemVersion}";

/// Resolves [host] explicitly and writes what each address family answered
/// into [log] (issue #58).
///
/// Dart asks IPv4 and IPv6 separately (see `staggeredLookup` in the SDK), and
/// a name that is a CNAME to an AAAA-only address fails in a way that the
/// headline of the exception does not explain: one of the two queries answers
/// and the other does not, or the OS refuses both. Each query is therefore
/// made here in its own right, and its addresses — or its error, with the
/// errno the OS gave — are logged as a line of their own.
Future<void> logHostResolution(DiagnosticsLog log, String host) async {
  for (var family in const [
    ("IPv4", InternetAddressType.IPv4),
    ("IPv6", InternetAddressType.IPv6),
  ]) {
    try {
      var addresses = await InternetAddress.lookup(host, type: family.$2);
      log.add(
        "lookup ${family.$1} $host -> "
        "${addresses.isEmpty ? "no address" : addresses.map((a) => a.address).join(", ")}",
      );
    } catch (error) {
      log.add("lookup ${family.$1} $host !! $error");
    }
  }
}
