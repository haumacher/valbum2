import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';

/// Reads a JSON fixture from `test/fixtures/json`.
String fixture(String name) =>
    File("test/fixtures/json/$name").readAsStringSync();

/// A client answering every request with the given body and status.
VAlbumClient clientReturning(
  String body, {
  int status = 200,
  String dataUrl = "http://server/valbum/data",
  List<http.Request>? requests,
}) =>
    VAlbumClient(
      dataUrl: dataUrl,
      httpClient: MockClient((request) async {
        requests?.add(request);
        return http.Response(
          body,
          status,
          headers: {"content-type": "application/json; charset=utf-8"},
        );
      }),
    );

/// A client answering every request through the given handler.
///
/// Use it where the answer depends on the request, e.g. to let the album load
/// but the following PUT fail.
VAlbumClient clientHandling(
  http.Response Function(http.Request request) handler, {
  String dataUrl = "http://server/valbum/data",
  List<http.Request>? requests,
}) =>
    VAlbumClient(
      dataUrl: dataUrl,
      httpClient: MockClient((request) async {
        requests?.add(request);
        return handler(request);
      }),
    );
