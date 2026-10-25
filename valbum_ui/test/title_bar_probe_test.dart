/// Probe of the title bar (issues #308, #309, #310): what is opened from a
/// black bar of an app in the light system theme — its menu and the dialog
/// behind a menu entry — must be legible, every text against the surface it
/// is painted on; and the status indicator stands over an album at the start
/// page and over no album below it.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/news.dart';

import 'title_bar_test.dart' show pumpAt;
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The WCAG contrast ratio of two colours.
double contrast(Color a, Color b) {
  var la = a.computeLuminance(), lb = b.computeLuminance();
  var hi = la > lb ? la : lb, lo = la > lb ? lb : la;
  return (hi + 0.05) / (lo + 0.05);
}

/// The opaque colour of the nearest [Material] above [element].
Color surfaceOf(Element element) {
  Color? found;
  element.visitAncestorElements((ancestor) {
    var widget = ancestor.widget;
    if (widget is Material &&
        widget.type != MaterialType.transparency &&
        widget.color != null &&
        widget.color!.a > 0.9) {
      found = widget.color;
      return false;
    }
    return true;
  });
  return found ?? Colors.white;
}

/// Checks every text below [root] against the surface under it.
void expectLegible(WidgetTester tester, Finder root, String where) {
  var texts = find.descendant(of: root, matching: find.byType(RichText));
  expect(texts, findsWidgets, reason: "$where shows text");
  for (var element in texts.evaluate()) {
    var text = (element.widget as RichText).text;
    var color = text.style?.color;
    if (color == null || color.a < 0.3) {
      continue;
    }
    var surface = surfaceOf(element);
    expect(contrast(color, surface), greaterThan(3.0),
        reason: "$where: '${text.toPlainText()}' in $color on $surface");
  }
}

void main() {
  testWidgets(
      'the menu of a black bar and the dialog behind it are legible '
      'in a light system theme', (tester) async {
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.light;
    addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
    await pumpAt(tester, fixture("listing_sub.json"), path: const ["sub"]);

    await tester.tap(find.byIcon(Icons.more_vert).last);
    await tester.pumpAndSettle();
    var menu = find.byType(PopupMenuItem<void Function(BuildContext)>);
    expect(menu, findsWidgets);
    expectLegible(
        tester,
        menu.first.evaluate().isEmpty
            ? menu
            : find
                .ancestor(of: menu.first, matching: find.byType(Material))
                .first,
        "the menu");

    var create = find.text(testCreateAlbum(tester));
    expect(create, findsOneWidget, reason: "an editor may create an album");
    await tester.tap(create);
    await tester.pumpAndSettle();
    var dialog = find.byType(Dialog);
    expect(dialog, findsOneWidget);
    expectLegible(tester, dialog, "the dialog");
  });

  testWidgets(
      'an album at the start page carries the indicator, one below '
      'it does not', (tester) async {
    await pumpAt(tester, fixture("album.json"));
    expect(find.byType(StatusIndicator), findsOneWidget,
        reason: "the start page is an album here");
    expect(find.byType(NewsBellButton), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
    await pumpAt(tester, fixture("album.json"), path: const ["Album"]);
    expect(find.byType(StatusIndicator), findsNothing);
    expect(find.byType(NewsBellButton), findsNothing);
  });
}

/// The label of the "create album" entry in the language of the app.
String testCreateAlbum(WidgetTester tester) =>
    AppLocalizations.of(tester.element(find.byType(Scaffold).first))!
        .createAlbum;
