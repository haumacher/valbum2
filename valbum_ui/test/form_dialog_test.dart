/// Form dialogs on a phone with its keyboard open, see issue #178.
///
/// The report: on a phone, "Album erstellen" → fill in → "Erstellen" closed
/// the dialog and created nothing. The keyboard left the dialog less room than
/// it needed, the button had to be scrolled to, the tap that closed the
/// keyboard re-centred the dialog, and the tap landed on the barrier — which
/// threw the form away and answered `null`.
///
/// Every form dialog is pumped here on a 360×640 view with a 300 px keyboard
/// ([phoneWithKeyboard]) through [showFormDialog], the helper every call site
/// uses: its primary button is reachable and answers the value, a tap on the
/// barrier leaves it standing, and Escape still cancels.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:intl/intl.dart';
import 'package:valbum_ui/album_date.dart';
import 'package:valbum_ui/form_dialog.dart';
import 'package:valbum_ui/image_properties.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/manage_view.dart';
import 'package:valbum_ui/resource.dart';

import 'move_test.dart' show recordingClient, treeAnswer;
import 'attribution_test.dart' show json;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// A phone in portrait, its soft keyboard taking the lower 300 px.
void phoneWithKeyboard(WidgetTester tester, {double keyboard = 300}) {
  tester.view.physicalSize = const Size(360, 640);
  tester.view.devicePixelRatio = 1;
  tester.view.viewInsets = FakeViewPadding(bottom: keyboard);
  addTearDown(tester.view.reset);
}

/// What [openForm] hands back: the answer once the dialog is closed.
class Opened {
  Object? answer = const _Pending();
  bool get closed => answer is! _Pending;
}

class _Pending {
  const _Pending();
}

/// Pumps a page with one button, which opens [dialog] through
/// [showFormDialog], and opens it.
Future<Opened> openForm(
  WidgetTester tester,
  WidgetBuilder dialog, {
  Locale locale = defaultTestLocale,
}) async {
  var opened = Opened();
  await tester.pumpWidget(localizedApp(
    Builder(
      builder: (context) => Scaffold(
        body: Center(
          child: TextButton(
            onPressed: () async {
              opened.answer = await showFormDialog<Object>(
                  context: context, builder: dialog);
            },
            child: const Text("open"),
          ),
        ),
      ),
    ),
    locale: locale,
  ));
  await tester.tap(find.text("open"));
  await tester.pumpAndSettle();
  return opened;
}

/// Brings [target] into view the way a reader does and taps it.
Future<void> reachAndTap(WidgetTester tester, Finder target) async {
  await tester.ensureVisible(target);
  await tester.pumpAndSettle();
  // Above the keyboard: a button behind it is one nobody can press.
  var view = tester.view;
  var visibleBottom = view.physicalSize.height / view.devicePixelRatio -
      view.viewInsets.bottom / view.devicePixelRatio;
  expect(tester.getRect(target).bottom, lessThanOrEqualTo(visibleBottom),
      reason: "the button is behind the keyboard");
  await tester.tap(target);
  await tester.pumpAndSettle();
}

/// One form dialog of the table.
class FormCase {
  final String name;
  final WidgetBuilder dialog;

  /// Fills the form in as a reader would, after it opened.
  final Future<void> Function(WidgetTester tester) fill;

  /// The button that completes the form.
  final Finder Function() primary;

  /// Checks the answer; `null` for a dialog that stays open after its
  /// primary action (it shows what the server made) — then [afterPrimary]
  /// says what must have happened.
  final void Function(Object? answer)? expectAnswer;
  final void Function(WidgetTester tester)? afterPrimary;

  /// Whether the form is a dialog opened from within [dialog]: Escape then
  /// closes that one and leaves the outer dialog standing.
  final bool nested;

  const FormCase({
    required this.name,
    required this.dialog,
    required this.primary,
    this.fill = _nothing,
    this.expectAnswer,
    this.afterPrimary,
    this.nested = false,
  });
}

Future<void> _nothing(WidgetTester tester) async {}

Future<void> typeInto(WidgetTester tester, Finder field, String text) async {
  await tester.ensureVisible(field);
  await tester.pumpAndSettle();
  await tester.enterText(field, text);
  await tester.pump();
}

final AppLocalizations en = testL10n;

final Person berta = Person(id: "p1", name: "Berta");

List<FormCase> formCases(List<http.Request> requests) => [
      FormCase(
        name: "CreateAlbumDialog",
        dialog: (_) => const CreateAlbumDialog(),
        fill: (tester) =>
            typeInto(tester, find.byType(TextFormField).first, "Trip"),
        primary: () => find.text(en.create),
        expectAnswer: (answer) => expect((answer as AlbumInfo).title, "Trip"),
      ),
      FormCase(
        name: "CreateFolderDialog",
        dialog: (_) => const CreateFolderDialog(),
        fill: (tester) =>
            typeInto(tester, find.byType(TextFormField).first, "2026"),
        primary: () => find.text(en.create),
        expectAnswer: (answer) => expect((answer as ListingInfo).path, "2026"),
      ),
      FormCase(
        name: "FolderPropertiesDialog",
        dialog: (_) => const FolderPropertiesDialog(
          FolderProperties(title: "Old", placement: Placement.none),
        ),
        fill: (tester) => typeInto(tester, find.byType(TextField).first, "New"),
        primary: () => find.text(en.apply),
        expectAnswer: (answer) =>
            expect((answer as FolderProperties).title, "New"),
      ),
      FormCase(
        name: "AlbumPropertiesDialog",
        dialog: (_) => const AlbumPropertiesDialog(
          AlbumProperties(title: "Old", subTitle: ""),
        ),
        fill: (tester) => typeInto(tester, find.byType(TextField).first, "New"),
        primary: () => find.text(en.apply),
        expectAnswer: (answer) =>
            expect((answer as AlbumProperties).title, "New"),
      ),
      FormCase(
        name: "HeadingDialog",
        dialog: (_) =>
            const HeadingDialog(title: "Heading", text: "", level: 1),
        fill: (tester) => typeInto(tester, find.byType(TextField), "Day 1"),
        primary: () => find.text(en.apply),
        expectAnswer: (answer) =>
            expect((answer as HeadingInput).text, "Day 1"),
      ),
      FormCase(
        name: "ImagePropertiesDialog (TextInputDialog)",
        dialog: (_) => ImagePropertiesDialog(
          ImagePart(
            name: "a.jpg",
            date: DateTime.utc(2026, 3, 1, 12).millisecondsSinceEpoch,
            camera: "Pentax K-3",
            location: GeoLocation(latitude: 48.1, longitude: 8.6),
          ),
        ),
        fill: (tester) =>
            typeInto(tester, find.byType(TextField), "At the lake"),
        primary: () => find.text(en.apply),
        expectAnswer: (answer) => expect(answer, "At the lake"),
      ),
      FormCase(
        name: "AdjustRecordingTimeDialog",
        dialog: (_) {
          var image = ImagePart(
            name: "a.jpg",
            date: DateTime(2026, 3, 1, 12).millisecondsSinceEpoch,
          );
          return AdjustRecordingTimeDialog(reference: image, images: [image]);
        },
        fill: (tester) => typeInto(tester, find.byKey(const Key("adjust-time")),
            "2026-03-01 13:00:00"),
        primary: () => find.text(en.apply),
        expectAnswer: (answer) =>
            expect((answer as ShiftToTime).corrected, DateTime(2026, 3, 1, 13)),
      ),
      FormCase(
        name: "PersonNameDialog",
        dialog: (_) => const PersonNameDialog(title: "New person"),
        fill: (tester) => typeInto(tester, find.byType(TextField), "Berta"),
        primary: () => find.byKey(const Key("persons-name-ok")),
        expectAnswer: (answer) => expect(answer, "Berta"),
      ),
      FormCase(
        name: "PersonChooser",
        dialog: (_) =>
            PersonChooser(people: [berta, Person(id: "p2", name: "Carl")]),
        fill: (tester) => typeInto(tester, find.byType(TextField), "ber"),
        primary: () => find.text("Berta"),
        expectAnswer: (answer) => expect((answer as Person).id, "p1"),
      ),
      FormCase(
        name: "FolderPicker",
        dialog: (_) => FolderPicker(
          client: recordingClient(treeAnswer, requests),
          initialPath: const [],
          targetIsAlbum: false,
          confirmLabel: (path) => "Move here",
        ),
        primary: () => find.text("Move here"),
        expectAnswer: (answer) =>
            expect((answer as PickedTarget).path, isEmpty),
      ),
      FormCase(
        name: "PermissionDialog",
        dialog: (_) => PermissionDialog(
          client: clientReturning('{"users":[]}', requests: requests),
          user: UserEntry(name: "bob", role: "view", clearance: "public"),
        ),
        primary: () => find.byKey(const Key("permission-save")),
        expectAnswer: (answer) => expect(answer, isA<UserList>()),
      ),
      FormCase(
        name: "InviteDialog",
        dialog: (_) => InviteDialog(
          client: clientReturning(
              '["InvitationCreated",{"id":"i1","url":"http://s/i/t/"}]',
              requests: requests),
        ),
        fill: (tester) =>
            typeInto(tester, find.byKey(const Key("invite-recipient")), "Aunt"),
        primary: () => find.byKey(const Key("invite-create")),
        // It stays and shows the address, which is made only once.
        afterPrimary: (tester) =>
            expect(requests.where((r) => r.method == "POST"), isNotEmpty),
      ),
      FormCase(
        name: "ShareLinkDialog",
        dialog: (_) => ShareLinkDialog(
          client: clientReturning('{"links": []}', requests: requests),
          path: const ["Trip"],
        ),
        fill: (tester) async {
          await tester.ensureVisible(find.byKey(const Key("new-link")));
          await tester.pumpAndSettle();
          await tester.tap(find.byKey(const Key("new-link")));
          await tester.pumpAndSettle();
        },
        primary: () => find.text(en.createLink),
        afterPrimary: (tester) =>
            expect(requests.where((r) => r.method == "POST"), isNotEmpty),
      ),
    ];

void main() {
  group('every form dialog on a phone with its keyboard open', () {
    for (var index = 0; index < formCases([]).length; index++) {
      var name = formCases([])[index].name;

      testWidgets('$name: the primary button is reached and answers',
          (tester) async {
        phoneWithKeyboard(tester);
        var requests = <http.Request>[];
        var form = formCases(requests)[index];
        var opened = await openForm(tester, form.dialog);
        await form.fill(tester);

        await reachAndTap(tester, form.primary());

        expect(tester.takeException(), isNull);
        if (form.expectAnswer != null) {
          expect(opened.closed, isTrue, reason: "the dialog did not answer");
          form.expectAnswer!(opened.answer);
        } else {
          expect(opened.closed, isFalse);
          form.afterPrimary!(tester);
        }
      });

      testWidgets('$name: a tap on the barrier does not close it',
          (tester) async {
        phoneWithKeyboard(tester);
        var form = formCases([])[index];
        var opened = await openForm(tester, form.dialog);
        await form.fill(tester);

        await tester.tapAt(const Offset(5, 5));
        await tester.pumpAndSettle();

        expect(opened.closed, isFalse);
        expect(
            find.byType(Dialog).evaluate().isNotEmpty ||
                find.byType(AlertDialog).evaluate().isNotEmpty,
            isTrue);
      });

      testWidgets('$name: Escape cancels', (tester) async {
        phoneWithKeyboard(tester);
        var form = formCases([])[index];
        var opened = await openForm(tester, form.dialog);
        await form.fill(tester);

        await tester.sendKeyEvent(LogicalKeyboardKey.escape);
        await tester.pumpAndSettle();

        if (form.nested) {
          expect(opened.closed, isFalse);
          expect(find.byType(TextField), findsNothing,
              reason: "the inner form is still open");
          return;
        }
        expect(opened.closed, isTrue);
        expect(opened.answer, isNull);
      });
    }
  });

  group('the create dialogs in German', () {
    const german = Locale("de");
    final de = l10nOf(german);

    testWidgets('"Album erstellen" is reached above the keyboard and answers',
        (tester) async {
      phoneWithKeyboard(tester);
      var opened = await openForm(tester, (_) => const CreateAlbumDialog(),
          locale: german);
      await typeInto(tester, find.byType(TextFormField).first, "Urlaub");

      // The button row fits the dialog's width in German too.
      expect(tester.takeException(), isNull);
      await reachAndTap(tester, find.text(de.create));

      expect((opened.answer as AlbumInfo).path, "Urlaub");
    });

    testWidgets('"Ordner erstellen" is reached above the keyboard and answers',
        (tester) async {
      phoneWithKeyboard(tester);
      var opened = await openForm(tester, (_) => const CreateFolderDialog(),
          locale: german);
      await typeInto(tester, find.byType(TextFormField).first, "Reisen");

      await reachAndTap(tester, find.text(de.create));

      expect((opened.answer as ListingInfo).path, "Reisen");
    });

    testWidgets('a tap beside either leaves it standing', (tester) async {
      phoneWithKeyboard(tester);
      for (var dialog in <WidgetBuilder>[
        (_) => const CreateAlbumDialog(),
        (_) => const CreateFolderDialog(),
      ]) {
        var opened = await openForm(tester, dialog, locale: german);
        await typeInto(tester, find.byType(TextFormField).first, "Urlaub");
        await tester.tapAt(const Offset(5, 5));
        await tester.pumpAndSettle();
        expect(opened.closed, isFalse);
        expect(find.text("Urlaub"), findsOneWidget);
      }
    });
  });

  group('the date of a new album', () {
    DateTime today() {
      var now = DateTime.now();
      return DateTime(now.year, now.month, now.day);
    }

    Future<AlbumInfo> createWithPickedDay(WidgetTester tester, DateTime day,
        {required bool typed}) async {
      phoneWithKeyboard(tester, keyboard: 0);
      tester.view.physicalSize = const Size(360, 800);
      var opened = await openForm(tester, (_) => const CreateAlbumDialog());
      await tester.tap(find.byIcon(Icons.date_range));
      await tester.pumpAndSettle();
      var material = MaterialLocalizations.of(
          tester.element(find.byType(DatePickerDialog)));
      if (typed) {
        // The pencil of the Material picker: the date typed, as a phone
        // reader may.
        await tester.tap(find.byIcon(Icons.edit_outlined));
        await tester.pumpAndSettle();
        await tester.enterText(
            find.byType(TextField).last, material.formatCompactDate(day));
        await tester.pump();
      } else {
        if (day.month != today().month || day.year != today().year) {
          await tester.tap(find.byIcon(Icons.chevron_right));
          await tester.pumpAndSettle();
        }
        await tester.tap(find.text("${day.day}").last);
        await tester.pumpAndSettle();
      }
      await tester.tap(find.text(material.okButtonLabel));
      await tester.pumpAndSettle();
      // Nothing refused: the picker is gone and the day stands in the field.
      expect(find.byType(DatePickerDialog), findsNothing);
      expect(find.text(DateFormat("yyyy-MM-dd").format(day)), findsOneWidget);

      await typeInto(tester, find.byType(TextFormField).first, "Trip");
      await reachAndTap(tester, find.text(en.create));
      expect(opened.closed, isTrue);
      return opened.answer as AlbumInfo;
    }

    for (var typed in [false, true]) {
      var how = typed ? "typed" : "picked in the calendar";

      testWidgets('today, $how, is taken', (tester) async {
        var album = await createWithPickedDay(tester, today(), typed: typed);
        expect(album.date, today().millisecondsSinceEpoch);
        expect(album.path, "${DateFormat("yyyy-MM-dd").format(today())} Trip");
      });

      testWidgets('a day next week, $how, is taken', (tester) async {
        // No upper bound: an album for the holiday that starts next week may
        // be made today (the author's decision on #178).
        var day = today().add(const Duration(days: 7));
        day = DateTime(day.year, day.month, day.day);
        var album = await createWithPickedDay(tester, day, typed: typed);
        expect(album.date, day.millisecondsSinceEpoch);
      });
    }

    test('the dialogs share one far bound', () {
      expect(lastAlbumDate, DateTime(2100));
      expect(firstAlbumDate, DateTime(1900));
    });

    testWidgets('the move picker creates an album for next week',
        (tester) async {
      phoneWithKeyboard(tester, keyboard: 0);
      tester.view.physicalSize = const Size(360, 800);
      var next = today().add(const Duration(days: 7));
      next = DateTime(next.year, next.month, next.day);
      var opened = await openForm(
        tester,
        (_) => FolderPicker(
          client: recordingClient(treeAnswer, []),
          initialPath: const [],
          confirmLabel: (path) => "Move here",
          mayCreateAlbum: true,
          newAlbumDate: next,
        ),
      );
      await tester.tap(find.byKey(const Key("picker-create-album")));
      await tester.pumpAndSettle();
      expect(find.text(DateFormat("yyyy-MM-dd").format(next)), findsOneWidget);
      await tester.enterText(find.byType(TextFormField).first, "Holiday");
      await reachAndTap(tester, find.text(en.create));

      var picked = opened.answer as PickedTarget;
      expect(picked.newAlbum!.date, next.millisecondsSinceEpoch);
      expect(picked.newAlbum!.path,
          "${DateFormat("yyyy-MM-dd").format(next)} Holiday");
    });
  });

  group('the album properties with the keyboard open', () {
    testWidgets('lets the crop editor step aside, and brings it back',
        (tester) async {
      phoneWithKeyboard(tester);
      await withFakeImageHttp(() async {
        var opened = await openForm(
          tester,
          (_) => AlbumPropertiesDialog(
            AlbumProperties(
              title: "Trip",
              subTitle: "",
              indexPicture: ThumbnailInfo(image: "a.jpg", scale: 1),
            ),
            client: clientReturning("{}"),
            baseUrl: "http://server/valbum/data/Trip",
          ),
        );
        expect(find.byKey(const Key("index-picture-editor")), findsNothing);
        expect(tester.takeException(), isNull);

        tester.view.viewInsets = FakeViewPadding.zero;
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        expect(find.byKey(const Key("index-picture-editor")), findsOneWidget);
        // Outside every scroll view: its pan is its own (issue #136).
        expect(
          find.ancestor(
            of: find.byKey(const Key("index-picture-editor")),
            matching: find.byType(Scrollable),
          ),
          findsNothing,
        );

        await tester.tap(find.text(en.apply));
        await tester.pumpAndSettle();
        expect((opened.answer as AlbumProperties).title, "Trip");
      });
    });
  });

  group('the listing on a phone', () {
    testWidgets('creates the album the reader filled in, in German',
        (tester) async {
      phoneWithKeyboard(tester);
      tester.platformDispatcher.localesTestValue = const [Locale("de")];
      addTearDown(tester.platformDispatcher.clearLocalesTestValue);
      final de = l10nOf(const Locale("de"));
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
          client: recordingClient(
            (request) => request.method == "PUT"
                ? json('["CreateResult",{"path":"Urlaub"}]')
                : treeAnswer(request),
            requests,
          ),
        ));
        await tester.pumpAndSettle();
      });

      await tester.tap(find.byIcon(Icons.more_vert).last);
      await tester.pumpAndSettle();
      await tester.tap(find.text(de.createAlbum));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextFormField).first, "Urlaub");
      await tester.pump();

      // A tap beside the dialog, where the re-centred dialog used to leave
      // the finger: the form is still there.
      await tester.tapAt(const Offset(5, 5));
      await tester.pumpAndSettle();
      expect(find.text("Urlaub"), findsOneWidget);

      await reachAndTap(tester, find.text(de.create));

      var put = requests.where((r) => r.method == "PUT").toList();
      expect(put, hasLength(1));
      expect(put.single.url.path, "/valbum/data/Urlaub/");
    });
  });
}
