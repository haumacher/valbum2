/// The dialogs of the labels of an album (issue #213): "Label…" on a
/// selection in the edit mode, and the rename and removal of one label on
/// every photograph of the album, offered on its chip.
library;

import 'package:flutter/material.dart';

import 'album_labels.dart';
import 'form_dialog.dart';
import 'l10n/app_localizations.dart';
import 'resource.dart';

/// Asks which labels the given selected [parts] are to carry.
///
/// Answers, per label that is to change, whether it is to be given to every
/// selected photograph (`true`) or taken off them (`false`); `null` where the
/// dialog was cancelled. A label left as it stood is not in the answer, so a
/// label only some of the selection carries stays exactly where it is.
Future<Map<String, bool>?> askLabels(
  BuildContext context, {
  required List<String> labels,
  required List<AlbumPart> parts,
}) =>
    showFormDialog<Map<String, bool>>(
      context: context,
      builder: (context) => LabelDialog(labels: labels, parts: parts),
    );

/// The labels of a selection, one check box each, and a field for a new one.
///
/// A check box shows what the selection carries: ticked where every selected
/// photograph carries the label, half where some do, empty where none does.
/// A tap makes the label one the whole selection carries or none of it does.
class LabelDialog extends StatefulWidget {
  /// The labels of the album, in the order they are offered.
  final List<String> labels;

  /// The selected parts, a group standing for its members.
  final List<AlbumPart> parts;

  const LabelDialog({super.key, required this.labels, required this.parts});

  @override
  State<LabelDialog> createState() => LabelDialogState();
}

class LabelDialogState extends State<LabelDialog> {
  final TextEditingController _typed = TextEditingController();

  /// The labels offered: the album's, and the new ones typed here.
  late final List<String> _labels = [...widget.labels];

  /// What the user decided per label, absent where nothing was decided.
  final Map<String, bool> _decided = {};

  @override
  void dispose() {
    _typed.dispose();
    super.dispose();
  }

  /// The state of the check box of [label]: the decision, else what the
  /// selection carries (`null` for some of it).
  bool? stateOf(String label) {
    var decided = _decided[label];
    if (decided != null) {
      return decided;
    }
    var coverage = labelCoverage(widget.parts, label);
    if (coverage.carrying == 0) {
      return false;
    }
    return coverage.carrying == coverage.total ? true : null;
  }

  void _toggle(String label) => setState(() {
        // Half becomes all; all becomes none; none becomes all.
        _decided[label] = stateOf(label) != true;
      });

  void _addTyped() {
    var label = normalizedLabel(_typed.text);
    if (label.isEmpty) {
      return;
    }
    setState(() {
      if (!_labels.contains(label)) {
        _labels.add(label);
      }
      _decided[label] = true;
      _typed.clear();
    });
  }

  /// The decisions that change something, see [askLabels].
  Map<String, bool> get changes {
    var result = <String, bool>{};
    _decided.forEach((label, carry) {
      var coverage = labelCoverage(widget.parts, label);
      var already =
          carry ? coverage.carrying == coverage.total : coverage.carrying == 0;
      if (!already) {
        result[label] = carry;
      }
    });
    return result;
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var count = [for (var part in widget.parts) ...imagesOfPart(part)].length;
    return FormDialogFrame(
      key: const Key("label-dialog"),
      title: Text(l10n.labelDialogTitle(count)),
      fields: [
        Text(l10n.labelDialogHelp,
            style: Theme.of(context).textTheme.bodySmall),
        const SizedBox(height: 8),
        if (_labels.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text(l10n.labelNoneYet, key: const Key("label-none")),
          ),
        for (var label in _labels)
          CheckboxListTile(
            key: Key("label-option-$label"),
            dense: true,
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            tristate: true,
            value: stateOf(label),
            title: Text(label),
            onChanged: (_) => _toggle(label),
          ),
        Row(
          children: [
            Expanded(
              child: TextField(
                key: const Key("label-new"),
                controller: _typed,
                autofocus: _labels.isEmpty,
                decoration: InputDecoration(labelText: l10n.labelNewField),
                onSubmitted: (_) => _addTyped(),
              ),
            ),
            IconButton(
              key: const Key("label-new-add"),
              tooltip: l10n.labelNewAdd,
              icon: const Icon(Icons.add),
              onPressed: _addTyped,
            ),
          ],
        ),
      ],
      actions: [
        TextButton(
          key: const Key("label-cancel"),
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.cancel),
        ),
        ElevatedButton(
          key: const Key("label-apply"),
          onPressed: () {
            // A label typed and not yet added is meant to be added.
            _addTyped();
            Navigator.of(context).pop(changes);
          },
          child: Text(l10n.labelApply),
        ),
      ],
    );
  }
}

/// Asks for the new name of [label]; `null` where the dialog was cancelled or
/// the name is the old one or empty.
Future<String?> askLabelRename(BuildContext context, String label) async {
  var answer = await showFormDialog<String>(
    context: context,
    builder: (context) => _LabelRenameDialog(label: label),
  );
  if (answer == null) {
    return null;
  }
  var name = normalizedLabel(answer);
  return name.isEmpty || name == label ? null : name;
}

class _LabelRenameDialog extends StatefulWidget {
  final String label;

  const _LabelRenameDialog({required this.label});

  @override
  State<_LabelRenameDialog> createState() => _LabelRenameDialogState();
}

class _LabelRenameDialogState extends State<_LabelRenameDialog> {
  late final TextEditingController _name =
      TextEditingController(text: widget.label);

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return FormDialogFrame(
      key: const Key("label-rename-dialog"),
      title: Text(l10n.labelRenameTitle(widget.label)),
      fields: [
        TextField(
          key: const Key("label-rename-field"),
          controller: _name,
          autofocus: true,
          decoration: InputDecoration(labelText: l10n.labelRenameField),
          onSubmitted: (value) => Navigator.of(context).pop(value),
        ),
        const SizedBox(height: 8),
        Text(l10n.labelRenameHelp,
            style: Theme.of(context).textTheme.bodySmall),
      ],
      actions: [
        TextButton(
          key: const Key("label-rename-cancel"),
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.cancel),
        ),
        ElevatedButton(
          key: const Key("label-rename-confirm"),
          onPressed: () => Navigator.of(context).pop(_name.text),
          child: Text(l10n.ok),
        ),
      ],
    );
  }
}

/// Asks whether [label] is to be taken off every photograph of the album.
Future<bool> confirmLabelDelete(BuildContext context, String label) async {
  var l10n = AppLocalizations.of(context)!;
  var confirmed = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      key: const Key("label-delete-dialog"),
      title: Text(l10n.labelDeleteTitle(label)),
      content: Text(l10n.labelDeleteExplanation),
      actions: [
        TextButton(
          key: const Key("label-delete-cancel"),
          onPressed: () => Navigator.of(context).pop(false),
          child: Text(l10n.cancel),
        ),
        ElevatedButton(
          key: const Key("label-delete-confirm"),
          onPressed: () => Navigator.of(context).pop(true),
          child: Text(l10n.labelDeleteConfirm),
        ),
      ],
    ),
  );
  return confirmed == true;
}
