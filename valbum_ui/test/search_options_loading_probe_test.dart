/// Probe: a chooser closed while the options are still on their way stays
/// closed when they arrive, and the next one opens at once over them.
library;

import 'dart:async';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;

import 'search_options_loading_test.dart' show SlowSpace, pumpSearch, pumpFew;
import 'search_view_test.dart' show json, options, settle;

void main() {
  testWidgets('a chooser closed before the options arrive does not come back',
      (tester) async {
    var space = SlowSpace();
    var arrival = Completer<http.Response>();
    space.optionsAnswer = () => arrival.future;
    await pumpSearch(tester, space);

    await tester.tap(find.byKey(const Key("search-add-persons")));
    await pumpFew(tester);
    expect(find.byKey(const Key("search-options-loading")), findsOneWidget);
    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await pumpFew(tester);
    expect(find.byKey(const Key("search-options-loading")), findsNothing);

    arrival.complete(json(options));
    await settle(tester);
    expect(find.byKey(const Key("search-choose-dialog")), findsNothing,
        reason: "closed is closed");
    expect(tester.takeException(), isNull);

    await tester.tap(find.byKey(const Key("search-add-persons")));
    await settle(tester);
    expect(find.byKey(const Key("search-choice-p1")), findsOneWidget);
    expect(space.optionsAsked, 1);
  });
}
