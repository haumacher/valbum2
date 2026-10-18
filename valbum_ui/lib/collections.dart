/// References, see issues #221 and #307: parts of an album that stand for
/// photographs lying in other albums of the space instead of holding files.
///
/// Every album may hold references beside its own files; a collection is an
/// album without files of its own, and the app shows it as any album. The
/// server answers a reference as the photograph as the caller would see it
/// in its own album, under a name of the album's own, and with a [PhotoRef]
/// saying where it lies ([ImagePart.ref], members only), see [isReference].
/// Every address stays `<album>/<name>` — the server answers it from the
/// source. A photograph that is gone from the space is a part with
/// [ImagePart.missing], answered to editors alone, which has no thumbnail and
/// is removed here.
///
/// The labels and the description of a reference are the album's own; every
/// other edit of the photograph is written by the server to the album it lies
/// in, so after every write of an album holding references the router
/// forgets those albums, see [forgetSources].
library;

import 'package:flutter/material.dart';

import 'action_guard.dart';
import 'app.dart';
import 'client.dart';
import 'dialog_style.dart';
import 'l10n/app_localizations.dart';
import 'offline.dart';
import 'photo_actions.dart' show photoSourceOf;
import 'resource.dart';
import 'selectable.dart';

/// The key of the mark a tile of a reference carries (issue #307).
const Key referenceMarkKey = Key("reference-mark");

/// Whether [image] is a reference of its album to a photograph of another
/// album, as the server tells a member (issue #307): a [PhotoRef] with the
/// photograph's hash. A photograph of a search names where it lies by its
/// path alone, and is no reference.
bool isReference(ImagePart image) {
  var ref = image.ref;
  return ref != null && ref.hash.isNotEmpty;
}

/// Whether [album] holds a reference ([isReference]).
bool holdsReferences(AlbumInfo album) =>
    _imagesOf(album.parts).any(isReference);

/// The folder (relative to the space root) the photograph [image] of a
/// reference or a search lies in, `null` where the server named none.
List<String>? sourceFolderOf(ImagePart image) {
  var ref = image.ref;
  if (ref == null || ref.path.isEmpty) {
    return null;
  }
  var segments = ref.path.split("/");
  return segments.sublist(0, segments.length - 1);
}

/// The folder line the image properties show for a reference or a
/// photograph of a search, `null` for every other photograph.
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

/// Forgets what the router holds for every album a reference of [album],
/// shown at [folder], points into, after a write of the album: the server
/// wrote the change of the photograph there (issues #221, #307), and the
/// album must show it when it is opened next. The album is the one
/// [photoSourceOf] names, in the space the album stands in (issue #301).
void forgetSources(
  VAlbumRouterDelegate? delegate,
  AlbumInfo album, {
  required List<String> folder,
}) {
  // A saved search writes to the albums its photographs lie in as well
  // (issue #227).
  if (delegate == null) {
    return;
  }
  var seen = <String>{};
  for (var image in _imagesOf(album.parts)) {
    if (image.ref == null) {
      continue;
    }
    var source = photoSourceOf(image, album: album, folder: folder);
    if (source != null && seen.add(source.folder.join("/"))) {
      delegate.forgetTree(source.folder);
    }
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

/// Asks whether the references [names] are to be taken out of the album at
/// [path] and takes them out, see issues #221 and #307: nothing on the disk
/// is touched, the photographs stay in their own albums.
///
/// Answers whether the request went through; the server's own account is read
/// out afterwards.
Future<bool> removeReferencesFromAlbum({
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
  var confirmed = await showAppDialog<bool>(
    context: context,
    builder: (context) => AppAlertDialog(
      key: const Key("remove-from-collection-dialog"),
      title: Text(l10n.removeReferencesQuestion(names.length)),
      content: Text(l10n.removeReferencesExplanation),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: Text(l10n.cancel),
        ),
        ElevatedButton(
          key: const Key("remove-from-collection-confirm"),
          onPressed: () => Navigator.of(context).pop(true),
          child: Text(l10n.removeReference),
        ),
      ],
    ),
  );
  if (confirmed != true || !context.mounted) {
    return false;
  }
  var done = await writeAction(context, () => client.delete(path, names));
  if (done == null) {
    return false;
  }
  var result = done.value;
  var said = {
    for (var outcome in result.outcomes)
      if (outcome.message.isNotEmpty) outcome.message,
  };
  messenger.showSnackBar(
    SnackBar(
      content: Text(
        said.isEmpty ? l10n.removedReferences(names.length) : said.join(" "),
      ),
      duration: const Duration(seconds: 6),
    ),
  );
  return true;
}
