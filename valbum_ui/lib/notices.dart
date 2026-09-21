/// What the engines of the app have to say, as data (issue #108).
///
/// The camera-roll sync, the photo library, the connectivity source and the
/// background scheduler all compose reasons a person reads — "no Wi-Fi", "the
/// photo library cannot be read", "ask the admin for a space" — and none of
/// them has a [BuildContext] to read a translation with. Handing them an
/// `AppLocalizations` at construction would make the engine speak a language;
/// so they answer an [AppNotice] instead, which says *what* is the matter and
/// nothing about how it is worded, and the view turns it into a sentence with
/// [noticeText].
///
/// Two consequences, and both of them are the point:
///
///  * the engines stay language-free, and their tests assert on data — a
///    refused sync is `NoWifiMobile()`, not a string that a retranslation
///    could break;
///  * what the **server** said is not one of these. A server sentence travels
///    verbatim (see `CameraRollStatus.message`): the server spells it, in its
///    own language, and the app neither translates nor paraphrases it.
library;

import 'package:flutter/foundation.dart';

import 'l10n/app_localizations.dart';

/// One thing an engine of the app has to say, without saying it in words.
@immutable
sealed class AppNotice {
  const AppNotice();

  /// What tells two notices of the same kind apart; empty where the kind is
  /// the whole of it.
  List<Object?> get _values => const [];

  @override
  bool operator ==(Object other) =>
      other is AppNotice &&
      other.runtimeType == runtimeType &&
      listEquals(other._values, _values);

  @override
  int get hashCode => Object.hash(runtimeType, Object.hashAll(_values));

  @override
  String toString() => "$runtimeType${_values.isEmpty ? "" : _values}";
}

/// A guest has no library of their own to sync into, see issue #54.
class GuestHasNoSpace extends AppNotice {
  const GuestHasNoSpace();
}

/// This device names no album server.
class NoServerConfigured extends AppNotice {
  const NoServerConfigured();
}

/// The album server cannot be reached at all.
class ServerOffline extends AppNotice {
  const ServerOffline();
}

/// The album server could not be reached, and this is what the transport said.
class ServerUnreachable extends AppNotice {
  final String problem;

  const ServerUnreachable(this.problem);

  @override
  List<Object?> get _values => [problem];
}

/// The device's photo library cannot be read, and nothing says why.
class PhotoLibraryUnreadable extends AppNotice {
  const PhotoLibraryUnreadable();
}

/// The device's photo library refused to answer.
class PhotoLibraryFailed extends AppNotice {
  final String problem;

  const PhotoLibraryFailed(this.problem);

  @override
  List<Object?> get _values => [problem];
}

/// The device's photo library could not be opened.
class PhotoLibraryOpenFailed extends AppNotice {
  final String problem;

  const PhotoLibraryOpenFailed(this.problem);

  @override
  List<Object?> get _values => [problem];
}

/// The user has not allowed this app to look at their photos.
class PhotoAccessDenied extends AppNotice {
  const PhotoAccessDenied();
}

/// The bytes of a photo are not on the device — still in the cloud.
class PhotoNotOnDevice extends AppNotice {
  final String name;

  const PhotoNotOnDevice(this.name);

  @override
  List<Object?> get _values => [name];
}

/// This platform has no photo library at all.
class NoPhotoLibraryHere extends AppNotice {
  const NoPhotoLibraryHere();
}

/// This platform has no camera roll, and the sync runs on the phones.
class NoPhotoLibraryPlatform extends AppNotice {
  const NoPhotoLibraryPlatform();
}

/// A browser has no camera roll, and the sync runs on the phones.
class NoPhotoLibraryBrowser extends AppNotice {
  const NoPhotoLibraryBrowser();
}

/// The platform refused to schedule the background sync.
class BackgroundScheduleFailed extends AppNotice {
  final String problem;

  const BackgroundScheduleFailed(this.problem);

  @override
  List<Object?> get _values => [problem];
}

/// The platform refused to drop the background sync.
class BackgroundUnscheduleFailed extends AppNotice {
  final String problem;

  const BackgroundUnscheduleFailed(this.problem);

  @override
  List<Object?> get _values => [problem];
}

/// This platform runs no background sync.
class NoBackgroundSyncHere extends AppNotice {
  const NoBackgroundSyncHere();
}

/// A browser runs no background sync.
class NoBackgroundSyncInBrowser extends AppNotice {
  const NoBackgroundSyncInBrowser();
}

/// This build of the app runs no background sync.
class NoBackgroundSyncInApp extends AppNotice {
  const NoBackgroundSyncInApp();
}

/// A test drives the sync itself; nothing is scheduled.
class NoBackgroundSyncInTest extends AppNotice {
  const NoBackgroundSyncInTest();
}

/// There is no network at all, and the sync waits for one.
class NoNetworkForSync extends AppNotice {
  const NoNetworkForSync();
}

/// The device is on a mobile connection and the sync is limited to Wi-Fi.
class NoWifiMobile extends AppNotice {
  const NoWifiMobile();
}

/// The device is on some other network and the sync is limited to Wi-Fi.
class NoWifiOther extends AppNotice {
  const NoWifiOther();
}

/// The sentence [notice] reads in the language of [l10n].
///
/// The one place a notice becomes words: every view that shows one asks here,
/// so that the same thing is said the same way wherever it is shown.
String noticeText(AppNotice notice, AppLocalizations l10n) => switch (notice) {
      GuestHasNoSpace() => l10n.noticeGuestNoSpace,
      NoServerConfigured() => l10n.noticeNoServerConfigured,
      ServerOffline() => l10n.noticeOffline,
      ServerUnreachable(problem: var problem) =>
        l10n.noticeServerUnreachable(problem),
      PhotoLibraryUnreadable() => l10n.noticePhotoLibraryUnreadable,
      PhotoLibraryFailed(problem: var problem) =>
        l10n.noticePhotoLibraryFailed(problem),
      PhotoLibraryOpenFailed(problem: var problem) =>
        l10n.noticePhotoLibraryOpenFailed(problem),
      PhotoAccessDenied() => l10n.noticePhotoAccessDenied,
      PhotoNotOnDevice(name: var name) => l10n.noticeAlbumNotOnDevice(name),
      NoPhotoLibraryHere() => l10n.noPhotoLibrary,
      NoPhotoLibraryPlatform() => l10n.noticeNoPhotoLibraryPlatform,
      NoPhotoLibraryBrowser() => l10n.noticeNoPhotoLibraryBrowser,
      BackgroundScheduleFailed(problem: var problem) =>
        l10n.noticeBackgroundScheduleFailed(problem),
      BackgroundUnscheduleFailed(problem: var problem) =>
        l10n.noticeBackgroundUnscheduleFailed(problem),
      NoBackgroundSyncHere() => l10n.noticeNoBackgroundSyncHere,
      NoBackgroundSyncInBrowser() => l10n.noticeNoBackgroundSyncInBrowser,
      NoBackgroundSyncInApp() => l10n.noticeNoBackgroundSyncInApp,
      NoBackgroundSyncInTest() => l10n.noticeNoBackgroundSyncInTest,
      NoNetworkForSync() => l10n.noticeNoNetwork,
      NoWifiMobile() => l10n.noticeNoWifiMobile,
      NoWifiOther() => l10n.noticeNoWifiOther,
    };
