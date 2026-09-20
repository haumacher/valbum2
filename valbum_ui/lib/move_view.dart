/// Moving images, albums and folders into another folder, see issue #47.
///
/// The user picks a target folder in [FolderPicker], the move is posted to the
/// source folder (`?action=move`, see [VAlbumClient.move]), and what the
/// server answers is shown: every entry that did not move says why.
library;

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'caller.dart';
import 'client.dart';
import 'listing_view.dart';
import 'offline.dart';
import 'resource.dart';
import 'rights.dart';

/// What a move acts on, as the button and the message name it.
///
/// The move of a multi-selection counts images, the move of a single tile of a
/// listing names it: both are one sentence — "Move 3 images to '2021'", "Move
/// '2020 Trip' to '2021'" — so the sentence is built from this.
abstract class MoveSubject {
  /// Names everything the move was asked for, e.g. `3 images`.
  String get asked;

  /// Names the [count] entries that actually moved.
  String moved(int count);

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
  String get asked => moved(count);

  @override
  String moved(int count) => count == 1 ? "1 image" : "$count images";

  @override
  bool get livesInAlbum => true;
}

/// One named entry of a folder — an album, a folder, a file.
class EntrySubject implements MoveSubject {
  final String name;

  const EntrySubject(this.name);

  @override
  String get asked => "'$name'";

  @override
  String moved(int count) => "'$name'";

  @override
  bool get livesInAlbum => false;
}

/// How the target folder of a move is named on the screen.
///
/// The wire target is a path relative to the root of the caller's space, the
/// empty string being the root itself — which has no name, so it is called
/// what the app calls it everywhere else.
String targetLabel(List<String> target) =>
    target.isEmpty ? "the top level" : "'${target.join("/")}'";

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
/// The picker offers only targets of the kind [MoveSubject.livesInAlbum]
/// names, see issue #113, and — for images — the album that does not exist
/// yet: [albumDate] is the day such an album is proposed with, the earliest
/// day the selection was taken on, see issue #114. The album is created first
/// and the images are then moved into the path the server answered for it,
/// which is not necessarily the path it was asked for: a placement rule of
/// the folder above files it into its year folder, see issue #48.
Future<void> moveWithPicker({
  required BuildContext context,
  required VAlbumClient client,
  required List<String> source,
  required List<String> names,
  required MoveSubject subject,
  required VoidCallback onMoved,
  DateTime? albumDate,
}) async {
  if (refuseWhileOffline(context)) {
    return;
  }
  if (names.isEmpty) {
    _say(context, "Nothing to move.");
    return;
  }

  var messenger = ScaffoldMessenger.of(context);
  var picked = await showDialog<PickedTarget>(
    context: context,
    builder: (context) => FolderPicker(
      client: client,
      initialPath:
          source.isEmpty ? const [] : source.sublist(0, source.length - 1),
      confirmLabel: (path) => "Move ${subject.asked} to ${targetLabel(path)}",
      targetIsAlbum: subject.livesInAlbum,
      // An album is created where an album may stand, and only for the images
      // that are to go into it, see issue #114.
      mayCreateAlbum: subject.livesInAlbum,
      newAlbumDate: albumDate,
    ),
  );
  if (picked == null || !context.mounted) {
    return;
  }

  var target = picked.path;
  var newAlbum = picked.newAlbum;
  var created = false;
  // Said with the summary when the new album was filed somewhere else than it
  // was asked for, as the listing says it when it creates one.
  var placement = "";
  if (newAlbum != null) {
    CreateResult creation;
    try {
      creation = await client.createAlbum(target, newAlbum);
    } catch (error) {
      // Nothing was created, so nothing is moved: the server's own reason.
      showRefusal(messenger, error);
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

  MoveResult result;
  try {
    result = await client.move(source, targetPath(target), names);
  } catch (error) {
    // The server's own reason, as every other refused write shows it — and,
    // where the album was created a moment ago, the plain fact that it is
    // there and empty.
    showRefusal(
      messenger,
      created ? albumKept(target, reasonOf(error)) : error,
    );
    if (created) {
      // The tree changed although nothing moved: the new album is in it.
      onMoved();
    }
    return;
  }

  // What moved is gone from where it was: the view has to be fetched again
  // before the outcome is read out, so that the album on the screen is the one
  // the server now holds.
  onMoved();

  var movedCount = result.outcomes.length - refusedOutcomes(result).length;
  var summary = movedCount == 0
      ? "Nothing moved to ${targetLabel(target)}."
      : "Moved ${subject.moved(movedCount)} to ${targetLabel(target)}.";
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
    title: "Move",
    summary: summary,
    result: result,
  );
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
  await showDialog<void>(
    context: context,
    builder: (context) => AlertDialog(
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
          child: const Text("OK"),
        ),
      ],
    ),
  );
}

/// Shows the server's own reason for a refused write, as every refused write
/// is shown.
void showRefusal(ScaffoldMessengerState messenger, Object error) =>
    messenger.showSnackBar(
      SnackBar(
        content: Text(reasonOf(error)),
        backgroundColor: Colors.red.shade700,
        duration: const Duration(seconds: 8),
      ),
    );

/// The reason a failed request gives, the server's own words where it spoke.
String reasonOf(Object error) =>
    error is VAlbumException ? error.message : "$error";

/// What is said when the album of issue #114 was created but the move into it
/// was refused.
///
/// The album is on the server and it is empty; saying only why the move failed
/// would leave the user looking for a folder they were never told about.
String albumKept(List<String> target, String reason) =>
    "$reason The new album ${targetLabel(target)} was created and is empty.";

void _say(BuildContext context, String message) =>
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), duration: const Duration(seconds: 6)),
    );

/// The day a new album for [parts] is proposed with, see issue #114.
///
/// The earliest day anything of the selection was taken on — a date of `0` is
/// "unknown" and does not count — and today where nothing says when anything
/// happened, which is the day the listing's own dialog opens on.
DateTime newAlbumDay(Iterable<AlbumPart> parts) {
  var earliest = 0;
  for (var part in parts) {
    for (var date in _datesOf(part)) {
      if (date != 0 && (earliest == 0 || date < earliest)) {
        earliest = date;
      }
    }
  }
  return earliest == 0
      ? DateTime.now()
      : DateTime.fromMillisecondsSinceEpoch(earliest);
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

/// The line that says why an image cannot be left in a folder of folders.
const String imagesLiveInAlbums = "Images live in albums — open one.";

/// The line an album shows in the picker, which holds no folders.
const String albumHoldsNoFolders = "An album holds no folders.";

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

  const PickedTarget(this.path, {this.newAlbum});
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
  if (folder.effectiveDate == 0) {
    return title;
  }
  var date = DateFormat("yyyy-MM-dd")
      .format(DateTime.fromMillisecondsSinceEpoch(folder.effectiveDate));
  return "$date $title";
}

/// Browses the caller's own tree and returns what the move goes into, `null`
/// when the dialog was left without picking anything.
///
/// Every level is fetched through the injected [client], so the picker shows
/// exactly what the server lets this caller see. An album is a leaf — there is
/// nothing to descend into.
///
/// The kind of the folder shown decides whether it can be confirmed at all,
/// see issue #113: with [targetIsAlbum] only an album can be picked (images
/// live in albums), without it only a folder of folders (an album holds no
/// folders). What cannot be picked says why instead of being declined by the
/// server afterwards.
///
/// The one thing the picker creates is the album of issue #114, and only where
/// [mayCreateAlbum] says the move is looking for one and the caller may add to
/// the folder shown: it is not created here either — the [PickedTarget] it
/// answers carries it, and the move creates it, see [moveWithPicker].
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

  const FolderPicker({
    super.key,
    required this.client,
    required this.initialPath,
    required this.confirmLabel,
    this.targetIsAlbum = true,
    this.mayCreateAlbum = false,
    this.newAlbumDate,
  });

  @override
  State<FolderPicker> createState() => FolderPickerState();
}

class FolderPickerState extends State<FolderPicker> {
  /// The folder currently shown, relative to the root of the caller's space.
  late List<String> _path;

  /// The folders of [_path], empty while it is loading, while it failed, and
  /// for an album (which has none).
  List<FolderInfo> _folders = const [];

  /// The listing shown, `null` for an album and while nothing is shown: what
  /// the caller may do here is answered with it, see [Rights].
  ListingInfo? _listing;

  /// Why the current level could not be loaded, `null` while all is well.
  String? _error;

  bool _loading = true;

  /// Whether the folder shown is an album — a leaf of the tree.
  bool _leaf = false;

  @override
  void initState() {
    super.initState();
    _path = List.of(widget.initialPath);
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
      _folders = const [];
      _listing = null;
      _leaf = false;
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
    setState(() {
      _loading = false;
      if (resource is ListingInfo) {
        _listing = resource;
        _folders = resource.folders;
      } else if (resource is AlbumInfo) {
        _leaf = true;
      } else {
        _error = "This folder cannot be shown.";
      }
    });
  }

  void _enter(String name) {
    setState(() => _path = [..._path, name]);
    _load();
  }

  void _up() {
    setState(() => _path = _path.sublist(0, _path.length - 1));
    _load();
  }

  /// Whether the folder shown says what it is at all.
  bool get _known => !_loading && _error == null;

  /// Whether the folder shown is of the kind this move goes into.
  ///
  /// Nothing is confirmed on a level that is still loading or that could not
  /// be read: what is unknown is not offered, see issue #113.
  bool get _mayConfirm => _known && _leaf == widget.targetIsAlbum;

  /// Whether an album may be created in the folder shown, see issue #114.
  ///
  /// The rights the listing was answered with decide, falling back on the
  /// caller's role where the server answered none — exactly the rule the
  /// listing itself offers "Create album" by. Creating an album is
  /// `contribute` on the server.
  bool _mayCreateAlbumHere(BuildContext context) =>
      widget.mayCreateAlbum &&
      _known &&
      !_leaf &&
      offeredRights(
        Rights.of(_listing),
        CallerInfo.permissionOf(context),
      ).mayContribute;

  @override
  Widget build(BuildContext context) => AlertDialog(
        key: const Key("folder-picker"),
        title: const Text("Move to…"),
        content: SizedBox(
          width: 400,
          height: 360,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                _path.isEmpty ? "Top level" : _path.join(" / "),
                key: const Key("picker-path"),
                style: Theme.of(context).textTheme.titleSmall,
              ),
              const Divider(),
              // Always there, a failed level included: the way back up must
              // not depend on what the current level answered.
              if (_path.isNotEmpty)
                ListTile(
                  key: const Key("picker-up"),
                  leading: const Icon(Icons.arrow_upward),
                  title: const Text("Up"),
                  onTap: _up,
                ),
              Expanded(child: _body(context)),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text("Cancel"),
          ),
          ElevatedButton(
            key: const Key("picker-confirm"),
            // A folder of the wrong kind cannot be picked; the line in the
            // list says why, see issue #113.
            onPressed: _mayConfirm
                ? () => Navigator.of(context).pop(PickedTarget(_path))
                : null,
            child: Text(widget.confirmLabel(_path)),
          ),
        ],
      );

  Widget _body(BuildContext context) {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    var error = _error;
    if (error != null) {
      // The server's own reason, inside the dialog: the picker stays open, so
      // the way back up is still there.
      return SingleChildScrollView(
        child: Text(error, key: const Key("picker-error")),
      );
    }
    return ListView(
      shrinkWrap: true,
      children: [
        if (_leaf)
          const ListTile(
            key: Key("picker-leaf"),
            leading: Icon(Icons.photo_album),
            title: Text(albumHoldsNoFolders),
          ),
        // Why this folder cannot be confirmed: an image belongs in an album,
        // and this is a folder of folders, see issue #113.
        if (!_leaf && widget.targetIsAlbum)
          const ListTile(
            key: Key("picker-needs-album"),
            leading: Icon(Icons.photo_library_outlined),
            title: Text(imagesLiveInAlbums),
          ),
        if (_mayCreateAlbumHere(context))
          ListTile(
            key: const Key("picker-create-album"),
            leading: const Icon(Icons.create_new_folder),
            title: const Text("Create new album…"),
            onTap: _createAlbum,
          ),
        for (var folder in _folders)
          ListTile(
            key: Key("picker-folder-${folder.name}"),
            leading: const Icon(Icons.folder),
            title: Text(folderLine(folder)),
            onTap: () => _enter(folder.name),
          ),
      ],
    );
  }

  /// Asks what the new album is called and leaves the picker with it.
  ///
  /// Nothing is created here: the album travels on the [PickedTarget], and the
  /// move creates it in the folder shown and then moves into the path the
  /// server answers, see [moveWithPicker] and issue #114.
  Future<void> _createAlbum() async {
    var album = await showDialog<AlbumInfo>(
      context: context,
      builder: (context) => CreateAlbumDialog(initialDate: widget.newAlbumDate),
    );
    if (album == null || !mounted) {
      return;
    }
    Navigator.of(context).pop(PickedTarget(_path, newAlbum: album));
  }
}
