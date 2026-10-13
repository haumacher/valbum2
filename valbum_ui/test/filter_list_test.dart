/// The one "filter + list + keyboard highlight" of every chooser, see issue
/// #261: the field narrows, the arrows walk what is left and stop at either
/// end, every change of the filter leaves the highlight nowhere, `Enter`
/// picks, `Escape` clears a filled field and closes the dialog from an empty
/// one, and the field takes the focus on a desktop but not on a phone.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/filter_list.dart';

import 'util/l10n.dart';

const List<String> fruit = ["Apple", "Apricot", "Banana", "Cherry"];

/// A chooser of [fruit] built from the shared pieces alone.
class FruitChooser extends StatefulWidget {
  /// What `Enter` was pressed on, `null` included for nothing highlighted.
  final List<String?> entered;

  const FruitChooser({super.key, required this.entered});

  @override
  State<FruitChooser> createState() => _FruitChooserState();
}

class _FruitChooserState extends State<FruitChooser> {
  final TextEditingController _filter = TextEditingController();

  late final PickHighlight<String> _highlight =
      PickHighlight<String>(idOf: (entry) => entry)
        ..addListener(() => setState(() {}));

  @override
  void dispose() {
    _highlight.dispose();
    _filter.dispose();
    super.dispose();
  }

  void _enter(String? highlighted) {
    widget.entered.add(highlighted);
    if (highlighted != null) {
      Navigator.of(context).pop(highlighted);
    }
  }

  @override
  Widget build(BuildContext context) {
    var needle = _filter.text.toLowerCase();
    _highlight.shown = [
      for (var entry in fruit)
        if (entry.toLowerCase().contains(needle)) entry,
    ];
    return PickKeys<String>(
      highlight: _highlight,
      onEnter: _enter,
      child: AlertDialog(
        key: const Key("fruit"),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            FilterField(
              fieldKey: const Key("fruit-filter"),
              controller: _filter,
              label: "Fruit",
              highlight: _highlight,
              onChanged: (_) => setState(() {}),
            ),
            for (var entry in _highlight.shown)
              _highlight.row(
                entry,
                ListTile(
                  key: Key("fruit-$entry"),
                  selected: _highlight.isHighlighted(entry),
                  title: Text(entry),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class Opened {
  Object? answer = const _Pending();
  bool get closed => answer is! _Pending;
}

class _Pending {
  const _Pending();
}

/// Opens the chooser and, unless [focus] says otherwise, puts the cursor in
/// its field, as the reader does who is about to type.
Future<Opened> pumpChooser(WidgetTester tester, List<String?> entered,
    {bool focus = true}) async {
  var opened = Opened();
  await tester.pumpWidget(localizedApp(Builder(
    builder: (context) => Scaffold(
      body: TextButton(
        child: const Text("open"),
        onPressed: () async {
          opened.answer = await showDialog<String>(
            context: context,
            builder: (context) => FruitChooser(entered: entered),
          );
        },
      ),
    ),
  )));
  await tester.tap(find.text("open"));
  await tester.pumpAndSettle();
  if (focus) {
    await tester.tap(find.byKey(const Key("fruit-filter")));
    await tester.pumpAndSettle();
  }
  return opened;
}

/// The entries highlighted, in the order they stand.
List<String> highlighted(WidgetTester tester) => [
      for (var entry in fruit)
        if (find.byKey(Key("fruit-$entry")).evaluate().isNotEmpty &&
            tester.widget<ListTile>(find.byKey(Key("fruit-$entry"))).selected)
          entry,
    ];

Future<void> press(WidgetTester tester, LogicalKeyboardKey key,
    {int times = 1}) async {
  for (var n = 0; n < times; n++) {
    await tester.sendKeyEvent(key);
  }
  await tester.pumpAndSettle();
}

bool fieldFocused(WidgetTester tester) => tester
    .widget<EditableText>(find.descendant(
        of: find.byKey(const Key("fruit-filter")),
        matching: find.byType(EditableText)))
    .focusNode
    .hasFocus;

void main() {
  testWidgets('starts on nothing, and the arrows stop at either end',
      (tester) async {
    await pumpChooser(tester, []);
    expect(highlighted(tester), isEmpty,
        reason: "a stray Enter must pick nothing");

    await press(tester, LogicalKeyboardKey.arrowDown);
    expect(highlighted(tester), ["Apple"]);
    await press(tester, LogicalKeyboardKey.arrowUp, times: 3);
    expect(highlighted(tester), ["Apple"], reason: "no wrap at the top");

    await press(tester, LogicalKeyboardKey.arrowDown, times: 10);
    expect(highlighted(tester), ["Cherry"], reason: "no wrap at the bottom");
  });

  testWidgets('the first step up from nothing lands on the last entry',
      (tester) async {
    await pumpChooser(tester, []);
    await press(tester, LogicalKeyboardKey.arrowUp);
    expect(highlighted(tester), ["Cherry"]);
  });

  testWidgets('every change of the filter leaves the highlight nowhere',
      (tester) async {
    await pumpChooser(tester, []);
    await press(tester, LogicalKeyboardKey.arrowDown, times: 2);
    expect(highlighted(tester), ["Apricot"]);

    await tester.enterText(find.byKey(const Key("fruit-filter")), "ap");
    await tester.pumpAndSettle();
    expect(highlighted(tester), isEmpty,
        reason: "Apricot is still shown, but the list changed under it");

    await press(tester, LogicalKeyboardKey.arrowDown, times: 2);
    expect(highlighted(tester), ["Apricot"],
        reason: "the arrows walk what the filter left");
  });

  testWidgets('Enter picks what the highlight stands on', (tester) async {
    var entered = <String?>[];
    var opened = await pumpChooser(tester, entered);

    await press(tester, LogicalKeyboardKey.enter);
    expect(entered, [null], reason: "nothing highlighted: nothing picked");
    expect(opened.closed, isFalse);

    await tester.enterText(find.byKey(const Key("fruit-filter")), "an");
    await tester.pumpAndSettle();
    await press(tester, LogicalKeyboardKey.arrowDown);
    await press(tester, LogicalKeyboardKey.enter);
    expect(opened.answer, "Banana");
  });

  testWidgets('Escape clears a filled filter, and closes from an empty one',
      (tester) async {
    var opened = await pumpChooser(tester, []);
    await tester.enterText(find.byKey(const Key("fruit-filter")), "ch");
    await tester.pumpAndSettle();
    await press(tester, LogicalKeyboardKey.arrowDown);
    expect(highlighted(tester), ["Cherry"]);

    await press(tester, LogicalKeyboardKey.escape);
    expect(opened.closed, isFalse);
    expect(find.byKey(const Key("fruit-Apple")), findsOneWidget,
        reason: "the whole list again");
    expect(highlighted(tester), isEmpty);

    await press(tester, LogicalKeyboardKey.escape);
    expect(opened.closed, isTrue);
    expect(opened.answer, isNull);
  });

  testWidgets('the field takes the focus on a desktop', (tester) async {
    await pumpChooser(tester, [], focus: false);
    expect(fieldFocused(tester), isTrue);
  },
      variant: const TargetPlatformVariant({
        TargetPlatform.linux,
        TargetPlatform.macOS,
        TargetPlatform.windows,
      }));

  testWidgets('a phone keeps its keyboard down', (tester) async {
    await pumpChooser(tester, [], focus: false);
    expect(fieldFocused(tester), isFalse);
  },
      variant: const TargetPlatformVariant({
        TargetPlatform.android,
        TargetPlatform.iOS,
      }));
}
