/// Tests of the camera filter of the inbox (issue #278): a chip per camera
/// found, with its count, plus "Unknown camera"; several chips a union, none
/// everything; what is hidden is never selected; remembered on the device.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/inbox_view.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/album_layout.dart' show ToImage;
import 'package:valbum_ui/image_transform.dart' show nextVisible;
import 'package:valbum_ui/resource.dart';

import 'inbox_select_camera_test.dart' show canon, phone, serve, cameraTool;
import 'inbox_view_test.dart'
    show
        inboxState,
        inboxTree,
        pumpInbox,
        reveal,
        selectedNames,
        tapHeading,
        tapTileOf,
        tapTileWith;
import 'move_test.dart' show recordingClient, tile;
import 'util/fake_image_http.dart';
import 'util/l10n.dart';

/// The chip of [camera], the empty label for "Unknown camera".
Finder chip(String camera) => find.byKey(camera.isEmpty
    ? const Key("inbox-camera-unknown")
    : Key("inbox-camera-chip-$camera"));

/// The labels of the chips shown, in the order shown, each with its count.
List<String> chipLines(WidgetTester tester) => [
      for (var element in find.byType(FilterChip).evaluate())
        [
          for (var text in find
              .descendant(
                  of: find.byWidget(element.widget),
                  matching: find.byType(Text))
              .evaluate())
            (text.widget as Text).data,
        ].join(" "),
    ];

Future<void> tapChip(WidgetTester tester, String camera) async {
  var finder = chip(camera);
  await reveal(tester, finder);
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

/// The names the inbox shows, sorted.
List<String> shownNames(WidgetTester tester) =>
    [for (var image in inboxState(tester).images) image.name]..sort();

List<String> sorted(List<String> names) => [...names]..sort();

/// Opens the inbox in an app remembering its settings in [store].
Future<void> pumpWithStore(
    WidgetTester tester, InMemorySettingsStore store) async {
  await withFakeImageHttp(() async {
    var client = recordingClient(serve, []);
    await tester.pumpWidget(VAlbumApp(
      client: client,
      settings: ServerSettings(
        store: store,
        platformDefault: () => client.dataUrl,
        loaded: true,
      ),
      initialRoute: const ListingOrAlbumRoute(["Inbox"]),
    ));
    await tester.pumpAndSettle();
  });
}

/// The viewer on the screen.
ImageViewState viewer(WidgetTester tester) =>
    tester.state<ImageViewState>(find.byType(ImageView));

Future<void> settle(WidgetTester tester) =>
    withFakeImageHttp(() => tester.pumpAndSettle());

/// Opens the viewer on [name] by the tile's tool.
Future<void> openViewer(WidgetTester tester, String name) async {
  await tapTileOf(tester, name);
  var open = find.descendant(
      of: tile(name), matching: find.byKey(const Key("inbox-open")));
  await reveal(tester, open);
  await tester.tap(open);
  await settle(tester);
  expect(viewer(tester).part.name, name);
}

/// Every name the viewer pages through from [start] onwards, as it steps.
List<String> pagedFrom(WidgetTester tester, AbstractImage start) {
  var minRating = viewer(tester).minRating;
  return [
    for (AbstractImage? image = start;
        image != null;
        image = nextVisible(image, minRating))
      ToImage.toImage(image).name,
  ];
}

void main() {
  testWidgets('a chip per camera with its count, and "Unknown camera"',
      (tester) async {
    await pumpInbox(tester, serve);

    // The most photographs first; the trashed one is not counted, the
    // camera-less videos and the broken file are among the unknown.
    expect(chipLines(tester), [
      "${testL10n.inboxUnknownCamera} 5",
      "$canon 3",
      "$phone 1",
    ]);
    // Nothing active: everything shown.
    expect(shownNames(tester).length, 9);
  });

  testWidgets('one chip shows that camera alone, its days only',
      (tester) async {
    await pumpInbox(tester, serve);
    await tapChip(tester, canon);

    expect(shownNames(tester), ["cam1.jpg", "cam2.jpg", "cam3.jpg"]);
    expect(find.byKey(const Key("inbox-day-2026-03-02")), findsOneWidget);
    // Every chip stays, so the filter can be widened again.
    expect(chip(""), findsOneWidget);
    expect(chip(phone), findsOneWidget);

    await tapChip(tester, canon);
    expect(shownNames(tester).length, 9);
  });

  testWidgets('two chips show the union', (tester) async {
    await pumpInbox(tester, serve);
    await tapChip(tester, phone);
    await tapChip(tester, "");

    expect(shownNames(tester),
        ["bad.jpg", "phone.mp4", "raw.mp4", "wa1.jpg", "wa2.jpg", "wa3.jpg"]);
  });

  testWidgets('a heading under a filter selects only what is shown',
      (tester) async {
    await pumpInbox(tester, serve);
    await tapChip(tester, canon);

    await tapHeading(tester, "inbox-month-2026-03");
    expect(sorted(selectedNames(tester)), ["cam1.jpg", "cam2.jpg"]);
    expect(
        inboxState(tester).isSelected(inboxState(tester).images.first), isTrue);
    expect(find.text(testL10n.selectedCount(2)), findsOneWidget);
  });

  testWidgets('"select all from this camera" gathers only what is shown',
      (tester) async {
    await pumpInbox(tester, serve);
    await tapChip(tester, "");
    await tapChip(tester, phone);

    await tapTileOf(tester, "wa1.jpg");
    await reveal(tester, cameraTool("wa1.jpg"));
    await tester.tap(cameraTool("wa1.jpg"));
    await tester.pumpAndSettle();
    expect(sorted(selectedNames(tester)),
        ["bad.jpg", "raw.mp4", "wa1.jpg", "wa2.jpg", "wa3.jpg"]);
  });

  testWidgets('what a filter hides leaves the selection', (tester) async {
    await pumpInbox(tester, serve);
    await tapTileOf(tester, "wa1.jpg");
    await tapTileWith(tester, "cam1.jpg", LogicalKeyboardKey.controlLeft);
    expect(sorted(selectedNames(tester)), ["cam1.jpg", "wa1.jpg"]);

    await tapChip(tester, canon);
    expect(selectedNames(tester), ["cam1.jpg"]);
    expect(find.text(testL10n.selectedCount(1)), findsOneWidget);

    // Widening the filter brings the photograph back, not its selection.
    await tapChip(tester, canon);
    expect(selectedNames(tester), ["cam1.jpg"]);
  });

  testWidgets('the filter is remembered on the device', (tester) async {
    var store = InMemorySettingsStore();
    await pumpWithStore(tester, store);
    await tapChip(tester, phone);
    expect(shownNames(tester), ["phone.mp4"]);

    // The app is gone and opened anew over the same store.
    await tester.pumpWidget(const SizedBox());
    await pumpWithStore(tester, store);
    expect(shownNames(tester), ["phone.mp4"]);
    var active = tester.widget<FilterChip>(chip(phone));
    expect(active.selected, isTrue);

    // Cleared, it is forgotten.
    await tapChip(tester, phone);
    await tester.pumpWidget(const SizedBox());
    await pumpWithStore(tester, store);
    expect(shownNames(tester).length, 9);
  });

  testWidgets('an unreadable store shows the inbox whole', (tester) async {
    var store = InMemorySettingsStore()..inboxCameraFilters = "not json";
    await pumpWithStore(tester, store);
    expect(shownNames(tester).length, 9);
    await tapChip(tester, canon);
    expect(shownNames(tester).length, 3);
  });

  testWidgets('an inbox of one camera shows no chips', (tester) async {
    // The photographs of the plain inbox carry no camera data at all.
    await pumpInbox(tester, (request) => inboxTree(request));
    expect(find.byKey(const Key("inbox-camera-chips")), findsNothing);
    expect(find.byType(FilterChip), findsNothing);
  });

  test('a remembered camera no photograph carries any more filters nothing',
      () {
    var cameras = [const InboxCamera("A", 2), const InboxCamera("", 1)];
    expect(activeCameras({"gone"}, cameras), isEmpty);
    expect(activeCameras({"A", "gone"}, cameras), {"A"});
    // One camera: no chips, so no filter either.
    expect(activeCameras({"A"}, [const InboxCamera("A", 2)]), isEmpty);
  });

  test('ties are broken by name, "Unknown camera" behind', () {
    ImagePart image(String camera) => ImagePart()..camera = camera;
    var days = [
      InboxDay(DateTime(2026, 3, 2), [image("b"), image(""), image("C")]),
      InboxDay(null, [image("a"), image("C")]),
    ];
    expect(
      [
        for (var camera in inboxCameras(days))
          "${camera.camera}:${camera.count}"
      ],
      ["C:2", "a:1", "b:1", ":1"],
    );
  });

  testWidgets('the viewer pages through the camera filtered by, and stops',
      (tester) async {
    await pumpInbox(tester, serve);
    await tapChip(tester, canon);
    await openViewer(tester, "cam1.jpg");

    // The first of the filtered set: nothing before it, as at an album start.
    expect(viewer(tester).previous, isNull);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    await settle(tester);
    expect(viewer(tester).part.name, "cam2.jpg");
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    await settle(tester);
    expect(viewer(tester).part.name, "cam3.jpg");

    // The last of the filtered set is the end, whatever lies behind it.
    expect(viewer(tester).next, isNull);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    await settle(tester);
    expect(viewer(tester).part.name, "cam3.jpg");

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
    await settle(tester);
    expect(viewer(tester).part.name, "cam2.jpg");
    await tester.sendKeyEvent(LogicalKeyboardKey.home);
    await settle(tester);
    expect(viewer(tester).part.name, "cam1.jpg");
    await tester.sendKeyEvent(LogicalKeyboardKey.end);
    await settle(tester);
    expect(viewer(tester).part.name, "cam3.jpg");
  });

  testWidgets('the viewer pages in the order shown, the trashed left out',
      (tester) async {
    await pumpInbox(tester, serve);
    await tapChip(tester, "");
    var shown = [for (var image in inboxState(tester).images) image.name];
    await openViewer(tester, "wa1.jpg");

    expect(pagedFrom(tester, viewer(tester).widget.image), shown);
    expect(shown, isNot(contains("trashed.jpg")));
  });
}
