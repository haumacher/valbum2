/// Probe for issue #90: the web build's server line composed with a stored
/// server (an accepted invitation) and with a page that has no server at all.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'util/l10n.dart';

Future<void> pumpWeb(
  WidgetTester tester, {
  String? platformDefault,
  String? serverUrl,
}) async {
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
  await tester.pumpWidget(MaterialApp(
    localizationsDelegates: testLocalizationsDelegates,
    supportedLocales: testSupportedLocales,
    home: OfflineScope(
      state: OfflineState(),
      cache: MemoryOfflineCache(),
      child: CameraRollScope(
        sync: sync,
        child: ServerSettingsScreen(
          settings: settings,
          isWeb: true,
          clientFor: (dataUrl) => VAlbumClient(
            dataUrl: dataUrl,
            httpClient: MockClient((request) async => http.Response("{}", 200)),
          ),
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('a stored server (an accepted invitation) is what the line names',
      (tester) async {
    await pumpWeb(
      tester,
      platformDefault: "http://origin/valbum/data",
      serverUrl: "http://family.example/albums/",
    );

    // The browser talks to what is stored, not to where the page came from.
    expect(
      tester.widget<Text>(find.byKey(serverLineKey)).data,
      "This browser talks to http://family.example/albums/",
    );
    expect(find.byKey(serverUrlFieldKey), findsNothing);
    expect(find.byKey(deviceCodeFieldKey), findsOneWidget);
  });

  testWidgets('a page without a server still offers nothing to type an address into',
      (tester) async {
    await pumpWeb(tester, platformDefault: null);

    expect(
      tester.widget<Text>(find.byKey(serverLineKey)).data,
      "This browser talks to no server yet.",
    );
    expect(find.byKey(serverUrlFieldKey), findsNothing);
    expect(find.byType(CameraRollSection), findsNothing);
  });
}
