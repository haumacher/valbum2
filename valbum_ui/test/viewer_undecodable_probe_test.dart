/// Probe of issue #313: a photo whose original no codec reads and whose
/// display rendition fails too — a corrupt JPEG, which has no display
/// rendition at all — says so, and paging away and back fetches the original
/// no second time; a failed display rendition is tried again on coming back,
/// as every failed picture of the viewer is, but nothing asks again by itself.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/not_ready.dart';

import 'util/l10n.dart';
import 'util/viewer_harness.dart';
import 'viewer_undecodable_test.dart'
    show display, isWrongDisplay, isWrongOriginal, original;

void main() {
  late List<http.Request> requests;
  late List<String> fetched;

  int fetchesOf(String url) => fetched.where((each) => each == url).length;

  setUp(() {
    withEmptyImageCache();
    resetNotReady();
    requests = [];
    fetched = [];
  });

  /// Shows `wrong.jpg`, pages to `b.jpg`, waits out the message, pages back.
  Future<void> visitTwice(WidgetTester tester) async {
    var images = [viewerImagePart("wrong.jpg"), viewerImagePart("b.jpg")];
    viewerAlbum(images, rights: const ["view", "download"]);
    await pumpViewerHarness(tester, images[0],
        client: recordingViewerClient(requests), caller: viewerMember("bob"));
    await letImagesDecode(tester);
    expect(find.byKey(const Key("image-failed")), findsOneWidget);
    expect(find.text(testL10n.pictureFailedMessage), findsOneWidget);

    await pageViewer(tester, LogicalKeyboardKey.arrowRight);
    await letImagesDecode(tester);
    expect(shownPictureUrl(tester), "$viewerBaseUrl/b.jpg");
    // The message is a snack bar of its own time: let it go.
    await tester.pump(const Duration(seconds: 7));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key("image-failed")), findsNothing);

    await pageViewer(tester, LogicalKeyboardKey.arrowLeft);
    await letImagesDecode(tester);
    await tester.pumpAndSettle();
  }

  testWidgets(
      'an undecodable original is fetched once across paging, and nothing '
      'asks again by itself', (tester) async {
    fakeImageRequests(
      requested: fetched,
      undecodable: isWrongOriginal,
      failing: isWrongDisplay,
    );
    await visitTwice(tester);

    expect(fetchesOf(original), 1,
        reason: "an original no codec reads is not fetched again: "
            "${fetched.join(", ")}");
    var asked = fetchesOf(display);
    await tester.pump(const Duration(seconds: 30));
    await tester.pumpAndSettle();
    expect(fetchesOf(display), asked, reason: "no loop: ${fetched.join(", ")}");
    expect(fetchesOf(original), 1);
    await tester.pumpWidget(const SizedBox());
  });

}
