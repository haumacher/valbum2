/// What the caller may do with a folder, and how the app says so (issue #49).
///
/// The server derives the caller's effective rights on every folder it answers
/// and sends them along as [FolderResource.rights], so the app never has to
/// ask a second time. This library is pure — no widgets, no HTTP — so the
/// reading of that field can be unit-tested on its own.
library;

import 'caller.dart';
import 'l10n/app_localizations.dart';
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

/// How a right is named on the screen, the name itself where the app knows
/// no such right (a server newer than this app).
String rightLabel(AppLocalizations l10n, String right) => switch (right) {
      rightView => l10n.rightLabelView,
      rightDownload => l10n.rightLabelDownload,
      rightContribute => l10n.rightLabelContribute,
      rightEdit => l10n.rightLabelEdit,
      _ => right,
    };

/// What a right means, one line, shown beside its check box; empty where the
/// app knows no such right.
String rightExplanation(AppLocalizations l10n, String right) =>
    switch (right) {
      rightView => l10n.rightExplanationView,
      rightDownload => l10n.rightExplanationDownload,
      rightContribute => l10n.rightExplanationContribute,
      rightEdit => l10n.rightExplanationEdit,
      _ => "",
    };

/// What the caller may do with one folder, see [FolderResource.rights].
///
/// The stronger rights imply the weaker ones; the server applies that closure
/// before it answers, and [Rights.ofNames] applies it again, so nothing here
/// depends on the server having done it.
class Rights {
  /// The rights held, closed under the implications.
  final Set<String> names;

  /// Whether the server really answered them for this folder (issue #85).
  ///
  /// `false` for the [everything] that stands in where a folder carries no
  /// rights at all — that is a server from before issue #49 saying nothing,
  /// not a server saying "all of them". Where nothing was said the app falls
  /// back on the caller's role, see [offeredRights].
  final bool answered;

  const Rights._(this.names, {this.answered = true});

  /// Every right — what an owner holds, and what an answer that says nothing
  /// is read as, see [Rights.of].
  static const Rights everything = Rights._({
    rightView,
    rightDownload,
    rightContribute,
    rightEdit,
  });

  /// [everything], but marked as nobody's word, see [answered].
  static const Rights unanswered = Rights._({
    rightView,
    rightDownload,
    rightContribute,
    rightEdit,
  }, answered: false);

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
      return unanswered;
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
  String phrase(AppLocalizations l10n) {
    if (mayEdit) {
      return l10n.rightsPhraseEdit;
    }
    if (mayContribute) {
      return l10n.rightsPhraseContribute;
    }
    if (mayDownload) {
      return l10n.rightsPhraseDownload;
    }
    if (mayView) {
      return l10n.rightsPhraseView;
    }
    return l10n.rightsPhraseNone;
  }

  @override
  String toString() => "Rights(${names.join(", ")})";
}

/// What the app *offers* for a folder, see [Rights] and [CallerPermission].
///
/// The server's per-folder rights are the source of truth and stay it: where
/// they were answered they decide alone — nothing more is offered than they
/// allow, and nothing they allow is hidden. Where they were *not* answered
/// (a server from before issue #49, or a view that has no folder at all) the
/// caller's role decides instead, so that a `view` or `contribute` caller is
/// not offered the edit mode and a `view` caller no upload (issue #85).
///
/// And where nobody said anything — no rights, no role — everything is offered
/// exactly as before all of this existed: the app never hides on a guess, and
/// a refusal speaks when it comes.
Rights offeredRights(Rights rights, CallerPermission permission) {
  if (rights.answered || !permission.named) {
    return rights;
  }
  if (permission.mayEdit) {
    return Rights.everything;
  }
  if (permission.mayContribute) {
    return Rights.ofNames(const [rightContribute, rightDownload]);
  }
  return Rights.ofNames(const [rightView]);
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
/// This is the coordinate system a path inside somebody's space is spelled in,
/// so it is what says whether a folder is the caller's own or reached through
/// the `~owner` form.
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
String? sharingNotice(
  AppLocalizations l10n,
  List<String> path,
  Rights rights,
) {
  var owner = spaceOwnerOf(path);
  if (owner == null && rights.complete) {
    return null;
  }
  var by =
      owner == null ? l10n.sharedWithYou : l10n.sharedByOwner(owner);
  return "$by — ${rights.phrase(l10n)}";
}
