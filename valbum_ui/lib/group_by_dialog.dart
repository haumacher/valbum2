/// The dialog of "Group by…", issue #238: the keys, what happens to the
/// headings there are, and a preview of the headings before they are
/// applied. The rules themselves are in `group_by.dart`.
library;

import 'package:flutter/material.dart';

import 'album_date.dart';
import 'album_edit.dart';
import 'form_dialog.dart';
import 'group_by.dart';
import 'inbox_view.dart' show inboxDayFormat;
import 'l10n/app_localizations.dart';
import 'resource.dart';

/// Opens the grouping dialog on [parts] and answers the plan to apply,
/// `null` where it was cancelled.
Future<GroupingPlan?> showGroupByDialog({
  required BuildContext context,
  required List<AlbumPart> parts,
  required Set<AlbumPart> selection,
  required int minRating,
  LocalTime localTime = deviceLocalTime,
}) =>
    showFormDialog<GroupingPlan>(
      context: context,
      builder: (context) => GroupByDialog(
        parts: parts,
        selection: selection,
        minRating: minRating,
        localTime: localTime,
      ),
    );

/// The name of [key] in the dialog.
String groupKeyName(AppLocalizations l10n, GroupKey key) => switch (key) {
      GroupKey.day => l10n.groupByKeyDay,
      GroupKey.town => l10n.groupByKeyTown,
      GroupKey.district => l10n.groupByKeyDistrict,
      GroupKey.region => l10n.groupByKeyRegion,
      GroupKey.country => l10n.groupByKeyCountry,
      GroupKey.feature => l10n.groupByKeyFeature,
      GroupKey.dayAndTown => l10n.groupByKeyDayAndTown,
    };

/// The order the keys are offered in.
const List<GroupKey> _offered = [
  GroupKey.day,
  GroupKey.dayAndTown,
  GroupKey.town,
  GroupKey.district,
  GroupKey.feature,
  GroupKey.region,
  GroupKey.country,
];

class GroupByDialog extends StatefulWidget {
  final List<AlbumPart> parts;
  final Set<AlbumPart> selection;
  final int minRating;
  final LocalTime localTime;

  const GroupByDialog({
    super.key,
    required this.parts,
    required this.selection,
    required this.minRating,
    this.localTime = deviceLocalTime,
  });

  @override
  State<GroupByDialog> createState() => GroupByDialogState();
}

class GroupByDialogState extends State<GroupByDialog> {
  /// The scope of the selection; not [GroupScope.selected] where nothing
  /// is selected, and then there is no choice to make.
  late final GroupScope selectionScope = groupScope(
    widget.parts,
    selection: widget.selection,
    minRating: widget.minRating,
  );

  late final GroupScope albumScope =
      groupScope(widget.parts, minRating: widget.minRating);

  /// Whether the selection is grouped rather than the whole album: by
  /// default where it holds two photos or more — the edit mode is entered by
  /// a long press that leaves one photo selected, and grouping that one
  /// alone is never what was meant.
  late bool onSelection = selectionScope.selected && selectionScope.images >= 2;

  GroupScope get scope => onSelection ? selectionScope : albumScope;

  GroupMode mode = GroupMode.replace;

  /// Day › Town, the default of the design.
  GroupKey sectionKey = GroupKey.day;

  GroupKey? subsectionKey = GroupKey.town;

  /// The subsection key of [GroupMode.addSubsections], which needs one.
  GroupKey addedKey = GroupKey.town;

  void setScope(bool selection) {
    onSelection = selection;
    // Nothing to keep where the new scope holds no section.
    if (scope.sections == 0) {
      mode = GroupMode.replace;
    }
  }

  GroupingPlan plan(AppLocalizations l10n) {
    var format = inboxDayFormat(l10n);
    return planGrouping(
      widget.parts,
      mode: mode,
      sectionKey: sectionKey,
      subsectionKey: mode == GroupMode.replace ? subsectionKey : addedKey,
      selection: onSelection ? widget.selection : const {},
      minRating: widget.minRating,
      dayText: format.format,
      localTime: widget.localTime,
    );
  }

  Widget keyChoice<T>({
    required Key key,
    required String label,
    required T value,
    required List<(T, String)> choices,
    required void Function(T) onChanged,
  }) =>
      Padding(
        padding: const EdgeInsets.only(top: 12),
        child: DropdownButtonFormField<T>(
          key: key,
          initialValue: value,
          isExpanded: true,
          decoration: InputDecoration(labelText: label),
          items: [
            for (var (choice, text) in choices)
              DropdownMenuItem<T>(
                key: Key("${(key as ValueKey).value}-"
                    "${choice is GroupKey ? choice.name : "none"}"),
                value: choice,
                child: Text(text),
              ),
          ],
          onChanged: (choice) => setState(() => onChanged(choice as T)),
        ),
      );

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var theme = Theme.of(context);
    var result = plan(l10n);
    var keys = [for (var key in _offered) (key, groupKeyName(l10n, key))];
    Widget choice(GroupMode value, String label) => ChoiceChip(
          key: Key("group-by-mode-${value.name}"),
          label: Text(label),
          selected: mode == value,
          onSelected: (_) => setState(() => mode = value),
        );

    return FormDialogFrame(
      key: const Key("group-by-dialog"),
      title: Text(l10n.groupByTitle),
      fields: [
        const SizedBox(height: 8),
        if (selectionScope.selected)
          Wrap(
            key: const Key("group-by-scope"),
            spacing: 8,
            runSpacing: 8,
            children: [
              ChoiceChip(
                key: const Key("group-by-scope-selection"),
                label: Text(
                    l10n.groupByScopeChoiceSelection(selectionScope.images)),
                selected: onSelection,
                onSelected: (_) => setState(() => setScope(true)),
              ),
              ChoiceChip(
                key: const Key("group-by-scope-album"),
                label: Text(l10n.groupByScopeChoiceAlbum),
                selected: !onSelection,
                onSelected: (_) => setState(() => setScope(false)),
              ),
            ],
          )
        else
          Text(l10n.groupByScopeAlbum, key: const Key("group-by-scope")),
        // Only where there are sections to keep (issue #238).
        if (scope.sections > 0)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                choice(GroupMode.replace, l10n.groupByModeReplace),
                choice(
                  GroupMode.addSubsections,
                  l10n.groupByModeAddSubsections,
                ),
              ],
            ),
          ),
        if (mode == GroupMode.replace) ...[
          keyChoice<GroupKey>(
            key: const ValueKey("group-by-section"),
            label: l10n.groupBySectionKey,
            value: sectionKey,
            choices: keys,
            onChanged: (key) => sectionKey = key,
          ),
          keyChoice<GroupKey?>(
            key: const ValueKey("group-by-subsection"),
            label: l10n.groupBySubsectionKey,
            value: subsectionKey,
            choices: [(null, l10n.groupByKeyNone), ...keys],
            onChanged: (key) => subsectionKey = key,
          ),
        ] else
          keyChoice<GroupKey>(
            key: const ValueKey("group-by-added"),
            label: l10n.groupBySubsectionKey,
            value: addedKey,
            choices: keys,
            onChanged: (key) => addedKey = key,
          ),
        const SizedBox(height: 16),
        if (result.isEmpty)
          Text(
            l10n.groupByNothingKeyed,
            key: const Key("group-by-nothing"),
            style: TextStyle(color: theme.colorScheme.error),
          )
        else ...[
          if (result.replaced > 0)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(
                l10n.groupByWillReplace(result.replaced),
                key: const Key("group-by-replaced"),
                style: TextStyle(color: theme.colorScheme.error),
              ),
            ),
          Text(l10n.groupByPreview, style: theme.textTheme.titleSmall),
          for (var (index, heading) in result.headings.indexed)
            Padding(
              key: Key("group-by-preview-$index"),
              padding: EdgeInsets.only(
                top: 4,
                left: heading.level == headingSubsection ? 24 : 0,
              ),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      heading.text,
                      style: heading.level == headingSection
                          ? const TextStyle(fontWeight: FontWeight.bold)
                          : null,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Text(l10n.photoCount(heading.count)),
                ],
              ),
            ),
        ],
      ],
      actions: [
        TextButton(
          key: const Key("group-by-cancel"),
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.cancel),
        ),
        ElevatedButton.icon(
          key: const Key("group-by-apply"),
          icon: const Icon(Icons.check),
          label: Text(l10n.apply),
          onPressed:
              result.isEmpty ? null : () => Navigator.of(context).pop(result),
        ),
      ],
    );
  }
}
