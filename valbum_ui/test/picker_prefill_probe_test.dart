/// Probe of the picker's search text proposed for a new album, from the
/// upload target's picker (issue #240) rather than the move: the same
/// picker, the same proposal, and the album is created under it.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/upload_target_view.dart';

import 'upload_target_view_test.dart' show pumpSection, tapKey, tree, json;

void main() {
  testWidgets("the upload target's picker proposes what was looked for",
      (tester) async {
    var created = <http.Request>[];
    var client = VAlbumClient(
      dataUrl: "http://server/valbum/data",
      httpClient: MockClient((request) async {
        if (request.url.queryParameters["type"] == "folders") {
          return json('["FolderMatches", {"folders": []}]');
        }
        if (request.method == "POST") {
          created.add(request);
          return json('["CreateResult", {"path": "2026/Ibiza"}]');
        }
        return tree(request);
      }),
    );
    await pumpSection(tester, client: client);
    await tapKey(tester, uploadTargetChooseKey);
    await tapKey(tester, const Key("picker-folder-2026"));
    await tester.enterText(find.byKey(const Key("picker-search")), " Ibiza ");
    await tester.pump(FolderPickerState.searchDelay);
    await tester.pumpAndSettle();
    await tapKey(tester, const Key("picker-create-album"));
    var title = tester
        .widget<EditableText>(find.descendant(
            of: find.byType(TextFormField).first,
            matching: find.byType(EditableText)))
        .controller
        .text;
    expect(title, "Ibiza");
  });
}
