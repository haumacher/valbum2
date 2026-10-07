/// How a contact of a personal link is recognised on their other devices:
/// passkeys (issue #204) and an authenticator app (issue #208).
///
/// A contact who came in through their own link has a credential in this
/// browser; on another device the link asks who they are. Besides a mailed
/// code and a provider of OpenID Connect, both of which need an address, a
/// contact may set up a **passkey** ("Recognise me on my other devices",
/// where the server has a public address and the browser has passkeys) and
/// an **authenticator app** here — offered after the first open (a banner,
/// [SignInOfferBanner]) and in the contact's own menu ("Sign-in options…",
/// [SignInOptionsDialog]), never as the default.
///
/// The setup needs no camera: on a phone ([totpSetupIsWide] false) the
/// dialog offers "Add to authenticator app" — the `otpauth://` link, which
/// opens the app on the same phone — and "Show setup key", the secret in
/// groups of four with a copy button for the app's "Enter a setup key"; on a
/// computer it shows the QR code of the same link with the key below it.
/// The app is active only after one of its codes was confirmed.
///
/// Every request is refused offline first, and a refusal is said in the
/// server's own words.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';

import 'client.dart';
import 'contact_session.dart';
import 'form_dialog.dart';
import 'l10n/app_localizations.dart';
import 'manage_view.dart' show DeviceCodeQr, dayOf;
import 'identify_view.dart' show oidcMethodPrefix, usePasskey;
import 'offline.dart';
import 'passkeys.dart';
import 'resource.dart';
import 'settings.dart' show defaultDeviceName;
import 'sign_in_form.dart'
    show
        leaveForProvider,
        memberSignInStore,
        pageLocation,
        providerReturnRefusal;

/// Opens an app link such as `otpauth://` in place: on the web the page
/// navigates to it, so that the system hands it to the app that registered
/// the scheme. A test replaces it.
Future<bool> Function(Uri url) openAppLink =
    (url) => launchUrl(url, webOnlyWindowName: "_self");

/// Whether the setup of an authenticator app is laid out for a computer: the
/// QR code first. A narrow screen is a phone, which cannot scan itself.
bool totpSetupIsWide(BuildContext context) =>
    MediaQuery.sizeOf(context).width >= 600;

/// The number of digits a code of an authenticator app has.
const int totpCodeDigits = 6;

/// What a field of an authenticator app's code keeps of what is typed or
/// pasted (issue #233): its digits — the app shows them as `123 456` —, at
/// most [totpCodeDigits] of them.
final TextInputFormatter totpCodeFormatter =
    TextInputFormatter.withFunction((oldValue, newValue) {
  var digits = newValue.text.replaceAll(RegExp("[^0-9]"), "");
  if (digits.length > totpCodeDigits) {
    digits = digits.substring(0, totpCodeDigits);
  }
  if (digits == newValue.text) {
    return newValue;
  }
  return TextEditingValue(
    text: digits,
    selection: TextSelection.collapsed(offset: digits.length),
  );
});

/// Whether [text] is a whole code of an authenticator app.
bool isTotpCode(String text) =>
    RegExp("^[0-9]{$totpCodeDigits}\$").hasMatch(text.trim());

/// The setup key in groups of four, as authenticator apps show it.
String groupedSetupKey(String secret) {
  var groups = <String>[];
  for (var start = 0; start < secret.length; start += 4) {
    groups.add(secret.substring(
        start, start + 4 > secret.length ? secret.length : start + 4));
  }
  return groups.join(" ");
}

/// Whether a contact session described by [info] is offered to set up a
/// way to be recognised elsewhere: a contact who has none yet.
bool offersSignInOptions(ShareInfo info) {
  var signIns = info.signIns;
  return info.contact != null &&
      signIns != null &&
      signIns.authenticator.isEmpty &&
      signIns.passkeys.isEmpty;
}

/// The banner of the offer after the first open: "Set up" and "Not now".
class SignInOfferBanner extends StatelessWidget {
  final VoidCallback onOpen;

  final VoidCallback onDismiss;

  const SignInOfferBanner({
    super.key,
    required this.onOpen,
    required this.onDismiss,
  });

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return MaterialBanner(
      key: const Key("sign-in-offer"),
      content: Text(l10n.signInOffer),
      leading: const Icon(Icons.devices),
      actions: [
        TextButton(
          key: const Key("sign-in-offer-dismiss"),
          onPressed: onDismiss,
          child: Text(l10n.addEmailNotNow),
        ),
        TextButton(
          key: const Key("sign-in-offer-open"),
          onPressed: onOpen,
          child: Text(l10n.signInOfferOpen),
        ),
      ],
    );
  }
}

/// Opens the contact's sign-in options; answers what they are afterwards,
/// `null` where nothing changed.
Future<ContactSignIns?> showSignInOptions({
  required BuildContext context,
  required VAlbumClient client,
  required ContactSignIns signIns,
  bool member = false,
  List<ProofMethod> proofMethods = const [],
}) =>
    showFormDialog<ContactSignIns>(
      context: context,
      builder: (context) => SignInOptionsDialog(
        client: client,
        signIns: signIns,
        member: member,
        proofMethods: proofMethods,
      ),
    );

/// The contact's own sign-in options: the authenticator app, set up or
/// removed.
class SignInOptionsDialog extends StatefulWidget {
  /// The client of the contact session: the link's token and the contact's
  /// credential.
  final VAlbumClient client;

  /// What the server said the contact has.
  final ContactSignIns signIns;

  /// Whether these are a signed-in member's (issue #233): the same dialog,
  /// with the member's own lead and their e-mail addresses.
  final bool member;

  /// How a member may prove an address here (`mail-code`, `oidc:<id>`), as
  /// `AuthInfo.proofMethods` says; empty for a contact.
  final List<ProofMethod> proofMethods;

  const SignInOptionsDialog({
    super.key,
    required this.client,
    required this.signIns,
    this.member = false,
    this.proofMethods = const [],
  });

  @override
  State<SignInOptionsDialog> createState() => SignInOptionsDialogState();
}

class SignInOptionsDialogState extends State<SignInOptionsDialog> {
  late ContactSignIns _signIns = widget.signIns;

  /// Whether anything changed, so that the caller learns it.
  bool _changed = false;

  /// The authenticator app being set up, `null` while none is.
  TotpSetup? _setup;

  final TextEditingController _code = TextEditingController();

  /// The address a member adds (issue #233), `null` while none is added.
  TextEditingController? _address;

  /// The code mailed to [_address].
  final TextEditingController _addressCode = TextEditingController();

  /// Whether a code was mailed to [_address].
  bool _addressSent = false;

  bool _busy = false;

  String? _error;

  @override
  void dispose() {
    _code.dispose();
    _address?.dispose();
    _addressCode.dispose();
    super.dispose();
  }

  /// Mails a code to the address being added, or confirms it (issue #233).
  Future<void> _addAddress() => _run(() async {
        var address = _address!.text.trim();
        if (!_addressSent) {
          await widget.client.proveEmail(EmailProof(address: address));
          if (mounted) {
            setState(() => _addressSent = true);
          }
          return;
        }
        await widget.client.verifyEmail(
            EmailVerify(address: address, code: _addressCode.text.trim()));
        var devices = await widget.client.devices();
        if (mounted) {
          setState(() {
            _signIns = devices.signIns ?? _signIns;
            _changed = true;
            _address?.dispose();
            _address = null;
            _addressSent = false;
            _addressCode.clear();
          });
        }
      });

  Future<void> _removeAddress(String address) => _run(() async {
        var answer = await widget.client
            .removeSignIn(emailSignInMethod, id: address);
        if (mounted) {
          setState(() {
            _signIns = answer;
            _changed = true;
          });
        }
      });

  /// "Link Google account" (issue #233): the page leaves for the provider and
  /// comes back to the application, which keeps the address it proved.
  Future<void> _linkProvider(ProofMethod method, AppLocalizations l10n) =>
      _run(() async {
        var started = await widget.client.oidcStart(OidcStart(
            provider: method.name.substring(oidcMethodPrefix.length)));
        var refusal = providerReturnRefusal(l10n, started);
        if (refusal != null) {
          throw VAlbumException(refusal);
        }
        memberSignInStore.keepMemberSignIn(PendingMemberSignIn(
          dataUrl: widget.client.dataUrl,
          binding: started.binding,
          deviceName: defaultDeviceName(l10n),
          adding: true,
        ));
        leaveForProvider(started.url);
      });

  void _update(VoidCallback change) => setState(change);

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

  Future<void> _startSetup() => _run(() async {
        var setup = await widget.client.totpSetup();
        if (mounted) {
          setState(() {
            _setup = setup;
            _code.clear();
          });
        }
      });

  Future<void> _confirm() => _run(() async {
        var answer = await widget.client.totpConfirm(_code.text.trim());
        if (mounted) {
          setState(() {
            _signIns = answer;
            _changed = true;
            _setup = null;
          });
        }
      });

  /// Whether passkeys are offered here: by the server, which has a public
  /// address, and by the browser.
  bool get _passkeysOffered =>
      _signIns.passkeysOffered && passkeyAuthenticator != null;

  /// "Recognise me on my other devices": the browser makes a passkey and the
  /// server stores it (issue #204).
  Future<void> _addPasskey(AppLocalizations l10n) => _run(() async {
        var options = await widget.client.passkeyRegisterStart();
        var answer = await usePasskey(
            l10n, () => passkeyAuthenticator!.create(options.options));
        var signIns = await widget.client.passkeyRegister(
            PasskeyResponse(ticket: options.ticket, response: answer));
        if (mounted) {
          setState(() {
            _signIns = signIns;
            _changed = true;
          });
        }
      });

  Future<void> _removePasskey(String id) => _run(() async {
        var answer =
            await widget.client.removeSignIn(passkeySignInMethod, id: id);
        if (mounted) {
          setState(() {
            _signIns = answer;
            _changed = true;
          });
        }
      });

  Future<void> _removeTotp() => _run(() async {
        var answer = await widget.client.removeSignIn(totpSignInMethod);
        if (mounted) {
          setState(() {
            _signIns = answer;
            _changed = true;
          });
        }
      });

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var setup = _setup;
    var since = _signIns.authenticator;
    return FormDialogFrame(
      key: const Key("sign-in-options-dialog"),
      title: Text(l10n.signInOptionsTitle),
      fields: [
        Text(widget.member ? l10n.signInOptionsMemberLead : l10n.signInOptionsLead),
        if (widget.member) ..._addresses(l10n),
        if (_passkeysOffered) ...[
          const SizedBox(height: 16),
          Text(l10n.passkeysHeading,
              style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 4),
          Text(l10n.passkeyExplanation),
          for (var passkey in _signIns.passkeys)
            ListTile(
              key: Key("sign-in-passkey-${passkey.id}"),
              contentPadding: EdgeInsets.zero,
              dense: true,
              leading: const Icon(Icons.key),
              title: Text(l10n.passkeyFrom(dayOf(passkey.created))),
              subtitle: passkey.lastUsed.isEmpty
                  ? null
                  : Text(l10n.passkeyLastUsed(dayOf(passkey.lastUsed))),
              trailing: IconButton(
                key: Key("sign-in-passkey-remove-${passkey.id}"),
                tooltip: l10n.remove,
                icon: const Icon(Icons.delete_outline),
                onPressed: _busy ? null : () => _removePasskey(passkey.id),
              ),
            ),
          const SizedBox(height: 8),
          Align(
            alignment: AlignmentDirectional.centerStart,
            child: OutlinedButton.icon(
              key: const Key("sign-in-passkey-add"),
              onPressed: _busy ? null : () => _addPasskey(l10n),
              icon: const Icon(Icons.key),
              label: Text(l10n.passkeyAdd),
            ),
          ),
        ],
        const SizedBox(height: 16),
        Text(l10n.authenticatorHeading,
            style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 4),
        if (setup != null)
          TotpSetupView(
            setup: setup,
            code: _code,
            busy: _busy,
            onConfirm: _confirm,
          )
        else if (since.isNotEmpty) ...[
          Text(l10n.authenticatorActiveSince(dayOf(since)),
              key: const Key("sign-in-totp-active")),
          Align(
            alignment: AlignmentDirectional.centerStart,
            child: TextButton.icon(
              key: const Key("sign-in-totp-remove"),
              onPressed: _busy ? null : _removeTotp,
              icon: const Icon(Icons.delete_outline),
              label: Text(l10n.remove),
            ),
          ),
        ] else ...[
          Text(l10n.authenticatorExplanation),
          const SizedBox(height: 8),
          Align(
            alignment: AlignmentDirectional.centerStart,
            child: OutlinedButton.icon(
              key: const Key("sign-in-totp-setup"),
              onPressed: _busy ? null : _startSetup,
              icon: const Icon(Icons.pin_outlined),
              label: Text(l10n.authenticatorSetUp),
            ),
          ),
        ],
        if (_error != null) ...[
          const SizedBox(height: 12),
          Text(
            _error!,
            key: const Key("sign-in-options-error"),
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        ],
      ],
      actions: [
        TextButton(
          key: const Key("sign-in-options-close"),
          onPressed: _busy
              ? null
              : () => Navigator.of(context).pop(_changed ? _signIns : null),
          child: Text(l10n.close),
        ),
      ],
    );
  }
}

extension on SignInOptionsDialogState {
  /// A member's e-mail addresses (issue #233): each signs them in by a mailed
  /// code or a provider; added by a proof, removed here.
  List<Widget> _addresses(AppLocalizations l10n) {
    var mail = widget.proofMethods.any((method) => method.name == "mail-code");
    var providers = widget.proofMethods
        .where((method) => method.name.startsWith(oidcMethodPrefix))
        .toList();
    var address = _address;
    // Offered only where this server can prove an address — a mail account,
    // a provider in the browser — as passkeys are only where it has them.
    // Addresses proven before stay listed, to be removed.
    var offered = mail || providers.isNotEmpty && pageLocation() != null;
    if (!offered && _signIns.addresses.isEmpty) {
      return const [];
    }
    return [
      const SizedBox(height: 16),
      Text(l10n.memberAddressesHeading,
          style: Theme.of(context).textTheme.titleSmall),
      const SizedBox(height: 4),
      if (offered) Text(l10n.memberAddressesExplanation),
      for (var entry in _signIns.addresses)
        ListTile(
          key: Key("sign-in-address-${entry.value}"),
          contentPadding: EdgeInsets.zero,
          dense: true,
          leading: const Icon(Icons.alternate_email),
          title: Text(entry.value),
          trailing: IconButton(
            key: Key("sign-in-address-remove-${entry.value}"),
            tooltip: l10n.remove,
            icon: const Icon(Icons.delete_outline),
            onPressed: _busy ? null : () => _removeAddress(entry.value),
          ),
        ),
      if (address != null) ...[
        TextField(
          key: const Key("sign-in-address-field"),
          controller: address,
          enabled: !_busy && !_addressSent,
          keyboardType: TextInputType.emailAddress,
          decoration: InputDecoration(labelText: l10n.identifyAddressLabel),
        ),
        if (_addressSent)
          TextField(
            key: const Key("sign-in-address-code"),
            controller: _addressCode,
            enabled: !_busy,
            keyboardType: TextInputType.number,
            decoration: InputDecoration(labelText: l10n.identifyCodeLabel),
          ),
        const SizedBox(height: 8),
        Align(
          alignment: AlignmentDirectional.centerEnd,
          child: FilledButton(
            key: const Key("sign-in-address-send"),
            onPressed: _busy ? null : _addAddress,
            child: Text(_addressSent
                ? l10n.identifyConfirmCode
                : l10n.identifySendCode),
          ),
        ),
      ],
      const SizedBox(height: 8),
      Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          if (mail && address == null)
            OutlinedButton.icon(
              key: const Key("sign-in-address-add"),
              onPressed: _busy
                  ? null
                  : () => _update(() => _address = TextEditingController()),
              icon: const Icon(Icons.alternate_email),
              label: Text(l10n.memberAddAddress),
            ),
          for (var method in providers)
            if (pageLocation() != null)
              OutlinedButton(
              key: Key("sign-in-link-${method.name}"),
              onPressed: _busy ? null : () => _linkProvider(method, l10n),
              child: Text(l10n.memberLinkProvider(method.label.isEmpty
                  ? method.name.substring(oidcMethodPrefix.length)
                  : method.label)),
            ),
        ],
      ),
    ];
  }
}

/// The method name of a member's e-mail address in a [SignInRemove].
const String emailSignInMethod = "email";

/// The method name of an authenticator app in a [SignInRemove].
const String totpSignInMethod = "totp";

/// The method name of a passkey in a [SignInRemove].
const String passkeySignInMethod = "passkey";

/// The setup of an authenticator app: the secret offered the way the device
/// can take it, then the field for one code of it.
class TotpSetupView extends StatefulWidget {
  final TotpSetup setup;

  /// The field the code is typed into.
  final TextEditingController code;

  final bool busy;

  final VoidCallback onConfirm;

  const TotpSetupView({
    super.key,
    required this.setup,
    required this.code,
    required this.busy,
    required this.onConfirm,
  });

  @override
  State<TotpSetupView> createState() => _TotpSetupViewState();
}

class _TotpSetupViewState extends State<TotpSetupView> {
  /// Whether the phone layout shows the setup key.
  bool _keyShown = false;

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var setup = widget.setup;
    var wide = totpSetupIsWide(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        if (wide) ...[
          Text(l10n.totpScan),
          const SizedBox(height: 8),
          Center(
            child: DeviceCodeQr(
              key: const Key("totp-qr"),
              payload: setup.uri,
              size: 180,
              semanticsLabel: l10n.totpQrSemantics,
            ),
          ),
          const SizedBox(height: 8),
          Text(l10n.totpOrEnterKey),
          _key(l10n),
        ] else ...[
          Text(l10n.totpOnThisPhone),
          const SizedBox(height: 8),
          FilledButton.icon(
            key: const Key("totp-open-app"),
            onPressed:
                widget.busy ? null : () => openAppLink(Uri.parse(setup.uri)),
            icon: const Icon(Icons.open_in_new),
            label: Text(l10n.totpAddToApp),
          ),
          const SizedBox(height: 4),
          Text(l10n.totpLinkNote),
          if (_keyShown)
            _key(l10n)
          else
            Align(
              alignment: AlignmentDirectional.centerStart,
              child: TextButton(
                key: const Key("totp-show-key"),
                onPressed: () => setState(() => _keyShown = true),
                child: Text(l10n.totpShowKey),
              ),
            ),
        ],
        const SizedBox(height: 12),
        Text(l10n.totpEnterCode),
        TextField(
          key: const Key("totp-code"),
          controller: widget.code,
          enabled: !widget.busy,
          keyboardType: TextInputType.number,
          inputFormatters: [totpCodeFormatter],
          decoration: InputDecoration(labelText: l10n.totpCodeLabel),
          onChanged: (_) => setState(() {}),
          onSubmitted: widget.busy || !isTotpCode(widget.code.text)
              ? null
              : (_) => widget.onConfirm(),
        ),
        const SizedBox(height: 8),
        Align(
          alignment: AlignmentDirectional.centerEnd,
          child: FilledButton(
            key: const Key("totp-confirm"),
            onPressed: widget.busy || !isTotpCode(widget.code.text)
                ? null
                : widget.onConfirm,
            child: Text(l10n.identifyConfirmCode),
          ),
        ),
      ],
    );
  }

  /// The setup key in groups of four, selectable, with a copy button.
  Widget _key(AppLocalizations l10n) => Row(
        children: [
          Expanded(
            child: SelectableText(
              groupedSetupKey(widget.setup.secret),
              key: const Key("totp-setup-key"),
              style: const TextStyle(
                  fontFamily: "monospace",
                  fontSize: 16,
                  fontWeight: FontWeight.w600),
            ),
          ),
          IconButton(
            key: const Key("totp-copy-key"),
            tooltip: l10n.copy,
            icon: const Icon(Icons.copy),
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: widget.setup.secret));
              if (mounted) {
                ScaffoldMessenger.maybeOf(context)
                    ?.showSnackBar(SnackBar(content: Text(l10n.totpKeyCopied)));
              }
            },
          ),
        ],
      );
}
