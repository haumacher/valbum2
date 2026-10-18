/// The shots of the same scenery in several albums of a space, the app half
/// of the overview of issue #257 beside the duplicates of #220.
///
/// A space-level page (`/.similar/`, see [SimilarRoute]) on the root listing,
/// reached from the start page's ⋮ menu where the duplicates are. One row per
/// group of photographs that look alike: their thumbnails side by side, each
/// with the album it lies in, each opening that album's viewer at the
/// photograph. The viewer opened here sits on this page
/// (`/.similar/<album>/<name>`, an [ImageRoute] with `fromSimilar`), so its
/// way back returns to this page as it was left, exactly as for the
/// duplicates (#228).
///
/// Read-only: nothing is offered here but the way into the viewer, which
/// offers what it always does. The server answers `?type=similar`, every
/// photograph one the caller may see, and refuses a share link; offline the
/// page says so with the usual refusal, the cache holding no copy of it.
/// While the server is still describing the photographs for the content
/// search it says so above what is known so far, and a space with the content
/// search off says why the page is empty.
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
import 'page_app_bar.dart';

/// The side of a thumbnail of the overview, in logical pixels.
const double similarThumbnailSize = 112;

/// The path segments of the album a photograph of a group lies in.
List<String> similarAlbumPath(SimilarPhoto photo) =>
    photo.album.isEmpty ? const [] : photo.album.split("/");

/// The path of a photograph below the space root, `album/name`: what names it
/// in the keys of this page.
String similarFilePath(SimilarPhoto photo) =>
    photo.album.isEmpty ? photo.name : "${photo.album}/${photo.name}";

/// The overview page of the shots of the same scenery.
class SimilarView extends StatefulWidget {
  /// The page this screen stands in, see [VAlbumState].
  final VAlbumState albumState;

  const SimilarView(this.albumState, {super.key});

  @override
  State<SimilarView> createState() => SimilarViewState();
}

class SimilarViewState extends State<SimilarView> {
  /// The answer of the server, `null` while it is asked or could not be.
  SimilarList? _list;

  /// Why there is no answer, `null` while there is one or it is asked.
  String? _failure;

  /// Whether the server is being asked.
  bool _loading = false;

  /// The reload counter of the router the page was last loaded at: asked
  /// again only where something reloaded the route, never on the way back
  /// from a viewer opened here, which finds the page as it was left.
  int? _version;

  AppLocalizations get _l10n => AppLocalizations.of(context)!;

  VAlbumClient get client => widget.albumState.client;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    var version = VAlbumNavigator.of(context).version;
    if (_version != version) {
      _version = version;
      load();
    }
  }

  /// Asks the server for the overview, or says why that is not possible.
  Future<void> load() async {
    if (OfflineScope.isOffline(context)) {
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
      var list = await client.similar();
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
        _failure = VAlbumClient.isTransportFailure(error)
            ? _l10n.offlineRefusal
            : error is VAlbumException
                ? error.message
                : "$error";
      });
    }
  }

  /// Opens the album of [photo] at the photograph, in a viewer that leads
  /// back to this page.
  void open(SimilarPhoto photo) {
    widget.albumState.navigator.go(
      ImageRoute(similarAlbumPath(photo), photo.name, fromSimilar: true),
    );
  }

  /// The name the album of [photo] is shown by: its title, else its folder
  /// name, else (the root of the space) the start page.
  String _albumName(SimilarPhoto photo) {
    if (photo.title.isNotEmpty) {
      return photo.title;
    }
    var path = similarAlbumPath(photo);
    return path.isEmpty ? _l10n.duplicatesSpaceRoot : path.last;
  }

  @override
  Widget build(BuildContext context) {
    var list = _list;
    return Scaffold(
      appBar: PageAppBar(
        leading: IconButton(
          key: const Key("similar-up"),
          icon: const Icon(Icons.arrow_back),
          tooltip: _l10n.up,
          onPressed: widget.albumState.navigator.up,
        ),
        automaticallyImplyLeading: false,
        title: Text(
          list == null
              ? _l10n.similarMenuEntry
              : _l10n.similarPageTitle(list.groups.length),
          key: const Key("similar-title"),
        ),
        actions: [
          IconButton(
            key: const Key("similar-reload"),
            icon: const Icon(Icons.refresh),
            tooltip: _l10n.reload,
            onPressed: _loading ? null : load,
          ),
        ],
      ),
      body: _body(list),
    );
  }

  Widget _body(SimilarList? list) {
    var failure = _failure;
    if (failure != null) {
      return Center(
        child: Padding(
          padding: pagePadding(context, const EdgeInsets.all(24)),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                failure,
                key: const Key("similar-failure"),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 16),
              TextButton(
                key: const Key("similar-retry"),
                onPressed: load,
                child: Text(_l10n.retry),
              ),
            ],
          ),
        ),
      );
    }
    if (list == null) {
      return const Center(child: CircularProgressIndicator());
    }
    var described = list.described;
    var describing =
        list.content && described != null && described.done < described.total;
    return ListView(
      padding: pagePadding(context, const EdgeInsets.all(8)),
      children: [
        if (describing)
          Card(
            key: const Key("similar-describing"),
            color: Colors.amber.shade100,
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Text(
                // The generated signature names the total first.
                _l10n.similarDescribing(described.total, described.done),
                style: const TextStyle(color: Colors.black87),
              ),
            ),
          ),
        if (list.groups.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 48, horizontal: 16),
            child: Text(
              list.content ? _l10n.similarEmpty : _l10n.similarOff,
              key: Key(list.content ? "similar-empty" : "similar-off"),
              textAlign: TextAlign.center,
            ),
          ),
        for (var (index, group) in list.groups.indexed) _group(index, group),
      ],
    );
  }

  /// One group: how many photographs it holds and when, then their
  /// thumbnails in a row that scrolls sideways.
  Widget _group(int index, SimilarGroup group) {
    var date = albumDateLabel(group.date);
    return Card(
      key: ValueKey("similar-group-$index"),
      child: Padding(
        padding: const EdgeInsets.all(8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              [
                if (date != null) date,
                _l10n.similarPhotos(group.photos.length),
              ].join(" · "),
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 8),
            SizedBox(
              height: similarThumbnailSize + 40,
              child: ListView(
                scrollDirection: Axis.horizontal,
                children: [
                  for (var photo in group.photos) _photo(photo),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// One photograph of a group, with its album, opening the viewer.
  Widget _photo(SimilarPhoto photo) {
    var image = photo.image;
    var file = similarFilePath(photo);
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: Semantics(
        label: "${_albumName(photo)}: ${photo.name}",
        child: InkWell(
          key: ValueKey("similar-photo-$file"),
          onTap: () => open(photo),
          child: SizedBox(
            width: similarThumbnailSize,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SizedBox(
                  width: similarThumbnailSize,
                  height: similarThumbnailSize,
                  child: ColoredBox(
                    color: Colors.black12,
                    child: image == null
                        ? const Icon(Icons.photo)
                        : orientedImageThumbnail(
                            client,
                            "${client.folderUrl(similarAlbumPath(photo))}"
                            "${image.name}",
                            image,
                            width: similarThumbnailSize,
                            height: similarThumbnailSize,
                          ),
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  _albumName(photo),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
