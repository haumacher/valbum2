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
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import 'package:qr_flutter/qr_flutter.dart';

import 'caller.dart';
import 'client.dart';
import 'device_code_payload.dart';
import 'l10n/app_localizations.dart';
import 'resource.dart';
import 'settings.dart';
import 'urls.dart';

/// The reason a request was refused, as it is shown to the user.
///
/// A [VAlbumException] carries the server's own sentence (the `ErrorInfo` of
/// the refusal); anything else — a transport failure — is said as it is, so
/// that nothing ever fails silently.
///
/// **Neither is translated, and that is the rule of issue #108**: what the
/// *server* said is the server's word and is shown as it arrives (the protocol
/// carries an `ErrorInfo.code` beside the message, which a later issue maps to
/// localized texts), and what a transport failure says is an OS message that
/// no wording of this app improves. What this app itself authors is a
/// different matter: a sentence it writes is never thrown to be read, it is
/// answered from `AppLocalizations` at the place that shows it, see
/// `serverUrlError` and `test/l10n_guard_test.dart`.
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
            child: Text(AppLocalizations.of(context)!.cancel),
          ),
          FilledButton(
            key: Key(confirmKey),
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(confirmLabel),
          ),
        ],
      ),
    );

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
String deviceCodeQrAdvice(AppLocalizations l10n) => l10n.deviceCodeQrAdvice;

/// The key of the "Recovery code" action beside a user (issue #89).
const Key recoveryCodeKey = Key("settings.users.recovery");

/// What a recovery code is, said where it is shown (issue #89).
///
/// The one difference from [deviceCodeAdvice]: this code signs a device in as
/// *somebody else*, so the sentence says whose and says to hand it to them and
/// nobody in between.
String recoveryCodeAdvice(AppLocalizations l10n, String userName) =>
    l10n.recoveryCodeAdvice(userName);

/// What a device code is, said where it is shown (issue #65).
///
/// The whole point in one sentence: this is a credential for a device of
/// *one's own*, and handing it to somebody else hands them one's library.
String deviceCodeAdvice(AppLocalizations l10n) => l10n.deviceCodeAdvice;

/// The key of the copyable link carrying the same credential as the QR code
/// above it (issue #91).
///
/// The very same payload the QR code holds, as text: a person who cannot hold
/// two screens together sends it to their other device instead. It is no URL
/// the server serves and nothing a browser offers to open, see
/// [encodeDeviceCodePayload].
const Key deviceCodeLinkKey = Key("settings.deviceCode.link");

/// The key of the button putting that link on the clipboard.
const Key deviceCodeCopyKey = Key("settings.deviceCode.copy");

/// What the link is for, said beside it.
String deviceCodeLinkAdvice(AppLocalizations l10n) =>
    l10n.deviceCodeLinkAdvice;

/// The key of the "Backup code" line of the devices section (issue #92).
const Key backupCodeStateKey = Key("settings.backupCode.state");

/// The key of the button making a backup code.
const Key backupCodeCreateKey = Key("settings.backupCode.create");

/// The key of the button withdrawing it.
const Key backupCodeRevokeKey = Key("settings.backupCode.revoke");

/// The key of the dialog showing a freshly made backup code.
const Key backupCodeDialogKey = Key("settings.backupCode.dialog");

/// What a backup code is, said where it is shown (issue #92).
///
/// The whole point in one sentence: it is the way back into one's own account
/// on a day when no device of one's own is signed in any more — which is also
/// why it must be written down now, and why nobody else may ever read it.
String backupCodeAdvice(AppLocalizations l10n) => l10n.backupCodeAdvice;

/// What the devices section says while there is no backup code.
String noBackupCode(AppLocalizations l10n) => l10n.noBackupCode;

/// What it says once there is one; [made] is the day it was made.
String backupCodeMade(AppLocalizations l10n, String made) =>
    l10n.backupCodeMade(made);

/// The question a sign-out that has no way back asks (issue #92).
///
/// Signing out on a *foreign* device is harmless — the device that showed
/// the code is still signed in. The danger is one's **last** device, and the
/// question names the ways back there are, in words, rather than letting the
/// door fall shut in silence.
String lastDeviceWarning(
  AppLocalizations l10n, {
  required bool isAdmin,
  required bool hasBackupCode,
}) {
  var ways = <String>[
    if (hasBackupCode) l10n.wayBackupCode,
    isAdmin ? l10n.wayRecoveryFromOtherAdmin : l10n.wayRecoveryFromAdmin,
    if (isAdmin) l10n.wayServerRestart,
  ];
  var last = ways.removeLast();
  var spelled = ways.isEmpty ? last : l10n.waysOrLast(ways.join(", "), last);
  return l10n.lastDeviceWarning(spelled);
}

/// What the warning says when the devices could not even be read.
String maybeLastDeviceWarning(AppLocalizations l10n) =>
    l10n.maybeLastDeviceWarning;

/// Asks before a sign-out that may have no way back, see issue #92.
///
/// Answers `true` when the sign-out may go ahead. It asks only where there is
/// something to warn about: with a second device signed in there is always a
/// way back, and a question nobody needs is a question people learn to click
/// away. [devices] is `null` where the server could not be asked, and that is
/// asked about too — the safe side of not knowing.
Future<bool> confirmLastSignOut({
  required BuildContext context,
  required DeviceList? devices,
  required String role,
}) async {
  var known = devices?.devices;
  if (known != null && known.length > 1) {
    return true;
  }
  if (known != null && known.isEmpty) {
    // Nothing to sign out of; the token this app holds proves nothing anyway.
    return true;
  }
  var l10n = AppLocalizations.of(context)!;
  var message = known == null
      ? maybeLastDeviceWarning(l10n)
      : lastDeviceWarning(
          l10n,
          isAdmin: role == roleAdmin,
          hasBackupCode: (devices?.backupCodeCreated ?? "").isNotEmpty,
        );
  var confirmed = await confirmHere(
    context: context,
    dialogKey: "sign-out-confirm",
    title: l10n.signOutThisDeviceTitle,
    message: message,
    confirmLabel: l10n.signOut,
    confirmKey: "sign-out-confirmed",
  );
  return confirmed == true;
}

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

  /// The role of the caller, so that the sign-out warning can name the ways
  /// back an administrator has (issue #92); empty where nobody said.
  final String role;

  /// Hands every device list this section reads to whoever is above it.
  ///
  /// The sign-out button of the settings needs exactly what this section
  /// reads: how many devices there are, and whether a backup code exists.
  final void Function(DeviceList devices)? onDevices;

  const DevicesSection({
    super.key,
    required this.client,
    required this.onSignedOutHere,
    this.ticker = secondsTicker,
    this.now = DateTime.now,
    this.role = "",
    this.onDevices,
  });

  @override
  State<DevicesSection> createState() => DevicesSectionState();
}

class DevicesSectionState extends State<DevicesSection> {
  /// The devices, `null` while they are being read.
  List<DeviceEntry>? _devices;

  /// When the caller's backup code was made, empty while they have none
  /// (issue #92); never the code, which the server cannot show again.
  String _backupCodeCreated = "";

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
        setState(() => _took(answer));
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

  /// Takes over what the server answered, and passes it on (issue #92).
  ///
  /// Called inside a `setState`: one place reads a [DeviceList], so one place
  /// is where the backup code and the device list are kept in step.
  void _took(DeviceList answer) {
    _devices = answer.devices;
    _backupCodeCreated = answer.backupCodeCreated;
    _problem = null;
    widget.onDevices?.call(answer);
  }

  /// The devices as the enclosing screen would see them, for the sign-out
  /// question of issue #92.
  DeviceList get _list => DeviceList(
        devices: _devices ?? const [],
        backupCodeCreated: _backupCodeCreated,
      );

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var devices = _devices;
    var problem = _problem;
    return Column(
      key: devicesSectionKey,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ...sectionHead(context, l10n.devicesHeading, l10n.devicesLead),
        if (devices == null) sectionProgress(l10n.askingServer),
        if (problem != null)
          sectionProblem(context, problem, const Key("settings.devices.error")),
        if (devices != null && problem == null && devices.isEmpty)
          Text(l10n.noDeviceSignedIn,
              key: const Key("settings.devices.empty")),
        for (var device in devices ?? const <DeviceEntry>[])
          ListTile(
            key: Key("device-${device.id}"),
            contentPadding: EdgeInsets.zero,
            leading: Icon(device.current ? Icons.smartphone : Icons.devices),
            title: Text(
              device.current
                  ? l10n.thisDeviceNamed(device.name)
                  : device.name,
            ),
            subtitle: Text(
              device.created.isEmpty
                  ? l10n.pairedAtUnknownTime
                  : l10n.pairedOn(dayOf(device.created)),
            ),
            trailing: IconButton(
              key: Key("device-remove-${device.id}"),
              icon: Icon(device.current ? Icons.logout : Icons.delete_outline),
              tooltip: device.current ? l10n.signOutHere : l10n.remove,
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
            label: Text(l10n.addDevice),
          ),
        ),
        ..._backupCodeLines(l10n),
      ],
    );
  }

  /// The backup code: whether there is one, and the two things one can do
  /// about it (issue #92).
  ///
  /// Never the code itself — that is shown once, when it is made, and the
  /// server keeps only its hash. What stands here is that there is one and
  /// since when, which is exactly what the sign-out warning needs.
  List<Widget> _backupCodeLines(AppLocalizations l10n) {
    var made = _backupCodeCreated;
    return [
      const SizedBox(height: 16),
      Text(
        made.isEmpty
            ? noBackupCode(l10n)
            : backupCodeMade(l10n, dayOf(made)),
        key: backupCodeStateKey,
      ),
      const SizedBox(height: 8),
      Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          OutlinedButton.icon(
            key: backupCodeCreateKey,
            onPressed: _busy ? null : _createBackupCode,
            icon: const Icon(Icons.vpn_key),
            label: Text(made.isEmpty
                ? l10n.createBackupCode
                : l10n.createNewBackupCode),
          ),
          if (made.isNotEmpty)
            TextButton.icon(
              key: backupCodeRevokeKey,
              onPressed: _busy ? null : _revokeBackupCode,
              icon: const Icon(Icons.delete_outline),
              label: Text(l10n.withdraw),
            ),
        ],
      ),
    ];
  }

  /// Shows a freshly made backup code, once (issue #92).
  ///
  /// The same dialog a device code is shown in, because it is the same thing:
  /// a code to type, a QR code to scan and a link to send. What differs is
  /// that this one does not count down, and that it says to write it down.
  Future<void> _createBackupCode() async {
    await showDialog<void>(
      context: context,
      builder: (context) => DeviceCodeDialog(
        key: backupCodeDialogKey,
        client: widget.client,
        backup: true,
        onDevices: (devices) {},
        ticker: widget.ticker,
        now: widget.now,
      ),
    );
    if (mounted) {
      // Whatever was made — or refused — the list above says what is true now.
      await _load();
    }
  }

  /// Withdraws the backup code, after asking.
  Future<void> _revokeBackupCode() async {
    var l10n = AppLocalizations.of(context)!;
    var confirmed = await confirmHere(
      context: context,
      dialogKey: "backup-code-confirm",
      title: l10n.withdrawBackupCodeTitle,
      message: l10n.withdrawBackupCodeMessage,
      confirmLabel: l10n.withdraw,
      confirmKey: "backup-code-confirmed",
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
      answer = await widget.client.revokeBackupCode();
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
      _took(answer);
    });
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
              widget.onDevices?.call(_list);
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
    var l10n = AppLocalizations.of(context)!;
    var lastOne = device.current && (_devices ?? const []).length <= 1;
    var confirmed = await confirmHere(
      context: context,
      dialogKey: "device-confirm",
      title: device.current
          ? l10n.signOutThisDeviceTitle
          : l10n.removeDeviceTitle(device.name),
      message: device.current
          // The last one is a door that locks behind you, so it says what the
          // way back is instead of "you can sign in again at any time", which
          // would then be a lie (issue #92).
          ? (lastOne
              ? lastDeviceWarning(
                  l10n,
                  isAdmin: widget.role == roleAdmin,
                  hasBackupCode: _backupCodeCreated.isNotEmpty,
                )
              : l10n.signOutThisDeviceMessage)
          : l10n.removeDeviceMessage(device.name),
      confirmLabel: device.current ? l10n.signOut : l10n.remove,
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
      _took(answer);
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

  /// Whom the code is for, empty for the caller themselves (issue #89).
  ///
  /// The recovery code an administrator makes for somebody who lost every
  /// device they had: the same code, said differently — there is no device of
  /// one's own to watch for, and the advice names the person.
  final String forUser;

  /// Whether this is the caller's **backup** code (issue #92).
  ///
  /// The same dialog, because it is the same thing: a code to type, a QR code
  /// to scan, a link to send. Two things differ — it does not count down,
  /// because it does not run out, and it says to write it down rather than to
  /// type it within ten minutes.
  final bool backup;

  const DeviceCodeDialog({
    super.key,
    required this.client,
    required this.onDevices,
    this.known = const {},
    this.ticker = secondsTicker,
    this.now = DateTime.now,
    this.forUser = "",
    this.backup = false,
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
      var answer = widget.backup
          ? await widget.client.backupCode()
          : await widget.client.deviceCode(userName: widget.forUser);
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
    if (_joined != null || widget.forUser.isNotEmpty || widget.backup) {
      // A code for somebody else adds a device to *their* list, never to this
      // one; there is nothing here to watch for (issue #89).
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
      var joined = AppLocalizations.of(context)!.deviceJoined(
        arrived.first.name,
      );
      setState(() {
        _joined = joined;
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

  /// Puts the link on the clipboard and says so (issue #91).
  Future<void> _copy(String payload) async {
    var copied = AppLocalizations.of(context)!.linkOnClipboard;
    await Clipboard.setData(ClipboardData(text: payload));
    if (!mounted) {
      return;
    }
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(copied),
        duration: const Duration(seconds: 3),
      ),
    );
  }

  /// A duration as `m:ss`, never negative.
  static String minutesAndSeconds(Duration left) {
    var seconds = left.isNegative ? 0 : left.inSeconds;
    return "${seconds ~/ 60}:${(seconds % 60).toString().padLeft(2, "0")}";
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var code = _code;
    var problem = _problem;
    var left = remaining;
    var expired = left != null && left <= Duration.zero;
    var payload =
        code == null ? "" : encodeDeviceCodePayload(serverUrl, code.code);
    return AlertDialog(
      key: deviceCodeDialogKey,
      title: Text(widget.backup
          ? l10n.backupCodeTitle
          : widget.forUser.isEmpty
              ? l10n.addDeviceTitle
              : l10n.recoveryCodeTitle(widget.forUser)),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (problem != null)
              sectionProblem(context, problem, deviceCodeErrorKey),
            if (problem == null && code == null)
              sectionProgress(l10n.askingServer),
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
                widget.backup
                    // It has no expiry at all, and that is the whole point.
                    ? l10n.backupCodeNoExpiry
                    : expired
                        ? l10n.codeExpired
                        : l10n.codeExpiresIn(
                            minutesAndSeconds(left ?? Duration.zero),
                          ),
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
                    payload: payload,
                  ),
                ),
                const SizedBox(height: 8),
                Text(deviceCodeQrAdvice(l10n)),
                // The same payload as text, for the other device that is not
                // in the room (issue #91). Copyable, because it is long, and
                // shown in full, because nothing is hidden from the person
                // the code belongs to.
                const SizedBox(height: 12),
                Text(deviceCodeLinkAdvice(l10n)),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: SelectableText(
                        payload,
                        key: deviceCodeLinkKey,
                        style: const TextStyle(
                          fontFamily: "monospace",
                          fontSize: 12,
                        ),
                      ),
                    ),
                    IconButton(
                      key: deviceCodeCopyKey,
                      icon: const Icon(Icons.copy),
                      tooltip: l10n.copyTheLink,
                      onPressed: () => _copy(payload),
                    ),
                  ],
                ),
              ],
              const SizedBox(height: 12),
              Text(widget.backup
                  ? backupCodeAdvice(l10n)
                  : widget.forUser.isEmpty
                      ? deviceCodeAdvice(l10n)
                      : recoveryCodeAdvice(l10n, widget.forUser)),
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
            child: Text(l10n.newCode),
          ),
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.done),
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
            semanticsLabel: AppLocalizations.of(context)!
                .deviceCodeQrSemantics,
          ),
        ),
      );
}

/// The three choices of a permission: what may be done, what is seen, and
/// whether links may be handed out (issue #85).
///
/// One widget for the two places that ask: the invite dialog, where the
/// permission is given, and the users section, where the administrator changes
/// it. The same words in both, because it is the same thing — and the words
/// are the ones the person themselves reads in their settings, see
/// [CallerPermission].
class PermissionChoices extends StatelessWidget {
  /// The prefix of the keys of the choices, e.g. `invite` or `permission`.
  final String keyPrefix;

  /// What is chosen now.
  final String role;
  final String clearance;
  final bool mayShare;

  /// Called with what was chosen instead.
  final void Function(String role) onRole;
  final void Function(String clearance) onClearance;
  final void Function(bool mayShare) onMayShare;

  /// Whether the choices answer at all; `false` while a request runs.
  final bool enabled;

  const PermissionChoices({
    super.key,
    required this.keyPrefix,
    required this.role,
    required this.clearance,
    required this.mayShare,
    required this.onRole,
    required this.onClearance,
    required this.onMayShare,
    this.enabled = true,
  });

  /// The roles a permission may be given, strongest first.
  static const List<String> roles = [roleEdit, roleContribute, roleView];

  /// The clearances a permission may be given, widest first.
  static const List<String> clearances = [
    clearanceAll,
    clearanceNonPrivate,
    clearancePublic,
  ];

  /// What each role means, in one line.
  static Map<String, String> roleExplanations(AppLocalizations l10n) => {
        roleEdit: l10n.roleExplanationEdit,
        roleContribute: l10n.roleExplanationContribute,
        roleView: l10n.roleExplanationView,
      };

  /// What each clearance means, in one line.
  static Map<String, String> clearanceExplanations(AppLocalizations l10n) => {
        clearanceAll: l10n.clearanceExplanationAll,
        clearanceNonPrivate: l10n.clearanceExplanationNonPrivate,
        clearancePublic: l10n.clearanceExplanationPublic,
      };

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var titles = Theme.of(context).textTheme.titleSmall;
    var roleWords = roleExplanations(l10n);
    var clearanceWords = clearanceExplanations(l10n);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(l10n.permissionMayHeading, style: titles),
        for (var choice in roles)
          _tile(
            key: "$keyPrefix-role-$choice",
            chosen: role == choice,
            title: CallerPermission.roleWord(l10n, choice),
            subtitle: roleWords[choice],
            onTap: () => onRole(choice),
          ),
        const SizedBox(height: 8),
        Text(l10n.permissionSeesHeading, style: titles),
        for (var choice in clearances)
          _tile(
            key: "$keyPrefix-clearance-$choice",
            chosen: clearance == choice,
            title: CallerPermission.clearanceWord(l10n, choice),
            subtitle: clearanceWords[choice],
            onTap: () => onClearance(choice),
          ),
        const SizedBox(height: 8),
        SwitchListTile(
          key: Key("$keyPrefix-may-share"),
          contentPadding: EdgeInsets.zero,
          dense: true,
          value: mayShare,
          title: Text(l10n.mayShareLinksSwitch),
          subtitle: Text(l10n.mayShareLinksExplanation),
          onChanged: enabled ? onMayShare : null,
        ),
      ],
    );
  }

  /// One choice of a group, ticked when it is the current one.
  Widget _tile({
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
        onTap: enabled ? onTap : null,
      );
}

/// Changes what one user of this space may do and see (issues #83/#85).
///
/// Prefilled with what they have now, saved with one request, and the server's
/// own sentence where it refuses — the last administrator may not be demoted,
/// and that is said here rather than guessed before.
class PermissionDialog extends StatefulWidget {
  final VAlbumClient client;

  /// The user whose permission is being changed.
  final UserEntry user;

  const PermissionDialog({
    super.key,
    required this.client,
    required this.user,
  });

  @override
  State<PermissionDialog> createState() => PermissionDialogState();
}

class PermissionDialogState extends State<PermissionDialog> {
  late String _role = CallerPermission.normalizeRole(widget.user.role);
  late String _clearance = CallerPermission.normalizeClearance(
    widget.user.clearance,
    CallerPermission.normalizeRole(widget.user.role),
  );
  late bool _mayShare = widget.user.mayShare;

  /// Whether the request is running.
  bool _busy = false;

  /// The server's reason for refusing, `null` while all is well.
  String? _refusal;

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var refusal = _refusal;
    return AlertDialog(
      key: const Key("permission-dialog"),
      title: Text(
        l10n.permissionDialogTitle(userDisplayName(l10n, widget.user.name)),
      ),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              // The administrator's own role is not offered here: an admin is
              // made by the server, and demoting the last one is what the
              // server refuses, see [_save].
              PermissionChoices(
                keyPrefix: "permission",
                role: _role,
                clearance: _clearance,
                mayShare: _mayShare,
                enabled: !_busy,
                onRole: (value) => setState(() => _role = value),
                onClearance: (value) => setState(() => _clearance = value),
                onMayShare: (value) => setState(() => _mayShare = value),
              ),
              if (refusal != null)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: Text(
                    refusal,
                    key: const Key("permission-refusal"),
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: Text(l10n.cancel),
        ),
        FilledButton(
          key: const Key("permission-save"),
          onPressed: _busy ? null : _save,
          child: Text(l10n.save),
        ),
      ],
    );
  }

  Future<void> _save() async {
    setState(() {
      _busy = true;
      _refusal = null;
    });
    UserList answer;
    try {
      answer = await widget.client.setPermission(UserPermission(
        name: widget.user.name,
        role: _role,
        clearance: _clearance,
        mayShare: _mayShare,
      ));
    } catch (error) {
      if (mounted) {
        setState(() {
          _busy = false;
          _refusal = refusalMessage(error);
        });
      }
      return;
    }
    if (mounted) {
      // The whole list comes back: the section shows what the server holds
      // now, not what was asked for.
      Navigator.of(context).pop(answer);
    }
  }
}

/// The users of this server, for the administrator alone (issue #55).
///
/// Who is here, what they are, where their library lies and on how many
/// devices they are signed in — and the one thing an administrator does with a
/// guest: make them a member, which gives them a library of their own.
class UsersSection extends StatefulWidget {
  final VAlbumClient client;

  /// Counts the invitations issued elsewhere on this screen; a change makes
  /// the list read itself again (issue #89).
  ///
  /// An invitation is a pending user, so issuing one puts a seat into this
  /// very list — and a list that did not notice would be the old model showing
  /// through.
  final int generation;

  const UsersSection({
    super.key,
    required this.client,
    this.generation = 0,
  });

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

  @override
  void didUpdateWidget(UsersSection oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.generation != widget.generation) {
      _load();
    }
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
    var l10n = AppLocalizations.of(context)!;
    var users = _users;
    var problem = _problem;
    return Column(
      key: usersSectionKey,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ...sectionHead(context, l10n.usersHeading, l10n.usersLead),
        if (users == null) sectionProgress(l10n.askingServer),
        if (problem != null)
          sectionProblem(context, problem, const Key("settings.users.error")),
        for (var user in users ?? const <UserEntry>[])
          ListTile(
            key: _keyOf(user),
            contentPadding: EdgeInsets.zero,
            leading: Icon(
              user.pending
                  ? Icons.mail_outline
                  : CallerPermission.normalizeRole(user.role) == roleAdmin
                      ? Icons.admin_panel_settings
                      : Icons.person,
            ),
            title: Text(_headline(l10n, user)),
            subtitle: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  _describe(l10n, user),
                  key: Key("user-permission-${_idOf(user)}"),
                ),
                // Which person of the register this member is (issue #128),
                // read here and edited in the face editor, where the people
                // are: this list says who is here, not who is in a photograph.
                if (user.personName.trim().isNotEmpty)
                  Text(
                    l10n.appearsInPhotosAs(user.personName.trim()),
                    key: const Key("user-person"),
                  ),
              ],
            ),
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                // An invitation nobody accepted is a pending user (issue #89),
                // and the one thing to do with them is to take the invitation
                // back — which removes them again.
                if (user.pending)
                  IconButton(
                    key: Key("user-withdraw-${_idOf(user)}"),
                    icon: const Icon(Icons.cancel_outlined),
                    tooltip: l10n.withdraw,
                    onPressed: _busy ? null : () => _withdraw(user),
                  ),
                // Somebody who lost every device they had gets the same code
                // as everybody else, made by an administrator (issue #89). A
                // user who never signed in has no name to make one for: the
                // seat code of the space is what signs them in.
                if (user.name.isNotEmpty)
                  IconButton(
                    key: Key("user-recovery-${user.name}"),
                    icon: const Icon(Icons.key_outlined),
                    tooltip: l10n.recoveryCodeTooltip,
                    onPressed: _busy ? null : () => _recoveryCode(user),
                  ),
                if (!user.pending) ...[
                  IconButton(
                    key: Key("user-edit-${user.name}"),
                    icon: const Icon(Icons.tune),
                    tooltip: l10n.changePermissionTooltip,
                    onPressed: _busy ? null : () => _edit(user),
                  ),
                  IconButton(
                    key: Key("user-remove-${user.name}"),
                    icon: const Icon(Icons.person_remove_outlined),
                    tooltip: l10n.remove,
                    onPressed: _busy ? null : () => _remove(user),
                  ),
                ],
              ],
            ),
          ),
      ],
    );
  }

  /// Shows a code signing a device of [user] in, for somebody who lost theirs
  /// (issue #89).
  ///
  /// The same code as every other — one device, one user, ten minutes, once —
  /// and the same dialog; only its target differs, and it dies with this
  /// administrator's device like every device-issued code.
  Future<void> _recoveryCode(UserEntry user) async {
    await showDialog<void>(
      context: context,
      builder: (context) => DeviceCodeDialog(
        key: recoveryCodeKey,
        client: widget.client,
        forUser: user.name,
        onDevices: (_) {},
      ),
    );
  }

  /// Opens the dialog changing what [user] may do and see (issue #83).
  Future<void> _edit(UserEntry user) async {
    var answer = await showDialog<UserList>(
      context: context,
      builder: (context) => PermissionDialog(client: widget.client, user: user),
    );
    if (answer == null || !mounted) {
      return;
    }
    setState(() {
      _users = answer.users;
      _problem = null;
    });
  }

  /// Removes [user] from this space, after asking (issue #83).
  ///
  /// What it does is said before it is done: their devices are signed out,
  /// and what they put into the space stays there with their name on it.
  Future<void> _remove(UserEntry user) async {
    var l10n = AppLocalizations.of(context)!;
    var confirmed = await confirmHere(
      context: context,
      dialogKey: "remove-user-confirm",
      title: l10n.removeUserTitle(userDisplayName(l10n, user.name)),
      message: l10n.removeUserMessage,
      confirmLabel: l10n.remove,
      confirmKey: "remove-user-confirmed",
    );
    if (confirmed != true || !mounted) {
      return;
    }
    setState(() {
      _busy = true;
      _problem = null;
    });
    UserList answer;
    try {
      answer = await widget.client.removeUser(user.name);
    } catch (error) {
      if (mounted) {
        // The server's own sentence: the last administrator stays, and it
        // says so.
        setState(() {
          _busy = false;
          _problem = refusalMessage(error);
        });
      }
      return;
    }
    if (mounted) {
      setState(() {
        _busy = false;
        _users = answer.users;
      });
    }
  }

  /// What names a row of this list: a user by their name, a pending user by
  /// the invitation they came in by (issue #89), which is what withdraws it.
  static String _idOf(UserEntry user) =>
      user.pending ? "pending-${user.invitation}" : user.name;

  static Key _keyOf(UserEntry user) => Key("user-${_idOf(user)}");

  /// The bold line of a row: who this is.
  ///
  /// A pending user has no name yet, so they are named by the inviter's own
  /// memento — "Invited for Grandma" — or, where the inviter wrote none, by
  /// the plain fact that somebody was invited (issue #89).
  static String _headline(AppLocalizations l10n, UserEntry user) {
    if (!user.pending) {
      return userDisplayName(l10n, user.name);
    }
    return user.recipient.trim().isEmpty
        ? l10n.invitedPending
        : l10n.invitedForPending(user.recipient.trim());
  }

  /// Withdraws the invitation of a pending [user], after asking (issue #89).
  ///
  /// Withdrawing it removes them: an invitation nobody accepted is a user
  /// nobody is.
  Future<void> _withdraw(UserEntry user) async {
    var l10n = AppLocalizations.of(context)!;
    var confirmed = await confirmHere(
      context: context,
      dialogKey: "withdraw-user-confirm",
      title: l10n.withdrawInvitationTitle,
      message: l10n.withdrawPendingUserMessage,
      confirmLabel: l10n.withdraw,
      confirmKey: "withdraw-user-confirmed",
    );
    if (confirmed != true || !mounted) {
      return;
    }
    setState(() {
      _busy = true;
      _problem = null;
    });
    try {
      await widget.client.uninvite(user.invitation);
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
    setState(() => _busy = false);
    // The withdrawal answers the invitation, not the users; the list says
    // what is there by asking again.
    await _load();
  }

  /// The line under a user's name: what they may do and see, where their
  /// library is, and since when (issue #85).
  ///
  /// Words, not field names: the three answers of the permission model read as
  /// a sentence about that person, see [CallerPermission.phrase]. Changing
  /// them is the administrator's own business, see [PermissionDialog].
  String _describe(AppLocalizations l10n, UserEntry user) {
    var permission = CallerPermission.ofFields(
      role: user.role,
      clearance: user.clearance,
      mayShare: user.mayShare,
    );
    var parts = <String>[permission.phrase(l10n)];
    if (user.pending) {
      // Nothing to say about a library or devices: they have neither until
      // somebody redeems the invitation (issue #89).
      if (user.invitedBy.isNotEmpty) {
        parts.add(l10n.invitedByUser(userDisplayName(l10n, user.invitedBy)));
      }
      if (user.created.isNotEmpty) {
        parts.add(l10n.sinceDay(dayOf(user.created)));
      }
      return parts.join(" — ");
    }
    parts.add(l10n.librarySpace(spaceDisplayName(l10n, user.space)));
    parts.add(l10n.deviceCount(user.devices));
    if (user.recipient.trim().isNotEmpty) {
      // The inviter's memento stays beside the name: "who is 'bob42' again?"
      parts.add(l10n.invitedForRecipient(user.recipient.trim()));
    }
    if (user.created.isNotEmpty) {
      parts.add(l10n.sinceDay(dayOf(user.created)));
    }
    return parts.join(" — ");
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
    var l10n = AppLocalizations.of(context)!;
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
        Text(l10n.openInvitationsHeading,
            style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 8),
        if (all == null) sectionProgress(l10n.askingServer),
        if (problem != null)
          sectionProblem(
              context, problem, const Key("settings.invitations.error")),
        if (all != null && problem == null && open.isEmpty)
          Text(l10n.noOpenInvitations,
              key: const Key("settings.invitations.empty")),
        for (var invitation in open)
          ListTile(
            key: Key("invitation-${invitation.id}"),
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.mail_outline),
            title: Text(_headline(l10n, invitation)),
            subtitle: Text(_describe(l10n, invitation)),
            trailing: IconButton(
              key: Key("invitation-revoke-${invitation.id}"),
              icon: const Icon(Icons.cancel_outlined),
              tooltip: l10n.withdraw,
              onPressed: _busy ? null : () => _revoke(invitation),
            ),
          ),
      ],
    );
  }

  /// What the invitation makes somebody, and by whom (issue #85).
  ///
  /// The same words the users list uses, because it is the same thing: the
  /// permission this person will have, see [CallerPermission.phrase].
  String _headline(AppLocalizations l10n, Invitation invitation) {
    var permission = CallerPermission.ofFields(
      role: invitation.role,
      clearance: invitation.clearance,
      mayShare: invitation.mayShare,
    );
    var phrase = permission.phrase(l10n);
    if (invitation.invitedBy.isEmpty) {
      return phrase;
    }
    return l10n.invitationPermissionBy(
      phrase,
      userDisplayName(l10n, invitation.invitedBy),
    );
  }

  /// The line under an invitation: the note it carries and how long it lives.
  String _describe(AppLocalizations l10n, Invitation invitation) {
    var parts = <String>[];
    if (invitation.recipient.trim().isNotEmpty) {
      // The inviter's own memento, see issue #89.
      parts.add(l10n.forRecipient(invitation.recipient.trim()));
    }
    if (invitation.note.trim().isNotEmpty) {
      parts.add(invitation.note.trim());
    }
    if (invitation.expires.isEmpty) {
      parts.add(l10n.expiresNever);
    } else if (expired(invitation)) {
      parts.add(l10n.expiredOnDay(dayOf(invitation.expires)));
    } else {
      parts.add(l10n.expiresOnDay(dayOf(invitation.expires)));
    }
    return parts.join(" — ");
  }

  /// Withdraws [invitation], after asking.
  Future<void> _revoke(Invitation invitation) async {
    var l10n = AppLocalizations.of(context)!;
    var confirmed = await confirmHere(
      context: context,
      dialogKey: "uninvite-confirm",
      title: l10n.withdrawInvitationTitle,
      message: l10n.withdrawInvitationMessage,
      confirmLabel: l10n.withdraw,
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
