/// Where the caption tooltip of an album tile is shown, issue #247: inside
/// the tile on its bottom edge with a small inset, not below the tile's
/// middle where a tooltip goes by default and covers the picture.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'place_caption_test.dart' show pumpAlbum, tileCaption;
import 'util/fake_image_http.dart';
import 'util/fake_video_player.dart';

/// The position of a tooltip of [tooltip] over a tile of [tile] at [center]
/// in an overlay of [overlay].
Offset position(
  Size tooltip, {
  Offset center = const Offset(300, 300),
  Size tile = const Size(200, 150),
  Size overlay = const Size(800, 600),
}) =>
    tileCaptionPosition(
      TooltipPositionContext(
        target: center,
        targetSize: tile,
        tooltipSize: tooltip,
        verticalOffset: 24,
        overlaySize: overlay,
      ),
    );

void main() {
  setUp(() {
    VideoPlayerPlatform.instance = FakeVideoPlayerPlatform();
  });

  group('the position', () {
    test('is on the bottom edge of the tile, centred, inside it', () {
      // The tile spans 200..400 × 225..375.
      expect(
        position(const Size(100, 30)),
        const Offset(250, 375 - tileCaptionInset - 30),
      );
    });

    test('of a caption taller than the tile starts at its top', () {
      expect(position(const Size(100, 200)).dy, 225 + tileCaptionInset);
    });

    test('stays in the overlay where the tile reaches out of it', () {
      // A tile at the left edge, cut off by the bottom of the screen.
      var at = position(
        const Size(150, 30),
        center: const Offset(40, 580),
        tile: const Size(80, 120),
      );
      expect(at, const Offset(0, 600 - 30));
    });
  });

  testWidgets('the caption of a tile is shown at the bottom of the tile', (
    tester,
  ) async {
    await withFakeImageHttp(() async {
      await pumpAlbum(tester, <http.Request>[]);
    });

    var tooltip = tileCaption("described.jpg");
    var tile = tester.getRect(tooltip);
    tester.state<TooltipState>(tooltip).ensureTooltipVisible();
    await tester.pumpAndSettle();

    var shown = find.text("At the lake");
    expect(shown, findsOneWidget);
    // The box of the tooltip: the text's nearest ancestor with a decoration.
    var box = tester.getRect(
      find.ancestor(of: shown, matching: find.byType(DecoratedBox)).first,
    );
    expect(box.bottom, closeTo(tile.bottom - tileCaptionInset, 0.5));
    expect(box.center.dx, closeTo(tile.center.dx, 0.5));
    expect(box.top, greaterThan(tile.center.dy), reason: "Below the middle.");
    expect(box.left, greaterThanOrEqualTo(tile.left));
    expect(box.right, lessThanOrEqualTo(tile.right));

    Tooltip.dismissAllToolTips();
    await tester.pumpAndSettle();
  });
}
