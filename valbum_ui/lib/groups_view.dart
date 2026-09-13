/// Groups: the screen that manages them and the dialog that names one and
/// ticks its members (issues #49 and #55).
///
/// A group is a name for a handful of people, so that a grant can be made out
/// to "family" instead of to five users one after the other. Until issue #55
/// a group could only be made where it was needed — inside the share dialog,
/// beside the grant it was about to carry — which meant there was no way to
/// look at the groups one has, and no way to give one a better name. This
/// screen is that way: it lists them, creates them, changes their members,
/// renames them and removes them.
///
/// [GroupDialog] lives here rather than in `share_view.dart` because both
/// screens open it; sharing imports it from here and behaves exactly as it
/// did.
library;

import 'package:flutter/material.dart';

import 'caller.dart';
import 'client.dart';
import 'resource.dart';

/// Opens the screen managing the caller's groups.
///
/// Offered to a member and to the administrator; a guest owns nothing to
/// share and is offered nothing, see `ServerSettingsScreen`.
Future<void> openGroupsScreen(BuildContext context, VAlbumClient client) =>
    Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => GroupsScreen(client: client)),
    );

/// Lists the groups the caller owns and is in, and manages the own ones.
class GroupsScreen extends StatefulWidget {
  final VAlbumClient client;

  const GroupsScreen({super.key, required this.client});

  @override
  State<GroupsScreen> createState() => GroupsScreenState();
}

class GroupsScreenState extends State<GroupsScreen> {
  /// The groups, `null` while they are being read.
  List<Group>? _groups;

  /// The users this server knows; empty where it refuses the list, which only
  /// means that a group's members cannot be ticked here.
  List<UserEntry> _users = const [];

  /// The server's reason for the last refusal, `null` while all is well.
  String? _error;

  /// Whether a request of this screen is running.
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      var answer = await widget.client.groups();
      if (mounted) {
        setState(() {
          _groups = answer.groups;
          _error = null;
        });
      }
    } catch (error) {
      if (mounted) {
        setState(() {
          _groups = const [];
          _error = refusalMessage(error);
        });
      }
    }
    try {
      var users = await widget.client.users();
      if (mounted) {
        setState(() => _users = users.users);
      }
    } catch (_) {
      // No user list: the groups are still listed, only their members cannot
      // be ticked from a list of names.
    }
  }

  /// Whether the caller may change [group].
  ///
  /// Its owner may, and so does the administrator; a group of somebody else's
  /// is listed (it can be shared with) but not touched. An answer that names
  /// no owner is an older server's, and is left changeable — the server
  /// refuses what it must, with its own sentence.
  bool _mine(Group group) =>
      group.owner.isEmpty ||
      group.owner == widget.client.userName ||
      CallerInfo.maybeOf(context)?.role == roleAdmin;

  @override
  Widget build(BuildContext context) {
    var groups = _groups;
    var error = _error;
    return Scaffold(
      appBar: AppBar(title: const Text("Groups")),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 640),
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              const Text(
                "A group is a name for a handful of people. Sharing with the "
                "group shares with everybody in it, now and later.",
              ),
              const SizedBox(height: 16),
              if (error != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: Text(error, key: const Key("groups-error")),
                ),
              if (groups == null)
                const Center(child: CircularProgressIndicator())
              else if (groups.isEmpty)
                const Text("No groups yet.", key: Key("groups-empty")),
              for (var group in groups ?? const <Group>[])
                ListTile(
                  key: Key("group-${group.name}"),
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(Icons.group),
                  title: Text(group.name),
                  subtitle: Text(
                    group.members.isEmpty
                        ? "no members"
                        : [for (var member in group.members) member.name]
                            .join(", "),
                  ),
                  trailing: _mine(group)
                      ? Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            IconButton(
                              key: Key("group-edit-${group.name}"),
                              icon: const Icon(Icons.edit),
                              tooltip: "Edit members",
                              onPressed: _busy ? null : () => _edit(group),
                            ),
                            IconButton(
                              key: Key("group-rename-${group.name}"),
                              icon: const Icon(Icons.drive_file_rename_outline),
                              tooltip: "Rename",
                              onPressed: _busy ? null : () => _rename(group),
                            ),
                            IconButton(
                              key: Key("group-remove-${group.name}"),
                              icon: const Icon(Icons.group_remove),
                              tooltip: "Remove group",
                              onPressed: _busy ? null : () => _remove(group),
                            ),
                          ],
                        )
                      : null,
                ),
              const SizedBox(height: 16),
              OutlinedButton.icon(
                key: const Key("group-create"),
                onPressed: _busy ? null : _create,
                icon: const Icon(Icons.group_add),
                label: const Text("New group…"),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _create() async {
    var group = await showDialog<Group>(
      context: context,
      builder: (context) => GroupDialog(users: _users),
    );
    if (group == null || !mounted) {
      return;
    }
    await _run(() => widget.client.saveGroup(group));
  }

  Future<void> _edit(Group group) async {
    var edited = await showDialog<Group>(
      context: context,
      builder: (context) => GroupDialog(users: _users, group: group),
    );
    if (edited == null || !mounted) {
      return;
    }
    await _run(() => widget.client.saveGroup(edited));
  }

  Future<void> _rename(Group group) async {
    var newName = await showDialog<String>(
      context: context,
      builder: (context) => GroupRenameDialog(group: group),
    );
    if (newName == null || !mounted || newName == group.name) {
      return;
    }
    await _run(() => widget.client.renameGroup(group.name, newName));
  }

  Future<void> _remove(Group group) async {
    var confirmed = await confirmHere(
      context: context,
      dialogKey: "group-remove-confirm",
      title: "Remove '${group.name}'?",
      message: "What was shared with the group stops being shared. The people "
          "in it keep their own albums.",
      confirmLabel: "Remove",
      confirmKey: "group-remove-confirmed",
    );
    if (confirmed != true || !mounted) {
      return;
    }
    await _run(() => widget.client.removeGroup(group.name));
  }

  /// Runs one request of this screen and shows the list it answers, or the
  /// reason it was refused.
  Future<void> _run(Future<GroupList> Function() request) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    GroupList answer;
    try {
      answer = await request();
    } catch (error) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = refusalMessage(error);
        });
      }
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() {
      _busy = false;
      _groups = answer.groups;
    });
  }
}

/// The reason a request was refused, as it is shown to the user.
///
/// A [VAlbumException] carries the server's own sentence (the `ErrorInfo` of
/// the refusal); anything else — a transport failure — is said as it is, so
/// that nothing ever fails silently.
String refusalMessage(Object error) =>
    error is VAlbumException ? error.message : "$error";

/// Asks the user before something is thrown away, answering their choice.
///
/// One dialog for every "are you sure?" of the management screens, so that
/// they all read and behave the same way.
Future<bool?> confirmHere({
  required BuildContext context,
  required String dialogKey,
  required String title,
  required String message,
  required String confirmLabel,
  required String confirmKey,
}) =>
    showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        key: Key(dialogKey),
        title: Text(title),
        content: Text(message),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text("Cancel"),
          ),
          FilledButton(
            key: Key(confirmKey),
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(confirmLabel),
          ),
        ],
      ),
    );

/// Gives a group a better name, see [VAlbumClient.renameGroup].
///
/// A rename used to be impossible — the name was what a grant named, and
/// changing it would silently have unshared everything. The server rewrites
/// the grants in the same step since issue #55, so the dialog says exactly
/// that: what was shared with the group stays shared.
class GroupRenameDialog extends StatefulWidget {
  final Group group;

  const GroupRenameDialog({super.key, required this.group});

  @override
  State<GroupRenameDialog> createState() => GroupRenameDialogState();
}

class GroupRenameDialogState extends State<GroupRenameDialog> {
  late final TextEditingController _name =
      TextEditingController(text: widget.group.name);

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        key: const Key("group-rename-dialog"),
        title: Text("Rename '${widget.group.name}'"),
        content: SizedBox(
          width: 400,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                key: const Key("group-new-name"),
                controller: _name,
                autofocus: true,
                autocorrect: false,
                decoration: const InputDecoration(label: Text("Name")),
                onSubmitted: (_) => _save(),
              ),
              const SizedBox(height: 8),
              const Text(
                "What was shared with this group stays shared: the grants "
                "naming it are renamed along with it.",
                key: Key("group-rename-note"),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text("Cancel"),
          ),
          ElevatedButton(
            key: const Key("group-rename-save"),
            onPressed: _save,
            child: const Text("Rename"),
          ),
        ],
      );

  void _save() {
    var name = _name.text.trim();
    if (name.isEmpty) {
      return;
    }
    Navigator.of(context).pop(name);
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
                  // A group is renamed where the grants naming it are renamed
                  // along with it, see [GroupRenameDialog] — never by typing
                  // over the name of the group being edited here.
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
