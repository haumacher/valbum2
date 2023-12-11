import 'dart:js_util';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/album_layout.dart' as layouter;
import 'resource.dart';

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
  final List<String> path;

  const VAlbumView({super.key, this.path = const []});

  @override
  State<VAlbumView> createState() => _VAlbumState();
}

class _VAlbumState extends State<VAlbumView> implements ResourceVisitor<Widget, BuildContext> {
  Future<Resource?>? _resourceFuture;

  @override
  void initState() {
    super.initState();

    _resourceFuture = load(widget.path);
  }

  List<String> get path => widget.path;
  String get baseUrl => "http://localhost:9090/valbum/data${path.isEmpty ? "" : "/${path.join("/")}"}";

  static Future<Resource?> load(List<String> path) async {
    var pathString = path.join("/");
    if (kDebugMode) {
      print("Fetching data: '$pathString'");
    }
    var response = await http.get(Uri.parse("http://localhost:9090/valbum/data/$pathString?type=json"));
    var reader = JsonReader.fromString(response.body);
    var resource = Resource.read(reader);
    return resource;
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<Resource?>(
      future: _resourceFuture,
      builder: (BuildContext context, AsyncSnapshot<Resource?> snapshot) {
        if (snapshot.hasError) {
          return buildError();
        } else if (snapshot.hasData) {
          var resource = snapshot.data;
          if (resource == null) {
            return buildError();
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

  Widget buildError() {
    return Scaffold(
      appBar: AppBar(
        title: const Text("Virtual photo album"),
      ),
      body: Center(
        child: Text(
          'Loading failed.',
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
                    child: Image.network("$baseUrl/${folder.name}/${folder.indexPicture!.image}",
                      width: imageWidth,
                      height: imageWidth,
                      fit: BoxFit.cover,
                    ),
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

  @override
  Widget visitAlbumInfo(AlbumInfo self, BuildContext arg) {
    if (kDebugMode) {
      print("Rendering album '${self.path}': ${self.title}");
    }
    
    var albumUrl = "$baseUrl/${self.path}";
    
    return Scaffold(
      appBar: AppBar(
        title: Text("${self.title} ${self.subTitle}"),
        centerTitle: true,
      ),
      body: LayoutBuilder(builder: (BuildContext context, BoxConstraints constraints) {
        var images = self.parts.whereType<AbstractImage>();

        var layout = layouter.AlbumLayout(constraints.maxWidth, 250, images);
        double pageWidth = layout.getPageWidth();

        var builder = ContentWidgetBuilder(pageWidth, albumUrl);

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

  Wrap imageList(Iterable<ImagePart> images, double imageWidth, double imageBorder) {
    return Wrap(
      children: images.map((part) {
        return Padding(padding: EdgeInsets.symmetric(horizontal: imageBorder, vertical: imageBorder),
          child: GestureDetector(
              onTap: () => Navigator.push(context, MaterialPageRoute(builder: (context) => VAlbumView(path: [...path, part.name]))),
              child: Column(
                children: [
                  Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Image.network("$baseUrl/${part.name}",
                      width: imageWidth,
                      height: imageWidth,
                      fit: BoxFit.cover,
                    ),
                  ),
                  Text(part.name, style: const TextStyle(fontWeight: FontWeight.bold),),
                ],
              )
          ),
        );
      }).toList(),
    );
  }

  @override
  Widget visitImagePart(ImagePart self, BuildContext arg) {
    return Scaffold(
      appBar: AppBar(
        title: Text(self.name),
        centerTitle: true,
      ),
      body: LayoutBuilder(builder: (BuildContext context, BoxConstraints constraints) {
        return InteractiveViewer(
          panEnabled: true,
          minScale: 0.25,
          maxScale: 4,
          child: Image.network(baseUrl,
            width: constraints.maxWidth,
            height: constraints.maxHeight,
            fit: BoxFit.contain,
          )
        );
      }),
      floatingActionButton: FloatingActionButton(
        onPressed: () {},
        tooltip: 'Add',
        child: const Icon(Icons.add),
      ), // This trailing comma makes auto-formatting nicer for build methods.
    );
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

class ContentWidgetBuilder implements layouter.ContentVisitor<Widget, double> {
  final double pageWidth;
  final String albumUrl;
  
  const ContentWidgetBuilder(this.pageWidth, this.albumUrl);

  @override
  Widget visitImg(layouter.Img content, double rowHeight) {
    var image = content.getImage();
    
    var width = content.getUnitWidth() * rowHeight;
    var height = rowHeight;
    
    return image.visitAbstractImage(ImageWidgetBuilder(albumUrl, width, height), null);
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
    var upperRow = upper.visit(ContentWidgetBuilder(width, albumUrl), rowHeight * content.getH1());

    var lower = content.getLower();
    var lowerRow = lower.visit(ContentWidgetBuilder(width, albumUrl), rowHeight * content.getH2());

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
  
  const ImageWidgetBuilder(this.albumUrl, this.width, this.height);
  
  @override
  Widget visitImageGroup(ImageGroup self, void arg) {
    return Image.network(albumUrl + self.images[self.representative].name,
      width: width,
      height: height,
      fit: BoxFit.contain,
    );
  }

  @override
  Widget visitImagePart(ImagePart self, void arg) {
    return Image.network(albumUrl + self.name,
      width: width,
      height: height,
      fit: BoxFit.contain,
    );
  }
}