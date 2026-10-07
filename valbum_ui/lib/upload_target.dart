/// Where the new photos of this device go (issue #240): the inbox of the space
/// by default, for a while an album the user picked — the holiday, say.
///
/// This amends #226 without undoing it. A space still has one inbox and
/// nobody chooses it; what is new is a **temporary upload target of this
/// device**, kept on the device only and per server ([UploadTargets]), never
/// in the space: other devices keep filling the inbox.
///
/// Three things send new photos and all three ask here: the camera-roll sync
/// (foreground and background, `camera_roll.dart`) and an upload into the
/// inbox from the app or the browser (`app.dart`). They go through an
/// [UploadRoute], which knows the two rules that keep a forgotten target from
/// losing anything:
///
///  * **an end date**, one week ahead by default and optional: past it, the
///    route sends to the inbox, clears the target, and says so once;
///  * **fallback, never loss** (#132's lesson): a target that answers `404`
///    (gone), `403` (no right any more) or `400` (no album, e.g. a collection)
///    on the requests the upload makes anyway is cleared, the photos go to
///    the inbox in the same run, and the reason is said. A route that has
///    nothing to send asks the server nothing.
///
/// A target that was **renamed or moved** is followed where the server can
/// tell: the server keeps no log of renamed folders, but its hash index knows
/// where every photo is now (`?action=check` answers paths, issue #118). The
/// target therefore remembers the content hashes of the last few photos this
/// device stored there ([UploadTarget.anchors]); on a `404` the route asks
/// once where they are, and follows only where the evidence is unambiguous:
/// at least two of them are found, every one found lies in one and the same
/// folder, and that folder is the old one renamed (same parent) or moved
/// (same name) — see [agreedFolder] and [followedFolder]. A single photo
/// proves nothing: the member may have sorted it into a sibling album, and a
/// wrong album hides new photos where the inbox shows them.
library;

import 'dart:convert';

import 'package:flutter/foundation.dart';

import 'client.dart';
import 'notices.dart';
import 'resource.dart';
import 'settings.dart';

/// How many of the photos last stored in a target it remembers, see
/// [UploadTarget.anchors].
const int uploadTargetAnchors = 5;

/// How far ahead the end date of a new target lies by default (issue #240).
const int uploadTargetDefaultDays = 7;

/// The end date a target chosen [now] is offered with: one week ahead.
DateTime defaultUploadTargetEnd(DateTime now) =>
    DateTime(now.year, now.month, now.day + uploadTargetDefaultDays);

/// An album new photos of this device go to instead of the inbox.
@immutable
class UploadTarget {
  /// The album's path below the root of the space.
  final List<String> path;

  /// The album's title as the user picked it, shown wherever the target is
  /// named; the folder name where it has none.
  final String title;

  /// The last day photos go there, `null` for a target without an end.
  ///
  /// A calendar day of the device: the target ends when that day is over.
  final DateTime? until;

  /// The content hashes of the last photos this device stored in the album,
  /// oldest first and at most [uploadTargetAnchors]; empty before the first
  /// one. What a renamed or moved album is found by, see [agreedFolder].
  final List<String> anchors;

  const UploadTarget({
    required this.path,
    this.title = "",
    this.until,
    this.anchors = const [],
  });

  /// How the target is named on the screen.
  String get name => title.isNotEmpty
      ? title
      : path.isEmpty
          ? ""
          : path.last;

  /// Whether the end date has passed at [now].
  bool expiredAt(DateTime now) {
    var end = until;
    if (end == null) {
      return false;
    }
    return !now.isBefore(DateTime(end.year, end.month, end.day + 1));
  }

  /// The same target with the given values replaced; [until] is replaced
  /// only where [replaceUntil] says so, because `null` means "no end".
  UploadTarget copyWith({
    List<String>? path,
    String? title,
    DateTime? until,
    bool replaceUntil = false,
    List<String>? anchors,
  }) =>
      UploadTarget(
        path: path ?? this.path,
        title: title ?? this.title,
        until: replaceUntil ? until : this.until,
        anchors: anchors ?? this.anchors,
      );

  /// This target as stored.
  Map<String, Object?> toJson() => {
        "path": path.join("/"),
        if (title.isNotEmpty) "title": title,
        if (until != null) "until": _day(until!),
        if (anchors.isNotEmpty) "anchors": anchors,
      };

  /// The target stored as [json], `null` for anything unreadable.
  static UploadTarget? parse(Object? json) {
    if (json is! Map) {
      return null;
    }
    var path = splitTargetPath("${json["path"] ?? ""}");
    if (path.isEmpty) {
      return null;
    }
    return UploadTarget(
      path: path,
      title: json["title"] is String ? json["title"] as String : "",
      until: _parseDay(json["until"]),
      anchors: json["anchors"] is List
          ? [
              for (var hash in json["anchors"] as List)
                if (hash is String && hash.isNotEmpty) hash
            ]
          : const [],
    );
  }

  @override
  bool operator ==(Object other) =>
      other is UploadTarget &&
      listEquals(other.path, path) &&
      other.title == title &&
      other.until == until &&
      listEquals(other.anchors, anchors);

  @override
  int get hashCode =>
      Object.hash(Object.hashAll(path), title, until, Object.hashAll(anchors));

  @override
  String toString() => jsonEncode(toJson());
}

/// A path as stored or answered, as the segments the app addresses it by.
List<String> splitTargetPath(String path) => [
      for (var segment in path.split("/"))
        if (segment.isNotEmpty) segment
    ];

String _day(DateTime day) => "${day.year.toString().padLeft(4, "0")}-"
    "${day.month.toString().padLeft(2, "0")}-"
    "${day.day.toString().padLeft(2, "0")}";

DateTime? _parseDay(Object? value) {
  if (value is! String) {
    return null;
  }
  var parsed = DateTime.tryParse(value);
  return parsed == null
      ? null
      : DateTime(parsed.year, parsed.month, parsed.day);
}

/// The ended target's notice as stored, so that a background run's fallback
/// is still said by the app that opens afterwards.
Map<String, Object?>? _endedToJson(AppNotice notice) => switch (notice) {
      UploadTargetExpired(album: var album, until: var until) => {
          "why": "expired",
          "album": album,
          "until": _day(until),
        },
      UploadTargetGone(album: var album) => {"why": "gone", "album": album},
      UploadTargetRefused(album: var album) => {
          "why": "refused",
          "album": album,
        },
      UploadTargetNotAlbum(album: var album) => {
          "why": "notAlbum",
          "album": album,
        },
      _ => null,
    };

AppNotice? _parseEnded(Object? json) {
  if (json is! Map) {
    return null;
  }
  var album = "${json["album"] ?? ""}";
  switch (json["why"]) {
    case "expired":
      var until = _parseDay(json["until"]);
      return until == null ? null : UploadTargetExpired(album, until);
    case "gone":
      return UploadTargetGone(album);
    case "refused":
      return UploadTargetRefused(album);
    case "notAlbum":
      return UploadTargetNotAlbum(album);
  }
  return null;
}

/// What is stored for one server: the target, or the notice of the one that
/// ended and that the user has not seen yet.
@immutable
class _Entry {
  final UploadTarget? target;
  final AppNotice? ended;

  const _Entry({this.target, this.ended});

  Map<String, Object?> toJson() => {
        if (target != null) ...target!.toJson(),
        if (ended != null && _endedToJson(ended!) != null)
          "ended": _endedToJson(ended!),
      };

  static _Entry parse(Object? json) => _Entry(
        target: UploadTarget.parse(json),
        ended: json is Map ? _parseEnded(json["ended"]) : null,
      );

  bool get isEmpty => target == null && ended == null;
}

/// The key a server's entry is stored under: its data URL, without a
/// trailing slash, so that both spellings name one server.
String _keyOf(String dataUrl) =>
    dataUrl.endsWith("/") ? dataUrl.substring(0, dataUrl.length - 1) : dataUrl;

/// The upload targets of this device, one per server, persisted in the
/// [SettingsStore] (issue #240).
///
/// Every change reads the store first and writes it back at once: the
/// background run (#169) writes the same blob from another isolate, and a
/// target it cleared must not come back from a stale copy here.
class UploadTargets extends ChangeNotifier {
  final SettingsStore store;

  /// The current time, injected so that a test decides what day it is.
  final DateTime Function() clock;

  Map<String, _Entry> _entries = const {};
  bool _loaded = false;
  bool _disposed = false;

  UploadTargets({required this.store, DateTime Function()? clock})
      : clock = clock ?? DateTime.now;

  /// Whether [load] has finished.
  bool get loaded => _loaded;

  /// Reads the stored targets.
  Future<void> load() async {
    await _read();
    _loaded = true;
    _changed();
  }

  Future<void> _read() async {
    var text = await store.loadUploadTargets();
    var entries = <String, _Entry>{};
    if (text != null && text.trim().isNotEmpty) {
      try {
        var json = jsonDecode(text);
        if (json is Map) {
          for (var entry in json.entries) {
            var parsed = _Entry.parse(entry.value);
            if (!parsed.isEmpty) {
              entries["${entry.key}"] = parsed;
            }
          }
        }
      } catch (_) {
        // Unreadable: nothing is lost but a target, and the inbox is where
        // the photos went before one was chosen.
      }
    }
    _entries = entries;
  }

  Future<void> _update(
      String dataUrl, _Entry Function(_Entry entry) change) async {
    await _read();
    var key = _keyOf(dataUrl);
    var next = change(_entries[key] ?? const _Entry());
    var entries = {..._entries};
    if (next.isEmpty) {
      entries.remove(key);
    } else {
      entries[key] = next;
    }
    _entries = entries;
    await store.saveUploadTargets(jsonEncode(
        {for (var entry in entries.entries) entry.key: entry.value.toJson()}));
    _changed();
  }

  void _changed() {
    if (!_disposed) {
      notifyListeners();
    }
  }

  _Entry _entryOf(String? dataUrl) => dataUrl == null
      ? const _Entry()
      : _entries[_keyOf(dataUrl)] ?? const _Entry();

  /// Where new photos for the server at [dataUrl] go, `null` for the inbox.
  ///
  /// A target whose end date has passed is the inbox already, even before
  /// a run clears it, see [endedOf].
  UploadTarget? targetOf(String? dataUrl) {
    var target = _entryOf(dataUrl).target;
    return target == null || target.expiredAt(clock()) ? null : target;
  }

  /// Why new photos go to the inbox again, `null` while there is nothing to
  /// say: the notice of a target that ended — its end date, or a fallback —
  /// until the user has seen it, see [dismiss].
  AppNotice? endedOf(String? dataUrl) {
    var entry = _entryOf(dataUrl);
    var target = entry.target;
    if (target != null && target.expiredAt(clock())) {
      return UploadTargetExpired(target.name, target.until!);
    }
    return entry.ended;
  }

  /// Sends new photos for the server at [dataUrl] to [target] from now on.
  Future<void> choose(String dataUrl, UploadTarget target) =>
      _update(dataUrl, (_) => _Entry(target: target));

  /// Moves the end date of the current target; `null` for none.
  Future<void> setUntil(String dataUrl, DateTime? until) =>
      _update(dataUrl, (entry) {
        var target = entry.target;
        if (target == null) {
          return entry;
        }
        return _Entry(
          target: target.copyWith(
            until: until == null
                ? null
                : DateTime(until.year, until.month, until.day),
            replaceUntil: true,
          ),
        );
      });

  /// Sends new photos to the inbox again, by the user's own hand: nothing is
  /// left to say.
  Future<void> backToInbox(String dataUrl) =>
      _update(dataUrl, (_) => const _Entry());

  /// The user has read why the photos go to the inbox again, see [endedOf].
  ///
  /// A target past its end date is cleared with it: what was said is true.
  Future<void> dismiss(String dataUrl) => _update(dataUrl, (entry) {
        var target = entry.target;
        if (target != null && !target.expiredAt(clock())) {
          return _Entry(target: target);
        }
        return const _Entry();
      });

  /// The target a send for [dataUrl] goes to now, read afresh from the store.
  ///
  /// A target past its end date is cleared here, and the notice saying so is
  /// answered as [ended] — once: the next call finds no target and says
  /// nothing.
  Future<({UploadTarget? target, AppNotice? ended})> resolve(
    String dataUrl,
  ) async {
    await _read();
    // What a background run (#169) changed meanwhile is shown from now on.
    _changed();
    var target = _entryOf(dataUrl).target;
    if (target == null) {
      return (target: null, ended: null);
    }
    if (!target.expiredAt(clock())) {
      return (target: target, ended: null);
    }
    var notice = UploadTargetExpired(target.name, target.until!);
    await end(dataUrl, notice);
    return (target: null, ended: notice);
  }

  /// Clears the target for [dataUrl] and keeps [why] for the user to read.
  Future<void> end(String dataUrl, AppNotice why) =>
      _update(dataUrl, (_) => _Entry(ended: why));

  /// The target was found at another path, see [UploadRoute].
  Future<void> follow(String dataUrl, UploadTarget target) =>
      _update(dataUrl, (entry) => _Entry(target: target));

  /// Records the newest photo this device stored in the target, see
  /// [UploadTarget.anchor].
  Future<void> anchor(
          String dataUrl, List<String> path, List<String> anchors) =>
      _update(dataUrl, (entry) {
        var target = entry.target;
        // A target the user changed meanwhile keeps its own anchor.
        if (target == null || !listEquals(target.path, path)) {
          return entry;
        }
        return _Entry(target: target.copyWith(anchors: anchors));
      });

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}

/// Whether [error] says that the target itself takes no photos from this
/// device: gone (`404`), not allowed (`403`), or no album (`400`, e.g. a
/// collection).
///
/// Everything else — a transport failure, `401`, a `415` about one file, a
/// `5xx` — is no news about the target and fails the send as before.
bool refusesTarget(Object error) =>
    error is VAlbumException &&
    error is! UploadInterrupted &&
    (error.status == 404 || error.status == 403 || error.status == 400);

/// Where the album at [before] went, given that the photo it was found by now
/// lies at [presentName] (a path below the space root), `null` where that is
/// no rename or move of it.
///
/// A rename (#130) keeps the parent and changes the name; a move keeps the
/// name and changes the parent. Anything else — a photo sorted out into some
/// other album, the trash below `.valbum`, the [inbox] — is not followed: an
/// album the user did not choose must never fill up behind their back.
List<String>? followedFolder(
  List<String> before,
  String presentName,
  List<String> inbox,
) {
  var segments = splitTargetPath(presentName);
  if (segments.length < 2 || before.isEmpty) {
    return null;
  }
  var folder = segments.sublist(0, segments.length - 1);
  if (folder.any((segment) => segment.startsWith("."))) {
    return null;
  }
  if (listEquals(folder, before) || listEquals(folder, inbox)) {
    return null;
  }
  var renamed = folder.length == before.length &&
      listEquals(
        folder.sublist(0, folder.length - 1),
        before.sublist(0, before.length - 1),
      );
  var moved = folder.last == before.last;
  return renamed || moved ? folder : null;
}

/// The folder the album at [before] went to, judged by where the index found
/// its remembered photos ([present], as `?action=check` answers them for
/// [anchors]), `null` unless the evidence is unambiguous (issue #240):
///
///  * at least two of the remembered photos are found;
///  * every one found lies in one and the same folder — a photo answered
///    without a path, or in another folder, is a split, and a split is no
///    evidence;
///  * that folder is a rename or a move of [before], see [followedFolder].
List<String>? agreedFolder(
  List<String> before,
  List<String> anchors,
  List<PresentFile> present,
  List<String> inbox,
) {
  var asked = anchors.toSet();
  List<String>? folder;
  var found = 0;
  for (var file in present) {
    if (!asked.contains(file.hash)) {
      continue;
    }
    found++;
    var segments = splitTargetPath(file.name);
    if (segments.length < 2) {
      // In the folder that was asked (the inbox), at the root, or present
      // without a path: not where an album could be.
      return null;
    }
    var here = segments.sublist(0, segments.length - 1);
    if (folder == null) {
      folder = here;
    } else if (!listEquals(folder, here)) {
      return null;
    }
  }
  if (folder == null || found < 2) {
    return null;
  }
  return followedFolder(before, "${folder.join("/")}/_", inbox);
}

/// Where one send of new photos goes, and what happened to the target on the
/// way (issue #240).
///
/// Opened once per run or per upload ([open]), which asks the store only; a
/// route that sends nothing has asked the server nothing.
class UploadRoute {
  final UploadTargets targets;
  final VAlbumClient client;

  /// The inbox of the space, as the server named it: the default and the
  /// fallback.
  final List<String> inbox;

  UploadTarget? _target;

  /// What the route has to say, in the order it happened.
  final List<AppNotice> notices = [];

  UploadRoute._(this.targets, this.client, this.inbox, this._target);

  /// The route for one send through [client], see [UploadTargets.resolve].
  static Future<UploadRoute> open({
    required UploadTargets targets,
    required VAlbumClient client,
    required List<String> inbox,
  }) async {
    var resolved = await targets.resolve(client.dataUrl);
    var route = UploadRoute._(targets, client, inbox, resolved.target);
    var ended = resolved.ended;
    if (ended != null) {
      route.notices.add(ended);
    }
    return route;
  }

  /// The target the photos go to, `null` while they go to the inbox.
  UploadTarget? get target => _target;

  /// Where the photos go now.
  List<String> get path => _target?.path ?? inbox;

  /// The last thing the route has to say, `null` where nothing happened.
  AppNotice? get notice => notices.isEmpty ? null : notices.last;

  /// Sends by [attempt] to where the photos go now, falling back to the inbox
  /// where the target refuses, see the library documentation.
  ///
  /// [attempt] is called with the path to send to; it is [VAlbumClient.uploadNew]
  /// in practice, whose check request and upload are the requests that tell
  /// the target is gone — no extra probe is made before them.
  Future<UploadSummary> send(
    Future<UploadSummary> Function(List<String> path) attempt,
  ) async {
    var target = _target;
    if (target == null) {
      return attempt(inbox);
    }
    UploadSummary summary;
    try {
      summary = await attempt(target.path);
    } catch (error) {
      if (!refusesTarget(error)) {
        rethrow;
      }
      var status = (error as VAlbumException).status;
      if (status == 404) {
        var moved = await _locate(target);
        if (moved != null) {
          try {
            summary = await attempt(moved);
            var followed = target.copyWith(path: moved, title: moved.last);
            _target = followed;
            await targets.follow(client.dataUrl, followed);
            notices.add(UploadTargetFollowed(target.name, followed.name));
            await _anchor(summary);
            return summary;
          } catch (again) {
            if (!refusesTarget(again)) {
              rethrow;
            }
          }
        }
      }
      AppNotice why = switch (status) {
        403 => UploadTargetRefused(target.name),
        400 => UploadTargetNotAlbum(target.name),
        _ => UploadTargetGone(target.name),
      };
      _target = null;
      await targets.end(client.dataUrl, why);
      notices.add(why);
      // Nothing is lost: the very photos go to the inbox, in this send.
      return attempt(inbox);
    }
    await _anchor(summary);
    return summary;
  }

  /// Asks the hash index once where the target's [UploadTarget.anchors] lie
  /// now, and answers the folder to follow, `null` where there is none, see
  /// [agreedFolder].
  Future<List<String>?> _locate(UploadTarget target) async {
    if (target.anchors.length < 2) {
      return null;
    }
    try {
      var check = await client.checkUploads(inbox, target.anchors);
      return agreedFolder(target.path, target.anchors, check.present, inbox);
    } catch (_) {
      // Nothing to follow by; the fallback is the inbox.
    }
    return null;
  }

  /// Remembers the photos [summary] stored in the target, see
  /// [UploadTarget.anchors].
  Future<void> _anchor(UploadSummary summary) async {
    var target = _target;
    if (target == null || summary.storedHashes.isEmpty) {
      return;
    }
    var anchors = [
      for (var hash in target.anchors)
        if (!summary.storedHashes.contains(hash)) hash,
      ...summary.storedHashes,
    ];
    if (anchors.length > uploadTargetAnchors) {
      anchors = anchors.sublist(anchors.length - uploadTargetAnchors);
    }
    if (listEquals(anchors, target.anchors)) {
      return;
    }
    _target = target.copyWith(anchors: anchors);
    await targets.anchor(client.dataUrl, target.path, anchors);
  }
}
