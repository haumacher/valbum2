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

import 'dart:ui' show PlatformDispatcher;

import 'package:flutter/widgets.dart';

import 'l10n/app_localizations.dart';

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

/// The words of the app, for the layers that have no widget tree to ask.
///
/// Everything a screen shows is read through `AppLocalizations.of(context)`;
/// the transport, the offline cache and the background run have no context —
/// and a sentence they compose is shown all the same (a refused request
/// travels as `VAlbumException.message`, a background report is persisted and
/// read back by the settings screen). They read the strings here instead: the
/// very same [resolveAppLocale] rule the app applies, asked of the platform's
/// own preference list rather than of a widget. So the transport speaks the
/// language the app speaks, and no layer keeps an English fallback of its own.
///
/// Which platform is asked matters, see [appPlatformDispatcher]: there are two
/// dispatchers, and only one of them is the one the app renders from.
AppLocalizations get platformMessages => lookupAppLocalizations(
      resolveAppLocale(
        appPlatformDispatcher.locales,
        AppLocalizations.supportedLocales,
      ),
    );

/// The platform the app reads its language from.
///
/// There are two [PlatformDispatcher]s to be had, and in a running app they
/// are one and the same object, so the difference is invisible:
///
///  * [WidgetsBinding.instance.platformDispatcher] — what the binding hands
///    the framework, and therefore what [WidgetsApp] resolves its locale from
///    and what every widget of the app is drawn in;
///  * [PlatformDispatcher.instance] — the engine's own singleton.
///
/// Under `flutter_test` they are **not** the same: the binding's dispatcher is
/// a `TestPlatformDispatcher` wrapping the engine's, and that wrapper is what
/// a test writes to (`tester.platformDispatcher.localesTestValue`, which is
/// how a widget test puts the app into another language). Reading the engine's
/// singleton here would mean an app showing German widgets beside an English
/// transport sentence — and no test could ever check the transport's language.
///
/// So the binding decides wherever there is one, which is every screen, every
/// widget test and every plain unit test of this suite. The engine's singleton
/// answers only where no binding has been initialized at all: the background
/// isolate the platform starts for a camera-roll run (see `background.dart`),
/// which words its persisted report without ever building a widget.
PlatformDispatcher get appPlatformDispatcher {
  try {
    return WidgetsBinding.instance.platformDispatcher;
  } catch (_) {
    // No binding: `WidgetsBinding.instance` asserts in a debug build and
    // fails the null cast in a release one, and both are answered the same
    // way — there is no app here, so the engine is all there is to ask.
    return PlatformDispatcher.instance;
  }
}
