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

  // The application id is what a store publishes the app under, for good
  // (the store cannot rename it), and what Android updates an installed app
  // by: `de.haumacher.valbum`, not the Flutter template's `valbum_ui`.
  test('the application id is de.haumacher.valbum', () {
    var gradle = File("android/app/build.gradle.kts").readAsStringSync();
    expect(gradle, contains('applicationId = "de.haumacher.valbum"'));
    expect(gradle, contains('namespace = "de.haumacher.valbum"'));
    expect(
      File("android/app/src/main/java/de/haumacher/valbum/MainActivity.java")
          .readAsStringSync(),
      contains("package de.haumacher.valbum;"),
    );
  });
}
