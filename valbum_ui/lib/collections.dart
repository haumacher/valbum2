/// Collections, see issue #221: albums whose parts *reference* photographs
/// lying in other albums of the space instead of holding files.
///
/// The server answers a collection as an album of kind
/// [AlbumKind.collection]: every [ImagePart] is the photograph as the caller
/// would see it in its own album, under a name of the collection's own, and
/// carries a [PhotoRef] saying where it lies ([ImagePart.ref], members only).
/// Every address stays `<collection>/<name>` — the server answers it from the
/// source. A photograph that is gone from the space is a part with
/// [ImagePart.missing], answered to editors alone, which has no thumbnail and
/// is removed here.
///
/// An edit of a photograph shown in a collection is written by the server to
/// the album the photograph lies in, so after every write through a
/// collection the router forgets those albums, see [forgetSources].
library;

import 'package:flutter/material.dart';

import 'app.dart';
import 'client.dart';
import 'l10n/app_localizations.dart';
import 'move_view.dart';
import 'offline.dart';
import 'resource.dart';

/// Whether [album] is a collection.
bool isCollection(AlbumInfo album) => album.kind == AlbumKind.collection;

/// The folder (relative to the space root) the photograph [image] of a
/// collection lies in, `null` where the server named none.
List<String>? sourceFolderOf(ImagePart image) {
  var ref = image.ref;
  if (ref == null || ref.path.isEmpty) {
    return null;
  }
  var segments = ref.path.split("/");
  return segments.sublist(0, segments.length - 1);
}

/// The folder line the image properties show for a photograph of a
/// collection, `null` for every other photograph.
String? sourceFolderLabel(ImagePart image) {
  var folder = sourceFolderOf(image);
  if (folder == null) {
    return null;
  }
  return folder.isEmpty ? "/" : folder.join("/");
}

/// The images of [parts], group members one by one.
Iterable<ImagePart> _imagesOf(Iterable<AlbumPart> parts) sync* {
  for (var part in parts) {
    if (part is ImagePart) {
      yield part;
    } else if (part is ImageGroup) {
      yield* part.images;
    }
  }
}

/// Forgets what the router holds for every album a photograph of the
/// collection [album] lies in, after a write through the collection: the
/// server wrote the change there (issue #221), and the album must show it when
/// it is opened next.
void forgetSources(VAlbumRouterDelegate? delegate, AlbumInfo album) {
  if (delegate == null || !isCollection(album)) {
    return;
  }
  var seen = <String>{};
  for (var image in _imagesOf(album.parts)) {
    var folder = sourceFolderOf(image);
    if (folder != null && seen.add(folder.join("/"))) {
      delegate.forgetTree(folder);
    }
  }
}

/// Forgets what the router holds for the album the photograph [image] of a
/// collection lies in, after a write through the collection (issue #221);
/// nothing for a photograph of an ordinary album.
void forgetSourceOf(VAlbumRouterDelegate? delegate, ImagePart image) {
  var folder = sourceFolderOf(image);
  if (delegate != null && folder != null) {
    delegate.forgetTree(folder);
  }
}

/// The tile of a photograph that is gone from the space.
///
/// No thumbnail is asked for: there is nothing to answer it from.
Widget missingTile(AppLocalizations l10n, double width, double height) =>
    SizedBox(
      key: const Key("collection-missing"),
      width: width,
      height: height,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: Colors.grey.shade900,
          border: Border.all(color: Colors.white24),
        ),
        child: Padding(
          padding: const EdgeInsets.all(8),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.broken_image_outlined, color: Colors.white54),
              const SizedBox(height: 4),
              Flexible(
                child: Text(
                  l10n.collectionPhotoMissing,
                  textAlign: TextAlign.center,
                  overflow: TextOverflow.fade,
                  style: const TextStyle(color: Colors.white70, fontSize: 12),
                ),
              ),
            ],
          ),
        ),
      ),
    );

/// Asks whether [names] are to be taken out of the collection at [path] and
/// takes them out, see issue #221: nothing on the disk is touched, the
/// photographs stay in their albums.
///
/// Answers whether the request went through; the server's own account is read
/// out afterwards.
Future<bool> removeFromCollection({
  required BuildContext context,
  required VAlbumClient client,
  required List<String> path,
  required List<String> names,
}) async {
  if (refuseWhileOffline(context)) {
    return false;
  }
  var l10n = AppLocalizations.of(context)!;
  var messenger = ScaffoldMessenger.of(context);
  var confirmed = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      key: const Key("remove-from-collection-dialog"),
      title: Text(l10n.removeFromCollectionQuestion(names.length)),
      content: Text(l10n.removeFromCollectionExplanation),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: Text(l10n.cancel),
        ),
        ElevatedButton(
          key: const Key("remove-from-collection-confirm"),
          onPressed: () => Navigator.of(context).pop(true),
          child: Text(l10n.removeFromCollection),
        ),
      ],
    ),
  );
  if (confirmed != true || !context.mounted) {
    return false;
  }
  MoveResult result;
  try {
    result = await client.delete(path, names);
  } catch (error) {
    showRefusal(messenger, error);
    return false;
  }
  var said = {
    for (var outcome in result.outcomes)
      if (outcome.message.isNotEmpty) outcome.message,
  };
  messenger.showSnackBar(
    SnackBar(
      content: Text(
        said.isEmpty
            ? l10n.removedFromCollection(names.length)
            : said.join(" "),
      ),
      duration: const Duration(seconds: 6),
    ),
  );
  return true;
}
