/// A picture the server has not made yet (issue #256).
///
/// The server never makes a preview, a display rendition or a face crop
/// while a request waits: its background makes every one of them, and a
/// request for one that is not there yet is answered `202 Accepted`. The app
/// shows such a picture as the neutral placeholder a tile shows while its
/// bytes are on their way — no error icon, no message, no log entry — and
/// asks nothing again by itself: an album of a thousand pictures the server
/// has not processed must not keep the server busy answering. The user's
/// reload asks again ([forgetNotReadyImages]).
library;

import 'package:flutter/painting.dart';

/// What fetching a picture throws where the server answered `202 Accepted`:
/// it has not made the picture yet, see the library comment.
class ImageNotReady implements Exception {
  /// The address that was answered so.
  final String url;

  const ImageNotReady(this.url);

  @override
  String toString() => "ImageNotReady($url)";
}

/// The HTTP status the server answers a picture with that it has not made
/// yet, see [ImageNotReady].
const int notReadyStatus = 202;

/// Whether [error], which an image stream reported, says that the server has
/// not made the picture yet: an [ImageNotReady] of a picture fetched through
/// the client, a [NetworkImageLoadException] of `202` of one the platform
/// fetched itself (the viewer's display rendition).
bool isImageNotReady(Object error) =>
    error is ImageNotReady ||
    (error is NetworkImageLoadException && error.statusCode == notReadyStatus);

/// The providers whose picture was answered "not made yet", see
/// [rememberNotReady].
final Set<ImageProvider> _notReady = <ImageProvider>{};

/// Remembers that [provider]'s picture was answered "not made yet".
///
/// Its failed stream stays in Flutter's [ImageCache] on purpose: a tile that
/// is merely built again (a selection, a relayout) is answered from there and
/// asks the server nothing. A reload throws it away, see
/// [forgetNotReadyImages].
void rememberNotReady(ImageProvider provider) => _notReady.add(provider);

/// Whether a picture was answered "not made yet" and is still remembered.
bool get hasNotReadyImages => _notReady.isNotEmpty;

/// Forgets every picture the server answered "not made yet", so that the
/// next build of its tile asks the server again: what the user's reload does.
void forgetNotReadyImages() {
  for (var provider in _notReady) {
    provider.evict();
  }
  _notReady.clear();
}
