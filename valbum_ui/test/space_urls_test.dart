/// Tests of the addresses of a multi-space server (issue #85, first slice).
///
/// Everything of one space lives below the space's own segment: the app at
/// `<context>/<space>/`, the API at `<context>/<space>/data`, a share link at
/// `<context>/<space>/s/<token>/` and an invitation at
/// `<context>/<space>/i/<token>/`. A single-space server keeps the addresses
/// it has today, without the space segment.
///
/// What is pinned here is one rule: **the last two segments decide**. A path
/// ending in `/s/<token>/` or `/i/<token>/` is a session, everything before it
/// is the base of the server the session belongs to — context path and space
/// alike — and the app neither counts those segments nor needs to, because
/// both readings lead to the same data URL and the same app base.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/urls.dart';

/// The session URL as the server would spell it again from what was read.
///
/// The round trip of a session: location → kind, token, data URL, app base →
/// location.
String respell(SessionUrl session) =>
    "${session.appBase}${session.kind.segment}/${session.token}/";

void main() {
  group('a session below a space', () {
    test('is a share link with the space in its data URL and app base', () {
      const location = "https://host/valbum/alice/s/t0ken/";

      var session = sessionUrl(Uri.parse(location));

      expect(session, isNotNull);
      expect(session!.kind, SessionKind.share);
      expect(session.isShare, isTrue);
      expect(session.token, "t0ken");
      expect(session.dataUrl, "https://host/valbum/alice/data");
      expect(session.basePath, "/valbum/alice/s/t0ken/");
      // Where an accepted invitation returns to, and what a device stores.
      expect(session.appBase, "https://host/valbum/alice/");
      expect(respell(session), location);
    });

    test('is an invitation the same way', () {
      const location = "https://host/valbum/alice/i/t0ken/";

      var session = sessionUrl(Uri.parse(location));

      expect(session!.kind, SessionKind.invitation);
      expect(session.isInvitation, isTrue);
      expect(session.token, "t0ken");
      expect(session.dataUrl, "https://host/valbum/alice/data");
      expect(session.appBase, "https://host/valbum/alice/");
      expect(respell(session), location);
    });

    test('keeps working without a space, as a single-space server serves it',
        () {
      for (var form in const [
        // context, no space
        ("https://host/valbum/s/t0ken/", "https://host/valbum/data",
            "https://host/valbum/"),
        ("https://host/valbum/i/t0ken/", "https://host/valbum/data",
            "https://host/valbum/"),
        // no context at all: a server at its own root
        ("https://host/s/t0ken/", "https://host/data", "https://host/"),
        ("https://host/i/t0ken/", "https://host/data", "https://host/"),
      ]) {
        var session = sessionUrl(Uri.parse(form.$1));
        expect(session, isNotNull, reason: form.$1);
        expect(session!.dataUrl, form.$2, reason: form.$1);
        expect(session.appBase, form.$3, reason: form.$1);
        expect(respell(session), form.$1, reason: form.$1);
      }
    });

    test('allows any number of leading segments, space or context', () {
      // The app cannot tell a deep context path from a context plus a space,
      // and does not have to: both answer the same base.
      for (var form in const [
        ("https://host/s/t0ken/", "https://host/data"),
        ("https://host/valbum/s/t0ken/", "https://host/valbum/data"),
        ("https://host/valbum/alice/s/t0ken/", "https://host/valbum/alice/data"),
        (
          "https://host/photos/deep/alice/s/t0ken/",
          "https://host/photos/deep/alice/data"
        ),
        (
          "https://host/a/b/c/d/e/alice/i/t0ken/",
          "https://host/a/b/c/d/e/alice/data"
        ),
      ]) {
        var session = sessionUrl(Uri.parse(form.$1));
        expect(session, isNotNull, reason: form.$1);
        expect(session!.dataUrl, form.$2, reason: form.$1);
        expect(respell(session), form.$1, reason: form.$1);
      }
    });

    test('carries the port and keeps a deep link inside the session', () {
      // Inside a link the location is the album being looked at; only the app
      // base still says which token this is, which is what the app passes.
      var session = sessionUrl(
        Uri.parse("http://nas.local:8080/valbum/alice/s/t0ken/2005-Blumen/"),
        basePath: "/valbum/alice/s/t0ken/",
      );

      expect(session!.token, "t0ken");
      expect(session.dataUrl, "http://nas.local:8080/valbum/alice/data");
      expect(session.appBase, "http://nas.local:8080/valbum/alice/");
    });

    test('is no session without a token, and none without the segment', () {
      for (var location in const [
        // The space itself, not a session.
        "https://host/valbum/alice/",
        // The reserved segment without a token.
        "https://host/valbum/alice/s/",
        "https://host/valbum/alice/i/",
        // An album of a space that happens to be called like the segment of a
        // kind — the last-but-one segment is `alice`, not `s`.
        "https://host/valbum/alice/s0mething/",
        "https://host/valbum/",
        "https://host/",
      ]) {
        expect(sessionUrl(Uri.parse(location)), isNull, reason: location);
      }
    });

    test('reads a reserved segment as a session, never as a space', () {
      // The ambiguity of issue #85: `<context>/s/<token>/` and a space *named*
      // `s` would be the same string. The server reserves `s` and `i` as space
      // names, so the app reads them as a session always.
      var session = sessionUrl(Uri.parse("https://host/valbum/s/alice/"));

      expect(session, isNotNull);
      expect(session!.kind, SessionKind.share);
      expect(session.token, "alice");
      expect(session.dataUrl, "https://host/valbum/data");
    });
  });

  group('the data URL of a space', () {
    test('is the `data` folder below the space, derived from the page', () {
      // Purely base-relative: a space is one more segment of the app base.
      expect(
        deriveDataUrl(Uri.parse("https://host/valbum/alice/"), isWeb: true),
        "https://host/valbum/alice/data",
      );
      expect(
        deriveDataUrl(
          Uri.parse("https://host/valbum/alice/2005-Blumen/"),
          isWeb: true,
          // Inside the app the location is the view; the base is what counts.
          basePath: "/valbum/alice/",
        ),
        "https://host/valbum/alice/data",
      );
      expect(
        deriveDataUrl(
          Uri.parse("http://nas.local:8080/photos/deep/alice/index.html"),
          isWeb: true,
        ),
        "http://nas.local:8080/photos/deep/alice/data",
      );
    });

    test('is what a typed server URL with a space yields', () {
      for (var typed in const [
        "https://host/valbum/alice",
        "https://host/valbum/alice/",
        "https://host/valbum/alice/index.html",
      ]) {
        expect(dataUrlOf(typed), "https://host/valbum/alice/data",
            reason: typed);
      }
    });

    test('is the inverse of the app base of that space', () {
      expect(
        appBaseOf("https://host/valbum/alice/data"),
        "https://host/valbum/alice/",
      );
      expect(appBaseOf("https://host/valbum/data"), "https://host/valbum/");
    });
  });

  group('the server field', () {
    test('takes a plain server URL with a space', () {
      var entered = serverLocationOf("https://host/valbum/alice/");

      expect(entered.serverUrl, "https://host/valbum/alice/");
      expect(entered.dataUrl, "https://host/valbum/alice/data");
      expect(entered.invitation, "");
      expect(entered.share, "");
      expect(serverUrlError("https://host/valbum/alice/"), isNull);
    });

    test('takes a pasted invitation into a space, storing the space', () {
      var entered = serverLocationOf("https://host/valbum/alice/i/t0ken/");

      expect(entered.isInvitation, isTrue);
      expect(entered.invitation, "t0ken");
      // Stored is the server — the space included, the token never.
      expect(entered.serverUrl, "https://host/valbum/alice/");
      expect(entered.dataUrl, "https://host/valbum/alice/data");
      expect(serverUrlError("https://host/valbum/alice/i/t0ken/"), isNull);
    });

    test('takes a pasted invitation without a space, as it always did', () {
      var entered = serverLocationOf("https://host/valbum/i/t0ken/");

      expect(entered.isInvitation, isTrue);
      expect(entered.invitation, "t0ken");
      expect(entered.serverUrl, "https://host/valbum/");
      expect(entered.dataUrl, "https://host/valbum/data");
    });

    test('refuses a share link, with or without a space', () {
      for (var link in const [
        "https://host/valbum/s/t0ken/",
        "https://host/valbum/alice/s/t0ken/",
        "https://host/photos/deep/alice/s/t0ken/",
      ]) {
        var entered = serverLocationOf(link);
        expect(entered.isShare, isTrue, reason: link);
        expect(entered.share, "t0ken", reason: link);
        // A link opens one album and signs nothing in: it is said, not stored.
        expect(serverUrlError(link), shareLinkRefusal, reason: link);
      }
    });

    test('refuses what is no absolute URL at all, space or not', () {
      expect(() => serverLocationOf("alice/i/t0ken/"), throwsFormatException);
      expect(serverUrlError(""), isNotNull);
    });
  });
}
