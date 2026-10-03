/// Choosing who a personal share link goes to (issues #201 and #202).
///
/// One widget for the web and the app, decided with the author on 2026-10-02:
///
///  1. the space's contacts (`?type=contacts`, every member sees all of them)
///     under a search field that narrows them by name and address, each
///     ticked to add it;
///  2. **"New contact"** at the end: a Name and an E-mail field. Pasting the
///     full form `Tante Petra <petra@gmx.de>` (or `"Müller, Petra" <…>`) into
///     either fills both, and a pasted list — a mail program's "To:" line,
///     separated by commas or semicolons — becomes one new contact per
///     address, each shown with its two fields to check;
///  3. an address that already belongs to a contact is not entered twice:
///     the chooser says "already in your contacts as …" and ticks that
///     contact instead; a name left empty is named by the address on the
///     server;
///  4. on a phone (issue #201, the author's decision of 2026-10-03), **"E-mail
///     address from my contacts…"** and **"Phone number from my contacts…"**:
///     the system's own picker hands back one address or number with the
///     contact's name, and no contacts permission is held (see
///     `phone_contacts.dart`). The pick becomes a new recipient with that
///     name and address — unless a space contact already holds the address,
///     which is then ticked as in 3. Picking again for the **same name**
///     (ignoring case) adds the address to that recipient instead of making a
///     second one, so "Oma" picked once by e-mail and once by phone is one
///     contact with both; a pick without a name is a recipient of its own.
///
/// What comes out is a list of [ShareRecipient]s — an existing contact by
/// its id, a new one by name and address — handed to [onChanged] on every
/// change. The server enters the new ones when the link is created (#198).
library;

import 'package:flutter/material.dart';

import 'client.dart';
import 'l10n/app_localizations.dart';
import 'phone_contacts.dart';
import 'photo_library.dart' show platformErrorText;
import 'resource.dart';

/// One address of a pasted line: the name before it (empty where none was
/// given) and the bare address.
typedef ParsedAddress = ({String name, String address});

/// Whether [text] is shaped like an e-mail address: something, one `@`,
/// a dotted domain, no blanks. The server normalises and checks it again.
bool isEmailAddress(String text) =>
    RegExp(r"^[^@\s<>,;]+@[^@\s<>,;]+\.[^@\s<>,;]+$").hasMatch(text);

/// The addresses of a pasted line: `Tante Petra <petra@gmx.de>`,
/// `"Müller, Petra" <petra@gmx.de>`, a bare `petra@gmx.de`, or a list of
/// those separated by commas or semicolons (the "To:" line of a mail
/// program). A comma inside quotes or angle brackets separates nothing; a
/// piece that holds no address is dropped.
List<ParsedAddress> parseAddressList(String text) {
  var pieces = <String>[];
  var current = StringBuffer();
  var quoted = false;
  var bracketed = false;
  for (var char in text.split("")) {
    if (char == '"') {
      quoted = !quoted;
    } else if (char == "<" && !quoted) {
      bracketed = true;
    } else if (char == ">" && !quoted) {
      bracketed = false;
    } else if ((char == "," || char == ";") && !quoted && !bracketed) {
      pieces.add(current.toString());
      current = StringBuffer();
      continue;
    }
    current.write(char);
  }
  pieces.add(current.toString());
  var result = <ParsedAddress>[];
  var full = RegExp(r"^(.*)<([^<>]*)>\s*$", dotAll: true);
  for (var piece in pieces) {
    var trimmed = piece.trim();
    if (trimmed.isEmpty) {
      continue;
    }
    var match = full.firstMatch(trimmed);
    if (match != null) {
      var name = match.group(1)!.trim();
      if (name.length >= 2 && name.startsWith('"') && name.endsWith('"')) {
        name = name.substring(1, name.length - 1).trim();
      }
      var address = match.group(2)!.trim();
      if (isEmailAddress(address)) {
        result.add((name: name, address: address));
      }
    } else if (isEmailAddress(trimmed)) {
      result.add((name: "", address: trimmed));
    }
  }
  return result;
}

/// Whether [text] is more than one address being typed: the full form or a
/// list, which the chooser takes apart, see [parseAddressList].
bool _isPasted(String text) =>
    text.contains("<") || text.contains(",") || text.contains(";");

/// A new contact being entered: its two fields.
class _Draft {
  final TextEditingController name;
  final TextEditingController email;

  _Draft({String name = "", String email = ""})
      : name = TextEditingController(text: name),
        email = TextEditingController(text: email);

  void dispose() {
    name.dispose();
    email.dispose();
  }
}

/// A recipient picked out of the phone's address book: the name it gave and
/// every address picked for that name.
class _Picked {
  final String name;
  final List<ContactAddress> addresses;

  _Picked(this.name, this.addresses);
}

/// What an address is compared by: an e-mail address ignoring case, a phone
/// number by its digits and a leading `+` (`00` read as `+`), so
/// `+49 170 12345` meets the `+4917012345` the server stores.
String _addressKey(AddressKind kind, String value) {
  var trimmed = value.trim().toLowerCase();
  if (kind != AddressKind.phone) {
    return trimmed;
  }
  var digits = trimmed.replaceAll(RegExp(r"[^0-9+]"), "");
  return digits.startsWith("00") ? "+${digits.substring(2)}" : digits;
}

/// The chooser of a personal link's recipients, see the library.
class RecipientChooser extends StatefulWidget {
  final VAlbumClient client;

  /// Told the recipients on every change.
  final ValueChanged<List<ShareRecipient>> onChanged;

  /// Whether the chooser takes input; false while the link is being made.
  final bool enabled;

  /// The phone's address book to pick from, `null` where there is none (the
  /// web, the desktop): the two entries of issue #201 are then not offered.
  final PhoneContacts? phoneContacts;

  const RecipientChooser({
    super.key,
    required this.client,
    required this.onChanged,
    this.enabled = true,
    this.phoneContacts,
  });

  @override
  State<RecipientChooser> createState() => RecipientChooserState();
}

class RecipientChooserState extends State<RecipientChooser> {
  /// The space's contacts, `null` while they are read.
  List<Contact>? _contacts;

  /// Why the contacts could not be read, in the server's words.
  String? _error;

  final TextEditingController _search = TextEditingController();

  /// The ids of the ticked contacts.
  final Set<String> _ticked = {};

  /// The new contacts being entered.
  final List<_Draft> _drafts = [];

  /// The recipients picked out of the phone's address book.
  final List<_Picked> _picked = [];

  /// Why the phone's picker could not be opened, `null` while it could.
  String? _pickError;

  /// The last "already in your contacts as …", `null` while there is none.
  String? _already;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _search.dispose();
    for (var draft in _drafts) {
      draft.dispose();
    }
    super.dispose();
  }

  Future<void> _load() async {
    try {
      var answer = await widget.client.contacts();
      if (mounted) {
        setState(() => _contacts = answer.contacts);
      }
    } catch (error) {
      if (mounted) {
        setState(() {
          _contacts = const [];
          _error = error is VAlbumException ? error.message : "$error";
        });
      }
    }
  }

  /// The contacts the search narrows to: by name, by the contact's own
  /// name and by address, ignoring case. A ticked contact stays in sight.
  List<Contact> get _shown {
    var query = _search.text.trim().toLowerCase();
    return [
      for (var contact in _contacts ?? const <Contact>[])
        if (_ticked.contains(contact.id) ||
            query.isEmpty ||
            contact.name.toLowerCase().contains(query) ||
            contact.displayName.toLowerCase().contains(query) ||
            contact.addresses
                .any((address) => address.value.toLowerCase().contains(query)))
          contact,
    ];
  }

  /// The contact holding [address] (an e-mail address unless [kind] says
  /// otherwise), `null` where none does.
  Contact? _holding(String address, [AddressKind kind = AddressKind.email]) {
    var wanted = _addressKey(kind, address);
    for (var contact in _contacts ?? const <Contact>[]) {
      if (contact.addresses.any((held) =>
          held.kind == kind && _addressKey(kind, held.value) == wanted)) {
        return contact;
      }
    }
    return null;
  }

  /// The recipients as they stand, see [RecipientChooser.onChanged].
  List<ShareRecipient> get recipients => [
        for (var id in _ticked) ShareRecipient(contact: id),
        for (var draft in _drafts)
          if (isEmailAddress(draft.email.text.trim()))
            ShareRecipient(
              name: draft.name.text.trim(),
              addresses: [
                ContactAddress(
                  kind: AddressKind.email,
                  value: draft.email.text.trim(),
                ),
              ],
            ),
        for (var picked in _picked)
          ShareRecipient(name: picked.name, addresses: List.of(picked.addresses)),
      ];

  void _changed() => widget.onChanged(recipients);

  void _tick(String id, bool ticked) {
    setState(() {
      if (ticked) {
        _ticked.add(id);
      } else {
        _ticked.remove(id);
      }
    });
    _changed();
  }

  void _addDraft() {
    setState(() => _drafts.add(_Draft()));
    _changed();
  }

  void _removeDraft(_Draft draft) {
    setState(() {
      _drafts.remove(draft);
      draft.dispose();
    });
    _changed();
  }

  /// Opens the phone's picker for one address of [kind], see the library.
  Future<void> _pick(AddressKind kind) async {
    var contacts = widget.phoneContacts;
    if (contacts == null) {
      return;
    }
    PickedAddress? picked;
    try {
      picked = await contacts.pick(kind);
    } catch (error) {
      if (mounted) {
        setState(() => _pickError = platformErrorText(error));
      }
      return;
    }
    if (!mounted) {
      return;
    }
    if (picked == null) {
      setState(() => _pickError = null);
      return;
    }
    _took(picked);
  }

  /// [picked] becomes a recipient, see the library.
  void _took(PickedAddress picked) {
    var address = picked.address;
    var holder = _holding(address.value, address.kind);
    setState(() {
      _pickError = null;
      if (holder != null) {
        _ticked.add(holder.id);
        _already = holder.name;
        return;
      }
      var name = picked.name.trim();
      var same = name.isEmpty
          ? null
          : _picked
              .where((other) => other.name.toLowerCase() == name.toLowerCase())
              .firstOrNull;
      if (same == null) {
        _picked.add(_Picked(name, [address]));
      } else if (!same.addresses.any((held) =>
          held.kind == address.kind &&
          _addressKey(held.kind, held.value) ==
              _addressKey(address.kind, address.value))) {
        same.addresses.add(address);
      }
    });
    _changed();
  }

  void _removePicked(_Picked picked) {
    setState(() => _picked.remove(picked));
    _changed();
  }

  /// A field of [draft] changed: a pasted full form or list is taken apart
  /// into drafts of their own, and an address a contact holds ticks it.
  void _edited(_Draft draft, String text) {
    var at = _drafts.indexOf(draft);
    if (at < 0) {
      return;
    }
    var replacement = <_Draft>[draft];
    if (_isPasted(text)) {
      var parsed = parseAddressList(text);
      if (parsed.isNotEmpty) {
        draft.name.text = parsed.first.name;
        draft.email.text = parsed.first.address;
        for (var more in parsed.skip(1)) {
          replacement.add(_Draft(name: more.name, email: more.address));
        }
      }
    }
    var kept = <_Draft>[];
    String? already;
    for (var candidate in replacement) {
      var holder = _holding(candidate.email.text);
      if (holder != null) {
        _ticked.add(holder.id);
        already = holder.name;
        if (candidate != draft) {
          candidate.dispose();
        }
        continue;
      }
      kept.add(candidate);
    }
    setState(() {
      _drafts.removeAt(at);
      _drafts.insertAll(at, kept);
      if (!kept.contains(draft)) {
        draft.dispose();
      }
      _already = already ?? _already;
    });
    _changed();
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var contacts = _contacts;
    var shown = _shown;
    var enabled = widget.enabled;
    return Column(
      key: const Key("recipient-chooser"),
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        TextField(
          key: const Key("recipient-search"),
          controller: _search,
          enabled: enabled,
          decoration: InputDecoration(
            isDense: true,
            prefixIcon: const Icon(Icons.search),
            hintText: l10n.recipientsSearch,
          ),
          onChanged: (_) => setState(() {}),
        ),
        if (contacts == null)
          const Padding(
            padding: EdgeInsets.all(8),
            child: Center(child: CircularProgressIndicator()),
          )
        else if (_error != null)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text(_error!, key: const Key("recipient-contacts-error")),
          )
        else if (contacts.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text(l10n.recipientsNoContacts),
          )
        else if (shown.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text(l10n.recipientsNoMatch,
                key: const Key("recipient-no-match")),
          ),
        for (var contact in shown)
          CheckboxListTile(
            key: Key("recipient-contact-${contact.id}"),
            dense: true,
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            value: _ticked.contains(contact.id),
            title: Text(contact.name),
            subtitle: contact.addresses.isEmpty
                ? null
                : Text(
                    [for (var address in contact.addresses) address.value]
                        .join(", "),
                    overflow: TextOverflow.ellipsis,
                  ),
            onChanged:
                enabled ? (value) => _tick(contact.id, value ?? false) : null,
          ),
        if (_already != null)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Text(
              l10n.newContactAlready(_already!),
              key: const Key("recipient-already"),
            ),
          ),
        for (var i = 0; i < _drafts.length; i++) _draftRow(l10n, i),
        ListTile(
          key: const Key("recipient-new-contact"),
          dense: true,
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.person_add_alt),
          title: Text(l10n.newContact),
          enabled: enabled,
          onTap: _addDraft,
        ),
        for (var i = 0; i < _picked.length; i++) _pickedRow(l10n, i),
        if (widget.phoneContacts != null) ...[
          ListTile(
            key: const Key("recipient-pick-email"),
            dense: true,
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.contact_mail_outlined),
            title: Text(l10n.recipientsPickEmail),
            enabled: enabled,
            onTap: () => _pick(AddressKind.email),
          ),
          ListTile(
            key: const Key("recipient-pick-phone"),
            dense: true,
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.contact_phone_outlined),
            title: Text(l10n.recipientsPickPhone),
            enabled: enabled,
            onTap: () => _pick(AddressKind.phone),
          ),
          if (_pickError != null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Text(
                l10n.recipientsPickFailed(_pickError!),
                key: const Key("recipient-pick-error"),
              ),
            ),
        ],
      ],
    );
  }

  /// The [i]th recipient picked out of the phone's address book: its name
  /// and every address picked for it.
  Widget _pickedRow(AppLocalizations l10n, int i) {
    var picked = _picked[i];
    var addresses = [for (var address in picked.addresses) address.value];
    return ListTile(
      key: Key("recipient-picked-$i"),
      dense: true,
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.person_outline),
      title: Text(picked.name.isEmpty ? addresses.first : picked.name),
      subtitle: Text(addresses.join(", "), overflow: TextOverflow.ellipsis),
      trailing: IconButton(
        key: Key("recipient-picked-remove-$i"),
        icon: const Icon(Icons.close),
        tooltip: l10n.newContactRemove,
        onPressed: widget.enabled ? () => _removePicked(picked) : null,
      ),
    );
  }

  /// The two fields of the [i]th new contact.
  Widget _draftRow(AppLocalizations l10n, int i) {
    var draft = _drafts[i];
    var email = draft.email.text.trim();
    var invalid = email.isNotEmpty && !isEmailAddress(email);
    return Padding(
      key: ObjectKey(draft),
      padding: const EdgeInsets.only(top: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: TextField(
              key: Key("recipient-new-name-$i"),
              controller: draft.name,
              enabled: widget.enabled,
              decoration: InputDecoration(
                isDense: true,
                labelText: l10n.newContactName,
              ),
              onChanged: (text) => _edited(draft, text),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              key: Key("recipient-new-email-$i"),
              controller: draft.email,
              enabled: widget.enabled,
              keyboardType: TextInputType.emailAddress,
              decoration: InputDecoration(
                isDense: true,
                labelText: l10n.newContactEmail,
                errorText: invalid ? l10n.newContactInvalid : null,
              ),
              onChanged: (text) => _edited(draft, text),
            ),
          ),
          IconButton(
            key: Key("recipient-new-remove-$i"),
            icon: const Icon(Icons.close),
            tooltip: l10n.newContactRemove,
            onPressed: widget.enabled ? () => _removeDraft(draft) : null,
          ),
        ],
      ),
    );
  }
}
