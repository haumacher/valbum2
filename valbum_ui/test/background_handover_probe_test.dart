/// Probe of issue #300: a background run on Android whose hand-over to the
/// expedited upload fails still gets the photo up — it uploads itself — and
/// a new photo still starts a run afterwards.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'background_test.dart' show FakeServer, enabledStore, photo;
import 'camera_roll_foreground_test.dart' show anyNetworkStore, backgroundRun;

/// A platform that refuses to register the upload work.
class RefusingUploads extends FakeBackgroundScheduler {
  RefusingUploads() : super(acceptsUploads: true);

  @override
  Future<bool> requestUpload(BackgroundNetwork network) async {
    await super.requestUpload(network);
    throw StateError("WorkManager is not initialized");
  }
}

void main() {
  for (var task in [
    backgroundTriggerTaskName,
    backgroundSyncTaskName,
    backgroundRetryTaskName,
  ]) {
    test('$task uploads itself where the hand-over fails', () async {
      var scheduler = RefusingUploads();
      var server = FakeServer();
      var result = await backgroundRun(
        anyNetworkStore(),
        FakePhotoLibrary(items: [photo("a.jpg", 1)]),
        server,
        MemorySyncRelay(),
        task: task,
        scheduler: scheduler,
      );

      expect(result.handedOver, isFalse);
      expect(
          server.uploadedNames,
          [
            ["a.jpg"]
          ],
          reason: "The photo goes up although the hand-over failed.");
      expect(scheduler.rearms.length + scheduler.ensures.length, 1,
          reason: "A new photo still starts a run afterwards.");
    });
  }

  test('a switched-off sync hands nothing over and arms nothing', () async {
    var scheduler = FakeBackgroundScheduler(acceptsUploads: true);
    var server = FakeServer();
    await backgroundRun(
      enabledStore(config: const CameraRollConfig(enabled: false)),
      FakePhotoLibrary(items: [photo("a.jpg", 1)]),
      server,
      MemorySyncRelay(),
      task: backgroundTriggerTaskName,
      scheduler: scheduler,
    );
    expect(scheduler.uploads, isEmpty);
    expect(scheduler.rearms, isEmpty);
    expect(server.requests, isEmpty);
  });
}
