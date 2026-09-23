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

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/photo_picker_view.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/video_view.dart';

import 'package:valbum_ui/album_view.dart';
import 'package:valbum_ui/camera_roll.dart';
import 'package:valbum_ui/camera_roll_view.dart';
import 'package:valbum_ui/image_properties.dart';
import 'package:valbum_ui/inbox_view.dart';
import 'package:valbum_ui/manage_view.dart';
import 'package:valbum_ui/move_view.dart';
import 'package:valbum_ui/notices.dart';

import 'album_menu_actions_test.dart' show pumpAlbum;
import 'camera_roll_test.dart' show Harness;
import 'devices_test.dart'
    show authOfUser, json, signedIn, storeSignedIn, threeDevices;
import 'inbox_listing_test.dart' show listingWithCount;
import 'inbox_view_test.dart' show inboxTree, pumpInbox;
import 'move_test.dart' show recordingClient, treeAnswer;
import 'persons_view_test.dart'
    show albumOf, authOf, editorClient, pumpEditor, selectFace;
import 'photo_picker_test.dart' show twoAlbums;
import 'trash_view_test.dart' as trash;
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
  sliceThree();
}

/// Shows the app in German for the length of one test.
///
/// The app has no language switch — the platform locale decides, see
/// `locales.dart` — so a screen pumped through [VAlbumApp] is put into German
/// by telling the test binding which language the device asks for.
void speakGerman(WidgetTester tester) {
  tester.platformDispatcher.localesTestValue = const [Locale("de")];
  addTearDown(tester.platformDispatcher.clearLocalesTestValue);
}

/// The screens of slice 3, each pumped in a German locale.
///
/// The same rule as [sliceTwo]: one block per screen, always against the
/// generated `AppLocalizations` of German, never against a German string
/// typed here. Slice 3 is the rest of the app — the album page, the listing,
/// the move picker, the image properties, the inbox and the camera-roll
/// status line — so what these hold is that the app, all of it, speaks the
/// language of the device.
void sliceThree() {
  group('slice 3 speaks German', () {
    testWidgets('the album page and its menu', (tester) async {
      speakGerman(tester);
      await withFakeImageHttp(() async {
        await pumpAlbum(tester, treeAnswer);
        await tester.tap(find.byIcon(Icons.more_vert).last);
        await tester.pumpAndSettle();
      });

      expect(find.text(de.albumProperties), findsOneWidget);
      expect(find.text(de.moveAlbumTo), findsOneWidget);
      expect(find.text(de.deleteAlbumAction), findsOneWidget);
      expect(find.text(de.minRatingLabel), findsOneWidget);
      expect(find.text(de.showMoreImages), findsOneWidget);
      expect(find.text(de.showFewerImages), findsOneWidget);
      expect(find.text(de.reload), findsOneWidget);
      expect(find.text(de.serverMenuEntry), findsOneWidget);
      // Re-reading the photo details, issue #161.
      expect(find.text(de.reanalyze), findsOneWidget);

      var en = l10nOf(const Locale("en"));
      expect(find.text(en.albumProperties), findsNothing);
    });

    testWidgets('the context menu of an edit-mode tile (issue #156)',
        (tester) async {
      speakGerman(tester);
      await withFakeImageHttp(() async {
        await pumpAlbum(tester, treeAnswer);
        await tester.longPress(find.byType(Image).first);
        await tester.pumpAndSettle();
        var box = tester.getRect(find.byKey(const ValueKey("b.jpg")));
        await tester.tapAt(
          Offset(box.left + 8, box.center.dy),
          buttons: kSecondaryMouseButton,
          kind: PointerDeviceKind.mouse,
        );
        await tester.pumpAndSettle();
      });

      expect(find.text(de.moveSubjectTo(const ImageSubject(1).asked(de))),
          findsOneWidget);
      expect(find.text(de.imageProperties), findsOneWidget);
    });

    testWidgets('the album properties dialog', (tester) async {
      speakGerman(tester);
      await tester.pumpWidget(
        localizedApp(
          const AlbumPropertiesDialog(
            AlbumProperties(title: "Zoo", subTitle: ""),
            mayChangeKind: true,
          ),
          locale: const Locale("de"),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text(de.albumProperties), findsOneWidget);
      expect(find.text(de.titleLabel), findsOneWidget);
      expect(find.text(de.subtitleLabel), findsOneWidget);
      expect(find.text(de.dateNone), findsOneWidget);
      expect(find.text(de.makeThisAnInbox), findsOneWidget);
      expect(find.text(de.noAlbumPictureHint), findsOneWidget);
      expect(find.text(de.cancel), findsOneWidget);
      expect(find.text(de.apply), findsOneWidget);
    });

    testWidgets('the listing and the dialog making an album', (tester) async {
      speakGerman(tester);
      await pumpListingIn(tester);
      await tester.tap(find.byIcon(Icons.more_vert).last);
      await tester.pumpAndSettle();

      expect(find.text(de.createAlbum), findsOneWidget);
      expect(find.text(de.createFolder), findsOneWidget);
      expect(find.text(de.folderProperties), findsOneWidget);

      await tester.tap(find.text(de.createAlbum));
      await tester.pumpAndSettle();

      expect(find.text(de.newAlbumTitle), findsOneWidget);
      expect(find.text(de.createInboxHint), findsOneWidget);
      expect(find.text(de.createAlbumUndatedHint), findsOneWidget);
      expect(find.text(de.titleLabel), findsOneWidget);
      expect(find.text(de.create), findsOneWidget);
    });

    testWidgets('the move picker', (tester) async {
      await tester.pumpWidget(
        localizedApp(
          FolderPicker(
            client: recordingClient(treeAnswer, []),
            initialPath: const [],
            confirmLabel: (path) => "move",
          ),
          locale: const Locale("de"),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text(de.moveToAction), findsOneWidget);
      expect(find.text(de.pickerTopLevel), findsOneWidget);
      // An image lives in an album, and the top level is a folder of folders.
      expect(find.text(de.imagesLiveInAlbums), findsOneWidget);
      expect(find.text(de.cancel), findsOneWidget);
    });

    testWidgets('the image properties of the viewer', (tester) async {
      await tester.pumpWidget(
        localizedApp(
          ImagePropertiesDialog(
            ImagePart(
              name: "a.jpg",
              date: DateTime.utc(2026, 3, 1, 12).millisecondsSinceEpoch,
              camera: "Pentax K-3",
              location: GeoLocation(latitude: 48.123456, longitude: 8.654321),
            ),
            editable: false,
          ),
          locale: const Locale("de"),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text(de.imageProperties), findsOneWidget);
      expect(find.text(de.propertyFile("a.jpg")), findsOneWidget);
      expect(find.text(de.propertyCamera("Pentax K-3")), findsOneWidget);
      expect(
        find.text(de.propertyLocation("48.123456", "8.654321")),
        findsOneWidget,
      );
      expect(find.byTooltip(de.showOnMap), findsOneWidget);
    });

    testWidgets('the inbox screen', (tester) async {
      speakGerman(tester);
      await pumpInbox(tester, inboxTree);
      await tester.tap(find.byIcon(Icons.more_vert).last);
      await tester.pumpAndSettle();

      expect(find.text(de.albumProperties), findsOneWidget);
      expect(find.text(de.reload), findsOneWidget);
      expect(find.text(de.serverMenuEntry), findsOneWidget);
      // The day headings are the locale's own, which is why they go through
      // `DateFormat` rather than through a table of names. The newest day
      // stands at the top and is the one that is built without a scroll.
      expect(
        find.text(inboxDayFormat(de).format(DateTime(2026, 4, 5))),
        findsOneWidget,
      );
      expect(
        inboxDayFormat(de).format(DateTime(2026, 3, 1)),
        isNot(inboxDayFormat(l10nOf(const Locale("en")))
            .format(DateTime(2026, 3, 1))),
      );
    });

    test('the camera-roll status line', () {
      var status = CameraRollStatus(
        phase: CameraRollPhase.idle,
        lastSuccess: DateTime.utc(2026, 3, 1, 12),
        lastStored: 2,
        lastPresent: 1,
      );
      // The hour is the reader's local one, whatever zone the test runs in.
      var hour = DateFormat.Hm(de.localeName).format(status.lastSuccess!.toLocal());
      expect(cameraRollLine(status, de), de.cameraRollSynced(2, 3, hour, 1));
      expect(
        cameraRollLine(const CameraRollStatus(), de),
        de.cameraRollOff,
      );
      expect(
        cameraRollLine(
          const CameraRollStatus(
            phase: CameraRollPhase.failed,
            notice: NoWifiMobile(),
          ),
          de,
        ),
        de.cameraRollFailed(de.noticeNoWifiMobile),
      );
      expect(de.cameraRollOff, isNot(l10nOf(const Locale("en")).cameraRollOff));
    });
  });

  test('the German words of slice 3 are not the English ones', () {
    var en = l10nOf(const Locale("en"));
    expect(de.albumProperties, isNot(en.albumProperties));
    expect(de.createAlbum, isNot(en.createAlbum));
    expect(de.moveToAction, isNot(en.moveToAction));
    expect(de.imageProperties, isNot(en.imageProperties));
    expect(de.inboxEmptyNotice, isNot(en.inboxEmptyNotice));
  });
}

/// Pumps the root listing of the move fixtures.
Future<void> pumpListingIn(WidgetTester tester) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(
      VAlbumApp(client: recordingClient(treeAnswer, [])),
    );
    await tester.pumpAndSettle();
  });
}

/// The screens of slice 2, each pumped in a German locale.
///
/// One assertion block per screen, and always against the generated
/// `AppLocalizations` of German — never against a German string typed here,
/// so that a retranslation cannot break these tests. What they hold is the
/// same thing the settings screen holds above: a screen that was converted
/// speaks the language of the device, all of it.
void sliceTwo() {
  group('the face editor speaks German', () {
    testWidgets('its groups, its banners and its menu entry', (tester) async {
      speakGerman(tester);
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(
          requests,
          auth: authOf(),
          album: albumOf(pending: true),
        ),
      );

      expect(find.text(de.personsTitle), findsOneWidget);
      expect(find.text(de.personsUnknownGroup), findsOneWidget);
      expect(find.text(de.personsNotAFaceGroup), findsOneWidget);
      expect(find.text(de.personsNewGroup), findsOneWidget);
      expect(find.text(de.personsPendingNotice), findsOneWidget);
      expect(find.text(de.personsFaceCount(2)), findsOneWidget);
      // The way back out of a decision, issue #138.
      expect(find.text(de.personsForgetTarget), findsOneWidget);

      var en = l10nOf(const Locale("en"));
      expect(find.text(en.personsNotAFaceGroup), findsNothing);
      expect(find.text(en.personsForgetTarget), findsNothing);
    });

    testWidgets('the actions offered on a selection (issue #139)',
        (tester) async {
      speakGerman(tester);
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(requests, auth: authOf(), album: albumOf()),
      );

      await selectFace(tester, "a.jpg#0");
      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(const Key("persons-selection-menu")));
        await tester.pumpAndSettle();
      });

      expect(find.text(de.personsNameEntry), findsOneWidget);
      expect(find.text(de.personsDeferEntry), findsOneWidget);
      // "Kein Gesicht" (issue #144) is also the heading of the group behind
      // the menu, so this one is asked for by its key.
      expect(
        find.descendant(
          of: find.byKey(const Key("persons-not-a-face")),
          matching: find.text(de.personsNotAFaceEntry),
        ),
        findsOneWidget,
      );

      var en = l10nOf(const Locale("en"));
      expect(find.text(en.personsNameEntry), findsNothing);
      expect(find.text(en.personsDeferEntry), findsNothing);
      expect(find.text(en.personsNotAFaceEntry), findsNothing);
    });

    testWidgets('the entries linking a person to a member', (tester) async {
      speakGerman(tester);
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(requests, auth: authOf(), album: albumOf()),
      );

      await tester.tap(find.byKey(const Key("persons-menu-p-anna")));
      await tester.pumpAndSettle();

      expect(find.text(de.personsLinkMeEntry), findsOneWidget);
      expect(find.text(de.personsRenameEntry), findsOneWidget);
      var en = l10nOf(const Locale("en"));
      expect(find.text(en.personsLinkMeEntry), findsNothing);
    });

    testWidgets('the count of an inbox on its listing tile', (tester) async {
      speakGerman(tester);
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
          client: recordingClient((_) => json(listingWithCount), []),
          initialRoute: const ListingOrAlbumRoute([]),
        ));
        await tester.pumpAndSettle();
      });

      expect(find.text(de.inboxPhotoCount(12)), findsOneWidget);
      var en = l10nOf(const Locale("en"));
      expect(find.text(en.inboxPhotoCount(12)), findsNothing);
    });

    testWidgets('the person a member is, in the users list', (tester) async {
      await tester.pumpWidget(localizedApp(
        Scaffold(
          body: UsersSection(
            client: VAlbumClient(
              dataUrl: "http://server/valbum/data",
              httpClient: MockClient((request) async => json(
                    '{"users": [{"name": "haui", "role": "admin", '
                    '"devices": 1, "person": "p-anna", '
                    '"personName": "Anna"}]}',
                  )),
            ),
          ),
        ),
        locale: const Locale("de"),
      ));
      await tester.pumpAndSettle();

      expect(find.text(de.appearsInPhotosAs("Anna")), findsOneWidget);
      var en = l10nOf(const Locale("en"));
      expect(find.text(en.appearsInPhotosAs("Anna")), findsNothing);
    });
  });

  group('the trash page speaks German (issue #152)', () {
    testWidgets('its title, its tools and the purge question',
        (tester) async {
      speakGerman(tester);
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
          client: trash.server(requests: []),
          initialRoute: const TrashRoute(["Zoo"]),
          settings: ServerSettings(
            store: InMemorySettingsStore(),
            platformDefault: () => trash.dataUrl,
            token: "dev-9",
            userName: "carol",
            loaded: true,
          ),
        ));
        await tester.pumpAndSettle();
      });

      expect(find.text(de.trashPageTitle), findsOneWidget);
      expect(find.text(de.trashPurgeAction), findsOneWidget);
      expect(find.byTooltip(de.trashRestore), findsNWidgets(2));

      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(const Key("trash-purge")));
        await tester.pumpAndSettle();
      });
      expect(find.text(de.trashPurgeTitle), findsOneWidget);
      expect(find.text(de.trashPurgeMessage), findsOneWidget);
      expect(find.text(de.trashPurgeConfirm), findsOneWidget);

      var en = l10nOf(const Locale("en"));
      expect(find.text(en.trashPageTitle), findsNothing);
      expect(find.text(en.trashPurgeMessage), findsNothing);
    });
  });

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
      // `findsWidgets`: German spells the heading "Shows" and the right
      // "View" with the same word, and the dialog carries both.
      expect(find.text(de.showsHeading), findsWidgets);
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

  test('the viewer names its faces in German too (issues #147 and #150)', () {
    var en = l10nOf(const Locale("en"));
    expect(de.viewerEditPersons, "Personen bearbeiten");
    expect(de.viewerMarkFace, isNot(en.viewerMarkFace));
    expect(de.viewerFaceDecision, isNot(en.viewerFaceDecision));
    expect(de.viewerEditPersonsDone, isNot(en.viewerEditPersonsDone));
    expect(de.personsChooserInAlbum, "In diesem Album");
    expect(de.personsChooserAll, isNot(en.personsChooserAll));
  });

  test('the German words of slice 2 are not the English ones', () {
    var en = l10nOf(const Locale("en"));
    expect(de.cameraRollHeading, isNot(en.cameraRollHeading));
    expect(de.videoCannotPlay, isNot(en.videoCannotPlay));
    expect(de.inviteDialogTitle, isNot(en.inviteDialogTitle));
    expect(de.photoLibraryTitle, isNot(en.photoLibraryTitle));
  });
}
