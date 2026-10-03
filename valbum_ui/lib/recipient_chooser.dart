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
///     server.
///
/// What comes out is a list of [ShareRecipient]s — an existing contact by
/// its id, a new one by name and address — handed to [onChanged] on every
/// change. The server enters the new ones when the link is created (#198).
library;

import 'package:flutter/material.dart';

import 'client.dart';
import 'l10n/app_localizations.dart';
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

/// The chooser of a personal link's recipients, see the library.
class RecipientChooser extends StatefulWidget {
  final VAlbumClient client;

  /// Told the recipients on every change.
  final ValueChanged<List<ShareRecipient>> onChanged;

  /// Whether the chooser takes input; false while the link is being made.
  final bool enabled;

  const RecipientChooser({
    super.key,
    required this.client,
    required this.onChanged,
    this.enabled = true,
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

  /// The contact holding [address], `null` where none does.
  Contact? _holding(String address) {
    var wanted = address.trim().toLowerCase();
    for (var contact in _contacts ?? const <Contact>[]) {
      if (contact.addresses.any((held) => held.value.toLowerCase() == wanted)) {
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
      ],
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
