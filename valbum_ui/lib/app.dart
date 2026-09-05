/// The application shell: the [VAlbumApp] widget, the [VAlbumScope] wiring and
/// the [VAlbumView] that loads a resource and dispatches to the view for its
/// type.
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

typedef Action = void Function(BuildContext context);

class VAlbumApp extends StatefulWidget {
  /// The client to talk to the server with.
  ///
  /// Defaults to a client for the server this app was loaded from, see
  /// [VAlbumClient.fromOrigin]. Tests inject a client with a fake transport.
  final VAlbumClient? client;

  const VAlbumApp({super.key, this.client});

  @override
  State<VAlbumApp> createState() => VAlbumAppState();
}

class VAlbumAppState extends State<VAlbumApp> {
  late final VAlbumClient client = widget.client ?? VAlbumClient.fromOrigin();

  // This widget is the root of your application.
  @override
  Widget build(BuildContext context) {
    return VAlbumScope(
      client: client,
      child: MaterialApp(
        title: 'Virtual Photo Album',
        theme: ThemeData(primarySwatch: Colors.blue),
        home: const VAlbumView(),
      ),
    );
  }
}

class VAlbumView extends StatefulWidget {
  /// The image to display.
  final AbstractImage? image;

  // The path of the resource to load (and then display).
  final List<String> path;

  const VAlbumView({super.key, this.path = const [], this.image});

  @override
  State<VAlbumView> createState() => VAlbumState();
}

class VAlbumState extends State<VAlbumView>
    implements ResourceVisitor<Widget, BuildContext> {
  Future<Resource?>? _resourceFuture;

  /// The transport to the album server, provided by the [VAlbumScope].
  late VAlbumClient client;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();

    client = VAlbumScope.of(context);
    if (_resourceFuture == null) {
      doLoad();
    }
  }

  void doLoad() {
    var image = widget.image;
    _resourceFuture = image == null ? load() : Future.value(image);
  }

  List<String> get path => widget.path;
  String get baseUrl => client.baseUrl(path);

  Future<Resource?> load() async {
    var resource = await client.loadResource(path);
    if (resource is AlbumInfo) {
      AlbumInitializer().init(resource);
    }
    return resource;
  }

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
        onPressed: doLoad,
        tooltip: 'Reload',
        child: const Icon(Icons.update),
      ), // This trailing comma makes auto-formatting nicer for build methods.
    );
  }

  @override
  Widget visitListingInfo(ListingInfo self, BuildContext arg) {
    if (kDebugMode) {
      print("Rendering listing '${self.path}': ${self.title}");
    }
    return ListingView(this, self);
  }

  @override
  Widget visitAlbumInfo(AlbumInfo self, BuildContext arg) {
    return AlbumContent(this, self, baseUrl, pushPart);
  }

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
      );

  /// Opens the "alternatives" view listing all images of the given group.
  void showGroupView(ImageGroup group) {
    Navigator.push(
      context,
      MaterialPageRoute<void>(
        builder: (context) =>
            GroupView(client: client, baseUrl: baseUrl, group: group),
      ),
    );
  }

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
  void reload() {
    setState(doLoad);
  }

  /// Displays the given image (of the album currently loaded) in place of the
  /// resource shown so far.
  void showImage(AbstractImage image) {
    setState(() {
      _resourceFuture = Future.value(image);
    });
  }

  Future<void> pushPart(AbstractImage image, String name) => Navigator.push(
        context,
        MaterialPageRoute(
          builder: (context) => VAlbumView(path: path, image: image),
        ),
      );

  void showElement(String name) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (context) => VAlbumView(path: [...path, name]),
      ),
    );
  }
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
