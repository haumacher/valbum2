/// Who the attribution is for (issue #96).
///
/// "Added by …" is bookkeeping among the members of a space: it says who
/// brought a photo into the family's album. A visitor arriving through a share
/// link stands outside that space — the names mean nothing to them, and a link
/// caller is never recognised as the contributor, so the line used to stand
/// under *every single* photo of a shared album. It is dropped there, and it
/// stays exactly as issue #53 left it for members.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;

import 'util/viewer_harness.dart';

Finder get attribution => find.byKey(const Key("image-contributor"));

Finder get caption => find.byKey(const Key("image-caption"));

void main() {
  setUp(withEmptyImageCache);

  testWidgets('a share-link visitor is shown no attribution at all',
      (tester) async {
    var images = [
      viewerImagePart("a.jpg", contributor: "user:bob", contributorLabel: "bob")
    ];
    viewerAlbum(images, rights: const ["view"]);

    await pumpViewerHarness(
      tester,
      images[0],
      client: recordingViewerClient(<http.Request>[]),
      share: viewerShareSession(),
    );

    expect(attribution, findsNothing);
    expect(find.textContaining("Added by"), findsNothing);
    // Nothing else was in the caption either, so there is no caption.
    expect(caption, findsNothing);
  });

  testWidgets('another member is shown it, the contributor is not',
      (tester) async {
    var images = [
      viewerImagePart("a.jpg", contributor: "user:bob", contributorLabel: "bob")
    ];
    viewerAlbum(images, rights: const ["view", "download"]);

    await pumpViewerHarness(
      tester,
      images[0],
      client: recordingViewerClient(<http.Request>[]),
      caller: viewerMember("carol"),
    );

    expect(attribution, findsOneWidget);
    expect(find.text("Added by bob"), findsOneWidget);
  });

  testWidgets('the contributor is shown nothing, as before', (tester) async {
    var images = [
      viewerImagePart("a.jpg", contributor: "user:bob", contributorLabel: "bob")
    ];
    viewerAlbum(images, rights: const ["view", "download"]);

    await pumpViewerHarness(
      tester,
      images[0],
      client: recordingViewerClient(<http.Request>[]),
      caller: viewerMember("bob"),
    );

    expect(attribution, findsNothing);
  });
}
