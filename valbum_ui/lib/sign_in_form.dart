/// The form that signs this device in: one code, a device name, and the name
/// field where the server asks for one (issues #89, #91).
///
/// A widget of its own because there are now two places that sign in, and they
/// must be the *same* sign-in: the server screen, where it sits under the
/// address, and the page a server refusing an anonymous caller is answered
/// with — where it is the whole point of the page, see `buildSignInRequired`
/// in `app.dart`. A browser already knows its server (it was loaded from it),
/// so telling it "sign-in required" and then sending it to a settings screen
/// was one detour too many.
///
/// The form owns nothing but what is typed into it. Where it signs in, what it
/// stores and what happens afterwards is the [ServerSettings] it is handed —
/// so a sign-in on the refusal page and a sign-in in the settings leave the
/// device in exactly the same state.
library;

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

import 'client.dart';
import 'contact_session.dart';
import 'device_code_payload.dart';
import 'device_code_scanner.dart';
import 'identify_view.dart' show oidcMethodPrefix, usePasskey;
import 'l10n/app_localizations.dart';
import 'passkeys.dart';
import 'platform.dart';
import 'resource.dart';
import 'settings.dart';
import 'sign_in_options.dart' show isTotpCode, totpCodeFormatter;
import 'urls.dart';

/// How the form signs this device in (issue #233): a code — the default —,
/// a member's authenticator app, or an e-mail address of theirs.
enum SignInWay { code, totp, email }

/// The key of "Other ways to sign in…" (issue #233).
const Key otherWaysKey = Key("signIn.otherWays");

/// The key of the field naming the member by name or e-mail address.
const Key memberNameFieldKey = Key("signIn.memberName");

/// The key of the field of the authenticator app's code.
const Key memberTotpFieldKey = Key("signIn.totpCode");

/// The key of the field of the address a code is mailed to.
const Key memberEmailFieldKey = Key("signIn.email");

/// The key of the field of the mailed code.
const Key memberEmailCodeFieldKey = Key("signIn.emailCode");

/// Where a member's sign-in with a provider keeps what it needs when the page
/// comes back (issue #233); the app reads the same store on its return. A
/// test replaces it.
ContactCredentialStore memberSignInStore = defaultContactCredentialStore();

/// The address of the page this app runs in, `null` off the web — where no
/// page leaves for a provider and comes back. A test replaces it.
Uri? Function() pageLocation = () => kIsWeb ? Uri.base : null;

/// Leaves the page for a provider's sign-in (issue #233). A test replaces
/// it.
void Function(String url) leaveForProvider = leaveForUrl;

/// Why a sign-in with a provider must not leave this page, `null` where it
/// may (issue #233).
///
/// What the return needs lies in this page's own storage, so the browser
/// must come back to a page of the same origin. The server names where it
/// sends the browser back ([OidcStarted.returnUrl]); where that is another
/// origin — the server's public address is not the one this page was opened
/// at — the sign-in could never be finished, and that is said before the page
/// is left rather than after. A server that does not name it is trusted.
String? providerReturnRefusal(AppLocalizations l10n, OidcStarted started) {
  var page = pageLocation();
  if (page == null) {
    return l10n.providerNeedsBrowser;
  }
  if (started.returnUrl.isEmpty) {
    return null;
  }
  var back = Uri.tryParse(started.returnUrl);
  if (back == null || back.origin != page.origin) {
    var origin = back == null ? started.returnUrl : back.origin;
    return l10n.providerReturnsElsewhere(origin);
  }
  return null;
}

/// What a scan naming another server is refused with, where this screen has
/// no server field to put it in (issue #91).
///
/// The refusal page of a browser is the case: it talks to the server it was
/// loaded from and to no other, so a code for a different server is said out
/// loud rather than sent to the wrong place.
String otherServerRefusal(AppLocalizations l10n, String scanned) =>
    l10n.otherServerRefusal(scanned);

/// The key of the sign-in form on a server's refusal page (issue #91).
const Key signInRequiredFormKey = Key("signInRequired.form");

/// Signs this device in at one server, see the library comment.
class SignInForm extends StatefulWidget {
  /// Where the token is stored once the server answered one.
  final ServerSettings settings;

  /// Builds the client the sign-in is sent with, see [ClientFactory].
  final ClientFactory clientFor;

  /// The server to sign in at, and the invitation it carries, if any.
  ///
  /// Not [ServerSettings.dataUrl]: the settings screen signs in at the address
  /// the user is *looking at*, which may not be the saved one yet.
  final ServerLocation location;

  /// Further buttons beside "Sign in", e.g. the settings' "Sign out".
  final List<Widget> actions;

  /// What a scan read, where the embedder has a server field to fill.
  ///
  /// `null` where there is none: the code is then still put into the field,
  /// and a payload naming another server is refused with
  /// [otherServerRefusal] instead of signing in at the wrong place.
  final void Function(DeviceCodePayload payload)? onScanned;

  /// Called once the server answered a token and it was stored.
  final void Function(SignedInUser user, ServerLocation location)? onSignedIn;

  /// Whether the form explains what a code is above the fields.
  ///
  /// The settings screen says it in its own lead; a standalone form says it
  /// itself.
  final bool explain;

  /// The code the field starts with, e.g. one a QR code carried.
  final String initialCode;

  /// Whether a successful sign-in also stores [location] as *the* server.
  ///
  /// True where the sign-in is how the device learns which server it belongs
  /// to — the first screen of the app (issue #91) — and false in the settings,
  /// where the address is saved by its own button and a sign-in may be tried
  /// against an address that is only being looked at. An invitation always
  /// stores it: what it names is a server *and* a token, and a device that
  /// just became somebody's there belongs at that server (issue #52).
  final bool saveServer;

  const SignInForm({
    super.key,
    required this.settings,
    required this.clientFor,
    required this.location,
    this.actions = const [],
    this.onScanned,
    this.onSignedIn,
    this.explain = false,
    this.initialCode = "",
    this.saveServer = false,
  });

  @override
  State<SignInForm> createState() => SignInFormState();
}

class SignInFormState extends State<SignInForm> {
  /// The code signing this device in (issues #65, #89, #92).
  ///
  /// Never stored: it is exchanged for this device's own token exactly once.
  late final TextEditingController codeController =
      TextEditingController(text: widget.initialCode);

  /// The name this device announces itself with.
  ///
  /// Filled in [didChangeDependencies]: the suggested name is a localized one
  /// (issue #108), and the localizations are only reachable once this state is
  /// in the tree.
  final TextEditingController deviceController = TextEditingController();

  /// Whether [deviceController] has been filled with its suggestion.
  bool _deviceNameFilled = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_deviceNameFilled) {
      return;
    }
    _deviceNameFilled = true;
    deviceController.text = widget.settings.deviceName ??
        defaultDeviceName(AppLocalizations.of(context)!);
  }

  /// The name the user signs in under, asked for only where it is a choice.
  late final TextEditingController userController = TextEditingController(
    text: widget.settings.userName ?? "",
  );

  /// Whether the server asked for a name, see [nameRequiredMessage].
  bool nameRequired = false;

  /// Why the sign-in was not even attempted, if it was not.
  String? signInError;

  /// The outcome of the last sign-in attempt, if any.
  ConnectionTestResult? pairing;

  /// Whether a sign-in request is running.
  bool pairingRunning = false;

  /// How this form signs in, see [SignInWay].
  SignInWay way = SignInWay.code;

  /// The ways the server offers a member besides a code, `null` until "Other
  /// ways to sign in…" asked it (issue #233).
  List<ProofMethod>? otherWays;

  /// Whether the server is being asked for [otherWays].
  bool _askingWays = false;

  /// The member's name or e-mail address, for their authenticator app.
  final TextEditingController memberController = TextEditingController();

  /// The code of the authenticator app.
  final TextEditingController totpController = TextEditingController();

  /// The address a code is mailed to.
  final TextEditingController emailController = TextEditingController();

  /// The mailed code.
  final TextEditingController emailCodeController = TextEditingController();

  /// Whether a code was mailed to [emailController]'s address.
  bool emailSent = false;

  @override
  void dispose() {
    codeController.dispose();
    deviceController.dispose();
    userController.dispose();
    memberController.dispose();
    totpController.dispose();
    emailController.dispose();
    emailCodeController.dispose();
    super.dispose();
  }

  /// Puts a scanned code into the field, see [SignInForm.onScanned].
  void acceptScanned(DeviceCodePayload payload) {
    codeController.text = payload.formattedCode;
    setState(() {
      signInError = null;
      pairing = null;
    });
  }

  /// Whether the sign-in asks for a name: an invitation names its new user,
  /// and a code for a user who has none needs one (issue #89).
  bool get asksForAName => widget.location.isInvitation || nameRequired;

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var inviting = widget.location.isInvitation;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (widget.explain && !inviting) ...[
          Text(signInCodeExplanation(l10n)),
          const SizedBox(height: 16),
        ],
        // The name is asked for where it is a choice — an invitation, and a
        // code for a user who has no name yet — and nowhere else, issue #89.
        if (asksForAName) ...[
          TextField(
            key: userNameFieldKey,
            controller: userController,
            autofocus: nameRequired,
            autocorrect: false,
            decoration: InputDecoration(
              labelText: inviting ? l10n.yourName : l10n.userNameLabel,
              helperText: inviting ? l10n.yourNameHelp : userNameHelp(l10n),
              helperMaxLines: 3,
              border: const OutlineInputBorder(),
            ),
            onSubmitted: (_) => signIn(),
          ),
          const SizedBox(height: 16),
        ],
        if (!inviting && way == SignInWay.code) ...[
          _codeField(l10n),
          const SizedBox(height: 16),
        ],
        if (!inviting && way == SignInWay.totp) ..._totpFields(l10n),
        if (!inviting && way == SignInWay.email) ..._emailFields(l10n),
        TextField(
          key: deviceNameFieldKey,
          controller: deviceController,
          autocorrect: false,
          decoration: InputDecoration(
            labelText: l10n.deviceNameLabel,
            border: const OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 16),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            FilledButton.icon(
              key: signInButtonKey,
              onPressed: pairingRunning ? null : signIn,
              icon: const Icon(Icons.login),
              label: Text(way == SignInWay.email && !emailSent
                  ? l10n.identifySendCode
                  : l10n.signInHeading),
            ),
            ...widget.actions,
          ],
        ),
        if (!inviting) ..._otherWays(l10n),
        const SizedBox(height: 16),
        if (pairingRunning)
          Row(
            children: [
              const SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
              const SizedBox(width: 8),
              Text(l10n.signingIn),
            ],
          ),
        if (signInError != null)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text(
              signInError!,
              key: signInErrorKey,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ),
        if (!pairingRunning && pairing != null) outcomeRow(context, pairing!),
      ],
    );
  }

  /// The fields of a member's authenticator app (issue #233): who, and the
  /// code the app shows.
  List<Widget> _totpFields(AppLocalizations l10n) => [
        TextField(
          key: memberNameFieldKey,
          controller: memberController,
          autocorrect: false,
          decoration: InputDecoration(
            labelText: l10n.memberNameOrEmailLabel,
            border: const OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 16),
        TextField(
          key: memberTotpFieldKey,
          controller: totpController,
          keyboardType: TextInputType.number,
          inputFormatters: [totpCodeFormatter],
          decoration: InputDecoration(
            labelText: l10n.totpCodeLabel,
            border: const OutlineInputBorder(),
          ),
          onSubmitted: (_) => signIn(),
        ),
        const SizedBox(height: 16),
      ];

  /// The fields of a mailed code (issue #233): the address, then the code.
  List<Widget> _emailFields(AppLocalizations l10n) => [
        TextField(
          key: memberEmailFieldKey,
          controller: emailController,
          autocorrect: false,
          enabled: !emailSent,
          keyboardType: TextInputType.emailAddress,
          decoration: InputDecoration(
            labelText: l10n.identifyAddressLabel,
            border: const OutlineInputBorder(),
          ),
          onSubmitted: (_) => signIn(),
        ),
        const SizedBox(height: 16),
        if (emailSent) ...[
          Text(l10n.memberCodeSent),
          const SizedBox(height: 8),
          TextField(
            key: memberEmailCodeFieldKey,
            controller: emailCodeController,
            keyboardType: TextInputType.number,
            decoration: InputDecoration(
              labelText: l10n.identifyCodeLabel,
              border: const OutlineInputBorder(),
            ),
            onSubmitted: (_) => signIn(),
          ),
          const SizedBox(height: 16),
        ],
      ];

  /// "Other ways to sign in…" and, once the server answered, the ways it
  /// offers a member besides a code (issue #233) — exactly those, so that
  /// nothing is offered the server would refuse.
  List<Widget> _otherWays(AppLocalizations l10n) {
    var ways = otherWays;
    if (ways == null) {
      return [
        const SizedBox(height: 8),
        Align(
          alignment: AlignmentDirectional.centerStart,
          child: TextButton(
            key: otherWaysKey,
            onPressed: _askingWays ? null : _askWays,
            child: Text(l10n.otherWaysToSignIn),
          ),
        ),
      ];
    }
    var names = ways.map((method) => method.name).toSet();
    var buttons = <Widget>[
      if (way != SignInWay.code)
        TextButton(
          key: const Key("signIn.way.code"),
          onPressed: pairingRunning ? null : () => _switch(SignInWay.code),
          child: Text(l10n.signInWithCode),
        ),
      if (names.contains("totp") && way != SignInWay.totp)
        TextButton(
          key: const Key("signIn.way.totp"),
          onPressed: pairingRunning ? null : () => _switch(SignInWay.totp),
          child: Text(l10n.identifyTotp),
        ),
      if (names.contains("mail-code") && way != SignInWay.email)
        TextButton(
          key: const Key("signIn.way.email"),
          onPressed: pairingRunning ? null : () => _switch(SignInWay.email),
          child: Text(l10n.memberSendCodeByEmail),
        ),
      if (names.contains("passkey") && passkeyAuthenticator != null)
        OutlinedButton.icon(
          key: const Key("signIn.way.passkey"),
          onPressed: pairingRunning ? null : _passkey,
          icon: const Icon(Icons.key),
          label: Text(l10n.identifyPasskey),
        ),
      // A provider's sign-in leaves the page and comes back to it: only in a
      // browser (issue #233).
      for (var method in ways)
        if (method.name.startsWith(oidcMethodPrefix) && pageLocation() != null)
          OutlinedButton(
            key: Key("signIn.way.${method.name}"),
            onPressed: pairingRunning ? null : () => _provider(method),
            child: Text(l10n.identifyContinueWith(method.label.isEmpty
                ? method.name.substring(oidcMethodPrefix.length)
                : method.label)),
          ),
    ];
    return [
      const SizedBox(height: 8),
      if (ways.isEmpty)
        Text(l10n.noOtherWaysToSignIn, key: const Key("signIn.noOtherWays"))
      else
        Wrap(spacing: 8, runSpacing: 8, children: buttons),
    ];
  }

  /// Asks the server which ways it offers a member besides a code.
  Future<void> _askWays() async {
    setState(() => _askingWays = true);
    List<ProofMethod> ways;
    try {
      var info = await widget
          .clientFor(widget.location.dataUrl)
          .withToken(null)
          .authInfo();
      ways = info.signInMethods;
    } catch (_) {
      ways = const [];
    }
    if (mounted) {
      setState(() {
        _askingWays = false;
        otherWays = ways;
      });
    }
  }

  void _switch(SignInWay next) => setState(() {
        way = next;
        signInError = null;
        pairing = null;
        emailSent = false;
        emailCodeController.clear();
      });

  /// The name this device announces itself with.
  String _deviceName(AppLocalizations l10n) =>
      deviceController.text.trim().isEmpty
          ? defaultDeviceName(l10n)
          : deviceController.text.trim();

  /// The client of a sign-in: the server of the form, and no token.
  VAlbumClient get _anonymous =>
      widget.clientFor(widget.location.dataUrl).withToken(null);

  /// "Sign in with passkey" (issue #233): a ceremony that names nobody, the
  /// passkey names the member; the pairing finishes it.
  Future<void> _passkey() => _signInWith((l10n) async {
        var client = _anonymous;
        var options = await client.passkeyStart();
        var answer = await usePasskey(
            l10n, () => passkeyAuthenticator!.get(options.options));
        return client.pair(
          deviceName: _deviceName(l10n),
          passkey: PasskeyResponse(ticket: options.ticket, response: answer),
        );
      });

  /// "Continue with Google" (issue #233): the page leaves for the provider
  /// and comes back to the application, which finishes the sign-in.
  Future<void> _provider(ProofMethod method) async {
    var l10n = AppLocalizations.of(context)!;
    setState(() {
      signInError = null;
      pairing = null;
      pairingRunning = true;
    });
    try {
      var started = await _anonymous.oidcStart(OidcStart(
          provider: method.name.substring(oidcMethodPrefix.length)));
      var refusal = providerReturnRefusal(l10n, started);
      if (refusal != null) {
        _failed(refusal);
        return;
      }
      // The server travels with the sign-in: the return finishes it there,
      // whether or not the address was saved (issue #233).
      memberSignInStore.keepMemberSignIn(PendingMemberSignIn(
        dataUrl: widget.location.dataUrl,
        binding: started.binding,
        deviceName: _deviceName(l10n),
      ));
      leaveForProvider(started.url);
    } on VAlbumException catch (failure) {
      _failed(failure.message);
    } catch (failure) {
      _failed("$failure");
    }
  }

  void _failed(String message) {
    if (mounted) {
      setState(() {
        pairingRunning = false;
        pairing = ConnectionTestResult(false, message);
      });
    }
  }

  /// Runs one way of a member's sign-in and stores what it answered exactly
  /// as a redeemed code is stored.
  Future<void> _signInWith(
      Future<PairResponse?> Function(AppLocalizations l10n) request) async {
    var l10n = AppLocalizations.of(context)!;
    setState(() {
      signInError = null;
      pairing = null;
      pairingRunning = true;
    });
    PairResponse? response;
    try {
      response = await request(l10n);
    } on VAlbumException catch (failure) {
      _failed(failure.message);
      return;
    } on http.ClientException catch (failure) {
      _failed(failure.message);
      return;
    } catch (failure) {
      _failed("$failure");
      return;
    }
    if (response == null) {
      if (mounted) {
        setState(() => pairingRunning = false);
      }
      return;
    }
    await _signedIn(response);
  }

  /// Stores the token a sign-in answered and tells the embedder, the end of
  /// every way in.
  Future<void> _signedIn(PairResponse response) async {
    var l10n = AppLocalizations.of(context)!;
    var location = widget.location;
    if (location.isInvitation || widget.saveServer) {
      // Before the token is stored: saving a server forgets the token of the
      // one it replaces, see [ServerSettings.save].
      await widget.settings.save(location.serverUrl);
    }
    await widget.settings.signedInAs(
      response.token,
      response.deviceName,
      userName: response.userName,
    );
    // Filled from one `?type=auth` and not from the pairing answer (issue
    // #86): that answer carries no space, no clearance and no share flag.
    var signedIn = await identityOf(widget.clientFor, location.dataUrl, response);
    if (!mounted) {
      return;
    }
    setState(() {
      pairingRunning = false;
      pairing = ConnectionTestResult(true, l10n.signInSucceeded);
      nameRequired = false;
      codeController.clear();
      totpController.clear();
      emailCodeController.clear();
      userController.text = signedIn.userName ?? "";
    });
    widget.onSignedIn?.call(signedIn, location);
  }

  /// A member's authenticator app (issue #233).
  ///
  /// Refused here, in its own words, until there is a name and a whole code:
  /// an empty field is no guess worth sending.
  Future<void> _signInByTotp() {
    var l10n = AppLocalizations.of(context)!;
    var refusal = memberController.text.trim().isEmpty
        ? l10n.memberNameRequired
        : !isTotpCode(totpController.text)
            ? l10n.totpCodeIncomplete
            : null;
    if (refusal != null) {
      setState(() {
        signInError = refusal;
        pairing = null;
      });
      return Future.value();
    }
    return _sendTotp();
  }

  Future<void> _sendTotp() => _signInWith((l10n) => _anonymous.pair(
        deviceName: _deviceName(l10n),
        userName: memberController.text.trim(),
        totpCode: totpController.text.trim(),
      ));

  /// A mailed code (issue #233): first the mail, then the code.
  Future<void> _signInByEmail() => _signInWith((l10n) async {
        var client = _anonymous;
        var address = emailController.text.trim();
        if (!emailSent) {
          await client.proveEmail(EmailProof(address: address));
          if (mounted) {
            setState(() => emailSent = true);
          }
          return null;
        }
        var answer = await client.verifyEmail(EmailVerify(
          address: address,
          code: emailCodeController.text.trim(),
          deviceName: _deviceName(l10n),
        ));
        var member = answer.member;
        if (member == null) {
          throw VAlbumException(l10n.memberAddressSignsNobodyIn);
        }
        return member;
      });

  /// The code field, with the camera beside it where there is one (issue #66).
  ///
  /// The scan is an addition to the typing and never a replacement: the field
  /// is the same field, and a platform without a camera simply has no button.
  Widget _codeField(AppLocalizations l10n) {
    var scanner = DeviceCodeScannerScope.of(context);
    var field = TextField(
      key: deviceCodeFieldKey,
      controller: codeController,
      autocorrect: false,
      textCapitalization: TextCapitalization.characters,
      inputFormatters: [deviceCodeFormatter],
      decoration: InputDecoration(
        labelText: l10n.signInCodeLabel,
        helperText: l10n.signInCodeHelp,
        helperMaxLines: 3,
        border: const OutlineInputBorder(),
      ),
      onSubmitted: (_) => signIn(),
    );
    if (!scanner.available) {
      return field;
    }
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(child: field),
        const SizedBox(width: 8),
        IconButton(
          key: deviceCodeScanKey,
          icon: const Icon(Icons.qr_code_scanner),
          tooltip: l10n.scanCode,
          onPressed: () => _scan(scanner),
        ),
      ],
    );
  }

  /// Reads a code off the camera and puts it into the fields (issue #66).
  ///
  /// What is scanned fills the fields, and *nothing else happens*: the sign-in
  /// is the button below, pressed by the person who can now see what was read.
  /// A picture of a QR code is worth no more than a forwarded code, and
  /// neither is sent anywhere until somebody says so.
  ///
  /// A scan that was cancelled, refused or impossible answers `null` and is
  /// silent here — the scanner itself said why, on its own page. Text that is
  /// not a code is refused with [notADeviceCodeRefusal], and a code for
  /// another server where this screen cannot go with
  /// [otherServerRefusal]; the fields are left exactly as they were.
  Future<void> _scan(DeviceCodeScanner scanner) async {
    var l10n = AppLocalizations.of(context)!;
    var scanned = await scanner.scan(context);
    if (scanned == null || !mounted) {
      return;
    }
    var payload = parseDeviceCodePayload(scanned);
    if (payload == null) {
      setState(() {
        signInError = notADeviceCodeRefusal(l10n);
        pairing = null;
      });
      return;
    }
    var takesTheServer = widget.onScanned;
    if (takesTheServer != null) {
      takesTheServer(payload);
      acceptScanned(payload);
      return;
    }
    if (!sameServer(payload.serverUrl, widget.location.serverUrl)) {
      setState(() {
        signInError = otherServerRefusal(l10n, payload.serverUrl);
        pairing = null;
      });
      return;
    }
    acceptScanned(payload);
  }

  /// Signs this device in at [SignInForm.location], storing what comes back.
  ///
  /// One field and one request, whoever made the code: a seat code, a code
  /// from another of one's devices, a recovery code, a backup code (issue
  /// #92) — or the token of an invitation, which since issue #89 *is* a code
  /// and goes in the very same field. The invitation of the location takes
  /// the place of what would be typed, because its link carries it; the name
  /// is then the name the new user chooses for themselves. The server tells
  /// the kinds apart, this form does not.
  Future<void> signIn() async {
    if (!widget.location.isInvitation && way == SignInWay.totp) {
      return _signInByTotp();
    }
    if (!widget.location.isInvitation && way == SignInWay.email) {
      return _signInByEmail();
    }
    var l10n = AppLocalizations.of(context)!;
    var location = widget.location;
    var code = location.isInvitation
        ? location.invitation
        : codeController.text.trim();
    // The link a code dialog offers beside its QR code, pasted into the code
    // field: its code, if it is a code for this server.
    var pasted = location.isInvitation ? null : parseDeviceCodePayload(code);
    if (pasted != null) {
      var takesTheServer = widget.onScanned;
      if (takesTheServer != null &&
          !sameServer(pasted.serverUrl, location.serverUrl)) {
        // Where the form may change the server, a pasted link does what a
        // scanned one does: the server into its field, the code into this
        // one, and the next press signs in there.
        takesTheServer(pasted);
        acceptScanned(pasted);
        return;
      }
      if (!sameServer(pasted.serverUrl, location.serverUrl)) {
        setState(() {
          signInError = otherServerRefusal(l10n, pasted.serverUrl);
          pairing = null;
        });
        return;
      }
      code = pasted.code;
      codeController.text = pasted.formattedCode;
    }
    if (code.isEmpty) {
      // There is one way in and it is a code (issue #89); an empty field is
      // said here rather than sent to be refused.
      setState(() {
        signInError = codeRequiredRefusal(l10n);
        pairing = null;
      });
      return;
    }
    // The name is sent where there is one to send: everywhere else the code
    // says who, and a name the server did not ask for would only be a check
    // it can fail.
    var userName = asksForAName ? userController.text.trim() : "";

    setState(() {
      signInError = null;
      pairing = null;
      pairingRunning = true;
    });

    var client = widget.clientFor(location.dataUrl).withToken(null);
    ConnectionTestResult outcome;
    SignedInUser? signedIn;
    try {
      var response = await client.pair(
        deviceCode: code,
        deviceName: deviceController.text.trim().isEmpty
            ? defaultDeviceName(l10n)
            : deviceController.text.trim(),
        userName: userName,
      );
      if (location.isInvitation || widget.saveServer) {
        // Before the token is stored: saving a server forgets the token of
        // the one it replaces, see [ServerSettings.save].
        await widget.settings.save(location.serverUrl);
      }
      await widget.settings.signedInAs(
        response.token,
        response.deviceName,
        userName: response.userName,
      );
      // Filled from one `?type=auth` and not from the pairing answer (issue
      // #86): that answer carries no space, no clearance and no share flag.
      signedIn = await identityOf(
        widget.clientFor,
        location.dataUrl,
        response,
      );
      outcome = ConnectionTestResult(true, l10n.signInSucceeded);
    } on VAlbumException catch (failure) {
      if (needsAName(failure)) {
        // The code signs in a user who has no name yet: the server says so,
        // the field appears with the server's own sentence above it, and the
        // person chooses the name themselves (issue #89). The code is left in
        // the field: it is still good, and the next press sends it with the
        // name.
        if (mounted) {
          setState(() {
            pairingRunning = false;
            nameRequired = true;
            pairing = null;
            signInError = failure.message;
          });
        }
        return;
      }
      outcome = ConnectionTestResult(false, failure.message);
    } on http.ClientException catch (failure) {
      outcome = ConnectionTestResult(false, failure.message);
    } catch (failure) {
      outcome = ConnectionTestResult(false, failure.toString());
    }

    if (!mounted) {
      return;
    }
    setState(() {
      pairingRunning = false;
      pairing = outcome;
      if (outcome.ok) {
        nameRequired = false;
        codeController.clear();
        userController.text = signedIn?.userName ?? "";
      }
    });
    if (outcome.ok && signedIn != null) {
      widget.onSignedIn?.call(signedIn, location);
    }
  }
}

/// Who the server says this device is, right after it was paired (issue #86).
///
/// The same question the caller scope asks, asked once here so that whatever
/// shows the signed-in user is right at once: the pairing answer names the
/// user, the device and the role, but not the space, the clearance or the
/// share flag. Where the question cannot be asked — a server that answers the
/// pairing and nothing else — what the pairing said stands.
Future<SignedInUser> identityOf(
  ClientFactory clientFor,
  String dataUrl,
  PairResponse response,
) async {
  var fallback = SignedInUser(
    userName: response.userName,
    deviceName: response.deviceName,
    role: response.role,
    space: response.space,
  );
  try {
    var info = await clientFor(dataUrl).withToken(response.token).authInfo();
    if (info.deviceName.isEmpty) {
      return fallback;
    }
    return SignedInUser(
      userName: info.userName,
      deviceName: info.deviceName,
      role: info.role,
      space: info.space,
      clearance: info.clearance,
      mayShare: info.mayShare,
    );
  } catch (_) {
    return fallback;
  }
}
