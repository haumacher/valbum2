/// What a search answers of a photograph is partial (issue #243): no faces,
/// no tags, the places trimmed to the place line. The viewer fetches the
/// photograph's own description when it shows such a part and adopts what it
/// carries; the criteria bar says how many photographs were found.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/image_properties.dart';
import 'package:valbum_ui/main.dart';

import 'search_select_test.dart' show SelectSpace, tile;
import 'search_view_test.dart' show chooseAnne, json, pathOf, pumpRoute, settle;
import 'util/l10n.dart';

/// A partial part of a search result named [name], as the server answers it.
String partial(String name, int index) =>
    '["ImagePart",{"kind":"IMAGE","name":"$name",'
    '"date":${1015113600000 + index * 10000},'
    '"width":2048,"height":1536,"orientation":"IDENTITY",'
    '"location":{"latitude":49.0134,"longitude":8.4044},'
    '"places":{"tags":[{"name":"Germany","kind":"COUNTRY"},'
    '{"name":"Karlsruhe","kind":"PLACE"}],"pending":""},'
    '"ref":{"hash":"","path":"$name"},"partial":true}]';

/// The photograph's own description of the part [name] (its file name).
String description(String name) =>
    '["ImagePart",{"kind":"IMAGE","name":"$name","date":1015113600000,'
    '"width":2048,"height":1536,"orientation":"IDENTITY",'
    '"location":{"latitude":49.0134,"longitude":8.4044},'
    '"faces":[{"index":0,"x":0.1,"y":0.1,"w":0.2,"h":0.2,"person":"p1",'
    '"confirmed":true,"state":"CONFIRMED"}],'
    '"tags":[{"x":0.1,"y":0.1,"w":0.2,"h":0.2,"person":"p1",'
    '"state":"CONFIRMED"}],'
    '"places":{"tags":['
    '{"geonameId":2921044,"name":"Germany","kind":"COUNTRY","country":"DE"},'
    '{"geonameId":2953481,"name":"Baden-Württemberg","kind":"ADM1",'
    '"country":"DE"},'
    '{"geonameId":3214105,"name":"Regierungsbezirk Karlsruhe","kind":"ADM2",'
    '"country":"DE"},'
    '{"geonameId":2892794,"name":"Karlsruhe","kind":"PLACE","country":"DE"}],'
    '"pending":""}}]';

/// The space of the select tests, answering partial parts and the
/// photographs' own descriptions.
class PartialSpace extends SelectSpace {
  /// The status the descriptions are answered with.
  int descriptionStatus = 200;

  PartialSpace() {
    searchAnswer = '["AlbumInfo",{"kind":"SEARCH","parts":['
        '${partial("2020 Trip/x.jpg", 0)},${partial("2020 Trip/y.jpg", 1)},'
        '${partial("2021 Zoo/z.jpg", 2)}],'
        '"rights":[{"name":"view"},{"name":"download"}]}]';
  }

  @override
  http.Response answer(http.Request request) {
    var path = pathOf(request);
    if (request.method == "GET" &&
        request.url.queryParameters["type"] == "json" &&
        path.endsWith(".jpg")) {
      if (descriptionStatus != 200) {
        return http.Response(
          '["ErrorInfo",{"message":"The photograph is gone."}]',
          descriptionStatus,
          headers: {"content-type": "application/json; charset=utf-8"},
        );
      }
      return json(description(path.substring(path.lastIndexOf("/") + 1)));
    }
    return super.answer(request);
  }

  /// The descriptions asked, by their path.
  List<String> get descriptions => [
        for (var request in requests)
          if (request.method == "GET" &&
              request.url.queryParameters["type"] == "json" &&
              pathOf(request).endsWith(".jpg"))
            pathOf(request),
      ];
}

ImageViewState viewer(WidgetTester tester) =>
    tester.state<ImageViewState>(find.byType(ImageView));

/// Opens the search view, finds the three photographs and opens [name].
Future<PartialSpace> open(WidgetTester tester, String name,
    {int status = 200}) async {
  var space = PartialSpace()..descriptionStatus = status;
  await pumpRoute(tester, space, const SearchRoute([]));
  await chooseAnne(tester);
  await tester.tap(tile(name));
  await settle(tester);
  return space;
}

void main() {
  var l10n = l10nOf();

  testWidgets(
      'opening a partial photograph asks for its own description once and '
      'shows its faces', (tester) async {
    var space = await open(tester, "2020 Trip/x.jpg");

    expect(space.descriptions, ["/valbum/data/2020 Trip/x.jpg"]);
    var part = viewer(tester).part;
    expect(part.partial, isFalse);
    expect(part.faces, hasLength(1));
    expect(part.tags, hasLength(1));
    expect(part.places!.tags.map((t) => t.geonameId),
        [2921044, 2953481, 3214105, 2892794]);
    expect(find.byType(FaceRegions), findsOneWidget,
        reason: "The named face is drawn over the picture.");

    // The properties dialog sees the full places.
    showImageProperties(viewer(tester).context, part, editable: false);
    await settle(tester);
    expect(
        tester
            .widget<SelectableText>(
                find.byKey(const Key("property-place-text")))
            .data,
        l10n.propertyPlace("Karlsruhe · Baden-Württemberg, Germany"));
  });

  testWidgets('paging asks the next one, paging back asks nothing again',
      (tester) async {
    var space = await open(tester, "2020 Trip/x.jpg");

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    await settle(tester);
    expect(viewer(tester).part.name, "2020 Trip/y.jpg");
    expect(space.descriptions,
        ["/valbum/data/2020 Trip/x.jpg", "/valbum/data/2020 Trip/y.jpg"]);
    expect(viewer(tester).part.partial, isFalse);

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
    await settle(tester);
    expect(viewer(tester).part.name, "2020 Trip/x.jpg");
    expect(space.descriptions, hasLength(2),
        reason: "A completed part is asked nothing again.");
  });

  testWidgets('a refusal keeps the picture and says the server\'s sentence',
      (tester) async {
    await open(tester, "2020 Trip/x.jpg", status: 500);

    expect(find.byType(ImageView), findsOneWidget);
    expect(find.byKey(const Key("image-description-failed")), findsOneWidget);
    expect(find.text("The photograph is gone."), findsOneWidget);
    var part = viewer(tester).part;
    expect(part.partial, isTrue, reason: "The next visit asks again.");
    expect(part.places!.tags, hasLength(2),
        reason: "What the search answered stays.");
  });

  testWidgets('the criteria bar says how many photographs were found',
      (tester) async {
    var space = PartialSpace();
    await pumpRoute(tester, space, const SearchRoute([]));
    expect(find.byKey(const Key("search-count")), findsNothing,
        reason: "Nothing before a search.");

    await chooseAnne(tester);
    expect(tester.widget<Text>(find.byKey(const Key("search-count"))).data,
        l10n.searchCount(3));
    expect(l10n.searchCount(3), "3 photos");
    expect(l10n.searchCount(1), "1 photo");
    expect(space.descriptions, isEmpty,
        reason: "The grid asks no photograph's description.");
  });

  testWidgets('a result of none shows no count, only its sentence',
      (tester) async {
    var space = PartialSpace()
      ..searchAnswer = '["AlbumInfo",{"kind":"SEARCH","parts":[]}]';
    await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);

    expect(find.byKey(const Key("search-count")), findsNothing);
    expect(find.byKey(const Key("search-empty")), findsOneWidget);
  });
}
