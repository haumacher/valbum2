/// The application shell: the [VAlbumApp] widget, the router wiring
/// ([VAlbumRouterDelegate], [VAlbumRouteInformationParser], [VAlbumNavigator])
/// and the [VAlbumView] that loads the resource a route lives in and
/// dispatches to the view of that route.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:image_picker/image_picker.dart';
import 'package:sn_progress_dialog/options/cancel.dart';
import 'package:sn_progress_dialog/progress_dialog.dart';

import 'album_model.dart';
import 'album_view.dart';
import 'background.dart';
import 'caller.dart';
import 'camera_roll.dart';
import 'camera_roll_view.dart';
import 'client.dart';
import 'connectivity.dart';
import 'group_view.dart';
import 'image_view.dart';
import 'invitation.dart';
import 'links.dart';
import 'listing_view.dart';
import 'offline.dart';
import 'photo_library.dart';
import 'platform.dart';
import 'resource.dart';
import 'rights.dart';
import 'routes.dart';
import 'settings.dart';
import 'share_session.dart';
import 'urls.dart';

typedef Action = void Function(BuildContext context);

class VAlbumApp extends StatefulWidget {
  /// The client to talk to the server with.
  ///
  /// Defaults to a client for the server named by the [settings]. Tests inject
  /// a client with a fake transport; that transport is kept when the user
  /// points the app at another server, and the client's data URL becomes the
  /// default while nothing is stored, see [ServerSettings.platformDefault].
  final VAlbumClient? client;

  /// The server the app talks to, and its persistence.
  ///
  /// Defaults to the value stored on the device (`shared_preferences`) over
  /// the platform default: the origin on the web, nothing anywhere else. Tests
  /// inject settings with an [InMemorySettingsStore].
  final ServerSettings? settings;

  /// The route to start at, instead of the location the app was opened with.
  ///
  /// Tests use it to pump the app deep-linked into an album or an image.
  final VAlbumRoute? initialRoute;

  /// What the app has already seen, kept for the times the server is away
  /// (issue #31).
  ///
  /// Defaults to a [FileOfflineCache] under the app's support directory, to a
  /// [MemoryOfflineCache] on the web — and to one in a test, which is what an
  /// injected [client] says this app is, as with the [settings].
  final OfflineCache? cache;

  /// Whether the app is currently showing what the [cache] holds.
  final OfflineState? offlineState;

  /// The device's photo library, watched by the camera-roll sync (issue #30).
  ///
  /// Defaults to the library of the platform the app runs on — a real one on
  /// Android and iOS, an [UnavailablePhotoLibrary] everywhere else. Tests
  /// inject a [FakePhotoLibrary].
  final PhotoLibrary? photoLibrary;

  /// The platform's periodic background execution (issue #32).
  ///
  /// Defaults to the scheduler of the platform the app runs on — `workmanager`
  /// on Android and iOS, an [UnavailableBackgroundScheduler] everywhere else.
  /// Tests inject a [FakeBackgroundScheduler]; an injected [client] means a
  /// test or an embedder drives this app, so nothing then registers a task
  /// with the device either.
  final BackgroundScheduler? backgroundScheduler;

  /// The token session the app was opened at: a share link (issue #51) or an
  /// invitation (issue #52).
  ///
  /// Defaults to what the app base says on the web (a base ending in
  /// `/s/<token>/` or `/i/<token>/`, see [sessionUrl]) and to `null` everywhere
  /// else: off the web such a URL is opened in a browser, or pasted into the
  /// server field, see `invitation.dart`. Tests inject it.
  final SessionUrl? session;

  const VAlbumApp({
    super.key,
    this.client,
    this.settings,
    this.initialRoute,
    this.cache,
    this.offlineState,
    this.photoLibrary,
    this.backgroundScheduler,
    this.session,
  });

  @override
  State<VAlbumApp> createState() => VAlbumAppState();
}

/// The application state: which server the app talks to, and the router.
///
/// The server URL is the one moving part: the [settings] notify this state,
/// which then builds a new [VAlbumClient] over the same transport, hands it to
/// the [router] (dropping everything loaded from the old server) and publishes
/// it through the [VAlbumScope] the views take their client from. Nothing in
/// the app holds a client across that swap.
class VAlbumAppState extends State<VAlbumApp> {
  /// The server the app talks to, see [VAlbumApp.settings].
  late final ServerSettings settings = widget.settings ?? _defaultSettings();

  /// What this app has already seen, see [VAlbumApp.cache].
  late final OfflineCache cache = widget.cache ?? _defaultCache();

  /// Whether the app is showing a cached copy, see [OfflineState].
  late final OfflineState offlineState = widget.offlineState ?? OfflineState();

  /// Whether [offlineState] was created here and must be disposed.
  bool get _ownsOfflineState => widget.offlineState == null;

  /// The device's photo library, see [VAlbumApp.photoLibrary].
  late final PhotoLibrary photoLibrary =
      widget.photoLibrary ?? defaultPhotoLibrary();

  /// The platform's periodic background execution, see
  /// [VAlbumApp.backgroundScheduler].
  late final BackgroundScheduler backgroundScheduler =
      widget.backgroundScheduler ?? _defaultScheduler();

  /// The scheduler used when the app is not told otherwise.
  ///
  /// An injected client means a test or an embedder drives this app; nothing
  /// of it may reach the device then, exactly as with the settings store and
  /// the cache, see [_defaultCache].
  BackgroundScheduler _defaultScheduler() => widget.client != null
      ? const UnavailableBackgroundScheduler(
          "Background sync is not available in this app.",
        )
      : defaultBackgroundScheduler();

  /// The camera-roll sync (issue #30), driven by the photo library and the
  /// current client.
  ///
  /// It uses the *current* client (a function, not a value): pointing the app
  /// at another server or signing in on this device swaps the client, and
  /// the next run must go to the new one. Nothing runs until the stored
  /// configuration says the user switched the sync on.
  late final CameraRollSync cameraRoll = CameraRollSync(
    store: settings.store,
    library: photoLibrary,
    clientOf: () => client,
    // The answer of the one `?type=auth` question this app asks, see
    // [_syncCaller]: a guest has no space of their own to sync into, and the
    // run says so instead of uploading (issue #54).
    callerOf: () async => caller,
    isOffline: () => offlineState.offline,
    scheduler: backgroundScheduler,
    connectivity: connectivity,
  );

  /// The network the device is on, see [ConnectivitySource] (issue #36).
  ///
  /// An injected client means a test or an embedder drives this app; nothing
  /// of it may reach the device then, exactly as with the scheduler, see
  /// [_defaultScheduler].
  late final ConnectivitySource connectivity = widget.client != null
      ? const UnknownConnectivity()
      : defaultConnectivity();

  /// The cache used when the app is not told otherwise.
  ///
  /// An injected client means a test or an embedder drives this app; nothing
  /// of it may reach the device's file system then, exactly as with the
  /// settings store, see [_defaultSettings].
  OfflineCache _defaultCache() =>
      widget.client != null ? MemoryOfflineCache() : defaultOfflineCache();

  /// The transport every client of this app sends its requests over.
  late final http.Client _transport = widget.client?.httpClient ?? _own();

  /// Whether [_transport] was created here and must be closed on [dispose].
  bool _ownsTransport = false;

  http.Client _own() {
    _ownsTransport = true;
    return http.Client();
  }

  /// The client the app talks to the server with, `null` if none is set up.
  VAlbumClient? client;

  /// The token session the app was opened at, see [VAlbumApp.session].
  late final SessionUrl? _session = widget.session ?? _detectSession();

  /// The session the app was loaded at, read off the app base on the web.
  ///
  /// Read once, here, for the same reason the origin is (see
  /// [_defaultSettings]): the location changes while the app runs, and only
  /// the app base still says which token this is.
  static SessionUrl? _detectSession() => kIsWeb
      ? sessionUrl(
          Uri.base,
          basePath: appBasePath(
            Uri.base.path,
            WidgetsBinding.instance.platformDispatcher.defaultRouteName,
          ),
        )
      : null;

  /// Whether the server said that the token of [_session] is neither a link
  /// nor an invitation after all, see [_confirmSession].
  bool _sessionAbandoned = false;

  /// The session this app runs in while it runs in one.
  SessionUrl? get session => _sessionAbandoned ? null : _session;

  /// The share link this session runs in, `null` in every other start.
  SessionUrl? get shareLink {
    var current = session;
    return current != null && current.isShare ? current : null;
  }

  /// The invitation this session runs in, `null` in every other start.
  SessionUrl? get invitation {
    var current = session;
    return current != null && current.isInvitation ? current : null;
  }

  /// What the server said the link is, `null` until `?type=auth` answered.
  ShareSession? shareSession;

  /// What the server said the invitation offers, `null` until `?type=auth`
  /// answered, see [_confirmSession].
  InvitationInfo? invitationInfo;

  /// Who the app talks to the server as, `null` while nobody said, see
  /// [CallerInfo].
  CallerInfo? caller;

  /// The server and token [caller] was asked about, so that the question is
  /// asked once per client and not once per rebuild.
  String? _callerAsked;

  /// Why the session cannot start, `null` while all is well.
  ///
  /// A `410` (expired, withdrawn, used up) is the reason this exists; every
  /// other failure of the start-up probe lands here too, so that a session
  /// never falls back to a page offering the server settings.
  VAlbumException? _shareRefusal;

  /// Whether the router may be built: what the app is waiting for is there.
  ///
  /// Two starts, and they wait for different things — which is the point:
  ///
  ///  * an ordinary start waits for the **device**: the stored server URL and
  ///    the stored token must be read before the first client is built, so it
  ///    waits for [ServerSettings.loaded];
  ///  * a link session waits for the **server**: the link names its own
  ///    server and carries its own token, so nothing of the device takes part
  ///    in it and `settings.loaded` is not asked at all — on a real device it
  ///    would never become true, because a link session never calls
  ///    [ServerSettings.load]. What it waits for is the `?type=auth` answer
  ///    that confirms the link, see [_confirmShareLink].
  ///
  /// A refused link session waits for nothing: it shows the plain page of
  /// [_startupScreen] instead of the app.
  bool get _readyForTheRouter {
    if (_shareRefusal != null) {
      return false;
    }
    if (invitation != null) {
      // An invitation opens no album: it ends in a sign-in and then leaves
      // this app base for the ordinary one, see [InvitationWelcomeScreen].
      return false;
    }
    if (shareLink != null) {
      return shareSession != null;
    }
    return settings.loaded;
  }

  VAlbumRouterDelegate? _router;

  /// The router state: which view the app shows, see [VAlbumRoute].
  ///
  /// Created with the first client; the app shows the settings screen while
  /// there is none.
  VAlbumRouterDelegate get router => _router ??= VAlbumRouterDelegate(
        client: client!,
        initialRoute: widget.initialRoute ?? ListingOrAlbumRoute.root,
      );

  /// The settings used when the app is not told otherwise.
  ///
  /// With a client injected (tests, an embedder) that client names the default
  /// server and nothing is stored. Otherwise the value stored on the device
  /// wins over the platform default: on the web the origin the app was loaded
  /// from, on every other platform nothing — there the settings screen opens
  /// first instead of guessing a server.
  ServerSettings _defaultSettings() {
    var injected = widget.client;
    if (injected != null) {
      return ServerSettings(
        store: InMemorySettingsStore(),
        platformDefault: () => injected.dataUrl,
        loaded: true,
      );
    }
    // Derived once, here in `initState`: `Uri.base` is the document location,
    // and the location changes while the app runs (every navigation writes the
    // route into it). Read again later, the "origin" would be whatever album
    // the user is looking at, and the app would ask a data URL that does not
    // exist, see issue #35.
    var origin = kIsWeb ? _originDataUrl() : null;
    return ServerSettings(
      store: const PreferencesSettingsStore(),
      platformDefault: () => origin,
    );
  }

  /// The data URL derived from the origin the web app was loaded from.
  ///
  /// Unlike [VAlbumClient.fromOrigin], the data URL is derived from the app
  /// base rather than from the document location: with the path URL strategy
  /// the location is the *view*, not the directory the app was loaded from,
  /// see [appBasePath].
  static String _originDataUrl() => deriveDataUrl(
        Uri.base,
        isWeb: true,
        basePath: appBasePath(
          Uri.base.path,
          WidgetsBinding.instance.platformDispatcher.defaultRouteName,
        ),
      );

  /// The location the app was opened with, read once at start-up.
  ///
  /// The router app is built only once the stored settings have been read; by
  /// then the deep link the app was opened with must still be the one it
  /// starts at, so it is captured before anything is shown, see
  /// [_routeInformationProvider].
  late final RouteInformation _initialRouteInformation = RouteInformation(
    uri: widget.initialRoute != null
        ? routeToUri(widget.initialRoute!)
        : Uri.parse(
            WidgetsBinding.instance.platformDispatcher.defaultRouteName,
          ),
  );

  /// Hands the router the location the app was opened with, and the locations
  /// the browser's back and forward buttons produce afterwards.
  late final PlatformRouteInformationProvider _routeInformationProvider =
      PlatformRouteInformationProvider(
    initialRouteInformation: _initialRouteInformation,
  );

  /// Builds a client for the given data URL over this app's transport.
  ///
  /// The client carries the token this device is signed in with, so
  /// that every request of the app identifies itself, see [ServerSettings].
  VAlbumClient clientFor(String dataUrl) => VAlbumClient(
        dataUrl: dataUrl,
        token: settings.token,
        // Names the cached copies of this device and is who the share dialog
        // leaves out of the people to share with, see issue #49.
        userName: settings.userName ?? "",
        httpClient: _transport,
        cache: cache,
        offlineState: offlineState,
      );

  @override
  void initState() {
    super.initState();
    // Both are read from the platform and must be read *now*, before anything
    // rewrites the location: the server the app was loaded from and the view
    // it was opened at, see [_defaultSettings] and [_initialRouteInformation].
    settings.addListener(_settingsChanged);
    _initialRouteInformation;
    _syncClient();
    if (session != null) {
      // A link session asks one question before it shows anything: is this
      // token a link, and what does it open? Nothing of the device takes part
      // in it — the settings are never *loaded* (no stored server URL, no
      // stored token), the camera roll never starts, and the router therefore
      // never waits for any of them, see [_readyForTheRouter]. Only a link
      // the server does not know falls back to the ordinary start, which
      // reads the device then, see [_confirmShareLink].
      _confirmSession();
      return;
    }
    // Before the first client is built: until this completes the app shows a
    // splash, see [build].
    var settingsLoaded =
        settings.loaded ? Future<void>.value() : settings.load();
    // App start-up is one of the sync's triggers; `start` does nothing at all
    // while the stored configuration says the sync is off. The sync starts
    // only once the server URL is known: otherwise its first run fails with
    // "no server configured" although one is stored on the device.
    settingsLoaded.then((_) => cameraRoll.load()).then((_) {
      if (mounted) {
        cameraRoll.start();
      }
    });
  }

  /// Asks the server once what the token in the app base really is.
  ///
  /// `?type=auth` answers an [AuthInfo.share] for a link caller, an
  /// [AuthInfo.invitation] for somebody holding an invitation and nothing for
  /// anybody else, so the one answer decides between three starts:
  ///
  ///  * what the URL said it is — the session is confirmed and the app runs
  ///    inside the link ([ShareSessionScope]) or shows the welcome screen of
  ///    the invitation ([InvitationWelcomeScreen]);
  ///  * neither — the token in the URL is not a session of this server (a
  ///    hand-made path, a link of another server), so it is abandoned and the
  ///    app starts as it always does, from the settings of this device;
  ///  * a refusal — a `410` for a link or an invitation that expired, was
  ///    withdrawn or was used up, and every other failure, which becomes the
  ///    plain page of [_startupScreen].
  Future<void> _confirmSession() async {
    var link = session!;
    var probe = client!;
    AuthInfo answer;
    try {
      answer = await probe.authInfo();
    } on VAlbumException catch (refusal) {
      if (mounted) {
        setState(() => _shareRefusal = refusal);
      }
      return;
    } catch (error) {
      if (mounted) {
        setState(() => _shareRefusal = VAlbumException("$error"));
      }
      return;
    }
    if (!mounted) {
      return;
    }
    if (link.isInvitation) {
      var offered = answer.invitation;
      if (offered == null) {
        _abandonSession();
        return;
      }
      setState(() => invitationInfo = offered);
      return;
    }
    var share = answer.share;
    if (share == null) {
      _abandonSession();
      return;
    }
    setState(() {
      shareSession = ShareSession(
        url: link,
        info: share,
        writeAllowed: answer.writeAllowed,
      );
    });
  }

  /// The token in the app base is no session of this server: start as usual.
  ///
  /// Without a word — a URL that is not a link and not an invitation is a URL
  /// like any other, and the device's own library is what lies behind it.
  void _abandonSession() {
    setState(() {
      _sessionAbandoned = true;
      _syncClient();
    });
    // The ordinary start, now that this is an ordinary session.
    var settingsLoaded =
        settings.loaded ? Future<void>.value() : settings.load();
    settingsLoaded.then((_) {
      if (mounted) {
        setState(_syncClient);
      }
    });
  }

  /// Stores what the accepted invitation answered: this server, and the token
  /// this device is signed in with from now on, see issue #52.
  ///
  /// Exactly what a sign-in with the pairing secret stores, and stored through
  /// the same [ServerSettings]: what came out of an invitation is an ordinary
  /// device token. The invitation token itself is written nowhere.
  Future<void> _invitationAccepted(PairResponse answer) async {
    await settings.save(invitation!.appBase);
    await settings.signedInAs(
      answer.token,
      answer.deviceName,
      userName: answer.userName,
    );
  }

  @override
  void dispose() {
    settings.removeListener(_settingsChanged);
    cameraRoll.dispose();
    connectivity.dispose();
    if (widget.photoLibrary == null) {
      photoLibrary.dispose();
    }
    _router?.dispose();
    if (widget.settings == null) {
      settings.dispose();
    }
    if (_ownsTransport) {
      _transport.close();
    }
    if (_ownsOfflineState) {
      offlineState.dispose();
    }
    _routeInformationProvider.dispose();
    _preRouter?.dispose();
    super.dispose();
  }

  /// The server changed: swap the client and reload what is shown.
  ///
  /// Inside a link session [_syncClient] ignores the settings entirely, so a
  /// store that answers late (or a screen that was left open in another tab)
  /// can never take the session's client away from it.
  void _settingsChanged() => setState(_syncClient);

  void _syncClient() {
    var link = session;
    if (link != null) {
      // A link names its own server: neither the stored server URL nor the
      // stored device token is consulted, and the session's token is never
      // written to the store. Nothing is cached either — a visitor's session
      // leaves nothing on the device it was opened on.
      if (client?.dataUrl == link.dataUrl && client?.token == link.token) {
        return;
      }
      var sessionClient = VAlbumClient(
        dataUrl: link.dataUrl,
        token: link.token,
        httpClient: _transport,
        offlineState: offlineState,
      );
      client = sessionClient;
      _router?.client = sessionClient;
      // Nothing of the device takes part in a session, the caller question
      // included: a link caller is nobody and an invitation holder is not a
      // user yet, see [_syncCaller].
      return;
    }
    var dataUrl = settings.dataUrl;
    if (dataUrl == null) {
      client = null;
      _syncCaller();
      return;
    }
    if (client?.dataUrl == dataUrl && client?.token == settings.token) {
      return;
    }
    var next = clientFor(dataUrl);
    client = next;
    _syncCaller();
    // Drops every resource and scroll offset of the previous server and
    // re-runs the load of the current route.
    _router?.client = next;
    // A server or token that changed is a reason to sync again: what the
    // previous server refused, the new one (or the sign-in) may accept.
    if (cameraRoll.loaded && cameraRoll.config.enabled) {
      cameraRoll.trigger();
    }
  }

  /// Asks the server who this device is signed in as, once per client.
  ///
  /// The rights the server sends with every folder say what may be done
  /// *there*; the role says what this caller is, which is the one thing a
  /// folder answer does not carry — a guest holds every right in their own
  /// root and may still put no photo into it, see [CallerInfo]. One question
  /// per client, never one per folder.
  ///
  /// Only for a signed-in device: an anonymous caller has no role, so nothing
  /// is asked and nothing is published. A server that does not answer leaves
  /// the caller unknown, and the app behaves exactly as it did before the
  /// question existed.
  void _syncCaller() {
    var current = client;
    var token = current?.token ?? "";
    var asked = current == null ? null : "${current.dataUrl}|$token";
    if (asked == _callerAsked) {
      return;
    }
    _callerAsked = asked;
    caller = null;
    if (current == null || token.isEmpty) {
      return;
    }
    current.authInfo().then((info) {
      if (mounted && _callerAsked == asked) {
        setState(() => caller = CallerInfo.of(info));
      }
    }).catchError((Object _) {
      // The server did not say; the caller stays unknown, see [CallerInfo].
    });
  }

  @override
  Widget build(BuildContext context) {
    return OfflineScope(
      state: offlineState,
      cache: cache,
      child: CameraRollScope(
        sync: cameraRoll,
        child: ServerSettingsScope(
          settings: settings,
          clientFor: clientFor,
          // Published around the whole app: every view asks whether it is
          // inside a link, see [ShareSession.of], and who is calling, see
          // [CallerInfo.maybeOf].
          child: ShareSessionScope(
            session: shareSession,
            child: CallerScope(
              caller: caller,
              child: _readyForTheRouter && client != null
                  ? _albumApp(client!)
                  : _beforeTheRouter(),
            ),
          ),
        ),
      ),
    );
  }

  /// The app before its router exists: the splash, or the server setup.
  ///
  /// A [MaterialApp.router] with a delegate that has no configuration at all,
  /// and that is the point: the root [Navigator] of a plain [MaterialApp]
  /// reports its route to the engine, which on the web rewrites the browser
  /// location to the app base. The deep link the app was opened with would be
  /// gone before the router ever saw it, see issue #35. A delegate whose
  /// `currentConfiguration` is `null` reports nothing, and the screen still
  /// gets a [Navigator] of its own for its dialogs.
  ///
  /// One delegate for both screens, kept across rebuilds: it holds the
  /// [Navigator] the settings screen lives in, and re-creating that would
  /// throw away what the user has typed.
  Widget _beforeTheRouter() => MaterialApp.router(
        title: 'Virtual Photo Album',
        theme: ThemeData(primarySwatch: Colors.blue),
        routerDelegate: _preRouter ??= SilentRouterDelegate(_startupScreen),
      );

  /// The delegate of the screens shown before the router, see
  /// [_beforeTheRouter].
  SilentRouterDelegate? _preRouter;

  /// The splash while the stored server URL is being read, the server setup
  /// once it turns out that no server is configured.
  Widget _startupScreen(BuildContext context) {
    var refusal = _shareRefusal;
    if (refusal != null) {
      // A link that is gone says so and offers nothing else: there is no
      // server to configure and no device to sign in, see issue #51.
      return ShareGoneScreen(message: refusal.message);
    }
    var invite = invitation;
    if (invite != null) {
      var offered = invitationInfo;
      if (offered == null) {
        // The invitation is being read; nothing of the device belongs on the
        // screen until the server has said who invited, see [_confirmSession].
        return const Scaffold(body: Center(child: CircularProgressIndicator()));
      }
      return InvitationWelcomeScreen(
        client: client!,
        info: offered,
        token: invite.token,
        appBase: invite.appBase,
        onJoined: _invitationAccepted,
        // An invitation that died between the welcome and the "Join" is gone
        // exactly as one that was dead on arrival, and says the same thing.
        onGone: (message) => setState(
          () => _shareRefusal = VAlbumException(message, status: 410),
        ),
      );
    }
    if (shareLink != null) {
      // The link is being confirmed; the device has nothing to say here, so
      // the settings screen is not a possible outcome, see [_readyForTheRouter].
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (!settings.loaded) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    return ServerSettingsScreen(
      settings: settings,
      clientFor: clientFor,
      closable: false,
    );
  }

  Widget _albumApp(VAlbumClient client) => VAlbumScope(
        client: client,
        child: MaterialApp.router(
          title: 'Virtual Photo Album',
          theme: ThemeData(primarySwatch: Colors.blue),
          routerDelegate: router,
          routeInformationParser: const VAlbumRouteInformationParser(),
          // Always this app's own provider: it carries the location the app
          // was opened with, captured before the splash could rewrite it.
          routeInformationProvider: _routeInformationProvider,
        ),
      );
}

/// A router delegate showing one screen and telling the engine nothing.
///
/// Used for the screens the app shows before its own router exists (the
/// splash, the server setup): they must not touch the browser location, see
/// [VAlbumAppState._beforeTheRouter]. The screen is built through a builder,
/// so that the same delegate — and with it the [Navigator] the screen's
/// dialogs are pushed into — survives every rebuild of the app.
class SilentRouterDelegate extends RouterDelegate<Object> with ChangeNotifier {
  /// Builds the screen shown.
  final WidgetBuilder screen;

  final GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();

  SilentRouterDelegate(this.screen);

  /// Nothing: this is what keeps the location untouched.
  @override
  Object? get currentConfiguration => null;

  @override
  Future<void> setNewRoutePath(Object configuration) => SynchronousFuture(null);

  /// The system back button closes a dialog, and does nothing else here.
  @override
  Future<bool> popRoute() async {
    var navigator = navigatorKey.currentState;
    return navigator != null && await navigator.maybePop();
  }

  @override
  Widget build(BuildContext context) => Navigator(
        key: navigatorKey,
        pages: [
          MaterialPage<void>(
            key: const ValueKey("valbum:startup"),
            child: Builder(builder: screen),
          ),
        ],
        onDidRemovePage: (page) {},
      );
}

/// Translates between the browser location and the [VAlbumRoute] shown.
///
/// On the web the location has already been stripped of the `<base href>`
/// directory by the path URL strategy, so the parser works with the default
/// base, see `routes.dart`.
class VAlbumRouteInformationParser extends RouteInformationParser<VAlbumRoute> {
  const VAlbumRouteInformationParser();

  @override
  Future<VAlbumRoute> parseRouteInformation(
    RouteInformation routeInformation,
  ) =>
      SynchronousFuture(parseRoute(routeInformation.uri));

  @override
  RouteInformation restoreRouteInformation(VAlbumRoute configuration) =>
      RouteInformation(uri: routeToUri(configuration));
}

/// The router state of the app: the [route] currently shown.
///
/// Every navigation of the app goes through [go] (or [up]), so that the
/// location always describes the view, the browser history holds every step
/// and a bookmarked URL opens the same view again.
class VAlbumRouterDelegate extends RouterDelegate<VAlbumRoute>
    with ChangeNotifier {
  VAlbumClient _client;

  final GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();

  /// The resources loaded so far, keyed by the album path they were loaded
  /// for.
  ///
  /// Returning to a listing already visited (the "up" action, the browser's
  /// back button) therefore shows it without asking the server again; a
  /// [reload] drops the entry.
  final Map<String, Future<Resource?>> _resources = {};

  /// The scroll offset of every album path visited, see [scrollOffset].
  final Map<String, double> _scrollOffsets = {};

  /// The edit session of every album being edited, see [editSession].
  final Map<String, AlbumEditSession> _editSessions = {};

  /// Whether the caller may manage the grants of a folder, by path, see
  /// [mayManageGrants].
  final Map<String, Future<bool>> _grantAccess = {};

  VAlbumRoute _route;

  /// Incremented by [reload], so that the view re-runs its load.
  int _version = 0;

  /// The canonical routes already looked up in the viewer's own tree, so that
  /// a route is never looked up twice — and a lookup that led nowhere is not
  /// retried, see [_resolveLink].
  final Set<String> _linksChecked = {};

  /// Whether a canonical route is currently being matched against the
  /// viewer's own links.
  ///
  /// The view is held back while it is: the canonical path would otherwise be
  /// fetched only to be left again the moment the link is found.
  bool _resolvingLink = false;

  VAlbumRouterDelegate({
    required VAlbumClient client,
    required VAlbumRoute initialRoute,
  })  : _client = client,
        _route = initialRoute {
    _considerLink(initialRoute);
  }

  /// The transport to the album server.
  VAlbumClient get client => _client;

  /// Talks to another server from now on.
  ///
  /// Everything loaded from the previous server is forgotten — the resources
  /// and the scroll offsets — and the view currently shown re-runs its load
  /// against the new one.
  set client(VAlbumClient value) {
    if (identical(value, _client)) {
      return;
    }
    _client = value;
    _resources.clear();
    _scrollOffsets.clear();
    _editSessions.clear();
    _grantAccess.clear();
    // Another server — and, after a sign-in, another caller: whether this
    // caller holds a link to the canonical route on the screen is an open
    // question again, see [_considerLink].
    _linksChecked.clear();
    _version++;
    _considerLink(_route);
    notifyListeners();
  }

  /// The view currently shown.
  VAlbumRoute get route => _route;

  @override
  VAlbumRoute get currentConfiguration => _route;

  @override
  Future<void> setNewRoutePath(VAlbumRoute configuration) {
    _route = configuration;
    _considerLink(configuration);
    return SynchronousFuture(null);
  }

  /// Opens a canonical `~owner/…` route at the viewer's own link to it, where
  /// there is one, see issue #50.
  ///
  /// A canonical URL — a share link, a path copied out of somebody else's
  /// app — works for everybody holding a grant on the target. A member who
  /// was *given* a link, though, has the album in their own tree, and there
  /// the URL, the way up and the scroll memory are their own; so the route is
  /// re-spelled before anything is fetched.
  ///
  /// Asked once per canonical route, and only of a caller who is signed in: an
  /// anonymous caller has no space, therefore no links, and the question would
  /// be a request for nothing.
  void _considerLink(VAlbumRoute route) {
    if (client.token == null || spaceOwnerOf(route.albumPath) == null) {
      return;
    }
    if (!_linksChecked.add(_pathKey(route.albumPath))) {
      return;
    }
    _resolvingLink = true;
    _resolveLink(route);
  }

  /// Looks the canonical [route] up in the viewer's root listing and goes to
  /// the link found, see [_considerLink] and [viewerPathFor].
  ///
  /// The link is *verified* before the app goes there — the resource is loaded
  /// (and thereby memoised for the view that follows), so a link the server no
  /// longer follows does not replace a canonical path that still works. It is
  /// never tried twice: whatever happens here, the route ends up either at the
  /// viewer's own path or at the canonical one.
  Future<void> _resolveLink(VAlbumRoute route) async {
    try {
      var root = await resourceAt(const []);
      if (root is! ListingInfo) {
        return;
      }
      var viewerPath = viewerPathFor(route.albumPath, root);
      if (viewerPath == null) {
        return;
      }
      try {
        await resourceAt(viewerPath);
      } catch (_) {
        // The link led nowhere: the canonical path is opened instead, and the
        // failed load is forgotten so that it does not answer for the path
        // later on.
        forget(viewerPath);
        return;
      }
      if (_route != route) {
        // The user navigated on while the root listing was being fetched.
        return;
      }
      _route = route.withAlbumPath(viewerPath);
    } catch (_) {
      // No root listing, no lookup: the canonical path is opened as it is.
    } finally {
      _resolvingLink = false;
      notifyListeners();
    }
  }

  /// Shows the given view, adding a history entry.
  void go(VAlbumRoute target) {
    if (target == _route) {
      return;
    }
    _route = target;
    notifyListeners();
  }

  /// Leaves the current view for the one above it, see [VAlbumRoute.up].
  ///
  /// Returns whether there was one.
  bool goUp() {
    var target = _route.up;
    if (target == null) {
      return false;
    }
    go(target);
    return true;
  }

  /// Re-fetches the resource of the current route from the server.
  void reload() {
    _resources.remove(_pathKey(_route.albumPath));
    _version++;
    notifyListeners();
  }

  /// Forgets the resource loaded for the given path, so that the next view of
  /// it asks the server again (the [reload] of a path not currently shown).
  void forget(List<String> path) => _resources.remove(_pathKey(path));

  /// Changes whenever [reload] dropped a resource, see [VAlbumNavigator].
  int get version => _version;

  /// The resource at the given album path, fetched at most once.
  Future<Resource?> resourceAt(List<String> path) =>
      _resources.putIfAbsent(_pathKey(path), () => _load(path));

  Future<Resource?> _load(List<String> path) async {
    var resource = await client.loadResource(path);
    if (resource is AlbumInfo) {
      AlbumInitializer().init(resource);
    }
    return resource;
  }

  /// The remembered scroll offset of the listing or album at [path].
  double scrollOffset(List<String> path) => _scrollOffsets[_pathKey(path)] ?? 0;

  /// Remembers the scroll offset of the listing or album at [path], so that
  /// returning to it shows the same part of the page again.
  void rememberScrollOffset(List<String> path, double offset) =>
      _scrollOffsets[_pathKey(path)] = offset;

  /// The edit session of the album at [path].
  ///
  /// The album view enters and leaves the edit mode here, not in a widget of
  /// its own: descending from the album into one of its images (or into the
  /// alternatives of a group, to pick the group's representative) disposes
  /// the album view, and the edit — the mode, the selection, the unsaved
  /// changes to the cached model — must survive the trip and be there when
  /// the way up leads back to the album.
  AlbumEditSession editSession(List<String> path) =>
      _editSessions.putIfAbsent(_pathKey(path), AlbumEditSession.new);

  /// Whether this caller may see and change the grants of the folder at
  /// [path], see issue #49.
  ///
  /// There is no endpoint asking the question, so it is asked by trying:
  /// `?type=grants` answers the owner of the space (and the administrator)
  /// and refuses everybody else with a 403. The answer is remembered per
  /// folder, so a view that offers "Share with…" asks once, not once per
  /// rebuild; the memo is dropped with everything else when the client is
  /// swapped. A server that cannot be reached, or one from before issue #49
  /// (404/405), answers "no": an entry that would fail is better not offered.
  Future<bool> mayManageGrants(List<String> path) =>
      _grantAccess.putIfAbsent(_pathKey(path), () async {
        try {
          await client.grants(path);
          return true;
        } catch (_) {
          return false;
        }
      });

  static String _pathKey(List<String> path) => path.join("/");

  /// The system back button (and the browser's, on the web) goes up.
  ///
  /// A dialog or another imperatively pushed route is closed first.
  @override
  Future<bool> popRoute() async {
    var navigator = navigatorKey.currentState;
    if (navigator != null && await navigator.maybePop()) {
      return true;
    }
    return goUp();
  }

  @override
  Widget build(BuildContext context) {
    if (_resolvingLink) {
      // Nothing is fetched for the canonical route while it may yet be
      // re-spelled in the viewer's own coordinates, see [_considerLink].
      return const Scaffold(
        body: Center(child: Text("Loading...", key: Key("link-lookup"))),
      );
    }
    return VAlbumNavigator(
      route: _route,
      version: _version,
      delegate: this,
      child: Navigator(
        key: navigatorKey,
        pages: [
          MaterialPage<void>(
            // Keyed by the album path, not by the route: opening an image of
            // the album keeps the album loaded.
            key: ValueKey("valbum:${_pathKey(_route.albumPath)}"),
            child: VAlbumView(route: _route),
          ),
        ],
        onDidRemovePage: (page) {},
      ),
    );
  }
}

/// The navigation API of the app, available to every view.
///
/// The views never talk to the [Navigator]: they ask for a [VAlbumRoute] to be
/// shown, and the router turns that into the view and the location.
class VAlbumNavigator extends InheritedWidget {
  /// The view currently shown.
  final VAlbumRoute route;

  /// The reload counter of the [delegate], see [VAlbumRouterDelegate.version].
  final int version;

  final VAlbumRouterDelegate delegate;

  const VAlbumNavigator({
    super.key,
    required this.route,
    required this.version,
    required this.delegate,
    required super.child,
  });

  /// The navigation API of the enclosing [VAlbumApp].
  static VAlbumNavigator of(BuildContext context) {
    var result = context.dependOnInheritedWidgetOfExactType<VAlbumNavigator>();
    assert(result != null, "No VAlbumNavigator found in the widget tree.");
    return result!;
  }

  /// Shows the given view.
  void go(VAlbumRoute target) => delegate.go(target);

  /// Shows the view above the current one, see [VAlbumRoute.up].
  void up() => delegate.goUp();

  /// Shows the root listing.
  void home() => delegate.go(ListingOrAlbumRoute.root);

  /// Re-fetches the displayed resource from the server.
  void reload() => delegate.reload();

  @override
  bool updateShouldNotify(VAlbumNavigator oldWidget) =>
      route != oldWidget.route ||
      version != oldWidget.version ||
      delegate != oldWidget.delegate;
}

/// Loads the listing or album a [VAlbumRoute] lives in and shows the view the
/// route names.
class VAlbumView extends StatefulWidget {
  /// The view to show.
  final VAlbumRoute route;

  const VAlbumView({super.key, required this.route});

  @override
  State<VAlbumView> createState() => VAlbumState();
}

class VAlbumState extends State<VAlbumView>
    implements ResourceVisitor<Widget, BuildContext> {
  Future<Resource?>? _resourceFuture;

  /// The transport to the album server, provided by the [VAlbumScope].
  late VAlbumClient client;

  /// The router, see [VAlbumNavigator].
  late VAlbumNavigator navigator;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();

    client = VAlbumScope.of(context);
    navigator = VAlbumNavigator.of(context);
    doLoad();
  }

  void doLoad() {
    _resourceFuture = navigator.delegate.resourceAt(path);
  }

  /// The view shown, see [VAlbumNavigator.route].
  VAlbumRoute get route => widget.route;

  List<String> get path => widget.route.albumPath;
  String get baseUrl => client.baseUrl(path);

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<Resource?>(
      future: _resourceFuture,
      builder: (BuildContext context, AsyncSnapshot<Resource?> snapshot) {
        if (snapshot.hasError) {
          return buildError(snapshot.error);
        } else if (snapshot.hasData) {
          var resource = snapshot.data;
          if (resource == null) {
            return buildError("No data loaded");
          }
          return resource.visitResource(this, context);
        } else {
          return buildLoading();
        }
      },
    );
  }

  Widget buildLoading() {
    return Scaffold(
      appBar: AppBar(title: const Text("Virtual photo album")),
      body: const Center(child: Text('Loading...')),
    );
  }

  /// The view of a load that failed, naming the server's own reason.
  ///
  /// A server refusing a device that is not signed in answers with the reason
  /// it refuses; that reason names a remedy the user reaches from here, so the
  /// view offers the way to the server settings alongside the retry. A refusal
  /// *because* nobody is signed in (401) is not an error of the app at all, so
  /// it gets a page of its own, see [buildSignInRequired].
  Widget buildError(Object? error) {
    var share = ShareSession.of(context);
    if (share != null) {
      return buildShareError(error);
    }
    if (error is VAlbumException && error.status == 401) {
      return buildSignInRequired(error);
    }
    return Scaffold(
      appBar: AppBar(title: const Text("Virtual photo album")),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                'Loading failed: ${error?.toString()}',
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 16),
              OutlinedButton.icon(
                onPressed: () => openServerSettings(context),
                icon: const Icon(Icons.settings),
                label: const Text("Server settings..."),
              ),
            ],
          ),
        ),
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: reload,
        tooltip: 'Reload',
        child: const Icon(Icons.update),
      ), // This trailing comma makes auto-formatting nicer for build methods.
    );
  }

  /// The view of a failed load inside a link session, see issue #51.
  ///
  /// Plain pages, all three of them: a visitor of a link has no settings to
  /// open and no device to sign in, so nothing here offers either — and the
  /// sentence on the screen is the server's own, since it is the server that
  /// knows whether the link expired, was withdrawn, or simply does not cover
  /// the path that was asked for.
  ///
  ///  * `410` — the link is gone, and nothing the visitor does brings it
  ///    back, see [ShareGoneScreen];
  ///  * `404` — from inside a link there is nothing outside it, so the only
  ///    way on is the link's own root, see [ShareConfinedScreen];
  ///  * anything else — the server could not be asked; the one button tries
  ///    again.
  Widget buildShareError(Object? error) {
    var refusal = error is VAlbumException ? error : null;
    var message = refusal?.message ?? "${error ?? "No data loaded"}";
    if (refusal?.status == 410) {
      return ShareGoneScreen(message: message);
    }
    if (refusal?.status == 404) {
      return ShareConfinedScreen(message: message, onHome: navigator.home);
    }
    return SharePlainPage(
      icon: Icons.cloud_off,
      message: message,
      action: FilledButton.icon(
        key: const Key("share-retry"),
        onPressed: reload,
        icon: const Icon(Icons.refresh),
        label: const Text("Try again"),
      ),
    );
  }

  /// The view of a server that refuses this device because nobody is signed
  /// in on it.
  ///
  /// The server says so with a 401 and its own message — which names the way
  /// in, be it the sign-in or a share link; the remedy is one button away, the
  /// sign-in lives in the server settings, see [ServerSettingsScreen].
  /// Reaching this page is a normal first contact with a server started with
  /// `--auth all`, or with a library that has been migrated to its users (see
  /// issue #45), not a failure of the app, so it says what to do instead of
  /// quoting an error.
  Widget buildSignInRequired(VAlbumException refusal) {
    return Scaffold(
      appBar: AppBar(title: const Text("Sign-in required")),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.lock_outline, size: 48),
                const SizedBox(height: 16),
                Text(
                  "Sign-in required",
                  style: Theme.of(context).textTheme.headlineSmall,
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 8),
                Text(refusal.message, textAlign: TextAlign.center),
                const SizedBox(height: 8),
                const Text(
                  "Sign in on this device: the server settings ask for your "
                  "user name and the pairing secret the server printed at "
                  "start-up.",
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 24),
                FilledButton.icon(
                  onPressed: () => openServerSettings(context),
                  icon: const Icon(Icons.settings),
                  label: const Text("Server settings..."),
                ),
              ],
            ),
          ),
        ),
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: reload,
        tooltip: 'Reload',
        child: const Icon(Icons.update),
      ),
    );
  }

  /// The error view of a route naming something the album does not contain.
  Widget buildMessage(String message) {
    return Scaffold(
      appBar: AppBar(
        title: const Text("Virtual photo album"),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          tooltip: "Up",
          onPressed: navigator.up,
        ),
      ),
      body: Center(child: Text(message)),
    );
  }

  @override
  Widget visitListingInfo(ListingInfo self, BuildContext arg) {
    if (kDebugMode) {
      print("Rendering listing '${self.path}': ${self.title}");
    }
    return _remembersScrollOffset(ListingView(this, self));
  }

  @override
  Widget visitAlbumInfo(AlbumInfo self, BuildContext arg) {
    var current = route;
    if (current is ListingOrAlbumRoute) {
      return _remembersScrollOffset(
        AlbumContent(this, self, baseUrl, pushPart),
      );
    }

    var name = _imageName(current);
    var image = _imageByName(self)[name];
    if (image == null) {
      return buildMessage("No such image: $name");
    }

    if (current is ImageRoute) {
      // As in the GWT client: an image belonging to a group is shown as its
      // group, offering the way down to the alternatives.
      return showImageView(image.group ?? image);
    }

    var group = image.group;
    if (group == null) {
      return buildMessage("No alternatives for image: $name");
    }

    if (current is AlternativesRoute) {
      return GroupView(
        client: client,
        baseUrl: baseUrl,
        group: group,
        onUp: navigator.up,
        onShowDetail: (member) => navigator.go(
          MemberRoute(path, name, member.name),
        ),
      );
    }

    var memberName = (current as MemberRoute).member;
    var members = group.images.where((image) => image.name == memberName);
    if (members.isEmpty) {
      return buildMessage("No such image: $memberName");
    }
    var member = members.first;
    var editing = navigator.delegate.editSession(path).editMode;
    return GroupDetailView(
      client: client,
      baseUrl: baseUrl,
      image: member,
      onUp: navigator.up,
      onShowImage: (next) => navigator.go(
        MemberRoute(path, name, next.thumbnailName),
      ),
      isRepresentative: identical(group.images[group.representative], member),
      // Picking the representative is an edit of the album, saved with the
      // other edits from the album view the edit mode was entered in.
      onSetRepresentative: editing
          ? () => setState(() {
                group.representative = group.images.indexOf(member);
              })
          : null,
    );
  }

  /// The name of the image a route inside an album names.
  static String _imageName(VAlbumRoute route) => switch (route) {
        ImageRoute(name: var name) => name,
        AlternativesRoute(name: var name) => name,
        MemberRoute(name: var name) => name,
        ListingOrAlbumRoute() => "",
      };

  /// Every image of the album by its file name, group members included.
  ///
  /// The [AlbumInfo.imageByName] of the model is transient and left empty by
  /// the [AlbumInitializer], so the lookup is built here.
  static Map<String, ImagePart> _imageByName(AlbumInfo album) {
    var result = <String, ImagePart>{};
    for (var part in album.parts) {
      if (part is ImagePart) {
        result[part.name] = part;
      } else if (part is ImageGroup) {
        for (var image in part.images) {
          result[image.name] = image;
        }
      }
    }
    return result;
  }

  /// Keeps the scroll offset of the listing or album across a visit to one of
  /// its images.
  Widget _remembersScrollOffset(Widget content) => _ScrollMemory(
        delegate: navigator.delegate,
        path: path,
        child: content,
      );

  @override
  Widget visitImagePart(ImagePart self, BuildContext arg) {
    return showImageView(self);
  }

  @override
  Widget visitImageGroup(ImageGroup self, BuildContext arg) {
    return showImageView(self);
  }

  /// The viewer of the given image (a group is shown by its representative).
  ///
  /// If the image is (or belongs to) an [ImageGroup], the viewer offers the
  /// "down" chevron opening the group's alternatives view, see [showGroupView].
  Widget showImageView(AbstractImage self) => ImageView(
        client: client,
        baseUrl: baseUrl,
        image: self,
        onShowImage: showImage,
        onShowGroup: showGroupView,
        onUp: navigator.up,
        // Where a photo of one's own can be taken back out of somebody else's
        // album, see issue #53: the album this route names is the folder the
        // move is posted to.
        albumPath: path,
        onTakenBack: takenBack,
      );

  /// Leaves a photo that was taken back out of this album, see issue #53.
  ///
  /// The photo is gone from where the viewer stands, so the viewer leaves; the
  /// album and the listing showing it by an index picture are both forgotten,
  /// because either may have changed by the move.
  void takenBack() {
    var self = path;
    if (self.isNotEmpty) {
      navigator.delegate.forget(self.sublist(0, self.length - 1));
    }
    navigator.up();
    // After the way up: the album is the route now, and this is what fetches
    // it again, exactly as a move out of the album's edit mode does.
    navigator.reload();
  }

  /// Opens the "alternatives" view listing all images of the given group.
  void showGroupView(ImageGroup group) =>
      navigator.go(AlternativesRoute(path, group.thumbnailName));

  @override
  Widget visitErrorInfo(ErrorInfo self, BuildContext arg) {
    return Scaffold(
      appBar: AppBar(title: const Text("Virtual photo album")),
      body: Center(child: Text('Loading failed: ${self.message}')),
      floatingActionButton: FloatingActionButton(
        onPressed: () {},
        tooltip: 'Reload',
        child: const Icon(Icons.update),
      ), // This trailing comma makes auto-formatting nicer for build methods.
    );
  }

  @override
  Widget visitHeading(Heading self, BuildContext arg) {
    throw UnimplementedError();
  }

  void uploadImages() async {
    if (refuseWhileOffline(context)) {
      return;
    }
    ImagePicker picker = ImagePicker();
    List<XFile> files = await picker.pickMultiImage();
    if (kDebugMode) {
      print("Files picked: ${files.map((e) => e.name)}");
    }

    if (files.isEmpty) {
      return;
    }

    var uploads = [
      for (var file in files)
        UploadFile(
          name: file.name,
          length: await file.length(),
          openRead: file.openRead,
        ),
    ];

    if (!mounted) {
      if (kDebugMode) {
        print("Context was destroyed.");
      }
      return;
    }

    await uploadPicked(uploads);
  }

  /// Uploads the given files into the album that is displayed.
  ///
  /// Only contents the album does not hold yet are transferred, see
  /// [VAlbumClient.uploadNew]: an upload retried after a lost connection never
  /// creates a second copy of a photo. What happened is said on the screen —
  /// both what was uploaded and what was already there.
  Future<void> uploadPicked(List<UploadFile> uploads) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var handle = UploadHandle();
    ProgressDialog pd = ProgressDialog(context: context);
    pd.show(
      msg: "Uploading files...",
      max: 100,
      closeWithDelay: 500,
      cancel: Cancel(
        cancelClicked: () {
          if (kDebugMode) {
            print("Aborting upload.");
          }
          handle.cancel();
        },
      ),
    );

    if (kDebugMode) {
      print("Starting upload.");
    }

    var messenger = ScaffoldMessenger.of(context);
    UploadSummary summary;
    try {
      summary = await client.uploadNew(
        path,
        uploads,
        onProgress: (percent) => pd.update(value: percent),
        handle: handle,
      );
    } catch (error) {
      pd.close(delay: 500);
      // The server said why it refused; that reason belongs on the screen.
      messenger.showSnackBar(
        SnackBar(
          content: Text("Upload fehlgeschlagen: $error"),
          backgroundColor: Colors.red.shade700,
          duration: const Duration(seconds: 8),
        ),
      );
      return;
    }

    pd.close(delay: 500);

    if (kDebugMode) {
      print("Upload complete: ${summary.message}");
    }

    messenger.showSnackBar(
      SnackBar(
        content: Text(summary.message),
        duration: const Duration(seconds: 4),
      ),
    );

    reload();
  }

  /// Re-fetches the displayed resource from the server.
  void reload() => navigator.reload();

  /// Displays the given image (of the album currently loaded).
  void showImage(AbstractImage image) =>
      navigator.go(ImageRoute(path, image.thumbnailName));

  /// Opens the viewer on the given part of the album.
  Future<void> pushPart(AbstractImage image, String name) async =>
      navigator.go(ImageRoute(path, name));

  /// Descends into the child folder of the displayed listing.
  void showElement(String name) =>
      navigator.go(ListingOrAlbumRoute([...path, name]));

  /// Shows the listing or album at the given path.
  ///
  /// The path is relative to the root of the caller's space, not to the view
  /// currently shown: an album created in a folder with a placement rule is
  /// filed somewhere else entirely, and the server says where, see issue #48.
  void showPath(List<String> path) => navigator.go(ListingOrAlbumRoute(path));

  /// Shows the root listing.
  void showRoot() => navigator.home();

  /// Shows the listing containing the displayed one.
  void showParent() => navigator.up();
}

/// The state of editing one album, kept by the [VAlbumRouterDelegate] across
/// the views of the album, see [VAlbumRouterDelegate.editSession].
class AlbumEditSession {
  /// Whether the album is in the edit mode.
  bool editMode = false;

  /// The selected album parts (an [ImageGroup] is selected as a whole).
  final Set<AlbumPart> selection = {};

  /// The part clicked last, the anchor of a shift-click range selection.
  AlbumPart? lastClicked;

  /// Whether the album carries edits that have not been written back yet.
  ///
  /// Set by every edit of the album view, cleared by a successful save. It is
  /// what the "view as" preview of issue #46 asks: the preview reloads the
  /// album from the server, which would throw unsaved edits away, so a dirty
  /// album refuses the switch instead of losing them silently.
  bool dirty = false;
}

/// Restores the scroll offset the listing or album at [path] was left with.
///
/// The scroll views of the listing and the album take their controller from
/// the ambient [PrimaryScrollController], so the offset can be kept here
/// without the views knowing about it.
class _ScrollMemory extends StatefulWidget {
  final VAlbumRouterDelegate delegate;
  final List<String> path;
  final Widget child;

  const _ScrollMemory({
    required this.delegate,
    required this.path,
    required this.child,
  });

  @override
  State<_ScrollMemory> createState() => _ScrollMemoryState();
}

class _ScrollMemoryState extends State<_ScrollMemory> {
  late final ScrollController controller = ScrollController(
    initialScrollOffset: widget.delegate.scrollOffset(widget.path),
  );

  @override
  void initState() {
    super.initState();
    // The offset is remembered while scrolling, not on dispose: the scroll
    // view is disposed before its ancestors, so by then its position is gone.
    controller.addListener(_remember);
  }

  @override
  void dispose() {
    controller.removeListener(_remember);
    controller.dispose();
    super.dispose();
  }

  void _remember() {
    // `controller.offset` throws if more than one scroll view is attached.
    var positions = controller.positions;
    if (positions.length == 1) {
      widget.delegate.rememberScrollOffset(widget.path, positions.first.pixels);
    }
  }

  @override
  Widget build(BuildContext context) => PrimaryScrollController(
        controller: controller,
        // The listing and the album scroll on every platform, not only on the
        // mobile ones the primary controller is inherited on by default.
        automaticallyInheritForPlatforms: TargetPlatform.values.toSet(),
        child: widget.child,
      );
}

Widget menu(BuildContext context, List<PopupMenuEntry<Action>> entries) =>
    PopupMenuButton<Action>(
      itemBuilder: (context) => entries,
      onSelected: (action) => action(context),
    );

/// A menu entry that only tells: a label and, at the other end of the row,
/// the current state the entries below it change.
PopupMenuEntry<Action> menuLabel(String text, String value, {Key? valueKey}) =>
    PopupMenuItem<Action>(
      enabled: false,
      child: Row(
        children: [
          Flexible(child: Text(text)),
          const SizedBox(width: 16),
          Text(value, key: valueKey),
        ],
      ),
    );

PopupMenuItem<Action> menuItem(
  IconData icon,
  String text,
  Action action, {
  bool enabled = true,
}) =>
    PopupMenuItem<Action>(
      value: action,
      enabled: enabled,
      child: Row(
        children: [
          Padding(
            padding: const EdgeInsets.only(right: 16),
            child: Icon(icon, color: enabled ? Colors.blueAccent : Colors.grey),
          ),
          // Wraps instead of overflowing the menu at a long text.
          Flexible(child: Text(text)),
        ],
      ),
    );
