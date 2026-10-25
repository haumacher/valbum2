/// The way into the search of the page shown (issue #309): the search button
/// of a folder's bar, or the entry of an album's three-dots menu.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// The key of both: the button of a folder, the menu entry of an album.
const Key openSearchKey = Key("open-search");

/// Taps the search of the page shown, opening the album's menu first where
/// the search is an entry of it. The caller settles what the tap starts.
Future<void> tapOpenSearch(WidgetTester tester) async {
  if (find.byKey(openSearchKey).evaluate().isEmpty) {
    await tester.tap(find.byIcon(Icons.more_vert).last);
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
  }
  await tester.tap(find.byKey(openSearchKey));
}
