/// The user's side of camera-roll sync (issue #30): the "Camera roll" section
/// of the server settings, the dialog choosing the inbox album, and the small
/// app-bar indicator saying that a sync is running.
///
/// The engine itself is in `camera_roll.dart`; nothing here decides anything,
/// it only shows what [CameraRollSync] is doing and hands the user's decisions
/// to it.
library;

import 'package:flutter/material.dart';

import 'camera_roll.dart';
import 'client.dart';
import 'resource.dart';
import 'settings.dart';

/// The key of the switch enabling the sync, so that a test can address it.
const Key cameraRollSwitchKey = Key("cameraRoll.enabled");

/// The key of the "Choose..." button opening the inbox picker.
const Key cameraRollChooseKey = Key("cameraRoll.choose");

/// The key of the "Sync now" button.
const Key cameraRollSyncNowKey = Key("cameraRoll.syncNow");

/// The key of the "Stop" button shown while a run transfers.
const Key cameraRollStopKey = Key("cameraRoll.stop");

/// The key of the app-bar indicator shown while a sync runs.
const Key cameraRollIndicatorKey = Key("cameraRoll.indicator");

/// Makes the [CameraRollSync] available to the widget tree.
class CameraRollScope extends InheritedNotifier<CameraRollSync> {
  const CameraRollScope({
    super.key,
    required CameraRollSync sync,
    required super.child,
  }) : super(notifier: sync);

  /// The sync engine of the enclosing app, `null` outside one (a view pumped
  /// on its own in a test).
  static CameraRollSync? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<CameraRollScope>()?.notifier;
}

/// The breadcrumb of an inbox path, or a plain word where there is none.
String inboxLabel(List<String> path) =>
    path.isEmpty ? "No album chosen yet" : path.join(" > ");

/// The "Camera roll" section of the server settings.
///
/// Shows nothing at all where no sync engine is in scope, exactly as the cache
/// section does: a settings screen pumped on its own in a test has no app
/// around it.
class CameraRollSection extends StatefulWidget {
  const CameraRollSection({super.key});

  @override
  State<CameraRollSection> createState() => _CameraRollSectionState();
}

class _CameraRollSectionState extends State<CameraRollSection> {
  /// The reason the last request was refused, shown until the next one.
  String? refusal;

  @override
  Widget build(BuildContext context) {
    var sync = CameraRollScope.maybeOf(context);
    if (sync == null) {
      return const SizedBox.shrink();
    }
    var config = sync.config;
    var status = sync.status;
    var available = sync.library.available;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 24),
        const Divider(),
        const SizedBox(height: 8),
        Text("Camera roll", style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        const Text(
          "New photos taken on this device are uploaded into an album of the "
          "library. Nothing is uploaded twice: the server is asked for the "
          "content of every photo before it is transferred.",
        ),
        const SizedBox(height: 8),
        SwitchListTile(
          key: cameraRollSwitchKey,
          contentPadding: EdgeInsets.zero,
          title: const Text("Upload new photos"),
          subtitle: available
              ? null
              : Text(sync.library.accessProblem ??
                  "No photo library on this platform"),
          value: config.enabled,
          onChanged: available ? _toggle : null,
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            const Icon(Icons.inbox, size: 20),
            const SizedBox(width: 8),
            Expanded(child: Text(inboxLabel(config.inbox))),
            const SizedBox(width: 8),
            OutlinedButton.icon(
              key: cameraRollChooseKey,
              onPressed: () => _chooseInbox(sync),
              icon: const Icon(Icons.folder_open),
              label: const Text("Choose..."),
            ),
          ],
        ),
        const SizedBox(height: 16),
        Text(status.line),
        if (status.running)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: LinearProgressIndicator(value: status.progress),
          ),
        const SizedBox(height: 16),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            FilledButton.icon(
              key: cameraRollSyncNowKey,
              onPressed:
                  status.running || !available ? null : () => _syncNow(sync),
              icon: const Icon(Icons.sync),
              label: const Text("Sync now"),
            ),
            if (status.running)
              OutlinedButton.icon(
                key: cameraRollStopKey,
                onPressed: sync.stop,
                icon: const Icon(Icons.stop),
                label: const Text("Stop"),
              ),
          ],
        ),
        if (refusal != null)
          Padding(
            padding: const EdgeInsets.only(top: 16),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(Icons.error, color: Colors.red),
                const SizedBox(width: 8),
                Expanded(child: Text(refusal!)),
              ],
            ),
          ),
      ],
    );
  }

  Future<void> _toggle(bool value) async {
    var sync = CameraRollScope.maybeOf(context);
    if (sync == null) {
      return;
    }
    var problem = await sync.setEnabled(value);
    if (!mounted) {
      return;
    }
    setState(() => refusal = problem);
  }

  Future<void> _syncNow(CameraRollSync sync) async {
    setState(() => refusal = null);
    await sync.syncNow();
  }

  /// Opens the picker and stores what the user chose.
  Future<void> _chooseInbox(CameraRollSync sync) async {
    var client = sync.clientOf();
    if (client == null) {
      setState(() => refusal = "Save the server URL first, then choose an "
          "album on it.");
      return;
    }
    var chosen = await showDialog<List<String>>(
      context: context,
      builder: (_) => InboxPickerDialog(client: client),
    );
    if (chosen == null || !mounted) {
      return;
    }
    await sync.chooseInbox(chosen);
    if (!mounted) {
      return;
    }
    setState(() => refusal = null);
  }
}

/// Browses the server's folders and answers the album that was chosen.
///
/// Pops the album path (a list of folder names), or `null` when the user
/// leaves without choosing. A folder that does not exist yet is created
/// through the same call the "Create album" of the listing view uses, so an
/// inbox is one dialog away even on a fresh library.
class InboxPickerDialog extends StatefulWidget {
  final VAlbumClient client;

  /// The folder the picker opens in.
  final List<String> initialPath;

  const InboxPickerDialog({
    super.key,
    required this.client,
    this.initialPath = const [],
  });

  @override
  State<InboxPickerDialog> createState() => _InboxPickerDialogState();
}

class _InboxPickerDialogState extends State<InboxPickerDialog> {
  late List<String> path = [...widget.initialPath];
  late Future<Resource?> resource = _load();

  /// What went wrong while creating a folder, if anything.
  String? problem;

  Future<Resource?> _load() => widget.client.loadResource(path);

  void _goTo(List<String> target) => setState(() {
        path = target;
        problem = null;
        resource = _load();
      });

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: const Text("Inbox album"),
        content: SizedBox(
          width: 420,
          height: 420,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _breadcrumbs(),
              const Divider(),
              Expanded(
                child: FutureBuilder<Resource?>(
                  future: resource,
                  builder: (context, snapshot) {
                    if (snapshot.hasError) {
                      return Center(
                        child: Text("Cannot list: ${snapshot.error}"),
                      );
                    }
                    if (!snapshot.hasData) {
                      return const Center(child: CircularProgressIndicator());
                    }
                    return _contents(snapshot.data);
                  },
                ),
              ),
              if (problem != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(
                    problem!,
                    style: const TextStyle(color: Colors.red),
                  ),
                ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text("Cancel"),
          ),
          TextButton.icon(
            onPressed: _createAlbum,
            icon: const Icon(Icons.create_new_folder),
            label: const Text("New album..."),
          ),
          FilledButton(
            onPressed: path.isEmpty
                ? null
                : () => Navigator.of(context).pop([...path]),
            child: const Text("Use this album"),
          ),
        ],
      );

  /// The path the picker stands in, every step of it a way back.
  Widget _breadcrumbs() => Wrap(
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          TextButton(
            onPressed: () => _goTo(const []),
            child: const Text("Library"),
          ),
          for (var index = 0; index < path.length; index++) ...[
            const Text(">"),
            TextButton(
              onPressed: () => _goTo(path.sublist(0, index + 1)),
              child: Text(path[index]),
            ),
          ],
        ],
      );

  Widget _contents(Resource? resource) => switch (resource) {
        ListingInfo(folders: var folders) when folders.isNotEmpty => ListView(
            children: [
              for (var folder in folders)
                ListTile(
                  leading: const Icon(Icons.folder),
                  title: Text(
                    folder.title.isEmpty ? folder.name : folder.title,
                  ),
                  subtitle: Text(folder.name),
                  onTap: () => _goTo([...path, folder.name]),
                ),
            ],
          ),
        ListingInfo() => const Center(
            child: Text("No folders here yet - create one below."),
          ),
        AlbumInfo(title: var title) => Center(
            child: Text(
              "'$title' is an album. New photos land here.",
              textAlign: TextAlign.center,
            ),
          ),
        ErrorInfo(message: var message) => Center(child: Text(message)),
        _ => const Center(child: Text("Nothing to show here.")),
      };

  /// Creates an album below the folder shown and descends into it.
  ///
  /// The server writes the `index.json` of a folder that does not exist yet,
  /// which is how "Create album" of the listing view works as well — an upload
  /// into a folder the server does not know would be stored as a single file
  /// instead, so the album has to exist before the first sync.
  Future<void> _createAlbum() async {
    var name = await showDialog<String>(
      context: context,
      builder: (context) => const _NameDialog(),
    );
    if (name == null || name.trim().isEmpty || !mounted) {
      return;
    }
    var folder = name.trim();
    try {
      await widget.client.putResource(
        "${widget.client.baseUrl(path)}/$folder",
        AlbumInfo(title: folder, path: folder),
      );
    } catch (error) {
      if (mounted) {
        setState(() => problem = "Cannot create '$folder': $error");
      }
      return;
    }
    if (mounted) {
      _goTo([...path, folder]);
    }
  }
}

/// Asks for the name of a new album.
class _NameDialog extends StatefulWidget {
  const _NameDialog();

  @override
  State<_NameDialog> createState() => _NameDialogState();
}

class _NameDialogState extends State<_NameDialog> {
  final TextEditingController controller = TextEditingController();

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: const Text("New album"),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: const InputDecoration(
            labelText: "Folder name",
            border: OutlineInputBorder(),
          ),
          onSubmitted: (value) => Navigator.of(context).pop(value),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text("Cancel"),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(controller.text),
            child: const Text("Create"),
          ),
        ],
      );
}

/// The app-bar indicator saying that a camera-roll sync is running.
///
/// Nothing at all while nothing runs, so it can be placed in every app bar
/// without changing its layout. Tapping it opens the server settings, where
/// the whole story is.
class CameraRollIndicator extends StatelessWidget {
  const CameraRollIndicator({super.key});

  @override
  Widget build(BuildContext context) {
    var sync = CameraRollScope.maybeOf(context);
    if (sync == null || !sync.status.running) {
      return const SizedBox.shrink();
    }
    return IconButton(
      key: cameraRollIndicatorKey,
      icon: const Icon(Icons.cloud_upload),
      tooltip: sync.status.line,
      onPressed: () => openServerSettings(context),
    );
  }
}
