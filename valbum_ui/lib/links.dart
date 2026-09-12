/// Link entries: an album somebody shared, showing in the recipient's own
/// tree, see issue #50.
///
/// A tile whose [FolderInfo.link] is non-empty is not a folder on disk but a
/// link to `~<owner>/<path>` — the canonical form of issue #49. Everything the
/// tile shows (its title, its subtitle, its index picture and its effective
/// date) is read from the target by the server, so a link looks like what it
/// points at; the app only needs to *mark* it and to name its owner.
///
/// Navigating into a link is ordinary navigation on the viewer's own path: the
/// server resolves the segment and answers the target, spelled in the viewer's
/// coordinates. The app therefore never follows a link itself — with one
/// exception, [viewerPathFor], which maps a canonical URL somebody pasted back
/// onto the viewer's own tree.
///
/// This library is pure — no widgets, no HTTP — so both rules can be
/// unit-tested on their own.
library;

import 'resource.dart';
import 'rights.dart';

/// The owner of the album a tile links to, `null` for an ordinary folder.
///
/// The link is the canonical `~<owner>/<path>`, so the owner is the name of
/// the space it reaches into, see [spaceOwnerOf]. A link the app cannot read
/// an owner out of (an empty link, a `~/…` naming nobody) yields `null`: the
/// tile is then shown as what it is, without a name attached to it.
String? linkOwnerOf(FolderInfo folder) => spaceOwnerOf(linkSegmentsOf(folder));

/// The segments of a tile's [FolderInfo.link], the empty list for an ordinary
/// folder.
List<String> linkSegmentsOf(FolderInfo folder) => splitLink(folder.link);

/// The segments of a canonical path, the empty list for the empty string.
List<String> splitLink(String link) =>
    [for (var segment in link.split("/")) if (segment.isNotEmpty) segment];

/// Where the canonical path [requested] lies in the viewer's own tree, `null`
/// when the viewer holds no link to it.
///
/// A canonical URL (`~alice/2024/Zoo`, the form a share link and a copied URL
/// use) works for anybody holding a grant on the target, but a member who was
/// given a link to the album already has it in their own tree — and there the
/// URL, the way up and the scroll memory are the viewer's own. So a canonical
/// route is mapped onto that entry before it is opened.
///
/// The match is made segment by segment against the [FolderInfo.link] of the
/// tiles of [root], and the **longest** matching link wins: a link may point
/// at a folder above the album that was asked for, in which case the rest of
/// the requested path is appended to the tile's name. A request that is not
/// canonical at all is not a question for this function and answers `null`.
///
/// Only the viewer's root listing is consulted. That is where the server
/// materialises a link (a link the user has moved elsewhere is not found, and
/// the canonical path is opened instead, which works); a lookup endpoint that
/// finds a link wherever it was moved to is the seam of issue #55.
List<String>? viewerPathFor(List<String> requested, ListingInfo root) {
  if (spaceOwnerOf(requested) == null) {
    return null;
  }
  FolderInfo? best;
  var bestLength = 0;
  for (var folder in root.folders) {
    var link = linkSegmentsOf(folder);
    if (link.length <= bestLength || !_isPrefix(link, requested)) {
      continue;
    }
    best = folder;
    bestLength = link.length;
  }
  if (best == null) {
    return null;
  }
  return [best.name, ...requested.sublist(bestLength)];
}

/// Whether [prefix] is a non-empty leading part of [path].
bool _isPrefix(List<String> prefix, List<String> path) {
  if (prefix.isEmpty || prefix.length > path.length) {
    return false;
  }
  for (var index = 0; index < prefix.length; index++) {
    if (prefix[index] != path[index]) {
      return false;
    }
  }
  return true;
}
