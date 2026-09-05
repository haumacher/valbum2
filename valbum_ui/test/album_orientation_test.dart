import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_edit.dart';
import 'package:valbum_ui/album_layout.dart' as layouter;
import 'package:valbum_ui/resource.dart';

/// The four operation tables of the retired Java
/// `de.haumacher.imageServer.shared.util.Orientations`, in the order of the
/// [Orientation] enum (JPEG codes 1..8). They are the contract the derived
/// group implementation in `album_edit.dart` has to reproduce.
const List<Orientation> _all = Orientation.values;

const Map<Orientation, Orientation> _javaRotL = {
  Orientation.identity: Orientation.rotL,
  Orientation.flipH: Orientation.rotLFlipV,
  Orientation.rot180: Orientation.rotR,
  Orientation.flipV: Orientation.rotLFlipH,
  Orientation.rotLFlipV: Orientation.flipV,
  Orientation.rotL: Orientation.rot180,
  Orientation.rotLFlipH: Orientation.flipH,
  Orientation.rotR: Orientation.identity,
};

const Map<Orientation, Orientation> _javaRotR = {
  Orientation.rotL: Orientation.identity,
  Orientation.rotLFlipV: Orientation.flipH,
  Orientation.rotR: Orientation.rot180,
  Orientation.rotLFlipH: Orientation.flipV,
  Orientation.flipV: Orientation.rotLFlipV,
  Orientation.rot180: Orientation.rotL,
  Orientation.flipH: Orientation.rotLFlipH,
  Orientation.identity: Orientation.rotR,
};

const Map<Orientation, Orientation> _javaFlipH = {
  Orientation.identity: Orientation.flipH,
  Orientation.flipH: Orientation.identity,
  Orientation.rot180: Orientation.flipV,
  Orientation.flipV: Orientation.rot180,
  Orientation.rotLFlipV: Orientation.rotR,
  Orientation.rotL: Orientation.rotLFlipH,
  Orientation.rotLFlipH: Orientation.rotL,
  Orientation.rotR: Orientation.rotLFlipV,
};

const Map<Orientation, Orientation> _javaFlipV = {
  Orientation.identity: Orientation.flipV,
  Orientation.flipH: Orientation.rot180,
  Orientation.rot180: Orientation.flipH,
  Orientation.flipV: Orientation.identity,
  Orientation.rotLFlipV: Orientation.rotL,
  Orientation.rotL: Orientation.rotLFlipV,
  Orientation.rotLFlipH: Orientation.rotR,
  Orientation.rotR: Orientation.rotLFlipH,
};

void main() {
  group('the orientation group', () {
    test('every orientation is a transformation and back', () {
      for (var orientation in _all) {
        expect(PlaneTransform.of(orientation).orientation, orientation);
      }
      // The eight transformations are pairwise distinct: the group has order 8.
      expect(_all.map(PlaneTransform.of).toSet(), hasLength(8));
    });

    test('the odd quarter turns are exactly the codes >= 5', () {
      // This is what `Orientations.widthInt` says: the display width is the raw
      // height exactly for the codes 5..8.
      for (var orientation in _all) {
        var swapped =
            layouter.Orientations.widthInt(orientation, 100, 50) == 50;
        expect(
          PlaneTransform.of(orientation).swapsDimensions,
          swapped,
          reason: "$orientation",
        );
      }
    });

    test('identity is neutral', () {
      for (var orientation in _all) {
        expect(OrientationOps.concat(orientation, Orientation.identity),
            orientation);
        expect(OrientationOps.concat(Orientation.identity, orientation),
            orientation);
      }
    });

    test('inverse undoes the transformation', () {
      for (var orientation in _all) {
        var tx = PlaneTransform.of(orientation);
        expect(tx.concat(tx.inverse), PlaneTransform.identity);
        expect(tx.inverse.concat(tx), PlaneTransform.identity);
      }
    });
  });

  group('the rotation operations', () {
    test('reproduce the Java rotL table', () {
      for (var orientation in _all) {
        expect(OrientationOps.rotL(orientation), _javaRotL[orientation],
            reason: "rotL($orientation)");
      }
    });

    test('reproduce the Java rotR table', () {
      for (var orientation in _all) {
        expect(OrientationOps.rotR(orientation), _javaRotR[orientation],
            reason: "rotR($orientation)");
      }
    });

    test('reproduce the Java flipH table', () {
      for (var orientation in _all) {
        expect(OrientationOps.flipH(orientation), _javaFlipH[orientation],
            reason: "flipH($orientation)");
      }
    });

    test('reproduce the Java flipV table', () {
      for (var orientation in _all) {
        expect(OrientationOps.flipV(orientation), _javaFlipV[orientation],
            reason: "flipV($orientation)");
      }
    });

    test('agree with the rotL of the layout library', () {
      for (var orientation in _all) {
        expect(
          OrientationOps.rotL(orientation),
          layouter.Orientations.rotL(orientation),
          reason: "$orientation",
        );
      }
    });

    test('obey the group laws', () {
      for (var orientation in _all) {
        // rotR o rotL = identity
        expect(
            OrientationOps.rotR(OrientationOps.rotL(orientation)), orientation);
        expect(
            OrientationOps.rotL(OrientationOps.rotR(orientation)), orientation);

        // rotR^4 = identity
        var rotated = orientation;
        for (var i = 0; i < 4; i++) {
          rotated = OrientationOps.rotR(rotated);
        }
        expect(rotated, orientation);

        // Two quarter turns are a half turn, in either direction.
        expect(OrientationOps.rotL(OrientationOps.rotL(orientation)),
            OrientationOps.rotR(OrientationOps.rotR(orientation)));

        // flipV^2 = flipH^2 = identity
        expect(OrientationOps.flipV(OrientationOps.flipV(orientation)),
            orientation);
        expect(OrientationOps.flipH(OrientationOps.flipH(orientation)),
            orientation);

        // A vertical flip is a horizontal flip turned by 180 degrees.
        expect(
          OrientationOps.flipV(orientation),
          OrientationOps.rotL(
            OrientationOps.rotL(OrientationOps.flipH(orientation)),
          ),
        );
      }
    });

    test('rotate the identity into the named orientations', () {
      expect(OrientationOps.rotR(Orientation.identity), Orientation.rotR);
      expect(OrientationOps.rotL(Orientation.identity), Orientation.rotL);
      expect(OrientationOps.flipV(Orientation.identity), Orientation.flipV);
      expect(OrientationOps.flipH(Orientation.identity), Orientation.flipH);
      expect(
        OrientationOps.rotL(OrientationOps.rotL(Orientation.identity)),
        Orientation.rot180,
      );
    });

    test('a quarter turn swaps width and height', () {
      for (var orientation in _all) {
        expect(
          PlaneTransform.of(OrientationOps.rotR(orientation)).swapsDimensions,
          !PlaneTransform.of(orientation).swapsDimensions,
        );
      }
    });
  });

  group('the rendering delta', () {
    test('is empty while nothing was rotated', () {
      for (var orientation in _all) {
        expect(
          OrientationOps.delta(orientation, orientation),
          PlaneTransform.identity,
        );
      }
    });

    test('is a quarter turn after one rotation, from every orientation', () {
      for (var orientation in _all) {
        // The delta is the conjugate `D o g o D^-1` of the operation `g`, so a
        // rotation of the raw data turns the *display* the other way round as
        // soon as the image is displayed mirrored. Rotating a mirrored image
        // by the (unmirrored) operation of the model is what the GWT client
        // did; the tile shows what the stored orientation means.
        var mirrored = PlaneTransform.of(orientation).mirrored;

        var right = OrientationOps.delta(
          orientation,
          OrientationOps.rotR(orientation),
        );
        expect(right.mirrored, isFalse, reason: "$orientation");
        // Counter-clockwise quarter turns: rotating right is three of them.
        expect(right.quarterTurns, mirrored ? 1 : 3, reason: "$orientation");

        var left = OrientationOps.delta(
          orientation,
          OrientationOps.rotL(orientation),
        );
        expect(left.mirrored, isFalse, reason: "$orientation");
        expect(left.quarterTurns, mirrored ? 3 : 1, reason: "$orientation");
      }
    });

    test('is a mirror after a flip, from every orientation', () {
      for (var orientation in _all) {
        var delta = OrientationOps.delta(
          orientation,
          OrientationOps.flipV(orientation),
        );
        expect(delta.mirrored, isTrue, reason: "$orientation");
        expect(delta.swapsDimensions, isFalse, reason: "$orientation");
      }
    });

    test('composes back to the identity', () {
      for (var from in _all) {
        for (var to in _all) {
          var forth = OrientationOps.delta(from, to);
          var back = OrientationOps.delta(to, from);
          expect(forth.concat(back), PlaneTransform.identity);
        }
      }
    });
  });
}
