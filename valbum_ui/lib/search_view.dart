/// The search view of issue #227: the photographs below a folder that match
/// the criteria chosen, shown by the album grid and viewer in date order.
///
/// A level on the folder it searches (`/<folder>/.search/`, see
/// [SearchRoute]), reached from the search icon of a folder's app bar — the
/// start page's searches the space — and offered to the members of the space
/// alone. What was chosen and what was found live with the router
/// ([SearchSession]), so a photograph opened from the results and closed again
/// finds the search as it was left.
///
/// The server answers the search as an album of [AlbumKind.search] whose
/// photographs are named by their path below the folder, so the album grid
/// shows them as an album of the folder would: every thumbnail and every
/// original is the photograph's own address. The search view only looks:
/// what is to be kept is saved as a view ("Save as view…", an album of kind
/// [AlbumKind.search] storing nothing but the query) or turned into a
/// collection ("Create collection from these…"); a photograph is edited in a
/// saved view or in its album ("Show in album").
library;

import 'package:flutter/material.dart';

import 'album_date.dart';
import 'album_model.dart';
import 'album_view.dart';
import 'app.dart';
import 'client.dart';
import 'form_dialog.dart';
import 'l10n/app_localizations.dart';
import 'listing_view.dart' show splitPath;
import 'move_view.dart';
import 'offline.dart';
import 'resource.dart';
import 'routes.dart';
import 'search_query.dart';

/// What the search view below one folder holds: the criteria, the search
/// running or done, and the options offered, see issue #227.
///
/// While a saved search is edited ([edited]), the view searches where the
/// saved search looks and "Save" writes the query back to it.
class SearchSession extends ChangeNotifier {
  /// What the user chose.
  SearchCriteria criteria = const SearchCriteria();

  /// The search for [criteria], `null` while nothing is chosen.
  Future<AlbumInfo>? result;

  /// What the folder offers to choose from, asked once.
  Future<SearchOptions>? options;

  /// The path of the saved search being edited, `null` for a search that is
  /// not saved.
  List<String>? edited;

  /// The saved search being edited, as it was opened.
  AlbumInfo? editedAlbum;

  /// Searches below [scope] for the current [criteria].
  void run(VAlbumClient client, List<String> scope) {
    if (criteria.isEmpty) {
      result = null;
    } else {
      var search = client.search(scope, criteria.toQuery()).then((album) {
        AlbumInitializer().init(album);
        return album;
      });
      // A refusal arriving before the view listens is the view's to show,
      // never an uncaught error.
      search.ignore();
      result = search;
    }
    notifyListeners();
  }

  /// Chooses [value] and searches for it.
  void choose(SearchCriteria value, VAlbumClient client, List<String> scope) {
    criteria = value;
    run(client, scope);
  }

  /// Starts editing the saved search [album] at [path] with what it stores.
  void edit(List<String> path, AlbumInfo album, SearchCriteria stored) {
    edited = path;
    editedAlbum = album;
    criteria = stored;
    result = null;
  }

  /// Leaves the editing of a saved search: a fresh search.
  void endEdit() {
    edited = null;
    editedAlbum = null;
    criteria = const SearchCriteria();
    result = null;
  }
}

/// The search view, see the library comment.
class SearchView extends StatefulWidget {
  /// The level of the router this view is shown on.
  final VAlbumState albumState;

  const SearchView(this.albumState, {super.key});

  @override
  State<SearchView> createState() => SearchViewState();
}

class SearchViewState extends State<SearchView> {
  /// The folder searched below.
  List<String> get path => widget.albumState.path;

  VAlbumClient get client => widget.albumState.client;

  SearchSession get session =>
      widget.albumState.navigator.delegate.searchSession(path);

  AppLocalizations get _l10n => AppLocalizations.of(context)!;

  late SearchSession _listened;

  final TextEditingController _text = TextEditingController();

  @override
  void initState() {
    super.initState();
    _listened = session;
    _listened.addListener(_changed);
    // The options are offered where they arrive; without them the choosers
    // offer nothing, and the search itself still says why.
    _listened.options ??= client.searchOptions(path)..ignore();
    _text.text = _listened.criteria.text;
    if (_listened.result == null && !_listened.criteria.isEmpty) {
      _listened.run(client, path);
    }
  }

  @override
  void dispose() {
    _listened.removeListener(_changed);
    _text.dispose();
    super.dispose();
  }

  void _changed() {
    if (mounted) {
      setState(() {});
    }
  }

  void _choose(SearchCriteria value) => session.choose(value, client, path);

  @override
  Widget build(BuildContext context) {
    var l10n = _l10n;
    var editing = session.edited != null;
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        leading: IconButton(
          key: const Key("search-up"),
          icon: const Icon(Icons.arrow_back),
          tooltip: l10n.up,
          onPressed: leave,
        ),
        automaticallyImplyLeading: false,
        title: Text(editing ? l10n.editSearch : l10n.searchAction),
        actions: [
          if (editing)
            IconButton(
              key: const Key("search-save-edit"),
              icon: const Icon(Icons.save),
              tooltip: l10n.save,
              onPressed: session.criteria.isEmpty ? null : saveEdit,
            )
          else
            IconButton(
              key: const Key("search-save-as-view"),
              icon: const Icon(Icons.bookmark_add_outlined),
              tooltip: l10n.saveAsView,
              onPressed: session.criteria.isEmpty ? null : saveAsView,
            ),
          IconButton(
            key: const Key("search-create-collection"),
            icon: const Icon(Icons.collections_bookmark_outlined),
            tooltip: l10n.createCollectionFromThese,
            onPressed: session.result == null ? null : createCollection,
          ),
        ],
      ),
      body: Column(
        children: [
          OfflineBanner(onRetry: () => session.run(client, path)),
          FutureBuilder<SearchOptions>(
            future: session.options,
            builder: (context, snapshot) =>
                criteriaBar(snapshot.data ?? SearchOptions()),
          ),
          Expanded(child: results()),
        ],
      ),
    );
  }

  /// The way back: to the saved search being edited, or to the folder.
  void leave() {
    var edited = session.edited;
    if (edited != null) {
      session.endEdit();
      widget.albumState.navigator.go(ListingOrAlbumRoute(edited));
      return;
    }
    widget.albumState.showParent();
  }

  // --- The criteria. ---

  /// The chips of the criteria, and the text field.
  Widget criteriaBar(SearchOptions options) {
    var l10n = _l10n;
    var criteria = session.criteria;
    var personNames = {for (var p in options.persons) p.id: p.name};
    var placeNames = {for (var p in options.places) p.geonameId: p.name};
    var ratingNames = _ratingNames(l10n);
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 8, 8, 4),
      child: Wrap(
        key: const Key("search-criteria"),
        spacing: 6,
        runSpacing: 6,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          for (var person in criteria.persons)
            InputChip(
              key: Key("search-person-$person"),
              avatar: const Icon(Icons.person, size: 18),
              label: Text(personNames[person] ?? person),
              onDeleted: () => _choose(
                criteria.copyWith(
                  persons: [...criteria.persons]..remove(person),
                ),
              ),
            ),
          ActionChip(
            key: const Key("search-add-persons"),
            avatar: const Icon(Icons.person_add_alt, size: 18),
            label: Text(l10n.searchPersons),
            onPressed: () => choosePersons(options),
          ),
          _dateChip(
            key: const Key("search-from"),
            label: l10n.searchFrom,
            value: criteria.from,
            onPicked: (day) => _choose(criteria.copyWith(from: day)),
            onCleared: () => _choose(criteria.copyWith(clearFrom: true)),
          ),
          _dateChip(
            key: const Key("search-to"),
            label: l10n.searchTo,
            value: criteria.to,
            onPicked: (day) => _choose(criteria.copyWith(to: day)),
            onCleared: () => _choose(criteria.copyWith(clearTo: true)),
          ),
          for (var place in criteria.places)
            InputChip(
              key: Key("search-place-$place"),
              avatar: const Icon(Icons.place, size: 18),
              label: Text(placeNames[place] ?? "$place"),
              onDeleted: () => _choose(
                criteria.copyWith(places: [...criteria.places]..remove(place)),
              ),
            ),
          ActionChip(
            key: const Key("search-add-places"),
            avatar: const Icon(Icons.add_location_alt_outlined, size: 18),
            label: Text(l10n.searchPlaces),
            onPressed: () => choosePlaces(options),
          ),
          for (var label in criteria.labels)
            InputChip(
              key: Key("search-label-$label"),
              avatar: const Icon(Icons.label_outline, size: 18),
              label: Text(label),
              onDeleted: () => _choose(
                criteria.copyWith(labels: [...criteria.labels]..remove(label)),
              ),
            ),
          ActionChip(
            key: const Key("search-add-labels"),
            avatar: const Icon(Icons.new_label_outlined, size: 18),
            label: Text(l10n.searchLabels),
            onPressed: () => chooseLabels(options),
          ),
          criteria.minRating == null
              ? ActionChip(
                  key: const Key("search-rating"),
                  avatar: const Icon(Icons.star_border, size: 18),
                  label: Text(l10n.searchRating),
                  onPressed: chooseRating,
                )
              : InputChip(
                  key: const Key("search-rating"),
                  avatar: const Icon(Icons.star, size: 18),
                  label: Text(
                    l10n.searchRatingAtLeast(
                      ratingNames[criteria.minRating] ?? "",
                    ),
                  ),
                  onPressed: chooseRating,
                  onDeleted: () =>
                      _choose(criteria.copyWith(clearMinRating: true)),
                ),
          criteria.camera == null
              ? ActionChip(
                  key: const Key("search-camera"),
                  avatar: const Icon(Icons.photo_camera_outlined, size: 18),
                  label: Text(l10n.searchCamera),
                  onPressed: () => chooseCamera(options),
                )
              : InputChip(
                  key: const Key("search-camera"),
                  avatar: const Icon(Icons.photo_camera, size: 18),
                  label: Text(criteria.camera!),
                  onPressed: () => chooseCamera(options),
                  onDeleted: () =>
                      _choose(criteria.copyWith(clearCamera: true)),
                ),
          DropdownButton<SearchMediaKind>(
            key: const Key("search-media"),
            value: criteria.media,
            dropdownColor: Colors.grey.shade900,
            style: const TextStyle(color: Colors.white),
            underline: const SizedBox.shrink(),
            items: [
              DropdownMenuItem(
                value: SearchMediaKind.all,
                child: Text(l10n.searchMediaAll),
              ),
              DropdownMenuItem(
                value: SearchMediaKind.photos,
                child: Text(l10n.searchMediaPhotos),
              ),
              DropdownMenuItem(
                value: SearchMediaKind.videos,
                child: Text(l10n.searchMediaVideos),
              ),
            ],
            onChanged: (value) {
              if (value != null) {
                _choose(criteria.copyWith(media: value));
              }
            },
          ),
          SizedBox(
            width: 280,
            child: TextField(
              key: const Key("search-text"),
              controller: _text,
              style: const TextStyle(color: Colors.white),
              textInputAction: TextInputAction.search,
              decoration: InputDecoration(
                isDense: true,
                prefixIcon: const Icon(Icons.search, color: Colors.white70),
                hintText: l10n.searchText,
                hintStyle: const TextStyle(color: Colors.white54),
              ),
              onSubmitted: (value) =>
                  _choose(session.criteria.copyWith(text: value)),
            ),
          ),
        ],
      ),
    );
  }

  Widget _dateChip({
    required Key key,
    required String label,
    required DateTime? value,
    required void Function(DateTime day) onPicked,
    required VoidCallback onCleared,
  }) {
    Future<void> pick() async {
      var now = DateTime.now();
      var picked = await showDatePicker(
        context: context,
        initialDate: value ?? now,
        firstDate: DateTime(1800),
        lastDate: DateTime(now.year + 1, 12, 31),
      );
      if (picked != null) {
        onPicked(picked);
      }
    }

    if (value == null) {
      return ActionChip(
        key: key,
        avatar: const Icon(Icons.event, size: 18),
        label: Text(label),
        onPressed: pick,
      );
    }
    return InputChip(
      key: key,
      avatar: const Icon(Icons.event, size: 18),
      label: Text("$label ${dayLabel(value)}"),
      onPressed: pick,
      onDeleted: onCleared,
    );
  }

  /// A day as the search view shows it, `yyyy-MM-dd`.
  static String dayLabel(DateTime day) => albumFolderName(day, "").trim();

  static Map<int, String> _ratingNames(AppLocalizations l10n) => {
    -1: l10n.ratingPoor,
    0: l10n.ratingUnrated,
    1: l10n.ratingGood,
    2: l10n.ratingVeryGood,
  };

  Future<void> choosePersons(SearchOptions options) async {
    var chosen = await _chooseMany<String>(
      title: _l10n.searchPersons,
      choices: {for (var p in options.persons) p.id: p.name},
      chosen: session.criteria.persons,
    );
    if (chosen != null) {
      _choose(session.criteria.copyWith(persons: chosen));
    }
  }

  Future<void> choosePlaces(SearchOptions options) async {
    var chosen = await _chooseMany<int>(
      title: _l10n.searchPlaces,
      choices: {
        for (var p in options.places)
          p.geonameId: p.country.isEmpty ? p.name : "${p.name} (${p.country})",
      },
      chosen: session.criteria.places,
    );
    if (chosen != null) {
      _choose(session.criteria.copyWith(places: chosen));
    }
  }

  Future<void> chooseLabels(SearchOptions options) async {
    var chosen = await _chooseMany<String>(
      title: _l10n.searchLabels,
      choices: {for (var l in options.labels) l.name: l.name},
      chosen: session.criteria.labels,
    );
    if (chosen != null) {
      _choose(session.criteria.copyWith(labels: chosen));
    }
  }

  Future<void> chooseRating() async {
    var names = _ratingNames(_l10n);
    var picked = await showDialog<int>(
      context: context,
      builder: (context) => SimpleDialog(
        key: const Key("search-rating-dialog"),
        title: Text(_l10n.searchRating),
        children: [
          SimpleDialogOption(
            onPressed: () => Navigator.of(context).pop(_noRating),
            child: Text(_l10n.searchAnyRating),
          ),
          for (var entry in names.entries)
            SimpleDialogOption(
              key: Key("search-rating-${entry.key}"),
              onPressed: () => Navigator.of(context).pop(entry.key),
              child: Text(_l10n.searchRatingAtLeast(entry.value)),
            ),
        ],
      ),
    );
    if (picked == null) {
      return;
    }
    _choose(
      picked == _noRating
          ? session.criteria.copyWith(clearMinRating: true)
          : session.criteria.copyWith(minRating: picked),
    );
  }

  static const int _noRating = -99;

  Future<void> chooseCamera(SearchOptions options) async {
    var picked = await showDialog<String>(
      context: context,
      builder: (context) => SimpleDialog(
        key: const Key("search-camera-dialog"),
        title: Text(_l10n.searchCamera),
        children: [
          SimpleDialogOption(
            onPressed: () => Navigator.of(context).pop(""),
            child: Text(_l10n.searchAnyCamera),
          ),
          for (var camera in options.cameras)
            SimpleDialogOption(
              key: Key("search-camera-${camera.name}"),
              onPressed: () => Navigator.of(context).pop(camera.name),
              child: Text(camera.name),
            ),
        ],
      ),
    );
    if (picked == null) {
      return;
    }
    _choose(
      picked.isEmpty
          ? session.criteria.copyWith(clearCamera: true)
          : session.criteria.copyWith(camera: picked),
    );
  }

  /// Asks which of [choices] (by their value, shown by their name) are
  /// chosen, starting from [chosen]; `null` where the dialog was left.
  Future<List<T>?> _chooseMany<T>({
    required String title,
    required Map<T, String> choices,
    required List<T> chosen,
  }) => showDialog<List<T>>(
    context: context,
    builder: (context) =>
        _ChooseManyDialog<T>(title: title, choices: choices, chosen: chosen),
  );

  // --- The results. ---

  Widget results() {
    var l10n = _l10n;
    var result = session.result;
    if (result == null) {
      return _sentence(l10n.searchChooseHint, const Key("search-hint"));
    }
    return FutureBuilder<AlbumInfo>(
      future: result,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          var error = snapshot.error;
          return _sentence(
            error is VAlbumException ? error.message : "$error",
            const Key("search-error"),
          );
        }
        var album = snapshot.data;
        if (album == null) {
          return const Center(child: CircularProgressIndicator());
        }
        if (!album.parts.any((part) => part is AbstractImage)) {
          return _sentence(l10n.searchNothingFound, const Key("search-empty"));
        }
        return AlbumContent(
          widget.albumState,
          album,
          widget.albumState.baseUrl,
          widget.albumState.pushPart,
          key: ObjectKey(album),
        );
      },
    );
  }

  static Widget _sentence(String text, Key key) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Text(
        text,
        key: key,
        textAlign: TextAlign.center,
        style: const TextStyle(color: Colors.white70, fontSize: 16),
      ),
    ),
  );

  /// The names of the photographs found, `null` while nothing was found yet.
  Future<List<String>> _foundNames() async {
    var album = await session.result;
    if (album == null) {
      return const [];
    }
    return [
      for (var part in album.parts)
        if (part is ImagePart) part.name,
    ];
  }

  // --- What is kept of a search. ---

  /// Saves the search as a view, see issue #227: a folder chosen with the
  /// move picker's folder choice, a title proposed from what was chosen, an
  /// album of [AlbumKind.search] created there with the query and nothing
  /// else — and opened.
  Future<void> saveAsView() async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var l10n = _l10n;
    var messenger = ScaffoldMessenger.of(context);
    var options = await session.options?.catchError((_) => SearchOptions());
    if (!mounted) {
      return;
    }
    var picked = await showFormDialog<PickedTarget>(
      context: context,
      builder: (context) => FolderPicker(
        key: const Key("save-as-view-picker"),
        client: client,
        title: l10n.saveAsView,
        initialPath: path,
        confirmLabel: (_) => l10n.saveAsViewHere,
        targetIsAlbum: false,
      ),
    );
    if (picked == null || !mounted) {
      return;
    }
    var criteria = session.criteria;
    var proposed = criteria.title(
      fallback: l10n.searchAction,
      personNames: {for (var p in options?.persons ?? <Person>[]) p.id: p.name},
      placeNames: {
        for (var p in options?.places ?? <PlaceTag>[]) p.geonameId: p.name,
      },
    );
    var title = await showDialog<String>(
      context: context,
      builder: (context) => TextInputDialog(
        key: const Key("save-as-view-title"),
        title: l10n.saveAsView,
        label: l10n.titleLabel,
        text: proposed,
        note: l10n.saveAsViewHint,
      ),
    );
    if (title == null || title.trim().isEmpty || !mounted) {
      return;
    }
    var name = title.trim();
    CreateResult created;
    try {
      created = await client.createAlbum(
        picked.path,
        AlbumInfo(
          kind: AlbumKind.search,
          title: name,
          path: albumFolderName(null, name),
          query: criteria.toQuery(),
        ),
      );
    } catch (error) {
      showRefusal(messenger, error);
      return;
    }
    var delegate = widget.albumState.navigator.delegate;
    delegate.forgetTree(picked.path);
    if (created.message.isNotEmpty) {
      messenger.showSnackBar(
        SnackBar(
          content: Text(created.message),
          duration: const Duration(seconds: 6),
        ),
      );
    }
    delegate.go(ListingOrAlbumRoute(splitPath(created.path)));
  }

  /// Writes the criteria to the saved search being edited, and goes back to
  /// it.
  Future<void> saveEdit() async {
    var edited = session.edited;
    var album = session.editedAlbum;
    if (edited == null || album == null || refuseWhileOffline(context)) {
      return;
    }
    var messenger = ScaffoldMessenger.of(context);
    // The saved search's own statements and the new query; no photograph:
    // what a saved search shows is never stored.
    var stored = AlbumInfo(
      kind: AlbumKind.search,
      title: album.title,
      subTitle: album.subTitle,
      starred: album.starred,
      date: album.date,
      indexPicture: album.indexPicture,
      query: session.criteria.toQuery(),
    );
    try {
      await client.saveAlbum(edited, stored);
    } catch (error) {
      showRefusal(messenger, error);
      return;
    }
    var delegate = widget.albumState.navigator.delegate;
    delegate.forget(edited);
    delegate.forgetTree(searchScopeOf(edited));
    session.endEdit();
    delegate.go(ListingOrAlbumRoute(edited));
  }

  /// Turns what was found into a collection, chosen or created, see issue
  /// #221: references to the photographs, nothing copied.
  Future<void> createCollection() async {
    var names = await _foundNames();
    if (!mounted) {
      return;
    }
    await collectWithPicker(
      context: context,
      client: client,
      source: path,
      names: names,
      delegate: widget.albumState.navigator.delegate,
    );
  }
}

/// The chooser of several values, see [SearchViewState._chooseMany].
class _ChooseManyDialog<T> extends StatefulWidget {
  final String title;
  final Map<T, String> choices;
  final List<T> chosen;

  const _ChooseManyDialog({
    super.key,
    required this.title,
    required this.choices,
    required this.chosen,
  });

  @override
  State<_ChooseManyDialog<T>> createState() => _ChooseManyDialogState<T>();
}

class _ChooseManyDialogState<T> extends State<_ChooseManyDialog<T>> {
  late final List<T> _chosen = [...widget.chosen];

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return AlertDialog(
      key: const Key("search-choose-dialog"),
      title: Text(widget.title),
      content: SizedBox(
        width: 360,
        child: widget.choices.isEmpty
            ? Text(l10n.searchNothingToChoose)
            : ListView(
                shrinkWrap: true,
                children: [
                  for (var entry in widget.choices.entries)
                    CheckboxListTile(
                      key: Key("search-choice-${entry.key}"),
                      value: _chosen.contains(entry.key),
                      title: Text(entry.value),
                      onChanged: (value) => setState(() {
                        if (value == true) {
                          _chosen.add(entry.key);
                        } else {
                          _chosen.remove(entry.key);
                        }
                      }),
                    ),
                ],
              ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.cancel),
        ),
        ElevatedButton(
          key: const Key("search-choose-done"),
          onPressed: () => Navigator.of(context).pop(_chosen),
          child: Text(l10n.done),
        ),
      ],
    );
  }
}
