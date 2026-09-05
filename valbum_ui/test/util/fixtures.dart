import 'dart:async';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';

import 'fake_image_http.dart';

/// Reads a JSON fixture from `test/fixtures/json`.
String fixture(String name) =>
    File("test/fixtures/json/$name").readAsStringSync();

/// Whether the given request asks for a thumbnail.
bool isThumbnailRequest(http.BaseRequest request) =>
    request.url.queryParameters["type"] == "tn";

/// Wraps a request handler so that thumbnails answer with a 1x1 PNG.
///
/// Since issue #31 the tiles of the app fetch their thumbnails through the
/// [VAlbumClient] (they carry the device token and feed the offline cache),
/// so the injected transport of a test has to serve images as well as JSON.
/// Every handler below goes through here; a test only describes its album.
Future<http.Response> Function(http.Request) servingThumbnails(
  FutureOr<http.Response> Function(http.Request request) handler,
) =>
    (request) async => isThumbnailRequest(request)
        ? http.Response.bytes(
            transparentPixelPng,
            200,
            headers: {"content-type": "image/png"},
          )
        : await handler(request);

/// A client answering every request with the given body and status.
VAlbumClient clientReturning(
  String body, {
  int status = 200,
  String dataUrl = "http://server/valbum/data",
  List<http.Request>? requests,
}) =>
    VAlbumClient(
      dataUrl: dataUrl,
      httpClient: MockClient(servingThumbnails((request) async {
        requests?.add(request);
        return http.Response(
          body,
          status,
          headers: {"content-type": "application/json; charset=utf-8"},
        );
      })),
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
      httpClient: MockClient(servingThumbnails((request) async {
        requests?.add(request);
        return handler(request);
      })),
    );
