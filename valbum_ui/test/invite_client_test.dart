/// Tests of the wire of the invitations of issue #52: the pairing that accepts
/// one, and the four calls that issue, list, withdraw and promote.
///
/// Only [VAlbumClient.invite] has a screen in this app; listing, withdrawing
/// and promoting belong to the management screens of issue #55. The calls are
/// tested here nevertheless — a call nobody ever ran is a call nobody knows
/// the shape of.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/resource.dart';

/// The server the tests talk to.
const String dataUrl = "http://server/valbum/data";

/// A client recording what it sent, answering [body].
VAlbumClient clientAnswering(
  String body, {
  int status = 200,
  List<http.Request>? requests,
  String? token,
}) =>
    VAlbumClient(
      dataUrl: dataUrl,
      token: token,
      httpClient: MockClient((request) async {
        requests?.add(request);
        return http.Response(
          body,
          status,
          headers: {"content-type": "application/json; charset=utf-8"},
        );
      }),
    );

void main() {
  group('accepting an invitation', () {
    test('pairs with the invitation instead of the secret', () async {
      var requests = <http.Request>[];
      var client = clientAnswering(
        '{"token": "dev-9", "deviceName": "Phone", "userName": "carol", '
        '"role": "member", "space": "carol"}',
        requests: requests,
      );

      var answer = await client.pair(
        invitation: "inv",
        deviceName: "Phone",
        userName: "carol",
      );

      expect(requests, hasLength(1));
      var request = requests.single;
      expect(request.method, "POST");
      expect(request.url.queryParameters["action"], "pair");
      expect(request.body, contains('"invitation":"inv"'));
      expect(request.body, contains('"userName":"carol"'));
      expect(request.body, contains('"deviceName":"Phone"'));
      expect(request.body, contains('"secret":""'));
      // A sign-in is how a device gets a token; it never carries one.
      expect(request.headers.containsKey("Authorization"), isFalse);

      expect(answer.token, "dev-9");
      expect(answer.userName, "carol");
      expect(answer.role, "member");
      expect(answer.space, "carol");
    });

    test('the pairing secret still signs in without an invitation', () async {
      var requests = <http.Request>[];
      var client = clientAnswering(
        '{"token": "t", "deviceName": "Phone", "userName": "", '
        '"role": "admin", "space": ""}',
        requests: requests,
      );

      await client.pair(secret: "demo", deviceName: "Phone");

      expect(requests.single.body, contains('"secret":"demo"'));
      expect(requests.single.body, contains('"invitation":""'));
    });
  });

  group('issuing an invitation', () {
    test('posts the role, the note and the expiry, and reads the token back',
        () async {
      var requests = <http.Request>[];
      var client = clientAnswering(
        '{"invitation": {"id": "i7", "role": "guest", "note": "Party", '
        '"expires": "2026-12-24T17:00:00Z", "invitedBy": "alice", '
        '"created": "2026-09-13T10:00:00Z", "used": "", "usedBy": "", '
        '"revoked": ""}, "token": "tok", "url": "/valbum/i/tok/"}',
        requests: requests,
        token: "alice-token",
      );

      var answer = await client.invite(Invitation(
        role: "guest",
        note: "Party",
        expires: "2026-12-24T17:00:00Z",
      ));

      var request = requests.single;
      expect(request.method, "POST");
      expect(request.url.path, "/valbum/data/");
      expect(request.url.queryParameters["action"], "invite");
      expect(request.headers["Authorization"], "Bearer alice-token");
      expect(request.body, contains('"role":"guest"'));
      expect(request.body, contains('"note":"Party"'));
      expect(request.body, contains('"expires":"2026-12-24T17:00:00Z"'));

      expect(answer.token, "tok");
      expect(answer.url, "/valbum/i/tok/");
      expect(answer.invitation?.id, "i7");
      expect(answer.invitation?.invitedBy, "alice");
    });

    test('a refusal carries the server\'s own sentence', () async {
      var client = clientAnswering(
        '["ErrorInfo", {"message": "The administrator invites people on this '
        'server. Ask them for an invitation."}]',
        status: 403,
      );

      expect(
        () => client.invite(Invitation(role: "member")),
        throwsA(
          isA<VAlbumException>()
              .having((e) => e.status, "status", 403)
              .having(
                (e) => e.message,
                "message",
                "The administrator invites people on this server. Ask them "
                    "for an invitation.",
              ),
        ),
      );
    });
  });

  group('managing invitations', () {
    test('lists them', () async {
      var requests = <http.Request>[];
      var client = clientAnswering(
        '{"invitations": [{"id": "i1", "role": "member", "note": "", '
        '"expires": "", "invitedBy": "alice", "created": "", "used": "", '
        '"usedBy": "", "revoked": ""}]}',
        requests: requests,
      );

      var answer = await client.invitations();

      expect(requests.single.method, "GET");
      expect(requests.single.url.path, "/valbum/data/");
      expect(requests.single.url.queryParameters["type"], "invitations");
      expect(answer.invitations.single.id, "i1");
      expect(answer.invitations.single.invitedBy, "alice");
    });

    test('withdraws one by its id', () async {
      var requests = <http.Request>[];
      var client = clientAnswering(
        '{"invitations": [{"id": "i1", "role": "member", "note": "", '
        '"expires": "", "invitedBy": "alice", "created": "", "used": "", '
        '"usedBy": "", "revoked": "2026-09-13T11:00:00Z"}]}',
        requests: requests,
      );

      var answer = await client.uninvite("i1");

      expect(requests.single.method, "POST");
      expect(requests.single.url.queryParameters["action"], "uninvite");
      expect(requests.single.body, contains('"id":"i1"'));
      expect(answer.invitations.single.revoked, isNotEmpty);
    });

    test('promotes a guest to a member', () async {
      var requests = <http.Request>[];
      var client = clientAnswering(
        '{"name": "carol", "role": "member"}',
        requests: requests,
      );

      var answer = await client.promote("carol");

      expect(requests.single.method, "POST");
      expect(requests.single.url.path, "/valbum/data/");
      expect(requests.single.url.queryParameters["action"], "promote");
      expect(requests.single.body, contains('"name":"carol"'));
      expect(answer.name, "carol");
      expect(answer.role, "member");
    });
  });
}
