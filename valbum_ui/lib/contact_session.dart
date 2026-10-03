/// Where a browser keeps who it is on a personal share link (issue #202).
///
/// A personal link (#198) lets its visitor in as a **contact** of the space:
/// the first open of a recipient's own link, a mailed code (#199) or a sign-in
/// with Google (#200) answers a [ContactCredential], which every further
/// request of the session carries beside the link's token as
/// `X-VAlbum-Contact` (see [VAlbumClient.contact]). This library is where the
/// browser keeps it:
///
///  * "Remember me on this device" ticked → `localStorage`, so that the
///    visitor is recognised again for the 90 days the server renews it;
///  * unticked → `sessionStorage`, gone with the tab.
///
/// One credential per **space**, keyed by the data URL: once a browser is
/// recognised as a contact, every personal link of the same space opens
/// without asking again — the server checks per link whether it admits them.
///
/// Every access is wrapped: a private window, blocked site data or a storage
/// quota make the browser's storage throw or come back empty, and the session
/// then simply asks again. The credential is never written into an address, a
/// log line or the device's settings store.
library;

import 'package:flutter/foundation.dart';

/// One area of the browser's storage: `localStorage` or `sessionStorage`.
///
/// The three calls the store needs and nothing else, so that the web build
/// reaches the real areas (`platform_web.dart`) and a test or another
/// platform a map ([MemoryStorageArea]).
abstract class StorageArea {
  /// The value under [key], `null` where there is none or the area fails.
  String? get(String key);

  /// Stores [value] under [key]; a failing area keeps nothing.
  void set(String key, String value);

  /// Removes what is stored under [key].
  void remove(String key);
}

/// A [StorageArea] in memory: the session of a test, and of every platform
/// off the web, where a link is opened in a browser anyway.
class MemoryStorageArea implements StorageArea {
  /// What is stored, readable by a test.
  final Map<String, String> values = {};

  @override
  String? get(String key) => values[key];

  @override
  void set(String key, String value) => values[key] = value;

  @override
  void remove(String key) => values.remove(key);
}

/// A sign-in with a provider of OpenID Connect that left the page (#200).
///
/// The page navigates to the provider and comes back as
/// `<link base>#oidc=<code>`; what it must still know then is kept here, in
/// the tab's own storage: the [binding] the exchange proves the start with,
/// and whether the visitor asked to be [remember]ed.
@immutable
class PendingSignIn {
  /// The `OidcStarted.binding` of the start.
  final String binding;

  /// Whether the credential is to be remembered on this device.
  final bool remember;

  const PendingSignIn({required this.binding, required this.remember});
}

/// The contact credentials of this browser, one per space.
class ContactCredentialStore {
  /// The area of a remembered credential: `localStorage` on the web.
  final StorageArea remembered;

  /// The area of a credential for this tab alone, and of a [PendingSignIn]:
  /// `sessionStorage` on the web.
  final StorageArea session;

  ContactCredentialStore({required this.remembered, required this.session});

  /// A store in memory, for a test and for every platform off the web.
  factory ContactCredentialStore.memory() => ContactCredentialStore(
        remembered: MemoryStorageArea(),
        session: MemoryStorageArea(),
      );

  /// The key of the credential of the space serving [dataUrl].
  static String credentialKey(String dataUrl) => "valbum.contact|$dataUrl";

  /// The key of the [PendingSignIn] of the space serving [dataUrl].
  static String signInKey(String dataUrl) => "valbum.oidc|$dataUrl";

  /// The key under which this browser remembers that the offer "Add your
  /// e-mail" was dismissed in the space serving [dataUrl] (issue #211).
  static String emailOfferKey(String dataUrl) =>
      "valbum.addEmailDismissed|$dataUrl";

  /// Whether the offer "Add your e-mail" was dismissed in the space at
  /// [dataUrl] in this browser: it is shown once, until it is dismissed. A
  /// storage that cannot say counts as "not dismissed".
  bool emailOfferDismissed(String dataUrl) =>
      _guarded<String?>(() => remembered.get(emailOfferKey(dataUrl))) == "1";

  /// Remembers in the [remembered] area that the offer "Add your e-mail" was
  /// dismissed in the space at [dataUrl]; a failing storage keeps nothing.
  void dismissEmailOffer(String dataUrl) =>
      _guarded(() => remembered.set(emailOfferKey(dataUrl), "1"));

  /// The credential this browser holds for the space at [dataUrl], `null`
  /// where it holds none — or cannot say.
  String? read(String dataUrl) {
    var key = credentialKey(dataUrl);
    var value =
        _guarded<String?>(() => session.get(key)) ??
        _guarded<String?>(() => remembered.get(key));
    return value == null || value.isEmpty ? null : value;
  }

  /// Keeps [credential] for the space at [dataUrl]: in the [remembered] area
  /// where the visitor asked to be [remember]ed, else for this tab alone.
  /// The other area forgets what it held, so that one browser holds one
  /// person per space.
  void write(String dataUrl, String credential, {required bool remember}) {
    var key = credentialKey(dataUrl);
    var keep = remember ? remembered : session;
    var drop = remember ? session : remembered;
    _guarded(() => drop.remove(key));
    _guarded(() => keep.set(key, credential));
  }

  /// Forgets the credential of the space at [dataUrl]: "Not you? Switch
  /// person".
  void clear(String dataUrl) {
    var key = credentialKey(dataUrl);
    _guarded(() => session.remove(key));
    _guarded(() => remembered.remove(key));
  }

  /// The sign-in that left the page for a provider, `null` where none did.
  PendingSignIn? pendingSignIn(String dataUrl) {
    var value = _guarded<String?>(() => session.get(signInKey(dataUrl)));
    if (value == null) {
      return null;
    }
    var split = value.indexOf("|");
    if (split < 0) {
      return null;
    }
    return PendingSignIn(
      remember: value.substring(0, split) == "1",
      binding: value.substring(split + 1),
    );
  }

  /// Keeps what a sign-in needs when the page comes back, see
  /// [PendingSignIn].
  void keepSignIn(String dataUrl, PendingSignIn pending) =>
      _guarded(() => session.set(signInKey(dataUrl),
          "${pending.remember ? "1" : "0"}|${pending.binding}"));

  /// Forgets the [PendingSignIn]: it is used once.
  void dropSignIn(String dataUrl) =>
      _guarded(() => session.remove(signInKey(dataUrl)));

  /// Runs one access to the browser's storage, answering `null` where it
  /// throws.
  static T? _guarded<T>(T Function() access) {
    try {
      return access();
    } catch (_) {
      return null;
    }
  }
}

/// The parameter of the fragment a sign-in with a provider comes back with:
/// `<link base>#oidc=<code>` (issue #200).
const String oidcFragmentParameter = "oidc";

/// The exchange code of a location the provider's sign-in returned to, `null`
/// where there is none.
String? oidcCodeOf(Uri location) {
  var fragment = location.fragment;
  if (fragment.isEmpty) {
    return null;
  }
  try {
    var code = Uri.splitQueryString(fragment)[oidcFragmentParameter];
    return code == null || code.isEmpty ? null : code;
  } catch (_) {
    return null;
  }
}

/// [location] without its fragment: what the address bar shows once the
/// exchange code was read, so that a reload or a bookmark never carries it.
Uri withoutFragment(Uri location) => Uri(
      scheme: location.hasScheme ? location.scheme : null,
      userInfo: location.userInfo.isEmpty ? null : location.userInfo,
      host: location.host.isEmpty ? null : location.host,
      port: location.hasPort ? location.port : null,
      path: location.path,
      query: location.hasQuery ? location.query : null,
    );
