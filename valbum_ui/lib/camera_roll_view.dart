/// The user's side of camera-roll sync (issue #30): the "Camera roll" section
/// of the server settings, the dialog choosing the inbox album, and the small
/// app-bar indicator saying that a sync is running.
///
/// The engine itself is in `camera_roll.dart`; nothing here decides anything,
/// it only shows what [CameraRollSync] is doing and hands the user's decisions
/// to it.
library;

import 'package:flutter/material.dart';

import 'caller.dart';
import 'camera_roll.dart';
import 'client.dart';
import 'photo_library.dart';
import 'resource.dart';
import 'settings.dart';

/// The key of the switch enabling the sync, so that a test can address it.
const Key cameraRollSwitchKey = Key("cameraRoll.enabled");

/// The key of the switch limiting the sync to Wi-Fi (issue #36).
const Key cameraRollWifiOnlyKey = Key("cameraRoll.wifiOnly");

/// The key of the list of device albums the sync watches (issue #117).
const Key cameraRollSourcesKey = Key("cameraRoll.sources");

/// The key of the line asking for an album to watch, shown while none is
/// (issue #117).
const Key cameraRollNoSourcesKey = Key("cameraRoll.noSources");

/// The key of the line saying why the device's albums cannot be listed.
const Key cameraRollSourcesProblemKey = Key("cameraRoll.sourcesProblem");

/// The key of the checkbox watching the device album of the given id.
Key cameraRollSourceKey(String id) => Key("cameraRoll.source.$id");

/// The key of the "Choose..." button opening the inbox picker.
const Key cameraRollChooseKey = Key("cameraRoll.choose");

/// The key of the "Sync now" button.
const Key cameraRollSyncNowKey = Key("cameraRoll.syncNow");

/// The key of the "Stop" button shown while a run transfers.
const Key cameraRollStopKey = Key("cameraRoll.stop");

/// The key of the line saying what the last background run did, or why there
/// is no background sync on this platform.
const Key cameraRollBackgroundKey = Key("cameraRoll.background");

/// The key of the line telling a guest why there is no camera-roll sync for
/// them (issue #54).
const Key cameraRollNoSpaceKey = Key("cameraRoll.noSpace");

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

/// The breadcrumb of an inbox path, or what happens while there is none.
///
/// No album chosen is no longer a dead end (issue #54): the first run creates
/// [defaultInboxName] in the user's own space, and the label says so rather
/// than asking for a decision that is not needed.
String inboxLabel(List<String> path) => path.isEmpty
    ? "No album chosen - new photos go into '$defaultInboxName'"
    : path.join(" > ");

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

  /// The albums of the device, asked once and again whenever access may have
  /// changed (issue #117).
  Future<List<PhotoAlbum>>? albums;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    albums ??= _loadAlbums();
  }

  /// The albums a user may watch, or an empty list where there are none.
  ///
  /// Asking the device for its albums needs the same permission the sync
  /// needs, so the same request is made here; a refusal is not swallowed but
  /// shown in the library's own words, see [PhotoLibrary.accessProblem].
  Future<List<PhotoAlbum>> _loadAlbums() async {
    var sync = CameraRollScope.maybeOf(context);
    var library = sync?.library;
    if (library == null || !library.available) {
      return const [];
    }
    if (!sync!.config.enabled) {
      // Opening the settings must not make the system ask for the photos: the
      // sync is off, nothing is watched, and the list appears the moment it
      // is switched on.
      return const [];
    }
    if (!await library.requestAccess()) {
      return const [];
    }
    return selectableSources(await library.albums());
  }

  /// Asks the device for its albums again, after something may have changed
  /// what it answers — the access that was just granted, above all.
  void _reloadAlbums() {
    var pending = _loadAlbums();
    setState(() {
      albums = pending;
    });
  }

  @override
  Widget build(BuildContext context) {
    var sync = CameraRollScope.maybeOf(context);
    if (sync == null) {
      return const SizedBox.shrink();
    }
    var config = sync.config;
    var status = sync.status;
    // A guest has no space of their own, so there is nothing to sync into and
    // nothing to choose: the switch and the picker are disabled and the one
    // sentence that says what would change it stands below them (issue #54).
    // A caller nobody named is not a guest, see [CallerInfo].
    var guest = CallerInfo.isGuestCaller(context);
    var hasLibrary = sync.library.available;
    var available = hasLibrary && !guest;
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
          // The library's own reason, never the guest's: a guest is told
          // about their space below, not about a camera they do have.
          subtitle: hasLibrary
              ? null
              : Text(sync.library.accessProblem ??
                  "No photo library on this platform"),
          value: config.enabled,
          onChanged: available ? _toggle : null,
        ),
        if (guest)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(
              guestNoSpaceNotice,
              key: cameraRollNoSpaceKey,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ),
        SwitchListTile(
          key: cameraRollWifiOnlyKey,
          contentPadding: EdgeInsets.zero,
          title: const Text("Only over Wi-Fi"),
          subtitle: const Text(
            "New photos wait for a Wi-Fi or a wired connection, so that the "
            "upload does not eat into a mobile data plan.",
          ),
          value: config.wifiOnly,
          onChanged: available ? (value) => _toggleWifiOnly(sync, value) : null,
        ),
        _sources(sync, available),
        const SizedBox(height: 8),
        Row(
          children: [
            const Icon(Icons.inbox, size: 20),
            const SizedBox(width: 8),
            Expanded(child: Text(inboxLabel(config.inbox))),
            const SizedBox(width: 8),
            OutlinedButton.icon(
              key: cameraRollChooseKey,
              // The same condition as the switches above (issue #90): where
              // nothing will ever be uploaded — no photo library, or a guest
              // with no space of their own — an album is not chosen either.
              onPressed: available ? () => _chooseInbox(sync) : null,
              icon: const Icon(Icons.folder_open),
              label: const Text("Choose..."),
            ),
          ],
        ),
        const SizedBox(height: 16),
        Text(status.line),
        _backgroundLine(sync),
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

  /// The device albums the sync watches (issue #117).
  ///
  /// Before this existed the sync uploaded the whole photo library — every
  /// picture any app ever saved. It watches what is ticked here, the camera
  /// alone where nothing was ticked yet and the device names a camera album;
  /// where it names none, nothing is watched and the section says so rather
  /// than quietly uploading everything again.
  ///
  /// A device that has no albums at all shows nothing here: there is nothing
  /// to choose from, and the sync scans what little there is.
  Widget _sources(CameraRollSync sync, bool available) =>
      FutureBuilder<List<PhotoAlbum>>(
        future: albums,
        builder: (context, snapshot) {
          // Only while the sync is on: a reason left over from somewhere else
          // is not this section's news while nothing is being watched.
          var problem = sync.library.available && sync.config.enabled
              ? sync.library.accessProblem
              : null;
          var found = snapshot.data ?? const <PhotoAlbum>[];
          if (found.isEmpty) {
            if (problem == null) {
              return const SizedBox.shrink();
            }
            return Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                problem,
                key: cameraRollSourcesProblemKey,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            );
          }
          var watched = sync.config.sourcesOf(found).toSet();
          return Padding(
            key: cameraRollSourcesKey,
            padding: const EdgeInsets.only(top: 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  "Albums to sync",
                  style: Theme.of(context).textTheme.titleSmall,
                ),
                const SizedBox(height: 4),
                Text(
                  newSourceNotice,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                if (watched.isEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      noSourcesNotice,
                      key: cameraRollNoSourcesKey,
                      style:
                          TextStyle(color: Theme.of(context).colorScheme.error),
                    ),
                  ),
                for (var album in found)
                  CheckboxListTile(
                    key: cameraRollSourceKey(album.id),
                    contentPadding: EdgeInsets.zero,
                    dense: true,
                    controlAffinity: ListTileControlAffinity.leading,
                    title: Text(album.name),
                    subtitle: Text(
                      "${album.count} ${album.count == 1 ? "photo" : "photos"}",
                    ),
                    value: watched.contains(album.id),
                    onChanged: available
                        ? (value) => _chooseSources(
                              sync,
                              found,
                              album,
                              value ?? false,
                            )
                        : null,
                  ),
              ],
            ),
          );
        },
      );

  /// Ticks or unticks one album, and hands the whole choice to the engine.
  Future<void> _chooseSources(
    CameraRollSync sync,
    List<PhotoAlbum> found,
    PhotoAlbum album,
    bool watch,
  ) async {
    var watched = sync.config.sourcesOf(found).toSet();
    if (watch) {
      watched.add(album.id);
    } else {
      watched.remove(album.id);
    }
    await sync.chooseSources(
      [
        // In the order the device names them, so that what is stored does not
        // depend on the order the boxes were ticked in.
        for (var candidate in found)
          if (watched.contains(candidate.id)) candidate.id
      ],
      albums: found,
    );
    if (mounted) {
      setState(() => refusal = null);
    }
  }

  /// What the sync does while the app is closed (issue #32): the report of the
  /// last background run, or the reason this platform has none.
  Widget _backgroundLine(CameraRollSync sync) {
    var scheduler = sync.scheduler;
    var problem = sync.backgroundProblem;
    var record = sync.lastBackgroundRun;
    String? text;
    if (!scheduler.available) {
      text = scheduler.unavailableReason;
    } else if (record != null) {
      text = record.line;
    }
    if (text == null && problem == null) {
      return const SizedBox.shrink();
    }
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (text != null)
            Text(
              text,
              key: cameraRollBackgroundKey,
              style: Theme.of(context).textTheme.bodySmall,
            ),
          if (problem != null)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                problem,
                style: const TextStyle(color: Colors.red),
              ),
            ),
        ],
      ),
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
    // Switching the sync on is where the library is asked for access; the
    // albums it refused to name a moment ago can be named now.
    _reloadAlbums();
  }

  Future<void> _toggleWifiOnly(CameraRollSync sync, bool value) async {
    await sync.setWifiOnly(value);
    if (!mounted) {
      return;
    }
    setState(() => refusal = null);
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

/// Browses the folders of the user's own space and answers the album that was
/// chosen.
///
/// Pops the album path (a list of folder names), or `null` when the user
/// leaves without choosing. A folder that does not exist yet is created
/// through the same call the "Create album" of the listing view uses, so an
/// inbox is one dialog away even on a fresh library.
///
/// The **own space** is the whole of what this picker offers (issue #54): the
/// paths it builds are relative to the root of the caller's space — it never
/// spells a canonical `~owner/...` — and a tile that links into somebody
/// else's space ([FolderInfo.link], issue #50) is left out of the listing, so
/// it can neither be chosen nor descended into. A camera roll dropped into a
/// shared album would upload every photo of this device into an album that
/// belongs to somebody else, and nothing on the tile would have warned about
/// it.
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
        ListingInfo(folders: var all) when _own(all).isNotEmpty => ListView(
            children: [
              for (var folder in _own(all))
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

  /// The tiles of the caller's own space, the links into another one dropped,
  /// see the class comment.
  static List<FolderInfo> _own(List<FolderInfo> folders) => [
        for (var folder in folders)
          if (folder.link.isEmpty) folder
      ];

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
