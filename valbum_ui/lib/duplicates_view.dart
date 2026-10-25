/// The photographs of a space that lie in more than one album, the app half
/// of issue #220.
///
/// A space-level page (`/.duplicates/`, see [DuplicatesRoute]) on the root
/// listing, reached from the start page's ⋮ menu. One row per photograph:
/// its thumbnail and every album it lies in, each of them opening that album
/// at the photograph. The viewer opened here sits on this page (issue #228:
/// `/.duplicates/<album>/<name>`, an [ImageRoute] with `fromDuplicates`), so
/// its way back — the app bar, `Escape`, the system's and the browser's back
/// button — returns to this page as it was left, scroll offset and all.
///
/// The server answers `?type=duplicates` read-only, every copy one the caller
/// may see. An editor may take one copy out from here (issue #228, key
/// `duplicates-delete-<album>/<name>`): what "Delete" of a photograph is for an
/// editor since #224 — the copy is rated as trash (−2) in **its** album, after
/// a question, written through the album's ordinary sidecar PUT. Nothing is
/// moved and no file is touched; the photograph waits in that album's trash
/// (#152), from where it is restored, and a photograph rated as trash is no
/// copy any more, so the overview is asked again afterwards.
///
/// Or an editor resolves a photograph while keeping it shown everywhere
/// (issue #307): one copy of a group is the original — the first one, until
/// another is chosen by the round button before its album — and every other
/// copy can be kept *as a reference* to it, one by one ("Keep here as
/// reference", key `duplicates-keep-<album>/<name>`) or all at once ("Keep all
/// others as references", key `duplicates-keep-all-<hash>`). The server is
/// asked first what it would do (`?action=keep-as-references-check`), and the
/// question names how many files go into the trash and, in the server's own
/// words, which copies stay as they are and why; afterwards the page says
/// what happened and asks for the overview again. A copy kept as a reference
/// is no copy any more: the group shrinks or goes.
///
/// It needs the server and nothing else: offline it says so with the usual
/// refusal and offers the retry, the cache holding no copy of it. While the
/// server's hash index is still being built (issue #118) it says so above
/// what is known so far.
library;

import 'package:flutter/material.dart' hide Orientation;

import 'action_guard.dart';
import 'album_date.dart';
import 'app.dart';
import 'client.dart';
import 'dialog_style.dart';
import 'l10n/app_localizations.dart';
import 'offline.dart';
import 'oriented_thumbnail.dart';
import 'page_insets.dart';
import 'resource.dart';
import 'routes.dart';
import 'selectable.dart';
import 'trash_view.dart' show trashRating;
import 'page_app_bar.dart';

/// The side of a group's thumbnail, in logical pixels.
const double duplicatesThumbnailSize = 112;

/// The path segments of the album a copy lies in; the root is no segment.
List<String> duplicateAlbumPath(DuplicateCopy copy) =>
    copy.album.isEmpty ? const [] : copy.album.split("/");

/// The name a copy's album is shown by: its title, else its folder name, else
/// (the root of the space) nothing.
String duplicateAlbumName(DuplicateCopy copy) {
  if (copy.title.isNotEmpty) {
    return copy.title;
  }
  var path = duplicateAlbumPath(copy);
  return path.isEmpty ? "" : path.last;
}

/// The path of a copy's file below the space root, `album/name`: what names a
/// copy in the keys of this page.
String duplicateFilePath(DuplicateCopy copy) =>
    copy.album.isEmpty ? copy.name : "${copy.album}/${copy.name}";

/// The image of [album] named [name], a group member found by itself;
/// `null` where the album holds none of that name.
ImagePart? duplicateImageIn(AlbumInfo album, String name) {
  for (var part in album.parts) {
    var images = switch (part) {
      ImagePart() => [part],
      ImageGroup() => part.images,
      _ => const <ImagePart>[],
    };
    for (var image in images) {
      if (image.name == name) {
        return image;
      }
    }
  }
  return null;
}

/// The overview page of the photographs in several albums.
class DuplicatesView extends StatefulWidget {
  /// The page this screen stands in, see [VAlbumState].
  final VAlbumState albumState;

  const DuplicatesView(this.albumState, {super.key});

  @override
  State<DuplicatesView> createState() => DuplicatesViewState();
}

class DuplicatesViewState extends State<DuplicatesView> {
  /// The copy chosen as the original of a group, by the group's hash, as the
  /// path of its file ([duplicateFilePath]); a group not named here has its
  /// first copy as the original.
  final Map<String, String> _originals = {};

  /// The answer of the server, `null` while it is asked or could not be.
  DuplicateList? _list;

  /// Why there is no answer, `null` while there is one or it is asked.
  String? _failure;

  /// Whether the server is being asked.
  bool _loading = false;

  /// The reload counter of the router the page was last loaded at, see
  /// [didChangeDependencies].
  int? _version;

  AppLocalizations get _l10n => AppLocalizations.of(context)!;

  VAlbumClient get client => widget.albumState.client;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Asked once, and again only where something reloaded the route — a
    // photograph taken back out of its album in a viewer opened here, say.
    // Coming back from such a viewer is no reason: the page stayed mounted
    // beneath it as it was (issue #228).
    var version = VAlbumNavigator.of(context).version;
    if (_version != version) {
      _version = version;
      load();
    }
  }

  /// Asks the server for the overview, or says why that is not possible.
  Future<void> load() async {
    if (OfflineScope.isOffline(context)) {
      // Nothing of this page is cached: offline it is the usual refusal.
      setState(() {
        _list = null;
        _failure = _l10n.offlineRefusal;
      });
      return;
    }
    setState(() {
      _loading = true;
      _failure = null;
    });
    try {
      var list = await client.duplicates();
      if (!mounted) {
        return;
      }
      setState(() {
        _list = list;
        _loading = false;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _list = null;
        _loading = false;
        // A server that cannot be reached is the offline refusal; a server
        // that answered speaks for itself.
        _failure = VAlbumClient.isTransportFailure(error)
            ? _l10n.offlineRefusal
            : error is VAlbumException
                ? error.message
                : "$error";
      });
    }
  }

  /// Opens the album of [copy] at its photograph, in a viewer that leads
  /// back to this page (issue #228).
  void open(DuplicateCopy copy) {
    widget.albumState.navigator.go(
      ImageRoute(duplicateAlbumPath(copy), copy.name, fromDuplicates: true),
    );
  }

  /// Whether the caller may take a copy out of its album: whoever may edit
  /// that album, and everybody where the server names nobody (`--auth off`).
  ///
  /// The delete is the album's ordinary sidecar PUT, which the server grants
  /// to `edit` on that album. The page holds no album, so it asks [rightsOf]
  /// for the space: for a paired caller that *is* the album's answer — the
  /// server derives a paired caller's rights from the role alone, the same in
  /// every album of the space (`AuthService.rights`: "one role, one answer,
  /// no path to consult"), so no request per album is needed to know it (#293).
  bool get mayDelete => rightsOf(context, null).mayEdit;

  /// The name [copy]'s album is spoken of by in a sentence.
  String _albumWord(DuplicateCopy copy) {
    var name = duplicateAlbumName(copy);
    return name.isEmpty ? _l10n.duplicatesSpaceRoot : name;
  }

  /// Moves [copy] to the trash of its own album, after asking: the copy is
  /// rated as trash there, exactly as "Delete" of a photograph is for an
  /// editor (#224), and nothing else of that album changes.
  Future<void> delete(DuplicateCopy copy) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var album = _albumWord(copy);
    var confirmed = await showAppDialog<bool>(
      context: context,
      builder: (context) => AppAlertDialog(
        key: const Key("duplicates-delete-dialog"),
        title: Text(_l10n.duplicatesDeleteTitle),
        content: Text(_l10n.duplicatesDeleteQuestion(album, copy.name)),
        actions: [
          TextButton(
            key: const Key("duplicates-delete-cancel"),
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(_l10n.cancel),
          ),
          ElevatedButton(
            key: const Key("duplicates-delete-confirm"),
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(_l10n.duplicatesDeleteConfirm),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }
    var messenger = ScaffoldMessenger.of(context);
    var path = duplicateAlbumPath(copy);
    // Asked again by [writeAction]: the connection may have gone while the
    // question stood. A refusal is the server's own reason, nothing changed
    // (issue #49).
    var done = await writeAction(context, () async {
      // The album as the server holds it now, not a copy the router may have
      // kept: only the one rating is changed in what is written back.
      var resource = await client.loadResource(path);
      if (resource is! AlbumInfo) {
        throw VAlbumException(_l10n.noSuchImage(copy.name));
      }
      var image = duplicateImageIn(resource, copy.name);
      if (image == null) {
        throw VAlbumException(_l10n.noSuchImage(copy.name));
      }
      image.rating = trashRating;
      return await client.saveAlbum(path, resource);
    });
    if (done == null) {
      return;
    }
    // The album holds something else now: the next visit asks the server.
    widget.albumState.navigator.delegate.forget(path);
    if (!mounted) {
      return;
    }
    messenger.showSnackBar(
      SnackBar(
        key: const Key("duplicates-deleted"),
        content: Text(_l10n.duplicatesDeleted(album, copy.name)),
      ),
    );
    // A photograph rated as trash is no copy: the group shrinks or goes.
    await load();
  }

  /// The copy of [group] kept as the original, see [_originals].
  DuplicateCopy? originalOf(DuplicateGroup group) {
    var chosen = _originals[group.hash];
    for (var copy in group.copies) {
      if (duplicateFilePath(copy) == chosen) {
        return copy;
      }
    }
    return group.copies.isEmpty ? null : group.copies.first;
  }

  /// Keeps [copies] of [group] as references to its original (issue #307):
  /// asks the server what it would do, puts that to the user — how many files
  /// go into the trash, which copies stay and why — and on their word does
  /// it, says what happened and asks for the overview again.
  Future<void> keepAsReferences(
    DuplicateGroup group,
    List<DuplicateCopy> copies,
  ) async {
    var original = originalOf(group);
    if (original == null || copies.isEmpty || refuseWhileOffline(context)) {
      return;
    }
    var messenger = ScaffoldMessenger.of(context);
    var originalPath = duplicateFilePath(original);
    var paths = [for (var copy in copies) duplicateFilePath(copy)];
    var byPath = {for (var copy in copies) duplicateFilePath(copy): copy};

    // The server's own answer to what it would do: one round trip, the code
    // that does it answering.
    var check = await writeAction(
      context,
      () => client.keepAsReferences(originalPath, paths, dryRun: true),
    );
    if (check == null || !mounted) {
      return;
    }
    var accepted = <DuplicateCopy>[];
    var refusals = <String>[];
    for (var outcome in check.value.outcomes) {
      var copy = byPath[outcome.name];
      if (copy == null) {
        continue;
      }
      if (outcome.message.isEmpty) {
        accepted.add(copy);
      } else {
        refusals
            .add(_l10n.duplicatesKeepReason(outcome.message, _albumWord(copy)));
      }
    }

    var confirmed = await showAppDialog<bool>(
      context: context,
      builder: (context) => AppAlertDialog(
        key: const Key("duplicates-keep-dialog"),
        title: Text(_l10n.duplicatesKeepTitle),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (accepted.isNotEmpty)
                Text(
                  _l10n.duplicatesKeepQuestion(
                    accepted
                        .map((copy) => _l10n.keptAlbumName(_albumWord(copy)))
                        .toSet()
                        .join(", "),
                    _albumWord(original),
                    accepted.length,
                  ),
                  key: const Key("duplicates-keep-question"),
                ),
              if (refusals.isNotEmpty) ...[
                if (accepted.isNotEmpty) const SizedBox(height: 12),
                Text(
                  _l10n.duplicatesKeepRefused(refusals.length),
                  key: const Key("duplicates-keep-refused"),
                ),
                for (var refusal in refusals)
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Text(refusal),
                  ),
              ],
            ],
          ),
        ),
        actions: [
          TextButton(
            key: const Key("duplicates-keep-cancel"),
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(accepted.isEmpty ? _l10n.close : _l10n.cancel),
          ),
          if (accepted.isNotEmpty)
            ElevatedButton(
              key: const Key("duplicates-keep-confirm"),
              onPressed: () => Navigator.of(context).pop(true),
              child: Text(_l10n.duplicatesKeepConfirm),
            ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }

    var done = await writeAction(
      context,
      () => client.keepAsReferences(
        originalPath,
        [for (var copy in accepted) duplicateFilePath(copy)],
      ),
    );
    if (done == null) {
      return;
    }
    var kept = 0;
    var reasons = <String>[];
    for (var outcome in done.value.outcomes) {
      var copy = byPath[outcome.name];
      if (outcome.newName.isNotEmpty) {
        kept++;
        if (copy != null) {
          // The album holds something else now: the next visit asks the
          // server.
          widget.albumState.navigator.delegate.forget(duplicateAlbumPath(copy));
        }
      } else if (outcome.message.isNotEmpty) {
        reasons.add(copy == null
            ? outcome.message
            : _l10n.duplicatesKeepReason(outcome.message, _albumWord(copy)));
      }
    }
    if (!mounted) {
      return;
    }
    var report = [
      if (kept > 0) _l10n.duplicatesKept(kept),
      if (reasons.isNotEmpty)
        _l10n.duplicatesKeptRefused(reasons.join(" "), reasons.length),
    ].join(" ");
    if (report.isNotEmpty) {
      messenger.showSnackBar(
        SnackBar(
          key: const Key("duplicates-kept"),
          content: Text(report, key: const Key("duplicates-kept-text")),
          duration: const Duration(seconds: 8),
        ),
      );
    }
    // A copy kept as a reference is no copy: the group shrinks or goes.
    await load();
  }

  @override
  Widget build(BuildContext context) {
    var list = _list;
    return Scaffold(
      appBar: PageAppBar(
        leading: WayBackButton(
          key: const Key("duplicates-up"),
          onPressed: widget.albumState.navigator.up,
        ),
        automaticallyImplyLeading: false,
        title: Text(
          list == null
              ? _l10n.duplicatesMenuEntry
              : _l10n.duplicatesPageTitle(list.groups.length),
          key: const Key("duplicates-title"),
        ),
        actions: [
          IconButton(
            key: const Key("duplicates-reload"),
            icon: const Icon(Icons.refresh),
            tooltip: _l10n.reload,
            onPressed: _loading ? null : load,
          ),
        ],
      ),
      body: _body(list),
    );
  }

  Widget _body(DuplicateList? list) {
    var failure = _failure;
    if (failure != null) {
      return _notice(
        failure,
        const Key("duplicates-failure"),
        action: TextButton(
          key: const Key("duplicates-retry"),
          onPressed: load,
          child: Text(_l10n.retry),
        ),
      );
    }
    if (list == null) {
      return const Center(child: CircularProgressIndicator());
    }
    var indexed = list.indexed;
    var indexing = indexed != null && indexed.done < indexed.total;
    return ListView(
      padding: pagePadding(context, const EdgeInsets.all(8)),
      children: [
        if (indexing)
          Card(
            key: const Key("duplicates-indexing"),
            color: Colors.amber.shade100,
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Text(
                // The generated signature names the total first.
                _l10n.duplicatesIndexing(indexed.total, indexed.done),
                style: const TextStyle(color: Colors.black87),
              ),
            ),
          ),
        if (list.groups.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 48, horizontal: 16),
            child: Text(
              _l10n.duplicatesEmpty,
              key: const Key("duplicates-empty"),
              textAlign: TextAlign.center,
            ),
          ),
        for (var group in list.groups) _group(group),
      ],
    );
  }

  Widget _notice(String text, Key key, {Widget? action}) => Center(
        child: Padding(
          padding: pagePadding(context, const EdgeInsets.all(24)),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(text, key: key, textAlign: TextAlign.center),
              if (action != null) ...[
                const SizedBox(height: 16),
                action,
              ],
            ],
          ),
        ),
      );

  /// One photograph: its thumbnail and every album it lies in.
  Widget _group(DuplicateGroup group) {
    var first = group.copies.isEmpty ? null : group.copies.first;
    var image = group.image;
    var date = albumDateLabel(group.date);
    return Card(
      key: ValueKey("duplicates-group-${group.hash}"),
      child: Padding(
        padding: const EdgeInsets.all(8),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // A screen reader names the photograph the thumbnail opens.
            Semantics(
              label: first?.name,
              child: InkWell(
                key: ValueKey("duplicates-thumbnail-${group.hash}"),
                onTap: first == null ? null : () => open(first),
                child: SizedBox(
                  width: duplicatesThumbnailSize,
                  height: duplicatesThumbnailSize,
                  child: ColoredBox(
                    color: Colors.black12,
                    child: image == null || first == null
                        ? const Icon(Icons.photo)
                        : orientedImageThumbnail(
                            client,
                            "${client.folderUrl(duplicateAlbumPath(first))}"
                            "${image.name}",
                            image,
                            width: duplicatesThumbnailSize,
                            height: duplicatesThumbnailSize,
                          ),
                  ),
                ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    [
                      if (date != null) date,
                      _l10n.duplicatesCopies(group.copies.length),
                    ].join(" · "),
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  for (var copy in group.copies) _copy(group, copy),
                  if (mayDelete && group.copies.length > 1)
                    Align(
                      alignment: AlignmentDirectional.centerStart,
                      child: TextButton.icon(
                        key: ValueKey("duplicates-keep-all-${group.hash}"),
                        icon: const Icon(Icons.link),
                        label: Text(_l10n.duplicatesKeepAllAsReferences),
                        onPressed: () => keepAsReferences(group, [
                          for (var copy in group.copies)
                            if (!identical(copy, originalOf(group))) copy,
                        ]),
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// One album holding the photograph, opening it at the photograph.
  Widget _copy(DuplicateGroup group, DuplicateCopy copy) {
    var name = duplicateAlbumName(copy);
    var date = albumDateLabel(copy.albumDate);
    var file = duplicateFilePath(copy);
    var isOriginal = identical(copy, originalOf(group));
    return ListTile(
      key: ValueKey("duplicates-copy-$file"),
      dense: true,
      contentPadding: EdgeInsets.zero,
      leading: !mayDelete
          ? const Icon(Icons.photo_album_outlined)
          : IconButton(
              key: ValueKey("duplicates-original-$file"),
              icon: Icon(isOriginal
                  ? Icons.radio_button_checked
                  : Icons.radio_button_unchecked),
              tooltip: isOriginal
                  ? _l10n.duplicatesOriginal
                  : _l10n.duplicatesChooseOriginal,
              onPressed: isOriginal
                  ? null
                  : () => setState(() => _originals[group.hash] = file),
            ),
      title: Text(name.isEmpty ? _l10n.duplicatesSpaceRoot : name),
      subtitle: Text([if (date != null) date, file].join(" · ")),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (mayDelete && !isOriginal)
            IconButton(
              key: ValueKey("duplicates-keep-$file"),
              icon: const Icon(Icons.link),
              tooltip: _l10n.duplicatesKeepAsReference,
              onPressed: () => keepAsReferences(group, [copy]),
            ),
          if (mayDelete)
            IconButton(
              key: ValueKey("duplicates-delete-$file"),
              icon: const Icon(Icons.delete_outline),
              tooltip: _l10n.duplicatesDeleteTooltip,
              onPressed: () => delete(copy),
            ),
          const Icon(Icons.chevron_right),
        ],
      ),
      onTap: () => open(copy),
    );
  }
}
