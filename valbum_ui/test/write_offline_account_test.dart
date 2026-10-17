/// The writes of the account side that went out while offline before issue
/// #293 — a link deleted, a recipient taken off or sent a new link on the
/// shares screen, a device signed out — are refused there now, with the one
/// sentence every offline refusal says, and nothing is sent.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/settings.dart';
import 'package:valbum_ui/offline.dart';
import 'package:valbum_ui/shares_view.dart';

import 'devices_test.dart' as devices;
import 'shares_view_test.dart' as shares;
import 'util/l10n.dart';

/// The shares screen, loaded while online; [state] is then turned offline by
/// the test.
Future<void> pumpShares(
  WidgetTester tester,
  shares.SharesServer server,
  OfflineState state,
) async {
  tester.view.physicalSize = const Size(1000, 3000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(OfflineScope(
    state: state,
    cache: MemoryOfflineCache(),
    child: localizedApp(SharesScreen(
      client: shares.clientOf(server),
      isWeb: true,
      now: shares.now,
    )),
  ));
  await tester.pumpAndSettle();
}

Future<void> goOffline(WidgetTester tester, OfflineState state) async {
  state.goneOffline(null);
  await tester.pumpAndSettle();
}

void main() {
  group('the shares screen offline', () {
    testWidgets('does not delete a link', (tester) async {
      var server = shares.SharesServer();
      var state = OfflineState();
      await pumpShares(tester, server, state);
      await goOffline(tester, state);
      await shares.tapKey(tester, "shares-delete-A1");
      await shares.tapKey(tester, "shares-delete-confirmed");
      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
      expect(server.count("unshare"), 0);
      expect(shares.cards(tester), contains("A1"));
    });

    testWidgets('does not take a recipient off', (tester) async {
      var server = shares.SharesServer();
      var state = OfflineState();
      await pumpShares(tester, server, state);
      await goOffline(tester, state);
      await shares.tapKey(tester, "shares-recipient-menu-L1-c2");
      await shares.tapKey(tester, "shares-remove-L1-c2");
      await shares.tapKey(tester, "shares-remove-confirmed");
      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
      expect(server.count("remove-recipient"), 0);
      expect(find.byKey(const Key("shares-recipient-L1-c2")), findsOneWidget);
    });

    testWidgets('does not send a recipient a new link', (tester) async {
      var server = shares.SharesServer();
      var state = OfflineState();
      await pumpShares(tester, server, state);
      await goOffline(tester, state);
      await shares.tapKey(tester, "shares-recipient-menu-L1-c1");
      await shares.tapKey(tester, "shares-renew-L1-c1");
      await shares.tapKey(tester, "shares-renew-confirmed");
      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
      expect(server.count("resend"), 0);
      expect(find.byKey(const Key("shares-renewed")), findsNothing);
    });
  });

  testWidgets('a device is not signed out offline', (tester) async {
    var requests = <http.Request>[];
    var state = OfflineState();
    await tester.binding.setSurfaceSize(const Size(800, 3600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    var transport = MockClient((request) async {
      requests.add(request);
      return devices.serverWith(
          devices: (_) => devices.json(devices.threeDevices()))(request);
    });
    await tester.pumpWidget(OfflineScope(
      state: state,
      cache: MemoryOfflineCache(),
      child: localizedApp(ServerSettingsScreen(
        settings: await devices.signedIn(devices.storeSignedIn()),
        clientFor: (dataUrl) =>
            VAlbumClient(dataUrl: dataUrl, httpClient: transport),
      )),
    ));
    await tester.pumpAndSettle();
    await goOffline(tester, state);

    await devices.tapVisible(tester, find.byKey(const Key("device-remove-d1")));
    await tester.tap(find.byKey(const Key("device-confirmed")));
    await tester.pumpAndSettle();

    expect(find.text(testL10n.offlineRefusal), findsOneWidget);
    expect(requests.where((r) => r.url.queryParameters["action"] == "unpair"),
        isEmpty);
    expect(find.byKey(const Key("device-d1")), findsOneWidget);
  });
}
