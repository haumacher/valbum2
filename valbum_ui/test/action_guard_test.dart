/// The one answer to "what does the app offer here" and the one way a view
/// writes, see `action_guard.dart` (issue #293).
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/action_guard.dart';
import 'package:valbum_ui/caller.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/loadable.dart';
import 'package:valbum_ui/offline.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/rights.dart';
import 'package:valbum_ui/share_session.dart';
import 'package:valbum_ui/urls.dart';

import 'util/l10n.dart';

/// A folder carrying the given rights.
AlbumInfo folderWith(List<String> rights) => AlbumInfo(
      title: "Zoo",
      rights: [for (var name in rights) RightName(name: name)],
    );

/// A link session allowing [rights].
ShareSession linkSession(List<String> rights) => ShareSession(
      url: const SessionUrl(
        kind: SessionKind.share,
        token: "t0ken",
        dataUrl: "http://server/valbum/data",
        basePath: "/valbum/s/t0ken/",
      ),
      info: ShareInfo(
        label: "For grandma",
        rights: [for (var name in rights) RightName(name: name)],
        path: "Zoo",
      ),
      writeAllowed: false,
    );

/// A caller of the given Phase 6 role.
CallerInfo callerOf(String role) =>
    CallerInfo(userName: "carol", role: role, space: "carol");

/// The context of a widget pumped under the given caller, link and offline
/// state, with a messenger to speak through.
Future<BuildContext> pumpScopes(
  WidgetTester tester, {
  CallerInfo? caller,
  ShareSession? share,
  bool offline = false,
}) async {
  late BuildContext found;
  await tester.pumpWidget(
    CallerScope(
      caller: caller,
      child: ShareSessionScope(
        session: share,
        child: OfflineScope(
          state: offline ? (OfflineState()..goneOffline(null)) : OfflineState(),
          cache: MemoryOfflineCache(),
          child: MaterialApp(
            localizationsDelegates: testLocalizationsDelegates,
            supportedLocales: testSupportedLocales,
            home: Scaffold(
              body: Builder(builder: (context) {
                found = context;
                return const SizedBox();
              }),
            ),
          ),
        ),
      ),
    ),
  );
  return found;
}

void main() {
  group("rightsOf", () {
    testWidgets("the server's rights decide where it answered them",
        (tester) async {
      var context = await pumpScopes(tester, caller: callerOf(roleEdit));
      var rights = rightsOf(context, folderWith(const ["view", "download"]));
      expect(rights.mayDownload, isTrue);
      // The role would allow it; the folder's answer does not.
      expect(rights.mayEdit, isFalse);
      expect(rights.mayContribute, isFalse);
    });

    testWidgets("the role decides where the server answered none",
        (tester) async {
      var context = await pumpScopes(tester, caller: callerOf(roleContribute));
      var rights = rightsOf(context, folderWith(const []));
      expect(rights.mayContribute, isTrue);
      expect(rights.mayDownload, isTrue);
      expect(rights.mayEdit, isFalse);

      context = await pumpScopes(tester, caller: callerOf(roleView));
      rights = rightsOf(context, null);
      expect(rights.mayView, isTrue);
      expect(rights.mayDownload, isFalse);
      expect(rights.mayEdit, isFalse);

      context = await pumpScopes(tester, caller: callerOf(roleEdit));
      expect(rightsOf(context, null).mayEdit, isTrue);
    });

    testWidgets("nothing is hidden where nobody said anything", (tester) async {
      var context = await pumpScopes(tester);
      expect(rightsOf(context, null).complete, isTrue);
      expect(rightsOf(context, folderWith(const [])).complete, isTrue);
    });

    testWidgets("a share link edits nothing, whatever it carries",
        (tester) async {
      var context = await pumpScopes(
        tester,
        share: linkSession(const ["edit"]),
      );
      var rights = rightsOf(context, folderWith(const ["edit"]));
      expect(rights.mayEdit, isFalse);
      // What the link allows besides stays.
      expect(rights.mayContribute, isTrue);
      expect(rights.mayDownload, isTrue);

      // A folder answered without rights in a link session.
      expect(rightsOf(context, null).mayEdit, isFalse);
    });

    testWidgets("a share link keeps what it allows", (tester) async {
      var context = await pumpScopes(
        tester,
        share: linkSession(const ["view", "download"]),
      );
      var rights = rightsOf(context, folderWith(const ["view", "download"]));
      expect(rights.mayDownload, isTrue);
      expect(rights.mayContribute, isFalse);
    });

    testWidgets("a preview acts on nothing", (tester) async {
      var context = await pumpScopes(tester, caller: callerOf(roleAdmin));
      var folder = folderWith(const ["edit"]);
      expect(rightsOf(context, folder).complete, isTrue);
      var previewed = rightsOf(context, folder, previewing: true);
      expect(previewed.mayView, isTrue);
      expect(previewed.mayDownload, isFalse);
      expect(previewed.mayContribute, isFalse);
      expect(previewed.mayEdit, isFalse);
    });

    testWidgets("asked without a dependency it answers the same",
        (tester) async {
      var context = await pumpScopes(
        tester,
        caller: callerOf(roleView),
        share: linkSession(const ["view"]),
      );
      expect(
        rightsOf(context, null, listen: false).names,
        rightsOf(context, null).names,
      );
    });
  });

  group("sessionRights", () {
    test("leaves an ordinary session alone", () {
      expect(identical(sessionRights(Rights.everything), Rights.everything),
          isTrue);
    });

    test("takes edit away in a link and everything but view in a preview", () {
      expect(sessionRights(Rights.everything, shareLink: true).names,
          {rightView, rightDownload, rightContribute});
      expect(sessionRights(Rights.everything, previewing: true).names,
          {rightView});
    });
  });

  group("writeAction", () {
    testWidgets("offline it refuses, says so and sends nothing",
        (tester) async {
      var context = await pumpScopes(tester, offline: true);
      var sent = 0;
      var refused = 0;
      Written<int>? answer;
      await tester.runAsync(() async {
        answer = await writeAction(
          context,
          () async => ++sent,
          onRefused: (_) => refused++,
        );
      });
      await tester.pumpAndSettle();
      expect(answer, isNull);
      expect(sent, 0);
      // Offline is no refusal of the server's: nothing to take back.
      expect(refused, 0);
      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
    });

    testWidgets("answers what the server answered", (tester) async {
      var context = await pumpScopes(tester);
      Written<String?>? answer;
      await tester.runAsync(() async {
        answer = await writeAction<String?>(context, () async => null);
      });
      // A write answering nothing is told from one that was not done.
      expect(answer, isNotNull);
      expect(answer!.value, isNull);
    });

    testWidgets("a refusal is said in the server's words and told",
        (tester) async {
      var context = await pumpScopes(tester);
      Object? told;
      Written<void>? answer;
      await tester.runAsync(() async {
        answer = await writeAction<void>(
          context,
          () async => throw VAlbumException("Needs the edit right."),
          onRefused: (error) => told = error,
          refusalKey: const Key("the-refusal"),
        );
      });
      await tester.pumpAndSettle();
      expect(answer, isNull);
      expect(told, isA<VAlbumException>());
      expect(find.text("Needs the edit right."), findsOneWidget);
      expect(find.byKey(const Key("the-refusal")), findsOneWidget);
    });

    testWidgets("a refusal is said in the view's own sentence, or not at all",
        (tester) async {
      var context = await pumpScopes(tester);
      await tester.runAsync(() async {
        await writeAction<void>(
          context,
          () async => throw VAlbumException("Gone."),
          refusal: (error) => "Kept: ${reasonOf(error)}",
        );
      });
      await tester.pumpAndSettle();
      expect(find.text("Kept: Gone."), findsOneWidget);

      context = await pumpScopes(tester);
      await tester.runAsync(() async {
        await writeAction<void>(
          context,
          () async => throw VAlbumException("Said elsewhere."),
          speaks: false,
        );
      });
      await tester.pumpAndSettle();
      expect(find.text("Said elsewhere."), findsNothing);
    });
  });
}
