/// Tests of the face editor (issue #126): the entry that opens it, the groups
/// it shows, the delta its Save posts, and what it refuses to a caller who
/// may not name a face.
library;

import 'dart:convert';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The server the tests talk to.
const String dataUrl = "http://server/valbum/data";

/// An answer with the JSON content type the app expects.
http.Response json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// The path of a request, with the percent-encoding of the wire undone.
String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

/// The answer of `?type=auth`, saying whether the space looks for faces.
String authOf({String role = "edit", bool faces = true}) =>
    '{"mode": "writes", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "carol", "role": "$role", "space": "", '
    '"clearance": "all", "mayShare": true, "faces": $faces}';

/// One face of the answer.
String faceOf(
  int index, {
  String cluster = "",
  String person = "",
  String state = "UNDECIDED",
  double x = 0.1,
  double y = 0.1,
}) =>
    '{"index": $index, "x": $x, "y": $y, "w": 0.2, "h": 0.2, '
    '"cluster": "$cluster", "person": "$person", '
    '"confirmed": ${state == "CONFIRMED"}, "state": "$state"}';

/// One photograph of the album, with the given faces.
String imageOf(String name, List<String> faces) =>
    '["ImagePart", {"kind": "IMAGE", "name": "$name", "date": 1, '
    '"width": 300, "height": 200, "orientation": "IDENTITY", "rating": 0, '
    '"faces": [${faces.join(",")}]}]';

/// The album the tests open: three photographs, the faces of issue #126's
/// acceptance list — A1 and A2 in the cluster `c1`, B1 confirmed as Anna, one
/// box somebody said is no face at all.
String albumOf({
  bool pending = false,
  List<String>? images,
  List<String> rights = const ["edit"],
}) =>
    '["AlbumInfo", {"path": "", "title": "Zoo", "subTitle": "", '
    '"facesPending": $pending, '
    '"rights": [${rights.map((r) => '{"name": "$r"}').join(",")}], '
    '"parts": [${(images ?? defaultImages).join(",")}]}]';

/// The photographs of the fixture.
final List<String> defaultImages = [
  imageOf("a.jpg", [
    faceOf(0, cluster: "c1"),
    faceOf(1, cluster: "c1", y: 0.5),
  ]),
  imageOf("b.jpg", [
    faceOf(0, cluster: "c2", person: "p-anna", state: "CONFIRMED"),
  ]),
  imageOf("c.jpg", [
    faceOf(0, cluster: "c3", state: "NOT_A_FACE"),
  ]),
];

/// The register of the space: one person, Anna.
const String annaOnly = '{"people": [{"id": "p-anna", "name": "Anna"}]}';

/// What the server answers a request the test does not describe.
typedef Answer = http.Response Function(http.Request request);

/// The transport the tests talk through, recording every request.
///
/// Everything the editor needs is answered here: the album, `?type=auth`,
/// `?type=people` and the face crops — the crop as a pixel, so that a test can
/// still see in [requests] *which* crops were asked for, which is how "a tag
/// without a detection asks for none" is checked.
VAlbumClient editorClient(
  List<http.Request> requests, {
  required String auth,
  required String album,
  String people = annaOnly,
  Answer? post,
}) =>
    VAlbumClient(
      dataUrl: dataUrl,
      token: "dev-9",
      userName: "carol",
      httpClient: MockClient(servingThumbnails((request) async {
        requests.add(request);
        var query = request.url.queryParameters;
        if (query["type"] == "auth") {
          return json(auth);
        }
        if (query["type"] == "people") {
          return json(people);
        }
        if (query["type"] == "face") {
          return http.Response.bytes(
            transparentPixelPng,
            200,
            headers: {"content-type": "image/png"},
          );
        }
        if (request.method == "POST") {
          return post?.call(request) ??
              http.Response("Unexpected: ${request.url}", 500);
        }
        if (query["type"] == "json" || pathOf(request) == "/valbum/data/") {
          return json(album);
        }
        return http.Response("No such resource: ${pathOf(request)}", 404);
      })),
    );

/// The POST bodies of the given action, decoded.
List<Map<String, dynamic>> posted(List<http.Request> requests, String action) =>
    [
      for (var request in requests)
        if (request.method == "POST" &&
            request.url.queryParameters["action"] == action)
          jsonDecode(request.body) as Map<String, dynamic>,
    ];

/// How often the album itself was fetched.
int albumFetches(List<http.Request> requests) => requests
    .where((request) =>
        request.method == "GET" &&
        request.url.queryParameters["type"] == "json" &&
        pathOf(request) == "/valbum/data/")
    .length;

/// Pumps the app on the album fixture.
Future<void> pumpAlbum(WidgetTester tester, VAlbumClient client) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: client,
      settings: ServerSettings(
        store: InMemorySettingsStore(),
        platformDefault: () => dataUrl,
        token: "dev-9",
        userName: "carol",
        loaded: true,
      ),
    ));
    await tester.pumpAndSettle();
  });
}

/// Opens the album menu of the view shown.
Future<void> openMenu(WidgetTester tester) async {
  await withFakeImageHttp(() async {
    await tester.tap(find.byIcon(Icons.more_vert));
    await tester.pumpAndSettle();
  });
}

/// Opens the album and, through its menu, the face editor.
Future<void> pumpEditor(WidgetTester tester, VAlbumClient client) async {
  await pumpAlbum(tester, client);
  await openMenu(tester);
  await withFakeImageHttp(() async {
    await tester.tap(find.byKey(const Key("persons")));
    await tester.pumpAndSettle();
  });
}

/// The heading of the group of the given key.
Finder header(String key) => find.byKey(Key("persons-header-$key"));

/// The tile of the given face, `<image>#<index>`.
Finder faceTile(String key) => find.byKey(Key("face-$key"));

/// Drags the given face onto [target], the way the album's tiles are dragged
/// (issue #94): a sideways pull lifts the tile, and it is carried anywhere.
Future<void> dragFaceTo(
  WidgetTester tester,
  String face,
  Finder target,
) async {
  await withFakeImageHttp(() async {
    var start = tester.getCenter(faceTile(face));
    var box = tester.getRect(target);
    var gesture = await tester.startGesture(start);
    await gesture.moveBy(const Offset(40, 0));
    await tester.pump();
    await gesture.moveTo(box.center);
    await tester.pump();
    await gesture.up();
    await tester.pumpAndSettle();
  });
}

/// Taps the Save of the editor.
Future<void> saveEditor(WidgetTester tester) async {
  await withFakeImageHttp(() async {
    await tester.tap(find.byKey(const Key("persons-save")));
    await tester.pumpAndSettle();
  });
}

void main() {
  group("the entry", () {
    testWidgets("opens the editor where the space looks for faces",
        (tester) async {
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(requests, auth: authOf(), album: albumOf()),
      );

      expect(find.byType(PersonsContent), findsOneWidget);
      expect(find.text(testL10n.personsTitle), findsOneWidget);
    });

    testWidgets("is absent where the space does not look for faces",
        (tester) async {
      var requests = <http.Request>[];
      await pumpAlbum(
        tester,
        editorClient(
          requests,
          auth: authOf(faces: false),
          album: albumOf(),
        ),
      );
      await openMenu(tester);

      expect(find.byKey(const Key("persons")), findsNothing);
    });

    testWidgets("is absent in a share session", (tester) async {
      // A link is answered no face at all (issue #124), so its editor would
      // be empty by construction — and a link is not an account.
      var requests = <http.Request>[];
      var client = editorClient(
        requests,
        auth: '{"mode": "writes", "deviceName": "", "writeAllowed": false, '
            '"userName": "", "role": "", "space": "", "faces": true, '
            '"share": {"label": "For grandma", "expires": "", '
            '"rights": [{"name": "view"}], "path": "~alice/Zoo"}}',
        album: albumOf(rights: const ["view"]),
      );
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
          client: client,
          settings: ServerSettings(
            store: InMemorySettingsStore(dataUrl, "", "", ""),
            platformDefault: () => dataUrl,
          ),
          session: const SessionUrl(
            kind: SessionKind.share,
            token: "tok-42",
            dataUrl: dataUrl,
            basePath: "/valbum/s/tok-42/",
          ),
        ));
        await tester.pumpAndSettle();
      });
      await openMenu(tester);

      expect(find.byKey(const Key("persons")), findsNothing);
    });
  });

  group("the groups", () {
    testWidgets("are the person, the unknown cluster and what is no face",
        (tester) async {
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(requests, auth: authOf(), album: albumOf()),
      );

      expect(find.text("Anna"), findsOneWidget);
      expect(
        tester
            .widget<Text>(find.byKey(const Key("persons-count-person:p-anna")))
            .data,
        testL10n.personsFaceCount(1),
      );

      expect(find.text(testL10n.personsUnknownGroup), findsOneWidget);
      expect(
        tester
            .widget<Text>(find.byKey(const Key("persons-count-cluster:c1")))
            .data,
        testL10n.personsFaceCount(2),
      );

      // "Not a face" is there, says how many, and shows none of them until
      // it is opened.
      expect(find.text(testL10n.personsNotAFaceGroup), findsOneWidget);
      expect(
        tester
            .widget<Text>(find.byKey(const Key("persons-count-notAFace")))
            .data,
        testL10n.personsFaceCount(1),
      );
      expect(faceTile("c.jpg#0"), findsNothing);

      await tester.tap(header(notAFaceGroup));
      await tester.pumpAndSettle();
      expect(faceTile("c.jpg#0"), findsOneWidget);
    });

    testWidgets("say so where the album has no face at all", (tester) async {
      // Opened by its address, because the menu entry is not offered for an
      // album that has none — this is the editor of an album whose detection
      // finished and found nothing, or a bookmark of one.
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
          client: editorClient(
            requests,
            auth: authOf(),
            album: albumOf(images: [imageOf("a.jpg", [])]),
          ),
          initialRoute: const PersonsRoute([]),
          settings: ServerSettings(
            store: InMemorySettingsStore(),
            platformDefault: () => dataUrl,
            token: "dev-9",
            userName: "carol",
            loaded: true,
          ),
        ));
        await tester.pumpAndSettle();
      });

      expect(find.byKey(const Key("persons-empty")), findsOneWidget);
    });
  });

  group("while the server is still looking", () {
    testWidgets("a banner says so and the album is asked again",
        (tester) async {
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(
          requests,
          auth: authOf(),
          album: albumOf(pending: true),
        ),
      );

      expect(find.byKey(const Key("persons-pending")), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(const Key("persons-pending"))).data,
        testL10n.personsPendingNotice,
      );

      var before = albumFetches(requests);
      await withFakeImageHttp(() async {
        await tester.pump(personsPendingInterval + const Duration(seconds: 1));
        await tester.pumpAndSettle();
      });
      expect(albumFetches(requests), greaterThan(before));

      // And the timer goes with the page, so nothing is left ticking.
      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(const Key("persons-up")));
        await tester.pumpAndSettle();
      });
      expect(find.byType(PersonsContent), findsNothing);
    });
  });

  group("naming a group", () {
    testWidgets("creates the person and confirms every face of it",
        (tester) async {
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(
          requests,
          auth: authOf(),
          album: albumOf(),
          post: (request) {
            switch (request.url.queryParameters["action"]) {
              case "create-person":
                return json('{"id": "p-bob", "name": "Bob"}');
              case "tag-faces":
                return json(albumOf());
            }
            return http.Response("Unexpected", 500);
          },
        ),
      );

      await tester.tap(header("cluster:c1"));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("persons-new-person")));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key("persons-name")), "Bob");
      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(const Key("persons-name-ok")));
        await tester.pumpAndSettle();
      });

      expect(posted(requests, "create-person"), [
        {"name": "Bob"}
      ]);
      // Nothing about the album has gone out yet: the assignments are
      // buffered until Save.
      expect(posted(requests, "tag-faces"), isEmpty);
      expect(find.text("Bob"), findsOneWidget);

      var before = albumFetches(requests);
      await saveEditor(tester);

      expect(posted(requests, "tag-faces"), [
        {
          "faces": [
            {
              "image": "a.jpg",
              "face": 0,
              "person": "p-bob",
              "state": "CONFIRMED"
            },
            {
              "image": "a.jpg",
              "face": 1,
              "person": "p-bob",
              "state": "CONFIRMED"
            },
          ]
        }
      ]);
      // And the album is read again, so that everything shows what the
      // server now has.
      expect(albumFetches(requests), greaterThan(before));
    });
  });

  group("dragging", () {
    testWidgets("onto a person confirms that face and leaves the other",
        (tester) async {
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(
          requests,
          auth: authOf(),
          album: albumOf(),
          post: (request) => json(albumOf()),
        ),
      );

      await dragFaceTo(tester, "a.jpg#0", header("person:p-anna"));
      await saveEditor(tester);

      expect(posted(requests, "tag-faces"), [
        {
          "faces": [
            {
              "image": "a.jpg",
              "face": 0,
              "person": "p-anna",
              "state": "CONFIRMED"
            },
          ]
        }
      ]);
    });

    testWidgets("out of a person rejects that person for the face",
        (tester) async {
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(
          requests,
          auth: authOf(),
          album: albumOf(),
          post: (request) => json(albumOf()),
        ),
      );

      await dragFaceTo(
        tester,
        "b.jpg#0",
        find.byKey(const Key("persons-new-group")),
      );
      await saveEditor(tester);

      expect(posted(requests, "tag-faces"), [
        {
          "faces": [
            {
              "image": "b.jpg",
              "face": 0,
              "person": "p-anna",
              "state": "REJECTED"
            },
          ]
        }
      ]);
    });
  });

  group("a suggestion", () {
    /// The album with A1 suggested — but not confirmed — to be Anna.
    String suggested() => albumOf(images: [
          imageOf("a.jpg", [
            faceOf(0, cluster: "c1", person: "p-anna"),
            faceOf(1, cluster: "c1", y: 0.5),
          ]),
        ]);

    testWidgets("stands under the person with a question mark and is confirmed",
        (tester) async {
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(
          requests,
          auth: authOf(),
          album: suggested(),
          post: (request) => json(suggested()),
        ),
      );

      expect(find.text(testL10n.personsSuggestedHeading("Anna")), findsOneWidget);

      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(const Key("persons-confirm-p-anna")));
        await tester.pumpAndSettle();
      });
      await saveEditor(tester);

      expect(posted(requests, "tag-faces"), [
        {
          "faces": [
            {
              "image": "a.jpg",
              "face": 0,
              "person": "p-anna",
              "state": "CONFIRMED"
            },
          ]
        }
      ]);
    });
  });

  testWidgets("a tag without a detection is drawn without asking for a crop",
      (tester) async {
    // Flutter's image cache outlives one test, and a crop of the same URL
    // asked for by another one would be answered from it: what is counted
    // here are requests, so the cache starts empty.
    forgetDecodedThumbnails();
    var requests = <http.Request>[];
    await pumpEditor(
      tester,
      editorClient(
        requests,
        auth: authOf(),
        album: albumOf(images: [
          imageOf("a.jpg", [
            faceOf(0, cluster: "c1"),
            // Behind every detection, without a cluster: a stored tag the
            // detector does not find, see issue #125.
            faceOf(1, person: "p-anna", state: "CONFIRMED", y: 0.6),
          ]),
        ]),
      ),
    );

    // It is shown, under Anna…
    expect(faceTile("a.jpg#1"), findsOneWidget);
    expect(find.text("Anna"), findsOneWidget);
    // … and nothing was asked of `?type=face` for it.
    var crops = [
      for (var request in requests)
        if (request.url.queryParameters["type"] == "face")
          request.url.queryParameters["face"]
    ];
    expect(crops, ["0"]);
  });

  testWidgets("a contributor sees the groups and no control at all",
      (tester) async {
    var requests = <http.Request>[];
    await pumpEditor(
      tester,
      editorClient(
        requests,
        auth: authOf(role: "contribute"),
        album: albumOf(rights: const ["contribute"]),
      ),
    );

    expect(find.text("Anna"), findsOneWidget);
    expect(find.text(testL10n.personsUnknownGroup), findsOneWidget);
    expect(
      find.byKey(const Key("persons-read-only")),
      findsOneWidget,
    );
    expect(find.byKey(const Key("drag-handle")), findsNothing);
    expect(find.byKey(const Key("persons-save")), findsNothing);
    expect(find.byKey(const Key("persons-new-group")), findsNothing);

    // The heading names nobody new either.
    await tester.tap(header("cluster:c1"));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key("persons-chooser")), findsNothing);
  });

  group("leaving", () {
    testWidgets("asks while something is unsaved and goes silently otherwise",
        (tester) async {
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(
          requests,
          auth: authOf(),
          album: albumOf(),
          post: (request) => json(albumOf()),
        ),
      );

      // Clean: Cancel simply leaves.
      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(const Key("persons-cancel")));
        await tester.pumpAndSettle();
      });
      expect(find.byType(PersonsContent), findsNothing);

      await openMenu(tester);
      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(const Key("persons")));
        await tester.pumpAndSettle();
      });
      await dragFaceTo(tester, "a.jpg#0", header("person:p-anna"));

      // Dirty: Cancel asks.
      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(const Key("persons-cancel")));
        await tester.pumpAndSettle();
      });
      expect(find.byKey(const Key("persons-discard-dialog")), findsOneWidget);
      await tester.tap(find.byKey(const Key("persons-keep-editing")));
      await tester.pumpAndSettle();
      expect(find.byType(PersonsContent), findsOneWidget);
    });

    testWidgets("asks through the router's guard on the way back",
        (tester) async {
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(
          requests,
          auth: authOf(),
          album: albumOf(),
          post: (request) => json(albumOf()),
        ),
      );
      await dragFaceTo(tester, "a.jpg#0", header("person:p-anna"));

      // What the browser's back button and the system's do.
      await withFakeImageHttp(() async {
        await tester
            .binding
            .handlePopRoute()
            .then((_) => tester.pumpAndSettle());
      });

      expect(find.byKey(const Key("persons-leave-dialog")), findsOneWidget);
      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(const Key("persons-stay")));
        await tester.pumpAndSettle();
      });
      expect(find.byType(PersonsContent), findsOneWidget);
    });
  });

  testWidgets("a refused Save says what the server said and keeps the buffer",
      (tester) async {
    var requests = <http.Request>[];
    await pumpEditor(
      tester,
      editorClient(
        requests,
        auth: authOf(),
        album: albumOf(),
        post: (request) => http.Response(
          '["ErrorInfo", {"message": "Only editors may name faces here."}]',
          403,
          headers: {"content-type": "application/json; charset=utf-8"},
        ),
      ),
    );

    await dragFaceTo(tester, "a.jpg#0", header("person:p-anna"));
    await saveEditor(tester);

    expect(find.text("Only editors may name faces here."), findsOneWidget);
    // The buffer stands: the face is still under Anna and Save would send it
    // again.
    var state = tester.state<PersonsContentState>(find.byType(PersonsContent));
    expect(state.dirty, isTrue);
    expect(state.delta().length, 1);
  });
}
