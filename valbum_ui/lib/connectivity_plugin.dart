/// The [ConnectivitySource] of Android and iOS, backed by `connectivity_plus`
/// (issue #36).
///
/// Reached only through the conditional import in `platform.dart`, and only
/// after the platform was checked — exactly as `photo_manager` is, see
/// `photo_library_manager.dart`. The web build never sees this file, so the
/// plugin's Dart code never reaches a browser.
///
/// The plugin answers with a *list*: a phone can be on Wi-Fi and on a VPN at
/// the same time, and a docked one on Ethernet as well. What matters here is
/// whether there is an unmetered member in it, so the list is folded into the
/// single [NetworkKind] the sync asks about.
library;

import 'package:connectivity_plus/connectivity_plus.dart';

import 'connectivity.dart';
import 'diagnostics.dart';

/// The device's network, through `connectivity_plus`.
class PluginConnectivity extends ConnectivitySource {
  final Connectivity _connectivity;

  /// Where a failure of the plugin is written, `null` for nowhere.
  final DiagnosticsLog? log;

  PluginConnectivity({Connectivity? connectivity, this.log})
      : _connectivity = connectivity ?? Connectivity();

  void _failed(String what, Object error) => log?.record(
        "Could not $what: the network is taken as unknown",
        facts: [platformErrorFact(error)],
        cause: failureCause(
          attempt: "connectivity $what",
          platformError: errorCause(error),
        ),
        window: const Duration(hours: 1),
      );

  /// The kind of network the device is on, [NetworkKind.unknown] where the
  /// platform refuses to say.
  ///
  /// A plugin that throws must not stop the sync forever: not knowing is the
  /// answer that lets a run go ahead, and a run that then fails says why
  /// itself. The alternative — treating a broken plugin as "no Wi-Fi" — would
  /// be a sync that never runs again and never says why.
  @override
  Future<NetworkKind> current() async {
    try {
      return kindOf(await _connectivity.checkConnectivity());
    } catch (error) {
      _failed("ask which network the device is on", error);
      return NetworkKind.unknown;
    }
  }

  @override
  Stream<NetworkKind> get changes =>
      _connectivity.onConnectivityChanged.map(kindOf).handleError(
          (Object error) => _failed("follow the network of the device", error));

  /// The kind of network the given plugin answer describes.
  ///
  /// An unmetered member wins over everything else: a phone on Wi-Fi *and* a
  /// mobile connection routes over the Wi-Fi, and a VPN over a Wi-Fi is still
  /// a Wi-Fi. `vpn` and `bluetooth` alone say nothing about what carries them,
  /// so they are [NetworkKind.other] — refused, rather than assumed free.
  static NetworkKind kindOf(List<ConnectivityResult> results) {
    if (results.isEmpty || results.every((r) => r == ConnectivityResult.none)) {
      return NetworkKind.none;
    }
    if (results.contains(ConnectivityResult.wifi)) {
      return NetworkKind.wifi;
    }
    if (results.contains(ConnectivityResult.ethernet)) {
      return NetworkKind.ethernet;
    }
    if (results.contains(ConnectivityResult.mobile)) {
      return NetworkKind.mobile;
    }
    return NetworkKind.other;
  }
}
