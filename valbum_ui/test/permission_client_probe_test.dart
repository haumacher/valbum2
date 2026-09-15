/// Probe for the admin client calls of #85 slice 4: bodies, answers and the
/// server's own sentence on a refusal.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/resource.dart';

void main() {
  test('set-permission and remove-user send what the server expects and parse its list', () async {
    var requests = <http.Request>[];
    var client = VAlbumClient(
      dataUrl: "http://server/valbum/alice/data",
      token: "admin-token",
      httpClient: MockClient((request) async {
        requests.add(request);
        return http.Response(
          '{"users":[{"name":"alice","role":"admin","clearance":"all","mayShare":true}],"revokedLinks":2}',
          200,
          headers: const {"content-type": "application/json"},
        );
      }),
    );
    var list = await client.setPermission(
        UserPermission(name: "bob", role: "contribute", clearance: "nonPrivate", mayShare: true));
    expect(list.users.map((u) => u.name).toList(), ["alice"]);
    expect(requests.single.url.toString(), "http://server/valbum/alice/data/?action=set-permission");
    expect(requests.single.headers["Authorization"], "Bearer admin-token");
    expect(requests.single.body, contains('"name":"bob"'));
    expect(requests.single.body, contains('"role":"contribute"'));
    expect(requests.single.body, contains('"mayShare":true'));

    var after = await client.removeUser("bob");
    expect(after.revokedLinks, 2, reason: "the answer says how many links died with the user");
    expect(requests.last.url.toString(), "http://server/valbum/alice/data/?action=remove-user");
    expect(requests.last.body, contains('"name":"bob"'));
  });

  test('a refusal carries the server\'s sentence, not a status code', () async {
    var client = VAlbumClient(
      dataUrl: "http://server/valbum/data",
      token: "admin-token",
      httpClient: MockClient((request) async => http.Response(
            '["ErrorInfo",{"message":"The last administrator cannot be demoted.","code":"LAST_ADMIN"}]',
            409,
            headers: const {"content-type": "application/json"},
          )),
    );
    try {
      await client.setPermission(UserPermission(name: "alice", role: "view", clearance: "public", mayShare: false));
      fail("expected a refusal");
    } on VAlbumException catch (e) {
      expect(e.message, "The last administrator cannot be demoted.");
    }
  });
}
