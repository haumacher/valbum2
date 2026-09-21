/// Which language the app speaks (issue #108).
///
/// The languages are the ones `lib/l10n/` carries, and `gen-l10n` lists them
/// alphabetically in `AppLocalizations.supportedLocales` — `[de, en]` today.
/// Flutter's own resolution answers the **first** supported locale when a
/// device asks for a language the app does not carry, so a French or a Dutch
/// phone would be shown German: an accident of the alphabet, not a decision.
///
/// English is the language the strings are written in and every other one is
/// derived from it (see `app_en.arb`), so English is what an unknown language
/// falls back to. [resolveAppLocale] says exactly that, and it says it without
/// naming the languages: `translateArb` adds a third one by adding an ARB
/// file, and nothing here has to change.
library;

import 'package:flutter/widgets.dart';

/// The language the app is written in, and the fallback of every other.
const Locale sourceLocale = Locale("en");

/// The locale to show, given what the device asks for and what the app has.
///
/// The device's list is walked in its own order of preference, and each entry
/// is matched twice: first exactly (language, script and country), then by
/// language alone — a `de_AT` phone reads the German of the app rather than
/// falling through to English. Only when nothing matches at all does
/// [sourceLocale] answer, and only where even that is missing does the first
/// supported locale stand, so this can never answer a locale the app has no
/// strings for.
///
/// Written as a plain function, not as a closure in the app: the widget tests
/// resolve with the very same rule, see `test/util/l10n.dart`.
Locale resolveAppLocale(
  List<Locale>? preferred,
  Iterable<Locale> supported,
) {
  for (var wanted in preferred ?? const <Locale>[]) {
    for (var candidate in supported) {
      if (candidate.languageCode == wanted.languageCode &&
          candidate.scriptCode == wanted.scriptCode &&
          candidate.countryCode == wanted.countryCode) {
        return candidate;
      }
    }
    for (var candidate in supported) {
      if (candidate.languageCode == wanted.languageCode) {
        return candidate;
      }
    }
  }
  for (var candidate in supported) {
    if (candidate.languageCode == sourceLocale.languageCode) {
      return candidate;
    }
  }
  return supported.first;
}
