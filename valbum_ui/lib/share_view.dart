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

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';

import 'album_edit.dart' show privacyMembers, privacyPublic;
import 'caller.dart';
import 'client.dart';
import 'l10n/app_localizations.dart';
import 'manage_view.dart' show dayOf;
import 'offline.dart';
import 'resource.dart';
import 'rights.dart';
import 'share_session.dart' show ratingFloorLabel;
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
  await showDialog<void>(
    context: context,
    builder: (context) =>
        ShareLinkDialog(client: client, path: path, label: label),
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

  const ShareLinkDialog({
    super.key,
    required this.client,
    required this.path,
    this.label,
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
  /// handed out through a link.
  int _maxPrivacy = privacyPublic;

  /// The rating floor of the new link, the lowest by default: a link shows
  /// what the album holds unless its author says otherwise.
  int _minRating = -2;

  /// The rights the new link carries; `view` is always among them.
  Set<String> _picked = const {rightView};

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
        height: 440,
        child: _links == null
            ? const Center(child: CircularProgressIndicator())
            : SingleChildScrollView(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: _created != null
                      ? _createdSection(context)
                      : [
                          ..._linkSection(context),
                          const Divider(),
                          ...(_creating
                              ? _formSection(context)
                              : _newLinkTile()),
                        ],
                ),
              ),
      ),
      actions: [
        TextButton(
          key: const Key("share-link-close"),
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.close),
        ),
      ],
    );
  }

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
      for (var link in links)
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
    ];
  }

  /// Whether the given link was made further up and can only be withdrawn
  /// there.
  bool _inherited(ShareLink link) => link.path != ownerPath;

  /// What a link shows and how long it lives, in one paragraph.
  String _describe(AppLocalizations l10n, ShareLink link) {
    var parts = [
      _rightsOf(link),
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
  String _rightsOf(ShareLink link) {
    var names = [
      for (var right in allRights)
        if (link.rights.any((held) => held.name == right))
          rightLabels[right] ?? right,
    ];
    return names.isEmpty
        ? (rightLabels[rightView] ?? rightView)
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
          onTap: _busy ? null : () => setState(() => _creating = true),
        ),
      ];

  /// The five questions a new link asks.
  List<Widget> _formSection(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var titles = Theme.of(context).textTheme.titleSmall;
    return [
      Text(l10n.newLinkHeading, style: titles),
      TextField(
        key: const Key("link-label"),
        controller: _label,
        autofocus: true,
        decoration: InputDecoration(
          label: Text(l10n.linkLabelLabel),
          helperText: l10n.linkLabelHelp,
        ),
      ),
      const SizedBox(height: 8),
      Text(l10n.expiresHeading, style: titles),
      for (var choice in LinkExpiry.values)
        _choiceTile(
          key: "expiry-${choice.name}",
          chosen: _expiry == choice,
          title: choice == LinkExpiry.date && _expiryDate != null
              ? DateFormat.yMMMd().format(_expiryDate!)
              : choice.labelOf(l10n),
          onTap: () => _chooseExpiry(choice),
        ),
      const SizedBox(height: 8),
      Text(l10n.showsHeading, style: titles),
      _choiceTile(
        key: "privacy-public",
        chosen: _maxPrivacy == privacyPublic,
        title: l10n.privacyPublicOnly,
        onTap: () => setState(() => _maxPrivacy = privacyPublic),
      ),
      _choiceTile(
        key: "privacy-members",
        chosen: _maxPrivacy == privacyMembers,
        title: l10n.privacyUpToMembers,
        subtitle: l10n.privacyMembersNote,
        onTap: () => setState(() => _maxPrivacy = privacyMembers),
      ),
      const SizedBox(height: 8),
      Text(l10n.lowestRatingHeading, style: titles),
      for (var rating in const [-2, -1, 0, 1, 2])
        _choiceTile(
          key: "rating-$rating",
          chosen: _minRating == rating,
          title: ratingFloorLabel(l10n, rating),
          onTap: () => setState(() => _minRating = rating),
        ),
      const SizedBox(height: 8),
      Text(l10n.permissionMayHeading, style: titles),
      CheckboxListTile(
        key: const Key("link-right-view"),
        contentPadding: EdgeInsets.zero,
        dense: true,
        controlAffinity: ListTileControlAffinity.leading,
        value: true,
        // Always on: a link that allows nothing would be a link to nothing.
        onChanged: null,
        title: Text(rightLabels[rightView] ?? rightView),
        subtitle: Text(rightExplanations[rightView] ?? ""),
      ),
      for (var right in const [rightDownload, rightContribute])
        CheckboxListTile(
          key: Key("link-right-$right"),
          contentPadding: EdgeInsets.zero,
          dense: true,
          controlAffinity: ListTileControlAffinity.leading,
          value: _picked.contains(right),
          onChanged: (value) => setState(() {
            _picked = {
              rightView,
              for (var held in _picked)
                if (held != right) held,
              if (value ?? false) right,
            };
          }),
          title: Text(rightLabels[right] ?? right),
          subtitle: Text(rightExplanations[right] ?? ""),
        ),
      // Never `edit`: a link is not an account, and the server refuses one
      // that would allow it.
      Padding(
        padding: const EdgeInsets.only(top: 8),
        child: Text(
          l10n.linkNeverEdits,
          key: const Key("link-no-edit"),
          style: const TextStyle(fontSize: 12),
        ),
      ),
      const SizedBox(height: 8),
      Row(
        mainAxisAlignment: MainAxisAlignment.end,
        children: [
          TextButton(
            key: const Key("link-cancel"),
            onPressed: _busy ? null : () => setState(() => _creating = false),
            child: Text(l10n.cancel),
          ),
          const SizedBox(width: 8),
          ElevatedButton(
            key: const Key("link-create"),
            onPressed: _busy ? null : _create,
            child: Text(l10n.createLink),
          ),
        ],
      ),
    ];
  }

  /// One choice of a group, ticked when it is the current one.
  ///
  /// A [ListTile], not a `RadioListTile`: the radio of the framework wants a
  /// `RadioGroup` ancestor since Flutter 3.32, and the dialog already shows
  /// its choices in the tile idiom of the settings.
  Widget _choiceTile({
    required String key,
    required bool chosen,
    required String title,
    String? subtitle,
    required VoidCallback onTap,
  }) =>
      ListTile(
        key: Key(key),
        contentPadding: EdgeInsets.zero,
        dense: true,
        selected: chosen,
        leading: Icon(
          chosen ? Icons.radio_button_checked : Icons.radio_button_unchecked,
        ),
        title: Text(title),
        subtitle: subtitle == null ? null : Text(subtitle),
        onTap: _busy ? null : onTap,
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
    setState(() {
      _busy = true;
      _refusal = null;
    });
    ShareLinkCreated answer;
    try {
      answer = await widget.client.share(
        widget.path,
        ShareLink(
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
    });
  }

  /// The URL of a link that was just made, shown exactly once.
  List<Widget> _createdSection(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var created = _created!;
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
      const SizedBox(height: 16),
      Align(
        alignment: Alignment.centerRight,
        child: ElevatedButton(
          key: const Key("share-link-done"),
          onPressed: () {
            setState(() {
              _created = null;
              _label.clear();
            });
            _load();
          },
          child: Text(l10n.done),
        ),
      ),
    ];
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
