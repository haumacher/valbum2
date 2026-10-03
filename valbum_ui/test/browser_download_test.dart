/// The download of a browser (issue #209), run in a real browser:
/// `flutter test --platform chrome test/browser_download_test.dart`.
///
/// The browser's saver hands the browser an *address* and never opens the
/// file through the app: the click it makes on an anchor carrying `download`
/// is caught here, before the browser follows it, and the file's
/// [DownloadSource.open] throws, so a saver that read the bytes would fail.
@TestOn('browser')
library;

import 'dart:js_interop';
import 'dart:js_interop_unsafe';

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/downloads.dart';
import 'package:valbum_ui/platform_web.dart';

void main() {
  test('the browser is handed the address, never the bytes', () async {
    var clicks = <(String, String)>[];
    var document = globalContext["document"] as JSObject;
    void onClick(JSObject event) {
      var target = event["target"] as JSObject;
      if ((target["tagName"] as JSString).toDart.toLowerCase() == "a") {
        clicks.add((
          (target["href"] as JSString).toDart,
          (target["download"] as JSString).toDart,
        ));
        event.callMethod<JSAny?>("preventDefault".toJS);
      }
    }

    var listener = onClick.toJS;
    document.callMethod<JSAny?>(
        "addEventListener".toJS, "click".toJS, listener, true.toJS);
    addTearDown(() => document.callMethod<JSAny?>(
        "removeEventListener".toJS, "click".toJS, listener, true.toJS));

    const saver = BrowserDownloadSaver();
    var outcome = await saver.save(DownloadSource(
      name: "Zoo.zip",
      open: () => throw StateError("The browser fetches the file itself."),
      address: () async =>
          "http://server/valbum/data/Zoo/?action=zip&ticket=T1&media=d.x.9.m",
    ));

    expect(outcome, SaveOutcome.saved);
    expect(saver.showsProgress, isTrue);
    expect(clicks, [
      (
        "http://server/valbum/data/Zoo/?action=zip&ticket=T1&media=d.x.9.m",
        "Zoo.zip",
      ),
    ]);
    // The anchor was only there for the click.
    expect(
        (document.callMethod<JSAny?>(
            "querySelectorAll".toJS, "a[download]".toJS) as JSObject)["length"],
        0.toJS);
  });
}
