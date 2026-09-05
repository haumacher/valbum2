/// The parts of the offline support that differ between the web and every
/// other platform, resolved at compile time.
///
/// The web build must never see `dart:io`, so the [FileOfflineCache] and the
/// `SocketException` it takes to detect a lost connection are reached through
/// this conditional import only.
library;

export 'platform_web.dart' if (dart.library.io) 'platform_io.dart';
