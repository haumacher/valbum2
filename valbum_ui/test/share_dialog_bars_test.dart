/// The share-link and invitation dialogs: one button bar, set from the state
/// (issue #206), and the compact form of a new link with its defaults
/// (issue #205).
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:intl/intl.dart';
import 'package:valbum_ui/caller.dart';
import 'package:valbum_ui/invitation.dart';
import 'package:valbum_ui/share_view.dart';

import 'form_dialog_test.dart' show Opened, openForm, phoneWithKeyboard;
import 'share_link_test.dart' show bodyOf, ownerAnswers, ownerClient, tapKey;
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The keys of the buttons in every button bar the open dialog shows.
///
/// A bar is an [OverflowBar] holding buttons — the dialog's `actions:` is
/// one; a row of buttons inside the content would be a second.
List<List<String>> buttonBars(WidgetTester tester) => [
      for (var bar in find
          .descendant(
            of: find.byType(AlertDialog),
            matching: find.byType(OverflowBar),
          )
          .evaluate())
        [
          for (var button in find
              .descendant(
                of: find.byWidget(bar.widget),
                matching: find.byWidgetPredicate((w) => w is ButtonStyleButton),
              )
              .evaluate())
            "${(button.widget.key as ValueKey<String>?)?.value}",
        ],
    ].where((bar) => bar.isNotEmpty).toList();

/// Opens the share-link dialog on `2024/Zoo` as a form dialog.
Future<void> openShareDialog(
  WidgetTester tester, {
  List<http.Request>? requests,
  bool mayShowMembers = true,
}) async {
  await openForm(
    tester,
    (_) => ShareLinkDialog(
      client: ownerClient(ownerAnswers, requests: requests),
      path: const ["2024", "Zoo"],
      mayShowMembers: mayShowMembers,
    ),
  );
}

/// The text a dropdown of the form shows as its value.
String shownValue(WidgetTester tester, String key) {
  var button = tester.widget<DropdownButton<Object>>(find.byKey(Key(key)));
  var item = button.items!.singleWhere((item) => item.value == button.value);
  return (item.child as Text).data!;
}

void main() {
  group('the share-link dialog has one button bar (issue #206)', () {
    testWidgets('the list: Close alone', (tester) async {
      await openShareDialog(tester);
      expect(buttonBars(tester), [
        ["share-link-close"],
      ]);
    });

    testWidgets('the form: Cancel and Create, no Close', (tester) async {
      await openShareDialog(tester);
      await tapKey(tester, "new-link");
      expect(buttonBars(tester), [
        ["link-cancel", "link-create"],
      ]);
      expect(find.byKey(const Key("share-link-close")), findsNothing);

      await tapKey(tester, "link-cancel");
      expect(buttonBars(tester), [
        ["share-link-close"],
      ]);
    });

    testWidgets('the result: Done alone, back to the list', (tester) async {
      await openShareDialog(tester);
      await tapKey(tester, "new-link");
      await tapKey(tester, "link-create");
      expect(find.byKey(const Key("share-link-url")), findsOneWidget);
      expect(buttonBars(tester), [
        ["share-link-done"],
      ]);

      await tapKey(tester, "share-link-done");
      expect(find.byKey(const Key("share-link-url")), findsNothing);
      expect(find.byKey(const Key("share-link-dialog")), findsOneWidget);
      expect(buttonBars(tester), [
        ["share-link-close"],
      ]);
    });
  });

  group('the invitation dialog has one button bar (issue #206)', () {
    Future<Opened> openInvite(WidgetTester tester) => openForm(
          tester,
          (_) => InviteDialog(
            client:
                clientReturning('{"token": "tok", "url": "/valbum/i/tok/"}'),
          ),
        );

    testWidgets('the form: Cancel and Create, no Close', (tester) async {
      var opened = await openInvite(tester);
      expect(buttonBars(tester), [
        ["invite-cancel", "invite-create"],
      ]);
      await tapKey(tester, "invite-cancel");
      expect(opened.closed, isTrue);
    });

    testWidgets('the result: Done alone, which closes', (tester) async {
      var opened = await openInvite(tester);
      await tapKey(tester, "invite-create");
      expect(find.byKey(const Key("invite-url")), findsOneWidget);
      expect(buttonBars(tester), [
        ["invite-done"],
      ]);
      await tapKey(tester, "invite-done");
      expect(opened.closed, isTrue);
    });
  });

  group('the form of a new link (issue #205)', () {
    testWidgets('opens with Never, All photos, every photo and View only',
        (tester) async {
      await openShareDialog(tester);
      await tapKey(tester, "new-link");

      expect(shownValue(tester, "link-expiry"), testL10n.expiryNever);
      expect(shownValue(tester, "link-shows"), testL10n.privacyUpToMembers);
      expect(shownValue(tester, "link-rating"), testL10n.linkRatingAllButTrash);
      for (var right in const ["download", "contribute"]) {
        expect(
            tester
                .widget<FilterChip>(find.byKey(Key("link-right-$right")))
                .selected,
            isFalse);
      }
      // No row per choice any more.
      for (var gone in const [
        "expiry-never", "expiry-week", "privacy-public", "privacy-members",
        "rating--2", "rating-0", "link-right-view", //
      ]) {
        expect(find.byKey(Key(gone)), findsNothing, reason: gone);
      }
    });

    testWidgets('created unchanged, it sends the defaults', (tester) async {
      var requests = <http.Request>[];
      await openShareDialog(tester, requests: requests);
      await tapKey(tester, "new-link");
      await tapKey(tester, "link-create");

      var body = bodyOf(requests, "share");
      expect(body, contains('"maxPrivacy":1'));
      expect(body, contains('"minRating":-2'));
      expect(body, isNot(contains('"expires":"2')));
      expect(RegExp('"name":').allMatches(body).length, 1);
      expect(body, contains('"name":"view"'));
    });

    testWidgets('a creator who sees public photos only is not offered more',
        (tester) async {
      var requests = <http.Request>[];
      await openShareDialog(tester, requests: requests, mayShowMembers: false);
      await tapKey(tester, "new-link");

      expect(shownValue(tester, "link-shows"), testL10n.privacyPublicOnly);
      await tester.tap(find.byKey(const Key("link-shows")));
      await tester.pumpAndSettle();
      expect(find.text(testL10n.privacyUpToMembers), findsNothing);

      await tapKey(tester, "link-create");
      expect(bodyOf(requests, "share"), contains('"maxPrivacy":0'));
    });

    testWidgets('the dialog asks the caller\'s clearance where it opens',
        (tester) async {
      for (var (clearance, offered) in const [
        (clearancePublic, false),
        (clearanceNonPrivate, true),
      ]) {
        await tester.pumpWidget(localizedApp(CallerScope(
          caller:
              CallerInfo(userName: "alice", role: "edit", clearance: clearance),
          child: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () => shareLinksOf(
                  context: context,
                  client: ownerClient(ownerAnswers),
                  path: const ["2024", "Zoo"],
                ),
                child: const Text("share"),
              ),
            ),
          ),
        )));
        await tester.tap(find.text("share"));
        await tester.pumpAndSettle();
        await tapKey(tester, "new-link");
        expect(
          shownValue(tester, "link-shows"),
          offered ? testL10n.privacyUpToMembers : testL10n.privacyPublicOnly,
          reason: clearance,
        );
        await tapKey(tester, "link-cancel");
        await tapKey(tester, "share-link-close");
      }
    });

    testWidgets('"On a date…" opens the picker and shows the day it chose',
        (tester) async {
      var requests = <http.Request>[];
      await openShareDialog(tester, requests: requests);
      await tapKey(tester, "new-link");

      // Cancelled: the answer that stood before stays.
      await tapKey(tester, "link-expiry");
      await tester.tap(find.text(testL10n.expiryPickDate).last);
      await tester.pumpAndSettle();
      expect(find.byType(DatePickerDialog), findsOneWidget);
      var material = MaterialLocalizations.of(
          tester.element(find.byType(DatePickerDialog)));
      await tester.tap(find.descendant(
          of: find.byType(DatePickerDialog),
          matching: find.text(material.cancelButtonLabel)));
      await tester.pumpAndSettle();
      expect(shownValue(tester, "link-expiry"), testL10n.expiryNever);

      // Chosen: the day is the value.
      await tapKey(tester, "link-expiry");
      await tester.tap(find.text(testL10n.expiryPickDate).last);
      await tester.pumpAndSettle();
      await tester.tap(find.text(material.okButtonLabel));
      await tester.pumpAndSettle();
      var now = DateTime.now();
      var day =
          DateTime(now.year, now.month, now.day).add(const Duration(days: 7));
      expect(shownValue(tester, "link-expiry"), DateFormat.yMMMd().format(day));

      await tapKey(tester, "link-create");
      var expires = RegExp('"expires":"([^"]*)"')
          .firstMatch(bodyOf(requests, "share"))!
          .group(1)!;
      expect(DateTime.parse(expires), day.toUtc());
    });
  });

  group('on a phone (issue #205)', () {
    void phone(WidgetTester tester, {double keyboard = 300}) {
      phoneWithKeyboard(tester, keyboard: keyboard);
      tester.view.physicalSize = const Size(390, 844);
    }

    testWidgets('Create is reached without scrolling, the keyboard open',
        (tester) async {
      phone(tester);
      var requests = <http.Request>[];
      await openShareDialog(tester, requests: requests);
      // The list may scroll; the form is what has to fit.
      await tapKey(tester, "new-link");
      expect(tester.takeException(), isNull);

      var create = tester.getRect(find.byKey(const Key("link-create")));
      expect(create.bottom, lessThanOrEqualTo(844 - 300));
      expect(create.top, greaterThanOrEqualTo(0));
      // Tapped where it stands, nothing scrolled.
      await tester.tapAt(create.center);
      await tester.pumpAndSettle();
      expect(bodyOf(requests, "share"), contains('"maxPrivacy":1'));
    });

    testWidgets('the default form fits without scrolling', (tester) async {
      phone(tester, keyboard: 0);
      await openShareDialog(tester);
      await tapKey(tester, "new-link");
      // Takes the focus, but the keyboard is not drawn here.
      expect(tester.takeException(), isNull);

      var scrollable = tester.state<ScrollableState>(find
          .descendant(
              of: find.byType(AlertDialog), matching: find.byType(Scrollable))
          .first);
      expect(scrollable.position.maxScrollExtent, 0);
      expect(tester.getRect(find.byKey(const Key("link-create"))).bottom,
          lessThanOrEqualTo(844));
    });
  });
}
