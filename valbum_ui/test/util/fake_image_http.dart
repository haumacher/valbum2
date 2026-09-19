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

  /// Back to "every picture is a 1x1 PNG, and nobody is counting".
  static void reset() {
    requested = null;
    pending = null;
    failing = null;
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
/// viewer still reports (issue #95).
void fakeImageRequests({
  List<String>? requested,
  bool Function(Uri url)? pending,
  bool Function(Uri url)? failing,
}) {
  FakeImageHttp.requested = requested;
  FakeImageHttp.pending = pending;
  FakeImageHttp.failing = failing;
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
          : null,
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

  _FakeHttpClientRequest(this.pending, this.status);

  @override
  final HttpHeaders headers = _FakeHttpHeaders();

  @override
  Future<HttpClientResponse> close() {
    if (pending) {
      // Never answered: the picture stays on its way.
      return Completer<HttpClientResponse>().future;
    }
    return Future<HttpClientResponse>.value(_FakeHttpClientResponse(status));
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => null;
}

class _FakeHttpClientResponse implements HttpClientResponse {
  final int? status;

  _FakeHttpClientResponse(this.status);

  @override
  int get statusCode => status ?? HttpStatus.ok;

  @override
  int get contentLength => transparentPixelPng.length;

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
  String get reasonPhrase => "OK";

  @override
  StreamSubscription<List<int>> listen(
    void Function(List<int> event)? onData, {
    Function? onError,
    void Function()? onDone,
    bool? cancelOnError,
  }) =>
      Stream<List<int>>.value(transparentPixelPng).listen(
        onData,
        onError: onError,
        onDone: onDone,
        cancelOnError: cancelOnError,
      );

  @override
  dynamic noSuchMethod(Invocation invocation) => null;
}

class _FakeHttpHeaders implements HttpHeaders {
  @override
  dynamic noSuchMethod(Invocation invocation) => null;
}
