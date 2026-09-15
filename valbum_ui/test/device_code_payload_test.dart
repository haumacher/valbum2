/// Tests of what a device-code QR code carries (issue #66).
///
/// The QR code must be worth exactly as much as the eight characters it shows
/// and not one bit more: it names a server and a code in a scheme of this
/// app's own, and everything else a camera may see — an invitation link, an
/// ordinary web address, a code with a letter that is not in the alphabet — is
/// not a device code and is refused here, before any field is filled and
/// before anything is sent.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/device_code_payload.dart';

void main() {
  test('encodes and parses a server with a path and a port', () {
    var payload =
        encodeDeviceCodePayload("http://nas.local:8080/valbum/", "ABCD2345");

    expect(payload, startsWith("valbum-device://pair?"));
    // Never a URL a browser or a camera app offers to open, see issue #66.
    expect(payload, isNot(contains("http://nas.local:8080/valbum/")));

    var parsed = parseDeviceCodePayload(payload);
    expect(parsed?.serverUrl, "http://nas.local:8080/valbum/");
    expect(parsed?.code, "ABCD2345");
    expect(parsed?.formattedCode, "ABCD-2345");
  });

  test('carries a server at the root and one with a deep path', () {
    for (var server in const [
      "http://localhost:9090/valbum/",
      "https://album.example.org/",
      "https://h.example.org/photos/family/valbum/",
    ]) {
      expect(
        parseDeviceCodePayload(encodeDeviceCodePayload(server, "WXYZ6789"))
            ?.serverUrl,
        server,
      );
    }
  });

  test('normalises a code that is read with dashes, spaces or lower case', () {
    var expected = const DeviceCodePayload(
      serverUrl: "http://h:8080/valbum/",
      code: "ABCDEFGH",
    );
    for (var written in const ["ABCD-EFGH", "abcd efgh", "abcd-efgh"]) {
      expect(
        parseDeviceCodePayload(
          encodeDeviceCodePayload("http://h:8080/valbum/", written),
        ),
        expected,
      );
    }
  });

  test('refuses everything that is not a device-code payload', () {
    for (var scanned in const [
      // An invitation link: a real credential of this very server, and
      // deliberately not something the sign-in screen accepts from a camera.
      "https://example.org/valbum/i/abc/",
      // A share link, likewise.
      "https://example.org/valbum/s/abc/",
      // Anything else a QR code in the world may say.
      "https://example.org/",
      "http://nas.local:8080/valbum/",
      "WIFI:S:guest;T:WPA;P:secret;;",
      "ABCD-2345",
      "",
      "   ",
      // The right shape, the wrong scheme.
      "valbum://pair?server=http%3A%2F%2Fh%2Fvalbum%2F&code=ABCD2345",
      // The right scheme, a host that is not what this payload is for.
      "valbum-device://sign-in?server=http%3A%2F%2Fh%2Fvalbum%2F&code=ABCD2345",
    ]) {
      expect(parseDeviceCodePayload(scanned), isNull, reason: scanned);
    }
  });

  test('refuses a payload whose parts are missing or malformed', () {
    for (var scanned in const [
      // No code.
      "valbum-device://pair?server=http%3A%2F%2Fh%2Fvalbum%2F",
      // No server.
      "valbum-device://pair?code=ABCD2345",
      // A server that names no host.
      "valbum-device://pair?server=%2Fvalbum%2F&code=ABCD2345",
      // Seven characters are not a code.
      "valbum-device://pair?server=http%3A%2F%2Fh%2F&code=ABCD234",
      // Nine are not either.
      "valbum-device://pair?server=http%3A%2F%2Fh%2F&code=ABCD23456",
      // Letters and digits the alphabet of issue #65 leaves out, because a
      // human being reads the code off a screen: O, 0, I and 1.
      "valbum-device://pair?server=http%3A%2F%2Fh%2F&code=ABCO2345",
      "valbum-device://pair?server=http%3A%2F%2Fh%2F&code=ABCD2340",
      "valbum-device://pair?server=http%3A%2F%2Fh%2F&code=ABCI2345",
      "valbum-device://pair?server=http%3A%2F%2Fh%2F&code=ABCD2341",
    ]) {
      expect(parseDeviceCodePayload(scanned), isNull, reason: scanned);
    }
  });

  test('accepts a code written the way the other screen shows it', () {
    expect(
      parseDeviceCodePayload(
        "valbum-device://pair?server=http%3A%2F%2Fh%2Fvalbum%2F&code=ABCD-EFGH",
      )?.code,
      "ABCDEFGH",
    );
    expect(
      parseDeviceCodePayload(
        "valbum-device://pair?server=http%3A%2F%2Fh%2Fvalbum%2F&code=abcd%20efgh",
      )?.code,
      "ABCDEFGH",
    );
  });
}
