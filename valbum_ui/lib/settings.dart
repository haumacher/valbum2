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
/// Credentials are not part of this screen (ROADMAP phase 2, issue #28).
library;

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import 'client.dart';
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
}

/// A [SettingsStore] keeping the value in memory only, used by tests.
class InMemorySettingsStore extends SettingsStore {
  String? value;

  InMemorySettingsStore([this.value]);

  @override
  Future<String?> load() async => value;

  @override
  Future<void> save(String serverUrl) async => value = serverUrl;

  @override
  Future<void> clear() async => value = null;
}

/// The [SettingsStore] of the app, backed by `shared_preferences`.
class PreferencesSettingsStore extends SettingsStore {
  /// The preferences key the server URL is stored under.
  static const String key = "serverUrl";

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
  bool _loaded = false;

  ServerSettings({
    required this.store,
    String? Function()? platformDefault,
    String? serverUrl,
    bool loaded = false,
  })  : platformDefault = platformDefault ?? _none,
        _serverUrl = serverUrl,
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

  /// Reads the stored value; called once before the first client is built.
  Future<void> load() async {
    _serverUrl = await store.load();
    _loaded = true;
    notifyListeners();
  }

  /// Stores [serverUrl] and switches the app over to that server.
  Future<void> save(String serverUrl) async {
    var value = serverUrl.trim();
    await store.save(value);
    _serverUrl = value;
    notifyListeners();
  }

  /// Forgets the stored value, returning to the [platformDefault].
  Future<void> reset() async {
    await store.clear();
    _serverUrl = null;
    notifyListeners();
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

  const ConnectionTestResult(this.ok, this.message);
}

/// Fetches the root resource of the server [client] talks to.
///
/// Every outcome is reported as a message; nothing fails silently.
Future<ConnectionTestResult> testServerConnection(VAlbumClient client) async {
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
  ConnectionTestResult withDetail(String detail) => detail.isEmpty
      ? this
      : ConnectionTestResult(ok, "$message ($detail)");
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

  /// The outcome of the last connection test, if any.
  ConnectionTestResult? result;

  /// Whether a connection test is running.
  bool testing = false;

  /// The reason the entered URL cannot be saved, if any.
  String? error;

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
              if (!testing && result != null)
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(
                      result!.ok ? Icons.check_circle : Icons.error,
                      color: result!.ok ? Colors.green : Colors.red,
                    ),
                    const SizedBox(width: 8),
                    Expanded(child: Text(result!.message)),
                  ],
                ),
            ],
          ),
        ),
      ),
    );
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

    var outcome =
        await testServerConnection(widget.clientFor(dataUrlOf(entered)));

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
    });
    if (widget.closable &&
        widget.settings.configured &&
        Navigator.of(context).canPop()) {
      Navigator.of(context).pop();
    }
  }
}
