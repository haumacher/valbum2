import 'dart:async';
import 'dart:convert';

import 'package:convert/convert.dart';
import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';

import 'offline.dart';
import 'platform.dart';
import 'resource.dart';
import 'urls.dart';

/// A failure while talking to the album server.
class VAlbumException implements Exception {
  final String message;

  /// The HTTP status the server answered with, `null` if the server did not
  /// answer at all (a transport failure) or the failure is not a status.
  final int? status;

  const VAlbumException(this.message, {this.status});

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

  /// The SHA-256 hash of the contents, in lower-case hex, `null` while it has
  /// not been computed yet.
  ///
  /// The hash is what makes an upload idempotent: the server compares it with
  /// the contents the target album already holds and stores nothing that is
  /// already there, see [VAlbumClient.uploadNew]. The server hashes what it
  /// receives itself, so this value is an optimisation, never a promise.
  final String? sha256;

  const UploadFile({
    required this.name,
    required this.length,
    required this.openRead,
    this.sha256,
  });

  /// The same file, with its contents' hash attached.
  UploadFile withHash(String hash) => UploadFile(
        name: name,
        length: length,
        openRead: openRead,
        sha256: hash,
      );
}

/// The [UploadedFile.status] of contents that the server has written.
const String uploadStored = "stored";

/// The [UploadedFile.status] of contents the album already held.
const String uploadPresent = "present";

/// The SHA-256 hash of the given contents, in lower-case hex.
///
/// The stream is consumed in chunks, so that hashing a video does not pull the
/// whole file into memory.
Future<String> sha256Of(Stream<List<int>> contents) async {
  var digests = AccumulatorSink<Digest>();
  var input = sha256.startChunkedConversion(digests);
  await for (var chunk in contents) {
    input.add(chunk);
  }
  input.close();
  return digests.events.single.toString();
}

/// What an upload did, see [VAlbumClient.uploadNew].
class UploadSummary {
  /// The number of files the server stored.
  final int stored;

  /// The number of files the album already held, which were not stored again.
  final int present;

  const UploadSummary({required this.stored, required this.present});

  /// The number of files the upload was asked to transfer.
  int get total => stored + present;

  /// What the user is told about the upload.
  ///
  /// Both counts are named: a sync that transfers nothing because everything
  /// is already there must not look like a sync that did nothing.
  String get message => "$stored hochgeladen, $present bereits vorhanden.";
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

  /// What this app has already seen, `null` if nothing is cached.
  ///
  /// Every answer of the server is written through into it, and only a
  /// *transport* failure — the server cannot be reached at all — is answered
  /// from it, see [loadResource] and issue #31.
  final OfflineCache? cache;

  /// Told whenever a load fell back to the [cache], or reached the server
  /// again; the views show what it says.
  final OfflineState? offlineState;

  /// How long a request may take before the server counts as unreachable.
  ///
  /// Without it a phone with a captive portal or a half-open connection waits
  /// forever instead of showing what it has.
  final Duration timeout;

  final http.Client _http;

  VAlbumClient({
    required this.dataUrl,
    this.token,
    http.Client? httpClient,
    this.cache,
    this.offlineState,
    this.timeout = const Duration(seconds: 15),
  }) : _http = httpClient ?? http.Client();

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
  VAlbumClient withDataUrl(String dataUrl) => VAlbumClient(
        dataUrl: dataUrl,
        token: token,
        httpClient: _http,
        cache: cache,
        offlineState: offlineState,
        timeout: timeout,
      );

  /// The same client, identifying itself with the given token from now on.
  ///
  /// `null` drops the token: the client talks to the server anonymously again.
  VAlbumClient withToken(String? token) => VAlbumClient(
        dataUrl: dataUrl,
        token: token,
        httpClient: _http,
        cache: cache,
        offlineState: offlineState,
        timeout: timeout,
      );

  /// The authorization header of every request, empty while unpaired.
  Map<String, String> get authHeaders {
    var value = token;
    return value == null || value.isEmpty
        ? const {}
        : {"Authorization": "Bearer $value"};
  }

  /// Client talking to the server this app was loaded from (on the web), or to
  /// the [defaultDataUrl] on all other platforms.
  factory VAlbumClient.fromOrigin({
    String? token,
    http.Client? httpClient,
    OfflineCache? cache,
    OfflineState? offlineState,
  }) =>
      VAlbumClient(
        dataUrl: deriveDataUrl(Uri.base, isWeb: kIsWeb),
        token: token,
        httpClient: httpClient,
        cache: cache,
        offlineState: offlineState,
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
  ///
  /// Network first, cache second: the server is asked, its answer is written
  /// through into the [cache], and only if the server cannot be *reached* does
  /// what the cache holds answer instead — the app is then told it is offline,
  /// see [OfflineState]. A server that answers with a status is the server
  /// speaking: that refusal is reported, never masked by an older copy.
  Future<Resource?> loadResource(List<String> path) async {
    var uri = jsonUrl(path);
    if (kDebugMode) {
      print("Fetching: $uri");
    }
    http.Response response;
    try {
      response = await _http
          .get(Uri.parse(uri), headers: authHeaders)
          .timeout(timeout);
    } catch (error) {
      if (!isTransportFailure(error)) {
        rethrow;
      }
      return _cachedResource(path, uri, error);
    }
    if (response.statusCode != 200) {
      throw failure(response.statusCode, response.body, "loading '$uri'");
    }
    offlineState?.online();
    await cache?.putResource(dataUrl, path, response.body);
    return Resource.read(JsonReader.fromString(response.body));
  }

  /// The last copy of [path] this app saw, after the server could not be
  /// reached.
  ///
  /// Throws a [VAlbumException] saying so when there is none: an empty screen
  /// would leave the user guessing whether the album is gone or the server is.
  Future<Resource?> _cachedResource(
    List<String> path,
    String uri,
    Object error,
  ) async {
    var entry = await cache?.getResource(dataUrl, path);
    if (entry == null) {
      offlineState?.goneOffline(null);
      throw VAlbumException(
        "The server cannot be reached (${transportMessage(error)}), and "
        "nothing is cached for this view.",
      );
    }
    offlineState?.goneOffline(entry.storedAt);
    if (kDebugMode) {
      print("Offline, showing the copy from ${entry.storedAt}: $uri");
    }
    return Resource.read(JsonReader.fromString(entry.text));
  }

  /// The bytes of the thumbnail at the given image URL.
  ///
  /// Thumbnails go through this client rather than through `Image.network`,
  /// for two reasons: the request carries the device token (a server started
  /// with `--auth all` refuses an anonymous image), and what was fetched is
  /// kept in the [cache], so an album already visited still shows its tiles
  /// while the server is away.
  Future<Uint8List> thumbnailBytes(String imageUrl) async {
    var url = thumbnailUrl(imageUrl);
    http.Response response;
    try {
      response = await _http
          .get(Uri.parse(url), headers: authHeaders)
          .timeout(timeout);
    } catch (error) {
      if (!isTransportFailure(error)) {
        rethrow;
      }
      var entry = await cache?.getThumbnail(url);
      if (entry == null) {
        rethrow;
      }
      return entry.bytes;
    }
    if (response.statusCode != 200) {
      throw failure(response.statusCode, response.body, "loading '$url'");
    }
    await cache?.putThumbnail(url, response.bodyBytes);
    return response.bodyBytes;
  }

  /// Whether the given error means the server could not be reached at all.
  ///
  /// This is the whole of "offline": a refusal, a missing album or a server
  /// error are answers, and answers are shown as they are.
  /// What a transport failure says, without the exception's own decoration.
  static String transportMessage(Object error) => switch (error) {
        http.ClientException(message: var message) => message,
        TimeoutException() => "no answer in time",
        _ => error.toString(),
      };

  static bool isTransportFailure(Object error) =>
      error is http.ClientException ||
      error is TimeoutException ||
      isSocketError(error);

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
  /// Answers what the server did with every file: contents the album already
  /// holds are reported as [uploadPresent] and are not stored a second time,
  /// see [uploadNew]. A server that answers with an empty body (before issue
  /// #29) is taken to have stored everything it was sent.
  ///
  /// Throws a [VAlbumException] naming the server's reason if the server
  /// refuses the upload — an unpaired device, for instance.
  Future<UploadResult> uploadFiles(
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
    return uploadResult(body, files);
  }

  /// The server's answer to an upload of the given files.
  ///
  /// A server that says nothing stored what it was sent: that is what the
  /// upload meant before the answer existed, so an older server keeps working.
  static UploadResult uploadResult(String body, List<UploadFile> files) {
    if (body.trim().isNotEmpty) {
      try {
        return UploadResult.read(JsonReader.fromString(body));
      } catch (_) {
        // Not an answer this app understands; fall back to the assumption
        // below rather than failing an upload that has already happened.
      }
    }
    return UploadResult(
      files: [
        for (var file in files)
          UploadedFile(
            name: file.name,
            storedAs: file.name,
            hash: file.sha256 ?? "",
            status: uploadStored,
          ),
      ],
    );
  }

  /// Asks the album at [path] which of the given contents it already holds.
  ///
  /// Asking is a read: it works without a paired device wherever reading does.
  Future<UploadCheckResult> checkUploads(
    List<String> path,
    List<String> hashes,
  ) async {
    var url = "${folderUrl(path)}?action=check";
    var check = UploadCheck(
      hashes: [for (var hash in hashes) ContentHash(hash: hash)],
    );
    var body = StringBuffer();
    check.writeContent(jsonStringWriter(body));

    var response = await _http.post(
      Uri.parse(url),
      encoding: Encoding.getByName("utf-8"),
      body: body.toString(),
      headers: {"Content-Type": "application/json", ...authHeaders},
    );
    if (response.statusCode >= 300) {
      throw failure(response.statusCode, response.body, "asking '$url'");
    }
    return UploadCheckResult.read(JsonReader.fromString(response.body));
  }

  /// Uploads to the album at [path] what it does not hold yet.
  ///
  /// Every file is hashed, the album is asked which of the contents it already
  /// has, and only the rest is transferred — a sync that is interrupted and
  /// retried therefore uploads only what is missing, and never duplicates a
  /// photo. The upload itself is idempotent as well, so a server that cannot
  /// answer the question is simply sent everything.
  Future<UploadSummary> uploadNew(
    List<String> path,
    List<UploadFile> files, {
    void Function(int percent)? onProgress,
    UploadHandle? handle,
  }) async {
    if (files.isEmpty) {
      return const UploadSummary(stored: 0, present: 0);
    }

    var hashed = <UploadFile>[];
    for (var file in files) {
      hashed.add(
        file.sha256 != null
            ? file
            : file.withHash(await sha256Of(file.openRead())),
      );
    }

    var known = <String>{};
    try {
      var check = await checkUploads(path, [for (var f in hashed) f.sha256!]);
      known.addAll([for (var present in check.present) present.hash]);
    } on VAlbumException catch (error) {
      // An older server does not know the question (404/405): it still refuses
      // a duplicate when the contents arrive, so the upload goes ahead. Any
      // other answer is the server speaking — a refusal must not be followed
      // by sending the contents anyway.
      if (error.status != 404 && error.status != 405) {
        rethrow;
      }
      if (kDebugMode) {
        print("The server cannot check uploads: $error");
      }
    } catch (error) {
      // A transport failure: the upload attempt reports it in its own right.
      if (kDebugMode) {
        print("Cannot check uploads: $error");
      }
    }

    var pending = [
      for (var file in hashed)
        if (!known.contains(file.sha256)) file
    ];
    var skipped = hashed.length - pending.length;
    if (pending.isEmpty) {
      onProgress?.call(100);
      return UploadSummary(stored: 0, present: skipped);
    }

    var result = await uploadFiles(
      folderUrl(path),
      pending,
      onProgress: onProgress,
      handle: handle,
    );
    var stored =
        result.files.where((file) => file.status != uploadPresent).length;
    return UploadSummary(
      stored: stored,
      present: skipped + (result.files.length - stored),
    );
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
      return VAlbumException(message, status: status);
    }
    return VAlbumException("HTTP $status while $what.", status: status);
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
