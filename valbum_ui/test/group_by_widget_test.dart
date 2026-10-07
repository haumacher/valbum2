/// "Group by…" in the album's edit mode, issue #238: the dialog previews
/// the headings with their counts, Apply puts them into the edit buffer,
/// Cancel takes them back, and Save writes them with the album's PUT.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:intl/intl.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/group_by_dialog.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/edit_drag.dart';
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

final PlaceTag florence =
    PlaceTag(geonameId: 3176959, name: "Florence", kind: PlaceKind.place);
final PlaceTag siena =
    PlaceTag(geonameId: 3166548, name: "Siena", kind: PlaceKind.place);

/// A photo taken at noon UTC on the given day of July 2026 — the same day in
/// every zone a test may run in.
ImagePart photo(String name, int day, PlaceTag town) => ImagePart(
      name: name,
      date: DateTime.utc(2026, 7, day, 12).millisecondsSinceEpoch,
      width: 2048,
      height: 1536,
      places: PlaceInfo(tags: [town]),
    );

/// Two days in Tuscany, with an old heading in front.
String trip() => AlbumInfo(
      path: "",
      title: "Tuscany",
      parts: [
        Heading(text: "Old", level: 1),
        photo("f1.jpg", 14, florence),
        photo("f2.jpg", 14, florence),
        photo("s1.jpg", 14, siena),
        photo("s2.jpg", 15, siena),
      ],
    ).toString();

String day(int day) => DateFormat.yMMMEd("en").format(DateTime(2026, 7, day));

VAlbumClient tripClient(List<http.Request> requests) => clientHandling(
      (request) => request.method == "PUT"
          ? http.Response("", 200)
          : http.Response(trip(), 200),
      requests: requests,
    );

/// Loads the album, enters the edit mode and takes the photo the long press
/// selected out again, so that the whole album is grouped.
Future<void> pumpEditMode(WidgetTester tester, VAlbumClient client) async {
  await tester.pumpWidget(VAlbumApp(client: client));
  await tester.pumpAndSettle();
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
  albumStateOf(tester).clearSelection();
  await tester.pumpAndSettle();
  expect(albumStateOf(tester).selection, isEmpty);
}

Future<void> openGroupBy(WidgetTester tester) async {
  await tester.tap(find.byIcon(Icons.more_vert).last);
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key("group-by")));
  await tester.pumpAndSettle();
}

Finder previewLine(int index) => find.byKey(Key("group-by-preview-$index"));

/// The texts of a preview line: the heading and its count.
List<String> textsOf(WidgetTester tester, Finder line) => [
      for (var text in tester.widgetList<Text>(
          find.descendant(of: line, matching: find.byType(Text))))
        text.data!,
    ];

bool chipSelected(WidgetTester tester, String key) =>
    tester.widget<ChoiceChip>(find.byKey(Key(key))).selected;

const List<String> grouped = [
  "Heading(Tue, Jul 14, 2026)",
  "Heading(Florence)",
  "f1.jpg",
  "f2.jpg",
  "Heading(Siena)",
  "s1.jpg",
  "Heading(Wed, Jul 15, 2026)",
  "Heading(Siena)",
  "s2.jpg",
];

void main() {
  testWidgets('the dialog previews Day › Town with counts', (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, tripClient(requests));
      await openGroupBy(tester);

      expect(find.byKey(const Key("group-by-dialog")), findsOneWidget);
      expect(find.text(testL10n.groupByScopeAlbum), findsOneWidget);
      // The album has a section, so both modes are offered.
      expect(find.byKey(const Key("group-by-mode-addSubsections")),
          findsOneWidget);
      // And it will be replaced, which the dialog says.
      expect(find.text(testL10n.groupByWillReplace(1)), findsOneWidget);

      expect(textsOf(tester, previewLine(0)), [day(14), "3 photos"]);
      expect(textsOf(tester, previewLine(1)), ["Florence", "2 photos"]);
      expect(textsOf(tester, previewLine(2)), ["Siena", "1 photo"]);
      expect(textsOf(tester, previewLine(3)), [day(15), "1 photo"]);
      expect(textsOf(tester, previewLine(4)), ["Siena", "1 photo"]);
      expect(previewLine(5), findsNothing);

      // Previewing writes nothing.
      expect(albumStateOf(tester).dirty, isFalse);
    });
  });

  testWidgets('a key without any keyed photo says why and applies nothing',
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, tripClient([]));
      await openGroupBy(tester);

      await tester.tap(find.byKey(const ValueKey("group-by-section")));
      await tester.pumpAndSettle();
      var district = find.byKey(const Key("group-by-section-district")).last;
      await tester.ensureVisible(district);
      await tester.pumpAndSettle();
      await tester.tap(district);
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("group-by-nothing")), findsOneWidget);
      expect(previewLine(0), findsNothing);
      var apply = tester
          .widget<ButtonStyleButton>(find.byKey(const Key("group-by-apply")));
      expect(apply.onPressed, isNull);
    });
  });

  testWidgets('Apply fills the buffer, Save posts the headings',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, tripClient(requests));
      await openGroupBy(tester);
      await tester.tap(find.byKey(const Key("group-by-apply")));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("group-by-dialog")), findsNothing);
      expect(partNames(albumOf(tester)), grouped);
      expect(albumStateOf(tester).dirty, isTrue);
      expect(albumStateOf(tester).editMode, isTrue);
      // Shown at once.
      expect(find.text("Florence"), findsOneWidget);
      expect(requests.where((r) => r.method == "PUT"), isEmpty);

      await tester.tap(find.byIcon(Icons.save));
      await tester.pumpAndSettle();
    });

    var put = requests.where((r) => r.method == "PUT").single;
    var saved = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;
    expect(partNames(saved), grouped);
    expect([
      for (var part in saved.parts)
        if (part is Heading) part.level
    ], [
      1,
      2,
      2,
      1,
      2
    ]);
  });

  testWidgets('Cancel of the edit discards the applied headings',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, tripClient(requests));
      await openGroupBy(tester);
      await tester.tap(find.byKey(const Key("group-by-apply")));
      await tester.pumpAndSettle();
      expect(partNames(albumOf(tester)), grouped);

      await tester.tap(find.byKey(const Key("edit-cancel")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("discard-changes")));
      await tester.pumpAndSettle();

      expect(partNames(albumOf(tester)),
          ["Heading(Old)", "f1.jpg", "f2.jpg", "s1.jpg", "s2.jpg"]);
    });
    expect(requests.where((r) => r.method == "PUT"), isEmpty);
  });

  testWidgets('the dialog\'s Cancel changes nothing', (tester) async {
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, tripClient([]));
      await openGroupBy(tester);
      await tester.tap(find.byKey(const Key("group-by-cancel")));
      await tester.pumpAndSettle();

      expect(partNames(albumOf(tester)),
          ["Heading(Old)", "f1.jpg", "f2.jpg", "s1.jpg", "s2.jpg"]);
      expect(albumStateOf(tester).dirty, isFalse);
    });
  });

  testWidgets('a selection is grouped alone, and the dialog says so',
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, tripClient([]));
      var album = albumOf(tester);
      var state = albumStateOf(tester);
      // The two photos in Siena, as a ctrl-click on each would select them.
      state.selection.addAll([album.parts[3], album.parts[4]]);
      await tester.pumpAndSettle();

      await openGroupBy(tester);
      // Two photos selected: the selection is grouped by default.
      expect(
          find.text(testL10n.groupByScopeChoiceSelection(2)), findsOneWidget);
      expect(chipSelected(tester, "group-by-scope-selection"), isTrue);
      expect(chipSelected(tester, "group-by-scope-album"), isFalse);
      // No section in the selection: nothing to keep, one mode.
      expect(
          find.byKey(const Key("group-by-mode-addSubsections")), findsNothing);
      await tester.tap(find.byKey(const Key("group-by-apply")));
      await tester.pumpAndSettle();

      expect(partNames(albumOf(tester)), [
        "Heading(Old)",
        "f1.jpg",
        "f2.jpg",
        "Heading(Tue, Jul 14, 2026)",
        "Heading(Siena)",
        "s1.jpg",
        "Heading(Wed, Jul 15, 2026)",
        "Heading(Siena)",
        "s2.jpg",
      ]);
    });
  });

  testWidgets('one selected photo: the whole album is preselected',
      (tester) async {
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: tripClient([])));
      await tester.pumpAndSettle();
      // The long press leaves the photo it was made on selected.
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      expect(albumStateOf(tester).selection, hasLength(1));

      await openGroupBy(tester);
      expect(
          find.text(testL10n.groupByScopeChoiceSelection(1)), findsOneWidget);
      expect(chipSelected(tester, "group-by-scope-album"), isTrue);
      expect(chipSelected(tester, "group-by-scope-selection"), isFalse);
      // The preview is the album's.
      expect(textsOf(tester, previewLine(0)), [day(14), "3 photos"]);

      // The other choice, and the preview follows it.
      await tester.tap(find.byKey(const Key("group-by-scope-selection")));
      await tester.pumpAndSettle();
      expect(chipSelected(tester, "group-by-scope-selection"), isTrue);
      expect(textsOf(tester, previewLine(0)), [day(14), "1 photo"]);
      expect(textsOf(tester, previewLine(1)), ["Florence", "1 photo"]);
      // The photos after it stay under the heading they were under.
      expect(textsOf(tester, previewLine(2)), ["Old", "3 photos"]);
      expect(previewLine(3), findsNothing);
    });
  });

  testWidgets('without a selection there is no choice', (tester) async {
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, tripClient([]));
      await openGroupBy(tester);
      expect(find.byKey(const Key("group-by-scope-selection")), findsNothing);
      expect(find.byKey(const Key("group-by-scope-album")), findsNothing);
      expect(find.text(testL10n.groupByScopeAlbum), findsOneWidget);
    });
  });

  testWidgets('keeps the sections and adds subsections', (tester) async {
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, tripClient([]));
      await openGroupBy(tester);
      await tester.tap(find.byKey(const Key("group-by-mode-addSubsections")));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey("group-by-section")), findsNothing);
      expect(find.byKey(const ValueKey("group-by-added")), findsOneWidget);
      expect(find.byKey(const Key("group-by-replaced")), findsNothing);
      expect(textsOf(tester, previewLine(0)), ["Florence", "2 photos"]);
      expect(textsOf(tester, previewLine(1)), ["Siena", "2 photos"]);

      await tester.tap(find.byKey(const Key("group-by-apply")));
      await tester.pumpAndSettle();
      expect(partNames(albumOf(tester)), [
        "Heading(Old)",
        "Heading(Florence)",
        "f1.jpg",
        "f2.jpg",
        "Heading(Siena)",
        "s1.jpg",
        "s2.jpg",
      ]);
    });
  });

  testWidgets('the dialog speaks German', (tester) async {
    var de = l10nOf(const Locale("de"));
    var parts = (Resource.fromString(trip()) as AlbumInfo).parts;
    await tester.pumpWidget(localizedApp(
      Builder(
        builder: (context) => Center(
          child: ElevatedButton(
            onPressed: () => showGroupByDialog(
              context: context,
              parts: parts,
              selection: {},
              minRating: -1,
            ),
            child: const Text("open"),
          ),
        ),
      ),
      locale: const Locale("de"),
    ));
    await tester.tap(find.text("open"));
    await tester.pumpAndSettle();

    expect(find.text(de.groupByTitle), findsOneWidget);
    expect(find.text(de.groupByScopeAlbum), findsOneWidget);
    expect(find.text(de.groupByModeReplace), findsOneWidget);
    expect(find.text(de.groupBySectionKey), findsOneWidget);
    expect(find.text(de.groupByKeyDay), findsOneWidget);
    expect(find.text(de.groupByPreview), findsOneWidget);
    expect(find.text(de.photoCount(3)), findsOneWidget);
    // The day in the editor's locale.
    expect(
      find.text(DateFormat.yMMMEd("de").format(DateTime(2026, 7, 14))),
      findsOneWidget,
    );
    expect(de.groupByAction, isNot(testL10n.groupByAction));
    expect(de.groupByKeyDayAndTown, "Tag und Ort");
  });
}
