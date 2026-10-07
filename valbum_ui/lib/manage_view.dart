/// The management sections of the server settings (issue #55): the devices
/// this person is signed in on, and the people of this space — its users and
/// the invitations that are still open, in one list (issue #218).
///
/// Both are lists of the same shape — ask the server, show what it
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
import 'form_dialog.dart';
import 'l10n/app_localizations.dart';
import 'resource.dart';
import 'settings.dart';
import 'sign_in_options.dart' show showSignInOptions;
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

/// The key of the people section: the users of the space and the open
/// invitations, in one list (issue #218).
const Key peopleSectionKey = Key("settings.people");

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
///
/// The ways a member set up to sign in on a new browser are ways back too
/// (issue #233): an authenticator app, a passkey, a proven e-mail address.
String lastDeviceWarning(
  AppLocalizations l10n, {
  required bool isAdmin,
  required bool hasBackupCode,
  ContactSignIns? signIns,
}) {
  var ways = <String>[
    if (signIns != null && signIns.authenticator.isNotEmpty)
      l10n.wayAuthenticator,
    if (signIns != null && signIns.passkeys.isNotEmpty) l10n.wayPasskey,
    if (signIns != null && signIns.addresses.isNotEmpty) l10n.wayEmailAddress,
    if (hasBackupCode) l10n.wayBackupCode,
    isAdmin ? l10n.wayRecoveryFromOtherAdmin : l10n.wayRecoveryFromAdmin,
    if (isAdmin) l10n.wayServerRestart,
  ];
  var last = ways.removeLast();
  var spelled = ways.isEmpty ? last : l10n.waysOrLast(ways.join(", "), last);
  return l10n.lastDeviceWarning(spelled);
}

/// The key of "Ways to sign in…" in the devices section (issue #233).
const Key memberSignInsKey = Key("settings.signIns");

/// What a member's ways to sign in are, in one line (issue #233).
String signInsSummary(AppLocalizations l10n, ContactSignIns signIns) {
  var parts = <String>[
    if (signIns.authenticator.isNotEmpty) l10n.authenticatorHeading,
    if (signIns.passkeys.isNotEmpty) l10n.contactPasskeyCount(signIns.passkeys.length),
    for (var address in signIns.addresses) address.value,
  ];
  return parts.isEmpty
      ? l10n.noMemberSignIns
      : l10n.memberSignInsState(parts.join(", "));
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
          signIns: devices?.signIns,
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

  /// The member's ways to sign in on a new browser besides a code (issue
  /// #233), `null` from a server that does not say.
  ContactSignIns? _signIns;

  /// The server's reason for the last refusal, `null` while all is well.
  String? _problem;

  /// Whether a request of this section is running.
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// "Ways to sign in…" (issue #233): the dialog a contact uses, for the
  /// member's own authenticator app, passkeys and addresses.
  Future<void> _openSignIns() async {
    var signIns = _signIns;
    if (signIns == null) {
      return;
    }
    List<ProofMethod> methods;
    try {
      methods = (await widget.client.authInfo()).proofMethods;
    } catch (_) {
      methods = const [];
    }
    if (!mounted) {
      return;
    }
    var answer = await showSignInOptions(
      context: context,
      client: widget.client,
      signIns: signIns,
      member: true,
      proofMethods: methods,
    );
    if (answer != null && mounted) {
      setState(() {
        _signIns = answer;
        widget.onDevices?.call(_list);
      });
    }
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
    _signIns = answer.signIns;
    _problem = null;
    widget.onDevices?.call(answer);
  }

  /// The devices as the enclosing screen would see them, for the sign-out
  /// question of issue #92.
  DeviceList get _list => DeviceList(
        devices: _devices ?? const [],
        backupCodeCreated: _backupCodeCreated,
        signIns: _signIns,
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
        ..._signInLines(l10n),
      ],
    );
  }

  /// The member's ways to sign in on a new browser (issue #233): what there
  /// is, and the button to the dialog that changes it.
  List<Widget> _signInLines(AppLocalizations l10n) {
    var signIns = _signIns;
    if (signIns == null) {
      return const [];
    }
    return [
      const SizedBox(height: 16),
      Text(signInsSummary(l10n, signIns),
          key: const Key("settings.signIns.state")),
      const SizedBox(height: 8),
      Align(
        alignment: Alignment.centerLeft,
        child: OutlinedButton.icon(
          key: memberSignInsKey,
          onPressed: _busy ? null : _openSignIns,
          icon: const Icon(Icons.key),
          label: Text(l10n.memberSignInsEntry),
        ),
      ),
    ];
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
                  signIns: _signIns,
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

  /// What a screen reader says of the code, `null` for a device code's: the
  /// widget draws other codes too, such as an authenticator app's setup
  /// (issue #233).
  final String? semanticsLabel;

  const DeviceCodeQr({
    super.key,
    required this.payload,
    this.size = deviceCodeQrSize,
    this.semanticsLabel,
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
            semanticsLabel: semanticsLabel ??
                AppLocalizations.of(context)!.deviceCodeQrSemantics,
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

  /// What each role means, in one short line under its name (issue #218).
  static Map<String, String> roleExplanations(AppLocalizations l10n) => {
        roleEdit: l10n.roleExplanationEdit,
        roleContribute: l10n.roleExplanationContribute,
        roleView: l10n.roleExplanationView,
      };

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var titles = Theme.of(context).textTheme.titleSmall;
    var roleWords = roleExplanations(l10n);
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
            // The name says it all: "Public and members' photos".
            title: CallerPermission.clearanceWord(l10n, choice),
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

/// The people of this space and the way to invite more (issues #55, #89,
/// #218): one list, one row per person.
///
/// An invitation *is* a pending user (issue #89), so the administrator reads
/// the users of the space — the people who arrived and the seats still waiting
/// for somebody — and every open invitation stands in that list exactly once,
/// as its pending user, completed by what the invitation list knows of it (its
/// note and its expiry). A caller who may invite but not administer is
/// answered their own invitations alone, and the list shows those. "Invite…"
/// stands at the end of the list it fills.
class PeopleSection extends StatefulWidget {
  final VAlbumClient? client;

  /// Whether the caller administers this space: only then are the users read.
  final bool isAdmin;

  /// Counts the invitations issued from this section; a change makes the list
  /// read itself again, see [didUpdateWidget].
  final int generation;

  /// Opens the dialog issuing an invitation; the caller counts [generation]
  /// up afterwards.
  final VoidCallback onInvite;

  const PeopleSection({
    super.key,
    required this.client,
    required this.isAdmin,
    required this.onInvite,
    this.generation = 0,
  });

  @override
  State<PeopleSection> createState() => PeopleSectionState();
}

/// One row of the people list: a user, an open invitation, or both — the
/// pending user an invitation created, together with that invitation.
class PeopleRow {
  /// The user, `null` for an invitation the users list does not carry (a
  /// caller who is not the administrator is answered no users).
  final UserEntry? user;

  /// The open invitation, `null` for a user who arrived and for a pending user
  /// whose invitation the invitation list did not answer.
  final Invitation? invitation;

  const PeopleRow({this.user, this.invitation});

  /// Whether this row is an invitation nobody accepted yet.
  bool get pending => user?.pending ?? true;

  /// The id of the invitation this row stands for, empty for an arrived user.
  String get invitationId =>
      pending ? (invitation?.id ?? user?.invitation ?? "") : "";

  /// What names the row in its keys: a user by their name, an invitation by
  /// its id.
  String get id => pending ? "pending-$invitationId" : user!.name;

  String get role => user?.role ?? invitation!.role;
  String get clearance => user?.clearance ?? invitation!.clearance;
  bool get mayShare => user?.mayShare ?? invitation!.mayShare;
  String get recipient =>
      (user?.recipient ?? "").trim().isNotEmpty
          ? user!.recipient.trim()
          : (invitation?.recipient ?? "").trim();
  String get invitedBy =>
      (user?.invitedBy ?? "").isNotEmpty
          ? user!.invitedBy
          : invitation?.invitedBy ?? "";
  String get created =>
      (user?.created ?? "").isNotEmpty ? user!.created : invitation?.created ?? "";
}

/// The rows of the people list: every user once, and every open invitation
/// once — as its pending user where the users list carries one, as a row of
/// its own otherwise (issue #218).
///
/// [users] is `null` where the caller is answered no users. Arrived users come
/// first, then the invitations, each in the order the server answered them.
List<PeopleRow> peopleRows(
  List<UserEntry>? users,
  List<Invitation> invitations,
) {
  var open = [
    for (var invitation in invitations)
      if (PeopleSectionState.open(invitation)) invitation,
  ];
  var byId = {for (var invitation in open) invitation.id: invitation};
  var arrived = <PeopleRow>[];
  var waiting = <PeopleRow>[];
  var shown = <String>{};
  for (var user in users ?? const <UserEntry>[]) {
    if (!user.pending) {
      arrived.add(PeopleRow(user: user));
      continue;
    }
    shown.add(user.invitation);
    waiting.add(PeopleRow(user: user, invitation: byId[user.invitation]));
  }
  for (var invitation in open) {
    if (!shown.contains(invitation.id)) {
      waiting.add(PeopleRow(invitation: invitation));
    }
  }
  return [...arrived, ...waiting];
}

class PeopleSectionState extends State<PeopleSection> {
  /// The users, `null` while they are read and for a caller who is answered
  /// none.
  List<UserEntry>? _users;

  /// The invitations, `null` while they are being read.
  List<Invitation>? _invitations;

  /// Whether the first answer is still outstanding.
  bool _loading = true;

  /// The server's reason for the last refusal, `null` while all is well.
  String? _problem;

  /// Whether a request of this section is running.
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _loading = widget.client != null;
    _load();
  }

  @override
  void didUpdateWidget(PeopleSection oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.generation != widget.generation ||
        oldWidget.isAdmin != widget.isAdmin) {
      _load();
    }
  }

  /// Whether [invitation] can still be accepted by somebody.
  ///
  /// An accepted one created its user and a withdrawn one is refused from then
  /// on; neither is open. An expired one is listed — somebody still holds a
  /// link to it, and withdrawing it tidies the list — and says so.
  static bool open(Invitation invitation) =>
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

  Future<void> _load() async {
    var client = widget.client;
    if (client == null) {
      return;
    }
    // Both lists at once; the users only for the administrator, who alone is
    // answered them. A refused invitation list still leaves the users shown,
    // their pending seats without a note and an expiry.
    var users = widget.isAdmin ? client.users() : null;
    var invitations = client.invitations();
    String? problem;
    List<UserEntry>? userList;
    List<Invitation> invitationList = const [];
    if (users != null) {
      try {
        userList = (await users).users;
      } catch (error) {
        problem = refusalMessage(error);
      }
    }
    try {
      invitationList = (await invitations).invitations;
    } catch (error) {
      problem ??= refusalMessage(error);
    }
    if (!mounted) {
      return;
    }
    setState(() {
      _loading = false;
      _users = userList;
      _invitations = invitationList;
      _problem = problem;
    });
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var problem = _problem;
    var rows = _loading
        ? const <PeopleRow>[]
        : peopleRows(_users, _invitations ?? const []);
    return Column(
      key: peopleSectionKey,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ...sectionHead(
          context,
          l10n.peopleHeading,
          widget.isAdmin ? l10n.usersLead : l10n.peopleLeadInviter,
        ),
        if (_loading && widget.client != null)
          sectionProgress(l10n.askingServer),
        if (problem != null)
          sectionProblem(context, problem, const Key("settings.people.error")),
        if (!_loading && problem == null && rows.isEmpty)
          Text(l10n.noOpenInvitations,
              key: const Key("settings.people.empty")),
        for (var row in rows) _tile(l10n, row),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          key: inviteButtonKey,
          onPressed: _busy ? null : widget.onInvite,
          icon: const Icon(Icons.person_add),
          label: Text(l10n.inviteAction),
        ),
      ],
    );
  }

  Widget _tile(AppLocalizations l10n, PeopleRow row) {
    var user = row.user;
    var named = !row.pending && user != null;
    var details = _details(l10n, row);
    return ListTile(
      key: Key("user-${row.id}"),
      contentPadding: EdgeInsets.zero,
      leading: Icon(
        row.pending
            ? Icons.mail_outline
            : CallerPermission.normalizeRole(row.role) == roleAdmin
                ? Icons.admin_panel_settings
                : Icons.person,
      ),
      title: Text(_headline(l10n, row)),
      subtitle: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            CallerPermission.ofFields(
              role: row.role,
              clearance: row.clearance,
              mayShare: row.mayShare,
            ).phrase(l10n),
            key: Key("user-permission-${row.id}"),
          ),
          if (details.isNotEmpty)
            Text(details, key: Key("user-details-${row.id}")),
          // Which person of the register this member is (issue #128), read
          // here and edited in the face editor, where the people are.
          if (user != null && user.personName.trim().isNotEmpty)
            Text(
              l10n.appearsInPhotosAs(user.personName.trim()),
              key: const Key("user-person"),
            ),
        ],
      ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          // An invitation nobody accepted is a pending user (issue #89), and
          // the one thing to do with it is to take it back.
          if (row.pending && row.invitationId.isNotEmpty)
            IconButton(
              key: Key("user-withdraw-${row.id}"),
              icon: const Icon(Icons.cancel_outlined),
              tooltip: l10n.withdraw,
              onPressed: _busy ? null : () => _withdraw(row),
            ),
          // Somebody who lost every device they had gets the same code as
          // everybody else, made by an administrator (issue #89).
          if (named && user.name.isNotEmpty) ...[
            // How they sign in on a new browser besides a code (issue #233).
            if (!HeldSignIns.ofUser(user).isEmpty)
              IconButton(
                key: Key("user-sign-ins-${user.name}"),
                icon: const Icon(Icons.key),
                tooltip: l10n.userSignInsTooltip,
                onPressed: _busy ? null : () => _signIns(user),
              ),
            IconButton(
              key: Key("user-recovery-${user.name}"),
              icon: const Icon(Icons.key_outlined),
              tooltip: l10n.recoveryCodeTooltip,
              onPressed: _busy ? null : () => _recoveryCode(user),
            ),
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
    );
  }

  /// The bold line of a row: who this is.
  ///
  /// A pending user has no name yet, so they are named by the inviter's own
  /// memento — "Invited for Grandma" — or, where the inviter wrote none, by
  /// the plain fact that somebody was invited (issue #89).
  static String _headline(AppLocalizations l10n, PeopleRow row) {
    if (!row.pending) {
      return userDisplayName(l10n, row.user!.name);
    }
    var recipient = row.recipient;
    return recipient.isEmpty
        ? l10n.invitedPending
        : l10n.invitedForPending(recipient);
  }

  /// The line under the permission: where the user's library is, on how many
  /// devices they are signed in and since when — or, for an invitation, who
  /// sent it, the note it carries and how long it lives.
  static String _details(AppLocalizations l10n, PeopleRow row) {
    var parts = <String>[];
    if (row.pending) {
      if (row.invitedBy.isNotEmpty) {
        parts.add(l10n.invitedByUser(userDisplayName(l10n, row.invitedBy)));
      }
      if (row.created.isNotEmpty) {
        parts.add(l10n.sinceDay(dayOf(row.created)));
      }
      var invitation = row.invitation;
      if (invitation != null) {
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
      }
      return parts.join(" — ");
    }
    var user = row.user!;
    parts.add(l10n.librarySpace(spaceDisplayName(l10n, user.space)));
    parts.add(l10n.deviceCount(user.devices));
    var signIns = HeldSignIns.ofUser(user);
    if (!signIns.isEmpty) {
      parts.add(signInsSummary(
          l10n,
          ContactSignIns(
            authenticator: signIns.authenticator,
            passkeys: signIns.passkeys,
            addresses: signIns.addresses,
          )));
    }
    if (user.recipient.trim().isNotEmpty) {
      // The inviter's memento stays beside the name: "who is 'bob42' again?"
      parts.add(l10n.invitedForRecipient(user.recipient.trim()));
    }
    if (user.created.isNotEmpty) {
      parts.add(l10n.sinceDay(dayOf(user.created)));
    }
    return parts.join(" — ");
  }

  /// Shows how [user] signs in besides a code, each way with "Remove"
  /// (issue #233).
  Future<void> _signIns(UserEntry user) async {
    await showDialog<void>(
      context: context,
      builder: (context) => UserSignInsDialog(
        client: widget.client!,
        user: user,
        onChanged: (answer) {
          if (mounted) {
            setState(() {
              _users = answer.users;
              _problem = null;
            });
          }
        },
      ),
    );
  }

  /// Shows a code signing a device of [user] in, for somebody who lost theirs
  /// (issue #89).
  Future<void> _recoveryCode(UserEntry user) async {
    await showDialog<void>(
      context: context,
      builder: (context) => DeviceCodeDialog(
        key: recoveryCodeKey,
        client: widget.client!,
        forUser: user.name,
        onDevices: (_) {},
      ),
    );
  }

  /// Opens the dialog changing what [user] may do and see (issue #83).
  Future<void> _edit(UserEntry user) async {
    var answer = await showFormDialog<UserList>(
      context: context,
      builder: (context) =>
          PermissionDialog(client: widget.client!, user: user),
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
      answer = await widget.client!.removeUser(user.name);
    } catch (error) {
      if (mounted) {
        // The server's own sentence: the last administrator stays.
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

  /// Withdraws the invitation of [row], after asking (issues #55, #89).
  ///
  /// Withdrawing it removes its pending user: an invitation nobody accepted is
  /// a user nobody is.
  Future<void> _withdraw(PeopleRow row) async {
    var l10n = AppLocalizations.of(context)!;
    var confirmed = await confirmHere(
      context: context,
      dialogKey: "withdraw-user-confirm",
      title: l10n.withdrawInvitationTitle,
      message: l10n.withdrawInvitationMessage,
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
    InvitationList answer;
    try {
      answer = await widget.client!.uninvite(row.invitationId);
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
    // The withdrawal answers the invitations, not the users; the
    // administrator's list says who is there by asking again.
    if (widget.isAdmin) {
      await _load();
    }
  }
}

/// The key of the contacts section of the server settings (issue #203).
const Key contactsSectionKey = Key("settings.contacts");

/// The contacts of this space (issue #203): the people personal links were
/// sent to or opened by, beside the members of [PeopleSection].
///
/// Every signed-in member reads the register — "when you share photos, you
/// also share contacts" (#195) — and whoever may share links ([mayManage],
/// the share flag the server checks the same way) renames a contact, ends the
/// browsers they are signed in on, shuts them out of every link and deletes
/// them. Every action asks the server at once and shows the contact as it
/// answers; a refusal is said in the server's words.
class ContactsSection extends StatefulWidget {
  final VAlbumClient? client;

  /// Whether the caller may manage the contacts: the share flag of their
  /// permission (or the administrator's).
  final bool mayManage;

  const ContactsSection({
    super.key,
    required this.client,
    required this.mayManage,
  });

  @override
  State<ContactsSection> createState() => ContactsSectionState();
}

class ContactsSectionState extends State<ContactsSection> {
  /// The contacts, `null` while they are read.
  List<Contact>? _contacts;

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
    var client = widget.client;
    if (client == null) {
      return;
    }
    try {
      var answer = await client.contacts();
      if (mounted) {
        setState(() {
          _contacts = answer.contacts;
          _problem = null;
        });
      }
    } catch (error) {
      if (mounted) {
        setState(() {
          _contacts = const [];
          _problem = refusalMessage(error);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var contacts = _contacts;
    var problem = _problem;
    return Column(
      key: contactsSectionKey,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ...sectionHead(context, l10n.contactsHeading, l10n.contactsLead),
        if (contacts == null && widget.client != null)
          sectionProgress(l10n.askingServer),
        if (problem != null)
          sectionProblem(
              context, problem, const Key("settings.contacts.error")),
        if (contacts != null && contacts.isEmpty && problem == null)
          Text(l10n.noContacts, key: const Key("settings.contacts.empty")),
        for (var contact in contacts ?? const <Contact>[])
          _tile(l10n, contact),
      ],
    );
  }

  Widget _tile(AppLocalizations l10n, Contact contact) {
    var blocked = contact.blocked.isNotEmpty;
    return ListTile(
      key: Key("contact-${contact.id}"),
      contentPadding: EdgeInsets.zero,
      leading: Icon(blocked ? Icons.person_off_outlined : Icons.contact_mail),
      title: Text(contact.name),
      subtitle: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (contact.displayName.trim().isNotEmpty &&
              contact.displayName.trim() != contact.name.trim())
            Text(l10n.contactOwnName(contact.displayName.trim())),
          for (var address in contact.addresses)
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Flexible(child: Text(address.value)),
                if (address.proven)
                  Padding(
                    padding: const EdgeInsets.only(left: 4),
                    child: Tooltip(
                      message: l10n.contactProvenAddress,
                      child: Icon(
                        Icons.verified,
                        size: 14,
                        key: Key("contact-proven-${contact.id}-${address.value}"),
                      ),
                    ),
                  ),
              ],
            ),
          Text(contactDetails(l10n, contact),
              key: Key("contact-details-${contact.id}")),
        ],
      ),
      trailing: widget.mayManage ? _menu(l10n, contact) : null,
    );
  }

  Widget _menu(AppLocalizations l10n, Contact contact) {
    var blocked = contact.blocked.isNotEmpty;
    return PopupMenuButton<void Function()>(
      key: Key("contact-menu-${contact.id}"),
      enabled: !_busy,
      onSelected: (action) => action(),
      itemBuilder: (context) => [
        PopupMenuItem(
          key: Key("contact-rename-${contact.id}"),
          value: () => _rename(contact),
          child: Text(l10n.contactRename),
        ),
        PopupMenuItem(
          key: Key("contact-sessions-${contact.id}"),
          value: () => _sessions(contact),
          child: Text(l10n.contactSessionsEntry),
        ),
        PopupMenuItem(
          key: Key("contact-sign-ins-${contact.id}"),
          value: () => _signIns(contact),
          child: Text(l10n.contactSignInsEntry),
        ),
        PopupMenuItem(
          key: Key("contact-block-${contact.id}"),
          value: () => _block(contact, !blocked),
          child: Text(blocked ? l10n.letInAgain : l10n.shutOutEverywhere),
        ),
        PopupMenuItem(
          key: Key("contact-delete-${contact.id}"),
          value: () => _delete(contact),
          child: Text(l10n.deleteEllipsis),
        ),
      ],
    );
  }

  /// Runs one request of this section and shows the contact it answers in
  /// the place of the one shown; `null` from [request] removes it.
  Future<void> _act(
    Contact contact,
    Future<Contact?> Function(VAlbumClient client) request,
  ) async {
    var client = widget.client;
    if (client == null) {
      return;
    }
    setState(() {
      _busy = true;
      _problem = null;
    });
    Contact? answer;
    try {
      answer = await request(client);
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
      _contacts = [
        for (var shown in _contacts ?? const <Contact>[])
          if (shown.id != contact.id)
            shown
          else if (answer != null)
            answer,
      ];
    });
  }

  Future<void> _rename(Contact contact) async {
    var name = await showFormDialog<String>(
      context: context,
      builder: (context) => ContactRenameDialog(name: contact.name),
    );
    if (name == null || name.trim().isEmpty || !mounted) {
      return;
    }
    await _act(contact, (client) => client.renameContact(contact.id, name));
  }

  Future<void> _block(Contact contact, bool shutOut) =>
      _act(contact, (client) => client.blockContact(contact.id, shutOut));

  Future<void> _sessions(Contact contact) async {
    var client = widget.client;
    if (client == null) {
      return;
    }
    await showDialog<void>(
      context: context,
      builder: (context) => ContactSessionsDialog(
        client: client,
        contact: contact,
        onChanged: (changed) {
          if (mounted) {
            setState(() {
              _contacts = [
                for (var shown in _contacts ?? const <Contact>[])
                  shown.id == changed.id ? changed : shown,
              ];
            });
          }
        },
      ),
    );
  }

  /// Shows how [contact] signs in besides their link (issues #208, #204),
  /// each way with "Remove".
  Future<void> _signIns(Contact contact) async {
    var client = widget.client;
    if (client == null) {
      return;
    }
    await showDialog<void>(
      context: context,
      builder: (context) => ContactSignInsDialog(
        client: client,
        contact: contact,
        onChanged: (changed) {
          if (mounted) {
            setState(() {
              _contacts = [
                for (var shown in _contacts ?? const <Contact>[])
                  shown.id == changed.id ? changed : shown,
              ];
            });
          }
        },
      ),
    );
  }

  /// Deletes [contact], after a question naming what goes and what stays.
  Future<void> _delete(Contact contact) async {
    var l10n = AppLocalizations.of(context)!;
    var confirmed = await confirmHere(
      context: context,
      dialogKey: "delete-contact-confirm",
      title: l10n.deleteContactTitle(contact.name),
      message: l10n.deleteContactMessage,
      confirmLabel: l10n.delete,
      confirmKey: "delete-contact-confirmed",
    );
    if (confirmed != true || !mounted) {
      return;
    }
    await _act(contact, (client) async {
      await client.deleteContact(contact.id);
      return null;
    });
  }
}

/// The line under a contact's addresses (issue #203): shut out, on how many
/// browsers they are signed in, how many photos they added, and when they
/// were last seen.
String contactDetails(AppLocalizations l10n, Contact contact) {
  var now = DateTime.now();
  var live = [
    for (var session in contact.sessions)
      if (_liveAt(session.expires, now)) session,
  ];
  return [
    if (contact.blocked.isNotEmpty) l10n.shutOutEverywhereMark,
    l10n.contactSessionCount(live.length),
    if (contact.authenticator.isNotEmpty) l10n.contactAuthenticatorMark,
    if (contact.passkeys.isNotEmpty) l10n.contactPasskeyCount(contact.passkeys.length),
    if (contact.uploads > 0) l10n.photosAdded(contact.uploads),
    if (contact.lastSeen.isNotEmpty) l10n.lastSeenOn(dayOf(contact.lastSeen)),
  ].join(" · ");
}

bool _liveAt(String expires, DateTime now) {
  try {
    return DateTime.parse(expires).isAfter(now);
  } catch (_) {
    return true;
  }
}

/// Asks for a contact's new name (issue #203); pops the name, or nothing.
class ContactRenameDialog extends StatefulWidget {
  /// The name the contact has now.
  final String name;

  const ContactRenameDialog({super.key, required this.name});

  @override
  State<ContactRenameDialog> createState() => _ContactRenameDialogState();
}

class _ContactRenameDialogState extends State<ContactRenameDialog> {
  late final TextEditingController _name =
      TextEditingController(text: widget.name);

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  void _done() {
    var name = _name.text.trim();
    if (name.isNotEmpty) {
      Navigator.of(context).pop(name);
    }
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return FormDialogFrame(
      key: const Key("contact-rename-dialog"),
      title: Text(l10n.contactRenameTitle),
      fields: [
        TextField(
          key: const Key("contact-rename-field"),
          controller: _name,
          autofocus: true,
          decoration: InputDecoration(labelText: l10n.nameLabel),
          onSubmitted: (_) => _done(),
        ),
        const SizedBox(height: 8),
        Text(l10n.contactRenameNote),
      ],
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.cancel),
        ),
        FilledButton(
          key: const Key("contact-rename-save"),
          onPressed: _done,
          child: Text(l10n.save),
        ),
      ],
    );
  }
}

/// The browsers a contact is signed in on, each with "End", and "End all"
/// (issue #203).
///
/// Ending one signs that browser out: the next time it opens a personal link
/// the contact is asked who they are. Every change is handed to [onChanged],
/// the section behind the dialog.
class ContactSessionsDialog extends StatefulWidget {
  final VAlbumClient client;

  final Contact contact;

  final ValueChanged<Contact> onChanged;

  const ContactSessionsDialog({
    super.key,
    required this.client,
    required this.contact,
    required this.onChanged,
  });

  @override
  State<ContactSessionsDialog> createState() => _ContactSessionsDialogState();
}

class _ContactSessionsDialogState extends State<ContactSessionsDialog> {
  late Contact _contact = widget.contact;

  String? _problem;

  bool _busy = false;

  Future<void> _end({String session = ""}) async {
    setState(() {
      _busy = true;
      _problem = null;
    });
    try {
      var answer = await widget.client
          .endContactSession(_contact.id, session: session);
      if (!mounted) {
        return;
      }
      setState(() {
        _busy = false;
        _contact = answer;
      });
      widget.onChanged(answer);
    } catch (error) {
      if (mounted) {
        setState(() {
          _busy = false;
          _problem = refusalMessage(error);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var now = DateTime.now();
    var sessions = [
      for (var session in _contact.sessions)
        if (_liveAt(session.expires, now)) session,
    ];
    var problem = _problem;
    return AlertDialog(
      key: const Key("contact-sessions-dialog"),
      title: Text(l10n.contactSessionsTitle(_contact.name)),
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (problem != null)
                sectionProblem(
                    context, problem, const Key("contact-sessions-error")),
              if (sessions.isEmpty)
                Text(l10n.noContactSessions,
                    key: const Key("contact-sessions-none")),
              for (var session in sessions)
                ListTile(
                  key: Key("contact-session-${session.id}"),
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                  leading: const Icon(Icons.web),
                  title: Text(l10n.contactSessionSince(dayOf(session.created))),
                  subtitle: Text([
                    if (session.lastUsed.isNotEmpty)
                      l10n.lastSeenOn(dayOf(session.lastUsed)),
                    if (session.linkLabel.isNotEmpty)
                      l10n.contactSessionVia(session.linkLabel),
                  ].join(" · ")),
                  trailing: TextButton(
                    key: Key("contact-session-end-${session.id}"),
                    onPressed: _busy ? null : () => _end(session: session.id),
                    child: Text(l10n.endSession),
                  ),
                ),
            ],
          ),
        ),
      ),
      actions: [
        if (sessions.length > 1)
          TextButton(
            key: const Key("contact-sessions-end-all"),
            onPressed: _busy ? null : () => _end(),
            child: Text(l10n.endAllSessions),
          ),
        TextButton(
          key: const Key("contact-sessions-close"),
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.close),
        ),
      ],
    );
  }
}

/// The ways somebody signs in that a member who manages them may remove: an
/// authenticator app, passkeys, and — for a member — proven addresses.
@immutable
class HeldSignIns {
  /// Whose they are, as the titles name them.
  final String name;

  /// Since when an authenticator app signs them in, empty for none.
  final String authenticator;

  final List<ContactPasskey> passkeys;

  final List<ContactAddress> addresses;

  const HeldSignIns({
    required this.name,
    required this.authenticator,
    required this.passkeys,
    this.addresses = const [],
  });

  /// A contact's (issues #204, #208).
  factory HeldSignIns.ofContact(Contact contact) => HeldSignIns(
        name: contact.name,
        authenticator: contact.authenticator,
        passkeys: contact.passkeys,
      );

  /// A member's (issue #233).
  factory HeldSignIns.ofUser(UserEntry user) => HeldSignIns(
        name: user.name,
        authenticator: user.authenticator,
        passkeys: user.passkeys,
        addresses: user.addresses,
      );

  /// Whether there is anything to remove.
  bool get isEmpty =>
      authenticator.isEmpty && passkeys.isEmpty && addresses.isEmpty;
}

/// How a contact signs in besides their link: their passkeys (issue #204)
/// and their authenticator app (issue #208), each with "Remove" after a
/// question.
///
/// Every change is handed to [onChanged], the section behind the dialog.
class ContactSignInsDialog extends StatelessWidget {
  final VAlbumClient client;

  final Contact contact;

  final ValueChanged<Contact> onChanged;

  const ContactSignInsDialog({
    super.key,
    required this.client,
    required this.contact,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) => HeldSignInsDialog(
        keyPrefix: "contact",
        signIns: HeldSignIns.ofContact(contact),
        removeMessage: AppLocalizations.of(context)!.contactSignInRemoveMessage,
        remove: (method, id) async {
          var answer =
              await client.removeContactSignIn(contact.id, method, id: id);
          onChanged(answer);
          return HeldSignIns.ofContact(answer);
        },
      );
}

/// How a member signs in on a new browser besides a code (issue #233), as
/// the administrator sees and removes it.
class UserSignInsDialog extends StatelessWidget {
  final VAlbumClient client;

  final UserEntry user;

  /// Hands the users the server answered to the section behind the dialog.
  final ValueChanged<UserList> onChanged;

  const UserSignInsDialog({
    super.key,
    required this.client,
    required this.user,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) => HeldSignInsDialog(
        keyPrefix: "user",
        signIns: HeldSignIns.ofUser(user),
        removeMessage: AppLocalizations.of(context)!.userSignInRemoveMessage,
        remove: (method, id) async {
          var answer = await client.removeUserSignIn(user.name, method, id: id);
          onChanged(answer);
          var changed = answer.users
              .where((entry) => entry.name == user.name)
              .firstOrNull;
          return changed == null
              ? HeldSignIns(name: user.name, authenticator: "", passkeys: const [])
              : HeldSignIns.ofUser(changed);
        },
      );
}

/// The ways somebody signs in, each with "Remove" after a question: the one
/// dialog of a contact's (issues #204, #208) and a member's (issue #233).
class HeldSignInsDialog extends StatefulWidget {
  /// What the keys of this dialog start with: `contact` or `user`.
  final String keyPrefix;

  final HeldSignIns signIns;

  /// What the question before a removal says.
  final String removeMessage;

  /// Removes one way (`totp`, `passkey`, `email` with its id) and answers
  /// what is left.
  final Future<HeldSignIns> Function(String method, String id) remove;

  const HeldSignInsDialog({
    super.key,
    required this.keyPrefix,
    required this.signIns,
    required this.removeMessage,
    required this.remove,
  });

  @override
  State<HeldSignInsDialog> createState() => _HeldSignInsDialogState();
}

class _HeldSignInsDialogState extends State<HeldSignInsDialog> {
  late HeldSignIns _signIns = widget.signIns;

  String? _problem;

  bool _busy = false;

  String get _p => widget.keyPrefix;

  Future<void> _remove(String method, {String id = ""}) async {
    var l10n = AppLocalizations.of(context)!;
    var confirmed = await confirmHere(
      context: context,
      dialogKey: "$_p-sign-in-remove-confirm",
      title: method == "passkey"
          ? l10n.contactPasskeyRemoveTitle(_signIns.name)
          : method == "email"
              ? l10n.signInAddressRemoveTitle(id, _signIns.name)
              : l10n.contactAuthenticatorRemoveTitle(_signIns.name),
      message: widget.removeMessage,
      confirmLabel: l10n.remove,
      confirmKey: "$_p-sign-in-remove-confirmed",
    );
    if (confirmed != true || !mounted) {
      return;
    }
    setState(() {
      _busy = true;
      _problem = null;
    });
    try {
      var answer = await widget.remove(method, id);
      if (!mounted) {
        return;
      }
      setState(() {
        _busy = false;
        _signIns = answer;
      });
    } catch (error) {
      if (mounted) {
        setState(() {
          _busy = false;
          _problem = refusalMessage(error);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var problem = _problem;
    var authenticator = _signIns.authenticator;
    return AlertDialog(
      key: Key("$_p-sign-ins-dialog"),
      title: Text(l10n.contactSignInsTitle(_signIns.name)),
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (problem != null)
                sectionProblem(context, problem, Key("$_p-sign-ins-error")),
              if (_signIns.isEmpty)
                Text(l10n.contactSignInsNone, key: Key("$_p-sign-ins-none")),
              for (var passkey in _signIns.passkeys)
                ListTile(
                  key: Key("$_p-sign-in-passkey-${passkey.id}"),
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                  leading: const Icon(Icons.key),
                  title: Text(l10n.passkeyFrom(dayOf(passkey.created))),
                  subtitle: passkey.lastUsed.isEmpty
                      ? null
                      : Text(l10n.passkeyLastUsed(dayOf(passkey.lastUsed))),
                  trailing: TextButton(
                    key: Key("$_p-sign-in-passkey-remove-${passkey.id}"),
                    onPressed:
                        _busy ? null : () => _remove("passkey", id: passkey.id),
                    child: Text(l10n.remove),
                  ),
                ),
              if (authenticator.isNotEmpty)
                ListTile(
                  key: Key("$_p-sign-in-totp"),
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                  leading: const Icon(Icons.pin_outlined),
                  title: Text(l10n.authenticatorHeading),
                  subtitle: Text(
                      l10n.authenticatorActiveSince(dayOf(authenticator))),
                  trailing: TextButton(
                    key: Key("$_p-sign-in-totp-remove"),
                    onPressed: _busy ? null : () => _remove("totp"),
                    child: Text(l10n.remove),
                  ),
                ),
              for (var address in _signIns.addresses)
                ListTile(
                  key: Key("$_p-sign-in-address-${address.value}"),
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                  leading: const Icon(Icons.alternate_email),
                  title: Text(address.value),
                  trailing: TextButton(
                    key: Key("$_p-sign-in-address-remove-${address.value}"),
                    onPressed:
                        _busy ? null : () => _remove("email", id: address.value),
                    child: Text(l10n.remove),
                  ),
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          key: Key("$_p-sign-ins-close"),
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.close),
        ),
      ],
    );
  }
}
