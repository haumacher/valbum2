/// Probe for issue #101: the prefetch composed with the rating filter — a
/// hidden neighbour is skipped, the visible one beyond it is fetched — for a
/// view-only visitor.
library;

import "package:flutter/material.dart" hide Orientation;
import "package:flutter/services.dart";
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'util/viewer_harness.dart';

void main() {
  testWidgets('a neighbour the rating filter hides is neither shown nor prefetched',
      (tester) async {
    var requests = <http.Request>[];
    var images = [
      viewerImagePart("a.jpg"),
      viewerImagePart("b.jpg")..rating = -1,
      viewerImagePart("c.jpg"),
      viewerImagePart("d.jpg"),
    ];
    var album = viewerAlbum(images, rights: const ["view"]);
    album.minRating = 0;

    await pumpViewerHarness(
      tester,
      images[0],
      client: recordingViewerClient(requests),
      share: viewerShareSession(),
    );

    List<String> thumbnails() => [
          for (var r in requests)
            if (r.url.queryParameters["type"] == "tn") r.url.path
        ];
    int fetched(String name) =>
        thumbnails().where((path) => path.endsWith("/$name")).length;

    // Shown: a. Prefetched: c (the first visible neighbour), never the hidden b.
    expect(fetched("a.jpg"), 1);
    expect(fetched("b.jpg"), 0, reason: "hidden by the rating filter");
    expect(fetched("c.jpg"), 1);
    expect(fetched("d.jpg"), 0);

    // Paging skips b as the viewer always did, and c comes from the cache.
    await withFakeImageHttp(() async {
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
      await tester.pumpAndSettle();
    });
    expect(tester.widget<ImageView>(find.byType(ImageView)).image.thumbnailName, "c.jpg");
    expect(fetched("c.jpg"), 1, reason: "the prefetch is what is shown");
    expect(fetched("d.jpg"), 1, reason: "the new neighbour");
    expect(fetched("b.jpg"), 0);
    // Nothing asked for an original: a visitor without download never does.
    expect(requests.where((r) => r.url.path.endsWith(".jpg") && !r.url.queryParameters.containsKey("type")), isEmpty);
  });
}
