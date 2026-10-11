/// Review probe of "Sign out" in the main menu composed with what lies
/// behind it: a nested listing, and the settings screen the sign-out used to
/// be reached from only.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'menu_sign_out_test.dart'
    show Harness, expectSignedOut, oneDevice, tapMenuSignOut;
import 'util/fake_image_http.dart';
import 'util/l10n.dart';

void main() {
  testWidgets(
      'signing out from a nested listing lands on the root, and the settings '
      'screen then has nothing to sign out', (tester) async {
    await withFakeImageHttp(() async {
      var h = Harness();
      await h.pump(tester, devices: oneDevice, route: const ["2021"]);
      expect(find.text("Summer"), findsOneWidget, reason: "inside 2021");
      var before = h.rootGets.length;

      await tapMenuSignOut(tester);
      await tester.tap(find.byKey(const Key("sign-out-confirmed")));
      await tester.pumpAndSettle();

      expectSignedOut(h, before);
      expect(find.text("Summer"), findsNothing, reason: "the root is shown");
      expect(find.text("2021"), findsOneWidget);

      // The settings screen agrees: the button that is the other door of the
      // same room is shut, and the sign-in form is what is offered.
      await tester.tap(find.byIcon(Icons.more_vert));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("sign-out")), findsNothing);
      await tester.tap(find.text(testL10n.serverMenuEntry));
      await tester.pumpAndSettle();
      expect(find.byType(ServerSettingsScreen), findsOneWidget);
      if (find.byKey(signOutButtonKey).evaluate().isEmpty) {
        await tester.scrollUntilVisible(find.byKey(signOutButtonKey), 200,
            scrollable: find.byType(Scrollable).first);
        await tester.pumpAndSettle();
      }
      var button = tester.widget<TextButton>(find.byKey(signOutButtonKey));
      expect(button.onPressed, isNull, reason: "nothing to sign out of");
      expect(find.text(testL10n.signInHeading), findsOneWidget);
    });
  });
}
