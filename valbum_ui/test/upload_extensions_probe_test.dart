/// Probe of #197: the extensions the app's picker offers are exactly those
/// the server accepts. `uploadExtensions` is kept by hand beside
/// `PreviewCache.SUPPORTED_EXTENSIONS`; this test reads the Java source so a
/// format added on one side and forgotten on the other fails here.
library;

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/media_picker.dart';

Set<String> serverExtensions() {
  var source = File(
          "../image-server/src/main/java/de/haumacher/imageServer/PreviewCache.java")
      .readAsStringSync();
  var constants = {
    for (var match in RegExp(r'static final String (\w+) = "([^"]*)";')
        .allMatches(source))
      match.group(1)!: match.group(2)!,
  };
  var list = RegExp(r'SUPPORTED_EXTENSIONS\s*=[^;]*?asList\(([^)]*)\)')
      .firstMatch(source)!
      .group(1)!;
  return {
    for (var name in list.split(",").map((s) => s.trim()))
      constants[name] ?? (throw StateError("unknown constant $name")),
  };
}

void main() {
  test('the picker offers what the server accepts, no more and no less', () {
    var server = serverExtensions();
    expect(server, contains("jpg"), reason: "the source was read");
    expect(uploadExtensions.toSet(), server);
  });
}
