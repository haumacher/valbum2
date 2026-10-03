/// "Add your e-mail so we recognise you on other devices" (issues #202, #211).
///
/// A contact who came in through their own link's first open (#198) has a
/// credential in this browser and nothing else: on another device the link
/// would ask who they are and offer no way to prove it. Where the server can
/// mail a code (`ShareInfo.methods` names `mail-code`) and the contact has no
/// e-mail address saved (`ShareInfo.contactHasEmail`), the session offers to
/// add one — a typed address, `?action=prove-email`, the code, then
/// `?action=verify-email`, the recognised-contact path of #199, which adds
/// the address and keeps the credential the session already has.
///
/// The offer is a banner over the session, shown once per browser and space:
/// dismissing it is remembered (`ContactCredentialStore.dismissEmailOffer`).
/// Every request is refused offline first, and a refusal is said in the
/// server's own words.
library;

import 'package:flutter/material.dart';

import 'client.dart';
import 'form_dialog.dart';
import 'identify_view.dart' show mailCodeMethod;
import 'l10n/app_localizations.dart';
import 'offline.dart';
import 'resource.dart';

/// Whether a contact session described by [info] is offered to add an
/// e-mail address: a contact, none saved yet, and a server that can mail a
/// code to prove one.
bool offersAddEmail(ShareInfo info) =>
    info.contact != null &&
    !info.contactHasEmail &&
    info.methods.any((method) => method.name == mailCodeMethod);

/// The banner of the offer: what it is for, "Add e-mail" and "Not now".
class AddEmailBanner extends StatelessWidget {
  /// Opens the dialog that adds the address.
  final VoidCallback onAdd;

  /// Dismisses the offer for good in this browser.
  final VoidCallback onDismiss;

  const AddEmailBanner({
    super.key,
    required this.onAdd,
    required this.onDismiss,
  });

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return MaterialBanner(
      key: const Key("add-email-offer"),
      content: Text(l10n.addEmailOffer),
      leading: const Icon(Icons.alternate_email),
      actions: [
        TextButton(
          key: const Key("add-email-dismiss"),
          onPressed: onDismiss,
          child: Text(l10n.addEmailNotNow),
        ),
        TextButton(
          key: const Key("add-email-open"),
          onPressed: onAdd,
          child: Text(l10n.addEmailOpen),
        ),
      ],
    );
  }
}

/// Opens the dialog adding an e-mail address to the contact of [client]'s
/// session; answers the [ContactCredential] the server answered, `null`
/// where the dialog was cancelled.
Future<ContactCredential?> addEmail({
  required BuildContext context,
  required VAlbumClient client,
}) =>
    showFormDialog<ContactCredential>(
      context: context,
      builder: (context) => AddEmailDialog(client: client),
    );

/// The dialog of the offer: the address, then the code mailed to it.
class AddEmailDialog extends StatefulWidget {
  /// The client of the contact session: the link's token and the contact's
  /// credential.
  final VAlbumClient client;

  const AddEmailDialog({super.key, required this.client});

  @override
  State<AddEmailDialog> createState() => AddEmailDialogState();
}

class AddEmailDialogState extends State<AddEmailDialog> {
  final TextEditingController _address = TextEditingController();
  final TextEditingController _code = TextEditingController();

  /// The address the code was sent to as typed, `null` before it was sent.
  String? _sentFor;

  /// The address the server says the code went to, masked.
  String _sentTo = "";

  /// Whether a request is running.
  bool _busy = false;

  /// The server's reason for the last refusal.
  String? _error;

  @override
  void dispose() {
    _address.dispose();
    _code.dispose();
    super.dispose();
  }

  /// Runs one request, refused offline and its refusal shown in the
  /// server's words.
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

  Future<void> _send() {
    var address = _address.text.trim();
    return _run(() async {
      var sent = await widget.client.proveEmail(EmailProof(address: address));
      if (mounted) {
        setState(() {
          _sentFor = address;
          _sentTo = sent.address.isEmpty ? address : sent.address;
          _code.clear();
        });
      }
    });
  }

  Future<void> _verify() => _run(() async {
        var answer = await widget.client.verifyEmail(EmailVerify(
          address: _sentFor!,
          code: _code.text.trim(),
        ));
        if (mounted) {
          Navigator.of(context).pop(answer);
        }
      });

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var sent = _sentFor != null;
    return FormDialogFrame(
      key: const Key("add-email-dialog"),
      title: Text(l10n.addEmailTitle),
      fields: [
        Text(l10n.addEmailExplanation),
        const SizedBox(height: 8),
        TextField(
          key: const Key("add-email-address"),
          controller: _address,
          autofocus: true,
          enabled: !_busy,
          keyboardType: TextInputType.emailAddress,
          decoration: InputDecoration(labelText: l10n.identifyAddressLabel),
          // Another address than the code was sent to starts over: a code
          // proves the address it went to and no other.
          onChanged: (value) => setState(() {
            if (_sentFor != null && value.trim() != _sentFor) {
              _sentFor = null;
            }
          }),
        ),
        if (sent) ...[
          const SizedBox(height: 12),
          Text(
            l10n.identifyCodeSent(_sentTo),
            key: const Key("add-email-sent"),
          ),
          TextField(
            key: const Key("add-email-code"),
            controller: _code,
            enabled: !_busy,
            keyboardType: TextInputType.number,
            decoration: InputDecoration(labelText: l10n.identifyCodeLabel),
            onChanged: (_) => setState(() {}),
          ),
        ],
        if (_error != null) ...[
          const SizedBox(height: 12),
          Text(
            _error!,
            key: const Key("add-email-error"),
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        ],
      ],
      actions: [
        TextButton(
          key: const Key("add-email-cancel"),
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: Text(l10n.cancel),
        ),
        if (sent)
          TextButton(
            key: const Key("add-email-send-again"),
            onPressed: _busy || _address.text.trim().isEmpty ? null : _send,
            child: Text(l10n.identifySendCode),
          ),
        ElevatedButton(
          key: Key(sent ? "add-email-verify" : "add-email-send"),
          onPressed:
              _busy || (sent ? _code.text.trim() : _address.text.trim()).isEmpty
                  ? null
                  : (sent ? _verify : _send),
          child: Text(sent ? l10n.identifyConfirmCode : l10n.identifySendCode),
        ),
      ],
    );
  }
}
