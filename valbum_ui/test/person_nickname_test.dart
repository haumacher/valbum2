/// The nickname a person is shown by on a photograph (issue #146).
///
/// The server keeps two names and splits the one typed field; the app only
/// decides which of the two to show. Where a person is *called* something —
/// the headings of the face editor, the suggestion of issue #127 — that is
/// the nickname; where they are *chosen* or *edited*, both names are said, so
/// that two grandmothers called "Oma" can be told apart and a round trip
/// through the rename dialog keeps what was typed.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/person_names.dart';
import 'package:valbum_ui/resource.dart';

import 'persons_view_test.dart'
    show
        albumOf,
        authOf,
        editorClient,
        faceOf,
        header,
        imageOf,
        json,
        openPersonMenu,
        posted,
        pumpEditor;
import 'util/fake_image_http.dart';
import 'util/l10n.dart';

/// Berta, who is called "Tante Berta" on a photograph.
const String bertaRegister = '{"people": [{"id": "p-berta", '
    '"name": "Berta Müller", "nickname": "Tante Berta"}]}';

/// Two people who are both called "Oma".
const String twoGrandmothers = '{"people": ['
    '{"id": "p-anna", "name": "Anna Schmidt", "nickname": "Oma"}, '
    '{"id": "p-berta", "name": "Berta Müller", "nickname": "Oma"}]}';

/// An album whose one face was confirmed as the given person.
String albumOfPerson(String person) => albumOf(images: [
      imageOf("a.jpg", [
        faceOf(0, cluster: "c1", person: person, state: "CONFIRMED"),
      ]),
    ]);

/// An album showing both grandmothers, one face each.
String albumOfBoth() => albumOf(images: [
      imageOf("a.jpg", [
        faceOf(0, cluster: "c1", person: "p-anna", state: "CONFIRMED"),
      ]),
      imageOf("b.jpg", [
        faceOf(0, cluster: "c2", person: "p-berta", state: "CONFIRMED"),
      ]),
    ]);

void main() {
  group("the helper", () {
    Person person(String name, String nickname) =>
        Person(id: name, name: name, nickname: nickname);

    test("shows the nickname and labels with both", () {
      var berta = person("Berta Müller", "Tante Berta");
      expect(displayName(berta), "Tante Berta");
      expect(fullLabel(berta), "Berta Müller (Tante Berta)");

      var carl = person("Carl Meier", "");
      expect(displayName(carl), "Carl Meier");
      expect(fullLabel(carl), "Carl Meier");

      // What the server sends is what it stored, but a blank is a blank.
      expect(displayName(person("Carl Meier", "   ")), "Carl Meier");
    });

    test("names in full only where a display name is not unique", () {
      var anna = person("Anna Schmidt", "Oma");
      var berta = person("Berta Müller", "Oma");
      var carl = person("Carl Meier", "Opa");

      var labels = disambiguate([anna, berta, carl]);
      expect(labels[anna.id], "Anna Schmidt (Oma)");
      expect(labels[berta.id], "Berta Müller (Oma)");
      expect(labels[carl.id], "Opa");

      // Alone, each of them is simply what they are called.
      expect(disambiguate([anna])[anna.id], "Oma");

      // The case of a nickname says nothing about who somebody is.
      expect(disambiguate([anna, person("Erna Klein", "OMA")])[anna.id],
          "Anna Schmidt (Oma)");
    });
  });

  group("the face editor", () {
    testWidgets("names a person by their nickname", (tester) async {
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(
          requests,
          auth: authOf(),
          album: albumOfPerson("p-berta"),
          people: () => bertaRegister,
        ),
      );

      expect(
        tester
            .widget<Text>(find.byKey(const Key("persons-heading-person:p-berta")))
            .data,
        "Tante Berta",
      );
      expect(find.text("Berta Müller"), findsNothing);
    });

    testWidgets("asks after the nickname too", (tester) async {
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(
          requests,
          auth: authOf(),
          album: albumOf(images: [
            imageOf("a.jpg", [faceOf(0, cluster: "c1", person: "p-berta")]),
          ]),
          people: () => bertaRegister,
        ),
      );

      expect(find.text(testL10n.personsSuggestedHeading("Tante Berta")),
          findsOneWidget);
    });

    testWidgets("names both grandmothers in full", (tester) async {
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(
          requests,
          auth: authOf(),
          album: albumOfBoth(),
          people: () => twoGrandmothers,
        ),
      );

      expect(
        tester
            .widget<Text>(find.byKey(const Key("persons-heading-person:p-anna")))
            .data,
        "Anna Schmidt (Oma)",
      );
      expect(
        tester
            .widget<Text>(find.byKey(const Key("persons-heading-person:p-berta")))
            .data,
        "Berta Müller (Oma)",
      );
    });
  });

  group("the chooser", () {
    testWidgets("says what somebody is called and who they are",
        (tester) async {
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(
          requests,
          auth: authOf(),
          album: albumOf(images: [
            imageOf("a.jpg", [faceOf(0, cluster: "c1")]),
          ]),
          people: () => bertaRegister,
        ),
      );

      await tester.tap(header("cluster:c1"));
      await tester.pumpAndSettle();

      expect(
        find.descendant(
          of: find.byKey(const Key("persons-pick-p-berta")),
          matching: find.text("Tante Berta"),
        ),
        findsOneWidget,
      );
      expect(
        tester
            .widget<Text>(find.byKey(const Key("persons-pick-name-p-berta")))
            .data,
        "Berta Müller",
      );
    });

    testWidgets("finds somebody by either of their names", (tester) async {
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(
          requests,
          auth: authOf(),
          album: albumOf(images: [
            imageOf("a.jpg", [faceOf(0, cluster: "c1")]),
          ]),
          people: () => bertaRegister,
        ),
      );

      await tester.tap(header("cluster:c1"));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key("persons-search")), "tante");
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("persons-pick-p-berta")), findsOneWidget);

      await tester.enterText(find.byKey(const Key("persons-search")), "müller");
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("persons-pick-p-berta")), findsOneWidget);

      await tester.enterText(find.byKey(const Key("persons-search")), "erna");
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("persons-pick-p-berta")), findsNothing);
    });
  });

  group("the rename dialog", () {
    testWidgets("is prefilled with both names and posts what was typed",
        (tester) async {
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(
          requests,
          auth: authOf(),
          album: albumOfPerson("p-berta"),
          people: () => bertaRegister,
          post: (request) => json('{"id": "p-berta", "name": "Berta Schmidt", '
              '"nickname": "Oma Berta"}'),
        ),
      );

      await openPersonMenu(tester, "p-berta");
      await tester.tap(find.byKey(const Key("persons-rename")));
      await tester.pumpAndSettle();

      expect(
        tester.widget<TextField>(find.byKey(const Key("persons-name"))).controller!.text,
        "Berta Müller (Tante Berta)",
      );

      await tester.enterText(
          find.byKey(const Key("persons-name")), "Berta Schmidt (Oma Berta)");
      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(const Key("persons-name-ok")));
        await tester.pumpAndSettle();
      });

      // The typed string goes out whole; splitting it is the server's one job.
      expect(posted(requests, "rename-person"), [
        {"id": "p-berta", "name": "Berta Schmidt (Oma Berta)"}
      ]);
      expect(
        tester
            .widget<Text>(find.byKey(const Key("persons-heading-person:p-berta")))
            .data,
        "Oma Berta",
      );
    });
  });
}
