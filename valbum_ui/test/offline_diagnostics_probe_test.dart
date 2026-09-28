/// Probe of issues #57 and #58: the "reached" rule and the request log are
/// mechanisms of the transport, not of the listing — so they must hold for a
/// thumbnail refusal after an offline fallback, for a streamed multipart
/// upload with a bearer, and for a derived client talking to another server.
library;

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

String refusal(String message) => '["ErrorInfo",{"message":"$message"}]';

String messagesOf(DiagnosticsLog log) =>
    log.entries.map((entry) => entry.message).join("\n");

void main() {
  test('a thumbnail refusal after an offline fallback is the server speaking',
      () async {
    var log = DiagnosticsLog();
    var state = OfflineState();
    var cache = MemoryOfflineCache();
    const dataUrl = "http://pi:8082/valbum/data";

    var listingFails = true;
    var client = VAlbumClient(
      dataUrl: dataUrl,
      token: "tok-probe",
      cache: cache,
      offlineState: state,
      log: log,
      httpClient: MockClient((request) async {
        if (request.url.query == "type=tn") {
          return http.Response(refusal("Private."), 403);
        }
        if (listingFails) {
          throw const SocketException(
            "Failed host lookup: 'pi'",
            osError: OSError("No address associated with hostname", 7),
          );
        }
        return http.Response('["ListingInfo",{"title":"Live"}]', 200);
      }),
    );

    // A copy from an earlier visit, filed as this signed-in device sees it.
    await cache.putResource(
      dataUrl,
      const [],
      '["ListingInfo",{"title":"Cached","folders":[]}]',
      user: client.cacheUser,
    );

    var cached = await client.loadResource(const []);
    expect(cached, isNotNull);
    expect(state.offline, isTrue, reason: "the copy was shown");

    // A refused thumbnail is an answer: the server is there.
    await expectLater(
      client.thumbnailBytes("$dataUrl/album/x.jpg"),
      throwsA(isA<VAlbumException>()),
    );
    expect(state.offline, isFalse, reason: "a 403 is the server speaking");

    // And the log has both: the complete OS error, and the refusal.
    var text = messagesOf(log);
    expect(text, contains("errno = 7"));
    expect(text, contains("No address associated with hostname"));
    expect(text, contains("Could not load the thumbnail of 'x.jpg': the server answered 403"));
    expect(text, contains("Request: GET http://pi:8082/valbum/data/album/x.jpg?type=tn (with bearer)"));
    expect(text, contains("Server said: Private."));
    expect(text, isNot(contains("tok-probe")));

    // The rule is symmetric: the next lost listing sets it again.
    await client.loadResource(const []);
    expect(state.offline, isTrue);
    listingFails = false;
    await client.loadResource(const []);
    expect(state.offline, isFalse);
  });

  test('a streamed upload that succeeds is not logged at all (issue #184)',
      () async {
    var log = DiagnosticsLog();
    var state = OfflineState()..goneOffline(null);
    var seenAuthorization = "";
    var client = VAlbumClient(
      dataUrl: "http://pi:8082/valbum/data",
      token: "secret-bearer-token",
      offlineState: state,
      log: log,
      httpClient: MockClient((request) async {
        seenAuthorization = request.headers["Authorization"] ?? "";
        // The mock has read the body already; a server would have too.
        expect(request.body, contains("SECRET-PIXELS"));
        return http.Response(
          '{"files":[{"name":"a.jpg","storedAs":"a.jpg","hash":"h","status":"stored"}]}',
          200,
        );
      }),
    );

    var contents = utf8.encode("SECRET-PIXELS");
    var result = await client.uploadFiles(
      "http://pi:8082/valbum/data/album/",
      [
        UploadFile(
          name: "a.jpg",
          length: contents.length,
          openRead: () => Stream.value(contents),
        ),
      ],
    );
    expect(result.files, hasLength(1));
    expect(seenAuthorization, "Bearer secret-bearer-token");
    expect(state.offline, isFalse, reason: "the upload was answered");

    // Nothing went wrong, so nothing is written down.
    expect(log.isEmpty, isTrue);
  });

  test('a derived client for another server writes into the same log',
      () async {
    var log = DiagnosticsLog();
    var first = VAlbumClient(
      dataUrl: "http://stored/valbum/data",
      log: log,
      httpClient: MockClient((request) async {
        if (request.url.host == "stored") {
          throw http.ClientException("Failed host lookup: 'stored'");
        }
        return http.Response('{"mode":"all","deviceName":"","writeAllowed":false,"userName":"","role":"","space":""}', 200);
      }),
    );
    var second = first.withDataUrl("http://homepi:8082/valbum/data");

    await expectLater(first.authInfo(), throwsA(anything));
    await second.authInfo();

    var text = messagesOf(log);
    expect(log.entries, hasLength(1), reason: "the answered one is no problem");
    expect(text, contains("Could not ask the server who this device is: no answer"));
    expect(text, contains("Request: GET http://stored/valbum/data/?type=auth"));
    expect(text, contains("Failed host lookup: 'stored'"));
    expect(text, isNot(contains("homepi")));
  });

  test('maskUrl masks every token and keeps everything else', () {
    expect(
      maskUrl("https://h/valbum/i/abcdef123456/data/?type=auth"),
      "https://h/valbum/i/ab••••••••56/data/?type=auth",
    );
    expect(
      maskUrl("https://h/valbum/s/xy/"),
      "https://h/valbum/s/••/",
    );
    // A folder that happens to be called `i` at the end masks nothing.
    expect(maskUrl("https://h/valbum/data/i"), "https://h/valbum/data/i");
    // A port and a query survive untouched.
    expect(
      maskUrl("http://homepi:8082/valbum/data/2024/?type=json"),
      "http://homepi:8082/valbum/data/2024/?type=json",
    );
    // The copied text masks the configured server too.
    var log = DiagnosticsLog()..add("x");
    var copy = log.copyText(serverUrl: "https://h/valbum/i/abcdef123456/");
    expect(copy, isNot(contains("abcdef123456")));
    expect(copy, contains("Server: https://h/valbum/i/ab••••••••56/"));
  });
}
