import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/image_view.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// Links the given images into an album, as loading an album does.
AlbumInfo linkedAlbum(List<AbstractImage> images, {int minRating = 0}) {
  var album = AlbumInfo(minRating: minRating, parts: images);
  for (var i = 0; i < images.length; i++) {
    var image = images[i];
    image.owner = album;
    image.previous = i > 0 ? images[i - 1] : null;
    image.next = i < images.length - 1 ? images[i + 1] : null;
    image.home = images.first;
    image.end = images.last;
  }
  return album;
}

ImagePart imagePart(String name, {int rating = 0, String comment = ""}) =>
    ImagePart(
      name: name,
      rating: rating,
      comment: comment,
      width: 2000,
      height: 1000,
    );

/// Shows the [ImageView], following the image it navigates to.
class ViewerHarness extends StatefulWidget {
  final AbstractImage initial;
  final void Function(ImageGroup group)? onShowGroup;

  const ViewerHarness(this.initial, {super.key, this.onShowGroup});

  @override
  State<ViewerHarness> createState() => ViewerHarnessState();
}

class ViewerHarnessState extends State<ViewerHarness> {
  late AbstractImage image = widget.initial;

  @override
  Widget build(BuildContext context) => ImageView(
        client: clientReturning("{}"),
        baseUrl: "http://server/valbum/data/album",
        image: image,
        onShowImage: (next) => setState(() => image = next),
        onShowGroup: widget.onShowGroup,
        // The app leaves the viewer by a route change; here the harness is
        // the second route of a test app, so "up" pops it.
        onUp: () => Navigator.maybePop(context),
      );
}

/// Pumps the viewer showing [initial] as the second route of an app.
Future<void> pumpViewer(
  WidgetTester tester,
  AbstractImage initial, {
  void Function(ImageGroup group)? onShowGroup,
}) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              child: const Text("open"),
              onPressed: () => Navigator.push(
                context,
                MaterialPageRoute<void>(
                  builder: (context) =>
                      ViewerHarness(initial, onShowGroup: onShowGroup),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text("open"));
    await tester.pumpAndSettle();
  });
}

/// The URL of the single image shown by the viewer.
String shownUrl(WidgetTester tester) {
  var image = tester.widget<Image>(find.byType(Image));
  return (image.image as NetworkImage).url;
}

Future<void> press(WidgetTester tester, LogicalKeyboardKey key) async {
  await withFakeImageHttp(() async {
    await tester.sendKeyEvent(key);
    await tester.pumpAndSettle();
  });
}

void main() {
  testWidgets('shows the image of the album', (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg")];
    linkedAlbum(images);

    await pumpViewer(tester, images[0]);

    expect(shownUrl(tester), "http://server/valbum/data/album/a.jpg");
  });

  testWidgets('the arrow keys step to the next and previous image',
      (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg"), imagePart("c.jpg")];
    linkedAlbum(images);

    await pumpViewer(tester, images[0]);

    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(shownUrl(tester), "http://server/valbum/data/album/b.jpg");

    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(shownUrl(tester), "http://server/valbum/data/album/c.jpg");

    // At the end of the album, the next key does nothing.
    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(shownUrl(tester), "http://server/valbum/data/album/c.jpg");

    await press(tester, LogicalKeyboardKey.arrowLeft);
    expect(shownUrl(tester), "http://server/valbum/data/album/b.jpg");
  });

  testWidgets('Home and End show the first and the last image', (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg"), imagePart("c.jpg")];
    linkedAlbum(images);

    await pumpViewer(tester, images[1]);

    await press(tester, LogicalKeyboardKey.end);
    expect(shownUrl(tester), "http://server/valbum/data/album/c.jpg");

    await press(tester, LogicalKeyboardKey.home);
    expect(shownUrl(tester), "http://server/valbum/data/album/a.jpg");
  });

  testWidgets('ArrowUp leaves the viewer', (tester) async {
    var images = [imagePart("a.jpg")];
    linkedAlbum(images);

    await pumpViewer(tester, images[0]);
    expect(find.byType(ImageView), findsOneWidget);

    await press(tester, LogicalKeyboardKey.arrowUp);

    expect(find.byType(ImageView), findsNothing);
    expect(find.text("open"), findsOneWidget);
  });

  testWidgets('the chevrons are hidden at the ends of the album',
      (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg"), imagePart("c.jpg")];
    linkedAlbum(images);

    await pumpViewer(tester, images[0]);
    expect(find.byIcon(Icons.chevron_left), findsNothing);
    expect(find.byIcon(Icons.chevron_right), findsOneWidget);

    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(find.byIcon(Icons.chevron_left), findsOneWidget);
    expect(find.byIcon(Icons.chevron_right), findsOneWidget);

    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(find.byIcon(Icons.chevron_left), findsOneWidget);
    expect(find.byIcon(Icons.chevron_right), findsNothing);
  });

  testWidgets('the chevrons navigate', (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg")];
    linkedAlbum(images);

    await pumpViewer(tester, images[0]);

    await withFakeImageHttp(() async {
      await tester.tap(find.byIcon(Icons.chevron_right));
      await tester.pumpAndSettle();
    });

    expect(shownUrl(tester), "http://server/valbum/data/album/b.jpg");
  });

  testWidgets('navigation skips images below the minimum rating',
      (tester) async {
    var images = [
      imagePart("a.jpg"),
      imagePart("hidden.jpg", rating: -1),
      imagePart("c.jpg"),
    ];
    linkedAlbum(images);

    await pumpViewer(tester, images[0]);

    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(shownUrl(tester), "http://server/valbum/data/album/c.jpg");

    await press(tester, LogicalKeyboardKey.arrowLeft);
    expect(shownUrl(tester), "http://server/valbum/data/album/a.jpg");
  });

  testWidgets('the rating filter hides the chevron at the filtered end',
      (tester) async {
    var images = [imagePart("a.jpg"), imagePart("hidden.jpg", rating: -1)];
    linkedAlbum(images);

    await pumpViewer(tester, images[0]);

    expect(find.byIcon(Icons.chevron_right), findsNothing);
  });

  testWidgets('a comment is shown as one paragraph per line', (tester) async {
    var images = [
      imagePart("a.jpg", comment: "First paragraph.\n\nSecond paragraph."),
    ];
    linkedAlbum(images);

    await pumpViewer(tester, images[0]);

    var comment = find.byKey(const Key("image-comment"));
    expect(comment, findsOneWidget);
    expect(
      find.descendant(of: comment, matching: find.byType(Text)),
      findsNWidgets(2),
    );
    expect(find.text("First paragraph."), findsOneWidget);
    expect(find.text("Second paragraph."), findsOneWidget);
  });

  testWidgets('an image without a comment shows none', (tester) async {
    var images = [imagePart("a.jpg")];
    linkedAlbum(images);

    await pumpViewer(tester, images[0]);

    expect(find.byKey(const Key("image-comment")), findsNothing);
  });

  testWidgets('a group is shown by its representative', (tester) async {
    var group = ImageGroup(
      representative: 1,
      images: [imagePart("a.jpg"), imagePart("b.jpg")],
    );
    linkedAlbum([group]);
    ImageGroup? shown;

    await pumpViewer(tester, group, onShowGroup: (self) => shown = self);

    expect(shownUrl(tester), "http://server/valbum/data/album/b.jpg");

    // The "down" chevron opens the alternatives view.
    await withFakeImageHttp(() async {
      await tester.tap(find.byIcon(Icons.expand_more));
      await tester.pumpAndSettle();
    });
    expect(shown, same(group));
  });

  testWidgets('a single image offers no alternatives', (tester) async {
    var images = [imagePart("a.jpg")];
    linkedAlbum(images);

    await pumpViewer(tester, images[0], onShowGroup: (self) {});

    expect(find.byIcon(Icons.expand_more), findsNothing);
  });

  testWidgets('a video is shown by its thumbnail', (tester) async {
    var video = ImagePart(
      name: "clip.mp4",
      kind: ImageKind.video,
      width: 1920,
      height: 1080,
    );
    linkedAlbum([video]);

    await pumpViewer(tester, video);

    expect(
        shownUrl(tester), "http://server/valbum/data/album/clip.mp4?type=tn");
  });

  testWidgets('the mouse wheel zooms around the cursor', (tester) async {
    var images = [imagePart("a.jpg")];
    linkedAlbum(images);

    await pumpViewer(tester, images[0]);

    var state = tester.state<ImageViewState>(find.byType(ImageView));
    var page = tester.getSize(find.byType(ImageView));
    var fitScale = state.transform(page).scale;

    var pointer = TestPointer(1, PointerDeviceKind.mouse);
    var cursor = const Offset(400, 300);
    await withFakeImageHttp(() async {
      pointer.hover(cursor);
      await tester.sendEventToBinding(pointer.scroll(const Offset(0, 100)));
      await tester.pumpAndSettle();
    });

    var transform = state.transform(page);
    expect(transform.scale, closeTo(fitScale * 1.2, 0.0001));

    // Scrolling back snaps to the fitted state again.
    await withFakeImageHttp(() async {
      await tester.sendEventToBinding(pointer.scroll(const Offset(0, -100)));
      await tester.pumpAndSettle();
    });

    expect(transform.scale, fitScale);
    expect(transform.isInitial, isTrue);
  });

  testWidgets('a click toggles between the fitted and the 1:1 display',
      (tester) async {
    var images = [imagePart("a.jpg")];
    linkedAlbum(images);

    await pumpViewer(tester, images[0]);

    var state = tester.state<ImageViewState>(find.byType(ImageView));
    var fitScale =
        state.transform(tester.getSize(find.byType(ImageView))).scale;
    expect(fitScale, lessThan(1.0));

    await withFakeImageHttp(() async {
      await tester.tapAt(const Offset(400, 300));
      await tester.pumpAndSettle();
    });

    var transform = state.transform(tester.getSize(find.byType(ImageView)));
    expect(transform.scale, 1.0);
    expect(transform.isInitial, isFalse);

    await withFakeImageHttp(() async {
      await tester.tapAt(const Offset(400, 300));
      await tester.pumpAndSettle();
    });

    expect(transform.scale, fitScale);
    expect(transform.isInitial, isTrue);
  });
}
