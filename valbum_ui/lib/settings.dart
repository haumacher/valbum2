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
/// The device token this app is paired with the server as lives beside the URL
/// (issue #28): it is issued by the server against a pairing secret, stored
/// here and sent by [VAlbumClient] on every request.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import 'camera_roll_view.dart';
import 'client.dart';
import 'offline.dart';
import 'resource.dart';
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

  /// The token this device is paired with the stored server as, or `null`.
  ///
  /// A store written before issue #28 holds no token; it loads as `null` and
  /// the app talks to the server anonymously, as it always did.
  Future<String?> loadToken();

  /// The name the stored token was issued to, or `null`.
  Future<String?> loadDeviceName();

  /// Stores the token the server issued and the name it was issued to.
  Future<void> saveToken(String token, String deviceName);

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

  /// The stored camera-roll configuration, see
  /// [SettingsStore.loadCameraRoll].
  String? cameraRoll;

  /// The stored report of the last background run, see
  /// [SettingsStore.loadBackgroundRun].
  String? backgroundRun;

  InMemorySettingsStore([this.value, this.token, this.deviceName]);

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
  Future<void> saveToken(String token, String deviceName) async {
    this.token = token;
    this.deviceName = deviceName;
  }

  @override
  Future<void> clearToken() async {
    token = null;
    deviceName = null;
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
  Future<void> saveToken(String token, String deviceName) async {
    var preferences = await SharedPreferences.getInstance();
    await preferences.setString(tokenKey, token);
    await preferences.setString(deviceNameKey, deviceName);
  }

  @override
  Future<void> clearToken() async {
    var preferences = await SharedPreferences.getInstance();
    await preferences.remove(tokenKey);
    await preferences.remove(deviceNameKey);
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
  bool _loaded = false;

  ServerSettings({
    required this.store,
    String? Function()? platformDefault,
    String? serverUrl,
    String? token,
    String? deviceName,
    bool loaded = false,
  })  : platformDefault = platformDefault ?? _none,
        _serverUrl = serverUrl,
        _token = token,
        _deviceName = deviceName,
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

  /// The token this device is paired with the server as, `null` if unpaired.
  ///
  /// Every request of the app carries it, see [VAlbumClient.token].
  String? get token => _token;

  /// The name the [token] was issued to, `null` if this device is unpaired.
  String? get deviceName => _deviceName;

  /// Whether this device is paired with the server it talks to.
  bool get paired => (_token ?? "").isNotEmpty;

  /// Reads the stored values; called once before the first client is built.
  Future<void> load() async {
    _serverUrl = await store.load();
    _token = await store.loadToken();
    _deviceName = await store.loadDeviceName();
    _loaded = true;
    notifyListeners();
  }

  /// Stores [serverUrl] and switches the app over to that server.
  ///
  /// A token belongs to the server that issued it: pointing the app at
  /// *another* server forgets it, saving the same URL again keeps it.
  Future<void> save(String serverUrl) async {
    var value = serverUrl.trim();
    await store.save(value);
    if (value != _serverUrl) {
      await _forgetToken();
    }
    _serverUrl = value;
    notifyListeners();
  }

  /// Forgets the stored value, returning to the [platformDefault].
  ///
  /// The token of the server given up is forgotten with it.
  Future<void> reset() async {
    await store.clear();
    await _forgetToken();
    _serverUrl = null;
    notifyListeners();
  }

  /// Remembers the token the server issued for this device, see [pair].
  Future<void> pairedAs(String token, String deviceName) async {
    await store.saveToken(token, deviceName);
    _token = token;
    _deviceName = deviceName;
    notifyListeners();
  }

  /// Forgets the token: the app talks to the server anonymously again.
  ///
  /// The server keeps its entry — only this device forgets how to prove it.
  Future<void> unpair() async {
    await _forgetToken();
    notifyListeners();
  }

  Future<void> _forgetToken() async {
    await store.clearToken();
    _token = null;
    _deviceName = null;
  }
}

/// Builds a client talking to the given data URL over the app's transport.
typedef ClientFactory = VAlbumClient Function(String dataUrl);

/// Makes the [ServerSettings] available to the widget tree.
class ServerSettingsScope extends InheritedNotifier<ServerSettings> {
  /// Builds the client the connection test talks to the entered server with.
  final ClientFactory clientFor;

  const ServerSettingsScope({
    super.key,
    required ServerSettings settings,
    required this.clientFor,
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

  /// What the server says about this device's pairing, `null` if it says
  /// nothing (a server from before issue #28).
  final String? authStatus;

  const ConnectionTestResult(this.ok, this.message, {this.authStatus});

  /// The same result reporting the given pairing status.
  ConnectionTestResult withAuthStatus(String? status) =>
      ConnectionTestResult(ok, message, authStatus: status);
}

/// Fetches the root resource of the server [client] talks to.
///
/// Every outcome is reported as a message; nothing fails silently.
Future<ConnectionTestResult> testServerConnection(VAlbumClient client) async =>
    (await _reachServer(client)).withAuthStatus(await _authStatus(client));

/// What the server says about this client's pairing, `null` if it says
/// nothing at all — a server from before issue #28 does not know the endpoint.
Future<String?> _authStatus(VAlbumClient client) async {
  AuthInfo info;
  try {
    info = await client.authInfo();
  } catch (_) {
    return null;
  }
  if (info.deviceName.isNotEmpty) {
    return "Paired as ${info.deviceName}";
  }
  return switch (info.mode) {
    "off" => "Not paired - this server needs no pairing",
    "all" => "Not paired - this server needs pairing to show anything",
    _ => "Not paired - changes need pairing",
  };
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

/// The key of the device name field, see [serverUrlFieldKey].
const Key deviceNameFieldKey = Key("settings.deviceName");

/// The key of the pairing secret field, see [serverUrlFieldKey].
const Key pairingSecretFieldKey = Key("settings.pairingSecret");

/// The key of the "Clear cache" button, see [serverUrlFieldKey].
const Key clearCacheButtonKey = Key("settings.clearCache");

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

  const ServerSettingsScreen({
    super.key,
    required this.settings,
    required this.clientFor,
    this.closable = true,
  });

  @override
  State<ServerSettingsScreen> createState() => ServerSettingsScreenState();
}

class ServerSettingsScreenState extends State<ServerSettingsScreen> {
  late final TextEditingController controller = TextEditingController(
    text: widget.settings.serverUrl ?? _suggestion(),
  );

  /// The name this device announces itself with when it is paired.
  late final TextEditingController deviceController = TextEditingController(
    text: widget.settings.deviceName ?? defaultDeviceName(),
  );

  /// The pairing secret the server was started with.
  ///
  /// Never stored: it is exchanged for the device token exactly once.
  final TextEditingController secretController = TextEditingController();

  /// The outcome of the last pairing attempt, if any.
  ConnectionTestResult? pairing;

  /// Whether a pairing request is running.
  bool pairingRunning = false;

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
  void dispose() {
    controller.dispose();
    deviceController.dispose();
    secretController.dispose();
    super.dispose();
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
              const Text(
                "The address the album server is reached at, as you would "
                "open it in a browser, e.g. 'http://nas.local:8080/valbum/'.",
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
              const SizedBox(height: 24),
              const Divider(),
              ..._pairingSection(settings),
              const CameraRollSection(),
              ..._cacheSection(),
            ],
          ),
        ),
      ),
    );
  }

  /// The icon and message of a connection test or pairing attempt.
  Widget _outcome(ConnectionTestResult outcome) => Row(
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

  /// The section pairing this device with the server.
  ///
  /// A server refusing anonymous changes issues a token against the pairing
  /// secret it was started with; the token is stored with the server URL and
  /// sent on every request from then on.
  List<Widget> _pairingSection(ServerSettings settings) => [
        const SizedBox(height: 8),
        Text("Pairing", style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        const Text(
          "A server that refuses anonymous changes issues a token for this "
          "device. The pairing secret is printed by the server at start-up.",
        ),
        const SizedBox(height: 16),
        Row(
          children: [
            Icon(
              settings.paired ? Icons.verified_user : Icons.no_encryption,
              color: settings.paired ? Colors.green : Colors.grey,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                settings.paired
                    ? "Paired as ${settings.deviceName ?? ""}"
                    : "Not paired",
              ),
            ),
          ],
        ),
        const SizedBox(height: 16),
        TextField(
          key: deviceNameFieldKey,
          controller: deviceController,
          autocorrect: false,
          decoration: const InputDecoration(
            labelText: "Device name",
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 16),
        TextField(
          key: pairingSecretFieldKey,
          controller: secretController,
          autocorrect: false,
          obscureText: true,
          decoration: const InputDecoration(
            labelText: "Pairing secret",
            border: OutlineInputBorder(),
          ),
          onSubmitted: (_) => _pair(),
        ),
        const SizedBox(height: 16),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            FilledButton.icon(
              onPressed: pairingRunning ? null : _pair,
              icon: const Icon(Icons.link),
              label: const Text("Pair this device"),
            ),
            TextButton.icon(
              onPressed: settings.paired ? _unpair : null,
              icon: const Icon(Icons.link_off),
              label: const Text("Unpair"),
            ),
          ],
        ),
        const SizedBox(height: 16),
        if (pairingRunning)
          const Row(
            children: [
              SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
              SizedBox(width: 8),
              Text("Pairing..."),
            ],
          ),
        if (!pairingRunning && pairing != null) _outcome(pairing!),
      ];

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

  /// Exchanges the pairing secret for a device token at the *entered* server.
  ///
  /// The URL does not have to be saved for this: pairing tests the address the
  /// user is looking at, like the connection test does.
  Future<void> _pair() async {
    if (OfflineScope.isOffline(context)) {
      // Pairing is a change on the server; while it cannot be reached there is
      // nothing to pair with. "Test connection" finds the server again and
      // clears this state.
      setState(
          () => pairing = const ConnectionTestResult(false, offlineRefusal));
      return;
    }
    var entered = controller.text;
    var problem = serverUrlError(entered);
    if (problem != null) {
      setState(() {
        error = problem;
        pairing = null;
      });
      return;
    }

    setState(() {
      error = null;
      pairing = null;
      pairingRunning = true;
    });

    var client = widget.clientFor(dataUrlOf(entered)).withToken(null);
    ConnectionTestResult outcome;
    try {
      var response = await client.pair(
        secretController.text.trim(),
        deviceController.text.trim().isEmpty
            ? defaultDeviceName()
            : deviceController.text.trim(),
      );
      await widget.settings.pairedAs(response.token, response.deviceName);
      outcome = ConnectionTestResult(true, "Paired as ${response.deviceName}");
    } on VAlbumException catch (failure) {
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
        secretController.clear();
      }
    });
  }

  /// Forgets the token of this device; the server keeps its entry.
  Future<void> _unpair() async {
    await widget.settings.unpair();
    if (!mounted) {
      return;
    }
    setState(() {
      pairing = const ConnectionTestResult(
        true,
        "This device no longer identifies itself to the server.",
      );
    });
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
      widget.clientFor(dataUrlOf(entered)).withToken(widget.settings.token),
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

    await widget.settings.save(entered);

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
      pairing = null;
    });
    if (widget.closable &&
        widget.settings.configured &&
        Navigator.of(context).canPop()) {
      Navigator.of(context).pop();
    }
  }
}
