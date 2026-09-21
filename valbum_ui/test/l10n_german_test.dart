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
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/photo_picker_view.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/video_view.dart';

import 'camera_roll_test.dart' show Harness;
import 'devices_test.dart'
    show authOfUser, json, signedIn, storeSignedIn, threeDevices;
import 'photo_picker_test.dart' show twoAlbums;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
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

  sliceTwo();
}

/// The screens of slice 2, each pumped in a German locale.
///
/// One assertion block per screen, and always against the generated
/// `AppLocalizations` of German — never against a German string typed here,
/// so that a retranslation cannot break these tests. What they hold is the
/// same thing the settings screen holds above: a screen that was converted
/// speaks the language of the device, all of it.
void sliceTwo() {
  group('slice 2 speaks German', () {
    testWidgets('the upload dialog', (tester) async {
      var progress = ValueNotifier<UploadProgress>(
        const UploadProgress(
          phase: UploadPhase.preparing,
          imagesDone: 0,
          imagesTotal: 3,
        ),
      );
      addTearDown(progress.dispose);
      await tester.pumpWidget(
        localizedApp(
          UploadProgressDialog(progress: progress, onCancel: () {}),
          locale: const Locale("de"),
        ),
      );
      await tester.pump();

      expect(find.text(de.uploadProgressTitle), findsOneWidget);
      expect(find.text(de.cancel), findsOneWidget);
    });

    testWidgets('the pages of a link that is gone or confined', (tester) async {
      await tester.pumpWidget(
        localizedApp(
          ShareGoneScreen(message: "Der Link ist weg.", onContinue: () {}),
          locale: const Locale("de"),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text(de.shareContinueToStart), findsOneWidget);

      await tester.pumpWidget(
        localizedApp(
          ShareConfinedScreen(message: "Nicht hier.", onHome: () {}),
          locale: const Locale("de"),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text(de.shareBackToAlbum), findsOneWidget);
    });

    testWidgets('the share-link dialog', (tester) async {
      await tester.binding.setSurfaceSize(const Size(900, 1200));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      var client = clientReturning('{"links": []}');
      await tester.pumpWidget(
        localizedApp(
          ShareLinkDialog(client: client, path: const ["Trip"]),
          locale: const Locale("de"),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text(de.shareDialogTitle("'Trip'")), findsOneWidget);
      expect(find.text(de.linksHeading), findsOneWidget);
      expect(find.text(de.noLinksYet), findsOneWidget);
      expect(find.text(de.newLinkTile), findsOneWidget);
      expect(find.text(de.close), findsOneWidget);

      await tester.tap(find.byKey(const Key("new-link")));
      await tester.pumpAndSettle();
      expect(find.text(de.newLinkHeading), findsOneWidget);
      expect(find.text(de.expiresHeading), findsOneWidget);
      expect(find.text(de.showsHeading), findsOneWidget);
      expect(find.text(de.lowestRatingHeading), findsOneWidget);
      expect(find.text(de.privacyMembersNote), findsOneWidget);
      expect(find.text(de.linkNeverEdits), findsOneWidget);
      expect(find.text(de.createLink), findsOneWidget);
    });

    testWidgets('the welcome screen of an invitation', (tester) async {
      await tester.pumpWidget(
        localizedApp(
          InvitationWelcomeScreen(
            client: clientReturning("{}"),
            info: InvitationInfo(role: roleView, invitedBy: "alice"),
            token: "inv-1",
            appBase: "http://server/valbum/",
            onJoined: (_) async {},
            onGone: (_) {},
            openUrl: (_) {},
          ),
          locale: const Locale("de"),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text(de.yourName), findsOneWidget);
      expect(find.text(de.yourNameHelp), findsOneWidget);
      expect(find.text(de.deviceNameLabel), findsOneWidget);
      expect(find.text(de.deviceNameHelp), findsOneWidget);
      expect(find.text(de.joinAction), findsOneWidget);
    });

    testWidgets('the dialog issuing an invitation', (tester) async {
      await tester.binding.setSurfaceSize(const Size(900, 1400));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(
        localizedApp(
          InviteDialog(client: clientReturning("{}")),
          locale: const Locale("de"),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text(de.inviteDialogTitle), findsOneWidget);
      expect(find.text(de.inviteRecipientLabel), findsOneWidget);
      expect(find.text(de.inviteNoteLabel), findsOneWidget);
      expect(find.text(de.expiresHeading), findsOneWidget);
      expect(find.text(de.createInvitation), findsOneWidget);
      expect(find.text(de.close), findsOneWidget);
    });

    testWidgets('the camera-roll section', (tester) async {
      await tester.binding.setSurfaceSize(const Size(800, 1600));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      var harness = Harness();
      addTearDown(harness.dispose);
      await tester.pumpWidget(
        localizedApp(
          CameraRollScope(
            sync: harness.sync,
            child: const Scaffold(
              body: SingleChildScrollView(child: CameraRollSection()),
            ),
          ),
          locale: const Locale("de"),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text(de.cameraRollHeading), findsOneWidget);
      expect(find.text(de.cameraRollExplanation), findsOneWidget);
      expect(find.text(de.cameraRollUploadNew), findsOneWidget);
      expect(find.text(de.onlyOverWifi), findsOneWidget);
      expect(find.text(de.onlyOverWifiExplanation), findsOneWidget);
      expect(find.text(de.chooseAction), findsOneWidget);
      expect(find.text(de.syncNow), findsOneWidget);
    });

    testWidgets('the alternatives view of a group', (tester) async {
      var group = ImageGroup(
        representative: 0,
        images: [
          ImagePart(name: "a.jpg", width: 2000, height: 1000),
          ImagePart(name: "b.jpg", width: 2000, height: 1000),
        ],
      );
      AlbumInitializer().init(AlbumInfo(title: "Album", parts: [group]));
      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          localizedApp(
            GroupView(
              client: clientReturning("{}"),
              baseUrl: "http://server/valbum/data/album",
              group: group,
              onUp: () {},
              onShowDetail: (_) {},
            ),
            locale: const Locale("de"),
          ),
        );
        await tester.pumpAndSettle();
      });

      expect(find.byTooltip(de.backToAlbum), findsOneWidget);
      expect(find.byTooltip(de.groupPicture), findsOneWidget);
    });

    testWidgets('the video that cannot be played', (tester) async {
      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          localizedApp(
            VideoView(
              videoUrl: "http://server/valbum/data/album/clip.mp4",
              posterUrl: "http://server/valbum/data/album/clip.mp4?type=tn",
              createController: (
                url, {
                Map<String, String> headers = const {},
              }) =>
                  throw StateError("Source error"),
            ),
            locale: const Locale("de"),
          ),
        );
        await tester.pumpAndSettle();
      });

      expect(find.text(de.videoCannotPlay), findsOneWidget);
      expect(find.text(de.videoNetworkHint), findsOneWidget);
      expect(find.text(de.videoDiagnosticsHint), findsOneWidget);
    });

    testWidgets('the in-app photo picker', (tester) async {
      await tester.pumpWidget(
        localizedApp(
          PhotoPickerScreen(library: twoAlbums()),
          locale: const Locale("de"),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text(de.photoLibraryTitle), findsOneWidget);
      expect(find.text(de.photoCount(3)), findsOneWidget);
      expect(find.text(de.photoPickerSelected(0)), findsOneWidget);
      expect(find.text(de.photoPickerUpload(0)), findsOneWidget);

      // And the month a photo was taken in is the locale's own month name,
      // which is why `monthLabel` asks `intl` instead of holding a table.
      await tester.tap(find.byKey(photoAlbumKey("Camera")));
      await tester.pumpAndSettle();
      expect(
        find.text("${monthLabel("2024-03", "de")} (2)"),
        findsOneWidget,
      );
      expect(monthLabel("2024-03", "de"), isNot(monthLabel("2024-03", "en")));
    });
  });

  test('the German words of slice 2 are not the English ones', () {
    var en = l10nOf(const Locale("en"));
    expect(de.cameraRollHeading, isNot(en.cameraRollHeading));
    expect(de.videoCannotPlay, isNot(en.videoCannotPlay));
    expect(de.inviteDialogTitle, isNot(en.inviteDialogTitle));
    expect(de.photoLibraryTitle, isNot(en.photoLibraryTitle));
  });
}
