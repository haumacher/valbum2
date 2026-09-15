/// What a device-code QR code carries, and the only place it is read
/// (issue #66).
///
/// The QR code on the signed-in device saves the typing on the new one, and it
/// must be worth no more than the code it shows: a 10-minute, single-use
/// device credential, and never a URL the server serves. Hence a scheme of
/// this app's own — `valbum-device://pair?server=…&code=…` — which no camera
/// app and no browser offers to open, and for which the app registers no
/// intent filter and no URL handler. A forwarded picture of the QR code is
/// worth exactly as much as a forwarded code: it names the server and the
/// code, which is what the other device would have been told anyway.
///
/// Encoding and parsing live together here, pure Dart and without a widget in
/// sight, so that the round trip is one thing to read and one thing to test.
/// Nothing else in the app interprets scanned text: the scanner hands its raw
/// string to [parseDeviceCodePayload] and believes nothing that comes back
/// `null`.
library;

/// The scheme of a device-code payload: not `http`, on purpose, see the
/// library comment.
const String deviceCodeScheme = "valbum-device";

/// The host of a device-code payload, naming what the payload is for.
const String deviceCodeHost = "pair";

/// The characters a device code is made of, see issue #65.
///
/// The server's own alphabet: no `I`, `O`, `0` or `1`, because the code is
/// read off one screen by a human being. A scanned code outside it is not a
/// code of this server and is refused here rather than sent.
const String deviceCodeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

/// How many characters a device code has.
const int deviceCodeLength = 8;

/// A device code and the server it belongs to, as a QR code carries them.
class DeviceCodePayload {
  /// The app base of the album server, the value the server field holds.
  final String serverUrl;

  /// The device code, upper case and without the dash, ready for the wire.
  final String code;

  const DeviceCodePayload({required this.serverUrl, required this.code});

  /// The code the way both screens spell it, `XXXX-XXXX`.
  String get formattedCode => formatDeviceCode(code);

  @override
  bool operator ==(Object other) =>
      other is DeviceCodePayload &&
      other.serverUrl == serverUrl &&
      other.code == code;

  @override
  int get hashCode => Object.hash(serverUrl, code);

  @override
  String toString() => "DeviceCodePayload($serverUrl, $code)";
}

/// A device code without its dashes, spaces and lower case.
///
/// The same normalisation the server performs before it hashes a code, so
/// that a code read out of a payload and a code typed into the field reach the
/// server as the same eight characters.
String normalizeDeviceCode(String code) =>
    code.toUpperCase().replaceAll(RegExp(r"[\s-]"), "");

/// Whether [code] can be a device code of this server at all.
///
/// Normalised first, so `abcd efgh` is judged as `ABCDEFGH`.
bool isDeviceCode(String code) {
  var normalized = normalizeDeviceCode(code);
  if (normalized.length != deviceCodeLength) {
    return false;
  }
  for (var index = 0; index < normalized.length; index++) {
    if (!deviceCodeAlphabet.contains(normalized[index])) {
      return false;
    }
  }
  return true;
}

/// A device code as both screens spell it: `XXXX-XXXX`.
///
/// Anything that is not a code of the expected length is handed back
/// normalised but unsplit — a shape nobody promised is better than a dash in
/// the wrong place.
String formatDeviceCode(String code) {
  var normalized = normalizeDeviceCode(code);
  if (normalized.length != deviceCodeLength) {
    return normalized;
  }
  return "${normalized.substring(0, 4)}-${normalized.substring(4)}";
}

/// What the QR code of [code] at [serverUrl] contains, see the library
/// comment.
///
/// [serverUrl] is the app base of the server as the settings store it (e.g.
/// `http://nas.local:8080/valbum/`), because that is the value the other
/// device's server field takes; it is percent-encoded into the query, so that
/// its own slashes, port and path survive.
String encodeDeviceCodePayload(String serverUrl, String code) => Uri(
      scheme: deviceCodeScheme,
      host: deviceCodeHost,
      path: "",
      queryParameters: {
        "server": serverUrl.trim(),
        "code": normalizeDeviceCode(code),
      },
    ).toString();

/// The payload [scanned] carries, or `null` if it carries none.
///
/// Deliberately strict: the scheme, the host and both parameters must be
/// there, the server must be an absolute URL with a host, and the code must
/// pass [isDeviceCode]. Everything else — an `https` URL, an invitation link,
/// a share link, a Wi-Fi QR code, a contact card, an empty string — is not a
/// device code and is answered with `null`, which the scanner shows as "this
/// is not a device code" and keeps scanning.
DeviceCodePayload? parseDeviceCodePayload(String scanned) {
  Uri uri;
  try {
    uri = Uri.parse(scanned.trim());
  } on FormatException {
    return null;
  }
  if (uri.scheme != deviceCodeScheme || uri.host != deviceCodeHost) {
    return null;
  }
  var server = (uri.queryParameters["server"] ?? "").trim();
  var code = uri.queryParameters["code"] ?? "";
  if (server.isEmpty || !isDeviceCode(code)) {
    return null;
  }
  Uri serverUri;
  try {
    serverUri = Uri.parse(server);
  } on FormatException {
    return null;
  }
  if (!serverUri.hasScheme || serverUri.host.isEmpty) {
    return null;
  }
  return DeviceCodePayload(
    serverUrl: server,
    code: normalizeDeviceCode(code),
  );
}
