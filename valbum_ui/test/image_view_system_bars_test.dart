/// The system bars around the viewer and the album, see issue #60.
///
/// On a phone the navigation bar used to sit on top of the picture and, in
/// landscape, exactly where the viewer's chevrons are. The viewer therefore
/// runs immersive while it is on screen, its controls keep clear of the insets
/// anyway (a bar can always be swiped back in), and the album ends its tiles
/// above the bar instead of under it.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'image_view_test.dart' show imagePart, linkedAlbum, pumpViewer;
import 'util/fixtures.dart';

/// The system UI modes the viewer asked the platform for, in order.
List<String> recordSystemUiModes(WidgetTester tester) {
  var modes = <String>[];
  tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
    SystemChannels.platform,
    (call) async {
      if (call.method == "SystemChrome.setEnabledSystemUIMode") {
        modes.add("${call.arguments}");
      }
      return null;
    },
  );
  addTearDown(
    () => tester.binding.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null),
  );
  return modes;
}

/// Runs the test as if it were running on the given platform.
///
/// The override has to be gone again before the test body ends (the framework
/// checks that no debug variable outlives a test), so every test calls
/// [restorePlatform] when it is done; the tear-down is the safety net for a
/// test that fails before that.
void asPlatform(TargetPlatform platform) {
  debugDefaultTargetPlatformOverride = platform;
  addTearDown(restorePlatform);
}

/// Ends what [asPlatform] began.
void restorePlatform() {
  debugDefaultTargetPlatformOverride = null;
}

/// An album of landscape images with the given names.
String albumOf(List<String> names) => '["AlbumInfo", {'
    '"path": "", "title": "Insets", "subTitle": "", "parts": [${[
      for (var name in names)
        '["ImagePart", {"kind": "IMAGE", "name": "$name", '
            '"width": 900, "height": 600, "orientation": "IDENTITY", '
            '"rating": 0}]',
    ].join(",")}]}]';

/// A listing of the given number of folders, without index pictures.
String listingOf(int count) => '["ListingInfo", {'
    '"path": "", "title": "Insets", "folders": [${[
      for (var i = 0; i < count; i++)
        '{"name": "folder$i", "title": "Folder $i", "subTitle": "", '
            '"indexPicture": {"image": "p$i.jpg", "scale": 1.0, '
            '"tx": 0.0, "ty": 0.0}}',
    ].join(",")}]}]';

/// The lowest edge any rendered tile reaches, in page coordinates.
double lowestTile(WidgetTester tester) => tester
    .renderObjectList<RenderBox>(find.byType(Image))
    .map((tile) => tile.localToGlobal(Offset(0, tile.size.height)).dy)
    .reduce((a, b) => a > b ? a : b);

void main() {
  testWidgets('the viewer hides the system bars while it is on screen',
      (tester) async {
    asPlatform(TargetPlatform.android);
    var modes = recordSystemUiModes(tester);

    var images = [imagePart("a.jpg"), imagePart("b.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[0]);

    expect(modes, ["SystemUiMode.immersiveSticky"]);

    // Leaving the viewer gives the bars back.
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    restorePlatform();

    expect(modes, ["SystemUiMode.immersiveSticky", "SystemUiMode.edgeToEdge"]);
  });

  testWidgets('stepping to the next image does not toggle the bars',
      (tester) async {
    asPlatform(TargetPlatform.android);
    var modes = recordSystemUiModes(tester);

    var images = [imagePart("a.jpg"), imagePart("b.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[0]);
    expect(modes, ["SystemUiMode.immersiveSticky"]);

    await tester.tap(find.byIcon(Icons.chevron_right));
    await tester.pumpAndSettle();

    // The step is no new viewer at all since issue #105, and the bars of the
    // one viewer stay hidden.
    expect(modes, ["SystemUiMode.immersiveSticky"]);

    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    restorePlatform();

    expect(modes, ["SystemUiMode.immersiveSticky", "SystemUiMode.edgeToEdge"]);
  });

  testWidgets('a platform without system bars is left alone', (tester) async {
    asPlatform(TargetPlatform.linux);
    var modes = recordSystemUiModes(tester);

    var images = [imagePart("a.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[0]);

    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    restorePlatform();

    expect(modes, isEmpty);
  });

  testWidgets('the viewer controls stay clear of the system insets',
      (tester) async {
    tester.view.padding = const FakeViewPadding(top: 36, bottom: 48);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    var images = [imagePart("a.jpg"), imagePart("b.jpg"), imagePart("c.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[1]);

    var page = tester.getRect(find.byType(ImageView));
    var safeTop = page.top + 36;
    var safeBottom = page.bottom - 48;

    for (var control in [
      find.byIcon(Icons.arrow_back),
      find.byIcon(Icons.chevron_left),
      find.byIcon(Icons.chevron_right),
    ]) {
      var rect = tester.getRect(control);
      expect(rect.top, greaterThanOrEqualTo(safeTop), reason: "$control");
      expect(rect.bottom, lessThanOrEqualTo(safeBottom), reason: "$control");
    }
  });

  testWidgets('the album ends its tiles above the system bar', (tester) async {
    tester.view.physicalSize = const Size(800, 600);
    tester.view.devicePixelRatio = 1.0;
    tester.view.padding = const FakeViewPadding(bottom: 48);
    addTearDown(tester.view.reset);

    var names = [for (var i = 0; i < 12; i++) "image$i.jpg"];
    await tester.pumpWidget(
      VAlbumApp(client: clientReturning(albumOf(names))),
    );
    await tester.pumpAndSettle();

    // To the end of the album, which now stops above the bar.
    await tester.drag(find.byType(SingleChildScrollView), const Offset(0, -2000));
    await tester.pumpAndSettle();

    var scroller = tester.getRect(find.byType(SingleChildScrollView));

    expect(lowestTile(tester), lessThanOrEqualTo(scroller.bottom - 48 + 0.5));
  });

  testWidgets('the listing ends its tiles above the system bar',
      (tester) async {
    tester.view.physicalSize = const Size(800, 600);
    tester.view.devicePixelRatio = 1.0;
    tester.view.padding = const FakeViewPadding(bottom: 48);
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      VAlbumApp(client: clientReturning(listingOf(40))),
    );
    await tester.pumpAndSettle();

    await tester.drag(
      find.byType(SingleChildScrollView),
      const Offset(0, -2000),
    );
    await tester.pumpAndSettle();

    var scroller = tester.getRect(find.byType(SingleChildScrollView));

    expect(lowestTile(tester), lessThanOrEqualTo(scroller.bottom - 48 + 0.5));
  });
}
