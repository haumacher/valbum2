# Some formats decode the whole raster

JPEG/PNG/GIF are subsampled while decoding. Lossless WebP (TwelveMonkeys) and JPEG XL (JXLatte)
need memory proportional to the full picture, so they reserve from `PictureReader`'s budget
(`maxMemory/2`, `-Dvalbum.decodeBudget`) and are refused with `PictureTooLargeException` beyond it.
Never decode a whole original raster elsewhere (#68).
