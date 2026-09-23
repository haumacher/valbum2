/// The Android manifest declares what the camera-roll sync needs (issue #166):
/// without `ACCESS_MEDIA_LOCATION` Android 10+ hands the app every photograph
/// with its position redacted, and the sync uploads the zeros for good.
library;

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('the manifest asks for the media location', () {
    var manifest =
        File("android/app/src/main/AndroidManifest.xml").readAsStringSync();
    expect(manifest, contains("android.permission.ACCESS_MEDIA_LOCATION"));
    expect(manifest, contains("android.permission.READ_MEDIA_IMAGES"));
  });
}
