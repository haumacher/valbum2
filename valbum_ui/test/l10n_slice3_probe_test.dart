/// Review probes of the last localization slice (issue #108): the engine's
/// data becomes a sentence only where it is shown, in the reader's language,
/// and a layer without a widget tree asks the platform for that language.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/locales.dart';
import 'package:valbum_ui/main.dart';

import 'package:valbum_ui/notices.dart';

import 'util/l10n.dart';

void main() {
  test('the camera-roll line says a missing inbox in the reader\'s language',
      () {
    var status = const CameraRollStatus(
      phase: CameraRollPhase.failed,
      notice: NoInboxForCaller(),
    );
    var german = cameraRollLine(status, l10nOf(const Locale("de")));
    var english = cameraRollLine(status, l10nOf(const Locale("en")));
    expect(german, isNot(english));
    expect(german.trim(), isNotEmpty);
  });

  testWidgets('a layer without a widget tree follows the platform locale',
      (tester) async {
    tester.platformDispatcher.localesTestValue = const [Locale("de", "AT")];
    addTearDown(tester.platformDispatcher.clearLocalesTestValue);
    expect(platformMessages.appTitle, l10nOf(const Locale("de")).appTitle,
        reason: "Austrian German reads German.");

    tester.platformDispatcher.localesTestValue = const [Locale("fr")];
    expect(platformMessages.appTitle, l10nOf(const Locale("en")).appTitle,
        reason: "A language the app does not carry falls back to English.");
  });
}
