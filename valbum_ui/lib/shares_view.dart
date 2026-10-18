/// The shares of a space in Manage (issue #269): what is shared with whom,
/// and the place to take it back or change it.
///
/// One list of every share link the caller may manage — every link of the
/// space for an administrator, the links they handed out for a member who may
/// share (the server decides, `?type=space-shares`) — seen two ways: **by
/// album**, the folder each link shares, or **by person**, each contact under
/// every link that lets them in (the rule of the overview of #267, answered by
/// the server as `admitted`), the links whoever holds them may open under
/// "Anyone with the link", and the addressed links that let nobody in any more
/// under "Nobody any more" — so both views hold the same links. A filter field
/// narrows by album, person or label, a switch shows the expired links too.
///
/// Every action asks the server at once and says what it did; whatever takes
/// something away from somebody asks first. The share dialog of an album opens
/// this screen narrowed to that album, a contact's menu narrowed to that
/// person.
library;

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'action_guard.dart';
import 'album_edit.dart' show privacyMembers, privacyPublic;
import 'caller.dart';
import 'client.dart';
import 'dialog_style.dart';
import 'filter_list.dart';
import 'form_dialog.dart';
import 'l10n/app_localizations.dart';
import 'manage_view.dart' show confirmHere, dayOf, refusalMessage;
import 'recipient_send.dart';
import 'resource.dart';
import 'rights.dart';
import 'selectable.dart';
import 'share_session.dart' show ratingName;
import 'share_view.dart';
import 'urls.dart';
import 'page_app_bar.dart';

/// How the shares screen groups the links.
enum SharesGrouping { album, person }

/// What kind of group of the "by person" view a group is.
enum ShareGroupKind {
  /// The links of one album.
  album,

  /// The links letting one contact in.
  person,

  /// The links whoever holds them may open: anonymous and personalized ones.
  anyone,

  /// The addressed links that let nobody in any more.
  nobody,
}

/// One group of the shares screen: a heading and the links under it.
class ShareGroup {
  final ShareGroupKind kind;

  /// The album's path, or the contact's id; empty for the two other kinds.
  final String key;

  /// The album's title, or the contact's name; empty for the two other kinds.
  final String name;

  final List<ShareLink> links;

  const ShareGroup(this.kind, this.key, this.name, this.links);
}

/// The links grouped by the folder they share, in the order of the paths.
List<ShareGroup> groupByAlbum(List<ShareLink> links) {
  var groups = <String, List<ShareLink>>{};
  var titles = <String, String>{};
  for (var link in links) {
    groups.putIfAbsent(link.path, () => []).add(link);
    if (link.title.isNotEmpty) {
      titles[link.path] = link.title;
    }
  }
  var paths = groups.keys.toList()..sort();
  return [
    for (var path in paths)
      ShareGroup(ShareGroupKind.album, path, titles[path] ?? "", groups[path]!),
  ];
}

/// Whether [link] lets in whoever holds it: an anonymous link, or a
/// personalized one nobody was named on.
bool opensToAnyone(ShareLink link) =>
    link.type != ShareType.personal || !link.addressed;

/// The contacts [link] lets in now, by id, with the name the space gives them:
/// its recipients and visitors the server answered `admitted` (issue #267's
/// rule, expiry aside).
Map<String, String> admittedBy(ShareLink link) => {
      for (var recipient in link.recipients)
        if (recipient.admitted) recipient.contact: recipient.name,
      for (var visitor in link.visitors)
        if (visitor.admitted) visitor.contact: visitor.name,
    };

/// The links grouped by the person they let in: "Anyone with the link" first,
/// then every contact by name, then "Nobody any more". Every link stands in at
/// least one group, so the view holds the same links as [groupByAlbum].
List<ShareGroup> groupByPerson(List<ShareLink> links) {
  var anyone = <ShareLink>[];
  var nobody = <ShareLink>[];
  var people = <String, List<ShareLink>>{};
  var names = <String, String>{};
  for (var link in links) {
    var admitted = admittedBy(link);
    if (opensToAnyone(link)) {
      anyone.add(link);
    } else if (admitted.isEmpty) {
      nobody.add(link);
    }
    admitted.forEach((contact, name) {
      people.putIfAbsent(contact, () => []).add(link);
      names[contact] = name;
    });
  }
  var contacts = people.keys.toList()
    ..sort((a, b) {
      var byName = names[a]!.toLowerCase().compareTo(names[b]!.toLowerCase());
      return byName != 0 ? byName : a.compareTo(b);
    });
  return [
    if (anyone.isNotEmpty) ShareGroup(ShareGroupKind.anyone, "", "", anyone),
    for (var contact in contacts)
      ShareGroup(
          ShareGroupKind.person, contact, names[contact]!, people[contact]!),
    if (nobody.isNotEmpty) ShareGroup(ShareGroupKind.nobody, "", "", nobody),
  ];
}

/// Whether [link] shares something of the folder at [album]: a link on the
/// folder, on a folder above it (which shares it too), or on one below it.
bool sharesAlbum(ShareLink link, String album) =>
    link.path == album ||
    link.path.isEmpty ||
    album.startsWith("${link.path}/") ||
    link.path.startsWith("$album/");

/// Whether [link] lets the contact [person] in now.
bool admitsPerson(ShareLink link, String person) =>
    admittedBy(link).containsKey(person);

/// Whether any of [texts] contains [filter], case aside.
bool _matches(String filter, Iterable<String> texts) {
  var wanted = filter.trim().toLowerCase();
  return wanted.isEmpty ||
      texts.any((text) => text.toLowerCase().contains(wanted));
}

/// Whether [link] matches the text of the filter field: its album's title or
/// path, its label, the label whose photos it shows, its maker, or a person on
/// it.
///
/// Without [people] the persons on it are left out: under the heading of one
/// person, the link does not match for the name of another.
bool linkMatches(ShareLink link, String filter, {bool people = true}) =>
    _matches(filter, [
      link.title,
      link.path,
      link.label,
      link.photoLabel,
      link.createdBy,
      if (people) ...[
        for (var recipient in link.recipients) recipient.name,
        for (var recipient in link.recipients)
          for (var address in recipient.addresses) address.value,
        for (var visitor in link.visitors) visitor.name,
      ],
    ]);

/// Opens the shares screen, narrowed to the album at [album] (named
/// [albumName]) or to the contact [person] (named [personName]) where given.
Future<void> openShares(
  BuildContext context, {
  required VAlbumClient client,
  String? album,
  String? albumName,
  String? person,
  String? personName,
}) =>
    Navigator.of(context, rootNavigator: true).push(
      MaterialPageRoute<void>(
        builder: (_) => SelectableArea(
          child: SharesScreen(
            client: client,
            album: album,
            albumName: albumName,
            person: person,
            personName: personName,
          ),
        ),
      ),
    );

/// The key of the shares section of the server settings.
const Key sharesSectionKey = Key("settings.shares");

/// The shares section of the server settings (issue #269): what it is, and the
/// button opening [SharesScreen]. Shown to whoever may share links.
class SharesSection extends StatelessWidget {
  final VAlbumClient client;

  const SharesSection({super.key, required this.client});

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return Column(
      key: sharesSectionKey,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 24),
        const Divider(),
        const SizedBox(height: 8),
        Text(l10n.sharesHeading,
            style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        Text(l10n.sharesLead),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          key: const Key("settings.shares.open"),
          icon: const Icon(Icons.share_outlined),
          label: Text(l10n.sharesOpen),
          onPressed: () => openShares(context, client: client),
        ),
      ],
    );
  }
}

/// What is shared with whom, see the library comment.
class SharesScreen extends StatefulWidget {
  final VAlbumClient client;

  /// The album the screen is narrowed to, in the coordinates the links are
  /// spelled in; `null` for none.
  final String? album;

  /// How [album] is named on its chip.
  final String? albumName;

  /// The contact the screen is narrowed to; `null` for none. Narrowed to a
  /// person, the screen starts grouped by person.
  final String? person;

  /// How [person] is named on its chip.
  final String? personName;

  /// Whether the screen runs in a browser, see [RecipientSendLines.isWeb].
  final bool isWeb;

  /// The moment "expired" is decided against; the device's clock by default.
  final DateTime? now;

  const SharesScreen({
    super.key,
    required this.client,
    this.album,
    this.albumName,
    this.person,
    this.personName,
    this.isWeb = kIsWeb,
    this.now,
  });

  @override
  State<SharesScreen> createState() => SharesScreenState();
}

class SharesScreenState extends State<SharesScreen> {
  /// The links, `null` while they are read.
  List<ShareLink>? _links;

  /// Why the links could not be read or a request was refused, `null` while
  /// all is well.
  String? _problem;

  bool _busy = false;

  late SharesGrouping _grouping =
      widget.person != null ? SharesGrouping.person : SharesGrouping.album;

  late String? _album = widget.album;

  late String? _person = widget.person;

  bool _showExpired = false;

  final TextEditingController _filter = TextEditingController();

  DateTime get _now => widget.now ?? DateTime.now();

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _filter.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      var answer = await widget.client.spaceShares();
      if (mounted) {
        setState(() {
          _links = answer.links;
          _problem = null;
        });
      }
    } catch (error) {
      if (mounted) {
        setState(() {
          _links = const [];
          _problem = refusalMessage(error);
        });
      }
    }
  }

  /// The links the scope chips and the switch leave, before the filter text.
  List<ShareLink> get _scoped {
    var album = _album;
    var person = _person;
    return [
      for (var link in _links ?? const <ShareLink>[])
        if ((_showExpired || !linkExpired(link, now: _now)) &&
            (album == null || sharesAlbum(link, album)) &&
            (person == null || admitsPerson(link, person)))
          link,
    ];
  }

  /// The groups shown: a group whose heading matches the filter with every
  /// link, else with the links that match it themselves.
  List<ShareGroup> _groups(AppLocalizations l10n) {
    var links = _scoped;
    var groups = _grouping == SharesGrouping.album
        ? groupByAlbum(links)
        : groupByPerson(links);
    var person = _person;
    var filter = _filter.text;
    return [
      for (var group in groups)
        if (_grouping != SharesGrouping.person ||
            person == null ||
            group.key == person)
          if (_matches(filter, _groupTexts(l10n, group)))
            group
          else if (group.links.any((link) => linkMatches(link, filter,
              people: group.kind != ShareGroupKind.person)))
            ShareGroup(group.kind, group.key, group.name, [
              for (var link in group.links)
                if (linkMatches(link, filter,
                    people: group.kind != ShareGroupKind.person))
                  link,
            ]),
    ];
  }

  List<String> _groupTexts(AppLocalizations l10n, ShareGroup group) =>
      switch (group.kind) {
        ShareGroupKind.album => [group.name, group.key],
        ShareGroupKind.person => [group.name],
        ShareGroupKind.anyone => [l10n.sharesAnyone],
        ShareGroupKind.nobody => [l10n.sharesNobody],
      };

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var links = _links;
    var problem = _problem;
    var groups = links == null ? const <ShareGroup>[] : _groups(l10n);
    var hiddenExpired = _showExpired
        ? 0
        : (links ?? const <ShareLink>[])
            .where((link) => linkExpired(link, now: _now))
            .length;
    return Scaffold(
      key: const Key("shares-screen"),
      appBar: PageAppBar(title: Text(l10n.sharesHeading)),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Wrap(
            spacing: 16,
            runSpacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              SegmentedButton<SharesGrouping>(
                key: const Key("shares-grouping"),
                segments: [
                  ButtonSegment(
                    value: SharesGrouping.album,
                    label: Text(l10n.sharesByAlbum,
                        key: const Key("shares-by-album")),
                    icon: const Icon(Icons.photo_album_outlined),
                  ),
                  ButtonSegment(
                    value: SharesGrouping.person,
                    label: Text(l10n.sharesByPerson,
                        key: const Key("shares-by-person")),
                    icon: const Icon(Icons.people_outline),
                  ),
                ],
                selected: {_grouping},
                onSelectionChanged: (value) =>
                    setState(() => _grouping = value.first),
              ),
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Switch(
                    key: const Key("shares-expired"),
                    value: _showExpired,
                    onChanged: (value) => setState(() => _showExpired = value),
                  ),
                  const SizedBox(width: 8),
                  Text(l10n.sharesShowExpired),
                ],
              ),
            ],
          ),
          const SizedBox(height: 8),
          FilterField(
            controller: _filter,
            label: l10n.sharesFilter,
            fieldKey: const Key("shares-filter"),
            autofocus: false,
            onChanged: (_) => setState(() {}),
          ),
          if (_album != null || _person != null) ...[
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              children: [
                if (_album != null)
                  InputChip(
                    key: const Key("shares-scope-album"),
                    label: Text(l10n.sharesScopeAlbum(
                        widget.albumName ?? _albumLabel(l10n, _album!))),
                    onDeleted: () => setState(() => _album = null),
                  ),
                if (_person != null)
                  InputChip(
                    key: const Key("shares-scope-person"),
                    label: Text(
                        l10n.sharesScopePerson(widget.personName ?? _person!)),
                    onDeleted: () => setState(() => _person = null),
                  ),
              ],
            ),
          ],
          if (problem != null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Text(
                problem,
                key: const Key("shares-problem"),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          if (links == null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 16),
              child: Row(
                children: [
                  const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                  const SizedBox(width: 8),
                  Text(l10n.askingServer),
                ],
              ),
            )
          else if (links.isEmpty && problem == null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 16),
              child: Text(l10n.sharesNone, key: const Key("shares-none")),
            )
          else if (groups.isEmpty && links.isNotEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 16),
              child:
                  Text(l10n.sharesNoMatch, key: const Key("shares-no-match")),
            ),
          for (var group in groups) ..._group(l10n, group),
          if (hiddenExpired > 0)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Text(l10n.sharesExpiredHidden(hiddenExpired),
                  key: const Key("shares-expired-hidden")),
            ),
        ],
      ),
    );
  }

  /// How an album is named where it has no title.
  String _albumLabel(AppLocalizations l10n, String path) =>
      path.isEmpty ? l10n.shareWholeSpace : path.split("/").last;

  List<Widget> _group(AppLocalizations l10n, ShareGroup group) {
    var key = "${group.kind.name}-${group.key}";
    var (title, subtitle, icon) = switch (group.kind) {
      ShareGroupKind.album => (
          group.name.isNotEmpty ? group.name : _albumLabel(l10n, group.key),
          group.key.isEmpty ? l10n.shareWholeSpace : group.key,
          Icons.photo_album_outlined,
        ),
      ShareGroupKind.person => (group.name, null, Icons.person_outline),
      ShareGroupKind.anyone => (l10n.sharesAnyone, null, Icons.public),
      ShareGroupKind.nobody => (l10n.sharesNobody, null, Icons.person_off),
    };
    return [
      const SizedBox(height: 16),
      ListTile(
        key: Key("shares-group-$key"),
        contentPadding: EdgeInsets.zero,
        leading: Icon(icon),
        title: Text(title, style: Theme.of(context).textTheme.titleMedium),
        subtitle: subtitle == null ? null : Text(subtitle),
      ),
      for (var link in group.links) _linkCard(l10n, group, link),
    ];
  }

  Widget _linkCard(AppLocalizations l10n, ShareGroup group, ShareLink link) {
    var expired = linkExpired(link, now: _now);
    var made = link.createdBy.isEmpty
        ? l10n.linkSharedOn(dayOf(link.created))
        : l10n.linkSharedBy(link.createdBy, dayOf(link.created));
    var albumLine = group.kind == ShareGroupKind.album
        ? null
        : [
            link.title.isNotEmpty ? link.title : _albumLabel(l10n, link.path),
            if (link.path.isNotEmpty) link.path,
          ].join(" · ");
    var key = "${group.kind.name}-${group.key}-${link.id}";
    return Card(
      key: Key("shares-link-$key"),
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  link.type == ShareType.personal
                      ? Icons.person_pin_outlined
                      : Icons.link,
                  color: expired ? Theme.of(context).disabledColor : null,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    link.label.isEmpty ? l10n.linkNoLabel : link.label,
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                ),
                TextButton(
                  key: Key("shares-edit-${link.id}"),
                  onPressed: _busy ? null : () => _edit(link),
                  child: Text(l10n.editLinkEntry),
                ),
                IconButton(
                  key: Key("shares-delete-${link.id}"),
                  icon: const Icon(Icons.delete_outline),
                  tooltip: l10n.deleteLinkTooltip,
                  onPressed: _busy ? null : () => _delete(link),
                ),
              ],
            ),
            if (albumLine != null)
              Text(albumLine, key: Key("shares-album-$key")),
            Text(
              linkSummary(l10n, link, now: _now),
              key: Key("shares-summary-$key"),
            ),
            Text(
              [made, if (link.uploads > 0) l10n.photosAdded(link.uploads)]
                  .join(" · "),
              key: Key("shares-made-$key"),
              style: Theme.of(context).textTheme.bodySmall,
            ),
            for (var recipient in link.recipients)
              ListTile(
                key: Key("shares-recipient-${link.id}-${recipient.contact}"),
                dense: true,
                contentPadding: const EdgeInsets.only(left: 16),
                leading: Icon(recipient.shutOut.isEmpty
                    ? Icons.person_outline
                    : Icons.person_off_outlined),
                title: Text(recipient.name),
                subtitle: Text(recipientState(l10n, recipient)),
                trailing: _recipientMenu(l10n, link, recipient),
              ),
            for (var visitor in link.visitors)
              ListTile(
                key: Key("shares-visitor-${link.id}-${visitor.contact}"),
                dense: true,
                contentPadding: const EdgeInsets.only(left: 16),
                leading: Icon(visitor.shutOut.isEmpty
                    ? Icons.person_pin_outlined
                    : Icons.person_off_outlined),
                title: Text(visitor.name),
                subtitle: Text(visitorState(l10n, visitor)),
                trailing: IconButton(
                  key: Key("shares-shut-out-${link.id}-${visitor.contact}"),
                  icon: Icon(
                      visitor.shutOut.isEmpty ? Icons.block : Icons.lock_open),
                  tooltip: visitor.shutOut.isEmpty
                      ? l10n.shutOutOfLink
                      : l10n.letInAgain,
                  onPressed: _busy
                      ? null
                      : () => _shutOut(link, visitor.contact, visitor.name,
                          visitor.shutOut.isEmpty),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _recipientMenu(
      AppLocalizations l10n, ShareLink link, ShareRecipient recipient) {
    var id = "${link.id}-${recipient.contact}";
    var isShutOut = recipient.shutOut.isNotEmpty;
    return PopupMenuButton<void Function()>(
      key: Key("shares-recipient-menu-$id"),
      enabled: !_busy,
      onSelected: (action) => action(),
      itemBuilder: (context) => [
        PopupMenuItem(
          key: Key("shares-renew-$id"),
          value: () => _renew(link, recipient),
          child: Text(l10n.recipientRenew),
        ),
        PopupMenuItem(
          key: Key("shares-shut-out-$id"),
          value: () =>
              _shutOut(link, recipient.contact, recipient.name, !isShutOut),
          child: Text(isShutOut ? l10n.letInAgain : l10n.shutOutOfLink),
        ),
        PopupMenuItem(
          key: Key("shares-remove-$id"),
          value: () => _removeRecipient(link, recipient),
          child: Text(l10n.recipientRemove),
        ),
      ],
    );
  }

  /// Says what an action did, in the line at the foot of the screen.
  void _say(String text) => ScaffoldMessenger.maybeOf(context)
      ?.showSnackBar(SnackBar(content: Text(text)));

  /// Runs one request of this screen; answers whether it went through, and
  /// shows the server's reason where it did not.
  ///
  /// Refused while offline before anything is sent, see [writeAction]; the
  /// server's reason is said in the line of this screen.
  Future<bool> _run(Future<void> Function() request) async {
    var done = await writeAction(
      context,
      () async {
        setState(() {
          _busy = true;
          _problem = null;
        });
        await request();
      },
      speaks: false,
      onRefused: (error) {
        if (mounted) {
          setState(() {
            _busy = false;
            _problem = refusalMessage(error);
          });
        }
      },
    );
    if (done == null) {
      return false;
    }
    if (mounted) {
      setState(() => _busy = false);
    }
    return mounted;
  }

  /// Puts the link the server answered in the place of the one shown.
  void _replace(ShareLinkList answer) {
    if (answer.links.isEmpty) {
      return;
    }
    var changed = answer.links.first;
    setState(() {
      _links = [
        for (var shown in _links ?? const <ShareLink>[])
          shown.id == changed.id ? changed : shown,
      ];
    });
  }

  Future<void> _delete(ShareLink link) async {
    var l10n = AppLocalizations.of(context)!;
    var confirmed = await confirmHere(
      context: context,
      dialogKey: "shares-delete-confirm",
      title: l10n.deleteLinkTitle,
      message: deleteLinkQuestion(l10n, link),
      confirmLabel: l10n.delete,
      confirmKey: "shares-delete-confirmed",
    );
    if (confirmed != true || !mounted) {
      return;
    }
    var done = await _run(() => widget.client.unshare(const [], link.id));
    if (done) {
      setState(() {
        _links = [
          for (var shown in _links ?? const <ShareLink>[])
            if (shown.id != link.id) shown,
        ];
      });
      _say(l10n.linkDeletedDone);
    }
  }

  Future<void> _removeRecipient(
      ShareLink link, ShareRecipient recipient) async {
    var l10n = AppLocalizations.of(context)!;
    var confirmed = await confirmHere(
      context: context,
      dialogKey: "shares-remove-confirm",
      title: l10n.recipientRemoveTitle(recipient.name),
      message: l10n.recipientRemoveMessage,
      confirmLabel: l10n.recipientRemove,
      confirmKey: "shares-remove-confirmed",
    );
    if (confirmed != true || !mounted) {
      return;
    }
    ShareLinkList? answer;
    var done = await _run(() async {
      answer = await widget.client.removeRecipient(link.id, recipient.contact);
    });
    if (done) {
      _replace(answer!);
      _say(l10n.recipientRemoved(recipient.name));
    }
  }

  /// Shuts [contact] out of [link] after asking, or lets them in again
  /// without: the same menu entry undoes it.
  Future<void> _shutOut(
      ShareLink link, String contact, String name, bool shutOut) async {
    var l10n = AppLocalizations.of(context)!;
    if (shutOut) {
      var confirmed = await confirmHere(
        context: context,
        dialogKey: "shares-shut-out-confirm",
        title: l10n.shutOutTitle(name),
        message: l10n.shutOutMessage,
        confirmLabel: l10n.shutOutOfLink,
        confirmKey: "shares-shut-out-confirmed",
      );
      if (confirmed != true || !mounted) {
        return;
      }
    }
    var done = await _run(
        () => widget.client.shutOut(const [], link.id, contact, shutOut));
    if (done) {
      // The answer of `shut-out` is the folder's listing of the link; the
      // screen's own carries more, so it is read again.
      await _load();
      _say(shutOut ? l10n.shutOutDone(name) : l10n.letInDone(name));
    }
  }

  /// Sends [recipient] a fresh link of their own after asking — the one they
  /// have stops working — and shows it with the ways to send it, as the share
  /// dialog does.
  Future<void> _renew(ShareLink link, ShareRecipient recipient) async {
    var l10n = AppLocalizations.of(context)!;
    var confirmed = await confirmHere(
      context: context,
      dialogKey: "shares-renew-confirm",
      title: l10n.recipientRenewTitle(recipient.name),
      message: l10n.recipientRenewMessage,
      confirmLabel: l10n.recipientRenew,
      confirmKey: "shares-renew-confirmed",
    );
    if (confirmed != true || !mounted) {
      return;
    }
    ShareLinkCreated? answer;
    var done = await _run(() async {
      answer = await widget.client.resend(const [], link.id, recipient.contact);
    });
    var created = answer;
    if (!done || created == null || !mounted) {
      return;
    }
    var album =
        link.title.isNotEmpty ? link.title : _albumLabel(l10n, link.path);
    await showAppDialog<void>(
      context: context,
      builder: (context) => AppAlertDialog(
        key: const Key("shares-renewed"),
        title: Text(l10n.sendAgainHeading(recipient.name)),
        content: SizedBox(
          width: 460,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (var fresh in created.recipients)
                  RecipientSendLines(
                    recipient: fresh,
                    url: absoluteServerUrl(widget.client.dataUrl, fresh.url),
                    album: album,
                    isWeb: widget.isWeb,
                    overview: sharedOverviewAddress(widget.client.dataUrl),
                  ),
                const SizedBox(height: 16),
                Text(l10n.sendAgainNote),
              ],
            ),
          ),
        ),
        actions: [
          FilledButton(
            key: const Key("shares-renewed-done"),
            onPressed: () => Navigator.of(context).pop(),
            child: Text(l10n.done),
          ),
        ],
      ),
    );
    if (mounted) {
      await _load();
    }
  }

  Future<void> _edit(ShareLink link) async {
    var l10n = AppLocalizations.of(context)!;
    // Asked here, where the caller is known, as the share dialog asks it
    // (issue #282). Nobody said → nothing is withheld on a guess.
    var caller = CallerInfo.maybeOf(context);
    var changed = await showFormDialog<ShareLink>(
      context: context,
      builder: (context) => ShareEditDialog(
        client: widget.client,
        link: link,
        now: _now,
        mayShowMembers: caller == null || caller.permission.seesMembers,
        grantableRights: grantableLinkRights(caller?.permission),
      ),
    );
    if (changed == null || !mounted) {
      return;
    }
    setState(() {
      _links = [
        for (var shown in _links ?? const <ShareLink>[])
          shown.id == changed.id ? changed : shown,
      ];
    });
    _say(l10n.linkChanged);
  }
}

/// How long an edited link lives, as the edit dialog offers it.
enum EditExpiry { keep, never, day, week, month, date }

/// Whether the change from [before] to [after] takes something away from
/// whoever holds the link (issue #269): a right dropped, the privacy ceiling
/// lowered, the rating floor raised, the photos narrowed to a label or to
/// another one, or an end set earlier than before.
bool takesAway(ShareLink before, ShareLink after) {
  Set<String> rights(ShareLink link) => {
        rightView,
        for (var right in link.rights) right.name,
      };
  var had = rights(before);
  var has = rights(after);
  if (had.any((right) => !has.contains(right))) {
    return true;
  }
  if (after.maxPrivacy < before.maxPrivacy ||
      after.minRating > before.minRating) {
    return true;
  }
  if (after.photoLabel.isNotEmpty && after.photoLabel != before.photoLabel) {
    return true;
  }
  if (after.expires.isNotEmpty && after.expires != before.expires) {
    var until = DateTime.tryParse(after.expires);
    var was = before.expires.isEmpty ? null : DateTime.tryParse(before.expires);
    return until == null || was == null || until.isBefore(was);
  }
  return false;
}

/// Changes the options of one share link in place (issue #269): its label,
/// expiry, what it shows and what it allows. The address stays the same.
///
/// The dialog sends the change itself, asks first where it takes something
/// away ([takesAway]), and stays open with the server's reason where it is
/// refused — a right the editor may not use, a ceiling above their own, an
/// expiry in the past. It answers the link as the server answered it.
class ShareEditDialog extends StatefulWidget {
  final VAlbumClient client;

  final ShareLink link;

  /// The moment new expiries are counted from.
  final DateTime? now;

  /// Whether the editor may give the link a ceiling showing what members
  /// see, as [ShareLinkDialog.mayShowMembers] (issue #282). A ceiling the
  /// link already has stays offered: the server asks only what changes.
  final bool mayShowMembers;

  /// The rights beyond `view` the editor may give the link, see
  /// [grantableLinkRights] (issue #282). A right the link already has stays
  /// offered and kept, whoever edits it: the server refuses only a right
  /// that is new to the link, and an edit of something else must not take
  /// one away unasked.
  final Set<String> grantableRights;

  const ShareEditDialog({
    super.key,
    required this.client,
    required this.link,
    this.now,
    this.mayShowMembers = true,
    this.grantableRights = const {rightDownload, rightContribute},
  });

  @override
  State<ShareEditDialog> createState() => ShareEditDialogState();
}

class ShareEditDialogState extends State<ShareEditDialog> {
  late final TextEditingController _label =
      TextEditingController(text: widget.link.label);

  late EditExpiry _expiry =
      widget.link.expires.isEmpty ? EditExpiry.never : EditExpiry.keep;

  DateTime? _expiryDate;

  late int _maxPrivacy = widget.link.maxPrivacy;

  late int _minRating = widget.link.minRating;

  late String _photoLabel = widget.link.photoLabel;

  late Set<String> _rights = {
    rightView,
    for (var right in widget.link.rights) right.name,
  };

  bool _busy = false;

  String? _refusal;

  DateTime get _now => widget.now ?? DateTime.now();

  @override
  void dispose() {
    _label.dispose();
    super.dispose();
  }

  String get _expiresAt => switch (_expiry) {
        EditExpiry.keep => widget.link.expires,
        EditExpiry.never => "",
        EditExpiry.day =>
          _now.add(const Duration(days: 1)).toUtc().toIso8601String(),
        EditExpiry.week =>
          _now.add(const Duration(days: 7)).toUtc().toIso8601String(),
        EditExpiry.month =>
          _now.add(const Duration(days: 30)).toUtc().toIso8601String(),
        EditExpiry.date => _expiryDate == null
            ? widget.link.expires
            : _expiryDate!.toUtc().toIso8601String(),
      };

  ShareLink get _changed => ShareLink(
        id: widget.link.id,
        label: _label.text.trim(),
        expires: _expiresAt,
        maxPrivacy: _maxPrivacy,
        minRating: _minRating,
        photoLabel: _photoLabel,
        rights: [
          for (var right in allRights)
            if (_rights.contains(right)) RightName(name: right),
        ],
      );

  Future<void> _save() async {
    var l10n = AppLocalizations.of(context)!;
    var changed = _changed;
    if (takesAway(widget.link, changed)) {
      var confirmed = await confirmHere(
        context: context,
        dialogKey: "edit-link-less",
        title: l10n.editLinkLessTitle,
        message: l10n.editLinkLessMessage,
        confirmLabel: l10n.editLinkConfirm,
        confirmKey: "edit-link-less-confirmed",
      );
      if (confirmed != true || !mounted) {
        return;
      }
    }
    var done = await writeAction(
      context,
      () {
        setState(() {
          _busy = true;
          _refusal = null;
        });
        return widget.client.editShare(changed);
      },
      speaks: false,
      onRefused: (error) {
        if (mounted) {
          setState(() {
            _busy = false;
            _refusal = refusalMessage(error);
          });
        }
      },
    );
    if (done == null || !mounted) {
      return;
    }
    var answer = done.value;
    Navigator.of(context)
        .pop(answer.links.isEmpty ? changed : answer.links.first);
  }

  Future<void> _chooseExpiry(EditExpiry choice) async {
    if (choice != EditExpiry.date) {
      setState(() => _expiry = choice);
      return;
    }
    var picked = await showDatePicker(
      context: context,
      initialDate: _expiryDate ?? _now.add(const Duration(days: 7)),
      firstDate: _now,
      lastDate: _now.add(const Duration(days: 3650)),
    );
    if (picked == null || !mounted) {
      return;
    }
    setState(() {
      _expiry = EditExpiry.date;
      _expiryDate = picked;
    });
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var link = widget.link;
    var labels = [
      for (var label in link.albumLabels) label.name,
      if (link.photoLabel.isNotEmpty &&
          !link.albumLabels.any((label) => label.name == link.photoLabel))
        link.photoLabel,
    ];
    var refusal = _refusal;
    return AppAlertDialog(
      key: const Key("edit-link-dialog"),
      title: Text(l10n.editLinkTitle),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(l10n.editLinkNote),
              if (refusal != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(
                    refusal,
                    key: const Key("edit-link-refusal"),
                    style:
                        TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ),
              TextField(
                key: const Key("edit-link-label"),
                controller: _label,
                decoration: InputDecoration(
                  isDense: true,
                  label: Text(l10n.linkLabelLabel),
                ),
              ),
              _choice<EditExpiry>(
                key: "edit-link-expiry",
                label: l10n.expiresHeading,
                value: _expiry,
                choices: [
                  if (link.expires.isNotEmpty)
                    (
                      EditExpiry.keep,
                      linkExpired(link, now: _now)
                          ? l10n.linkExpiredOn(dayOf(link.expires))
                          : l10n.expiryAsNow(dayOf(link.expires)),
                    ),
                  (EditExpiry.never, l10n.expiryNever),
                  (EditExpiry.day, l10n.expiryOneDay),
                  (EditExpiry.week, l10n.expiryOneWeek),
                  (EditExpiry.month, l10n.expiryOneMonth),
                  (
                    EditExpiry.date,
                    _expiry == EditExpiry.date && _expiryDate != null
                        ? DateFormat.yMMMd().format(_expiryDate!)
                        : l10n.expiryPickDate,
                  ),
                ],
                onChanged: _chooseExpiry,
              ),
              if (labels.isNotEmpty)
                _choice<String>(
                  key: "edit-link-photo-label",
                  label: l10n.labelFilterHeading,
                  value: _photoLabel,
                  choices: [
                    ("", l10n.labelFilterWholeAlbum),
                    for (var label in labels)
                      (label, l10n.labelFilterOnly(label)),
                  ],
                  onChanged: (value) => setState(() => _photoLabel = value),
                ),
              _choice<int>(
                key: "edit-link-shows",
                label: l10n.showsHeading,
                value: _maxPrivacy,
                choices: [
                  if (link.maxPrivacy > privacyMembers)
                    (link.maxPrivacy, l10n.privacyUpToMembers),
                  if (widget.mayShowMembers ||
                      link.maxPrivacy == privacyMembers)
                    (privacyMembers, l10n.privacyUpToMembers),
                  (privacyPublic, l10n.privacyPublicOnly),
                ],
                onChanged: (value) => setState(() => _maxPrivacy = value),
              ),
              _choice<int>(
                key: "edit-link-rating",
                label: l10n.lowestRatingHeading,
                value: _minRating,
                choices: [
                  for (var rating
                      in {-2, 0, 1, 2, link.minRating}.toList()..sort())
                    (
                      rating,
                      rating <= -2
                          ? l10n.linkRatingAllButTrash
                          : l10n.linkRatingAtLeast(ratingName(l10n, rating)),
                    ),
                ],
                onChanged: (value) => setState(() => _minRating = value),
              ),
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: InputDecorator(
                  decoration: InputDecoration(
                    isDense: true,
                    labelText: l10n.permissionMayHeading,
                    helperText: l10n.linkRightsHelp,
                  ),
                  child: Wrap(
                    spacing: 8,
                    runSpacing: 4,
                    children: [
                      for (var right in const [rightDownload, rightContribute])
                        if (widget.grantableRights.contains(right) ||
                            link.rights.any((had) => had.name == right))
                          FilterChip(
                            key: Key("edit-link-right-$right"),
                            visualDensity: VisualDensity.compact,
                            label: Text(rightLabel(l10n, right)),
                            selected: _rights.contains(right),
                            onSelected: _busy
                                ? null
                                : (value) => setState(() {
                                      _rights = {
                                        for (var held in _rights)
                                          if (held != right) held,
                                        if (value) right,
                                      };
                                    }),
                          ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          key: const Key("edit-link-cancel"),
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: Text(l10n.cancel),
        ),
        FilledButton(
          key: const Key("edit-link-save"),
          onPressed: _busy ? null : _save,
          child: Text(l10n.save),
        ),
      ],
    );
  }

  /// One question whose answer is one of [choices], as a dropdown under its
  /// [label] — the rows of the share dialog's form.
  Widget _choice<T>({
    required String key,
    required String label,
    required T value,
    required List<(T, String)> choices,
    required ValueChanged<T> onChanged,
  }) =>
      Padding(
        padding: const EdgeInsets.only(top: 12),
        child: InputDecorator(
          decoration: InputDecoration(isDense: true, labelText: label),
          child: DropdownButtonHideUnderline(
            child: DropdownButton<T>(
              key: Key(key),
              value: value,
              isDense: true,
              isExpanded: true,
              items: [
                for (var (choice, text) in choices)
                  DropdownMenuItem<T>(
                    value: choice,
                    child: Text(text, overflow: TextOverflow.ellipsis),
                  ),
              ],
              onChanged: _busy
                  ? null
                  : (picked) {
                      if (picked != null) {
                        onChanged(picked);
                      }
                    },
            ),
          ),
        ),
      );
}
