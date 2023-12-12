import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/album_layout.dart' as layouter;
import 'resource.dart';

const String host = "http://localhost:9090/valbum/data";

void main() {
  runApp(const VAlbumApp());
}

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

    _resourceFuture = widget.image == null ? load(widget.path) : Future.value(widget.image);
  }

  List<String> get path => widget.path;
  String get baseUrl => "$host/${path.isEmpty ? "" : "${path.join("/")}"}";

  static Future<Resource?> load(List<String> path) async {
    var pathString = path.join("/");
    if (kDebugMode) {
      print("Fetching data: '$pathString'");
    }
    var response = await http.get(Uri.parse("$host/$pathString?type=json"));
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
        onPressed: () {},
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
      ),
      body: LayoutBuilder(builder: (BuildContext context, BoxConstraints constraints) {
        double imageBorder = 8;
        var preferredImageWidth = 300;
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
      floatingActionButton: FloatingActionButton(
        onPressed: () {},
        tooltip: 'Add',
        child: const Icon(Icons.add),
      ), // This trailing comma makes auto-formatting nicer for build methods.
    );
  }

  Wrap buildFolderList(ListingInfo self, double imageWidth, double imageBorder) {
    return Wrap(
      children: self.folders.map((folder) {
        return Padding(padding: EdgeInsets.all(imageBorder),
          child: GestureDetector(
              onTap: () => Navigator.push(context, MaterialPageRoute(builder: (context) => VAlbumView(path: [...path, folder.name]))),
              child: Column(
                children: [
                  Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: buildFolderWidget(folder, imageWidth),
                  ),
                  Text(folder.title, style: const TextStyle(fontWeight: FontWeight.bold),),
                  Text(folder.subTitle)
                ],
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

    return Image.network("$baseUrl/${folder.name}/${indexPicture!.image}",
      width: width,
      height: width,
      fit: BoxFit.cover,
    );
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
                children: layout.map((row) => row.visit(builder, 0)).toList(),
            ),
        );
      }),
      floatingActionButton: FloatingActionButton(
        onPressed: () {},
        tooltip: 'Add',
        child: const Icon(Icons.add),
      ), // This trailing comma makes auto-formatting nicer for build methods.
    );
  }

  Future<dynamic> pushPart(AbstractImage image, String name) =>  Navigator.push(context, MaterialPageRoute(builder: (context) => VAlbumView(path: path, image: image)));

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
      child: Image.network("$baseUrl/${self.name}",
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
        child: Image.network(albumUrl + partName,
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
        child: Image.network(albumUrl + self.name,
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