/// The album view: the row layout of the images, the rating filter, the edit
/// mode with the per-tile editor and the album properties editor.
library;

import 'dart:math';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:valbum_ui/album_layout.dart' as layouter;

import 'album_edit.dart';
import 'album_model.dart';
import 'app.dart';
import 'camera_roll_view.dart';
import 'client.dart';
import 'resource.dart';
import 'offline.dart';
import 'settings.dart';
import 'thumbnails.dart';

class AlbumContent extends StatefulWidget {
  final VAlbumState albumState;
  final AlbumInfo album;
  final String baseUrl;
  final Future Function(AbstractImage image, String name) pushPart;

  const AlbumContent(
    this.albumState,
    this.album,
    this.baseUrl,
    this.pushPart, {
    super.key,
  });

  @override
  State<StatefulWidget> createState() {
    return AlbumContentState();
  }
}

class AlbumContentState extends State<AlbumContent> {
  /// The edit in progress: the mode, the selection and the click anchor.
  ///
  /// It lives with the router, not with this widget, so that a trip into an
  /// image or into the alternatives of a group and back finds the album still
  /// being edited, see [AlbumEditSession].
  AlbumEditSession get session =>
      widget.albumState.navigator.delegate.editSession(widget.albumState.path);

  bool get editMode => session.editMode;
  set editMode(bool value) => session.editMode = value;

  /// The selected album parts (an [ImageGroup] is selected as a whole).
  Set<AlbumPart> get selection => session.selection;

  /// The part clicked last, the anchor of a shift-click range selection.
  AlbumPart? get lastClicked => session.lastClicked;
  set lastClicked(AlbumPart? value) => session.lastClicked = value;

  /// The orientation each image had when the album was laid out.
  ///
  /// The row layout is computed from these, not from the (possibly just
  /// rotated) orientation of the model: rotating an image in the tile editor
  /// must not reflow the album under the user's hands. The rotated image is
  /// scaled into the tile box it already has; the layout follows on the next
  /// load of the album. This is the `setDownScale` of the retired GWT client.
  final Map<ImagePart, Orientation> _layoutOrientation = {};

  /// The rating an image needs to be shown, see [AlbumInfo.minRating].
  int get minRating => widget.album.minRating;

  @override
  void didUpdateWidget(AlbumContent oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.album, widget.album)) {
      _layoutOrientation.clear();
      selection.clear();
      lastClicked = null;
    }
  }

  /// The orientation the given image is laid out with, see
  /// [_layoutOrientation].
  Orientation layoutOrientation(ImagePart image) =>
      _layoutOrientation.putIfAbsent(image, () => image.orientation);

  /// Enters the edit mode with the given part selected.
  ///
  /// Refused while the app is offline: an edit that could never be saved is
  /// worse than no edit at all, so the reason is said instead, see
  /// [refuseWhileOffline].
  void setEditMode(AlbumPart selected) {
    if (refuseWhileOffline(context)) {
      return;
    }
    setState(() {
      editMode = true;
      selection
        ..clear()
        ..add(selected);
      lastClicked = selected;
    });
  }

  bool isSelected(AlbumPart part) => selection.contains(part);

  bool get hasMultiSelection => selection.length > 1;

  /// Handles a click on the tile of the given part.
  ///
  /// Plain: select exactly this part, clicking the only selected one clears
  /// the selection. Ctrl/Meta: toggle. Shift: extend the selection to the
  /// range between the part clicked last and this one, as the GWT client did.
  void handleTap(AlbumPart part) {
    var keyboard = HardwareKeyboard.instance;
    setState(() {
      if (keyboard.isShiftPressed) {
        var anchor = lastClicked ?? widget.album.parts.first;
        var select = selection.contains(anchor);
        for (var image in imageRange(widget.album.parts, anchor, part)) {
          if (select) {
            selection.add(image);
          } else {
            selection.remove(image);
          }
        }
      } else if (keyboard.isControlPressed || keyboard.isMetaPressed) {
        _toggle(part);
      } else {
        var wasOnlySelection = selection.length == 1 && isSelected(part);
        selection.clear();
        if (!wasOnlySelection) {
          selection.add(part);
        }
      }
      lastClicked = part;
    });
  }

  /// Adds or removes the given part from the selection.
  void toggleSelection(AlbumPart part) => setState(() {
        _toggle(part);
        lastClicked = part;
      });

  void _toggle(AlbumPart part) {
    if (!selection.remove(part)) {
      selection.add(part);
    }
  }

  void clearSelection() {
    selection.clear();
    lastClicked = null;
  }

  /// Applies [edit] to the model and redraws the album.
  void editImage(VoidCallback edit) => setState(edit);

  /// Groups the selected parts, [representative] becoming the image shown for
  /// the group in the album, see [groupSelection].
  ///
  /// Returns whether the group was created.
  bool groupSelected(AbstractImage representative) {
    var group = groupSelection(widget.album, selection, representative);
    if (group == null) {
      return false;
    }
    setState(clearSelection);
    return true;
  }

  /// Dissolves the given group, its images stay selected, see [ungroup].
  void ungroupSelected(ImageGroup group) => setState(() {
        var members = ungroup(widget.album, group);
        clearSelection();
        selection.addAll(members);
        lastClicked = members.isEmpty ? null : members.last;
      });

  /// Removes the given heading from the album.
  void deleteHeading(Heading heading) => setState(() {
        widget.album.parts =
            widget.album.parts.where((p) => !identical(p, heading)).toList();
        selection.remove(heading);
      });

  /// Opens the heading editor prefilled with the current text.
  ///
  /// An empty text is refused, as in the GWT client, which ignored the save.
  Future<void> editHeading(Heading heading) async {
    var text = await showDialog<String>(
      context: context,
      builder: (context) => TextInputDialog(
        title: "Überschrift bearbeiten",
        label: "Überschrift",
        text: heading.text,
      ),
    );
    if (text == null || text.trim().isEmpty || !mounted) {
      return;
    }
    setState(() => heading.text = text);
  }

  /// Shows one rating level more (the `+` key of the GWT client).
  void showMore() =>
      setState(() => widget.album.minRating = showMoreRating(minRating));

  /// Shows one rating level less (the `-` key of the GWT client).
  void showLess() =>
      setState(() => widget.album.minRating = showLessRating(minRating));

  /// Writes the album back to the server and leaves the edit mode.
  ///
  /// The album is stored as the `index.json` sidecar of its own folder; the
  /// server keeps the previous sidecar as a backup. On success the album is
  /// re-fetched, so that the transient links between its parts are rebuilt
  /// from the state the server now has. A failed write keeps the edit mode
  /// open and reports the HTTP status.
  Future<void> save() async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var messenger = ScaffoldMessenger.of(context);

    try {
      await client.saveAlbum(widget.albumState.path, widget.album);
    } catch (error) {
      messenger.showSnackBar(
        SnackBar(
          content: Text("Speichern fehlgeschlagen: $error"),
          backgroundColor: Colors.red.shade700,
          duration: const Duration(seconds: 8),
        ),
      );
      // Stay in edit mode, the changes are not persisted yet.
      return;
    }

    if (!mounted) {
      return;
    }

    setState(() {
      editMode = false;
      clearSelection();
      // The album is laid out anew from what was saved.
      _layoutOrientation.clear();
    });

    // Re-load the album, so that the transient part links are rebuilt. The
    // listing above shows the album by its index picture, which may have
    // just been chosen: it is fetched anew on the way up.
    var path = widget.albumState.path;
    if (path.isNotEmpty) {
      widget.albumState.navigator.delegate
          .forget(path.sublist(0, path.length - 1));
    }
    widget.albumState.reload();
  }

  /// Opens the album properties editor and applies its result to the album.
  Future<void> editProperties() async {
    var album = widget.album;
    var result = await showDialog<AlbumProperties>(
      context: context,
      builder: (context) => AlbumPropertiesDialog(
        AlbumProperties(title: album.title, subTitle: album.subTitle),
      ),
    );

    if (result == null || !mounted) {
      return;
    }

    setState(() {
      album.title = result.title;
      album.subTitle = result.subTitle;
    });
  }

  String get albumUrl => "${widget.baseUrl}/${widget.album.path}";

  /// The transport to the album server.
  VAlbumClient get client => widget.albumState.client;

  @override
  Widget build(BuildContext context) {
    var self = widget.album;

    // The album shows its photos, not its chrome: with something to show and
    // nothing being edited there is no app bar, only the floating controls over
    // the photos, see [contentView].
    var immersive = !editMode && self.parts.isNotEmpty;

    return Scaffold(
      appBar: immersive
          ? null
          : AppBar(
              title: Column(
                children: [
                  Text(self.title),
                  if (self.subTitle.isNotEmpty) Text(self.subTitle),
                ],
              ),
              centerTitle: true,
              actions: [
                ...navigationActions(context),
                if (editMode)
                  IconButton(
                    onPressed: editProperties,
                    tooltip: "Album properties",
                    icon: const Icon(Icons.tune),
                  ),
                if (editMode)
                  IconButton(
                    onPressed: save,
                    tooltip: "Save",
                    icon: const Icon(Icons.save),
                  ),
              ],
            ),
      backgroundColor: Colors.black,
      body: Column(
        children: [
          // Says plainly when the album below is the copy from the cache.
          OfflineBanner(onRetry: widget.albumState.reload),
          if (self.parts.isNotEmpty) Expanded(child: contentView(self)),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: widget.albumState.uploadImages,
        tooltip: 'Upload',
        child: const Icon(Icons.cloud_upload),
      ), // This trailing comma makes auto-formatting nicer for build methods.
    );
  }

  /// The controls of the album: the way up and the album's menu.
  ///
  /// The same controls wherever the album shows them: in the app bar of the
  /// edit mode and of an empty album, and floating over the photos otherwise,
  /// see [contentView]. An album without them is a dead end — the browser
  /// back button aside, there was no way back to the index, see issue #35.
  /// There is no home button: the index is one or more steps up, and the
  /// listing offers the home from there.
  List<Widget> navigationActions(BuildContext context) => [
        ...wayUp(),
        ...albumMenu(context),
      ];

  /// The way out of the album, nothing at the root.
  List<Widget> wayUp() => [
        if (widget.albumState.path.isNotEmpty)
          IconButton(
            icon: const Icon(Icons.arrow_back),
            tooltip: "Up",
            onPressed: widget.albumState.showParent,
          ),
      ];

  /// The album's menu, with the rating filter in it.
  ///
  /// The `+` and `-` keys stay the shortcut of the filter, see [onKey]; the
  /// menu is the touch equivalent, telling the current threshold and offering
  /// the two steps, each disabled at its GWT bound.
  List<Widget> albumMenu(BuildContext context) => [
        // Unobtrusive while a camera-roll sync runs, nothing otherwise.
        const CameraRollIndicator(),
        menu(context, [
          menuLabel(
            "Mindestbewertung",
            "≥ $minRating",
            valueKey: const Key("minRating"),
          ),
          menuItem(
            Icons.add_circle_outline,
            "Mehr Bilder zeigen",
            (_) => showMore(),
            enabled: minRating > minMinRating,
          ),
          menuItem(
            Icons.remove_circle_outline,
            "Weniger Bilder zeigen",
            (_) => showLess(),
            enabled: minRating < maxMinRating,
          ),
          const PopupMenuDivider(),
          menuItem(Icons.update, "Reload", (_) => widget.albumState.reload()),
          menuItem(Icons.settings, "Server...", openServerSettings),
        ]),
      ];

  /// A floating control over the photos: dark and translucent, so that the
  /// album stays what it is about while never losing its way back.
  Widget floating(List<Widget> children) => DecoratedBox(
        decoration: BoxDecoration(
          color: Colors.black54,
          borderRadius: BorderRadius.circular(16),
        ),
        child: IconTheme.merge(
          data: const IconThemeData(color: Colors.white, size: 20),
          child: Row(mainAxisSize: MainAxisSize.min, children: children),
        ),
      );

  Widget contentView(AlbumInfo self) {
    var shown = visibleParts(self);
    var hidesEverything =
        self.parts.isNotEmpty && !shown.any((part) => part is AbstractImage);

    return Focus(
      autofocus: true,
      onKeyEvent: onKey,
      child: Stack(
        children: [
          LayoutBuilder(
            builder: (BuildContext context, BoxConstraints constraints) {
              return SingleChildScrollView(
                scrollDirection: Axis.vertical,
                // The full width, whatever the content: a `Column` shrinks to
                // its widest child, so an album whose images the rating filter
                // all hides used to collapse its title into the top left
                // corner, under the filter bar, see issue #35.
                child: SizedBox(
                  width: constraints.maxWidth,
                  child: Column(
                    children: [
                      if (!editMode)
                        Padding(
                          // Between the floating up button and the menu,
                          // in the row they float in: the side padding keeps
                          // a long title from running underneath them, the
                          // top padding centres a single line on them.
                          padding: const EdgeInsets.fromLTRB(64, 12, 64, 4),
                          child: Text(
                            self.title,
                            textAlign: TextAlign.center,
                            style: const TextStyle(
                              fontSize: 28,
                              color: Colors.white,
                            ),
                          ),
                        ),
                      if (!editMode)
                        if (self.subTitle.isNotEmpty)
                          Padding(
                            padding: const EdgeInsets.fromLTRB(16, 0, 16, 4),
                            child: Text(
                              self.subTitle,
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                fontSize: 16,
                                color: Colors.white,
                              ),
                            ),
                          ),
                      // A filter that hides everything says so: an empty black
                      // page would look like an empty album.
                      if (hidesEverything)
                        Padding(
                          padding: const EdgeInsets.fromLTRB(16, 24, 16, 24),
                          child: Text(
                            "No image is rated $minRating or better - "
                            "press + (or the + button) to show more.",
                            textAlign: TextAlign.center,
                            style: const TextStyle(
                              fontSize: 16,
                              color: Colors.white70,
                            ),
                          ),
                        ),
                      ...buildParts(self, constraints.maxWidth),
                    ],
                  ),
                ),
              );
            },
          ),
          if (!editMode && widget.albumState.path.isNotEmpty)
            Positioned(top: 8, left: 8, child: floating(wayUp())),
          if (!editMode)
            Positioned(top: 8, right: 8, child: floating(albumMenu(context))),
        ],
      ),
    );
  }

  /// The `+` and `-` keys of the GWT client, widening and narrowing the rating
  /// filter.
  KeyEventResult onKey(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) {
      return KeyEventResult.ignored;
    }
    var key = event.logicalKey;
    if (event.character == "+" ||
        key == LogicalKeyboardKey.add ||
        key == LogicalKeyboardKey.numpadAdd) {
      showMore();
      return KeyEventResult.handled;
    }
    if (event.character == "-" ||
        key == LogicalKeyboardKey.minus ||
        key == LogicalKeyboardKey.numpadSubtract) {
      showLess();
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  /// Renders the album parts: each run of images is laid out as a block of
  /// rows, headings separate those blocks.
  ///
  /// Images whose rating is below [minRating] are left out, headings are
  /// always shown.
  List<Widget> buildParts(AlbumInfo self, double maxWidth) {
    var result = <Widget>[];
    var images = <AbstractImage>[];

    void flushImages() {
      if (images.isEmpty) {
        return;
      }
      var pending = images;
      var layout = withLayoutOrientations(
        pending,
        () => layouter.AlbumLayout(maxWidth, 250, pending),
      );
      var builder = ContentWidgetBuilder(imageTile, layout.getPageWidth());
      result.addAll(builder.buildRows(layout));
      images = <AbstractImage>[];
    }

    for (var part in visibleParts(self)) {
      if (part is AbstractImage) {
        images.add(part);
      } else if (part is Heading) {
        flushImages();
        result.add(headingView(part));
      }
    }
    flushImages();

    return result;
  }

  /// The tile of the given image, with the tile editor in the edit mode.
  Widget imageTile(AbstractImage image, double width, double height) =>
      image.visitAbstractImage(ImageWidgetBuilder(this, width, height), null);

  /// Runs [body] with the images set to the orientation they are laid out
  /// with, see [_layoutOrientation].
  ///
  /// The layout reads [ImagePart.orientation] from the model to decide whether
  /// width and height are swapped. While an image is being rotated, the model
  /// is already ahead of the layout, so the value is swapped in for the
  /// duration of the layout computation.
  T withLayoutOrientations<T>(List<AbstractImage> images, T Function() body) {
    var representatives = images.map(layouter.ToImage.toImage).toList();
    var current = [for (var image in representatives) image.orientation];
    for (var image in representatives) {
      image.orientation = layoutOrientation(image);
    }
    try {
      return body();
    } finally {
      for (var i = 0; i < representatives.length; i++) {
        representatives[i].orientation = current[i];
      }
    }
  }

  /// The heading between two blocks of images.
  ///
  /// In the edit mode it carries the two inline tools of the GWT
  /// `HeadingDisplay`: edit the text and delete the heading.
  Widget headingView(Heading heading) => Padding(
        padding: const EdgeInsets.only(top: 24, bottom: 8),
        child: Row(
          key: ValueKey(heading),
          mainAxisSize: MainAxisSize.min,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              heading.text,
              style: const TextStyle(fontSize: 22, color: Colors.white),
            ),
            if (editMode)
              IconButton(
                icon: const Icon(Icons.edit),
                iconSize: 20,
                color: Colors.white,
                tooltip: "Überschrift bearbeiten",
                onPressed: () => editHeading(heading),
              ),
            if (editMode)
              IconButton(
                icon: const Icon(Icons.delete_outline),
                iconSize: 20,
                color: Colors.white,
                tooltip: "Überschrift löschen",
                onPressed: () => deleteHeading(heading),
              ),
          ],
        ),
      );
}

/// Builds the tile of one image, given the box the layout assigned to it.
typedef TileBuilder = Widget Function(
  AbstractImage image,
  double width,
  double height,
);

/// The gap between two neighbouring tiles of the album, in logical pixels.
///
/// The retired GWT client separated the images by 2px, which is what makes a
/// row of photos read as separate pictures instead of one collage, see issue
/// #34. The gap is applied when the layout is turned into widgets, not in the
/// layout algorithm itself: the algorithm decides which images share a row,
/// and that decision must not depend on the decoration.
const double tileSpacing = 2.0;

/// The box a [layouter.Row] hands to each of its contents.
///
/// A content is as wide as its unit width times [scale] and as high as
/// [height]. The two are the same number for a row filling the page, but not
/// inside a [layouter.DoubleRow], whose two halves have to give up
/// [tileSpacing] of their height to the gap between them while still filling
/// the width of the double row.
class RowBox {
  /// The factor a content's unit width is multiplied by to get its width.
  final double scale;

  /// The height of the row.
  final double height;

  const RowBox(this.scale, this.height);

  /// The box of a top-level row, whose geometry follows from the page width.
  static const RowBox page = RowBox(0, 0);
}

/// Turns the rows of an [layouter.AlbumLayout] into widgets.
///
/// The tiles themselves are built by the [TileBuilder] handed in: the album
/// view builds an [ImageWidgetBuilder] tile (with the edit mode on top), the
/// alternatives view of a group (`group_view.dart`) a plain thumbnail.
class ContentWidgetBuilder implements layouter.ContentVisitor<Widget, RowBox> {
  final TileBuilder tile;
  final double pageWidth;

  const ContentWidgetBuilder(this.tile, this.pageWidth);

  /// The rows of [layout] as widgets, separated by [tileSpacing].
  List<Widget> buildRows(layouter.AlbumLayout layout) => [
        for (var (index, row) in layout.getRows().indexed) ...[
          if (index > 0) const SizedBox(height: tileSpacing),
          row.visit(this, RowBox.page),
        ],
      ];

  @override
  Widget visitImg(layouter.Img content, RowBox box) {
    var image = content.getImage();

    var width = content.getUnitWidth() * box.scale;
    var height = box.height;

    return tile(image, width, height);
  }

  @override
  Widget visitRow(layouter.Row content, RowBox box) {
    // The gaps are taken out of the page first, so that the tiles still fill
    // the width exactly and keep their aspect ratio.
    var gaps = (content.size() - 1) * tileSpacing;
    var scale = max(0.0, pageWidth - gaps) / content.getUnitWidth();
    // A row of a double row is told its height, see [visitDoubleRow]; a
    // top-level row derives it from the page width.
    var contentBox = RowBox(scale, box.height > 0 ? box.height : scale);

    return Row(
      children: [
        for (var (index, child) in content.indexed) ...[
          if (index > 0) const SizedBox(width: tileSpacing),
          child.visit(this, contentBox),
        ],
      ],
    );
  }

  @override
  Widget visitDoubleRow(layouter.DoubleRow content, RowBox box) {
    var width = content.getUnitWidth() * box.scale;
    var height = box.height;

    // The double row keeps the height of the row it sits in: the gap between
    // its halves comes out of their heights, which keep their ratio.
    var inner = max(0.0, height - tileSpacing);
    var contentBuilder = ContentWidgetBuilder(tile, width);
    var upperRow = content.getUpper().visit(
          contentBuilder,
          RowBox(0, inner * content.getH1()),
        );
    var lowerRow = content.getLower().visit(
          contentBuilder,
          RowBox(0, inner * content.getH2()),
        );

    return SizedBox(
      width: width,
      height: height,
      child: Column(
        children: [
          upperRow,
          const SizedBox(height: tileSpacing),
          lowerRow,
        ],
      ),
    );
  }

  @override
  Widget visitPadding(layouter.Padding content, RowBox box) {
    var width = content.getUnitWidth() * box.scale;
    var height = box.height;

    return SizedBox(width: width, height: height);
  }
}

class ImageWidgetBuilder implements AbstractImageVisitor<Widget, void> {
  final AlbumContentState state;
  final double width, height;

  const ImageWidgetBuilder(this.state, this.width, this.height);

  @override
  Widget visitImageGroup(ImageGroup self, void arg) {
    return thumbnailView(self.images[self.representative], self);
  }

  @override
  Widget visitImagePart(ImagePart self, void arg) {
    return thumbnailView(self, self);
  }

  /// The tile for [image], the image representing the album part [part].
  ///
  /// A tap opens the viewer on [part], not on [image]: for a group that is the
  /// group itself, whose previous/next are the album's — the representative's
  /// own links stay inside the group (see [AlbumInitializer]).
  Widget thumbnailView(ImagePart image, AbstractImage part) {
    if (state.editMode) {
      return ThumbnailEditor(
        state,
        this,
        image,
        part,
        key: ValueKey(image.name),
      );
    } else {
      return GestureDetector(
        onTap: () => state.widget.pushPart(part, image.name),
        onLongPress: () => state.setEditMode(part),
        child: imageThumbnail(image),
      );
    }
  }

  /// The thumbnail of the given image, filling the tile box.
  ///
  /// The server bakes the orientation of the image file into the thumbnail it
  /// serves. An orientation changed in the tile editor is therefore applied
  /// here as the delta to the orientation the tile was laid out with, and the
  /// re-oriented image is scaled down into the tile box it already occupies,
  /// so that rotating does not reflow the album.
  Widget imageThumbnail(ImagePart image) {
    var thumbnail = orientedThumbnail(image);
    if (image.kind == ImageKind.image) {
      return thumbnail;
    }
    // A video tile carries the play mark the old client showed.
    return SizedBox(
      width: width,
      height: height,
      child: Stack(
        fit: StackFit.expand,
        children: [
          thumbnail,
          const Center(
            child: Icon(
              Icons.play_circle_outline,
              key: Key("video-indicator"),
              size: 48,
              color: Colors.white70,
              shadows: [Shadow(color: Colors.black54, blurRadius: 8)],
            ),
          ),
        ],
      ),
    );
  }

  Widget orientedThumbnail(ImagePart image) {
    Widget result = thumbnail(
      state.client,
      "${state.albumUrl}${image.thumbnailName}",
      width: width,
      height: height,
      fit: BoxFit.contain,
    );

    var delta = OrientationOps.delta(
      state.layoutOrientation(image),
      image.orientation,
    );
    if (delta == PlaneTransform.identity) {
      return result;
    }

    if (delta.mirrored) {
      result = Transform.scale(scaleX: -1, scaleY: 1, child: result);
    }
    // [PlaneTransform.quarterTurns] counts counter-clockwise, [RotatedBox]
    // clockwise.
    var clockwise = (4 - delta.quarterTurns % 4) % 4;
    if (clockwise != 0) {
      result = RotatedBox(quarterTurns: clockwise, child: result);
    }

    return SizedBox(
      width: width,
      height: height,
      child: FittedBox(fit: BoxFit.contain, child: result),
    );
  }
}

/// The tile of an image in the album edit mode: selection and the three
/// overlay toolbars.
class ThumbnailEditor extends StatefulWidget {
  final AlbumContentState state;
  final ImageWidgetBuilder builder;

  /// The image shown, the representative if [part] is an [ImageGroup].
  final ImagePart image;

  /// The album part this tile stands for.
  final AlbumPart part;

  const ThumbnailEditor(
    this.state,
    this.builder,
    this.image,
    this.part, {
    super.key,
  });

  @override
  State<StatefulWidget> createState() => ThumbnailEditorState();
}

class ThumbnailEditorState extends State<ThumbnailEditor> {
  bool _hovered = false;

  AlbumContentState get album => widget.state;
  ImagePart get image => widget.image;
  AlbumPart get part => widget.part;

  bool get selected => album.isSelected(part);
  bool get multiSelected => selected && album.hasMultiSelection;

  /// Whether the tile shows its toolbars.
  bool get active => selected || _hovered;

  @override
  Widget build(BuildContext context) {
    var width = widget.builder.width;
    var height = widget.builder.height;

    return MouseRegion(
      hitTestBehavior: HitTestBehavior.translucent,
      opaque: false,
      onEnter: (event) => setState(() => _hovered = true),
      onExit: (event) => setState(() => _hovered = false),
      child: SizedBox(
        width: width,
        height: height,
        child: Stack(
          children: [
            Positioned.fill(
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () => album.handleTap(part),
                onLongPress: () => album.toggleSelection(part),
                child: widget.builder.imageThumbnail(image),
              ),
            ),
            if (isIndexPicture(album.widget.album, image))
              const Positioned(
                right: 4,
                bottom: 4,
                child: IgnorePointer(
                  child: Tooltip(
                    message: "Albumbild",
                    child: Icon(
                      Icons.photo_album,
                      key: Key("album-index-picture"),
                      color: Colors.amberAccent,
                      shadows: [Shadow(color: Colors.black, blurRadius: 4)],
                    ),
                  ),
                ),
              ),
            if (selected)
              Positioned.fill(
                child: IgnorePointer(
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      border: Border.all(color: Colors.blueAccent, width: 3),
                    ),
                  ),
                ),
              ),
            if (active) Positioned(top: 0, left: 0, right: 0, child: topBar()),
            if (active) Positioned.fill(child: Center(child: centerBar())),
            if (active)
              Positioned(bottom: 0, left: 0, right: 0, child: bottomBar()),
          ],
        ),
      ),
    );
  }

  /// The rotation tools, next to the selection mark.
  Widget topBar() => toolbar([
        toolButton(
          selected ? Icons.check_box : Icons.check_box_outline_blank,
          "Auswählen",
          () => album.toggleSelection(part),
          active: selected,
        ),
        toolButton(Icons.rotate_right, "Nach rechts drehen", rotateRight),
        toolButton(Icons.swap_vert, "Vertikal spiegeln", flipVertically),
        toolButton(Icons.rotate_left, "Nach links drehen", rotateLeft),
      ]);

  /// The tools acting on the selection.
  Widget? centerBar() {
    if (!selected) {
      return null;
    }
    var self = part;
    return toolbar([
      if (multiSelected)
        toolButton(Icons.join_left, "Gruppieren", createGroup)
      else ...[
        toolButton(Icons.title, "Überschrift einfügen", createHeading),
        if (self is ImageGroup) ...[
          toolButton(
            Icons.call_split,
            "Gruppierung aufheben",
            () => album.ungroupSelected(self),
          ),
          // Into the alternatives to pick the representative: the edit mode
          // stays on for the way back, see [AlbumEditSession].
          toolButton(
            Icons.collections,
            "Gruppenbild wählen",
            () => album.widget.albumState.showGroupView(self),
          ),
        ],
      ],
      toolButton(Icons.notes, "Bildeigenschaften", editImageProperties),
      // The image standing for the album in the listing above, chosen where
      // the images are compared: the representative of a group stands for it.
      toolButton(
        Icons.photo_album,
        "Als Albumbild verwenden",
        setIndexPicture,
        active: isIndexPicture(album.widget.album, image),
      ),
    ]);
  }

  /// The rating chooser.
  Widget bottomBar() => toolbar([
        ratingButton(Icons.star, "Sehr gut", 2),
        ratingButton(Icons.add, "Gut", 1),
        ratingButton(Icons.remove, "Schlecht", -1),
        ratingButton(Icons.delete, "Papierkorb", -2),
      ]);

  Widget ratingButton(IconData icon, String tooltip, int value) => toolButton(
        icon,
        tooltip,
        () => setRating(value),
        active: isActiveRating(image.rating, value),
      );

  Widget toolbar(List<Widget> buttons) => FittedBox(
        fit: BoxFit.scaleDown,
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: Colors.black54,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Row(mainAxisSize: MainAxisSize.min, children: buttons),
        ),
      );

  Widget toolButton(
    IconData icon,
    String tooltip,
    VoidCallback onPressed, {
    bool active = false,
  }) =>
      IconButton(
        icon: Icon(icon),
        iconSize: 18,
        color: active ? Colors.amberAccent : Colors.white,
        tooltip: tooltip,
        padding: const EdgeInsets.all(4),
        visualDensity: VisualDensity.compact,
        constraints: const BoxConstraints(minWidth: 28, minHeight: 28),
        onPressed: onPressed,
      );

  void rotateLeft() =>
      album.editImage(() => image.orientation = OrientationOps.rotL(
            image.orientation,
          ));

  void rotateRight() =>
      album.editImage(() => image.orientation = OrientationOps.rotR(
            image.orientation,
          ));

  void flipVertically() =>
      album.editImage(() => image.orientation = OrientationOps.flipV(
            image.orientation,
          ));

  void setRating(int value) => album.editImage(
        () => image.rating = toggleRating(image.rating, value),
      );

  /// Makes this tile's image the album's index picture, see [indexPictureOf].
  void setIndexPicture() => album.editImage(
        () => album.widget.album.indexPicture = indexPictureOf(image),
      );

  /// Groups the selected images, this tile's image representing the group.
  void createGroup() {
    var self = part;
    if (self is! AbstractImage || !album.groupSelected(self)) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text("Zum Gruppieren mindestens zwei Bilder auswählen"),
          duration: Duration(seconds: 4),
        ),
      );
    }
  }

  /// Inserts a heading before this tile's part.
  Future<void> createHeading() async {
    var text = await showDialog<String>(
      context: context,
      builder: (context) => const TextInputDialog(
        title: "Überschrift einfügen",
        label: "Überschrift",
        text: "",
      ),
    );
    if (text == null || !mounted) {
      return;
    }
    album.editImage(() => insertHeadingBefore(album.widget.album, part, text));
  }

  /// Edits the comment of the image shown by this tile.
  Future<void> editImageProperties() async {
    var text = await showDialog<String>(
      context: context,
      builder: (context) => TextInputDialog(
        title: "Bildeigenschaften",
        label: "Kommentar",
        text: image.comment,
        multiLine: true,
      ),
    );
    if (text == null || !mounted) {
      return;
    }
    album.editImage(() => image.comment = text);
  }
}

/// A dialog editing a single text, used for headings and image comments.
class TextInputDialog extends StatefulWidget {
  final String title;
  final String label;
  final String text;
  final bool multiLine;

  const TextInputDialog({
    super.key,
    required this.title,
    required this.label,
    required this.text,
    this.multiLine = false,
  });

  @override
  State<StatefulWidget> createState() => TextInputDialogState();
}

class TextInputDialogState extends State<TextInputDialog> {
  late final TextEditingController controller =
      TextEditingController(text: widget.text);

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            DefaultTextStyle(
              style: DialogTheme.of(context).titleTextStyle ??
                  Theme.of(context).textTheme.titleLarge!,
              child: Semantics(
                namesRoute: Theme.of(context).platform != TargetPlatform.iOS,
                container: true,
                child: Text(widget.title),
              ),
            ),
            TextField(
              controller: controller,
              autofocus: true,
              minLines: widget.multiLine ? 3 : 1,
              maxLines: widget.multiLine ? 8 : 1,
              decoration: InputDecoration(label: Text(widget.label)),
            ),
            Padding(
              padding: const EdgeInsets.only(top: 16),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text("Abbrechen"),
                  ),
                  Padding(
                    padding: const EdgeInsets.only(left: 8),
                    child: ElevatedButton.icon(
                      icon: const Icon(Icons.check),
                      label: const Text("Übernehmen"),
                      onPressed: () =>
                          Navigator.of(context).pop(controller.text),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The values edited by the [AlbumPropertiesDialog].
class AlbumProperties {
  final String title;
  final String subTitle;

  const AlbumProperties({required this.title, required this.subTitle});
}

/// Edits the title and the subtitle of an album.
class AlbumPropertiesDialog extends StatefulWidget {
  final AlbumProperties properties;

  const AlbumPropertiesDialog(this.properties, {super.key});

  @override
  State<StatefulWidget> createState() => AlbumPropertiesDialogState();
}

class AlbumPropertiesDialogState extends State<AlbumPropertiesDialog> {
  late final TextEditingController titleController =
      TextEditingController(text: widget.properties.title);
  late final TextEditingController subTitleController =
      TextEditingController(text: widget.properties.subTitle);

  @override
  void dispose() {
    titleController.dispose();
    subTitleController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            DefaultTextStyle(
              style: DialogTheme.of(context).titleTextStyle ??
                  Theme.of(context).textTheme.titleLarge!,
              child: Semantics(
                namesRoute: Theme.of(context).platform != TargetPlatform.iOS,
                container: true,
                child: const Text("Albumeigenschaften"),
              ),
            ),
            TextField(
              controller: titleController,
              autofocus: true,
              decoration: const InputDecoration(label: Text("Titel")),
            ),
            TextField(
              controller: subTitleController,
              decoration: const InputDecoration(label: Text("Subtitel")),
            ),
            Padding(
              padding: const EdgeInsets.only(top: 16),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text("Abbrechen"),
                  ),
                  Padding(
                    padding: const EdgeInsets.only(left: 8),
                    child: ElevatedButton.icon(
                      icon: const Icon(Icons.check),
                      label: const Text("Übernehmen"),
                      onPressed: applyPressed,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  void applyPressed() {
    Navigator.of(context).pop(
      AlbumProperties(
        title: titleController.text,
        subTitle: subTitleController.text,
      ),
    );
  }
}
