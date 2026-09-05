/// The application shell: the [VAlbumApp] widget, the router wiring
/// ([VAlbumRouterDelegate], [VAlbumRouteInformationParser], [VAlbumNavigator])
/// and the [VAlbumView] that loads the resource a route lives in and
/// dispatches to the view of that route.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:sn_progress_dialog/options/cancel.dart';
import 'package:sn_progress_dialog/progress_dialog.dart';

import 'album_model.dart';
import 'album_view.dart';
import 'client.dart';
import 'group_view.dart';
import 'image_view.dart';
import 'listing_view.dart';
import 'resource.dart';
import 'routes.dart';
import 'urls.dart';

typedef Action = void Function(BuildContext context);

class VAlbumApp extends StatefulWidget {
  /// The client to talk to the server with.
  ///
  /// Defaults to a client for the server this app was loaded from, see
  /// [VAlbumClient.fromOrigin]. Tests inject a client with a fake transport.
  final VAlbumClient? client;

  /// The route to start at, instead of the location the app was opened with.
  ///
  /// Tests use it to pump the app deep-linked into an album or an image.
  final VAlbumRoute? initialRoute;

  const VAlbumApp({super.key, this.client, this.initialRoute});

  @override
  State<VAlbumApp> createState() => VAlbumAppState();
}

class VAlbumAppState extends State<VAlbumApp> {
  /// The client the app talks to the server with.
  ///
  /// Unlike [VAlbumClient.fromOrigin], the data URL is derived from the app
  /// base rather than from the document location: with the path URL strategy
  /// the location is the *view*, not the directory the app was loaded from,
  /// see [appBasePath].
  late final VAlbumClient client = widget.client ??
      VAlbumClient(
        dataUrl: deriveDataUrl(
          Uri.base,
          isWeb: kIsWeb,
          basePath: kIsWeb
              ? appBasePath(
                  Uri.base.path,
                  WidgetsBinding.instance.platformDispatcher.defaultRouteName,
                )
              : null,
        ),
      );

  /// The router state: which view the app shows, see [VAlbumRoute].
  late final VAlbumRouterDelegate router = VAlbumRouterDelegate(
    client: client,
    initialRoute: widget.initialRoute ?? ListingOrAlbumRoute.root,
  );

  @override
  void dispose() {
    router.dispose();
    super.dispose();
  }

  // This widget is the root of your application.
  @override
  Widget build(BuildContext context) {
    return VAlbumScope(
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
  /// The transport to the album server.
  final VAlbumClient client;

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
    required this.client,
    required VAlbumRoute initialRoute,
  }) : _route = initialRoute;

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

  Widget buildError(Object? error) {
    return Scaffold(
      appBar: AppBar(title: const Text("Virtual photo album")),
      body: Center(child: Text('Loading failed: ${error?.toString()}')),
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

    var status = await client.uploadFiles(
      baseUrl,
      uploads,
      onProgress: (percent) => pd.update(value: percent),
      handle: handle,
    );

    pd.close(delay: 500);

    if (kDebugMode) {
      print(status == 200 ? "Upload complete." : "Upload failed: $status");
    }

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
