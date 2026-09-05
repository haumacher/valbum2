/// The thumbnails of the listing, the album and the group view: an
/// [ImageProvider] that fetches through the [VAlbumClient].
///
/// `Image.network` opens a connection of its own, which carries neither the
/// device token (a server started with `--auth all` refuses those requests,
/// issue #28) nor anything the offline cache could answer from (issue #31).
/// Every tile of the app therefore goes through this provider instead; the
/// full-size image of the viewer stays on `Image.network`, with the token
/// passed as a header, because caching originals is not what the cache is for.
library;

import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

import 'client.dart';

/// The thumbnail of the image at [imageUrl], fetched through [client].
@immutable
class ThumbnailImage extends ImageProvider<ThumbnailImage> {
  /// The transport the bytes are fetched over.
  final VAlbumClient client;

  /// The URL of the *image*, not of its thumbnail: the client knows how a
  /// thumbnail of it is asked for, see [VAlbumClient.thumbnailUrl].
  final String imageUrl;

  /// The scale to place in the [ImageInfo] of the decoded image.
  final double scale;

  const ThumbnailImage(this.client, this.imageUrl, {this.scale = 1.0});

  /// The URL the bytes are fetched from, the one a test looks for.
  String get url => client.thumbnailUrl(imageUrl);

  @override
  Future<ThumbnailImage> obtainKey(ImageConfiguration configuration) =>
      SynchronousFuture<ThumbnailImage>(this);

  @override
  ImageStreamCompleter loadImage(
    ThumbnailImage key,
    ImageDecoderCallback decode,
  ) =>
      MultiFrameImageStreamCompleter(
        codec: key._load(decode),
        scale: key.scale,
        debugLabel: key.url,
        informationCollector: () => [ErrorDescription("Thumbnail: ${key.url}")],
      );

  Future<ui.Codec> _load(ImageDecoderCallback decode) async {
    Uint8List bytes;
    try {
      bytes = await client.thumbnailBytes(imageUrl);
    } catch (error) {
      // The stream must be told, or the image widget waits forever.
      PaintingBinding.instance.imageCache.evict(this);
      rethrow;
    }
    if (bytes.isEmpty) {
      PaintingBinding.instance.imageCache.evict(this);
      throw StateError("The thumbnail at $url is empty.");
    }
    return decode(await ui.ImmutableBuffer.fromUint8List(bytes));
  }

  /// Two providers are the same image when they name the same URL on the same
  /// server with the same identity: a token change must re-fetch.
  @override
  bool operator ==(Object other) =>
      other is ThumbnailImage &&
      other.imageUrl == imageUrl &&
      other.scale == scale &&
      other.client.dataUrl == client.dataUrl &&
      other.client.token == client.token;

  @override
  int get hashCode =>
      Object.hash(imageUrl, scale, client.dataUrl, client.token);

  @override
  String toString() => "ThumbnailImage($url)";
}

/// The tile showing the thumbnail of [imageUrl].
///
/// The counterpart of the `Image.network` the app used before: same arguments,
/// but the bytes come through the client, see [ThumbnailImage].
Widget thumbnail(
  VAlbumClient client,
  String imageUrl, {
  Key? key,
  double? width,
  double? height,
  BoxFit? fit,
}) =>
    Image(
      key: key,
      image: ThumbnailImage(client, imageUrl),
      width: width,
      height: height,
      fit: fit,
    );
