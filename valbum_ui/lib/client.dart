import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';

import 'resource.dart';
import 'urls.dart';

/// A failure while talking to the album server.
class VAlbumException implements Exception {
  final String message;

  const VAlbumException(this.message);

  @override
  String toString() => message;
}

/// A file to be uploaded, decoupled from the file picker implementation.
class UploadFile {
  /// The file name announced to the server.
  final String name;

  /// The number of bytes [openRead] produces.
  final int length;

  /// Opens the contents of the file.
  final Stream<List<int>> Function() openRead;

  const UploadFile({
    required this.name,
    required this.length,
    required this.openRead,
  });
}

/// Handle allowing to cancel a running upload.
class UploadHandle {
  bool _cancelled = false;

  /// Requests the running upload to stop.
  void cancel() => _cancelled = true;

  /// Whether [cancel] was called.
  bool get cancelled => _cancelled;
}

/// The single point of contact with the album server.
///
/// All URL construction and all HTTP traffic of the app goes through this
/// class, so that tests can inject a fake [http.Client].
class VAlbumClient {
  /// The URL of the JSON API, without trailing slash,
  /// e.g. `http://localhost:9090/valbum/data`.
  final String dataUrl;

  /// The token this device is paired with the server as, `null` while the app
  /// talks to the server anonymously.
  ///
  /// When set, every request of this client — reads, writes and the streamed
  /// upload alike — carries it as `Authorization: Bearer <token>`, see
  /// [authHeaders]. A server started with `--auth writes` (the default)
  /// refuses an anonymous write, see [pair].
  final String? token;

  final http.Client _http;

  VAlbumClient({required this.dataUrl, this.token, http.Client? httpClient})
      : _http = httpClient ?? http.Client();

  /// The transport this client sends its requests over.
  ///
  /// Exposed so that a client for another server can be built without
  /// building another transport, see [withDataUrl].
  http.Client get httpClient => _http;

  /// A client talking to [dataUrl] over the transport of this one.
  ///
  /// Used when the user points the app at a different server, see
  /// `ServerSettings`: only the URL changes, the transport (a fake one, in a
  /// test) stays the same.
  VAlbumClient withDataUrl(String dataUrl) =>
      VAlbumClient(dataUrl: dataUrl, token: token, httpClient: _http);

  /// The same client, identifying itself with the given token from now on.
  ///
  /// `null` drops the token: the client talks to the server anonymously again.
  VAlbumClient withToken(String? token) =>
      VAlbumClient(dataUrl: dataUrl, token: token, httpClient: _http);

  /// The authorization header of every request, empty while unpaired.
  Map<String, String> get authHeaders {
    var value = token;
    return value == null || value.isEmpty
        ? const {}
        : {"Authorization": "Bearer $value"};
  }

  /// Client talking to the server this app was loaded from (on the web), or to
  /// the [defaultDataUrl] on all other platforms.
  factory VAlbumClient.fromOrigin({String? token, http.Client? httpClient}) =>
      VAlbumClient(
        dataUrl: deriveDataUrl(Uri.base, isWeb: kIsWeb),
        token: token,
        httpClient: httpClient,
      );

  /// The URL of the resource at the given path (a list of folder names).
  String baseUrl(List<String> path) =>
      "$dataUrl${path.isEmpty ? "" : "/${path.join("/")}"}";

  /// The URL of the folder resource at [path], with a trailing slash.
  ///
  /// This is the URL an album is stored to, see [saveAlbum], and the URL its
  /// JSON representation is loaded from, see [jsonUrl].
  String folderUrl(List<String> path) {
    var pathString = path.join("/");
    return "$dataUrl/${pathString.isEmpty ? "" : "$pathString/"}";
  }

  /// The URL delivering the JSON representation of the resource at [path].
  String jsonUrl(List<String> path) => "${folderUrl(path)}?type=json";

  /// The URL delivering a thumbnail of the image at the given URL.
  String thumbnailUrl(String imageUrl) => "$imageUrl?type=tn";

  /// The URL delivering the original of the image at the given URL.
  String originalUrl(String imageUrl) => imageUrl;

  /// Loads the resource at the given path.
  Future<Resource?> loadResource(List<String> path) async {
    var uri = jsonUrl(path);
    if (kDebugMode) {
      print("Fetching: $uri");
    }
    var response = await _http.get(Uri.parse(uri), headers: authHeaders);
    if (response.statusCode != 200) {
      throw failure(response.statusCode, response.body, "loading '$uri'");
    }
    return Resource.read(JsonReader.fromString(response.body));
  }

  /// Stores the given resource at the given URL.
  Future<void> putResource(String url, Resource resource) async {
    var response = await _http.put(
      Uri.parse(url),
      encoding: Encoding.getByName("utf-8"),
      body: resource.toString(),
      headers: {"Content-Type": "application/json", ...authHeaders},
    );
    if (response.statusCode >= 300) {
      throw failure(response.statusCode, response.body, "storing '$url'");
    }
  }

  /// Stores the given album as the `index.json` sidecar of its own folder.
  ///
  /// The album is written to its own URL (the folder at [path]); the server
  /// keeps the previous sidecar as a backup. Throws a [VAlbumException] naming
  /// the HTTP status if the server refuses the write.
  Future<void> saveAlbum(List<String> path, AlbumInfo album) =>
      putResource(folderUrl(path), album);

  /// Uploads the given files to the resource at the given URL.
  ///
  /// Reports the transfer progress in percent to [onProgress]. The upload stops
  /// early when [handle] is cancelled.
  ///
  /// Throws a [VAlbumException] naming the server's reason if the server
  /// refuses the upload — an unpaired device, for instance.
  Future<void> uploadFiles(
    String url,
    List<UploadFile> files, {
    void Function(int percent)? onProgress,
    UploadHandle? handle,
  }) async {
    var uri = Uri.parse(url);

    var multipart = http.MultipartRequest("PUT", uri);
    for (var file in files) {
      multipart.files.add(
        http.MultipartFile(
          file.name,
          file.openRead(),
          file.length,
          filename: file.name,
        ),
      );
    }
    var contentLength = multipart.contentLength;

    var request = http.StreamedRequest("PUT", uri);
    request.headers.addAll(multipart.headers);
    request.headers.addAll(authHeaders);
    request.contentLength = contentLength;

    // Feed the multipart body into the request while reporting progress.
    unawaited(() async {
      var transferred = 0;
      try {
        await for (var chunk in multipart.finalize()) {
          if (handle != null && handle.cancelled) {
            break;
          }
          request.sink.add(chunk);
          transferred += chunk.length;
          onProgress?.call(
            contentLength == 0
                ? 100
                : (100 * transferred / contentLength).round(),
          );
        }
      } finally {
        request.sink.close();
      }
    }());

    var response = await _http.send(request);
    var body = await response.stream.bytesToString();
    if (response.statusCode >= 300) {
      throw failure(response.statusCode, body, "uploading to '$url'");
    }
  }

  /// Pairs this device with the server, returning the token it issued.
  ///
  /// The token is what makes the app a known caller; store it with the server
  /// settings and hand it to [withToken]. Throws a [VAlbumException] carrying
  /// the server's reason when the secret is wrong.
  Future<PairResponse> pair(String secret, String deviceName) async {
    var url = "${folderUrl(const [])}?action=pair";
    var request = PairRequest(secret: secret, deviceName: deviceName);
    var body = StringBuffer();
    request.writeContent(jsonStringWriter(body));

    var response = await _http.post(
      Uri.parse(url),
      encoding: Encoding.getByName("utf-8"),
      body: body.toString(),
      headers: const {"Content-Type": "application/json"},
    );
    if (response.statusCode >= 300) {
      throw failure(response.statusCode, response.body, "pairing with '$url'");
    }
    return PairResponse.read(JsonReader.fromString(response.body));
  }

  /// What this client is allowed to do on the server, and as which device.
  ///
  /// Answered by every server, paired or not: this is how the app learns that
  /// it must pair before it can change anything.
  Future<AuthInfo> authInfo() async {
    var url = "${folderUrl(const [])}?type=auth";
    var response = await _http.get(Uri.parse(url), headers: authHeaders);
    if (response.statusCode >= 300) {
      throw failure(response.statusCode, response.body, "asking '$url'");
    }
    return AuthInfo.read(JsonReader.fromString(response.body));
  }

  /// The exception for a refused request.
  ///
  /// A server that refuses says why: the body of a refusal is an [ErrorInfo]
  /// whose message is meant for the user, so that is what the exception
  /// carries. Only where there is no such body does the status have to do.
  static VAlbumException failure(int status, String body, String what) {
    var message = errorMessage(body);
    if (message != null) {
      return VAlbumException(message);
    }
    return VAlbumException("HTTP $status while $what.");
  }

  /// The message of an [ErrorInfo] body, `null` if the body is not one.
  static String? errorMessage(String body) {
    try {
      var resource = Resource.read(JsonReader.fromString(body));
      if (resource is ErrorInfo && resource.message.isNotEmpty) {
        return resource.message;
      }
    } catch (_) {
      // Not a resource at all; the status has to do.
    }
    return null;
  }

  /// Releases the underlying HTTP resources.
  void close() => _http.close();
}

/// Makes the [VAlbumClient] available to the widget tree.
class VAlbumScope extends InheritedWidget {
  final VAlbumClient client;

  const VAlbumScope({super.key, required this.client, required super.child});

  /// The client provided by the closest enclosing [VAlbumScope].
  static VAlbumClient of(BuildContext context) {
    var scope = context.dependOnInheritedWidgetOfExactType<VAlbumScope>();
    assert(scope != null, "No VAlbumScope found in the widget tree.");
    return scope!.client;
  }

  @override
  bool updateShouldNotify(VAlbumScope oldWidget) => client != oldWidget.client;
}
