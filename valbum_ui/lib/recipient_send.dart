/// Sending each recipient of a personal link their own link (issues #201 and
/// #202).
///
/// The server sends nothing (#195): the sharer's own mail program, chat or
/// share sheet carries the link, so that it comes from somebody the recipient
/// knows. After a personal link is created there is one block per recipient,
/// one line per way to reach them:
///
///  * "E-mail to petra@gmx.de" → `mailto:` with the bare address (a display
///    name in `mailto:` is not portable), a translated subject and that
///    recipient's own link in the body — on the web and in the app;
///  * "WhatsApp to +49…" → `https://wa.me/<number>?text=…` and "SMS to +49…"
///    → `sms:`, in the app;
///  * "Other app…" → the system share sheet, in the app;
///  * "Copy link", everywhere.
///
/// The text is a short default the sharer edits in their own app.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:share_plus/share_plus.dart';

import 'about.dart' show openExternalUrl;
import 'l10n/app_localizations.dart';
import 'resource.dart';

/// Opens the system share sheet with [text]; a test replaces it.
Future<void> Function(String text, {String? subject}) openShareSheet =
    (text, {subject}) async {
  await SharePlus.instance.share(ShareParams(text: text, subject: subject));
};

/// The `mailto:` of one recipient: the bare [address], [subject] and [body]
/// percent-encoded (a `+` for a blank would stand in the mail as a `+`).
Uri mailtoUri(String address, String subject, String body) => Uri.parse(
      "mailto:$address"
      "?subject=${Uri.encodeComponent(subject)}"
      "&body=${Uri.encodeComponent(body)}",
    );

/// The `mailto:` of a group link (issue #211): every recipient in `bcc`, so
/// that nobody learns the others' addresses, and the one link in the body.
Uri groupMailtoUri(List<String> addresses, String subject, String body) =>
    Uri.parse(
      "mailto:?bcc=${addresses.join(",")}"
      "&subject=${Uri.encodeComponent(subject)}"
      "&body=${Uri.encodeComponent(body)}",
    );

/// The digits WhatsApp's `wa.me` takes: the number without `+`, blanks and
/// punctuation.
String _digitsOf(String number) => number.replaceAll(RegExp(r"[^0-9]"), "");

/// A chat with [number] in the sharer's own WhatsApp, [text] typed in.
Uri whatsAppUri(String number, String text) => Uri.parse(
      "https://wa.me/${_digitsOf(number)}?text=${Uri.encodeComponent(text)}",
    );

/// A message to [number] in the SMS app, [text] typed in.
Uri smsUri(String number, String text) => Uri.parse(
      "sms:${number.replaceAll(RegExp(r"[^0-9+]"), "")}"
      "?body=${Uri.encodeComponent(text)}",
    );

/// The ways to send one recipient their own link, see the library.
class RecipientSendLines extends StatelessWidget {
  /// The recipient and their own link.
  final RecipientLink recipient;

  /// The recipient's own link, absolute.
  final String url;

  /// How the shared album is named in the message.
  final String album;

  /// Whether the page runs in a browser: there the mail program and the
  /// clipboard are the ways, a phone's apps are not at hand.
  final bool isWeb;

  const RecipientSendLines({
    super.key,
    required this.recipient,
    required this.url,
    required this.album,
    required this.isWeb,
  });

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var id = recipient.contact;
    var subject = l10n.shareMessageSubject(album);
    var body = l10n.shareMessageBody(recipient.name, album, url);
    var emails = [
      for (var address in recipient.addresses)
        if (address.kind == AddressKind.email) address.value,
    ];
    var phones = [
      for (var address in recipient.addresses)
        if (address.kind == AddressKind.phone) address.value,
    ];
    return Column(
      key: Key("recipient-link-$id"),
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Text(
            recipient.name,
            style: Theme.of(context).textTheme.titleSmall,
          ),
        ),
        for (var i = 0; i < emails.length; i++)
          _line(
            context,
            Key("send-email-$id-$i"),
            Icons.mail_outline,
            l10n.sendEmailTo(emails[i]),
            () => _launchLink(context, mailtoUri(emails[i], subject, body)),
          ),
        if (!isWeb)
          for (var i = 0; i < phones.length; i++) ...[
            _line(
              context,
              Key("send-whatsapp-$id-$i"),
              Icons.chat_outlined,
              l10n.sendWhatsAppTo(phones[i]),
              () => _launchLink(context, whatsAppUri(phones[i], body)),
            ),
            _line(
              context,
              Key("send-sms-$id-$i"),
              Icons.sms_outlined,
              l10n.sendSmsTo(phones[i]),
              () => _launchLink(context, smsUri(phones[i], body)),
            ),
          ],
        if (!isWeb)
          _line(
            context,
            Key("send-other-$id"),
            Icons.share,
            l10n.sendOtherApp,
            () => openShareSheet(body, subject: subject),
          ),
        _line(
          context,
          Key("send-copy-$id"),
          Icons.copy,
          l10n.copyLinkAction,
          () => _copyLink(context, url),
        ),
      ],
    );
  }

  Widget _line(
    BuildContext context,
    Key key,
    IconData icon,
    String text,
    VoidCallback onTap,
  ) =>
      ListTile(
        key: key,
        dense: true,
        contentPadding: EdgeInsets.zero,
        leading: Icon(icon),
        title: Text(text),
        onTap: onTap,
      );
}

/// The ways to send a group link (issue #211): one mail to every recipient
/// that has an e-mail address, and the link to copy.
class GroupSendLines extends StatelessWidget {
  /// The recipients' e-mail addresses.
  final List<String> addresses;

  /// The group link, absolute.
  final String url;

  /// How the shared album is named in the message.
  final String album;

  const GroupSendLines({
    super.key,
    required this.addresses,
    required this.url,
    required this.album,
  });

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var subject = l10n.shareMessageSubject(album);
    var body = l10n.shareMessageBodyGroup(album, url);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        if (addresses.isNotEmpty)
          ListTile(
            key: const Key("send-group-email"),
            dense: true,
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.mail_outline),
            title: Text(l10n.groupLinkMailAll),
            onTap: () => _launchLink(
                context, groupMailtoUri(addresses, subject, body)),
          ),
        ListTile(
          key: const Key("send-group-copy"),
          dense: true,
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.copy),
          title: Text(l10n.copyLinkAction),
          onTap: () => _copyLink(context, url),
        ),
      ],
    );
  }
}

/// Opens [uri] outside the app, saying so where nothing on the device can.
Future<void> _launchLink(BuildContext context, Uri uri) async {
  var messenger = ScaffoldMessenger.maybeOf(context);
  var failed = AppLocalizations.of(context)!.launchFailed;
  bool opened;
  try {
    opened = await openExternalUrl(uri);
  } catch (_) {
    opened = false;
  }
  if (!opened) {
    messenger?.showSnackBar(SnackBar(content: Text(failed)));
  }
}

/// Copies [url], saying so.
Future<void> _copyLink(BuildContext context, String url) async {
  var messenger = ScaffoldMessenger.maybeOf(context);
  var said = AppLocalizations.of(context)!.linkCopied;
  await Clipboard.setData(ClipboardData(text: url));
  messenger?.showSnackBar(SnackBar(content: Text(said)));
}
