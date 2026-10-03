/// The photographs of a space that lie in more than one album, the app half
/// of issue #220.
///
/// A space-level page (`/.duplicates/`, see [DuplicatesRoute]) on the root
/// listing, reached from the start page's ⋮ menu. One row per photograph:
/// its thumbnail and every album it lies in, each of them opening that album
/// at the photograph (the viewer route). **Nothing is moved or deleted** —
/// the server answers `?type=duplicates` read-only, every copy one the caller
/// may see, and this page only shows it.
///
/// It needs the server and nothing else: offline it says so with the usual
/// refusal and offers the retry, the cache holding no copy of it. While the
/// server's hash index is still being built (issue #118) it says so above
/// what is known so far.
library;

import 'package:flutter/material.dart' hide Orientation;

import 'album_date.dart';
import 'app.dart';
import 'client.dart';
import 'l10n/app_localizations.dart';
import 'offline.dart';
import 'oriented_thumbnail.dart';
import 'page_insets.dart';
import 'resource.dart';
import 'routes.dart';

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

/// The overview page of the photographs in several albums.
class DuplicatesView extends StatefulWidget {
  /// The page this screen stands in, see [VAlbumState].
  final VAlbumState albumState;

  const DuplicatesView(this.albumState, {super.key});

  @override
  State<DuplicatesView> createState() => DuplicatesViewState();
}

class DuplicatesViewState extends State<DuplicatesView> {
  /// The answer of the server, `null` while it is asked or could not be.
  DuplicateList? _list;

  /// Why there is no answer, `null` while there is one or it is asked.
  String? _failure;

  /// Whether the server is being asked.
  bool _loading = false;

  /// Whether the first load was started, see [didChangeDependencies].
  bool _started = false;

  AppLocalizations get _l10n => AppLocalizations.of(context)!;

  VAlbumClient get client => widget.albumState.client;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_started) {
      _started = true;
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

  /// Opens the album of [copy] at its photograph.
  void open(DuplicateCopy copy) {
    widget.albumState.navigator.go(
      ImageRoute(duplicateAlbumPath(copy), copy.name),
    );
  }

  @override
  Widget build(BuildContext context) {
    var list = _list;
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          key: const Key("duplicates-up"),
          icon: const Icon(Icons.arrow_back),
          tooltip: _l10n.up,
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
                  for (var copy in group.copies) _copy(copy),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// One album holding the photograph, opening it at the photograph.
  Widget _copy(DuplicateCopy copy) {
    var name = duplicateAlbumName(copy);
    var date = albumDateLabel(copy.albumDate);
    var file = copy.album.isEmpty ? copy.name : "${copy.album}/${copy.name}";
    return ListTile(
      key: ValueKey("duplicates-copy-$file"),
      dense: true,
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.photo_album_outlined),
      title: Text(name.isEmpty ? _l10n.duplicatesSpaceRoot : name),
      subtitle: Text([if (date != null) date, file].join(" · ")),
      trailing: const Icon(Icons.chevron_right),
      onTap: () => open(copy),
    );
  }
}
