/// The platform pieces of the offline support on the web.
library;

import 'background.dart';
import 'connectivity.dart';
import 'offline.dart';
import 'photo_library.dart';

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
PhotoLibrary defaultPhotoLibrary() => const UnavailablePhotoLibrary(
      "No photo library in a browser - camera-roll sync runs on Android and "
      "iOS.",
    );

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
    const UnavailableBackgroundScheduler(
      "Background sync is not available in a browser; the camera roll syncs "
      "while the app is open.",
    );

/// Runs [task] as the platform's background task: never, in a browser.
void executeBackgroundTask(Future<bool> Function() task) {}
