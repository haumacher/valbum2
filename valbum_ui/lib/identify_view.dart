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
///    a sign-in with each provider of OpenID Connect — and where none
///    applies, the sentence "Ask <sharer> to send you the link again."
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
import 'resource.dart';
import 'urls.dart';

/// The name of the mailed-code method, see `EmailProofs.METHOD` (#199).
const String mailCodeMethod = "mail-code";

/// The prefix of a method signing in through OpenID Connect (#200).
const String oidcMethodPrefix = "oidc:";

/// The refusal of an open personal link (`AuthService.IDENTIFY_OPEN`), which
/// [isGroupLink] tells from a group link's while the server names neither.
const String openLinkRefusal =
    "This link asks who you are: it opens once you have confirmed your e-mail address.";

/// Whether [needs] is the refusal of a **group link** (issue #211): the own
/// token of a personal link with recipients, which any recipient opens by
/// proving an address of theirs.
///
/// The server says so in `IdentifyRequired.group`, a field of a server
/// package built in parallel; this build's model does not know it yet, so it
/// is read through a guarded accessor and, where absent, decided from what
/// such a refusal looks like: no recipient's own link, no first open, no
/// masked address, a way to prove one — and not the open link's own sentence
/// ([refusal] is the server's message).
bool isGroupLink(IdentifyRequired needs, {String? refusal}) {
  try {
    var group = (needs as dynamic).group;
    if (group is bool) {
      return group;
    }
  } catch (_) {
    // The field is not in this build's model: decide by the shape below.
  }
  return !needs.firstOpen &&
      needs.contact == null &&
      needs.addresses.isEmpty &&
      needs.methods.isNotEmpty &&
      refusal != openLinkRefusal;
}

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

  /// Whether the link is a group link (#211): one address for all the
  /// recipients of a personal link, each proving an address of theirs once,
  /// see [isGroupLink].
  final bool group;

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
    this.group = false,
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
    super.dispose();
  }

  IdentifyRequired get _identify => widget.identify;

  /// Whether the link is an open personal link: no recipient's own link.
  bool get _open => _identify.contact == null && !widget.group;

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
    var label = _identify.label.trim();
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
                  label.isEmpty ? l10n.sharedAlbumFallback : label,
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
    if (methods.contains(mailCodeMethod)) {
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
    return [
      Text(l10n.identifyWhoTitle,
          style: Theme.of(context).textTheme.titleMedium),
      const SizedBox(height: 8),
      Text(widget.group
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
        TextField(
          key: const Key("identify-address"),
          controller: _address,
          enabled: !_busy,
          keyboardType: TextInputType.emailAddress,
          decoration: InputDecoration(labelText: l10n.identifyAddressLabel),
        ),
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
}
