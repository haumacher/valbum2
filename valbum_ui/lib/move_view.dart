/// Moving images, albums and folders into another folder, see issue #47.
///
/// The user picks a target folder in [FolderPicker], the move is posted to the
/// source folder (`?action=move`, see [VAlbumClient.move]), and what the
/// server answers is shown: every entry that did not move says why.
library;

import 'dart:async';

import 'package:flutter/material.dart';

import 'action_guard.dart';
import 'album_date.dart';
import 'app.dart';
import 'client.dart';
import 'dialog_style.dart';
import 'filter_list.dart';
import 'form_dialog.dart';
import 'kept_photos.dart';
import 'l10n/app_localizations.dart';
import 'listing_view.dart';
import 'loadable.dart';
import 'offline.dart';
import 'resource.dart';
import 'rights.dart';
import 'selectable.dart';

export 'loadable.dart' show reasonOf;

/// What a move acts on, as the button and the message name it.
///
/// The move of a multi-selection counts images, the move of a single tile of a
/// listing names it: both are one sentence — "Move 3 images to '2021'", "Move
/// '2020 Trip' to '2021'" — so the sentence is built from this.
abstract class MoveSubject {
  /// Names everything the move was asked for, e.g. `3 images`.
  String asked(AppLocalizations l10n);

  /// Names the [count] entries that actually moved.
  String moved(AppLocalizations l10n, int count);

  /// Whether what moves lives *in* an album, see issue #113.
  ///
  /// The kind of the target decides whether a move is possible at all: an
  /// image lives in an album and nowhere else, an album (or a folder) lives in
  /// a folder of folders and nowhere else. The picker therefore offers only
  /// the kind this subject can go to, instead of letting the server decline
  /// what it already knows is wrong.
  bool get livesInAlbum;
}

/// A number of images (or videos) of an album, see [MoveSubject].
class ImageSubject implements MoveSubject {
  final int count;

  const ImageSubject(this.count);

  @override
  String asked(AppLocalizations l10n) => moved(l10n, count);

  @override
  String moved(AppLocalizations l10n, int count) => l10n.imageCount(count);

  @override
  bool get livesInAlbum => true;
}

/// One named entry of a folder — an album, a folder, a file.
class EntrySubject implements MoveSubject {
  final String name;

  const EntrySubject(this.name);

  @override
  String asked(AppLocalizations l10n) => "'$name'";

  @override
  String moved(AppLocalizations l10n, int count) => "'$name'";

  @override
  bool get livesInAlbum => false;
}

/// How the target folder of a move is named on the screen.
///
/// The wire target is a path relative to the root of the caller's space, the
/// empty string being the root itself — which has no name, so it is called
/// what the app calls it everywhere else.
String targetLabel(AppLocalizations l10n, List<String> target) =>
    target.isEmpty ? l10n.targetTopLevel : "'${target.join("/")}'";

/// The wire form of a target folder path: the segments, the root empty.
String targetPath(List<String> target) => target.join("/");

/// Asks for a target folder and moves [names] out of the folder at [source].
///
/// Refused while the app is offline, like every other write. Nothing is sent
/// while the picker is open, and nothing is sent when it is cancelled. After
/// a move [onMoved] is called — the caller reloads the view it shows, because
/// what moved is no longer where it was — and the outcome is reported: a snack
/// bar when everything moved, a dialog when the server refused an entry, so a
/// refusal is never missed.
///
/// [delegate] is the router holding what the session has already loaded, and
/// the *target* of the move is forgotten here, tree and all, see issue #134:
/// the caller knows the folder it moved out of, but only this function knows
/// where the move went — and a target visited earlier in the session would
/// otherwise still be shown without the album that has just landed in it.
/// `null` is a view built outside the router, which has nothing to forget.
///
/// The picker offers only targets of the kind [MoveSubject.livesInAlbum]
/// names, see issue #113, and — for images — the album that does not exist
/// yet: [takenOn] is the earliest day the selection was taken on
/// ([earliestTaken]), which such an album is proposed with (today where it is
/// `null`), see issue #114. The album is created first
/// and the images are then moved into the path the server answered for it,
/// which is not necessarily the path it was asked for: a placement rule of
/// the folder above files it into its year folder, see issue #48.
///
/// Images are moved to where they belong, see issue #279: the picker opens on
/// the album the server suggests for [takenOn] (the one nearest to that day,
/// never [source] itself while there is another), selected — or, where
/// nothing says when the images were taken, on [source] selected. See
/// [FolderPicker.suggestNear].
///
/// An album the picker created in this very dialog is said to be so in the
/// move request, and the server then gives it its cover from what arrives,
/// see issue #277: the first best-rated photo above normal, else [cover],
/// else the earliest. A move into an album that already existed never touches
/// its cover. [cover] is the photograph the move was opened on from its
/// context menu (issue #273), `null` for a move of the selection bar (no
/// photo under the pointer).
Future<void> moveWithPicker({
  required BuildContext context,
  required VAlbumClient client,
  required List<String> source,
  required List<String> names,
  required MoveSubject subject,
  required VAlbumRouterDelegate? delegate,
  required VoidCallback onMoved,
  DateTime? takenOn,
  String? cover,
}) async {
  if (refuseWhileOffline(context)) {
    return;
  }
  var l10n = AppLocalizations.of(context)!;
  if (names.isEmpty) {
    _say(context, l10n.nothingToMove);
    return;
  }

  var messenger = ScaffoldMessenger.of(context);
  var picked = await showFormDialog<PickedTarget>(
    context: context,
    builder: (context) => FolderPicker(
      client: client,
      initialPath:
          source.isEmpty ? const [] : source.sublist(0, source.length - 1),
      confirmLabel: (path) =>
          l10n.moveConfirm(subject.asked(l10n), targetLabel(l10n, path)),
      targetIsAlbum: subject.livesInAlbum,
      // An album is created where an album may stand, and only for the images
      // that are to go into it, see issue #114.
      mayCreateAlbum: subject.livesInAlbum,
      newAlbumDate: takenOn ?? DateTime.now(),
      // Where the images belong, see issue #279; an album or a folder is
      // moved into a folder of folders, which nothing suggests.
      suggestNear: subject.livesInAlbum ? takenOn : null,
      sourceAlbum: subject.livesInAlbum ? source : null,
    ),
  );
  if (picked == null || !context.mounted) {
    return;
  }

  var target = picked.path;
  // The folder the picker was confirmed on, which is what the move changed
  // even when the album it created was filed into a year folder inside it.
  var pickedPath = picked.path;
  var newAlbum = picked.newAlbum;
  var created = false;
  // Said with the summary when the new album was filed somewhere else than it
  // was asked for, as the listing says it when it creates one.
  var placement = "";
  if (newAlbum != null) {
    // Nothing was created, so nothing is moved: the server's own reason.
    var creation = (await writeAction(
      context,
      () => client.createAlbum(target, newAlbum),
    ))
        ?.value;
    if (creation == null) {
      return;
    }
    created = true;
    // Where the album landed, never where it was asked for, see issue #48.
    target = splitPath(creation.path);
    placement = creation.message;
    if (!context.mounted) {
      return;
    }
  }

  // The server's own reason, as every other refused write shows it — and,
  // where the album was created a moment ago, the plain fact that it is there
  // and empty.
  var moved = await writeAction(
    context,
    () => client.move(
      source,
      targetPath(target),
      names,
      // Only an album made for this move is given a cover, see issue #277.
      created: created,
      cover: cover,
    ),
    refusal:
        created ? (error) => albumKept(l10n, target, reasonOf(error)) : null,
  );
  if (moved == null) {
    if (created) {
      // The tree changed although nothing moved: the new album is in it.
      delegate?.forgetTree(pickedPath);
      onMoved();
    }
    return;
  }
  var result = moved.value;

  // What moved is gone from where it was, and it is now in the target: both
  // sides have to be fetched again before the outcome is read out. The source
  // is the caller's own view, the target is the tree this function picked.
  delegate?.forgetTree(pickedPath);
  onMoved();

  var movedCount = result.outcomes.length - refusedOutcomes(result).length;
  var summary = movedCount == 0
      ? l10n.nothingMovedTo(targetLabel(l10n, target))
      : l10n.movedToTarget(
          subject.moved(l10n, movedCount),
          targetLabel(l10n, target),
        );
  if (placement.isNotEmpty) {
    // An album that was filed away says so, here as in the listing.
    summary = "$summary $placement";
  }

  if (!context.mounted) {
    // The view the move was started from is gone: the summary still reaches
    // the messenger, the dialog listing the refusals has nowhere to open.
    messenger.showSnackBar(
      SnackBar(content: Text(summary), duration: const Duration(seconds: 6)),
    );
    return;
  }
  await reportOutcomes(
    context: context,
    messenger: messenger,
    title: l10n.moveTitle,
    summary: summary,
    result: result,
  );
}

/// Asks for an album and adds [names] of the folder at [source] to it as
/// references, see issues #221 and #307.
///
/// Choose or create: the picker confirms on an album the caller may add
/// photos to — any album, a collection being one, never a saved search (the
/// inbox is no entry of a listing) — and offers "Create new album…" where the
/// caller may add to the folder shown; an album created there is made first
/// and the photographs are then added to the path the server answered for
/// it. Nothing is copied — the album stores references. The target is
/// forgotten by the router afterwards, so that it is fetched afresh when it
/// is opened; the source is unchanged. Refused while offline, like every
/// other write.
Future<void> collectWithPicker({
  required BuildContext context,
  required VAlbumClient client,
  required List<String> source,
  required List<String> names,
  required VAlbumRouterDelegate? delegate,
}) async {
  if (refuseWhileOffline(context)) {
    return;
  }
  var l10n = AppLocalizations.of(context)!;
  if (names.isEmpty) {
    _say(context, l10n.nothingToCollect);
    return;
  }
  var messenger = ScaffoldMessenger.of(context);
  var picked = await showFormDialog<PickedTarget>(
    context: context,
    builder: (context) => FolderPicker(
      key: const Key("collection-picker"),
      client: client,
      title: l10n.addAsReferenceTitle,
      initialPath:
          source.isEmpty ? const [] : source.sublist(0, source.length - 1),
      confirmLabel: (path) => l10n.addToCollectionConfirm(names.length),
      mayCreateAlbum: true,
      requireContribute: true,
    ),
  );
  if (picked == null || !context.mounted) {
    return;
  }
  var target = picked.path;
  var created = picked.newAlbum;
  if (created != null) {
    var creation = await writeAction(
      context,
      () => client.createAlbum(target, created),
    );
    if (creation == null) {
      return;
    }
    target = splitPath(creation.value.path);
    delegate?.forgetTree(picked.path);
    if (!context.mounted) {
      return;
    }
  }
  // Only an album made for these photographs is given a cover by them, see
  // issue #277.
  var collected = await writeAction(
    context,
    () => client.collect(
      source,
      targetPath(target),
      names,
      created: created != null,
    ),
  );
  if (collected == null) {
    return;
  }
  var result = collected.value;
  delegate?.forgetTree(target);
  var added = [
    for (var outcome in result.outcomes)
      if (outcome.newName.isNotEmpty && outcome.message.isEmpty) outcome,
  ];
  var summary = l10n.addedToCollection(added.length, targetLabel(l10n, target));
  if (!context.mounted) {
    messenger.showSnackBar(
      SnackBar(content: Text(summary), duration: const Duration(seconds: 6)),
    );
    return;
  }
  await reportOutcomes(
    context: context,
    messenger: messenger,
    title: l10n.addAsReferenceTitle,
    summary: summary,
    result: result,
  );
}

/// What deleting an album or a folder does, said before it is done (#109).
///
/// The app cannot know which of the two will happen: whether anything below
/// the folder is a photograph is a question about the disk, and the server is
/// the one looking at it. So both are said, in the order they are decided —
/// and the sentence that matters most is said last: nothing is deleted from
/// the disk.
String deleteExplanation(AppLocalizations l10n) => l10n.deleteExplanation;

/// Asks whether [what] is really to be deleted, and deletes it, see #109.
///
/// Refused while the app is offline, like every other write, and nothing is
/// sent while the question is open or when it is answered with "Cancel".
/// Afterwards [onDeleted] is called — what was deleted is no longer where it
/// was — and the server's own account of what it did is read out.
///
/// Answers whether the request went through, so that a caller standing *in*
/// what it deleted can leave: a refusal of the whole request (a share link, a
/// caller who may not change this folder, a server that cannot be reached) is
/// shown and answered `false`, and the view stays where it is.
///
/// [parent] is the folder the entries live in — the delete is posted to it,
/// as a move is posted to the folder it moves out of.
Future<bool> deleteWithConfirmation({
  required BuildContext context,
  required VAlbumClient client,
  required List<String> parent,
  required List<String> names,
  required String what,
  required VoidCallback onDeleted,
}) async {
  if (refuseWhileOffline(context)) {
    return false;
  }
  var l10n = AppLocalizations.of(context)!;
  var messenger = ScaffoldMessenger.of(context);
  // What other albums still show stays there, and the question says where
  // (issue #307).
  var question = keptQuestion(
      l10n, await keptOnDelete(context, client, parent, names), null);
  if (!context.mounted) {
    return false;
  }
  var confirmed = await showAppDialog<bool>(
    context: context,
    builder: (context) => AppAlertDialog(
      key: const Key("delete-dialog"),
      title: Text(l10n.deleteQuestion(what)),
      content: Text(question == null
          ? l10n.deleteExplanation
          : "${l10n.deleteExplanation}\n\n$question"),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: Text(l10n.cancel),
        ),
        ElevatedButton(
          key: const Key("delete-confirm"),
          onPressed: () => Navigator.of(context).pop(true),
          child: Text(l10n.delete),
        ),
      ],
    ),
  );
  if (confirmed != true || !context.mounted) {
    return false;
  }

  // The server's own reason, as every other refused write shows it.
  var deleted = await writeAction(
    context,
    () => client.delete(parent, names),
  );
  if (deleted == null) {
    return false;
  }
  var result = deleted.value;

  // What was deleted is gone from where it was: the view is fetched again
  // before the outcome is read out.
  onDeleted();

  // Verbatim, and every name of them: the server says which of the two things
  // it did — removed, or set aside as this name — and the app paraphrases
  // neither.
  var said = deleteOutcomes(l10n, result);
  messenger.showSnackBar(
    SnackBar(
      content: Text(said.isEmpty ? l10n.deletedWhat(what) : said.join(" ")),
      duration: const Duration(seconds: 8),
    ),
  );
  return true;
}

/// What the server said about a delete, verbatim, and where it kept photos
/// that other albums show (issue #307), in the app's words: a photo kept
/// that way is said once, by [keptReport], not by the server's sentence.
List<String> deleteOutcomes(AppLocalizations l10n, MoveResult result) {
  var report = keptReport(l10n, keptOf(result));
  return [
    for (var outcome in result.outcomes)
      if (outcome.message.isNotEmpty && !isKeptPhoto(outcome)) outcome.message,
    if (report != null) report,
  ];
}

/// The entries the server did not move: a [MoveOutcome] carrying a message is
/// one that stayed where it was, and says why.
List<MoveOutcome> refusedOutcomes(MoveResult result) => [
      for (var outcome in result.outcomes)
        if (outcome.message.isNotEmpty) outcome,
    ];

/// Reads out what the server did with the entries of a [MoveResult].
///
/// A snack bar with [summary] when everything went through, a dialog listing
/// the server's own reason for every entry that stayed otherwise — a refusal
/// is never missed, and never paraphrased. Shared by the move of issue #47 and
/// the "Apply rule" of issue #48, which differ only in their wording.
///
/// [messenger] is the messenger of the view that started the action, taken
/// before it was left; it defaults to the one of [context].
Future<void> reportOutcomes({
  required BuildContext context,
  required String title,
  required String summary,
  required MoveResult result,
  ScaffoldMessengerState? messenger,
}) async {
  var target = messenger ?? ScaffoldMessenger.of(context);
  var refused = refusedOutcomes(result);

  if (refused.isEmpty) {
    target.showSnackBar(
      SnackBar(content: Text(summary), duration: const Duration(seconds: 6)),
    );
    return;
  }

  if (!context.mounted) {
    return;
  }
  await showAppDialog<void>(
    context: context,
    builder: (context) => AppAlertDialog(
      key: const Key("move-outcome"),
      title: Text(title),
      content: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(summary),
            const SizedBox(height: 12),
            // The server's reason, verbatim: it knows why, the app does not.
            for (var outcome in refused)
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Text("'${outcome.name}': ${outcome.message}"),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(AppLocalizations.of(context)!.ok),
        ),
      ],
    ),
  );
}

/// What is said when the album of issue #114 was created but the move into it
/// was refused.
///
/// The album is on the server and it is empty; saying only why the move failed
/// would leave the user looking for a folder they were never told about.
String albumKept(AppLocalizations l10n, List<String> target, String reason) =>
    "$reason ${l10n.newAlbumCreatedEmpty(targetLabel(l10n, target))}";

void _say(BuildContext context, String message) =>
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), duration: const Duration(seconds: 6)),
    );

/// The earliest day anything of [parts] was taken on, `null` where nothing
/// says — a date of `0` is "unknown" and does not count.
///
/// The day a new album for them is proposed with (issue #114; today where
/// this is `null`, the day the listing's own dialog opens on), and the day the
/// album a move suggests lies nearest to (issue #279).
DateTime? earliestTaken(Iterable<AlbumPart> parts) {
  var earliest = 0;
  for (var part in parts) {
    for (var date in _datesOf(part)) {
      if (date != 0 && (earliest == 0 || date < earliest)) {
        earliest = date;
      }
    }
  }
  return earliest == 0 ? null : DateTime.fromMillisecondsSinceEpoch(earliest);
}

/// The dates an album part carries: one for an image, one per member for a
/// group — the whole group travels, so the whole group has a say.
Iterable<int> _datesOf(AlbumPart part) sync* {
  if (part is ImagePart) {
    yield part.date;
  } else if (part is ImageGroup) {
    for (var image in part.images) {
      yield image.date;
    }
  }
}

/// Why the picker refuses an album where a folder of folders is looked for:
/// it holds no folders.
String albumHoldsNoFolders(AppLocalizations l10n) => l10n.albumHoldsNoFolders;

/// What the picker was left with, see [FolderPicker].
///
/// Almost always simply the folder that was shown when the move was confirmed.
/// [newAlbum] is the one exception, issue #114: the album that is to be
/// created in [path] first, the images then going into the album — into the
/// path the *server* answers for it, not into [path].
@immutable
class PickedTarget {
  /// The folder the picker was confirmed on.
  final List<String> path;

  /// The album to create in [path] before moving, `null` for a plain move.
  final AlbumInfo? newAlbum;

  /// The title of the album the picker was confirmed on, empty where it has
  /// none or the folder is no album (issue #240 names the target by it).
  final String title;

  const PickedTarget(this.path, {this.newAlbum, this.title = ""});
}

/// The one line the picker names a folder with, see issue #113.
///
/// Composed from what the entry carries, never read off the folder name: the
/// effective date and the title, the name alone where there is no title, the
/// title alone where nothing says when the album happened. The folder name is
/// `date title` by the naming convention, so showing it beside the title said
/// the same thing twice.
String folderLine(FolderInfo folder) {
  var title = folder.title.isEmpty ? folder.name : folder.title;
  // Only an album has a date, see issue #133: the sort key of a folder of
  // folders is not a day anything happened on.
  if (!folderHasDate(folder)) {
    return title;
  }
  // The one composition of `yyyy-MM-dd title`, shared with the create-album
  // dialog, see [albumFolderName].
  return albumFolderName(
    DateTime.fromMillisecondsSinceEpoch(folder.effectiveDate),
    title,
  );
}

/// The icon the picker shows a folder of the given kind with.
IconData folderIcon(FolderKind? kind) => switch (kind) {
      FolderKind.search => Icons.saved_search,
      _ => Icons.folder,
    };

/// Where a match of the picker's search lies, see issue #261: the names of
/// the folders above it, as the picker's own path line spells a folder.
String matchLocation(AppLocalizations l10n, String path) {
  var segments = splitPath(path);
  return segments.length <= 1
      ? l10n.folderSearchTopLevel
      : l10n
          .folderSearchIn(segments.sublist(0, segments.length - 1).join(" / "));
}

/// The height of the picker's content below which its list no longer has
/// the room to scroll by itself: the search field, the path line, the way up
/// and two or three entries, see [FolderPicker] and issue #178.
const double _roomyPickerHeight = 260;

/// The width of the screen from which the picker's create button carries
/// its words beside the title; below, it is an icon, see [FolderPicker].
const double _wideCreateButton = 600;

/// Browses the caller's own tree and returns what the move goes into, `null`
/// when the dialog was left without picking anything.
///
/// Every level is fetched through the injected [client], so the picker shows
/// exactly what the server lets this caller see. Every level shown is a
/// folder of folders: a tap on a folder of folders opens it, a tap on a leaf
/// — an album, a saved search, which hold no folders — selects
/// it ([FolderPickerState._choose]), and the confirm then acts on the leaf
/// selected. `Enter` on the keyboard's highlight does what the tap does.
///
/// The kind decides what can be confirmed at all, see issue #113: with
/// [targetIsAlbum] only an album (images live in albums), without it only the
/// folder of folders shown (an album holds no folders). One rule decides for
/// every leaf
/// ([FolderPickerState._refusal]): a leaf the purpose cannot take is shown
/// disabled, its row saying why, instead of being declined by the server
/// afterwards or silently doing nothing.
///
/// The one thing the picker creates is the album of issue #114, and only where
/// [mayCreateAlbum] says the move is looking for one and the caller may add to
/// the folder shown: it is not created here either — the [PickedTarget] it
/// answers carries it, and the move creates it, see [moveWithPicker]. Its
/// button stands in the dialog's title, never scrolled away by a long level.
///
/// Above the tree stands the search field of issue #261, the one filter field
/// of every chooser (see `filter_list.dart`): what is typed is looked for in
/// the **whole space** — albums and folders by title, folder name and date —
/// by the server ([VAlbumClient.findFolders]), and the matches stand as one
/// flat list, each with the folder it lies in. Picking a folder of folders
/// opens it; picking a leaf shows the folder it lies in with the leaf chosen
/// as a tap on its row would choose it, by exactly the rules of browsing. An
/// empty field is the tree.
///
/// A move of images opens where they belong, see issue #279 and
/// [suggestNear]: at the level of the album suggested, that album *selected*
/// and scrolled to the middle — the confirm then acts on it, not on the folder
/// shown. Going anywhere else leaves the selection behind. Every level lists
/// its folders the oldest first ([pickerFolders]).
class FolderPicker extends StatefulWidget {
  final VAlbumClient client;

  /// The folder the picker opens at.
  final List<String> initialPath;

  /// Names the move in the confirm button, given the folder currently shown.
  final String Function(List<String> path) confirmLabel;

  /// Whether the target of this move is an album rather than a folder of
  /// folders, see issue #113.
  final bool targetIsAlbum;

  /// Whether creating the album to move into is offered, see issue #114.
  final bool mayCreateAlbum;

  /// The day such a new album is proposed with, `null` for none.
  final DateTime? newAlbumDate;

  /// The title of the dialog, "Move to…" where none is given.
  final String? title;

  /// Whether only an album the caller may add photos to can be confirmed
  /// (issue #240): the upload target of a device is chosen with this picker,
  /// and an album that refuses the upload is no target.
  ///
  /// A move needs no such limit here — the server refuses it with its own
  /// reason, entry by entry.
  final bool requireContribute;

  /// The day the images to move were taken on, see issue #279: the picker
  /// asks the server for the album nearest to it
  /// ([VAlbumClient.nearestAlbum]) and opens with it selected. Where the
  /// server suggests nothing or cannot be asked, the picker opens at
  /// [initialPath] as it always did — it is a suggestion, not a step.
  final DateTime? suggestNear;

  /// The album the images are moved out of, see issue #279: never suggested
  /// while another album is — moving there moves nothing — and the album
  /// selected where [suggestNear] is `null` (images that do not say when they
  /// were taken, such as scans, mostly stay near where they are). An inbox is
  /// no entry of a listing, so from there the picker opens unselected.
  final List<String>? sourceAlbum;

  const FolderPicker({
    super.key,
    required this.client,
    required this.initialPath,
    required this.confirmLabel,
    this.targetIsAlbum = true,
    this.mayCreateAlbum = false,
    this.newAlbumDate,
    this.title,
    this.requireContribute = false,
    this.suggestNear,
    this.sourceAlbum,
  });

  @override
  State<FolderPicker> createState() => FolderPickerState();
}

class FolderPickerState extends State<FolderPicker> {
  /// How long the typing must rest before the server is asked, see #261.
  static const Duration searchDelay = Duration(milliseconds: 300);

  /// The folder currently shown, relative to the root of the caller's space.
  late List<String> _path;

  /// The search field of issue #261.
  final TextEditingController _query = TextEditingController();

  /// The entry the keyboard stands on: a folder of the level shown, or a
  /// match of the search, see [PickHighlight].
  late final PickHighlight<Object> _highlight = PickHighlight<Object>(
    idOf: (entry) => switch (entry) {
      FolderMatch match => "match:${match.path}",
      FolderInfo folder => "folder:${folder.name}",
      _ => entry,
    },
  )..addListener(_highlightMoved);

  /// Waits for the typing to rest, see [searchDelay].
  Timer? _searchDelay;

  /// Counts the searches asked, so that an answer to text that has changed
  /// since is dropped.
  int _searchSerial = 0;

  /// Whether a search is under way: said on the screen as long as it takes.
  bool _searching = false;

  /// What the server answered the last search, `null` before the first.
  FolderMatches? _matches;

  /// The text [_matches] answers.
  String _matched = "";

  /// Why the last search failed, the server's own words; `null` while all is
  /// well.
  String? _searchError;

  /// The folders of [_path] in the order the listing shows them
  /// ([pickerFolders]), empty while it is loading and while it failed.
  List<FolderInfo> _folders = const [];

  /// The listing shown, `null` while nothing is shown: what the caller may do
  /// here is answered with it, see [Rights].
  ListingInfo? _listing;

  /// Why the current level could not be loaded, `null` while all is well.
  String? _error;

  bool _loading = true;

  /// The leaf of the level shown that the confirm acts on: the one tapped,
  /// or the one the picker opened with (issue #279); `null` where none is
  /// selected — then the confirm acts on the folder shown.
  FolderInfo? _selected;

  /// The name of the leaf to choose once the level shown has loaded.
  String? _toSelect;

  /// Why a leaf of the level shown was refused once it was asked about, by
  /// its name: an album the caller may not add photos to, see
  /// [FolderPicker.requireContribute]. What a leaf's kind refuses needs no
  /// asking, see [_refusal].
  Map<String, String> _refused = const {};

  /// The name of the leaf whose rights are being asked for, see [_choose].
  String? _checking;

  /// Counts the questions about a leaf, so that an answer for a level that
  /// is no longer shown is dropped.
  int _checkSerial = 0;

  /// Whether the picker was taken anywhere since it opened: a suggestion
  /// that arrives afterwards is no longer wanted.
  bool _moved = false;

  /// Scrolls the level shown, to bring [_selected] to the middle.
  final ScrollController _scroll = ScrollController();

  /// Marks the row of [_selected], to find it on the screen.
  final GlobalKey _selectedRow = GlobalKey();

  @override
  void initState() {
    super.initState();
    _path = List.of(widget.initialPath);
    _open();
  }

  /// Opens the picker where the move belongs, see issue #279 and
  /// [FolderPicker.suggestNear]; at [FolderPicker.initialPath] where nothing
  /// is suggested.
  Future<void> _open() async {
    var near = widget.suggestNear;
    var source = widget.sourceAlbum;
    List<String>? selection;
    if (near != null) {
      try {
        var found = await widget.client.nearestAlbum(
          near,
          source: source ?? const [],
        );
        if (found.folders.isNotEmpty) {
          selection = splitPath(found.folders.first.path);
        }
      } catch (error) {
        // A suggestion only: the picker opens as it always did. A log line
        // for the developer, never shown.
        debugPrint("move-suggestion($near):$error");
      }
    } else {
      selection = source;
    }
    if (!mounted || _moved) {
      return;
    }
    if (selection != null && selection.isNotEmpty) {
      _path = selection.sublist(0, selection.length - 1);
      _toSelect = selection.last;
    }
    await _load();
  }

  @override
  void dispose() {
    _scroll.dispose();
    _searchDelay?.cancel();
    _highlight.dispose();
    _query.dispose();
    super.dispose();
  }

  void _highlightMoved() => setState(() {});

  /// Whether the search field holds anything: then the matches are shown,
  /// otherwise the tree.
  bool get _searchShown => _query.text.trim().isNotEmpty;

  /// What the search field does on every change, see issue #261: the server
  /// is asked once the typing rests; an emptied field is the tree again.
  void _queryChanged(String text) {
    _searchDelay?.cancel();
    var asked = text.trim();
    if (asked.isEmpty) {
      setState(() {
        _searchSerial++;
        _searching = false;
        _matches = null;
        _searchError = null;
      });
      return;
    }
    setState(() {
      _searching = true;
      _searchError = null;
    });
    _searchDelay = Timer(searchDelay, () => _findFolders(asked));
  }

  Future<void> _findFolders(String text) async {
    var serial = ++_searchSerial;
    FolderMatches found;
    try {
      // The whole space, whatever level is shown.
      found = await widget.client.findFolders(const [], text);
    } catch (error) {
      if (mounted && serial == _searchSerial) {
        setState(() {
          _searching = false;
          _searchError = reasonOf(error);
        });
      }
      return;
    }
    if (!mounted || serial != _searchSerial) {
      return;
    }
    // Other entries than before: the highlight stands on none of them.
    _highlight.reset();
    setState(() {
      _searching = false;
      _matches = found;
      _matched = text;
    });
  }

  /// Shows a match in the tree again: a folder of folders opened, a leaf in
  /// the folder it lies in, chosen as a tap on its row chooses it.
  ///
  /// A match the server answered without its tile is opened like a folder;
  /// should it turn out to be a leaf, [_load] shows it where it lies.
  void _goTo(FolderMatch match) {
    _searchDelay?.cancel();
    _query.clear();
    _leaving();
    var segments = splitPath(match.path);
    var kind = match.folder?.kind;
    var leaf = kind != null && kind != FolderKind.folder;
    setState(() {
      _searchSerial++;
      _searching = false;
      _matches = null;
      _searchError = null;
      _path = leaf ? segments.sublist(0, segments.length - 1) : segments;
    });
    if (leaf) {
      _toSelect = segments.last;
    }
    _load();
  }

  /// What `Enter` does: what a tap on the highlighted row does.
  void _pick(Object? highlighted) {
    if (highlighted is FolderMatch) {
      _goTo(highlighted);
    } else if (highlighted is FolderInfo) {
      _tap(highlighted);
    }
  }

  /// What a tap on the row of [folder] does: a folder of folders is opened,
  /// a leaf is chosen.
  void _tap(FolderInfo folder) {
    if (folder.kind == FolderKind.folder) {
      _enter(folder.name);
    } else {
      _choose(folder);
    }
  }

  Future<void> _load() async {
    // Another level, other entries: the highlight stands on none of them.
    _highlight.reset();
    _checkSerial++;
    setState(() {
      _selected = null;
      _loading = true;
      _error = null;
      _folders = const [];
      _listing = null;
      _refused = const {};
      _checking = null;
    });
    Resource? resource;
    try {
      resource = await widget.client.loadResource(_path);
    } catch (error) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = error is VAlbumException ? error.message : "$error";
        });
      }
      return;
    }
    if (!mounted) {
      return;
    }
    var wanted = _toSelect;
    _toSelect = null;
    if (resource is AlbumInfo && _path.isNotEmpty) {
      // A leaf where a folder of folders was expected: a match answered
      // without its tile, or a folder whose kind changed since its row was
      // drawn. A level is always a folder of folders, so the leaf is shown
      // where it lies, chosen as a tap on its row would choose it.
      setState(() {
        _toSelect = _path.last;
        _path = _path.sublist(0, _path.length - 1);
      });
      await _load();
      return;
    }
    setState(() {
      _loading = false;
      if (resource is ListingInfo) {
        _listing = resource;
        // The oldest first at every level, see issue #279: the listing opens
        // on the latest year (issue #270), the picker reads through time.
        _folders = pickerFolders(resource.folders);
      } else {
        _error = AppLocalizations.of(context)!.folderCannotBeShown;
      }
    });
    for (var folder in _folders) {
      // Never opened: a leaf only is chosen, so nothing here goes in circles.
      if (folder.name == wanted && folder.kind != FolderKind.folder) {
        _choose(folder);
      }
    }
    if (_selected != null) {
      // Once the row is laid out.
      WidgetsBinding.instance.addPostFrameCallback((_) => _centre(0));
    }
  }

  /// Why the purpose of this picker cannot take the leaf [folder], `null`
  /// where it can — and for a folder of folders, which is opened, not taken.
  ///
  /// The one rule for every leaf, browsed to, suggested or found: an image
  /// goes into an album (issue #113) — a collection is an album (issue #307)
  /// —, an album or a folder into no leaf at all, and nothing into a saved
  /// search (issue #227). What only the server can answer — whether
  /// the caller may add photos to an album (issue #240) — is asked when the
  /// leaf is chosen, and remembered in [_refused].
  String? _refusal(FolderInfo folder) {
    if (folder.kind == FolderKind.folder) {
      return null;
    }
    var asked = _refused[folder.name];
    if (asked != null) {
      return asked;
    }
    var l10n = AppLocalizations.of(context)!;
    return switch (folder.kind) {
      FolderKind.search => l10n.searchTakesNoPhotos,
      _ => widget.targetIsAlbum ? null : albumHoldsNoFolders(l10n),
    };
  }

  /// Chooses the leaf [folder] as what the confirm acts on, where the
  /// purpose takes it ([_refusal]). Choosing it again leaves it chosen: a
  /// second tap — or the double click a desktop opens a folder with — must
  /// not undo the choice, and nothing would be gained by undoing it, since a
  /// folder of folders that takes no leaf takes none to be chosen either.
  ///
  /// Where only an album the caller may add photos to will do, the server is
  /// asked about the album first, and an album that refuses is marked so.
  void _choose(FolderInfo folder) {
    if (folder.name == _selected?.name || _refusal(folder) != null) {
      return;
    }
    if (widget.requireContribute) {
      _askContribute(folder);
      return;
    }
    setState(() => _selected = folder);
  }

  /// Asks whether the caller may add photos to the album [folder] of the
  /// level shown, and chooses it, or marks it refused. The rights the album
  /// is answered with decide, the caller's role where it was answered none,
  /// see [offeredRights].
  Future<void> _askContribute(FolderInfo folder) async {
    var serial = ++_checkSerial;
    setState(() {
      _selected = null;
      _checking = folder.name;
    });
    String? refusal;
    try {
      var album = await widget.client.loadResource([..._path, folder.name]);
      if (!mounted || serial != _checkSerial) {
        return;
      }
      var l10n = AppLocalizations.of(context)!;
      if (album is! AlbumInfo) {
        refusal = l10n.folderCannotBeShown;
      } else if (!rightsOf(context, album).mayContribute) {
        refusal = l10n.uploadTargetNoContribute;
      }
    } catch (error) {
      refusal = reasonOf(error);
    }
    if (!mounted || serial != _checkSerial) {
      return;
    }
    setState(() {
      _checking = null;
      if (refusal == null) {
        _selected = folder;
      } else {
        _refused = {..._refused, folder.name: refusal};
      }
    });
  }

  /// Brings the row of [_selected] to the middle of the list, see issue
  /// #279. The list lays out only the rows near what it shows, so a row far
  /// down is walked towards a screenful at a time until it is there.
  void _centre(int attempt) {
    if (!mounted || _selected == null || _searchShown) {
      return;
    }
    var row = _selectedRow.currentContext;
    if (row != null) {
      Scrollable.ensureVisible(row, alignment: 0.5);
      return;
    }
    if (!_scroll.hasClients || attempt >= 200) {
      return;
    }
    var position = _scroll.position;
    var next = (position.pixels + position.viewportDimension).clamp(
      position.minScrollExtent,
      position.maxScrollExtent,
    );
    if (next == position.pixels) {
      return;
    }
    _scroll.jumpTo(next);
    WidgetsBinding.instance.addPostFrameCallback((_) => _centre(attempt + 1));
  }

  /// What every way to another level does first: a selection, and one still
  /// to come, belong to the level the picker opened on.
  void _leaving() {
    _moved = true;
    _toSelect = null;
    _checkSerial++;
  }

  void _enter(String name) {
    _leaving();
    setState(() => _path = [..._path, name]);
    _load();
  }

  void _up() {
    _leaving();
    setState(() => _path = _path.sublist(0, _path.length - 1));
    _load();
  }

  /// The folder the confirm acts on: the leaf selected, else the folder
  /// shown.
  List<String> get _target {
    var selected = _selected;
    return selected == null ? _path : [..._path, selected.name];
  }

  /// Whether the folder shown says what it is at all.
  bool get _known => !_loading && _error == null;

  /// Whether the confirm acts on something of the kind this move goes into.
  ///
  /// Nothing is confirmed on a level that is still loading or that could not
  /// be read: what is unknown is not offered, see issue #113. A leaf is
  /// confirmed once it is chosen, which only a leaf the purpose takes can be
  /// ([_choose]); the folder of folders shown only where a folder of folders
  /// is looked for — an album or a folder moving.
  bool get _mayConfirm =>
      _known && (_selected != null || !widget.targetIsAlbum);

  /// Whether an album may be created in the folder shown, see issue #114.
  ///
  /// The rights the listing was answered with decide, falling back on the
  /// caller's role where the server answered none — exactly the rule the
  /// listing itself offers "Create album" by. Creating an album is
  /// `contribute` on the server.
  bool _mayCreateAlbumHere(BuildContext context) =>
      widget.mayCreateAlbum &&
      _known &&
      rightsOf(context, _listing).mayContribute;

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var searching = _searchShown;
    // What the keyboard walks: the entries the list below the field shows.
    _highlight.shown = searching
        ? [...?_matches?.folders]
        : _known
            ? _folders
            : const [];
    return PickKeys<Object>(
      highlight: _highlight,
      onEnter: _pick,
      child: AppAlertDialog(
        key: const Key("folder-picker"),
        // What is created stands beside the title, at every level it may be
        // created on: in the list it was scrolled away as soon as the picker
        // opened on an album far down a long level (issue #279), and with a
        // phone's keyboard up the content scrolls as a whole. On a phone an
        // icon, so that the title keeps its line.
        title: Row(
          children: [
            Expanded(child: Text(widget.title ?? l10n.moveToAction)),
            if (_mayCreateAlbumHere(context))
              _createButton(
                context,
                key: const Key("picker-create-album"),
                icon: Icons.create_new_folder,
                label: l10n.createNewAlbum,
                onPressed: _createAlbum,
              ),
          ],
        ),
        content: SizedBox(
          width: 400,
          // The tree as tall as before the search field of issue #261 stood
          // above it.
          height: 420,
          child: LayoutBuilder(builder: (context, constraints) {
            // With room, the list scrolls by itself below the field. Where a
            // phone's keyboard leaves the dialog too little for that, the
            // whole content scrolls and the list is laid out in full, as in
            // the person chooser (issue #178) — without building the field
            // anew, see [CrampedScroll].
            var roomy = constraints.maxHeight >= _roomyPickerHeight;
            var list = searching
                ? _results(context, scrolls: roomy)
                : _body(context, scrolls: roomy);
            var column = Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: roomy ? MainAxisSize.max : MainAxisSize.min,
              children: [
                FilterField(
                  fieldKey: const Key("picker-search"),
                  controller: _query,
                  label: l10n.folderSearchLabel,
                  highlight: _highlight,
                  onChanged: _queryChanged,
                  onSubmitted: (_) => _pick(_highlight.current),
                ),
                const SizedBox(height: 8),
                // The folder the confirm acts on, while searching too.
                Text(
                  _target.isEmpty ? l10n.pickerTopLevel : _target.join(" / "),
                  key: const Key("picker-path"),
                  style: Theme.of(context).textTheme.titleSmall,
                ),
                const Divider(),
                // Always there, a failed level included: the way back up
                // must not depend on what the current level answered.
                if (_path.isNotEmpty && !searching)
                  ListTile(
                    key: const Key("picker-up"),
                    leading: const Icon(Icons.arrow_upward),
                    title: Text(l10n.up),
                    onTap: _up,
                  ),
                if (roomy) Expanded(child: list) else list,
              ],
            );
            return CrampedScroll(
              roomy: roomy,
              maxHeight: constraints.maxHeight,
              child: column,
            );
          }),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(l10n.cancel),
          ),
          ElevatedButton(
            key: const Key("picker-confirm"),
            // A leaf of the wrong kind cannot be chosen; its row says why,
            // see issue #113.
            onPressed: _mayConfirm
                ? () => Navigator.of(context).pop(
                      PickedTarget(_target, title: _selected?.title ?? ""),
                    )
                : null,
            child: Text(widget.confirmLabel(_target)),
          ),
        ],
      ),
    );
  }

  /// The button beside the title creating what this picker may create: with
  /// its [label] where the screen has the room, an icon carrying it as its
  /// tooltip on a phone.
  Widget _createButton(
    BuildContext context, {
    required Key key,
    required IconData icon,
    required String label,
    required VoidCallback onPressed,
  }) =>
      MediaQuery.sizeOf(context).width >= _wideCreateButton
          ? TextButton.icon(
              key: key,
              icon: Icon(icon),
              label: Text(label),
              onPressed: onPressed,
            )
          : IconButton(
              key: key,
              icon: Icon(icon),
              tooltip: label,
              onPressed: onPressed,
            );

  /// The matches of the search field, see issue #261: each by the line the
  /// tree names it with and the folder it lies in, the best first. A search
  /// under way says so as long as it takes, a failed one says why.
  Widget _results(BuildContext context, {required bool scrolls}) {
    var l10n = AppLocalizations.of(context)!;
    var matches = _matches;
    var error = _searchError;
    return ListView(
      shrinkWrap: true,
      physics: scrolls ? null : const NeverScrollableScrollPhysics(),
      children: [
        if (_searching)
          ListTile(
            key: const Key("picker-searching"),
            leading: const SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            title: Text(l10n.folderSearchSearching),
          ),
        if (error != null)
          ListTile(
            key: const Key("picker-search-error"),
            leading: const Icon(Icons.error_outline),
            title: Text(l10n.folderSearchFailed(error)),
          ),
        if (!_searching &&
            error == null &&
            matches != null &&
            matches.folders.isEmpty)
          ListTile(
            key: const Key("picker-search-nothing"),
            leading: const Icon(Icons.search_off),
            title: Text(l10n.folderSearchNothing(_matched)),
          ),
        for (var match in matches?.folders ?? const <FolderMatch>[])
          _highlight.row(
            match,
            ListTile(
              key: Key("picker-match-${match.path}"),
              selected: _highlight.isHighlighted(match),
              selectedTileColor: PickHighlight.color(context),
              leading: Icon(folderIcon(match.folder?.kind)),
              title: Text(
                match.folder == null
                    ? splitPath(match.path).last
                    : folderLine(match.folder!),
              ),
              subtitle: Text(
                matchLocation(l10n, match.path),
                key: Key("picker-match-in-${match.path}"),
                style: Theme.of(context).textTheme.bodySmall,
              ),
              onTap: () => _goTo(match),
            ),
          ),
        if (matches != null && matches.more)
          Padding(
            padding: const EdgeInsets.all(8),
            child: Text(
              l10n.folderSearchMore(matches.folders.length),
              key: const Key("picker-search-more"),
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
      ],
    );
  }

  Widget _body(BuildContext context, {required bool scrolls}) {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    var error = _error;
    if (error != null) {
      // The server's own reason, inside the dialog: the picker stays open, so
      // the way back up is still there.
      var text = Text(error, key: const Key("picker-error"));
      return scrolls ? SingleChildScrollView(child: text) : text;
    }
    return ListView(
      controller: _scroll,
      shrinkWrap: true,
      physics: scrolls ? null : const NeverScrollableScrollPhysics(),
      children: [
        for (var folder in _folders)
          _highlight.row(folder, _folderRow(context, folder)),
      ],
    );
  }

  /// The row of [folder] of the level shown; the leaf the confirm acts on
  /// ([_selected]) stands out and is marked, a leaf the purpose cannot take
  /// is disabled and says why ([_refusal]).
  Widget _folderRow(BuildContext context, FolderInfo folder) {
    var selected = folder.name == _selected?.name;
    var refusal = _refusal(folder);
    var tile = ListTile(
      key: Key("picker-folder-${folder.name}"),
      enabled: refusal == null,
      selected: selected || _highlight.isHighlighted(folder),
      selectedTileColor: PickHighlight.color(context),
      leading: Icon(folderIcon(folder.kind)),
      title: Text(folderLine(folder)),
      subtitle: refusal == null
          ? null
          : Text(refusal, key: Key("picker-refused-${folder.name}")),
      trailing: selected
          ? const Icon(Icons.check, key: Key("picker-selected"))
          : folder.name == _checking
              ? const SizedBox(
                  key: Key("picker-checking"),
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : null,
      onTap: () => _tap(folder),
    );
    return selected ? KeyedSubtree(key: _selectedRow, child: tile) : tile;
  }

  /// Asks what the new album is called and leaves the picker with it.
  ///
  /// Nothing is created here: the album travels on the [PickedTarget], and the
  /// move creates it in the folder shown and then moves into the path the
  /// server answers, see [moveWithPicker] and issue #114.
  ///
  /// What stands in the search field proposes the album: the album looked for
  /// and not found is the one to create. A text that begins with a date
  /// (`2025-11-26 Trip`) proposes that date and the rest as the title; any
  /// other proposes itself as the title beside [FolderPicker.newAlbumDate].
  Future<void> _createAlbum() async {
    var typed = _query.text.trim();
    var proposed = splitAlbumName(typed);
    var album = await showFormDialog<AlbumInfo>(
      context: context,
      builder: (context) => CreateAlbumDialog(
        initialDate: proposed.date ?? widget.newAlbumDate,
        initialTitle: typed.isEmpty ? null : proposed.title,
      ),
    );
    if (album == null || !mounted) {
      return;
    }
    Navigator.of(context).pop(PickedTarget(_path, newAlbum: album));
  }
}
