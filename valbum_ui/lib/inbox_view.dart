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
///    sorted by date (`Inboxes.flatten`), and this screen draws a heading per
///    day with a month line where the month changes. Nothing of that is
///    stored, exactly as `effectiveDate` is derived and never stored;
///  * tapping a heading selects everything under it, which is how a day (or a
///    month) is moved into an album in one gesture;
///  * what an inbox has no use for is not there: no reorder, no drag handles,
///    no headings of its own, no description, no album picture, no rating, no
///    groups, no "view as".
///
/// It is a screen of its own rather than a mode of `album_view.dart` for one
/// reason: the album page is built around its edit session — the buffer, the
/// dirty flag, the leave guard, the reordering — and an inbox must never have
/// one. Everything that can be shared *is* shared: the row layout
/// ([layouter.AlbumLayout], [ContentWidgetBuilder], [rowExtent]), the oriented
/// thumbnail, the recording-time dialog, the image properties, the move and
/// the delete flows, the router and the viewer.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/rendering.dart' show ScrollCacheExtent;
import 'package:intl/intl.dart';
import 'package:valbum_ui/album_layout.dart' as layouter;

import 'album_edit.dart';
import 'album_model.dart';
import 'album_view.dart';
import 'app.dart';
import 'caller.dart';
import 'camera_roll_view.dart';
import 'client.dart';
import 'image_properties.dart';
import 'l10n/app_localizations.dart';
import 'listing_view.dart';
import 'move_view.dart';
import 'offline.dart';
import 'oriented_thumbnail.dart';
import 'resource.dart';
import 'rights.dart';
import 'settings.dart';
import 'share_session.dart';

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
String inboxHeadingTooltip(AppLocalizations l10n) =>
    l10n.selectEverythingBelow;

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

final DateFormat _dayKeyFormat = DateFormat("yyyy-MM-dd");
final DateFormat _monthKeyFormat = DateFormat("yyyy-MM");

/// The key the month line of [month] carries.
Key inboxMonthKey(DateTime month) =>
    Key("inbox-month-${_monthKeyFormat.format(month)}");

/// The parts of an inbox as the days the screen draws, see [InboxDay].
///
/// The server answers the parts flat and sorted by date, the undated last
/// (`Inboxes.flatten`), so the days come out in that very order: a day is
/// opened when a photograph of a day not seen before arrives. A group cannot
/// reach an inbox — the server dissolves one on the way out — but where one
/// did, its images are taken one by one, which is what the inbox shows
/// everywhere else.
List<InboxDay> inboxDays(List<AlbumPart> parts) {
  var order = <String>[];
  var byDay = <String, List<ImagePart>>{};
  var days = <String, DateTime?>{};

  void add(ImagePart image) {
    DateTime? day;
    if (image.date != 0) {
      var taken = DateTime.fromMillisecondsSinceEpoch(image.date);
      day = DateTime(taken.year, taken.month, taken.day);
    }
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

class InboxContentState extends State<InboxContent> {
  /// The photographs currently selected, by identity.
  ///
  /// Held by the state and by nothing else: there is no edit session to keep
  /// it in, and a trip into the viewer and back rebuilds the screen from the
  /// album the router still holds — the selection is the one thing such a
  /// trip costs, which is the price of having no buffer at all.
  final Set<ImagePart> _selection = Set.identity();

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

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    share = ShareSession.of(context);
  }

  @override
  void didUpdateWidget(InboxContent oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.album, widget.album)) {
      _selection.clear();
    }
  }

  /// What the caller may do with this inbox, as the server answered it.
  Rights get rights => offeredRights(
        Rights.of(album),
        CallerInfo.peek(context)?.permission ?? CallerPermission.unknown,
      );

  /// Whether this caller may write the inbox's sidecar.
  ///
  /// The rotation, the recording time and the privacy level are one sidecar
  /// PUT, which the server grants to `edit` alone; a member with `contribute`
  /// sees their own contributions here (issue #135) and may take them out —
  /// see [mayTakeOut] — but not rewrite the album.
  bool get mayWrite => rights.mayEdit && share == null;

  /// Whether this caller may move photographs out of the inbox and delete
  /// them.
  ///
  /// `edit`, or the contributor of the photographs themselves: the server
  /// applies the rule of issue #53 and refuses what it must with
  /// `CONTRIBUTION_REFUSED`, which is read out as it is said.
  bool get mayTakeOut =>
      (rights.mayEdit || rights.mayContribute) && share == null;

  /// Every photograph of the inbox, in the order the server answered it.
  List<ImagePart> get images => [
        for (var section in inboxDays(album.parts)) ...section.images,
      ];

  bool isSelected(ImagePart image) => _selection.contains(image);

  void toggleSelection(ImagePart image) => setState(() {
        if (!_selection.remove(image)) {
          _selection.add(image);
        }
      });

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

  void clearSelection() => setState(_selection.clear);

  /// The selected photographs, in the order the inbox shows them.
  List<ImagePart> get selected => [
        for (var image in images)
          if (isSelected(image)) image,
      ];

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
    if (refuseWhileOffline(context)) {
      return;
    }
    var messenger = ScaffoldMessenger.of(context);
    setState(edit);
    try {
      await client.saveAlbum(path, album);
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(undo);
      showRefusal(messenger, error);
    }
  }

  /// Turns [image] by [operation] and writes it, see [writeNow].
  Future<void> turn(
    ImagePart image,
    Orientation Function(Orientation) operation,
  ) {
    var before = image.orientation;
    return writeNow(
      () => image.orientation = operation(image.orientation),
      () => image.orientation = before,
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

    var answer = await showDialog<TimeCorrection>(
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
  Future<void> moveSelection() async {
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
      albumDate: newAlbumDay(chosen),
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

  /// Puts the selection into the trash of the space, see issues #109 and #135.
  ///
  /// The same `?action=delete` an album is deleted with, naming photographs
  /// instead of folders: the server moves each of them into
  /// `<space>/.valbum/trash/<album folder>/` by the move mechanism, sidecar
  /// part and hash entry carried. Nothing is deleted from disk, and the
  /// question says so.
  Future<void> deleteSelection() async {
    var chosen = selected;
    if (chosen.isEmpty) {
      showMessage(inboxNothingSelected(_l10n));
      return;
    }
    if (refuseWhileOffline(context)) {
      return;
    }
    var messenger = ScaffoldMessenger.of(context);
    var confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
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

    MoveResult result;
    try {
      result = await client.delete(
        path,
        [for (var image in chosen) image.thumbnailName],
      );
    } catch (error) {
      // The server's own reason, as every other refused write shows it.
      showRefusal(messenger, error);
      return;
    }

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

  /// Opens the album properties, which for an inbox carry the kind switch.
  ///
  /// The very dialog the album page opens and the very write it makes: an
  /// inbox has no buffer, so this is always the "write at once" branch, and
  /// the answer says where the folder is now — clearing the switch dates the
  /// folder again and may rename it (issue #130).
  Future<void> editProperties() async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var result = await showDialog<AlbumProperties>(
      context: context,
      builder: (context) => AlbumPropertiesDialog(
        AlbumProperties(
          title: album.title,
          subTitle: album.subTitle,
          date: album.date,
          indexPicture: album.indexPicture,
          kind: album.kind,
        ),
        mayChangeKind: true,
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
      album.kind = values.kind;
    }

    setState(() => apply(result));

    var messenger = ScaffoldMessenger.of(context);
    CreateResult stored;
    try {
      stored = await client.saveAlbum(path, album);
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() => apply(before));
      showRefusal(messenger, error);
      return;
    }
    if (!mounted) {
      return;
    }
    // The listing above shows this folder by its title and its kind, both of
    // which may have just changed -- and so may the folder's own name (#130).
    if (path.isNotEmpty) {
      widget.albumState.navigator.delegate
          .forget(path.sublist(0, path.length - 1));
    }
    if (stored.message.isEmpty) {
      // The same address, another kind: the album page takes over, or this
      // screen does, without anybody leaving the folder they are standing in.
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
    var sections = inboxDays(album.parts);
    var count = _selection.length;
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
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
          const CameraRollIndicator(),
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
                : LayoutBuilder(
                    builder: (context, constraints) => CustomScrollView(
                      scrollDirection: Axis.vertical,
                      scrollCacheExtent:
                          const ScrollCacheExtent.viewport(contextViewports),
                      slivers: [
                        ...buildSlivers(sections, constraints.maxWidth),
                        SliverToBoxAdapter(
                          child: SizedBox(
                            height: MediaQuery.paddingOf(context).bottom,
                          ),
                        ),
                      ],
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
  /// No "view as" (an inbox is shown to nobody else), no rating filter (an
  /// inbox has no ratings), no share link (the server refuses one), no sort
  /// (the order *is* the date) — what is left is what an inbox is for.
  List<PopupMenuEntry<void Function(BuildContext)>> inboxMenu(int count) => [
        if (mayTakeOut && count > 0) ...[
          keyedMenuItem(
            const Key("move-to"),
            Icons.drive_file_move,
            inboxMoveLabel(_l10n, count),
            (_) => moveSelection(),
          ),
          keyedMenuItem(
            const Key("delete-selection"),
            Icons.delete_outline,
            inboxDeleteLabel(_l10n, count),
            (_) => deleteSelection(),
          ),
        ],
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
      ];

  /// The inbox as the slivers of its scroll view: a month line where the
  /// month changes, a day heading, and the rows of that day between them.
  ///
  /// The layout of every day is computed here — it is cheap, and a row cannot
  /// be laid out without its neighbours — while the *widget* of a row, and
  /// with it the thumbnail of every tile in it, is built on demand, exactly
  /// as the album page builds its rows since issue #111.
  List<Widget> buildSlivers(List<InboxDay> sections, double maxWidth) {
    var result = <Widget>[];
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
    if (image.kind == ImageKind.image && marker == null) {
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
/// drag handle and no reordering (the order is the date), no rating, no
/// grouping, no heading, no album picture, no description. What is left acts
/// immediately — a rotation is a sidecar PUT, not an entry in a buffer.
///
/// A tap **selects**: the inbox is always in the selection mode, which is
/// what it is for. The viewer is one tool away ([inboxOpenTooltip]), because
/// a screen whose tap selects needs a way to look at a photograph closely,
/// and the long press is the second selection gesture the album uses.
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
  Widget build(BuildContext context) => MouseRegion(
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
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => inbox.toggleSelection(image),
                  onLongPress: () => inbox.toggleSelection(image),
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

  /// The selection box and the rotations, which write at once.
  Widget topBar() => toolbar([
        toolButton(
          selected ? Icons.check_box : Icons.check_box_outline_blank,
          inboxSelectTooltip(_l10n),
          () => inbox.toggleSelection(image),
          active: selected,
          key: const Key("inbox-select"),
        ),
        if (inbox.mayWrite) ...[
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

  /// The viewer, the recording time, what the photograph is, and its privacy
  /// level.
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
      if (inbox.mayWrite)
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
