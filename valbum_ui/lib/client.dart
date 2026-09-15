import 'dart:async';
import 'dart:convert';

import 'package:convert/convert.dart';
import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';

import 'diagnostics.dart';
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

  /// The number of files that never reached the server, see issue #63.
  ///
  /// An upload goes out in batches ([UploadBatching]), and a batch that fails
  /// leaves the batches before it on the server. What is left over is counted
  /// here, so that the user is told both halves of the truth: what arrived,
  /// and what has to be sent again.
  final int remaining;

  const UploadSummary({
    required this.stored,
    required this.present,
    this.remaining = 0,
  });

  /// The number of files the upload was asked to transfer.
  int get total => stored + present + remaining;

  /// The number of files that are on the server now.
  int get onServer => stored + present;

  /// Whether everything that was asked for arrived.
  bool get complete => remaining == 0;

  /// What the user is told about the upload.
  ///
  /// Both counts are named: a sync that transfers nothing because everything
  /// is already there must not look like a sync that did nothing.
  String get message => "$stored hochgeladen, $present bereits vorhanden.";
}

/// The greatest number of files one request carries, see [UploadBatching].
const int uploadBatchFiles = 25;

/// The greatest number of bytes one request carries, see [UploadBatching].
const int uploadBatchBytes = 100 * 1024 * 1024;

/// How many files, and how many bytes, go into one request (issue #63).
///
/// A hundred photos in a single streamed multipart body is one socket that has
/// to stay open for many minutes; Android's Doze closes it as soon as the
/// screen locks, and the whole transfer is lost. Bounded batches make the loss
/// bounded too: what a batch delivered stays delivered, and only the rest is
/// sent again.
///
/// The same bound is what the camera-roll sync files its batches by, see
/// [split]: one mechanism, used by the explicit upload and by the sync.
class UploadBatching {
  /// The greatest number of items one batch holds.
  final int maxFiles;

  /// The greatest number of bytes one batch holds, unless a single item is
  /// bigger than that — an item is never split.
  final int maxBytes;

  const UploadBatching({
    this.maxFiles = uploadBatchFiles,
    this.maxBytes = uploadBatchBytes,
  });

  /// The bound the app uploads with.
  static const UploadBatching standard = UploadBatching();

  /// Splits [items] into batches, in order, by both bounds.
  ///
  /// Whichever bound is reached first ends the batch. An item larger than
  /// [maxBytes] forms a batch of its own rather than being dropped or split.
  List<List<T>> split<T>(List<T> items, int Function(T item) lengthOf) {
    var batches = <List<T>>[];
    var batch = <T>[];
    var bytes = 0;
    for (var item in items) {
      var length = lengthOf(item);
      if (batch.isNotEmpty &&
          (batch.length >= maxFiles || bytes + length > maxBytes)) {
        batches.add(batch);
        batch = <T>[];
        bytes = 0;
      }
      batch.add(item);
      bytes += length;
    }
    if (batch.isNotEmpty) {
      batches.add(batch);
    }
    return batches;
  }
}

/// What a lost connection is called on the screen, see
/// [interruptedUploadMessage].
const String uploadConnectionLost = "Verbindung verloren";

/// What the user is told about an upload that stopped halfway (issue #63).
///
/// Plain words, and the raw exception is not among them: a `ClientException`
/// naming a broken pipe says nothing to the person holding the phone. What
/// they need to know is how much arrived, how much did not, and that sending
/// the rest again costs nothing — the server stores no photo twice, see
/// [VAlbumClient.uploadNew]. The exception itself is in the diagnostics log,
/// where it belongs, see [DiagnosticsLog].
String interruptedUploadMessage({
  required String cause,
  required int onServer,
  required int total,
  required int remaining,
}) =>
    "$cause: $onServer von $total Fotos sind auf dem Server, die übrigen "
    "$remaining können erneut gesendet werden.";

/// An upload that stopped after some of its batches had arrived, see
/// [VAlbumClient.uploadNew] and issue #63.
///
/// Carries what arrived: the view shows [message] and reloads the album, so
/// that the photos that did make it are on the screen.
class UploadInterrupted extends VAlbumException {
  /// What the upload achieved before it stopped.
  final UploadSummary summary;

  /// What stopped it, for the log, never for the screen.
  final Object cause;

  UploadInterrupted({
    required this.summary,
    required this.cause,
    required String message,
  }) : super(message);
}

/// What a cancelled upload is refused with, see [VAlbumClient.uploadFiles].
///
/// A cancellation is a refusal like any other, and refusals speak: the user
/// pressed the button, and the screen says what came of it rather than falling
/// silent or — worse — claiming success for a body that was cut in half.
const String uploadCancelledMessage = "Der Upload wurde abgebrochen.";

/// What the dialog says while the server is asked what it already holds.
const String uploadAskingMessage = "Der Server wird gefragt...";

/// What the dialog says once the body is handed over and the server's answer
/// is still outstanding, see issue #59.
///
/// This is the whole of the bug that was reported: the dialog counted the read
/// from local storage, reached 100 % and closed itself while the bytes were
/// still in flight, so the upload looked like it had happened and the album
/// looked like it had refused it.
const String uploadWaitingMessage = "Warte auf den Server...";

/// What the dialog says while the images are on their way (issue #70).
///
/// The one measurement the person can check against what they picked: images.
/// Not batches, not bytes, not requests — the report of #70 was that two
/// different numbers were counted at once while the wheel only spun.
String uploadImageCountMessage(int done, int total) =>
    "$done von $total Bildern";

/// What part of an upload is running, see [UploadProgress].
enum UploadPhase {
  /// The contents are being hashed, so that the server can be asked which of
  /// them it already holds.
  preparing,

  /// The server is being asked exactly that.
  asking,

  /// The images are on their way; this is the only phase with a measurable
  /// progress, see [UploadProgress.determinate].
  transferring,

  /// The last body is handed over and the server's answer is outstanding, see
  /// [uploadWaitingMessage] and issue #59.
  waiting,
}

/// How far an upload has got, in the one unit the person understands
/// (issue #70).
///
/// The whole of what [VAlbumClient.uploadNew] reports: which phase is running,
/// how many images of how many have arrived, and the [fraction] the wheel
/// shows. The batches the transfer is cut into are transport and are never
/// named here, see [UploadBatching].
class UploadProgress {
  /// What is running, see [UploadPhase].
  final UploadPhase phase;

  /// The images the server has *confirmed* — whole batches that answered.
  ///
  /// During [UploadPhase.preparing] this is the image being hashed instead:
  /// that phase counts its way through the picked files and there is nothing
  /// on the server yet.
  final int imagesDone;

  /// How many images are being uploaded.
  ///
  /// The number picked until the server has said which of them are new; from
  /// then on the number that is actually transferred, so that the count the
  /// person reads is the count that will arrive.
  final int imagesTotal;

  /// The transfer progress the wheel shows, in `[0, 1]`.
  ///
  /// It may move within a batch — the bytes of that batch mapped onto its
  /// images, so that a single large batch still shows a moving wheel — but
  /// never past what [imagesDone] will be once the batch answers, and never
  /// 1.0 before the last answer has arrived: the dialog is closed by the code,
  /// not by the value, see issue #59.
  final double fraction;

  const UploadProgress({
    required this.phase,
    required this.imagesDone,
    required this.imagesTotal,
    this.fraction = 0,
  });

  /// What the dialog shows before the upload has reported anything.
  factory UploadProgress.start(int images) => UploadProgress(
        phase: UploadPhase.preparing,
        imagesDone: images > 0 ? 1 : 0,
        imagesTotal: images,
      );

  /// Whether the wheel can show a value, or has to spin.
  bool get determinate => phase == UploadPhase.transferring;

  /// [fraction] as whole percent, for the number inside the wheel.
  int get percent => (fraction * 100).round().clamp(0, 100);

  /// The one line the dialog shows, see [uploadImageCountMessage].
  String get line => switch (phase) {
        UploadPhase.preparing =>
          "Wird vorbereitet: $imagesDone von $imagesTotal...",
        UploadPhase.asking => uploadAskingMessage,
        UploadPhase.transferring =>
          uploadImageCountMessage(imagesDone, imagesTotal),
        UploadPhase.waiting => uploadWaitingMessage,
      };

  @override
  bool operator ==(Object other) =>
      other is UploadProgress &&
      other.phase == phase &&
      other.imagesDone == imagesDone &&
      other.imagesTotal == imagesTotal &&
      other.fraction == fraction;

  @override
  int get hashCode => Object.hash(phase, imagesDone, imagesTotal, fraction);

  @override
  String toString() =>
      "UploadProgress(${phase.name}, $imagesDone/$imagesTotal, $fraction)";
}

/// The greatest [UploadProgress.fraction] reported while an answer is still
/// outstanding, see [UploadProgress.fraction].
const double uploadProgressCeiling = 0.99;

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

  /// The name of the user this device is signed in as, empty for "the library
  /// owner" (who has no name of their own) and while nobody is signed in.
  ///
  /// It is not sent anywhere — the [token] identifies the caller — but it
  /// names the cached copies of this device, see [cacheUser], and it is who
  /// the share dialog leaves out of the list of people to share with.
  final String userName;

  /// What this app has already seen, `null` if nothing is cached.
  ///
  /// Every answer of the server is written through into it, and only a
  /// *transport* failure — the server cannot be reached at all — is answered
  /// from it, see [loadResource] and issue #31.
  final OfflineCache? cache;

  /// Told whenever a load fell back to the [cache], or reached the server
  /// again; the views show what it says.
  ///
  /// *Reached* is decided in one place for every request this client makes,
  /// see [_ObservedTransport]: an answer — any answer, a `401` included — is
  /// the server speaking, and only a transport failure is being offline.
  final OfflineState? offlineState;

  /// Where every request of this client is written down, `null` in a test
  /// that does not care (issue #58).
  final DiagnosticsLog? log;

  /// How long a request may take before the server counts as unreachable.
  ///
  /// Without it a phone with a captive portal or a half-open connection waits
  /// forever instead of showing what it has.
  final Duration timeout;

  /// The transport as it was handed in: the one the app shares between its
  /// clients, undecorated, see [httpClient].
  final http.Client _transport;

  /// The transport every request actually goes through, see
  /// [_ObservedTransport].
  late final http.Client _http = _ObservedTransport(
    _transport,
    offlineState: offlineState,
    log: log,
  );

  VAlbumClient({
    required this.dataUrl,
    this.token,
    this.userName = "",
    http.Client? httpClient,
    this.cache,
    this.offlineState,
    this.log,
    this.timeout = const Duration(seconds: 15),
  }) : _transport = httpClient ?? http.Client();

  /// The transport this client sends its requests over.
  ///
  /// The *undecorated* one: a client for another server is built over the same
  /// transport and puts its own observer around it, see [withDataUrl].
  http.Client get httpClient => _transport;

  /// A client talking to [dataUrl] over the transport of this one.
  ///
  /// Used when the user points the app at a different server, see
  /// `ServerSettings`: only the URL changes, the transport (a fake one, in a
  /// test) stays the same.
  VAlbumClient withDataUrl(String dataUrl) => VAlbumClient(
        dataUrl: dataUrl,
        token: token,
        userName: userName,
        httpClient: _transport,
        cache: cache,
        offlineState: offlineState,
        log: log,
        timeout: timeout,
      );

  /// The same client, identifying itself with the given token from now on.
  ///
  /// `null` drops the token: the client talks to the server anonymously again.
  VAlbumClient withToken(String? token, {String? userName}) => VAlbumClient(
        dataUrl: dataUrl,
        token: token,
        userName: userName ?? this.userName,
        httpClient: _transport,
        cache: cache,
        offlineState: offlineState,
        log: log,
        timeout: timeout,
      );

  /// The authorization header of every request, empty while unpaired.
  Map<String, String> get authHeaders {
    var value = token;
    return value == null || value.isEmpty
        ? const {}
        : {"Authorization": "Bearer $value"};
  }

  /// Who the cached copies of this device belong to, see
  /// [OfflineCache.resourceKey] (issue #49).
  ///
  /// The empty string while nobody is signed in, `"@<name>"` otherwise —
  /// `"@"` for the library owner, whose name the server leaves empty. The
  /// token's presence is what tells the two apart: the server calls the
  /// unnamed owner and an anonymous caller both `""`, but they are shown
  /// different libraries, so their copies must not share a key.
  String get cacheUser {
    var value = token;
    return value == null || value.isEmpty ? "" : "@$userName";
  }

  /// Client talking to the server this app was loaded from (on the web), or to
  /// the [defaultDataUrl] on all other platforms.
  factory VAlbumClient.fromOrigin({
    String? token,
    String userName = "",
    http.Client? httpClient,
    OfflineCache? cache,
    OfflineState? offlineState,
    DiagnosticsLog? log,
  }) =>
      VAlbumClient(
        dataUrl: deriveDataUrl(Uri.base, isWeb: kIsWeb),
        token: token,
        userName: userName,
        httpClient: httpClient,
        cache: cache,
        offlineState: offlineState,
        log: log,
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
  /// speaking: that refusal is reported, never masked by an older copy — and
  /// it clears the offline state, because the server *answered*; that happens
  /// for every request alike, see [_ObservedTransport] and issue #57.
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
    // Parsed before it is cached: an answer that is not album data must not
    // become the cached copy of this album.
    var resource = parseResource(response.body, uri);
    await cache?.putResource(dataUrl, path, response.body, user: cacheUser);
    return resource;
  }

  /// The URL delivering the JSON representation of [path] as a caller of the
  /// given clearance would receive it, see [loadPreview].
  ///
  /// `viewAs` can only *lower* the caller's own clearance, so it is safe for
  /// anyone to send; the server decides what it answers with.
  String previewUrl(List<String> path, String viewAs) =>
      "${jsonUrl(path)}&viewAs=$viewAs";

  /// Loads the resource at [path] as a caller of the clearance [viewAs]
  /// (`"members"` or `"public"`) would receive it, see issue #46.
  ///
  /// This is the album author's preview of what the family or a share link
  /// sees, and it deliberately does **not** touch the [cache]: the answer is a
  /// *smaller* album than the one this device is entitled to, and writing it
  /// through would make the next offline view of the album show the preview
  /// instead of the album. For the same reason nothing is served from the
  /// cache here — a preview that cannot be fetched is no preview, and the
  /// reason is reported instead.
  ///
  /// A server refusing the request (an unknown value, a caller it does not
  /// know) answers with its own reason, which is thrown as a
  /// [VAlbumException] like any other refusal.
  Future<Resource?> loadPreview(List<String> path, String viewAs) async {
    var uri = previewUrl(path, viewAs);
    if (kDebugMode) {
      print("Fetching preview: $uri");
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
      throw VAlbumException(
        "The server cannot be reached (${transportMessage(error)}), so there "
        "is nothing to preview.",
      );
    }
    if (response.statusCode != 200) {
      throw failure(response.statusCode, response.body, "loading '$uri'");
    }
    return parseResource(response.body, uri);
  }

  /// The resource in an answer of the server, never a raw parse failure.
  ///
  /// A status of 200 does not mean an album server answered: pointed at the
  /// wrong URL the app is served the web server's `index.html` (the album
  /// server answers every unknown extension-less path with it, so that deep
  /// links work), and the user then saw a `FormatException` quoting HTML. What
  /// this says instead names the address that answered and the remedy, see
  /// issue #35.
  static Resource parseResource(String body, String url) {
    Resource? resource;
    try {
      resource = Resource.read(JsonReader.fromString(body));
    } catch (_) {
      // Not album data at all; the message below says so.
    }
    if (resource == null) {
      throw notAlbumData(url);
    }
    return resource;
  }

  /// The failure of an answer that is not album data, see [parseResource].
  static VAlbumException notAlbumData(String url) => VAlbumException(
        "The server at '$url' did not answer with album data - not a VAlbum "
        "server, or is the server URL in the settings wrong?",
      );

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
    var entry = await cache?.getResource(dataUrl, path, user: cacheUser);
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
      var entry = await cache?.getThumbnail(url, user: cacheUser);
      if (entry == null) {
        rethrow;
      }
      return entry.bytes;
    }
    if (response.statusCode != 200) {
      throw failure(response.statusCode, response.body, "loading '$url'");
    }
    await cache?.putThumbnail(url, response.bodyBytes, user: cacheUser);
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

  /// Stores the given listing as the `index.json` sidecar of its own folder.
  ///
  /// The counterpart of [saveAlbum] for a folder of folders: its title and its
  /// placement rule, see issue #48. The derived [FolderInfo.effectiveDate] of
  /// the children travels along with what was loaded; the server drops it
  /// before it writes, so nothing derived is ever frozen into a sidecar.
  Future<void> saveListing(List<String> path, ListingInfo listing) =>
      putResource(folderUrl(path), listing);

  /// Creates the album [album] as a new folder named [AlbumInfo.path] below
  /// the folder at [folderPath].
  ///
  /// The folder that is asked for is not necessarily the folder the album ends
  /// up in: a placement rule on the folder above files the album into its year
  /// (or month) folder, see issue #48. The answer therefore says where the
  /// album landed — the [CreateResult.path] is relative to the root of the
  /// caller's space, and [CreateResult.message] says why it is not where it
  /// was asked for.
  ///
  /// A server that answers with an empty body (or with something that is no
  /// [CreateResult]) is one from before issue #48: it created the album
  /// exactly where it was asked to, so that is what is answered.
  Future<CreateResult> createAlbum(
    List<String> folderPath,
    AlbumInfo album,
  ) async {
    var path = [...folderPath, album.path];
    var asked = path.join("/");
    var url = folderUrl(path);

    var response = await _http.put(
      Uri.parse(url),
      encoding: Encoding.getByName("utf-8"),
      body: album.toString(),
      headers: {"Content-Type": "application/json", ...authHeaders},
    );
    if (response.statusCode >= 300) {
      throw failure(response.statusCode, response.body, "creating '$asked'");
    }

    if (response.body.trim().isEmpty) {
      return CreateResult(path: asked);
    }
    CreateResult result;
    try {
      result = CreateResult.read(JsonReader.fromString(response.body));
    } catch (_) {
      // Not a CreateResult: an older server answering something else.
      return CreateResult(path: asked);
    }
    return result.path.isEmpty ? CreateResult(path: asked) : result;
  }

  /// Uploads the given files to the resource at the given URL.
  ///
  /// Reports the progress of the *transfer* in percent to [onProgress]: the
  /// body is handed to the transport as a stream the transport itself pulls,
  /// and a byte is counted when it is pulled, not when it was read from the
  /// phone's storage, see [_CountingRequest] and issue #59. On a link slower
  /// than the disk the two are worlds apart, and the number the user was shown
  /// used to be the faster of them.
  ///
  /// The upload stops as soon as [handle] is cancelled: the body stream fails,
  /// the request fails with it, and this throws — a cancelled upload never
  /// answers an [UploadResult], whatever the transport made of the truncated
  /// body.
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

    // Finalized *before* the headers are copied: `MultipartRequest` writes its
    // `content-type` (with the boundary of the body it is about to produce)
    // only in `finalize()`. Copying the headers first sent a multipart body
    // without a content type, which the server refused with 415, see issue
    // #35.
    var multipartBody = multipart.finalize();

    var request = _CountingRequest(
      "PUT",
      uri,
      multipartBody,
      handle: handle,
      onTransferred: (transferred) => onProgress?.call(
        contentLength <= 0 ? 100 : (100 * transferred / contentLength).round(),
      ),
    );
    request.headers.addAll(multipart.headers);
    request.headers.addAll(authHeaders);
    request.contentLength = contentLength;

    http.StreamedResponse response;
    String body;
    try {
      response = await _http.send(request);
      body = await response.stream.bytesToString();
    } catch (error) {
      // A cancelled upload fails the body stream; whatever the transport made
      // of that is not worth quoting, the user knows what they did.
      if (handle != null && handle.cancelled) {
        throw const VAlbumException(uploadCancelledMessage);
      }
      rethrow;
    }
    // Checked again: a transport that buffers the whole body (a test double,
    // a proxy) can answer a request that was cancelled halfway, and a
    // cancelled upload must never be reported as a success.
    if (handle != null && handle.cancelled) {
      throw const VAlbumException(uploadCancelledMessage);
    }
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

  /// Moves the named entries of the folder at [path] into [target].
  ///
  /// [target] is a folder path relative to the root of the caller's space,
  /// the empty string being the root itself; [names] are entries of the
  /// folder at [path] — image files, or the names of sub-folders (albums and
  /// folders of folders). Naming the representative of a group moves the
  /// whole group, see issue #47.
  ///
  /// The answer carries one [MoveOutcome] per name: the name the entry has at
  /// the target now, or the reason it stayed where it was. A refusal of the
  /// whole request — an unpaired device, a target outside the space — is the
  /// server speaking and is thrown as a [VAlbumException] carrying its
  /// message, like every other refused write.
  Future<MoveResult> move(
    List<String> path,
    String target,
    List<String> names,
  ) async {
    var url = "${folderUrl(path)}?action=move";
    var request = MoveRequest(
      target: target,
      names: [for (var name in names) MoveName(name: name)],
    );
    var body = StringBuffer();
    request.writeContent(jsonStringWriter(body));

    var response = await _http.post(
      Uri.parse(url),
      encoding: Encoding.getByName("utf-8"),
      body: body.toString(),
      headers: {"Content-Type": "application/json", ...authHeaders},
    );
    if (response.statusCode >= 300) {
      throw failure(response.statusCode, response.body, "moving to '$target'");
    }
    return MoveResult.read(JsonReader.fromString(response.body));
  }

  /// Removes the named link entries from the folder at [path], see issue #50.
  ///
  /// The same body as a [move] with no target: a link is an entry of a folder
  /// like any other, and the answer carries one [MoveOutcome] per name — an
  /// empty message for the entry that is gone, the server's own reason for the
  /// one that stayed. Only the entry is removed; the album it points at stays
  /// with its owner, and the server remembers the refusal so that the link is
  /// not materialised again.
  ///
  /// A refusal of the whole request — a caller without `edit` on the folder,
  /// a name that is no link — is the server speaking and is thrown as a
  /// [VAlbumException], like every other refused write.
  Future<MoveResult> unlink(List<String> path, List<String> names) async {
    var url = "${folderUrl(path)}?action=unlink";
    var request = MoveRequest(
      target: "",
      names: [for (var name in names) MoveName(name: name)],
    );
    var body = StringBuffer();
    request.writeContent(jsonStringWriter(body));

    var response = await _http.post(
      Uri.parse(url),
      encoding: Encoding.getByName("utf-8"),
      body: body.toString(),
      headers: {"Content-Type": "application/json", ...authHeaders},
    );
    if (response.statusCode >= 300) {
      throw failure(
        response.statusCode,
        response.body,
        "removing from '${path.join("/")}'",
      );
    }
    return MoveResult.read(JsonReader.fromString(response.body));
  }

  /// Applies the placement rule of the folder at [path] to what is already in
  /// it, see issue #48.
  ///
  /// The rule places, it does not police: an album that was filed by hand
  /// stays where it was put until this is asked for. The answer carries one
  /// [MoveOutcome] per direct child album — the path it was filed to, or the
  /// reason it stayed. A refusal of the whole request is the server speaking
  /// and is thrown as a [VAlbumException], like every other refused write.
  Future<MoveResult> place(List<String> path) async {
    var url = "${folderUrl(path)}?action=place";
    var response = await _http.post(
      Uri.parse(url),
      encoding: Encoding.getByName("utf-8"),
      headers: {"Content-Type": "application/json", ...authHeaders},
    );
    if (response.statusCode >= 300) {
      throw failure(
        response.statusCode,
        response.body,
        "filing in '${path.join("/")}'",
      );
    }
    return MoveResult.read(JsonReader.fromString(response.body));
  }

  /// Uploads to the album at [path] what it does not hold yet.
  ///
  /// Every file is hashed, the album is asked which of the contents it already
  /// has, and only the rest is transferred — a sync that is interrupted and
  /// retried therefore uploads only what is missing, and never duplicates a
  /// photo. The upload itself is idempotent as well, so a server that cannot
  /// answer the question is simply sent everything.
  ///
  /// The transfer itself goes out in batches of at most [UploadBatching.maxFiles]
  /// files and [UploadBatching.maxBytes] bytes (issue #63): one long socket for
  /// a hundred photos is what a locked screen kills, and a batch that arrived
  /// stays arrived. A batch that fails after others succeeded throws an
  /// [UploadInterrupted] saying how much is on the server and how much is not.
  ///
  /// [onProgress] reports one [UploadProgress] per step: the phase, how many
  /// images the server has confirmed, and the fraction the wheel shows
  /// (issue #70). One measurement, images — the batches are transport and are
  /// never reported. Hashing a hundred photos on a phone takes long enough
  /// that a dialog showing nothing at all looks hung, so the preparing counts
  /// itself out (issue #59); a caller that does not care leaves [onProgress]
  /// out, as the camera-roll sync does, which reports its own progress over
  /// its own batches, see `camera_roll.dart`.
  Future<UploadSummary> uploadNew(
    List<String> path,
    List<UploadFile> files, {
    void Function(UploadProgress progress)? onProgress,
    UploadHandle? handle,
    UploadBatching batching = UploadBatching.standard,
  }) async {
    if (files.isEmpty) {
      return const UploadSummary(stored: 0, present: 0);
    }

    var hashed = <UploadFile>[];
    for (var file in files) {
      onProgress?.call(UploadProgress(
        phase: UploadPhase.preparing,
        imagesDone: hashed.length + 1,
        imagesTotal: files.length,
      ));
      hashed.add(
        file.sha256 != null
            ? file
            : file.withHash(await sha256Of(file.openRead())),
      );
    }
    onProgress?.call(UploadProgress(
      phase: UploadPhase.asking,
      imagesDone: 0,
      imagesTotal: files.length,
    ));

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
      // Nothing to transfer: everything the person picked is already there.
      // The wheel is full because the work is done, not because a value said
      // so — the caller closes the dialog, see issue #59.
      onProgress?.call(const UploadProgress(
        phase: UploadPhase.transferring,
        imagesDone: 0,
        imagesTotal: 0,
        fraction: 1,
      ));
      return UploadSummary(stored: 0, present: skipped);
    }

    var batches = batching.split(pending, (file) => file.length);

    var stored = 0;
    var present = skipped;
    var sentFiles = 0;
    var images = pending.length;
    var confirmed = 0;
    var reported = -1.0;
    // The fraction never runs backwards, and it reaches 1.0 exactly once, when
    // the last batch has answered: everything before that is capped at
    // [uploadProgressCeiling], because an answer is still outstanding.
    void report(UploadPhase phase, double fraction, {bool finished = false}) {
      var value = finished
          ? fraction.clamp(0.0, 1.0)
          : fraction.clamp(0.0, uploadProgressCeiling);
      if (value < reported) {
        value = reported;
      }
      reported = value;
      onProgress?.call(UploadProgress(
        phase: phase,
        imagesDone: confirmed,
        imagesTotal: images,
        fraction: value,
      ));
    }

    for (var index = 0; index < batches.length; index++) {
      var batch = batches[index];
      // The images of this batch are what its byte progress may advance the
      // wheel by — never further: what the wheel shows is bounded by what
      // [UploadProgress.imagesDone] becomes once this batch answers.
      var batchImages = batch.length;
      var last = index == batches.length - 1;
      report(UploadPhase.transferring, confirmed / images);

      UploadResult result;
      try {
        result = await uploadFiles(
          folderUrl(path),
          batch,
          onProgress: (percent) {
            var within = (confirmed + batchImages * percent / 100) / images;
            // The body of the *last* batch being out is the one wait the
            // person is told about, see [uploadWaitingMessage] and issue #59;
            // between batches the image count stays on the screen.
            report(
              last && percent >= 100
                  ? UploadPhase.waiting
                  : UploadPhase.transferring,
              within,
            );
          },
          handle: handle,
        );
      } catch (error) {
        if (handle != null && handle.cancelled) {
          // A cancellation speaks for itself, and it says so in the words of
          // the button that was pressed.
          rethrow;
        }
        if (sentFiles == 0) {
          // Nothing arrived, so nothing is partial: a refusal is the server
          // speaking and a transport failure is the caller's own story to
          // tell, exactly as before the batches existed.
          rethrow;
        }
        var summary = UploadSummary(
          stored: stored,
          present: present,
          remaining: pending.length - sentFiles,
        );
        throw UploadInterrupted(
          summary: summary,
          cause: error,
          message: interruptedUploadMessage(
            cause: _uploadFailureCause(error),
            onServer: summary.onServer,
            total: summary.total,
            remaining: summary.remaining,
          ),
        );
      }

      var batchStored =
          result.files.where((file) => file.status != uploadPresent).length;
      stored += batchStored;
      present += result.files.length - batchStored;
      sentFiles += batch.length;
      // Only now, with the answer in hand, are these images on the server.
      confirmed += batchImages;
      report(
        UploadPhase.transferring,
        confirmed / images,
        finished: last,
      );
    }
    return UploadSummary(stored: stored, present: present);
  }

  /// What an upload failure is called in [interruptedUploadMessage].
  ///
  /// A transport failure is a lost connection, whatever the socket layer calls
  /// it; a refusal is the server speaking, and the server's own sentence is
  /// what the user reads — without its full stop, because the message
  /// continues.
  static String _uploadFailureCause(Object error) {
    if (isTransportFailure(error)) {
      return uploadConnectionLost;
    }
    var message =
        error is VAlbumException ? error.message : error.toString().trim();
    message = message.trim();
    while (message.endsWith(".") || message.endsWith("!")) {
      message = message.substring(0, message.length - 1).trimRight();
    }
    return message.isEmpty ? uploadConnectionLost : message;
  }

  /// The server's reason for refusing the original at [imageUrl], `null` if
  /// there is none.
  ///
  /// The viewer displays the original through `Image.network`, which opens a
  /// connection of its own and can only report *that* the picture did not
  /// load, never why. When it fails, the viewer asks this, which fetches the
  /// same URL through this client and reads the [ErrorInfo] of a refusal — a
  /// `view`-only grant answers 403 with a message meant for the user, see
  /// issue #49, and that message belongs on the screen in place of the
  /// picture.
  ///
  /// Answers `null` where the server did not refuse at all (the picture failed
  /// for another reason) and where it cannot be reached: neither is a refusal
  /// to quote.
  Future<String?> originalRefusal(String imageUrl) async {
    try {
      var response = await _http
          .get(Uri.parse(originalUrl(imageUrl)), headers: authHeaders)
          .timeout(timeout);
      if (response.statusCode < 300) {
        return null;
      }
      return failure(response.statusCode, response.body, "loading the image")
          .message;
    } catch (_) {
      return null;
    }
  }

  /// The grants covering the folder at [path], the nearest one first
  /// (issue #49).
  ///
  /// Answerable only for the owner of the space the folder lies in (and for
  /// the administrator): who else was let in is nobody else's business. Every
  /// other caller is refused with a 403, which is how the app decides not to
  /// offer "Share with…" at all, see `share_view.dart`.
  Future<GrantList> grants(List<String> path) async {
    var url = "${folderUrl(path)}?type=grants";
    var response = await _http.get(Uri.parse(url), headers: authHeaders);
    if (response.statusCode >= 300) {
      throw failure(response.statusCode, response.body, "asking '$url'");
    }
    return GrantList.read(JsonReader.fromString(response.body));
  }

  /// Grants [grant] on the folder at [path], replacing an earlier grant to the
  /// same subject.
  ///
  /// The owner and the path of the grant come from the URL; only its
  /// [Grant.subject] and [Grant.rights] are read from the body.
  Future<GrantList> grant(List<String> path, Grant grant) =>
      _postGrant(path, "grant", grant);

  /// Removes the grant to [subject] on the folder at [path].
  Future<GrantList> revoke(List<String> path, String subject) =>
      _postGrant(path, "revoke", Grant(subject: subject));

  Future<GrantList> _postGrant(
    List<String> path,
    String action,
    Grant grant,
  ) async {
    var url = "${folderUrl(path)}?action=$action";
    var body = StringBuffer();
    grant.writeContent(jsonStringWriter(body));

    var response = await _http.post(
      Uri.parse(url),
      encoding: Encoding.getByName("utf-8"),
      body: body.toString(),
      headers: {"Content-Type": "application/json", ...authHeaders},
    );
    if (response.statusCode >= 300) {
      throw failure(response.statusCode, response.body, "asking '$url'");
    }
    return GrantList.read(JsonReader.fromString(response.body));
  }

  /// The share links covering the folder at [path], the nearest one first
  /// (issue #51).
  ///
  /// Answerable only for the owner of the space the folder lies in (and for
  /// the administrator), exactly as for the grants — and no answer ever
  /// carries a token: the token is shown once, when the link is made.
  Future<ShareLinkList> shares(List<String> path) async {
    var url = "${folderUrl(path)}?type=shares";
    var response = await _http.get(Uri.parse(url), headers: authHeaders);
    if (response.statusCode >= 300) {
      throw failure(response.statusCode, response.body, "asking '$url'");
    }
    return ShareLinkList.read(JsonReader.fromString(response.body));
  }

  /// Creates a share link on the folder at [path], answering it **with** its
  /// token (issue #51).
  ///
  /// The owner and the path of the link come from the URL; the body carries
  /// the label, the expiry, the privacy ceiling, the rating floor and the
  /// rights. The token travels back exactly once — the server keeps its hash
  /// and can never show it again, so what this answers is shown to the user
  /// there and then and stored nowhere.
  Future<ShareLinkCreated> share(List<String> path, ShareLink link) async {
    var url = "${folderUrl(path)}?action=share";
    var response = await _postJson(url, link);
    return ShareLinkCreated.read(JsonReader.fromString(response));
  }

  /// Withdraws the share link of the given id from the folder at [path].
  ///
  /// Only the id travels: the server knows the rest, and refuses an id that
  /// is not a link covering this folder with a 404.
  Future<ShareLinkList> unshare(List<String> path, String id) async {
    var url = "${folderUrl(path)}?action=unshare";
    var response = await _postJson(url, ShareLink(id: id));
    return ShareLinkList.read(JsonReader.fromString(response));
  }

  /// Posts the given share link as JSON, answering the body of the answer.
  Future<String> _postJson(String url, ShareLink value) =>
      _postBody(url, _jsonOf(value.writeContent));

  /// Posts the given invitation as JSON, answering the body of the answer.
  Future<String> _postInvitation(String url, Invitation value) =>
      _postBody(url, _jsonOf(value.writeContent));

  /// The JSON a model object writes, as a string.
  static String _jsonOf(void Function(JsonSink) write) {
    var body = StringBuffer();
    write(jsonStringWriter(body));
    return body.toString();
  }

  /// Posts [body] as JSON, answering the body of the answer.
  Future<String> _postBody(String url, String body) async {
    var response = await _http.post(
      Uri.parse(url),
      encoding: Encoding.getByName("utf-8"),
      body: body,
      headers: {"Content-Type": "application/json", ...authHeaders},
    );
    if (response.statusCode >= 300) {
      throw failure(response.statusCode, response.body, "asking '$url'");
    }
    return response.body;
  }

  /// The groups this caller owns and the groups they are in (issue #49).
  Future<GroupList> groups() async {
    var url = "${folderUrl(const [])}?type=groups";
    var response = await _http.get(Uri.parse(url), headers: authHeaders);
    if (response.statusCode >= 300) {
      throw failure(response.statusCode, response.body, "asking '$url'");
    }
    return GroupList.read(JsonReader.fromString(response.body));
  }

  /// Renames the group [name] to [newName] (issue #55).
  ///
  /// A rename is its own request because it is more than a new name: the
  /// server rewrites every grant made out to the group in the same step, so
  /// that nothing that was shared with it stops working. Only its owner and
  /// the administrator may, and a name that is a user's name is refused with
  /// the server's own sentence.
  Future<GroupList> renameGroup(String name, String newName) async {
    var url = "${folderUrl(const [])}?action=regroup";
    var response = await _postBody(
      url,
      _jsonOf(GroupRename(name: name, newName: newName).writeContent),
    );
    return GroupList.read(JsonReader.fromString(response));
  }

  /// Creates [group], or replaces the members of one the caller owns.
  Future<GroupList> saveGroup(Group group) => _postGroup("group", group);

  /// Removes the group of the given name, which the caller must own.
  Future<GroupList> removeGroup(String name) =>
      _postGroup("ungroup", Group(name: name));

  Future<GroupList> _postGroup(String action, Group group) async {
    var url = "${folderUrl(const [])}?action=$action";
    var body = StringBuffer();
    group.writeContent(jsonStringWriter(body));

    var response = await _http.post(
      Uri.parse(url),
      encoding: Encoding.getByName("utf-8"),
      body: body.toString(),
      headers: {"Content-Type": "application/json", ...authHeaders},
    );
    if (response.statusCode >= 300) {
      throw failure(response.statusCode, response.body, "asking '$url'");
    }
    return GroupList.read(JsonReader.fromString(response.body));
  }

  /// The devices this caller is signed in on (issue #55).
  ///
  /// Always the caller's *own* devices: the administrator manages the users of
  /// this server, not other people's phones. A share link is refused with a
  /// 403, an anonymous caller and an invitation bearer with a 401 — each
  /// carrying the server's own sentence.
  Future<DeviceList> devices() async {
    var url = "${folderUrl(const [])}?type=devices";
    var response = await _http.get(Uri.parse(url), headers: authHeaders);
    if (response.statusCode >= 300) {
      throw failure(response.statusCode, response.body, "asking '$url'");
    }
    return DeviceList.read(JsonReader.fromString(response.body));
  }

  /// Signs the device of the given id out, answering the ones that remain.
  ///
  /// Only the id travels: the server knows the rest, and answers a device that
  /// is not one of the caller's own with a 404. Naming the *asking* device is
  /// allowed and is how a device signs itself out for good — the token this
  /// client holds proves nothing from then on, so the caller drops it, see
  /// `ServerSettings.signOut`.
  Future<DeviceList> unpair(String id) async {
    var url = "${folderUrl(const [])}?action=unpair";
    var response = await _postBody(
      url,
      _jsonOf(DeviceEntry(id: id).writeContent),
    );
    return DeviceList.read(JsonReader.fromString(response));
  }

  /// A code to type on a further device of one's own (issue #65).
  ///
  /// Adding a device is not inviting somebody, and the wire says so: the
  /// answer is eight characters and an expiry, never a link and never a token
  /// that is a bearer anywhere. Whoever types it is signed in *as this
  /// caller*, so the code lives ten minutes, works once, and the device it
  /// pairs shows up in [devices] at once, where it can be signed out again.
  ///
  /// The request needs no body: whom the code signs in and which device asked
  /// the server reads from this client's token. Refused with the server's own
  /// sentence for a share link (403) and for a caller that is no device (401).
  Future<DeviceCodeCreated> deviceCode() async {
    var url = "${folderUrl(const [])}?action=device-code";
    var response = await _postBody(url, "{}");
    return DeviceCodeCreated.read(JsonReader.fromString(response));
  }

  /// The names and roles of the users of this server (issue #49).
  ///
  /// Needed to share: a member picks whom to grant something to. A guest and
  /// an anonymous caller are refused, and the share dialog then offers the
  /// groups and "everybody" alone.
  Future<UserList> users() async {
    var url = "${folderUrl(const [])}?type=users";
    var response = await _http.get(Uri.parse(url), headers: authHeaders);
    if (response.statusCode >= 300) {
      throw failure(response.statusCode, response.body, "asking '$url'");
    }
    return UserList.read(JsonReader.fromString(response.body));
  }

  /// Issues an invitation, answering its token exactly once (issue #52).
  ///
  /// An invitation creates a *user*, so it names no path and is asked of the
  /// data root: the body says only what the accepting person becomes — the
  /// [Invitation.role] (`member` or `guest`), an [Invitation.note] for the
  /// inviter's own list and the [Invitation.expires] instant. The answer
  /// carries the token and the URL the app is served under for it; the server
  /// keeps only a hash and can never show either again.
  ///
  /// Refused with the server's own reason for a guest, and for a member on a
  /// server started with `--invite admin`.
  Future<InvitationCreated> invite(Invitation invitation) async {
    var url = "${folderUrl(const [])}?action=invite";
    var response = await _postInvitation(url, invitation);
    return InvitationCreated.read(JsonReader.fromString(response));
  }

  /// The invitations this caller issued, the admin's being all of them.
  ///
  /// What the open-invitations section of the settings shows, see issue #55;
  /// [uninvite] answers the same list.
  Future<InvitationList> invitations() async {
    var url = "${folderUrl(const [])}?type=invitations";
    var response = await _http.get(Uri.parse(url), headers: authHeaders);
    if (response.statusCode >= 300) {
      throw failure(response.statusCode, response.body, "asking '$url'");
    }
    return InvitationList.read(JsonReader.fromString(response.body));
  }

  /// Withdraws the invitation of the given id.
  ///
  /// The record is kept and marked withdrawn — a user it already created is
  /// untouched — and the token is refused from then on. Only the issuer and
  /// the admin may; anybody else is told that there is no such invitation.
  Future<InvitationList> uninvite(String id) async {
    var url = "${folderUrl(const [])}?action=uninvite";
    var response = await _postInvitation(url, Invitation(id: id));
    return InvitationList.read(JsonReader.fromString(response));
  }

  /// Turns the guest of the given name into a member (issue #52).
  ///
  /// One rename on the server: the guest's root becomes their space, the links
  /// in it already in place. Only the admin may, and only a guest becomes a
  /// member — there is no way back, and the users section of the settings asks
  /// before it does it, see issue #55.
  Future<UserEntry> promote(String name) async {
    var url = "${folderUrl(const [])}?action=promote";
    var body = StringBuffer();
    MemberName(name: name).writeContent(jsonStringWriter(body));
    var response = await _postBody(url, body.toString());
    return UserEntry.read(JsonReader.fromString(response));
  }

  /// Signs in on this device, returning the token the server issued.
  ///
  /// The token is what makes the app a known caller; store it with the server
  /// settings and hand it to [withToken]. Throws a [VAlbumException] carrying
  /// the server's reason when the secret is wrong or the name is not the one
  /// the server holds.
  ///
  /// [userName] names the user signing in and is empty for "the library
  /// owner": the pairing secret signs the owner in, and the first sign-in that
  /// carries a name gives the owner that name (issue #45). The wire action is
  /// still the pairing request of issue #28, so an older server simply ignores
  /// the name.
  ///
  /// [invitation] is the other way in (issue #52): accepting an invitation
  /// *is* pairing, so a live invitation token takes the place of the [secret]
  /// and the [userName] is then the name of the user to create — required, and
  /// refused where it is taken. The request carries one or the other; a
  /// request carrying both is read by the server as an invitation.
  ///
  /// [deviceCode] is the third way in (issue #65) and the one that creates
  /// nobody: a code shown on a device that is already signed in adds a
  /// *further device of the same user*, so the [userName] is a check and not a
  /// choice there — the server refuses a name that is not the code's user. The
  /// [secret] stays empty with it.
  ///
  /// Never carries the device's own token: a sign-in is how a device *gets*
  /// one, and an invitation token is a bearer for nothing but `?type=auth`.
  Future<PairResponse> pair({
    String secret = "",
    required String deviceName,
    String userName = "",
    String invitation = "",
    String deviceCode = "",
  }) async {
    var url = "${folderUrl(const [])}?action=pair";
    var request = PairRequest(
      secret: secret,
      deviceName: deviceName,
      userName: userName,
      invitation: invitation,
      deviceCode: deviceCode,
    );
    var body = StringBuffer();
    request.writeContent(jsonStringWriter(body));

    var response = await _http.post(
      Uri.parse(url),
      encoding: Encoding.getByName("utf-8"),
      body: body.toString(),
      headers: const {"Content-Type": "application/json"},
    );
    if (response.statusCode >= 300) {
      throw failure(response.statusCode, response.body, "signing in at '$url'");
    }
    return PairResponse.read(JsonReader.fromString(response.body));
  }

  /// What this client is allowed to do on the server, and as whom.
  ///
  /// Answered by every server, signed in or not: this is how the app learns
  /// that it must sign in before it can change anything, and who this device
  /// is signed in as (issue #45).
  Future<AuthInfo> authInfo() async {
    var url = "${folderUrl(const [])}?type=auth";
    var response = await _http.get(Uri.parse(url), headers: authHeaders);
    if (response.statusCode >= 300) {
      throw failure(response.statusCode, response.body, "asking '$url'");
    }
    // The answer an unknown path is served with is HTML, not auth data, see
    // [parseResource]: that is the wrong server URL speaking, not a bug.
    try {
      return AuthInfo.read(JsonReader.fromString(response.body));
    } catch (_) {
      throw notAlbumData(url);
    }
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
  void close() => _transport.close();
}

/// A request whose body is counted as the *transport* pulls it.
///
/// The point of issue #59: `http.StreamedRequest` hands the body to an
/// unbounded `StreamController`, so pumping the multipart body into its sink
/// measures how fast the phone reads its own storage, not how fast the bytes
/// leave the device. `IOClient` drains the request body with
/// `addStream(request.finalize())`, so a request that *is* its own body stream
/// is pulled at the pace of the socket: every chunk handed out has been asked
/// for by the transport, and the socket's own buffer is the only slack left.
///
/// Cancellation lives here too: the mapped stream fails as soon as the
/// [UploadHandle] is cancelled, which fails the request rather than quietly
/// truncating a body whose `content-length` promised more.
class _CountingRequest extends http.BaseRequest {
  /// The body, not yet pulled.
  final http.ByteStream _body;

  /// Told the running number of bytes the transport has pulled.
  final void Function(int transferred) _onTransferred;

  /// Watched before every chunk, `null` for an upload that cannot be
  /// cancelled.
  final UploadHandle? _handle;

  _CountingRequest(
    super.method,
    super.url,
    this._body, {
    required void Function(int transferred) onTransferred,
    UploadHandle? handle,
  })  : _onTransferred = onTransferred,
        _handle = handle;

  @override
  http.ByteStream finalize() {
    super.finalize();
    var transferred = 0;
    return http.ByteStream(
      _body.map((chunk) {
        var handle = _handle;
        if (handle != null && handle.cancelled) {
          throw const _UploadCancelled();
        }
        transferred += chunk.length;
        _onTransferred(transferred);
        return chunk;
      }),
    );
  }
}

/// Thrown into the body stream of a cancelled upload, see [_CountingRequest].
///
/// It never reaches a caller: the transport turns it into a failure of the
/// request, and [VAlbumClient.uploadFiles] answers the cancellation with
/// [uploadCancelledMessage].
class _UploadCancelled implements Exception {
  const _UploadCancelled();

  @override
  String toString() => uploadCancelledMessage;
}

/// The one place every request of a [VAlbumClient] passes through.
///
/// `http.Client.get`, `post`, `put` and the streamed upload all end in
/// [http.BaseClient.send], so a decorator around the transport sees every
/// request the app makes — which is what two rules of issue #57 and issue #58
/// need to hold *generally*:
///
///  * an answer, whatever its status, means the server was reached, so the
///    offline state is cleared; only a transport failure leaves it alone (and
///    the load that fell back on a cached copy sets it, see
///    [VAlbumClient._cachedResource]);
///  * every request is written into the [DiagnosticsLog] with its method, its
///    URL and either the status or the *complete* text of the failure.
///
/// The alternative — touching both rules into each of the twenty-odd request
/// methods — is what let the lock-out of issue #57 exist in the first place:
/// [VAlbumClient.loadResource] was the only method that reported having
/// reached the server, and it reported it only for a `200`.
///
/// Nothing secret passes: no body is ever logged, and of the `Authorization`
/// header only whether there was one.
class _ObservedTransport extends http.BaseClient {
  final http.Client _inner;
  final OfflineState? offlineState;
  final DiagnosticsLog? log;

  _ObservedTransport(this._inner, {this.offlineState, this.log});

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    var bearer = request.headers.containsKey("Authorization");
    var method = request.method;
    var url = request.url.toString();
    http.StreamedResponse response;
    try {
      response = await _inner.send(request);
    } catch (error) {
      log?.failed(method, url, error: error, bearer: bearer);
      rethrow;
    }
    // The server spoke; whether it liked the request is the caller's business.
    offlineState?.online();
    log?.answered(method, url, status: response.statusCode, bearer: bearer);
    return response;
  }

  @override
  void close() => _inner.close();
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
