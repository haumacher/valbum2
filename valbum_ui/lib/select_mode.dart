/// The select mode of an album, for downloading some of its photographs
/// (issue #209).
///
/// Whoever may download but has no edit mode — a share link made with
/// `download`, a `view` or `contribute` member — had only the whole album and
/// the viewer's single original. The select mode gives them the edit mode's
/// selection and nothing else: entered from the album's menu ("Select
/// photos…") or by a long press on a tile, left by the `×` of its bar or by
/// `Escape`, clicked exactly as the edit mode and the inbox are clicked
/// ([selectionAfterTap], issue #160), the headings' check boxes selecting
/// their section (#158), and one action in its bar: "Download n originals".
/// An editor is never offered it — they have the edit mode, whose selection
/// the album menu's download takes.
///
/// The state lives in `AlbumContentState` (its `selection` is the one the edit
/// mode uses); this file holds what the mode draws.
library;

import 'package:flutter/material.dart';

import 'l10n/app_localizations.dart';

/// The bar of the select mode: the way out, how many are selected, and the
/// download of the selection — nothing else (issue #209).
///
/// [onDownload] is `null` while nothing is selected, which disables the
/// button rather than taking it away, so that the bar does not jump.
PreferredSizeWidget selectModeAppBar({
  required AppLocalizations l10n,
  required int count,
  required VoidCallback onLeave,
  required VoidCallback? onDownload,
}) =>
    AppBar(
      key: const Key("select-mode-bar"),
      automaticallyImplyLeading: false,
      leading: IconButton(
        key: const Key("select-mode-leave"),
        icon: const Icon(Icons.close),
        tooltip: l10n.selectModeLeave,
        onPressed: onLeave,
      ),
      title: Text(
        l10n.selectedCount(count),
        key: const Key("select-mode-count"),
      ),
      actions: [
        Padding(
          padding: const EdgeInsets.only(right: 8),
          child: TextButton.icon(
            key: const Key("select-mode-download"),
            onPressed: onDownload,
            icon: const Icon(Icons.download),
            label: Text(l10n.downloadSelection(count)),
          ),
        ),
      ],
    );

/// A tile of the select mode: the thumbnail, a mark saying whether it is
/// selected, and the gestures of the selection.
///
/// A tap is a click of the edit mode ([onTap] reads the modifier keys); a
/// long press toggles, which is a finger's way to a multiple selection.
class SelectableTile extends StatelessWidget {
  /// Whether the photograph is selected.
  final bool selected;

  /// A click on the tile.
  final VoidCallback onTap;

  /// A long press on the tile.
  final VoidCallback onLongPress;

  /// The thumbnail.
  final Widget child;

  const SelectableTile({
    super.key,
    required this.selected,
    required this.onTap,
    required this.onLongPress,
    required this.child,
  });

  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: onTap,
        onLongPress: onLongPress,
        child: Stack(
          fit: StackFit.expand,
          children: [
            child,
            if (selected)
              IgnorePointer(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: Colors.black26,
                    border: Border.all(color: Colors.amberAccent, width: 3),
                  ),
                ),
              ),
            Positioned(
              top: 4,
              right: 4,
              child: IgnorePointer(
                child: Icon(
                  selected ? Icons.check_circle : Icons.radio_button_unchecked,
                  key: Key(selected ? "select-mark-on" : "select-mark-off"),
                  size: 24,
                  color: selected ? Colors.amberAccent : Colors.white70,
                  shadows: const [Shadow(color: Colors.black, blurRadius: 4)],
                ),
              ),
            ),
          ],
        ),
      );
}
