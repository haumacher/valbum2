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
import 'contact_session.dart';
import 'device_code_scanner.dart';
import 'offline.dart';
import 'phone_contacts.dart';
import 'photo_library.dart';
import 'notices.dart';
import 'wakelock.dart';

// The upload of a browser keeps the photo bytes out of Dart, see
// `browser_upload.dart` and issue #170.
export 'browser_upload.dart' show blobOfObjectUrl, sha256OfBlob, sendBlobForm;

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
PhotoLibrary defaultPhotoLibrary({bool background = false}) =>
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
/// see [BrowserDownloadSaver].
DownloadSaver defaultDownloadSaver() => const BrowserDownloadSaver();

/// A browser has no address book to pick a recipient from (issue #201).
PhoneContacts? defaultPhoneContacts() => null;

/// Hands a download to the browser, which fetches it by itself (issue #209).
///
/// The app's own transport collects a whole answer before it hands it on —
/// an album of videos does not survive that, and the browser's download would
/// appear only after the transfer — so the browser is given an *address*
/// instead: a signed one of issue #185 for an original, a one-time download
/// ticket for an archive, neither carrying the bearer. The page clicks an
/// anchor carrying `download` and that address, which starts the browser's
/// own download at once, with its progress, its cancel, and its own marking
/// of a broken transfer as failed. The anchor is put into the document for the
/// click (a browser that clicks only attached elements would otherwise ignore
/// it) and taken out again.
class BrowserDownloadSaver extends DownloadSaver {
  const BrowserDownloadSaver();

  @override
  bool get showsProgress => true;

  @override
  Future<SaveOutcome> save(DownloadSource source,
      {DownloadProgress? progress}) async {
    var url = await source.address();
    var document = globalContext["document"] as JSObject;
    var anchor = document.callMethod<JSObject>("createElement".toJS, "a".toJS);
    anchor["href"] = url.toJS;
    anchor["download"] = source.name.toJS;
    var body = document["body"] as JSObject;
    body.callMethod<JSAny?>("appendChild".toJS, anchor);
    anchor.callMethod<JSAny?>("click".toJS);
    body.callMethod<JSAny?>("removeChild".toJS, anchor);
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

/// What this machine says about itself, for the header of a diagnostics log
/// and the facts of a failure entry (issue #184).
///
/// The browser's user agent: which browser, which version and on which
/// system is what decides whether a video format plays, and a page can ask
/// nothing more precise.
String platformDescription() {
  try {
    var navigator = globalContext["navigator"] as JSObject?;
    var agent = (navigator?["userAgent"] as JSString?)?.toDart;
    if (agent != null && agent.isNotEmpty) {
      return "web, $agent";
    }
  } catch (_) {
    // A page without a navigator is still the web build.
  }
  return "web";
}

/// Resolves [host] explicitly: not possible in a browser (issue #58).
///
/// A page cannot ask the resolver anything — the browser resolves names for
/// it and says nothing about how. That is said in the entry rather than left
/// out, so that a pasted log from the web is not read as "both lookups
/// succeeded".
Future<List<String>> hostResolution(String host) async => [
      "Lookup $host: name resolution cannot be asked in a browser; the page "
          "sees only whether the request went through.",
    ];

/// Where this browser keeps who it is on a personal share link (issue #202):
/// `localStorage` for a visitor who asked to be remembered, `sessionStorage`
/// otherwise, see `contact_session.dart`.
ContactCredentialStore defaultContactCredentialStore() =>
    ContactCredentialStore(
      remembered: _BrowserStorageArea("localStorage"),
      session: _BrowserStorageArea("sessionStorage"),
    );

/// One of the browser's two storage areas, reached anew on every access: in a
/// private window or with site data blocked, merely *naming* the area throws,
/// which [ContactCredentialStore] catches.
class _BrowserStorageArea implements StorageArea {
  final String _name;

  _BrowserStorageArea(this._name);

  JSObject get _area => globalContext[_name] as JSObject;

  @override
  String? get(String key) =>
      (_area.callMethod<JSAny?>("getItem".toJS, key.toJS) as JSString?)?.toDart;

  @override
  void set(String key, String value) =>
      _area.callMethod<JSAny?>("setItem".toJS, key.toJS, value.toJS);

  @override
  void remove(String key) =>
      _area.callMethod<JSAny?>("removeItem".toJS, key.toJS);
}
