/// Probe for #117: per-album sources composed with the server's duplicate
/// answer (#29) and with a failed batch (the watermark rule).
library;

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'camera_roll_sources_test.dart' show photo, inbox, serverDataUrl;

class ProbeHarness {
  final FakePhotoLibrary library = FakePhotoLibrary();
  final InMemorySettingsStore store = InMemorySettingsStore();
  final List<String> uploads = [];
  late final CameraRollSync sync;

  /// Whether the server claims to hold every photo it is asked about.
  bool everythingPresent = false;

  /// The file whose upload the server refuses, if any.
  String? refuse;

  ProbeHarness() {
    store.cameraRoll =
        const CameraRollConfig(enabled: true, inbox: inbox).toJson();
    var client = VAlbumClient(
      dataUrl: serverDataUrl,
      httpClient: MockClient((request) async {
        if (request.method == "POST") {
          if (!everythingPresent) return http.Response('{"present":[]}', 200);
          var asked = jsonDecode(request.body)["hashes"] as List;
          var present = [
            for (var h in asked) {"hash": h["hash"], "name": "x.jpg"}
          ];
          return http.Response(jsonEncode({"present": present}), 200);
        }
        if (request.method == "PUT") {
          if (refuse != null && request.body.contains('filename="$refuse"')) {
            return http.Response(
                '["ErrorInfo",{"message":"The disk is full."}]', 500,
                headers: {"content-type": "application/json"});
          }
          uploads.add(request.body);
        }
        return http.Response("", 200);
      }),
    );
    sync = CameraRollSync(store: store, library: library, clientOf: () => client);
  }

  List<String> get uploadedNames => [
        for (var body in uploads)
          for (var m in RegExp('filename="([^"]+)"').allMatches(body)) m.group(1)!
      ];
}

void main() {
  test('a photo the server already holds advances its album\'s mark without '
      'an upload', () async {
    var h = ProbeHarness()..everythingPresent = true;
    addTearDown(() => h.sync.dispose());
    var cam = h.library.addAlbum("Camera", [photo("cam.jpg", 1)], id: "cam", camera: true);
    await h.sync.load();
    var seen = <CameraRollStatus>[];
    h.sync.addListener(() => seen.add(h.sync.status));
    await h.sync.chooseSources([cam.id], albums: await h.library.albums());
    await pumpEventQueue();
    await h.sync.syncNow();
    expect(h.uploadedNames, isEmpty);
    expect(h.sync.config.markOf("cam").since, photo("cam.jpg", 1).takenAt,
        reason: "present counts as accepted, the mark moves on");
    // Choosing the sources already ran the engine; that run reported the
    // photo as present, and the explicit run afterwards found nothing new.
    expect(seen.map((s) => s.lastPresent), contains(1));
  });

  test('a refused upload in the second album keeps that album\'s mark and '
      'the first album\'s progress', () async {
    var h = ProbeHarness()..refuse = "wa.jpg";
    addTearDown(() => h.sync.dispose());
    var cam = h.library.addAlbum("Camera", [photo("cam.jpg", 1)], id: "cam", camera: true);
    var wa = h.library.addAlbum("WhatsApp", [photo("wa.jpg", 2)], id: "wa");
    await h.sync.load();
    await h.sync.chooseSources([cam.id, wa.id], albums: await h.library.albums());
    await pumpEventQueue();
    await h.sync.syncNow();
    expect(h.uploadedNames, ["cam.jpg"]);
    expect(h.sync.config.markOf("cam").since, photo("cam.jpg", 1).takenAt);
    expect(h.sync.config.markOf("wa"), SourceMark.beginning,
        reason: "nothing of the refused batch was accepted");
    expect(h.sync.status.phase, anyOf(CameraRollPhase.failed, CameraRollPhase.waiting));
    expect(h.sync.status.message, contains("disk is full"));
    // The retry scans only what is still open: the refused album from the
    // beginning, the camera from its mark.
    h.refuse = null;
    await h.sync.syncNow();
    expect(h.uploadedNames, ["cam.jpg", "wa.jpg"]);
    expect(h.sync.config.markOf("wa").since, photo("wa.jpg", 2).takenAt);
  });
}
