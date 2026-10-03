/// A background run reads the photo permission and never asks for it
/// (issue #169).
///
/// Since #166 the app asks for the photos together with
/// `ACCESS_MEDIA_LOCATION` through `requestPermissionExtend`. On Android 14 and
/// later that request needs an Activity whenever a permission is not held yet;
/// the engine WorkManager starts for a background run has none, and the run
/// failed with a `NullPointerException` and its whole Java stack on the
/// settings screen. These tests pin the four decisions of the fix:
///
///  * a background library only reads the state (`getPermissionState`) and
///    asks the app's own `media_location` plugin whether the location may be
///    read — both answered without an Activity;
///  * without the location permission the background run uploads nothing and
///    says why, because every original would be read as its redacted copy;
///  * a platform failure is told by its message, never by its stack;
///  * every line of the camera-roll section can be selected and copied.
///
/// The platform channels are mocked: the test binding reaches no device.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:media_location/media_location.dart';
import 'package:photo_manager/photo_manager.dart' show PermissionState;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/notices.dart';
import 'package:valbum_ui/photo_library_manager.dart';

import 'util/l10n.dart';

/// The channel `photo_manager` talks to its platform side on.
const MethodChannel photoManagerChannel =
    MethodChannel("com.fluttercandies/photo_manager");

/// The moment every test reads as "now".
final DateTime now = DateTime.utc(2026, 9, 28, 16, 32);

/// A long Java stack, as the platform put it into the `details` of the
/// exception the user reported.
const String javaStack = "java.lang.NullPointerException: Attempt to invoke "
    "virtual method 'java.lang.Class java.lang.Object.getClass()' on a null "
    "object reference\n"
    "\tat h10.e(r8-map-id-0123456789abcdef:12)\n"
    "\tat android.os.Handler.handleCallback(Handler.java:959)\n"
    "\tat com.android.internal.os.ZygoteInit.main(ZygoteInit.java:1019)\n";

/// The message of that exception.
const String javaMessage = "Attempt to invoke virtual method "
    "'java.lang.Class java.lang.Object.getClass()' on a null object reference";

/// What the mocked platform side of `photo_manager` and of `media_location`
/// answers, and what it was asked.
class FakePlatform {
  /// Every method `photo_manager` was asked, in order.
  final List<MethodCall> photoCalls = [];

  /// How often the location question was asked.
  int locationQuestions = 0;

  /// The permission state answered, as `photo_manager` encodes it
  /// ([PermissionState.index]).
  PermissionState photos;

  /// Whether `ACCESS_MEDIA_LOCATION` is held.
  bool location;

  /// What `getPermissionState` throws instead of answering, if anything.
  PlatformException? permissionFailure;

  FakePlatform({
    this.photos = PermissionState.authorized,
    this.location = true,
  });

  /// Installs the handlers; they are removed when the test ends.
  void install() {
    var messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(photoManagerChannel, (call) async {
      photoCalls.add(call);
      switch (call.method) {
        case "getPermissionState":
        case "requestPermissionExtend":
          var failure = permissionFailure;
          if (failure != null) {
            throw failure;
          }
          return photos.index;
        default:
          // Every listing of the library: nothing there.
          return {"data": <Object>[]};
      }
    });
    messenger.setMockMethodCallHandler(mediaLocationChannel, (call) async {
      expect(call.method, mediaLocationHeldMethod);
      locationQuestions++;
      return location;
    });
    addTearDown(() {
      messenger.setMockMethodCallHandler(photoManagerChannel, null);
      messenger.setMockMethodCallHandler(mediaLocationChannel, null);
    });
  }

  /// The names of the methods `photo_manager` was asked.
  List<String> get photoMethods => [for (var call in photoCalls) call.method];
}

/// The library a background run builds, talking to the mocked channels.
///
/// The location question goes to the plugin's channel directly: the library's
/// own default asks it on Android only, and the test runs on the host.
PhotoManagerLibrary backgroundLibrary() => PhotoManagerLibrary(
      interactive: false,
      locationGranted: isMediaLocationGranted,
    );

/// A store holding a switched-on sync into `Inbox` on a paired server.
InMemorySettingsStore enabledStore() {
  var store = InMemorySettingsStore("http://server/valbum/", "token", "Phone");
  store.cameraRoll = const CameraRollConfig(
    enabled: true,
  ).toJson();
  return store;
}

/// A server that stores every upload and knows nothing else.
class FakeServer {
  final List<http.Request> uploads = [];

  http.Client get transport => MockClient((request) async {
        // Where the inbox is, as `?type=auth` names it (issue #226).
        if (request.url.queryParameters["type"] == "auth") {
          return http.Response(
              '{"mode":"writes","role":"admin","inbox":"${"Inbox"}"}', 200);
        }
        if (request.method == "POST") {
          return http.Response('{"present":[]}', 200);
        }
        if (request.method == "PUT") {
          uploads.add(request);
          return http.Response("", 200);
        }
        return http.Response("", 404);
      });
}

/// Runs one background sync against the mocked platform.
Future<BackgroundRunResult> runInBackground(
  WidgetTester tester,
  InMemorySettingsStore store,
  FakeServer server,
) async {
  var library = backgroundLibrary();
  addTearDown(library.dispose);
  return (await tester.runAsync(() => runBackgroundSync(
        store: store,
        library: library,
        transport: server.transport,
        connectivity: FakeConnectivity(),
        clock: () => now,
      )))!;
}

/// Pumps the camera-roll section in [locale], showing what [store] recorded.
Future<void> pumpSection(
  WidgetTester tester,
  InMemorySettingsStore store, {
  Locale locale = defaultTestLocale,
}) async {
  var sync = CameraRollSync(
    callerOf: () async => CallerInfo(role: roleMember, inbox: "Inbox"),
    store: store,
    library: FakePhotoLibrary(),
    clientOf: () => null,
    scheduler: FakeBackgroundScheduler(),
  );
  addTearDown(sync.dispose);
  await tester.runAsync(sync.load);
  await tester.pumpWidget(localizedApp(
    CameraRollScope(
      sync: sync,
      child: const Scaffold(
        body: SingleChildScrollView(child: CameraRollSection()),
      ),
    ),
    locale: locale,
  ));
  await tester.pumpAndSettle();
}

void main() {
  group('the access check of a background library', () {
    testWidgets('reads the state and never requests a permission',
        (tester) async {
      var platform = FakePlatform()..install();
      var library = backgroundLibrary();

      expect(await library.requestAccess(), isTrue);

      expect(platform.photoMethods, ["getPermissionState"]);
      expect(platform.photoMethods, isNot(contains("requestPermissionExtend")));
      // It asks about the same permissions the foreground requests, the
      // location included, so that "limited" and "denied" mean the same.
      var arguments = platform.photoCalls.single.arguments as Map;
      expect((arguments["androidPermission"] as Map)["mediaLocation"], isTrue);
      expect(platform.locationQuestions, 1);
      expect(library.accessProblem, isNull);
    });

    testWidgets('the app on the screen still asks, the location included',
        (tester) async {
      var platform = FakePlatform()..install();
      var library =
          PhotoManagerLibrary(locationGranted: isMediaLocationGranted);

      expect(await library.requestAccess(), isTrue);

      expect(platform.photoMethods, ["requestPermissionExtend"]);
      var arguments = platform.photoCalls.single.arguments as Map;
      expect((arguments["androidPermission"] as Map)["mediaLocation"], isTrue);
    });

    testWidgets('declines without the location permission', (tester) async {
      var platform = FakePlatform(location: false)..install();
      var library = backgroundLibrary();

      expect(await library.requestAccess(), isFalse);

      expect(library.accessProblem, const MediaLocationNotGranted());
      expect(platform.photoMethods, isNot(contains("requestPermissionExtend")));
    });

    testWidgets('answers the photo refusal where the photos are not allowed',
        (tester) async {
      var platform = FakePlatform(photos: PermissionState.denied)..install();
      var library = backgroundLibrary();

      expect(await library.requestAccess(), isFalse);

      expect(library.accessProblem, const PhotoAccessDenied());
      // Nothing to ask about the location of photos it may not read.
      expect(platform.locationQuestions, 0);
      expect(platform.photoMethods, ["getPermissionState"]);
    });

    testWidgets('accepts the photos the user picked ("limited")',
        (tester) async {
      FakePlatform(photos: PermissionState.limited).install();
      var library = backgroundLibrary();

      expect(await library.requestAccess(), isTrue);
    });

    testWidgets('declines where the location question cannot be answered',
        (tester) async {
      FakePlatform().install();
      var library = PhotoManagerLibrary(
        interactive: false,
        locationGranted: () async => throw MissingPluginException("gone"),
      );

      expect(await library.requestAccess(), isFalse);

      expect(library.accessProblem, const PhotoLibraryOpenFailed("gone"));
    });
  });

  group('a platform failure', () {
    testWidgets('is told by its message, not by its Java stack',
        (tester) async {
      var platform = FakePlatform()..install();
      platform.permissionFailure = PlatformException(
        code: "error",
        message: javaMessage,
        details: javaStack,
      );
      var library = backgroundLibrary();

      expect(await library.requestAccess(), isFalse);

      var text = noticeText(library.accessProblem!, testL10n);
      expect(text, contains(javaMessage));
      expect(text, isNot(contains("at android.os")));
      expect(text, isNot(contains("PlatformException")));
      expect(text, isNot(contains("ZygoteInit")));
    });

    test('without a message is told by its code', () {
      expect(
        platformErrorText(PlatformException(code: "no_access", details: "x")),
        "no_access",
      );
      expect(
        platformErrorText(
            PlatformException(code: "no_access", message: "  ", details: "x")),
        "no_access",
      );
      expect(platformErrorText(StateError("plain")), "Bad state: plain");
    });

    testWidgets('of the foreground request is told by its message too',
        (tester) async {
      var platform = FakePlatform()..install();
      platform.permissionFailure = PlatformException(
        code: "error",
        message: javaMessage,
        details: javaStack,
      );
      var library =
          PhotoManagerLibrary(locationGranted: isMediaLocationGranted);

      expect(await library.requestAccess(), isFalse);

      expect(library.accessProblem, const PhotoLibraryOpenFailed(javaMessage));
    });
  });

  group('a background run', () {
    testWidgets('without the location permission uploads nothing and says so',
        (tester) async {
      var platform = FakePlatform(location: false)..install();
      var store = enabledStore();
      var server = FakeServer();

      var result = await runInBackground(tester, store, server);

      expect(result.ran, isTrue);
      expect(result.ok, isFalse);
      expect(server.uploads, isEmpty);
      // Nothing of the library was read, so nothing redacted could be.
      expect(platform.photoMethods, ["getPermissionState"]);
      expect(
        result.record?.message,
        testL10n.noticeMediaLocationNotGranted,
      );
      expect(await store.loadBackgroundRunRecord(), result.record);

      await pumpSection(tester, store);
      expect(
        tester.widget<Text>(find.byKey(cameraRollBackgroundKey)).data,
        allOf(
          contains("Open the app once so it may read where the photos were "
              "taken; until then the background sync uploads nothing."),
          contains("failed"),
        ),
      );
    });

    testWidgets('on a German phone records and shows the German sentence',
        (tester) async {
      tester.platformDispatcher.localesTestValue = const [Locale("de")];
      addTearDown(tester.platformDispatcher.clearLocalesTestValue);
      FakePlatform(location: false).install();
      var store = enabledStore();
      var server = FakeServer();

      var result = await runInBackground(tester, store, server);

      var german = l10nOf(const Locale("de"));
      expect(result.record?.message, german.noticeMediaLocationNotGranted);
      expect(
        german.noticeMediaLocationNotGranted,
        isNot(testL10n.noticeMediaLocationNotGranted),
      );

      await pumpSection(tester, store, locale: const Locale("de"));
      expect(
        tester.widget<Text>(find.byKey(cameraRollBackgroundKey)).data,
        contains(german.noticeMediaLocationNotGranted),
      );
    });

    testWidgets('with the location permission proceeds as before',
        (tester) async {
      var platform = FakePlatform(location: true)..install();
      var store = enabledStore();
      var server = FakeServer();

      var result = await runInBackground(tester, store, server);

      expect(result.ok, isTrue, reason: "$result");
      expect(platform.photoMethods.first, "getPermissionState");
      expect(platform.photoMethods, isNot(contains("requestPermissionExtend")));
      // It went on to read the library.
      expect(platform.photoMethods, contains("getAssetPathList"));
      expect(platform.locationQuestions, 1);
    });

    testWidgets('without photo access records the photo refusal',
        (tester) async {
      FakePlatform(photos: PermissionState.denied).install();
      var store = enabledStore();
      var server = FakeServer();

      var result = await runInBackground(tester, store, server);

      expect(result.ok, isFalse);
      expect(server.uploads, isEmpty);
      expect(result.record?.message, testL10n.noticePhotoAccessDenied);
    });
  });

  group('the camera-roll section', () {
    testWidgets('can be selected and copied, and its controls still work',
        (tester) async {
      var store = enabledStore();
      await store.saveBackgroundRunRecord(
        BackgroundRunRecord.failed(now, "The photo library cannot be opened."),
      );

      await pumpSection(tester, store);

      for (var key in [cameraRollStatusKey, cameraRollBackgroundKey]) {
        expect(
          find.ancestor(
            of: find.byKey(key),
            matching: find.byType(SelectionArea),
          ),
          findsOneWidget,
          reason: "$key",
        );
      }
      // The switches under the region still take their taps.
      bool wifiOnly() => tester
          .widget<SwitchListTile>(find.byKey(cameraRollWifiOnlyKey))
          .value;
      var before = wifiOnly();
      await tester.tap(find.byKey(cameraRollWifiOnlyKey));
      await tester.pumpAndSettle();
      expect(wifiOnly(), !before);
    });
  });
}
