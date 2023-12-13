import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:date_field/date_field.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:image_picker/image_picker.dart';
import 'package:intl/intl.dart';
import 'package:jsontool/jsontool.dart';
import 'package:sn_progress_dialog/options/cancel.dart';
import 'package:sn_progress_dialog/progress_dialog.dart';
import 'package:valbum_ui/album_layout.dart' as layouter;
import 'resource.dart';

const String host = "http://localhost:9090/valbum/data";

void main() {
  runApp(const VAlbumApp());
}

typedef void Action(BuildContext context);

class VAlbumApp extends StatelessWidget {
  const VAlbumApp({super.key});

  // This widget is the root of your application.
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Virtual Photo Album',
      theme: ThemeData(
        primarySwatch: Colors.blue,
      ),
      home: const VAlbumView(),
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
  State<VAlbumView> createState() => _VAlbumState();
}

class _VAlbumState extends State<VAlbumView> implements ResourceVisitor<Widget, BuildContext> {
  Future<Resource?>? _resourceFuture;

  @override
  void initState() {
    super.initState();

    doLoad();
  }

  void doLoad() {
    _resourceFuture = widget.image == null ? load(widget.path) : Future.value(widget.image);
  }

  List<String> get path => widget.path;
  String get baseUrl => "$host${path.isEmpty ? "" : "/${path.join("/")}"}";

  static Future<Resource?> load(List<String> path) async {
    var pathString = path.join("/");
    var uri = "$host/${pathString.isEmpty ? "" : "$pathString/"}?type=json";
    if (kDebugMode) {
      print("Fetching: $uri");
    }
    var response = await http.get(Uri.parse(uri));
    var reader = JsonReader.fromString(response.body);
    var resource = Resource.read(reader);
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
      appBar: AppBar(
        title: const Text("Virtual photo album"),
      ),
      body: Center(
        child: Text(
          'Loading...',
        ),
      ),
    );
  }

  Widget buildError(Object? error) {
    return Scaffold(
      appBar: AppBar(
        title: const Text("Virtual photo album"),
      ),
      body: Center(
        child: Text(
          'Loading failed: ${error?.toString()}',
        ),
      ),
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
    return Scaffold(
      appBar: AppBar(
        title: Text(self.title),
        actions: <Widget>[
          menu(context, [
            menuItem(Icons.create_new_folder, 'Create album', createAlbum),
            menuItem(Icons.create_new_folder_outlined, 'Create folder', createFolder),
            menuItem(Icons.update, "Reload", (_) => reload()),
          ]),
        ],
      ),
      body: LayoutBuilder(builder: (BuildContext context, BoxConstraints constraints) {
        double imageBorder = 8;
        var preferredImageWidth = 200;
        var maxWidth = constraints.maxWidth;
        double preferredImageSpace = preferredImageWidth + 2 * imageBorder;
        double imagesPerRowFrag = maxWidth / preferredImageSpace;
        var imagesPerRow = imagesPerRowFrag.round();
        bool underflow = self.folders.length < imagesPerRow;
        double difference = underflow ? 0 : maxWidth - imagesPerRow * preferredImageSpace;
        double imageSpace = preferredImageSpace + difference / imagesPerRow;

        return SingleChildScrollView(
          scrollDirection: Axis.vertical,
          child: buildFolderList(self, imageSpace - 2 * imageBorder, imageBorder));
      }),
    );
  }

  Wrap buildFolderList(ListingInfo self, double imageWidth, double imageBorder) {
    return Wrap(
      children: self.folders.map((folder) {
        return Padding(padding: EdgeInsets.all(imageBorder),
          child: GestureDetector(
              onTap: () => Navigator.push(context, MaterialPageRoute(builder: (context) => VAlbumView(path: [...path, folder.name]))),
              child: SizedBox(width: imageWidth,
                child: Column(
                  children: [
                    Padding(
                      padding: const EdgeInsets.only(bottom: 4),
                      child: buildFolderWidget(folder, imageWidth),
                    ),
                    Text(folder.title,
                      style: const TextStyle(fontWeight: FontWeight.bold),
                      textAlign: TextAlign.center,
                    ),
                    Text(folder.subTitle,
                      textAlign: TextAlign.center,
                    )
                  ],
                ),
              )

          ),
        );
      }).toList(),
    );
  }

  Widget buildFolderWidget(FolderInfo folder, double width) {
    var indexPicture = folder.indexPicture;
    if (indexPicture == null) {
      return Container(width: width, height: width,
        decoration: BoxDecoration(borderRadius: BorderRadius.circular(5), border: Border.all(color: Colors.blue, width: 3)),
        child: Center(child: Icon(Icons.folder, size: width / 2, color: Colors.blue,)),
      );
    }

    return Image.network("$baseUrl/${folder.name}/${indexPicture.image}?type=tn",
      width: width,
      height: width,
      fit: BoxFit.cover,
    );
  }

  void createFolder(BuildContext context) async {
    ListingInfo? folder = await showGeneralDialog(
        context: context,
        pageBuilder: (context, _, __) => const CreateFolderDialog()
    );

    if (folder == null) {
      return;
    }

    await http.put(Uri.parse("$baseUrl/${folder.path}"),
        encoding: Encoding.getByName("utf-8"),
        body: folder.toString(),
        headers: {
          "Content-Type": "application/json",
        }
    );

    reload();
  }

  void createAlbum(BuildContext context) async {
    AlbumInfo? album = await showGeneralDialog(
      context: context,
      pageBuilder: (context, _, __) => const CreateAlbumDialog()
    );

    if (album == null) {
      return;
    }

    await http.put(Uri.parse("$baseUrl/${album.path}"),
        encoding: Encoding.getByName("utf-8"),
        body: album.toString(),
        headers: {
          "Content-Type": "application/json",
        }
    );

    reload();
  }

  @override
  Widget visitAlbumInfo(AlbumInfo self, BuildContext arg) {
    return Scaffold(
      appBar: AppBar(
        title: Text("${self.title} ${self.subTitle}"),
        centerTitle: true,
      ),
      body: LayoutBuilder(builder: (BuildContext context, BoxConstraints constraints) {
        var images = self.parts.whereType<AbstractImage>();

        var layout = layouter.AlbumLayout(constraints.maxWidth, 250, images);
        double pageWidth = layout.getPageWidth();

        var builder = ContentWidgetBuilder(pageWidth, "$baseUrl/${self.path}", pushPart);

        return SingleChildScrollView(
            scrollDirection: Axis.vertical,
            child: Column(
                children: layout.map((row) => row.visit(builder, 0.0)).toList(),
            ),
        );
      }),
      floatingActionButton: FloatingActionButton(
        onPressed: uploadImages,
        tooltip: 'Upload',
        child: const Icon(Icons.cloud_upload),
      ), // This trailing comma makes auto-formatting nicer for build methods.
    );
  }

  void uploadImages() async {
    ImagePicker picker = ImagePicker();
    List<XFile> files = await picker.pickMultiImage();
    if (kDebugMode) {
      print("Files picked: ${files.map((e) => e.name)}");
    }

    var uri = Uri.parse(baseUrl);

    var multipartRequest = http.MultipartRequest("PUT", uri);
    for (var file in files) {
      var multipart = http.MultipartFile.fromPath(file.name, file.path, filename: file.name);
      multipartRequest.files.add(await multipart);
    }
    var multipartStream = multipartRequest.finalize();
    var contentLength = multipartRequest.contentLength;

    var client = HttpClient();
    var put = await client.putUrl(uri);

    if (!context.mounted) {
      if (kDebugMode) {
        print("Context was destroyed.");
      }
      return;
    }

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
            put.abort();
          },
        )
    );

    put.contentLength = contentLength;
    multipartRequest.headers.forEach((key, value) => put.headers.set(key, value));

    var bytesTransferred = 0;
    await put.addStream(multipartStream.transform(StreamTransformer.fromHandlers(
      handleData: (data, sink) {
        sink.add(data);

        bytesTransferred += data.length;
        pd.update(value: (100 * bytesTransferred / contentLength).round());
        // Show progress.
      },
    )));

    if (kDebugMode) {
      print("Starting upload.");
    }

    var response = await put.close();

    if (kDebugMode) {
      print("Upload complete.");
    }

    pd.close(delay: 500);

    if (response.statusCode == 200) {
      if (kDebugMode) {
        print("Upload complete.");
      }
    } else {
      if (kDebugMode) {
        print("Upload failed: ${response.statusCode}");
      }
    }

    reload();
  }

  void reload() {
    setState(doLoad);
  }

  Future<dynamic> pushPart(AbstractImage image, String name) =>
    Navigator.push(context, MaterialPageRoute(
      builder: (context) => VAlbumView(path: path, image: image)
    )
  );

  @override
  Widget visitImagePart(ImagePart self, BuildContext arg) {
    return Scaffold(
      appBar: AppBar(
        title: Text(self.name),
        centerTitle: true,
      ),
      body: LayoutBuilder(builder: (BuildContext context, BoxConstraints constraints) {
        return CallbackShortcuts(
            bindings: <ShortcutActivator, VoidCallback> {
              const SingleActivator(LogicalKeyboardKey.arrowLeft): () => gotoPrevious(self),
              const SingleActivator(LogicalKeyboardKey.arrowRight): () => gotoNext(self),
              const SingleActivator(LogicalKeyboardKey.arrowUp): () {
                Navigator.pop(context);
              },
            },
            child: Focus(
              autofocus: true,
              child: createViewer(self, constraints),
            )
        );
      }),
    );
  }

  InteractiveViewer createViewer(ImagePart self, BoxConstraints constraints) {
    var tx = TransformationController();

    return InteractiveViewer(
      panEnabled: true,
      minScale: 0.25,
      maxScale: 4,
      transformationController: tx,
      onInteractionEnd: (details) {
        if (details.pointerCount > 1 || tx.value.hasScale()) {
          return;
        }
        if (details.velocity.pixelsPerSecond.distance > 50) {
          var primaryVelocity = details.velocity.pixelsPerSecond.dx;
          if (kDebugMode) {
            print(details);
          }
          if (primaryVelocity > 40) {
            gotoPrevious(self);
          }
          // Swiping to the left.
          else if (primaryVelocity < 40) {
            gotoNext(self);
          }
        }
      },

      // TODO: Support video playback
      child: Image.network("$baseUrl/${self.name}${self.kind == ImageKind.video ? "?type=tn" : ""}",
        width: constraints.maxWidth,
        height: constraints.maxHeight,
        fit: BoxFit.contain,
      )
    );
  }

  void gotoPrevious(AbstractImage self) {
    if (self.previous != null) {
      setState(() {
        _resourceFuture = Future.value(self.previous);
      });
    }
  }

  void gotoNext(AbstractImage self) {
    if (self.next != null) {
      setState(() {
        _resourceFuture = Future.value(self.next);
      });
    }
  }

  @override
  Widget visitImageGroup(ImageGroup self, BuildContext arg) {
    // TODO: implement visitImageGroup
    throw UnimplementedError();
  }

  @override
  Widget visitErrorInfo(ErrorInfo self, BuildContext arg) {
    return Scaffold(
      appBar: AppBar(
        title: const Text("Virtual photo album"),
      ),
      body: Center(
        child: Text(
          'Loading failed: ${self.message}',
        ),
      ),
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
}

class CreateAlbumDialog extends StatefulWidget {
  const CreateAlbumDialog({super.key});

  @override
  State<StatefulWidget> createState() => CreateAlbumDialogState();
}

class CreateAlbumDialogState extends State<CreateAlbumDialog> {
  var formKey = GlobalKey<FormState>();
  String? albumTitle;
  String? albumSubTitle;
  DateTime? albumDate;

  @override
  Widget build(BuildContext context) {
    var now = DateTime.now();

    return Dialog(
      child: Form(
        key: formKey,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              DefaultTextStyle(
                style: DialogTheme.of(context).titleTextStyle ?? Theme.of(context).textTheme.titleLarge!,
                child: Semantics(
                  // For iOS platform, the focus always lands on the title.
                  // Set nameRoute to false to avoid title being announced twice.
                  namesRoute: Theme.of(context).platform != TargetPlatform.iOS,
                  container: true,
                  child: Text("Neues Album"),
                ),
              ),
              DateTimeFormField(
                mode: DateTimeFieldPickerMode.date,
                firstDate: DateTime(1900),
                lastDate: now,
                initialDate: now,
                onSaved: (value) => albumDate = value,
                dateFormat: DateFormat("yyyy-MM-dd"),
                validator: (value) {
                  return value == null ? "Muss angegeben werden." : null;
                },
                decoration: InputDecoration(
                    label: Text("Datum"),
                    suffixIcon: Icon(Icons.date_range)
                ),
              ),
              TextFormField(
                decoration: InputDecoration(
                  label: Text("Titel"),
                ),
                onSaved: (value) => albumTitle = value,
                validator: (String? value) {
                  return value == null || value.isEmpty ? "Darf nicht leer sein" : null;
                },
              ),
              TextFormField(
                decoration: InputDecoration(label: Text("Untertitel")),
                onSaved: (value) => albumSubTitle = value,
              ),
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    ElevatedButton.icon(
                      icon: const Icon(Icons.check),
                      label: Text("Anlegen"),
                      onPressed: createPressed,
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  void createPressed() {
    var formState = formKey.currentState;
    if (!formState!.validate()) {
      return;
    }

    formState.save();

    var title = albumTitle!;
    var date = albumDate;

    var info = AlbumInfo(
      title: title,
      subTitle: albumSubTitle ?? "",
      path: (date != null ? DateFormat("yyyy-MM-dd ").format(date) : "") + title,
    );

    Navigator.of(context).pop(info);
  }
}

class CreateFolderDialog extends StatefulWidget {
  const CreateFolderDialog({super.key});

  @override
  State<StatefulWidget> createState() => CreateFolderDialogState();
}

class CreateFolderDialogState extends State<CreateFolderDialog> {
  var formKey = GlobalKey<FormState>();
  String? folderName;

  @override
  Widget build(BuildContext context) {
    var now = DateTime.now();

    return Dialog(
      child: Form(
        key: formKey,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              DefaultTextStyle(
                style: DialogTheme.of(context).titleTextStyle ?? Theme.of(context).textTheme.titleLarge!,
                child: Semantics(
                  // For iOS platform, the focus always lands on the title.
                  // Set nameRoute to false to avoid title being announced twice.
                  namesRoute: Theme.of(context).platform != TargetPlatform.iOS,
                  container: true,
                  child: Text("Neuer Ordner"),
                ),
              ),
              TextFormField(
                decoration: InputDecoration(
                  label: Text("Name"),
                ),
                onSaved: (value) => folderName = value,
                validator: (String? value) {
                  return value == null || value.isEmpty ? "Darf nicht leer sein" : null;
                },
              ),
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    ElevatedButton.icon(
                      icon: const Icon(Icons.check),
                      label: Text("Anlegen"),
                      onPressed: createPressed,
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  void createPressed() {
    var formState = formKey.currentState;
    if (!formState!.validate()) {
      return;
    }

    formState.save();

    var name = folderName!;

    var info = ListingInfo(
      title: name,
      path: name,
    );

    Navigator.of(context).pop(info);
  }
}

extension AlmostIdentity on Matrix4 {
  bool hasScale() {
    return (row0.x - 1).abs() + (row1.y - 1).abs() + (row2.z - 1).abs() > 0.001;
  }
}

class ContentWidgetBuilder implements layouter.ContentVisitor<Widget, double> {
  final double pageWidth;
  final String albumUrl;
  final Future Function(AbstractImage, String) pushPart;
  
  const ContentWidgetBuilder(this.pageWidth, this.albumUrl, this.pushPart);

  @override
  Widget visitImg(layouter.Img content, double rowHeight) {
    var image = content.getImage();
    
    var width = content.getUnitWidth() * rowHeight;
    var height = rowHeight;

    return image.visitAbstractImage(ImageWidgetBuilder(albumUrl, width, height, pushPart), null);
  }

  @override
  Widget visitRow(layouter.Row content, double rowHeight) {
    double scale = pageWidth / content.getUnitWidth();
    double rowHeight = scale;

    return Row(
      children: content.map((content) => content.visit(this, rowHeight)).toList(),
    );
  }

  @override
  Widget visitDoubleRow(layouter.DoubleRow content, double rowHeight) {
    var width = content.getUnitWidth() * rowHeight;
    var height = rowHeight;

    var upper = content.getUpper();
    var contentBuilder = ContentWidgetBuilder(width, albumUrl, pushPart);
    var upperRow = upper.visit(contentBuilder, rowHeight * content.getH1());

    var lower = content.getLower();
    var lowerRow = lower.visit(contentBuilder, rowHeight * content.getH2());

    return Column(children: [upperRow, lowerRow],);
  }

  @override
  Widget visitPadding(layouter.Padding content, double rowHeight) {
    var width = content.getUnitWidth() * rowHeight;
    var height = rowHeight;

    return SizedBox(width: width, height: height);
  }
}

class ImageWidgetBuilder implements AbstractImageVisitor<Widget, void> {
  final String albumUrl;
  final double width, height;
  final Future Function(AbstractImage, String) pushPart;
  
  const ImageWidgetBuilder(this.albumUrl, this.width, this.height, this.pushPart);
  
  @override
  Widget visitImageGroup(ImageGroup self, void arg) {
    var image = self.images[self.representative];
    var partName = image.name;
    return GestureDetector(
        onTap: () => pushPart(image, partName),
        child: Image.network("$albumUrl$partName?type=tn",
          width: width,
          height: height,
          fit: BoxFit.contain,
        )
    );
  }

  @override
  Widget visitImagePart(ImagePart self, void arg) {
    return GestureDetector(
        onTap: () => pushPart(self, self.name),
        child: Image.network("$albumUrl${self.name}?type=tn",
          width: width,
          height: height,
          fit: BoxFit.contain,
        )
    );
  }
}

enum Direction {
  previous, next;
}

/// Initializes [AbstractImage.next] and [AbstractImage.previous] fields.
class AlbumInitializer implements AlbumPartVisitor<AbstractImage?, Direction> {
  AbstractImage? previous;
  AbstractImage? next;

  void init(AlbumInfo self) {
    for (var part in self.parts) {
      AbstractImage? self = part.visitAlbumPart(this, Direction.previous);
      if (self != null) previous = self;
    }
    for (var part in self.parts.reversed) {
      AbstractImage? self = part.visitAlbumPart(this, Direction.next);
      if (self != null) next = self;
    }
  }

  @override
  AbstractImage? visitHeading(Heading self, Direction arg) {
    return null;
  }

  @override
  AbstractImage? visitImageGroup(ImageGroup self, Direction arg) {
    return initImage(arg, self);
  }

  @override
  AbstractImage? visitImagePart(ImagePart self, Direction arg) {
    return initImage(arg, self);
  }

  AbstractImage initImage(Direction arg, AbstractImage self) {
    if (arg == Direction.previous) {
      self.previous = previous;
    } else {
      self.next = next;
    }
    return self;
  }

}

Widget menu(BuildContext context, List<PopupMenuItem<Action>> entries) => PopupMenuButton<Action>(
  itemBuilder: (context) => entries,
  onSelected: (action) => action(context),
);

PopupMenuItem<Action> menuItem(IconData icon, String text, Action action) => PopupMenuItem<Action>(
  value: action,
  child: Row(
    children: [
      Padding(
        padding: const EdgeInsets.only(right: 16),
        child: Icon(icon, color: Colors.blueAccent)),
      Text(text)
    ]
  )
);
