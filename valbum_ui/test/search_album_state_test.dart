/// The search of an album keeps out of the album's state, issue #265.
///
/// Since #249 the search of an album stands on the album's own path. What
/// the router keeps per album — the guard asking about unsaved changes
/// (#99), the edit session with its selection, the scroll offset — must
/// still be the album's alone: the results of its search neither take it
/// over, nor read it, nor take it away when the search closes.
library;

import 'dart:convert';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'search_layout_test.dart' show resultsScrollable;
import 'search_leave_test.dart'
    show
        LongSpace,
        albumPosition,
        browserTo,
        leave,
        openSearch,
        widen,
        zoo,
        zooSearch;
import 'search_view_test.dart' show chooseAnne, pathOf, pumpRoute, settle;
import 'util/edit_drag.dart';

Finder get leaveDialog => find.byKey(const Key("leave-dialog"));

/// The view of the album `2024/Zoo`, whether on top or beneath the search.
AlbumContentState zooStateOf(WidgetTester tester) =>
    tester.state<AlbumContentState>(find
        .byWidgetPredicate((widget) =>
            widget is AlbumContent && widget.albumState.route == zoo)
        .first);

/// The results of the search view.
AlbumContentState resultsStateOf(WidgetTester tester) =>
    tester.state<AlbumContentState>(find
        .byWidgetPredicate((widget) =>
            widget is AlbumContent && widget.albumState.route is SearchRoute)
        .first);

/// Enters the edit mode of `2024/Zoo` with `a0.jpg` selected, and makes an
/// unsaved change to the album.
Future<void> editZoo(WidgetTester tester) async {
  await tester.longPress(tile("a0.jpg"));
  await settle(tester);
  var album = zooStateOf(tester);
  expect(album.editMode, isTrue);
  await dragBehind(tester, "a1.jpg", "a2.jpg");
  expect(album.dirty, isTrue, reason: "The album carries an unsaved change.");
}

void main() {
  testWidgets('a search opened and closed leaves the album its guard',
      (tester) async {
    var space = LongSpace();
    var delegate = await pumpRoute(tester, space, zoo);
    await openSearch(tester);
    await chooseAnne(tester);
    await leave(tester);
    expect(delegate.route, zoo);

    await editZoo(tester);
    delegate.goUp();
    await settle(tester);

    expect(leaveDialog, findsOneWidget,
        reason: "Leaving asks about the unsaved change.");
    expect(delegate.route, zoo);
  });

  testWidgets('leaving through the search asks the album, which saves itself',
      (tester) async {
    var space = LongSpace();
    var delegate = await pumpRoute(tester, space, zoo);
    await editZoo(tester);
    await browserTo(tester, zooSearch);
    expect(delegate.route, const SearchRoute(["2024", "Zoo"]));

    delegate.go(ListingOrAlbumRoute.root);
    await settle(tester);
    expect(leaveDialog, findsOneWidget);
    await tester.tap(find.byKey(const Key("save-and-leave")));
    await settle(tester);

    var puts = [
      for (var request in space.requests)
        if (request.method == "PUT") request
    ];
    expect(puts, hasLength(1));
    expect(pathOf(puts.single), "/valbum/data/2024/Zoo/");
    var written = jsonEncode(jsonDecode(puts.single.body));
    expect(written, contains("a0.jpg"),
        reason: "The album itself is written back.");
    expect(written, isNot(contains("x0.jpg")),
        reason: "Never what its search found.");
  });

  testWidgets('a search opened over a dirty album leaves it its guard',
      (tester) async {
    var space = LongSpace();
    var delegate = await pumpRoute(tester, space, zoo);
    await editZoo(tester);
    await browserTo(tester, zooSearch);
    await leave(tester);
    expect(delegate.route, zoo);

    delegate.goUp();
    await settle(tester);

    expect(leaveDialog, findsOneWidget);
  });

  testWidgets('widening the search does not leave the album beneath it',
      (tester) async {
    var space = LongSpace();
    var delegate = await pumpRoute(tester, space, zoo);
    await editZoo(tester);
    await browserTo(tester, zooSearch);
    await chooseAnne(tester);

    await widen(tester);
    expect(leaveDialog, findsNothing,
        reason: "The album stays beneath the search of the library.");
    expect(delegate.route, const SearchRoute([]));

    delegate.go(ListingOrAlbumRoute.root);
    await settle(tester);
    expect(leaveDialog, findsOneWidget,
        reason: "Going home takes the album away.");
  });

  testWidgets('the selections of the album and its search are their own',
      (tester) async {
    var space = LongSpace();
    var delegate = await pumpRoute(tester, space, zoo);
    await editZoo(tester);
    var album = zooStateOf(tester);
    var selected = {...album.selection};
    expect(selected, isNotEmpty);

    await browserTo(tester, zooSearch);
    var results = resultsStateOf(tester);
    expect(results.editMode, isFalse,
        reason: "The search is not the album's edit.");
    expect(results.selection, isEmpty);
    await tester.longPress(tile("2020 Trip/x0.jpg"));
    await settle(tester);
    expect(results.selectMode, isTrue);
    expect(results.selection, hasLength(1));
    expect(album.selection, selected,
        reason: "Selecting a result selects nothing of the album.");
    await tester.tap(find.byKey(const Key("select-mode-leave")));
    await settle(tester);
    expect(album.selection, selected,
        reason: "Leaving the select mode clears the search's selection alone.");

    await leave(tester);
    expect(delegate.route, zoo);
    expect(zooStateOf(tester).selection, selected);
    expect(zooStateOf(tester).editMode, isTrue);
  });

  testWidgets('the search scrolls without moving the album\'s offset',
      (tester) async {
    var space = LongSpace();
    var delegate = await pumpRoute(tester, space, zoo);
    await tester.drag(find.byType(Scrollable).first, const Offset(0, -600));
    await settle(tester);
    var offset = albumPosition(tester).pixels;
    expect(offset, greaterThan(0));
    expect(delegate.scrollOffset(zoo.albumPath), offset);

    await openSearch(tester);
    await chooseAnne(tester);
    var searchPosition = tester.state<ScrollableState>(resultsScrollable);
    expect(searchPosition.position.pixels, 0,
        reason: "The search starts at its top.");
    await tester.drag(resultsScrollable, const Offset(0, -1500));
    await settle(tester);

    expect(delegate.scrollOffset(zoo.albumPath), offset);
    await leave(tester);
    expect(albumPosition(tester).pixels, offset);
  });
}
