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

import 'client.dart';
import 'move_view.dart' show showRefusal;
import 'offline.dart';
import 'resource.dart';
import 'rights.dart';

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
/// never reaches one). Three things must hold before the question is even
/// asked, and all three are already on the screen: somebody is signed in, the
/// caller holds every right here, and the path does not name another user's
/// space. Without them the answer can only be "no", and asking would put a
/// `?type=grants` request behind every folder the app shows — including every
/// folder an anonymous caller browses.
bool couldManageGrants(
  VAlbumClient client,
  List<String> path,
  Rights rights,
) =>
    (client.token ?? "").isNotEmpty &&
    rights.complete &&
    spaceOwnerOf(path) == null;

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
