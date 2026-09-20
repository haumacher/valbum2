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
Widget thumbnail(
  VAlbumClient client,
  String imageUrl, {
  Key? key,
  double? width,
  double? height,
  double? displayHeight,
  BoxFit? fit,
}) =>
    _Thumbnail(
      key: key,
      client: client,
      imageUrl: imageUrl,
      width: width,
      height: height,
      displayHeight: displayHeight,
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
}) =>
    ResizeImage(
      ThumbnailImage(client, imageUrl),
      height: (displayHeight * devicePixelRatio).ceil(),
      allowUpscaling: false,
    );

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
  final BoxFit? fit;

  const _Thumbnail({
    super.key,
    required this.client,
    required this.imageUrl,
    required this.width,
    required this.height,
    required this.displayHeight,
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
