/// How the person chooser lists and is driven, see issue #150.
///
/// The people already in the album stand in a section of their own at the top,
/// everybody else behind them, each section in alphabetical order of what is
/// written on the line (the display name of issue #146). The cursor stands in
/// the search field when the dialog opens, the arrows walk the entries that
/// are left after the filter, and `Enter` picks the one they stand on — or,
/// where the typed name matches nobody, creates that person.
///
/// The register is awaited (issue #291): while it is on its way the chooser
/// waits in its list and offers no new person, and a register that failed
/// says why, with a Retry — never "Nobody yet" and a duplicate person.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/loadable.dart';
import 'package:valbum_ui/persons_view.dart';
import 'package:valbum_ui/resource.dart';

import 'util/l10n.dart';

/// The register the tests choose from.
List<Person> register() => [
      Person(id: "p-zoe", name: "Zoe"),
      Person(id: "p-anna", name: "anna"),
      Person(id: "p-bob", name: "Bob", nickname: "Oma"),
    ];

/// Pumps the chooser and answers what it popped, once it is closed.
Future<Person?> pumpChooser(
  WidgetTester tester, {
  Set<String> inAlbum = const {},
  List<Person>? people,
  Loadable<List<Person>>? source,
  Future<Person?> Function(String name)? onCreate,
}) async {
  Person? chosen;
  await tester.pumpWidget(
    MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: Builder(
        builder: (context) => Scaffold(
          body: TextButton(
            child: const Text("open"),
            onPressed: () async {
              chosen = await showDialog<Person>(
                context: context,
                builder: (context) => PersonChooser(
                  people: source ?? Loadable.value(people ?? register()),
                  inAlbum: inAlbum,
                  onCreate: onCreate,
                ),
              );
            },
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text("open"));
  if (source != null && !source.hasValue) {
    // Not settled: a progress indicator spins while the register is awaited.
    await pumpFew(tester);
  } else {
    await tester.pumpAndSettle();
  }
  return chosen;
}

/// Lets a few frames pass without waiting for a progress indicator to stop.
Future<void> pumpFew(WidgetTester tester) async {
  for (var n = 0; n < 5; n++) {
    await tester.pump(const Duration(milliseconds: 50));
  }
}

/// The "New person" entry, as the chooser draws it.
TextButton newPerson(WidgetTester tester) =>
    tester.widget<TextButton>(find.byKey(const Key("persons-new-person")));

/// The ids of the entries, in the order they stand on the screen.
List<String> orderOf(WidgetTester tester) {
  const String prefix = "persons-pick-";
  var ids = <String>[];
  for (var element in find.byType(ListTile).evaluate()) {
    var key = element.widget.key;
    if (key is ValueKey<String> && key.value.startsWith(prefix)) {
      ids.add(key.value.substring(prefix.length));
    }
  }
  return ids;
}

/// Whether the entry of the given person is the one the keyboard stands on.
bool highlighted(WidgetTester tester, String id) =>
    tester.widget<ListTile>(find.byKey(Key("persons-pick-$id"))).selected;

void main() {
  testWidgets('lists the people of the album first, each section in order',
      (tester) async {
    await pumpChooser(tester, inAlbum: const {"p-zoe"});

    expect(find.text(testL10n.personsChooserInAlbum), findsOneWidget);
    expect(find.text(testL10n.personsChooserAll), findsOneWidget);
    // Zoe is in the album; "anna" and "Oma" (Bob's nickname, issue #146) come
    // behind her, in the order of what is written on the line, ignoring case.
    expect(orderOf(tester), ["p-zoe", "p-anna", "p-bob"]);
  });

  testWidgets('shows no heading where a section is empty', (tester) async {
    await pumpChooser(tester);

    expect(find.text(testL10n.personsChooserInAlbum), findsNothing);
    expect(find.text(testL10n.personsChooserAll), findsNothing);
    expect(orderOf(tester), ["p-anna", "p-bob", "p-zoe"]);
  });

  testWidgets('has the cursor in the search field when it opens',
      (tester) async {
    await pumpChooser(tester);

    // Nothing was tapped: typing goes into the field because it has the focus.
    tester.testTextInput.enterText("an");
    await tester.pumpAndSettle();
    expect(orderOf(tester), ["p-anna"]);
  });

  testWidgets('filters both sections by either name', (tester) async {
    await pumpChooser(tester, inAlbum: const {"p-zoe"});

    await tester.enterText(find.byKey(const Key("persons-search")), "o");
    await tester.pumpAndSettle();
    // "Zoe" and "Oma"/"Bob" carry an `o`, "anna" does not.
    expect(orderOf(tester), ["p-zoe", "p-bob"]);
  });

  testWidgets('walks the remaining entries and picks with Enter',
      (tester) async {
    await pumpChooser(tester, inAlbum: const {"p-zoe"});

    // Nothing is highlighted before an arrow is pressed.
    expect(highlighted(tester, "p-zoe"), isFalse);

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.pumpAndSettle();
    expect(highlighted(tester, "p-zoe"), isTrue);

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.pumpAndSettle();
    expect(highlighted(tester, "p-anna"), isTrue);
    expect(highlighted(tester, "p-zoe"), isFalse);

    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key("persons-chooser")), findsNothing);
  });

  testWidgets('does not wrap at either end', (tester) async {
    await pumpChooser(tester);

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);
    await tester.pumpAndSettle();
    expect(highlighted(tester, "p-anna"), isTrue);

    for (var n = 0; n < 5; n++) {
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    }
    await tester.pumpAndSettle();
    expect(highlighted(tester, "p-zoe"), isTrue);
  });

  testWidgets('forgets the highlight when the filter changes', (tester) async {
    await pumpChooser(tester);

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.pumpAndSettle();
    expect(highlighted(tester, "p-anna"), isTrue);

    await tester.enterText(find.byKey(const Key("persons-search")), "o");
    await tester.pumpAndSettle();
    expect(highlighted(tester, "p-bob"), isFalse);
    expect(highlighted(tester, "p-zoe"), isFalse);
  });

  testWidgets('Enter on a name nobody carries creates that person',
      (tester) async {
    var created = <String>[];
    await pumpChooser(
      tester,
      onCreate: (name) async {
        created.add(name);
        return Person(id: "p-new", name: name);
      },
    );

    await tester.enterText(
      find.byKey(const Key("persons-search")),
      "New Person",
    );
    await tester.pumpAndSettle();
    expect(orderOf(tester), isEmpty);

    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pumpAndSettle();
    // The name dialog of "New person…", prefilled with what was typed.
    expect(find.byType(PersonNameDialog), findsOneWidget);
    await tester.tap(find.byKey(const Key("persons-name-ok")));
    await tester.pumpAndSettle();
    expect(created, ["New Person"]);
  });

  testWidgets('a tap still picks, as it always did', (tester) async {
    await pumpChooser(tester, inAlbum: const {"p-zoe"});
    await tester.tap(find.byKey(const Key("persons-pick-p-bob")));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key("persons-chooser")), findsNothing);
  });

  testWidgets('waits for a register on its way and offers no new person',
      (tester) async {
    var arrival = Completer<List<Person>>();
    var created = <String>[];
    await pumpChooser(
      tester,
      source: Loadable(() => arrival.future),
      onCreate: (name) async {
        created.add(name);
        return Person(id: "p-new", name: name);
      },
    );

    expect(find.byKey(const Key("persons-register-loading")), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    expect(find.byKey(const Key("persons-nobody")), findsNothing,
        reason: "Nobody is said to be missing of a register not seen.");
    expect(newPerson(tester).onPressed, isNull,
        reason: "No new person over a register that may know them.");

    // Typed meanwhile, a name the register will turn out to know: Enter
    // creates nobody.
    await tester.enterText(find.byKey(const Key("persons-search")), "Zo");
    await pumpFew(tester);
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await pumpFew(tester);
    expect(find.byType(PersonNameDialog), findsNothing);
    expect(created, isEmpty);

    arrival.complete(register());
    await tester.pumpAndSettle();
    expect(find.byKey(const Key("persons-register-loading")), findsNothing);
    expect(orderOf(tester), ["p-zoe"],
        reason: "The click is not lost: the chooser fills, filtered.");
    expect(newPerson(tester).onPressed, isNotNull);
    expect(created, isEmpty);
  });

  testWidgets('says why a register failed, and a retry fills it',
      (tester) async {
    var asked = 0;
    Future<List<Person>> Function() answer = () async =>
        throw const VAlbumException("The register cannot be read.",
            status: 500);
    var created = <String>[];
    await pumpChooser(
      tester,
      source: Loadable(() {
        asked++;
        return answer();
      }),
      onCreate: (name) async {
        created.add(name);
        return Person(id: "p-new", name: name);
      },
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key("persons-register-failed")), findsOneWidget);
    expect(find.textContaining("The register cannot be read."), findsOneWidget,
        reason: "The server's own words.");
    expect(find.byKey(const Key("persons-nobody")), findsNothing);
    expect(newPerson(tester).onPressed, isNull);
    expect(asked, 1);

    await tester.enterText(find.byKey(const Key("persons-search")), "Nobody");
    await tester.pumpAndSettle();
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pumpAndSettle();
    expect(find.byType(PersonNameDialog), findsNothing);

    answer = () async => register();
    await tester.enterText(find.byKey(const Key("persons-search")), "");
    await tester.tap(find.byKey(const Key("persons-register-retry")));
    await tester.pumpAndSettle();
    expect(asked, 2, reason: "The retry asks again.");
    expect(find.byKey(const Key("persons-register-failed")), findsNothing);
    expect(orderOf(tester), ["p-anna", "p-bob", "p-zoe"]);
    expect(newPerson(tester).onPressed, isNotNull);
    expect(created, isEmpty, reason: "Nobody was created on the way.");
  });

  testWidgets('a chooser opened over a failed register asks again',
      (tester) async {
    var asked = 0;
    var failing = true;
    var source = Loadable<List<Person>>(() async {
      asked++;
      if (failing) {
        throw const VAlbumException("Down.", status: 503);
      }
      return register();
    });
    await source.load().then((_) {}, onError: (Object _) {});
    expect(source.error, isNotNull);

    failing = false;
    await pumpChooser(tester, source: source);
    await tester.pumpAndSettle();
    expect(asked, 2);
    expect(orderOf(tester), ["p-anna", "p-bob", "p-zoe"]);
  });

  testWidgets('says "Nobody yet" only of a register that arrived empty',
      (tester) async {
    await pumpChooser(tester, people: const []);
    expect(find.byKey(const Key("persons-nobody")), findsOneWidget);
    expect(find.byKey(const Key("persons-register-loading")), findsNothing);
  });

  testWidgets('leaves out who is excluded (the merge)', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: Scaffold(
          body: PersonChooser(
            people: Loadable.value(register()),
            excluded: const {"p-bob"},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(orderOf(tester), ["p-anna", "p-zoe"]);
    expect(find.byKey(const Key("persons-new-person")), findsNothing);
  });
}
