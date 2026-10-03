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

  // A recipient's own personal link goes out through the sharer's own apps
  // (issue #201): Android 11+ lets the app see only the handlers it declares,
  // and nothing about it asks for the address book.
  test('the manifest declares the apps a personal link is sent through', () {
    var manifest =
        File("android/app/src/main/AndroidManifest.xml").readAsStringSync();
    expect(manifest, contains("<queries>"));
    for (var scheme in ["mailto", "sms", "https"]) {
      expect(manifest, contains('android:scheme="$scheme"'));
    }
    expect(manifest, contains("android.intent.action.SEND"));
    expect(manifest, isNot(contains("READ_CONTACTS")));
  });

  // A recipient picked out of the phone's address book (issue #201) needs no
  // permission: `ACTION_PICK` on the e-mail or phone rows hands back one row
  // with a read grant for that row alone. Nothing the plugin merges into the
  // app asks for the address book either, and nothing needs a `<queries>`
  // entry, because an implicit intent started for a result is not subject to
  // package visibility — the plugin starts the picker without resolving it.
  test('picking a contact asks for no permission', () {
    var plugin =
        File("packages/contact_pick/android/src/main/AndroidManifest.xml")
            .readAsStringSync();
    expect(plugin, isNot(contains("uses-permission")));
    expect(plugin, isNot(contains("READ_CONTACTS")));
    var code = File("packages/contact_pick/android/src/main/java/de/haumacher/"
            "valbum/contact_pick/ContactPickPlugin.java")
        .readAsStringSync();
    expect(code, contains("Intent.ACTION_PICK"));
    expect(code, isNot(contains("resolveActivity")));
    expect(code, isNot(contains("queryIntentActivities")));
  });
}
