import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/routes.dart';

/// Probe of issue #228: a viewer opened from the overview keeps its way back
/// for photographs at the root of the space and for names with blanks.
void main() {
  test('a photograph at the root of the space', () {
    var route = const ImageRoute([], "IMG 1.jpg", fromDuplicates: true);
    var parsed = parseRoute(Uri.parse(route.path));
    expect(parsed, route);
    expect(parsed.up, const DuplicatesRoute());
  });

  test('a nested album with blanks, paged to the next photograph', () {
    var route = const ImageRoute(["2020", "2020-07-01 Rom Tour"], "a b.jpg",
        fromDuplicates: true);
    var parsed = parseRoute(Uri.parse(route.path)) as ImageRoute;
    expect(parsed, route);
    var next = parsed.withName("c.jpg");
    expect(next.up, const DuplicatesRoute());
    expect(parseRoute(Uri.parse(next.path)), next);
  });

  test('an ordinary viewer still goes back to its album', () {
    var route = const ImageRoute(["A"], "x.jpg");
    expect(parseRoute(Uri.parse(route.path)), route);
    expect(route.up, const ListingOrAlbumRoute(["A"]));
  });
}
