/// Passkeys of a contact of a personal link (issue #204): what the page asks
/// of `navigator.credentials`.
///
/// The server makes the options (`?action=passkey-register-start`,
/// `?action=passkey-start`) and checks the answer; this is the browser in
/// between. Both directions are WebAuthn's JSON form — binary values
/// base64url — so that the transport stays plain text and a test can stand in
/// for the browser.
///
/// A share session runs on the web only, so only the web build has an
/// authenticator ([passkeyAuthenticator] is `null` elsewhere, and in a
/// browser that offers no WebAuthn); where it is `null` neither "Sign in with
/// passkey" nor "Recognise me on my other devices" is offered.
library;

import 'platform.dart';

/// The browser's passkeys, see the library.
abstract class PasskeyAuthenticator {
  const PasskeyAuthenticator();

  /// Makes a passkey for the given `PublicKeyCredentialCreationOptions` (in
  /// their JSON form) and answers the `PublicKeyCredential` in its JSON form.
  ///
  /// Throws [PasskeyCancelled] where the person (or the browser) declined,
  /// [PasskeyFailed] for anything else.
  Future<String> create(String options);

  /// Signs with a passkey for the given `PublicKeyCredentialRequestOptions`
  /// (in their JSON form) and answers the `PublicKeyCredential` in its JSON
  /// form; the throws of [create].
  Future<String> get(String options);
}

/// The person or the browser declined: no passkey was made or used.
class PasskeyCancelled implements Exception {
  const PasskeyCancelled();
}

/// The browser could not make or use a passkey, for the reason it gave.
class PasskeyFailed implements Exception {
  /// The browser's own reason, for the message shown.
  final String reason;

  const PasskeyFailed(this.reason);

  @override
  String toString() => "PasskeyFailed($reason)";
}

/// The passkeys of this browser, `null` where there are none to use.
///
/// A seam like `phoneContacts`: a widget test sets it to a fake.
PasskeyAuthenticator? passkeyAuthenticator = defaultPasskeyAuthenticator();
