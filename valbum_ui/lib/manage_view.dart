/// The management sections of the server settings (issue #55): the devices
/// this person is signed in on, the users of this server, and the invitations
/// that are still open.
///
/// All three are lists of the same shape — ask the server, show what it
/// answers, offer the one action that belongs to a row, show the server's own
/// sentence when it refuses — so they are written the same way and live
/// together here rather than swelling `settings.dart`. Each is a section of
/// the settings screen, not a screen of its own: they are all about *this
/// device and this person*, which is what the settings screen is.
///
/// Groups are the exception and have a screen of their own, see
/// `groups_view.dart`: a group is about other people, it outlives the device,
/// and it is reached from here.
library;

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'caller.dart';
import 'client.dart';
import 'groups_view.dart';
import 'resource.dart';
import 'settings.dart';

/// The day of an ISO-8601 instant, in the reader's own time zone.
///
/// An instant the app cannot parse is shown as it came: an unreadable date is
/// still more than no date, and it says which server spelled it that way.
String dayOf(String instant) {
  try {
    return DateFormat.yMMMd().format(DateTime.parse(instant).toLocal());
  } catch (_) {
    return instant;
  }
}

/// The key of the "My devices" section.
const Key devicesSectionKey = Key("settings.devices");

/// The key of the users section, which only the administrator is shown.
const Key usersSectionKey = Key("settings.users");

/// The key of the pending invitations section.
const Key invitationsSectionKey = Key("settings.invitations");

/// The key of the "Groups…" button.
const Key groupsButtonKey = Key("settings.groups");

/// The heading and the explanation every section of this library opens with.
List<Widget> sectionHead(BuildContext context, String title, String lead) => [
      const SizedBox(height: 24),
      const Divider(),
      const SizedBox(height: 8),
      Text(title, style: Theme.of(context).textTheme.titleMedium),
      const SizedBox(height: 8),
      Text(lead),
      const SizedBox(height: 8),
    ];

/// What a section shows while it is waiting for the server.
Widget sectionProgress(String what) => Row(
      children: [
        const SizedBox(
          width: 16,
          height: 16,
          child: CircularProgressIndicator(strokeWidth: 2),
        ),
        const SizedBox(width: 8),
        Text(what),
      ],
    );

/// What a section shows when the server refused, or could not be reached.
Widget sectionProblem(BuildContext context, String message, Key key) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Text(
        message,
        key: key,
        style: TextStyle(color: Theme.of(context).colorScheme.error),
      ),
    );

/// The devices this person is signed in on, and the way to sign one out
/// (issue #55).
///
/// Only ever the caller's own devices — that is all the server answers — and
/// the device the app runs on is marked, because signing *it* out is allowed
/// and means something else than removing another one: this device forgets its
/// token, which is exactly what "Sign out" above does, see [onSignedOutHere].
class DevicesSection extends StatefulWidget {
  /// The client talking to the saved server, carrying this device's token.
  final VAlbumClient client;

  /// Drops the token of this device, after the server signed it out.
  ///
  /// The settings own the store, so they do it: this section only knows that
  /// the token it was talking with proves nothing any more.
  final Future<void> Function() onSignedOutHere;

  const DevicesSection({
    super.key,
    required this.client,
    required this.onSignedOutHere,
  });

  @override
  State<DevicesSection> createState() => DevicesSectionState();
}

class DevicesSectionState extends State<DevicesSection> {
  /// The devices, `null` while they are being read.
  List<DeviceEntry>? _devices;

  /// The server's reason for the last refusal, `null` while all is well.
  String? _problem;

  /// Whether a request of this section is running.
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      var answer = await widget.client.devices();
      if (mounted) {
        setState(() {
          _devices = answer.devices;
          _problem = null;
        });
      }
    } catch (error) {
      if (mounted) {
        setState(() {
          _devices = const [];
          _problem = refusalMessage(error);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    var devices = _devices;
    var problem = _problem;
    return Column(
      key: devicesSectionKey,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ...sectionHead(
          context,
          "My devices",
          "Every device you signed in on holds a token of its own. Removing "
              "one here makes that token worthless; the device has to sign in "
              "again.",
        ),
        if (devices == null) sectionProgress("Asking the server..."),
        if (problem != null)
          sectionProblem(context, problem, const Key("settings.devices.error")),
        if (devices != null && problem == null && devices.isEmpty)
          const Text("No device is signed in.",
              key: Key("settings.devices.empty")),
        for (var device in devices ?? const <DeviceEntry>[])
          ListTile(
            key: Key("device-${device.id}"),
            contentPadding: EdgeInsets.zero,
            leading: Icon(device.current ? Icons.smartphone : Icons.devices),
            title: Text(
              device.current ? "${device.name} (this device)" : device.name,
            ),
            subtitle: Text(
              device.created.isEmpty
                  ? "Paired at an unknown time"
                  : "Paired on ${dayOf(device.created)}",
            ),
            trailing: IconButton(
              key: Key("device-remove-${device.id}"),
              icon: Icon(device.current ? Icons.logout : Icons.delete_outline),
              tooltip: device.current ? "Sign out here" : "Remove",
              onPressed: _busy ? null : () => _remove(device),
            ),
          ),
      ],
    );
  }

  /// Signs [device] out, after asking.
  ///
  /// Removing the asking device is "sign out here": the server takes its entry
  /// away and this app drops the token it was talking with, exactly as the
  /// sign-out button does — the section goes with it.
  Future<void> _remove(DeviceEntry device) async {
    var confirmed = await confirmHere(
      context: context,
      dialogKey: "device-confirm",
      title:
          device.current ? "Sign out this device?" : "Remove '${device.name}'?",
      message: device.current
          ? "This device forgets its sign-in and talks to the server "
              "anonymously again. You can sign in again at any time."
          : "'${device.name}' stops being signed in. It has to sign in again "
              "before it can change anything.",
      confirmLabel: device.current ? "Sign out" : "Remove",
      confirmKey: "device-confirmed",
    );
    if (confirmed != true || !mounted) {
      return;
    }
    setState(() {
      _busy = true;
      _problem = null;
    });
    DeviceList answer;
    try {
      answer = await widget.client.unpair(device.id);
    } catch (error) {
      if (mounted) {
        setState(() {
          _busy = false;
          _problem = refusalMessage(error);
        });
      }
      return;
    }
    if (device.current) {
      // The token this section was talking with is gone; the device forgets
      // it, and the settings take the section away with the sign-in.
      await widget.onSignedOutHere();
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() {
      _busy = false;
      _devices = answer.devices;
    });
  }
}

/// The users of this server, for the administrator alone (issue #55).
///
/// Who is here, what they are, where their library lies and on how many
/// devices they are signed in — and the one thing an administrator does with a
/// guest: make them a member, which gives them a library of their own.
class UsersSection extends StatefulWidget {
  final VAlbumClient client;

  const UsersSection({super.key, required this.client});

  @override
  State<UsersSection> createState() => UsersSectionState();
}

class UsersSectionState extends State<UsersSection> {
  /// The users, `null` while they are being read.
  List<UserEntry>? _users;

  /// The server's reason for the last refusal, `null` while all is well.
  String? _problem;

  /// Whether a request of this section is running.
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      var answer = await widget.client.users();
      if (mounted) {
        setState(() {
          _users = answer.users;
          _problem = null;
        });
      }
    } catch (error) {
      if (mounted) {
        setState(() {
          _users = const [];
          _problem = refusalMessage(error);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    var users = _users;
    var problem = _problem;
    return Column(
      key: usersSectionKey,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ...sectionHead(
          context,
          "Users",
          "Everybody who has an account on this server. A guest has no library "
              "of their own until you make them a member.",
        ),
        if (users == null) sectionProgress("Asking the server..."),
        if (problem != null)
          sectionProblem(context, problem, const Key("settings.users.error")),
        for (var user in users ?? const <UserEntry>[])
          ListTile(
            key: Key("user-${user.name}"),
            contentPadding: EdgeInsets.zero,
            leading: Icon(
              user.role == roleAdmin
                  ? Icons.admin_panel_settings
                  : user.role == roleGuest
                      ? Icons.person_outline
                      : Icons.person,
            ),
            title: Text(userDisplayName(user.name)),
            subtitle: Text(_describe(user)),
            trailing: user.role == roleGuest
                ? TextButton(
                    key: Key("user-promote-${user.name}"),
                    onPressed: _busy ? null : () => _promote(user),
                    child: const Text("Make member"),
                  )
                : null,
          ),
      ],
    );
  }

  /// The line under a user's name: what they are, where they are, and since
  /// when.
  String _describe(UserEntry user) {
    var parts = <String>[
      user.role.isEmpty ? "unknown role" : user.role,
      user.role == roleGuest
          ? "no library of their own"
          : "library: ${spaceDisplayName(user.space)}",
      "${user.devices} device${user.devices == 1 ? "" : "s"}",
    ];
    if (user.created.isNotEmpty) {
      parts.add("since ${dayOf(user.created)}");
    }
    return parts.join(" — ");
  }

  /// Makes the guest [user] a member, after asking.
  Future<void> _promote(UserEntry user) async {
    var confirmed = await confirmHere(
      context: context,
      dialogKey: "promote-confirm",
      title: "Make ${user.name} a member?",
      message: "A folder of their own is created for them, and they can put "
          "albums into it. There is no way back.",
      confirmLabel: "Make member",
      confirmKey: "promote-confirmed",
    );
    if (confirmed != true || !mounted) {
      return;
    }
    setState(() {
      _busy = true;
      _problem = null;
    });
    UserEntry answer;
    try {
      answer = await widget.client.promote(user.name);
    } catch (error) {
      if (mounted) {
        setState(() {
          _busy = false;
          _problem = refusalMessage(error);
        });
      }
      return;
    }
    if (!mounted) {
      return;
    }
    // What the server answered takes the place of the row it is about: the
    // rest of the list did not change, and nothing else has to be fetched.
    setState(() {
      _busy = false;
      _users = [
        for (var known in _users ?? const <UserEntry>[])
          if (known.name == answer.name) answer else known,
      ];
    });
  }
}

/// The invitations that are still open (issue #55).
///
/// The administrator is answered every invitation of this server, a member
/// their own — so this section is shown to both, beside the "Invite…" button
/// that fills it. An invitation that was accepted or withdrawn is not pending
/// any more and is not listed: what is listed is what is still worth
/// withdrawing.
class InvitationsSection extends StatefulWidget {
  final VAlbumClient client;

  /// Counts the invitations issued elsewhere on this screen; a change makes
  /// the list read itself again, see [didUpdateWidget].
  final int generation;

  const InvitationsSection({
    super.key,
    required this.client,
    this.generation = 0,
  });

  @override
  State<InvitationsSection> createState() => InvitationsSectionState();
}

class InvitationsSectionState extends State<InvitationsSection> {
  /// The invitations, `null` while they are being read.
  List<Invitation>? _invitations;

  /// The server's reason for the last refusal, `null` while all is well.
  String? _problem;

  /// Whether a request of this section is running.
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(InvitationsSection oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.generation != widget.generation) {
      _load();
    }
  }

  Future<void> _load() async {
    try {
      var answer = await widget.client.invitations();
      if (mounted) {
        setState(() {
          _invitations = answer.invitations;
          _problem = null;
        });
      }
    } catch (error) {
      if (mounted) {
        setState(() {
          _invitations = const [];
          _problem = refusalMessage(error);
        });
      }
    }
  }

  /// Whether [invitation] can still be accepted by somebody.
  ///
  /// An accepted one created its user and a withdrawn one is refused from then
  /// on; neither is pending. An expired one is listed — it is still a record
  /// somebody holds a link to, and withdrawing it tidies the list — and is
  /// labelled as expired.
  static bool pending(Invitation invitation) =>
      invitation.used.isEmpty && invitation.revoked.isEmpty;

  /// Whether the instant of [invitation] has passed.
  static bool expired(Invitation invitation) {
    if (invitation.expires.isEmpty) {
      return false;
    }
    try {
      return DateTime.parse(invitation.expires).isBefore(DateTime.now());
    } catch (_) {
      return false;
    }
  }

  @override
  Widget build(BuildContext context) {
    var all = _invitations;
    var problem = _problem;
    var open = [
      for (var invitation in all ?? const <Invitation>[])
        if (pending(invitation)) invitation,
    ];
    return Column(
      key: invitationsSectionKey,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 16),
        Text("Open invitations", style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 8),
        if (all == null) sectionProgress("Asking the server..."),
        if (problem != null)
          sectionProblem(
              context, problem, const Key("settings.invitations.error")),
        if (all != null && problem == null && open.isEmpty)
          const Text("No invitation is waiting to be accepted.",
              key: Key("settings.invitations.empty")),
        for (var invitation in open)
          ListTile(
            key: Key("invitation-${invitation.id}"),
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.mail_outline),
            title: Text(_headline(invitation)),
            subtitle: Text(_describe(invitation)),
            trailing: IconButton(
              key: Key("invitation-revoke-${invitation.id}"),
              icon: const Icon(Icons.cancel_outlined),
              tooltip: "Withdraw",
              onPressed: _busy ? null : () => _revoke(invitation),
            ),
          ),
      ],
    );
  }

  /// Whom the invitation makes what, and by whom.
  String _headline(Invitation invitation) {
    var role = invitation.role == roleGuest ? "guest" : "member";
    var by = invitation.invitedBy.isEmpty
        ? ""
        : " by ${userDisplayName(invitation.invitedBy)}";
    return "As a $role$by";
  }

  /// The line under an invitation: the note it carries and how long it lives.
  String _describe(Invitation invitation) {
    var parts = <String>[];
    if (invitation.note.trim().isNotEmpty) {
      parts.add(invitation.note.trim());
    }
    if (invitation.expires.isEmpty) {
      parts.add("expires: never");
    } else if (expired(invitation)) {
      parts.add("expired on ${dayOf(invitation.expires)}");
    } else {
      parts.add("expires ${dayOf(invitation.expires)}");
    }
    return parts.join(" — ");
  }

  /// Withdraws [invitation], after asking.
  Future<void> _revoke(Invitation invitation) async {
    var confirmed = await confirmHere(
      context: context,
      dialogKey: "uninvite-confirm",
      title: "Withdraw this invitation?",
      message: "The link stops working. Somebody who already accepted it keeps "
          "their account.",
      confirmLabel: "Withdraw",
      confirmKey: "uninvite-confirmed",
    );
    if (confirmed != true || !mounted) {
      return;
    }
    setState(() {
      _busy = true;
      _problem = null;
    });
    InvitationList answer;
    try {
      answer = await widget.client.uninvite(invitation.id);
    } catch (error) {
      if (mounted) {
        setState(() {
          _busy = false;
          _problem = refusalMessage(error);
        });
      }
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() {
      _busy = false;
      _invitations = answer.invitations;
    });
  }
}
