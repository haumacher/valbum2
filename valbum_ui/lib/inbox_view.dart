/// The inbox screen, the app half of issues #131 and #136.
///
/// An inbox is a **kind of album** ([AlbumKind.inbox]), not a resource of its
/// own: the same folder, the same sidecar, the same parts, so upload, move,
/// delete, thumbnails and the viewer are the ones the album page uses. What
/// differs is what the screen *is*:
///
///  * it is always in the selection mode and buffers nothing. There is no
///    [AlbumEditSession] behind it — no Save, no Cancel, no dirty guard
///    (issue #99): every action writes at once, and leaving the screen asks
///    nothing because there is nothing unsaved to ask about;
///  * the headings are **derived**: the server answers the parts flat and
///    sorted by date, the newest first (`Inboxes.flatten` — the latest
///    arrivals are what one comes to sort), and this screen draws a heading per
///    day with a month line where the month changes. Nothing of that is
///    stored, exactly as `effectiveDate` is derived and never stored;
///  * a click on a tile has the album's selection semantics (issue #160, the
///    one decision [selectionAfterTap]): a plain click selects exactly that
///    photograph (a second one on the only selected clears), ctrl/meta
///    toggles, shift takes the range from the photograph clicked last in the
///    order drawn — across day and month headings — and a finger toggles by
///    a long press;
///  * tapping a heading selects everything under it, which is how a day (or a
///    month) is moved into an album in one gesture;
///  * an editor rates a photograph with the album tile's four buttons
///    ([ratingButtons]) — on the whole selection where the tile is part of
///    one of several (#224) — written at once, and **"Delete" is the −2
///    rating** (#160): no move, and a question only for several photographs
///    — the photograph is hidden from the inbox like a trashed one from the
///    album grid ([isVisiblePart] at [minMinRating]) and is restored or
///    purged on the trash page of #152, which the menu offers ("Show trash")
///    while one exists. A member with `contribute` alone cannot write the sidecar, sees no rating and deletes
///    their own photographs into the trash folder of the space as #131 made
///    it, until #159 decides otherwise;
///  * a secondary click on a tile opens a menu at the pointer (issue #222):
///    the move first, then the delete, the properties, the recording time and
///    the crop, each only where the caller may, acting on the selection where
///    the clicked photograph is part of it and otherwise on that photograph
///    alone, which first becomes the selection (the #139/#156 rule); the
///    browser's own menu is taken away while the inbox stands;
///  * a space has exactly one, the folder the server names in `?type=auth`
///    (issue #226): it is never a tile of a listing and never chosen, it is
///    opened from the inbox icon of the start page, whose badge says how much
///    waits here;
///  * a row of chips above the photographs filters them by camera (issue
///    #278), one per camera found plus "Unknown camera", several of them a
///    union, none of them everything — remembered on this device, never on
///    the server, see [inboxCameras] and [InboxCameraFilterStorage];
///  * what an inbox has no use for is not there: no reorder, no drag handles,
///    no headings of its own, no description, no album picture, no groups, no
///    "view as".
///
/// It is a screen of its own rather than a mode of `album_view.dart` for one
/// reason: the album page is built around its edit session — the buffer, the
/// dirty flag, the leave guard, the reordering — and an inbox must never have
/// one. Everything that can be shared *is* shared: the row layout
/// ([layouter.AlbumLayout], [ContentWidgetBuilder], [rowExtent]), the oriented
/// thumbnail, the recording-time dialog, the image properties, the move and
/// the delete flows, the router and the viewer.
library;

import 'dart:convert';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/rendering.dart' show ScrollCacheExtent;
import 'package:flutter/services.dart'
    show HardwareKeyboard, KeyDownEvent, LogicalKeyboardKey;
import 'package:intl/intl.dart';
import 'package:valbum_ui/album_layout.dart' as layouter;

import 'about.dart';
import 'action_guard.dart';
import 'album_date.dart' show localDayOf;
import 'album_edit.dart';
import 'album_model.dart';
import 'album_view.dart';
import 'app.dart';
import 'broken.dart';
import 'caller.dart';
import 'client.dart';
import 'crop.dart';
import 'crop_editor.dart' show cropPhoto;
import 'dialog_style.dart';
import 'downloads.dart';
import 'form_dialog.dart';
import 'image_properties.dart';
import 'key_guard.dart';
import 'keyboard_scroll.dart';
import 'l10n/app_localizations.dart';
import 'listing_view.dart';
import 'move_view.dart';
import 'offline.dart';
import 'oriented_thumbnail.dart';
import 'persons_view.dart' show BrowserMenuClaim;
import 'photo_actions.dart';
import 'resource.dart';
import 'return_reveal.dart';
import 'rights.dart';
import 'routes.dart' show TrashRoute;
import 'selectable.dart';
import 'settings.dart';
import 'share_session.dart';
import 'trash_view.dart' show hasTrashedImages, trashRating;
import 'status.dart';
import 'page_app_bar.dart';

// ---------------------------------------------------------------------------
// The words of the inbox screen.
//
// English literals in one place, so the localization slice of issue #108 can
// lift them in one step: every string this screen shows is a constant (or a
// function over constants) here, and nothing below spells one inline.
// ---------------------------------------------------------------------------

/// What an inbox holding nothing says.
String inboxEmptyNotice(AppLocalizations l10n) => l10n.inboxEmptyNotice;

/// The heading of the photographs whose date nobody knows.
String inboxUndatedHeading(AppLocalizations l10n) => l10n.inboxUndatedHeading;

/// The entry moving the selection into an album.
String inboxMoveLabel(AppLocalizations l10n, int count) =>
    l10n.moveSubjectTo(ImageSubject(count).asked(l10n));

/// The entry putting the selection into the trash of the space.
String inboxDeleteLabel(AppLocalizations l10n, int count) =>
    l10n.deleteSubjectAction(ImageSubject(count).asked(l10n));

/// The title of the question asked before a delete.
String inboxDeleteTitle(AppLocalizations l10n, int count) =>
    l10n.deleteQuestion(ImageSubject(count).asked(l10n));

/// What deleting photographs of an inbox does, said before it is done.
///
/// The counterpart of `deleteExplanation` (issue #109), which speaks of an
/// album: here it is the photographs themselves that travel, and the sentence
/// that matters most is the last one — nothing is deleted from disk.
String inboxDeleteExplanation(AppLocalizations l10n) =>
    l10n.inboxDeleteExplanation;

/// The entry clearing the selection.
String inboxClearSelection(AppLocalizations l10n) => l10n.clearSelection;

/// What the app bar says under the title while something is selected.
String inboxSelectionLine(AppLocalizations l10n, int count) =>
    l10n.selectedCount(count);

/// The tooltip of the tool opening a photograph in the viewer.
String inboxOpenTooltip(AppLocalizations l10n) => l10n.open;

/// The tooltip of the selection box of a tile.
String inboxSelectTooltip(AppLocalizations l10n) => l10n.select;

/// The tooltip of the heading that selects everything under it.
String inboxHeadingTooltip(AppLocalizations l10n) => l10n.selectEverythingBelow;

/// What is said when a selection was asked to do something it cannot.
String inboxNothingSelected(AppLocalizations l10n) => l10n.nothingSelected;

/// How a day heading is written out.
DateFormat inboxDayFormat(AppLocalizations l10n) =>
    DateFormat.yMMMEd(l10n.localeName);

/// How a month line is written out.
DateFormat inboxMonthFormat(AppLocalizations l10n) =>
    DateFormat.yMMMM(l10n.localeName);

// ---------------------------------------------------------------------------
// The derived headings.
// ---------------------------------------------------------------------------

/// One day of an inbox: the photographs taken on it, in the order the server
/// answered them.
///
/// Derived on every build from [AlbumInfo.parts] and never stored, see the
/// library comment. [day] is local midnight of the day, `null` for the one
/// section holding the photographs that carry no date at all.
class InboxDay {
  /// Local midnight of the day, `null` for the undated section.
  final DateTime? day;

  /// The photographs of that day, in the order they arrived.
  final List<ImagePart> images;

  const InboxDay(this.day, this.images);

  /// Whether this is the section of the photographs nothing dates.
  bool get undated => day == null;

  /// The first day of the month this section belongs to, `null` for the
  /// undated section — which belongs to no month and starts none.
  DateTime? get month {
    var self = day;
    return self == null ? null : DateTime(self.year, self.month);
  }

  /// The key the heading of this section carries.
  Key get headingKey => day == null
      ? const Key("inbox-undated")
      : Key("inbox-day-${_dayKeyFormat.format(day!)}");

  /// What the heading reads.
  String heading(AppLocalizations l10n) => day == null
      ? inboxUndatedHeading(l10n)
      : inboxDayFormat(l10n).format(day!);
}

/// Chronological inside a day, the name breaking a tie so that the order is
/// the same on every build — the reverse of the server's order between days.
int _byTimeTaken(ImagePart a, ImagePart b) {
  var byDate = a.date.compareTo(b.date);
  return byDate != 0
      ? byDate
      : a.name.toLowerCase().compareTo(b.name.toLowerCase());
}

final DateFormat _dayKeyFormat = DateFormat("yyyy-MM-dd");
final DateFormat _monthKeyFormat = DateFormat("yyyy-MM");

/// The key the month line of [month] carries.
Key inboxMonthKey(DateTime month) =>
    Key("inbox-month-${_monthKeyFormat.format(month)}");

/// The parts of an inbox as the days the screen draws, see [InboxDay].
///
/// The server answers the parts flat and sorted by date, the newest first and
/// the undated last (`Inboxes.flatten`), so the days come out in that very
/// order — the latest day at the top, an older month further down: a day is
/// opened when a photograph of a day not seen before arrives. **Inside a day
/// the photographs run chronologically** (the author, 2026-09-23: the days in
/// reverse, the pictures of a day in the order they were taken), which is
/// decided here and not on the server, because the day is this device's day —
/// the server knows no zone to cut one at. A group cannot reach an inbox — the
/// server dissolves one on the way out — but where one did, its images are
/// taken one by one, which is what the inbox shows everywhere else.
///
/// A photograph rated below [minRating] is not shown at all (issue #160): the
/// album grid's floor [minMinRating], so what was deleted here — rated −2 —
/// vanishes from its day on the write and is found again in the trash page of
/// issue #152. An inbox has no rating filter of its own; the floor is fixed.
List<InboxDay> inboxDays(
  List<AlbumPart> parts, {
  int minRating = minMinRating,
}) {
  var order = <String>[];
  var byDay = <String, List<ImagePart>>{};
  var days = <String, DateTime?>{};

  void add(ImagePart image) {
    if (!isVisiblePart(image, minRating)) {
      return;
    }
    var day = localDayOf(image.date);
    var key = day == null ? "" : _dayKeyFormat.format(day);
    if (!byDay.containsKey(key)) {
      order.add(key);
      byDay[key] = [];
      days[key] = day;
    }
    byDay[key]!.add(image);
  }

  for (var part in parts) {
    if (part is ImagePart) {
      add(part);
    } else if (part is ImageGroup) {
      part.images.forEach(add);
    }
  }
  for (var images in byDay.values) {
    images.sort(_byTimeTaken);
  }

  // The undated section is the last one, whatever the server sent: it is the
  // heap nothing can file, and it belongs behind what is filed.
  var dated = [
    for (var key in order)
      if (key.isNotEmpty) InboxDay(days[key], byDay[key]!),
  ];
  if (byDay.containsKey("")) {
    dated.add(InboxDay(null, byDay[""]!));
  }
  return dated;
}

// ---------------------------------------------------------------------------
// The camera filter, issue #278.
// ---------------------------------------------------------------------------

/// One camera of an inbox and how many of its photographs the inbox shows.
///
/// [camera] is the label of [ImagePart.camera] — of a video too, which
/// carries its container's — and the empty label stands for every photograph
/// without camera data, the chip "Unknown camera" (the one group #274 gathers
/// such photographs in as well, see [inSameCameraGroup]).
class InboxCamera {
  /// The camera label, empty for the photographs without camera data.
  final String camera;

  /// How many photographs of the inbox carry [camera].
  final int count;

  const InboxCamera(this.camera, this.count);

  /// Whether this stands for the photographs without camera data.
  bool get unknown => camera.isEmpty;

  /// The key of the chip of this camera.
  Key get chipKey => unknown
      ? const Key("inbox-camera-unknown")
      : Key("inbox-camera-chip-$camera");

  /// What the chip of this camera reads.
  String label(AppLocalizations l10n) =>
      unknown ? l10n.inboxUnknownCamera : camera;
}

/// The cameras of what [days] show, the chips of the filter: the most
/// photographs first, a tie by name, and "Unknown camera" behind the named
/// ones of its count.
List<InboxCamera> inboxCameras(List<InboxDay> days) {
  var counts = <String, int>{};
  for (var day in days) {
    for (var image in day.images) {
      counts[image.camera] = (counts[image.camera] ?? 0) + 1;
    }
  }
  var result = [
    for (var entry in counts.entries) InboxCamera(entry.key, entry.value),
  ];
  result.sort((a, b) {
    var byCount = b.count.compareTo(a.count);
    if (byCount != 0) {
      return byCount;
    }
    if (a.unknown != b.unknown) {
      return a.unknown ? 1 : -1;
    }
    return a.camera.toLowerCase().compareTo(b.camera.toLowerCase());
  });
  return result;
}

/// The cameras of [stored] that [cameras] offer a chip for: the filter in
/// effect.
///
/// Empty — everything shown — where fewer than two cameras are found, since
/// then there is no chip row to see or to undo a filter with, and a stored
/// camera none of whose photographs waits here any more filters nothing: an
/// inbox never stands empty for a filter the screen does not show.
Set<String> activeCameras(Set<String> stored, List<InboxCamera> cameras) =>
    cameras.length < 2
        ? const {}
        : {
            for (var camera in cameras)
              if (stored.contains(camera.camera)) camera.camera,
          };

/// [days] with only the photographs of [cameras], the days left empty
/// dropped; all of [days] where [cameras] is empty.
List<InboxDay> filterByCamera(List<InboxDay> days, Set<String> cameras) {
  if (cameras.isEmpty) {
    return days;
  }
  var result = <InboxDay>[];
  for (var day in days) {
    var images = [
      for (var image in day.images)
        if (cameras.contains(image.camera)) image,
    ];
    if (images.isNotEmpty) {
      result.add(InboxDay(day.day, images));
    }
  }
  return result;
}

/// The camera filter of an inbox, kept in the [SettingsStore] of this device
/// (issue #278) under the inbox's address: one JSON object mapping an inbox
/// to its cameras.
///
/// Never fails: a store that cannot be read — web storage refused in a
/// private window, a blob from another version — is an inbox shown whole, and
/// a write that fails leaves the filter in effect until the screen is left.
extension InboxCameraFilterStorage on SettingsStore {
  /// The cameras [inbox] was last filtered by on this device.
  Future<Set<String>> loadInboxCameraFilter(String inbox) async {
    try {
      var cameras = (await _inboxCameraFilters())[inbox];
      if (cameras is List) {
        return {
          for (var camera in cameras)
            if (camera is String) camera,
        };
      }
    } catch (_) {
      // Nothing remembered.
    }
    return {};
  }

  /// Remembers [cameras] as the filter of [inbox], forgetting it where empty.
  Future<void> saveInboxCameraFilter(String inbox, Set<String> cameras) async {
    try {
      var all = await _inboxCameraFilters();
      if (cameras.isEmpty) {
        all.remove(inbox);
      } else {
        all[inbox] = [...cameras]..sort();
      }
      await saveInboxCameraFilters(jsonEncode(all));
    } catch (_) {
      // Kept for this visit only.
    }
  }

  Future<Map<String, dynamic>> _inboxCameraFilters() async {
    try {
      var json = await loadInboxCameraFilters();
      var decoded = json == null ? null : jsonDecode(json);
      if (decoded is Map<String, dynamic>) {
        return decoded;
      }
    } catch (_) {
      // Unreadable: started afresh.
    }
    return {};
  }
}

// ---------------------------------------------------------------------------
// The screen.
// ---------------------------------------------------------------------------

/// The screen of an album of kind [AlbumKind.inbox], see the library comment.
class InboxContent extends StatefulWidget {
  final VAlbumState albumState;
  final AlbumInfo album;
  final String baseUrl;
  final Future Function(AbstractImage image, String name) pushPart;

  const InboxContent(
    this.albumState,
    this.album,
    this.baseUrl,
    this.pushPart, {
    super.key,
  });

  @override
  State<StatefulWidget> createState() => InboxContentState();
}

class InboxContentState extends State<InboxContent>
    with ReturnReveal<InboxContent>, PhotoActionHost {
  /// The photographs currently selected, by identity.
  ///
  /// Held by the state and by nothing else: there is no edit session to keep
  /// it in, and a trip into the viewer and back rebuilds the screen from the
  /// album the router still holds — the selection is the one thing such a
  /// trip costs, which is the price of having no buffer at all.
  final Set<ImagePart> _selection = Set.identity();

  /// The photograph clicked last, the anchor of a shift-click range, see
  /// [handleTap].
  ImagePart? _anchor;

  /// The cameras this inbox is filtered by as remembered on this device
  /// (issue #278); in effect only as far as [activeCameras] lets it.
  Set<String> _cameraFilter = {};

  /// The inbox address [_cameraFilter] was loaded for, see [_loadCameraFilter].
  String? _cameraFilterOf;

  /// The share link this inbox is being looked at through, always `null` in
  /// practice: the server answers `404` for an inbox to a link (issue #135),
  /// so the app never gets here. Read all the same, so that nothing this
  /// screen offers could ever appear inside a link.
  ShareSession? share;

  VAlbumClient get client => widget.albumState.client;

  List<String> get path => widget.albumState.path;

  AlbumInfo get album => widget.album;

  String get albumUrl => "${widget.baseUrl}/${album.path}";

  /// The words this screen reads in.
  AppLocalizations get _l10n => AppLocalizations.of(context)!;

  /// Asks the server again how much waits here, see [CallerInfo.refresh];
  /// kept from the tree while it can still be read, and called when the
  /// screen is left (issue #226).
  VoidCallback? _refreshCaller;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    share = ShareSession.of(context);
    _refreshCaller =
        context.getInheritedWidgetOfExactType<CallerScope>()?.refresh;
    // The way back from a viewer opened by its address, issue #272.
    watchReturn();
    _loadCameraFilter();
  }

  /// The store the camera filter is remembered in, `null` where the screen is
  /// shown without the app around it.
  SettingsStore? get _store =>
      ServerSettingsScope.maybeOf(context)?.settings.store;

  /// Reads the camera filter remembered for this inbox, once per address.
  ///
  /// Until it is read the inbox is shown whole; a chip tapped in the meantime
  /// wins over what is read afterwards.
  void _loadCameraFilter() {
    var inbox = albumUrl;
    var store = _store;
    if (_cameraFilterOf == inbox || store == null) {
      return;
    }
    _cameraFilterOf = inbox;
    store.loadInboxCameraFilter(inbox).then((cameras) {
      if (!mounted || _cameraFilterOf != inbox || cameras.isEmpty) {
        return;
      }
      setState(() {
        _cameraFilter = cameras;
        _keepSelectionShown();
      });
    });
  }

  /// Every photograph the inbox shows, of every camera, as days.
  List<InboxDay> get _allDays => inboxDays(album.parts);

  /// The days the inbox shows: [_allDays] narrowed by the camera filter.
  List<InboxDay> get shownDays {
    var all = _allDays;
    return filterByCamera(all, activeCameras(_cameraFilter, inboxCameras(all)));
  }

  /// Turns the filter by [camera] on or off, see [activeCameras] — what is in
  /// effect plus or minus [camera], remembered on this device.
  ///
  /// A photograph the filter hides leaves the selection (the decision on
  /// issue #278): the inbox is shared, and a move or a delete must never take
  /// along a photograph — someone else's — that is no longer on the screen.
  void toggleCamera(String camera) {
    var cameras = inboxCameras(_allDays);
    var next = {...activeCameras(_cameraFilter, cameras)};
    if (!next.remove(camera)) {
      next.add(camera);
    }
    setState(() {
      _cameraFilter = next;
      _keepSelectionShown();
    });
    _store?.saveInboxCameraFilter(albumUrl, next);
  }

  /// Takes out of the selection what the camera filter hides.
  void _keepSelectionShown() {
    var shown = Set<ImagePart>.identity()..addAll(images);
    _selection.retainWhere(shown.contains);
    if (_anchor != null && !shown.contains(_anchor)) {
      _anchor = null;
    }
  }

  @override
  VAlbumState get revealView => widget.albumState;

  @override
  void didUpdateWidget(InboxContent oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.album, widget.album)) {
      _selection.clear();
      _anchor = null;
    }
    _loadCameraFilter();
  }

  /// What the caller may do with this inbox, as the server answered it, see
  /// [rightsOf]: never `edit` through a share link. Asked without a
  /// dependency, as before: this is read from `initState` as well.
  Rights get rights => rightsOf(context, album, listen: false);

  /// Whether this caller may write the inbox's sidecar.
  ///
  /// The rotation, the recording time and the privacy level are one sidecar
  /// PUT, which the server grants to `edit` alone; a member with `contribute`
  /// sees their own contributions here (issue #135) and may take them out —
  /// see [mayTakeOut] — but not rewrite the album.
  bool get mayWrite => rights.mayEdit;

  /// Whether this caller may move photographs out of the inbox and delete
  /// them.
  ///
  /// `edit`, or the contributor of the photographs themselves: the server
  /// applies the rule of issue #53 and refuses what it must with
  /// `CONTRIBUTION_REFUSED`, which is read out as it is said.
  bool get mayTakeOut =>
      (rights.mayEdit || rights.mayContribute) && share == null;

  /// Every photograph the inbox shows, in the order it shows them: the days
  /// newest first, a day chronologically, the trashed left out ([inboxDays]),
  /// and only the cameras filtered by ([shownDays], issue #278) — so a
  /// shift-click range, a camera gathered and a day selected take nothing
  /// the screen does not show.
  List<ImagePart> get images => [
        for (var section in shownDays) ...section.images,
      ];

  bool isSelected(ImagePart image) => _selection.contains(image);

  /// Adds or removes [image] — the long press and the tile's check box, the
  /// finger's way to a multiple selection, as in the album.
  void toggleSelection(ImagePart image) => setState(() {
        if (!_selection.remove(image)) {
          _selection.add(image);
        }
        _anchor = image;
      });

  /// Handles a click on the tile of [image] with the album's semantics
  /// (issue #160, [selectionAfterTap]): a plain click selects exactly this
  /// photograph and clears the selection when it was the only one selected,
  /// ctrl/meta toggles, shift takes the range from the photograph clicked last
  /// in the order the screen shows them — so a range runs across day and month
  /// headings as they are drawn.
  void handleTap(ImagePart image) {
    var keyboard = HardwareKeyboard.instance;
    setState(() {
      var outcome = selectionAfterTap<ImagePart>(
        ordered: images,
        tapped: image,
        current: _selection,
        anchor: _anchor,
        shift: keyboard.isShiftPressed,
        ctrl: keyboard.isControlPressed || keyboard.isMetaPressed,
      );
      _selection
        ..clear()
        ..addAll(outcome.selection);
      _anchor = outcome.anchor;
    });
  }

  /// Selects everything of [section], or takes all of it out again.
  ///
  /// The second tap deselects, which is what makes a heading a switch rather
  /// than a one-way gesture: a day added by mistake is taken back the way it
  /// was added.
  void toggleSection(Iterable<ImagePart> images) => setState(() {
        var all = images.toList();
        if (all.every(_selection.contains)) {
          all.forEach(_selection.remove);
        } else {
          _selection.addAll(all);
        }
      });

  void clearSelection() => setState(() {
        _selection.clear();
        _anchor = null;
      });

  /// Adds every photograph of the camera group of [reference] to the
  /// selection (issue #274, the album's #78): the same camera, or — for a
  /// photograph without camera data — every other one without any.
  ///
  /// Gathered from what the inbox shows ([images]), so a trashed photograph
  /// is never selected unseen; *added* to the selection, never replacing it.
  void selectSameCamera(ImagePart reference) {
    var added = [
      for (var image in images)
        if (inSameCameraGroup(image, reference) && !isSelected(image)) image,
    ];
    if (added.isEmpty) {
      showMessage(nothingElseFromCamera(_l10n, reference));
      return;
    }
    setState(() {
      _selection.addAll(added);
      _anchor = added.last;
    });
  }

  /// Makes [image] the whole selection and the anchor of a following
  /// shift-click — what a secondary click on a tile outside the selection
  /// does before its menu opens (issue #222, the album's rule of #156).
  void selectOnly(ImagePart image) => setState(() {
        _selection
          ..clear()
          ..add(image);
        _anchor = image;
      });

  // -------------------------------------------------------------------------
  // The browser's own context menu.
  // -------------------------------------------------------------------------

  /// This screen's claim on the browser's context menu, see
  /// [_syncBrowserMenu] and [BrowserMenuClaim].
  final BrowserMenuClaim _browserMenu = BrowserMenuClaim();

  /// Takes the browser's context menu away while the inbox stands (issue
  /// #222), the [BrowserMenu] seam of #144: on the web a secondary click on a
  /// tile would otherwise open the browser's menu over the tile's own, see
  /// [InboxTileState.showTileMenu]. An inbox is always in its selection mode,
  /// so the claim is held for as long as the screen is mounted, see
  /// [BrowserMenuClaim]; asked on every build as the album asks (#156).
  void _syncBrowserMenu() => _browserMenu.hold(share == null);

  @override
  void dispose() {
    _browserMenu.release();
    // What was sorted here, moved out or thrown away is no longer waiting: the
    // badge on the start page is asked anew once this screen is gone, and
    // never while the tree is being torn down.
    var refresh = _refreshCaller;
    if (refresh != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) => refresh());
    }
    super.dispose();
  }

  // -------------------------------------------------------------------------
  // The actions of a photo, see `photo_actions.dart` (issue #290).
  // -------------------------------------------------------------------------

  @override
  PhotoFacts get photoFacts {
    var permission =
        CallerInfo.peek(context)?.permission ?? CallerPermission.unknown;
    return PhotoFacts(
      view: PhotoView.inbox,
      mode: PhotoMode.select,
      rights: rights,
      // Where the server has not said who the caller is, nothing is hidden
      // on a guess, see `offeredRights`.
      signedIn: CallerInfo.peek(context) == null || permission.named,
      shareLink: share != null,
    );
  }

  @override
  BuildContext get photoContext => context;

  @override
  int get actedOnCount => _selection.length;

  @override
  VAlbumRouterDelegate? get photoDelegate =>
      widget.albumState.navigator.delegate;

  @override
  void openPhoto(PhotoTile tile) =>
      widget.pushPart(tile.image, tile.image.name);

  @override
  Future<void> movePhotos(PhotoTile tile) =>
      // The photo under the pointer covers an album made for it (#273).
      moveSelection(cover: tile.image.name);

  @override
  Future<void> deletePhotos(PhotoTile tile) => deleteSelection();

  /// Adds the selected photographs to a collection, chosen or created (issue
  /// #221): nothing changes in the inbox.
  @override
  Future<void> collectPhotos(PhotoTile? tile) => collectWithPicker(
        context: context,
        client: client,
        source: path,
        names: [
          for (var image in selected)
            if (image.hasPicture) image.name,
        ],
        delegate: widget.albumState.navigator.delegate,
      );

  /// Downloads the originals of the selection (issue #164): one archive
  /// named after the inbox where the platform keeps files, one original
  /// after the other on a phone.
  @override
  Future<void> downloadPhotos(PhotoTile? tile) {
    var names = [for (var image in selected) image.name];
    if (names.isEmpty) {
      showMessage(inboxNothingSelected(_l10n));
      return Future.value();
    }
    return runDownload(
      context,
      (progress) => downloadOriginals(
        client,
        path,
        names,
        archiveName: archiveNameOf(album.title),
        progress: progress,
      ),
    );
  }

  @override
  Future<void> adjustPhotoTime(PhotoTile tile) =>
      adjustRecordingTimeOf(tile.image);

  @override
  Future<void> showPhotoProperties(PhotoTile tile) =>
      showProperties(tile.image);

  /// Crops the photograph of [tile] (issue #212): the editor the viewer's
  /// menu opens, on this photograph alone whatever is selected, written at
  /// once through its own `?action=crop` — which is how the inbox writes
  /// anyway.
  @override
  Future<void> cropPhotoOf(PhotoTile tile) async {
    var image = tile.image;
    var changed = await cropPhoto(
      context,
      client: client,
      albumPath: path,
      imageUrl: "$albumUrl${image.name}",
      image: image,
      album: album,
    );
    if (changed && mounted) {
      // The tile has another aspect now: the day is laid out anew.
      relayout();
    }
  }

  /// `Escape` clears the selection (issue #290), as it leaves the album's
  /// select mode.
  KeyEventResult onKey(FocusNode node, KeyEvent event) {
    if (event is KeyDownEvent &&
        event.logicalKey == LogicalKeyboardKey.escape &&
        _selection.isNotEmpty) {
      clearSelection();
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  /// The selected photographs, in the order the inbox shows them.
  List<ImagePart> get selected => [
        for (var image in images)
          if (isSelected(image)) image,
      ];

  /// Lays the screen out anew, after a photograph changed its aspect.
  void relayout() => setState(() {});

  void showMessage(String message) =>
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(message), duration: const Duration(seconds: 6)),
      );

  // -------------------------------------------------------------------------
  // The writes, which happen at once.
  // -------------------------------------------------------------------------

  /// Applies [edit] to the album and writes the sidecar straight away.
  ///
  /// There is no buffer to put an edit in and no Save to write it: what the
  /// inbox changes is written the moment it is changed, the way the folder
  /// properties of a listing are. A refused write takes the change back
  /// ([undo]) and says the server's own reason, so the screen never shows
  /// something the server does not hold.
  ///
  /// What is sent is the inbox exactly as the server answered it, with the
  /// edited part substituted: the server keeps its own order and its own
  /// grouping for an inbox (`Inboxes.restoreArrangement`), so a round trip
  /// carries the edit and nothing else.
  Future<void> writeNow(VoidCallback edit, VoidCallback undo) async {
    // Applied only once [writeAction] lets the write go out: offline nothing
    // changes on the screen either.
    await writeAction(
      context,
      () {
        setState(edit);
        return client.saveAlbum(path, album);
      },
      onRefused: (_) {
        if (mounted) {
          setState(undo);
        }
      },
    );
  }

  /// Turns [image] by [operation] and writes it, see [writeNow].
  Future<void> turn(
    ImagePart image,
    Orientation Function(Orientation) operation,
  ) {
    var before = image.orientation;
    var crop = image.crop;
    return writeNow(
      // The crop rides along into the new frame (issue #212).
      () => turnImage(image, operation(image.orientation)),
      () {
        image.orientation = before;
        image.crop = crop;
      },
    );
  }

  /// Rates by the rating button for [value] on the tile of [image] and
  /// writes it at once, see [writeNow] and [toggleRating] — the album tile's
  /// buttons (#160).
  ///
  /// The button acts on the whole selection where [image] is part of a
  /// selection of several, otherwise on [image] alone ([ratingTargets], issue
  /// #224), in one write taken back as a whole on a refusal; what the pressed
  /// tile's button shows decides the rating for all of them. Rating several
  /// as trash asks first ([confirmTrashing]). A photograph rated as trash
  /// leaves the screen with the write (see [inboxDays]) and therefore the
  /// selection; a refused write brings back both.
  Future<void> rate(ImagePart image, int value) => trashOrRate(
        ratingTargets(image, selected),
        toggleRating(image.rating, value),
      );

  /// Gives [chosen] the rating [rating] in one write, asking first where
  /// several are rated as trash (issue #224) — refused offline before any
  /// question is asked.
  Future<void> trashOrRate(List<ImagePart> chosen, int rating) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    if (rating == trashRating &&
        !await confirmTrashing(context, chosen.length)) {
      return;
    }
    if (!mounted) {
      return;
    }
    await rateAll(chosen, rating);
  }

  /// Gives every one of [chosen] the rating [rating] in one write.
  Future<void> rateAll(List<ImagePart> chosen, int rating) {
    var before = {for (var image in chosen) image: image.rating};
    var selected = {for (var image in chosen) image: isSelected(image)};
    return writeNow(
      () {
        for (var image in chosen) {
          image.rating = rating;
          if (!isVisiblePart(image, minMinRating)) {
            _selection.remove(image);
            if (identical(_anchor, image)) {
              _anchor = null;
            }
          }
        }
      },
      () {
        before.forEach((image, rating) => image.rating = rating);
        selected.forEach((image, was) {
          if (was) {
            _selection.add(image);
          }
        });
      },
    );
  }

  /// Sets the privacy level of [image] and writes it, see [writeNow].
  ///
  /// The control stays in the inbox although nobody but an editor ever sees
  /// one (the author's decision on issue #131): this is where a photograph's
  /// level is set *before* it is sorted, and it keeps that level when it
  /// leaves.
  Future<void> setPrivacy(ImagePart image, int level) {
    var before = image.privacy;
    return writeNow(
      () => image.privacy = level,
      () => image.privacy = before,
    );
  }

  /// Corrects the recording time of the selection, see issues #77 and #102.
  ///
  /// Kept, and kept first: the grouping of the whole screen is the recording
  /// time, so a photograph with a wrong one lands under the wrong day and is
  /// sorted into the wrong album. [adjustRecordingTime] refiles every
  /// corrected image by its new date, so the days on the screen are right
  /// again the moment the write goes through.
  Future<void> adjustRecordingTimeOf(ImagePart invokedOn) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    // The selection, or the tile the tool was invoked on where nothing is
    // selected: one photograph whose time is wrong is corrected without
    // having to be selected first.
    var selection = Set<AlbumPart>.identity();
    if (_selection.isEmpty) {
      selection.add(invokedOn);
    } else {
      selection.addAll(_selection);
    }
    var chosen = selectedImages(album, selection);
    var reference = referenceImage(album, selection, invokedOn);
    if (reference == null) {
      showMessage(_l10n.nothingToAdjust);
      return;
    }

    var answer = await showFormDialog<TimeCorrection>(
      context: context,
      builder: (context) => AdjustRecordingTimeDialog(
        reference: reference,
        images: chosen,
      ),
    );
    if (answer == null || !mounted) {
      return;
    }

    // What the album held before, so a refused write can be taken back whole:
    // the correction refiles the parts, so both the dates and the order have
    // to be remembered.
    var parts = List.of(album.parts);
    var dates = {for (var image in chosen) image: image.date};
    void undo() {
      dates.forEach((image, date) => image.date = date);
      album.parts = parts;
      AlbumInitializer().init(album);
    }

    var changed = false;
    switch (answer) {
      case UseNameDates():
        for (var image in chosen) {
          var named = differingNameDate(image);
          var offset = named == null ? null : offsetFor(image, named);
          if (offset == null) {
            continue;
          }
          var one = Set<AlbumPart>.identity()..add(image);
          changed = adjustRecordingTime(album, one, offset) || changed;
        }
      case ShiftToTime(corrected: var corrected):
        var offset = offsetFor(reference, corrected);
        changed =
            offset != null && adjustRecordingTime(album, selection, offset);
    }
    if (!changed) {
      showMessage(_l10n.nothingToAdjust);
      return;
    }
    // Already applied to the model by [adjustRecordingTime]: what is left is
    // the write, and the way back if it is refused.
    await writeNow(() {}, undo);
  }

  /// Shows what [image] is; an inbox photograph has no description (#136).
  Future<void> showProperties(ImagePart image) =>
      showImageProperties(context, image, editable: false);

  // -------------------------------------------------------------------------
  // The two ways out of the inbox.
  // -------------------------------------------------------------------------

  /// Moves the selection into an album, see issues #47 and #114.
  ///
  /// The picker offers the album that does not exist yet, proposed with the
  /// earliest day of the selection — which on this screen is the day whose
  /// heading was tapped, and which is exactly the album that is about to be
  /// made out of it.
  ///
  /// [cover] is the photo a tile's context menu was opened on, see
  /// `moveWithPicker` and issue #273.
  Future<void> moveSelection({String? cover}) async {
    var chosen = selected;
    if (chosen.isEmpty) {
      showMessage(inboxNothingSelected(_l10n));
      return;
    }
    await moveWithPicker(
      context: context,
      client: client,
      source: path,
      names: [for (var image in chosen) image.thumbnailName],
      subject: ImageSubject(chosen.length),
      delegate: widget.albumState.navigator.delegate,
      takenOn: earliestTaken(chosen),
      cover: cover,
      onMoved: () {
        if (!mounted) {
          return;
        }
        clearSelection();
        // The listing above shows this inbox by a cover that may just have
        // moved away, and the inbox itself is asked for anew.
        if (path.isNotEmpty) {
          widget.albumState.navigator.delegate
              .forget(path.sublist(0, path.length - 1));
        }
        widget.albumState.reload();
      },
    );
  }

  /// Deletes the selection.
  ///
  /// For an editor ([mayWrite]) exactly what "delete" is in an album (issue
  /// #160): every selected photograph is rated −2 and written at once — asked
  /// first where there are several (issue #224, the question of a tile's
  /// trash button) — it vanishes from the inbox and is restored or purged in
  /// the trash page of issue #152, which the menu offers from then on
  /// ([mayShowTrash]).
  ///
  /// A member with `contribute` alone (issue #135) cannot write the sidecar
  /// and so cannot rate; for them the delete stays what #131 made it — see
  /// [takeOutIntoTrashFolder] — until issue #159 decides otherwise.
  Future<void> deleteSelection() async {
    var chosen = selected;
    if (chosen.isEmpty) {
      showMessage(inboxNothingSelected(_l10n));
      return;
    }
    if (mayWrite) {
      await trashOrRate(chosen, trashRating);
      return;
    }
    await takeOutIntoTrashFolder(chosen);
  }

  /// Whether the menu offers the trash page of issue #152: to an editor, where
  /// a photograph of this inbox is rated as trash.
  bool get mayShowTrash => mayWrite && hasTrashedImages(album);

  /// Opens the trash of this inbox, the album's own page (#152).
  void showTrash() => widget.albumState.navigator.go(TrashRoute(path));

  /// Puts [chosen] into the trash folder of the space, see issues #109 and
  /// #135 — the delete of a contributor, see [deleteSelection].
  ///
  /// The same `?action=delete` an album is deleted with, naming photographs
  /// instead of folders: the server moves each of them into
  /// `<space>/.valbum/trash/<album folder>/` by the move mechanism, sidecar
  /// part and hash entry carried. Nothing is deleted from disk, and the
  /// question says so.
  Future<void> takeOutIntoTrashFolder(List<ImagePart> chosen) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var messenger = ScaffoldMessenger.of(context);
    var confirmed = await showAppDialog<bool>(
      context: context,
      builder: (context) => AppAlertDialog(
        key: const Key("inbox-delete-dialog"),
        title: Text(inboxDeleteTitle(_l10n, chosen.length)),
        content: Text(inboxDeleteExplanation(_l10n)),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(_l10n.cancel),
          ),
          ElevatedButton(
            key: const Key("inbox-delete-confirm"),
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(_l10n.delete),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }

    var done = await writeAction(
      context,
      () => client.delete(
        path,
        [for (var image in chosen) image.thumbnailName],
      ),
    );
    if (done == null) {
      return;
    }
    var result = done.value;

    clearSelection();
    if (path.isNotEmpty) {
      widget.albumState.navigator.delegate
          .forget(path.sublist(0, path.length - 1));
    }
    widget.albumState.reload();

    // Verbatim, every name of them: the server says what it did with each
    // photograph and the app paraphrases none of it.
    var said = [
      for (var outcome in result.outcomes)
        if (outcome.message.isNotEmpty) outcome.message,
    ];
    messenger.showSnackBar(
      SnackBar(
        content: Text(said.isEmpty
            ? _l10n.deletedWhat(ImageSubject(chosen.length).asked(_l10n))
            : said.join(" ")),
        duration: const Duration(seconds: 8),
      ),
    );
  }

  /// Opens the album properties: the title and the subtitle of the inbox.
  ///
  /// The very dialog the album page opens and the very write it makes: an
  /// inbox has no buffer, so this is always the "write at once" branch. The
  /// inbox is what the space names it, never an album switched to one (issue
  /// #226), and the server never renames it after its title.
  Future<void> editProperties() async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var result = await showFormDialog<AlbumProperties>(
      context: context,
      builder: (context) => AlbumPropertiesDialog(
        AlbumProperties(
          title: album.title,
          subTitle: album.subTitle,
          date: album.date,
          indexPicture: album.indexPicture,
          kind: album.kind,
        ),
        client: client,
        baseUrl: widget.baseUrl,
      ),
    );
    if (result == null || !mounted) {
      return;
    }

    var before = AlbumProperties(
      title: album.title,
      subTitle: album.subTitle,
      date: album.date,
      indexPicture: album.indexPicture,
      kind: album.kind,
    );
    void apply(AlbumProperties values) {
      album.title = values.title;
      album.subTitle = values.subTitle;
      album.date = values.date;
      album.indexPicture = values.indexPicture;
    }

    var messenger = ScaffoldMessenger.of(context);
    var done = await writeAction(
      context,
      () {
        setState(() => apply(result));
        return client.saveAlbum(path, album);
      },
      onRefused: (_) {
        if (mounted) {
          setState(() => apply(before));
        }
      },
    );
    if (done == null || !mounted) {
      return;
    }
    var stored = done.value;
    if (stored.message.isEmpty) {
      // The same address, the new title shown.
      widget.albumState.navigator.delegate.forget(path);
      widget.albumState.reload();
      return;
    }
    widget.albumState.showPath(splitPath(stored.path));
    messenger.showSnackBar(
      SnackBar(
        content: Text(stored.message),
        duration: const Duration(seconds: 6),
      ),
    );
  }

  // -------------------------------------------------------------------------
  // The screen itself.
  // -------------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    _syncBrowserMenu();
    var all = _allDays;
    var cameras = inboxCameras(all);
    var active = activeCameras(_cameraFilter, cameras);
    // The viewer opened from here pages through what is shown (issue #278).
    setCameraFilter(album, active);
    var sections = filterByCamera(all, active);
    var count = _selection.length;
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: PageAppBar(
        // The way back is the leading control at the left on every page of
        // the app (issue #100).
        leading: path.isEmpty
            ? null
            : IconButton(
                icon: const Icon(Icons.arrow_back),
                tooltip: _l10n.up,
                onPressed: widget.albumState.showParent,
              ),
        automaticallyImplyLeading: false,
        title: Column(
          children: [
            Text(album.title),
            if (count > 0)
              Text(
                inboxSelectionLine(_l10n, count),
                key: const Key("inbox-selection"),
                style: const TextStyle(fontSize: 12),
              ),
          ],
        ),
        centerTitle: true,
        actions: [
          const StatusIndicator(),
          menu(context, inboxMenu(count)),
        ],
      ),
      body: Column(
        children: [
          // Says plainly when the inbox below is the copy from the cache;
          // every action then refuses with the usual reason.
          OfflineBanner(onRetry: widget.albumState.reload),
          Expanded(
            child: sections.isEmpty
                ? Center(
                    child: Padding(
                      padding: const EdgeInsets.all(32),
                      child: Text(
                        inboxEmptyNotice(_l10n),
                        key: const Key("inbox-empty"),
                        style: const TextStyle(
                          color: Colors.white70,
                          fontSize: 16,
                        ),
                      ),
                    ),
                  )
                // A scrollbar and the paging keys, issue #168.
                : KeyboardScroll(
                    onKeyEvent: guardKeys(onKey),
                    child: LayoutBuilder(
                      builder: (context, constraints) {
                        // Clear of a cutout or the navigation bar at a side
                        // in landscape; the app bar took the top inset.
                        var insets = MediaQuery.paddingOf(context);
                        return CustomScrollView(
                          scrollDirection: Axis.vertical,
                          scrollCacheExtent: const ScrollCacheExtent.viewport(
                              contextViewports),
                          slivers: [
                            // One camera or none: nothing to filter by.
                            if (cameras.length > 1)
                              SliverPadding(
                                padding: EdgeInsets.only(
                                    left: insets.left, right: insets.right),
                                sliver: SliverToBoxAdapter(
                                  child: cameraChips(cameras, active),
                                ),
                              ),
                            SliverPadding(
                              padding: EdgeInsets.only(
                                  left: insets.left, right: insets.right),
                              sliver: SliverMainAxisGroup(
                                slivers: buildSlivers(
                                  sections,
                                  constraints.maxWidth -
                                      insets.left -
                                      insets.right,
                                ),
                              ),
                            ),
                            SliverToBoxAdapter(
                              child: SizedBox(height: insets.bottom),
                            ),
                          ],
                        );
                      },
                    ),
                  ),
          ),
        ],
      ),
      floatingActionButton: rights.mayContribute && share == null
          ? FloatingActionButton(
              onPressed: widget.albumState.uploadImages,
              tooltip: _l10n.upload,
              child: const Icon(Icons.cloud_upload),
            )
          : null,
    );
  }

  /// The menu of the inbox: what is done with the selection, and with the
  /// folder itself.
  ///
  /// No "view as" (an inbox is shown to nobody else), no rating filter (the
  /// trashed are hidden, the rest is all shown), no share link (the server refuses one), no sort
  /// (the order *is* the date) — what is left is what an inbox is for.
  List<PopupMenuEntry<void Function(BuildContext)>> inboxMenu(int count) {
    // What is done with the selection, as the catalogue allows it (#290).
    var facts = photoFacts;
    return [
      if (count > 0 && PhotoAction.move.allowedFor(facts))
        keyedMenuItem(
          const Key("move-to"),
          Icons.drive_file_move,
          inboxMoveLabel(_l10n, count),
          (_) => moveSelection(),
        ),
      if (count > 0 && PhotoAction.delete.allowedFor(facts))
        keyedMenuItem(
          const Key("delete-selection"),
          Icons.delete_outline,
          inboxDeleteLabel(_l10n, count),
          (_) => deleteSelection(),
        ),
      if (count > 0 && PhotoAction.download.allowedFor(facts))
        keyedMenuItem(
          const Key("download-selection"),
          Icons.download,
          _l10n.downloadSelection(count),
          (_) => downloadPhotos(null),
        ),
      if (selected.any((image) => image.hasPicture) &&
          PhotoAction.collect.allowedFor(facts))
        keyedMenuItem(
          const Key("add-to-collection"),
          Icons.add_link,
          _l10n.addCountToAlbumAsReference(count),
          (_) => collectPhotos(null),
        ),
      // What was deleted here, restored or purged where an album's is
      // (issues #152, #160).
      if (mayShowTrash)
        keyedMenuItem(
          const Key("show-trash"),
          Icons.delete_sweep_outlined,
          _l10n.showTrash,
          (_) => showTrash(),
        ),
      if (count > 0)
        keyedMenuItem(
          const Key("clear-selection"),
          Icons.deselect,
          inboxClearSelection(_l10n),
          (_) => clearSelection(),
        ),
      // The one thing the properties of an inbox are for: saying that it is
      // one, and saying that it is not one any more, see issue #136.
      if (mayWrite)
        keyedMenuItem(
          const Key("album-properties"),
          Icons.tune,
          _l10n.albumProperties,
          (_) => editProperties(),
        ),
      menuItem(Icons.update, _l10n.reload, (_) => widget.albumState.reload()),
      if (share == null)
        menuItem(Icons.settings, _l10n.serverMenuEntry, openServerSettings),
      // What this app is, the last entry of every main menu (#187).
      aboutMenuItem(_l10n),
    ];
  }

  /// The chips of the camera filter (issue #278), each with the number of
  /// photographs of its camera, in the style of the album's label chips
  /// (#213); scrolling away with the photographs as those do.
  Widget cameraChips(List<InboxCamera> cameras, Set<String> active) => Padding(
        key: const Key("inbox-camera-chips"),
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
        child: Wrap(
          alignment: WrapAlignment.center,
          spacing: 8,
          runSpacing: 4,
          children: [
            for (var camera in cameras)
              _cameraChip(camera, active.contains(camera.camera)),
          ],
        ),
      );

  Widget _cameraChip(InboxCamera camera, bool selected) => FilterChip(
        key: camera.chipKey,
        selected: selected,
        showCheckmark: false,
        // Opaque, as the album's label chip and for its reason: a translucent
        // colour lets Material 3's light surface through the black page.
        backgroundColor: Color.alphaBlend(Colors.white12, Colors.black),
        selectedColor: Colors.white,
        side: const BorderSide(color: Colors.white38),
        avatar: camera.unknown
            ? Icon(
                Icons.no_photography,
                size: 16,
                color: selected ? Colors.black54 : Colors.white60,
              )
            : null,
        label: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              camera.label(_l10n),
              style: TextStyle(color: selected ? Colors.black : Colors.white),
            ),
            const SizedBox(width: 6),
            Text(
              "${camera.count}",
              style: TextStyle(
                color: selected ? Colors.black54 : Colors.white60,
              ),
            ),
          ],
        ),
        onSelected: (_) => toggleCamera(camera.camera),
      );

  /// The inbox as the slivers of its scroll view: a month line where the
  /// month changes, a day heading, and the rows of that day between them.
  ///
  /// The layout of every day is computed here — it is cheap, and a row cannot
  /// be laid out without its neighbours — while the *widget* of a row, and
  /// with it the thumbnail of every tile in it, is built on demand, exactly
  /// as the album page builds its rows since issue #111.
  List<Widget> buildSlivers(List<InboxDay> sections, double maxWidth) {
    var result = <Widget>[];
    beginRowBlocks();
    DateTime? shownMonth;
    for (var section in sections) {
      var month = section.month;
      if (month != null && month != shownMonth) {
        shownMonth = month;
        // Everything of the month, so that tapping the line takes a whole
        // month into an album in one gesture.
        var ofMonth = [
          for (var candidate in sections)
            if (candidate.month == month) ...candidate.images,
        ];
        result.add(SliverToBoxAdapter(
          child: sectionHeading(
            key: inboxMonthKey(month),
            text: inboxMonthFormat(_l10n).format(month),
            images: ofMonth,
            fontSize: 26,
          ),
        ));
      }
      result.add(SliverToBoxAdapter(
        child: sectionHeading(
          key: section.headingKey,
          text: section.heading(_l10n),
          images: section.images,
          fontSize: 20,
        ),
      ));
      result.add(rowsOf(section.images, maxWidth));
    }
    return result;
  }

  /// One derived heading: the text, how many photographs stand under it, and
  /// the tap that selects them all.
  Widget sectionHeading({
    required Key key,
    required String text,
    required List<ImagePart> images,
    required double fontSize,
  }) {
    var all = images.isNotEmpty && images.every(isSelected);
    return InkWell(
      key: key,
      onTap: () => toggleSection(images),
      child: Padding(
        padding: EdgeInsets.only(top: fontSize, bottom: 8, left: 16, right: 16),
        // Left-aligned: a centred heading with a check box in front of it
        // reads as a stray control (the author, 2026-09-23); the album's own
        // headings follow with issue #158.
        child: Row(
          mainAxisAlignment: MainAxisAlignment.start,
          children: [
            Icon(
              all ? Icons.check_box : Icons.check_box_outline_blank,
              size: fontSize,
              color: all ? Colors.amberAccent : Colors.white54,
            ),
            const SizedBox(width: 8),
            Flexible(
              child: Tooltip(
                message: inboxHeadingTooltip(_l10n),
                child: Text(
                  text,
                  style: TextStyle(fontSize: fontSize, color: Colors.white),
                ),
              ),
            ),
            const SizedBox(width: 8),
            Text(
              "${images.length}",
              style: const TextStyle(fontSize: 14, color: Colors.white54),
            ),
          ],
        ),
      ),
    );
  }

  /// The rows of one day, built on demand, see [buildSlivers].
  Widget rowsOf(List<ImagePart> images, double maxWidth) {
    var layout = layouter.AlbumLayout(maxWidth, 250, images);
    var pageWidth = layout.getPageWidth();
    var rows = layout.getRows();
    var builder = ContentWidgetBuilder(tileOf, pageWidth);
    double topGap(int index) => index > 0 ? tileSpacing : 0.0;
    double extentOf(int index) =>
        rowExtent(rows[index], pageWidth) + topGap(index);
    return SliverVariedExtentList(
      // Where the way back from a viewer finds a row, see [ReturnReveal].
      key: rowBlock(rows, extentOf, topGap),
      itemExtentBuilder: (index, dimensions) => extentOf(index),
      delegate: RowListDelegate(
        (context, index) =>
            builder.buildRow(rows[index], topGap: topGap(index)),
        childCount: rows.length,
        totalExtent: [
          for (var index = 0; index < rows.length; index++) extentOf(index),
        ].fold(0.0, (sum, extent) => sum + extent),
      ),
    );
  }

  /// The tile of one photograph of the inbox.
  Widget tileOf(AbstractImage image, double width, double height) {
    var part = layouter.ToImage.toImage(image);
    return InboxTile(
      inbox: this,
      image: part,
      width: width,
      height: height,
      key: ValueKey(part.name),
    );
  }

  /// The picture of one tile, turned by its stored orientation and marked
  /// with what the album page marks it with: the play mark of a video and the
  /// privacy level.
  Widget pictureOf(ImagePart image, double width, double height) {
    var picture = orientedImageThumbnail(
      client,
      "$albumUrl${image.thumbnailName}",
      image,
      width: width,
      height: height,
    );
    var marker = privacyIcon(image.privacy);
    // A file that cannot be read is its broken tile alone (issue #253): it
    // plays nothing, so it carries no play mark.
    if (image.isBroken || (image.kind == ImageKind.image && marker == null)) {
      return picture;
    }
    return SizedBox(
      width: width,
      height: height,
      child: Stack(
        fit: StackFit.expand,
        children: [
          picture,
          if (image.kind != ImageKind.image)
            const Center(
              child: Icon(
                Icons.play_circle_outline,
                key: Key("video-indicator"),
                size: 48,
                color: Colors.white70,
                shadows: [Shadow(color: Colors.black54, blurRadius: 8)],
              ),
            ),
          if (marker != null)
            Positioned(
              top: 4,
              left: 4,
              child: IgnorePointer(
                child: Tooltip(
                  message: privacyName(_l10n, image.privacy),
                  child: Icon(
                    marker,
                    key: const Key("privacy-marker"),
                    size: 18,
                    color: Colors.white,
                    shadows: const [
                      Shadow(color: Colors.black, blurRadius: 4),
                    ],
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// One photograph of the inbox: the picture, the selection and the tools that
/// write at once.
///
/// The tile of the album's edit mode without what an inbox has no use for: no
/// drag handle and no reordering (the order is the date), no grouping, no
/// heading, no album picture, no description. What is left acts immediately —
/// a rotation or a rating is a sidecar PUT, not an entry in a buffer.
///
/// A tap **selects**, with the album's semantics
/// ([InboxContentState.handleTap]): the inbox is always in the selection
/// mode, which is what it is for. The viewer is one tool away ([inboxOpenTooltip]), because a screen
/// whose tap selects needs a way to look at a photograph closely, and the
/// long press toggles, the second selection gesture the album uses.
class InboxTile extends StatefulWidget {
  final InboxContentState inbox;
  final ImagePart image;
  final double width;
  final double height;

  const InboxTile({
    super.key,
    required this.inbox,
    required this.image,
    required this.width,
    required this.height,
  });

  @override
  State<StatefulWidget> createState() => InboxTileState();
}

class InboxTileState extends State<InboxTile> {
  /// The words this tile reads in.
  AppLocalizations get _l10n => AppLocalizations.of(context)!;

  bool _hovered = false;

  InboxContentState get inbox => widget.inbox;
  ImagePart get image => widget.image;

  bool get selected => inbox.isSelected(image);

  /// Whether the tile shows its tools.
  bool get active => selected || _hovered;

  @override
  Widget build(BuildContext context) =>
      SelectionExcluded(child: _buildTile(context));

  Widget _buildTile(BuildContext context) => MouseRegion(
        hitTestBehavior: HitTestBehavior.translucent,
        opaque: false,
        onEnter: (event) => setState(() => _hovered = true),
        onExit: (event) => setState(() => _hovered = false),
        child: SizedBox(
          width: widget.width,
          height: widget.height,
          child: Stack(
            children: [
              Positioned.fill(
                // The tile shell of every grid (issue #290): a tap selects,
                // a long press toggles, a secondary click opens the tile's
                // menu at the pointer (issue #222).
                child: PhotoTileGestures(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => inbox.handleTap(image),
                  onLongPress: () => inbox.toggleSelection(image),
                  onMenu: showTileMenu,
                  child: inbox.pictureOf(image, widget.width, widget.height),
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
              if (active)
                Positioned(top: 0, left: 0, right: 0, child: topBar()),
              if (active)
                Positioned(bottom: 0, left: 0, right: 0, child: bottomBar()),
            ],
          ),
        ),
      );

  /// The selection box, the camera group (issue #274) and the rotations,
  /// which write at once.
  Widget topBar() => toolbar([
        toolButton(
          selected ? Icons.check_box : Icons.check_box_outline_blank,
          inboxSelectTooltip(_l10n),
          () => inbox.toggleSelection(image),
          active: selected,
          key: const Key("inbox-select"),
        ),
        // Everything of this camera, or everything without camera data, as on
        // the album's tile (issue #274); not on a file that cannot be read.
        if (image.hasPicture)
          toolButton(
            selectSameCameraIcon(image),
            selectSameCameraLabel(_l10n, image),
            () => inbox.selectSameCamera(image),
            key: const Key("inbox-select-camera"),
          ),
        // A file that cannot be read has no picture to turn (issue #253).
        if (inbox.mayWrite && image.hasPicture) ...[
          toolButton(
            Icons.rotate_right,
            _l10n.turnRight,
            () => inbox.turn(image, OrientationOps.rotR),
            key: const Key("inbox-rotate-right"),
          ),
          toolButton(
            Icons.swap_vert,
            _l10n.flipVertically,
            () => inbox.turn(image, OrientationOps.flipV),
          ),
          toolButton(
            Icons.rotate_left,
            _l10n.turnLeft,
            () => inbox.turn(image, OrientationOps.rotL),
            key: const Key("inbox-rotate-left"),
          ),
        ],
      ]);

  /// The viewer, the recording time, what the photograph is, its rating and
  /// its privacy level.
  Widget bottomBar() {
    var level = image.privacy;
    var next = nextPrivacy(level);
    return toolbar([
      toolButton(
        Icons.open_in_full,
        inboxOpenTooltip(_l10n),
        () => inbox.widget.pushPart(image, image.name),
        key: const Key("inbox-open"),
      ),
      if (PhotoAction.time.offeredOn(image, inbox.photoFacts))
        toolButton(
          Icons.more_time,
          _l10n.adjustRecordingTimeAction,
          () => inbox.adjustRecordingTimeOf(image),
          key: const Key("inbox-adjust-time"),
        ),
      toolButton(
        Icons.notes,
        _l10n.imageProperties,
        () => inbox.showProperties(image),
        key: const Key("inbox-properties"),
      ),
      // The album tile's rating buttons, written at once (issue #160). Only
      // an editor: a `contribute` member sorting their own photographs cannot
      // write the sidecar a rating lives in (#135) — whether they should be
      // able to is issue #159.
      if (inbox.mayWrite)
        ...ratingButtons(
          _l10n,
          rating: image.rating,
          onRate: (value) => inbox.rate(image, value),
          button: toolButton,
        ),
      if (inbox.mayWrite)
        toolButton(
          privacyControlIcon(level),
          // The generated signature names the next level first, see
          // `app_localizations.dart`.
          _l10n.privacyControlTooltip(
            privacyName(_l10n, next),
            privacyName(_l10n, level),
          ),
          () => inbox.setPrivacy(image, next),
          active: level != privacyPublic,
          key: const Key("privacy-control"),
        ),
    ]);
  }

  /// The context menu of this tile, opened by a secondary click at
  /// [position] (issue #222), the album edit mode's (#156) for an inbox: the
  /// catalogue's tile menu (#290), see `photo_actions.dart`.
  ///
  /// It acts on the whole selection when this photograph is part of it;
  /// otherwise this photograph first becomes the selection alone, so that
  /// what the menu says is what it acts on.
  Future<void> showTileMenu(Offset position) async {
    if (!selected) {
      inbox.selectOnly(image);
    }
    await showPhotoTileMenu(
      host: inbox,
      tile: PhotoTile(image, image),
      position: position,
      keyPrefix: "inbox-context",
    );
  }

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
    Key? key,
  }) =>
      IconButton(
        key: key,
        icon: Icon(icon),
        iconSize: 18,
        color: active ? Colors.amberAccent : Colors.white,
        tooltip: tooltip,
        padding: const EdgeInsets.all(4),
        visualDensity: VisualDensity.compact,
        constraints: const BoxConstraints(minWidth: 28, minHeight: 28),
        onPressed: onPressed,
      );
}
