/// Tests of "Select all from this camera" in the inbox (issue #274): the
/// album's tool of #78 on the inbox tile, gathering across the whole inbox,
/// and on a photograph without camera data "Select all without camera data".
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;

import 'inbox_view_test.dart'
    show
        inboxTree,
        noon,
        pumpInbox,
        reveal,
        selectedNames,
        tapTileOf,
        tapTileWith;
import 'move_test.dart' show tile;
import 'util/l10n.dart';

const String canon = "Canon EOS 5D";
const String phone = "SAMSUNG SM-G991B";

/// A part of the inbox, taken at noon of the given day.
String part(
  String name,
  DateTime taken, {
  String camera = "",
  String kind = "IMAGE",
  String broken = "",
  int rating = 0,
}) =>
    '["ImagePart", {"kind": "$kind", "name": "$name", '
    '"date": ${taken.millisecondsSinceEpoch}, "width": 2048, '
    '"height": 1536, "orientation": "IDENTITY", "rating": $rating'
    '${camera.isEmpty ? "" : ', "camera": "$camera"'}'
    '${broken.isEmpty ? "" : ', "broken": "$broken"'}}]';

/// An inbox of three days: a camera, a phone's video, WhatsApp images without
/// camera data, a video without any, a broken file and a trashed photograph.
String camerasInbox() =>
    '["AlbumInfo", {"path": "Inbox", "title": "Inbox", "subTitle": "", '
    '"kind": "INBOX", "parts": [${[
      part("cam1.jpg", noon(2026, 3, 2), camera: canon),
      part("phone.mp4", noon(2026, 3, 2), camera: phone, kind: "VIDEO"),
      part("wa1.jpg", noon(2026, 3, 2)),
      part("cam2.jpg", noon(2026, 3, 1), camera: canon),
      part("raw.mp4", noon(2026, 3, 1), kind: "VIDEO"),
      part("wa2.jpg", noon(2026, 3, 1)),
      part("trashed.jpg", noon(2026, 3, 1), rating: -2),
      part("bad.jpg", noon(2026, 2, 10), broken: "Not a JPEG file"),
      part("cam3.jpg", noon(2026, 2, 10), camera: canon),
      part("wa3.jpg", noon(2026, 2, 10)),
    ].join(", ")}]}]';

http.Response serve(http.Request request) =>
    inboxTree(request, album: camerasInbox());

/// The camera tool on the tile of [name].
Finder cameraTool(String name) => find.descendant(
    of: tile(name), matching: find.byKey(const Key("inbox-select-camera")));

String? tooltipOf(WidgetTester tester, String name) =>
    tester.widget<IconButton>(cameraTool(name)).tooltip;

Future<void> tapCameraTool(WidgetTester tester, String name) async {
  await reveal(tester, tile(name));
  await tester.tap(cameraTool(name));
  await tester.pumpAndSettle();
}

List<String> sorted(List<String> names) => [...names]..sort();

void main() {
  testWidgets('the inbox tile offers it and gathers the camera across days',
      (tester) async {
    await pumpInbox(tester, serve);
    await tapTileOf(tester, "cam1.jpg");

    expect(cameraTool("cam1.jpg"), findsOneWidget);
    expect(tooltipOf(tester, "cam1.jpg"), testL10n.selectAllFromCamera);

    await tapCameraTool(tester, "cam1.jpg");
    // Every day of the inbox, not only the one the tile stands in.
    expect(sorted(selectedNames(tester)), ["cam1.jpg", "cam2.jpg", "cam3.jpg"]);
  });

  testWidgets('on a photograph without camera data it gathers exactly those',
      (tester) async {
    await pumpInbox(tester, serve);
    await tapTileOf(tester, "wa1.jpg");

    expect(tooltipOf(tester, "wa1.jpg"), testL10n.selectAllWithoutCamera);
    expect(
      find.descendant(
          of: cameraTool("wa1.jpg"),
          matching: find.byIcon(Icons.no_photography)),
      findsOneWidget,
    );

    await tapCameraTool(tester, "wa1.jpg");
    // The camera-less video is among them, the phone's video is not; the
    // trashed photograph is not shown and is not selected unseen.
    var names = sorted(selectedNames(tester));
    expect(names, ["bad.jpg", "raw.mp4", "wa1.jpg", "wa2.jpg", "wa3.jpg"]);
    expect(names, isNot(contains("phone.mp4")));
    expect(names, isNot(contains("trashed.jpg")));
  });

  testWidgets('adds to the selection, never replaces it', (tester) async {
    await pumpInbox(tester, serve);
    await tapTileOf(tester, "phone.mp4");
    await tapTileWith(tester, "cam2.jpg", LogicalKeyboardKey.controlLeft);
    expect(sorted(selectedNames(tester)), ["cam2.jpg", "phone.mp4"]);

    await tapCameraTool(tester, "cam2.jpg");
    expect(sorted(selectedNames(tester)),
        ["cam1.jpg", "cam2.jpg", "cam3.jpg", "phone.mp4"]);

    // A second group on top (the tile shows its tools once selected).
    await tapTileWith(tester, "wa2.jpg", LogicalKeyboardKey.controlLeft);
    await tapCameraTool(tester, "wa2.jpg");
    expect(sorted(selectedNames(tester)), [
      "bad.jpg",
      "cam1.jpg",
      "cam2.jpg",
      "cam3.jpg",
      "phone.mp4",
      "raw.mp4",
      "wa1.jpg",
      "wa2.jpg",
      "wa3.jpg",
    ]);
  });

  testWidgets('says so when there is nothing else to add', (tester) async {
    await pumpInbox(tester, serve);
    await tapTileOf(tester, "phone.mp4");

    // A video carries a camera of its own: the phone took nothing else.
    expect(tooltipOf(tester, "phone.mp4"), testL10n.selectAllFromCamera);
    await tapCameraTool(tester, "phone.mp4");
    expect(find.text(testL10n.noOtherImageFromCamera), findsOneWidget);
    expect(selectedNames(tester), ["phone.mp4"]);
  });

  testWidgets('says so for the camera-less group as well', (tester) async {
    await pumpInbox(tester, serve);
    await tapTileOf(tester, "wa1.jpg");
    await tapCameraTool(tester, "wa1.jpg");
    await tester.pump(const Duration(seconds: 10));
    await tester.pumpAndSettle();

    await tapCameraTool(tester, "wa1.jpg");
    expect(find.text(testL10n.noOtherImageWithoutCamera), findsOneWidget);
  });

  testWidgets('is not offered on a file that cannot be read', (tester) async {
    await pumpInbox(tester, serve);
    await tapTileOf(tester, "bad.jpg");

    expect(selectedNames(tester), ["bad.jpg"]);
    expect(cameraTool("bad.jpg"), findsNothing);
  });
}
