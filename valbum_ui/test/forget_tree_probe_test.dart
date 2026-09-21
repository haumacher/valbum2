/// Review probes of `VAlbumRouterDelegate.forgetTree` (issue #134): a tree is
/// forgotten by path segments, never by a string prefix, and the root means
/// everything.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'util/fixtures.dart';

String listing(String path) =>
    '["ListingInfo", {"path":"$path","title":"$path","folders":[]}]';

void main() {
  late List<http.Request> requests;
  late VAlbumRouterDelegate delegate;

  int fetches(String dataPath) => requests
      .where((r) =>
          r.method == "GET" &&
          r.url.queryParameters["type"] == "json" &&
          Uri.decodeComponent(r.url.path) == "/valbum/data/$dataPath")
      .length;

  setUp(() {
    requests = [];
    delegate = VAlbumRouterDelegate(
      client: clientHandling(
        (request) => http.Response(
          listing(Uri.decodeComponent(request.url.path)),
          200,
          headers: {"content-type": "application/json; charset=utf-8"},
        ),
        requests: requests,
      ),
      initialRoute: parseRoute(Uri.parse("/")),
    );
    addTearDown(delegate.dispose);
  });

  Future<void> visitAll() async {
    await delegate.resourceAt(["A"]);
    await delegate.resourceAt(["A", "B"]);
    await delegate.resourceAt(["AB"]);
    await delegate.resourceAt(["A B"]);
    await delegate.resourceAt([]);
  }

  test('forgets a folder and what is below it, and nothing that merely starts alike',
      () async {
    await visitAll();
    expect(fetches("A/"), 1);

    delegate.forgetTree(["A"]);
    await visitAll();

    expect(fetches("A/"), 2, reason: "The folder itself is fetched again.");
    expect(fetches("A/B/"), 2, reason: "What lies below it is fetched again.");
    expect(fetches("AB/"), 1, reason: "'AB' is not below 'A'.");
    expect(fetches("A B/"), 1, reason: "'A B' is not below 'A'.");
    expect(fetches(""), 1, reason: "The root is above, not below.");
  });

  test('forgetting the root forgets everything', () async {
    await visitAll();

    delegate.forgetTree([]);
    await visitAll();

    for (var path in ["", "A/", "A/B/", "AB/", "A B/"]) {
      expect(fetches(path), 2, reason: path);
    }
  });
}
