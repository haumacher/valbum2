/// The [DeviceCodeScanner] of a phone, backed by `mobile_scanner` (issue #66).
///
/// Reached only through the conditional import in `platform.dart`, and only
/// after the platform was checked, exactly as `wakelock_plugin.dart`: the
/// plugin bundles CameraX and ML Kit on Android and Apple's Vision framework
/// on iOS, and has no implementation this app wants anywhere else. The web
/// build never imports this file, so `mobile_scanner` is not linked into it at
/// all.
library;

import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import 'device_code_payload.dart';
import 'device_code_scanner.dart';
import 'l10n/app_localizations.dart';

/// The key of the page showing the camera.
const Key deviceCodeScannerPageKey = Key("settings.deviceCode.scanner");

/// The key of the line saying what was read that was not a device code.
const Key deviceCodeScannerRefusalKey =
    Key("settings.deviceCode.scanner.refusal");

/// The key of the line saying why the camera cannot be used at all.
const Key deviceCodeScannerErrorKey = Key("settings.deviceCode.scanner.error");

/// Reads a device code off the camera of the device.
class PluginDeviceCodeScanner extends DeviceCodeScanner {
  const PluginDeviceCodeScanner();

  @override
  bool get available => true;

  @override
  Future<String?> scan(BuildContext context) => Navigator.of(context).push<String>(
        MaterialPageRoute<String>(
          builder: (context) => const DeviceCodeScannerPage(),
          fullscreenDialog: true,
        ),
      );
}

/// The full-screen camera the device code is read with.
///
/// It closes on the first barcode that *is* a device code, see
/// [parseDeviceCodePayload], and says so and keeps looking for every other
/// one. Whether the camera can be used at all is the plugin's word, shown
/// where it happens: a refused permission and a machine without a camera are
/// both a sentence on this page and a scan that answers `null`, never a silent
/// failure — the field on the sign-in screen stays typeable either way.
class DeviceCodeScannerPage extends StatefulWidget {
  const DeviceCodeScannerPage({super.key});

  @override
  State<DeviceCodeScannerPage> createState() => DeviceCodeScannerPageState();
}

class DeviceCodeScannerPageState extends State<DeviceCodeScannerPage> {
  /// Only QR codes: a device code is never a barcode of another shape, and
  /// narrowing the formats keeps the scanner off everything else.
  final MobileScannerController controller = MobileScannerController(
    formats: const [BarcodeFormat.qrCode],
  );

  /// What was read that was not a device code, `null` while nothing was.
  String? _refusal;

  /// Whether a code was accepted already, so that a second frame carrying the
  /// same code does not pop the page twice.
  bool _done = false;

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  /// One frame's worth of barcodes.
  void _detected(BarcodeCapture capture) {
    if (_done || !mounted) {
      return;
    }
    for (var barcode in capture.barcodes) {
      var text = barcode.rawValue ?? "";
      if (text.isEmpty) {
        continue;
      }
      if (parseDeviceCodePayload(text) != null) {
        _done = true;
        Navigator.of(context).pop(text);
        return;
      }
    }
    if (mounted && _refusal == null) {
      setState(
        () => _refusal = notADeviceCodeRefusal(AppLocalizations.of(context)!),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return Scaffold(
      key: deviceCodeScannerPageKey,
      appBar: AppBar(
        title: Text(l10n.scanCodeTitle),
        leading: IconButton(
          icon: const Icon(Icons.close),
          tooltip: l10n.close,
          onPressed: () => Navigator.of(context).pop(),
        ),
      ),
      body: Stack(
        children: [
          Positioned.fill(
            child: MobileScanner(
              controller: controller,
              onDetect: _detected,
              errorBuilder: (context, error) => _cameraProblem(error),
            ),
          ),
          Positioned(
            left: 16,
            right: 16,
            bottom: 24,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                _banner(context, l10n.scanCodeAdvice, null),
                if (_refusal != null) ...[
                  const SizedBox(height: 8),
                  _banner(
                    context,
                    _refusal!,
                    deviceCodeScannerRefusalKey,
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// A line of text readable over a camera preview.
  Widget _banner(BuildContext context, String message, Key? key) =>
      DecoratedBox(
        decoration: BoxDecoration(
          color: Colors.black.withValues(alpha: 0.6),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Text(
            message,
            key: key,
            textAlign: TextAlign.center,
            style: const TextStyle(color: Colors.white),
          ),
        ),
      );

  /// Why the camera cannot be used, in the plugin's own words where it has
  /// any.
  Widget _cameraProblem(MobileScannerException error) {
    var l10n = AppLocalizations.of(context)!;
    var reason = switch (error.errorCode) {
      MobileScannerErrorCode.permissionDenied => l10n.cameraNotAllowed,
      MobileScannerErrorCode.unsupported => l10n.cameraUnsupported,
      _ => error.errorDetails?.message ?? l10n.cameraNotOpened,
    };
    return ColoredBox(
      color: Colors.black,
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(
            reason,
            key: deviceCodeScannerErrorKey,
            textAlign: TextAlign.center,
            style: const TextStyle(color: Colors.white),
          ),
        ),
      ),
    );
  }
}
