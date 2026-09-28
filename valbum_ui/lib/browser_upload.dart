/// The upload of a browser, which keeps the photo bytes out of Dart
/// (issue #170).
///
/// Imported by `platform_web.dart` only, so the other platforms never see it.
/// On the web the whole app runs on the page's one thread, and the upload used
/// to run through it byte for byte: `XFile.openRead` hands out a picked photo
/// as one chunk, so hashing it with `package:crypto` compiled to JavaScript was
/// one synchronous stretch per photo, and `BrowserClient` copied the whole
/// multipart body of a batch (up to `uploadBatchBytes`) into one `Uint8List`
/// before handing it to `XMLHttpRequest`. On a phone that blocked the page for
/// seconds at a time, which Android answers with "not responding" — and the
/// garbage of a hundred megabytes kept it coming back after the upload ended.
///
/// Here the browser does both jobs natively and asynchronously: the hash is
/// `crypto.subtle.digest` over the photo's `Blob` ([sha256OfBlob]), and a
/// batch goes out as a `FormData` of the picked `Blob`s over `XMLHttpRequest`
/// ([sendBlobForm]), so no byte of a photo is ever copied into the Dart heap
/// by the transfer.
///
/// `dart:js_interop_unsafe` rather than extension types, as in
/// `BlobDownloadSaver`: the app's language version predates them.
library;

import 'dart:async';
import 'dart:js_interop';
import 'dart:js_interop_unsafe';
import 'dart:typed_data';

import 'package:convert/convert.dart';
import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;

/// The largest file [sha256OfBlob] hands to Web Crypto in one piece.
///
/// `crypto.subtle.digest` has no streaming form, so it is given the whole
/// contents; a photo is a few megabytes, but a video picked in a browser could
/// be gigabytes, and that is hashed in [hashSliceBytes] slices instead.
const int webCryptoMaxBytes = 256 * 1024 * 1024;

/// The size of one slice of the fallback hash, see [sha256OfBlob].
///
/// Small enough that hashing one slice with `package:crypto` compiled to
/// JavaScript stays well below the 50 ms of a "long task" on a desktop, and
/// below the 200 ms that the upload of issue #170 was measured against on a
/// four times slower phone.
const int hashSliceBytes = 256 * 1024;

/// The `Blob` behind the object URL [url], `null` where it cannot be had.
///
/// `image_picker` hands a picked file out as an `XFile` whose path is an object
/// URL and keeps the `File` itself to itself; fetching the URL answers that
/// very `Blob` without reading it — the browser reads a `Blob` only when
/// somebody asks for its contents.
Future<Object?> blobOfObjectUrl(String url) async {
  if (!url.startsWith("blob:")) {
    return null;
  }
  try {
    var response =
        await globalContext.callMethod<JSPromise<JSObject>>("fetch".toJS, url.toJS).toDart;
    return await response.callMethod<JSPromise<JSObject>>("blob".toJS).toDart;
  } catch (_) {
    // A revoked URL, a browser that refuses to fetch it: the caller falls back
    // to reading the file through `XFile.openRead`, as before issue #170.
    return null;
  }
}

/// The SHA-256 hash of the contents of [blob], in lower-case hex — the very
/// string `sha256Of` answers for the same bytes.
///
/// Web Crypto where the page has it — native, asynchronous and off the page's
/// thread. `crypto.subtle` exists only in a *secure context*, though: a page
/// served over plain `http` from an address other than `localhost` (a server
/// on the home network, reached by its IP) has none, and then the contents are
/// hashed with `package:crypto` in slices of [hashSliceBytes], each read from
/// the `Blob` on its own and each followed by a return to the event loop, so
/// that no single task of the page runs long. [useWebCrypto] `false` forces
/// that path (a test compares the two).
Future<String> sha256OfBlob(Object blob, {bool useWebCrypto = true}) async {
  var file = blob as JSObject;
  var size = (file["size"] as JSNumber).toDartInt;
  var subtle = _subtleCrypto();
  if (useWebCrypto && subtle != null && size <= webCryptoMaxBytes) {
    var buffer =
        await file.callMethod<JSPromise<JSArrayBuffer>>("arrayBuffer".toJS).toDart;
    var digest = await subtle
        .callMethod<JSPromise<JSArrayBuffer>>("digest".toJS, "SHA-256".toJS, buffer)
        .toDart;
    return hex.encode(digest.toDart.asUint8List());
  }

  var digests = AccumulatorSink<Digest>();
  var input = sha256.startChunkedConversion(digests);
  for (var start = 0; start < size; start += hashSliceBytes) {
    var end = start + hashSliceBytes < size ? start + hashSliceBytes : size;
    var slice = file.callMethod<JSObject>("slice".toJS, start.toJS, end.toJS);
    var buffer =
        await slice.callMethod<JSPromise<JSArrayBuffer>>("arrayBuffer".toJS).toDart;
    input.add(buffer.toDart.asUint8List());
    // The slice's promise may already be settled; this makes sure the next
    // slice is a task of its own.
    await Future<void>.delayed(Duration.zero);
  }
  input.close();
  return digests.events.single.toString();
}

/// `crypto.subtle`, `null` outside a secure context.
JSObject? _subtleCrypto() {
  var crypto = globalContext["crypto"];
  if (crypto == null || crypto.isUndefinedOrNull) {
    return null;
  }
  var subtle = (crypto as JSObject)["subtle"];
  if (subtle == null || subtle.isUndefinedOrNull) {
    return null;
  }
  return subtle as JSObject;
}

/// One part of a [sendBlobForm] body: the field and file name the server
/// stores the file under, and its `Blob`.
typedef BlobPart = ({String name, Object blob});

/// What the server answered a [sendBlobForm]: the status and the body as text.
typedef BlobFormAnswer = ({int status, String body});

/// Thrown by [sendBlobForm] once the upload was aborted because [cancelled]
/// said so; the caller answers it with its own cancellation message.
class BlobFormCancelled implements Exception {
  const BlobFormCancelled();
}

/// How often [sendBlobForm] asks whether the upload was cancelled, between
/// the progress events that ask as well.
const Duration cancelPollInterval = Duration(milliseconds: 200);

/// Sends [parts] as one `multipart/form-data` body by [method] to [uri].
///
/// The body is a browser `FormData` of the `Blob`s themselves, each under a
/// field and a file name of its [BlobPart.name] — exactly the parts
/// `MultipartRequest` wrote before issue #170 and `ImageServlet` reads — and
/// the browser writes the boundary and the `Content-Type`; [headers] (the
/// bearer) are set on the request as they are. [onProgress] is told the bytes
/// of the body sent so far and their total, from `xhr.upload.onprogress`, and
/// once more with both equal when the body is out.
///
/// [cancelled] is asked at every progress event and every
/// [cancelPollInterval]; once it says yes the request is aborted and this
/// throws a [BlobFormCancelled]. A request that fails in the network throws the
/// `ClientException` that `BrowserClient` throws for the same failure, so the
/// caller tells a lost connection as it always did. Any answer of the server,
/// a refusal included, is returned for the caller to read.
Future<BlobFormAnswer> sendBlobForm(
  Uri uri,
  List<BlobPart> parts, {
  String method = "PUT",
  Map<String, String> headers = const {},
  void Function(int sent, int total)? onProgress,
  bool Function()? cancelled,
}) {
  var result = Completer<BlobFormAnswer>();
  var form = (globalContext["FormData"] as JSFunction).callAsConstructor<JSObject>();
  for (var part in parts) {
    form.callMethod<JSAny?>(
        "append".toJS, part.name.toJS, part.blob as JSObject, part.name.toJS);
  }

  var xhr =
      (globalContext["XMLHttpRequest"] as JSFunction).callAsConstructor<JSObject>();
  xhr.callMethod<JSAny?>("open".toJS, method.toJS, uri.toString().toJS, true.toJS);
  headers.forEach((name, value) {
    xhr.callMethod<JSAny?>("setRequestHeader".toJS, name.toJS, value.toJS);
  });

  Timer? poll;
  void finish(void Function() complete) {
    poll?.cancel();
    if (!result.isCompleted) {
      complete();
    }
  }

  var aborted = false;
  void checkCancelled() {
    if (!aborted && cancelled != null && cancelled()) {
      aborted = true;
      xhr.callMethod<JSAny?>("abort".toJS);
      // `abort` fires `onabort` synchronously in every browser this runs in;
      // should one not, the upload is still over.
      finish(() => result.completeError(const BlobFormCancelled()));
    }
  }

  var upload = xhr["upload"] as JSObject;
  upload["onprogress"] = (JSObject event) {
    checkCancelled();
    if ((event["lengthComputable"] as JSBoolean).toDart) {
      onProgress?.call(
        (event["loaded"] as JSNumber).toDartInt,
        (event["total"] as JSNumber).toDartInt,
      );
    }
  }.toJS;
  upload["onload"] = (JSObject event) {
    var total = (event["total"] as JSNumber).toDartInt;
    onProgress?.call(total, total);
  }.toJS;

  xhr["onload"] = (JSObject event) {
    finish(() => result.complete((
          status: (xhr["status"] as JSNumber).toDartInt,
          body: (xhr["responseText"] as JSString).toDart,
        )));
  }.toJS;
  void failed(JSObject event) {
    finish(() => aborted
        ? result.completeError(const BlobFormCancelled())
        : result.completeError(
            http.ClientException("XMLHttpRequest error.", uri)));
  }

  xhr["onerror"] = failed.toJS;
  xhr["ontimeout"] = failed.toJS;
  xhr["onabort"] = failed.toJS;

  checkCancelled();
  if (!aborted) {
    xhr.callMethod<JSAny?>("send".toJS, form);
    poll = Timer.periodic(cancelPollInterval, (_) => checkCancelled());
  }
  return result.future;
}

/// A `Blob` of [bytes], for a test that needs one.
Object blobOf(Uint8List bytes, {String type = "application/octet-stream"}) {
  var options = JSObject()..["type"] = type.toJS;
  return (globalContext["Blob"] as JSFunction)
      .callAsConstructor<JSObject>([bytes.toJS].toJS, options);
}
