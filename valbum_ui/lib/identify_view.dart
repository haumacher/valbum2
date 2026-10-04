/// The card a personal share link shows before it opens (issue #202).
///
/// A personal link (#198) answers a caller it does not recognise with a `401`
/// whose [ErrorInfo.identify] says what it needs; this page is that answer
/// turned into a screen. Two cases:
///
///  * **the first open** of a recipient's own link ([IdentifyRequired.firstOpen]):
///    the link alone identifies the recipient, who confirms their name, reads
///    whom it is shown to and chooses whether this browser remembers them;
///    `?action=identify` answers the credential;
///  * **any other open** in a browser that does not recognise the visitor:
///    one card offering the ways the server lists, in this order — a code
///    mailed to an address saved with the contact (masked, so nothing is
///    typed but the code; on an open link the visitor's own address), then
///    a sign-in with each provider of OpenID Connect, then — only where the
///    server says somebody the link may let in set one up — a passkey (issue
///    #204, where the browser has passkeys) and a code from an authenticator
///    app (issue #208); and where none applies, the sentence "Ask <sharer> to
///    send you the link again."
///
/// What the server says when it refuses is shown in its own words. Every
/// request is refused offline with the usual reason first. The card never
/// stores anything itself: a credential is handed to [onCredential], a
/// sign-in that leaves the page to [onSignInStarted], and the app keeps them
/// (`contact_session.dart`).
library;

import 'package:flutter/material.dart';

import 'about.dart';
import 'client.dart';
import 'l10n/app_localizations.dart';
import 'offline.dart';
import 'page_insets.dart';
import 'passkeys.dart';
import 'resource.dart';
import 'urls.dart';

/// The name of the mailed-code method, see `EmailProofs.METHOD` (#199).
const String mailCodeMethod = "mail-code";

/// The prefix of a method signing in through OpenID Connect (#200).
const String oidcMethodPrefix = "oidc:";

/// The name of the method of a code from an authenticator app, see
/// `TotpSignIns.METHOD` (#208).
const String totpMethod = "totp";

/// The name of the method of a passkey, see `Passkeys.METHOD` (#204).
const String passkeyMethod = "passkey";

/// The identification card of a personal share link, see the library.
class IdentifyScreen extends StatefulWidget {
  /// The client of the session: the link's token as the bearer, and beside it
  /// whatever credential this browser holds for the space.
  final VAlbumClient client;

  /// The link the page was opened at, for its cover (#104).
  final SessionUrl session;

  /// What the server said it needs.
  final IdentifyRequired identify;

  /// A refusal to show above the card, in the server's words: what the
  /// return from a provider was answered, for one.
  final String? message;

  /// Takes the credential the server answered.
  final void Function(ContactCredential credential) onCredential;

  /// Takes a sign-in through a provider that is about to leave the page: the
  /// app keeps its binding and navigates.
  final void Function(OidcStarted started, bool remember) onSignInStarted;

  /// Forgets the credential this browser holds and asks again, `null` where
  /// it holds none (issue #202): "Not you? Switch person".
  final VoidCallback? onSwitchPerson;

  const IdentifyScreen({
    super.key,
    required this.client,
    required this.session,
    required this.identify,
    required this.onCredential,
    required this.onSignInStarted,
    this.message,
    this.onSwitchPerson,
  });

  @override
  State<IdentifyScreen> createState() => IdentifyScreenState();
}

class IdentifyScreenState extends State<IdentifyScreen> {
  late final TextEditingController _name = TextEditingController(
    text: widget.identify.contact?.displayName ?? "",
  );

  final TextEditingController _address = TextEditingController();

  final TextEditingController _code = TextEditingController();

  final TextEditingController _totpCode = TextEditingController();

  /// Whether the field for a code of an authenticator app is open: the way
  /// is offered behind one tap, never as the default (issue #208).
  bool _totpOpen = false;

  /// "Remember me on this device", ticked by default (the author's decision).
  bool _remember = true;

  /// Whether a request of the card is running.
  bool _busy = false;

  /// The server's reason for the last refusal.
  late String? _error = widget.message;

  /// The address a code went to, as [EmailProof] named it; `null` while no
  /// code was asked for.
  EmailProof? _proof;

  /// The masked address the server says the code went to.
  String _sentTo = "";

  @override
  void dispose() {
    _name.dispose();
    _address.dispose();
    _code.dispose();
    _totpCode.dispose();
    super.dispose();
  }

  IdentifyRequired get _identify => widget.identify;

  /// Whether the link is a group link (#211): one address for all the
  /// recipients of a personal link, each proving an address of theirs once.
  bool get _group => _identify.group;

  /// Whether the link is an open personal link: no recipient's own link.
  bool get _open => _identify.contact == null && !_group;

  /// Whether the visitor types the address the code goes to: an open link,
  /// and a group link, which never shows whom it was sent to (#211).
  bool get _typesAddress => _identify.contact == null;

  /// Runs one request of the card, refused offline and its refusal shown in
  /// the server's words.
  Future<void> _run(Future<void> Function() request) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await request();
    } catch (error) {
      if (mounted) {
        setState(
            () => _error = error is VAlbumException ? error.message : "$error");
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  Future<void> _confirmName() => _run(() async {
        var answer = await widget.client.identify(ContactIdentify(
          remember: _remember,
          displayName: _name.text.trim(),
        ));
        widget.onCredential(answer);
      });

  Future<void> _sendCode(EmailProof proof) => _run(() async {
        var sent = await widget.client.proveEmail(proof);
        if (mounted) {
          setState(() {
            _proof = proof;
            _sentTo = sent.address.isEmpty ? proof.address : sent.address;
            _code.clear();
          });
        }
      });

  Future<void> _verify() {
    var proof = _proof!;
    return _run(() async {
      var answer = await widget.client.verifyEmail(EmailVerify(
        address: proof.address,
        choice: proof.choice,
        code: _code.text.trim(),
        remember: _remember,
        displayName: _open ? _name.text.trim() : "",
      ));
      widget.onCredential(answer);
    });
  }

  Future<void> _verifyTotp() => _run(() async {
        var answer = await widget.client.totpVerify(TotpCode(
          code: _totpCode.text.trim(),
          address: _typesAddress ? _address.text.trim() : "",
          remember: _remember,
          displayName: _open ? _name.text.trim() : "",
        ));
        widget.onCredential(answer);
      });

  /// Signs in with a passkey (issue #204): the server's options, the
  /// browser's passkey, the server's check.
  Future<void> _signInWithPasskey(AppLocalizations l10n) => _run(() async {
        var options = await widget.client.passkeyStart();
        var answer = await usePasskey(
            l10n, () => passkeyAuthenticator!.get(options.options));
        var credential = await widget.client.passkeyVerify(PasskeyResponse(
          ticket: options.ticket,
          response: answer,
          remember: _remember,
          displayName: _open ? _name.text.trim() : "",
        ));
        widget.onCredential(credential);
      });

  Future<void> _signIn(String provider) => _run(() async {
        var started = await widget.client.oidcStart(OidcStart(
          provider: provider,
          remember: _remember,
          displayName: _open ? _name.text.trim() : "",
        ));
        widget.onSignInStarted(started, _remember);
      });

  /// The address of the link's cover (#104): the shared album's picture,
  /// answered anonymously below the link's own base.
  String get _coverUrl => Uri.parse(widget.session.dataUrl)
      .replace(path: "${widget.session.basePath}cover.jpg")
      .toString();

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var theme = Theme.of(context);
    // The shared album's title (#104's rule); the link's label is its
    // maker's alone and never shown here.
    var title = _identify.title.trim();
    var sharer = _identify.sharedBy.trim();
    return Scaffold(
      key: const Key("identify-screen"),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: SingleChildScrollView(
            padding: pagePadding(context, const EdgeInsets.all(24)),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              mainAxisSize: MainAxisSize.min,
              children: [
                ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: Image.network(
                    _coverUrl,
                    key: const Key("identify-cover"),
                    height: 180,
                    fit: BoxFit.cover,
                    // No cover, no gap: the link may show none.
                    errorBuilder: (context, error, stack) =>
                        const SizedBox.shrink(),
                  ),
                ),
                const SizedBox(height: 16),
                Text(
                  title.isEmpty ? l10n.sharedAlbumFallback : title,
                  key: const Key("identify-label"),
                  style: theme.textTheme.headlineSmall,
                  textAlign: TextAlign.center,
                ),
                if (sharer.isNotEmpty)
                  Text(
                    l10n.identifySharedBy(sharer),
                    key: const Key("identify-shared-by"),
                    textAlign: TextAlign.center,
                  ),
                const SizedBox(height: 24),
                ...(_identify.firstOpen
                    ? _firstOpen(l10n, sharer)
                    : _proofs(l10n, sharer)),
                if (_error != null) ...[
                  const SizedBox(height: 16),
                  Text(
                    _error!,
                    key: const Key("identify-error"),
                    style: TextStyle(color: theme.colorScheme.error),
                  ),
                ],
                if (widget.onSwitchPerson != null) ...[
                  const SizedBox(height: 16),
                  TextButton.icon(
                    key: const Key("identify-switch-person"),
                    onPressed: _busy ? null : widget.onSwitchPerson,
                    icon: const Icon(Icons.switch_account),
                    label: Text(l10n.switchPerson),
                  ),
                ],
                const SizedBox(height: 24),
                Center(child: aboutButton(context)),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _rememberBox(AppLocalizations l10n) => CheckboxListTile(
        key: const Key("identify-remember"),
        contentPadding: EdgeInsets.zero,
        controlAffinity: ListTileControlAffinity.leading,
        value: _remember,
        title: Text(l10n.identifyRemember),
        onChanged: _busy
            ? null
            : (value) => setState(() => _remember = value ?? false),
      );

  Widget _nameField(AppLocalizations l10n) => TextField(
        key: const Key("identify-name"),
        controller: _name,
        enabled: !_busy,
        decoration: InputDecoration(labelText: l10n.identifyNameLabel),
      );

  /// The first open: the name, whom it is shown to, remember me.
  List<Widget> _firstOpen(AppLocalizations l10n, String sharer) => [
        Text(l10n.identifyFirstOpenIntro),
        const SizedBox(height: 8),
        _nameField(l10n),
        const SizedBox(height: 12),
        Text(
          sharer.isEmpty
              ? l10n.identifyNoticeNobody
              : l10n.identifyNotice(sharer),
          key: const Key("identify-notice"),
        ),
        _rememberBox(l10n),
        const SizedBox(height: 8),
        FilledButton(
          key: const Key("identify-continue"),
          onPressed: _busy ? null : _confirmName,
          child: Text(l10n.identifyContinue),
        ),
      ];

  /// A later open: the ways the server lists, in their order.
  List<Widget> _proofs(AppLocalizations l10n, String sharer) {
    var methods = [for (var method in _identify.methods) method.name];
    var offered = <Widget>[];
    var mail = methods.contains(mailCodeMethod);
    var totp = methods.contains(totpMethod);
    if (_typesAddress && _proof == null && (mail || totp)) {
      // One address field for every way that needs the visitor's address.
      offered.add(TextField(
        key: const Key("identify-address"),
        controller: _address,
        enabled: !_busy,
        keyboardType: TextInputType.emailAddress,
        decoration: InputDecoration(labelText: l10n.identifyAddressLabel),
      ));
    }
    if (mail) {
      offered.addAll(_mailCode(l10n));
    }
    for (var method in _identify.methods) {
      if (!method.name.startsWith(oidcMethodPrefix)) {
        continue;
      }
      var provider = method.name.substring(oidcMethodPrefix.length);
      var name = method.label.trim().isEmpty ? provider : method.label.trim();
      offered.add(Padding(
        padding: const EdgeInsets.only(top: 8),
        child: OutlinedButton.icon(
          key: Key("identify-oidc-$provider"),
          onPressed: _busy ? null : () => _signIn(provider),
          icon: const Icon(Icons.login),
          label: Text(l10n.identifyContinueWith(name)),
        ),
      ));
    }
    if (methods.contains(passkeyMethod) && passkeyAuthenticator != null) {
      offered.add(Padding(
        padding: const EdgeInsets.only(top: 8),
        child: OutlinedButton.icon(
          key: const Key("identify-passkey"),
          onPressed: _busy ? null : () => _signInWithPasskey(l10n),
          icon: const Icon(Icons.key),
          label: Text(l10n.identifyPasskey),
        ),
      ));
    }
    if (totp) {
      offered.addAll(_authenticator(l10n));
    }
    return [
      Text(l10n.identifyWhoTitle,
          style: Theme.of(context).textTheme.titleMedium),
      const SizedBox(height: 8),
      Text(_group
          ? l10n.identifyGroupIntro
          : _open
              ? l10n.identifyOpenIntro
              : l10n.identifyRecipientIntro),
      if (offered.isEmpty) ...[
        const SizedBox(height: 16),
        Text(
          sharer.isEmpty
              ? l10n.identifyAskAgainNobody
              : l10n.identifyAskAgain(sharer),
          key: const Key("identify-ask-again"),
        ),
      ] else ...[
        if (_open) ...[
          const SizedBox(height: 8),
          _nameField(l10n),
        ],
        _rememberBox(l10n),
        ...offered,
      ],
    ];
  }

  /// The mailed code: one button per saved e-mail address (masked), or on an
  /// open link a field for the visitor's own; then the code.
  List<Widget> _mailCode(AppLocalizations l10n) {
    var proof = _proof;
    if (proof != null) {
      return [
        Text(l10n.identifyCodeSent(_sentTo),
            key: const Key("identify-code-sent")),
        TextField(
          key: const Key("identify-code"),
          controller: _code,
          enabled: !_busy,
          autofocus: true,
          keyboardType: TextInputType.number,
          decoration: InputDecoration(labelText: l10n.identifyCodeLabel),
          onSubmitted: _busy ? null : (_) => _verify(),
        ),
        const SizedBox(height: 8),
        FilledButton(
          key: const Key("identify-verify"),
          onPressed: _busy ? null : _verify,
          child: Text(l10n.identifyConfirmCode),
        ),
      ];
    }
    if (_typesAddress) {
      return [
        const SizedBox(height: 8),
        FilledButton(
          key: const Key("identify-send-code"),
          onPressed: _busy
              ? null
              : () => _sendCode(EmailProof(address: _address.text.trim())),
          child: Text(l10n.identifySendCode),
        ),
      ];
    }
    var addresses = _identify.addresses;
    return [
      for (var i = 0; i < addresses.length; i++)
        if (addresses[i].kind == AddressKind.email)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: FilledButton.icon(
              key: Key("identify-send-code-${i + 1}"),
              onPressed: _busy
                  ? null
                  : () => _sendCode(EmailProof(
                        address: addresses[i].masked,
                        choice: i + 1,
                      )),
              icon: const Icon(Icons.mail_outline),
              label: Text(l10n.identifySendCodeTo(addresses[i].masked)),
            ),
          ),
    ];
  }

  /// A code from an authenticator app (issue #208): one button that opens
  /// the field, then the code; on a link that names nobody the address field
  /// above says whose app it is.
  List<Widget> _authenticator(AppLocalizations l10n) {
    if (!_totpOpen) {
      return [
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: OutlinedButton.icon(
            key: const Key("identify-totp"),
            onPressed: _busy ? null : () => setState(() => _totpOpen = true),
            icon: const Icon(Icons.pin_outlined),
            label: Text(l10n.identifyTotp),
          ),
        ),
      ];
    }
    return [
      const SizedBox(height: 16),
      Text(l10n.identifyTotp, style: Theme.of(context).textTheme.titleSmall),
      TextField(
        key: const Key("identify-totp-code"),
        controller: _totpCode,
        enabled: !_busy,
        autofocus: true,
        keyboardType: TextInputType.number,
        decoration: InputDecoration(labelText: l10n.totpCodeLabel),
        onSubmitted: _busy ? null : (_) => _verifyTotp(),
      ),
      const SizedBox(height: 8),
      FilledButton(
        key: const Key("identify-totp-verify"),
        onPressed: _busy ? null : _verifyTotp,
        child: Text(l10n.identifyConfirmCode),
      ),
    ];
  }
}

/// Runs [ceremony] of the browser's passkeys and answers what it made; a
/// passkey the person declined, or one the browser could not use, becomes a
/// refusal in the app's own words.
Future<String> usePasskey(
    AppLocalizations l10n, Future<String> Function() ceremony) async {
  try {
    return await ceremony();
  } on PasskeyCancelled {
    throw VAlbumException(l10n.passkeyCancelled);
  } on PasskeyFailed catch (failure) {
    throw VAlbumException(l10n.passkeyFailed(failure.reason));
  }
}
