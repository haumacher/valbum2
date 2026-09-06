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
import 'camera_roll.dart';
import 'camera_roll_view.dart';
import 'client.dart';
import 'group_view.dart';
import 'image_view.dart';
import 'listing_view.dart';
import 'offline.dart';
import 'photo_library.dart';
import 'platform.dart';
import 'resource.dart';
import 'routes.dart';
import 'settings.dart';
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

  const VAlbumApp({
    super.key,
    this.client,
    this.settings,
    this.initialRoute,
    this.cache,
    this.offlineState,
    this.photoLibrary,
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

  /// The camera-roll sync (issue #30), driven by the photo library and the
  /// current client.
  ///
  /// It uses the *current* client (a function, not a value): pointing the app
  /// at another server or pairing this device swaps the client, and the next
  /// run must go to the new one. Nothing runs until the stored configuration
  /// says the user switched the sync on.
  late final CameraRollSync cameraRoll = CameraRollSync(
    store: settings.store,
    library: photoLibrary,
    clientOf: () => client,
    isOffline: () => offlineState.offline,
  );

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
    return ServerSettings(
      store: const PreferencesSettingsStore(),
      platformDefault: () => kIsWeb ? _originDataUrl() : null,
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

  /// Builds a client for the given data URL over this app's transport.
  ///
  /// The client carries the token this device is paired with the server as, so
  /// that every request of the app identifies itself, see [ServerSettings].
  VAlbumClient clientFor(String dataUrl) => VAlbumClient(
        dataUrl: dataUrl,
        token: settings.token,
        httpClient: _transport,
        cache: cache,
        offlineState: offlineState,
      );

  @override
  void initState() {
    super.initState();
    settings.addListener(_settingsChanged);
    _syncClient();
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

  @override
  void dispose() {
    settings.removeListener(_settingsChanged);
    cameraRoll.dispose();
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
    super.dispose();
  }

  /// The server changed: swap the client and reload what is shown.
  void _settingsChanged() => setState(_syncClient);

  void _syncClient() {
    var dataUrl = settings.dataUrl;
    if (dataUrl == null) {
      client = null;
      return;
    }
    if (client?.dataUrl == dataUrl && client?.token == settings.token) {
      return;
    }
    var next = clientFor(dataUrl);
    client = next;
    // Drops every resource and scroll offset of the previous server and
    // re-runs the load of the current route.
    _router?.client = next;
    // A server or token that changed is a reason to sync again: what the
    // previous server refused, the new one (or the pairing) may accept.
    if (cameraRoll.loaded && cameraRoll.config.enabled) {
      cameraRoll.trigger();
    }
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
          child: !settings.loaded
              ? _splash()
              : client == null
                  ? _serverSetup()
                  : _albumApp(client!),
        ),
      ),
    );
  }

  /// Shown while the stored server URL is being read.
  Widget _splash() => MaterialApp(
        title: 'Virtual Photo Album',
        theme: ThemeData(primarySwatch: Colors.blue),
        home: const Scaffold(body: Center(child: CircularProgressIndicator())),
      );

  /// Shown when no server is configured: there is nothing else to show.
  Widget _serverSetup() => MaterialApp(
        title: 'Virtual Photo Album',
        theme: ThemeData(primarySwatch: Colors.blue),
        home: ServerSettingsScreen(
          settings: settings,
          clientFor: clientFor,
          closable: false,
        ),
      );

  Widget _albumApp(VAlbumClient client) => VAlbumScope(
        client: client,
        child: MaterialApp.router(
          title: 'Virtual Photo Album',
          theme: ThemeData(primarySwatch: Colors.blue),
          routerDelegate: router,
          routeInformationParser: const VAlbumRouteInformationParser(),
          routeInformationProvider: widget.initialRoute == null
              ? null
              : PlatformRouteInformationProvider(
                  initialRouteInformation: RouteInformation(
                    uri: routeToUri(widget.initialRoute!),
                  ),
                ),
        ),
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

  VAlbumRoute _route;

  /// Incremented by [reload], so that the view re-runs its load.
  int _version = 0;

  VAlbumRouterDelegate({
    required VAlbumClient client,
    required VAlbumRoute initialRoute,
  })  : _client = client,
        _route = initialRoute;

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
    _version++;
    notifyListeners();
  }

  /// The view currently shown.
  VAlbumRoute get route => _route;

  @override
  VAlbumRoute get currentConfiguration => _route;

  @override
  Future<void> setNewRoutePath(VAlbumRoute configuration) {
    _route = configuration;
    return SynchronousFuture(null);
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
  /// A server refusing an unpaired device answers with the reason it refuses;
  /// that reason names a remedy the user reaches from here, so the view offers
  /// the way to the server settings alongside the retry.
  Widget buildError(Object? error) {
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

  /// The error view of a route naming something the album does not contain.
  Widget buildMessage(String message) {
    return Scaffold(
      appBar: AppBar(
        title: const Text("Virtual photo album"),
        leading: IconButton(
          icon: const Icon(Icons.arrow_upward),
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
    return GroupDetailView(
      client: client,
      baseUrl: baseUrl,
      image: member,
      onUp: navigator.up,
      onShowImage: (next) => navigator.go(
        MemberRoute(path, name, next.thumbnailName),
      ),
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
      );

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

  /// Shows the root listing.
  void showRoot() => navigator.home();

  /// Shows the listing containing the displayed one.
  void showParent() => navigator.up();
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

Widget menu(BuildContext context, List<PopupMenuItem<Action>> entries) =>
    PopupMenuButton<Action>(
      itemBuilder: (context) => entries,
      onSelected: (action) => action(context),
    );

PopupMenuItem<Action> menuItem(IconData icon, String text, Action action) =>
    PopupMenuItem<Action>(
      value: action,
      child: Row(
        children: [
          Padding(
            padding: const EdgeInsets.only(right: 16),
            child: Icon(icon, color: Colors.blueAccent),
          ),
          Text(text),
        ],
      ),
    );
