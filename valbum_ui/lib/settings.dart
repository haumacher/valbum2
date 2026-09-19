/// The server the app talks to: the stored setting ([ServerSettings]), its
/// persistence ([SettingsStore]) and the screen that edits it
/// ([ServerSettingsScreen]).
///
/// The app derives its data URL from its own origin on the web (see
/// `urls.dart`), but a phone or a desktop build has no origin to derive
/// anything from. The user therefore names the *app base* of the album server
/// — `http://nas.local:8080/valbum/` — and everything else follows from it,
/// see [dataUrlOf].
///
/// The device token this app is signed in with lives beside the URL (issue
/// #28): it is issued by the server against a sign-in code, stored here
/// and sent by [VAlbumClient] on every request. Since issue #45 the token
/// belongs to a *user* — the name that user signed in under is stored beside
/// the token, so the settings can say who this device is even while the
/// server cannot be reached.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import 'caller.dart';
import 'camera_roll_view.dart';
import 'client.dart';
import 'device_code_payload.dart';
import 'device_code_scanner.dart';
import 'diagnostics.dart';
import 'invitation.dart';
import 'manage_view.dart';
import 'offline.dart';
import 'platform.dart';
import 'resource.dart';
import 'sign_in_form.dart';
import 'urls.dart';

/// Persistence of the server URL on the device.
///
/// An interface, so that tests use an [InMemorySettingsStore] and never touch
/// the platform channel of `shared_preferences`.
abstract class SettingsStore {
  const SettingsStore();

  /// The stored server URL, or `null` if the user has not chosen one.
  Future<String?> load();

  /// Stores the given server URL.
  Future<void> save(String serverUrl);

  /// Forgets the stored server URL, restoring the platform default.
  Future<void> clear();

  /// The token this device is signed in at the stored server with, or `null`.
  ///
  /// A store written before issue #28 holds no token; it loads as `null` and
  /// the app talks to the server anonymously, as it always did.
  Future<String?> loadToken();

  /// The name the stored token was issued to, or `null`.
  Future<String?> loadDeviceName();

  /// The user name the stored token was issued for, or `null`.
  ///
  /// A store written before issue #45 holds no user name — the token is still
  /// good, the app simply does not know whom it belongs to until the server
  /// says so, see [ServerSettingsScreen].
  Future<String?> loadUserName();

  /// Stores the token the server issued, the device it was issued to and the
  /// user it was issued for (empty for "the library owner").
  Future<void> saveToken(String token, String deviceName, String userName);

  /// Forgets the stored token: the app is anonymous again.
  Future<void> clearToken();

  /// The camera-roll configuration of this device as stored JSON, or `null`.
  ///
  /// One blob under one key: a store written before issue #30 holds nothing
  /// there and loads as a disabled sync, see [CameraRollStorage].
  Future<String?> loadCameraRoll();

  /// Stores the camera-roll configuration.
  Future<void> saveCameraRoll(String json);

  /// What the last background sync run did, as stored JSON, or `null`.
  ///
  /// A second blob beside the camera-roll configuration (issue #32): it is
  /// written by the background isolate, which must not race the foreground
  /// over the watermark blob, and it is nothing but a report — a store that
  /// holds none simply has not run in the background yet, see
  /// [BackgroundRunStorage].
  Future<String?> loadBackgroundRun();

  /// Stores what a background sync run did.
  Future<void> saveBackgroundRun(String json);
}

/// A [SettingsStore] keeping the value in memory only, used by tests.
class InMemorySettingsStore extends SettingsStore {
  String? value;

  /// The stored device token, see [SettingsStore.loadToken].
  String? token;

  /// The stored device name, see [SettingsStore.loadDeviceName].
  String? deviceName;

  /// The stored user name, see [SettingsStore.loadUserName].
  String? userName;

  /// The stored camera-roll configuration, see
  /// [SettingsStore.loadCameraRoll].
  String? cameraRoll;

  /// The stored report of the last background run, see
  /// [SettingsStore.loadBackgroundRun].
  String? backgroundRun;

  InMemorySettingsStore([
    this.value,
    this.token,
    this.deviceName,
    this.userName,
  ]);

  @override
  Future<String?> load() async => value;

  @override
  Future<void> save(String serverUrl) async => value = serverUrl;

  @override
  Future<void> clear() async => value = null;

  @override
  Future<String?> loadToken() async => token;

  @override
  Future<String?> loadDeviceName() async => deviceName;

  @override
  Future<String?> loadUserName() async => userName;

  @override
  Future<void> saveToken(
      String token, String deviceName, String userName) async {
    this.token = token;
    this.deviceName = deviceName;
    this.userName = userName;
  }

  @override
  Future<void> clearToken() async {
    token = null;
    deviceName = null;
    userName = null;
  }

  @override
  Future<String?> loadCameraRoll() async => cameraRoll;

  @override
  Future<void> saveCameraRoll(String json) async => cameraRoll = json;

  @override
  Future<String?> loadBackgroundRun() async => backgroundRun;

  @override
  Future<void> saveBackgroundRun(String json) async => backgroundRun = json;
}

/// The [SettingsStore] of the app, backed by `shared_preferences`.
class PreferencesSettingsStore extends SettingsStore {
  /// The preferences key the server URL is stored under.
  static const String key = "serverUrl";

  /// The preferences key the device token is stored under.
  static const String tokenKey = "deviceToken";

  /// The preferences key the device name is stored under.
  static const String deviceNameKey = "deviceName";

  /// The preferences key the signed-in user name is stored under.
  ///
  /// A store written before issue #45 holds nothing there, see
  /// [SettingsStore.loadUserName].
  static const String userNameKey = "userName";

  /// The preferences key the camera-roll configuration is stored under.
  static const String cameraRollKey = "cameraRoll";

  /// The preferences key the report of the last background run is stored
  /// under, see [SettingsStore.loadBackgroundRun].
  static const String backgroundRunKey = "cameraRollBackground";

  const PreferencesSettingsStore();

  @override
  Future<String?> load() async =>
      (await SharedPreferences.getInstance()).getString(key);

  @override
  Future<void> save(String serverUrl) async =>
      (await SharedPreferences.getInstance()).setString(key, serverUrl);

  @override
  Future<void> clear() async =>
      (await SharedPreferences.getInstance()).remove(key);

  @override
  Future<String?> loadToken() async =>
      (await SharedPreferences.getInstance()).getString(tokenKey);

  @override
  Future<String?> loadDeviceName() async =>
      (await SharedPreferences.getInstance()).getString(deviceNameKey);

  @override
  Future<String?> loadUserName() async =>
      (await SharedPreferences.getInstance()).getString(userNameKey);

  @override
  Future<void> saveToken(
      String token, String deviceName, String userName) async {
    var preferences = await SharedPreferences.getInstance();
    await preferences.setString(tokenKey, token);
    await preferences.setString(deviceNameKey, deviceName);
    await preferences.setString(userNameKey, userName);
  }

  @override
  Future<void> clearToken() async {
    var preferences = await SharedPreferences.getInstance();
    await preferences.remove(tokenKey);
    await preferences.remove(deviceNameKey);
    await preferences.remove(userNameKey);
  }

  @override
  Future<String?> loadCameraRoll() async =>
      (await SharedPreferences.getInstance()).getString(cameraRollKey);

  @override
  Future<void> saveCameraRoll(String json) async =>
      (await SharedPreferences.getInstance()).setString(cameraRollKey, json);

  @override
  Future<String?> loadBackgroundRun() async =>
      (await SharedPreferences.getInstance()).getString(backgroundRunKey);

  @override
  Future<void> saveBackgroundRun(String json) async =>
      (await SharedPreferences.getInstance()).setString(backgroundRunKey, json);
}

/// The server URL the app uses, and the way it is changed.
///
/// The app listens to this notifier: whenever [dataUrl] changes, it builds a
/// new [VAlbumClient], drops everything loaded from the old server and reloads
/// the view it shows.
class ServerSettings extends ChangeNotifier {
  final SettingsStore store;

  /// The data URL to use while nothing is stored.
  ///
  /// On the web this is the URL derived from the origin the app was loaded
  /// from; on every other platform there is nothing to derive, so it is
  /// `null` and the app opens this screen instead of guessing a server.
  ///
  /// A function (not a value), so that a test can decide what "the platform
  /// default" is without the code reading `kIsWeb` inline.
  final String? Function() platformDefault;

  String? _serverUrl;
  String? _token;
  String? _deviceName;
  String? _userName;
  bool _loaded = false;

  ServerSettings({
    required this.store,
    String? Function()? platformDefault,
    String? serverUrl,
    String? token,
    String? deviceName,
    String? userName,
    bool loaded = false,
  })  : platformDefault = platformDefault ?? _none,
        _serverUrl = serverUrl,
        _token = token,
        _deviceName = deviceName,
        _userName = userName,
        _loaded = loaded;

  static String? _none() => null;

  /// Whether [load] has finished; until then the app shows a splash.
  bool get loaded => _loaded;

  /// The server URL stored on this device, `null` while the default applies.
  String? get serverUrl => _serverUrl;

  /// The data URL the app talks to, `null` if no server is configured.
  ///
  /// A stored [serverUrl] overrides the [platformDefault] — also on the web,
  /// where the app may deliberately be pointed at a different server than the
  /// one it was loaded from.
  String? get dataUrl {
    var stored = _serverUrl;
    if (stored != null) {
      try {
        return dataUrlOf(stored);
      } on FormatException {
        // A value the app can no longer parse must not lock the user out.
        return platformDefault();
      }
    }
    return platformDefault();
  }

  /// Whether a server to talk to is known, see [dataUrl].
  bool get configured => dataUrl != null;

  /// The token this device is signed in with, `null` if it is not signed in.
  ///
  /// Every request of the app carries it, see [VAlbumClient.token].
  String? get token => _token;

  /// The name the [token] was issued to, `null` if this device is signed out.
  String? get deviceName => _deviceName;

  /// The user the [token] was issued for, `null` where the store does not say.
  ///
  /// Empty means "the library owner", the user the pairing secret signs in
  /// while nobody has given that owner a name. `null` is a store written
  /// before issue #45 — the token is good, only the name was never stored;
  /// the server is asked who this device is, see [ServerSettingsScreen].
  String? get userName => _userName;

  /// Whether this device is signed in at the server it talks to.
  bool get signedIn => (_token ?? "").isNotEmpty;

  /// Reads the stored values; called once before the first client is built.
  Future<void> load() async {
    _serverUrl = await store.load();
    _token = await store.loadToken();
    _deviceName = await store.loadDeviceName();
    _userName = await store.loadUserName();
    _loaded = true;
    notifyListeners();
  }

  /// Stores [serverUrl] and switches the app over to that server.
  ///
  /// A token belongs to the server that issued it: pointing the app at
  /// *another* server signs this device out there, naming the same server
  /// again keeps it.
  ///
  /// What counts is the *server*, not the string: `http://h/valbum`,
  /// `http://h/valbum/` and `http://h/valbum/index.html` are the same server,
  /// and so is the platform default that applies while nothing is stored —
  /// on the web, saving the URL of the server the app was loaded from must not
  /// throw away the token this device just signed in with, see issue #35.
  Future<void> save(String serverUrl) async {
    var value = serverUrl.trim();
    var before = dataUrl;
    await store.save(value);
    _serverUrl = value;
    await _forgetTokenIfServerChanged(before);
    notifyListeners();
  }

  /// Forgets the stored value, returning to the [platformDefault].
  ///
  /// The token is kept where the default names the same server the stored URL
  /// did (the web app pointed back at its own origin), and forgotten wherever
  /// the app ends up at another server, see [save].
  Future<void> reset() async {
    var before = dataUrl;
    await store.clear();
    _serverUrl = null;
    await _forgetTokenIfServerChanged(before);
    notifyListeners();
  }

  /// Forgets the token unless the app still talks to the same server it talked
  /// to at [before].
  Future<void> _forgetTokenIfServerChanged(String? before) async {
    if (dataUrl != before) {
      await _forgetToken();
    }
  }

  /// Remembers the token the server issued for this device, see
  /// [VAlbumClient.pair].
  ///
  /// [userName] is what the server answered, empty for the library owner while
  /// that owner has no name.
  Future<void> signedInAs(
    String token,
    String deviceName, {
    String userName = "",
  }) async {
    await store.saveToken(token, deviceName, userName);
    _token = token;
    _deviceName = deviceName;
    _userName = userName;
    notifyListeners();
  }

  /// Forgets the token: the app talks to the server anonymously again.
  ///
  /// The server keeps its entry — only this device forgets how to prove it.
  Future<void> signOut() async {
    await _forgetToken();
    notifyListeners();
  }

  Future<void> _forgetToken() async {
    await store.clearToken();
    _token = null;
    _deviceName = null;
    _userName = null;
  }
}

/// Builds a client talking to the given data URL over the app's transport.
typedef ClientFactory = VAlbumClient Function(String dataUrl);

/// Makes the [ServerSettings] available to the widget tree.
class ServerSettingsScope extends InheritedNotifier<ServerSettings> {
  /// Builds the client the connection test talks to the entered server with.
  final ClientFactory clientFor;

  /// What the app did on the network, shown by the diagnostics section of the
  /// settings screen (issue #58).
  final DiagnosticsLog? diagnostics;

  const ServerSettingsScope({
    super.key,
    required ServerSettings settings,
    required this.clientFor,
    this.diagnostics,
    required super.child,
  }) : super(notifier: settings);

  /// The settings of the enclosing app.
  static ServerSettingsScope of(BuildContext context) {
    var scope =
        context.dependOnInheritedWidgetOfExactType<ServerSettingsScope>();
    assert(scope != null, "No ServerSettingsScope found in the widget tree.");
    return scope!;
  }

  ServerSettings get settings => notifier!;
}

/// Opens the server settings on top of the view the user is in.
///
/// A [ServerSettingsScope] must be in scope, see [VAlbumApp].
void openServerSettings(BuildContext context) {
  var scope = ServerSettingsScope.of(context);
  Navigator.of(context).push(
    MaterialPageRoute<void>(
      builder: (_) => ServerSettingsScreen(
        settings: scope.settings,
        clientFor: scope.clientFor,
        diagnostics: scope.diagnostics,
        closable: true,
      ),
    ),
  );
}

/// The outcome of a connection test, see [testServerConnection].
@immutable
class ConnectionTestResult {
  /// Whether an album server answered.
  final bool ok;

  /// What to show the user: the title of the root resource, or the reason.
  final String message;

  /// What the server says about this device's sign-in, `null` if it says
  /// nothing (a server from before issue #28).
  final String? authStatus;

  const ConnectionTestResult(this.ok, this.message, {this.authStatus});

  /// The same result reporting the given sign-in status.
  ConnectionTestResult withAuthStatus(String? status) =>
      ConnectionTestResult(ok, message, authStatus: status);
}

/// The icon and the message of a connection test or a sign-in attempt.
///
/// One row for every place that reports such an outcome — the address section,
/// the sign-in form, the refusal page — so that a success and a refusal look
/// the same wherever they are shown.
Widget outcomeRow(BuildContext context, ConnectionTestResult outcome) => Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(
          outcome.ok ? Icons.check_circle : Icons.error,
          color: outcome.ok ? Colors.green : Colors.red,
        ),
        const SizedBox(width: 8),
        Expanded(child: Text(outcome.message)),
      ],
    );

/// Fetches the root resource of the server [client] talks to.
///
/// Every outcome is reported as a message; nothing fails silently. Every step
/// is written into the client's [DiagnosticsLog] as well (issue #58) — the
/// URL the entered address was turned into, what the name resolves to on each
/// address family, what the root listing answered and what `?type=auth` said —
/// because a connection that fails on one network and works on another is
/// settled by data, not by the headline of an exception.
Future<ConnectionTestResult> testServerConnection(VAlbumClient client) async {
  var log = client.log;
  log?.add("connection test: data URL ${maskUrl(client.dataUrl)}");
  if (log != null) {
    await _logResolution(log, client.dataUrl);
  }
  var reached = await _reachServer(client);
  log?.add(
    "connection test: root ${reached.ok ? "reached" : "failed"} - "
    "${reached.message}",
  );
  var status = await _authStatus(client);
  log?.add("connection test: auth ${status ?? "no answer"}");
  return reached.withAuthStatus(status);
}

/// Resolves the host of [dataUrl] explicitly, see `logHostResolution`.
///
/// A step of its own, with lines of its own: a pasted log has to answer "did
/// the IPv4 query fail, did the IPv6 query fail, what did the OS say", which
/// the failure of the request alone never says.
Future<void> _logResolution(DiagnosticsLog log, String dataUrl) async {
  String host;
  try {
    host = Uri.parse(dataUrl).host;
  } catch (error) {
    log.add("connection test: no host in '$dataUrl' ($error)");
    return;
  }
  if (host.isEmpty) {
    log.add("connection test: no host in '$dataUrl'");
    return;
  }
  await logHostResolution(log, host);
}

/// What the server says about this client's sign-in, `null` if it says
/// nothing at all — a server from before issue #28 does not know the endpoint.
Future<String?> _authStatus(VAlbumClient client) async {
  AuthInfo info;
  try {
    info = await client.authInfo();
  } catch (_) {
    return null;
  }
  if (info.deviceName.isNotEmpty) {
    return "Signed in as ${userDisplayName(info.userName)} "
        "on ${info.deviceName}";
  }
  return switch (info.mode) {
    "off" => "Not signed in - this server needs no sign-in",
    "all" => "Not signed in - this server shows nothing without a sign-in",
    _ => "Not signed in - changes need a sign-in",
  };
}

/// How the user of the given name is named on the screen.
///
/// The server leaves the name empty for the administrator of a space nobody
/// has signed into yet: the seat is there from the start, and the sign-in that
/// redeems its code gives them their name (issues #45, #89).
String userDisplayName(String userName) =>
    userName.isEmpty ? "the library owner" : userName;

/// How the space of the given name is named on the screen.
///
/// A user's space is the folder under the server's base folder their requests
/// are resolved against; the owner of an unmigrated library has none, which
/// means the whole base folder.
String spaceDisplayName(String space) =>
    space.isEmpty ? "the whole library" : space;

/// A token as the screen shows it: enough to recognise it, not enough to use.
///
/// An invitation in the server field is a secret somebody was sent; it is
/// shown so that a person sees *which* one they pasted, never so that it can
/// be read off the screen, see issue #52.
String maskedToken(String token) => maskToken(token);

/// Who a device is signed in as, as far as the app knows.
///
/// [role] and [space] are `null` while only the store has spoken: they are not
/// persisted, they come from `GET ?type=auth` (or from the answer to the
/// sign-in itself), so a server that cannot be reached leaves them unknown
/// rather than stale.
@immutable
class SignedInUser {
  /// The name the user signed in under, empty for the library owner and
  /// `null` where nobody has said yet — a token stored before issue #45
  /// carries no name until the server answers, see
  /// [SettingsStore.loadUserName].
  final String? userName;

  /// The name of this device at the server.
  final String deviceName;

  /// The role the server reports, `null` while the server has not said.
  final String? role;

  /// The space the server resolves this user's requests against, `null` while
  /// the server has not said; empty means the whole library.
  final String? space;

  /// Which privacy levels this user sees, as the server spelled it; empty
  /// where it said nothing, see [CallerPermission] and issue #85.
  final String clearance;

  /// Whether this user may create share links, as the server said it.
  final bool mayShare;

  const SignedInUser({
    this.userName,
    required this.deviceName,
    this.role,
    this.space,
    this.clearance = "",
    this.mayShare = false,
  });

  /// What this user may do and see, in the normalised form every view reads,
  /// see [CallerPermission.of].
  CallerPermission get permission => CallerPermission.ofFields(
        role: role ?? "",
        clearance: clearance,
        mayShare: mayShare,
      );
}

Future<ConnectionTestResult> _reachServer(VAlbumClient client) async {
  try {
    var resource = await client.loadResource([]);
    var title = _titleOf(resource);
    if (title == null) {
      return const ConnectionTestResult(
        false,
        "The answer is not album data — not a VAlbum server?",
      );
    }
    return ConnectionTestResult(
      true,
      title.isEmpty ? "Album server reached" : title,
    );
  } on VAlbumException catch (error) {
    // A refusal is a server: it answered, it just will not show this caller
    // anything. Reporting it as a failure is what left the app believing it
    // was offline in front of a server started with `--auth all`, see issue
    // #57 — and the sign-in below is exactly the remedy it asks for.
    if (error.status == 401 || error.status == 403) {
      return ConnectionTestResult(
        true,
        error.status == 401
            ? "Album server reached - it needs a sign-in before it shows "
                "anything"
            : "Album server reached - it refuses what this device is signed "
                "in as",
      ).withDetail(error.message);
    }
    return ConnectionTestResult(false, error.message);
  } on http.ClientException catch (error) {
    return ConnectionTestResult(false, error.message);
  } on FormatException catch (error) {
    return const ConnectionTestResult(
      false,
      "The answer is not album data — not a VAlbum server?",
    ).withDetail(error.message);
  } catch (error) {
    return ConnectionTestResult(false, error.toString());
  }
}

extension on ConnectionTestResult {
  /// The same result with [detail] appended, where there is one.
  ConnectionTestResult withDetail(String detail) =>
      detail.isEmpty ? this : ConnectionTestResult(ok, "$message ($detail)");
}

/// The title the root resource of an album server announces.
///
/// `null` if the answer is no resource at all: then the server at the other
/// end is not a VAlbum server. An [ErrorInfo] *is* an album server answering,
/// but it is answering with its problem, so that message is shown.
String? _titleOf(Resource? resource) => switch (resource) {
      ListingInfo(title: var title) => title,
      AlbumInfo(title: var title) => title,
      ErrorInfo(message: var message) => message,
      _ => null,
    };

/// A device name to suggest when this device is paired the first time.
///
/// Only a suggestion in the field; the user names the device, and the server
/// stores whatever it is given.
String defaultDeviceName() {
  if (kIsWeb) {
    return "This browser";
  }
  return switch (defaultTargetPlatform) {
    TargetPlatform.android => "Android phone",
    TargetPlatform.iOS => "iPhone",
    TargetPlatform.macOS => "Mac",
    TargetPlatform.windows => "Windows PC",
    TargetPlatform.linux => "Linux PC",
    TargetPlatform.fuchsia => "My device",
  };
}

/// The key of the server URL field, so that a test can address it.
const Key serverUrlFieldKey = Key("settings.serverUrl");

/// The key of the line explaining what belongs in that field.
const Key serverUrlHelpKey = Key("settings.serverUrl.help");

/// What belongs in the server field (issue #85).
///
/// Both shapes, because both are addresses somebody is given: a server with
/// one library is reached at its context path, and a server with several
/// spaces puts each of them below a segment of its own — and the space is
/// simply part of the address, see `urls.dart`.
const String serverUrlHelp =
    "The address the album server is reached at, as you would open it in a "
    "browser, e.g. 'http://nas.local:8080/valbum/'. Where the server holds "
    "several spaces, the address carries the space: "
    "'https://host/valbum/<space>/'.";

/// The key of the line naming the server, shown in place of the address
/// section on the web (issue #90).
///
/// A browser talks to the server it was loaded from and to no other: an album
/// of a different origin cannot be read from this page. So there is nothing to
/// enter there, and the screen says which server it is about instead of
/// offering a field that could only be wrong.
const Key serverLineKey = Key("settings.serverLine");

/// The key of the device name field, see [serverUrlFieldKey].
const Key deviceNameFieldKey = Key("settings.deviceName");

/// The key of the user name field, see [serverUrlFieldKey].
///
/// Shown only where a name is actually needed (issue #89): when an invitation
/// is being accepted, and when the server answered [nameRequiredMessage] to a
/// sign-in — a code for a user who has no name yet. Everywhere else the code
/// says who is signing in, and asking would be asking for nothing.
const Key userNameFieldKey = Key("settings.userName");

/// The key of the code field, see [serverUrlFieldKey] and issues #65 and #89.
///
/// The one way to sign a device in: a single-use code that adds this device to
/// one user — the one the server printed at start-up for the administrator of
/// a space nobody signed into yet, one shown under "My devices" on a device
/// that is already signed in, or a recovery code from an administrator.
const Key deviceCodeFieldKey = Key("settings.deviceCode");

/// The key of the button opening the camera to read a device code (issue #66).
///
/// Beside [deviceCodeFieldKey], and built only where there is a camera to
/// open: on the web and on a desktop the field is typed into, and no button
/// promises a scanner that does not exist, see [DeviceCodeScanner.available].
const Key deviceCodeScanKey = Key("settings.deviceCode.scan");

/// The key of the line saying what the caller may do and see (issue #85).
const Key permissionLineKey = Key("settings.permission");

/// The key of the "Sign in" button, see [SignInForm].
const Key signInButtonKey = Key("settings.signIn");

/// The key of the sign-in form itself, see [SignInForm].
const Key signInFormKey = Key("settings.signIn.form");

/// The key of the "Sign out" button beside it.
const Key signOutButtonKey = Key("settings.signOut");

/// The key of what a sign-out left to say.
const Key signOutMessageKey = Key("settings.signOut.message");

/// The key of the sign-in section's own refusal, see [bothCredentialsRefusal].
const Key signInErrorKey = Key("settings.signIn.error");

/// Keeps a device code readable while it is typed (issue #65).
///
/// Upper case, letters, digits and the dash the other device shows; anything
/// else is not part of a code and is dropped rather than carried to the
/// server, which would only refuse it.
final TextInputFormatter deviceCodeFormatter =
    TextInputFormatter.withFunction((oldValue, newValue) {
  var kept = newValue.text.toUpperCase().replaceAll(RegExp("[^A-Z0-9-]"), "");
  if (kept == newValue.text) {
    return newValue;
  }
  return TextEditingValue(
    text: kept,
    selection: TextSelection.collapsed(
      offset: kept.length < newValue.selection.end
          ? kept.length
          : newValue.selection.end,
    ),
  );
});

/// What belongs in the user name field (issues #86, #89).
///
/// It used to say "leave empty to sign in as the library owner", which was
/// right while the owner was the one user of a library and is wrong in a space
/// where the administrator is one user among several: a nameless user has no
/// attribution, shows as an empty row in the users list, and cannot be
/// addressed by `set-permission` or `remove-user`.
const String userNameHelp =
    "Your name in this space; it is what the others see and what your photos "
    "are attributed to.";

/// What the sign-in section says about the code (issue #89).
const String signInCodeExplanation =
    "Enter the code the server printed at start-up, or a code from one of your "
    "devices, or a recovery code your administrator gave you.";

/// What the server answers a sign-in that needs a name it was not given.
///
/// The server's own sentence, mirrored here so that the app can tell this
/// refusal from every other `400` and show the name field. It is compared
/// against what arrives, never shown instead of it: what the person reads is
/// always what the server said (issue #89).
const String nameRequiredMessage =
    "This code signs in a user who has no name yet. Choose the name you want "
    "to be known by in this space.";

/// What a sign-in without any code is refused with, before anything is sent.
const String codeRequiredRefusal = "Enter the code that signs this device in.";

/// Whether the given failure is the server asking for a name (issue #89).
///
/// Matched on the status and the server's own sentence, never on a code of our
/// own invention: the protocol carries a message, and this is the one `400`
/// the app does something else with than showing it as a failure.
bool needsAName(VAlbumException failure) =>
    failure.status == 400 && failure.message.trim() == nameRequiredMessage;

/// The key of the "Clear cache" button, see [serverUrlFieldKey].
const Key clearCacheButtonKey = Key("settings.clearCache");

/// The diagnostics section, collapsed until somebody needs it (issue #58).
const Key diagnosticsSectionKey = Key("settings.diagnostics");

/// The button putting the whole log, header and all, on the clipboard.
const Key diagnosticsCopyKey = Key("settings.diagnostics.copy");

/// The button forgetting what was logged.
const Key diagnosticsClearKey = Key("settings.diagnostics.clear");

/// The key of the invitation the entered URL carries, see [serverUrlFieldKey].
///
/// Present exactly while the sign-in section signs in with an invitation
/// instead of the pairing secret (issue #52).
const Key invitationSectionKey = Key("settings.invitation");

/// The key of the action dropping the invitation of the entered URL.
const Key invitationDropKey = Key("settings.invitation.drop");

/// The key of the sentence a share link pasted into the server field is
/// refused with, see [shareLinkRefusal].
const Key shareLinkRefusalKey = Key("settings.shareLink");

/// The key of the "Invite…" button, see [invitationSectionKey].
const Key inviteButtonKey = Key("settings.invite");

/// The screen editing the URL of the album server.
class ServerSettingsScreen extends StatefulWidget {
  final ServerSettings settings;

  /// Builds the client the connection test uses, see [ClientFactory].
  final ClientFactory clientFor;

  /// Whether the screen can be left without choosing a server.
  ///
  /// `false` when it is the app's start screen because no server is
  /// configured yet: there is nothing to go back to.
  final bool closable;

  /// What the app did on the network, shown by the diagnostics section
  /// (issue #58); a screen without one keeps a log of its own, which is empty
  /// until something uses it.
  final DiagnosticsLog? diagnostics;

  /// Whether this screen runs in the web build (issue #90).
  ///
  /// A browser can act on less than a device: it talks to the server it was
  /// loaded from and to no other, and it has no camera roll. So the address
  /// section and the camera-roll section are not built here at all — what a
  /// browser cannot do is not shown, rather than shown and refused.
  ///
  /// A parameter (not [kIsWeb] read inline), exactly as
  /// [ServerSettings.platformDefault] is a function: a widget test runs off
  /// the web and pumps this screen both ways.
  final bool isWeb;

  const ServerSettingsScreen({
    super.key,
    required this.settings,
    required this.clientFor,
    this.diagnostics,
    this.closable = true,
    this.isWeb = kIsWeb,
  });

  @override
  State<ServerSettingsScreen> createState() => ServerSettingsScreenState();
}

class ServerSettingsScreenState extends State<ServerSettingsScreen> {
  late final TextEditingController controller = TextEditingController(
    text: widget.settings.serverUrl ?? _suggestion(),
  );

  /// The caller's devices as the devices section last read them (issue #92).
  ///
  /// What the sign-out needs to know whether this is the last one, and
  /// whether there is a backup code to fall back on. `null` while nothing has
  /// been read.
  DeviceList? deviceList;

  /// What a sign-out left to say, `null` while there is nothing.
  String? signOutMessage;

  /// Who this device is signed in as, `null` while it is signed out.
  SignedInUser? identity;

  /// Why the server could not be asked who this device is, if it could not.
  ///
  /// The store knows the user name and the device, never the role and the
  /// space; where the server does not answer, the app says so instead of
  /// leaving the section silent.
  String? identityProblem;

  /// The invitation the entered URL carries, empty while it carries none.
  ///
  /// Never stored: it is exchanged for a device token exactly once, exactly
  /// like the pairing secret, see issue #52.
  String invitationToken = "";

  /// What the server said the entered invitation offers, `null` while it has
  /// not answered (or answered that the token is no invitation of its).
  InvitationInfo? invitationOffer;

  /// Why the entered invitation cannot be used, if it cannot.
  String? invitationProblem;

  /// What the app did on the network, see [DiagnosticsLog].
  ///
  /// The app's own log where there is one; a screen pumped without one (a
  /// test, an embedder) keeps an empty one of its own rather than hiding the
  /// section, so that what the section is stays visible.
  late final DiagnosticsLog log = widget.diagnostics ?? DiagnosticsLog();

  /// The outcome of the last connection test, if any.
  ConnectionTestResult? result;

  /// Whether a connection test is running.
  bool testing = false;

  /// The reason the entered URL cannot be saved, if any.
  String? error;

  /// What clearing the cache did, shown until the screen is left.
  String? cacheMessage;

  /// Counts the times the cache changed, so that its size is read again.
  int _cacheGeneration = 0;

  /// Counts the invitations issued here, so that the list of the open ones
  /// reads itself again, see [InvitationsSection.generation].
  int _invitationGeneration = 0;

  /// A pre-filled suggestion: the server the app talks to, or the demo server.
  ///
  /// This is only a suggestion in the field; it is never used silently.
  String _suggestion() {
    var current = widget.settings.dataUrl ?? defaultDataUrl;
    return current.endsWith("/data")
        ? current.substring(0, current.length - "data".length)
        : current;
  }

  @override
  void initState() {
    super.initState();
    var settings = widget.settings;
    // Whatever changes the settings — a save that switches the server and
    // forgets the token with it, a sign-out — must show here at once.
    settings.addListener(_settingsChanged);
    if (settings.signedIn) {
      identity = SignedInUser(
        userName: settings.userName,
        deviceName: settings.deviceName ?? "",
      );
      _askWhoThisDeviceIs();
    }
    // A screen opened with an invitation already in the field (the app was
    // pointed here by one) asks about it at once, exactly as a paste does.
    _enteredChanged();
  }

  /// What the URL in the field names, `null` while it names nothing usable.
  ServerLocation? get entered {
    try {
      return serverLocationOf(controller.text);
    } on FormatException {
      return null;
    }
  }

  /// The entered URL changed: pick up an invitation it carries, drop the one
  /// it no longer carries.
  ///
  /// A person on a phone gets the invitation by message and pastes it where
  /// the app asks for a server; the field therefore reads a `/i/<token>/` URL
  /// as "this server, and this invitation", see [serverLocationOf].
  void _enteredChanged() {
    var location = entered;
    var token = location?.invitation ?? "";
    if (token == invitationToken) {
      return;
    }
    invitationToken = token;
    invitationOffer = null;
    invitationProblem = null;
    if (token.isEmpty) {
      return;
    }
    _askAboutInvitation(token, location!.dataUrl);
  }

  /// Asks the invited server who invited, and as what.
  ///
  /// The invitation token is the bearer of this one request and of the sign-in
  /// below, and of nothing else: on every other endpoint it is anonymous, see
  /// issue #52.
  Future<void> _askAboutInvitation(String token, String dataUrl) async {
    AuthInfo info;
    try {
      info = await widget.clientFor(dataUrl).withToken(token).authInfo();
    } on VAlbumException catch (failure) {
      _invitationUnusable(token, failure.message);
      return;
    } on http.ClientException catch (failure) {
      _invitationUnusable(token, failure.message);
      return;
    } catch (failure) {
      _invitationUnusable(token, failure.toString());
      return;
    }
    if (!mounted || invitationToken != token) {
      return;
    }
    var offered = info.invitation;
    if (offered == null) {
      _invitationUnusable(
        token,
        "This server does not know this invitation. Ask for a new one.",
      );
      return;
    }
    setState(() {
      invitationOffer = offered;
      invitationProblem = null;
    });
  }

  void _invitationUnusable(String token, String problem) {
    if (!mounted || invitationToken != token) {
      return;
    }
    setState(() {
      invitationOffer = null;
      invitationProblem = problem;
    });
  }

  /// Forgets the invitation of the entered URL: the plain server stays.
  void _dropInvitation() {
    var location = entered;
    if (location != null) {
      controller.text = location.serverUrl;
    }
    setState(_enteredChanged);
  }

  /// Asks the *saved* server who this device is, filling [identity].
  ///
  /// The token belongs to the server that issued it, so this asks the server
  /// the app talks to, not the URL currently in the field. The stored user
  /// name is shown meanwhile; a store written before issue #45 has none, and
  /// this is what fills it in.
  Future<void> _askWhoThisDeviceIs() async {
    var settings = widget.settings;
    var dataUrl = settings.dataUrl;
    var token = settings.token;
    if (dataUrl == null || token == null) {
      return;
    }
    AuthInfo info;
    try {
      info = await widget.clientFor(dataUrl).withToken(token).authInfo();
    } on VAlbumException catch (failure) {
      _identityUnknown(failure.message);
      return;
    } on http.ClientException catch (failure) {
      _identityUnknown(failure.message);
      return;
    } catch (failure) {
      _identityUnknown(failure.toString());
      return;
    }
    if (!mounted || !widget.settings.signedIn) {
      return;
    }
    if (info.deviceName.isEmpty) {
      // A token the server does not know is answered as an anonymous caller:
      // the device is still holding a token, but it proves nothing any more.
      setState(() => identityProblem =
          "This server does not know this device. Sign in again.");
      return;
    }
    setState(() {
      identityProblem = null;
      identity = SignedInUser(
        userName: info.userName,
        deviceName: info.deviceName,
        role: info.role,
        space: info.space,
        clearance: info.clearance,
        mayShare: info.mayShare,
      );
    });
  }

  void _identityUnknown(String problem) {
    if (!mounted) {
      return;
    }
    setState(() => identityProblem =
        "The server did not say who this device is: $problem");
  }

  @override
  void dispose() {
    widget.settings.removeListener(_settingsChanged);
    controller.dispose();
    super.dispose();
  }

  void _settingsChanged() {
    if (!mounted) {
      return;
    }
    setState(() {
      if (!widget.settings.signedIn) {
        // The identity went with the token; nothing is known any more.
        identityProblem = null;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    var settings = widget.settings;
    return Scaffold(
      appBar: AppBar(
        title: const Text("Album server"),
        automaticallyImplyLeading: widget.closable,
      ),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 640),
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              if (widget.isWeb)
                ..._serverLine(settings)
              else
                ..._addressSection(settings),
              const SizedBox(height: 24),
              const Divider(),
              ..._signInSection(settings),
              if (!widget.isWeb) const CameraRollSection(),
              ..._cacheSection(),
              ..._diagnosticsSection(settings),
            ],
          ),
        ),
      ),
    );
  }

  /// The one line the web build shows in place of the address section: which
  /// server this browser talks to, see [serverLineKey].
  ///
  /// [ServerSettings.dataUrl] rather than the platform default alone, so that
  /// the line names the server the app really asks — normally the origin it
  /// was loaded from, and a stored address where an accepted invitation wrote
  /// one. The data URL is spelled back as the app base, which is the address
  /// the browser itself shows.
  List<Widget> _serverLine(ServerSettings settings) {
    var dataUrl = settings.dataUrl;
    return [
      Text(
        dataUrl == null
            ? "This browser talks to no server yet."
            : "This browser talks to ${appBaseOf(dataUrl)}",
        key: serverLineKey,
      ),
    ];
  }

  /// The address section: what the server URL is, the field it is entered in
  /// and what can be done with it. Built off the web only (issue #90).
  List<Widget> _addressSection(ServerSettings settings) => [
              const Text(
                serverUrlHelp,
                key: serverUrlHelpKey,
              ),
              const SizedBox(height: 16),
              TextField(
                key: serverUrlFieldKey,
                controller: controller,
                autocorrect: false,
                keyboardType: TextInputType.url,
                decoration: InputDecoration(
                  labelText: "Server URL",
                  border: const OutlineInputBorder(),
                  errorText: error,
                ),
                onChanged: (_) => setState(() {
                  error = null;
                  result = null;
                  _enteredChanged();
                }),
                onSubmitted: (_) => _save(),
              ),
              const SizedBox(height: 16),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  FilledButton.icon(
                    onPressed: _save,
                    icon: const Icon(Icons.save),
                    label: const Text("Save"),
                  ),
                  OutlinedButton.icon(
                    onPressed: testing ? null : _test,
                    icon: const Icon(Icons.network_check),
                    label: const Text("Test connection"),
                  ),
                  TextButton.icon(
                    onPressed: settings.serverUrl == null ? null : _reset,
                    icon: const Icon(Icons.settings_backup_restore),
                    label: Text(
                      settings.platformDefault() == null
                          ? "Forget this server"
                          : "Use the server this app was loaded from",
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              if (testing)
                const Row(
                  children: [
                    SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                    SizedBox(width: 8),
                    Text("Contacting the server..."),
                  ],
                ),
              if (!testing && result != null) _outcome(result!),
              if (!testing && result?.authStatus != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8, left: 32),
                  child: Text(result!.authStatus!),
                ),
      ];

  /// The icon and message of a connection test or pairing attempt.
  Widget _outcome(ConnectionTestResult outcome) => outcomeRow(context, outcome);

  /// The section signing this device in at the server.
  ///
  /// One field and one thing in it: a **code** (issue #89). Whoever issued it
  /// — the server at start-up, a device of one's own, an administrator, or the
  /// person themselves as a backup code (issue #92) — the server answers a
  /// token for this device, which is stored with the server URL and sent on
  /// every request from then on.
  ///
  /// The form itself is [SignInForm], the same widget the refusal page of a
  /// server that shows nothing to anonymous callers is built from (issue #91):
  /// what this screen adds around it is who this device is signed in as, the
  /// way out, and the management sections below.
  List<Widget> _signInSection(ServerSettings settings) {
    // A share link is no sign-in and names no server: it is said, not stored,
    // see [shareLinkRefusal].
    if (entered?.isShare ?? false) {
      return [
        const SizedBox(height: 8),
        Text("Sign in", style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        const Text(shareLinkRefusal, key: shareLinkRefusalKey),
      ];
    }
    var location = entered;
    var inviting = invitationToken.isNotEmpty;
    return [
      const SizedBox(height: 8),
      Text("Sign in", style: Theme.of(context).textTheme.titleMedium),
      const SizedBox(height: 8),
      if (!inviting) const Text(signInCodeExplanation),
      const SizedBox(height: 16),
      ..._identityDisplay(settings),
      const SizedBox(height: 16),
      if (inviting) ..._invitationDisplay(),
      if (location == null)
        const Text(
          "Name a server above before signing in.",
          key: Key("settings.signIn.noServer"),
        )
      else
        SignInForm(
          key: signInFormKey,
          settings: settings,
          clientFor: widget.clientFor,
          location: location,
          onScanned: _scannedServer,
          onSignedIn: _signedIn,
          actions: [
            TextButton.icon(
              key: signOutButtonKey,
              onPressed: settings.signedIn ? _signOut : null,
              icon: const Icon(Icons.logout),
              label: const Text("Sign out"),
            ),
          ],
        ),
      if (signOutMessage != null)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Text(signOutMessage!, key: signOutMessageKey),
        ),
      ..._devicesSection(),
      ..._inviteSection(),
    ];
  }

  /// A scanned payload names a server too: it goes into the field above
  /// (issues #66, #91).
  ///
  /// The code itself the form keeps; this is only the server it belongs to,
  /// and it is *shown*, never acted on — the sign-in is the button below,
  /// pressed by the person who can now see what was read.
  void _scannedServer(DeviceCodePayload payload) {
    controller.text = payload.serverUrl;
    setState(() {
      error = null;
      result = null;
      _enteredChanged();
    });
  }

  /// The sign-in succeeded: show who this device is now, see [SignInForm].
  void _signedIn(SignedInUser user, ServerLocation location) {
    if (!mounted) {
      return;
    }
    setState(() {
      identity = user;
      identityProblem = null;
      if (invitationToken.isNotEmpty) {
        // Used up: the field shows the server it named, and the section is
        // the ordinary sign-in again.
        controller.text = location.serverUrl;
        _enteredChanged();
      }
    });
  }

  /// The invitation the entered URL carries: who invited, as what, and the way
  /// out of it.
  ///
  /// The token itself is shown masked and cannot be edited — it is not a thing
  /// to type, it is a thing that was pasted — and dropping it leaves the plain
  /// server URL behind, so the field stays usable for an ordinary sign-in.
  List<Widget> _invitationDisplay() {
    var offer = invitationOffer;
    var problem = invitationProblem;
    return [
      Card(
        key: invitationSectionKey,
        margin: EdgeInsets.zero,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(Icons.mail_outline),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text("Invitation ${maskedToken(invitationToken)}"),
                  ),
                  TextButton(
                    key: invitationDropKey,
                    onPressed: _dropInvitation,
                    child: const Text("Not this one"),
                  ),
                ],
              ),
              if (offer != null) ...[
                const SizedBox(height: 8),
                Text(
                  invitationHeadline(offer.invitedBy, offer.role),
                  key: const Key("settings.invitation.offer"),
                ),
                if (offer.note.trim().isNotEmpty)
                  Text(offer.note.trim(),
                      key: const Key("settings.invitation.note")),
              ],
              if (problem != null) ...[
                const SizedBox(height: 8),
                Text(problem, key: const Key("settings.invitation.problem")),
              ],
              if (offer == null && problem == null) ...[
                const SizedBox(height: 8),
                const Text("Asking the server about this invitation..."),
              ],
            ],
          ),
        ),
      ),
      const SizedBox(height: 16),
    ];
  }

  /// The way to invite somebody, for a caller who may (issue #52).
  ///
  /// Offered to a member and to the admin, never to a guest — a guest invites
  /// nobody. Whether a *member* may is the server's word under
  /// `--invite admin`, and it is said in the dialog where it is asked, not
  /// guessed here.
  ///
  /// Only issuing an invitation is offered. Listing the invitations one handed
  /// out, withdrawing them and turning a guest into a member are the
  /// management screens of issue #55; the calls they need are in
  /// [VAlbumClient] already.
  List<Widget> _inviteSection() {
    var role = identity?.role ?? "";
    if (!CallerInfo(role: role).mayInvite) {
      return const [];
    }
    var client = _managementClient();
    return [
      const SizedBox(height: 24),
      const Divider(),
      const SizedBox(height: 8),
      Text("People", style: Theme.of(context).textTheme.titleMedium),
      const SizedBox(height: 8),
      const Text(
        "An invitation is a single-use link that creates one account on this "
        "server. Send it to the person it is for, and to nobody else.",
      ),
      const SizedBox(height: 16),
      Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          OutlinedButton.icon(
            key: inviteButtonKey,
            onPressed: _invite,
            icon: const Icon(Icons.person_add),
            label: const Text("Invite…"),
          ),
        ],
      ),
      // What became of the invitations one handed out: the administrator is
      // answered all of them, a member their own, see issue #55.
      if (client != null)
        InvitationsSection(
          client: client,
          generation: _invitationGeneration,
        ),
      // Who is on this server at all is the administrator's business alone.
      if (client != null && role == roleAdmin) UsersSection(client: client),
    ];
  }

  /// The client the management sections talk to: the *saved* server, with the
  /// token of this device.
  ///
  /// `null` while no server is configured — the sections have nobody to ask
  /// then, and are not shown.
  VAlbumClient? _managementClient() {
    var settings = widget.settings;
    var dataUrl = settings.dataUrl;
    if (dataUrl == null) {
      return null;
    }
    return widget.clientFor(dataUrl).withToken(
          settings.token,
          userName: identity?.userName ?? settings.userName ?? "",
        );
  }

  /// The devices this person is signed in on, see issue #55.
  ///
  /// Shown only where there is something to list: a device token is stored
  /// *and* the server has said who this device is. A server that does not know
  /// this device, and a caller the server never named, have no device list to
  /// show — what they are told is said by the sign-in section above.
  List<Widget> _devicesSection() {
    var settings = widget.settings;
    var client = _managementClient();
    if (!settings.signedIn ||
        client == null ||
        (identity?.role ?? "").isEmpty) {
      return const [];
    }
    return [
      DevicesSection(
        client: client,
        role: identity?.role ?? "",
        // The same sign-out the button above performs: the server has already
        // taken this device's entry away, and the device forgets its token.
        onSignedOutHere: _forgetToken,
        // What the sign-out button above needs to know whether this is the
        // last device, and whether a backup code is lying in a drawer.
        onDevices: (list) {
          if (mounted) {
            setState(() => deviceList = list);
          }
        },
      ),
    ];
  }

  /// Opens the dialog issuing an invitation at the *saved* server.
  ///
  /// The saved server and its token: an invitation is issued by a signed-in
  /// caller, and the token belongs to the server that issued it — exactly the
  /// client [_askWhoThisDeviceIs] asks with.
  Future<void> _invite() async {
    var dataUrl = widget.settings.dataUrl;
    if (dataUrl == null) {
      return;
    }
    await openInviteDialog(
      context,
      widget.clientFor(dataUrl).withToken(widget.settings.token),
    );
    if (!mounted) {
      return;
    }
    // Whatever was issued belongs in the list below at once.
    setState(() => _invitationGeneration++);
  }

  /// Who this device is signed in as, or that it is not.
  ///
  /// The name and the device come from the store, so they are there before the
  /// server is asked and stay there while it cannot be reached; the role and
  /// the space are only shown once the server has said them, see
  /// [_askWhoThisDeviceIs].
  List<Widget> _identityDisplay(ServerSettings settings) {
    var user = settings.signedIn ? identity : null;
    var lines = <String>[];
    if (user != null) {
      var userName = user.userName;
      lines.add(userName == null
          ? "Signed in on this device"
          : "Signed in as ${userDisplayName(userName)}");
      var role = user.role;
      if (role != null && role.isNotEmpty) {
        lines.add("Role: $role");
      }
      if (user.deviceName.isNotEmpty) {
        lines.add("Device: ${user.deviceName}");
      }
      // The space of the address this device talks to (issue #85); empty on
      // a server with a single library, which then says nothing about it.
      var space = user.space;
      if (space != null && space.isNotEmpty) {
        lines.add("Space: $space");
      }
      // A guest's space is not a library of their own, so it is said in words
      // rather than shown as a folder, see issue #52.
      if (role == roleGuest) {
        lines.add(guestLibraryNotice);
      }
    } else {
      lines.add("Not signed in");
    }
    var problem = user == null ? null : identityProblem;
    // What this caller may do and see, in plain words (issue #85): the role,
    // the clearance and whether links may be handed out are three answers of
    // the server, and somebody checking whether it thinks of them what they
    // think it does should not have to read three field names to find out.
    var permission = user?.permission;
    return [
      Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            user != null ? Icons.verified_user : Icons.no_encryption,
            color: user != null ? Colors.green : Colors.grey,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (var line in lines) Text(line),
                if (permission != null && permission.named)
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Text(
                      permission.sentence,
                      key: permissionLineKey,
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
      if (problem != null)
        Padding(
          padding: const EdgeInsets.only(top: 8, left: 32),
          child: Text(problem),
        ),
    ];
  }

  /// The section reporting and clearing the offline cache (issue #31).
  ///
  /// Empty where there is no cache to speak of — a view pumped on its own in a
  /// test; the app always has one, see [VAlbumApp].
  List<Widget> _cacheSection() {
    var cache = OfflineScope.maybeOf(context)?.cache;
    if (cache == null) {
      return const [];
    }
    return [
      const SizedBox(height: 24),
      const Divider(),
      const SizedBox(height: 8),
      Text("Cache", style: Theme.of(context).textTheme.titleMedium),
      const SizedBox(height: 8),
      const Text(
        "Albums and thumbnails already seen are kept on this device, so that "
        "the library can be browsed while the server is away.",
      ),
      const SizedBox(height: 16),
      FutureBuilder<int>(
        // Re-read whenever the screen rebuilds, so that clearing shows.
        key: ValueKey(_cacheGeneration),
        future: cache.size(),
        builder: (context, snapshot) => Text(
          snapshot.hasData
              ? "Currently cached: ${formatBytes(snapshot.data!)}"
              : "Currently cached: ...",
        ),
      ),
      const SizedBox(height: 16),
      Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          OutlinedButton.icon(
            key: clearCacheButtonKey,
            onPressed: () => _clearCache(cache),
            icon: const Icon(Icons.delete_sweep),
            label: const Text("Clear cache"),
          ),
        ],
      ),
      if (cacheMessage != null)
        Padding(
          padding: const EdgeInsets.only(top: 16),
          child: _outcome(ConnectionTestResult(true, cacheMessage!)),
        ),
    ];
  }

  /// Empties the cache, after asking; what happened is shown on the screen.
  Future<void> _clearCache(OfflineCache cache) async {
    var confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text("Clear the cache?"),
        content: const Text(
          "Everything kept for offline browsing is forgotten. It is fetched "
          "again the next time the server is reached.",
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text("Cancel"),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text("Clear"),
          ),
        ],
      ),
    );
    if (confirmed != true) {
      return;
    }
    var freed = await cache.size();
    await cache.clear();
    if (!mounted) {
      return;
    }
    setState(() {
      _cacheGeneration++;
      cacheMessage = "Cache cleared, ${formatBytes(freed)} freed.";
    });
  }

  /// The diagnostics section: what this app did on the network (issue #58).
  ///
  /// Collapsed by default — the settings screen keeps its shape for everyone
  /// who never needs it — and monospaced, newest last, because what is pasted
  /// into a bug report is read line by line.
  List<Widget> _diagnosticsSection(ServerSettings settings) => [
        const SizedBox(height: 8),
        const Divider(),
        ExpansionTile(
          key: diagnosticsSectionKey,
          tilePadding: EdgeInsets.zero,
          childrenPadding: const EdgeInsets.only(bottom: 16),
          title: Text(
            "Diagnostics",
            style: Theme.of(context).textTheme.titleMedium,
          ),
          subtitle: const Text(
            "What this app did on the network - copy it into a bug report.",
          ),
          children: [
            AnimatedBuilder(
              animation: log,
              builder: (context, _) => Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    width: double.infinity,
                    constraints: const BoxConstraints(maxHeight: 240),
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      border: Border.all(color: Theme.of(context).dividerColor),
                      borderRadius: BorderRadius.circular(4),
                    ),
                    child: SingleChildScrollView(
                      // Anchored at the end: the newest line is the one the
                      // person came here to read.
                      reverse: true,
                      child: SelectableText(
                        _logText(),
                        style: const TextStyle(
                          fontFamily: "monospace",
                          fontSize: 11,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      FilledButton.icon(
                        key: diagnosticsCopyKey,
                        onPressed: () => _copyDiagnostics(settings),
                        icon: const Icon(Icons.copy_all),
                        label: const Text("Copy"),
                      ),
                      TextButton.icon(
                        key: diagnosticsClearKey,
                        onPressed: log.isEmpty ? null : log.clear,
                        icon: const Icon(Icons.delete_outline),
                        label: const Text("Clear"),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ];

  /// The log as the box shows it, oldest first.
  String _logText() {
    var entries = log.entries;
    if (entries.isEmpty) {
      return "Nothing logged yet. Test the connection, or browse the album, "
          "and what the app asked the server appears here.";
    }
    return entries.join("\n");
  }

  /// Puts the whole log, header and all, on the clipboard and says so.
  Future<void> _copyDiagnostics(ServerSettings settings) async {
    var text = log.copyText(
      serverUrl: settings.serverUrl ?? settings.dataUrl,
      platform: platformDescription(),
    );
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) {
      return;
    }
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text("The diagnostics log is on the clipboard."),
        duration: Duration(seconds: 4),
      ),
    );
  }

  /// Forgets the token of this device; the server keeps its entry.
  ///
  /// Asks first where there is no way back: the last signed-in device
  /// (issue #92). What the question says is what the person would need to get
  /// in again — a recovery code, a restart of the server, their backup code —
  /// and it is asked even when the device list could not be read, because
  /// "probably fine" is no answer to a door that locks behind one.
  Future<void> _signOut() async {
    var known = await _knownDevices();
    if (!mounted) {
      return;
    }
    if (!await confirmLastSignOut(
      context: context,
      devices: known,
      role: identity?.role ?? "",
    )) {
      return;
    }
    await widget.settings.signOut();
    if (!mounted) {
      return;
    }
    setState(() {
      identity = null;
      identityProblem = null;
      signOutMessage = "This device no longer identifies itself to the server.";
    });
  }

  /// Drops the token of this device, the server having signed it out already.
  ///
  /// What the devices section calls after it removed *this* device: the
  /// question was asked there, and asking it twice would be asking about a
  /// door that is already shut.
  Future<void> _forgetToken() async {
    await widget.settings.signOut();
    if (!mounted) {
      return;
    }
    setState(() {
      identity = null;
      identityProblem = null;
      deviceList = null;
      signOutMessage = "This device no longer identifies itself to the server.";
    });
  }

  /// The caller's devices as this screen last saw them, asked for if it has
  /// not seen any yet.
  ///
  /// `null` where the server could not be asked at all; the sign-out warning
  /// reads that as "possibly the last one", see [confirmLastSignOut].
  Future<DeviceList?> _knownDevices() async {
    var known = deviceList;
    if (known != null) {
      return known;
    }
    var client = _managementClient();
    if (client == null || !widget.settings.signedIn) {
      return null;
    }
    try {
      return await client.devices();
    } catch (_) {
      return null;
    }
  }

  /// Fetches the root resource of the *entered* server, without saving it.
  Future<void> _test() async {
    var entered = controller.text;
    var problem = serverUrlError(entered);
    if (problem != null) {
      setState(() {
        error = problem;
        result = null;
      });
      return;
    }

    setState(() {
      error = null;
      result = null;
      testing = true;
    });

    var outcome = await testServerConnection(
      widget
          .clientFor(serverLocationOf(entered).dataUrl)
          .withToken(widget.settings.token),
    );

    if (!mounted) {
      return;
    }
    setState(() {
      testing = false;
      result = outcome;
    });
  }

  Future<void> _save() async {
    var entered = controller.text;
    var problem = serverUrlError(entered);
    if (problem != null) {
      setState(() => error = problem);
      return;
    }

    // An invitation URL names a server and a token; what a device stores is
    // the server, see [serverLocationOf]. Anything else is stored as typed.
    var location = serverLocationOf(entered);
    await widget.settings.save(
      location.isInvitation ? location.serverUrl : entered,
    );

    if (!mounted) {
      return;
    }
    if (widget.closable && Navigator.of(context).canPop()) {
      Navigator.of(context).pop();
    }
  }

  Future<void> _reset() async {
    await widget.settings.reset();

    if (!mounted) {
      return;
    }
    setState(() {
      controller.text = _suggestion();
      result = null;
      error = null;
      signOutMessage = null;
    });
    if (widget.closable &&
        widget.settings.configured &&
        Navigator.of(context).canPop()) {
      Navigator.of(context).pop();
    }
  }
}
