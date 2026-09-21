/// The [Wakelock] of a phone, backed by `wakelock_plus` (issue #63).
///
/// Reached only through the conditional import in `platform.dart`, and only
/// after the platform was checked, exactly as `photo_library_manager.dart`:
/// the plugin has no implementation this app wants outside Android and iOS.
library;

import 'package:flutter/foundation.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import 'wakelock.dart';

/// Keeps the screen of the device on while an upload runs.
class PluginWakelock extends Wakelock {
  const PluginWakelock();

  @override
  Future<void> keepAwake(bool awake) async {
    try {
      await WakelockPlus.toggle(enable: awake);
    } catch (error) {
      // The upload is not refused because the screen may lock: it is only as
      // fragile as it was before this existed, see [Wakelock].
      if (kDebugMode) {
        print("wakelock !! $error");
      }
    }
  }
}
