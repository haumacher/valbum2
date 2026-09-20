/// Probe for #112: the map URL with a template that repeats its placeholders
/// and a position in the southern and western hemispheres.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/image_properties.dart';
import 'package:valbum_ui/resource.dart';

void main() {
  test('a template repeating {lat}/{lon} is filled everywhere, negatives kept',
      () {
    var location = GeoLocation(latitude: -33.9180001, longitude: -70.6);
    var url = mapUrlFor(
      "https://www.openstreetmap.org/?mlat={lat}&mlon={lon}#map=16/{lat}/{lon}",
      location,
    );
    expect(url,
        "https://www.openstreetmap.org/?mlat=-33.918000&mlon=-70.600000#map=16/-33.918000/-70.600000");
    expect(mapLocationText(location), contains("-33.918000, -70.600000"));
  });

  test('a template without placeholders is answered as it is', () {
    expect(mapUrlFor("https://maps.example/", GeoLocation(latitude: 1, longitude: 2)),
        "https://maps.example/");
  });
}
