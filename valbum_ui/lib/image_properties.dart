/// The one dialog showing what an image is and editing its description,
/// see issue #122.
///
/// There were two: the album tile's tool composed the description field with
/// the file name, the recording time and the camera, while the viewer's
/// description button composed the same field with the attribution alone — two
/// call sites building one thing by hand, which is why they had drifted apart.
/// The composition lives here now, and both open it: the location line of
/// issue #112 was added here once and is thereby in both places.
library;

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:url_launcher/url_launcher.dart';

import 'album_view.dart' show TextInputDialog;
import 'attribution.dart';
import 'caller.dart';
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
/// adjustment of issue #77 may have corrected — the camera that took it
/// (issue #78) and, where the file said so, where it was taken (issue #112).
///
/// [mapUrl] is the template of the space, which the location line opens; an
/// empty one is [defaultMapUrl], so a caller that has no server to ask still
/// gets a map.
List<Widget> imagePropertyLines(ImagePart image, {String mapUrl = ""}) => [
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
      if (image.location != null)
        ImageLocationLine(
          image.location!,
          mapUrl: mapUrl.isEmpty ? defaultMapUrl : mapUrl,
        ),
    ];

/// How many decimals a coordinate is spelled with.
///
/// Six of them are about ten centimetres at the equator — finer than any
/// camera knows where it stood, and coarse enough to read at a glance.
const int mapCoordinateDecimals = 6;

/// One coordinate as the location line spells it.
///
/// A dot as the decimal separator and no thousands separator, **whatever the
/// locale of the device** (issue #112): this number is read by a map, not by a
/// person alone, and `48,123456` is a different number to every map there is.
/// So this does not go through [NumberFormat], which would spell a German
/// device's comma.
String mapCoordinate(double value) =>
    value.toStringAsFixed(mapCoordinateDecimals);

/// Where [location] is on a map, as the template [mapUrl] spells it.
///
/// `{lat}` and `{lon}` are substituted with the decimal degrees, each of them
/// a [mapCoordinate]; a template may name either of them as often as it likes,
/// as OpenStreetMap's does.
String mapUrlFor(String mapUrl, GeoLocation location) =>
    (mapUrl.isEmpty ? defaultMapUrl : mapUrl)
        .replaceAll("{lat}", mapCoordinate(location.latitude))
        .replaceAll("{lon}", mapCoordinate(location.longitude));

/// What the location line reads, the coordinates and nothing else.
String mapLocationText(GeoLocation location) =>
    "Location: ${mapCoordinate(location.latitude)}, "
    "${mapCoordinate(location.longitude)}";

/// Where a photo was taken: the coordinates, and a tap opening them on a map.
///
/// The text is selectable like every other line of the block, so the numbers
/// can be copied out of the dialog; the button beside it hands [mapUrlFor] to
/// whatever the device shows maps with — a browser opens a tab, a phone its
/// map application.
class ImageLocationLine extends StatelessWidget {
  /// Where the photo was taken.
  final GeoLocation location;

  /// The template of the space, see [mapUrlFor].
  final String mapUrl;

  const ImageLocationLine(this.location, {super.key, this.mapUrl = ""});

  /// Opens the map of [location], doing nothing where nothing can show it.
  Future<void> open() async {
    var url = Uri.parse(mapUrlFor(mapUrl, location));
    await launchUrl(url, mode: LaunchMode.externalApplication);
  }

  @override
  Widget build(BuildContext context) => Row(
        key: const Key("property-location"),
        mainAxisSize: MainAxisSize.min,
        children: [
          Flexible(child: SelectableText(mapLocationText(location))),
          IconButton(
            key: const Key("property-location-map"),
            icon: const Icon(Icons.map_outlined),
            iconSize: 18,
            visualDensity: VisualDensity.compact,
            tooltip: "Show on a map",
            onPressed: open,
          ),
        ],
      );
}

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
        details: imagePropertyLines(image, mapUrl: CallerInfo.mapUrlOf(context)),
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
