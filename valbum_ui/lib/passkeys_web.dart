/// The passkeys of a browser (issue #204): `navigator.credentials` with the
/// options of WebAuthn's JSON form turned into the binary values the browser
/// asks for, and its answer turned back into JSON.
///
/// Written by hand rather than through `PublicKeyCredential.toJSON()` and
/// `parseCreationOptionsFromJSON`, which not every browser in use offers yet.
/// Reached only through `platform_web.dart`.
library;

import 'dart:convert';
import 'dart:js_interop';
import 'dart:js_interop_unsafe';
import 'dart:typed_data';

import 'passkeys.dart';

/// The [PasskeyAuthenticator] of this browser, `null` where it offers none.
PasskeyAuthenticator? browserPasskeys() {
  try {
    var navigator = globalContext["navigator"] as JSObject?;
    if (globalContext["PublicKeyCredential"] == null ||
        navigator == null ||
        navigator["credentials"] == null) {
      return null;
    }
  } catch (_) {
    return null;
  }
  return const _BrowserPasskeys();
}

class _BrowserPasskeys extends PasskeyAuthenticator {
  const _BrowserPasskeys();

  JSObject get _credentials =>
      (globalContext["navigator"] as JSObject)["credentials"] as JSObject;

  @override
  Future<String> create(String options) async {
    var decoded = jsonDecode(options) as Map<String, dynamic>;
    var publicKey = decoded.jsify() as JSObject;
    publicKey["challenge"] = _bytes(decoded["challenge"] as String);
    (publicKey["user"] as JSObject)["id"] =
        _bytes((decoded["user"] as Map<String, dynamic>)["id"] as String);
    _descriptorIds(publicKey, "excludeCredentials",
        decoded["excludeCredentials"] as List<dynamic>?);
    var credential = await _call("create", publicKey);
    var response = credential["response"] as JSObject;
    var transports = <String>[];
    if (response["getTransports"] != null) {
      var list = response.callMethod<JSArray<JSString>?>("getTransports".toJS);
      for (var transport in list?.toDart ?? const <JSString>[]) {
        transports.add(transport.toDart);
      }
    }
    return jsonEncode({
      ..._common(credential),
      "response": {
        "clientDataJSON": _text(response["clientDataJSON"]),
        "attestationObject": _text(response["attestationObject"]),
        "transports": transports,
      },
    });
  }

  @override
  Future<String> get(String options) async {
    var decoded = jsonDecode(options) as Map<String, dynamic>;
    var publicKey = decoded.jsify() as JSObject;
    publicKey["challenge"] = _bytes(decoded["challenge"] as String);
    _descriptorIds(publicKey, "allowCredentials",
        decoded["allowCredentials"] as List<dynamic>?);
    var credential = await _call("get", publicKey);
    var response = credential["response"] as JSObject;
    var userHandle = response["userHandle"];
    return jsonEncode({
      ..._common(credential),
      "response": {
        "clientDataJSON": _text(response["clientDataJSON"]),
        "authenticatorData": _text(response["authenticatorData"]),
        "signature": _text(response["signature"]),
        if (userHandle != null) "userHandle": _text(userHandle),
      },
    });
  }

  /// Calls `navigator.credentials.<method>({publicKey})`; a refusal of the
  /// person or the browser becomes [PasskeyCancelled], anything else
  /// [PasskeyFailed].
  Future<JSObject> _call(String method, JSObject publicKey) async {
    var argument = JSObject();
    argument["publicKey"] = publicKey;
    JSObject? credential;
    try {
      credential = await _credentials
          .callMethod<JSPromise<JSObject?>>(method.toJS, argument)
          .toDart;
    } catch (error) {
      var name = _errorName(error);
      if (name == "NotAllowedError" || name == "AbortError") {
        throw const PasskeyCancelled();
      }
      throw PasskeyFailed(name ?? "$error");
    }
    if (credential == null) {
      throw const PasskeyCancelled();
    }
    return credential;
  }

  Map<String, Object?> _common(JSObject credential) => {
        "id": (credential["id"] as JSString).toDart,
        "rawId": _text(credential["rawId"]),
        "type": "public-key",
        if (credential["authenticatorAttachment"] != null)
          "authenticatorAttachment":
              (credential["authenticatorAttachment"] as JSString).toDart,
        "clientExtensionResults": <String, Object?>{},
      };

  /// Puts the binary id of every descriptor of the given list in place.
  void _descriptorIds(
      JSObject publicKey, String name, List<dynamic>? descriptors) {
    if (descriptors == null) {
      return;
    }
    var list = publicKey[name] as JSArray<JSObject>;
    var items = list.toDart;
    for (var i = 0; i < descriptors.length; i++) {
      items[i]["id"] =
          _bytes((descriptors[i] as Map<String, dynamic>)["id"] as String);
    }
  }
}

/// The name of a `DOMException`, `null` where the error is none.
String? _errorName(Object error) {
  try {
    var name = (error as JSObject)["name"];
    return name == null ? null : (name as JSString).toDart;
  } catch (_) {
    return null;
  }
}

/// The bytes of a base64url value, as the browser takes them.
JSUint8Array _bytes(String base64) =>
    Uint8List.fromList(base64Url.decode(base64Url.normalize(base64))).toJS;

/// An `ArrayBuffer` of the browser in base64url without padding.
String _text(JSAny? buffer) {
  var bytes = (buffer as JSArrayBuffer).toDart.asUint8List();
  return base64Url.encode(bytes).replaceAll("=", "");
}
