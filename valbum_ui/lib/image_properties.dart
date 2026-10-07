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
import 'form_dialog.dart';
import 'attribution.dart';
import 'caller.dart';
import 'collections.dart';
import 'l10n/app_localizations.dart';
import 'resource.dart';

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
/// (issue #78), the raw file shot beside it (issue #191) and, where the file
/// said so, where it was taken (issue #112).
///
/// [mapUrl] is the template of the space, which the location line opens; an
/// empty one is [defaultMapUrl], so a caller that has no server to ask still
/// gets a map.
List<Widget> imagePropertyLines(
  AppLocalizations l10n,
  ImagePart image, {
  String mapUrl = "",
}) =>
    [
      imagePropertyLine(
        const Key("property-file"),
        l10n.propertyFile(image.name),
      ),
      if (image.date != 0)
        imagePropertyLine(
          const Key("property-time"),
          l10n.propertyTaken(
            imagePropertyTimeFormat.format(
              DateTime.fromMillisecondsSinceEpoch(image.date),
            ),
          ),
        ),
      // Where a photograph of a collection lies (issue #221).
      if (sourceFolderLabel(image) != null)
        imagePropertyLine(
          const Key("property-source"),
          l10n.propertySource(sourceFolderLabel(image)!),
        ),
      if (image.camera.isNotEmpty)
        imagePropertyLine(
          const Key("property-camera"),
          l10n.propertyCamera(image.camera),
        ),
      // The raw shot beside the photograph, which is the same photograph
      // (issue #191).
      if (image.raw.isNotEmpty)
        imagePropertyLine(
          const Key("property-raw"),
          l10n.propertyRaw(image.raw),
        ),
      // Where it was taken: the place the server named (issue #234), with the
      // coordinates one tap away; the coordinates alone from a server that
      // names none.
      if (hasPosition(image.location) && hasPlaceLine(image.places))
        ImagePlaceLine(
          image.places!,
          image.location!,
          mapUrl: mapUrl.isEmpty ? defaultMapUrl : mapUrl,
        )
      else if (hasPosition(image.location))
        ImageLocationLine(
          image.location!,
          mapUrl: mapUrl.isEmpty ? defaultMapUrl : mapUrl,
        ),
    ];

/// Whether [places] has anything to show: tags, or the sentence why there are
/// none yet.
bool hasPlaceLine(PlaceInfo? places) =>
    places != null && (places.tags.isNotEmpty || places.pending.isNotEmpty);

/// The place line of [tags], from the most specific name to the country.
///
/// Three groups, separated by a middle dot, each only where it has a name:
///
///  1. the named feature close by (a palace, a park, a lake);
///  2. the part of town and the town — or, where the photo lies in no town,
///     the most local administrative division GeoNames knows there;
///  3. the state (the first administrative level) and the country.
///
/// Within a group the names are separated by a comma, and a name already said
/// is not repeated (Berlin the city and Berlin the state are "Berlin ·
/// Germany"). The divisions between the town and the state (district,
/// county) are left out: they are what a person would not say.
///
/// `Karlsruhe Schloss · Innenstadt, Karlsruhe · Baden-Württemberg, Germany`
String placeLineText(List<PlaceTag> tags) {
  String? of(PlaceKind kind) {
    for (var tag in tags) {
      if (tag.kind == kind && tag.name.isNotEmpty) {
        return tag.name;
      }
    }
    return null;
  }

  var local = of(PlaceKind.place) ??
      of(PlaceKind.adm4) ??
      of(PlaceKind.adm3) ??
      of(PlaceKind.adm2);
  var groups = [
    [of(PlaceKind.feature)],
    [of(PlaceKind.district), local],
    [of(PlaceKind.adm1), of(PlaceKind.country)],
  ];
  var said = <String>{};
  var parts = <String>[];
  for (var group in groups) {
    var names = <String>[];
    for (var name in group) {
      if (name != null && said.add(name.toLowerCase())) {
        names.add(name);
      }
    }
    if (names.isNotEmpty) {
      parts.add(names.join(", "));
    }
  }
  return parts.join(" · ");
}

/// Where a photo was taken, as a place: the [placeLineText] of its tags, or
/// the server's sentence why there are none yet (shown as it stands, like
/// every server message), with the [ImageLocationLine] — the coordinates and
/// the map — unfolded by the button beside it (issue #234).
class ImagePlaceLine extends StatefulWidget {
  /// What the server said about the place.
  final PlaceInfo places;

  /// Where the photo was taken.
  final GeoLocation location;

  /// The template of the space, see [mapUrlFor].
  final String mapUrl;

  const ImagePlaceLine(
    this.places,
    this.location, {
    super.key,
    this.mapUrl = "",
  });

  @override
  State<ImagePlaceLine> createState() => _ImagePlaceLineState();
}

class _ImagePlaceLineState extends State<ImagePlaceLine> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var places = widget.places;
    var pending = places.tags.isEmpty;
    var text = pending
        ? places.pending
        : l10n.propertyPlace(placeLineText(places.tags));
    return Column(
      key: const Key("property-place"),
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Flexible(
              child: SelectableText(
                text,
                key: Key(
                  pending ? "property-place-pending" : "property-place-text",
                ),
              ),
            ),
            IconButton(
              key: const Key("property-place-expand"),
              icon: Icon(_expanded ? Icons.expand_less : Icons.expand_more),
              iconSize: 18,
              visualDensity: VisualDensity.compact,
              tooltip: _expanded ? l10n.hideCoordinates : l10n.showCoordinates,
              onPressed: () => setState(() => _expanded = !_expanded),
            ),
          ],
        ),
        if (_expanded)
          ImageLocationLine(widget.location, mapUrl: widget.mapUrl),
      ],
    );
  }
}

/// Whether [location] names a place at all, see issue #161.
///
/// A pair of zeroes is what a camera with geotagging on and no fix writes;
/// the server no longer answers it, but an older server stored and still
/// answers it, and "0.000000, 0.000000" is no place anybody was.
bool hasPosition(GeoLocation? location) =>
    location != null && (location.latitude != 0 || location.longitude != 0);

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
String mapLocationText(AppLocalizations l10n, GeoLocation location) =>
    l10n.propertyLocation(
      mapCoordinate(location.latitude),
      mapCoordinate(location.longitude),
    );

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
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return Row(
      key: const Key("property-location"),
      mainAxisSize: MainAxisSize.min,
      children: [
        Flexible(child: SelectableText(mapLocationText(l10n, location))),
        IconButton(
          key: const Key("property-location-map"),
          icon: const Icon(Icons.map_outlined),
          iconSize: 18,
          visualDensity: VisualDensity.compact,
          tooltip: l10n.showOnMap,
          onPressed: open,
        ),
      ],
    );
  }
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

  /// Whether the description may be edited here at all, see issue #136.
  ///
  /// An image lying in an **inbox** has no description: an inbox is a heap of
  /// photographs waiting to be sorted, and what is written about a photograph
  /// is written in the album it ends up in. The dialog then shows what the
  /// image *is* — the details block and the attribution — and nothing to type
  /// in, rather than offering a field whose content would be refused.
  final bool editable;

  const ImagePropertiesDialog(
    this.image, {
    super.key,
    this.initial,
    this.editable = true,
  });

  @override
  Widget build(BuildContext context) =>
      editable ? _editor(context) : _reader(context);

  /// What the dialog shows where nothing is edited: the same lines, no field.
  Widget _reader(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var note = attributionShown(l10n, image);
    return AlertDialog(
      key: const Key("image-properties-read-only"),
      title: Text(l10n.imageProperties),
      content: SingleChildScrollView(
        child: DefaultTextStyle.merge(
          style: Theme.of(context).textTheme.bodySmall,
          child: Column(
            key: const Key("properties-details"),
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              ...imagePropertyLines(
                l10n,
                image,
                mapUrl: CallerInfo.mapUrlOf(context),
              ),
              if (note != null)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text(
                    note,
                    key: const Key("properties-contributor"),
                  ),
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.ok),
        ),
      ],
    );
  }

  Widget _editor(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return TextInputDialog(
      title: l10n.imageProperties,
      label: l10n.commentLabel,
      text: initial ?? image.comment,
      multiLine: true,
      details: imagePropertyLines(
        l10n,
        image,
        mapUrl: CallerInfo.mapUrlOf(context),
      ),
      // Who added this photo, the editor's own contributions included: the
      // screen saying what an image is says where it came from, see #53.
      note: attributionShown(l10n, image),
    );
  }
}

/// Opens the [ImagePropertiesDialog] on [image] and answers what was typed.
///
/// With [editable] `false` the dialog shows what the image is and edits
/// nothing, and the answer is always `null`, see [ImagePropertiesDialog].
Future<String?> showImageProperties(
  BuildContext context,
  ImagePart image, {
  String? initialDescription,
  bool editable = true,
}) {
  Widget build(BuildContext context) => ImagePropertiesDialog(
        image,
        initial: initialDescription,
        editable: editable,
      );
  // The description is typed input and is closed by Cancel or Escape only;
  // the read-only details are something shown, which a tap beside closes,
  // see issue #178.
  return editable
      ? showFormDialog<String>(context: context, builder: build)
      : showDialog<String>(context: context, builder: build);
}
