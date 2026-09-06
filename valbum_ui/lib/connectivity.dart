/// The network the device is on, behind an interface (issue #36).
///
/// Uploading a camera roll over a mobile connection can cost real money, so
/// the sync can be limited to an unmetered network — see
/// [CameraRollConfig.wifiOnly]. What "unmetered" is, is a platform question,
/// and this library is the only place that asks it:
///
///  * [ConnectivitySource] is the question — the [NetworkKind] the device is
///    on right now, plus a stream that fires when it changes.
///  * [UnknownConnectivity] is the answer of a platform that cannot say (a
///    browser, a desktop). It answers [NetworkKind.unknown], which *allows*
///    the sync: refusing where nothing is known would break a sync that works.
///  * [FakeConnectivity] is what a test (or an embedder) drives itself.
///
/// The platform-backed implementation is selected by the conditional import in
/// `platform.dart`; see `connectivity_plugin.dart` for Android and iOS.
library;

import 'dart:async';

/// The kind of network the device is on.
///
/// One value per thing the sync has to tell apart, not one per transport the
/// platform knows: everything that is neither unmetered nor absent is
/// [other], because it is all refused alike.
enum NetworkKind {
  /// No network at all.
  none,

  /// A wireless local network.
  wifi,

  /// A wired network.
  ethernet,

  /// A mobile connection — the one this setting exists for.
  mobile,

  /// Something else the platform reports: Bluetooth tethering, a VPN without
  /// an underlying network the platform names, ...
  other,

  /// The platform cannot say, see [UnknownConnectivity].
  unknown;

  /// Whether a Wi-Fi-only sync may run on this kind of network.
  ///
  /// [ethernet] counts: a docked phone or a desktop on a cable is exactly as
  /// unmetered as Wi-Fi, and the setting is about the phone plan, not about
  /// the radio. [unknown] counts as well — see the library documentation.
  bool get unmetered =>
      this == wifi || this == ethernet || this == unknown;

  /// Why a Wi-Fi-only sync cannot run on this kind of network.
  ///
  /// Refusals speak: this is what the settings screen shows instead of a sync
  /// that quietly does not happen.
  String get refusal => switch (this) {
        NetworkKind.none =>
          "No network: the sync waits for a Wi-Fi connection.",
        NetworkKind.mobile =>
          "No Wi-Fi: the sync is limited to Wi-Fi, and this device is on a "
              "mobile connection.",
        _ => "No Wi-Fi: the sync is limited to Wi-Fi, and this device is on "
            "another network.",
      };
}

/// The network the device is on.
abstract class ConnectivitySource {
  const ConnectivitySource();

  /// The kind of network the device is on right now.
  Future<NetworkKind> current();

  /// Fires whenever the kind of network changed.
  ///
  /// May never fire — a platform without change notifications simply relies
  /// on the periodic scan of the sync engine.
  Stream<NetworkKind> get changes => const Stream<NetworkKind>.empty();

  /// Releases whatever the platform holds.
  void dispose() {}
}

/// The [ConnectivitySource] of a platform that cannot say what it is on.
///
/// The web build and the desktop builds get this: a browser has no camera roll
/// to sync in the first place, and a desktop has no phone plan to protect. It
/// answers [NetworkKind.unknown], so a Wi-Fi-only sync runs there rather than
/// refusing forever.
class UnknownConnectivity extends ConnectivitySource {
  const UnknownConnectivity();

  @override
  Future<NetworkKind> current() async => NetworkKind.unknown;
}

/// A [ConnectivitySource] the test (or an embedder) sets by hand.
///
/// Lives in `lib/` for the same reason [FakePhotoLibrary] does: an embedder
/// driving this app's engine itself needs it as much as a test does.
class FakeConnectivity extends ConnectivitySource {
  NetworkKind kind;

  /// The number of times [current] was asked.
  int asked = 0;

  final StreamController<NetworkKind> _changes =
      StreamController<NetworkKind>.broadcast();

  FakeConnectivity([this.kind = NetworkKind.wifi]);

  @override
  Future<NetworkKind> current() async {
    asked++;
    return kind;
  }

  @override
  Stream<NetworkKind> get changes => _changes.stream;

  /// Moves the device onto another network and announces the change.
  void moveTo(NetworkKind value) {
    kind = value;
    if (!_changes.isClosed) {
      _changes.add(value);
    }
  }

  @override
  void dispose() => _changes.close();
}
