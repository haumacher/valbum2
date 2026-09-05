import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

// Probe review for issue #16: the save must carry the whole album, in order,
// including parts the editor cannot touch yet, and survive awkward text.
void main() {
  testWidgets('saving preserves heading and group order and awkward titles',
      (tester) async {
    var requests = <http.Request>[];
    var client = clientHandling(
      (r) => http.Response(
        r.method == "PUT" ? "" : fixture("album.json"),
        200,
        headers: {"content-type": "application/json"},
      ),
      requests: requests,
    );
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client));
      await tester.pumpAndSettle();
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      await tester.tap(find.byIcon(Icons.tune));
      await tester.pumpAndSettle();
      var fields = find.byType(TextField);
      await tester.enterText(fields.at(0), 'Grüße "aus" Karlsruhe \\ 2002');
      await tester.enterText(fields.at(1), '');
      await tester.tap(find.text('Übernehmen'));
      await tester.pumpAndSettle();
      await tester.tap(find.byIcon(Icons.save));
      await tester.pumpAndSettle();
    });

    var puts = requests.where((r) => r.method == "PUT").toList();
    expect(puts, hasLength(1));
    var saved = Resource.read(JsonReader.fromString(puts.single.body))
        as AlbumInfo;
    var original = Resource.read(JsonReader.fromString(fixture("album.json")))
        as AlbumInfo;
    expect(saved.title, 'Grüße "aus" Karlsruhe \\ 2002');
    expect(saved.subTitle, '');
    expect(saved.parts.length, original.parts.length);
    for (var i = 0; i < original.parts.length; i++) {
      expect(saved.parts[i].runtimeType, original.parts[i].runtimeType,
          reason: "part $i");
    }
    var group = saved.parts.whereType<ImageGroup>().single;
    var origGroup = original.parts.whereType<ImageGroup>().single;
    expect(group.representative, origGroup.representative);
    expect(group.images.map((i) => i.name), origGroup.images.map((i) => i.name));
    expect(saved.parts.whereType<Heading>().single.text,
        original.parts.whereType<Heading>().single.text);
  });
}
