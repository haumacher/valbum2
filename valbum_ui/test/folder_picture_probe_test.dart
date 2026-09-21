/// Review probe of the folder picture (issue #110), composed with the folder
/// properties of #130: writing a title must not take the picture away.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;

import 'folder_picture_test.dart' hide main;
import 'move_test.dart' hide main;

void main() {
  testWidgets('a folder keeps its chosen picture when its title is written',
      (tester) async {
    var requests = <http.Request>[];
    await pumpAt(
      tester,
      (request) {
        if (request.method == "PUT") {
          return json('{"path":"F","message":""}');
        }
        return json(pathOf(request) == "/valbum/data/F/"
            ? folderF(index: "A")
            : rootWithCoveredFolder);
      },
      route: const ["F"],
      requests: requests,
    );

    await tester.tap(find.byIcon(Icons.more_vert).last);
    await tester.pumpAndSettle();
    await tester.tap(find.text("Folder properties"));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).first, "Family");
    await tester.tap(find.text("Übernehmen"));
    await tester.pumpAndSettle();

    var put = requests.singleWhere((r) => r.method == "PUT");
    expect(pathOf(put), "/valbum/data/F/");
    expect(put.body, contains('"title":"Family"'));
    expect(put.body, contains('"index":"A"'),
        reason: "The choice rides along with every write of the listing.");
  });
}
