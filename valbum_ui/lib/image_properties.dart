/// The one dialog showing what an image is and editing its description,
/// see issue #122.
///
/// There were two: the album tile's tool composed the description field with
/// the file name, the recording time and the camera, while the viewer's
/// description button composed the same field with the attribution alone — two
/// call sites building one thing by hand, which is why they had drifted apart.
/// The composition lives here now, and both open it; the location line of
/// issue #112 is added here once and is then in both places.
library;

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'album_view.dart' show TextInputDialog;
import 'attribution.dart';
import 'resource.dart';

/// The heading of the dialog, wherever it was opened from.
///
/// English, like the app, see issue #122 — it was "Bildeigenschaften" while
/// two call sites spelled it.
const String imagePropertiesTitle = "Image properties";

/// The label of the one field the dialog edits.
const String imagePropertiesLabel = "Kommentar";

/// How the recording time is spelled in the details block.
final DateFormat imagePropertyTimeFormat = DateFormat("yyyy-MM-dd HH:mm:ss");

/// One read-only line of the details block, addressed by [key].
///
/// Selectable, so that a file name can be copied out of the dialog. The style
/// is the one the dialog puts around its details block.
Widget imagePropertyLine(Key key, String text) =>
    SelectableText(text, key: key);

/// What the album knows about [image] and does not edit here.
///
/// Each line only where there is something to say: the name the file carries
/// (issue #103), the recording time it is sorted by — the sidecar's, which the
/// adjustment of issue #77 may have corrected — and the camera that took it
/// (issue #78). The keys are the handle the location line of issue #112 lands
/// beside.
List<Widget> imagePropertyLines(ImagePart image) => [
      imagePropertyLine(const Key("property-file"), "File: ${image.name}"),
      if (image.date != 0)
        imagePropertyLine(
          const Key("property-time"),
          "Taken: ${imagePropertyTimeFormat.format(
            DateTime.fromMillisecondsSinceEpoch(image.date),
          )}",
        ),
      if (image.camera.isNotEmpty)
        imagePropertyLine(
            const Key("property-camera"), "Camera: ${image.camera}"),
    ];

/// The properties of one image: the details block, the attribution note and
/// the description field.
///
/// Answers the edited description, `null` when it was cancelled. What is
/// done with the answer is the caller's own business — the tile writes it
/// into the album buffer, the viewer either writes the album at once or marks
/// the buffer dirty, see issue #122.
class ImagePropertiesDialog extends StatelessWidget {
  /// The image the dialog shows and whose description it edits.
  final ImagePart image;

  /// The text the field starts with, the image's own description by default.
  ///
  /// The viewer hands the text of a refused write back in, so that a retry
  /// costs no typing.
  final String? initial;

  const ImagePropertiesDialog(this.image, {super.key, this.initial});

  @override
  Widget build(BuildContext context) => TextInputDialog(
        title: imagePropertiesTitle,
        label: imagePropertiesLabel,
        text: initial ?? image.comment,
        multiLine: true,
        details: imagePropertyLines(image),
        // Who added this photo, the editor's own contributions included: the
        // screen saying what an image is says where it came from, see #53.
        note: attributionShown(image),
      );
}

/// Opens the [ImagePropertiesDialog] on [image] and answers what was typed.
Future<String?> showImageProperties(
  BuildContext context,
  ImagePart image, {
  String? initialDescription,
}) =>
    showDialog<String>(
      context: context,
      builder: (context) =>
          ImagePropertiesDialog(image, initial: initialDescription),
    );
