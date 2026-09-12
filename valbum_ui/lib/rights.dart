/// What the caller may do with a folder, and how the app says so (issue #49).
///
/// The server derives the caller's effective rights on every folder it answers
/// and sends them along as [FolderResource.rights], so the app never has to
/// ask a second time. This library is pure — no widgets, no HTTP — so the
/// reading of that field can be unit-tested on its own.
library;

import 'resource.dart';

/// The name of the right to see a listing and its thumbnails.
const String rightView = "view";

/// The name of the right to fetch the original bytes of an image.
const String rightDownload = "download";

/// The name of the right to add images to a folder.
const String rightContribute = "contribute";

/// The name of the right to change a folder and everything in it.
const String rightEdit = "edit";

/// Every right, in the order they are offered.
const List<String> allRights = [
  rightView,
  rightDownload,
  rightContribute,
  rightEdit,
];

/// How a right is named on the screen.
const Map<String, String> rightLabels = {
  rightView: "View",
  rightDownload: "Download",
  rightContribute: "Contribute",
  rightEdit: "Edit",
};

/// What a right means, one line, shown beside its check box.
const Map<String, String> rightExplanations = {
  rightView: "See the album and its thumbnails",
  rightDownload: "Take copies of the originals",
  rightContribute: "Add photos",
  rightEdit: "Change the album and everything in it",
};

/// What the caller may do with one folder, see [FolderResource.rights].
///
/// The stronger rights imply the weaker ones; the server applies that closure
/// before it answers, and [Rights.ofNames] applies it again, so nothing here
/// depends on the server having done it.
class Rights {
  /// The rights held, closed under the implications.
  final Set<String> names;

  const Rights._(this.names);

  /// Every right — what an owner holds, and what an answer that says nothing
  /// is read as, see [Rights.of].
  static const Rights everything = Rights._({
    rightView,
    rightDownload,
    rightContribute,
    rightEdit,
  });

  /// The rights the given names denote, with the implications applied.
  factory Rights.ofNames(Iterable<String> names) {
    var held = <String>{};
    for (var name in names) {
      switch (name) {
        case rightEdit:
          held.addAll(allRights);
        case rightContribute:
          held.addAll(const [rightContribute, rightView]);
        case rightDownload:
          held.addAll(const [rightDownload, rightView]);
        case rightView:
          held.add(rightView);
      }
    }
    return Rights._(held);
  }

  /// What the caller may do with the given folder.
  ///
  /// A folder that carries no rights at all is read as **every** right: a
  /// server from before issue #49 does not send the field, and the current
  /// server never answers a folder with an empty set — a folder the caller may
  /// not even view is refused with a 401 or a 403 instead. So the app keeps
  /// working unchanged against an older server, and nothing is taken away on
  /// the strength of a field that is simply not there.
  factory Rights.of(FolderResource? folder) {
    var names = folder?.rights ?? const <RightName>[];
    if (names.isEmpty) {
      return everything;
    }
    return Rights.ofNames([for (var right in names) right.name]);
  }

  /// Whether the folder and its thumbnails may be seen.
  bool get mayView => names.contains(rightView);

  /// Whether the original bytes of an image may be fetched.
  bool get mayDownload => names.contains(rightDownload);

  /// Whether photos may be added.
  bool get mayContribute => names.contains(rightContribute);

  /// Whether the folder may be changed.
  bool get mayEdit => names.contains(rightEdit);

  /// Whether every right is held — the owner's view of their own folder.
  bool get complete => names.length == allRights.length;

  /// What the caller may do, in one half-sentence.
  String get phrase {
    if (mayEdit) {
      return "you may change it";
    }
    if (mayContribute) {
      return "you may add photos";
    }
    if (mayDownload) {
      return "you may look and download";
    }
    if (mayView) {
      return "you may look";
    }
    return "you may do nothing here";
  }

  @override
  String toString() => "Rights(${names.join(", ")})";
}

/// The prefix marking the canonical path into another user's space.
const String spacePrefix = "~";

/// The owner named by a path that reaches into another user's space, `null`
/// while the path lies in the caller's own space.
///
/// The server answers every path in the coordinates of the request, so a view
/// that was reached through `~alice/2024/Zoo` keeps saying `~alice`, see issue
/// #49.
String? spaceOwnerOf(List<String> path) {
  if (path.isEmpty || !path.first.startsWith(spacePrefix)) {
    return null;
  }
  var name = path.first.substring(spacePrefix.length);
  return name.isEmpty ? null : name;
}

/// The path relative to the root of the owner's space: the request path
/// without a leading `~owner` segment.
///
/// This is the coordinate system the grants of `?type=grants` are spelled in
/// ([Grant.path]), so it is what says whether a grant was made *here* or is
/// inherited from above.
String ownerPathOf(List<String> path) {
  var segments = spaceOwnerOf(path) == null ? path : path.sublist(1);
  return segments.join("/");
}

/// The line saying that this folder is not the caller's own, `null` when it is.
///
/// Shown wherever the caller is not the owner: either the path names somebody
/// else's space, or the rights are less than all four. An owner browsing their
/// own library sees nothing of this.
///
/// An album reached **through a link** (issue #50) is a case of the second
/// kind: the path is the viewer's own, so the notice says "Shared with you —
/// you may …" and does not name the owner. That is the truth of what the app
/// knows here — the answer carries the rights but no owner, and the link that
/// does name one lives on the tile of the parent listing, where the "from
/// <owner>" line says it. Naming the owner in the album as well would mean
/// carrying the tile along the route, and a route that is opened again from a
/// bookmark or a reload has no tile to carry.
String? sharingNotice(List<String> path, Rights rights) {
  var owner = spaceOwnerOf(path);
  if (owner == null && rights.complete) {
    return null;
  }
  var by = owner == null ? "Shared with you" : "Shared by $owner";
  return "$by — ${rights.phrase}";
}
