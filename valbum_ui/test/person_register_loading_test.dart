/// The register of the people as the persons choosers wait for it (issue
/// #291): never an empty register where it is on its way or failed — that
/// said "Nobody yet" and offered a new person the space already had.
///
/// The [PeopleRegistry] keeps a failure as a failure and asks again; the face
/// editor and the viewer open their chooser at once and let it wait, say the
/// reason of a failure with a Retry, and create nobody before the register
/// arrived.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/people_registry.dart';

import 'persons_view_test.dart' as editor;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/viewer_harness.dart';
import 'viewer_edit_persons_test.dart' as viewer;

/// A failed answer of the server, in its own words.
http.Response refusal(int status, String message) => http.Response(
      '["ErrorInfo",{"message":"$message"}]',
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// The register of the viewer tests, answered.
http.Response registerAnswer() => http.Response(
      viewer.register,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// A client answering `?type=people` by [people], counting the requests.
VAlbumClient peopleClient(
  List<http.Request> asked,
  Future<http.Response> Function() people,
) =>
    VAlbumClient(
      dataUrl: viewerDataUrl,
      httpClient: MockClient((request) async {
        if (request.url.queryParameters["type"] == "people") {
          asked.add(request);
          return people();
        }
        return http.Response("No such resource: ${request.url.path}", 404);
      }),
    );

/// The viewer's client: the register by [people], taggings and creations
/// recorded in [posted] and [created].
VAlbumClient viewerClient(
  Future<http.Response> Function() people, {
  required List<Map<String, dynamic>> posted,
  required List<String> created,
}) =>
    VAlbumClient(
      dataUrl: viewerDataUrl,
      httpClient: MockClient((request) async {
        var query = request.url.queryParameters;
        if (query["type"] == "people") {
          return people();
        }
        if (query["action"] == "tag-faces") {
          posted.add(jsonDecode(request.body) as Map<String, dynamic>);
          return http.Response(
            viewer.confirmedAlbum,
            200,
            headers: {"content-type": "application/json; charset=utf-8"},
          );
        }
        if (query["action"] == "create-person") {
          created.add(request.body);
          return http.Response("Unexpected", 500);
        }
        if (isThumbnailRequest(request)) {
          return http.Response.bytes(
            transparentPixelPng,
            200,
            headers: {"content-type": "image/png"},
          );
        }
        return http.Response("No such resource: ${request.url.path}", 404);
      }),
    );

/// Lets a few frames pass without waiting for a progress indicator to stop.
Future<void> pumpFew(WidgetTester tester) => withFakeImageHttp(() async {
      for (var n = 0; n < 5; n++) {
        await tester.pump(const Duration(milliseconds: 50));
      }
    });

/// Lets everything settle, the images answered.
Future<void> settle(WidgetTester tester) =>
    withFakeImageHttp(() => tester.pumpAndSettle());

/// The "New person" entry of the chooser.
TextButton newPerson(WidgetTester tester) =>
    tester.widget<TextButton>(find.byKey(const Key("persons-new-person")));

/// Opens the viewer's chooser on the face nobody decided about.
Future<void> openViewerChooser(WidgetTester tester) async {
  await viewer.enterMode(tester);
  await tester.tap(find.byKey(const Key("face-box-2")));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key("face-decision-name")));
}

void main() {
  group("the registry", () {
    test("keeps a failure as a failure and asks again", () async {
      var asked = <http.Request>[];
      var answer = refusal(500, "The register cannot be read.");
      var registry = PeopleRegistry.of(peopleClient(asked, () async => answer));

      await expectLater(registry.load(), throwsA(isA<VAlbumException>()));
      expect(registry.people, isEmpty);
      expect(registry.persons.hasValue, isFalse,
          reason: "A failure is no empty register.");
      expect(registry.persons.error, isNotNull);
      expect(asked, hasLength(1));

      answer = registerAnswer();
      var people = await registry.load();
      expect(asked, hasLength(2), reason: "The next load asks again.");
      expect(people.keys, containsAll(["p-anna", "p-bob"]));

      await registry.load();
      expect(asked, hasLength(2), reason: "An answer is held.");
    });

    for (var status in [401, 403]) {
      test("keeps a refusal ($status) as the answer, asked once", () async {
        var asked = <http.Request>[];
        var registry = PeopleRegistry.of(
            peopleClient(asked, () async => refusal(status, "Not for you.")));

        await expectLater(registry.load(), throwsA(isA<VAlbumException>()));
        await expectLater(registry.load(), throwsA(isA<VAlbumException>()));
        expect(asked, hasLength(1),
            reason: "A caller who may not see persons is not asked again.");
        expect(registry.persons.hasValue, isFalse);

        // An explicit retry still asks.
        await expectLater(
            registry.persons.retry(), throwsA(isA<VAlbumException>()));
        expect(asked, hasLength(2));
      });
    }

    test("asks once for callers waiting together", () async {
      var asked = <http.Request>[];
      var arrival = Completer<http.Response>();
      var registry =
          PeopleRegistry.of(peopleClient(asked, () => arrival.future));
      var first = registry.load();
      var second = registry.load();
      arrival.complete(registerAnswer());
      expect((await first).keys, (await second).keys);
      expect(asked, hasLength(1));
    });
  });

  group("the face editor", () {
    testWidgets("waits for the register, says why it failed, and retries",
        (tester) async {
      var requests = <http.Request>[];
      var asked = 0;
      Future<http.Response> Function() answer =
          () async => refusal(500, "The register cannot be read.");
      await editor.pumpEditor(
        tester,
        editor.editorClient(
          requests,
          auth: editor.authOf(),
          album: editor.albumOf(),
          peopleAnswer: () {
            asked++;
            return answer();
          },
          post: (request) => http.Response("Unexpected", 500),
        ),
      );
      expect(asked, 1);

      // Opened over the failed register: asked again, and failed again.
      await tester.tap(editor.header("cluster:c1"));
      await settle(tester);
      expect(asked, 2);
      expect(find.byKey(const Key("persons-chooser")), findsOneWidget);
      expect(find.byKey(const Key("persons-register-failed")), findsOneWidget);
      expect(
          find.textContaining("The register cannot be read."), findsOneWidget);
      expect(find.byKey(const Key("persons-nobody")), findsNothing);
      expect(newPerson(tester).onPressed, isNull);

      // The retry is slow: it waits, then fills.
      var arrival = Completer<http.Response>();
      answer = () => arrival.future;
      await tester.tap(find.byKey(const Key("persons-register-retry")));
      await pumpFew(tester);
      expect(asked, 3);
      expect(find.byKey(const Key("persons-register-loading")), findsOneWidget);
      expect(newPerson(tester).onPressed, isNull);

      arrival.complete(editor.json(editor.annaOnly));
      await settle(tester);
      expect(find.byKey(const Key("persons-pick-p-anna")), findsOneWidget);
      expect(newPerson(tester).onPressed, isNotNull);
      expect(editor.posted(requests, "create-person"), isEmpty);
    });

    testWidgets("the merge waits for the register as well", (tester) async {
      var requests = <http.Request>[];
      var arrival = Completer<http.Response>();
      var registerOf = '{"people": [{"id": "p-anna", "name": "Anna"}, '
          '{"id": "p-bob", "name": "Bob"}]}';
      await editor.pumpEditor(
        tester,
        editor.editorClient(
          requests,
          auth: editor.authOf(),
          album: editor.albumOf(),
          peopleAnswer: () => arrival.future,
        ),
      );

      var screen =
          tester.state<PersonsContentState>(find.byType(PersonsContent));
      var group = screen.groups.firstWhere((g) => g.person == "p-anna");
      unawaited(screen.mergePerson(group));
      await pumpFew(tester);
      expect(find.byKey(const Key("persons-register-loading")), findsOneWidget);

      arrival.complete(editor.json(registerOf));
      await settle(tester);
      expect(find.byKey(const Key("persons-pick-p-bob")), findsOneWidget);
      expect(find.byKey(const Key("persons-pick-p-anna")), findsNothing,
          reason: "Nobody is merged into themselves.");
    });
  });

  group("the viewer", () {
    setUp(withEmptyImageCache);

    testWidgets("opens its chooser at once and lets it wait", (tester) async {
      var posted = <Map<String, dynamic>>[];
      var created = <String>[];
      var arrival = Completer<http.Response>();
      await viewer.pumpEditor(
        tester,
        client: viewerClient(() => arrival.future,
            posted: posted, created: created),
      );
      await openViewerChooser(tester);
      await pumpFew(tester);

      expect(find.byKey(const Key("persons-chooser")), findsOneWidget);
      expect(find.byKey(const Key("persons-register-loading")), findsOneWidget);
      expect(find.byKey(const Key("persons-nobody")), findsNothing);
      expect(newPerson(tester).onPressed, isNull);

      await tester.enterText(find.byKey(const Key("persons-search")), "Bo");
      await pumpFew(tester);
      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await pumpFew(tester);
      expect(find.byType(PersonNameDialog), findsNothing,
          reason: "No new person over a register on its way.");

      arrival.complete(registerAnswer());
      await settle(tester);
      expect(find.byKey(const Key("persons-pick-p-bob")), findsOneWidget);
      await tester.tap(find.byKey(const Key("persons-pick-p-bob")));
      await settle(tester);

      var only = viewer.assignmentsOf(posted).first as Map<String, dynamic>;
      expect(only["person"], "p-bob");
      expect(created, isEmpty);
    });

    testWidgets("says why the register failed, and a retry fills it",
        (tester) async {
      var posted = <Map<String, dynamic>>[];
      var created = <String>[];
      var asked = 0;
      Future<http.Response> Function() answer =
          () async => refusal(500, "The register cannot be read.");
      await viewer.pumpEditor(
        tester,
        client: viewerClient(() {
          asked++;
          return answer();
        }, posted: posted, created: created),
      );
      await openViewerChooser(tester);
      await settle(tester);

      expect(find.byKey(const Key("persons-register-failed")), findsOneWidget);
      expect(
          find.textContaining("The register cannot be read."), findsOneWidget);
      expect(find.byKey(const Key("persons-nobody")), findsNothing);
      expect(newPerson(tester).onPressed, isNull);
      var before = asked;

      answer = () async => registerAnswer();
      await tester.tap(find.byKey(const Key("persons-register-retry")));
      await settle(tester);
      expect(asked, before + 1);
      await tester.tap(find.byKey(const Key("persons-pick-p-anna")));
      await settle(tester);

      var only = viewer.assignmentsOf(posted).first as Map<String, dynamic>;
      expect(only["person"], "p-anna");
      expect(created, isEmpty);
    });
  });
}
