import 'dart:js_util';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
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
      title: 'Flutter Demo',
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
      body: Wrap(
        children: self.folders.map((folder) {
          return Padding(padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
            child: GestureDetector(
                onTap: () => Navigator.push(context, MaterialPageRoute(builder: (context) => VAlbumView(path: [...path, folder.name]))),
                child: Column(
                  children: [
                    Padding(
                      padding: const EdgeInsets.only(bottom: 4),
                      child: Image.network("$baseUrl/${folder.name}/${folder.indexPicture!.image}",
                        width: 300,
                        height: 300,
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
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: () {},
        tooltip: 'Add',
        child: const Icon(Icons.add),
      ), // This trailing comma makes auto-formatting nicer for build methods.
    );
  }

  @override
  Widget visitAlbumInfo(AlbumInfo self, BuildContext arg) {
    if (kDebugMode) {
      print("Rendering album '${self.path}': ${self.title}");
    }
    return Scaffold(
      appBar: AppBar(
        title: Text(self.title),
      ),
      body: Wrap(
        children: self.parts.where((part) => part is ImagePart).map((part) => part as ImagePart).map((part) {
          return Padding(padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
            child: GestureDetector(
                child: Column(
                  children: [
                    Padding(
                      padding: const EdgeInsets.only(bottom: 4),
                      child: Image.network("$baseUrl/${part.name}",
                        width: 300,
                        height: 300,
                        fit: BoxFit.cover,
                      ),
                    ),
                    Text(part.name, style: const TextStyle(fontWeight: FontWeight.bold),),
                  ],
                )
            ),
          );
        }).toList(),
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: () {},
        tooltip: 'Add',
        child: const Icon(Icons.add),
      ), // This trailing comma makes auto-formatting nicer for build methods.
    );
  }

  @override
  Widget visitHeading(Heading self, BuildContext arg) {
    // TODO: implement visitHeading
    throw UnimplementedError();
  }

  @override
  Widget visitImageGroup(ImageGroup self, BuildContext arg) {
    // TODO: implement visitImageGroup
    throw UnimplementedError();
  }

  @override
  Widget visitImagePart(ImagePart self, BuildContext arg) {
    // TODO: implement visitImagePart
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
}
