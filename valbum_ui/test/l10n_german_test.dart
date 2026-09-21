/// The German smoke test of issue #108: the server settings screen pumped in
/// a `de` locale speaks German.
///
/// One screen, the whole of it — the app bar, the address section, the
/// sign-in, the devices, the cache and the diagnostics — because the point of
/// the slice is that a screen is converted *completely*: a single English
/// heading left behind is exactly what this would miss if it only checked one
/// word. What it asserts against is the generated `AppLocalizations` of the
/// German locale, never a German string typed here: the words are the ARB's,
/// and a retranslation must not break this test.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/settings.dart';

import 'devices_test.dart'
    show authOfUser, json, signedIn, storeSignedIn, threeDevices;
import 'util/l10n.dart';

/// The German strings, as the app would show them.
final AppLocalizations de = l10nOf(const Locale("de"));

/// Pumps the settings screen in [locale].
Future<void> pumpIn(
  WidgetTester tester,
  Locale locale, {
  ServerSettings? settings,
}) async {
  await tester.binding.setSurfaceSize(const Size(800, 3600));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  var store = storeSignedIn();
  var transport = MockClient((request) async {
    var query = request.url.queryParameters;
    if (query["type"] == "auth") {
      return json(authOfUser("admin"));
    }
    if (query["type"] == "devices") {
      return json(threeDevices());
    }
    if (query["type"] == "invitations") {
      return json('{"invitations": []}');
    }
    return json('{"users": []}');
  });
  await tester.pumpWidget(
    localizedApp(
      ServerSettingsScreen(
        settings: settings ?? await signedIn(store),
        clientFor: (dataUrl) =>
            VAlbumClient(dataUrl: dataUrl, httpClient: transport),
      ),
      locale: locale,
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('the settings screen is German in a German locale',
      (tester) async {
    await pumpIn(tester, const Locale("de"));

    // The app bar, the address section and its buttons.
    expect(find.text(de.serverScreenTitle), findsOneWidget);
    expect(
      tester.widget<Text>(find.byKey(serverUrlHelpKey)).data,
      de.serverUrlHelp,
    );
    expect(find.text(de.save), findsOneWidget);
    expect(find.text(de.testConnection), findsOneWidget);

    // The sign-in section.
    expect(find.text(de.signInHeading), findsWidgets);
    expect(find.text(de.signInCodeExplanation), findsOneWidget);
    expect(find.text(de.signOut), findsOneWidget);
    expect(find.text(de.deviceNameLabel), findsOneWidget);
    expect(find.text(de.signInCodeLabel), findsOneWidget);

    // The devices, the people, the cache and the diagnostics.
    expect(find.text(de.devicesHeading), findsOneWidget);
    expect(find.text(de.addDevice), findsOneWidget);
    expect(find.text(de.peopleHeading), findsOneWidget);
    expect(find.text(de.usersHeading), findsOneWidget);
    expect(find.text(de.diagnosticsHeading), findsOneWidget);
    expect(find.text(de.diagnosticsLead), findsOneWidget);

    // And nothing English is left standing where German belongs.
    var en = l10nOf(const Locale("en"));
    expect(find.text(en.serverScreenTitle), findsNothing);
    expect(find.text(en.devicesHeading), findsNothing);
    expect(find.text(en.diagnosticsHeading), findsNothing);
  });

  testWidgets('the same screen is English in an English locale',
      (tester) async {
    await pumpIn(tester, const Locale("en"));
    var en = l10nOf(const Locale("en"));
    expect(find.text(en.serverScreenTitle), findsOneWidget);
    expect(find.text(en.devicesHeading), findsOneWidget);
    expect(find.text(de.serverScreenTitle), findsNothing);
  });

  test('the German strings are not the English ones', () {
    var en = l10nOf(const Locale("en"));
    expect(de.serverScreenTitle, isNot(en.serverScreenTitle));
    expect(de.devicesHeading, isNot(en.devicesHeading));
  });
}
