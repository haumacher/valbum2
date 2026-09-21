/// Throwing an album's generated cache away, the app half of issue #98.
///
/// A preview written by an early build could be truncated, and nothing ever
/// looks inside a cached file: it is fresh when it is newer than the original,
/// so a broken thumbnail stayed broken for ever. Since issue #98 an
/// administrator can tell the server to delete what it generated itself —
/// `preview-*`, `video-*.mp4`, `teaser-*.mp4` and their `.tmp` leftovers,
/// inside that one folder's `.vacache` and nowhere else. The photos are never
/// touched, and the previews are made anew when they are next asked for.
///
/// An extension rather than a method of [VAlbumClient]: this is one call of
/// one screen, and the client is the app's common transport — it is built from
/// the client's public pieces ([VAlbumClient.folderUrl],
/// [VAlbumClient.authHeaders], [VAlbumClient.httpClient],
/// [VAlbumClient.timeout]) exactly as a method of it would be, and a refusal
/// is thrown as the [VAlbumException] every other refused write is thrown as.
library;

import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';

import 'client.dart';
import 'locales.dart';
import 'resource.dart';

extension CacheRefreshClient on VAlbumClient {
  /// Throws away the generated cache of the folder at [path] (issue #98).
  ///
  /// Needs the admin role; a server refusing it answers `403` with an
  /// [ErrorInfo] whose message is meant for the user, and that message is what
  /// the thrown [VAlbumException] carries — a refusal speaks.
  ///
  /// Answers how many files the server deleted, which is the only evidence the
  /// caller gets that the broken thumbnail they were looking at is really
  /// gone.
  Future<CacheRefreshed> refreshCache(List<String> path) async {
    var url = "${folderUrl(path)}?action=refresh-cache";
    http.Response response;
    try {
      response = await httpClient.post(
        Uri.parse(url),
        encoding: Encoding.getByName("utf-8"),
        headers: {"Content-Type": "application/json", ...authHeaders},
      ).timeout(timeout);
    } on Exception catch (error) {
      // A server that cannot be reached is not a refusal, and the album must
      // not be reloaded as if the cache had gone.
      throw VAlbumException(
        platformMessages.serverNotReached(describeTransportError(error)),
      );
    }
    if (response.statusCode >= 300) {
      throw VAlbumClient.failure(
        response.statusCode,
        response.body,
        platformMessages.doingRefreshingPreviews(
          "'${path.join("/")}'",
        ),
      );
    }
    return CacheRefreshed.read(JsonReader.fromString(response.body));
  }
}

/// What to say about a transport failure, without repeating the type name.
String describeTransportError(Object error) => switch (error) {
      http.ClientException(message: var message) => message,
      _ => error.toString().trim(),
    };
