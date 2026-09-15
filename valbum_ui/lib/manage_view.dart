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

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:qr_flutter/qr_flutter.dart';

import 'caller.dart';
import 'client.dart';
import 'device_code_payload.dart';
import 'groups_view.dart';
import 'resource.dart';
import 'settings.dart';
import 'urls.dart';

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

/// The key of the "Add a device…" button of the devices section (issue #65).
const Key addDeviceButtonKey = Key("settings.devices.add");

/// The key of the dialog showing a device code.
const Key deviceCodeDialogKey = Key("settings.deviceCode.dialog");

/// The key of the code itself, shown large and monospaced as `XXXX-XXXX`.
const Key deviceCodeKey = Key("settings.deviceCode.code");

/// The key of the line saying how long the code still works.
const Key deviceCodeRemainingKey = Key("settings.deviceCode.remaining");

/// The key of the line saying that a device paired while the dialog was open.
const Key deviceCodeJoinedKey = Key("settings.deviceCode.joined");

/// The key of the server's reason for refusing a code.
const Key deviceCodeErrorKey = Key("settings.deviceCode.error");

/// The key of the button asking for a fresh code once one has run out.
const Key deviceCodeRenewKey = Key("settings.deviceCode.renew");

/// The key of the QR code carrying the same credential as the code above it
/// (issue #66).
const Key deviceCodeQrKey = Key("settings.deviceCode.qr");

/// How large the QR code is drawn: enough for a phone camera to read it off a
/// phone screen, small enough for a dialog on that phone.
const double deviceCodeQrSize = 200;

/// What the QR code is for, said beside it.
const String deviceCodeQrAdvice =
    "Or scan this on the other device, at Sign in.";

/// What a device code is, said where it is shown (issue #65).
///
/// The whole point in one sentence: this is a credential for a device of
/// *one's own*, and handing it to somebody else hands them one's library.
const String deviceCodeAdvice =
    "Type this on the other device within 10 minutes. It signs that device in "
    "as you \u2014 never give it to anyone else.";

/// How often the device list is read again while a code is on screen.
const Duration deviceCodePollInterval = Duration(seconds: 3);

/// The beat the device-code dialog counts and polls with.
///
/// A second of real time by default; a test hands in a stream it drives
/// itself, so that ten minutes pass in a pump.
typedef DeviceCodeTicker = Stream<void> Function();

/// The ticker of a running app: one tick a second, forever.
Stream<void> secondsTicker() =>
    Stream<void>.periodic(const Duration(seconds: 1), (_) {});

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

  /// The beat the device-code dialog counts and polls with (issue #65).
  final DeviceCodeTicker ticker;

  /// The clock the device-code dialog measures the remaining time against.
  final DateTime Function() now;

  const DevicesSection({
    super.key,
    required this.client,
    required this.onSignedOutHere,
    this.ticker = secondsTicker,
    this.now = DateTime.now,
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
        const SizedBox(height: 8),
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton.icon(
            key: addDeviceButtonKey,
            onPressed: _busy ? null : _addDevice,
            icon: const Icon(Icons.phonelink_setup),
            label: const Text("Add a device\u2026"),
          ),
        ),
      ],
    );
  }

  /// Shows a code to type on a further device of this person's own (issue #65).
  ///
  /// Deliberately not the invitation dialog and deliberately not a link: a
  /// device credential is read off this screen and typed on the other one, so
  /// that there is nothing to forward. What the dialog watches for while it is
  /// open is the other device arriving, which is also what makes a stranger
  /// using the code visible at once.
  Future<void> _addDevice() async {
    await showDialog<void>(
      context: context,
      builder: (context) => DeviceCodeDialog(
        client: widget.client,
        known: {
          for (var device in _devices ?? const <DeviceEntry>[]) device.id,
        },
        onDevices: (devices) {
          if (mounted) {
            setState(() {
              _devices = devices;
              _problem = null;
            });
          }
        },
        ticker: widget.ticker,
        now: widget.now,
      ),
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

/// The dialog showing a code for a further device of one's own (issue #65).
///
/// A *device* credential and nothing that looks like an invitation: no link,
/// no URL, nothing to forward — eight characters read off this screen and
/// typed on the other one, good for ten minutes and for one device. The dialog
/// says so in [deviceCodeAdvice], counts the minutes down, and watches the
/// device list while it is open, so that the other device arriving is
/// confirmed here and a stranger arriving is seen here.
class DeviceCodeDialog extends StatefulWidget {
  /// The client talking to the saved server, carrying this device's token.
  final VAlbumClient client;

  /// The ids of the devices that were listed before the code was asked for.
  final Set<String> known;

  /// Hands the freshly read device list to the section behind the dialog.
  final void Function(List<DeviceEntry> devices) onDevices;

  /// The beat this dialog counts and polls with, see [DeviceCodeTicker].
  final DeviceCodeTicker ticker;

  /// The clock the remaining time is measured against.
  final DateTime Function() now;

  const DeviceCodeDialog({
    super.key,
    required this.client,
    required this.onDevices,
    this.known = const {},
    this.ticker = secondsTicker,
    this.now = DateTime.now,
  });

  @override
  State<DeviceCodeDialog> createState() => DeviceCodeDialogState();
}

class DeviceCodeDialogState extends State<DeviceCodeDialog> {
  /// The code the server issued, `null` while it is being asked for.
  DeviceCodeCreated? _code;

  /// The server's reason for refusing, `null` while all is well.
  String? _problem;

  /// What the dialog says once a device paired, `null` while none did.
  String? _joined;

  /// The devices that were there before; anything else is the new one.
  late Set<String> _known = {...widget.known};

  StreamSubscription<void>? _ticks;

  /// How many ticks have passed, so that the list is read every third one.
  int _ticksSeen = 0;

  @override
  void initState() {
    super.initState();
    _ask();
    _ticks = widget.ticker().listen((_) => _tick());
  }

  @override
  void dispose() {
    _ticks?.cancel();
    super.dispose();
  }

  /// Asks the server for a code, and says so when it refuses.
  Future<void> _ask() async {
    setState(() {
      _code = null;
      _problem = null;
      _joined = null;
    });
    try {
      var answer = await widget.client.deviceCode();
      if (mounted) {
        setState(() {
          _code = answer;
          _problem = null;
        });
      }
    } catch (error) {
      if (mounted) {
        setState(() {
          _problem = refusalMessage(error);
        });
      }
    }
  }

  /// One beat: the countdown moves, and every third one asks who is here.
  void _tick() {
    _ticksSeen++;
    if (mounted) {
      setState(() {});
    }
    if (_ticksSeen % (deviceCodePollInterval.inSeconds) == 0) {
      _poll();
    }
  }

  /// Reads the device list again, watching for one that was not there before.
  ///
  /// A failure is swallowed on purpose: this is a courtesy poll, and a network
  /// hiccup while a code is on screen says nothing about the code. What the
  /// server refuses when it is *asked for a code* is shown, see [_ask].
  Future<void> _poll() async {
    if (_joined != null) {
      return;
    }
    List<DeviceEntry> devices;
    try {
      devices = (await widget.client.devices()).devices;
    } catch (_) {
      return;
    }
    if (!mounted) {
      return;
    }
    widget.onDevices(devices);
    var arrived = [
      for (var device in devices)
        if (!_known.contains(device.id)) device,
    ];
    if (arrived.isNotEmpty) {
      setState(() {
        _joined = "${arrived.first.name} joined.";
        _known = {for (var device in devices) device.id};
      });
    }
  }

  /// The server this code belongs to, as the other device's server field
  /// takes it (issue #66).
  ///
  /// The app base of the server this dialog's client talks to — never the data
  /// URL, which is what the *client* uses: what is scanned is stored, and what
  /// is stored is the app base, see [appBaseOf].
  String get serverUrl => appBaseOf(widget.client.dataUrl);

  /// How long the code still works, `null` while there is none.
  Duration? get remaining {
    var code = _code;
    if (code == null || code.expires.isEmpty) {
      return null;
    }
    try {
      return DateTime.parse(code.expires).difference(widget.now());
    } catch (_) {
      return null;
    }
  }

  /// A duration as `m:ss`, never negative.
  static String minutesAndSeconds(Duration left) {
    var seconds = left.isNegative ? 0 : left.inSeconds;
    return "${seconds ~/ 60}:${(seconds % 60).toString().padLeft(2, "0")}";
  }

  @override
  Widget build(BuildContext context) {
    var code = _code;
    var problem = _problem;
    var left = remaining;
    var expired = left != null && left <= Duration.zero;
    return AlertDialog(
      key: deviceCodeDialogKey,
      title: const Text("Add a device"),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (problem != null)
              sectionProblem(context, problem, deviceCodeErrorKey),
            if (problem == null && code == null)
              sectionProgress("Asking the server..."),
            if (code != null) ...[
              SelectableText(
                code.code,
                key: deviceCodeKey,
                style: const TextStyle(
                  fontFamily: "monospace",
                  fontSize: 34,
                  fontWeight: FontWeight.bold,
                  letterSpacing: 4,
                ),
              ),
              const SizedBox(height: 12),
              Text(
                expired
                    ? "This code has expired."
                    : "Expires in ${minutesAndSeconds(left ?? Duration.zero)}",
                key: deviceCodeRemainingKey,
              ),
              // The same credential in a form a camera reads, so that
              // nothing has to be typed on a phone (issue #66). It carries
              // the server and the code in a scheme of this app's own and is
              // deliberately not a URL anything offers to open, see
              // [encodeDeviceCodePayload]. It goes with the code: an expired
              // or used-up code is nothing to scan either.
              if (!expired && _joined == null) ...[
                const SizedBox(height: 16),
                Center(
                  child: DeviceCodeQr(
                    key: deviceCodeQrKey,
                    payload: encodeDeviceCodePayload(serverUrl, code.code),
                  ),
                ),
                const SizedBox(height: 8),
                const Text(deviceCodeQrAdvice),
              ],
              const SizedBox(height: 12),
              const Text(deviceCodeAdvice),
            ],
            if (_joined != null) ...[
              const SizedBox(height: 12),
              Text(
                _joined!,
                key: deviceCodeJoinedKey,
                style: TextStyle(color: Theme.of(context).colorScheme.primary),
              ),
            ],
          ],
        ),
      ),
      actions: [
        if (expired || problem != null)
          TextButton(
            key: deviceCodeRenewKey,
            onPressed: _ask,
            child: const Text("New code"),
          ),
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text("Done"),
        ),
      ],
    );
  }
}

/// The QR code of a device code (issue #66).
///
/// A widget of its own rather than a bare [QrImageView], because what it
/// draws is the thing worth asserting: [payload] is the whole content, and
/// `QrImageView` keeps its own data private. It is also where the quiet zone
/// lives — white around the code whatever the theme, since a QR code drawn
/// dark on dark is not readable.
class DeviceCodeQr extends StatelessWidget {
  /// What the QR code contains, see [encodeDeviceCodePayload].
  final String payload;

  /// How large the code is drawn, see [deviceCodeQrSize].
  final double size;

  const DeviceCodeQr({
    super.key,
    required this.payload,
    this.size = deviceCodeQrSize,
  });

  @override
  Widget build(BuildContext context) => Container(
        color: Colors.white,
        padding: const EdgeInsets.all(12),
        // A box of a fixed size around it, because `QrImageView` measures
        // itself with a `LayoutBuilder` and the dialog asks its content for
        // intrinsic dimensions, which such a builder refuses to answer.
        child: SizedBox.square(
          dimension: size,
          child: QrImageView(
            data: payload,
            size: size,
            backgroundColor: Colors.white,
            // The quiet zone is the white [Container] around this box; a
            // second margin inside would only make the modules smaller.
            padding: EdgeInsets.zero,
            // One screen read by one camera a hand's width away: the middle
            // level tolerates a reflection without making the modules small.
            errorCorrectionLevel: QrErrorCorrectLevel.M,
            version: QrVersions.auto,
            semanticsLabel: "Device code as a QR code",
          ),
        ),
      );
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
