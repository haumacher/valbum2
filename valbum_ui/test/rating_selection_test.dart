/// Tests of a tile's rating buttons on a selection (issue #224): a button of
/// a tile that is part of a selection of several acts on the whole selection,
/// any other on that tile alone; Very good, Good and Poor at once, Trash on
/// several only after "Move n photos to the trash?". In the album's edit mode
/// into the buffer, in the inbox in one write, taken back as a whole on a
/// refusal; the inbox's Delete asks the same for more than one photograph.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/inbox_view.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'album_camera_test.dart'
    show album, albumState, ctrlTapTile, image, pumpEditMode;
import 'inbox_view_test.dart'
    show
        inboxJson,
        inboxState,
        inboxTree,
        pumpInbox,
        reveal,
        selectedNames,
        tapHeading,
        tapTileOf;
import 'move_test.dart' show json, tile;
import 'util/l10n.dart';

/// The button of the given rating on the tile of [name].
Finder button(String name, String rating) => find.descendant(
    of: tile(name), matching: find.byKey(Key("rating-$rating")));

/// Moves a mouse over the tile of [name], so that it shows its tools without
/// being selected.
Future<void> hover(WidgetTester tester, String name) async {
  var mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
  await mouse.addPointer(location: Offset.zero);
  addTearDown(mouse.removePointer);
  await mouse.moveTo(tester.getRect(tile(name)).center);
  await tester.pumpAndSettle();
}

Future<void> press(WidgetTester tester, String name, String rating) async {
  await tester.tap(button(name, rating));
  await tester.pumpAndSettle();
}

/// The ratings of the parts of the album as the album page holds it, a group
/// member by member.
Map<String, int> albumRatings(WidgetTester tester) => {
      for (var part in album(tester).parts)
        if (part is ImagePart)
          part.name: part.rating
        else if (part is ImageGroup)
          for (var member in part.images) member.name: member.rating,
    };

/// Three photographs and a group of two, represented by its first.
String fourTiles() => AlbumInfo(
      path: "",
      title: "Ratings",
      parts: [
        image("a.jpg", 1000),
        image("b.jpg", 2000),
        image("c.jpg", 3000),
        ImageGroup(
          images: [image("g1.jpg", 4000), image("g2.jpg", 5000)],
          representative: 0,
        ),
      ],
    ).toString();

/// The ratings of the sidecar a PUT carried.
Map<String, int> sentRatings(http.Request put) => {
      for (var part in (Resource.fromString(put.body) as AlbumInfo).parts)
        if (part is ImagePart) part.name: part.rating,
    };

/// The ratings the inbox screen holds.
Map<String, int> inboxRatings(WidgetTester tester) => {
      for (var part in inboxState(tester).album.parts)
        if (part is ImagePart) part.name: part.rating,
    };

void main() {
  group('which photos a rating button acts on', () {
    test('the selection where the tile is part of one of several', () {
      var a = Object(), b = Object(), c = Object();
      expect(ratingTargets(a, [a, b]), [a, b]);
      expect(ratingTargets(c, [a, b]), [c]);
      expect(ratingTargets(a, [a]), [a]);
      expect(ratingTargets(a, <Object>[]), [a]);
    });
  });

  group('in the album edit mode', () {
    testWidgets('a tile outside the selection rates itself alone',
        (tester) async {
      await pumpEditMode(tester, fourTiles(), select: "a.jpg");
      await ctrlTapTile(tester, "b.jpg");
      await hover(tester, "c.jpg");

      await press(tester, "c.jpg", "good");

      expect(albumRatings(tester),
          {"a.jpg": 0, "b.jpg": 0, "c.jpg": 1, "g1.jpg": 0, "g2.jpg": 0});
      expect(albumState(tester).selection, hasLength(2));
      expect(albumState(tester).session.dirty, isTrue);
    });

    testWidgets('a tile selected alone rates itself, trash without a question',
        (tester) async {
      await pumpEditMode(tester, fourTiles(), select: "a.jpg");

      await press(tester, "a.jpg", "trash");

      expect(find.byKey(const Key("trash-confirm-dialog")), findsNothing);
      expect(albumRatings(tester)["a.jpg"], -2);
      expect(albumRatings(tester)["b.jpg"], 0);
      // Trashed, it is gone from the grid and from the selection.
      expect(albumState(tester).selection, isEmpty);
    });

    for (var (name, value) in [("very-good", 2), ("good", 1), ("poor", -1)]) {
      testWidgets('$name on a selected tile rates the whole selection at once',
          (tester) async {
        await pumpEditMode(tester, fourTiles(), select: "a.jpg");
        await ctrlTapTile(tester, "b.jpg");
        await ctrlTapTile(tester, "g1.jpg");

        await press(tester, "b.jpg", name);

        expect(find.byKey(const Key("trash-confirm-dialog")), findsNothing);
        // A group is rated by its representative, as its tile always was.
        expect(albumRatings(tester), {
          "a.jpg": value,
          "b.jpg": value,
          "c.jpg": 0,
          "g1.jpg": value,
          "g2.jpg": 0,
        });
        expect(albumState(tester).selection, hasLength(3));
        expect(albumState(tester).session.dirty, isTrue);
      });
    }

    testWidgets('trash on a selection asks first; Cancel changes nothing',
        (tester) async {
      await pumpEditMode(tester, fourTiles(), select: "a.jpg");
      await ctrlTapTile(tester, "b.jpg");
      await ctrlTapTile(tester, "c.jpg");

      await press(tester, "a.jpg", "trash");

      expect(find.byKey(const Key("trash-confirm-dialog")), findsOneWidget);
      expect(find.text(testL10n.trashSeveralQuestion(3)), findsOneWidget);
      expect(find.text("Move 3 photos to the trash?"), findsOneWidget);
      expect(find.text(testL10n.moveToTrash), findsOneWidget);
      await tester.tap(find.byKey(const Key("trash-confirm-cancel")));
      await tester.pumpAndSettle();

      expect(albumRatings(tester).values, everyElement(0));
      expect(albumState(tester).selection, hasLength(3));
      expect(albumState(tester).session.dirty, isFalse);
    });

    testWidgets(
        'trash on a selection, confirmed, rates every one -2 into the '
        'buffer and empties the selection', (tester) async {
      await pumpEditMode(tester, fourTiles(), select: "a.jpg");
      await ctrlTapTile(tester, "c.jpg");

      await press(tester, "c.jpg", "trash");
      expect(find.text(testL10n.trashSeveralQuestion(2)), findsOneWidget);
      await tester.tap(find.byKey(const Key("trash-confirm")));
      await tester.pumpAndSettle();

      expect(albumRatings(tester),
          {"a.jpg": -2, "b.jpg": 0, "c.jpg": -2, "g1.jpg": 0, "g2.jpg": 0});
      expect(albumState(tester).selection, isEmpty);
      expect(tile("a.jpg"), findsNothing);
      expect(tile("c.jpg"), findsNothing);
      expect(albumState(tester).session.dirty, isTrue);
    });
  });

  group('in the inbox', () {
    /// The inbox answering every write as accepted, recording the requests.
    http.Response accepting(http.Request request) =>
        request.method == "PUT" ? json("") : inboxTree(request);

    List<http.Request> puts(List<http.Request> requests) =>
        requests.where((r) => r.method == "PUT").toList();

    testWidgets('a tile outside the selection rates itself alone',
        (tester) async {
      var requests = <http.Request>[];
      await pumpInbox(tester, accepting, requests: requests);
      await tapHeading(tester, "inbox-day-2026-03-01");
      await reveal(tester, tile("c.jpg"));
      await hover(tester, "c.jpg");

      await press(tester, "c.jpg", "poor");

      expect(sentRatings(puts(requests).single),
          {"d.jpg": 0, "c.jpg": -1, "a.jpg": 0, "b.jpg": 0});
      expect(selectedNames(tester), ["a.jpg", "b.jpg"]);
    });

    testWidgets('a tile selected alone: trash in one write, no question',
        (tester) async {
      var requests = <http.Request>[];
      await pumpInbox(tester, accepting, requests: requests);
      await tapTileOf(tester, "a.jpg");

      await press(tester, "a.jpg", "trash");

      expect(find.byKey(const Key("trash-confirm-dialog")), findsNothing);
      expect(sentRatings(puts(requests).single)["a.jpg"], -2);
      expect(selectedNames(tester), isEmpty);
    });

    for (var (name, value) in [("very-good", 2), ("good", 1), ("poor", -1)]) {
      testWidgets('$name on a selected tile rates the selection in one write',
          (tester) async {
        var requests = <http.Request>[];
        await pumpInbox(tester, accepting, requests: requests);
        await tapHeading(tester, "inbox-day-2026-03-01");

        await press(tester, "b.jpg", name);

        expect(find.byKey(const Key("trash-confirm-dialog")), findsNothing);
        expect(puts(requests), hasLength(1), reason: "one write for all");
        expect(sentRatings(puts(requests).single),
            {"d.jpg": 0, "c.jpg": 0, "a.jpg": value, "b.jpg": value});
        expect(selectedNames(tester), ["a.jpg", "b.jpg"]);
      });
    }

    testWidgets('trash on a selection asks first; Cancel writes nothing',
        (tester) async {
      var requests = <http.Request>[];
      await pumpInbox(tester, accepting, requests: requests);
      await tapHeading(tester, "inbox-day-2026-03-01");

      await press(tester, "a.jpg", "trash");
      expect(find.text(testL10n.trashSeveralQuestion(2)), findsOneWidget);
      await tester.tap(find.byKey(const Key("trash-confirm-cancel")));
      await tester.pumpAndSettle();

      expect(puts(requests), isEmpty);
      expect(inboxRatings(tester).values, everyElement(0));
      expect(selectedNames(tester), ["a.jpg", "b.jpg"]);
    });

    testWidgets(
        'trash on a selection, confirmed, is one write, and the photographs '
        'leave the screen and the selection', (tester) async {
      var requests = <http.Request>[];
      await pumpInbox(tester, accepting, requests: requests);
      await tapHeading(tester, "inbox-day-2026-03-01");

      await press(tester, "a.jpg", "trash");
      await tester.tap(find.byKey(const Key("trash-confirm")));
      await tester.pumpAndSettle();

      expect(sentRatings(puts(requests).single),
          {"d.jpg": 0, "c.jpg": 0, "a.jpg": -2, "b.jpg": -2});
      expect(tile("a.jpg"), findsNothing);
      expect(tile("b.jpg"), findsNothing);
      expect(selectedNames(tester), isEmpty);
    });

    testWidgets('asks in the language of the device', (tester) async {
      tester.platformDispatcher.localesTestValue = const [Locale("de")];
      addTearDown(tester.platformDispatcher.clearLocalesTestValue);
      await pumpInbox(tester, accepting);
      await tapHeading(tester, "inbox-day-2026-03-01");

      await press(tester, "a.jpg", "trash");

      expect(
          find.text("2 Fotos in den Papierkorb verschieben?"), findsOneWidget);
      expect(find.text("In den Papierkorb verschieben"), findsOneWidget);
      expect(find.text("Abbrechen"), findsOneWidget);
    });

    testWidgets('a refused write takes the whole selection back',
        (tester) async {
      await pumpInbox(
        tester,
        (request) => request.method == "PUT"
            ? http.Response(
                '["ErrorInfo", {"message": "Not your inbox."}]',
                403,
                headers: {"content-type": "application/json"},
              )
            : inboxTree(request),
      );
      await tapHeading(tester, "inbox-day-2026-03-01");

      await press(tester, "a.jpg", "trash");
      await tester.tap(find.byKey(const Key("trash-confirm")));
      await tester.pumpAndSettle();

      expect(find.text("Not your inbox."), findsOneWidget);
      expect(inboxRatings(tester).values, everyElement(0));
      expect(tile("a.jpg"), findsOneWidget);
      expect(tile("b.jpg"), findsOneWidget);
      expect(selectedNames(tester), ["a.jpg", "b.jpg"]);
    });

    testWidgets('trash on a selection is refused offline before any question',
        (tester) async {
      var requests = <http.Request>[];
      var state = OfflineState();
      await pumpInbox(tester, inboxTree,
          requests: requests, offlineState: state);
      state.goneOffline(null);
      await tester.pumpAndSettle();
      await tapHeading(tester, "inbox-day-2026-03-01");

      await press(tester, "a.jpg", "trash");

      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
      expect(find.byKey(const Key("trash-confirm-dialog")), findsNothing);
      expect(requests.where((r) => r.method != "GET"), isEmpty);
    });

    group('the delete of an editor', () {
      Future<void> secondaryClick(WidgetTester tester, String name) async {
        await reveal(tester, tile(name));
        var box = tester.getRect(tile(name));
        await tester.tapAt(Offset(box.left + 8, box.center.dy),
            buttons: kSecondaryMouseButton, kind: PointerDeviceKind.mouse);
        await tester.pumpAndSettle();
      }

      testWidgets(
          'from the context menu asks for several; Cancel writes '
          'nothing', (tester) async {
        var requests = <http.Request>[];
        await pumpInbox(tester, accepting, requests: requests);
        await tapHeading(tester, "inbox-day-2026-03-01");
        await secondaryClick(tester, "a.jpg");
        await tester.tap(find.byKey(const Key("inbox-context-delete")));
        await tester.pumpAndSettle();

        expect(find.text(testL10n.trashSeveralQuestion(2)), findsOneWidget);
        await tester.tap(find.byKey(const Key("trash-confirm-cancel")));
        await tester.pumpAndSettle();
        expect(puts(requests), isEmpty);
        expect(selectedNames(tester), ["a.jpg", "b.jpg"]);
      });

      testWidgets('of one photograph asks nothing', (tester) async {
        var requests = <http.Request>[];
        await pumpInbox(tester, accepting, requests: requests);
        await secondaryClick(tester, "c.jpg");
        await tester.tap(find.byKey(const Key("inbox-context-delete")));
        await tester.pumpAndSettle();

        expect(find.byKey(const Key("trash-confirm-dialog")), findsNothing);
        expect(sentRatings(puts(requests).single)["c.jpg"], -2);
      });

      testWidgets('from the menu, Escape on the question writes nothing',
          (tester) async {
        var requests = <http.Request>[];
        await pumpInbox(tester, accepting, requests: requests);
        await tapHeading(tester, "inbox-day-2026-03-01");
        await tester.tap(find.byIcon(Icons.more_vert).last);
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key("delete-selection")));
        await tester.pumpAndSettle();
        expect(find.byKey(const Key("trash-confirm-dialog")), findsOneWidget);
        await tester.sendKeyEvent(LogicalKeyboardKey.escape);
        await tester.pumpAndSettle();

        expect(puts(requests), isEmpty);
        expect(selectedNames(tester), ["a.jpg", "b.jpg"]);
      });
    });

    testWidgets('a contributor\'s delete keeps its own question',
        (tester) async {
      await pumpInbox(
        tester,
        (request) => inboxTree(request,
            album: inboxJson(rights: const ["view", "download", "contribute"])),
      );
      await tapHeading(tester, "inbox-day-2026-03-01");
      await tester.tap(find.byIcon(Icons.more_vert).last);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("delete-selection")));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("inbox-delete-dialog")), findsOneWidget);
      expect(find.byKey(const Key("trash-confirm-dialog")), findsNothing);
      expect(find.text(inboxDeleteExplanation(testL10n)), findsOneWidget);
    });
  });
}
