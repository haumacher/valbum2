/// The album view: the row layout of the images, the edit mode and the album
/// properties editor.
library;

import 'package:flutter/material.dart';
import 'package:valbum_ui/album_layout.dart' as layouter;

import 'album_model.dart';
import 'app.dart';
import 'client.dart';
import 'resource.dart';

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

enum SelectionState {
  none,
  single,
  multiple;

  static SelectionState fromSize(int size) {
    switch (size) {
      case 0:
        return SelectionState.none;
      case 1:
        return SelectionState.single;
      default:
        return SelectionState.multiple;
    }
  }
}

class AlbumContentState extends State<AlbumContent> {
  bool editMode = false;

  Set<ThumbnailEditorState> selection = {};

  AbstractImage? selectionRequest;

  void setEditMode(AbstractImage selected) => setState(() {
        editMode = true;
        selectionRequest = selected;
      });

  void addToSelection(ThumbnailEditorState selected) {
    if (selection.contains(selected)) {
      return;
    }

    var stateBefore = SelectionState.fromSize(selection.length);
    selection.add(selected);
    var stateAfter = SelectionState.fromSize(selection.length);

    if (stateAfter != stateBefore) {
      for (var x in selection) {
        x.updateSelectionState(stateAfter);
      }
    } else {
      selected.updateSelectionState(stateAfter);
    }
  }

  void removeFromSelection(ThumbnailEditorState selected) {
    var stateBefore = SelectionState.fromSize(selection.length);
    selection.remove(selected);
    var stateAfter = SelectionState.fromSize(selection.length);

    if (stateAfter != stateBefore) {
      for (var x in selection) {
        x.updateSelectionState(stateAfter);
      }
    }
    selected.updateSelectionState(SelectionState.none);
  }

  void setSelection(ThumbnailEditorState selected) {
    selection.where((element) => element != selected).forEach(
          (element) => element.updateSelectionState(SelectionState.none),
        );
    selection.removeWhere((element) => element != selected);

    addToSelection(selected);
  }

  /// Writes the album back to the server and leaves the edit mode.
  ///
  /// The album is stored as the `index.json` sidecar of its own folder; the
  /// server keeps the previous sidecar as a backup. On success the album is
  /// re-fetched, so that the transient links between its parts are rebuilt
  /// from the state the server now has. A failed write keeps the edit mode
  /// open and reports the HTTP status.
  Future<void> save() async {
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
    });

    // Re-load the album, so that the transient part links are rebuilt.
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

  void clearSelection() {
    for (var x in selection) {
      x.updateSelectionState(SelectionState.none);
    }
    selection.clear();
  }

  String get albumUrl => "${widget.baseUrl}/${widget.album.path}";

  /// The transport to the album server.
  VAlbumClient get client => widget.albumState.client;

  @override
  Widget build(BuildContext context) {
    var self = widget.album;

    return Scaffold(
      appBar: !editMode && self.parts.isNotEmpty
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
      body: self.parts.isEmpty ? null : contentView(self),
      floatingActionButton: FloatingActionButton(
        onPressed: widget.albumState.uploadImages,
        tooltip: 'Upload',
        child: const Icon(Icons.cloud_upload),
      ), // This trailing comma makes auto-formatting nicer for build methods.
    );
  }

  LayoutBuilder contentView(AlbumInfo self) {
    return LayoutBuilder(
      builder: (BuildContext context, BoxConstraints constraints) {
        return SingleChildScrollView(
          scrollDirection: Axis.vertical,
          child: Column(
            children: [
              if (!editMode)
                Padding(
                  padding: const EdgeInsets.only(top: 20, bottom: 4),
                  child: Text(
                    self.title,
                    style: const TextStyle(fontSize: 28, color: Colors.white),
                  ),
                ),
              if (!editMode)
                if (self.subTitle.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Text(
                      self.subTitle,
                      style: const TextStyle(fontSize: 16, color: Colors.white),
                    ),
                  ),
              ...buildParts(self, constraints.maxWidth),
            ],
          ),
        );
      },
    );
  }

  /// Renders the album parts: each run of images is laid out as a block of
  /// rows, headings separate those blocks.
  List<Widget> buildParts(AlbumInfo self, double maxWidth) {
    var result = <Widget>[];
    var images = <AbstractImage>[];

    void flushImages() {
      if (images.isEmpty) {
        return;
      }
      var layout = layouter.AlbumLayout(maxWidth, 250, images);
      var builder = ContentWidgetBuilder(this, layout.getPageWidth());
      result.addAll(layout.map((row) => row.visit(builder, 0.0)));
      images = <AbstractImage>[];
    }

    for (var part in self.parts) {
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

  Widget headingView(Heading heading) => Padding(
        padding: const EdgeInsets.only(top: 24, bottom: 8),
        child: Text(
          heading.text,
          style: const TextStyle(fontSize: 22, color: Colors.white),
        ),
      );
}

class ContentWidgetBuilder implements layouter.ContentVisitor<Widget, double> {
  final AlbumContentState state;
  final double pageWidth;

  const ContentWidgetBuilder(this.state, this.pageWidth);

  @override
  Widget visitImg(layouter.Img content, double rowHeight) {
    var image = content.getImage();

    var width = content.getUnitWidth() * rowHeight;
    var height = rowHeight;

    return image.visitAbstractImage(
      ImageWidgetBuilder(state, width, height),
      null,
    );
  }

  @override
  Widget visitRow(layouter.Row content, double rowHeight) {
    double scale = pageWidth / content.getUnitWidth();
    double rowHeight = scale;

    return Row(
      children:
          content.map((content) => content.visit(this, rowHeight)).toList(),
    );
  }

  @override
  Widget visitDoubleRow(layouter.DoubleRow content, double rowHeight) {
    var width = content.getUnitWidth() * rowHeight;

    var upper = content.getUpper();
    var contentBuilder = ContentWidgetBuilder(state, width);
    var upperRow = upper.visit(contentBuilder, rowHeight * content.getH1());

    var lower = content.getLower();
    var lowerRow = lower.visit(contentBuilder, rowHeight * content.getH2());

    return Column(children: [upperRow, lowerRow]);
  }

  @override
  Widget visitPadding(layouter.Padding content, double rowHeight) {
    var width = content.getUnitWidth() * rowHeight;
    var height = rowHeight;

    return SizedBox(width: width, height: height);
  }
}

class ImageWidgetBuilder implements AbstractImageVisitor<Widget, void> {
  final AlbumContentState state;
  final double width, height;

  const ImageWidgetBuilder(this.state, this.width, this.height);

  @override
  Widget visitImageGroup(ImageGroup self, void arg) {
    var image = self.images[self.representative];
    return thumbnailView(image);
  }

  @override
  Widget visitImagePart(ImagePart self, void arg) {
    return thumbnailView(self);
  }

  Widget thumbnailView(ImagePart self) {
    if (state.editMode) {
      return ThumbnailEditor(state, this, self);
    } else {
      return GestureDetector(
        onTap: () => state.widget.pushPart(self, self.name),
        onLongPress: () => state.setEditMode(self),
        child: imageThumbnail(self),
      );
    }
  }

  Image imageThumbnail(AbstractImage image) {
    return Image.network(
      state.client.thumbnailUrl("${state.albumUrl}${image.thumbnailName}"),
      width: width,
      height: height,
      fit: BoxFit.contain,
    );
  }
}

class ThumbnailEditor extends StatefulWidget {
  final AlbumContentState state;
  final ImageWidgetBuilder builder;
  final AbstractImage image;

  const ThumbnailEditor(this.state, this.builder, this.image, {super.key});

  @override
  State<StatefulWidget> createState() => ThumbnailEditorState();
}

class ThumbnailEditorState extends State<ThumbnailEditor> {
  SelectionState _selected = SelectionState.none;
  bool _hovered = false;

  bool get selected => _selected != SelectionState.none;
  bool get multiSelected => _selected == SelectionState.multiple;

  bool get active => selected || _hovered;

  void updateSelectionState(SelectionState value) =>
      setState(() => _selected = value);

  @override
  void initState() {
    super.initState();

    if (widget.state.selectionRequest == widget.image) {
      _selected = SelectionState.single;
      widget.state.selection.add(this);
      widget.state.selectionRequest = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      hitTestBehavior: HitTestBehavior.translucent,
      opaque: false,
      onEnter: (event) => setState(() => _hovered = true),
      onExit: (event) => setState(() => _hovered = false),
      child: thumbnailView(),
    );
  }

  Widget thumbnailView() {
    if (active) {
      return Stack(
        children: [
          selected
              ? widget.builder.imageThumbnail(widget.image)
              : onClickImage(),
          if (multiSelected)
            SizedBox(
              width: widget.builder.width,
              height: widget.builder.height,
              child: Center(
                child: CircleAvatar(
                  radius: 20,
                  backgroundColor: Colors.blueAccent,
                  child: IconButton(
                    color: Colors.white,
                    icon: const Icon(Icons.join_left),
                    onPressed: createGroup,
                  ),
                ),
              ),
            ),
          Positioned(
            top: 4,
            left: 4,
            child: Checkbox(
              value: selected,
              onChanged: (value) => setSelected(value ?? false),
            ),
          ),
        ],
      );
    } else {
      return onClickImage();
    }
  }

  void createGroup() {}

  GestureDetector onClickImage() {
    return GestureDetector(
      onTap: select,
      onLongPress: addToSelection,
      child: widget.builder.imageThumbnail(widget.image),
    );
  }

  void setSelected(bool value) {
    if (value && !selected) {
      addToSelection();
    } else if (selected && !value) {
      removeFromSelection();
    }
  }

  void addToSelection() {
    widget.state.addToSelection(this);
  }

  void removeFromSelection() {
    widget.state.removeFromSelection(this);
  }

  void select() {
    widget.state.setSelection(this);
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
