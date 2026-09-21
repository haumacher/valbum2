/// What a widget test needs to pump a localized widget (issue #108).
///
/// Every screen of the app reads its words through `AppLocalizations.of`, so
/// the [MaterialApp] a test pumps has to carry the delegates the real app
/// carries. These are those delegates, plus the two ways a test names a
/// language: [localizedApp] pumps a widget in a given locale, and [l10nOf]
/// answers the very strings the app would show in it — so an assertion keeps
/// matching the English text through the `en` locale and a German smoke test
/// asks for the German one.
library;

import 'package:flutter/material.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:valbum_ui/l10n/app_localizations.dart';
import 'package:valbum_ui/locales.dart';

export 'package:valbum_ui/l10n/app_localizations.dart' show AppLocalizations;

/// The delegates every pumped [MaterialApp] of a test carries.
const List<LocalizationsDelegate<dynamic>> testLocalizationsDelegates =
    AppLocalizations.localizationsDelegates;

/// The locales the app supports, as a test pumps them.
const List<Locale> testSupportedLocales = AppLocalizations.supportedLocales;

/// English, unless a test says otherwise: what the existing assertions match.
const Locale defaultTestLocale = Locale("en");

/// The strings of [locale], as the app would show them.
///
/// Used by a test that asserts on a text the app composes, and by every call
/// of a function that now takes an [AppLocalizations] (`serverUrlError`,
/// `lastDeviceWarning`, …).
AppLocalizations l10nOf([Locale locale = defaultTestLocale]) {
  // What `flutter_localizations` does for a running app, done here for a plain
  // unit test: a helper that composes a line with a `DateFormat` of the
  // locale — `cameraRollLine`, the inbox headings — needs the date symbols of
  // that locale, and outside a widget test nobody has loaded them. The local
  // implementation fills them in synchronously, so the very next `DateFormat`
  // finds them.
  initializeDateFormatting(locale.toLanguageTag());
  return lookupAppLocalizations(locale);
}

/// The English strings, the language the test suite reads in.
AppLocalizations get testL10n => l10nOf();

/// A [MaterialApp] carrying the localizations, showing [home] in [locale].
///
/// [resolveAppLocale] is the app's own rule, so a test asking for a language
/// the app does not carry gets what the app would give a phone asking for it
/// — English, not whatever stands first in the alphabet.
MaterialApp localizedApp(
  Widget home, {
  Locale locale = defaultTestLocale,
  GlobalKey<NavigatorState>? navigatorKey,
  List<NavigatorObserver> navigatorObservers = const [],
}) =>
    MaterialApp(
      locale: locale,
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      localeListResolutionCallback: resolveAppLocale,
      navigatorKey: navigatorKey,
      navigatorObservers: navigatorObservers,
      home: home,
    );
