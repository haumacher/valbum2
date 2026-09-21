/// The guard of the localization (issue #108).
///
/// Two things this asserts, and both of them are about what is *not* there:
///
///  * every language has exactly the keys the English source has, with the
///    same placeholders in each — a translation that dropped or renamed a
///    placeholder compiles to a different method signature and is a bug the
///    generator only finds where the key is used;
///  * the files already converted carry no user-facing literal any more.
///
/// [convertedFiles] is the list a later slice extends: a file goes in when
/// every word it shows comes from `AppLocalizations`. What a file may still
/// hold in spite of that is named in [allowedLiterals] — and each entry says
/// why, because an allowlist nobody argues with is a list that grows.
library;

import 'dart:convert';
import 'dart:io';

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/locales.dart';

/// The ARB the app is written in; everything else is generated from it.
const String templateArb = "lib/l10n/app_en.arb";

/// The languages generated beside it.
const List<String> generatedArbs = ["lib/l10n/app_de.arb"];

/// The files whose every user-facing word comes from `AppLocalizations`.
///
/// Slice 1 of issue #108: the server settings screen and everything it is
/// made of. A later slice adds its own files here, and the test below then
/// holds them to the same rule.
const List<String> convertedFiles = [
  "lib/caller.dart",
  "lib/device_code_scanner.dart",
  "lib/first_screen.dart",
  "lib/manage_view.dart",
  "lib/settings.dart",
  "lib/sign_in_form.dart",
  "lib/urls.dart",
];

/// The literals a converted file may still hold, and why.
const Map<String, String> allowedLiterals = {
  // The server's own sentence, matched against what arrives so that the app
  // can show the name field; never shown instead of it, see issue #89.
  "This code signs in a user who has no name yet. Choose the name you want "
      "to be known by in this space.": "protocol constant, matched not shown",
  // `toString()` of a value class: a debugger reads these, never a user.
  r"CallerPermission(${}, ${}, ${})": "toString()",
  r"CallerInfo(${}, ${}, ${})": "toString()",
  r"SessionUrl(${} ${} at ${})": "toString()",
  // The diagnostics log of issue #58, pasted into a bug report in English.
  r"connection test: data URL ${}": "diagnostics log",
  r"connection test: root ${} - ${}": "diagnostics log",
  r"connection test: auth ${}": "diagnostics log",
  r"connection test: no host in '${}' (${})": "diagnostics log",
  r"connection test: no host in '${}'": "diagnostics log",
  // An assertion message, read by a developer.
  "No ServerSettingsScope found in the widget tree.":
      "assertion, never shown to a user",
  // Thrown, never shown: see `allowedThrownLiterals`.
  "Not an absolute server URL (expected e.g. 'http://host:8080/valbum/').":
      "thrown, see allowedThrownLiterals",
  // What the connection test appends to its own, already localized, message.
  r"${} (${})": "punctuation around a localized message",
  // The countdown of a device code: digits and a colon.
  r"${}:${}": "a duration, not a sentence",
};

/// The message of an exception a converted file throws, and why it may be
/// English.
///
/// A thrown sentence is the one user-facing literal a `Text(…)` scan never
/// sees: it travels as `error.message` and lands in a refusal on the screen
/// unless somebody stops it. So every one of them is named here, and the rule
/// is that it is named **because nothing shows it** — the catch turns it into
/// a localized sentence of the app's own. A message that is shown belongs in
/// the ARB, not in this map.
const Map<String, String> allowedThrownLiterals = {
  "Not an absolute server URL (expected e.g. 'http://host:8080/valbum/').":
      "FormatException of dataUrlOf/serverLocationOf; serverUrlError answers "
          "`serverUrlInvalid` instead of showing it, and the other callers "
          "match on the exception rather than reading it",
};

/// The exception constructors whose first argument is a message.
const List<String> messageCarryingErrors = [
  "FormatException",
  "Exception",
  "ArgumentError",
  "StateError",
  "UnsupportedError",
  "UnimplementedError",
];

/// The keys of an ARB file, in order.
List<String> keysOf(Map<String, dynamic> arb) => [
      for (var key in arb.keys)
        if (!key.startsWith("@")) key
    ];

/// The placeholders an ICU message names.
Set<String> placeholdersOf(String message) => {
      for (var match in RegExp(r"\{(\w+)").allMatches(message)) match.group(1)!,
    };

/// [source] without its comments.
String withoutComments(String source) {
  var out = StringBuffer();
  var inBlock = false;
  for (var line in source.split("\n")) {
    var trimmed = line.trimLeft();
    if (inBlock) {
      if (trimmed.contains("*/")) {
        inBlock = false;
      }
      continue;
    }
    if (trimmed.startsWith("/*")) {
      inBlock = !trimmed.contains("*/");
      continue;
    }
    if (trimmed.startsWith("///") || trimmed.startsWith("//")) {
      continue;
    }
    out.writeln(line);
  }
  return out.toString();
}

/// Every string literal of [source], with adjacent ones joined.
///
/// Dart writes a long sentence as several literals in a row, so they are
/// joined again here — an allowlist that had to name half a sentence would be
/// unreadable. An interpolation (`${…}`) is skipped over rather than cut
/// through, so a literal carrying one comes out in one piece.
List<String> stringLiterals(String source) {
  var text = withoutComments(source);
  var literals = <String>[];
  var pending = StringBuffer();
  var pendingOpen = false;
  var i = 0;

  void flush() {
    if (pendingOpen) {
      literals.add(pending.toString());
      pending.clear();
      pendingOpen = false;
    }
  }

  while (i < text.length) {
    var char = text[i];
    if (char == '"' || char == "'") {
      var quote = char;
      var triple = text.startsWith(quote * 3, i);
      i += triple ? 3 : 1;
      var value = StringBuffer();
      while (i < text.length) {
        if (triple && text.startsWith(quote * 3, i)) {
          i += 3;
          break;
        }
        if (!triple && text[i] == quote) {
          i++;
          break;
        }
        if (text[i] == "\\") {
          value.write(text[i]);
          if (i + 1 < text.length) {
            value.write(text[i + 1]);
          }
          i += 2;
          continue;
        }
        if (text[i] == r"$" &&
            i + 1 < text.length &&
            RegExp("[A-Za-z_]").hasMatch(text[i + 1])) {
          // A bare `$name` interpolation, spelled like a braced one.
          i++;
          while (i < text.length && RegExp("[A-Za-z0-9_]").hasMatch(text[i])) {
            i++;
          }
          value.write(r"${}");
          continue;
        }
        if (text.startsWith(r"${", i)) {
          var depth = 0;
          while (i < text.length) {
            if (text[i] == "{") {
              depth++;
            } else if (text[i] == "}") {
              depth--;
              if (depth == 0) {
                i++;
                break;
              }
            }
            i++;
          }
          value.write(r"${}");
          continue;
        }
        value.write(text[i]);
        i++;
      }
      pending.write(value);
      pendingOpen = true;
      // Another literal right behind this one belongs to the same sentence.
      var ahead = i;
      while (
          ahead < text.length && (text[ahead] == " " || text[ahead] == "\n")) {
        ahead++;
      }
      if (ahead >= text.length || (text[ahead] != '"' && text[ahead] != "'")) {
        flush();
      } else {
        i = ahead;
      }
      continue;
    }
    i++;
  }
  flush();
  return literals;
}

/// The literals of [source] that read like a sentence somebody is shown.
///
/// A sentence is at least two words and holds a letter; a key, a role name, a
/// URL and a format pattern are one word and are not what this hunts for.
List<String> sentenceLiterals(String source) => [
      for (var literal in stringLiterals(source))
        if (literal.contains(RegExp("[A-Za-z]")) &&
            literal.trim().contains(" "))
          literal,
    ];

/// The literals [source] hands to an exception constructor.
List<String> thrownLiterals(String source) {
  var text = withoutComments(source);
  var found = <String>[];
  for (var name in messageCarryingErrors) {
    var pattern = RegExp(
      "$name\\s*\\(\\s*((?:\"(?:[^\"\\\\]|\\\\.)*\"\\s*)+)",
    );
    for (var match in pattern.allMatches(text)) {
      var literals = stringLiterals(match.group(1)!);
      if (literals.isNotEmpty) {
        found.add(literals.join());
      }
    }
  }
  return found;
}

void main() {
  group("the ARB files", () {
    late Map<String, dynamic> template;

    setUp(() {
      template = jsonDecode(File(templateArb).readAsStringSync())
          as Map<String, dynamic>;
    });

    test("have the keys of the English source, and only those", () {
      for (var path in generatedArbs) {
        var other =
            jsonDecode(File(path).readAsStringSync()) as Map<String, dynamic>;
        expect(
          keysOf(other).toSet(),
          keysOf(template).toSet(),
          reason: "$path does not carry the keys of $templateArb; "
              "run `gradle translateArb` after changing the source",
        );
      }
    });

    test("name the same placeholders in every language", () {
      for (var path in generatedArbs) {
        var other =
            jsonDecode(File(path).readAsStringSync()) as Map<String, dynamic>;
        for (var key in keysOf(template)) {
          expect(
            placeholdersOf(other[key] as String),
            placeholdersOf(template[key] as String),
            reason: "$path, '$key': the translation renamed or dropped a "
                "placeholder, which the generator turns into a different "
                "method signature",
          );
        }
      }
    });
  });

  group("the language shown", () {
    test("falls back to English, not to the first of the alphabet", () {
      // What `gen-l10n` writes today, and what it would write with a third
      // language: the alphabet puts German first either way.
      const two = [Locale("de"), Locale("en")];
      const three = [Locale("de"), Locale("en"), Locale("fr")];
      for (var supported in [two, three]) {
        expect(
          resolveAppLocale(const [Locale("nl")], supported),
          const Locale("en"),
          reason: "an unsupported language must read the source language",
        );
        expect(resolveAppLocale(null, supported), const Locale("en"));
        expect(resolveAppLocale(const [], supported), const Locale("en"));
      }
    });

    test("is the one asked for where the app carries it", () {
      const supported = [Locale("de"), Locale("en")];
      expect(resolveAppLocale(const [Locale("de")], supported),
          const Locale("de"));
      // A region the app has no strings of its own for still reads its
      // language.
      expect(resolveAppLocale(const [Locale("de", "AT")], supported),
          const Locale("de"));
      // The device's own order of preference decides.
      expect(
        resolveAppLocale(const [Locale("nl"), Locale("de")], supported),
        const Locale("de"),
      );
    });

    test("never answers a locale the app has no strings for", () {
      const supported = [Locale("de")];
      expect(resolveAppLocale(const [Locale("fr")], supported),
          const Locale("de"));
    });
  });

  group("the converted files", () {
    test("hold no user-facing literal any more", () {
      var offenders = <String>[];
      for (var path in convertedFiles) {
        var source = File(path).readAsStringSync();
        for (var literal in sentenceLiterals(source)) {
          if (allowedLiterals.containsKey(literal)) {
            continue;
          }
          offenders.add("$path: \"$literal\"");
        }
      }
      expect(
        offenders,
        isEmpty,
        reason: "these read like sentences a user sees; put them into "
            "$templateArb and read them through AppLocalizations, or say in "
            "`allowedLiterals` why they are not shown",
      );
    });

    test("throw no sentence that could reach a reader unlocalized", () {
      var offenders = <String>[];
      for (var path in convertedFiles) {
        for (var literal in thrownLiterals(File(path).readAsStringSync())) {
          if (allowedThrownLiterals.containsKey(literal)) {
            continue;
          }
          offenders.add("$path: \"$literal\"");
        }
      }
      expect(
        offenders,
        isEmpty,
        reason: "a thrown message reaches the screen as `error.message` "
            "wherever somebody shows it, and it is English whatever the app "
            "speaks; answer a localized sentence at the catch and name the "
            "thrown one in `allowedThrownLiterals` with that reason",
      );
    });

    test("are all there", () {
      for (var path in convertedFiles) {
        expect(File(path).existsSync(), isTrue, reason: "$path is gone");
      }
    });
  });
}
