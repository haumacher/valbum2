/// Moving images, albums and folders into another folder, see issue #47.
///
/// The user picks a target folder in [FolderPicker], the move is posted to the
/// source folder (`?action=move`, see [VAlbumClient.move]), and what the
/// server answers is shown: every entry that did not move says why.
library;

import 'package:flutter/material.dart';

import 'client.dart';
import 'offline.dart';
import 'resource.dart';

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
}

/// A number of images (or videos) of an album, see [MoveSubject].
class ImageSubject implements MoveSubject {
  final int count;

  const ImageSubject(this.count);

  @override
  String get asked => moved(count);

  @override
  String moved(int count) => count == 1 ? "1 image" : "$count images";
}

/// One named entry of a folder — an album, a folder, a file.
class EntrySubject implements MoveSubject {
  final String name;

  const EntrySubject(this.name);

  @override
  String get asked => "'$name'";

  @override
  String moved(int count) => "'$name'";
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
Future<void> moveWithPicker({
  required BuildContext context,
  required VAlbumClient client,
  required List<String> source,
  required List<String> names,
  required MoveSubject subject,
  required VoidCallback onMoved,
}) async {
  if (refuseWhileOffline(context)) {
    return;
  }
  if (names.isEmpty) {
    _say(context, "Nothing to move.");
    return;
  }

  var messenger = ScaffoldMessenger.of(context);
  var target = await showDialog<List<String>>(
    context: context,
    builder: (context) => FolderPicker(
      client: client,
      initialPath:
          source.isEmpty ? const [] : source.sublist(0, source.length - 1),
      confirmLabel: (path) => "Move ${subject.asked} to ${targetLabel(path)}",
    ),
  );
  if (target == null || !context.mounted) {
    return;
  }

  MoveResult result;
  try {
    result = await client.move(source, targetPath(target), names);
  } catch (error) {
    // The server's own reason, as every other refused write shows it.
    messenger.showSnackBar(
      SnackBar(
        content: Text(error is VAlbumException ? error.message : "$error"),
        backgroundColor: Colors.red.shade700,
        duration: const Duration(seconds: 8),
      ),
    );
    return;
  }

  // What moved is gone from where it was: the view has to be fetched again
  // before the outcome is read out, so that the album on the screen is the one
  // the server now holds.
  onMoved();

  var refused = [
    for (var outcome in result.outcomes)
      if (outcome.message.isNotEmpty) outcome,
  ];
  var movedCount = result.outcomes.length - refused.length;
  var summary = movedCount == 0
      ? "Nothing moved to ${targetLabel(target)}."
      : "Moved ${subject.moved(movedCount)} to ${targetLabel(target)}.";

  if (refused.isEmpty) {
    messenger.showSnackBar(
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
      title: const Text("Move"),
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

void _say(BuildContext context, String message) =>
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), duration: const Duration(seconds: 6)),
    );

/// Browses the caller's own tree and returns the folder path picked, `null`
/// when the dialog was left without picking one.
///
/// It creates nothing: the target of a move is a folder that is already there.
/// Every level is fetched through the injected [client], so the picker shows
/// exactly what the server lets this caller see. An album is a leaf — it can
/// be picked (images move into albums) but there is nothing to descend into.
class FolderPicker extends StatefulWidget {
  final VAlbumClient client;

  /// The folder the picker opens at.
  final List<String> initialPath;

  /// Names the move in the confirm button, given the folder currently shown.
  final String Function(List<String> path) confirmLabel;

  const FolderPicker({
    super.key,
    required this.client,
    required this.initialPath,
    required this.confirmLabel,
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
              Expanded(child: _body()),
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
            onPressed: () => Navigator.of(context).pop(_path),
            child: Text(widget.confirmLabel(_path)),
          ),
        ],
      );

  Widget _body() {
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
            title: Text("An album holds no folders."),
          ),
        for (var folder in _folders)
          ListTile(
            key: Key("picker-folder-${folder.name}"),
            leading: const Icon(Icons.folder),
            title: Text(folder.title.isEmpty ? folder.name : folder.title),
            subtitle: folder.title == folder.name ? null : Text(folder.name),
            onTap: () => _enter(folder.name),
          ),
      ],
    );
  }
}
