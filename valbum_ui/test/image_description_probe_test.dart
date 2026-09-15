/// Probe for the viewer's description edit (issue #80): the direct write must
/// carry every other stored value of the album unchanged, and a member of a
/// group keeps its group.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/image_view.dart';
import 'package:valbum_ui/resource.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'util/fake_image_http.dart';
import 'util/fake_video_player.dart';
import 'util/fixtures.dart';

const List<String> albumPath = ["album"];
const String albumUrl = "http://server/valbum/data/album";

AlbumInfo linkedAlbum(List<AlbumPart> parts) {
  var album = AlbumInfo(
    title: "Probe",
    subTitle: "Sub",
    date: 12345,
    parts: parts,
    rights: [RightName(name: "view"), RightName(name: "edit")],
  );
  for (var part in parts) {
    part.owner = album;
    if (part is ImageGroup) {
      for (var member in part.images) {
        member.owner = album;
        member.group = part;
      }
    }
  }
  return album;
}

void main() {
  setUp(() {
    VideoPlayerPlatform.instance = FakeVideoPlayerPlatform();
  });

  testWidgets('the direct write keeps dates, cameras, ratings, privacy, headings and groups',
      (tester) async {
    var member = ImagePart(name: "m.jpg", width: 2000, height: 1000, date: 7, camera: "Canon EOS 5D", rating: 2, privacy: 1, comment: "old");
    var sibling = ImagePart(name: "s.jpg", width: 2000, height: 1000, date: 8, camera: "Canon EOS 5D", comment: "sib");
    var group = ImageGroup(images: [member, sibling], representative: 1);
    var solo = ImagePart(name: "a.jpg", width: 2000, height: 1000, date: 5, privacy: 2, rating: -1, comment: "solo");
    var album = linkedAlbum([Heading(text: "Start"), solo, group, Heading(text: "End")]);

    var puts = <http.Request>[];
    var client = VAlbumClient(
      dataUrl: "http://server/valbum/data",
      httpClient: MockClient(servingThumbnails((request) async {
        if (request.method == "PUT") puts.add(request);
        return http.Response("{}", 200, headers: const {"content-type": "application/json"});
      })),
    );

    await withFakeImageHttp(() async {
      await tester.pumpWidget(MaterialApp(
        home: ImageView(
          client: client,
          baseUrl: albumUrl,
          image: member,
          onShowImage: (next) {},
          editPath: albumPath,
        ),
      ));
      await tester.pumpAndSettle();
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), "new words");
      await tester.pumpAndSettle();
      await tester.tap(find.text("Übernehmen"));
      await tester.pumpAndSettle();
    });

    expect(puts, hasLength(1));
    expect(puts.single.url.toString(), startsWith("$albumUrl/"));
    var written = Resource.fromString(puts.single.body) as AlbumInfo;
    expect(written.title, "Probe");
    expect(written.subTitle, "Sub");
    expect(written.date, 12345);
    expect(written.parts.map((p) => p.runtimeType.toString()).toList(),
        ["Heading", "ImagePart", "ImageGroup", "Heading"]);
    var writtenGroup = written.parts[2] as ImageGroup;
    expect(writtenGroup.representative, 1);
    var m = writtenGroup.images[0];
    expect(m.comment, "new words");
    expect(m.date, 7);
    expect(m.camera, "Canon EOS 5D");
    expect(m.rating, 2);
    expect(m.privacy, 1);
    expect(writtenGroup.images[1].comment, "sib");
    var a = written.parts[1] as ImagePart;
    expect(a.comment, "solo");
    expect(a.privacy, 2);
    expect(a.rating, -1);
    expect(a.date, 5);
    // Derived values are not written.
    expect(written.effectiveDate, 0);
    expect(album.parts.length, 4, reason: "the model kept its shape");
  });
}
