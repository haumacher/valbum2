/// The rules of `page_app_bar.dart` (issues #305, #308, #309), kept by reading
/// the source as `action_guard_scan_test.dart` keeps its own:
///
///  * every page's app bar is a [PageAppBar] — the one place where the way up
///    is made the leading control, the title starts at the left and a page of
///    photographs gets its black bar. A plain `AppBar(` is allowed only in the
///    modal tools named in [appBarExceptions], each with its reason;
///  * the back arrow is drawn by [WayBackButton] alone, which [PageAppBar]
///    refuses among its actions — so no page can put the way up at the right
///    again (#308). The one other arrow is named in [arrowExceptions];
///  * the status indicator and the bell are put into a bar by
///    [topLevelStatus] alone, which the top-level page asks for (#309,
///    reversing the "on every page" of #305);
///  * there is no home button anywhere (#309).
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/news.dart';
import 'package:valbum_ui/page_app_bar.dart';
import 'package:valbum_ui/status.dart';

import 'action_guard_scan_test.dart' show blankComments, nameOf, viewFiles;
import 'util/l10n.dart';

/// The files that may build a plain [AppBar], and why.
const Map<String, String> appBarExceptions = {
  "page_app_bar.dart": "the one place a page's app bar is built",
  "crop_editor.dart": "a modal tool over one photo: its bar holds the way out "
      "and Save, nothing else",
  "device_code_scanner_plugin.dart": "the camera scanning a code: a modal "
      "tool left by its own back button",
  "photo_picker_view.dart": "a modal picker answering its caller: it is left "
      "by choosing or cancelling",
};

/// The files that may draw the back arrow other than through
/// [WayBackButton], and why.
const Map<String, String> arrowExceptions = {
  "page_app_bar.dart": "the WayBackButton itself",
  "image_view.dart": "the viewer has no bar: its way back is a white overlay "
      "button at the top left over the picture, like its other controls",
};

/// The files that may place the status indicator or the bell, and why.
const Map<String, String> statusExceptions = {
  "page_app_bar.dart": "topLevelStatus, the one place deciding where they "
      "stand",
};

/// The lines of [source] matching [pattern] outside comments.
List<int> linesMatching(String source, RegExp pattern) {
  var code = blankComments(source);
  return [
    for (var match in pattern.allMatches(code))
      "\n".allMatches(code.substring(0, match.start)).length + 1,
  ];
}

/// The places of [source] building a plain app bar, as line numbers.
List<int> plainAppBars(String source) =>
    linesMatching(source, RegExp(r"(?<![A-Za-z_])(Sliver)?AppBar\s*\("));

/// The places of [source] drawing the back arrow.
List<int> backArrows(String source) =>
    linesMatching(source, RegExp(r"\bIcons\.arrow_back\b"));

/// The places of [source] constructing the indicator or the bell — not
/// declaring them (`const StatusIndicator({super.key})`).
List<int> statusPlacements(String source) => linesMatching(
    source, RegExp(r"\b(StatusIndicator|NewsBellButton)\s*\((?!\s*\{)"));

/// The places of [source] drawing a home button.
List<int> homeButtons(String source) =>
    linesMatching(source, RegExp(r"\bIcons\.home\b"));

/// The `file:line` of every view file outside [exceptions] where [scan]
/// finds something.
List<String> offendersOf(
  List<int> Function(String source) scan, {
  Map<String, String> exceptions = const {},
}) =>
    [
      for (var file in viewFiles())
        if (!exceptions.containsKey(nameOf(file)))
          for (var line in scan(file.readAsStringSync())) "${file.path}:$line",
    ];

void main() {
  test('every page builds its app bar as a PageAppBar', () {
    expect(offendersOf(plainAppBars, exceptions: appBarExceptions), isEmpty,
        reason: "build a page's app bar with PageAppBar (page_app_bar.dart), "
            "or name a modal tool in appBarExceptions with its reason");
  });

  test('the back arrow is a WayBackButton, the leading control (#308)', () {
    expect(offendersOf(backArrows, exceptions: arrowExceptions), isEmpty,
        reason: "the way up is a WayBackButton in the leading place of a "
            "PageAppBar, which refuses it among the actions");
  });

  test('the indicator and the bell stand on the top-level page alone (#309)',
      () {
    expect(offendersOf(statusPlacements, exceptions: statusExceptions), isEmpty,
        reason: "put the status indicator and the bell into a bar through "
            "topLevelStatus (page_app_bar.dart)");
  });

  test('there is no home button anywhere (#309)', () {
    expect(offendersOf(homeButtons), isEmpty,
        reason: "the start page is reached by going up");
  });

  test('the scans find what they look for and pass a comment', () {
    expect(plainAppBars("var bar = AppBar(title: x);"), [1]);
    expect(plainAppBars("// AppBar(\nvar bar = PageAppBar();"), isEmpty);
    expect(plainAppBars("SliverAppBar(pinned: true)"), [1]);
    expect(backArrows("x\nIcon(Icons.arrow_back)"), [2]);
    expect(backArrows("Icon(Icons.arrow_back_ios)"), isEmpty);
    expect(backArrows("// Icons.arrow_back"), isEmpty);
    expect(statusPlacements("const StatusIndicator(),"), [1]);
    expect(statusPlacements("NewsBellButton(key: k)"), [1]);
    expect(statusPlacements("const StatusIndicator({super.key});"), isEmpty);
    expect(homeButtons("icon: const Icon(Icons.home),"), [1]);
    expect(homeButtons("Icons.home_outlined"), isEmpty);
  });

  Future<void> pump(WidgetTester tester, PreferredSizeWidget bar) =>
      tester.pumpWidget(MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: Scaffold(appBar: bar),
      ));

  testWidgets('a PageAppBar adds neither the indicator nor the bell',
      (tester) async {
    await pump(tester, const PageAppBar(actions: [Icon(Icons.search)]));
    var bar = tester.widget<AppBar>(find.byType(AppBar));
    expect(bar.actions, [isA<Icon>()]);
    expect(find.byType(StatusIndicator), findsNothing);
    expect(find.byType(NewsBellButton), findsNothing);
    expect(bar.centerTitle, isFalse);
  });

  testWidgets('a PageAppBar refuses the way up among its actions',
      (tester) async {
    await pump(
      tester,
      PageAppBar(actions: [WayBackButton(onPressed: () {})]),
    );
    expect(tester.takeException(), isA<AssertionError>());
  });

  test('only the top-level page is given the indicator and the bell', () {
    expect(topLevelStatus(topLevel: false), isEmpty);
    expect(topLevelStatus(topLevel: true),
        [isA<StatusIndicator>(), isA<NewsBellButton>()]);
  });
}
