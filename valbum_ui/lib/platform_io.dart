/// The platform pieces of the offline support on everything but the web.
library;

import 'dart:io';

import 'offline.dart';
import 'offline_file.dart';

/// The cache the app uses when it is not told otherwise: a directory on the
/// device, so that what was seen survives the app being closed.
OfflineCache defaultOfflineCache() => FileOfflineCache.underSupportDirectory();

/// Whether the given error is the network itself failing.
///
/// `package:http` wraps most of these in a `ClientException`, but not all of
/// them (a failed DNS lookup surfaces as a plain `SocketException` on some
/// platforms), so the `dart:io` type is checked as well.
bool isSocketError(Object error) => error is SocketException;
