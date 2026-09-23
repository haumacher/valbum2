/// Probe of issue #154 composed with a stored crop: a crop written before the
/// covering rule (the picture letterboxed inside the square, shifted aside) is
/// left exactly as it is by opening and applying the properties, and is pulled
/// into cover by the first gesture that touches it.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'index_picture_crop_test.dart' hide main;
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The fixture album with a stored crop that does not cover the square: a 4:3
/// landscape at scale 1 (letterboxed) shifted 40 units to the right.
String albumWithLetterboxedCrop() => fixture("album.json").replaceFirst(
      '"parts": [',
      '"indexPicture": {"image": "landscape.jpg", "scale": 1, "tx": 40, "ty": 0}, '
      '"parts": [',
    );

void main() {
  testWidgets('a stored crop that does not cover is left alone until touched',
      (tester) async {
    var client = clientReturning(albumWithLetterboxedCrop());
    await settle(tester, () => tester.pumpWidget(VAlbumApp(client: client)));
    await openProperties(tester, choose: false);

    // Opening and applying without a gesture rewrites nothing: the stored
    // statement stands, letterboxed or not.
    await tap(tester, find.text(testL10n.apply));
    var untouched = album(tester).indexPicture!;
    expect(untouched.scale, 1);
    expect(untouched.tx, 40);
    expect(untouched.ty, 0);

    // One zoom step touches it: the scale is raised to the picture's own
    // least (4/3 beats 1 × 1.25), and the offset is pulled into the room the
    // covering picture has, 150·(1 − 3/4) = 37.5.
    await openPropertiesMenu(tester);
    await tap(tester, find.byTooltip(testL10n.zoomIn));
    await tap(tester, find.text(testL10n.apply));
    var covered = album(tester).indexPicture!;
    expect(covered.scale, closeTo(4 / 3, 1e-9));
    expect(covered.tx, closeTo(37.5, 1e-9));
    expect(covered.ty, 0);
  });
}
