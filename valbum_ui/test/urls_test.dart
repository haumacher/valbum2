import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/urls.dart';
import 'util/l10n.dart';

void main() {
  group('deriveDataUrl', () {
    test('app served below a context path', () {
      expect(
        deriveDataUrl(Uri.parse("http://host:8080/valbum/"), isWeb: true),
        "http://host:8080/valbum/data",
      );
    });

    test('app served at the root', () {
      expect(
        deriveDataUrl(Uri.parse("http://host/"), isWeb: true),
        "http://host/data",
      );
    });

    test('location pointing to the index page', () {
      expect(
        deriveDataUrl(Uri.parse("https://h/valbum/index.html"), isWeb: true),
        "https://h/valbum/data",
      );
    });

    test('query and fragment are dropped', () {
      expect(
        deriveDataUrl(Uri.parse("https://h/valbum/?x=1#top"), isWeb: true),
        "https://h/valbum/data",
      );
    });

    test('deep location with an explicit base href', () {
      expect(
        deriveDataUrl(
          Uri.parse("http://h/valbum/some/route/"),
          isWeb: true,
          basePath: "/valbum/",
        ),
        "http://h/valbum/data",
      );
    });

    test('without a base href the location is the app base', () {
      // The app installs no routing, so this is the input actually seen.
      expect(
        deriveDataUrl(Uri.parse("http://h/valbum/some/route/"), isWeb: true),
        "http://h/valbum/some/route/data",
      );
    });

    test('non-web falls back to the default', () {
      expect(
        deriveDataUrl(Uri.parse("http://host/valbum/"), isWeb: false),
        defaultDataUrl,
      );
      expect(
        deriveDataUrl(
          Uri.parse("http://host/valbum/"),
          isWeb: false,
          fallback: "http://box:9090/valbum/data",
        ),
        "http://box:9090/valbum/data",
      );
    });
  });

  group('dataUrlOf', () {
    test('a context path without a trailing slash', () {
      expect(dataUrlOf("http://h:8080/valbum"), "http://h:8080/valbum/data");
    });

    test('a context path with a trailing slash', () {
      expect(dataUrlOf("http://h:8080/valbum/"), "http://h:8080/valbum/data");
    });

    test('the index page of the app', () {
      expect(
        dataUrlOf("http://h:8080/valbum/index.html"),
        "http://h:8080/valbum/data",
      );
    });

    test('a server serving the app at its root', () {
      expect(dataUrlOf("https://h/"), "https://h/data");
      expect(dataUrlOf("https://h"), "https://h/data");
    });

    test('query and fragment are dropped', () {
      expect(
        dataUrlOf("http://h:8080/valbum/?x=1#top"),
        "http://h:8080/valbum/data",
      );
    });

    test('surrounding whitespace is ignored', () {
      expect(dataUrlOf("  http://h/valbum/  "), "http://h/valbum/data");
    });

    test('a URL without a host is refused', () {
      expect(() => dataUrlOf("nas.local"), throwsFormatException);
      expect(() => dataUrlOf("/valbum/"), throwsFormatException);
    });
  });

  group('serverUrlError', () {
    test('accepts a usable server URL', () {
      expect(serverUrlError(testL10n, "http://nas.local:8080/valbum/"), isNull);
    });

    test('names the problem of an empty or relative URL', () {
      expect(serverUrlError(testL10n, "   "), isNotNull);
      expect(serverUrlError(testL10n, "nas.local"), isNotNull);
    });
  });

  group('sessionUrl', () {
    test('a link below a context path', () {
      var link = sessionUrl(
        Uri.parse("http://h:8080/valbum/s/abc123/"),
        basePath: "/valbum/s/abc123/",
      );
      expect(link, isNotNull);
      expect(link!.token, "abc123");
      expect(link.dataUrl, "http://h:8080/valbum/data");
      expect(link.basePath, "/valbum/s/abc123/");
    });

    test('a link on a server serving the app at its root', () {
      var link = sessionUrl(
        Uri.parse("http://h:8080/s/abc/"),
        basePath: "/s/abc/",
      );
      expect(link!.token, "abc");
      expect(link.dataUrl, "http://h:8080/data");
      expect(link.basePath, "/s/abc/");
    });

    test('an ordinary app base is no session', () {
      expect(
        sessionUrl(
          Uri.parse("http://h:8080/valbum/"),
          basePath: "/valbum/",
        ),
        isNull,
      );
      // And the data URL of such a start is the one it always was.
      expect(
        deriveDataUrl(
          Uri.parse("http://h:8080/valbum/"),
          isWeb: true,
          basePath: "/valbum/",
        ),
        "http://h:8080/valbum/data",
      );
    });

    test('an empty segment is not a token', () {
      expect(
        sessionUrl(
          Uri.parse("http://h:8080/valbum/s/"),
          basePath: "/valbum/s/",
        ),
        isNull,
      );
    });

    test('the base path decides, not the document location', () {
      // Inside a session the location is the album being looked at; only the
      // app base still says which link this is.
      var link = sessionUrl(
        Uri.parse("http://h:8080/valbum/s/abc/2005/"),
        basePath: "/valbum/s/abc/",
      );
      expect(link!.token, "abc");
      expect(link.dataUrl, "http://h:8080/valbum/data");
    });

    test('without a base path the directory of the location is used', () {
      var link = sessionUrl(Uri.parse("http://h:8080/valbum/s/abc/"));
      expect(link!.token, "abc");
      expect(link.dataUrl, "http://h:8080/valbum/data");
    });
  });

  group('invitation sessions', () {
    test('an invitation below a context path', () {
      var session = sessionUrl(
        Uri.parse("http://h:8080/valbum/i/abc/"),
        basePath: "/valbum/i/abc/",
      );
      expect(session, isNotNull);
      expect(session!.kind, SessionKind.invitation);
      expect(session.isInvitation, isTrue);
      expect(session.token, "abc");
      expect(session.dataUrl, "http://h:8080/valbum/data");
      expect(session.basePath, "/valbum/i/abc/");
      expect(session.appBase, "http://h:8080/valbum/");
    });

    test('a share link is still a share session', () {
      var session = sessionUrl(
        Uri.parse("http://h:8080/valbum/s/abc/"),
        basePath: "/valbum/s/abc/",
      );
      expect(session!.kind, SessionKind.share);
      expect(session.isShare, isTrue);
      expect(session.isInvitation, isFalse);
    });

    test('an empty invitation segment is no session', () {
      expect(
        sessionUrl(
          Uri.parse("http://h:8080/valbum/i/"),
          basePath: "/valbum/i/",
        ),
        isNull,
      );
    });
  });

  group('serverLocationOf', () {
    test('reads an invitation URL as a server and a token', () {
      var location = serverLocationOf("http://h/valbum/i/tok-1/");
      expect(location.serverUrl, "http://h/valbum/");
      expect(location.dataUrl, "http://h/valbum/data");
      expect(location.invitation, "tok-1");
      expect(location.isInvitation, isTrue);
      expect(location.isShare, isFalse);
    });

    test('a share link names no server to sign in at', () {
      var location = serverLocationOf("http://h/valbum/s/tok/");
      expect(location.share, "tok");
      expect(location.isShare, isTrue);
      expect(serverUrlError(testL10n, "http://h/valbum/s/tok/"), shareLinkRefusal(testL10n));
    });

    test('a plain URL is read exactly as it always was', () {
      var location = serverLocationOf("http://h/valbum/");
      expect(location.serverUrl, "http://h/valbum/");
      expect(location.dataUrl, dataUrlOf("http://h/valbum/"));
      expect(location.invitation, "");
      expect(location.share, "");
      expect(serverUrlError(testL10n, "http://h/valbum/"), isNull);
      // And a URL without a trailing slash, and an index page.
      expect(serverLocationOf("http://h/valbum").dataUrl, "http://h/valbum/data");
      expect(
        serverLocationOf("http://h/valbum/index.html").serverUrl,
        "http://h/valbum/",
      );
    });

    test('refuses what is no absolute URL', () {
      expect(() => serverLocationOf("nas.local"), throwsFormatException);
    });
  });

  group('absoluteServerUrl', () {
    test('makes the server-relative share URL absolute', () {
      expect(
        absoluteServerUrl("http://h/valbum/data", "/valbum/s/tok/"),
        "http://h/valbum/s/tok/",
      );
      expect(
        absoluteServerUrl("http://h:8080/valbum/data", "/s/tok/"),
        "http://h:8080/s/tok/",
      );
    });
  });
}
