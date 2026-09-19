/// Tests of what the server settings show in the web build and what they do
/// not (issue #90).
///
/// A browser can act on less than a device: it talks to the server it was
/// loaded from and to no other, and it has no camera roll. The screen
/// therefore builds no address section and no camera-roll section there, and
/// names the server in one line instead. The platform is a parameter of the
/// screen, so both builds are pumped here — every other test of the settings
/// runs off the web and sees the screen as a device sees it.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

/// The server the pumped screen talks to.
const String serverDataUrl = "http://server/valbum/data";

/// A transport that answers nothing: no test here contacts a server.
http.Client get silent =>
    MockClient((request) async => http.Response("{}", 200));

/// Pumps the settings screen as the web build or as a device build.
Future<void> pumpSettings(
  WidgetTester tester, {
  required bool isWeb,
  String? platformDefault = serverDataUrl,
  String? serverUrl,
}) async {
  // A viewport tall enough for the whole screen: the settings are a lazy
  // [ListView], and "this is not shown" must not be answered by "this is
  // below the fold".
  await tester.binding.setSurfaceSize(const Size(1000, 4000));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  var settings = ServerSettings(
    store: InMemorySettingsStore(),
    platformDefault: () => platformDefault,
    serverUrl: serverUrl,
    loaded: true,
  );
  var sync = CameraRollSync(
    store: InMemorySettingsStore(),
    library: FakePhotoLibrary(),
    clientOf: () => null,
  );
  addTearDown(sync.dispose);
  await tester.pumpWidget(
    MaterialApp(
      home: OfflineScope(
        state: OfflineState(),
        cache: MemoryOfflineCache(),
        child: CameraRollScope(
          sync: sync,
          child: ServerSettingsScreen(
            settings: settings,
            isWeb: isWeb,
            clientFor: (dataUrl) =>
                VAlbumClient(dataUrl: dataUrl, httpClient: silent),
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  group('the web build', () {
    testWidgets('shows no address section', (tester) async {
      await pumpSettings(tester, isWeb: true);

      expect(find.byKey(serverUrlFieldKey), findsNothing);
      expect(find.byKey(serverUrlHelpKey), findsNothing);
      expect(find.text("Save"), findsNothing);
      expect(find.text("Test connection"), findsNothing);
      expect(find.text("Use the server this app was loaded from"), findsNothing);
      expect(find.text("Forget this server"), findsNothing);
    });

    testWidgets('names the server this browser talks to', (tester) async {
      await pumpSettings(tester, isWeb: true);

      expect(
        tester.widget<Text>(find.byKey(serverLineKey)).data,
        "This browser talks to http://server/valbum/",
      );
    });

    testWidgets('shows no camera-roll section', (tester) async {
      await pumpSettings(tester, isWeb: true);

      expect(find.byType(CameraRollSection), findsNothing);
      expect(find.byKey(cameraRollSwitchKey), findsNothing);
      expect(find.byKey(cameraRollChooseKey), findsNothing);
    });

    testWidgets('keeps sign-in, cache and diagnostics', (tester) async {
      await pumpSettings(tester, isWeb: true);

      expect(find.byKey(deviceCodeFieldKey), findsOneWidget);
      expect(find.byKey(clearCacheButtonKey), findsOneWidget);
      expect(find.byKey(diagnosticsSectionKey), findsOneWidget);
    });
  });

  group('a device build', () {
    testWidgets('keeps the address section', (tester) async {
      await pumpSettings(tester, isWeb: false);

      expect(find.byKey(serverUrlFieldKey), findsOneWidget);
      expect(find.byKey(serverUrlHelpKey), findsOneWidget);
      expect(find.text("Save"), findsOneWidget);
      expect(find.text("Test connection"), findsOneWidget);
      expect(find.byKey(serverLineKey), findsNothing);
    });

    testWidgets('keeps the camera-roll section and its chooser',
        (tester) async {
      await pumpSettings(tester, isWeb: false);

      expect(find.byType(CameraRollSection), findsOneWidget);
      expect(find.byKey(cameraRollSwitchKey), findsOneWidget);
      expect(find.byKey(cameraRollChooseKey), findsOneWidget);
    });
  });
}
