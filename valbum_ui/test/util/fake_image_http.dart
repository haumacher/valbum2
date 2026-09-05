import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

/// A 1x1 pixel PNG served for every image request made in a widget test.
final Uint8List transparentPixelPng = base64Decode(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4//8/"
  "AAX+Av4N70a4AAAAAElFTkSuQmCC",
);

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
  Future<HttpClientRequest> getUrl(Uri url) async => _FakeHttpClientRequest();

  @override
  Future<HttpClientRequest> openUrl(String method, Uri url) async =>
      _FakeHttpClientRequest();

  @override
  dynamic noSuchMethod(Invocation invocation) => null;
}

class _FakeHttpClientRequest implements HttpClientRequest {
  @override
  final HttpHeaders headers = _FakeHttpHeaders();

  @override
  Future<HttpClientResponse> close() async => _FakeHttpClientResponse();

  @override
  dynamic noSuchMethod(Invocation invocation) => null;
}

class _FakeHttpClientResponse implements HttpClientResponse {
  @override
  int get statusCode => HttpStatus.ok;

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
