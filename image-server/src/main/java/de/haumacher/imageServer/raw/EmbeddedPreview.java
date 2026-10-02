/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.raw;

/**
 * The preview a raw file carries, and how large its picture is, see
 * {@link RawFile#embedded(java.io.File)}.
 *
 * <p>
 * Either a JPEG stream at an offset of the file ({@link #getOffset()}, {@link #getLength()}), or
 * &mdash; for a DNG whose only preview is an uncompressed RGB thumbnail in an IFD of its main chain,
 * which is what Android's <code>DngCreator</code> writes &mdash; that IFD, read by the TIFF reader of
 * the JDK as the image of the given {@link #getTiffIndex() index}.
 * </p>
 */
public final class EmbeddedPreview {

	private final long _offset;

	private final long _length;

	private final int _width;

	private final int _height;

	private final int _tiffIndex;

	EmbeddedPreview(long offset, long length, int width, int height) {
		this(offset, length, width, height, -1);
	}

	private EmbeddedPreview(long offset, long length, int width, int height, int tiffIndex) {
		_offset = offset;
		_length = length;
		_width = width;
		_height = height;
		_tiffIndex = tiffIndex;
	}

	/** An uncompressed picture in the IFD of the given index of a TIFF's main chain. */
	static EmbeddedPreview tiffPicture(int index, int width, int height) {
		return new EmbeddedPreview(0, 0, width, height, index);
	}

	/** The offset of the JPEG's first byte (its <code>SOI</code>) in the raw file. */
	public long getOffset() {
		return _offset;
	}

	/** How many bytes the JPEG takes. */
	public long getLength() {
		return _length;
	}

	/** The width of the picture, as stored, before any orientation. */
	public int getWidth() {
		return _width;
	}

	/** The height of the picture, likewise. */
	public int getHeight() {
		return _height;
	}

	/**
	 * The index of the TIFF image that is the preview, <code>-1</code> where the preview is a JPEG
	 * stream.
	 */
	public int getTiffIndex() {
		return _tiffIndex;
	}

	/** How many pixels the picture has; what decides between two previews. */
	long pixels() {
		return ((long) _width) * _height;
	}

	@Override
	public String toString() {
		return _width + " x " + _height + (_tiffIndex >= 0 ? " in TIFF image " + _tiffIndex
			: " at " + _offset + " (" + _length + " bytes)");
	}
}
