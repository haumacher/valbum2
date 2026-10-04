# `ResizeImage` bypasses the ImageCache for its inner provider

A `ResizeImage` resolves its inner `ThumbnailImage` outside Flutter's `ImageCache`, so each new decode
size would re-download. Hence `thumbnailByteCache` (encoded bytes, LRU) and `decodedThumbnailHeight`
(reuse the tile's decoding key in the viewer underlay). Large decoded originals must be pinned with a
live `ImageStreamListener` or the cache evicts them (#148).
