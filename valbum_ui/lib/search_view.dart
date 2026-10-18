/// The search view of issue #227: the photographs below a folder that match
/// the criteria chosen, shown by the album grid and viewer in date order.
///
/// A level on the folder it searches (`/<folder>/.search/`, see
/// [SearchRoute]), reached from the search icon of a folder's app bar — the
/// start page's searches the space — or of an album's controls, which
/// searches the album alone (issues #243, #249), and offered to the members
/// of the space alone. Where it looks is the first chip of the criteria, "In:
/// <album>", whose removal searches the whole library for the same criteria.
/// The criteria scroll away with the results, one page on a phone (issue
/// #248). What was chosen and what was found live with the router
/// ([SearchSession]), so a photograph opened from the results and closed
/// again finds the search as it was left. The criteria travel in the
/// location as well (`?q=`, issue #243), so that a reload or a bookmark shows
/// the same search; a change of them replaces the location rather than adding
/// a step to the history, see `VAlbumRouterDelegate.searchChanged`.
///
/// The server answers the search as an album of [AlbumKind.search] whose
/// photographs are named by their path below the folder, so the album grid
/// shows them as an album of the folder would: every thumbnail and every
/// original is the photograph's own address. The search view edits nothing:
/// what is to be kept is saved as a view ("Save as view…", an album of kind
/// [AlbumKind.search] storing nothing but the query) or turned into a
/// collection ("Create collection from these…"); a photograph is edited in a
/// saved view or in its album ("Show in album").
///
/// Some of what was found is taken by the select mode of issue #209, which
/// the view offers to whoever may download, editors included — it has no edit
/// mode (issue #243): a long press on a tile or "Select" of the tile's menu
/// at the pointer enters it — a member who may not download enters it to
/// collect alone (issue #290) —, its bar replaces the view's bar and criteria
/// while it is on, and its actions download the selection and add it to a
/// collection.
///
/// "Group by" (issue #244) shows what was found under headings by album,
/// year, month, place or camera, see `search_grouping.dart`: it travels in the
/// query and is stored by "Save as view…", and a change of it alone groups the
/// result already in anew, without asking the server again.
/// A new search starts grouped by album ([freshSearchCriteria], issue #289),
/// and the heading of an album opens it; a stored saved search keeps the
/// grouping it stores. "Show in album" of a tile's menu opens the album a
/// photograph lies in with it in view and marked, see `photo_actions.dart`.
///
/// Every chosen criterion can be excluded (issue #242): a long press on its
/// chip, or a secondary click, turns it into "Not: …", and the choosers say
/// so with a switch of their own; "All of these" at the start of the criteria
/// turns into "Any of these", which finds what matches one of them.
///
/// Where the space has content search on ([SearchOptions.content], issue
/// #257), "AI search" next to the search field takes its text as a
/// description of what the photos show: the server ranks by it, the most
/// similar first, a page at a time ("Show more"), and the other chips narrow
/// what is ranked. The result is shown in the server's order, ungrouped
/// unless the user groups it; switching back restores the conventional
/// search with the criteria and the grouping it had. A refusal (content
/// search off, the embedder down) is said in the server's words.
///
/// "More like this" (issue #257, offered on a photo by the viewer and the
/// tile menus, see `photo_actions.dart`) opens this view with the photo as
/// the query: a chip showing its thumbnail, removable like every other one,
/// which leaves the conventional search of what else is chosen; the other
/// chips narrow it. It is a search by what the photos show, so the "AI
/// search" switch shows it on and cannot be turned off while the photo is the
/// query; the field's text then narrows as in the conventional search. A
/// photo that is not there for the reader, or not analysed yet, is said in
/// the app's words in place of the result.
library;

import 'package:flutter/material.dart';

import 'action_guard.dart';
import 'album_date.dart';
import 'album_model.dart';
import 'album_view.dart';
import 'app.dart';
import 'client.dart';
import 'dialog_style.dart';
import 'downloads.dart';
import 'filter_list.dart';
import 'form_dialog.dart';
import 'l10n/app_localizations.dart';
import 'listing_view.dart' show splitPath;
import 'loadable.dart';
import 'move_view.dart';
import 'offline.dart';
import 'resource.dart';
import 'rights.dart' show spaceOwnerOf;
import 'routes.dart';
import 'search_query.dart';
import 'selectable.dart';
import 'thumbnails.dart' show ThumbnailImage;
import 'page_app_bar.dart';

/// What the search view below one folder holds: the criteria, the search
/// running or done, and the options offered, see issue #227.
///
/// While a saved search is edited ([edited]), the view searches where the
/// saved search looks and "Save" writes the query back to it.
class SearchSession extends ChangeNotifier {
  /// What the user chose; a new search starts grouped by album (issue #289),
  /// see [freshSearchCriteria].
  SearchCriteria criteria = freshSearchCriteria;

  /// How many photos an AI search asks for (issue #257): a page, and one
  /// more page with every "Show more"; a new search starts with one page.
  int contentCount = contentSearchPage;

  /// The grouping of the conventional search while the AI search, which
  /// starts ungrouped, is on: what switching back restores, see
  /// [byContent].
  List<SearchGroup>? conventionalGroupBy;

  /// The search for [criteria], `null` while nothing is chosen.
  Future<AlbumInfo>? result;

  /// What the folder offers to choose from, asked once — and again after it
  /// failed, see [askOptions]; `null` before the first view of the folder.
  Loadable<SearchOptions>? options;

  /// Asks the server what the folder at [path] offers to choose from, unless
  /// that is on its way or arrived already. A failure is kept for the
  /// choosers to say, and asked again by the next view of the folder and by
  /// the retry of a chooser.
  void askOptions(VAlbumClient client, List<String> path) {
    var asked = options;
    if (asked == null) {
      asked = Loadable(() => client.searchOptions(path));
      // What arrives names the chosen values in the criteria bar.
      asked.addListener(notifyListeners);
      options = asked;
    }
    asked.load().ignore();
  }

  /// The path of the saved search being edited, `null` for a search that is
  /// not saved.
  List<String>? edited;

  /// The saved search being edited, as it was opened.
  AlbumInfo? editedAlbum;

  /// The album or folder this search of the whole library was widened from
  /// ([SearchViewState.widen]), `null` for a search entered any other way.
  ///
  /// It stays beneath the search, mounted as it was left, and the way back
  /// returns to it — not to the start page, which the search was never
  /// opened from (issue #259). The router forgets it once the search is
  /// left, see `VAlbumRouterDelegate.searchOriginOf`.
  List<String>? origin;

  /// The query of a location this screen cannot show (issue #243), as it was
  /// written; `null` while the location said nothing of the kind.
  ///
  /// The view then says that the search cannot be edited here, as "Edit
  /// search" does of such a saved search, and the location keeps it, so that
  /// a reload says the same. The first criterion chosen replaces it.
  String? unreadable;

  /// The criteria as the location carries them, see [SearchRoute.query]:
  /// the stored query as JSON, `null` while nothing is chosen.
  String? get addressQuery =>
      unreadable ?? (criteria.isEmpty ? null : criteria.toAddress());

  /// Takes over the criteria a location carries ([SearchRoute.query]) and
  /// searches for them: a reload, a bookmark, the browser's back and forward
  /// buttons (issue #243).
  ///
  /// What the session holds already is kept as it is, its result too, and a
  /// query this screen cannot show is kept as [unreadable].
  void adopt(String query, VAlbumClient client, List<String> scope) {
    if (query == addressQuery) {
      return;
    }
    var read = SearchCriteria.fromAddress(query);
    if (read == null) {
      unreadable = query;
      criteria = freshSearchCriteria;
      result = null;
      notifyListeners();
      return;
    }
    unreadable = null;
    criteria = read;
    contentCount = contentSearchPage;
    adoptions++;
    run(client, scope);
  }

  /// How often [adopt] took over criteria, so that the view puts their text
  /// into its field — and only then: a text typed but not yet submitted is
  /// kept while other criteria are chosen.
  int adoptions = 0;

  /// Searches below [scope] for the current [criteria].
  void run(VAlbumClient client, List<String> scope) {
    if (criteria.isEmpty) {
      result = null;
    } else {
      var query = criteria.toQuery();
      var search = client
          .search(
        scope,
        query,
        count: criteria.ranks ? contentCount : null,
      )
          .then((album) {
        // Grouped by what was asked for, whatever the server echoed.
        album.query = query;
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

  /// Chooses [value] and searches for it; a change of the grouping alone
  /// groups the result there is anew, see [regroup].
  void choose(SearchCriteria value, VAlbumClient client, List<String> scope) {
    var found = result != null && criteria.findsAs(value);
    unreadable = null;
    criteria = value;
    if (found) {
      regroup();
    } else {
      contentCount = contentSearchPage;
      run(client, scope);
    }
  }

  /// Switches the AI search of issue #257 on or off, with [text] the text
  /// of the field: on, the search starts ungrouped — in the order of
  /// similarity — and the grouping there was is kept for switching back;
  /// off, that grouping is restored, unless the user grouped the AI search
  /// in the meantime.
  void byContent(
    bool on,
    String text,
    VAlbumClient client,
    List<String> scope,
  ) {
    var value = criteria.copyWith(text: text, byContent: on);
    if (on && !criteria.byContent) {
      conventionalGroupBy = [...criteria.groupBy];
      value = value.copyWith(groupBy: const []);
    } else if (!on && criteria.byContent) {
      var restored = conventionalGroupBy;
      if (restored != null && criteria.groupBy.isEmpty) {
        value = value.copyWith(groupBy: restored);
      }
      conventionalGroupBy = null;
    }
    choose(value, client, scope);
  }

  /// Asks the AI search for one more page of photos, see [contentCount].
  void more(VAlbumClient client, List<String> scope) {
    contentCount += contentSearchPage;
    run(client, scope);
  }

  /// Groups the result there is by the grouping of [criteria] (issue #244):
  /// the headings are derived in the app, the server is not asked again.
  void regroup() {
    var current = result;
    if (current == null) {
      return;
    }
    var groupBy = [...criteria.groupBy];
    var regrouped = current.then((album) {
      album.query = (album.query ?? criteria.toQuery())..groupBy = groupBy;
      AlbumInitializer().init(album);
      return album;
    });
    regrouped.ignore();
    result = regrouped;
    notifyListeners();
  }

  /// Starts editing the saved search [album] at [path] with what it stores.
  void edit(List<String> path, AlbumInfo album, SearchCriteria stored) {
    edited = path;
    editedAlbum = album;
    unreadable = null;
    criteria = stored;
    contentCount = contentSearchPage;
    conventionalGroupBy = null;
    result = null;
  }

  /// Leaves the editing of a saved search: a fresh search.
  void endEdit() {
    edited = null;
    editedAlbum = null;
    unreadable = null;
    criteria = freshSearchCriteria;
    contentCount = contentSearchPage;
    conventionalGroupBy = null;
    result = null;
  }
}

/// How many photos an AI search asks for at a time (issue #257), see
/// [SearchSession.contentCount]: the server answers the most similar, never
/// more than it allows.
const int contentSearchPage = 100;

/// What a new search starts with (issue #289): nothing chosen, the photos
/// found grouped by the album they lie in — the question a search is asked
/// most is where the photographs are. A stored saved search keeps the
/// grouping it stores, see [SearchCriteria.fromQuery]: a persisted value is
/// never changed behind the user's back.
const SearchCriteria freshSearchCriteria =
    SearchCriteria(groupBy: [SearchGroup.album]);

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

  /// Whether the results are in the select mode, whose bar then replaces the
  /// view's own bar and the criteria (issue #243).
  bool _selecting = false;

  /// The search the select mode was entered on: a new search shows new
  /// results, outside the mode.
  Future<AlbumInfo>? _selectedIn;

  @override
  void initState() {
    super.initState();
    _listened = session;
    _listened.addListener(_changed);
    // The choosers wait for the options, and say why where they failed.
    _listened.askOptions(client, path);
    _text.text = _listened.criteria.text;
    _adopted = _listened.adoptions;
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

  /// The [SearchSession.adoptions] whose text the field shows.
  int _adopted = 0;

  void _changed() {
    if (mounted) {
      if (_adopted != session.adoptions) {
        _adopted = session.adoptions;
        _text.text = session.criteria.text;
      }
      setState(() {
        if (_selecting && !identical(_selectedIn, session.result)) {
          _selecting = false;
        }
      });
    }
  }

  /// The results entered or left the select mode, see [_selecting].
  void _selectModeChanged(bool on) {
    if (mounted) {
      setState(() {
        _selecting = on;
        _selectedIn = session.result;
      });
    }
  }

  /// Chooses [value] and searches for it; the location follows, replacing
  /// its entry of the history (issue #243).
  void _choose(SearchCriteria value) {
    session.choose(value, client, path);
    widget.albumState.navigator.delegate.searchChanged(context);
  }

  @override
  Widget build(BuildContext context) {
    var l10n = _l10n;
    var editing = session.edited != null;
    // The select mode's bar is the results' own (issue #243): it replaces
    // this bar and the criteria while the mode is on, and shows its own line
    // when the app is offline.
    var selecting = _selecting;
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: selecting
          ? null
          : PageAppBar(
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
          if (!selecting)
            OfflineBanner(onRetry: () => session.run(client, path)),
          // The criteria scroll away with what was found, one page from
          // the chips down to the last photograph (issue #248): on a phone
          // they would otherwise take most of the screen.
          Expanded(
            child: results(
              selecting
                  ? null
                  // The names of the values chosen, once the options arrived.
                  : criteriaBar(session.options?.value ?? SearchOptions()),
            ),
          ),
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
    var anyOf = criteria.anyOf && !criteria.ranks;
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 8, 8, 4),
      child: Wrap(
        key: const Key("search-criteria"),
        spacing: 6,
        runSpacing: 6,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          ..._scopeChip(),
          ActionChip(
            key: const Key("search-any-of"),
            avatar: Icon(
              anyOf ? Icons.join_full : Icons.join_inner,
              size: 18,
            ),
            label: Text(anyOf ? l10n.searchAnyOf : l10n.searchAllOf),
            // What is ranked is narrowed by all of the others (issue #257).
            onPressed: criteria.ranks
                ? null
                : () => _choose(criteria.copyWith(anyOf: !criteria.anyOf)),
          ),
          if (criteria.similar != null) _similarChip(criteria.similar!),
          for (var (person, excluded) in _rows(
            criteria.persons,
            criteria.excludedPersons,
          ))
            _valueChip<String>(
              key: "search-person-$person",
              icon: Icons.person,
              name: personNames[person] ?? person,
              value: person,
              excluded: excluded,
              included: criteria.persons,
              excludedValues: criteria.excludedPersons,
              choose: (included, excludedValues) => _choose(
                criteria.copyWith(
                  persons: included,
                  excludedPersons: excludedValues,
                ),
              ),
            ),
          ActionChip(
            key: const Key("search-add-persons"),
            avatar: const Icon(Icons.person_add_alt, size: 18),
            label: Text(l10n.searchPersons),
            onPressed: choosePersons,
          ),
          _dateChip(
            key: "search-from",
            label: l10n.searchFrom,
            value: criteria.from,
            onPicked: (day) => _choose(criteria.copyWith(from: day)),
            onCleared: () => _choose(criteria.copyWith(clearFrom: true)),
          ),
          _dateChip(
            key: "search-to",
            label: l10n.searchTo,
            value: criteria.to,
            onPicked: (day) => _choose(criteria.copyWith(to: day)),
            onCleared: () => _choose(criteria.copyWith(clearTo: true)),
          ),
          for (var (place, excluded) in _rows(
            criteria.places,
            criteria.excludedPlaces,
          ))
            _valueChip<int>(
              key: "search-place-$place",
              icon: Icons.place,
              name: placeNames[place] ?? "$place",
              value: place,
              excluded: excluded,
              included: criteria.places,
              excludedValues: criteria.excludedPlaces,
              choose: (included, excludedValues) => _choose(
                criteria.copyWith(
                  places: included,
                  excludedPlaces: excludedValues,
                ),
              ),
            ),
          ActionChip(
            key: const Key("search-add-places"),
            avatar: const Icon(Icons.add_location_alt_outlined, size: 18),
            label: Text(l10n.searchPlaces),
            onPressed: choosePlaces,
          ),
          for (var (label, excluded) in _rows(
            criteria.labels,
            criteria.excludedLabels,
          ))
            _valueChip<String>(
              key: "search-label-$label",
              icon: Icons.label_outline,
              name: label,
              value: label,
              excluded: excluded,
              included: criteria.labels,
              excludedValues: criteria.excludedLabels,
              choose: (included, excludedValues) => _choose(
                criteria.copyWith(
                  labels: included,
                  excludedLabels: excludedValues,
                ),
              ),
            ),
          ActionChip(
            key: const Key("search-add-labels"),
            avatar: const Icon(Icons.new_label_outlined, size: 18),
            label: Text(l10n.searchLabels),
            onPressed: chooseLabels,
          ),
          criteria.minRating == null
              ? ActionChip(
                  key: const Key("search-rating"),
                  avatar: const Icon(Icons.star_border, size: 18),
                  label: Text(l10n.searchRating),
                  onPressed: chooseRating,
                )
              : _kindChip(
                  key: "search-rating",
                  kind: SearchKind.rating,
                  icon: Icons.star,
                  name: l10n.searchRatingAtLeast(
                    ratingNames[criteria.minRating] ?? "",
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
                  onPressed: chooseCamera,
                )
              : _kindChip(
                  key: "search-camera",
                  kind: SearchKind.camera,
                  icon: Icons.photo_camera,
                  name: criteria.camera!,
                  onPressed: chooseCamera,
                  onDeleted: () =>
                      _choose(criteria.copyWith(clearCamera: true)),
                ),
          criteria.groupBy.isEmpty
              ? ActionChip(
                  key: const Key("search-group-by"),
                  avatar: const Icon(Icons.segment, size: 18),
                  label: Text(l10n.groupByTitle),
                  onPressed: chooseGrouping,
                )
              : InputChip(
                  key: const Key("search-group-by"),
                  avatar: const Icon(Icons.segment, size: 18),
                  label: Text(
                    l10n.searchGroupedBy(
                      criteria.groupBy
                          .map((key) => searchGroupName(l10n, key))
                          .join(" › "),
                    ),
                  ),
                  onPressed: chooseGrouping,
                  onDeleted: () =>
                      _choose(criteria.copyWith(groupBy: const [])),
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
                // The exclusion of the text: the field has no chip to press
                // long, its long press selects (issue #242).
                prefixIcon: IconButton(
                  key: Key(
                    criteria.excludes(SearchKind.text)
                        ? "search-text-exclude-excluded"
                        : "search-text-exclude",
                  ),
                  isSelected: criteria.excludes(SearchKind.text),
                  icon: const Icon(Icons.search, color: Colors.white70),
                  selectedIcon: const Icon(
                    Icons.block,
                    color: Colors.redAccent,
                  ),
                  tooltip: l10n.searchExclude,
                  // A description of what the photos show ranks, and is
                  // never excluded (issue #257).
                  onPressed:
                      criteria.has(SearchKind.text) && !criteria.textRanks
                          ? () => _choose(
                                session.criteria
                                    .copyWith(text: _text.text)
                                    .toggled(SearchKind.text),
                              )
                          : null,
                ),
                hintText: criteria.textRanks
                    ? l10n.searchContentHint
                    : l10n.searchText,
                hintStyle: const TextStyle(color: Colors.white54),
              ),
              onSubmitted: (value) =>
                  _choose(session.criteria.copyWith(text: value)),
            ),
          ),
          // The AI search, where the space has it on (issue #257) — or
          // where the criteria are one already, so that it can be left. A
          // photo as the query is one as well: on, and left by removing the
          // photo's chip.
          if (options.content || criteria.byWhatTheyShow)
            FilterChip(
              key: const Key("search-by-content"),
              avatar: const Icon(Icons.auto_awesome, size: 18),
              label: Text(l10n.searchByContent),
              tooltip: l10n.searchByContentTooltip,
              selected: criteria.byWhatTheyShow,
              onSelected: criteria.similar != null
                  ? null
                  : (on) {
                      session.byContent(on, _text.text, client, path);
                      widget.albumState.navigator.delegate
                          .searchChanged(context);
                    },
            ),
          _count(),
        ],
      ),
    );
  }

  /// The chip of the photo "More like this" compares with (issue #257): its
  /// thumbnail, and a delete button that leaves the search of the other
  /// chips. A reference the server answered empty (the reader may not see
  /// the photo) shows no picture.
  Widget _similarChip(String similar) {
    var space = spaceOwnerOf(path) != null ? [path.first] : const <String>[];
    var segments = [
      for (var segment in similar.split("/"))
        if (segment.isNotEmpty) segment,
    ];
    return InputChip(
      key: const Key("search-similar"),
      avatar: segments.isEmpty
          ? const Icon(Icons.image_search, size: 18)
          : CircleAvatar(
              key: const Key("search-similar-thumbnail"),
              backgroundImage: ThumbnailImage(
                client,
                client.baseUrl([...space, ...segments]),
              ),
            ),
      label: Text(_l10n.searchSimilarChip),
      tooltip: _l10n.searchSimilarTooltip,
      onDeleted: () => _choose(
        session.criteria.copyWith(text: _text.text, clearSimilar: true),
      ),
    );
  }

  /// The chip naming where the search looks (issue #249): the album or the
  /// folder it was opened from, "In: <title>"; nothing at the root, which is
  /// the whole library. Its delete button widens the search to the whole
  /// library, see [widen] — not while a saved search is edited, which looks
  /// below the folder it lies in.
  List<Widget> _scopeChip() {
    if (path.isEmpty) {
      return const [];
    }
    var editing = session.edited != null;
    return [
      FutureBuilder<Resource?>(
        // The level beneath has asked for it already: no request of its own.
        future: widget.albumState.navigator.delegate.resourceAt(path),
        builder: (context, snapshot) {
          var scope = snapshot.data;
          var title = switch (scope) {
            AlbumInfo(:var title) || ListingInfo(:var title) => title,
            _ => "",
          };
          return InputChip(
            key: const Key("search-scope"),
            avatar: Icon(
              scope is AlbumInfo
                  ? Icons.photo_album_outlined
                  : Icons.folder_outlined,
              size: 18,
            ),
            label: Text(
              _l10n.searchScope(title.isEmpty ? path.last : title),
            ),
            onDeleted: editing ? null : widen,
            deleteButtonTooltipMessage: _l10n.searchEverywhere,
          );
        },
      ),
    ];
  }

  /// Searches the whole library for the criteria chosen here (issue #249):
  /// the search view of the root, a step of the history of its own, so that
  /// the browser's back button returns to the search of the album. It sits
  /// on the album, not on the start page: the back arrow leaves the search
  /// to where it was opened from (issue #259).
  void widen() {
    var navigator = widget.albumState.navigator;
    var wide = navigator.delegate.searchSession(const []);
    if (wide.edited != null) {
      wide.endEdit();
    }
    wide.origin = [...path];
    var query = session.addressQuery;
    if (query == null) {
      // Nothing chosen here: nothing chosen there either, whatever the
      // search of the library held before.
      wide.choose(freshSearchCriteria, client, const []);
    }
    navigator.go(SearchRoute(const [], query: query));
  }

  /// How many photographs the search found, once its result is in; nothing
  /// before, and nothing for a result of none, which the results say in a
  /// sentence of their own.
  Widget _count() => FutureBuilder<AlbumInfo>(
        future: session.result,
        builder: (context, snapshot) {
          var count = 0;
          for (var part in snapshot.data?.parts ?? const <AlbumPart>[]) {
            count += part is ImageGroup
                ? part.images.length
                : (part is ImagePart ? 1 : 0);
          }
          if (count == 0) {
            return const SizedBox.shrink();
          }
          var counted = Text(
            _l10n.searchCount(count),
            key: const Key("search-count"),
            style: const TextStyle(color: Colors.white70),
          );
          // An AI search answers the most similar a page at a time: a page
          // that came back full may have more (issue #257).
          if (!session.criteria.ranks || count < session.contentCount) {
            return counted;
          }
          return Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              counted,
              TextButton(
                key: const Key("search-more"),
                onPressed: () => session.more(client, path),
                child: Text(_l10n.searchShowMore),
              ),
            ],
          );
        },
      );

  /// The rows of a kind of several values: the included ones, then the
  /// excluded ones, each with whether it is excluded.
  static Iterable<(T, bool)> _rows<T>(List<T> included, List<T> excluded) => [
        for (var value in included) (value, false),
        for (var value in excluded) (value, true),
      ];

  /// The chip of [value] of a kind of several values (a person, a place, a
  /// label): "Not: …" where [excluded] (issue #242), turned the other way by
  /// a long press or a secondary click, and taken away by its delete icon.
  Widget _valueChip<T>({
    required String key,
    required IconData icon,
    required String name,
    required T value,
    required bool excluded,
    required List<T> included,
    required List<T> excludedValues,
    required void Function(List<T> included, List<T> excluded) choose,
  }) =>
      _excludable(
        onToggle: () => excluded
            ? choose(
                [...included, value],
                [...excludedValues]..remove(value),
              )
            : choose(
                [...included]..remove(value),
                [...excludedValues, value],
              ),
        child: InputChip(
          key: Key(excluded ? "$key-excluded" : key),
          avatar: Icon(icon, size: 18),
          label: Text(excluded ? _l10n.searchExcluded(name) : name),
          onDeleted: () => choose(
            [...included]..remove(value),
            [...excludedValues]..remove(value),
          ),
        ),
      );

  /// The chip of the row of [kind], chosen: "Not: …" where it is excluded
  /// (issue #242), turned the other way by a long press or a secondary click.
  Widget _kindChip({
    required String key,
    required SearchKind kind,
    required IconData icon,
    required String name,
    required VoidCallback onPressed,
    required VoidCallback onDeleted,
  }) {
    var excluded = session.criteria.excludes(kind);
    return _excludable(
      onToggle: () => _choose(session.criteria.toggled(kind)),
      child: InputChip(
        key: Key(excluded ? "$key-excluded" : key),
        avatar: Icon(icon, size: 18),
        label: Text(excluded ? _l10n.searchExcluded(name) : name),
        onPressed: onPressed,
        onDeleted: onDeleted,
      ),
    );
  }

  /// [child], whose exclusion a long press (touch) or a secondary click
  /// (mouse) turns, see issue #242.
  static Widget _excludable({
    required VoidCallback onToggle,
    required Widget child,
  }) =>
      SelectionExcluded(
        child: _excludableTile(onToggle: onToggle, child: child),
      );

  static Widget _excludableTile({
    required VoidCallback onToggle,
    required Widget child,
  }) =>
      GestureDetector(
        onLongPress: onToggle,
        onSecondaryTap: onToggle,
        child: child,
      );

  Widget _dateChip({
    required String key,
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
        key: Key(key),
        avatar: const Icon(Icons.event, size: 18),
        label: Text(label),
        onPressed: pick,
      );
    }
    // Both ends are one row: excluding one excludes the range.
    return _kindChip(
      key: key,
      kind: SearchKind.date,
      icon: Icons.event,
      name: "$label ${dayLabel(value)}",
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

  Future<void> choosePersons() async {
    var chosen = await _chooseMany<String>(
      title: _l10n.searchPersons,
      choices: (options) => {for (var p in options.persons) p.id: p.name},
      chosen: session.criteria.persons,
      excluded: session.criteria.excludedPersons,
    );
    if (chosen != null) {
      _choose(
        session.criteria.copyWith(
          persons: chosen.included,
          excludedPersons: chosen.excluded,
        ),
      );
    }
  }

  Future<void> choosePlaces() async {
    var chosen = await _chooseMany<int>(
      title: _l10n.searchPlaces,
      choices: (options) => {
        for (var p in options.places)
          p.geonameId: p.country.isEmpty ? p.name : "${p.name} (${p.country})",
      },
      chosen: session.criteria.places,
      excluded: session.criteria.excludedPlaces,
    );
    if (chosen != null) {
      _choose(
        session.criteria.copyWith(
          places: chosen.included,
          excludedPlaces: chosen.excluded,
        ),
      );
    }
  }

  Future<void> chooseLabels() async {
    var chosen = await _chooseMany<String>(
      title: _l10n.searchLabels,
      choices: (options) => {for (var l in options.labels) l.name: l.name},
      chosen: session.criteria.labels,
      excluded: session.criteria.excludedLabels,
    );
    if (chosen != null) {
      _choose(
        session.criteria.copyWith(
          labels: chosen.included,
          excludedLabels: chosen.excluded,
        ),
      );
    }
  }

  Future<void> chooseRating() async {
    var names = _ratingNames(_l10n);
    var picked = await showAppDialog<(int, bool)>(
      context: context,
      builder: (context) => _ExcludableChoiceDialog<int>(
        key: const Key("search-rating-dialog"),
        title: _l10n.searchRating,
        excluded: session.criteria.excludes(SearchKind.rating),
        none: (_noRating, _l10n.searchAnyRating),
        choices: [
          for (var entry in names.entries)
            (
              "search-rating-${entry.key}",
              entry.key,
              _l10n.searchRatingAtLeast(entry.value),
            ),
        ],
      ),
    );
    if (picked == null) {
      return;
    }
    var (rating, exclude) = picked;
    _choose(
      rating == _noRating
          ? session.criteria.copyWith(clearMinRating: true)
          : _excluding(
              session.criteria.copyWith(minRating: rating),
              SearchKind.rating,
              exclude,
            ),
    );
  }

  /// [criteria] with the row of [kind] excluded where [exclude] says so.
  static SearchCriteria _excluding(
    SearchCriteria criteria,
    SearchKind kind,
    bool exclude,
  ) =>
      criteria.copyWith(
        excluded: exclude
            ? {...criteria.excluded, kind}
            : ({...criteria.excluded}..remove(kind)),
      );

  static const int _noRating = -99;

  Future<void> chooseCamera() async {
    var title = _l10n.searchCamera;
    var picked = await showAppDialog<(String, bool)>(
      context: context,
      builder: (context) => _AwaitingOptions(
        title: title,
        session: session,
        builder: (options) => _ExcludableChoiceDialog<String>(
          key: const Key("search-camera-dialog"),
          title: title,
          excluded: session.criteria.excludes(SearchKind.camera),
          none: ("", _l10n.searchAnyCamera),
          choices: [
            for (var camera in options.cameras)
              ("search-camera-${camera.name}", camera.name, camera.name),
          ],
        ),
      ),
    );
    if (picked == null) {
      return;
    }
    var (camera, exclude) = picked;
    _choose(
      camera.isEmpty
          ? session.criteria.copyWith(clearCamera: true)
          : _excluding(
              session.criteria.copyWith(camera: camera),
              SearchKind.camera,
              exclude,
            ),
    );
  }

  /// Asks what the photographs found are grouped by (issue #244), and groups
  /// them so.
  Future<void> chooseGrouping() async {
    var picked = await showFormDialog<List<SearchGroup>>(
      context: context,
      builder: (context) =>
          SearchGroupDialog(groupBy: session.criteria.groupBy),
    );
    if (picked == null || !mounted) {
      return;
    }
    _choose(session.criteria.copyWith(groupBy: picked));
  }

  /// Asks which of [choices] (by their value, shown by their name) are
  /// chosen and which of them excluded (issue #242), starting from [chosen]
  /// and [excluded]; `null` where the dialog was left.
  ///
  /// The choices are those of the options of the folder: the dialog waits
  /// for them where they are on their way, and says why where they failed,
  /// see [_AwaitingOptions].
  Future<({List<T> included, List<T> excluded})?> _chooseMany<T>({
    required String title,
    required Map<T, String> Function(SearchOptions options) choices,
    required List<T> chosen,
    required List<T> excluded,
  }) =>
      showAppDialog<({List<T> included, List<T> excluded})>(
        context: context,
        builder: (context) => _AwaitingOptions(
          title: title,
          session: session,
          builder: (options) => _ChooseManyDialog<T>(
            title: title,
            choices: choices(options),
            chosen: chosen,
            excluded: excluded,
          ),
        ),
      );

  // --- The results. ---

  /// What was found below [criteria], which scroll away with it (issue
  /// #248); `null` while the select mode hides the criteria.
  ///
  /// A new result is a new page, which starts at its top: the criteria are
  /// in view again whenever one of them changed.
  Widget results(Widget? criteria) {
    var l10n = _l10n;
    var result = session.result;
    if (result == null) {
      // A location naming what this screen cannot show (issue #243).
      if (session.unreadable != null) {
        return _page(
          criteria,
          _sentence(l10n.searchCannotEdit, const Key("search-cannot-edit")),
        );
      }
      return _page(
        criteria,
        _sentence(l10n.searchChooseHint, const Key("search-hint")),
      );
    }
    return FutureBuilder<AlbumInfo>(
      future: result,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          var error = snapshot.error;
          return _page(
            criteria,
            _sentence(
              error is VAlbumException ? error.message : "$error",
              const Key("search-error"),
            ),
          );
        }
        var album = snapshot.data;
        if (album == null) {
          return _page(
            criteria,
            const Center(child: CircularProgressIndicator()),
          );
        }
        if (album.searchProblem != SearchProblem.none) {
          // "More like this" of a photo that is not there for the reader,
          // or not analysed yet (issue #257).
          return _page(
            criteria,
            _sentence(
              searchProblemText(l10n, album.searchProblem),
              const Key("search-problem"),
            ),
          );
        }
        if (!album.parts.any((part) => part is AbstractImage)) {
          return _page(
            criteria,
            _sentence(l10n.searchNothingFound, const Key("search-empty")),
          );
        }
        return AlbumContent(
          widget.albumState,
          album,
          widget.albumState.baseUrl,
          widget.albumState.pushPart,
          // A new grouping is a new page: the layout, the scroll position
          // and the select mode start afresh (issue #244).
          key: ValueKey((album, album.query?.groupBy.join(","))),
          onSelectModeChanged: _selectModeChanged,
          downloadSelected: downloadFound,
          collectSelected: collectFound,
          header: criteria,
        );
      },
    );
  }

  /// One scrolling page of [criteria] and, below them, [content] filling
  /// what is left of the screen (issue #248).
  static Widget _page(Widget? criteria, Widget content) => CustomScrollView(
        key: const Key("search-page"),
        slivers: [
          if (criteria != null) SliverToBoxAdapter(child: criteria),
          SliverFillRemaining(hasScrollBody: false, child: content),
        ],
      );

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
  // --- The select mode, see issue #243. ---

  /// Downloads the originals of the photographs found under [names] — paths
  /// below the folder searched, as [_foundNames] names them.
  ///
  /// One request to the folder searched: the server takes paths below a
  /// folder and asks each photograph what its own album asks (issue #243), so
  /// a selection of several albums is one archive, named after the search,
  /// whose entries keep their paths. One photograph is its own original, and
  /// a phone saves original by original, as [downloadOriginals] decides.
  Future<void> downloadFound(List<String> names) async {
    var title = await _searchTitle();
    if (!mounted || names.isEmpty) {
      return;
    }
    await runDownload(
      context,
      (progress) => downloadOriginals(
        client,
        path,
        names,
        archiveName: archiveNameOf(title),
        progress: progress,
      ),
    );
  }

  /// The title of the search, as "Save as view…" proposes it.
  Future<String> _searchTitle() async {
    var options =
        await session.options?.load().catchError((_) => SearchOptions());
    if (!mounted) {
      return "";
    }
    return session.criteria.title(
      fallback: _l10n.searchAction,
      not: _l10n.searchWordNot,
      or: _l10n.searchWordOr,
      similarTo: _l10n.searchSimilarChip,
      personNames: {for (var p in options?.persons ?? <Person>[]) p.id: p.name},
      placeNames: {
        for (var p in options?.places ?? <PlaceTag>[]) p.geonameId: p.name,
      },
    );
  }

  /// Adds the photographs found under [names] to a collection, chosen or
  /// created, see issue #221: what [createCollection] does for all of them.
  Future<void> collectFound(List<String> names) => collectWithPicker(
        context: context,
        client: client,
        source: path,
        names: names,
        delegate: widget.albumState.navigator.delegate,
      );

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
    var options =
        await session.options?.load().catchError((_) => SearchOptions());
    // A saved search lies in a folder and looks below it: from the search of
    // an album (issue #249) the picker opens at the folder the album lies in.
    var scope = await widget.albumState.navigator.delegate
        .resourceAt(path)
        .catchError((_) => null);
    if (!mounted) {
      return;
    }
    var picked = await showFormDialog<PickedTarget>(
      context: context,
      builder: (context) => FolderPicker(
        key: const Key("save-as-view-picker"),
        client: client,
        title: l10n.saveAsView,
        initialPath: scope is AlbumInfo ? searchScopeOf(path) : path,
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
      not: l10n.searchWordNot,
      or: l10n.searchWordOr,
      similarTo: l10n.searchSimilarChip,
      personNames: {for (var p in options?.persons ?? <Person>[]) p.id: p.name},
      placeNames: {
        for (var p in options?.places ?? <PlaceTag>[]) p.geonameId: p.name,
      },
    );
    var title = await showAppDialog<String>(
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
    var done = await writeAction(
      context,
      () => client.createAlbum(
        picked.path,
        AlbumInfo(
          kind: AlbumKind.search,
          title: name,
          path: albumFolderName(null, name),
          query: criteria.toQuery(),
        ),
      ),
    );
    if (done == null) {
      return;
    }
    var created = done.value;
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
    if (edited == null || album == null) {
      return;
    }
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
    var done =
        await writeAction(context, () => client.saveAlbum(edited, stored));
    if (done == null) {
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
    await collectFound(names);
  }
}

/// A chooser of the search view while the options of the folder are on their
/// way or failed, see [LoadableBuilder]: it waits with a progress indicator
/// and turns into the chooser [builder] makes once they arrive — a click
/// before they did is not lost — and says the reason of a failure, the
/// server's own words where it spoke, with a retry that asks again.
///
/// Never the chooser of an empty list while there is nothing to tell yet:
/// that said "nothing to choose from" of a library full of persons.
class _AwaitingOptions extends StatelessWidget {
  final String title;

  /// Whose [SearchSession.options] are awaited.
  final SearchSession session;

  /// The chooser of the options that arrived.
  final Widget Function(SearchOptions options) builder;

  const _AwaitingOptions({
    required this.title,
    required this.session,
    required this.builder,
  });

  @override
  Widget build(BuildContext context) {
    return LoadableBuilder<SearchOptions>(
      // Asked by the view when it was shown, see [SearchSession.askOptions].
      loadable: session.options!,
      keyPrefix: "search-options",
      builder: (context, options) => builder(options),
      frame: (context, status) => AppAlertDialog(
        title: Text(title),
        content: status,
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(AppLocalizations.of(context)!.cancel),
          ),
        ],
      ),
    );
  }
}

/// The chooser of several values, see [SearchViewState._chooseMany].
class _ChooseManyDialog<T> extends StatefulWidget {
  final String title;
  final Map<T, String> choices;

  /// The values chosen and included.
  final List<T> chosen;

  /// The values chosen and excluded (issue #242).
  final List<T> excluded;

  const _ChooseManyDialog({
    super.key,
    required this.title,
    required this.choices,
    required this.chosen,
    required this.excluded,
  });

  @override
  State<_ChooseManyDialog<T>> createState() => _ChooseManyDialogState<T>();
}

class _ChooseManyDialogState<T> extends State<_ChooseManyDialog<T>> {
  /// Every value ticked, the excluded ones included.
  late final List<T> _chosen = [...widget.chosen, ...widget.excluded];

  /// The values ticked and excluded, see issue #242.
  late final Set<T> _excluded = {...widget.excluded};

  /// The filter of a long list (issue #243).
  final TextEditingController _filter = TextEditingController();

  /// From how many choices on the list is filtered: a short list is read at
  /// a glance.
  static const int filterFrom = 9;

  @override
  void dispose() {
    _filter.dispose();
    super.dispose();
  }

  bool get _filtered => widget.choices.length >= filterFrom;

  /// The choices shown: those whose name holds the filter, ignoring case — and
  /// every choice ticked, so that nothing chosen disappears from view.
  Iterable<MapEntry<T, String>> get _shown {
    var filter = _filter.text.trim().toLowerCase();
    return widget.choices.entries.where(
      (entry) =>
          filter.isEmpty ||
          _chosen.contains(entry.key) ||
          entry.value.toLowerCase().contains(filter),
    );
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var list = ListView(
      shrinkWrap: true,
      children: [
        for (var entry in _shown)
          CheckboxListTile(
            key: Key("search-choice-${entry.key}"),
            value: _chosen.contains(entry.key),
            title: Text(
              _excluded.contains(entry.key)
                  ? l10n.searchExcluded(entry.value)
                  : entry.value,
            ),
            // Excluding a value ticks it: "without Philipp" is a choice.
            secondary: IconButton(
              key: Key("search-exclude-${entry.key}"),
              isSelected: _excluded.contains(entry.key),
              icon: const Icon(Icons.block),
              selectedIcon: const Icon(Icons.block, color: Colors.redAccent),
              tooltip: l10n.searchExclude,
              onPressed: () => setState(() {
                if (!_excluded.remove(entry.key)) {
                  _excluded.add(entry.key);
                  if (!_chosen.contains(entry.key)) {
                    _chosen.add(entry.key);
                  }
                }
              }),
            ),
            onChanged: (value) => setState(() {
              if (value == true) {
                _chosen.add(entry.key);
              } else {
                _chosen.remove(entry.key);
                _excluded.remove(entry.key);
              }
            }),
          ),
      ],
    );
    return AppAlertDialog(
      key: const Key("search-choose-dialog"),
      title: Text(widget.title),
      content: SizedBox(
        width: 360,
        child: widget.choices.isEmpty
            ? Text(
                l10n.searchNothingToChoose,
                key: const Key("search-nothing-to-choose"),
              )
            : !_filtered
                ? list
                : Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      // The one filter field of every chooser (issue #261):
                      // Escape clears it, a phone's keyboard stays down.
                      FilterField(
                        fieldKey: const Key("search-choose-filter"),
                        controller: _filter,
                        label: l10n.personsSearchLabel,
                        onChanged: (_) => setState(() {}),
                      ),
                      const SizedBox(height: 8),
                      Flexible(child: list),
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
          onPressed: () => Navigator.of(context).pop((
            included: [
              for (var value in _chosen)
                if (!_excluded.contains(value)) value,
            ],
            excluded: [
              for (var value in _chosen)
                if (_excluded.contains(value)) value,
            ],
          )),
          child: Text(l10n.done),
        ),
      ],
    );
  }
}

/// The chooser of one value of the rating or the camera: [none] for no
/// value, one of [choices] (key, value, name), and the switch excluding the
/// value chosen (issue #242). Answers the value and whether it is excluded.
class _ExcludableChoiceDialog<T> extends StatefulWidget {
  final String title;
  final bool excluded;
  final (T, String) none;
  final List<(String, T, String)> choices;

  const _ExcludableChoiceDialog({
    super.key,
    required this.title,
    required this.excluded,
    required this.none,
    required this.choices,
  });

  @override
  State<_ExcludableChoiceDialog<T>> createState() =>
      _ExcludableChoiceDialogState<T>();
}

class _ExcludableChoiceDialogState<T>
    extends State<_ExcludableChoiceDialog<T>> {
  late bool _excluded = widget.excluded;

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var (noValue, noName) = widget.none;
    return AppSimpleDialog(
      title: Text(widget.title),
      children: [
        SwitchListTile(
          key: const Key("search-exclude-switch"),
          value: _excluded,
          title: Text(l10n.searchExclude),
          onChanged: (value) => setState(() => _excluded = value),
        ),
        AppDialogOption(
          onPressed: () => Navigator.of(context).pop((noValue, false)),
          child: Text(noName),
        ),
        // A chooser with nothing to offer says so (no camera recorded here).
        if (widget.choices.isEmpty)
          Padding(
            key: const Key("search-nothing-to-choose"),
            padding: const EdgeInsets.fromLTRB(24, 8, 24, 8),
            child: Text(l10n.searchNothingToChoose),
          ),
        for (var (key, value, name) in widget.choices)
          AppDialogOption(
            key: Key(key),
            onPressed: () => Navigator.of(context).pop((value, _excluded)),
            child: Text(_excluded ? l10n.searchExcluded(name) : name),
          ),
      ],
    );
  }
}

/// The name of [key] in the search view (issue #244).
String searchGroupName(AppLocalizations l10n, SearchGroup key) => switch (key) {
      SearchGroup.album => l10n.searchGroupByAlbum,
      SearchGroup.year => l10n.searchGroupByYear,
      SearchGroup.month => l10n.searchGroupByMonth,
      SearchGroup.country => l10n.groupByKeyCountry,
      SearchGroup.region => l10n.groupByKeyRegion,
      SearchGroup.town => l10n.groupByKeyTown,
      SearchGroup.camera => l10n.searchCamera,
    };

/// The dialog of the search view's "Group by" (issue #244): what makes the
/// sections and, optionally, the subsections — two dropdowns, which read at a
/// phone's width where two lists of eight choices would not. The subsections
/// are never by the key of the sections. Answers the keys chosen, empty for
/// none.
class SearchGroupDialog extends StatefulWidget {
  /// The grouping there is.
  final List<SearchGroup> groupBy;

  const SearchGroupDialog({super.key, required this.groupBy});

  @override
  State<SearchGroupDialog> createState() => _SearchGroupDialogState();
}

class _SearchGroupDialogState extends State<SearchGroupDialog> {
  late SearchGroup? first =
      widget.groupBy.isEmpty ? null : widget.groupBy.first;

  late SearchGroup? second =
      widget.groupBy.length < 2 ? null : widget.groupBy[1];

  List<SearchGroup> get chosen => [
        if (first != null) first!,
        if (first != null && second != null && second != first) second!,
      ];

  Widget keyChoice({
    required String key,
    required String label,
    required SearchGroup? value,
    required List<SearchGroup> offered,
    required ValueChanged<SearchGroup?>? onChanged,
  }) {
    var l10n = AppLocalizations.of(context)!;
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: DropdownButtonFormField<SearchGroup?>(
        // Rebuilt with what it offers: a value it no longer offers is gone.
        key: ValueKey((key, offered.join(","))),
        initialValue: value,
        isExpanded: true,
        decoration: InputDecoration(labelText: label),
        items: [
          DropdownMenuItem<SearchGroup?>(
            key: Key("$key-none"),
            value: null,
            child: Text(l10n.groupByKeyNone),
          ),
          for (var choice in offered)
            DropdownMenuItem<SearchGroup?>(
              key: Key("$key-${choice.name}"),
              value: choice,
              child: Text(searchGroupName(l10n, choice)),
            ),
        ],
        onChanged: onChanged,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return FormDialogFrame(
      key: const Key("search-group-by-dialog"),
      title: Text(l10n.groupByTitle),
      fields: [
        KeyedSubtree(
          key: const Key("search-group-first"),
          child: keyChoice(
            key: "search-group-first",
            label: l10n.groupBySectionKey,
            value: first,
            offered: SearchGroup.values,
            onChanged: (value) => setState(() {
              first = value;
              if (value == null || second == value) {
                second = null;
              }
            }),
          ),
        ),
        KeyedSubtree(
          key: const Key("search-group-second"),
          child: keyChoice(
            key: "search-group-second",
            label: l10n.groupBySubsectionKey,
            value: first == null ? null : second,
            offered: [
              for (var choice in SearchGroup.values)
                if (choice != first) choice,
            ],
            // Subsections need sections.
            onChanged: first == null
                ? null
                : (value) => setState(() => second = value),
          ),
        ),
      ],
      actions: [
        TextButton(
          key: const Key("search-group-cancel"),
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.cancel),
        ),
        ElevatedButton.icon(
          key: const Key("search-group-apply"),
          icon: const Icon(Icons.check),
          label: Text(l10n.apply),
          onPressed: () => Navigator.of(context).pop(chosen),
        ),
      ],
    );
  }
}
