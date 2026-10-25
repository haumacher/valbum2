/// Probe of issue #315: the cuts of the space settings are typed the way the
/// author's German keyboard types them — a decimal comma — and a value with
/// two separators or a thousands group is no cut.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/space_settings_view.dart';

void main() {
  test('a decimal comma is a cut, as a point is', () {
    expect(SpaceSettingsScreenState.parseThreshold("0,97"), 0.97);
    expect(SpaceSettingsScreenState.parseThreshold(" 0.97 "), 0.97);
    expect(SpaceSettingsScreenState.parseThreshold("1"), 1.0);
    expect(SpaceSettingsScreenState.parseThreshold(""), 0,
        reason: "empty is the built-in cut");
  });

  test('what is no number between 0 and 1 is no cut', () {
    expect(SpaceSettingsScreenState.parseThreshold("0,9,7"), isNull);
    expect(SpaceSettingsScreenState.parseThreshold("1,5"), isNull);
    expect(SpaceSettingsScreenState.parseThreshold("-0,1"), isNull);
    expect(SpaceSettingsScreenState.parseThreshold("NaN"), isNull);
  });
}
