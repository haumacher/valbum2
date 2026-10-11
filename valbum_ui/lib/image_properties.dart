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
      // Where it was taken: the place the server named (issue #234) with the
      // map a tap away; the coordinates and the map from a server that names
      // none.
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

/// Whether the server named the place of [image]: a position, and the names
/// of the places it lies in (issue #234) — not the sentence why there are
/// none yet.
bool hasNamedPlace(ImagePart image) =>
    hasPosition(image.location) &&
    image.places != null &&
    placeLineText(image.places!.tags).isNotEmpty;

/// Whether [image] shows its place as its caption where it has no
/// description: its own word ([ImagePart.placeCaption]) where it said one,
/// else its album's ([AlbumInfo.placeCaption]), where nothing said means
/// shown.
///
/// [album] is `null` where the image is shown without one; it is then shown
/// as an album that said nothing shows it.
bool placeCaptionShown(ImagePart image, AlbumInfo? album) =>
    switch (image.placeCaption) {
      PlaceCaption.shown => true,
      PlaceCaption.hidden => false,
      PlaceCaption.unset => album?.placeCaption != PlaceCaption.hidden,
    };

/// What is written under a picture, see [captionOf].
class ImageCaption {
  /// The text: the description, or the [placeLineText] of the place.
  final String text;

  /// Whether [text] is the place and not a description: the viewer then puts
  /// the map marker behind it.
  final bool isPlace;

  const ImageCaption(this.text, {this.isPlace = false});
}

/// The caption of [image] in [album], `null` where there is none.
///
/// The one rule every display of a picture follows — the caption under the
/// viewer's picture and the tooltip of the album's tile: the description
/// where there is one; else, where the place is shown as caption
/// ([placeCaptionShown]) and the server named it ([hasNamedPlace]), the
/// place line; else nothing.
ImageCaption? captionOf(ImagePart image, AlbumInfo? album) {
  if (image.comment.trim().isNotEmpty) {
    return ImageCaption(image.comment);
  }
  if (placeCaptionShown(image, album) && hasNamedPlace(image)) {
    return ImageCaption(placeLineText(image.places!.tags), isPlace: true);
  }
  return null;
}

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
///
/// What a search answers of a photograph ([ImagePart.partial], issue #243)
/// carries exactly the tags this reads — of each kind the first with a name,
/// of the town's fallbacks the first there is — with name and kind alone:
/// `PhotoSearch.placeLine` on the server is tied to this function, and the
/// two change together.
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
/// every server message), followed by the marker opening the position on the
/// map (issue #234). The coordinates themselves are not shown: the place says
/// where, the map shows where exactly.
class ImagePlaceLine extends StatelessWidget {
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

  /// Opens the map of [location], doing nothing where nothing can show it.
  Future<void> open() => openMap(mapUrl, location);

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var pending = places.tags.isEmpty;
    var text = pending
        ? places.pending
        : l10n.propertyPlace(placeLineText(places.tags));
    return Row(
      key: const Key("property-place"),
      mainAxisSize: MainAxisSize.min,
      children: [
        Flexible(
          child: SelectableText(
            text,
            key:
                Key(pending ? "property-place-pending" : "property-place-text"),
          ),
        ),
        IconButton(
          key: const Key("property-place-map"),
          icon: const Icon(mapMarkerIcon),
          iconSize: 18,
          visualDensity: VisualDensity.compact,
          tooltip: l10n.showOnMap,
          onPressed: open,
        ),
      ],
    );
  }
}

/// The icon of the button opening a position on the map: the map marker, the
/// inverted drop every map draws, on the place line and the coordinates line
/// alike.
const IconData mapMarkerIcon = Icons.location_on;

/// Opens [location] on the map [mapUrl] names, see [mapUrlFor], with whatever
/// the device shows maps with — a browser opens a tab, a phone its map
/// application.
Future<void> openMap(String mapUrl, GeoLocation location) async {
  var url = Uri.parse(mapUrlFor(mapUrl, location));
  await launchUrl(url, mode: LaunchMode.externalApplication);
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
  Future<void> open() => openMap(mapUrl, location);

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
          icon: const Icon(mapMarkerIcon),
          iconSize: 18,
          visualDensity: VisualDensity.compact,
          tooltip: l10n.showOnMap,
          onPressed: open,
        ),
      ],
    );
  }
}

/// What the editable [ImagePropertiesDialog] answers: the description and
/// whether the place stands in for a missing one.
class ImagePropertiesResult {
  /// The description as typed.
  final String description;

  /// The picture's own word on its place caption, see [ImagePart.placeCaption].
  final PlaceCaption placeCaption;

  const ImagePropertiesResult(this.description, this.placeCaption);

  /// Whether this says anything else than [image] holds.
  bool changes(ImagePart image) =>
      description != image.comment || placeCaption != image.placeCaption;

  /// Writes this into [image].
  void applyTo(ImagePart image) {
    image.comment = description;
    image.placeCaption = placeCaption;
  }
}

/// The properties of one image: the details block, the attribution note, the
/// description field and — where the server named the image's place — whether
/// that place stands in as caption where there is no description.
///
/// Answers an [ImagePropertiesResult], `null` when it was cancelled. What is
/// done with the answer is the caller's own business — the tile writes it
/// into the album buffer, the viewer either writes the album at once or marks
/// the buffer dirty, see issue #122.
class ImagePropertiesDialog extends StatefulWidget {
  /// The image the dialog shows and whose description it edits.
  final ImagePart image;

  /// The text the field starts with, the image's own description by default.
  ///
  /// The viewer hands the text of a refused write back in, so that a retry
  /// costs no typing.
  final String? initial;

  /// The place caption the choice starts with, the image's own by default;
  /// handed back in with [initial].
  final PlaceCaption? initialPlaceCaption;

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
    this.initialPlaceCaption,
    this.editable = true,
  });

  @override
  State<ImagePropertiesDialog> createState() => ImagePropertiesDialogState();
}

class ImagePropertiesDialogState extends State<ImagePropertiesDialog> {
  /// The place caption being chosen.
  late PlaceCaption placeCaption =
      widget.initialPlaceCaption ?? widget.image.placeCaption;

  ImagePart get image => widget.image;

  @override
  Widget build(BuildContext context) =>
      widget.editable ? _editor(context) : _reader(context);

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
      text: widget.initial ?? image.comment,
      multiLine: true,
      // Edited with the description and only where there is a place to show:
      // a choice about nothing would be noise.
      fields: [
        if (hasNamedPlace(image)) _placeCaptionChoice(l10n),
      ],
      answer: (text) => ImagePropertiesResult(text, placeCaption),
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

  /// Whether the place stands in for a missing description: as the album
  /// says, or the picture's own word either way.
  Widget _placeCaptionChoice(AppLocalizations l10n) => Padding(
        padding: const EdgeInsets.only(top: 12),
        child: DropdownButtonFormField<PlaceCaption>(
          key: const Key("image-place-caption-choice"),
          initialValue: placeCaption,
          // A phone's dialog is narrow: the option shrinks, the row never
          // overflows.
          isExpanded: true,
          decoration: InputDecoration(label: Text(l10n.displayLocation)),
          items: [
            DropdownMenuItem(
              value: PlaceCaption.unset,
              child: Text(
                l10n.placeCaptionAsAlbum,
                key: const Key("place-caption-unset"),
                overflow: TextOverflow.ellipsis,
              ),
            ),
            DropdownMenuItem(
              value: PlaceCaption.shown,
              child: Text(
                l10n.placeCaptionShown,
                key: const Key("place-caption-shown"),
                overflow: TextOverflow.ellipsis,
              ),
            ),
            DropdownMenuItem(
              value: PlaceCaption.hidden,
              child: Text(
                l10n.placeCaptionHidden,
                key: const Key("place-caption-hidden"),
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
          onChanged: (value) {
            if (value != null) {
              setState(() => placeCaption = value);
            }
          },
        ),
      );
}

/// Opens the [ImagePropertiesDialog] on [image] and answers what was edited.
///
/// With [editable] `false` the dialog shows what the image is and edits
/// nothing, and the answer is always `null`, see [ImagePropertiesDialog].
Future<ImagePropertiesResult?> showImageProperties(
  BuildContext context,
  ImagePart image, {
  String? initialDescription,
  PlaceCaption? initialPlaceCaption,
  bool editable = true,
}) {
  Widget build(BuildContext context) => ImagePropertiesDialog(
        image,
        initial: initialDescription,
        initialPlaceCaption: initialPlaceCaption,
        editable: editable,
      );
  // The description is typed input and is closed by Cancel or Escape only;
  // the read-only details are something shown, which a tap beside closes,
  // see issue #178.
  return editable
      ? showFormDialog<ImagePropertiesResult>(context: context, builder: build)
      : showDialog<ImagePropertiesResult>(context: context, builder: build);
}
