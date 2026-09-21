/// Reading a device code off the camera instead of typing it (issue #66).
///
/// Behind an interface for the same reason as [Wakelock] and [PhotoLibrary]: a
/// widget test must not reach a platform channel, and a camera is the most
/// platform-bound thing this app touches. The scanner of a phone lives in
/// `device_code_scanner_plugin.dart` and is reached only through the
/// conditional import of `platform.dart`, so the web build never links
/// `mobile_scanner` at all — a browser and a desktop get
/// [NoDeviceCodeScanner], which says it is not available, and the sign-in
/// screen then builds no scan button rather than a button that refuses.
///
/// What a scanner answers is the *raw text* of the barcode. It is interpreted
/// in exactly one place, [parseDeviceCodePayload]; nothing here believes
/// anything about it.
library;

import 'package:flutter/widgets.dart';

import 'l10n/app_localizations.dart';

/// What a QR code that is not a device code is answered with.
///
/// Said wherever such a code turns up: on the scanner, which keeps looking —
/// a poster, a Wi-Fi code or a link simply is not what that screen is for —
/// and on the sign-in screen, should a scanner ever answer something the app
/// cannot read. Nothing is filled in and nothing is sent either way.
String notADeviceCodeRefusal(AppLocalizations l10n) => l10n.notADeviceCode;

/// Something that can read a QR code with the camera of the device.
abstract class DeviceCodeScanner {
  const DeviceCodeScanner();

  /// Whether this platform can scan at all.
  ///
  /// `false` means the sign-in screen offers nothing: a disabled button that
  /// can never become enabled is worse than no button, because it promises a
  /// camera that does not exist.
  bool get available;

  /// Opens the camera and answers the text of the code that was read.
  ///
  /// `null` when nothing was read: the user closed the scanner, the camera
  /// permission was refused, or there is no camera. A reason the user needs to
  /// see is shown by the scanner itself, on its own page — this answer only
  /// says that the field was not filled.
  Future<String?> scan(BuildContext context);
}

/// The scanner of a platform that has no camera this app can use, and of every
/// test.
class NoDeviceCodeScanner extends DeviceCodeScanner {
  const NoDeviceCodeScanner();

  @override
  bool get available => false;

  @override
  Future<String?> scan(BuildContext context) async => null;
}

/// A [DeviceCodeScanner] that answers what a test told it to.
///
/// Lives in `lib/` next to [RecordingWakelock], for the same reason: the app's
/// own test doubles belong with the abstraction they stand in for.
class FakeDeviceCodeScanner extends DeviceCodeScanner {
  /// The raw text the camera "read", `null` for a cancelled scan.
  final String? answer;

  /// How often the scanner was opened.
  int scans = 0;

  FakeDeviceCodeScanner(this.answer);

  @override
  bool get available => true;

  @override
  Future<String?> scan(BuildContext context) async {
    scans++;
    return answer;
  }
}

/// Makes the [DeviceCodeScanner] of the app available to the widget tree.
class DeviceCodeScannerScope extends InheritedWidget {
  /// What reads a QR code with the camera, see [DeviceCodeScanner].
  final DeviceCodeScanner scanner;

  const DeviceCodeScannerScope({
    super.key,
    required this.scanner,
    required super.child,
  });

  /// The scanner of the enclosing app, a [NoDeviceCodeScanner] outside one (a
  /// screen pumped on its own in a test).
  static DeviceCodeScanner of(BuildContext context) =>
      context
          .dependOnInheritedWidgetOfExactType<DeviceCodeScannerScope>()
          ?.scanner ??
      const NoDeviceCodeScanner();

  @override
  bool updateShouldNotify(DeviceCodeScannerScope oldWidget) =>
      scanner != oldWidget.scanner;
}
