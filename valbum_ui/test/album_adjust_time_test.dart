import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

// Issue #77: the clock of a real camera is off by seconds, minutes or days.
// "Adjust recording time…" shifts the date of every selected image in the
// sidecar — never the EXIF of the original — and files each of them where
// its new date belongs, out of its group and out of its section.

/// An image of the given name taken at the given date.
ImagePart image(String name, int date, {ImageKind kind = ImageKind.image}) =>
    ImagePart(
      name: name,
      date: date,
      kind: kind,
      width: 2048,
      height: 1536,
    );

/// A short description of the stored order of the album's parts.
List<String> partNames(AlbumInfo album) =>
    [for (var part in album.parts) partName(part)];

String partName(AlbumPart part) {
  if (part is Heading) return "Heading(${part.text})";
  if (part is ImagePart) return part.name;
  var group = part as ImageGroup;
  return "Group(${group.images.map((i) => i.name).join(",")})";
}

/// The names of the images of the album, groups flattened, in stored order.
List<String> imageNames(AlbumInfo album) => [
      for (var part in album.parts)
        if (part is ImagePart)
          part.name
        else if (part is ImageGroup)
          for (var member in part.images) member.name,
    ];

Set<AlbumPart> selectionOf(Iterable<AlbumPart> parts) =>
    Set<AlbumPart>.identity()..addAll(parts);

AlbumContentState albumState(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent));

AlbumInfo album(WidgetTester tester) => albumState(tester).widget.album;

/// The tile of the image with the given file name.
Finder tile(String name) => find.byKey(ValueKey(name));

/// The tool with the given icon on the tile of [name].
Finder tool(String name, IconData icon) =>
    find.descendant(of: tile(name), matching: find.byIcon(icon));

/// Taps a tile beside its toolbars, so that the tap reaches the tile itself.
Future<void> tapTile(WidgetTester tester, String name) async {
  var box = tester.getRect(tile(name));
  await tester.tapAt(Offset(box.left + 8, box.center.dy));
  await tester.pumpAndSettle();
}

/// Loads the given album and enters the edit mode by a long press.
Future<void> pumpEditMode(WidgetTester tester, VAlbumClient client) async {
  await tester.pumpWidget(VAlbumApp(client: client));
  await tester.pumpAndSettle();
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
}

/// A time in the local zone, the way the dialog reads and writes one.
DateTime at(int year, int month, int day, int hour, int minute,
        [int sec = 0]) =>
    DateTime(year, month, day, hour, minute, sec);

int millis(DateTime time) => time.millisecondsSinceEpoch;

void main() {
  group('the offset of a recording time adjustment', () {
    test('is the difference to the time the reference carries', () {
      var reference = image("a.jpg", millis(at(2002, 3, 3, 10, 0)));

      expect(offsetFor(reference, at(2002, 3, 3, 12, 13, 5)),
          const Duration(hours: 2, minutes: 13, seconds: 5));
      expect(offsetFor(reference, at(2002, 3, 3, 10, 0)), Duration.zero);
      expect(offsetFor(reference, at(2002, 3, 2, 10, 0)),
          const Duration(days: -1));

      // Nothing entered, or nothing to state an offset against.
      expect(offsetFor(reference, null), isNull);
      expect(offsetFor(image("n.jpg", 0), at(2002, 3, 3, 10, 0)), isNull);
    });

    test('is put into words with its sign and the units that matter', () {
      expect(
        offsetInWords(testL10n, const Duration(hours: 2, minutes: 13, seconds: 5)),
        "+2 h 13 min 5 s",
      );
      // A zero in the middle is kept, so the reading stays unambiguous.
      expect(
        offsetInWords(testL10n, const Duration(days: -1, minutes: -4)),
        "−1 d 0 h 4 min",
      );
      expect(offsetInWords(testL10n, const Duration(seconds: -30)), "−30 s");
      expect(offsetInWords(testL10n, Duration.zero), "0 s");
    });
  });

  group('adjusting the recording time', () {
    test('shifts every selected image, a group member and a video too', () {
      var a = image("a.jpg", millis(at(2002, 3, 3, 10, 0)));
      var inGroup = image("g1.jpg", millis(at(2002, 3, 3, 10, 30)));
      var other = image("g2.jpg", millis(at(2002, 3, 3, 10, 40)));
      var video =
          image("v.mp4", millis(at(2002, 3, 3, 11, 0)), kind: ImageKind.video);
      var group = ImageGroup(images: [inGroup, other], representative: 0);
      var info = AlbumInfo(parts: [a, group, video]);
      AlbumInitializer().init(info);

      var changed = adjustRecordingTime(
        info,
        selectionOf([a, inGroup, video]),
        const Duration(hours: 1),
      );

      expect(changed, isTrue);
      expect(a.date, millis(at(2002, 3, 3, 11, 0)));
      expect(inGroup.date, millis(at(2002, 3, 3, 11, 30)));
      expect(video.date, millis(at(2002, 3, 3, 12, 0)));
      // The image that was not selected keeps its time.
      expect(other.date, millis(at(2002, 3, 3, 10, 40)));
    });

    test('shifts backwards for a camera that runs ahead', () {
      var a = image("a.jpg", millis(at(2002, 3, 3, 12, 0)));
      var b = image("b.jpg", millis(at(2002, 3, 3, 10, 0)));
      var info = AlbumInfo(parts: [b, a]);
      AlbumInitializer().init(info);

      expect(
        adjustRecordingTime(
          info,
          selectionOf([a]),
          const Duration(hours: -3),
        ),
        isTrue,
      );
      expect(a.date, millis(at(2002, 3, 3, 9, 0)));
      // And it moved in front of the image it now precedes.
      expect(partNames(info), ["a.jpg", "b.jpg"]);
    });

    test('leaves a group of two, which dissolves into its other image', () {
      var early = image("early.jpg", millis(at(2002, 3, 3, 9, 0)));
      var wrong = image("wrong.jpg", millis(at(2002, 3, 3, 9, 5)));
      var partner = image("partner.jpg", millis(at(2002, 3, 3, 9, 6)));
      var group = ImageGroup(images: [wrong, partner], representative: 0);
      var late = image("late.jpg", millis(at(2002, 3, 3, 18, 0)));
      var info = AlbumInfo(parts: [
        early,
        group,
        Heading(text: "Am Abend"),
        late,
      ]);
      AlbumInitializer().init(info);

      // The camera was nine hours behind.
      expect(
        adjustRecordingTime(
          info,
          selectionOf([wrong]),
          const Duration(hours: 9, minutes: 30),
        ),
        isTrue,
      );

      // The group is left with one image and is dissolved, as ungrouping
      // dissolves it; the adjusted image lands in the evening section.
      expect(partNames(info), [
        "early.jpg",
        "partner.jpg",
        "Heading(Am Abend)",
        "late.jpg",
        "wrong.jpg",
      ]);
      expect(info.parts.whereType<ImageGroup>(), isEmpty);
    });

    test('leaves a group of three, which keeps the other two', () {
      var a = image("a.jpg", millis(at(2002, 3, 3, 9, 0)));
      var g1 = image("g1.jpg", millis(at(2002, 3, 3, 10, 0)));
      var g2 = image("g2.jpg", millis(at(2002, 3, 3, 10, 1)));
      var g3 = image("g3.jpg", millis(at(2002, 3, 3, 10, 2)));
      // The adjusted image is the one representing the group.
      var group = ImageGroup(images: [g1, g2, g3], representative: 0);
      var info = AlbumInfo(parts: [a, group]);
      AlbumInitializer().init(info);

      expect(
        adjustRecordingTime(
          info,
          selectionOf([g1]),
          const Duration(hours: -2),
        ),
        isTrue,
      );

      expect(partNames(info), ["g1.jpg", "a.jpg", "Group(g2.jpg,g3.jpg)"]);
      // The representative was adjusted away, so the group shows the first
      // image it has left — a valid index in every case.
      expect(group.representative, 0);
      expect(group.images[group.representative], g2);
    });

    test('moves an image across a heading when the clock was days off', () {
      var day1 = image("day1.jpg", millis(at(2002, 3, 3, 10, 0)));
      var strayed = image("strayed.jpg", millis(at(2002, 3, 3, 11, 0)));
      var day2 = image("day2.jpg", millis(at(2002, 3, 4, 10, 0)));
      var info = AlbumInfo(parts: [
        Heading(text: "Erster Tag"),
        day1,
        strayed,
        Heading(text: "Zweiter Tag"),
        day2,
      ]);
      AlbumInitializer().init(info);

      // Its camera was a day behind: the image belongs to the second day.
      expect(
        adjustRecordingTime(
          info,
          selectionOf([strayed]),
          const Duration(days: 1),
        ),
        isTrue,
      );

      expect(partNames(info), [
        "Heading(Erster Tag)",
        "day1.jpg",
        "Heading(Zweiter Tag)",
        "day2.jpg",
        "strayed.jpg",
      ]);
    });

    test('never moves unadjusted entries relative to each other', () {
      var parts = <AlbumPart>[];
      var selected = <AlbumPart>[];
      // Every second image belongs to the camera that runs two hours behind.
      for (var i = 0; i < 8; i++) {
        var part = image("i$i.jpg", millis(at(2002, 3, 3, 9 + i, 0)));
        parts.add(part);
        if (i.isEven) {
          selected.add(part);
        }
      }
      parts.insert(4, Heading(text: "Mittags"));
      var info = AlbumInfo(parts: parts);
      AlbumInitializer().init(info);

      var untouched = [
        for (var part in info.parts)
          if (!selected.contains(part)) partName(part),
      ];

      expect(
        adjustRecordingTime(
          info,
          selectionOf(selected),
          const Duration(hours: 2),
        ),
        isTrue,
      );

      var after = [
        for (var part in info.parts)
          if (!selected.contains(part)) partName(part),
      ];
      expect(after, untouched);
    });

    test('keeps the order of two images landing at the same time', () {
      var first = image("first.jpg", millis(at(2002, 3, 3, 10, 0)));
      var second = image("second.jpg", millis(at(2002, 3, 3, 10, 0)));
      var anchor = image("anchor.jpg", millis(at(2002, 3, 3, 8, 0)));
      var info = AlbumInfo(parts: [first, second, anchor]);
      AlbumInitializer().init(info);

      expect(
        adjustRecordingTime(
          info,
          selectionOf([first, second]),
          const Duration(hours: -1),
        ),
        isTrue,
      );

      // Both at 9:00 now, behind the 8:00 image and in the order they had.
      expect(partNames(info), ["anchor.jpg", "first.jpg", "second.jpg"]);
    });

    test('changes nothing for an offset of zero', () {
      var a = image("a.jpg", millis(at(2002, 3, 3, 10, 0)));
      var b = image("b.jpg", millis(at(2002, 3, 3, 11, 0)));
      var info = AlbumInfo(parts: [b, a]);
      AlbumInitializer().init(info);

      expect(adjustRecordingTime(info, selectionOf([a, b]), Duration.zero),
          isFalse);
      // Not even the order the album happens to be in is repaired: this
      // action adjusts, it does not sort (that is "Sort by date", #76).
      expect(partNames(info), ["b.jpg", "a.jpg"]);
      expect(a.date, millis(at(2002, 3, 3, 10, 0)));
    });

    test('leaves an image without a recording time alone', () {
      var undated = image("n.jpg", 0);
      var dated = image("a.jpg", millis(at(2002, 3, 3, 10, 0)));
      var info = AlbumInfo(parts: [undated, dated]);
      AlbumInitializer().init(info);

      expect(
        adjustRecordingTime(
          info,
          selectionOf([undated, dated]),
          const Duration(hours: 1),
        ),
        isTrue,
      );
      // "0" means "no recording time known"; shifting it would invent one.
      expect(undated.date, 0);
      expect(dated.date, millis(at(2002, 3, 3, 11, 0)));

      // And a selection of undated images alone changes nothing at all.
      expect(
        adjustRecordingTime(
          info,
          selectionOf([undated]),
          const Duration(hours: 1),
        ),
        isFalse,
      );
    });

    test('says which images a selection stands for', () {
      var a = image("a.jpg", 100);
      var g1 = image("g1.jpg", 200);
      var g2 = image("g2.jpg", 300);
      var group = ImageGroup(images: [g1, g2], representative: 0);
      var heading = Heading(text: "H");
      var info = AlbumInfo(parts: [heading, a, group]);
      AlbumInitializer().init(info);

      // A selected group stands for all of its images, a heading for none.
      expect(selectedImages(info, selectionOf([heading, group])), [g1, g2]);
      expect(selectedImages(info, selectionOf([a, g2])), [a, g2]);

      // The reference is the tile the action was invoked on where that tile
      // shows a selected image, the first selected one otherwise.
      expect(referenceImage(info, selectionOf([a, g2]), g2), g2);
      // A group's tile stands for the image representing it.
      expect(referenceImage(info, selectionOf([group]), group), g1);
      // The tile invoked on shows nothing that is selected: the first
      // selected image in stored order takes over.
      expect(referenceImage(info, selectionOf([a, g2]), group), a);
      expect(referenceImage(info, selectionOf([a, g2]), heading), a);
      expect(referenceImage(info, selectionOf([a, g2])), a);
      expect(referenceImage(info, selectionOf([heading])), isNull);
    });

    test('files an image by the album insertion rule', () {
      var parts = <AlbumPart>[
        image("a.jpg", 100),
        Heading(text: "H"),
        ImageGroup(images: [image("g1.jpg", 200), image("g2.jpg", 400)]),
        image("c.jpg", 500),
      ];

      // Directly behind the last entry that is not later — the heading is
      // passed over, the group counts with its earliest image.
      expect(insertIndexByDate(parts, 150), 1);
      expect(insertIndexByDate(parts, 250), 3);
      expect(insertIndexByDate(parts, 600), 4);
      // Nothing is that old: before the earliest-dated entry.
      expect(insertIndexByDate(parts, 50), 0);
      // Nothing dated at all: at the end.
      expect(insertIndexByDate([Heading(text: "H")], 50), 1);
    });
  });

  group('the "Adjust recording time…" tile action', () {
    String albumJson() => AlbumInfo(
          path: "",
          title: "Zwei Kameras",
          parts: [
            image("a.jpg", millis(at(2002, 3, 3, 9, 0))),
            image("late.jpg", millis(at(2002, 3, 3, 10, 0))),
            image("c.jpg", millis(at(2002, 3, 3, 14, 0))),
          ],
        ).toString();

    testWidgets('shows the offset and the count and saves the new order',
        (tester) async {
      var requests = <http.Request>[];
      var client = clientHandling(
        (request) => request.method == "PUT"
            ? http.Response("", 200)
            : http.Response(albumJson(), 200),
        requests: requests,
      );

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);
        // The long press selected the first tile; select the second one.
        await tapTile(tester, "late.jpg");
        expect(albumState(tester).selection, hasLength(1));

        await tester.tap(tool("late.jpg", Icons.more_time));
        await tester.pumpAndSettle();

        // The dialog names the reference and what it is about to do.
        expect(find.text("Adjust recording time"), findsOneWidget);
        expect(
          tester.widget<Text>(find.byKey(const Key("adjust-reference"))).data,
          "late.jpg: 2002-03-03 10:00:00",
        );
        expect(
          tester.widget<Text>(find.byKey(const Key("adjust-count"))).data,
          "Applies to 1 image",
        );
        expect(
          tester.widget<Text>(find.byKey(const Key("adjust-offset"))).data,
          "Nothing to adjust",
        );
        expect(find.byKey(const Key("adjust-help")), findsOneWidget);

        // The camera was five hours and two minutes behind.
        await tester.enterText(
          find.byKey(const Key("adjust-time")),
          "2002-03-03 15:02:00",
        );
        await tester.pumpAndSettle();
        expect(
          tester.widget<Text>(find.byKey(const Key("adjust-offset"))).data,
          "+5 h 2 min",
        );

        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();

        // The image moved behind the one it now follows.
        expect(partNames(album(tester)), ["a.jpg", "c.jpg", "late.jpg"]);
        expect(requests.where((r) => r.method == "PUT"), isEmpty);

        await tester.tap(find.byIcon(Icons.save));
        await tester.pumpAndSettle();
      });

      var put = requests.where((r) => r.method == "PUT").single;
      var saved = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;
      expect([for (var part in saved.parts) (part as ImagePart).name],
          ["a.jpg", "c.jpg", "late.jpg"]);
      expect(
          (saved.parts.last as ImagePart).date, millis(at(2002, 3, 3, 15, 2)));
    });

    testWidgets('writes nothing when the time was not changed', (tester) async {
      var requests = <http.Request>[];
      var client = clientHandling(
        (request) => request.method == "PUT"
            ? http.Response("", 200)
            : http.Response(albumJson(), 200),
        requests: requests,
      );

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);
        await tapTile(tester, "late.jpg");

        await tester.tap(tool("late.jpg", Icons.more_time));
        await tester.pumpAndSettle();
        // Confirming the time that is already there.
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();

        expect(find.text("Nothing to adjust"), findsOneWidget);
        expect(albumState(tester).dirty, isFalse);
        expect(partNames(album(tester)), ["a.jpg", "late.jpg", "c.jpg"]);
        expect(requests.where((r) => r.method == "PUT"), isEmpty);
        expect(imageNames(album(tester)), ["a.jpg", "late.jpg", "c.jpg"]);
      });
    });
  });
}
