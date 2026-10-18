/// The rule of `page_app_bar.dart` (issue #305), kept by reading the source as
/// `action_guard_scan_test.dart` keeps its own: every page's app bar is a
/// [PageAppBar], which ends with the status indicator — "in the app bar, on
/// every page" — so that a new page cannot forget it. A plain `AppBar(` is
/// allowed only in the modal tools named in [appBarExceptions], each with
/// its reason.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/page_app_bar.dart';
import 'package:valbum_ui/status.dart';

import 'action_guard_scan_test.dart' show blankComments, nameOf, viewFiles;
import 'util/l10n.dart';

/// The files that may build a plain [AppBar], and why.
const Map<String, String> appBarExceptions = {
  "page_app_bar.dart": "the one place a page's app bar is built",
  "status.dart": "the status page itself: the indicator would open what is "
      "already shown",
  "crop_editor.dart": "a modal tool over one photo: its bar holds the way out "
      "and Save, nothing else",
  "device_code_scanner_plugin.dart": "the camera scanning a code: a modal "
      "tool left by its own back button",
  "photo_picker_view.dart": "a modal picker answering its caller: it is left "
      "by choosing or cancelling",
  "select_mode.dart": "the bar of the select mode: the way out, the count "
      "and what is done with the selection, nothing else (issue #209)",
  "load_failure.dart": "the page of a load that failed: it offers the way to "
      "the start page, the settings and the retry",
};

/// The places of [source] building a plain app bar, as line numbers.
List<int> plainAppBars(String source) {
  var code = blankComments(source);
  var plain = RegExp(r"(?<![A-Za-z_])(Sliver)?AppBar\s*\(");
  return [
    for (var match in plain.allMatches(code))
      "\n".allMatches(code.substring(0, match.start)).length + 1,
  ];
}

void main() {
  test('every page builds its app bar as a PageAppBar', () {
    var offenders = <String>[];
    for (var file in viewFiles()) {
      var name = nameOf(file);
      if (appBarExceptions.containsKey(name)) {
        continue;
      }
      for (var line in plainAppBars(file.readAsStringSync())) {
        offenders.add("${file.path}:$line");
      }
    }
    expect(offenders, isEmpty,
        reason: "build a page's app bar with PageAppBar (page_app_bar.dart), "
            "or name a modal tool in appBarExceptions with its reason");
  });

  test('the scan finds a plain app bar and passes a comment', () {
    expect(plainAppBars("var bar = AppBar(title: x);"), [1]);
    expect(plainAppBars("// AppBar(\nvar bar = PageAppBar();"), isEmpty);
    expect(plainAppBars("SliverAppBar(pinned: true)"), [1]);
  });

  testWidgets('a PageAppBar ends with the indicator, once', (tester) async {
    Future<void> pump(List<Widget> actions) => tester.pumpWidget(MaterialApp(
          localizationsDelegates: testLocalizationsDelegates,
          supportedLocales: testSupportedLocales,
          home: Scaffold(appBar: PageAppBar(actions: actions)),
        ));
    await pump(const [Icon(Icons.search)]);
    var bar = tester.widget<AppBar>(find.byType(AppBar));
    expect(bar.actions!.last, isA<StatusIndicator>());
    expect(bar.actions!.whereType<StatusIndicator>(), hasLength(1));

    // A trailing menu stays the last thing of the row.
    var menu = PopupMenuButton<int>(itemBuilder: (_) => const []);
    expect(PageAppBar.withIndicator([const Icon(Icons.search), menu]),
        [isA<Icon>(), isA<StatusIndicator>(), menu]);

    // Placed by the page itself: not added a second time.
    await pump(const [StatusIndicator(), Icon(Icons.search)]);
    bar = tester.widget<AppBar>(find.byType(AppBar));
    expect(bar.actions!.whereType<StatusIndicator>(), hasLength(1));
    expect(bar.actions!.first, isA<StatusIndicator>());
  });
}
