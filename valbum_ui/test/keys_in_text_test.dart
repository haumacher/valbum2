/// A key typed into a text field is the field's (issue #306): no shortcut of
/// a page takes it, and a printable shortcut is bound to the character a key
/// produces, never to the US key at its place — a German `ß` arrives as
/// [LogicalKeyboardKey.minus] and zoomed the album out instead of reaching
/// the search field.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/key_guard.dart';
import 'package:valbum_ui/main.dart';

import 'search_view_test.dart' show Space, chooseAnne, pumpRoute, settle;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

int minRating(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent)).minRating;

/// Whether the primary focus is the one of a text field.
bool inField() =>
    FocusManager.instance.primaryFocus?.context
        ?.findAncestorWidgetOfExactType<EditableText>() !=
    null;

void main() {
  group('the search field of the search view', () {
    Future<void> focusField(WidgetTester tester) async {
      var space = Space();
      await pumpRoute(tester, space, const SearchRoute([]));
      await chooseAnne(tester);
      expect(find.byType(AlbumContent), findsOneWidget,
          reason: "The answer is shown by the album grid below the field.");
      await tester.tap(find.byKey(const Key("search-text")));
      await settle(tester);
      expect(inField(), isTrue);
    }

    for (var (name, key, character) in [
      ("ß", LogicalKeyboardKey.minus, "ß"),
      ("-", LogicalKeyboardKey.minus, "-"),
      ("+", LogicalKeyboardKey.equal, "+"),
      ("numpad -", LogicalKeyboardKey.numpadSubtract, "-"),
      ("ö", LogicalKeyboardKey.semicolon, "ö"),
    ]) {
      testWidgets('receives "$name" and the zoom stays', (tester) async {
        await focusField(tester);
        var before = minRating(tester);

        var handled = await tester.sendKeyDownEvent(key, character: character);
        await tester.sendKeyUpEvent(key);
        await settle(tester);

        expect(handled, isFalse,
            reason: "A key handled by a page never reaches the text input.");
        expect(minRating(tester), before);
      });
    }

    testWidgets('keeps the arrows and Escape for the field', (tester) async {
      await focusField(tester);
      for (var key in [
        LogicalKeyboardKey.arrowLeft,
        LogicalKeyboardKey.arrowRight,
      ]) {
        await tester.sendKeyEvent(key);
      }
      await settle(tester);
      expect(inField(), isTrue);
    });
  });

  group('the album outside a text field', () {
    Future<void> pumpAlbum(WidgetTester tester) async {
      await tester.pumpWidget(
          VAlbumApp(client: clientReturning(fixture("album-ratings.json"))));
      await tester.pumpAndSettle();
    }

    testWidgets('zooms by "+" and "-", typed or on the numpad', (tester) async {
      await withFakeImageHttp(() async {
        await pumpAlbum(tester);
        var start = minRating(tester);

        await tester.sendKeyEvent(LogicalKeyboardKey.minus, character: "-");
        await tester.pumpAndSettle();
        expect(minRating(tester), start + 1);

        // `+` on a US layout is Shift and the key of `=`; on a German one
        // a key of its own, which Flutter calls the US key at its place.
        await tester.sendKeyEvent(LogicalKeyboardKey.bracketRight,
            character: "+");
        await tester.pumpAndSettle();
        expect(minRating(tester), start);

        await tester.sendKeyEvent(LogicalKeyboardKey.numpadSubtract);
        await tester.pumpAndSettle();
        expect(minRating(tester), start + 1);

        await tester.sendKeyEvent(LogicalKeyboardKey.numpadAdd);
        await tester.pumpAndSettle();
        expect(minRating(tester), start);
      });
    });

    testWidgets('does not zoom out by a German "ß"', (tester) async {
      await withFakeImageHttp(() async {
        await pumpAlbum(tester);
        var start = minRating(tester);

        var handled = await tester.sendKeyDownEvent(LogicalKeyboardKey.minus,
            character: "ß");
        await tester.sendKeyUpEvent(LogicalKeyboardKey.minus);
        await tester.pumpAndSettle();

        expect(handled, isFalse);
        expect(minRating(tester), start);
      });
    });
  });

  group('PageShortcuts', () {
    Future<List<String>> pump(WidgetTester tester) async {
      var fired = <String>[];
      await tester.pumpWidget(MaterialApp(
        home: PageShortcuts(
          bindings: {
            const SingleActivator(LogicalKeyboardKey.escape): () =>
                fired.add("escape"),
            const SingleActivator(LogicalKeyboardKey.arrowLeft): () =>
                fired.add("left"),
            const SingleActivator(LogicalKeyboardKey.enter): () =>
                fired.add("enter"),
            const CharacterActivator("e"): () => fired.add("e"),
          },
          child: const Focus(
            autofocus: true,
            child: Material(
              child: TextField(key: Key("field")),
            ),
          ),
        ),
      ));
      await tester.pump();
      return fired;
    }

    final keys = [
      LogicalKeyboardKey.escape,
      LogicalKeyboardKey.arrowLeft,
      LogicalKeyboardKey.enter,
      LogicalKeyboardKey.keyE,
    ];

    testWidgets('act outside a text field', (tester) async {
      var fired = await pump(tester);
      for (var key in keys) {
        expect(await tester.sendKeyEvent(key), isTrue, reason: "$key");
      }
      expect(fired, ["escape", "left", "enter", "e"]);
    });

    testWidgets('leave every key to a focused text field', (tester) async {
      var fired = await pump(tester);
      await tester.tap(find.byKey(const Key("field")));
      await tester.pump();
      expect(inField(), isTrue);
      for (var key in keys) {
        await tester.sendKeyEvent(key);
      }
      await tester.pump();
      expect(fired, isEmpty);
      expect(inField(), isTrue);
    });
  });
}
