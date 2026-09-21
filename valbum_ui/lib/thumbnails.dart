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

/// The crop of one face of an image, fetched through [client] (issue #126).
///
/// The [ThumbnailImage] of a face: the same transport, the same token, the
/// same offline cache — only the URL differs, `?type=face&face=<index>`. A
/// provider of its own rather than a parameter of [ThumbnailImage], so that
/// the two are two cache keys and a face crop never stands in for a thumbnail.
///
/// The [index] is the position in the answer the editor read and is stable
/// for that answer alone (see `FaceInfo`), which is exactly the lifetime of
/// this provider: a re-fetched album builds new ones.
@immutable
class FaceImage extends ImageProvider<FaceImage> {
  /// The transport the bytes are fetched over.
  final VAlbumClient client;

  /// The URL of the *image* the face was found in.
  final String imageUrl;

  /// Which face of it, see [VAlbumClient.faceUrl].
  final int index;

  /// The scale to place in the [ImageInfo] of the decoded image.
  final double scale;

  const FaceImage(this.client, this.imageUrl, this.index, {this.scale = 1.0});

  /// The URL the bytes are fetched from, the one a test looks for.
  String get url => client.faceUrl(imageUrl, index);

  @override
  Future<FaceImage> obtainKey(ImageConfiguration configuration) =>
      SynchronousFuture<FaceImage>(this);

  @override
  ImageStreamCompleter loadImage(
    FaceImage key,
    ImageDecoderCallback decode,
  ) =>
      MultiFrameImageStreamCompleter(
        codec: key._load(decode),
        scale: key.scale,
        debugLabel: key.url,
        informationCollector: () => [ErrorDescription("Face: ${key.url}")],
      );

  Future<ui.Codec> _load(ImageDecoderCallback decode) async {
    Uint8List bytes;
    try {
      bytes = await client.faceBytes(imageUrl, index);
    } catch (error) {
      // The stream must be told, or the image widget waits forever.
      PaintingBinding.instance.imageCache.evict(this);
      rethrow;
    }
    if (bytes.isEmpty) {
      PaintingBinding.instance.imageCache.evict(this);
      throw StateError("The face crop at $url is empty.");
    }
    return decode(await ui.ImmutableBuffer.fromUint8List(bytes));
  }

  @override
  bool operator ==(Object other) =>
      other is FaceImage &&
      other.imageUrl == imageUrl &&
      other.index == index &&
      other.scale == scale &&
      other.client.dataUrl == client.dataUrl &&
      other.client.token == client.token;

  @override
  int get hashCode =>
      Object.hash(imageUrl, index, scale, client.dataUrl, client.token);

  @override
  String toString() => "FaceImage($url)";
}

/// What a tile is painted with while its thumbnail is on its way (issue #111).
///
/// A neutral grey, translucent so that it reads on the album's black page as
/// well as on the listing's light one. It is a matter of paint only: the box
/// it fills is the one the tile already has, see [_ThumbnailState._placeheld].
const Color thumbnailPlaceholderColor = Color(0x33808080);

/// How long an arriving thumbnail takes to fade in over its placeholder.
const Duration thumbnailFadeInDuration = Duration(milliseconds: 150);

/// The tile showing the thumbnail of [imageUrl].
///
/// The counterpart of the `Image.network` the app used before: same arguments,
/// but the bytes come through the client, see [ThumbnailImage].
///
/// [displayHeight] is the height the image itself is drawn at, in logical
/// pixels. Where the caller knows it, the thumbnail is decoded at that height
/// times the device's pixel ratio instead of at its full size — see
/// [resizedThumbnail]. Where it does not (the viewer's fallback, the cropped
/// index picture of a listing, which magnifies a part of the thumbnail), the
/// provider is left alone and the full thumbnail is decoded, as before.
/// Where no [displayHeight] is known but the picture is the one a tile has
/// already decoded, [asTileDecoded] asks for *that* decoding, see
/// [decodedThumbnailHeight] and issue #120.
Widget thumbnail(
  VAlbumClient client,
  String imageUrl, {
  Key? key,
  double? width,
  double? height,
  double? displayHeight,
  bool asTileDecoded = false,
  BoxFit? fit,
}) =>
    _Thumbnail(
      key: key,
      client: client,
      imageUrl: imageUrl,
      width: width,
      height: height,
      displayHeight: displayHeight,
      asTileDecoded: asTileDecoded,
      fit: fit,
    );

/// The thumbnail of [imageUrl], decoded at the size it is shown at.
///
/// The server's thumbnails are 600 px high (1200 for a portrait one), which is
/// roughly 2 MB decoded; an album tile shows perhaps 200 px of that. Decoding
/// every tile at its full size fills Flutter's [ImageCache] with a few dozen
/// images, and an album larger than that evicts its own thumbnails while the
/// viewer is open — the way back then fetches and decodes them all again, see
/// issue #93. Decoded at the height it is drawn at, a tile costs a ninth of
/// that and the cache holds many times more of the album.
///
/// The height asked for is the displayed height times the device's pixel
/// ratio, rounded up, so nothing is ever drawn from fewer pixels than it
/// shows; `allowUpscaling: false` caps it at the thumbnail's own size, so a
/// tile larger than the thumbnail decodes it unchanged instead of blowing it
/// up in memory. The cache key changes with the size, which is the point: two
/// tiles of different size are two different decodings of one download.
ImageProvider resizedThumbnail(
  VAlbumClient client,
  String imageUrl, {
  required double displayHeight,
  required double devicePixelRatio,
}) {
  var height = (displayHeight * devicePixelRatio).ceil();
  _rememberDecodedHeight(imageUrl, height);
  return _decodedAt(client, imageUrl, height);
}

/// The provider decoding the thumbnail of [imageUrl] at [height] pixels.
ImageProvider _decodedAt(VAlbumClient client, String imageUrl, int height) =>
    ResizeImage(
      ThumbnailImage(client, imageUrl),
      height: height,
      allowUpscaling: false,
    );

/// At how many pixels the thumbnail of each image was decoded last, see
/// [decodedThumbnailHeight] (issue #120).
///
/// Insertion-ordered, and an entry that is asked for again moves to the end,
/// so dropping the first entry drops the one longest untouched.
final Map<String, int> _decodedHeights = <String, int>{};

/// How many decodings are remembered, see [_decodedHeights].
///
/// A string and an int per image, and far more than any viewport holds: what
/// is wanted is that the tiles on the screen — and the ones the list keeps in
/// its cache region — are still known when the viewer asks. An album larger
/// than this simply forgets its oldest rows, and a viewer opened on one of
/// those pays the one request of a deep link.
const int _decodedHeightsRemembered = 1024;

void _rememberDecodedHeight(String imageUrl, int height) {
  if (_decodedHeights.remove(imageUrl) == null &&
      _decodedHeights.length >= _decodedHeightsRemembered) {
    _decodedHeights.remove(_decodedHeights.keys.first);
  }
  _decodedHeights[imageUrl] = height;
}

/// The height, in pixels, the thumbnail of [imageUrl] was last decoded at by
/// a tile, `null` where no tile has decoded it (issue #120).
///
/// A tile decodes its thumbnail through a [ResizeImage] keyed by the height it
/// is drawn at, see [resizedThumbnail]; the viewer's underlay (issue #101)
/// shows the very same picture and used to ask for the raw [ThumbnailImage]
/// instead. [ResizeImage] resolves its inner provider *outside* the
/// [ImageCache], so those are two keys over one download and opening an image
/// from its tile cost a second request for bytes the album already had. Asking
/// with the tile's own key makes the underlay a cache hit.
///
/// It is a height and nothing else — no bytes, no provider, no image — so it
/// can never be stale: "Refresh previews" (issue #98) empties the [ImageCache]
/// and the tiles decode again at the same height, which is the same key and
/// the new bytes. What it cannot answer is an image no tile has drawn: a deep
/// link straight into the viewer fetches its underlay once, as it did before.
int? decodedThumbnailHeight(String imageUrl) => _decodedHeights[imageUrl];

/// Forgets every remembered decoding, so that one test never answers the next.
@visibleForTesting
void forgetDecodedThumbnailHeights() => _decodedHeights.clear();

/// A tile that holds on to its picture for as long as it is mounted.
///
/// The [Image] widget alone does not: a route that is covered by another one
/// stays mounted but is switched to [TickerMode] `enabled: false`, and
/// `Image` answers that by dropping its listener on the image stream. With
/// the last listener gone, Flutter's [ImageCache] forgets the image as a
/// *live* one — and once it has also been evicted from the size-limited cache
/// (which a large album does to its own thumbnails, see issue #93), the way
/// back resolves the provider again, misses, and fetches and decodes every
/// visible tile from the server a second time.
///
/// Keeping the album mounted beneath the viewer is therefore only half the
/// answer; the other half is this listener, which does not go away when the
/// page is covered. It costs one image per *mounted* tile — the album's list
/// is lazy, so that is the viewport — and it is what makes ascending from a
/// photo free.
class _Thumbnail extends StatefulWidget {
  final VAlbumClient client;
  final String imageUrl;
  final double? width;
  final double? height;
  final double? displayHeight;
  final bool asTileDecoded;
  final BoxFit? fit;

  const _Thumbnail({
    super.key,
    required this.client,
    required this.imageUrl,
    required this.width,
    required this.height,
    required this.displayHeight,
    required this.asTileDecoded,
    required this.fit,
  });

  @override
  State<_Thumbnail> createState() => _ThumbnailState();
}

class _ThumbnailState extends State<_Thumbnail> {
  /// The stream this tile holds a listener on, see [_Thumbnail].
  ImageStream? _held;

  /// Takes the frame and lets go of it again: the picture is drawn by the
  /// [Image] below, this listener is here to be counted, not to paint. An
  /// [ImageInfo] handed to a listener is its own to dispose.
  late final ImageStreamListener _listener = ImageStreamListener(
    (ImageInfo image, bool synchronous) => image.dispose(),
    // Errors are reported by the [Image] widget; a listener without an error
    // handler would let a failed thumbnail throw out of the stream.
    onError: (Object error, StackTrace? stack) {},
  );

  /// The provider of this tile, at the size it is drawn at where that is
  /// known, see [thumbnail].
  ImageProvider _provider(BuildContext context) {
    var displayHeight = widget.displayHeight;
    if (displayHeight == null ||
        !displayHeight.isFinite ||
        displayHeight <= 0) {
      if (widget.asTileDecoded) {
        // The picture a tile has already decoded, asked for with the tile's
        // own key, see [decodedThumbnailHeight] and issue #120.
        var decoded = decodedThumbnailHeight(widget.imageUrl);
        if (decoded != null) {
          return _decodedAt(widget.client, widget.imageUrl, decoded);
        }
      }
      return ThumbnailImage(widget.client, widget.imageUrl);
    }
    return resizedThumbnail(
      widget.client,
      widget.imageUrl,
      displayHeight: displayHeight,
      devicePixelRatio: MediaQuery.devicePixelRatioOf(context),
    );
  }

  void _hold() {
    var stream =
        _provider(context).resolve(createLocalImageConfiguration(context));
    if (stream.key == _held?.key) {
      return;
    }
    _held?.removeListener(_listener);
    _held = stream..addListener(_listener);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // The device's pixel ratio decides the size the tile is decoded at.
    _hold();
  }

  @override
  void didUpdateWidget(_Thumbnail oldWidget) {
    super.didUpdateWidget(oldWidget);
    _hold();
  }

  @override
  void dispose() {
    _held?.removeListener(_listener);
    _held = null;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Image(
        image: _provider(context),
        width: widget.width,
        height: widget.height,
        fit: widget.fit,
        frameBuilder: _placeheld,
      );

  /// The tile while its bytes are on their way, see
  /// [thumbnailPlaceholderColor] (issue #111).
  ///
  /// The box is the one the [Image] itself lays out — the album gives it the
  /// width and the height the row layout assigned, so the placeholder is
  /// exactly the picture's own box and nothing ever reflows when the bytes
  /// arrive. Where the caller gives no size (the cropped index picture of a
  /// listing), the box is whatever the parent constrains the image to, which
  /// is what the finished picture gets as well.
  Widget _placeheld(
    BuildContext context,
    Widget child,
    int? frame,
    bool wasSynchronouslyLoaded,
  ) {
    var shown = wasSynchronouslyLoaded || frame != null;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: shown ? null : thumbnailPlaceholderColor,
      ),
      child: AnimatedOpacity(
        opacity: shown ? 1 : 0,
        // A picture that was there all along (the cache answered) is simply
        // there; one that was waited for fades in over its placeholder.
        duration:
            wasSynchronouslyLoaded ? Duration.zero : thumbnailFadeInDuration,
        child: child,
      ),
    );
  }
}

/// The [ThumbnailImage] an image provider fetches through, `null` for
/// anything else.
///
/// A tile that knows the height it is drawn at wraps the provider in a
/// [ResizeImage], see [resizedThumbnail]; what the tile shows is the same
/// image either way, and this is how a test of the layout finds it.
ThumbnailImage? thumbnailOf(ImageProvider provider) => switch (provider) {
      ThumbnailImage() => provider,
      ResizeImage(imageProvider: var inner) => thumbnailOf(inner),
      _ => null,
    };
