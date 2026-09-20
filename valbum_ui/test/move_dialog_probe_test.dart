/// Probe for #113/#114: the new album's date across a group and an undated
/// video, and the names the move posts for a group.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'move_target_kind_test.dart' as k;
import 'util/fake_image_http.dart';

const int jan1st2001 = 978307200000;

/// An album with a plain image, a group whose *second* member is the earliest
/// photo of all, and a video that carries no date.
const String probeInbox = '["AlbumInfo", {"path": "Inbox", "title": "Inbox", '
    '"subTitle": "", "rights": [{"name": "edit"}], "parts": ['
    '["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": ${k.may1st}, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY"}], '
    '["ImageGroup", {"representative": 0, "images": ['
    '{"kind": "IMAGE", "name": "g1.jpg", "date": ${k.march3rd}, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY"}, '
    '{"kind": "IMAGE", "name": "g2.jpg", "date": $jan1st2001, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY"}]}], '
    '["ImagePart", {"kind": "VIDEO", "name": "clip.mp4", "date": 0, '
    '"width": 1920, "height": 1080, "orientation": "IDENTITY"}]'
    ']}]';

http.Response answer(http.Request request) {
  if (request.method == "PUT") {
    return k.json('{"path":"2001-01-01 Probe","message":""}');
  }
  if (request.url.queryParameters["action"] == "move") {
    return k.json('{"outcomes":['
        '{"name":"a.jpg","newName":"a.jpg","message":""},'
        '{"name":"g1.jpg","newName":"g1.jpg","message":""},'
        '{"name":"clip.mp4","newName":"clip.mp4","message":""}]}');
  }
  if (k.pathOf(request) == "/valbum/data/Inbox/") {
    return k.json(probeInbox);
  }
  return k.treeAnswer(request);
}

Future<void> addToSelection(WidgetTester tester, String tileKey) async {
  var box = tester.getRect(find.byKey(ValueKey(tileKey)));
  await tester.longPressAt(Offset(box.left + 8, box.center.dy));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('the new album is dated by the earliest member of a group, an '
      'undated video not counting, and the group moves by its representative',
      (tester) async {
    var requests = <http.Request>[];
    var client = k.recordingClient(answer, requests);
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: client,
        initialRoute: const ListingOrAlbumRoute(["Inbox"]),
      ));
      await tester.pumpAndSettle();
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      await addToSelection(tester, "g1.jpg");
      await addToSelection(tester, "clip.mp4");
      await k.openPicker(tester);
      expect(find.text("Move 3 images to the top level"), findsOneWidget);
      await tester.tap(find.byKey(const Key("picker-create-album")));
      await tester.pumpAndSettle();
      expect(find.text("2001-01-01"), findsOneWidget,
          reason: "the group's second member is the earliest dated photo");
      await tester.enterText(find.byType(TextFormField).first, "Probe");
      await tester.pumpAndSettle();
      await tester.tap(find.text("Anlegen"));
      await tester.pumpAndSettle();
    });
    var put = requests.singleWhere((r) => r.method == "PUT");
    expect(Uri.decodeFull(put.url.toString()), "${k.dataUrl}/2001-01-01 Probe/");
    var post =
        requests.singleWhere((r) => r.url.queryParameters["action"] == "move");
    expect(post.body,
        '{"target":"2001-01-01 Probe","names":[{"name":"a.jpg"},{"name":"g1.jpg"},{"name":"clip.mp4"}]}');
    expect(find.textContaining("Moved 3 images to '2001-01-01 Probe'."),
        findsOneWidget);
  });
}
