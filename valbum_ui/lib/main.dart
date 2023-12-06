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
      home: const VAlbumHome(),
    );
  }
}

class VAlbumHome extends StatefulWidget {
  const VAlbumHome({super.key});

  @override
  State<VAlbumHome> createState() => _VAlbumState();
}

class _VAlbumState extends State<VAlbumHome> implements ResourceVisitor<Widget, BuildContext> {
  Future<Resource?> _resourceFuture = load();

  static Future<Resource?> load() async {
    var response = await http.get(Uri.parse("http://localhost:9090/valbum/data/?type=json"));
    var reader = JsonReader.fromString(response.body);
    var resource = Resource.read(reader);
    return resource;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text("Virtual photo album"),
      ),
      body: FutureBuilder<Resource?>(
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
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: () {},
        tooltip: 'Add Album',
        child: const Icon(Icons.add),
      ), // This trailing comma makes auto-formatting nicer for build methods.
    );
  }

  Center buildLoading() {
    return Center(
      child: Text(
        'Loading...',
      ),
    );
  }

  Center buildError() {
    return Center(
      child: Text(
        'Loading failed.',
      ),
    );
  }

  @override
  Widget visitAlbumInfo(AlbumInfo self, BuildContext arg) {
    // TODO: implement visitAlbumInfo
    throw UnimplementedError();
  }

  @override
  Widget visitErrorInfo(ErrorInfo self, BuildContext arg) {
    return Center(
      child: Text(
        'Loading failed: ${self.message}',
      ),
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
  Widget visitListingInfo(ListingInfo self, BuildContext arg) {
    return Column(
      children: [
        Text(self.title),
        Wrap(
          children: self.folders.map((folder) {
            return Column(
              children: [
                Image.network("http://localhost:9090/valbum/data/" + folder.name + "/" + folder.indexPicture!.image), 
                Text(folder.title),
                Text(folder.subTitle)
              ],
            );}).toList(),
        )
      ],
    );
  }
}
