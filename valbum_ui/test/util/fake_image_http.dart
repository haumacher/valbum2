import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';

/// A 1x1 pixel PNG served for every image request made in a widget test.
final Uint8List transparentPixelPng = base64Decode(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4//8/"
  "AAX+Av4N70a4AAAAAElFTkSuQmCC",
);

/// What the fake answers, and what it records, see [fakeImageRequests].
///
/// Deliberately global and mutable: Flutter's [NetworkImage] keeps **one**
/// static [HttpClient], created the first time a picture is loaded and bound
/// to the [HttpOverrides] of the zone it was created in. A later zone never
/// reaches it, so a test that wants its own answers changes what that one
/// client does rather than trying to replace it. (The debug hook
/// `debugNetworkImageHttpClientProvider` would be the other way, but the test
/// binding refuses a painting debug variable left set by a test.)
class FakeImageHttp {
  /// Every URL asked for, where a test collects them.
  static List<String>? requested;

  /// Which URLs are never answered.
  static bool Function(Uri url)? pending;

  /// Which URLs are answered with a `500`.
  static bool Function(Uri url)? failing;

  /// Which URLs are answered with a `202` and an `ErrorInfo`: the server has
  /// not made the picture yet (issue #256).
  static bool Function(Uri url)? notReady;

  /// Which URLs are answered `200` with bytes no codec decodes: the head of a
  /// HEIF file, as a HEIC named `.jpg` arrives (issue #313).
  static bool Function(Uri url)? undecodable;

  /// Back to "every picture is a 1x1 PNG, and nobody is counting".
  static void reset() {
    requested = null;
    pending = null;
    failing = null;
    notReady = null;
    undecodable = null;
  }
}

/// Sets up what the originals of this test answer, see [FakeImageHttp].
///
/// [requested] collects every URL asked for — that is how a test sees what
/// the viewer fetched and what it prefetched (issue #101). A URL [pending]
/// answers `true` for is never answered at all — the picture stays on its
/// way, which is the state the viewer has to fill with the thumbnail instead
/// of an empty box. A URL [failing] answers `true` for is answered with a
/// `500`: the server could not deliver it, which is the one failure the
/// viewer still reports (issue #95). A URL [notReady] answers `true` for is
/// answered `202 Accepted` with an `ErrorInfo`, as the server answers a
/// picture its background has not made yet (issue #256) — no failure, the
/// placeholder or the thumbnail beneath stands in until a re-ask (issue
/// #258). The predicates are read at every request, so a test may change its
/// mind (the background made it) by changing what they answer. A URL
/// [undecodable] answers `true` for is answered `200` with the head of a HEIF
/// file ([heifBytes]), which the platform's codec cannot read (issue #313).
void fakeImageRequests({
  List<String>? requested,
  bool Function(Uri url)? pending,
  bool Function(Uri url)? failing,
  bool Function(Uri url)? notReady,
  bool Function(Uri url)? undecodable,
}) {
  FakeImageHttp.requested = requested;
  FakeImageHttp.pending = pending;
  FakeImageHttp.failing = failing;
  FakeImageHttp.notReady = notReady;
  FakeImageHttp.undecodable = undecodable;
  addTearDown(FakeImageHttp.reset);
}

/// Runs [body] with all `dart:io` HTTP traffic answered by a 1x1 PNG.
///
/// `Image.network` bypasses the app's injected [http.Client] (Flutter's
/// [NetworkImage] uses a `dart:io` [HttpClient] of its own), and the test
/// binding answers every real request with a 400. Overriding the ambient
/// [HttpClient] is therefore the only way to keep image loading deterministic
/// without changing the way the app renders images.
T withFakeImageHttp<T>(T Function() body) =>
    HttpOverrides.runZoned(body, createHttpClient: (_) => _FakeHttpClient());

class _FakeHttpClient implements HttpClient {
  @override
  bool autoUncompress = true;

  @override
  Future<HttpClientRequest> getUrl(Uri url) async => _open(url);

  @override
  Future<HttpClientRequest> openUrl(String method, Uri url) async => _open(url);

  _FakeHttpClientRequest _open(Uri url) {
    FakeImageHttp.requested?.add(url.toString());
    return _FakeHttpClientRequest(
      FakeImageHttp.pending?.call(url) ?? false,
      (FakeImageHttp.failing?.call(url) ?? false)
          ? HttpStatus.internalServerError
          : (FakeImageHttp.notReady?.call(url) ?? false)
              ? HttpStatus.accepted
              : null,
      undecodable: FakeImageHttp.undecodable?.call(url) ?? false,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => null;
}

class _FakeHttpClientRequest implements HttpClientRequest {
  /// Whether this request is never answered, see [withFakeImageHttp].
  final bool pending;

  /// The status to answer with, `null` for the 1x1 PNG.
  final int? status;

  /// Whether the answer is [heifBytes] rather than the 1x1 PNG.
  final bool undecodable;

  _FakeHttpClientRequest(this.pending, this.status, {this.undecodable = false});

  @override
  final HttpHeaders headers = _FakeHttpHeaders();

  @override
  Future<HttpClientResponse> close() {
    if (pending) {
      // Never answered: the picture stays on its way.
      return Completer<HttpClientResponse>().future;
    }
    return Future<HttpClientResponse>.value(
        _FakeHttpClientResponse(status, undecodable: undecodable));
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => null;
}

class _FakeHttpClientResponse implements HttpClientResponse {
  final int? status;

  /// Whether the body is [heifBytes] rather than the 1x1 PNG.
  final bool undecodable;

  _FakeHttpClientResponse(this.status, {this.undecodable = false});

  @override
  int get statusCode => status ?? HttpStatus.ok;

  /// What is sent: the 1x1 PNG, or the `ErrorInfo` of a `202`.
  Uint8List get _body => status == HttpStatus.accepted
      ? _notReadyBody
      : undecodable
          ? heifBytes
          : transparentPixelPng;

  @override
  int get contentLength => _body.length;

  @override
  HttpClientResponseCompressionState get compressionState =>
      HttpClientResponseCompressionState.notCompressed;

  @override
  HttpHeaders get headers => _FakeHttpHeaders();

  @override
  bool get isRedirect => false;

  @override
  bool get persistentConnection => false;

  @override
  String get reasonPhrase => status == HttpStatus.accepted ? "Accepted" : "OK";

  /// What `NetworkImage` does with an answer other than `200` before it
  /// throws its `NetworkImageLoadException`.
  @override
  Future<E> drain<E>([E? futureValue]) => Future<E>.value(futureValue as E);

  @override
  StreamSubscription<List<int>> listen(
    void Function(List<int> event)? onData, {
    Function? onError,
    void Function()? onDone,
    bool? cancelOnError,
  }) =>
      Stream<List<int>>.value(_body).listen(
        onData,
        onError: onError,
        onDone: onDone,
        cancelOnError: cancelOnError,
      );

  @override
  dynamic noSuchMethod(Invocation invocation) => null;
}

/// The head of a HEIF file (an ISO media `ftyp` box of the brand `heic`): what
/// a HEIC named `.jpg` sends, and what no codec of the test engine reads, see
/// [FakeImageHttp.undecodable].
final Uint8List heifBytes = Uint8List.fromList([
  0, 0, 0, 24, // box size
  ...ascii.encode("ftypheic"),
  0, 0, 0, 0, // minor version
  ...ascii.encode("mif1heic"),
  0, 0, 0, 8, ...ascii.encode("meta"),
]);

/// The answer of a picture the server has not made yet, see
/// [FakeImageHttp.notReady].
final Uint8List _notReadyBody = Uint8List.fromList(utf8.encode(
    '["ErrorInfo", {"message": "This picture is being prepared; please '
    'reload shortly."}]'));

class _FakeHttpHeaders implements HttpHeaders {
  @override
  dynamic noSuchMethod(Invocation invocation) => null;
}
