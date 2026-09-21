/// The first screen of the app off the web: "Where is your album?" (issue #91).
///
/// A browser knows its server — it was loaded from it — but a phone or a
/// desktop build has to be told. It used to be told on the whole server
/// settings screen, address section, camera roll, cache, diagnostics and all,
/// which is a lot of screen for somebody who was simply sent a link.
///
/// So: one field that takes either a server address or a **link that carries
/// both** — an invitation URL names a server and a token at once — and the QR
/// scanner beside it, which carries a server and a code. Once the server is
/// known the sign-in form follows on the same screen, and an invitation opens
/// the welcome screen it opens in a browser, so that the app and the browser
/// have one join path.
library;

import 'package:flutter/material.dart';

import 'client.dart';
import 'device_code_payload.dart';
import 'device_code_scanner.dart';
import 'l10n/app_localizations.dart';
import 'resource.dart';
import 'settings.dart';
import 'sign_in_form.dart';
import 'urls.dart';

/// The key of the whole screen.
const Key firstScreenKey = Key("first.screen");

/// The key of the one field: a server address, or a link that carries one.
const Key firstScreenFieldKey = Key("first.server");

/// The key of the scanner button beside it (issue #66).
const Key firstScreenScanKey = Key("first.server.scan");

/// The key of the button that accepts what was entered.
const Key firstScreenContinueKey = Key("first.continue");

/// The key of the reason what was entered cannot be used.
const Key firstScreenProblemKey = Key("first.problem");

/// The key of the line naming the server once it is known.
const Key firstScreenServerKey = Key("first.server.named");

/// The key of the way back to the field.
const Key firstScreenChangeKey = Key("first.change");

/// The key of the way on without signing in.
const Key firstScreenSkipKey = Key("first.skip");

/// What the field asks for, above it.
String firstScreenLead(AppLocalizations l10n) => l10n.firstScreenLead;

/// The heading of the screen.
String firstScreenTitle(AppLocalizations l10n) => l10n.firstScreenTitle;

/// What a server that cannot be read is refused with.
String firstScreenNoServer(AppLocalizations l10n) => l10n.firstScreenNoServer;

/// Asks for the album server, and then signs in at it, see the library
/// comment.
class FirstScreen extends StatefulWidget {
  /// Where the server and the token are stored.
  final ServerSettings settings;

  /// Builds the client the invitation is asked about with, see
  /// [ClientFactory].
  final ClientFactory clientFor;

  /// Opens the welcome screen of an invitation the field was given.
  ///
  /// The app does it, not this screen: the welcome screen is a screen of the
  /// app's own start-up, whether the invitation arrived as an address bar or
  /// as a paste, see `VAlbumApp`.
  final void Function(ServerLocation location, InvitationInfo info) onInvited;

  const FirstScreen({
    super.key,
    required this.settings,
    required this.clientFor,
    required this.onInvited,
  });

  @override
  State<FirstScreen> createState() => FirstScreenState();
}

class FirstScreenState extends State<FirstScreen> {
  final TextEditingController controller = TextEditingController();

  /// The server this screen has settled on, `null` while it is being named.
  ServerLocation? accepted;

  /// The code a scan read, put into the sign-in form below once there is one.
  String scannedCode = "";

  /// Why what was entered cannot be used, `null` while it can.
  String? problem;

  /// Whether the server is being asked about an invitation.
  bool asking = false;

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var server = accepted;
    return Scaffold(
      key: firstScreenKey,
      appBar: AppBar(title: Text(firstScreenTitle(l10n))),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children:
                  server == null ? _asking(context) : _signingIn(context, server),
            ),
          ),
        ),
      ),
    );
  }

  /// Step one: the one field, the scanner, and the way on.
  List<Widget> _asking(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var scanner = DeviceCodeScannerScope.of(context);
    var field = TextField(
      key: firstScreenFieldKey,
      controller: controller,
      autocorrect: false,
      keyboardType: TextInputType.url,
      decoration: InputDecoration(
        labelText: l10n.serverAddressOrLink,
        border: const OutlineInputBorder(),
      ),
      onChanged: (_) => setState(() => problem = null),
      onSubmitted: (_) => accept(),
    );
    return [
      Text(firstScreenLead(l10n)),
      const SizedBox(height: 24),
      if (!scanner.available)
        field
      else
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(child: field),
            const SizedBox(width: 8),
            IconButton(
              key: firstScreenScanKey,
              icon: const Icon(Icons.qr_code_scanner),
              tooltip: l10n.scanCode,
              onPressed: () => _scan(scanner),
            ),
          ],
        ),
      const SizedBox(height: 16),
      if (problem != null)
        Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Text(
            problem!,
            key: firstScreenProblemKey,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        ),
      if (asking)
        Row(
          children: [
            const SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            const SizedBox(width: 8),
            Text(l10n.askingServer),
          ],
        )
      else
        FilledButton.icon(
          key: firstScreenContinueKey,
          onPressed: accept,
          icon: const Icon(Icons.arrow_forward),
          label: Text(l10n.continueAction),
        ),
    ];
  }

  /// Step two: the server is known, so this is the ordinary sign-in.
  ///
  /// The same [SignInForm] the server screen and the refusal page of a browser
  /// build, so that there is one sign-in in this app and not three. It saves
  /// the server together with the token, because a device that just became
  /// somebody's at a server belongs at that server.
  ///
  /// A server that shows its albums to anonymous callers needs no sign-in at
  /// all, and the way past is offered rather than hidden.
  List<Widget> _signingIn(BuildContext context, ServerLocation server) {
    var l10n = AppLocalizations.of(context)!;
    return [
      Text(l10n.albumServerLine(server.serverUrl), key: firstScreenServerKey),
      const SizedBox(height: 8),
      Align(
        alignment: Alignment.centerLeft,
        child: TextButton.icon(
          key: firstScreenChangeKey,
          onPressed: () => setState(() {
            accepted = null;
            scannedCode = "";
          }),
          icon: const Icon(Icons.edit),
          label: Text(l10n.anotherServer),
        ),
      ),
      const SizedBox(height: 16),
      SignInForm(
        key: const Key("first.signIn"),
        settings: widget.settings,
        clientFor: widget.clientFor,
        location: server,
        initialCode: scannedCode,
        saveServer: true,
        explain: true,
      ),
      const SizedBox(height: 8),
      TextButton(
        key: firstScreenSkipKey,
        onPressed: () => widget.settings.save(server.serverUrl),
        child: Text(l10n.openWithoutSigningIn),
      ),
    ];
  }

  /// Reads a payload off the camera: the server into the field, the code into
  /// the sign-in below (issues #66, #91).
  ///
  /// Nothing else happens — the same rule as everywhere a code is scanned: it
  /// is shown to the person who scanned it, and they press the button.
  Future<void> _scan(DeviceCodeScanner scanner) async {
    var l10n = AppLocalizations.of(context)!;
    var scanned = await scanner.scan(context);
    if (scanned == null || !mounted) {
      return;
    }
    var payload = parseDeviceCodePayload(scanned);
    if (payload == null) {
      setState(() => problem = notADeviceCodeRefusal(l10n));
      return;
    }
    controller.text = payload.serverUrl;
    setState(() {
      problem = null;
      scannedCode = payload.formattedCode;
    });
  }

  /// Takes what was entered: a server, an invitation link, or nothing usable.
  Future<void> accept() async {
    var l10n = AppLocalizations.of(context)!;
    ServerLocation location;
    try {
      location = serverLocationOf(controller.text);
    } on FormatException {
      setState(() => problem = firstScreenNoServer(l10n));
      return;
    }
    if (location.isShare) {
      // A share link opens one album in a browser and signs nothing in; it is
      // said rather than silently turned into a server address.
      setState(() => problem = shareLinkRefusal(l10n));
      return;
    }
    if (!location.isInvitation) {
      setState(() {
        problem = null;
        accepted = location;
      });
      return;
    }
    // An invitation names a server *and* a token: it does here what clicking
    // it in a browser does, see [InvitationWelcomeScreen].
    setState(() {
      problem = null;
      asking = true;
    });
    AuthInfo answer;
    try {
      answer = await widget
          .clientFor(location.dataUrl)
          .withToken(location.invitation)
          .authInfo();
    } catch (failure) {
      if (mounted) {
        setState(() {
          asking = false;
          problem = failure is VAlbumException
              ? failure.message
              : failure.toString();
        });
      }
      return;
    }
    if (!mounted) {
      return;
    }
    var offered = answer.invitation;
    if (offered == null) {
      setState(() {
        asking = false;
        problem = l10n.invitationUnknownHere;
      });
      return;
    }
    setState(() => asking = false);
    widget.onInvited(location, offered);
  }
}
