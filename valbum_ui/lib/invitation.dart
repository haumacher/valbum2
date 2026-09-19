/// Invitations, see issue #52: accepting one at `<context>/i/<token>/`, and
/// issuing one from the server settings.
///
/// An invitation is the mirror image of a share link. A share link is a
/// session that never becomes anything (`share_session.dart`): it carries its
/// token for as long as the page is open and stores nothing. An invitation
/// carries its token for exactly one question and one answer — "who invited me
/// and as what?", "here is my name" — and what comes out of it is an ordinary
/// device token, stored exactly as a sign-in with the pairing secret stores
/// one. Three things follow, and this library is where they are said once:
///
///  * the invitation token is **never** stored. It is a bearer for
///    `?type=auth` and a field of the pairing request, and nothing else ever
///    sees it;
///  * accepting *is* pairing: the app calls [VAlbumClient.pair] with the
///    invitation instead of the secret, and the answer is the same
///    [PairResponse] the settings screen already knows what to do with;
///  * once the device is signed in, the app has no business at
///    `<context>/i/<token>/` any more. It leaves that base for `<context>/` in
///    a full navigation, so that the token is gone from the URL and gone from
///    the history, see [leaveForUrl].
///
/// The web is where a link is *opened*; on a phone the same URL is pasted into
/// the server field, which recognises it exactly as the app base does, see
/// [serverLocationOf].
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;

import 'caller.dart';
import 'client.dart';
import 'manage_view.dart';
import 'platform.dart';
import 'resource.dart';
import 'settings.dart';
import 'urls.dart';

/// What an invitation promises, said to the person who opened it (issue #85).
///
/// The words of the settings, so that what an invitation promises and what the
/// invited person later reads about themselves are the same, see
/// [CallerPermission.roleWordYou]. A role the app does not know promises
/// nothing rather than the wrong thing, and the sentence then simply ends
/// after the invitation, see [invitationHeadline].
String invitationRoleName(String role) => CallerPermission.roleWordYou(role);

/// Who invited, and what they offered.
String invitationHeadline(String invitedBy, String role) {
  var may = invitationRoleName(role);
  return "${userDisplayName(invitedBy)} invited you to this album server"
      "${may.isEmpty ? "." : ": $may."}";
}

/// What being invited as a guest means, in one line.
///
/// Kept for the welcome screen of an invitation that a pre-#83 server issued
/// with the retired `guest` role; a guest is a `view` user now, see issue #85.
const String guestRoleExplanation =
    "A guest has no albums of their own: their library is what others share "
    "with them.";

/// The role a new invitation offers unless another is chosen (issue #85).
///
/// The least that still lets somebody see the family's pictures: whoever
/// invites can widen it in the dialog, and a permission given too generously
/// is not noticed until somebody changes what they should not have.
const String defaultInviteRole = roleView;

/// The clearance a new invitation offers unless another is chosen.
///
/// Not [clearancePublic]: an invited person is somebody the library's owner
/// knows, and the public level is what a link to a stranger gets.
const String defaultInviteClearance = clearanceNonPrivate;

/// The query parameter a dead invitation address is redirected with (#88).
///
/// A page load of `<context>[/<space>]/i/<token>/` whose token is no live
/// invitation of that space is answered `302` to the ordinary app base of that
/// space, with the reason here. The app reads it once, says it, and removes it
/// from the location again, see `VAlbumApp.location`.
const String invitationNoticeParameter = "invitation";

/// The invitation was accepted already, see [invitationNoticeParameter].
const String invitationUsed = "used";

/// The invitation's lifetime has run out.
const String invitationExpired = "expired";

/// The invitation was withdrawn by whoever issued it.
const String invitationWithdrawn = "withdrawn";

/// This server never issued such an invitation.
const String invitationUnknown = "unknown";

/// What the notice of a dead invitation address says, `null` for a reason this
/// build does not know (issue #88).
///
/// The used one is the reason this exists, and it is the one that depends on
/// the device: the browser that joined through the very link that is now used
/// up *is* signed in here, so it is told so by name. A device that is not
/// signed in is told the one thing that still works — a device code from the
/// device that accepted the invitation.
///
/// An unknown reason says nothing rather than something wrong; the parameter
/// is removed from the location either way.
String? invitationNoticeText(
  String reason, {
  required bool signedIn,
  String? userName,
}) {
  switch (reason) {
    case invitationUsed:
      if (!signedIn) {
        return "This invitation was already used. If you accepted it on "
            "another device, sign in here with a device code from that "
            "device; otherwise ask for a new invitation.";
      }
      var name = userName ?? "";
      if (name.isEmpty) {
        return "This invitation was already used \u2014 you are already "
            "signed in here.";
      }
      return "This invitation was already used \u2014 you are signed in here "
          "as $name.";
    case invitationExpired:
      return "This invitation has expired. Ask for a new one.";
    case invitationWithdrawn:
      return "This invitation was withdrawn.";
    case invitationUnknown:
      return "This is not an invitation of this server.";
    default:
      return null;
  }
}

/// The key of the user name field of the welcome screen.
const Key invitationUserFieldKey = Key("invitation.userName");

/// The key of the device name field of the welcome screen.
const Key invitationDeviceFieldKey = Key("invitation.deviceName");

/// The screen an invitation link opens: who invited, and the way in.
///
/// Everything a person needs to decide and nothing else — there is no server
/// to configure (the link names it), no album to look at (an invitation opens
/// none) and no way back (the person came here from a message). The one thing
/// asked is the name they want to be known by, because that is what the server
/// cannot choose for them.
class InvitationWelcomeScreen extends StatefulWidget {
  /// The client talking to the server that issued the invitation.
  ///
  /// It carries the invitation token as its bearer, which is what
  /// [VAlbumClient.authInfo] needed; the pairing request below carries no
  /// token at all and names the invitation in its body.
  final VAlbumClient client;

  /// What the server said the invitation offers.
  final InvitationInfo info;

  /// The invitation token, as the pairing request names it.
  final String token;

  /// The app base of the server, `<context>/` — what a device stores, and
  /// where the accepted invitation leaves for.
  final String appBase;

  /// Stores what the server answered: the server URL and the device token.
  ///
  /// The app does it, not this screen: which store a device writes to is the
  /// app's business, see `VAlbumApp`.
  final Future<void> Function(PairResponse answer) onJoined;

  /// Says that the invitation is gone (the server's `410`), with its reason.
  final void Function(String message) onGone;

  /// Leaves this page for the given URL; the platform's own navigation by
  /// default, see [leaveForUrl].
  final void Function(String url) openUrl;

  const InvitationWelcomeScreen({
    super.key,
    required this.client,
    required this.info,
    required this.token,
    required this.appBase,
    required this.onJoined,
    required this.onGone,
    this.openUrl = leaveForUrl,
  });

  @override
  State<InvitationWelcomeScreen> createState() =>
      InvitationWelcomeScreenState();
}

class InvitationWelcomeScreenState extends State<InvitationWelcomeScreen> {
  final TextEditingController _user = TextEditingController();

  late final TextEditingController _device =
      TextEditingController(text: defaultDeviceName());

  /// The server's reason for refusing the last attempt, shown at the field.
  String? _refusal;

  /// Whether a join is running.
  bool _joining = false;

  /// Who this device became, `null` while the invitation is not accepted yet.
  PairResponse? _joined;

  @override
  void dispose() {
    _user.dispose();
    _device.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        key: const Key("invitation-welcome"),
        body: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 480),
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children:
                    _joined == null ? _invitation(context) : _welcome(context),
              ),
            ),
          ),
        ),
      );

  /// The invitation itself, and the form that accepts it.
  List<Widget> _invitation(BuildContext context) {
    var info = widget.info;
    var refusal = _refusal;
    return [
      const Icon(Icons.mail_outline, size: 48),
      const SizedBox(height: 16),
      Text(
        invitationHeadline(info.invitedBy, info.role),
        key: const Key("invitation-headline"),
        style: Theme.of(context).textTheme.titleMedium,
      ),
      if (info.note.trim().isNotEmpty) ...[
        const SizedBox(height: 8),
        Text(info.note.trim(), key: const Key("invitation-note")),
      ],
      if (info.role == roleGuest) ...[
        const SizedBox(height: 8),
        const Text(guestRoleExplanation, key: Key("invitation-guest-note")),
      ],
      const SizedBox(height: 24),
      TextField(
        key: invitationUserFieldKey,
        controller: _user,
        autocorrect: false,
        autofocus: true,
        decoration: InputDecoration(
          labelText: "Your name",
          helperText: "How the others on this server see you.",
          border: const OutlineInputBorder(),
          errorText: refusal,
        ),
        onChanged: (_) => setState(() => _refusal = null),
        onSubmitted: (_) => _join(),
      ),
      const SizedBox(height: 16),
      TextField(
        key: invitationDeviceFieldKey,
        controller: _device,
        autocorrect: false,
        decoration: const InputDecoration(
          labelText: "Device name",
          helperText: "Which of your devices this is.",
          border: OutlineInputBorder(),
        ),
      ),
      const SizedBox(height: 24),
      if (_joining)
        const Row(
          children: [
            SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            SizedBox(width: 8),
            Text("Joining..."),
          ],
        )
      else
        FilledButton.icon(
          key: const Key("invitation-join"),
          onPressed: _join,
          icon: const Icon(Icons.login),
          label: const Text("Join"),
        ),
    ];
  }

  /// The one screen after the invitation was accepted.
  List<Widget> _welcome(BuildContext context) {
    var joined = _joined!;
    return [
      const Icon(Icons.verified_user, size: 48, color: Colors.green),
      const SizedBox(height: 16),
      Text(
        "You're in as ${userDisplayName(joined.userName)}.",
        key: const Key("invitation-joined"),
        style: Theme.of(context).textTheme.titleMedium,
      ),
      const SizedBox(height: 8),
      Text(
        joined.role == roleGuest
            ? guestLibraryNotice
            : "This device is signed in; your albums are yours from now on.",
        key: const Key("invitation-joined-note"),
      ),
      const SizedBox(height: 24),
      FilledButton.icon(
        key: const Key("invitation-open"),
        onPressed: () => widget.openUrl(widget.appBase),
        icon: const Icon(Icons.photo_library),
        label: const Text("Open your albums"),
      ),
    ];
  }

  /// Accepts the invitation: the pairing request that creates the user.
  Future<void> _join() async {
    var name = _user.text.trim();
    if (name.isEmpty) {
      setState(() => _refusal = "Choose the name you want to be known by.");
      return;
    }
    setState(() {
      _joining = true;
      _refusal = null;
    });
    PairResponse answer;
    try {
      answer = await widget.client.pair(
        invitation: widget.token,
        deviceName:
            _device.text.trim().isEmpty ? defaultDeviceName() : _device.text.trim(),
        userName: name,
      );
    } on VAlbumException catch (failure) {
      if (!mounted) {
        return;
      }
      // An invitation that expired, was withdrawn or was already used is gone
      // for good: there is no field to correct, so the form goes with it.
      if (failure.status == 410) {
        widget.onGone(failure.message);
        return;
      }
      setState(() {
        _joining = false;
        _refusal = failure.message;
      });
      return;
    } catch (failure) {
      if (!mounted) {
        return;
      }
      setState(() {
        _joining = false;
        _refusal = "$failure";
      });
      return;
    }
    await widget.onJoined(answer);
    if (!mounted) {
      return;
    }
    setState(() {
      _joining = false;
      _joined = answer;
    });
  }
}

/// How long a new invitation lives, as the dialog offers it.
///
/// Never "never": the server settles on seven days for a request that names
/// no instant, and an invitation that lived forever would be a password
/// somebody forgot they handed out.
enum InviteExpiry {
  day("1 day", Duration(days: 1)),
  week("1 week", Duration(days: 7)),
  month("1 month", Duration(days: 30));

  /// How the choice is named on the screen.
  final String label;

  /// How long from now the invitation lives.
  final Duration duration;

  const InviteExpiry(this.label, this.duration);
}

/// Opens the dialog issuing an invitation, see [InviteDialog].
Future<void> openInviteDialog(BuildContext context, VAlbumClient client) =>
    showDialog<void>(
      context: context,
      builder: (_) => InviteDialog(client: client),
    );

/// Issues an invitation and shows its URL exactly once (issue #52).
///
/// Three questions and no more: what the person becomes, a note for the
/// inviter's own list, and how long the invitation lives. Everything else an
/// invitation could ask — which albums, which rights — is a grant's business
/// and is asked where albums are, see issue #49.
class InviteDialog extends StatefulWidget {
  final VAlbumClient client;

  const InviteDialog({super.key, required this.client});

  @override
  State<InviteDialog> createState() => InviteDialogState();
}

class InviteDialogState extends State<InviteDialog> {
  final TextEditingController _note = TextEditingController();

  String _role = defaultInviteRole;

  String _clearance = defaultInviteClearance;

  bool _mayShare = false;

  InviteExpiry _expiry = InviteExpiry.week;

  /// Whether a request of this dialog is running.
  bool _busy = false;

  /// The server's reason for the refused request, `null` while all is well.
  String? _refusal;

  /// The invitation that was just issued, shown with its URL exactly once.
  InvitationCreated? _created;

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        key: const Key("invite-dialog"),
        title: const Text("Invite somebody"),
        content: SizedBox(
          width: 460,
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children:
                  _created == null ? _form(context) : _createdSection(context),
            ),
          ),
        ),
        actions: [
          TextButton(
            key: const Key("invite-close"),
            onPressed: _busy ? null : () => Navigator.of(context).pop(),
            child: const Text("Close"),
          ),
        ],
      );

  List<Widget> _form(BuildContext context) {
    var titles = Theme.of(context).textTheme.titleSmall;
    var refusal = _refusal;
    return [
      // What the invited person may do, see and hand out: the same three
      // choices the administrator changes later, see [PermissionChoices] and
      // issue #85.
      PermissionChoices(
        keyPrefix: "invite",
        role: _role,
        clearance: _clearance,
        mayShare: _mayShare,
        enabled: !_busy,
        onRole: (value) => setState(() => _role = value),
        onClearance: (value) => setState(() => _clearance = value),
        onMayShare: (value) => setState(() => _mayShare = value),
      ),
      const SizedBox(height: 8),
      TextField(
        key: const Key("invite-note"),
        controller: _note,
        decoration: const InputDecoration(
          label: Text("Note"),
          helperText: "Who this is, for your own list.",
        ),
      ),
      const SizedBox(height: 8),
      Text("Expires", style: titles),
      for (var choice in InviteExpiry.values)
        _choiceTile(
          key: "invite-expiry-${choice.name}",
          chosen: _expiry == choice,
          title: choice.label,
          onTap: () => setState(() => _expiry = choice),
        ),
      if (refusal != null)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Text(
            refusal,
            key: const Key("invite-refusal"),
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        ),
      const SizedBox(height: 8),
      Align(
        alignment: Alignment.centerRight,
        child: ElevatedButton(
          key: const Key("invite-create"),
          onPressed: _busy ? null : _create,
          child: const Text("Create invitation"),
        ),
      ),
    ];
  }

  /// One choice of a group, ticked when it is the current one, exactly as the
  /// share-link dialog shows its choices.
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

  /// The instant the invitation expires at, always spelled out.
  ///
  /// The server would settle on seven days for an empty one; sending the
  /// instant means that what the dialog shows is what was asked for.
  String get _expiresAt =>
      DateTime.now().toUtc().add(_expiry.duration).toIso8601String();

  Future<void> _create() async {
    setState(() {
      _busy = true;
      _refusal = null;
    });
    InvitationCreated answer;
    try {
      answer = await widget.client.invite(Invitation(
        role: _role,
        clearance: _clearance,
        mayShare: _mayShare,
        note: _note.text.trim(),
        expires: _expiresAt,
      ));
    } on VAlbumException catch (failure) {
      _refused(failure.message);
      return;
    } on http.ClientException catch (failure) {
      _refused(failure.message);
      return;
    } catch (failure) {
      _refused("$failure");
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() {
      _busy = false;
      _created = answer;
    });
  }

  void _refused(String message) {
    if (!mounted) {
      return;
    }
    setState(() {
      _busy = false;
      _refusal = message;
    });
  }

  /// The URL of the invitation that was just issued, shown exactly once.
  List<Widget> _createdSection(BuildContext context) {
    var created = _created!;
    var url = absoluteServerUrl(widget.client.dataUrl, created.url);
    var invitation = created.invitation;
    return [
      Text("The invitation", style: Theme.of(context).textTheme.titleSmall),
      const SizedBox(height: 8),
      Row(
        children: [
          Expanded(
            child: SelectableText(url, key: const Key("invite-url")),
          ),
          IconButton(
            key: const Key("invite-copy"),
            icon: const Icon(Icons.copy),
            tooltip: "Copy",
            onPressed: () => _copy(url),
          ),
        ],
      ),
      if (invitation != null && invitation.expires.isNotEmpty) ...[
        const SizedBox(height: 8),
        Text(
          "Valid until ${_day(invitation.expires)}, and for one person.",
          key: const Key("invite-expiry"),
        ),
      ],
      const SizedBox(height: 16),
      const Text(
        "Send it now: the server keeps only its fingerprint and can never "
        "show it again. A lost invitation is withdrawn and made anew.",
        key: Key("invite-once"),
      ),
      const SizedBox(height: 16),
      Align(
        alignment: Alignment.centerRight,
        child: ElevatedButton(
          key: const Key("invite-done"),
          onPressed: () => setState(() {
            _created = null;
            _note.clear();
          }),
          child: const Text("Done"),
        ),
      ),
    ];
  }

  /// The day of an ISO-8601 instant, in the inviter's own time zone.
  String _day(String instant) => dayOf(instant);

  Future<void> _copy(String url) async {
    var messenger = ScaffoldMessenger.of(context);
    await Clipboard.setData(ClipboardData(text: url));
    messenger.showSnackBar(
      const SnackBar(content: Text("The invitation was copied.")),
    );
  }
}
