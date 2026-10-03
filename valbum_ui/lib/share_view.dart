/// Share links: the links covering a folder, and the dialog that makes one
/// (issues #51 and #85).
///
/// A link is a token that opens one folder for whoever holds it, with a
/// ceiling on what it shows and an expiry. Since Phase 6 it is the *only* way
/// of sharing left: the grants made out to users and named groups of issue #49
/// — and the "Share with…" dialog that made them — are retired with the space
/// model, where a person is given a permission in a space instead, see
/// issue #85.
///
/// Who may hand out a link is one field of the sign-in answer since issue #83,
/// see [mayShareFolder].
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';

import 'album_edit.dart' show privacyMembers, privacyPublic;
import 'caller.dart';
import 'client.dart';
import 'form_dialog.dart';
import 'l10n/app_localizations.dart';
import 'manage_view.dart' show dayOf;
import 'offline.dart';
import 'phone_contacts.dart';
import 'recipient_chooser.dart';
import 'recipient_send.dart';
import 'resource.dart';
import 'rights.dart';
import 'share_session.dart' show ratingFloorLabel, ratingName;
import 'urls.dart';

/// Whether this caller may hand out a link to the folder at [path]
/// (issues #83/#85).
///
/// Four things, all of them already on the screen: somebody is signed in, the
/// caller holds every right here, the path does not name another user's space,
/// and the caller's own permission allows links at all — that last one is what
/// the server answers with `?type=auth`, see [CallerPermission.mayShare].
///
/// No request of its own any more. Until issue #83 the question had no
/// endpoint and was asked by *trying* `?type=grants`, which is retired with
/// the grants themselves: a permission belongs to a user now, and whether that
/// user may share is one field of the sign-in answer.
bool mayShareFolder(
  VAlbumClient client,
  List<String> path,
  Rights rights, {
  bool mayShare = true,
}) =>
    (client.token ?? "").isNotEmpty &&
    rights.complete &&
    spaceOwnerOf(path) == null &&
    mayShare;

/// How long a new share link lives, as the dialog offers it.
enum LinkExpiry {
  never,
  day,
  week,
  month,
  date;

  /// How the choice is named on the screen.
  String labelOf(AppLocalizations l10n) => switch (this) {
        LinkExpiry.never => l10n.expiryNever,
        LinkExpiry.day => l10n.expiryOneDay,
        LinkExpiry.week => l10n.expiryOneWeek,
        LinkExpiry.month => l10n.expiryOneMonth,
        LinkExpiry.date => l10n.expiryPickDate,
      };

  /// The instant a link created now expires at, `null` if it never does.
  ///
  /// [date] has no instant of its own — the user picks one, see
  /// [ShareLinkDialogState._pickDate].
  DateTime? from(DateTime now) => switch (this) {
        LinkExpiry.never => null,
        LinkExpiry.day => now.add(const Duration(days: 1)),
        LinkExpiry.week => now.add(const Duration(days: 7)),
        LinkExpiry.month => now.add(const Duration(days: 30)),
        LinkExpiry.date => null,
      };
}

/// Who may open a new link, the first question of its form (issues #201 and
/// #202): one dropdown with three plain choices, the author's labels.
enum LinkKind {
  /// "Anyone with the link (anonymous)": the link as it always was, the
  /// default.
  anonymous,

  /// "Anyone with the link (personalized)": an open personal link, whose
  /// visitors prove an address (#199/#200) — offered only where the server
  /// can prove one.
  open,

  /// "Selected contacts": a personal link with recipients, each sent a link
  /// of their own; the only choice showing the recipient chooser.
  selected;

  /// How the choice is named on the screen.
  String labelOf(AppLocalizations l10n) => switch (this) {
        LinkKind.anonymous => l10n.linkTypeAnonymous,
        LinkKind.open => l10n.linkTypeOpenPersonal,
        LinkKind.selected => l10n.linkTypeSelected,
      };
}

/// The three states of the share-link dialog, each with its own buttons.
enum _LinkDialogState { list, form, result }

/// Opens the share-link dialog on the folder at [path], see issue #51.
///
/// Offered exactly where "Share with…" is: a link is a grant whose subject is
/// a token, so the same person manages both. Refused while the app is
/// offline, like every other write.
Future<void> shareLinksOf({
  required BuildContext context,
  required VAlbumClient client,
  required List<String> path,
  String? label,
}) async {
  if (refuseWhileOffline(context)) {
    return;
  }
  // Asked here, where the caller is known: the dialog lies on the root
  // navigator. Nobody said → nothing is withheld on a guess.
  var caller = CallerInfo.maybeOf(context);
  var mayShowMembers = caller == null || caller.permission.seesMembers;
  var mayProveAddresses = caller?.mayProveAddresses ?? false;
  await showFormDialog<void>(
    context: context,
    builder: (context) => ShareLinkDialog(
      client: client,
      path: path,
      label: label,
      mayShowMembers: mayShowMembers,
      mayProveAddresses: mayProveAddresses,
    ),
  );
}

/// Lists, withdraws and creates the share links covering one folder.
///
/// A dialog of its own: a new link asks five questions (label, expiry,
/// privacy ceiling, rating floor, rights), and who is offered it at all is
/// what the caller's own permission says, see [mayShareFolder].
class ShareLinkDialog extends StatefulWidget {
  final VAlbumClient client;

  /// The folder being shared, in the coordinates of the request.
  final List<String> path;

  /// How the folder is named in the title, its last segment by default.
  final String? label;

  /// Whether the dialog runs in a browser, which has no phone apps to send a
  /// recipient's link through, see `recipient_send.dart`.
  final bool isWeb;

  /// Whether the creator may hand out a link showing what members see.
  ///
  /// False for a creator whose own clearance is public: the server refuses a
  /// link above the creator's clearance rather than trimming it (issue #84,
  /// `shareAboveClearance`), so "All photos" is not offered at all.
  final bool mayShowMembers;

  /// Whether the server can prove a visitor's address — by a mailed code
  /// (#199) or a provider of OpenID Connect (#200) — which an open personal
  /// link and a group link need (issues #202, #211): `AuthInfo.proofMethods`
  /// of the one `?type=auth`, see [CallerInfo.mayProveAddresses].
  final bool mayProveAddresses;

  const ShareLinkDialog({
    super.key,
    required this.client,
    required this.path,
    this.label,
    this.mayShowMembers = true,
    this.mayProveAddresses = false,
    this.isWeb = kIsWeb,
  });

  @override
  State<ShareLinkDialog> createState() => ShareLinkDialogState();
}

class ShareLinkDialogState extends State<ShareLinkDialog> {
  /// The links covering the folder, `null` while they are being read.
  List<ShareLink>? _links;

  /// Why the links could not be read, `null` while all is well.
  String? _error;

  /// The server's reason for the last refused request of this dialog.
  String? _refusal;

  /// Whether the form of a new link is open.
  bool _creating = false;

  /// The link that was just created, shown with its token exactly once.
  ShareLinkCreated? _created;

  /// Whether a request of this dialog is running.
  bool _busy = false;

  final TextEditingController _label = TextEditingController();

  LinkExpiry _expiry = LinkExpiry.never;

  /// The day picked for [LinkExpiry.date], `null` while none is picked.
  DateTime? _expiryDate;

  /// The privacy ceiling of the new link.
  ///
  /// Only [privacyPublic] and [privacyMembers] are offered: the server clamps
  /// a link's clearance to `min(members, maxPrivacy)`, so a third choice
  /// would promise something no link ever shows — a private photo is never
  /// handed out through a link. [privacyPublic] by default: a photo is public
  /// unless somebody marked it "members" or "private", so the public photos
  /// are every photo nobody restricted, and a link shows the restricted ones
  /// only where its author asks for them (issue #205, corrected by the
  /// author: the first version defaulted to the members' level and so handed
  /// exactly the photos marked "members only" to whoever holds a link).
  late int _maxPrivacy = privacyPublic;

  /// The rating floor of the new link, the lowest by default: a link shows
  /// what the album holds unless its author says otherwise.
  int _minRating = -2;

  /// The rights the new link carries; `view` is always among them.
  Set<String> _picked = const {rightView};

  /// Who may open the new link, see [LinkKind].
  LinkKind _kind = LinkKind.anonymous;

  /// Whether the server can prove a visitor's address, which an open
  /// personal link and a group link need, see
  /// [ShareLinkDialog.mayProveAddresses].
  bool get _mayProve => widget.mayProveAddresses;

  /// The recipients of a link for [LinkKind.selected].
  List<ShareRecipient> _recipients = const [];

  /// Whether a link for [LinkKind.selected] goes out as one link for the
  /// whole group rather than a link for each person, the default (#211).
  bool _group = false;

  /// Whether the link just created is to be sent as a group link.
  bool _createdAsGroup = false;

  /// The name of the recipient a fresh link was just made for by "Send
  /// again", `null` for a link that was just created.
  String? _resentTo;

  /// The folder's path in the coordinates the links are spelled in.
  String get ownerPath => ownerPathOf(widget.path);

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _label.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      var answer = await widget.client.shares(widget.path);
      if (mounted) {
        setState(() {
          _links = answer.links;
          _error = null;
        });
      }
    } catch (error) {
      if (mounted) {
        setState(() {
          _links = const [];
          _error = error is VAlbumException ? error.message : "$error";
        });
      }
    }
  }

  /// Which of its three states the dialog is in, see [_actions].
  _LinkDialogState get _state => _created != null
      ? _LinkDialogState.result
      : _creating
          ? _LinkDialogState.form
          : _LinkDialogState.list;

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var name = widget.label ??
        (widget.path.isEmpty
            ? l10n.shareTargetTopLevel
            : "'${widget.path.last}'");
    return AlertDialog(
      key: const Key("share-link-dialog"),
      title: Text(l10n.shareDialogTitle(name)),
      content: SizedBox(
        width: 460,
        child: _links == null
            ? const SizedBox(
                height: 120,
                child: Center(child: CircularProgressIndicator()),
              )
            : SingleChildScrollView(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: switch (_state) {
                    _LinkDialogState.result => _createdSection(context),
                    _LinkDialogState.form => _formSection(context),
                    _LinkDialogState.list => [
                        ..._linkSection(context),
                        const Divider(),
                        ..._newLinkTile(),
                      ],
                  },
                ),
              ),
      ),
      // The one button bar of the dialog, set from its state, see
      // `form_dialog.dart` and issue #206.
      actions: _actions(l10n),
    );
  }

  /// The buttons of the dialog's one bar in its current state (issue #206):
  /// Close on the list, Cancel and Create on the form, Done on the result.
  List<Widget> _actions(AppLocalizations l10n) => switch (_state) {
        _LinkDialogState.list => [
            TextButton(
              key: const Key("share-link-close"),
              onPressed: () => Navigator.of(context).pop(),
              child: Text(l10n.close),
            ),
          ],
        _LinkDialogState.form => [
            TextButton(
              key: const Key("link-cancel"),
              onPressed: _busy ? null : () => setState(() => _creating = false),
              child: Text(l10n.cancel),
            ),
            ElevatedButton(
              key: const Key("link-create"),
              onPressed: _busy ||
                      (_kind == LinkKind.selected && _recipients.isEmpty)
                  ? null
                  : _create,
              child: Text(l10n.createLink),
            ),
          ],
        _LinkDialogState.result => [
            ElevatedButton(
              key: const Key("share-link-done"),
              // Back to the list, which now carries the new link.
              onPressed: () {
                setState(() {
                  _created = null;
                  _resentTo = null;
                  _label.clear();
                  _kind = LinkKind.anonymous;
                  _recipients = const [];
                  _group = false;
                  _createdAsGroup = false;
                });
                _load();
              },
              child: Text(l10n.done),
            ),
          ],
      };

  /// The links covering this folder, the inherited ones marked.
  List<Widget> _linkSection(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var links = _links ?? const <ShareLink>[];
    var error = _error;
    var refusal = _refusal;
    return [
      Text(l10n.linksHeading, style: Theme.of(context).textTheme.titleSmall),
      if (error != null)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Text(error, key: const Key("share-link-error")),
        ),
      if (refusal != null)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Text(
            refusal,
            key: const Key("share-link-refusal"),
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        ),
      if (error == null && links.isEmpty)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Text(l10n.noLinksYet, key: const Key("share-link-none")),
        ),
      for (var link in links) ...[
        ListTile(
          key: Key("link-${link.id}"),
          contentPadding: EdgeInsets.zero,
          dense: true,
          isThreeLine: true,
          leading: Icon(_inherited(link) ? Icons.arrow_upward : Icons.link),
          title: Text(link.label.isEmpty ? l10n.linkNoLabel : link.label),
          subtitle: Text(_describe(l10n, link)),
          trailing: _inherited(link) || link.revoked.isNotEmpty
              ? null
              : IconButton(
                  key: Key("withdraw-${link.id}"),
                  icon: const Icon(Icons.link_off),
                  tooltip: l10n.withdrawTooltip,
                  onPressed: _busy ? null : () => _withdraw(link),
                ),
        ),
        // Who a personal link went to, each with "Send again" where the
        // link is this folder's own and live (issue #201).
        if (!_inherited(link) && link.revoked.isEmpty)
          for (var recipient in link.recipients)
            ListTile(
              key: Key("recipient-${link.id}-${recipient.contact}"),
              dense: true,
              contentPadding: const EdgeInsets.only(left: 40),
              leading: const Icon(Icons.person_outline),
              title: Text(recipient.name),
              trailing: TextButton(
                key: Key("resend-${link.id}-${recipient.contact}"),
                onPressed: _busy ? null : () => _resend(link, recipient),
                child: Text(l10n.sendAgain),
              ),
            ),
      ],
    ];
  }

  /// Whether the given link was made further up and can only be withdrawn
  /// there.
  bool _inherited(ShareLink link) => link.path != ownerPath;

  /// What a link shows and how long it lives, in one paragraph.
  String _describe(AppLocalizations l10n, ShareLink link) {
    var parts = [
      if (link.type == ShareType.personal)
        link.recipients.isEmpty
            ? l10n.linkPersonalOpen
            : l10n.linkRecipientCount(link.recipients.length),
      _rightsOf(l10n, link),
      link.expires.isEmpty
          ? l10n.linkNeverExpires
          : l10n.expiresOnDay(_day(link.expires)),
      link.maxPrivacy >= privacyMembers
          ? l10n.linkUpToMembers
          : l10n.linkPublicOnly,
      ratingFloorLabel(l10n, link.minRating),
    ];
    var text = parts.join(" · ");
    if (link.revoked.isNotEmpty) {
      return "$text\n${l10n.linkWithdrawnOn(_day(link.revoked))}";
    }
    if (_inherited(link)) {
      return "$text\n${l10n.linkInheritedFrom(_pathLabel(l10n, link.path))}";
    }
    return text;
  }

  /// The rights of a link, as the list shows them.
  ///
  /// The words are `rights.dart`'s own: a right is named there once, for
  /// every screen that shows one.
  String _rightsOf(AppLocalizations l10n, ShareLink link) {
    var names = [
      for (var right in allRights)
        if (link.rights.any((held) => held.name == right))
          rightLabel(l10n, right),
    ];
    return names.isEmpty
        ? rightLabel(l10n, rightView)
        : names.join(", ");
  }

  /// The day of an ISO-8601 instant, in the viewer's own time zone.
  ///
  /// The instant itself is the server's business; what the author of a link
  /// wants to read is the day it runs out.
  String _day(String instant) => dayOf(instant);

  /// The entry opening the form of a new link.
  List<Widget> _newLinkTile() => [
        ListTile(
          key: const Key("new-link"),
          contentPadding: EdgeInsets.zero,
          dense: true,
          leading: const Icon(Icons.add_link),
          title: Text(AppLocalizations.of(context)!.newLinkTile),
          onTap: _busy ? null : _openForm,
        ),
      ];

  /// Opens the form of a new link.
  void _openForm() => setState(() => _creating = true);

  /// The form of a new link: the label, then one compact row per question
  /// (issue #205).
  ///
  /// Most links are made with the defaults, so the label comes first and has
  /// the focus, and every other question is one row whose answer stands in it
  /// — "type a label → Create". The rows are [_questions], each built by
  /// [_choiceRow] or [_row], so a further question is one more entry there.
  List<Widget> _formSection(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return [
      Text(l10n.newLinkHeading, style: Theme.of(context).textTheme.titleSmall),
      TextField(
        key: const Key("link-label"),
        controller: _label,
        autofocus: true,
        decoration: InputDecoration(
          isDense: true,
          label: Text(l10n.linkLabelLabel),
          helperText: l10n.linkLabelHelp,
        ),
      ),
      ..._questions(l10n),
    ];
  }

  /// The ratings a link may start at, see [_minRating].
  ///
  /// No −1: a link never shows a photo rated −2 whatever it stores (issue
  /// #152), so "at least Poor" would show exactly what "every photo but the
  /// trash" shows.
  static const List<int> _ratingFloors = [-2, 0, 1, 2];

  /// The questions below the label, one row each.
  List<Widget> _questions(AppLocalizations l10n) => [
        _choiceRow<LinkKind>(
          key: "link-type",
          label: l10n.linkTypeHeading,
          value: _kind,
          choices: [
            for (var kind in LinkKind.values) (kind, kind.labelOf(l10n)),
          ],
          disabled: {if (!_mayProve) LinkKind.open},
          disabledReason: _mayProve ? null : l10n.linkTypeNeedsProof,
          onChanged: (value) => setState(() => _kind = value),
        ),
        if (_kind == LinkKind.selected)
          _row(
            label: l10n.recipientsHeading,
            helper: _recipients.isEmpty ? l10n.recipientsNeeded : null,
            child: RecipientChooser(
              client: widget.client,
              enabled: !_busy,
              // The phone's address book, never on the web (issue #201).
              phoneContacts: widget.isWeb ? null : phoneContacts,
              onChanged: (value) => setState(() => _recipients = value),
            ),
          ),
        // A link for each person (the default), or one for the group whose
        // visitors each prove an address once — so only where the server
        // can prove one (issue #211).
        if (_kind == LinkKind.selected)
          _choiceRow<bool>(
            key: "link-delivery",
            label: l10n.linkDeliveryHeading,
            value: _group,
            choices: [
              (false, l10n.linkDeliveryEach),
              (true, l10n.linkDeliveryGroup),
            ],
            disabled: {if (!_mayProve) true},
            disabledReason: _mayProve ? null : l10n.linkTypeNeedsProof,
            onChanged: (value) => setState(() => _group = value),
          ),
        _choiceRow<LinkExpiry>(
          key: "link-expiry",
          label: l10n.expiresHeading,
          value: _expiry,
          choices: [
            for (var choice in LinkExpiry.values)
              (
                choice,
                choice == LinkExpiry.date && _expiryDate != null
                    ? DateFormat.yMMMd().format(_expiryDate!)
                    : choice.labelOf(l10n),
              ),
          ],
          onChanged: _chooseExpiry,
        ),
        _choiceRow<int>(
          key: "link-shows",
          label: l10n.showsHeading,
          helper: l10n.privacyMembersNote,
          value: _maxPrivacy,
          choices: [
            if (widget.mayShowMembers)
              (privacyMembers, l10n.privacyUpToMembers),
            (privacyPublic, l10n.privacyPublicOnly),
          ],
          onChanged: (value) => setState(() => _maxPrivacy = value),
        ),
        _choiceRow<int>(
          key: "link-rating",
          label: l10n.lowestRatingHeading,
          value: _minRating,
          choices: [
            for (var rating in _ratingFloors)
              (
                rating,
                rating <= -2
                    ? l10n.linkRatingAllButTrash
                    : l10n.linkRatingAtLeast(ratingName(l10n, rating)),
              ),
          ],
          onChanged: (value) => setState(() => _minRating = value),
        ),
        // `view` is always among the rights, so only the two that are a
        // choice are offered; each is independent of the other, which no
        // single select could say. Never `edit`: a link is not an account,
        // and the server refuses one that would allow it.
        _row(
          label: l10n.permissionMayHeading,
          helper: l10n.linkRightsHelp,
          child: Wrap(
            spacing: 8,
            runSpacing: 4,
            children: [
              for (var right in const [rightDownload, rightContribute])
                FilterChip(
                  key: Key("link-right-$right"),
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  label: Text(rightLabel(l10n, right)),
                  tooltip: rightExplanation(l10n, right),
                  selected: _picked.contains(right),
                  onSelected: _busy
                      ? null
                      : (value) => setState(() {
                            _picked = {
                              rightView,
                              for (var held in _picked)
                                if (held != right) held,
                              if (value) right,
                            };
                          }),
                ),
            ],
          ),
        ),
      ];

  /// One question of the form whose answer is one of [choices], as a
  /// dropdown under its [label].
  ///
  /// A [DropdownButton] controlled by [value] alone, not a form field that
  /// keeps a value of its own: "On a date…" whose picker is cancelled must
  /// leave the answer that stood before.
  Widget _choiceRow<T>({
    required String key,
    required String label,
    String? helper,
    required T value,
    required List<(T, String)> choices,
    Set<T> disabled = const {},
    String? disabledReason,
    required ValueChanged<T> onChanged,
  }) =>
      _row(
        label: label,
        helper: helper,
        child: DropdownButtonHideUnderline(
          child: DropdownButton<T>(
            key: Key(key),
            value: value,
            isDense: true,
            isExpanded: true,
            // Variable heights in the open menu, where a choice that is not
            // offered carries its reason; the closed row stays one line.
            itemHeight: null,
            selectedItemBuilder: (context) => [
              for (var (_, text) in choices)
                Text(text, overflow: TextOverflow.ellipsis),
            ],
            items: [
              for (var (choice, text) in choices)
                DropdownMenuItem<T>(
                  value: choice,
                  // Shown and not offered, with the reason under it.
                  enabled: !disabled.contains(choice),
                  child: disabled.contains(choice)
                      ? _disabledChoice(text, disabledReason)
                      : Text(text, overflow: TextOverflow.ellipsis),
                ),
            ],
            // One choice is no choice: shown, and not offered.
            onChanged: _busy || choices.length < 2
                ? null
                : (picked) {
                    if (picked != null) {
                      onChanged(picked);
                    }
                  },
          ),
        ),
      );

  /// A choice of a dropdown that is shown and not offered: dimmed, and the
  /// [reason] under it where there is one (issues #201/#202).
  Widget _disabledChoice(String text, String? reason) {
    var dim = Theme.of(context).disabledColor;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(text, style: TextStyle(color: dim)),
          if (reason != null)
            Text(
              reason,
              key: const Key("link-type-reason"),
              style: Theme.of(context).textTheme.bodySmall?.copyWith(color: dim),
            ),
        ],
      ),
    );
  }

  /// One row of the form: [child] under the question's [label].
  Widget _row({
    required String label,
    String? helper,
    required Widget child,
  }) =>
      Padding(
        padding: const EdgeInsets.only(top: 12),
        child: InputDecorator(
          decoration: InputDecoration(
            isDense: true,
            labelText: label,
            helperText: helper,
            helperMaxLines: 2,
          ),
          child: child,
        ),
      );

  /// How a link's own folder is named, the whole space having no name.
  String _pathLabel(AppLocalizations l10n, String path) =>
      path.isEmpty ? l10n.shareWholeSpace : "'$path'";

  Future<void> _chooseExpiry(LinkExpiry choice) async {
    if (choice != LinkExpiry.date) {
      setState(() => _expiry = choice);
      return;
    }
    await _pickDate();
  }

  /// Asks for the day the link runs out on, see [LinkExpiry.date].
  Future<void> _pickDate() async {
    var now = DateTime.now();
    var picked = await showDatePicker(
      context: context,
      initialDate: _expiryDate ?? now.add(const Duration(days: 7)),
      firstDate: now,
      lastDate: now.add(const Duration(days: 3650)),
    );
    if (picked == null || !mounted) {
      return;
    }
    setState(() {
      _expiry = LinkExpiry.date;
      _expiryDate = picked;
    });
  }

  /// The instant the new link expires at, empty if it never does.
  String get _expiresAt {
    if (_expiry == LinkExpiry.date) {
      var day = _expiryDate;
      // An unfinished "a date…" is read as "never": nothing is invented for
      // the user, and the list says plainly what was made.
      return day == null ? "" : day.toUtc().toIso8601String();
    }
    var at = _expiry.from(DateTime.now());
    return at == null ? "" : at.toUtc().toIso8601String();
  }

  /// Creates the link and shows its URL, once.
  Future<void> _create() async {
    if (refuseWhileOffline(context)) {
      return;
    }
    setState(() {
      _busy = true;
      _refusal = null;
    });
    ShareLinkCreated answer;
    try {
      answer = await widget.client.share(
        widget.path,
        ShareLink(
          type: _kind == LinkKind.anonymous
              ? ShareType.anonymous
              : ShareType.personal,
          recipients: _kind == LinkKind.selected ? _recipients : const [],
          label: _label.text.trim(),
          expires: _expiresAt,
          maxPrivacy: _maxPrivacy,
          minRating: _minRating,
          // What the check boxes show, `view` included: the server closes the
          // set again, so sending it only means that what was shown was sent.
          rights: [
            for (var right in allRights)
              if (_picked.contains(right)) RightName(name: right),
          ],
        ),
      );
    } catch (error) {
      if (mounted) {
        setState(() {
          _busy = false;
          _refusal = error is VAlbumException ? error.message : "$error";
          // Back to the list, where the reason is shown.
          _creating = false;
        });
      }
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() {
      _busy = false;
      _creating = false;
      _created = answer;
      _createdAsGroup = _kind == LinkKind.selected && _group;
    });
  }

  /// The URL of a link that was just made, shown exactly once.
  List<Widget> _createdSection(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var created = _created!;
    if (_createdAsGroup && _resentTo == null) {
      return _groupSection(context, created);
    }
    if (created.recipients.isNotEmpty) {
      return _recipientsSection(context, created);
    }
    var url = absoluteServerUrl(widget.client.dataUrl, created.url);
    return [
      Text(l10n.theLinkHeading, style: Theme.of(context).textTheme.titleSmall),
      const SizedBox(height: 8),
      Row(
        children: [
          Expanded(
            child: SelectableText(url, key: const Key("share-link-url")),
          ),
          IconButton(
            key: const Key("copy-link"),
            icon: const Icon(Icons.copy),
            tooltip: l10n.copy,
            onPressed: () => _copy(url),
          ),
        ],
      ),
      const SizedBox(height: 16),
      Text(l10n.shareLinkOnce, key: const Key("share-link-once")),
    ];
  }

  /// How the shared folder is named in the message carrying a link.
  String get _albumName {
    var given = widget.label;
    if (given != null && given.trim().isNotEmpty) {
      return given.trim();
    }
    return widget.path.isEmpty
        ? AppLocalizations.of(context)!.shareWholeSpace
        : widget.path.last;
  }

  /// The links of a personal link's recipients, each with the ways to send
  /// it (issues #201/#202): every one shown exactly once.
  List<Widget> _recipientsSection(
    BuildContext context,
    ShareLinkCreated created,
  ) {
    var l10n = AppLocalizations.of(context)!;
    var resent = _resentTo;
    return [
      Text(
        resent == null
            ? l10n.recipientLinksHeading
            : l10n.sendAgainHeading(resent),
        style: Theme.of(context).textTheme.titleSmall,
      ),
      for (var recipient in created.recipients)
        RecipientSendLines(
          recipient: recipient,
          url: absoluteServerUrl(widget.client.dataUrl, recipient.url),
          album: _albumName,
          isWeb: widget.isWeb,
        ),
      const SizedBox(height: 16),
      Text(
        resent == null ? l10n.recipientLinksOnce : l10n.sendAgainNote,
        key: const Key("recipient-links-once"),
      ),
    ];
  }

  /// A group link (issue #211): the link's own address, one mail to every
  /// recipient with an e-mail address and the link to copy — and for those
  /// without one, whom the group link cannot recognise, their own links.
  List<Widget> _groupSection(BuildContext context, ShareLinkCreated created) {
    var l10n = AppLocalizations.of(context)!;
    var url = absoluteServerUrl(widget.client.dataUrl, created.url);
    bool hasEmail(RecipientLink recipient) => recipient.addresses
        .any((address) => address.kind == AddressKind.email);
    var addresses = [
      for (var recipient in created.recipients)
        for (var address in recipient.addresses)
          if (address.kind == AddressKind.email) address.value,
    ];
    var without = [
      for (var recipient in created.recipients)
        if (!hasEmail(recipient)) recipient,
    ];
    return [
      Text(l10n.linkDeliveryGroup,
          style: Theme.of(context).textTheme.titleSmall),
      const SizedBox(height: 8),
      SelectableText(url, key: const Key("share-link-url")),
      GroupSendLines(addresses: addresses, url: url, album: _albumName),
      Text(l10n.groupLinkNote, key: const Key("group-link-note")),
      if (without.isNotEmpty) ...[
        const SizedBox(height: 16),
        Text(l10n.groupLinkWithoutEmail,
            key: const Key("group-link-without-email")),
        for (var recipient in without)
          RecipientSendLines(
            recipient: recipient,
            url: absoluteServerUrl(widget.client.dataUrl, recipient.url),
            album: _albumName,
            isWeb: widget.isWeb,
          ),
      ],
    ];
  }

  /// "Send again" (#198's `resend`): a fresh link of their own for one
  /// recipient, the earlier one void, shown with the ways to send it.
  Future<void> _resend(ShareLink link, ShareRecipient recipient) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      var answer =
          await widget.client.resend(widget.path, link.id, recipient.contact);
      if (mounted) {
        setState(() {
          _busy = false;
          _created = answer;
          _resentTo = recipient.name;
        });
      }
    } catch (error) {
      if (mounted) {
        setState(() {
          _busy = false;
          _refusal = error is VAlbumException ? error.message : "$error";
        });
      }
    }
  }

  Future<void> _copy(String url) async {
    var messenger = ScaffoldMessenger.of(context);
    var said = AppLocalizations.of(context)!.linkCopied;
    await Clipboard.setData(ClipboardData(text: url));
    messenger.showSnackBar(SnackBar(content: Text(said)));
  }

  /// Withdraws a link, after asking: a link somebody already has stops
  /// working the moment this is done.
  Future<void> _withdraw(ShareLink link) async {
    var outer = AppLocalizations.of(context)!;
    var name = link.label.isEmpty
        ? outer.withdrawLinkThisLink
        : "'${link.label}'";
    var confirmed = await showDialog<bool>(
      context: context,
      builder: (context) {
        var l10n = AppLocalizations.of(context)!;
        return AlertDialog(
          key: const Key("withdraw-confirm"),
          title: Text(l10n.withdrawLinkTitle),
          content: Text(l10n.withdrawLinkMessage(name)),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: Text(l10n.cancel),
            ),
            ElevatedButton(
              key: const Key("withdraw-confirm-ok"),
              onPressed: () => Navigator.of(context).pop(true),
              child: Text(l10n.withdraw),
            ),
          ],
        );
      },
    );
    if (confirmed != true || !mounted) {
      return;
    }
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      await widget.client.unshare(widget.path, link.id);
    } catch (error) {
      if (mounted) {
        setState(() {
          _busy = false;
          _refusal = error is VAlbumException ? error.message : "$error";
        });
      }
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() => _busy = false);
    await _load();
  }
}
