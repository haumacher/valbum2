/// "Share with…": the grants on a folder, and the groups needed to make them
/// (issue #49).
///
/// Sharing is one mechanism, the grant: a subject (a user, a named group, or
/// everybody) may do something (view, download, contribute, edit) on a folder
/// and everything below it. This dialog shows the grants that cover the folder
/// — the ones made here and the ones inherited from above — revokes them where
/// they were made, and adds new ones. The management screens proper are #55;
/// what is here is what sharing needs.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';

import 'album_edit.dart' show privacyMembers, privacyPublic;
import 'caller.dart';
import 'client.dart';
import 'move_view.dart' show showRefusal;
import 'offline.dart';
import 'resource.dart';
import 'rights.dart';
import 'share_session.dart' show ratingFloorLabel;
import 'urls.dart';

/// The subject naming everybody, signed in or not.
const String anonymousSubject = "anonymous";

/// The subject prefix of a single user.
const String userSubjectPrefix = "user:";

/// The subject prefix of a named group.
const String groupSubjectPrefix = "group:";

/// The subject naming the given user.
String userSubject(String name) => "$userSubjectPrefix$name";

/// The subject naming the given group.
String groupSubject(String name) => "$groupSubjectPrefix$name";

/// How a grant's subject is named on the screen.
String subjectLabel(String subject) {
  if (subject == anonymousSubject) {
    return "Everybody (no sign-in)";
  }
  if (subject.startsWith(userSubjectPrefix)) {
    return subject.substring(userSubjectPrefix.length);
  }
  if (subject.startsWith(groupSubjectPrefix)) {
    return "${subject.substring(groupSubjectPrefix.length)} (group)";
  }
  return subject;
}

/// What everybody is warned about wherever the anonymous subject is offered.
const String anonymousNote =
    "Everybody means everybody, signed in or not — and only the images marked "
    "public are ever shown to them.";

/// Whether it is worth asking the server whether this caller manages the
/// grants of the folder at [path], see
/// [VAlbumRouterDelegate.mayManageGrants].
///
/// Grants are managed by the owner of the space a folder lies in (and by the
/// administrator, who holds no rights in anybody else's space and therefore
/// never reaches one). Four things must hold before the question is even
/// asked, and all four are already on the screen: somebody is signed in, the
/// caller holds every right here, the path does not name another user's space,
/// and that caller is not a guest. Without them the answer can only be "no",
/// and asking would put a `?type=grants` request behind every folder the app
/// shows — including every folder an anonymous caller browses.
///
/// [isGuest] is the fourth (issue #52). A guest's own root reads exactly like
/// an owner's folder — every right, no other space named — and `?type=grants`
/// there answers a perfectly ordinary empty list, because there is nothing to
/// list; it is the *grant* that the server refuses, with
/// `AuthService.GUEST_GRANT_REFUSED`. A guest's library is what others share
/// with them and there is nothing in it that is theirs to share on, so the
/// entry is not offered and the question is not asked, see
/// [CallerInfo.isGuest].
bool couldManageGrants(
  VAlbumClient client,
  List<String> path,
  Rights rights, {
  bool isGuest = false,
}) =>
    (client.token ?? "").isNotEmpty &&
    rights.complete &&
    spaceOwnerOf(path) == null &&
    !isGuest;

/// Opens the share dialog on the folder at [path].
///
/// [label] names the folder in the title; the dialog is opened only where the
/// caller may manage the grants there, see
/// [VAlbumRouterDelegate.mayManageGrants]. Refused while the app is offline,
/// like every other write.
Future<void> shareWith({
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
    builder: (context) => ShareDialog(client: client, path: path, label: label),
  );
}

/// Shows, revokes and adds the grants covering one folder.
class ShareDialog extends StatefulWidget {
  final VAlbumClient client;

  /// The folder being shared, in the coordinates of the request — a path into
  /// somebody else's space still starts with `~owner`, see [ownerPathOf].
  final List<String> path;

  /// How the folder is named in the title, its last segment by default.
  final String? label;

  const ShareDialog({
    super.key,
    required this.client,
    required this.path,
    this.label,
  });

  @override
  State<ShareDialog> createState() => ShareDialogState();
}

class ShareDialogState extends State<ShareDialog> {
  /// The grants covering the folder, `null` while they are being read.
  List<Grant>? _grants;

  /// Why the grants could not be read, `null` while all is well.
  String? _error;

  /// The users this server knows, without the caller; empty where the server
  /// refuses the list (a guest may not see it) — the groups and "everybody"
  /// are still there to share with.
  List<UserEntry> _users = const [];

  /// The groups the caller owns and is in.
  List<Group> _groups = const [];

  /// The subject the new grant is for, `null` while none is picked.
  String? _subject;

  /// The rights the new grant carries, closed under the implications.
  Set<String> _picked = const {rightView};

  /// Whether a request of this dialog is running.
  bool _busy = false;

  /// The folder's path in the coordinates the grants are spelled in.
  String get ownerPath => ownerPathOf(widget.path);

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    await _loadGrants();
    // The lists are what the picker offers; a server that refuses one of them
    // (a guest may not see the users) simply offers less, it is not an error
    // of the dialog.
    try {
      var users = await widget.client.users();
      if (mounted) {
        setState(() => _users = [
              for (var user in users.users)
                if (user.name != widget.client.userName) user,
            ]);
      }
    } catch (_) {
      // No user list: the groups and "everybody" remain.
    }
    try {
      var groups = await widget.client.groups();
      if (mounted) {
        setState(() => _groups = groups.groups);
      }
    } catch (_) {
      // No groups: sharing with a single user remains.
    }
  }

  Future<void> _loadGrants() async {
    try {
      var answer = await widget.client.grants(widget.path);
      if (mounted) {
        setState(() {
          _grants = answer.grants;
          _error = null;
        });
      }
    } catch (error) {
      if (mounted) {
        setState(() {
          _grants = const [];
          _error = error is VAlbumException ? error.message : "$error";
        });
      }
    }
  }

  /// Whether the given grant was made further up and can only be revoked
  /// there.
  bool _inherited(Grant grant) => grant.path != ownerPath;

  @override
  Widget build(BuildContext context) {
    var name = widget.label ??
        (widget.path.isEmpty ? "the top level" : "'${widget.path.last}'");
    return AlertDialog(
      key: const Key("share-dialog"),
      title: Text("Share $name"),
      content: SizedBox(
        width: 460,
        height: 420,
        child: _grants == null
            ? const Center(child: CircularProgressIndicator())
            : SingleChildScrollView(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    ..._grantSection(context),
                    const Divider(),
                    ..._addSection(context),
                  ],
                ),
              ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text("Close"),
        ),
        ElevatedButton(
          key: const Key("share-apply"),
          onPressed: _subject == null || _busy ? null : _apply,
          child: const Text("Share"),
        ),
      ],
    );
  }

  /// The grants that cover this folder, the inherited ones marked.
  List<Widget> _grantSection(BuildContext context) {
    var grants = _grants ?? const <Grant>[];
    var error = _error;
    return [
      Text("Shared with", style: Theme.of(context).textTheme.titleSmall),
      if (error != null)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Text(error, key: const Key("share-error")),
        ),
      if (error == null && grants.isEmpty)
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 8),
          child: Text("Nobody yet.", key: Key("share-nobody")),
        ),
      for (var grant in grants)
        ListTile(
          key: Key("grant-${grant.subject}"),
          contentPadding: EdgeInsets.zero,
          dense: true,
          leading: Icon(_inherited(grant) ? Icons.arrow_upward : Icons.person),
          title: Text(subjectLabel(grant.subject)),
          subtitle: Text(
            _inherited(grant)
                ? "${_rightsOf(grant)} — inherited from "
                    "${_pathLabel(grant.path)}, revoke it there"
                : _rightsOf(grant),
          ),
          trailing: _inherited(grant)
              ? null
              : IconButton(
                  key: Key("revoke-${grant.subject}"),
                  icon: const Icon(Icons.link_off),
                  tooltip: "Revoke",
                  onPressed: _busy ? null : () => _revoke(grant),
                ),
        ),
    ];
  }

  /// The rights of a grant, as the list shows them.
  String _rightsOf(Grant grant) {
    var names = [
      for (var right in allRights)
        if (grant.rights.any((held) => held.name == right))
          rightLabels[right] ?? right,
    ];
    return names.isEmpty ? "nothing" : names.join(", ");
  }

  /// How a grant's own folder is named, the whole space having no name.
  String _pathLabel(String path) =>
      path.isEmpty ? "the whole space" : "'$path'";

  /// The picker of a subject and the rights the new grant carries.
  List<Widget> _addSection(BuildContext context) => [
        Text("Share with", style: Theme.of(context).textTheme.titleSmall),
        for (var user in _users)
          _subjectTile(
            subject: userSubject(user.name),
            icon: Icons.person_outline,
            title: user.name,
          ),
        for (var group in _groups)
          _subjectTile(
            subject: groupSubject(group.name),
            icon: Icons.group_outlined,
            title: "${group.name} (group)",
            subtitle: group.members.isEmpty
                ? "no members"
                : [for (var member in group.members) member.name].join(", "),
            // Only the owner of a group may change it; everybody else sees it
            // and can share with it, which is what a group is for.
            trailing: group.owner == widget.client.userName
                ? Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      IconButton(
                        key: Key("group-edit-${group.name}"),
                        icon: const Icon(Icons.edit),
                        tooltip: "Edit members",
                        onPressed: _busy ? null : () => _editGroup(group),
                      ),
                      IconButton(
                        key: Key("group-remove-${group.name}"),
                        icon: const Icon(Icons.group_remove),
                        tooltip: "Remove group",
                        onPressed: _busy ? null : () => _removeGroup(group),
                      ),
                    ],
                  )
                : null,
          ),
        _subjectTile(
          subject: anonymousSubject,
          icon: Icons.public,
          title: subjectLabel(anonymousSubject),
        ),
        // Says what everybody means, always — not only once it is picked.
        const Padding(
          padding: EdgeInsets.only(left: 16, bottom: 8),
          child: Text(
            anonymousNote,
            key: Key("anonymous-note"),
            style: TextStyle(fontSize: 12),
          ),
        ),
        ListTile(
          key: const Key("subject-new-group"),
          contentPadding: EdgeInsets.zero,
          dense: true,
          leading: const Icon(Icons.group_add),
          title: const Text("New group…"),
          onTap: _busy ? null : _newGroup,
        ),
        const SizedBox(height: 8),
        Text("May", style: Theme.of(context).textTheme.titleSmall),
        for (var right in allRights) _rightTile(right),
      ];

  Widget _subjectTile({
    required String subject,
    required IconData icon,
    required String title,
    String? subtitle,
    Widget? trailing,
  }) =>
      ListTile(
        key: Key("subject-$subject"),
        contentPadding: EdgeInsets.zero,
        dense: true,
        selected: _subject == subject,
        leading: Icon(icon),
        title: Text(title),
        subtitle: subtitle == null ? null : Text(subtitle),
        trailing: trailing,
        onTap: () => setState(() => _subject = subject),
      );

  /// One right, with the implications made visible: what a stronger right
  /// already carries is ticked and cannot be unticked on its own.
  Widget _rightTile(String right) {
    var implied = _picked.contains(right) && _impliedBy(right);
    return CheckboxListTile(
      key: Key("right-$right"),
      contentPadding: EdgeInsets.zero,
      dense: true,
      controlAffinity: ListTileControlAffinity.leading,
      value: _picked.contains(right),
      onChanged: implied ? null : (value) => _toggleRight(right, value ?? false),
      title: Text(rightLabels[right] ?? right),
      subtitle: Text(rightExplanations[right] ?? ""),
    );
  }

  /// Whether another picked right already carries [right], so that it cannot
  /// be dropped on its own.
  bool _impliedBy(String right) => _picked.any(
        (picked) =>
            picked != right && Rights.ofNames([picked]).names.contains(right),
      );

  void _toggleRight(String right, bool value) => setState(() {
        if (value) {
          _picked = Rights.ofNames({..._picked, right}).names;
        } else {
          _picked = Rights.ofNames(
            [
              for (var picked in _picked)
                if (picked != right) picked,
            ],
          ).names;
        }
      });

  /// Sends the new grant and reads the list again.
  ///
  /// The body carries exactly what the check boxes show, which is the closure
  /// of what was ticked: ticking "contribute" ticks "view" in front of the
  /// user, so "view" travels along. The server closes the set again — sending
  /// the closure only means that what was shown is what was sent.
  Future<void> _apply() async {
    var subject = _subject;
    if (subject == null) {
      return;
    }
    var messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    try {
      await widget.client.grant(
        widget.path,
        Grant(
          subject: subject,
          rights: [
            for (var right in allRights)
              if (_picked.contains(right)) RightName(name: right),
          ],
        ),
      );
    } catch (error) {
      showRefusal(messenger, error);
      if (mounted) {
        setState(() => _busy = false);
      }
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() {
      _busy = false;
      _subject = null;
      _picked = const {rightView};
    });
    await _loadGrants();
  }

  Future<void> _revoke(Grant grant) async {
    var messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    try {
      await widget.client.revoke(widget.path, grant.subject);
    } catch (error) {
      showRefusal(messenger, error);
      if (mounted) {
        setState(() => _busy = false);
      }
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() => _busy = false);
    await _loadGrants();
  }

  /// Creates a group and offers it at once, without the dialog being reopened.
  Future<void> _newGroup() async {
    var group = await showDialog<Group>(
      context: context,
      builder: (context) => GroupDialog(users: _users),
    );
    if (group == null || !mounted) {
      return;
    }
    await _saveGroup(group);
  }

  Future<void> _editGroup(Group group) async {
    var edited = await showDialog<Group>(
      context: context,
      builder: (context) => GroupDialog(users: _users, group: group),
    );
    if (edited == null || !mounted) {
      return;
    }
    await _saveGroup(edited);
  }

  Future<void> _saveGroup(Group group) async {
    var messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    GroupList answer;
    try {
      answer = await widget.client.saveGroup(group);
    } catch (error) {
      showRefusal(messenger, error);
      if (mounted) {
        setState(() => _busy = false);
      }
      return;
    }
    if (!mounted) {
      return;
    }
    // What the server stored, which knows the owner; an older answer that
    // says nothing leaves what was sent, so the group is selectable either
    // way.
    var stored = answer.groups.isEmpty ? group : answer.groups.first;
    setState(() {
      _busy = false;
      _groups = [
        for (var known in _groups)
          if (known.name != stored.name) known,
        stored,
      ];
      _subject = groupSubject(stored.name);
    });
  }

  Future<void> _removeGroup(Group group) async {
    var messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    try {
      await widget.client.removeGroup(group.name);
    } catch (error) {
      showRefusal(messenger, error);
      if (mounted) {
        setState(() => _busy = false);
      }
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() {
      _busy = false;
      _groups = [
        for (var known in _groups)
          if (known.name != group.name) known,
      ];
      if (_subject == groupSubject(group.name)) {
        _subject = null;
      }
    });
  }
}

/// Names a group and ticks its members, for creating one and for replacing
/// the members of one's own.
class GroupDialog extends StatefulWidget {
  /// The users that can be members.
  final List<UserEntry> users;

  /// The group being edited, `null` while a new one is being named.
  final Group? group;

  const GroupDialog({super.key, required this.users, this.group});

  @override
  State<GroupDialog> createState() => GroupDialogState();
}

class GroupDialogState extends State<GroupDialog> {
  late final TextEditingController _name =
      TextEditingController(text: widget.group?.name ?? "");

  late Set<String> _members = {
    for (var member in widget.group?.members ?? const <MemberName>[])
      member.name,
  };

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        key: const Key("group-dialog"),
        title: Text(widget.group == null ? "New group" : "Edit members"),
        content: SizedBox(
          width: 400,
          height: 320,
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  key: const Key("group-name"),
                  controller: _name,
                  // The name of a group cannot change: a grant names it, and
                  // renaming it would silently drop what was shared with it.
                  enabled: widget.group == null,
                  autofocus: widget.group == null,
                  decoration: const InputDecoration(label: Text("Name")),
                ),
                const SizedBox(height: 8),
                Text("Members", style: Theme.of(context).textTheme.titleSmall),
                if (widget.users.isEmpty)
                  const Text(
                    "This server does not tell you who else is here.",
                    key: Key("group-no-users"),
                  ),
                for (var user in widget.users)
                  CheckboxListTile(
                    key: Key("member-${user.name}"),
                    contentPadding: EdgeInsets.zero,
                    dense: true,
                    controlAffinity: ListTileControlAffinity.leading,
                    value: _members.contains(user.name),
                    onChanged: (value) => setState(() {
                      if (value ?? false) {
                        _members = {..._members, user.name};
                      } else {
                        _members = {
                          for (var member in _members)
                            if (member != user.name) member,
                        };
                      }
                    }),
                    title: Text(user.name),
                  ),
              ],
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text("Cancel"),
          ),
          ElevatedButton(
            key: const Key("group-save"),
            onPressed: _save,
            child: const Text("Save"),
          ),
        ],
      );

  void _save() {
    var name = _name.text.trim();
    if (name.isEmpty) {
      return;
    }
    Navigator.of(context).pop(
      Group(
        name: name,
        members: [
          // In the order the users are listed, so that the body of a request
          // does not depend on the order the boxes were ticked in.
          for (var user in widget.users)
            if (_members.contains(user.name)) MemberName(name: user.name),
        ],
      ),
    );
  }
}

/// How long a new share link lives, as the dialog offers it.
enum LinkExpiry {
  never("Never"),
  day("1 day"),
  week("1 week"),
  month("1 month"),
  date("A date…");

  /// How the choice is named on the screen.
  final String label;

  const LinkExpiry(this.label);

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
/// A dialog of its own, not a third section of [ShareDialog]: that one is
/// already two sections and 420 pixels tall, and a new link asks five more
/// questions (label, expiry, privacy ceiling, rating floor, rights) that have
/// nothing to do with picking a subject. Two menu entries, two dialogs — and
/// the one thing they share, that only the owner of the space is offered
/// either, is the same answer of the same `?type=grants` probe.
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
    var name = widget.label ??
        (widget.path.isEmpty ? "the top level" : "'${widget.path.last}'");
    return AlertDialog(
      key: const Key("share-link-dialog"),
      title: Text("Share $name by link"),
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
          child: const Text("Close"),
        ),
      ],
    );
  }

  /// The links covering this folder, the inherited ones marked.
  List<Widget> _linkSection(BuildContext context) {
    var links = _links ?? const <ShareLink>[];
    var error = _error;
    var refusal = _refusal;
    return [
      Text("Links", style: Theme.of(context).textTheme.titleSmall),
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
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 8),
          child: Text("No links yet.", key: Key("share-link-none")),
        ),
      for (var link in links)
        ListTile(
          key: Key("link-${link.id}"),
          contentPadding: EdgeInsets.zero,
          dense: true,
          isThreeLine: true,
          leading: Icon(_inherited(link) ? Icons.arrow_upward : Icons.link),
          title: Text(link.label.isEmpty ? "(no label)" : link.label),
          subtitle: Text(_describe(link)),
          trailing: _inherited(link) || link.revoked.isNotEmpty
              ? null
              : IconButton(
                  key: Key("withdraw-${link.id}"),
                  icon: const Icon(Icons.link_off),
                  tooltip: "Withdraw…",
                  onPressed: _busy ? null : () => _withdraw(link),
                ),
        ),
    ];
  }

  /// Whether the given link was made further up and can only be withdrawn
  /// there.
  bool _inherited(ShareLink link) => link.path != ownerPath;

  /// What a link shows and how long it lives, in one paragraph.
  String _describe(ShareLink link) {
    var parts = [
      _rightsOf(link),
      link.expires.isEmpty ? "never expires" : "expires ${_day(link.expires)}",
      link.maxPrivacy >= privacyMembers ? "up to members" : "public only",
      ratingFloorLabel(link.minRating),
    ];
    var text = parts.join(" · ");
    if (link.revoked.isNotEmpty) {
      return "$text\nwithdrawn ${_day(link.revoked)}";
    }
    if (_inherited(link)) {
      return "$text\ninherited from ${_pathLabel(link.path)}, "
          "withdraw it there";
    }
    return text;
  }

  /// The rights of a link, as the list shows them.
  String _rightsOf(ShareLink link) {
    var names = [
      for (var right in allRights)
        if (link.rights.any((held) => held.name == right))
          rightLabels[right] ?? right,
    ];
    return names.isEmpty ? "View" : names.join(", ");
  }

  /// The day of an ISO-8601 instant, in the viewer's own time zone.
  ///
  /// The instant itself is the server's business; what the author of a link
  /// wants to read is the day it runs out.
  String _day(String instant) {
    try {
      return DateFormat.yMMMd().format(DateTime.parse(instant).toLocal());
    } catch (_) {
      return instant;
    }
  }

  /// The entry opening the form of a new link.
  List<Widget> _newLinkTile() => [
        ListTile(
          key: const Key("new-link"),
          contentPadding: EdgeInsets.zero,
          dense: true,
          leading: const Icon(Icons.add_link),
          title: const Text("New link…"),
          onTap: _busy ? null : () => setState(() => _creating = true),
        ),
      ];

  /// The five questions a new link asks.
  List<Widget> _formSection(BuildContext context) {
    var titles = Theme.of(context).textTheme.titleSmall;
    return [
      Text("New link", style: titles),
      TextField(
        key: const Key("link-label"),
        controller: _label,
        autofocus: true,
        decoration: const InputDecoration(
          label: Text("Label"),
          helperText: "What this link is, for your own list.",
        ),
      ),
      const SizedBox(height: 8),
      Text("Expires", style: titles),
      for (var choice in LinkExpiry.values)
        _choiceTile(
          key: "expiry-${choice.name}",
          chosen: _expiry == choice,
          title: choice == LinkExpiry.date && _expiryDate != null
              ? DateFormat.yMMMd().format(_expiryDate!)
              : choice.label,
          onTap: () => _chooseExpiry(choice),
        ),
      const SizedBox(height: 8),
      Text("Shows", style: titles),
      _choiceTile(
        key: "privacy-public",
        chosen: _maxPrivacy == privacyPublic,
        title: "Public photos only",
        onTap: () => setState(() => _maxPrivacy = privacyPublic),
      ),
      _choiceTile(
        key: "privacy-members",
        chosen: _maxPrivacy == privacyMembers,
        title: "Up to what members see",
        subtitle: "A private photo is never shown through a link.",
        onTap: () => setState(() => _maxPrivacy = privacyMembers),
      ),
      const SizedBox(height: 8),
      Text("Lowest rating", style: titles),
      for (var rating in const [-2, -1, 0, 1, 2])
        _choiceTile(
          key: "rating-$rating",
          chosen: _minRating == rating,
          title: ratingFloorLabel(rating),
          onTap: () => setState(() => _minRating = rating),
        ),
      const SizedBox(height: 8),
      Text("May", style: titles),
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
      const Padding(
        padding: EdgeInsets.only(top: 8),
        child: Text(
          "A link never allows editing.",
          key: Key("link-no-edit"),
          style: TextStyle(fontSize: 12),
        ),
      ),
      const SizedBox(height: 8),
      Row(
        mainAxisAlignment: MainAxisAlignment.end,
        children: [
          TextButton(
            key: const Key("link-cancel"),
            onPressed: _busy ? null : () => setState(() => _creating = false),
            child: const Text("Cancel"),
          ),
          const SizedBox(width: 8),
          ElevatedButton(
            key: const Key("link-create"),
            onPressed: _busy ? null : _create,
            child: const Text("Create link"),
          ),
        ],
      ),
    ];
  }

  /// One choice of a group, ticked when it is the current one.
  ///
  /// A [ListTile], not a `RadioListTile`: the radio of the framework wants a
  /// `RadioGroup` ancestor since Flutter 3.32, and the dialog already shows
  /// its choices in the tile idiom of [ShareDialog].
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
  String _pathLabel(String path) =>
      path.isEmpty ? "the whole space" : "'$path'";

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
    var created = _created!;
    var url = absoluteServerUrl(widget.client.dataUrl, created.url);
    return [
      Text("The link", style: Theme.of(context).textTheme.titleSmall),
      const SizedBox(height: 8),
      Row(
        children: [
          Expanded(
            child: SelectableText(url, key: const Key("share-link-url")),
          ),
          IconButton(
            key: const Key("copy-link"),
            icon: const Icon(Icons.copy),
            tooltip: "Copy",
            onPressed: () => _copy(url),
          ),
        ],
      ),
      const SizedBox(height: 16),
      const Text(
        "Copy it now: the server keeps only its fingerprint and can never "
        "show it again. A lost link is withdrawn and made anew.",
        key: Key("share-link-once"),
      ),
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
          child: const Text("Done"),
        ),
      ),
    ];
  }

  Future<void> _copy(String url) async {
    var messenger = ScaffoldMessenger.of(context);
    await Clipboard.setData(ClipboardData(text: url));
    messenger.showSnackBar(
      const SnackBar(content: Text("The link was copied.")),
    );
  }

  /// Withdraws a link, after asking: a link somebody already has stops
  /// working the moment this is done.
  Future<void> _withdraw(ShareLink link) async {
    var name = link.label.isEmpty ? "this link" : "'${link.label}'";
    var confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        key: const Key("withdraw-confirm"),
        title: const Text("Withdraw the link?"),
        content: Text(
          "Anybody holding $name stops seeing the album at once. "
          "This cannot be undone; a new link can be made instead.",
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text("Cancel"),
          ),
          ElevatedButton(
            key: const Key("withdraw-confirm-ok"),
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text("Withdraw"),
          ),
        ],
      ),
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
