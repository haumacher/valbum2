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
/// Since slice 3 of issue #108 this is **every hand-written file under
/// `lib/`** — the generated `resource.dart` and the generated `l10n/` are the
/// only ones left out. The app is converted; what a file may still hold in
/// spite of that is named in [allowedLiterals], and each entry says why.
const List<String> convertedFiles = [
  "lib/album_date.dart",
  "lib/album_edit.dart",
  "lib/album_layout.dart",
  "lib/album_model.dart",
  "lib/album_view.dart",
  "lib/app.dart",
  "lib/attribution.dart",
  "lib/background.dart",
  "lib/background_workmanager.dart",
  "lib/cache_refresh.dart",
  "lib/caller.dart",
  "lib/camera_roll.dart",
  "lib/camera_roll_view.dart",
  "lib/client.dart",
  "lib/connectivity.dart",
  "lib/connectivity_plugin.dart",
  "lib/device_code_payload.dart",
  "lib/device_code_scanner.dart",
  "lib/device_code_scanner_plugin.dart",
  "lib/diagnostics.dart",
  "lib/drag_scroll.dart",
  "lib/first_screen.dart",
  "lib/group_view.dart",
  "lib/image_properties.dart",
  "lib/image_transform.dart",
  "lib/image_view.dart",
  "lib/inbox_view.dart",
  "lib/invitation.dart",
  "lib/listing_view.dart",
  "lib/locales.dart",
  "lib/main.dart",
  "lib/manage_view.dart",
  "lib/move_view.dart",
  "lib/name_date.dart",
  "lib/notices.dart",
  "lib/offline.dart",
  "lib/offline_file.dart",
  "lib/oriented_thumbnail.dart",
  "lib/people_registry.dart",
  "lib/person_names.dart",
  "lib/photo_library.dart",
  "lib/photo_library_manager.dart",
  "lib/persons_view.dart",
  "lib/photo_picker_view.dart",
  "lib/platform.dart",
  "lib/platform_io.dart",
  "lib/platform_web.dart",
  "lib/rights.dart",
  "lib/routes.dart",
  "lib/settings.dart",
  "lib/share_session.dart",
  "lib/share_view.dart",
  "lib/sign_in_form.dart",
  "lib/thumbnails.dart",
  "lib/upload_progress.dart",
  "lib/urls.dart",
  "lib/video_view.dart",
  "lib/wakelock.dart",
  "lib/wakelock_plugin.dart",
];

/// The files under `lib/` this test does not hold to the rule, and why.
///
/// Both of them are generated, and both are regenerated on every build: the
/// model from `model.proto`, the localizations from the ARB files.
const List<String> generatedFiles = [
  "lib/resource.dart",
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
  r"UploadProgress(${}, ${}/${}, ${})": "toString()",
  r"RenditionState(${}, ${}, ${})": "toString()",
  r"DeviceCodePayload(${}, ${})": "toString()",
  r"PhotoAlbum(${}, ${}, ${})": "toString()",
  r"PlaneTransform(${}, mirrored: ${})": "toString()",
  // The diagnostics log of issue #58, pasted into a bug report in English.
  r"connection test: data URL ${}": "diagnostics log",
  r"connection test: root ${} - ${}": "diagnostics log",
  r"connection test: auth ${}": "diagnostics log",
  r"connection test: no host in '${}' (${})": "diagnostics log",
  r"connection test: no host in '${}'": "diagnostics log",
  // The headings of that log itself, see `diagnostics.dart`: the whole file
  // is a bug report, and a bug report is read by a developer.
  "VAlbum diagnostics": "diagnostics log",
  r"App: ${}": "diagnostics log",
  r"Platform: ${}": "diagnostics log",
  r"Server: ${}": "diagnostics log",
  r"Copied: ${}": "diagnostics log",
  r"Entries: ${} (of ${})": "diagnostics log",
  "(nothing logged yet)": "diagnostics log",
  r"lookup ${} ${} -> ${}": "diagnostics log",
  r"lookup ${} ${} !! ${}": "diagnostics log",
  r"lookup ${}: name resolution cannot be asked in a browser; the page sees "
      "only whether the request went through.": "diagnostics log",
  // The `kDebugMode` prints of the app: a developer's console, never a screen.
  r"fetching ${}": "debug log",
  r"fetching preview ${}": "debug log",
  r"offline, copy from ${} of ${}": "debug log",
  r"upload check !! ${}": "debug log",
  r"upload check refused !! ${}": "debug log",
  r"rendering listing ${} / ${}": "debug log",
  "context gone": "debug log",
  r"files picked ${}": "debug log",
  "upload started": "debug log",
  "upload aborted": "debug log",
  r"upload complete: ${}": "debug log",
  "album view gone, no reload after upload": "debug log",
  r"no messenger for ${}": "debug log",
  r"background sync task !! ${}": "debug log",
  r"wakelock !! ${}": "debug log",
  r"Cannot open the offline cache: ${}": "debug log",
  r"The offline cache index is damaged, rebuilding it: ${}": "debug log",
  r"Cannot read the offline cache directory: ${}": "debug log",
  r"Cannot read a cached entry: ${}": "debug log",
  r"Cannot write a cached entry: ${}": "debug log",
  r"Cannot drop a cached entry: ${}": "debug log",
  r"Cannot clear the offline cache: ${}": "debug log",
  r"Cannot write the offline cache index: ${}": "debug log",
  r"Unknown cache index version: ${}": "debug log, and thrown inside the "
      "cache where the rebuild catches it",
  r"Thumbnail: ${}": "an ErrorDescription of the image stream, read in a "
      "flutter error report",
  r"Face: ${}": "an ErrorDescription of the image stream, read in a "
      "flutter error report",
  // Assertion messages, read by a developer.
  "No ServerSettingsScope found in the widget tree.":
      "assertion, never shown to a user",
  "no OfflineScope in the widget tree": "assertion, never shown to a user",
  "no VAlbumNavigator in the widget tree": "assertion, never shown to a user",
  "No VAlbumScope found in the widget tree.":
      "assertion, never shown to a user",
  // Thrown, never shown: see `allowedThrownLiterals`.
  "Not an absolute server URL (expected e.g. 'http://host:8080/valbum/').":
      "thrown, see allowedThrownLiterals",
  r"The thumbnail at ${} is empty.": "thrown, see allowedThrownLiterals",
  r"The face crop at ${} is empty.": "thrown, see allowedThrownLiterals",
  // What the connection test appends to its own, already localized, message.
  r"${} (${})": "punctuation around a localized message",
  // The countdown of a device code: digits and a colon.
  r"${}:${}": "a duration, not a sentence",
  // A byte count and its unit, see `formatBytes`.
  r"${} B": "a byte count, not a sentence",
  // The wire form of a bearer token.
  r"Bearer ${}": "an HTTP header, not a sentence",
  // A format pattern, not a text: `yyyy-MM-dd HH:mm:ss` is what the recording
  // time is typed and read in, and it is the same in every language.
  "yyyy-MM-dd HH:mm:ss": "a date format pattern, not a text",
  // The regular expression of `nameDate`, pinned by the shared fixture
  // `image-server/src/test/fixtures/name-dates.json` (issue #102).
  r"(?<!\d)(?<y1>\d{4})(?<m1>\d{2})(?<d1>\d{2})[_\- ]?":
      "a regular expression",
  r"|(?<!\d)(?<y2>\d{4})-(?<m2>\d{2})-(?<d2>\d{2})(?: at |[_ T])":
      "a regular expression",
  // Two adjacent literals of `isLegalFolderName`, which the scanner joins:
  // `"/"`, `r"\"` and `"\u0000"` are the path separators a folder name may
  // not carry.
  "\\\") &&\n      !name.contains(": "path separators, not a sentence",
  // The diagnostics log of the video renditions, issues #73/#74: English in
  // a bug report, like every other line of `diagnostics.dart`.
  r"video rendition ${} !! ${}": "diagnostics log",
  r"video rendition ${} unavailable${}": "diagnostics log",
  r"video ${} !! ${}": "diagnostics log",
  // The words a player's own failure is classified by, see `videoErrorHint`:
  // they are matched against the platform's English message, which no
  // translation of this app ever changes, and none of them is ever shown.
  "renderer error": "keyword of the video failure classification",
  "no suitable": "keyword of the video failure classification",
  "source error": "keyword of the video failure classification",
  "unable to connect": "keyword of the video failure classification",
  "failed to connect": "keyword of the video failure classification",
  "connect timed out": "keyword of the video failure classification",
  "response code": "keyword of the video failure classification",
  "unknown host": "keyword of the video failure classification",
  "not permitted": "keyword of the video failure classification",
  // The thrown message of `album_layout.dart`, see `allowedThrownLiterals`.
  r"Invalid JPEG orientation code: ${}": "thrown, see allowedThrownLiterals",
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
  r"Unknown cache index version: ${}":
      "thrown inside `offline_file.dart` and caught two lines further on, "
          "where the index is rebuilt; it never leaves the cache",
  r"The thumbnail at ${} is empty.":
      "thrown into the image stream, where a failed decode is shown as the "
          "viewer's own `testL10n.pictureFailedMessage` (issue #95)",
  r"The face crop at ${} is empty.":
      "thrown into the image stream of a face tile, which falls back to the "
          "picture and the box instead of showing it (issue #126)",
  r"Invalid JPEG orientation code: ${}":
      "a programming error of the layout: the codes come from the model, and "
          "an unknown one is a bug, not something a reader is told",
  r"${}":
      "not a sentence at all: the exception is built from what the transport "
          "or the server said, and that text is not a literal of this app",
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
///
/// A placeholder is `{name}` or the argument of a plural, `{name, plural,
/// …}` — the name is followed by a `}` or a `,` and by nothing else. The
/// branches of a plural open a brace of their own (`=1{1 photo}`,
/// `other{{count} photos}`), and the first word of such a branch is text,
/// not a placeholder: reading it as one would make two languages that say
/// the same thing differently look like a renamed placeholder.
Set<String> placeholdersOf(String message) => {
      for (var match in RegExp(r"\{(\w+)\s*[},]").allMatches(message))
        match.group(1)!,
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

    test("are every hand-written file under lib/", () {
      var found = [
        for (var entry in Directory("lib").listSync())
          if (entry is File && entry.path.endsWith(".dart")) entry.path,
      ]..sort();
      expect(
        found.toSet(),
        {...convertedFiles, ...generatedFiles},
        reason: "every file under lib/ is either converted (and named in "
            "`convertedFiles`) or generated (and named in `generatedFiles`); "
            "a file added since is neither",
      );
    });
  });
}
