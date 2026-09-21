/// Review probes of the localization mechanism (issue #108), composed with
/// what the slice did not look at: the words a helper composes outside a
/// widget in German, and a locale the app does not carry.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'util/l10n.dart';

void main() {
  final de = l10nOf(const Locale("de"));
  final en = l10nOf(const Locale("en"));

  test('every refusal of a server address speaks German in German', () {
    for (var input in ["", "http://host/valbum/s/abcdef/", "::nonsense"]) {
      var german = serverUrlError(de, input);
      var english = serverUrlError(en, input);
      expect(german, isNotNull, reason: "'$input' must be refused");
      expect(german, isNot(english),
          reason: "'$input' is refused with an English sentence in German: '$german'");
    }
  });

  testWidgets('a locale the app does not carry falls back to English',
      (tester) async {
    await tester.pumpWidget(localizedApp(
      Builder(
        builder: (context) => Text(AppLocalizations.of(context)!.appTitle),
      ),
      locale: const Locale("fr"),
    ));
    await tester.pumpAndSettle();
    expect(find.text(en.appTitle), findsOneWidget);
  });

  test('German differs from English wherever English says something', () {
    // A German file that merely copied the English one would pass the key and
    // placeholder checks of the guard; the translation itself has to show.
    expect(de.appTitle, isNot(en.appTitle));
    expect(de.serverUrlEmpty, isNot(en.serverUrlEmpty));
  });
}
