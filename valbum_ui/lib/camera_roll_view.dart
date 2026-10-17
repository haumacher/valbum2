/// The user's side of camera-roll sync (issue #30): the "Camera roll" section
/// of the server settings, the dialog choosing the inbox album, and the small
/// app-bar indicator saying that a sync is running.
///
/// The engine itself is in `camera_roll.dart`; nothing here decides anything,
/// it only shows what [CameraRollSync] is doing and hands the user's decisions
/// to it.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';

import 'background.dart';
import 'caller.dart';
import 'camera_roll.dart';
import 'dialog_style.dart';
import 'filter_list.dart';
import 'form_dialog.dart';
import 'l10n/app_localizations.dart';
import 'loadable.dart';
import 'notices.dart';
import 'photo_library.dart';
import 'selectable.dart';
import 'settings.dart';
import 'upload_target_view.dart';

/// The key of the switch enabling the sync, so that a test can address it.
const Key cameraRollSwitchKey = Key("cameraRoll.enabled");

/// The key of the switch limiting the sync to Wi-Fi (issue #36).
const Key cameraRollWifiOnlyKey = Key("cameraRoll.wifiOnly");

/// The key of the part of the section naming the device albums the sync
/// watches (issue #117), with the button choosing them (issue #298).
const Key cameraRollSourcesKey = Key("cameraRoll.sources");

/// The key of the line naming the albums the sync watches (issue #298).
const Key cameraRollSourcesSummaryKey = Key("cameraRoll.sourcesSummary");

/// The key of the button opening the dialog that chooses the albums to watch
/// (issue #298).
const Key cameraRollChooseSourcesKey = Key("cameraRoll.chooseSources");

/// The key of the dialog choosing the albums to watch (issue #298).
const Key cameraRollSourcesDialogKey = Key("cameraRoll.sourcesDialog");

/// The key of the field narrowing the albums of that dialog.
const Key cameraRollSourcesFilterKey = Key("cameraRoll.sourcesFilter");

/// The key of the button of that dialog applying the choice.
const Key cameraRollSourcesApplyKey = Key("cameraRoll.sourcesApply");

/// The key of the button of that dialog discarding the choice.
const Key cameraRollSourcesCancelKey = Key("cameraRoll.sourcesCancel");

/// What the keys of the progress, the failure and the Retry of the device's
/// albums start with, in the section and in the dialog alike: see
/// [LoadableBuilder.keyPrefix] for the endings.
const String cameraRollAlbumsKeyPrefix = "cameraRoll.albums";

/// The key of the line asking for an album to watch, shown while none is
/// (issue #117).
const Key cameraRollNoSourcesKey = Key("cameraRoll.noSources");

/// The key of the line saying why the device's albums cannot be listed.
const Key cameraRollSourcesProblemKey = Key("cameraRoll.sourcesProblem");

/// The key of the checkbox watching the device album of the given id, a row
/// of the dialog choosing the albums (issue #298).
Key cameraRollSourceKey(String id) => Key("cameraRoll.source.$id");

/// The key of the line naming the inbox the photos go into (issue #226).
const Key cameraRollInboxKey = Key("cameraRoll.inbox");

/// The key of the "Sync now" button.
const Key cameraRollSyncNowKey = Key("cameraRoll.syncNow");

/// The key of the "Sync anyway" button offered while the server is still
/// building its hash index (issue #118).
const Key cameraRollSyncAnywayKey = Key("cameraRoll.syncAnyway");

/// The key of the "Stop" button shown while a run transfers.
const Key cameraRollStopKey = Key("cameraRoll.stop");

/// The key of the line saying what the last background run did, or why there
/// is no background sync on this platform.
const Key cameraRollBackgroundKey = Key("cameraRoll.background");

/// The key of the line saying what the sync is doing or did last.
const Key cameraRollStatusKey = Key("cameraRoll.status");

/// The key of the selection region around the whole section, which makes
/// every line of it copyable (issue #169).
const Key cameraRollSelectableKey = Key("cameraRoll.selectable");

/// The key of the line telling a guest why there is no camera-roll sync for
/// them (issue #54).
const Key cameraRollNoSpaceKey = Key("cameraRoll.noSpace");

/// The key of the app-bar indicator shown while a sync runs.
const Key cameraRollIndicatorKey = Key("cameraRoll.indicator");

/// The dialog saying why the app asks to show notifications (issue #300), and
/// its button that goes on to the system's question.
const Key cameraRollNotificationsWhyKey = Key("cameraRoll.notificationsWhy");
const Key cameraRollNotificationsAllowKey =
    Key("cameraRoll.notificationsAllow");
const Key cameraRollNotificationsNotNowKey =
    Key("cameraRoll.notificationsNotNow");

/// The line saying that the app may show no notifications (issue #300).
const Key cameraRollNotificationsOffKey = Key("cameraRoll.notificationsOff");

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
  ///
  /// A [Loadable] (issue #298): the section and the dialog choosing from them
  /// show a progress while the device is asked, and its reason with a Retry
  /// where it failed — never an empty list that hides the failure.
  Loadable<List<PhotoAlbum>>? _albums;

  /// Whether the device shows the notification of an upload (issue #300),
  /// `null` until it was asked — and where there is no such notification.
  bool? _notificationsAllowed;

  bool _notificationsChecked = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_albums == null) {
      _albums = Loadable<List<PhotoAlbum>>(_loadAlbums);
      _albums!.load().ignore();
    }
    if (!_notificationsChecked) {
      _notificationsChecked = true;
      unawaited(_checkNotifications());
    }
  }

  /// Asks the device whether it shows the notification of an upload; asking
  /// asks nobody, see [_askForNotifications].
  Future<void> _checkNotifications() async {
    var notifier = CameraRollScope.maybeOf(context)?.notifier;
    if (notifier == null || !notifier.available) {
      return;
    }
    var allowed = await notifier.allowed();
    if (mounted) {
      setState(() => _notificationsAllowed = allowed);
    }
  }

  /// Asks for the permission to show the notification of an upload, when
  /// the sync was just switched on (issue #300).
  ///
  /// The app says first what the notification is for and what doing without
  /// it costs — nothing but the sight of the progress — and only then lets
  /// the system ask; "Not now" asks nothing. The section says afterwards that
  /// the notifications are off, where they are.
  Future<void> _askForNotifications(CameraRollSync sync) async {
    var notifier = sync.notifier;
    if (!notifier.available) {
      return;
    }
    if (!await notifier.allowed() && await notifier.askable() && mounted) {
      var l10n = AppLocalizations.of(context)!;
      var ask = await showAppDialog<bool>(
        context: context,
        builder: (context) => AppAlertDialog(
          key: cameraRollNotificationsWhyKey,
          title: Text(l10n.notificationsWhyTitle),
          content: Text(l10n.notificationsWhyBody),
          actions: [
            TextButton(
              key: cameraRollNotificationsNotNowKey,
              onPressed: () => Navigator.of(context).pop(false),
              child: Text(l10n.notificationsNotNow),
            ),
            FilledButton(
              key: cameraRollNotificationsAllowKey,
              onPressed: () => Navigator.of(context).pop(true),
              child: Text(l10n.notificationsAllow),
            ),
          ],
        ),
      );
      if (ask == true) {
        await notifier.request();
      }
    }
    await _checkNotifications();
  }

  @override
  void dispose() {
    _albums?.dispose();
    super.dispose();
  }

  /// The albums a user may watch, or an empty list where there are none.
  ///
  /// Asking the device for its albums needs the same permission the sync
  /// needs, so the same request is made here; a refusal is not swallowed but
  /// shown in the library's own words, see [PhotoLibrary.accessProblem]. A
  /// device that fails to list them fails the [Loadable], in the platform's
  /// own words where it gave some.
  Future<List<PhotoAlbum>> _loadAlbums() async {
    if (!mounted) {
      return const [];
    }
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
    try {
      return selectableSources(await library.albums());
    } on PlatformException catch (failure) {
      // The platform's sentence, not the exception's dump.
      throw failure.message ?? failure.code;
    }
  }

  /// Asks the device for its albums again, after something may have changed
  /// what it answers — the access that was just granted, above all.
  void _reloadAlbums() {
    _albums?.retry().ignore();
  }

  @override
  Widget build(BuildContext context) {
    var sync = CameraRollScope.maybeOf(context);
    if (sync == null) {
      return const SizedBox.shrink();
    }
    var config = sync.config;
    var status = sync.status;
    // A guest has no space of their own, so there is nothing to sync into:
    // the switches are disabled and the one
    // sentence that says what would change it stands below them (issue #54).
    // A caller nobody named is not a guest, see [CallerInfo].
    var guest = CallerInfo.isGuestCaller(context);
    var hasLibrary = sync.library.available;
    var available = hasLibrary && !guest;
    var l10n = AppLocalizations.of(context)!;
    // Every line this section says can be copied (issue #169): a status or a
    // refusal is what one pastes into a bug report, and a reason the platform
    // gave is not something to type off a screen. One region around the whole
    // section, so that a selection may span the status and the background
    // line; the switches, the boxes and the buttons take their taps as before.
    // The settings page is one [SelectableArea] already; shown alone, the
    // section is one of its own.
    return SelectableArea(
      key: cameraRollSelectableKey,
      child: _content(
          context, sync, config, status, guest, hasLibrary, available, l10n),
    );
  }

  Widget _content(
    BuildContext context,
    CameraRollSync sync,
    CameraRollConfig config,
    CameraRollStatus status,
    bool guest,
    bool hasLibrary,
    bool available,
    AppLocalizations l10n,
  ) {
    var inbox = CallerInfo.maybeOf(context)?.inbox;
    // For a while the photos may go to an album instead (issue #240); the
    // line says so, and the section above it changes it.
    var targetScope = UploadTargetScope.maybeOf(context);
    var target = targetScope?.targets.targetOf(targetScope.dataUrl);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 24),
        const Divider(),
        const SizedBox(height: 8),
        Text(
          l10n.cameraRollHeading,
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 8),
        Text(l10n.cameraRollExplanation),
        const SizedBox(height: 8),
        SwitchListTile(
          key: cameraRollSwitchKey,
          contentPadding: EdgeInsets.zero,
          title: Text(l10n.cameraRollUploadNew),
          // The library's own reason, never the guest's: a guest is told
          // about their space below, not about a camera they do have.
          subtitle: hasLibrary
              ? null
              : Text(noticeOr(
                  sync.library.accessProblem, l10n.noPhotoLibrary, l10n)),
          value: config.enabled,
          onChanged: available ? _toggle : null,
        ),
        if (guest)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(
              noticeText(guestNoSpaceNotice, l10n),
              key: cameraRollNoSpaceKey,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ),
        SwitchListTile(
          key: cameraRollWifiOnlyKey,
          contentPadding: EdgeInsets.zero,
          title: Text(l10n.onlyOverWifi),
          subtitle: Text(l10n.onlyOverWifiExplanation),
          value: config.wifiOnly,
          onChanged: available ? (value) => _toggleWifiOnly(sync, value) : null,
        ),
        _sources(sync, available),
        const SizedBox(height: 8),
        // Where the photos go is the server's word, never a choice of this
        // device (issue #226): the one inbox of the space, named here --
        // unless this device sends them to an album for a while (#240).
        if (inbox != null && inbox.isNotEmpty)
          Row(
            children: [
              Icon(target == null ? Icons.inbox : Icons.drive_folder_upload,
                  size: 20),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  target == null
                      ? l10n.cameraRollInboxTarget(inbox)
                      : uploadTargetLine(target, l10n),
                  key: cameraRollInboxKey,
                ),
              ),
            ],
          ),
        const SizedBox(height: 16),
        Text(cameraRollLine(status, l10n), key: cameraRollStatusKey),
        _backgroundLine(sync),
        // Refusals speak (issue #300): without the permission the upload runs
        // all the same, and this says that only its progress is not seen.
        if (config.enabled && _notificationsAllowed == false)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(
              l10n.cameraRollNotificationsOff,
              key: cameraRollNotificationsOffKey,
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
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
              label: Text(l10n.syncNow),
            ),
            if (status.running)
              OutlinedButton.icon(
                key: cameraRollStopKey,
                onPressed: sync.stop,
                icon: const Icon(Icons.stop),
                label: Text(l10n.stop),
              ),
            // The run is waiting for the server to finish reading the library;
            // whoever would rather have the photos now is offered the choice,
            // and it is remembered (issue #118).
            if (status.indexing && !status.running)
              OutlinedButton.icon(
                key: cameraRollSyncAnywayKey,
                onPressed: () => sync.setSyncWhileIndexing(true),
                icon: const Icon(Icons.play_arrow),
                label: Text(l10n.syncAnyway),
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
  /// picture any app ever saved. It watches what is chosen here, the camera
  /// alone where nothing was chosen yet and the device names a camera album;
  /// where it names none, nothing is watched and the section says so rather
  /// than quietly uploading everything again.
  ///
  /// One line names what is watched, and a button opens the dialog that
  /// chooses it (issue #298): a phone with dozens of albums made a box for
  /// each of them a long list in the middle of the settings.
  ///
  /// A device that has no albums at all shows nothing here: there is nothing
  /// to choose from, and the sync scans what little there is.
  Widget _sources(CameraRollSync sync, bool available) {
    var loadable = _albums!;
    return ListenableBuilder(
      listenable: loadable,
      builder: (context, _) {
        var l10n = AppLocalizations.of(context)!;
        if (!loadable.hasValue) {
          // While the sync is off the device is not asked, and there is
          // nothing to wait for or to fail.
          if (!sync.library.available || !sync.config.enabled) {
            return const SizedBox.shrink();
          }
          return _sourcesBlock(
            context,
            sync,
            available,
            const [],
            LoadableStatus(
              loadable: loadable,
              keyPrefix: cameraRollAlbumsKeyPrefix,
            ),
          );
        }
        // Only while the sync is on: a reason left over from somewhere else
        // is not this section's news while nothing is being watched.
        var problem = sync.library.available && sync.config.enabled
            ? sync.library.accessProblem
            : null;
        var found = loadable.value!;
        if (found.isEmpty) {
          if (problem == null) {
            return const SizedBox.shrink();
          }
          return Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(
              noticeText(problem, l10n),
              key: cameraRollSourcesProblemKey,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          );
        }
        var watched = sync.config.sourcesOf(found).toSet();
        var names = [
          for (var album in found)
            if (watched.contains(album.id)) album.name
        ];
        return _sourcesBlock(
          context,
          sync,
          available,
          found,
          names.isEmpty
              ? Text(
                  l10n.cameraRollNoSources,
                  key: cameraRollNoSourcesKey,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                )
              : Text(
                  sourcesSummary(names, l10n),
                  key: cameraRollSourcesSummaryKey,
                ),
        );
      },
    );
  }

  /// The heading, [line] and the button opening the dialog.
  Widget _sourcesBlock(
    BuildContext context,
    CameraRollSync sync,
    bool available,
    List<PhotoAlbum> found,
    Widget line,
  ) {
    var l10n = AppLocalizations.of(context)!;
    return Padding(
      key: cameraRollSourcesKey,
      padding: const EdgeInsets.only(top: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            l10n.albumsToSync,
            style: Theme.of(context).textTheme.titleSmall,
          ),
          const SizedBox(height: 4),
          Row(
            children: [
              Expanded(child: line),
              const SizedBox(width: 8),
              OutlinedButton(
                key: cameraRollChooseSourcesKey,
                onPressed: available ? () => _openSources(sync) : null,
                child: Text(l10n.cameraRollChooseSources),
              ),
            ],
          ),
        ],
      ),
    );
  }

  /// Opens the dialog choosing the albums, and hands the whole choice it
  /// answers to the engine; Cancel and Escape change nothing.
  Future<void> _openSources(CameraRollSync sync) async {
    var loadable = _albums!;
    var chosen = await showFormDialog<List<String>>(
      context: context,
      builder: (context) => CameraRollSourcesDialog(
        albums: loadable,
        watchedOf: sync.config.sourcesOf,
      ),
    );
    if (chosen == null || !mounted) {
      return;
    }
    await sync.chooseSources(chosen, albums: loadable.value ?? const []);
    if (mounted) {
      setState(() => refusal = null);
    }
  }

  /// What the sync does while the app is closed (issue #32): the report of the
  /// last background run, or the reason this platform has none.
  Widget _backgroundLine(CameraRollSync sync) {
    var l10n = AppLocalizations.of(context)!;
    var scheduler = sync.scheduler;
    var problem = sync.backgroundProblem;
    var record = sync.lastBackgroundRun;
    String? text;
    if (!scheduler.available) {
      var reason = scheduler.unavailableReason;
      text = reason == null ? null : noticeText(reason, l10n);
    } else if (record != null) {
      text = backgroundRunLine(record, l10n);
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
                noticeText(problem, l10n),
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
    setState(() => refusal = problem == null
        ? null
        : noticeText(problem, AppLocalizations.of(context)!));
    // Switching the sync on is where the library is asked for access; the
    // albums it refused to name a moment ago can be named now.
    _reloadAlbums();
    if (value && problem == null) {
      await _askForNotifications(sync);
    }
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
}

/// How many albums [sourcesSummary] names before it counts the rest.
const int _namedSources = 2;

/// The line naming the watched albums [names], in the order the device names
/// them: the first [_namedSources] by name, the rest counted — "Camera,
/// WhatsApp Images and 2 more" (issue #298).
String sourcesSummary(List<String> names, AppLocalizations l10n) {
  var named = names.length <= _namedSources + 1
      // A count of one would take as much room as the name it hides.
      ? names
      : names.take(_namedSources).toList();
  return l10n.cameraRollSourcesSummary(
    named.join(", "),
    names.length - named.length,
  );
}

/// The height of the dialog's content below which its list no longer has the
/// room to scroll by itself, see [CrampedScroll]: the explanation, the filter
/// field and two or three albums.
const double _roomySourcesHeight = 240;

/// The dialog choosing the albums of the device the camera-roll sync watches
/// (issue #298).
///
/// A box for each album, with its name and the number of its photos, below a
/// [FilterField] that narrows them as in every chooser of the app; the arrows
/// walk the boxes and `Enter` ticks the one they stand on. The dialog answers
/// the ids of the ticked albums in the order the device names them — so that
/// what is stored does not depend on the order they were ticked in — on
/// Apply, and `null` on Cancel or Escape. The albums are a [Loadable]: the
/// dialog waits for them, or says why they failed, with a Retry.
class CameraRollSourcesDialog extends StatefulWidget {
  /// The albums to choose from.
  final Loadable<List<PhotoAlbum>> albums;

  /// The ids of the albums watched now, given what the device holds; ticked
  /// when the dialog opens (or when the albums arrive).
  final List<String> Function(List<PhotoAlbum> albums) watchedOf;

  const CameraRollSourcesDialog({
    super.key,
    required this.albums,
    required this.watchedOf,
  });

  @override
  State<CameraRollSourcesDialog> createState() =>
      _CameraRollSourcesDialogState();
}

class _CameraRollSourcesDialogState extends State<CameraRollSourcesDialog> {
  final TextEditingController _filter = TextEditingController();

  /// Which album the keyboard stands on, see [PickHighlight].
  late final PickHighlight<PhotoAlbum> _highlight =
      PickHighlight<PhotoAlbum>(idOf: (album) => album.id)
        ..addListener(_changed);

  /// The ids ticked, `null` until the albums arrived.
  Set<String>? _ticked;

  @override
  void initState() {
    super.initState();
    widget.albums.addListener(_albumsChanged);
    _tickWatched();
    // Albums that failed are asked again when the dialog opens over them —
    // after the build, which is no time to tell the others listening.
    scheduleMicrotask(() {
      if (mounted) {
        widget.albums.load().ignore();
      }
    });
  }

  @override
  void dispose() {
    widget.albums.removeListener(_albumsChanged);
    _highlight.dispose();
    _filter.dispose();
    super.dispose();
  }

  void _changed() => setState(() {});

  void _albumsChanged() => setState(_tickWatched);

  /// Ticks what is watched, once the albums are there to say it of.
  void _tickWatched() {
    if (_ticked == null && widget.albums.hasValue) {
      _ticked = widget.watchedOf(widget.albums.value!).toSet();
    }
  }

  void _toggle(PhotoAlbum album, bool watch) {
    setState(() {
      if (watch) {
        _ticked!.add(album.id);
      } else {
        _ticked!.remove(album.id);
      }
    });
  }

  void _apply() {
    var found = widget.albums.value ?? const <PhotoAlbum>[];
    var ticked = _ticked ?? const <String>{};
    Navigator.of(context).pop([
      for (var album in found)
        if (ticked.contains(album.id)) album.id
    ]);
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var loaded = widget.albums.hasValue && _ticked != null;
    var found = widget.albums.value ?? const <PhotoAlbum>[];
    var needle = _filter.text.trim().toLowerCase();
    var shown = [
      for (var album in found)
        if (needle.isEmpty || album.name.toLowerCase().contains(needle)) album
    ];
    _highlight.shown = loaded ? shown : const [];
    return PickKeys<PhotoAlbum>(
      highlight: _highlight,
      onEnter: (album) {
        if (album != null) {
          _toggle(album, !_ticked!.contains(album.id));
        }
      },
      child: AppAlertDialog(
        key: cameraRollSourcesDialogKey,
        title: Text(l10n.albumsToSync),
        content: SizedBox(
          width: 360,
          child: LayoutBuilder(builder: (context, constraints) {
            // With room, the list scrolls by itself below the field; where a
            // phone's keyboard leaves too little, the whole content scrolls
            // and the list is laid out in full, see [CrampedScroll].
            var roomy = constraints.maxHeight >= _roomySourcesHeight;
            var list = ListView(
              shrinkWrap: true,
              physics: roomy ? null : const NeverScrollableScrollPhysics(),
              children: [
                for (var album in shown) _row(context, l10n, album),
              ],
            );
            return CrampedScroll(
              roomy: roomy,
              maxHeight: constraints.maxHeight,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    l10n.cameraRollNewSource,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  const SizedBox(height: 8),
                  FilterField(
                    fieldKey: cameraRollSourcesFilterKey,
                    controller: _filter,
                    label: l10n.cameraRollSourcesFilter,
                    highlight: _highlight,
                    onChanged: (_) => setState(() {}),
                  ),
                  const SizedBox(height: 8),
                  if (!loaded)
                    LoadableStatus(
                      loadable: widget.albums,
                      keyPrefix: cameraRollAlbumsKeyPrefix,
                    )
                  else if (found.isEmpty)
                    Text(l10n.cameraRollNoDeviceAlbums)
                  else if (shown.isEmpty)
                    Text(l10n.cameraRollSourcesNoMatch)
                  else if (roomy)
                    Flexible(child: list)
                  else
                    list,
                ],
              ),
            );
          }),
        ),
        actions: [
          TextButton(
            key: cameraRollSourcesCancelKey,
            onPressed: () => Navigator.of(context).pop(),
            child: Text(l10n.cancel),
          ),
          FilledButton(
            key: cameraRollSourcesApplyKey,
            onPressed: loaded ? _apply : null,
            child: Text(l10n.apply),
          ),
        ],
      ),
    );
  }

  /// The box of one album, with its name and the number of its photos.
  Widget _row(BuildContext context, AppLocalizations l10n, PhotoAlbum album) =>
      _highlight.row(
        album,
        CheckboxListTile(
          key: cameraRollSourceKey(album.id),
          selected: _highlight.isHighlighted(album),
          selectedTileColor: PickHighlight.color(context),
          contentPadding: EdgeInsets.zero,
          dense: true,
          controlAffinity: ListTileControlAffinity.leading,
          title: Text(album.name),
          subtitle: Text(l10n.photoCount(album.count)),
          value: _ticked!.contains(album.id),
          onChanged: (value) => _toggle(album, value ?? false),
        ),
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
      tooltip: cameraRollLine(sync.status, AppLocalizations.of(context)!),
      onPressed: () => openServerSettings(context),
    );
  }
}

/// The sentence [notice] reads, [fallback] where there is no notice.
String noticeOr(AppNotice? notice, String fallback, AppLocalizations l10n) =>
    notice == null ? fallback : noticeText(notice, l10n);

/// A time of day as the camera-roll lines write it, in the locale's own form.
String cameraRollTime(DateTime when, AppLocalizations l10n) =>
    DateFormat.Hm(l10n.localeName).format(when.toLocal());

/// The one line the settings section and the app-bar tooltip show (issue #108).
///
/// The engine answers what happened as data ([CameraRollStatus]) and says
/// nothing in words; this is where the words are, so that a run reads in the
/// language of the device. What the **server** said travels verbatim through
/// [CameraRollStatus.message] and is never re-worded here.
String cameraRollLine(CameraRollStatus status, AppLocalizations l10n) {
  String reason() =>
      status.message ??
      (status.notice == null
          ? l10n.cameraRollUnknownReason
          : noticeText(status.notice!, l10n));
  String when() => status.nextAttempt == null
      ? l10n.cameraRollNextAttempt
      : cameraRollTime(status.nextAttempt!, l10n);

  switch (status.phase) {
    case CameraRollPhase.disabled:
      return l10n.cameraRollOff;
    case CameraRollPhase.unavailable:
      return noticeOr(
          status.notice, status.message ?? l10n.noPhotoLibrary, l10n);
    case CameraRollPhase.running:
      // A run handed over to the platform has not said yet what it found
      // (issue #300).
      if (status.total == 0) {
        return l10n.cameraRollLooking;
      }
      return l10n.cameraRollUploading(status.total, status.done + 1);
    case CameraRollPhase.waiting:
      // A run that waits for the index has not failed: it is postponed, and
      // the sentence says what the server is doing (issue #118).
      if (status.indexing) {
        var indexing = l10n.cameraRollIndexing(
            status.indexingTotal!, status.indexingDone!);
        return "$indexing ${l10n.cameraRollWaitingUntil(when())}";
      }
      return l10n.cameraRollFailedRetrying(reason(), when());
    case CameraRollPhase.failed:
      return l10n.cameraRollFailed(reason());
    case CameraRollPhase.idle:
      return _lastRunLine(status, l10n);
  }
}

String _lastRunLine(CameraRollStatus status, AppLocalizations l10n) {
  var line = _syncedLine(status, l10n);
  // Where the photos went when that was an album, and what became of the
  // album they were meant for (issue #240).
  var target = status.lastTarget;
  if (target != null && status.lastStored > 0) {
    line = "$line ${l10n.uploadTargetWentTo(target)}";
  }
  var targetNotice = status.targetNotice;
  if (targetNotice != null) {
    line = "$line ${noticeText(targetNotice, l10n)}";
  }
  var skipped = status.lastSkipped;
  if (skipped.isEmpty) {
    return line;
  }
  // What the server does not take is said once, and not tried again (#186).
  var named = skipped.take(3).join(", ") + (skipped.length > 3 ? ", ..." : "");
  return "$line ${l10n.cameraRollSkipped(named, skipped.length)}";
}

String _syncedLine(CameraRollStatus status, AppLocalizations l10n) {
  var when = status.lastSuccess;
  if (when == null) {
    return l10n.cameraRollWaitingForPhotos;
  }
  var count = status.lastStored + status.lastPresent;
  if (count == 0) {
    return l10n.cameraRollNothingNew(cameraRollTime(when, l10n));
  }
  var line = l10n.cameraRollSynced(
    status.lastStored,
    count,
    cameraRollTime(when, l10n),
    status.lastPresent,
  );
  var where = status.lastPresentIn;
  if (where.isEmpty) {
    return line;
  }
  var named = where.take(3).join(", ") + (where.length > 3 ? ", ..." : "");
  return "$line ${l10n.alreadyInLibrary(named)}";
}

/// What the last background run did, as the settings section says it.
String backgroundRunLine(BackgroundRunRecord record, AppLocalizations l10n) =>
    record.ok
        ? l10n.backgroundLastRun(record.stored, record.stamp, record.present)
        : l10n.backgroundLastRunFailed(
            record.message ?? l10n.cameraRollUnknownReason,
            record.stamp,
          );
