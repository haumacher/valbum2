/// The platform pieces of the offline support on the web, and the one
/// navigation that leaves the Flutter app behind, see [leaveForUrl].
library;

import 'dart:async';
import 'dart:js_interop';
import 'dart:js_interop_unsafe';

import 'background.dart';
import 'client.dart';
import 'downloads.dart';
import 'connectivity.dart';
import 'device_code_scanner.dart';
import 'diagnostics.dart';
import 'offline.dart';
import 'photo_library.dart';
import 'notices.dart';
import 'wakelock.dart';

/// The cache the web app uses: memory only.
///
/// In a browser the app is served by the very server the data comes from, and
/// the browser's own HTTP cache is the offline story there; a second persisted
/// copy in `IndexedDB` would duplicate it, see [MemoryOfflineCache].
OfflineCache defaultOfflineCache() => MemoryOfflineCache();

/// Whether the given error is the network itself failing.
///
/// There is no `SocketException` in a browser; everything the transport can
/// fail with arrives as a `ClientException`, which the caller checks itself.
bool isSocketError(Object error) => false;

/// The photo library of a browser: there is none, see [PhotoLibrary].
///
/// A page cannot watch the machine's photos, so camera-roll sync says so
/// instead of pretending (issue #30).
PhotoLibrary defaultPhotoLibrary() =>
    const UnavailablePhotoLibrary(NoPhotoLibraryBrowser());

/// What keeps the screen awake in a browser: nothing, see [Wakelock].
///
/// A tab does not lock the machine's screen, and a page that goes to sleep with
/// the machine takes its upload with it either way (issue #63). The
/// `wakelock_plus` plugin is never imported in the web build.
Wakelock defaultWakelock() => const NoWakelock();

/// What reads a device code off the camera in a browser: nothing, see
/// [DeviceCodeScanner].
///
/// A page could ask for the camera, but the web build is opened on a machine
/// with a keyboard, and eight characters are typed there in seconds; the
/// scanner is a phone's affordance (issue #66). The `mobile_scanner` plugin is
/// never imported in the web build, and the sign-in screen builds no scan
/// button here.
DeviceCodeScanner defaultDeviceCodeScanner() => const NoDeviceCodeScanner();

/// The network a browser is on: it does not say, see [ConnectivitySource].
///
/// A page cannot ask what carries it, and there is no camera roll in a browser
/// to sync anyway; the Wi-Fi-only setting therefore never refuses anything
/// here (issue #36). The `connectivity_plus` plugin is never imported in the
/// web build.
ConnectivitySource defaultConnectivity() => const UnknownConnectivity();

/// The background execution of a browser tab: there is none, see
/// [BackgroundScheduler].
///
/// A tab that is closed is gone, so the camera-roll sync of the web build runs
/// while the app is open and says as much (issue #32). The `workmanager`
/// plugin is never imported here — the web build must not see its Dart code.
BackgroundScheduler defaultBackgroundScheduler() =>
    const UnavailableBackgroundScheduler(NoBackgroundSyncInBrowser());

/// Runs [task] as the platform's background task: never, in a browser.
void executeBackgroundTask(Future<bool> Function() task) {}

/// Where a downloaded original goes in a browser: the browser's downloads,
/// see [BlobDownloadSaver].
DownloadSaver defaultDownloadSaver() => const BlobDownloadSaver();

/// Hands a download to the browser as a blob, under the file's own name
/// (issue #164).
///
/// The bytes were fetched through the client, bearer and all; the page then
/// makes an object URL of them and clicks an anchor carrying `download`, which
/// is the one way a page starts a download of what it already holds. The
/// anchor is put into the document for the click (a browser that clicks only
/// attached elements would otherwise ignore it) and taken out again, and the
/// object URL is revoked a moment later — at once would cancel the download in
/// a browser that reads the blob only after the click has returned.
class BlobDownloadSaver extends DownloadSaver {
  const BlobDownloadSaver();

  @override
  Future<SaveOutcome> save(DownloadedFile file) async {
    // `dart:js_interop_unsafe` rather than extension types: the app's language
    // version predates them, and these five calls do not justify raising it.
    var options = JSObject()..["type"] = file.contentType.toJS;
    var blob = (globalContext["Blob"] as JSFunction)
        .callAsConstructor<JSObject>([file.bytes.toJS].toJS, options);
    var urls = globalContext["URL"] as JSObject;
    var url = urls.callMethod<JSString>("createObjectURL".toJS, blob);
    var document = globalContext["document"] as JSObject;
    var anchor = document.callMethod<JSObject>("createElement".toJS, "a".toJS);
    anchor["href"] = url;
    anchor["download"] = file.name.toJS;
    var body = document["body"] as JSObject;
    body.callMethod<JSAny?>("appendChild".toJS, anchor);
    anchor.callMethod<JSAny?>("click".toJS);
    body.callMethod<JSAny?>("removeChild".toJS, anchor);
    Timer(
      const Duration(minutes: 1),
      () => urls.callMethod<JSAny?>("revokeObjectURL".toJS, url),
    );
    return SaveOutcome.saved;
  }
}

/// Replaces the page the app runs in by [url], forgetting the page it leaves.
///
/// The one thing the Flutter router cannot do: an accepted invitation has to
/// leave its *app base* — the app was served under `<context>/i/<token>/`, and
/// once the device is signed in it belongs at `<context>/`, with the token
/// gone from the URL and gone from the history, see `invitation.dart`. A route
/// change would keep the base and keep the token; only a full navigation
/// changes what the browser asks the server for next.
///
/// `replace`, not `assign`: the invitation is used up, so the back button must
/// not lead to a page that would probe it again.
void leaveForUrl(String url) => _replaceLocation(url);

@JS('window.location.replace')
external void _replaceLocation(String url);

/// Rewrites the browser location to [url] without loading anything.
///
/// The sibling of [leaveForUrl] for the one thing that must *not* be a
/// navigation: the reason a dead invitation address was redirected with
/// (`?invitation=used`, issue #88) is said once, and then it has no business
/// in the location any more — a reload or a bookmark taken afterwards must not
/// repeat it. `replaceState` leaves the page and the history entry where they
/// are and changes only what the address bar shows.
void rewritePageUrl(String url) => _replaceState(null, "", url);

@JS('window.history.replaceState')
external void _replaceState(JSAny? data, String title, String url);

/// What this machine says about itself, for the header of a diagnostics log.
///
/// A browser is asked through the page, not through `dart:io`; the user agent
/// is in the bug report anyway, so the header says only that this is the web
/// build.
String platformDescription() => "web";

/// Resolves [host] explicitly: not possible in a browser (issue #58).
///
/// A page cannot ask the resolver anything — the browser resolves names for
/// it and says nothing about how. That is written into the log rather than
/// left out, so that a pasted log from the web is not read as "both lookups
/// succeeded".
Future<void> logHostResolution(DiagnosticsLog log, String host) async {
  log.add(
    "lookup $host: name resolution cannot be asked in a browser; the page "
    "sees only whether the request went through.",
  );
}
