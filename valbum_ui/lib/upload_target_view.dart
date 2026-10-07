/// The user's side of the upload target of issue #240: the line on the start
/// page, the section of the settings, and the way an album is chosen.
///
/// Nothing here decides anything: [UploadTargets] keeps the target, and an
/// [UploadRoute] sends by it, see `upload_target.dart`.
library;

import 'package:flutter/material.dart';

import 'caller.dart';
import 'client.dart';
import 'form_dialog.dart';
import 'l10n/app_localizations.dart';
import 'listing_view.dart' show splitPath;
import 'move_view.dart';
import 'notices.dart';
import 'offline.dart';
import 'upload_target.dart';

/// The key of the start page's line naming the upload target.
const Key uploadTargetLineKey = Key("uploadTarget.line");

/// The key of the start page's line saying why photos go to the inbox again.
const Key uploadTargetEndedKey = Key("uploadTarget.ended");

/// The key of the "Back to inbox" button, on the start page and in the
/// settings.
const Key uploadTargetResetKey = Key("uploadTarget.reset");

/// The key of the button dismissing the line of [uploadTargetEndedKey].
const Key uploadTargetDismissKey = Key("uploadTarget.dismiss");

/// The key of the settings section.
const Key uploadTargetSectionKey = Key("uploadTarget.section");

/// The key of the settings line saying where new photos go.
const Key uploadTargetCurrentKey = Key("uploadTarget.current");

/// The key of the "Choose album…" button of the settings.
const Key uploadTargetChooseKey = Key("uploadTarget.choose");

/// The key of the "Change end date" button of the settings.
const Key uploadTargetChangeEndKey = Key("uploadTarget.changeEnd");

/// The key of the dialog setting the end date.
const Key uploadTargetDialogKey = Key("uploadTarget.dialog");

/// The key of the dialog's line naming the end date; tapping it picks
/// another day.
const Key uploadTargetUntilKey = Key("uploadTarget.until");

/// The key of the dialog's button removing the end date.
const Key uploadTargetNoEndKey = Key("uploadTarget.noEnd");

/// The key of the dialog's button giving the target an end date again.
const Key uploadTargetSetEndKey = Key("uploadTarget.setEnd");

/// The key of the dialog's confirm button.
const Key uploadTargetSaveKey = Key("uploadTarget.save");

/// Makes the [UploadTargets] of the app available, with the client whose
/// server they are kept for.
class UploadTargetScope extends InheritedNotifier<UploadTargets> {
  /// The client the app talks to, `null` while no server is configured and
  /// inside a share link, which never sets a target.
  final VAlbumClient? client;

  const UploadTargetScope({
    super.key,
    required UploadTargets targets,
    required this.client,
    required super.child,
  }) : super(notifier: targets);

  /// The targets of the enclosing app, `null` outside one.
  static UploadTargetScope? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<UploadTargetScope>();

  UploadTargets get targets => notifier!;

  /// The data URL the targets are kept for, `null` where there is none.
  String? get dataUrl => client?.dataUrl;

  @override
  bool updateShouldNotify(UploadTargetScope oldWidget) =>
      super.updateShouldNotify(oldWidget) || oldWidget.client != client;
}

/// The sentence naming where new photos go: the album, with its end date.
String uploadTargetLine(UploadTarget target, AppLocalizations l10n) {
  var until = target.until;
  return until == null
      ? l10n.uploadTargetLine(target.name)
      : l10n.uploadTargetLineUntil(target.name, uploadTargetDay(until, l10n));
}

/// Whether the caller of the enclosing app may have a target at all: a
/// signed-in caller the server names an inbox for — whoever may put photos
/// into the space — and never inside a share link.
bool _offered(BuildContext context, UploadTargetScope? scope) =>
    scope != null &&
    scope.dataUrl != null &&
    (CallerInfo.maybeOf(context)?.hasInbox ?? false);

/// The line on the start page (issue #240): where new photos go while they
/// do not go to the inbox, with the one tap back to it — or, once, why they
/// go to the inbox again.
///
/// Nothing at all while photos go to the inbox and nothing is to be said.
class UploadTargetBanner extends StatelessWidget {
  const UploadTargetBanner({super.key});

  @override
  Widget build(BuildContext context) {
    var scope = UploadTargetScope.maybeOf(context);
    if (!_offered(context, scope)) {
      return const SizedBox.shrink();
    }
    var l10n = AppLocalizations.of(context)!;
    var targets = scope!.targets;
    var dataUrl = scope.dataUrl!;
    var target = targets.targetOf(dataUrl);
    var ended = targets.endedOf(dataUrl);
    if (target == null && ended == null) {
      return const SizedBox.shrink();
    }
    var theme = Theme.of(context);
    return Material(
      color: theme.colorScheme.secondaryContainer,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
        child: Row(
          children: [
            Icon(
              target != null ? Icons.drive_folder_upload : Icons.inbox,
              size: 20,
              color: theme.colorScheme.onSecondaryContainer,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                target != null
                    ? uploadTargetLine(target, l10n)
                    : noticeText(ended!, l10n),
                key:
                    target != null ? uploadTargetLineKey : uploadTargetEndedKey,
                style: TextStyle(color: theme.colorScheme.onSecondaryContainer),
              ),
            ),
            if (target != null)
              TextButton(
                key: uploadTargetResetKey,
                onPressed: () => targets.backToInbox(dataUrl),
                child: Text(l10n.uploadTargetBackToInbox),
              )
            else
              TextButton(
                key: uploadTargetDismissKey,
                onPressed: () => targets.dismiss(dataUrl),
                child: Text(l10n.ok),
              ),
          ],
        ),
      ),
    );
  }
}

/// The settings section "Upload new photos to" (issue #240), beside the
/// camera-roll sync — on the web as well, where the upload into the inbox is
/// the one that follows it.
class UploadTargetSection extends StatelessWidget {
  const UploadTargetSection({super.key});

  @override
  Widget build(BuildContext context) {
    var scope = UploadTargetScope.maybeOf(context);
    if (!_offered(context, scope)) {
      return const SizedBox.shrink();
    }
    var l10n = AppLocalizations.of(context)!;
    var targets = scope!.targets;
    var dataUrl = scope.dataUrl!;
    var target = targets.targetOf(dataUrl);
    var ended = targets.endedOf(dataUrl);
    return Column(
      key: uploadTargetSectionKey,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 24),
        const Divider(),
        const SizedBox(height: 8),
        Text(
          l10n.uploadTargetHeading,
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 8),
        Text(l10n.uploadTargetExplanation),
        const SizedBox(height: 8),
        Row(
          children: [
            Icon(
              target != null ? Icons.drive_folder_upload : Icons.inbox,
              size: 20,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                target != null
                    ? uploadTargetLine(target, l10n)
                    : l10n.uploadTargetToInbox,
                key: uploadTargetCurrentKey,
              ),
            ),
          ],
        ),
        if (ended != null)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(
              noticeText(ended, l10n),
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            OutlinedButton.icon(
              key: uploadTargetChooseKey,
              onPressed: () => chooseUploadTarget(context),
              icon: const Icon(Icons.photo_album),
              label: Text(l10n.uploadTargetChoose),
            ),
            if (target != null)
              OutlinedButton.icon(
                key: uploadTargetChangeEndKey,
                onPressed: () => _changeEnd(context, targets, dataUrl, target),
                icon: const Icon(Icons.event),
                label: Text(l10n.uploadTargetChangeEnd),
              ),
            if (target != null)
              OutlinedButton.icon(
                key: uploadTargetResetKey,
                onPressed: () => targets.backToInbox(dataUrl),
                icon: const Icon(Icons.inbox),
                label: Text(l10n.uploadTargetBackToInbox),
              ),
          ],
        ),
      ],
    );
  }

  Future<void> _changeEnd(
    BuildContext context,
    UploadTargets targets,
    String dataUrl,
    UploadTarget target,
  ) async {
    var answer = await showFormDialog<UploadTargetEnd>(
      context: context,
      builder: (context) => UploadTargetDialog(
        album: target.name,
        until: target.until,
        today: targets.clock(),
      ),
    );
    if (answer == null) {
      return;
    }
    await targets.setUntil(dataUrl, answer.until);
  }
}

/// Picks the album new photos go to, asks for the end date, and keeps both
/// (issue #240).
///
/// The move picker, limited to albums the caller may add photos to; an album
/// may be created on the way, exactly where a move would offer it. Nothing is
/// kept when either dialog is left without confirming. Refused while offline,
/// like every other write: the picker has nothing to show then.
Future<void> chooseUploadTarget(BuildContext context) async {
  var scope = UploadTargetScope.maybeOf(context);
  var client = scope?.client;
  if (scope == null || client == null) {
    return;
  }
  if (refuseWhileOffline(context)) {
    return;
  }
  var targets = scope.targets;
  var dataUrl = client.dataUrl;
  var l10n = AppLocalizations.of(context)!;
  var messenger = ScaffoldMessenger.of(context);
  var picked = await showFormDialog<PickedTarget>(
    context: context,
    builder: (context) => FolderPicker(
      key: const Key("upload-target-picker"),
      client: client,
      title: l10n.uploadTargetPickerTitle,
      initialPath: const [],
      confirmLabel: (_) => l10n.uploadTargetPickHere,
      targetIsAlbum: true,
      mayCreateAlbum: true,
      newAlbumDate: targets.clock(),
      requireContribute: true,
    ),
  );
  if (picked == null || !context.mounted) {
    return;
  }
  var path = picked.path;
  var title = picked.title;
  var newAlbum = picked.newAlbum;
  if (newAlbum != null) {
    try {
      // Where the album landed, never where it was asked for (issue #48).
      path = splitPath((await client.createAlbum(path, newAlbum)).path);
    } catch (error) {
      showRefusal(messenger, error);
      return;
    }
    title = newAlbum.title;
    if (!context.mounted) {
      return;
    }
  }
  var target = UploadTarget(path: path, title: title);
  var answer = await showFormDialog<UploadTargetEnd>(
    context: context,
    builder: (context) => UploadTargetDialog(
      album: target.name,
      until: defaultUploadTargetEnd(targets.clock()),
      today: targets.clock(),
    ),
  );
  if (answer == null) {
    return;
  }
  await targets.choose(
    dataUrl,
    UploadTarget(path: path, title: title, until: answer.until),
  );
}

/// What [UploadTargetDialog] was confirmed with: the end date, `null` for
/// none.
@immutable
class UploadTargetEnd {
  final DateTime? until;

  const UploadTargetEnd(this.until);
}

/// Asks until when new photos go to [album]: one week ahead by default, any
/// later day, or no end at all (issue #240).
class UploadTargetDialog extends StatefulWidget {
  /// The album, as the title names it.
  final String album;

  /// The end date the dialog opens with, `null` for none.
  final DateTime? until;

  /// The day it is now: no end date before it can be picked.
  final DateTime today;

  const UploadTargetDialog({
    super.key,
    required this.album,
    required this.until,
    required this.today,
  });

  @override
  State<UploadTargetDialog> createState() => _UploadTargetDialogState();
}

class _UploadTargetDialogState extends State<UploadTargetDialog> {
  late DateTime? _until = widget.until;

  DateTime get _first =>
      DateTime(widget.today.year, widget.today.month, widget.today.day);

  Future<void> _pick() async {
    var initial = _until ?? defaultUploadTargetEnd(widget.today);
    var picked = await showDatePicker(
      context: context,
      initialDate: initial.isBefore(_first) ? _first : initial,
      firstDate: _first,
      lastDate: DateTime(_first.year + 5),
    );
    if (picked != null && mounted) {
      setState(() => _until = picked);
    }
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var until = _until;
    return AlertDialog(
      key: uploadTargetDialogKey,
      title: Text(l10n.uploadTargetDialogTitle(widget.album)),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ListTile(
            key: uploadTargetUntilKey,
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.event),
            title: Text(until == null
                ? l10n.uploadTargetNoEnd
                : l10n.uploadTargetUntil(uploadTargetDay(until, l10n))),
            onTap: _pick,
            trailing: until == null
                ? TextButton(
                    key: uploadTargetSetEndKey,
                    onPressed: () => setState(
                        () => _until = defaultUploadTargetEnd(widget.today)),
                    child: Text(l10n.uploadTargetSetEnd),
                  )
                : TextButton(
                    key: uploadTargetNoEndKey,
                    onPressed: () => setState(() => _until = null),
                    child: Text(l10n.uploadTargetNoEnd),
                  ),
          ),
          const SizedBox(height: 8),
          Text(
            l10n.uploadTargetEndExplanation,
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.cancel),
        ),
        ElevatedButton(
          key: uploadTargetSaveKey,
          onPressed: () => Navigator.of(context).pop(UploadTargetEnd(until)),
          child: Text(l10n.uploadTargetSave),
        ),
      ],
    );
  }
}
