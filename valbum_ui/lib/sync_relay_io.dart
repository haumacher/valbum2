/// The [SyncRelay] of a phone: the isolates of the app process meet at two
/// names of the [IsolateNameServer] (issue #300).
///
/// Every engine of a Flutter app — the one of the Activity, and every one
/// WorkManager starts for a background run — runs its isolate in the same
/// Dart VM of the one app process, and the name server is that VM's. So:
///
///  * the run holding the claim registers its control port under
///    [claimPortName]; `registerPortWithName` refuses a name that is taken,
///    which is what makes the claim exclusive. Another run pings that port
///    before it waits: an engine that was torn down without releasing (the
///    platform stopped the work, the process was not killed) leaves a name
///    behind whose port answers nothing, and that name is removed;
///  * the app on the screen registers its port under [watchPortName], and a
///    run sends every status there while somebody listens — a lookup per
///    status, no subscription that could outlive either side.
///
/// Reached only through `platform_io.dart`: neither the name server nor
/// `dart:isolate` exists in a browser.
library;

import 'dart:async';
import 'dart:isolate';
import 'dart:ui' show IsolateNameServer;

import 'sync_relay.dart';

/// The name the control port of the run holding the claim is registered
/// under.
const String claimPortName = "de.haumacher.valbum.sync.run";

/// The name the port of the app on the screen is registered under.
const String watchPortName = "de.haumacher.valbum.sync.watch";

/// How long a ping of the claim's holder may take before the holder counts as
/// gone.
const Duration _pingTimeout = Duration(seconds: 2);

/// How often a run waiting for the claim looks again.
const Duration _claimPoll = Duration(seconds: 1);

/// The [SyncRelay] of a process of isolates, see the library comment.
class IsolateSyncRelay extends SyncRelay {
  @override
  Future<RunClaim?> claim({
    required String task,
    required Duration patience,
  }) async {
    var deadline = DateTime.now().add(patience);
    var control = ReceivePort();
    while (true) {
      if (IsolateNameServer.registerPortWithName(
          control.sendPort, claimPortName)) {
        return _IsolateClaim(task, control);
      }
      var holder = IsolateNameServer.lookupPortByName(claimPortName);
      if (holder != null && !await _alive(holder)) {
        // Left behind by an engine that is gone; only that very port is
        // removed, never one another run registered meanwhile.
        if (IsolateNameServer.lookupPortByName(claimPortName) == holder) {
          IsolateNameServer.removePortNameMapping(claimPortName);
        }
        continue;
      }
      if (!DateTime.now().isBefore(deadline)) {
        control.close();
        return null;
      }
      await Future<void>.delayed(_claimPoll);
    }
  }

  @override
  Stream<RelayMessage> watch() {
    ReceivePort? port;
    late StreamController<RelayMessage> controller;
    controller = StreamController<RelayMessage>(
      onListen: () {
        var receive = port = ReceivePort();
        // The newest app engine is the one on the screen: an Activity that
        // was recreated in the same process leaves the name of the old one.
        IsolateNameServer.removePortNameMapping(watchPortName);
        IsolateNameServer.registerPortWithName(receive.sendPort, watchPortName);
        receive.listen((wire) {
          var message = RelayMessage.fromWire(wire);
          if (message != null) {
            controller.add(message);
          }
        });
      },
      onCancel: () {
        var receive = port;
        port = null;
        if (receive != null) {
          if (IsolateNameServer.lookupPortByName(watchPortName) ==
              receive.sendPort) {
            IsolateNameServer.removePortNameMapping(watchPortName);
          }
          receive.close();
        }
      },
    );
    return controller.stream;
  }

  @override
  Future<bool> requestStop() async {
    var holder = IsolateNameServer.lookupPortByName(claimPortName);
    if (holder == null) {
      return false;
    }
    holder.send(const ["stop"]);
    return true;
  }

  /// Whether the run behind [holder] answers.
  static Future<bool> _alive(SendPort holder) async {
    var reply = ReceivePort();
    try {
      holder.send(["ping", reply.sendPort]);
      await reply.first.timeout(_pingTimeout);
      return true;
    } on TimeoutException {
      return false;
    } finally {
      reply.close();
    }
  }
}

class _IsolateClaim extends RunClaim {
  final String task;
  final ReceivePort _control;
  final StreamController<void> _stops = StreamController<void>.broadcast();
  bool _released = false;

  _IsolateClaim(this.task, this._control) {
    _control.listen((message) {
      if (message is List && message.isNotEmpty) {
        switch (message.first) {
          case "ping":
            if (message.length > 1 && message[1] is SendPort) {
              (message[1] as SendPort).send(true);
            }
          case "stop":
            _stops.add(null);
        }
      }
    });
  }

  @override
  void publish(
    Map<String, Object?> status, {
    bool finished = false,
    bool retryable = false,
  }) {
    IsolateNameServer.lookupPortByName(watchPortName)?.send(RelayMessage(
      task: task,
      status: status,
      finished: finished,
      retryable: retryable,
    ).toWire());
  }

  @override
  Stream<void> get stopRequests => _stops.stream;

  @override
  Future<void> release() async {
    if (_released) {
      return;
    }
    _released = true;
    if (IsolateNameServer.lookupPortByName(claimPortName) ==
        _control.sendPort) {
      IsolateNameServer.removePortNameMapping(claimPortName);
    }
    _control.close();
    await _stops.close();
  }
}
