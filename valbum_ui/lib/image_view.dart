/// The single image viewer: zoom, pan, swipe and keyboard navigation.
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart'
    show defaultTargetPlatform, kIsWeb, TargetPlatform;
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'album_layout.dart' show Orientations, ToImage;
import 'app.dart';
import 'attribution.dart';
import 'caller.dart';
import 'client.dart';
import 'downloads.dart';
import 'image_properties.dart';
import 'album_edit.dart' show PlaneTransform;
import 'image_transform.dart';
import 'move_view.dart';
import 'offline.dart';
import 'people_registry.dart';
import 'person_names.dart';
import 'persons_view.dart' show PersonChooser;
import 'l10n/app_localizations.dart';
import 'resource.dart';
import 'rights.dart';
import 'share_session.dart';
import 'thumbnails.dart';
import 'video_view.dart';

/// The velocity (in pixels per second) a drag must reach to count as a swipe.
const double _swipeVelocity = 400;

/// How far a finger must move before the drag axis is decided (issue #61).
const double _axisThreshold = 8;

/// The part of the page width a slow drag must cross to navigate (issue #61).
const double _swipeDistanceFraction = 1 / 3;

/// The part of the page width a drag against the album's end may cover.
///
/// At the first (last) image there is nothing to page to, so the drag is only
/// answered by a rubber band that approaches this much and never passes it.
const double _rubberBandFraction = 1 / 5;

/// How long a drag that did not navigate takes to slide back (issue #61).
const Duration _snapBackDuration = Duration(milliseconds: 150);

/// The picture the viewer shows for the image at [imageUrl] (issue #95).
///
/// With `download` that is the original, as it always was. Without it the
/// server would refuse the original (`AuthService.DOWNLOAD_REFUSED`), so the
/// viewer asks for the **preview rendition** instead — `?type=tn`, which the
/// `view` right allows and which the album tiles are drawn from anyway. It is
/// the largest picture such a caller can have, so it is shown fitted to the
/// page and never resized on the way in.
///
/// One helper for one question, because the display and the prefetch of
/// issue #101 must produce the *same* [ImageProvider]: an [ImageCache] key
/// that differs by a hair turns a prefetched neighbour into a second download.
ImageProvider viewerPicture(
  VAlbumClient client,
  String imageUrl, {
  required bool mayDownload,
}) {
  if (mayDownload) {
    // The original is not cached (an album of originals would fill the
    // device), but it must still identify itself: a server started with
    // `--auth all` refuses an anonymous image.
    return NetworkImage(client.originalUrl(imageUrl),
        headers: client.authHeaders);
  }
  return ThumbnailImage(client, imageUrl);
}

/// How small a hand-drawn rectangle may be and still mean a face (issue #147).
///
/// In pixels of the screen on both sides: below that it is a tap that slipped,
/// and a face nobody can see the outline of is not a face somebody marked.
const double _minMarkedFace = 8;

/// How large a click on the picture is sent, in pixels of the screen (#155).
///
/// A click with the marking tool is a small box centred on the click: the
/// server looks for a face in a region of the original around it that is at
/// least a fifth of the picture's long side, so the box only has to say
/// *where*, and a square of this size says so at any zoom.
const double _clickedFace = 24;

/// How tall the name chip over a face box is drawn, in pixels of the screen.
const double _faceLabelHeight = 22;

/// How far a face box must be dragged to be moved or resized (issue #157).
///
/// In pixels of the screen, in either direction: a drag shorter than that is
/// a click whose hand shook, and it opens the face's sheet like a tap.
const double _adjustSlop = 4;

/// How large a corner handle of a face box takes the pointer, in pixels of the
/// screen (issue #157); the square drawn in it is smaller.
const double _handleSize = 24;

/// The four corners of a face box a handle resizes it by (issue #157).
enum FaceCorner {
  topLeft("top-left"),
  topRight("top-right"),
  bottomLeft("bottom-left"),
  bottomRight("bottom-right");

  /// How the corner is spelled in the key of its handle.
  final String keyName;

  const FaceCorner(this.keyName);

  /// Where the corner lies on the given rectangle.
  Offset of(Rect rect) => switch (this) {
        FaceCorner.topLeft => rect.topLeft,
        FaceCorner.topRight => rect.topRight,
        FaceCorner.bottomLeft => rect.bottomLeft,
        FaceCorner.bottomRight => rect.bottomRight,
      };
}

/// A face box being moved or resized in the viewer, see issue #157.
///
/// Everything in page coordinates: the box as it was when the drag began, and
/// the box as the pointer has made it since. The picture it belongs to is
/// remembered, so that a drag that outlives a page turn adjusts nothing.
class _FaceAdjustment {
  final String image;
  final FaceInfo face;

  /// The corner being dragged, `null` where the whole box is moved.
  final FaceCorner? corner;

  final Rect start;
  Rect rect;

  /// Where the pointer went down, in global coordinates.
  final Offset origin;

  /// The longest the pointer has been away from where it went down, per axis.
  double moved = 0;

  /// Whether the new box has been posted and the answer is awaited: the box
  /// stays where it was dragged to until the answer says where it is.
  bool posting = false;

  _FaceAdjustment(this.image, this.face, this.corner, this.start, this.origin)
      : rect = start;
}

/// The box [start] dragged by [delta], kept inside [bounds] (issue #157).
///
/// Moving keeps the size and stops at the edges of the picture; a corner moves
/// alone, the opposite corner standing still, and never nearer to it than
/// [minSide], so a box can be made small but not turned inside out. Pure, so
/// that the geometry is checked without a screen.
Rect adjustedRect(
  Rect start,
  FaceCorner? corner,
  Offset delta,
  Rect bounds, {
  double minSide = _minMarkedFace,
}) {
  if (corner == null) {
    var dx = delta.dx;
    var dy = delta.dy;
    if (start.width <= bounds.width) {
      dx = dx.clamp(bounds.left - start.left, bounds.right - start.right);
    }
    if (start.height <= bounds.height) {
      dy = dy.clamp(bounds.top - start.top, bounds.bottom - start.bottom);
    }
    return start.shift(Offset(dx, dy));
  }
  var point = corner.of(start) + delta;
  var x = point.dx.clamp(bounds.left, bounds.right);
  var y = point.dy.clamp(bounds.top, bounds.bottom);
  var left = start.left, top = start.top;
  var right = start.right, bottom = start.bottom;
  switch (corner) {
    case FaceCorner.topLeft:
      left = math.min(x, right - minSide);
      top = math.min(y, bottom - minSide);
    case FaceCorner.topRight:
      right = math.max(x, left + minSide);
      top = math.min(y, bottom - minSide);
    case FaceCorner.bottomLeft:
      left = math.min(x, right - minSide);
      bottom = math.max(y, top + minSide);
    case FaceCorner.bottomRight:
      right = math.max(x, left + minSide);
      bottom = math.max(y, top + minSide);
  }
  return Rect.fromLTRB(left, top, right, bottom);
}

/// The axis a drag of the fitted image was locked to, see issue #61.
///
/// A drag of the fitted image follows the finger along one axis only: the
/// picture must not drift diagonally away from a horizontal paging swipe.
/// The axis is decided from the first few pixels of the movement; a zoomed
/// image and a pinch are [free] and pan as they always did.
enum _DragAxis { undecided, horizontal, vertical, free }

/// How many image viewers are on screen, see [_enterImmersive].
int _immersiveViewers = 0;

/// Whether this platform has system bars the viewer should hide (issue #60).
///
/// Only Android and iOS have them; on the web and on the desktop the calls
/// are not available (and would be meaningless), so they are skipped.
bool get _hasSystemBars =>
    !kIsWeb &&
    (defaultTargetPlatform == TargetPlatform.android ||
        defaultTargetPlatform == TargetPlatform.iOS);

/// Hides the system bars while an image viewer is on screen (issue #60).
///
/// The navigation bar overlaps the picture and, in landscape, sits exactly
/// where the viewer's chevrons are, so the viewer runs immersive. Stepping
/// from image to image creates the next viewer before the previous one is
/// disposed; the counter keeps the bars from flickering in between, and the
/// restore is deferred by a microtask so that the order of the two does not
/// matter.
void _enterImmersive() {
  if (_immersiveViewers++ == 0) {
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  }
}

/// Gives the system bars back when the last viewer left, see [_enterImmersive].
void _leaveImmersive() {
  if (_immersiveViewers > 0) {
    _immersiveViewers--;
  }
  scheduleMicrotask(() {
    if (_immersiveViewers == 0) {
      SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    }
  });
}

/// Displays a single [AbstractImage] full-screen.
///
/// The image is fitted into the viewport honouring its
/// [ImagePart.orientation]; the mouse wheel zooms around the cursor, dragging
/// pans, a click toggles between the fitted view and a pixel-by-pixel display
/// of the clicked spot (see [ImageTransform]). The chevrons, the arrow keys and
/// swipe gestures navigate to the previous and next image, honouring the album's
/// rating filter (see [nextVisible]).
class ImageView extends StatefulWidget {
  /// The transport to the album server (for building the image URLs).
  final VAlbumClient client;

  /// The URL of the album the image belongs to.
  final String baseUrl;

  /// The image to display, a group is displayed by its representative.
  final AbstractImage image;

  /// Displays another image of the same album.
  final void Function(AbstractImage image) onShowImage;

  /// Opens the "alternatives" view of the group the image belongs to.
  ///
  /// The "down" chevron and the `ArrowDown` key are only offered if this is
  /// given and the image is part of an [ImageGroup].
  final void Function(ImageGroup group)? onShowGroup;

  /// Leaves the viewer, back to the album (or to the alternatives view).
  final VoidCallback? onUp;

  /// The minimum rating an image must have to be shown.
  ///
  /// Defaults to the [AlbumInfo.minRating] of the album owning the image.
  final int? minRating;

  /// Further controls floating over the image at the top right, such as the
  /// [GroupDetailView]'s choice of the group's representative.
  final List<Widget> actions;

  /// The path of the album the image lives in, `null` where the view does not
  /// know it (see issue #53).
  ///
  /// A take-back is a move posted to the album's own folder, so without the
  /// path there is nothing to post to and the button is not offered. The
  /// alternatives view of a group is such a place on purpose: a group moves as
  /// a whole, named by its representative, and the member shown there is not
  /// what the server would move — the take-back of a grouped photo belongs to
  /// the viewer of the album, which shows exactly that representative.
  final List<String>? albumPath;

  /// Called after a photo was taken back, so the view it left can be fetched
  /// again; the viewer falls back to [onUp] where this is not given.
  final VoidCallback? onTakenBack;

  /// The path of the album whose sidecar a description is written to
  /// (issue #80), `null` where the view does not know it.
  ///
  /// Not [albumPath], although it names the same album: that one says where a
  /// *take-back* may be posted, and the alternatives view of a group has none
  /// on purpose — a group moves as a whole. Writing a description is a
  /// different thing, and a group member's description is written to the same
  /// album as any other.
  final List<String>? editPath;

  /// Whether the album this image belongs to is being edited (issue #80).
  ///
  /// Two plain values rather than the album's `AlbumEditSession` itself: that
  /// class lives in `app.dart`, which builds this view, and the view needs no
  /// more of it than this. Inside the edit mode a description edited here goes
  /// into the editing buffer like a tile edit — [onEdited] marks the album
  /// dirty and the album view saves it with everything else, so that nothing
  /// is written twice and the album's discard still covers it. Outside it, the
  /// change is written at once, see [editDescription].
  final bool editing;

  /// Called when a description was changed into the album's editing buffer.
  final VoidCallback? onEdited;

  /// The router, so that a photo taken back out of this album is fetched anew
  /// where it landed, see issue #134.
  ///
  /// `null` in a viewer built outside the router — a widget test, a session
  /// that holds no navigation of its own: such a viewer has nothing to forget.
  final VAlbumRouterDelegate? delegate;

  const ImageView({
    super.key,
    required this.client,
    required this.baseUrl,
    required this.image,
    required this.onShowImage,
    this.onShowGroup,
    this.onUp,
    this.minRating,
    this.actions = const [],
    this.albumPath,
    this.onTakenBack,
    this.editPath,
    this.editing = false,
    this.onEdited,
    this.delegate,
  });

  @override
  State<ImageView> createState() => ImageViewState();
}

class ImageViewState extends State<ImageView>
    with SingleTickerProviderStateMixin {
  ImageTransform? _transform;

  /// The state the transform had when the current gesture started.
  double _gestureScale = 1;
  double _gestureTx = 0;
  double _gestureTy = 0;
  Offset _gestureFocus = Offset.zero;

  /// The axis the running drag of the fitted image is locked to (issue #61).
  _DragAxis _axis = _DragAxis.free;

  /// How far the finger moved along the locked axis, before the rubber band.
  double _dragDx = 0;

  /// Whether more than one finger took part in the running gesture.
  bool _multiTouch = false;

  /// Slides a drag that did not navigate back to the fitted position.
  late final AnimationController _snapBack = AnimationController(
    vsync: this,
    duration: _snapBackDuration,
  )..addListener(_onSnapBack);

  /// The transform the running snap-back animates, `null` while none runs.
  ImageTransform? _snapping;

  /// The translation the snap-back starts from.
  double _snapFrom = 0;

  /// Whether this viewer hid the system bars, see [_enterImmersive].
  bool _immersive = false;

  /// Whether the faces of the picture are being named, see issue #147.
  ///
  /// A state of the page and not a route level: the mode is entered from the
  /// viewer's menu and left by its own control (or by `Escape`), and it
  /// **survives paging** — this state outlives the picture it shows, so a
  /// whole album is named without entering the mode once per photograph.
  bool _editPersons = false;

  /// Whether a drag on the picture draws a face box instead of panning (#147).
  bool _markingFaces = false;

  /// The rectangle being drawn, in page coordinates; `null` while none is.
  Rect? _marked;

  /// Where the drag drawing [_marked] started, in page coordinates (#155).
  ///
  /// Kept on its own, because the rectangle is spanned between this point and
  /// wherever the pointer is now — in every direction. Anchoring it on the
  /// last frame's corner instead collapsed every drag that went left or up.
  Offset? _markStart;

  /// The face box being moved or resized, `null` while none is (#157).
  _FaceAdjustment? _adjusting;

  /// The register, as far as this viewer has loaded it, see [PeopleRegistry].
  Map<String, Person> _people = const {};

  @override
  void initState() {
    super.initState();
    _immersive = _hasSystemBars;
    if (_immersive) {
      _enterImmersive();
    }
  }

  @override
  void dispose() {
    _snapBack.dispose();
    _releasePictures();
    if (_immersive) {
      _leaveImmersive();
    }
    super.dispose();
  }

  /// The image displayed, the representative of a group.
  ImagePart get part => ToImage.toImage(widget.image);

  /// Whether the displayed part is a video and not a still image.
  bool get isVideo => part.kind != ImageKind.image;

  /// The URL of the displayed part on the server, without any `type` parameter.
  String get dataUrl => "${widget.baseUrl}/${part.name}";

  /// The rating filter of the album the image belongs to.
  int get minRating => widget.minRating ?? widget.image.owner?.minRating ?? 0;

  /// The previous image passing the rating filter, `null` at the album start.
  AbstractImage? get previous => previousVisible(widget.image, minRating);

  /// The next image passing the rating filter, `null` at the album end.
  AbstractImage? get next => nextVisible(widget.image, minRating);

  /// The album the displayed image belongs to, `null` where the transient
  /// owner link was never built.
  ///
  /// The whole album, because that is what is written back: the sidecar is one
  /// document, and a changed description is saved the way every other album
  /// edit is saved, see [editDescription] and [VAlbumClient.saveAlbum].
  AlbumInfo? get album => widget.image.owner;

  /// What the caller may do with that album, as the server answered it with
  /// the album itself (issue #49).
  Rights get rights => Rights.of(album);

  /// Whether a description may be edited here at all.
  ///
  /// The `edit` right, an album to write into, and never inside a share link:
  /// a link is not an account, see issue #51.
  bool get mayEditDescription =>
      album != null &&
      rights.mayEdit &&
      ShareSession.of(context) == null &&
      (widget.editPath != null || inEditSession);

  /// Whether the album this image belongs to is being edited (issue #80).
  ///
  /// The session lives with the router, keyed by the album's path, so a trip
  /// into an image and back finds the album still being edited; what the album
  /// view reads off it, this view is handed, see `app.dart`.
  bool get inEditSession => widget.editing;

  /// Opens the description dialog on the displayed image (issue #80).
  ///
  /// The dialog the tile editor of the album opens, on the image that is being
  /// looked at — which is where a description is written in practice, and for
  /// a group member in detail mode it is that member's own description. A
  /// caller who may not change the album is told so rather than being left
  /// with a gesture that does nothing, see [notEditableMessage].
  Future<void> editDescription() async {
    var self = album;
    if (self == null) {
      // No album behind this image: nothing to write into, and nothing this
      // view could say that would help.
      return;
    }
    if (self.kind == AlbumKind.inbox) {
      // A photograph in an inbox carries no description, see issue #136: the
      // dialog says what the image is and offers nothing to type in — the
      // description belongs to the album the photograph ends up in.
      await showImageProperties(context, part, editable: false);
      return;
    }
    if (!mayEditDescription) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            AppLocalizations.of(context)!.notEditableMessage,
            key: const Key("image-not-editable"),
          ),
          duration: const Duration(seconds: 4),
        ),
      );
      return;
    }
    await _askForDescription(self, part, part.comment);
  }

  /// Asks for the description of [image], starting from [initial], and applies
  /// what comes back.
  ///
  /// A refused write comes back here with the text that was typed: the server
  /// said why, and what was written must not be lost to a failed request.
  Future<void> _askForDescription(
    AlbumInfo owner,
    ImagePart image,
    String initial,
  ) async {
    // The one image properties dialog, which the album's tile editor opens as
    // well: what it shows of an image is composed in one place, see issue
    // #122 and `image_properties.dart`.
    var text = await showImageProperties(
      context,
      image,
      initialDescription: initial,
    );
    if (text == null || !mounted || text == image.comment) {
      return;
    }
    var before = image.comment;
    setState(() => image.comment = text);
    if (inEditSession) {
      // The album is being edited: this belongs in the same buffer as every
      // other edit, and is written when the album is saved.
      widget.onEdited?.call();
      return;
    }
    var path = widget.editPath;
    if (path == null) {
      // Guarded by [mayEditDescription]; here for the reader.
      return;
    }
    if (refuseWhileOffline(context)) {
      setState(() => image.comment = before);
      return;
    }
    var messenger = ScaffoldMessenger.of(context);
    try {
      await widget.client.saveAlbum(path, owner);
    } catch (error) {
      if (!mounted) {
        return;
      }
      // Nothing was stored, so nothing has changed: the caption says what the
      // server holds, not what was attempted.
      setState(() => image.comment = before);
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            error is VAlbumException ? error.message : "$error",
            key: const Key("image-description-failed"),
          ),
          backgroundColor: Colors.red.shade700,
          duration: const Duration(seconds: 8),
        ),
      );
      // The text is not thrown away with the request: the dialog comes back
      // holding it, so that a retry costs no typing.
      await _askForDescription(owner, image, text);
    }
  }

  // --- Taking a copy of the original, see issue #164. ---

  /// Whether the original of this picture may be taken out of the album.
  ///
  /// The `download` right on the album, whoever holds it — a member, and a
  /// share link made with it alike: the link's rights are what it was made
  /// with, and the server answers them as the album's rights.
  bool get mayDownloadOriginal => album != null && rights.mayDownload;

  /// Fetches the original of the shown picture and hands it to the platform,
  /// see `downloads.dart`.
  Future<void> downloadOriginal() => runDownload(context, () async {
        var file = await widget.client
            .downloadOriginal("${widget.baseUrl}/${part.name}");
        var outcome = await downloadSaver.save(file);
        return DownloadResult(
          count: outcome == SaveOutcome.saved ? 1 : 0,
          name: file.name,
          cancelled: outcome == SaveOutcome.cancelled,
        );
      });

  // --- Naming the faces of the picture, see issue #147. ---

  /// Whether this picture's faces may be named here at all.
  ///
  /// The `edit` right, a space that looks for faces, an album to post to, and
  /// never inside a share link — a link is answered no face at all and is not
  /// an account (issues #124 and #51). A video is left out because the
  /// detector never looks at one (issue #123): there is nothing to name and
  /// nothing to draw on.
  bool get mayEditPersons =>
      !isVideo &&
      album != null &&
      widget.editPath != null &&
      rights.mayEdit &&
      CallerInfo.facesOf(context) &&
      ShareSession.of(context) == null;

  /// Whether the mode is on, see [_editPersons].
  bool get editPersons => _editPersons && mayEditPersons;

  /// Enters the mode in which every face is marked and can be decided about.
  void enterEditPersons() {
    if (!mayEditPersons) {
      return;
    }
    setState(() => _editPersons = true);
    _loadPeople();
  }

  /// Leaves the mode, by the Done control and by `Escape`.
  void leaveEditPersons() {
    if (!_editPersons) {
      return;
    }
    setState(() {
      _editPersons = false;
      _markingFaces = false;
      _marked = null;
      _markStart = null;
      _adjusting = null;
    });
  }

  /// The register, loaded once per client and held, see [PeopleRegistry].
  Future<Map<String, Person>> _loadPeople() async {
    var registry = PeopleRegistry.of(widget.client);
    var loaded = await registry.load();
    if (mounted && !identical(loaded, _people)) {
      setState(() => _people = loaded);
    }
    return loaded;
  }

  /// Every face of the picture, which is what the mode shows.
  ///
  /// All of them, not only the confirmed ones of issue #145: the mode is where
  /// a suggestion is answered, an unknown face is named and a false detection
  /// is said to be one, so a face that is not drawn is a face nobody can
  /// correct.
  List<FaceInfo> get editableFaces =>
      ShareSession.of(context) != null ? const [] : part.faces;

  /// What stands over a face box, `null` where there is nothing to say.
  ///
  /// The person for a confirmation, the question of issue #127 for a
  /// suggestion, and nothing for a face nobody has said anything about — and
  /// nothing for a decision *against* somebody either: a rejected face and a
  /// false detection are drawn dimmed, which is the statement.
  String? faceLabel(FaceInfo face, AppLocalizations l10n) {
    var person = _people[face.person];
    if (person == null) {
      return null;
    }
    if (face.confirmed) {
      // What they are called on a photograph, and this is one (issue #146).
      return displayName(person);
    }
    if (face.state == FaceState.undecided) {
      return l10n.personsSuggestedHeading(displayName(person));
    }
    return null;
  }

  /// Asks what is to happen to the given face, and does it (issue #147).
  ///
  /// One sheet with the four decisions the face editor of issue #126 knows,
  /// each of which posts at once: there is no buffer beside the editor's and
  /// no Save here, the doctrine of the inbox and of issue #121's properties.
  Future<void> decideFace(FaceInfo face) async {
    var l10n = AppLocalizations.of(context)!;
    var suggested = !face.confirmed &&
        face.state == FaceState.undecided &&
        _people[face.person] != null;
    var decided = face.state != FaceState.undecided;
    var chosen = await showModalBottomSheet<String>(
      context: context,
      builder: (context) => SafeArea(
        key: const Key("face-decision"),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              dense: true,
              title: Text(
                l10n.viewerFaceDecision,
                style: Theme.of(context).textTheme.labelLarge,
              ),
            ),
            ListTile(
              key: const Key("face-decision-name"),
              leading: const Icon(Icons.person_outline),
              title: Text(l10n.personsNameEntry),
              onTap: () => Navigator.of(context).pop("name"),
            ),
            if (suggested)
              ListTile(
                key: const Key("face-decision-confirm"),
                leading: const Icon(Icons.check),
                title: Text(l10n.personsConfirmSuggestion),
                onTap: () => Navigator.of(context).pop("confirm"),
              ),
            ListTile(
              key: const Key("face-decision-not-a-face"),
              leading: const Icon(Icons.block),
              title: Text(l10n.personsNotAFaceEntry),
              onTap: () => Navigator.of(context).pop("not-a-face"),
            ),
            if (decided)
              ListTile(
                key: const Key("face-decision-forget"),
                leading: const Icon(Icons.undo),
                title: Text(l10n.personsForgetEntry),
                onTap: () => Navigator.of(context).pop("forget"),
              ),
          ],
        ),
      ),
    );
    if (chosen == null || !mounted) {
      return;
    }
    switch (chosen) {
      case "name":
        var person = await choosePerson();
        if (person == null || !mounted) {
          return;
        }
        await tagFace(
          face: face.index,
          person: person.id,
          state: FaceState.confirmed,
        );
        break;
      case "confirm":
        await tagFace(
          face: face.index,
          person: face.person,
          state: FaceState.confirmed,
        );
        break;
      case "not-a-face":
        await tagFace(
          face: face.index,
          person: "",
          state: FaceState.notAFace,
        );
        break;
      case "forget":
        // The way back out of a decision, see issue #138: whom it was about is
        // none of the request's business.
        await tagFace(
          face: face.index,
          person: "",
          state: FaceState.undecided,
        );
        break;
    }
  }

  /// Asks who somebody is, offering the register and a new person (issue #125).
  ///
  /// The very chooser of the face editor, so that naming a face is the same
  /// thing wherever it is done; a person created here is remembered in the
  /// register, so the label under the face can be written at once.
  Future<Person?> choosePerson() async {
    var l10n = AppLocalizations.of(context)!;
    var people = await _loadPeople();
    if (!mounted) {
      return null;
    }
    var chosen = await showDialog<Person>(
      context: context,
      builder: (context) => PersonChooser(
        people: _distinct(people),
        title: l10n.personsUnknownGroup,
        // Who already carries a face in this album stands at the top, see
        // issue #150 — the people of the shown album, not of this picture.
        inAlbum: _personsOfAlbum,
        onCreate: (name) async {
          try {
            return await widget.client.createPerson(name);
          } catch (error) {
            _refused(error);
            return null;
          }
        },
      ),
    );
    if (chosen == null) {
      return null;
    }
    PeopleRegistry.of(widget.client).remember(chosen);
    if (mounted) {
      setState(() => _people = PeopleRegistry.of(widget.client).people);
    }
    return chosen;
  }

  /// Who already carries a face of the album this picture belongs to (#150).
  ///
  /// A confirmation and a suggestion of issue #127 alike: both put a name on
  /// the screen, and both make that person one of the few this album is about.
  Set<String> get _personsOfAlbum {
    var self = album;
    if (self == null) {
      return const {};
    }
    return {
      for (var image in _imagesOf(self))
        for (var face in image.faces)
          if (face.person.isNotEmpty) face.person,
    };
  }

  /// The people of the register, each of them once.
  ///
  /// The register is keyed by every id a face may name, the ids merged away
  /// among them (see [PeopleRegistry]), so the same person stands in it
  /// several times; a chooser that offered them twice would ask which of two
  /// identical lines is meant.
  static List<Person> _distinct(Map<String, Person> people) {
    var byId = <String, Person>{};
    for (var person in people.values) {
      byId[person.id] = person;
    }
    return byId.values.toList();
  }

  /// Stores one decision about one face, at once (issue #147).
  ///
  /// Refused offline with the usual reason and nothing else attempted; a
  /// refusal of the server is said in the server's own words and changes
  /// nothing, because nothing was changed here before the answer came back.
  /// What the answer carries is adopted, see [_adopt]: the faces of this
  /// photograph as the server now has them, so the labels say what is stored.
  Future<bool> tagFace({
    int face = 0,
    MarkedBox? box,
    required String person,
    required FaceState state,
  }) async {
    var path = widget.editPath;
    if (path == null || refuseWhileOffline(context)) {
      return false;
    }
    var messenger = ScaffoldMessenger.of(context);
    var assignment = FaceAssignment(
      image: part.name,
      face: face,
      person: person,
      state: state,
      // Where a box is given the index is ignored, and the box is the frame
      // the picture is shown in, see `FaceAssignment.x` and issue #142.
      x: box?.x ?? 0,
      y: box?.y ?? 0,
      w: box?.w ?? 0,
      h: box?.h ?? 0,
    );
    try {
      var answer = await widget.client.tagFaces(path, [assignment]);
      if (!mounted) {
        return false;
      }
      _adopt(answer);
      return true;
    } catch (error) {
      if (!mounted) {
        return false;
      }
      _tagFailed(messenger, error);
      return false;
    }
  }

  /// Says in the server's own words that a write about a face was refused.
  void _tagFailed(ScaffoldMessengerState messenger, Object error) {
    messenger.showSnackBar(
      SnackBar(
        content: Text(
          error is VAlbumException ? error.message : "$error",
          key: const Key("face-tag-failed"),
        ),
        backgroundColor: Colors.red.shade700,
        duration: const Duration(seconds: 8),
      ),
    );
  }

  /// Gives the given face the given box, at once (issue #157).
  ///
  /// `?action=adjust-faces`: the face by its index and the new box, in the
  /// frame the faces are answered in, together with the decision the face
  /// stands under — a confirmation or a rejection travels along with its
  /// person, and anything else (a face nobody decided about, a suggestion of
  /// issue #127, which is no decision) is sent undecided. Refused offline with
  /// the usual reason; a refusal of the server is said in its own words and
  /// changes nothing, so the box snaps back to where the server has it.
  Future<bool> adjustFace(FaceInfo face, MarkedBox box) async {
    var path = widget.editPath;
    if (path == null || refuseWhileOffline(context)) {
      return false;
    }
    var messenger = ScaffoldMessenger.of(context);
    var decided =
        face.state == FaceState.confirmed || face.state == FaceState.rejected;
    var assignment = FaceAssignment(
      image: part.name,
      face: face.index,
      person: decided ? face.person : "",
      state: decided ? face.state : FaceState.undecided,
      x: box.x,
      y: box.y,
      w: box.w,
      h: box.h,
    );
    try {
      var answer =
          await widget.client.adjustFaces(path, TagFaces(faces: [assignment]));
      if (!mounted) {
        return false;
      }
      _adopt(answer);
      return true;
    } catch (error) {
      if (!mounted) {
        return false;
      }
      _tagFailed(messenger, error);
      return false;
    }
  }

  /// Begins moving (without [corner]) or resizing the box of [face] (#157).
  void _beginAdjusting(
    ImageTransform tx,
    FaceInfo face,
    FaceCorner? corner,
    Offset origin,
  ) {
    if (_adjusting?.posting ?? false) {
      // One adjustment at a time: the answer to the last one is not in yet.
      return;
    }
    setState(() {
      _adjusting = _FaceAdjustment(
        part.name,
        face,
        corner,
        pageRectOfBox(tx, face.x, face.y, face.w, face.h),
        origin,
      );
    });
  }

  /// Follows the pointer, [delta] away from where it went down (#157).
  void _updateAdjusting(ImageTransform tx, Offset delta) {
    var adjusting = _adjusting;
    if (adjusting == null || adjusting.posting) {
      return;
    }
    setState(() {
      adjusting.moved = math.max(
        adjusting.moved,
        math.max(delta.dx.abs(), delta.dy.abs()),
      );
      adjusting.rect = adjustedRect(
        adjusting.start,
        adjusting.corner,
        delta,
        pageRectOfBox(tx, 0, 0, 1, 1),
      );
    });
  }

  /// Lets go of the box: a drag too short is a tap, anything else is posted.
  Future<void> _endAdjusting(ImageTransform tx) async {
    var adjusting = _adjusting;
    if (adjusting == null || adjusting.posting) {
      return;
    }
    if (adjusting.image != part.name || adjusting.moved < _adjustSlop) {
      setState(() => _adjusting = null);
      if (adjusting.image == part.name) {
        await decideFace(adjusting.face);
      }
      return;
    }
    var box = markedBox(tx, adjusting.rect.topLeft, adjusting.rect.bottomRight);
    if (box == null) {
      setState(() => _adjusting = null);
      return;
    }
    setState(() => adjusting.posting = true);
    try {
      await adjustFace(adjusting.face, box);
    } finally {
      if (mounted && identical(_adjusting, adjusting)) {
        // Drawn from the faces again: where the answer put it, or — refused —
        // where it was.
        setState(() => _adjusting = null);
      }
    }
  }

  /// Drops a drag the gesture arena took away.
  void _cancelAdjusting() {
    var adjusting = _adjusting;
    if (adjusting != null && !adjusting.posting) {
      setState(() => _adjusting = null);
    }
  }

  /// The gestures of a face box or of one of its handles (issue #157).
  ///
  /// Three recognisers share the arena, and the kind of pointer decides:
  ///
  /// * a **tap** opens the face's sheet, as it did before (#147);
  /// * a **mouse** (or a pen) drags at once — the pan recogniser is offered
  ///   those pointers only, and wins the arena as soon as the pointer has
  ///   moved a precise pointer's slop; a drag that ends within [_adjustSlop]
  ///   is still a tap, so a click whose hand shook opens the sheet;
  /// * a **finger** drags after a long press: a plain swipe on a box is no
  ///   move, because a finger swipes to page and to pan and a box is easily
  ///   hit on the way; holding it first says "this box". A long press that
  ///   does not move is a tap as well.
  ///
  /// A drag that starts on a box never reaches the picture beneath — the box
  /// is the hit, the picture's own pan is not in the arena at all — so the
  /// picture stands still while a box is dragged, as it does for the marking
  /// tool.
  Map<Type, GestureRecognizerFactory> _adjustGestures(
    ImageTransform tx,
    FaceInfo face,
    FaceCorner? corner,
  ) {
    var adjustable = face.state != FaceState.notAFace;
    return {
      TapGestureRecognizer:
          GestureRecognizerFactoryWithHandlers<TapGestureRecognizer>(
        () => TapGestureRecognizer(),
        (recognizer) => recognizer.onTap = () => decideFace(face),
      ),
      if (adjustable)
        PanGestureRecognizer:
            GestureRecognizerFactoryWithHandlers<PanGestureRecognizer>(
          () => PanGestureRecognizer(supportedDevices: const {
            PointerDeviceKind.mouse,
            PointerDeviceKind.stylus,
            PointerDeviceKind.invertedStylus,
            PointerDeviceKind.trackpad,
          }),
          (recognizer) => recognizer
            ..dragStartBehavior = DragStartBehavior.down
            ..onStart = ((details) =>
                _beginAdjusting(tx, face, corner, details.globalPosition))
            ..onUpdate = ((details) {
              var origin = _adjusting?.origin;
              if (origin != null) {
                _updateAdjusting(tx, details.globalPosition - origin);
              }
            })
            ..onEnd = ((details) => _endAdjusting(tx))
            ..onCancel = _cancelAdjusting,
        ),
      if (adjustable)
        LongPressGestureRecognizer:
            GestureRecognizerFactoryWithHandlers<LongPressGestureRecognizer>(
          () => LongPressGestureRecognizer(),
          (recognizer) => recognizer
            ..onLongPressStart = ((details) {
              HapticFeedback.selectionClick();
              _beginAdjusting(tx, face, corner, details.globalPosition);
            })
            ..onLongPressMoveUpdate =
                ((details) => _updateAdjusting(tx, details.offsetFromOrigin))
            ..onLongPressEnd = ((details) => _endAdjusting(tx))
            ..onLongPressCancel = _cancelAdjusting,
        ),
    };
  }

  /// Takes the faces of the answered album over onto the picture shown.
  ///
  /// The answer is the album as this caller is answered it, so its copy of
  /// this photograph carries the decisions *and* what issue #127 makes of them
  /// now. Only the faces and the decisions travel: the part on the screen is
  /// the one the album view holds, linked to its neighbours, and replacing it
  /// would cut the picture out of the album it is being paged through.
  void _adopt(AlbumInfo? answer) {
    if (answer == null) {
      return;
    }
    for (var image in _imagesOf(answer)) {
      if (image.name != part.name) {
        continue;
      }
      setState(() {
        part.faces = image.faces;
        part.tags = image.tags;
      });
      return;
    }
  }

  /// Every photograph of the given album, the members of a group included.
  static List<ImagePart> _imagesOf(AlbumInfo album) {
    var result = <ImagePart>[];
    for (var entry in album.parts) {
      if (entry is ImagePart) {
        result.add(entry);
      } else if (entry is ImageGroup) {
        result.addAll(entry.images);
      }
    }
    return result;
  }

  /// Says what the server said about a refused request about a person.
  void _refused(Object error) {
    if (!mounted) {
      return;
    }
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          error is VAlbumException ? error.message : "$error",
          key: const Key("face-person-failed"),
        ),
        backgroundColor: Colors.red.shade700,
        duration: const Duration(seconds: 8),
      ),
    );
  }

  /// The group the displayed image belongs to, `null` if it is a single image.
  ImageGroup? get group {
    var self = widget.image;
    return self is ImageGroup ? self : part.group;
  }

  /// Whether the failure of the current picture was already reported, so that
  /// a picture that keeps failing says so once (issue #95).
  bool _failureReported = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // The first thing a viewer does, before anybody pages anywhere.
    prefetchNeighbours();
  }

  @override
  void didUpdateWidget(ImageView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.image != widget.image) {
      // Start over with the fitted view.
      _stopSnapBack();
      _transform = null;
      _failureReported = false;
      // The neighbours of the image now shown are two others, see issue #101.
      prefetchNeighbours();
    }
  }

  /// The picture of the displayed image, see [viewerPicture].
  ImageProvider get picture => pictureOf(part);

  /// The picture of any image of this album, the displayed one included.
  ///
  /// The rights are the album's, and the neighbours live in the same album, so
  /// what the caller may have of them is what they may have of this one.
  ImageProvider pictureOf(ImagePart image) => viewerPicture(
        widget.client,
        "${widget.baseUrl}/${image.name}",
        mayDownload: rights.mayDownload,
      );

  /// The pictures held decoded for this viewer, by image name (issue #148).
  ///
  /// The shown one and its two neighbours, each kept alive by a listener on
  /// its own [ImageStream], see [prefetchNeighbours].
  final Map<String, _PinnedPicture> _pinned = {};

  /// Fetches, decodes and **pins** the pictures around the shown one (#101).
  ///
  /// Paging then paints the neighbour from the [ImageCache] in the very frame
  /// the route changes, instead of leaving the screen empty for as long as an
  /// original takes to arrive. Exactly three — the shown picture and its two
  /// neighbours, because the memory of a phone is not an album — and the same
  /// provider the display would use ([pictureOf]), so that the picture really
  /// is a cache hit and not a second download.
  ///
  /// Caching alone was not enough (issue #148): the [ImageCache] drops a
  /// decoded picture that does not fit into `maximumSizeBytes`, and a decoded
  /// 24 MP original is 96 MB of the 100 MB budget, so the second prefetch
  /// evicted the first and paging showed the underlay while the original was
  /// fetched and decoded again. A completer with a **live listener** is held
  /// regardless of that budget and is handed out by `putIfAbsent`
  /// synchronously, so the [Image] of [buildContent] finds it in its very
  /// first build (`wasSynchronouslyLoaded`) and paints the sharp picture at
  /// once. Raising the budget instead would cost the memory on every device,
  /// and decoding at display size would take the detail zooming needs.
  ///
  /// A video is left alone: what the viewer shows of one is the player, whose
  /// poster is the thumbnail the album has anyway. A pin that fails is
  /// released and never reported — nobody asked for it.
  void prefetchNeighbours() {
    var wanted = <String, ImagePart>{};
    for (var image in [widget.image, previous, next]) {
      if (image == null) {
        continue;
      }
      var picture = ToImage.toImage(image);
      if (picture.kind != ImageKind.image) {
        continue;
      }
      wanted[picture.name] = picture;
    }
    // What has paged out of reach lets go of its memory.
    for (var name in _pinned.keys.toList()) {
      if (!wanted.containsKey(name)) {
        _pinned.remove(name)!.release();
      }
    }
    var configuration = createLocalImageConfiguration(context);
    for (var entry in wanted.entries) {
      _pinned.putIfAbsent(
        entry.key,
        () => _PinnedPicture(
          pictureOf(entry.value).resolve(configuration),
          // A picture that never arrives is simply not held, see above. The
          // pin lets go of itself, so a failure that is answered before the
          // entry is even stored leaves nothing behind.
          onFailed: () => _pinned.remove(entry.key),
        ),
      );
    }
  }

  /// Lets go of every picture this viewer held, see [prefetchNeighbours].
  void _releasePictures() {
    for (var pin in _pinned.values) {
      pin.release();
    }
    _pinned.clear();
  }

  /// Says once that the picture could not be delivered, see
  /// [pictureFailedMessage].
  ///
  /// Called from a builder, so the message is shown after the frame: a
  /// snack bar may not be put up while the tree is being built.
  void _reportFailure() {
    if (_failureReported) {
      return;
    }
    _failureReported = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            AppLocalizations.of(context)!.pictureFailedMessage,
            key: const Key("image-failed"),
          ),
          duration: const Duration(seconds: 6),
        ),
      );
    });
  }

  /// The transform of the image in a viewport of the given size.
  ImageTransform transform(Size page) {
    var result = _transform;
    if (result == null ||
        result.pageWidth != page.width ||
        result.pageHeight != page.height) {
      result = _transform = ImageTransform.ofImage(
        part,
        pageWidth: page.width,
        pageHeight: page.height,
      );
    }
    return result;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: CallbackShortcuts(
        bindings: <ShortcutActivator, VoidCallback>{
          const SingleActivator(LogicalKeyboardKey.arrowLeft): showPrevious,
          const SingleActivator(LogicalKeyboardKey.arrowRight): showNext,
          const SingleActivator(LogicalKeyboardKey.home): showFirst,
          const SingleActivator(LogicalKeyboardKey.end): showLast,
          const SingleActivator(LogicalKeyboardKey.arrowUp): showParent,
          const SingleActivator(LogicalKeyboardKey.arrowDown): showGroup,
          // Not a touch-only feature: a long press is what a phone has, `e`
          // is what a keyboard has (issue #80).
          const SingleActivator(LogicalKeyboardKey.keyE): editDescription,
          // The way out of the edit-persons mode a keyboard has (issue #147).
          const SingleActivator(LogicalKeyboardKey.escape): leaveEditPersons,
        },
        child: Focus(
          autofocus: true,
          child: LayoutBuilder(
            builder: (context, constraints) {
              var page = constraints.biggest;
              return Stack(
                fit: StackFit.expand,
                children: [
                  isVideo ? buildVideoViewer() : buildViewer(page),
                  // The faces, in the coordinates of the page (issue #147).
                  if (editPersons) ...buildFaceEditing(transform(page)),
                  ...buildOverlay(context),
                ],
              );
            },
          ),
        ),
      ),
    );
  }

  /// The image itself, with the gesture handling.
  Widget buildViewer(Size page) {
    var tx = transform(page);

    return Listener(
      onPointerSignal: (event) {
        if (event is PointerScrollEvent) {
          setState(() {
            tx.zoom(
              event.scrollDelta.dy.sign,
              event.localPosition.dx,
              event.localPosition.dy,
            );
          });
        }
      },
      child: GestureDetector(
        onTapUp: (details) => setState(() {
          tx.toggle(details.localPosition.dx, details.localPosition.dy);
        }),
        // The description of the image, where the image is looked at
        // (issue #80).
        onLongPress: editDescription,
        onScaleStart: (details) {
          _stopSnapBack();
          _gestureScale = tx.scale;
          _gestureTx = tx.tx;
          _gestureTy = tx.ty;
          _gestureFocus = details.localFocalPoint;
          _multiTouch = details.pointerCount > 1;
          _dragDx = 0;
          // A fitted image pages; a zoomed one pans, as it always did.
          _axis = _multiTouch || tx.scale > tx.fitScale
              ? _DragAxis.free
              : _DragAxis.undecided;
        },
        onScaleUpdate: (details) {
          if (details.pointerCount > 1) {
            // A second finger turns the drag into a pinch: free pan and zoom.
            _multiTouch = true;
            _axis = _DragAxis.free;
          }
          if (_axis != _DragAxis.free) {
            dragFitted(tx, details.localFocalPoint);
            return;
          }
          setState(() {
            var newScale = _gestureScale * details.scale;

            // The point of the image that was grabbed, in image pixels.
            var imgX = (_gestureFocus.dx - _gestureTx) / _gestureScale;
            var imgY = (_gestureFocus.dy - _gestureTy) / _gestureScale;

            var focus = details.localFocalPoint;
            tx.setCustom(
              focus.dx - imgX * newScale,
              focus.dy - imgY * newScale,
              newScale,
            );
          });
        },
        onScaleEnd: (details) => onDragEnd(tx, details),
        child: ClipRect(
          child: Stack(
            clipBehavior: Clip.none,
            children: buildLayers(tx),
          ),
        ),
      ),
    );
  }

  /// The faces of the picture, drawn in the coordinates of the page (#147).
  ///
  /// Deliberately *not* in the picture's own layer, where the hover regions of
  /// issue #145 hang: that layer is turned by [ImageTransform.matrix], and a
  /// name drawn in it would stand on its head on a photograph turned upside
  /// down. The boxes are therefore mapped through the very same matrix
  /// ([pageRectOfBox]) and laid out here, upright, where a chip is a chip and
  /// a tap is a tap — and they follow every zoom and every pan all the same,
  /// because the matrix is asked again in every build.
  ///
  /// The order is: the boxes, then the rectangle being drawn, then — while the
  /// marking tool is on — the surface that takes the drag. So a tap reaches a
  /// face while the tool is off, and the drawing takes the whole picture while
  /// it is on; the viewer's own pan is suspended for as long as that, which is
  /// what the tool is.
  List<Widget> buildFaceEditing(ImageTransform tx) {
    var l10n = AppLocalizations.of(context)!;
    var faces = editableFaces;
    var drawn = _marked;
    return [
      for (var face in faces) ...buildFaceBox(tx, face, l10n),
      if (drawn != null)
        Positioned.fromRect(
          // Keyed like everything in this layer: the list grows and shrinks
          // while a face is being drawn, and an element that moved to another
          // slot would be built anew — which would take the very gesture
          // recogniser away that is drawing the rectangle.
          key: const Key("face-marking-slot"),
          rect: drawn,
          child: IgnorePointer(
            child: DecoratedBox(
              key: const Key("face-marking"),
              decoration: BoxDecoration(
                border: Border.all(color: Colors.lightBlueAccent, width: 2),
                color: Colors.lightBlueAccent.withValues(alpha: 0.15),
              ),
            ),
          ),
        ),
      if (_markingFaces)
        Positioned.fill(
          key: const Key("face-marking-slot-surface"),
          child: GestureDetector(
            key: const Key("face-marking-surface"),
            // The whole picture, and nothing under it: while a face is being
            // drawn the picture does not pan, which is what makes the drawing
            // possible at all.
            behavior: HitTestBehavior.opaque,
            // The rectangle starts where the finger went down, not where the
            // drag was recognised.
            dragStartBehavior: DragStartBehavior.down,
            onPanStart: (details) => setState(() {
              _markStart = details.localPosition;
              _marked = details.localPosition & Size.zero;
            }),
            onPanUpdate: (details) => setState(() {
              var start = _markStart;
              if (start != null) {
                // Spanned between the start and the pointer, whichever way
                // the drag goes (#155).
                _marked = Rect.fromPoints(start, details.localPosition);
              }
            }),
            onPanEnd: (details) => markFace(tx),
            onPanCancel: () => setState(() {
              _marked = null;
              _markStart = null;
            }),
            // A tap is not a slipped drag: on a face it opens that face's
            // sheet, as it does with the tool off, and anywhere else it is a
            // click that marks a face (#155).
            onTapUp: (details) => tapWhileMarking(tx, details.localPosition),
          ),
        ),
    ];
  }

  /// One face: its box, and the chip naming it where there is a name.
  List<Widget> buildFaceBox(
    ImageTransform tx,
    FaceInfo face,
    AppLocalizations l10n,
  ) {
    var adjusting = _adjusting;
    var adjusted = adjusting != null &&
        adjusting.image == part.name &&
        adjusting.face.index == face.index;
    var rect = adjusted
        ? adjusting.rect
        : pageRectOfBox(tx, face.x, face.y, face.w, face.h);
    var label = faceLabel(face, l10n);
    var handles = face.state != FaceState.notAFace && !_markingFaces;
    // A decision *against* somebody is drawn dimmed rather than written out:
    // "not this person" and "no face at all" are statements about what is not
    // there, and a name is what a chip is for.
    var refused =
        face.state == FaceState.rejected || face.state == FaceState.notAFace;
    var colour = refused
        ? Colors.white30
        : face.confirmed
            ? Colors.white
            : Colors.white70;
    return [
      Positioned.fromRect(
        key: Key("face-box-slot-${face.index}"),
        rect: rect,
        child: Semantics(
          label: handles ? l10n.viewerAdjustFaceHint : null,
          child: RawGestureDetector(
            key: Key("face-box-${face.index}"),
            behavior: HitTestBehavior.opaque,
            gestures: _adjustGestures(tx, face, null),
            child: DecoratedBox(
              decoration: BoxDecoration(
                border: Border.all(
                  color: adjusted ? Colors.lightBlueAccent : colour,
                  width: face.confirmed || adjusted ? 2 : 1.5,
                ),
              ),
              child: const SizedBox.expand(),
            ),
          ),
        ),
      ),
      // The handles of issue #157, over the box's corners so that a corner is
      // taken by its handle and the inside by the box.
      if (handles)
        for (var corner in FaceCorner.values)
          Positioned.fromRect(
            key: Key("face-handle-slot-${face.index}-${corner.keyName}"),
            rect: Rect.fromCenter(
              center: corner.of(rect),
              width: _handleSize,
              height: _handleSize,
            ),
            child: RawGestureDetector(
              key: Key("face-handle-${face.index}-${corner.keyName}"),
              behavior: HitTestBehavior.opaque,
              gestures: _adjustGestures(tx, face, corner),
              child: Center(
                child: Container(
                  width: 10,
                  height: 10,
                  decoration: BoxDecoration(
                    color: Colors.black54,
                    border: Border.all(
                      color: adjusted ? Colors.lightBlueAccent : colour,
                      width: 1.5,
                    ),
                  ),
                ),
              ),
            ),
          ),
      if (label != null)
        Positioned(
          key: Key("face-label-slot-${face.index}"),
          left: rect.left,
          // Over the box, and inside the page where the box touches the top.
          top: math.max(0, rect.top - _faceLabelHeight),
          child: IgnorePointer(
            child: Container(
              key: Key("face-label-${face.index}"),
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              color: Colors.black54,
              child: Text(
                label,
                style: const TextStyle(color: Colors.white, fontSize: 12),
              ),
            ),
          ),
        ),
    ];
  }

  /// What a rectangle drawn on the picture becomes: a face of somebody (#147).
  ///
  /// A rectangle too small to see is a tap that slipped and draws nothing. The
  /// chooser decides whether anything is posted at all — cancelling it leaves
  /// the picture as it was, because nothing was written anywhere.
  Future<void> markFace(ImageTransform tx) async {
    var drawn = _marked;
    setState(() {
      _marked = null;
      _markStart = null;
    });
    if (drawn == null) {
      return;
    }
    if (drawn.width < _minMarkedFace || drawn.height < _minMarkedFace) {
      // Never dropped silently (#155): a box that vanished without a word
      // looks like a tool that does not work.
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            AppLocalizations.of(context)!.viewerMarkFaceTooSmall,
            key: const Key("face-marking-too-small"),
          ),
        ),
      );
      return;
    }
    var box = markedBox(tx, drawn.topLeft, drawn.bottomRight);
    if (box == null) {
      return;
    }
    await markRegion(box);
  }

  /// What a tap does while the marking tool is on (#155).
  ///
  /// On a face it opens that face's sheet — a new face inside a face is never
  /// what was meant, and the tool must not take the faces away from the tap —
  /// the smallest box under the point winning, which is the face and not the
  /// group around it. Anywhere else it marks a face by a click: a small box
  /// centred on the point, which the server widens into the region it looks
  /// for a face in.
  Future<void> tapWhileMarking(ImageTransform tx, Offset point) async {
    FaceInfo? hit;
    double hitArea = double.infinity;
    for (var face in editableFaces) {
      var rect = pageRectOfBox(tx, face.x, face.y, face.w, face.h);
      if (!rect.contains(point)) {
        continue;
      }
      var area = rect.width * rect.height;
      if (area < hitArea) {
        hit = face;
        hitArea = area;
      }
    }
    if (hit != null) {
      await decideFace(hit);
      return;
    }
    var half = const Offset(_clickedFace / 2, _clickedFace / 2);
    var box = markedBox(tx, point - half, point + half);
    if (box == null) {
      return;
    }
    await markRegion(box);
  }

  /// Marks a region of the picture as a face and names it (#155).
  ///
  /// The box is posted at once, undecided: the server looks for a face in the
  /// original around it and answers what it found there — the detector's own
  /// box, with a suggestion of issue #127 where it recognises somebody — or
  /// the box itself as a region nobody decided about. A suggestion is shown on
  /// the box and asks nothing more: one tap on the box confirms it. Where
  /// there is none the chooser asks who it is, and the choice is posted for
  /// that face; cancelling it leaves the region, undecided.
  Future<void> markRegion(MarkedBox box) async {
    var posted = await tagFace(
      box: box,
      person: "",
      state: FaceState.undecided,
    );
    if (!posted || !mounted) {
      return;
    }
    var face = faceAtBox(part.faces, box);
    if (face == null) {
      return;
    }
    if (!face.confirmed &&
        face.state == FaceState.undecided &&
        face.person.isNotEmpty) {
      // A suggestion: the chip on the box says whom, and the box confirms.
      await _loadPeople();
      return;
    }
    if (face.state != FaceState.undecided) {
      // Somebody decided about this face already; its sheet is one tap away.
      return;
    }
    var person = await choosePerson();
    if (person == null || !mounted) {
      return;
    }
    await tagFace(
      face: face.index,
      person: person.id,
      state: FaceState.confirmed,
    );
  }

  /// What is drawn into the viewport: the thumbnail, and the picture over it.
  ///
  /// Two layers, because a picture that is not there yet must not leave the
  /// screen empty (issue #101): the thumbnail of the album is already fetched
  /// and decoded, so it is painted in the very first frame and the picture
  /// appears over it as soon as its own first frame arrives. A switch from one
  /// image to the next is therefore thumbnail -> sharp, never blank -> sharp,
  /// and with the neighbour prefetched it is sharp at once.
  ///
  /// The thumbnail is asked for with the key the *tile* decoded it under
  /// (`asTileDecoded`, see [decodedThumbnailHeight] and issue #120): the tile
  /// decodes at the height it is drawn at, and an underlay asking for the raw
  /// provider instead was a second cache key over the very same download —
  /// opening an image from its tile fetched its thumbnail twice. An image no
  /// tile has drawn (a deep link straight into the viewer) has no such key and
  /// is fetched once, as before.
  List<Widget> buildLayers(ImageTransform tx) => [
        pictureLayer(
          tx,
          thumbnail(
            widget.client,
            dataUrl,
            key: const Key("image-thumbnail"),
            asTileDecoded: true,
            fit: BoxFit.fill,
          ),
        ),
        buildContent(tx),
      ];

  /// The picture itself, in the layer its coordinates belong to.
  Widget buildContent(ImageTransform tx) {
    var image = Image(
      // What a test asks for when it asks what the viewer shows: the
      // thumbnail beneath is a second [Image] in the same tree.
      key: const Key("image-picture"),
      image: picture,
      fit: BoxFit.fill,
      // A rebuild with the same picture keeps what is on the screen.
      gaplessPlayback: true,
      // Nothing until the first frame has decoded: what shows through is the
      // thumbnail beneath, see [buildLayers].
      frameBuilder: (context, child, frame, wasSynchronouslyLoaded) =>
          wasSynchronouslyLoaded || frame != null
              ? child
              : const SizedBox.expand(),
      // `Image` can only say *that* the picture failed. It is never a refused
      // right — the viewer asks for nothing this caller may not have, see
      // [viewerPicture] — so it is a failure, and failures speak once.
      errorBuilder: (context, error, stackTrace) {
        _reportFailure();
        return const SizedBox.expand();
      },
    );
    // The same layer either way: what the caller may have differs, where it
    // is drawn does not, see [pictureLayer] and issue #106.
    var named = namedFaces;
    if (named.isEmpty) {
      return pictureLayer(tx, image);
    }
    // The faces hang *in* the picture's own layer (issue #145): the layer is
    // the raw rectangle of the file and [ImageTransform.matrix] turns, scales
    // and pans it, so a box given as a fraction of that rectangle is turned by
    // exactly the transform the picture is turned by — there is no second
    // place the two could drift apart in — and the regions follow every zoom
    // and every pan for free.
    return pictureLayer(
      tx,
      FaceRegions(
        client: widget.client,
        faces: named,
        rawWidth: tx.rawWidth,
        rawHeight: tx.rawHeight,
        scale: tx.scale,
        swapsDimensions: PlaneTransform.of(tx.orientation).swapsDimensions,
        child: image,
      ),
    );
  }

  /// The faces of the displayed image that name somebody (issue #145).
  ///
  /// A confirmation and nothing else: a suggestion of issue #127 is what a
  /// recogniser believes, and believing is not knowing — the face editor is
  /// where a suggestion is answered. A face nobody decided about, one that was
  /// rejected and one somebody said is no face at all carry no person either.
  ///
  /// Never inside a share link. The server already answers a link caller no
  /// face at all (`Faces.maySee`), so this is the second lock on the same
  /// door: who somebody is, is bookkeeping among the members of a space, like
  /// the attribution of issue #96.
  List<FaceInfo> get namedFaces {
    if (ShareSession.of(context) != null || editPersons) {
      // In the mode of issue #147 every face is drawn, named and tappable, in
      // the coordinates of the page: the hover regions would be a second box
      // over the same face, saying the same thing.
      return const [];
    }
    return [
      for (var face in part.faces)
        if (face.confirmed && face.person.isNotEmpty) face,
    ];
  }

  /// The layer every picture of this viewer is drawn in.
  ///
  /// One layer, because every picture the server delivers stands in the same
  /// coordinates: the original carries the orientation of its own file, and
  /// so does a rendition — `PreviewCache` bakes the file's orientation into
  /// it and nothing else. [ImagePart.orientation] is the transform stored
  /// *beside* the image, on top of what the file says, and
  /// [ImageTransform.matrix] applies it together with the zoom and the pan,
  /// mapping the rectangle `(0, 0)` to ([ImageTransform.rawWidth],
  /// [ImageTransform.rawHeight]) onto the viewport.
  ///
  /// The preview rendition a caller without `download` is shown (issue #95)
  /// and the thumbnail kept beneath the picture (issue #101) therefore hang
  /// here as well: they used to skip the orientation and laid a rotated photo
  /// on its side, see issue #106. A rendition has the aspect ratio of the raw
  /// rectangle, so filling it distorts nothing.
  Widget pictureLayer(ImageTransform tx, Widget child) => Positioned(
        left: 0,
        top: 0,
        width: tx.rawWidth,
        height: tx.rawHeight,
        child: Transform(transform: tx.matrix, child: child),
      );

  /// The video player, filling the slot the image would occupy.
  ///
  /// Zoom and pan make no sense for a video (and would fight the player's own
  /// controls), so the video is only fitted into the page; the swipe gestures
  /// of the image viewer are kept, as are all the surrounding chrome and the
  /// keyboard shortcuts.
  ///
  /// The slot is the *oriented* one (issue #116): the fit is computed from
  /// [Orientations.widthInt]/[Orientations.heightInt] of the stored
  /// [ImagePart.orientation] — a `rotL` clip of a landscape file occupies a
  /// portrait box — and the poster and the playing surface inside it are
  /// turned by that same orientation, see [VideoView.orientation]. A still
  /// image is turned by [ImageTransform.matrix] in [pictureLayer]; a video
  /// cannot be, because the player is a platform surface and not a picture
  /// this app paints, so it is turned where it is laid out instead.
  Widget buildVideoViewer() {
    var self = part;
    var width =
        Orientations.widthInt(self.orientation, self.width, self.height);
    var height =
        Orientations.heightInt(self.orientation, self.width, self.height);
    var aspectRatio = width > 0 && height > 0 ? width / height : 16 / 9;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      // A video has a description like every other part of the album
      // (issue #80).
      onLongPress: editDescription,
      onScaleStart: (details) {},
      onScaleEnd: (details) {
        if (details.pointerCount <= 1) {
          onSwipe(details);
        }
      },
      child: Center(
        child: AspectRatio(
          aspectRatio: aspectRatio,
          child: VideoView(
            key: ValueKey(dataUrl),
            // The original is the fallback and the download; what is played
            // is the rendition, where the server has one (issue #75).
            videoUrl: widget.client.originalUrl(dataUrl),
            renditionUrl: widget.client.playbackUrl(dataUrl),
            probeRendition: widget.client.renditionState,
            posterUrl: widget.client.thumbnailUrl(dataUrl),
            // Poster and player turned together, see [VideoView.orientation].
            orientation: self.orientation,
            headers: widget.client.authHeaders,
            // A failure of the platform player goes into the same log every
            // request of this app goes into, see issue #73.
            log: widget.client.log,
          ),
        ),
      ),
    );
  }

  /// Moves the fitted image with the finger, along one axis only (issue #61).
  ///
  /// The axis is decided from the first [_axisThreshold] pixels of the
  /// movement: a horizontal drag pages to the neighbouring image and the
  /// picture follows it, a vertical one is the way back to the album (or up to
  /// the group's alternatives) and leaves the picture where it is — there is
  /// no album sliding in behind it that the movement could point at, and an
  /// image that only ever moves where paging can take it says plainly what the
  /// gesture does.
  void dragFitted(ImageTransform tx, Offset focus) {
    var delta = focus - _gestureFocus;
    if (_axis == _DragAxis.undecided) {
      if (delta.distance < _axisThreshold) {
        return;
      }
      _axis = delta.dx.abs() >= delta.dy.abs()
          ? _DragAxis.horizontal
          : _DragAxis.vertical;
    }
    if (_axis != _DragAxis.horizontal) {
      return;
    }

    _dragDx = delta.dx;
    var moved = boundedDrag(tx, delta.dx);
    setState(() => tx.setCustom(_gestureTx + moved, _gestureTy, _gestureScale));
  }

  /// How far the image really moves for a drag of [dx], see [dragFitted].
  ///
  /// A drag towards an image that is there follows the finger; one at the
  /// start (or the end) of the album has nothing to page to and is answered by
  /// a rubber band that approaches [_rubberBandFraction] of the page and never
  /// passes it.
  double boundedDrag(ImageTransform tx, double dx) {
    var blocked = dx > 0 ? previous == null : next == null;
    if (!blocked) {
      return dx;
    }
    var limit = tx.pageWidth * _rubberBandFraction;
    return limit <= 0 ? 0 : dx / (1 + dx.abs() / limit);
  }

  /// Finishes a drag: a fast or a long drag of the un-zoomed image is a swipe.
  void onDragEnd(ImageTransform tx, ScaleEndDetails details) {
    var axis = _axis;
    var dragDx = _dragDx;
    _axis = _DragAxis.free;
    _dragDx = 0;

    if (details.pointerCount > 1 ||
        _multiTouch ||
        axis == _DragAxis.free ||
        tx.scale > tx.fitScale) {
      // A pinch, or the image is zoomed in: the drag was a pan.
      return;
    }

    if (axis == _DragAxis.horizontal) {
      if (!swipeHorizontally(tx, dragDx, details.velocity)) {
        // Not far and not fast enough: the image slides back.
        snapBack(tx);
      }
      return;
    }

    // The vertical gestures never moved the image, see [dragFitted].
    setState(tx.reset);
    if (axis == _DragAxis.vertical) {
      swipeVertically(details.velocity);
    } else {
      onSwipe(details);
    }
  }

  /// Pages to the neighbouring image if the horizontal drag asked for it.
  ///
  /// A flick navigates as it always did; a slow drag navigates once it crossed
  /// [_swipeDistanceFraction] of the page width. Neither passes the ends of
  /// the album: where there is no neighbour, the drag snaps back.
  bool swipeHorizontally(ImageTransform tx, double dragDx, Velocity velocity) {
    var speed = velocity.pixelsPerSecond;
    var flick =
        speed.distance >= _swipeVelocity && speed.dx.abs() > speed.dy.abs();
    var far = dragDx.abs() >= tx.pageWidth * _swipeDistanceFraction;
    if (!flick && !far) {
      return false;
    }

    // Dragging (or flicking) to the left uncovers the next image. A flick
    // against a drag that had already gone far is the finger saying "no":
    // the image goes back to where it was, it does not page the other way.
    var flickForwards = speed.dx < 0;
    var dragForwards = dragDx < 0;
    if (flick && far && flickForwards != dragForwards) {
      return false;
    }
    var forwards = flick ? flickForwards : dragForwards;
    var target = forwards ? next : previous;
    if (target == null) {
      return false;
    }

    setState(tx.reset);
    show(target);
    return true;
  }

  /// Answers a vertical flick: down leaves the viewer, up opens the group.
  void swipeVertically(Velocity velocity) {
    var speed = velocity.pixelsPerSecond;
    if (speed.distance < _swipeVelocity) {
      return;
    }
    if (speed.dy > 0) {
      showParent();
    } else {
      showGroup();
    }
  }

  /// Navigates if the finished gesture was a fast enough swipe.
  ///
  /// Used where the image itself is not dragged (the video, and a gesture too
  /// short to decide an axis). Both ends of the album are respected by [show],
  /// which does nothing where there is no image to go to.
  void onSwipe(ScaleEndDetails details) {
    var velocity = details.velocity.pixelsPerSecond;
    if (velocity.distance < _swipeVelocity) {
      return;
    }

    if (velocity.dx.abs() > velocity.dy.abs()) {
      if (velocity.dx > 0) {
        showPrevious();
      } else {
        showNext();
      }
    } else {
      swipeVertically(details.velocity);
    }
  }

  /// Slides the dragged image back to its fitted position, see [onDragEnd].
  void snapBack(ImageTransform tx) {
    _snapFrom = tx.tx;
    if ((_snapFrom - tx.fitTx).abs() < 0.5) {
      setState(tx.reset);
      return;
    }
    _snapping = tx;
    _snapBack
      ..reset()
      ..forward();
  }

  /// Stops a running snap-back, leaving the image where it stands.
  void _stopSnapBack() {
    _snapping = null;
    _snapBack.stop();
  }

  /// One step of the snap-back animation, see [snapBack].
  void _onSnapBack() {
    var tx = _snapping;
    if (tx == null) {
      return;
    }
    setState(() {
      if (_snapBack.value >= 1) {
        _snapping = null;
        tx.reset();
      } else {
        var moved = Curves.easeOut.transform(_snapBack.value);
        tx.setCustom(
          _snapFrom + (tx.fitTx - _snapFrom) * moved,
          tx.fitTy,
          tx.fitScale,
        );
      }
    });
  }

  /// The navigation chevrons and the caption shown on top of the image.
  List<Widget> buildOverlay(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var self = part;
    var attribution = attributionOf(context, self);
    // The controls stay clear of the system bars (issue #60): the viewer runs
    // immersive, but a bar shown by a swipe from the edge must never sit on a
    // button — in landscape the navigation bar is exactly where the chevrons
    // are.
    var insets = MediaQuery.paddingOf(context);
    var actions = [
      ...widget.actions,
      if (widget.albumPath != null && mayTakeBack(context, self))
        overlayButton(
          Icons.undo,
          l10n.takeBack,
          takeBack,
          key: const Key("image-take-back"),
        ),
      // The controls of the edit-persons mode stand where the viewer's other
      // controls do (issue #147): the tool that draws a face, and the way out.
      if (editPersons) ...[
        overlayButton(
          Icons.crop_free,
          l10n.viewerMarkFace,
          () => setState(() {
            _markingFaces = !_markingFaces;
            _marked = null;
          }),
          key: const Key("viewer-mark-face"),
          color: _markingFaces ? Colors.lightBlueAccent : Colors.white,
        ),
        overlayButton(
          Icons.done,
          l10n.viewerEditPersonsDone,
          leaveEditPersons,
          key: const Key("viewer-edit-persons-done"),
        ),
      ] else if (mayEditPersons || mayDownloadOriginal)
        // The viewer's own menu, the last control at the right (issue #100).
        Container(
          key: const Key("viewer-menu"),
          decoration: const BoxDecoration(
            color: Colors.black38,
            shape: BoxShape.circle,
          ),
          // The look of every other control over the picture: white, and as
          // large (#155) — the theme's dark icon was all but invisible here.
          child: menu(context, icon: imageOverlayIcon(Icons.more_vert), [
            // The copy the `download` right promises, see issue #164.
            if (mayDownloadOriginal)
              PopupMenuItem<void Function(BuildContext)>(
                key: const Key("viewer-download"),
                value: (_) => downloadOriginal(),
                child: Row(
                  children: [
                    const Padding(
                      padding: EdgeInsets.only(right: 16),
                      child: Icon(Icons.download, color: Colors.blueAccent),
                    ),
                    Flexible(child: Text(l10n.viewerDownload)),
                  ],
                ),
              ),
            if (mayEditPersons)
              PopupMenuItem<void Function(BuildContext)>(
                key: const Key("viewer-edit-persons"),
                value: (_) => enterEditPersons(),
                child: Row(
                  children: [
                    const Padding(
                      padding: EdgeInsets.only(right: 16),
                      child:
                          Icon(Icons.people_outline, color: Colors.blueAccent),
                    ),
                    Flexible(child: Text(l10n.viewerEditPersons)),
                  ],
                ),
              ),
          ]),
        ),
    ];
    return [
      Positioned(
        left: insets.left + 8,
        top: insets.top + 8,
        child: overlayButton(Icons.arrow_back, l10n.backToAlbum, showParent),
      ),
      if (previous != null)
        Positioned(
          left: insets.left + 8,
          top: insets.top,
          bottom: insets.bottom,
          child: Center(
            child: overlayButton(
              Icons.chevron_left,
              l10n.previousImage,
              showPrevious,
            ),
          ),
        ),
      if (next != null)
        Positioned(
          right: insets.right + 8,
          top: insets.top,
          bottom: insets.bottom,
          child: Center(
            child: overlayButton(
              Icons.chevron_right,
              l10n.nextImage,
              showNext,
            ),
          ),
        ),
      if (actions.isNotEmpty)
        Positioned(
          right: insets.right + 8,
          top: insets.top + 8,
          child: Row(mainAxisSize: MainAxisSize.min, children: actions),
        ),
      if (group != null && widget.onShowGroup != null)
        Positioned(
          left: insets.left,
          right: insets.right,
          top: insets.top + 8,
          child: Center(
            child: overlayButton(
              Icons.expand_more,
              l10n.showAlternatives,
              showGroup,
            ),
          ),
        ),
      if (editPersons && _markingFaces)
        Positioned(
          left: insets.left,
          right: insets.right,
          bottom: insets.bottom + 8,
          child: Center(
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              color: Colors.black54,
              child: Text(
                l10n.viewerMarkFaceHint,
                key: const Key("viewer-mark-face-hint"),
                style: const TextStyle(color: Colors.white),
              ),
            ),
          ),
        )
      else if (self.comment.isNotEmpty || attribution != null)
        Positioned(
          left: 0,
          right: 0,
          bottom: 0,
          child: buildCaption(self.comment, attribution, insets),
        ),
    ];
  }

  Widget overlayButton(
    IconData icon,
    String tooltip,
    VoidCallback onPressed, {
    Key? key,
    Color color = Colors.white,
  }) =>
      imageOverlayButton(icon, tooltip, onPressed, key: key, color: color);

  /// What is written under the image: who added it, and what was said about
  /// it.
  ///
  /// One block, not two: the attribution and the comment read as one caption,
  /// in one style, with the attribution first — it says where the picture
  /// comes from, the comment says what it shows. An image with neither carries
  /// no caption at all, exactly as before issue #53.
  Widget buildCaption(
    String comment,
    String? attribution,
    EdgeInsets insets,
  ) =>
      Container(
        key: const Key("image-caption"),
        color: Colors.black54,
        // The block may run under the bars, its text may not (issue #60).
        padding: EdgeInsets.fromLTRB(
          insets.left + 16,
          16,
          insets.right + 16,
          insets.bottom + 16,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (attribution != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Text(
                  attribution,
                  key: const Key("image-contributor"),
                  style: const TextStyle(color: Colors.white),
                  textAlign: TextAlign.center,
                ),
              ),
            if (comment.isNotEmpty) buildComment(comment),
          ],
        ),
      );

  /// The comment of the image, one [Text] per paragraph.
  Widget buildComment(String comment) => Column(
        key: const Key("image-comment"),
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (var paragraph in commentParagraphs(comment))
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Text(
                paragraph,
                style: const TextStyle(color: Colors.white),
                textAlign: TextAlign.center,
              ),
            ),
        ],
      );

  /// Takes the displayed photo back out of this album, see issue #53.
  ///
  /// A take-back is a move and nothing else: the same folder picker the album
  /// offers under "Move to…", the same `?action=move`, the same reading-out of
  /// what the server answered. The app never deletes a photo, and the server
  /// refuses what this caller may not take (`CONTRIBUTION_REFUSED`), which is
  /// then shown in the server's own words.
  ///
  /// A group is named by its representative — the image this viewer shows —
  /// so a grouped photo is taken back with its alternatives, which is how a
  /// group moves everywhere else.
  ///
  /// Deliberately one photo at a time: taking several back would need a
  /// selection, and a selection lives in the edit mode, which is exactly what
  /// a contributor does not have.
  Future<void> takeBack() async {
    var path = widget.albumPath;
    if (path == null) {
      return;
    }
    await moveWithPicker(
      context: context,
      client: widget.client,
      source: path,
      names: [part.name],
      subject: const ImageSubject(1),
      delegate: widget.delegate,
      // An album created in the picker is dated by the photo that goes into
      // it, see issue #114.
      albumDate: newAlbumDay([part]),
      onMoved: () {
        // The photo is no longer in this album, so the viewer showing it has
        // nothing left to show: back to the album, which is fetched again.
        var onTakenBack = widget.onTakenBack;
        if (onTakenBack != null) {
          onTakenBack();
        } else {
          showParent();
        }
      },
    );
  }

  void showPrevious() => show(previous);

  void showNext() => show(next);

  void showFirst() => show(firstVisible(homeOf(widget.image), minRating));

  void showLast() => show(lastVisible(endOf(widget.image), minRating));

  void show(AbstractImage? image) {
    if (image != null) {
      widget.onShowImage(image);
    }
  }

  /// Leaves the viewer, back to the album.
  void showParent() {
    widget.onUp?.call();
  }

  /// Opens the "alternatives" view of the group the image belongs to.
  void showGroup() {
    var self = group;
    var onShowGroup = widget.onShowGroup;
    if (self != null && onShowGroup != null) {
      onShowGroup(self);
    }
  }
}

/// The face of [faces] a marked [box] became on the server (#155).
///
/// The server answers the detector's own box where it found a face around
/// the marked one — larger than a click, and never exactly what a hand drew —
/// or the marked box itself where it found none. So the face meant is one that
/// contains the centre of the box, or one that overlaps it by more than half;
/// of several, the one overlapping it most, and of equal ones the smallest.
FaceInfo? faceAtBox(List<FaceInfo> faces, MarkedBox box) {
  var centreX = box.x + box.w / 2;
  var centreY = box.y + box.h / 2;
  FaceInfo? best;
  var bestOverlap = -1.0;
  var bestArea = double.infinity;
  for (var face in faces) {
    var contains = centreX >= face.x &&
        centreX <= face.x + face.w &&
        centreY >= face.y &&
        centreY <= face.y + face.h;
    var overlap = _overlap(box, face);
    if (!contains && overlap <= 0.5) {
      continue;
    }
    var area = face.w * face.h;
    if (overlap > bestOverlap || (overlap == bestOverlap && area < bestArea)) {
      best = face;
      bestOverlap = overlap;
      bestArea = area;
    }
  }
  return best;
}

/// The intersection of the two boxes over their union, `0..1`.
double _overlap(MarkedBox box, FaceInfo face) {
  var left = math.max(box.x, face.x);
  var top = math.max(box.y, face.y);
  var right = math.min(box.x + box.w, face.x + face.w);
  var bottom = math.min(box.y + box.h, face.y + face.h);
  if (right <= left || bottom <= top) {
    return 0;
  }
  var intersection = (right - left) * (bottom - top);
  var union = box.w * box.h + face.w * face.h - intersection;
  return union <= 0 ? 0 : intersection / union;
}

/// The icon of a control floating over the image of an [ImageView]: white and
/// as large as [imageOverlayButton]'s, for a control that is not one of those
/// buttons (the viewer's menu, #155).
Widget imageOverlayIcon(IconData icon, {Color color = Colors.white}) =>
    Icon(icon, color: color, size: 32);

/// A button floating over the image of an [ImageView].
Widget imageOverlayButton(
  IconData icon,
  String tooltip,
  VoidCallback onPressed, {
  Color color = Colors.white,
  Key? key,
}) =>
    IconButton(
      key: key,
      icon: Icon(icon),
      iconSize: 32,
      color: color,
      tooltip: tooltip,
      style: IconButton.styleFrom(backgroundColor: Colors.black38),
      onPressed: onPressed,
    );

/// How long the mouse has to rest on a face before it is named (issue #145).
///
/// Short, because the name is the answer to a question the pointer already
/// asked; long enough that sweeping the mouse across a group photo does not
/// flash a name per face.
const Duration _faceHoverWait = Duration(milliseconds: 300);

/// How wide the outline of a hovered face is drawn, in pixels of the screen.
const double _faceOutlineWidth = 1.5;

/// The room between a face box and the name standing outside it (issue #162).
const double _nameMargin = 6;

/// The [child] picture with a hover region over every named face (issue #145).
///
/// The regions are laid out in the *raw* rectangle of the file — the box of
/// [FaceInfo] is a fraction of it — and this whole widget hangs in the layer
/// [ImageViewState.pictureLayer] builds, so the orientation, the zoom and the
/// pan of [ImageTransform.matrix] reach the boxes and the picture as one
/// thing. Nothing here turns anything: a box that was turned twice, or turned
/// by a rule of its own, would sit beside the face it names.
///
/// What a region does is hover and nothing else: a [MouseRegion] that is
/// translucent to the hit test, so the tap, the double tap, the drag, the
/// pinch and the long press of the viewer pass through it untouched. The
/// [Tooltip] is triggered manually — by the mouse entering it — and never by
/// a long press, which belongs to the image properties (issue #80); a touch
/// device has no hover and therefore sees nothing, which is the whole
/// feature: it names what the pointer points at.
class FaceRegions extends StatefulWidget {
  /// The transport the register is loaded with, see [PeopleRegistry].
  final VAlbumClient client;

  /// The faces to name, all of them confirmed as somebody.
  final List<FaceInfo> faces;

  /// The width of the raw rectangle the boxes are fractions of.
  final double rawWidth;

  /// The height of the raw rectangle the boxes are fractions of.
  final double rawHeight;

  /// The scale the layer is drawn at, so that the outline keeps its width.
  final double scale;

  /// Whether the stored turn of the picture ([ImagePart.orientation]) is a
  /// quarter turn, which swaps the side of a box that stands upright on the
  /// screen — the side the name has to clear (issue #162).
  final bool swapsDimensions;

  /// The picture the regions lie over.
  final Widget child;

  const FaceRegions({
    super.key,
    required this.client,
    required this.faces,
    required this.rawWidth,
    required this.rawHeight,
    required this.scale,
    this.swapsDimensions = false,
    required this.child,
  });

  @override
  State<FaceRegions> createState() => _FaceRegionsState();
}

class _FaceRegionsState extends State<FaceRegions> {
  /// The register, empty while it is being loaded and after a refusal.
  Map<String, Person> _people = const {};

  /// The [FaceInfo.index] the mouse is resting on, `null` while it is nowhere.
  int? _hovered;

  @override
  void initState() {
    super.initState();
    // Lazily, and exactly once per client: this widget only exists where the
    // picture really shows a confirmed face, see [ImageViewState.namedFaces].
    var registry = PeopleRegistry.of(widget.client);
    _people = registry.people;
    registry.load().then((people) {
      if (mounted && !identical(people, _people)) {
        setState(() => _people = people);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    var regions = <Widget>[];
    for (var face in widget.faces) {
      var person = _people[face.person];
      // What they are called on a photograph, and this is one (issue #146).
      var name = person == null ? "" : displayName(person);
      if (name.isEmpty) {
        // A face whose person the register does not know names nobody, and a
        // region that names nobody is not a region: it would outline a face
        // and then have nothing to say.
        continue;
      }
      regions.add(
        Positioned(
          left: face.x * widget.rawWidth,
          top: face.y * widget.rawHeight,
          width: face.w * widget.rawWidth,
          height: face.h * widget.rawHeight,
          child: _region(face, name),
        ),
      );
    }
    return Stack(
      fit: StackFit.expand,
      clipBehavior: Clip.none,
      children: [widget.child, ...regions],
    );
  }

  /// How far from the centre of [face] its name stands: just outside the box.
  double _nameOffset(FaceInfo face) {
    var upright = widget.swapsDimensions
        ? face.w * widget.rawWidth
        : face.h * widget.rawHeight;
    return upright * widget.scale / 2 + _nameMargin;
  }

  /// One face: the tooltip naming it, and the outline while it is hovered.
  Widget _region(FaceInfo face, String name) {
    var hovered = _hovered == face.index;
    // The layer is scaled by [ImageTransform.scale], so a constant width here
    // would be a hairline on a fitted photograph and a fat band on a zoomed
    // one. Divided by the scale it is the same line on the screen at every
    // zoom.
    var width =
        widget.scale > 0 ? _faceOutlineWidth / widget.scale : _faceOutlineWidth;
    return Tooltip(
      message: name,
      waitDuration: _faceHoverWait,
      // A [Tooltip] stands a fixed distance from the *centre* of its child, so
      // on any box taller than that distance the name lay over the face it
      // named (issue #162). Half the box's height on the screen — the side
      // that stands upright once the picture is turned — plus a margin puts
      // it just outside the box, at every zoom.
      verticalOffset: _nameOffset(face),
      // The viewer's own long press opens the image properties (issue #80).
      // A tooltip that answered it too would take that gesture away on every
      // face; the mouse entering the region is what shows this one.
      triggerMode: TooltipTriggerMode.manual,
      preferBelow: false,
      child: MouseRegion(
        key: Key("face-hover-${face.index}"),
        // Not opaque: the [Tooltip]'s own region is the parent of this one and
        // has to be entered as well, and nothing behind the picture may be cut
        // off from the mouse either.
        opaque: false,
        // Added to the hit test without answering it, so the gestures of the
        // viewer keep passing through, see the class doc.
        hitTestBehavior: HitTestBehavior.translucent,
        onEnter: (_) => setState(() => _hovered = face.index),
        onExit: (_) => setState(() {
          if (_hovered == face.index) {
            _hovered = null;
          }
        }),
        child: hovered
            ? DecoratedBox(
                decoration: BoxDecoration(
                  // Faint, and white because the viewer is black: the picture
                  // is what is being looked at, the box only says where the
                  // name belongs.
                  border: Border.all(color: Colors.white70, width: width),
                ),
                child: const SizedBox.expand(),
              )
            : const SizedBox.expand(),
      ),
    );
  }
}

/// One decoded picture the viewer holds in memory, see
/// [ImageViewState.prefetchNeighbours] and issue #148.
///
/// A listener on the [ImageStream] is the whole mechanism: the [ImageCache]
/// keeps a completer that has one in its live map whatever its size budget
/// says, and hands it to the next [Image] naming the same key without a round
/// trip. Removing the listener gives the picture back to the budget.
class _PinnedPicture {
  final ImageStream _stream;
  late final ImageStreamListener _listener;

  /// Whether the listener was already taken off, so that [release] may be
  /// called from both ends — a failure and the page change — exactly once.
  bool _released = false;

  _PinnedPicture(this._stream, {required void Function() onFailed}) {
    _listener = ImageStreamListener(
      // Nothing to do: holding the picture *is* the purpose.
      (image, synchronousCall) {},
      onError: (error, stackTrace) {
        release();
        onFailed();
      },
    );
    _stream.addListener(_listener);
  }

  /// Lets the [ImageCache] have the picture back.
  void release() {
    if (_released) {
      return;
    }
    _released = true;
    _stream.removeListener(_listener);
  }
}
