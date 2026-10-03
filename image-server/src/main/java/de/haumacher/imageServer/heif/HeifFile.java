/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.heif;

import com.drew.lang.ByteArrayReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifReader;
import com.drew.metadata.xmp.XmpReader;
import de.haumacher.imageServer.PreviewCache;
import de.haumacher.imageServer.coded.CodedPicture;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.util.servlet.Util;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The structure of a HEIC/HEIF photograph (issue #186) or an AVIF picture (issue #193), read out
 * of its ISOBMFF container.
 *
 * <p>
 * Only the boxes of the <code>meta</code> box are read, never a pixel: the primary item
 * (<code>pitm</code>), the item types (<code>iinf</code>), where their data lie
 * (<code>iloc</code>, in the file or in <code>idat</code>), which tiles make up a
 * <code>grid</code> (<code>iref dimg</code>) and which metadata describe the picture
 * (<code>iref cdsc</code>), which item is its alpha channel (<code>iref auxl</code> with the
 * alpha <code>auxC</code>, premultiplied where <code>iref prem</code> says so), and the properties
 * (<code>iprp</code>: <code>hvcC</code>, <code>av1C</code>, <code>ispe</code>, <code>colr</code>,
 * <code>clap</code>, <code>irot</code>, <code>imir</code>). The pixels themselves are HEVC (a
 * HEIC) or AV1 (an AVIF, which is HEIF with AV1 coded items — the same grids, the same turns, the
 * same metadata items) and are decoded by {@link HeifDecoder}.
 * </p>
 *
 * <h2>The raw raster</h2>
 *
 * <p>
 * The <em>raw raster</em> of a HEIC — what a face box is stored in (issue #142), what
 * {@link #getRawWidth()} measures and what {@link HeifDecoder#decodeRaw} answers — is the picture
 * as coded: the tiles of a grid stitched and cut to the grid's output size, the clean aperture
 * (<code>clap</code>) applied, and <em>neither</em> <code>irot</code> nor <code>imir</code>. Those
 * two are the HEIF counterpart of the EXIF orientation of a JPEG, and {@link #getOrientation()}
 * says them as one: the very {@link Orientation} the preview, the face index and the app turn a
 * JPEG's raster by. The EXIF orientation a HEIC carries is <em>not</em> read: a phone writes the
 * turn it already stored as <code>irot</code> into it too, so applying it would turn the picture
 * twice.
 * </p>
 *
 * <p>
 * A clean aperture standing behind a turn in the property list is pulled in front of it (the crop
 * rectangle is mapped back through the turn), so that "cut, then turn" is always what the rest of
 * the server sees.
 * </p>
 */
public final class HeifFile implements CodedPicture {

	/** The file extensions of a HEIF photograph — HEIC/HEIF and AVIF — lower case. */
	public static final Set<String> EXTENSIONS = Set.of("heic", "heif", "avif");

	/** The item type of an HEVC coded picture. */
	static final String HVC1 = "hvc1";

	/** The item type of an AV1 coded picture (issue #193). */
	static final String AV01 = "av01";

	/** The auxiliary type of an alpha channel, MPEG-B CICP and the older HEVC spelling. */
	private static final Set<String> ALPHA_URNS =
		Set.of("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha", "urn:mpeg:hevc:2015:auxid:1");

	/** The item type of a grid of pictures. */
	static final String GRID = "grid";

	private final int _rawWidth;

	private final int _rawHeight;

	private final Orientation _orientation;

	private final int _cropLeft;

	private final int _cropTop;

	private final Plane _image;

	private final Plane _alpha;

	private final boolean _premultiplied;

	private final byte[] _exif;

	private final byte[] _xmp;

	/**
	 * One coded picture: its parameter sets (an HEVC picture's VPS, SPS and PPS, an AV1 picture's
	 * configuration OBUs) and where its data lie in the file.
	 */
	public static final class Tile {

		final byte[] _parameterSets;

		/** The length prefix of a NAL unit, 0 for AV1, whose OBUs carry their own size. */
		final int _lengthSize;

		final long[] _offsets;

		final long[] _lengths;

		final byte[] _inline;

		Tile(byte[] parameterSets, int lengthSize, long[] offsets, long[] lengths, byte[] inline) {
			_parameterSets = parameterSets;
			_lengthSize = lengthSize;
			_offsets = offsets;
			_lengths = lengths;
			_inline = inline;
		}
	}

	/**
	 * The coded pictures of one channel set — the colour picture, or its alpha channel — as a grid
	 * of tiles (one tile where it is no grid), stitched row by row and cut to the raw raster by the
	 * crop of the {@link HeifFile}.
	 */
	public static final class Plane {

		final boolean _av1;

		final int _columns;

		final int _rows;

		final int _tileWidth;

		final int _tileHeight;

		final List<Tile> _tiles;

		final Boolean _fullRange;

		final int _matrix;

		Plane(boolean av1, int columns, int rows, int tileWidth, int tileHeight, List<Tile> tiles,
				Boolean fullRange, int matrix) {
			_av1 = av1;
			_columns = columns;
			_rows = rows;
			_tileWidth = tileWidth;
			_tileHeight = tileHeight;
			_tiles = tiles;
			_fullRange = fullRange;
			_matrix = matrix;
		}

		/** Whether the tiles are AV1 coded (an AVIF), not HEVC. */
		public boolean isAv1() {
			return _av1;
		}

		/** How many tiles wide the plane is. */
		public int getColumns() {
			return _columns;
		}

		/** How many tiles high the plane is. */
		public int getRows() {
			return _rows;
		}

		/** The coded pictures, row by row. */
		public List<Tile> getTiles() {
			return _tiles;
		}

		/** What the <code>nclx</code> colour box says of the range, <code>null</code> without one. */
		public Boolean getFullRange() {
			return _fullRange;
		}

		/** The matrix coefficients of the <code>nclx</code> colour box, <code>-1</code> without one. */
		public int getMatrix() {
			return _matrix;
		}
	}

	private HeifFile(int rawWidth, int rawHeight, Orientation orientation, int cropLeft, int cropTop, Plane image,
			Plane alpha, boolean premultiplied, byte[] exif, byte[] xmp) {
		_rawWidth = rawWidth;
		_rawHeight = rawHeight;
		_orientation = orientation;
		_cropLeft = cropLeft;
		_cropTop = cropTop;
		_image = image;
		_alpha = alpha;
		_premultiplied = premultiplied;
		_exif = exif;
		_xmp = xmp;
	}

	/** Whether the given file is named as a HEIF photograph (HEIC/HEIF, AVIF), the extension in any case. */
	public static boolean isHeif(File file) {
		return isHeif(file.getName());
	}

	/** Whether the given file name is that of a HEIF photograph (HEIC/HEIF, AVIF), the extension in any case. */
	public static boolean isHeif(String name) {
		String suffix = Util.suffix(name);
		return suffix != null && EXTENSIONS.contains(suffix.toLowerCase(Locale.ROOT));
	}

	@Override
	public int getRawWidth() {
		return _rawWidth;
	}

	@Override
	public int getRawHeight() {
		return _rawHeight;
	}

	/** How the raw raster is turned for display: <code>irot</code> and <code>imir</code> as one. */
	@Override
	public Orientation getOrientation() {
		return _orientation;
	}

	/** The colour picture. */
	public Plane getImage() {
		return _image;
	}

	/** The alpha channel, <code>null</code> for an opaque picture. */
	public Plane getAlpha() {
		return _alpha;
	}

	/** Whether the colour picture is premultiplied with its alpha channel (<code>iref prem</code>). */
	public boolean isPremultiplied() {
		return _premultiplied;
	}

	/** Whether the colour picture is AV1 coded, an AVIF (issue #193). */
	public boolean isAv1() {
		return _image._av1;
	}

	/** How many tiles wide the coded picture is, 1 for a picture that is no grid. */
	public int getColumns() {
		return _image._columns;
	}

	/** How many tiles high the coded picture is, 1 for a picture that is no grid. */
	public int getRows() {
		return _image._rows;
	}

	/** The width of one tile (of the whole picture where it is no grid). */
	public int getTileWidth() {
		return _image._tileWidth;
	}

	/** The height of one tile. */
	public int getTileHeight() {
		return _image._tileHeight;
	}

	/** Where the raw raster begins in the stitched tiles, horizontally. */
	public int getCropLeft() {
		return _cropLeft;
	}

	/** Where the raw raster begins in the stitched tiles, vertically. */
	public int getCropTop() {
		return _cropTop;
	}

	/** The coded pictures of the colour picture, row by row. */
	public List<Tile> getTiles() {
		return _image._tiles;
	}

	/** What the <code>nclx</code> colour box says of the range, <code>null</code> without one. */
	public Boolean getFullRange() {
		return _image._fullRange;
	}

	/** The matrix coefficients of the <code>nclx</code> colour box, <code>-1</code> without one. */
	public int getMatrix() {
		return _image._matrix;
	}

	/** The TIFF structure of the EXIF item describing the picture, <code>null</code> without one. */
	public byte[] getExif() {
		return _exif;
	}

	/** The XMP packet describing the picture, <code>null</code> without one. */
	public byte[] getXmp() {
		return _xmp;
	}

	/**
	 * What the EXIF and XMP items of the picture say, as metadata-extractor reads them out of a
	 * JPEG: the same directories, so the date, the camera, the position and the named faces of
	 * issue #129 are read by the very code that reads them out of a JPEG.
	 *
	 * <p>
	 * The {@link com.drew.metadata.exif.ExifIFD0Directory#TAG_ORIENTATION} it may carry is not
	 * this picture's orientation, see {@link #getOrientation()}.
	 * </p>
	 */
	@Override
	public Metadata metadata() {
		Metadata metadata = new Metadata();
		if (_exif != null) {
			new ExifReader().extract(new ByteArrayReader(_exif), metadata, 0);
		}
		if (_xmp != null) {
			new XmpReader().extract(_xmp, metadata);
		}
		return metadata;
	}

	@Override
	public int getDisplayWidth() {
		return swaps(_orientation) ? _rawHeight : _rawWidth;
	}

	@Override
	public int getDisplayHeight() {
		return swaps(_orientation) ? _rawWidth : _rawHeight;
	}

	@Override
	public BufferedImage decodeRaw(File file, int left, int top, int width, int height, int outWidth,
			int outHeight) throws IOException {
		return HeifDecoder.decodeRaw(file, this, left, top, width, height, outWidth, outHeight);
	}

	@Override
	public void writeUprightJpeg(File file, int outWidth, int outHeight, File target) throws IOException {
		HeifDecoder.writeUprightJpeg(file, this, outWidth, outHeight, target);
	}

	static boolean swaps(Orientation orientation) {
		return orientation.ordinal() >= 4;
	}

	// --- Reading. ---

	/**
	 * Reads the structure of the given HEIC/HEIF file.
	 *
	 * @throws IOException
	 *         If the file is no HEIF, or its primary picture is coded in a way this server cannot
	 *         decode (an overlay, a JPEG item, ...): the message says which.
	 */
	public static HeifFile read(File file) throws IOException {
		try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
			return new Parser(file.getName(), in).parse();
		} catch (IndexOutOfBoundsException | IllegalArgumentException ex) {
			throw new IOException("'" + file.getName() + "' is no readable HEIF file: " + ex.getMessage(), ex);
		}
	}

	private static final class Item {
		String _type = "";

		String _contentType = "";

		boolean _hidden;

		int _constructionMethod;

		long _baseOffset;

		final List<long[]> _extents = new ArrayList<>();

		final List<Integer> _properties = new ArrayList<>();
	}

	private static final class Parser {

		private final String _name;

		private final RandomAccessFile _in;

		private long _primary = -1;

		private final Map<Long, Item> _items = new HashMap<>();

		private final Map<Long, List<Long>> _tiles = new HashMap<>();

		private final Map<Long, List<Long>> _describes = new HashMap<>();

		/** Auxiliary item to the items it belongs to (<code>iref auxl</code>). */
		private final Map<Long, List<Long>> _auxiliaries = new HashMap<>();

		/** Premultiplied item to its alpha items (<code>iref prem</code>). */
		private final Map<Long, List<Long>> _premultiplied = new HashMap<>();

		private final List<Box> _properties = new ArrayList<>();

		private byte[] _idat = new byte[0];

		Parser(String name, RandomAccessFile in) {
			_name = name;
			_in = in;
		}

		private Item item(long id) {
			return _items.computeIfAbsent(Long.valueOf(id), k -> new Item());
		}

		HeifFile parse() throws IOException {
			long length = _in.length();
			long position = 0;
			boolean meta = false;
			String brand = null;
			while (position + 8 <= length) {
				_in.seek(position);
				long size = _in.readInt() & 0xFFFFFFFFL;
				String type = fourCC(_in.readInt());
				long header = 8;
				if (size == 1) {
					size = _in.readLong();
					header = 16;
				} else if (size == 0) {
					size = length - position;
				}
				if (size < header || position + size > length) {
					throw new IOException("'" + _name + "' is no readable HEIF file: the box '" + type + "' is cut off.");
				}
				if ("ftyp".equals(type)) {
					brand = fourCC(_in.readInt());
				} else if ("meta".equals(type)) {
					byte[] body = new byte[(int) (size - header)];
					_in.readFully(body);
					meta(new Box(type, body, 0, body.length));
					meta = true;
				}
				position += size;
			}
			if (brand == null || !meta) {
				throw new IOException("'" + _name + "' is no HEIF file: no 'ftyp' and 'meta' box.");
			}
			return build();
		}

		private void meta(Box box) throws IOException {
			// A full box: version and flags first.
			for (Box child : box.children(4)) {
				switch (child._type) {
					case "pitm":
						_primary = child.version() == 0 ? child.u16(4) : child.u32(4);
						break;
					case "iinf":
						iinf(child);
						break;
					case "iloc":
						iloc(child);
						break;
					case "iref":
						iref(child);
						break;
					case "iprp":
						iprp(child);
						break;
					case "idat":
						_idat = child.bytes(0, child._length);
						break;
					default:
						break;
				}
			}
		}

		private void iinf(Box box) {
			int offset = box.version() == 0 ? 6 : 8;
			for (Box infe : box.children(offset)) {
				if (!"infe".equals(infe._type)) {
					continue;
				}
				int version = infe.version();
				if (version < 2) {
					continue;
				}
				int at = 4;
				long id;
				if (version == 2) {
					id = infe.u16(at);
					at += 2;
				} else {
					id = infe.u32(at);
					at += 4;
				}
				at += 2; // protection index
				Item item = item(id);
				item._type = fourCC((int) infe.u32(at));
				item._hidden = (infe.flags() & 1) != 0;
				at += 4;
				int nameEnd = infe.stringEnd(at);
				at = nameEnd + 1;
				if ("mime".equals(item._type) && at < infe._length) {
					int end = infe.stringEnd(at);
					item._contentType = infe.string(at, end);
				}
			}
		}

		private void iloc(Box box) {
			int version = box.version();
			int at = 4;
			int sizes = box.u8(at++);
			int offsetSize = sizes >> 4;
			int lengthSize = sizes & 0xF;
			int more = box.u8(at++);
			int baseOffsetSize = more >> 4;
			int indexSize = version == 1 || version == 2 ? more & 0xF : 0;
			long count;
			if (version < 2) {
				count = box.u16(at);
				at += 2;
			} else {
				count = box.u32(at);
				at += 4;
			}
			for (long n = 0; n < count; n++) {
				long id;
				if (version < 2) {
					id = box.u16(at);
					at += 2;
				} else {
					id = box.u32(at);
					at += 4;
				}
				Item item = item(id);
				if (version == 1 || version == 2) {
					item._constructionMethod = box.u16(at) & 0xF;
					at += 2;
				}
				at += 2; // data reference index
				item._baseOffset = box.uN(at, baseOffsetSize);
				at += baseOffsetSize;
				int extents = box.u16(at);
				at += 2;
				for (int e = 0; e < extents; e++) {
					at += indexSize;
					long extentOffset = box.uN(at, offsetSize);
					at += offsetSize;
					long extentLength = box.uN(at, lengthSize);
					at += lengthSize;
					item._extents.add(new long[] { extentOffset, extentLength });
				}
			}
		}

		private void iref(Box box) {
			boolean wide = box.version() != 0;
			for (Box reference : box.children(4)) {
				int at = 0;
				long from = wide ? reference.u32(at) : reference.u16(at);
				at += wide ? 4 : 2;
				int count = reference.u16(at);
				at += 2;
				List<Long> to = new ArrayList<>();
				for (int n = 0; n < count; n++) {
					to.add(Long.valueOf(wide ? reference.u32(at) : reference.u16(at)));
					at += wide ? 4 : 2;
				}
				if ("dimg".equals(reference._type)) {
					_tiles.put(Long.valueOf(from), to);
				} else if ("cdsc".equals(reference._type)) {
					_describes.put(Long.valueOf(from), to);
				} else if ("auxl".equals(reference._type)) {
					_auxiliaries.put(Long.valueOf(from), to);
				} else if ("prem".equals(reference._type)) {
					_premultiplied.put(Long.valueOf(from), to);
				}
			}
		}

		private void iprp(Box box) {
			for (Box child : box.children(0)) {
				if ("ipco".equals(child._type)) {
					_properties.addAll(child.children(0));
				} else if ("ipma".equals(child._type)) {
					int version = child.version();
					boolean wideIndex = (child.flags() & 1) != 0;
					int at = 4;
					long count = child.u32(at);
					at += 4;
					for (long n = 0; n < count; n++) {
						long id;
						if (version < 1) {
							id = child.u16(at);
							at += 2;
						} else {
							id = child.u32(at);
							at += 4;
						}
						int associations = child.u8(at++);
						Item item = item(id);
						for (int a = 0; a < associations; a++) {
							int index;
							if (wideIndex) {
								index = child.u16(at) & 0x7FFF;
								at += 2;
							} else {
								index = child.u8(at) & 0x7F;
								at += 1;
							}
							if (index > 0) {
								item._properties.add(Integer.valueOf(index));
							}
						}
					}
				}
			}
		}

		private Box property(Item item, String type) {
			for (Integer index : item._properties) {
				int n = index.intValue() - 1;
				if (n >= 0 && n < _properties.size() && type.equals(_properties.get(n)._type)) {
					return _properties.get(n);
				}
			}
			return null;
		}

		private byte[] data(Item item) throws IOException {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			for (long[] extent : item._extents) {
				long offset = item._baseOffset + extent[0];
				long extentLength = extent[1];
				if (item._constructionMethod == 1) {
					if (extentLength == 0) {
						extentLength = _idat.length - offset;
					}
					out.write(_idat, (int) offset, (int) extentLength);
				} else if (item._constructionMethod == 0) {
					if (extentLength == 0) {
						extentLength = _in.length() - offset;
					}
					byte[] buffer = new byte[(int) extentLength];
					_in.seek(offset);
					_in.readFully(buffer);
					out.write(buffer);
				} else {
					throw new IOException("'" + _name + "' refers to its data in a way this server cannot read.");
				}
			}
			return out.toByteArray();
		}

		private Tile tile(long id) throws IOException {
			Item item = _items.get(Long.valueOf(id));
			if (item != null && AV01.equals(item._type)) {
				return av1Tile(item);
			}
			if (item == null || !HVC1.equals(item._type)) {
				throw new IOException("'" + _name + "' is coded as '" + (item == null ? "?" : item._type)
					+ "', which this server cannot decode; only HEVC ('hvc1') and AV1 ('av01') pictures are supported.");
			}
			Box hvcC = property(item, "hvcC");
			if (hvcC == null) {
				throw new IOException("'" + _name + "' carries an HEVC picture without its configuration.");
			}
			int lengthSize = (hvcC.u8(21) & 3) + 1;
			ByteArrayOutputStream sets = new ByteArrayOutputStream();
			int arrays = hvcC.u8(22);
			int at = 23;
			for (int a = 0; a < arrays; a++) {
				at++; // completeness and NAL unit type
				int count = hvcC.u16(at);
				at += 2;
				for (int n = 0; n < count; n++) {
					int size = hvcC.u16(at);
					at += 2;
					sets.write(0);
					sets.write(0);
					sets.write(0);
					sets.write(1);
					sets.write(hvcC._data, hvcC._offset + at, size);
					at += size;
				}
			}
			if (item._constructionMethod == 0) {
				long[] offsets = new long[item._extents.size()];
				long[] lengths = new long[item._extents.size()];
				for (int n = 0; n < offsets.length; n++) {
					offsets[n] = item._baseOffset + item._extents.get(n)[0];
					lengths[n] = item._extents.get(n)[1];
				}
				return new Tile(sets.toByteArray(), lengthSize, offsets, lengths, null);
			}
			return new Tile(sets.toByteArray(), lengthSize, new long[0], new long[0], data(item));
		}

		/**
		 * An AV1 coded picture: the configuration OBUs of its <code>av1C</code> (the sequence header,
		 * which the item's own data repeat as a rule) and the item's OBUs, which carry their own
		 * sizes (the low-overhead bitstream format of AV1-ISOBMFF).
		 */
		private Tile av1Tile(Item item) throws IOException {
			Box av1C = property(item, "av1C");
			if (av1C == null || av1C._length < 4) {
				throw new IOException("'" + _name + "' carries an AV1 picture without its configuration.");
			}
			byte[] sets = av1C.bytes(4, av1C._length - 4);
			if (item._constructionMethod == 0) {
				long[] offsets = new long[item._extents.size()];
				long[] lengths = new long[item._extents.size()];
				for (int n = 0; n < offsets.length; n++) {
					offsets[n] = item._baseOffset + item._extents.get(n)[0];
					lengths[n] = item._extents.get(n)[1];
				}
				return new Tile(sets, 0, offsets, lengths, null);
			}
			return new Tile(sets, 0, new long[0], new long[0], data(item));
		}

		private int[] ispe(Item item) {
			Box ispe = property(item, "ispe");
			if (ispe == null) {
				return null;
			}
			return new int[] { (int) ispe.u32(4), (int) ispe.u32(8) };
		}

		/** The coded pictures of the given item, a grid or a single picture, and its size. */
		private Plane plane(long id, int[] size) throws IOException {
			Item item = _items.get(Long.valueOf(id));
			int columns;
			int rows;
			int width;
			int height;
			List<Tile> tiles = new ArrayList<>();
			int tileWidth;
			int tileHeight;
			Item first;
			if (GRID.equals(item._type)) {
				byte[] grid = data(item);
				if (grid.length < 8) {
					throw new IOException("'" + _name + "' carries a broken grid description.");
				}
				boolean wide = (grid[1] & 1) != 0;
				rows = (grid[2] & 0xFF) + 1;
				columns = (grid[3] & 0xFF) + 1;
				if (wide) {
					if (grid.length < 12) {
						throw new IOException("'" + _name + "' carries a broken grid description.");
					}
					width = (int) Box.u32(grid, 4);
					height = (int) Box.u32(grid, 8);
				} else {
					width = Box.u16(grid, 4);
					height = Box.u16(grid, 6);
				}
				List<Long> ids = _tiles.getOrDefault(Long.valueOf(id), Collections.emptyList());
				if (ids.size() != rows * columns) {
					throw new IOException("'" + _name + "' names " + ids.size() + " tiles for a grid of "
						+ columns + " x " + rows + ".");
				}
				int[] tileSize = null;
				for (Long tileId : ids) {
					tiles.add(tile(tileId.longValue()));
					int[] own = ispe(_items.get(tileId));
					if (tileSize == null) {
						tileSize = own;
					} else if (own != null && (own[0] != tileSize[0] || own[1] != tileSize[1])) {
						throw new IOException("'" + _name + "' carries tiles of different sizes.");
					}
				}
				if (tileSize == null) {
					throw new IOException("'" + _name + "' does not say how large its tiles are.");
				}
				tileWidth = tileSize[0];
				tileHeight = tileSize[1];
				if (width > columns * tileWidth || height > rows * tileHeight) {
					throw new IOException("'" + _name + "' is larger than its tiles.");
				}
				first = _items.get(ids.get(0));
			} else {
				tiles.add(tile(id));
				int[] own = ispe(item);
				if (own == null) {
					throw new IOException("'" + _name + "' does not say how large it is.");
				}
				columns = 1;
				rows = 1;
				width = own[0];
				height = own[1];
				tileWidth = width;
				tileHeight = height;
				first = item;
			}
			if (width <= 0 || height <= 0) {
				throw new IOException("'" + _name + "' has no picture.");
			}
			size[0] = width;
			size[1] = height;

			Boolean fullRange = null;
			int matrix = -1;
			Box colr = property(item, "colr");
			if (colr == null) {
				colr = property(first, "colr");
			}
			if (colr != null && colr._length >= 11 && "nclx".equals(colr.string(0, 4))) {
				matrix = colr.u16(8);
				fullRange = Boolean.valueOf((colr.u8(10) & 0x80) != 0);
			}
			return new Plane(AV01.equals(first._type), columns, rows, tileWidth, tileHeight, tiles, fullRange,
				matrix);
		}

		/**
		 * The alpha channel of the primary picture, <code>null</code> where it has none: the
		 * auxiliary item of the alpha type (not a depth map, which an iPhone writes the same way),
		 * of the primary's size.
		 */
		private Plane alpha(int width, int height) throws IOException {
			for (Map.Entry<Long, List<Long>> entry : _auxiliaries.entrySet()) {
				if (!entry.getValue().contains(Long.valueOf(_primary))) {
					continue;
				}
				Item item = _items.get(entry.getKey());
				if (item == null) {
					continue;
				}
				Box auxC = property(item, "auxC");
				if (auxC == null || auxC._length <= 4 || !ALPHA_URNS.contains(auxC.string(4, auxC.stringEnd(4)))) {
					continue;
				}
				int[] size = new int[2];
				Plane plane = plane(entry.getKey().longValue(), size);
				if (size[0] != width || size[1] != height) {
					throw new IOException("'" + _name + "' carries an alpha channel of " + size[0] + " x " + size[1]
						+ " for a picture of " + width + " x " + height + ".");
				}
				return plane;
			}
			return null;
		}

		HeifFile build() throws IOException {
			Item primary = _items.get(Long.valueOf(_primary));
			if (primary == null) {
				throw new IOException("'" + _name + "' names no primary picture.");
			}

			int[] size = new int[2];
			Plane image = plane(_primary, size);
			int width = size[0];
			int height = size[1];
			Plane alpha = alpha(width, height);
			boolean premultiplied = alpha != null && _premultiplied.containsKey(Long.valueOf(_primary));

			// The transformative properties, in the order they are listed: what is cut and turned.
			double left = 0;
			double top = 0;
			double right = width;
			double bottom = height;
			// Maps the raw raster (the stitched picture) onto the current picture.
			AffineTransform toCurrent = new AffineTransform();
			double currentWidth = width;
			double currentHeight = height;
			for (Integer index : primary._properties) {
				int n = index.intValue() - 1;
				if (n < 0 || n >= _properties.size()) {
					continue;
				}
				Box property = _properties.get(n);
				AffineTransform step;
				switch (property._type) {
					case "irot": {
						int quarters = property.u8(0) & 3;
						step = new AffineTransform();
						for (int q = 0; q < quarters; q++) {
							// A quarter turn anti-clockwise of a picture of the current size.
							AffineTransform turn = new AffineTransform(0, -1, 1, 0, 0, currentWidth);
							step.preConcatenate(turn);
							double swap = currentWidth;
							currentWidth = currentHeight;
							currentHeight = swap;
						}
						break;
					}
					case "imir": {
						// ISO/IEC 23008-12:2022, 6.5.12: mode 0 exchanges top and bottom, mode 1 left
						// and right (libheif and libavif read it so).
						boolean leftRight = (property.u8(0) & 1) != 0;
						step = leftRight
							? new AffineTransform(-1, 0, 0, 1, currentWidth, 0)
							: new AffineTransform(1, 0, 0, -1, 0, currentHeight);
						break;
					}
					case "clap": {
						double cropWidth = fraction(property, 0);
						double cropHeight = fraction(property, 8);
						double offsetX = fraction(property, 16);
						double offsetY = fraction(property, 24);
						double x0 = Math.max(0, Math.round(offsetX + (currentWidth - 1) / 2 - (cropWidth - 1) / 2));
						double y0 = Math.max(0, Math.round(offsetY + (currentHeight - 1) / 2 - (cropHeight - 1) / 2));
						double x1 = Math.min(currentWidth, x0 + Math.round(cropWidth));
						double y1 = Math.min(currentHeight, y0 + Math.round(cropHeight));
						if (x1 <= x0 || y1 <= y0) {
							throw new IOException("'" + _name + "' cuts its picture away.");
						}
						// The rectangle in the raw raster.
						try {
							AffineTransform back = toCurrent.createInverse();
							Point2D a = back.transform(new Point2D.Double(x0, y0), null);
							Point2D b = back.transform(new Point2D.Double(x1, y1), null);
							left = Math.min(a.getX(), b.getX());
							top = Math.min(a.getY(), b.getY());
							right = Math.max(a.getX(), b.getX());
							bottom = Math.max(a.getY(), b.getY());
						} catch (java.awt.geom.NoninvertibleTransformException ex) {
							throw new IOException(ex);
						}
						step = AffineTransform.getTranslateInstance(-x0, -y0);
						currentWidth = x1 - x0;
						currentHeight = y1 - y0;
						break;
					}
					default:
						continue;
				}
				toCurrent.preConcatenate(step);
			}

			int rawLeft = (int) Math.round(left);
			int rawTop = (int) Math.round(top);
			int rawWidth = (int) Math.round(right - left);
			int rawHeight = (int) Math.round(bottom - top);
			Orientation orientation = orientation(toCurrent);

			byte[] exif = null;
			byte[] xmp = null;
			for (Map.Entry<Long, List<Long>> entry : _describes.entrySet()) {
				if (!entry.getValue().contains(Long.valueOf(_primary))) {
					continue;
				}
				Item item = _items.get(entry.getKey());
				if (item == null) {
					continue;
				}
				if ("Exif".equals(item._type) && exif == null) {
					exif = exif(data(item));
				} else if ("mime".equals(item._type) && xmp == null
					&& item._contentType.startsWith("application/rdf+xml")) {
					xmp = data(item);
				}
			}

			return new HeifFile(rawWidth, rawHeight, orientation, rawLeft, rawTop, image, alpha, premultiplied, exif,
				xmp);
		}

		private static double fraction(Box box, int at) {
			long numerator = box.u32(at);
			long denominator = box.u32(at + 4);
			if (denominator == 0) {
				return 0;
			}
			// clap's horizontal and vertical offsets are signed, its sizes are not.
			return at >= 16 ? ((int) numerator) / (double) denominator : numerator / (double) denominator;
		}

		private static byte[] exif(byte[] data) {
			return exifTiff(data);
		}
	}

	/**
	 * The TIFF structure behind the offset field of a HEIF Exif item — and of a JPEG XL
	 * <code>Exif</code> box, which is the same (issue #193) —, <code>null</code> where there is none.
	 */
	public static byte[] exifTiff(byte[] data) {
		if (data.length < 4) {
			return null;
		}
		long offset = Box.u32(data, 0);
		int start = (int) (4 + offset);
		if (offset > data.length || start >= data.length) {
			return null;
		}
		// Some writers count the offset wrongly; the TIFF header says where it really is.
		for (int at = start; at + 4 <= data.length && at < start + 16; at++) {
			if ((data[at] == 'M' && data[at + 1] == 'M' && data[at + 2] == 0 && data[at + 3] == 42)
				|| (data[at] == 'I' && data[at + 1] == 'I' && data[at + 2] == 42 && data[at + 3] == 0)) {
				start = at;
				break;
			}
		}
		byte[] result = new byte[data.length - start];
		System.arraycopy(data, start, result, 0, result.length);
		return result;
	}

	/**
	 * The orientation whose upright transform has the linear part of the given one: the eight
	 * transforms of {@link PreviewCache#orientationTransform} differ in it and in nothing else.
	 */
	static Orientation orientation(AffineTransform rawToShown) {
		for (Orientation candidate : Orientation.values()) {
			AffineTransform tx = PreviewCache.orientationTransform(candidate, 1, 1);
			if (same(tx.getScaleX(), rawToShown.getScaleX()) && same(tx.getShearX(), rawToShown.getShearX())
				&& same(tx.getShearY(), rawToShown.getShearY()) && same(tx.getScaleY(), rawToShown.getScaleY())) {
				return candidate;
			}
		}
		return Orientation.IDENTITY;
	}

	private static boolean same(double a, double b) {
		return Math.abs(a - b) < 1e-9;
	}

	private static String fourCC(int value) {
		return new String(new byte[] { (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value },
			StandardCharsets.ISO_8859_1);
	}

	/** A box read into memory: its type and its body. */
	private static final class Box {

		final String _type;

		final byte[] _data;

		final int _offset;

		final int _length;

		Box(String type, byte[] data, int offset, int length) {
			_type = type;
			_data = data;
			_offset = offset;
			_length = length;
		}

		int version() {
			return u8(0);
		}

		int flags() {
			return (u8(1) << 16) | (u8(2) << 8) | u8(3);
		}

		int u8(int at) {
			check(at, 1);
			return _data[_offset + at] & 0xFF;
		}

		int u16(int at) {
			check(at, 2);
			return u16(_data, _offset + at);
		}

		long u32(int at) {
			check(at, 4);
			return u32(_data, _offset + at);
		}

		long uN(int at, int size) {
			switch (size) {
				case 0:
					return 0;
				case 4:
					return u32(at);
				case 8:
					return (u32(at) << 32) | u32(at + 4);
				default:
					throw new IllegalArgumentException("a field of " + size + " bytes");
			}
		}

		int stringEnd(int at) {
			int end = at;
			while (end < _length && _data[_offset + end] != 0) {
				end++;
			}
			return end;
		}

		String string(int from, int to) {
			check(from, to - from);
			return new String(_data, _offset + from, to - from, StandardCharsets.UTF_8);
		}

		byte[] bytes(int from, int length) {
			check(from, length);
			byte[] result = new byte[length];
			System.arraycopy(_data, _offset + from, result, 0, length);
			return result;
		}

		private void check(int at, int size) {
			if (at < 0 || at + size > _length) {
				throw new IndexOutOfBoundsException("box '" + _type + "' is too short");
			}
		}

		List<Box> children(int start) {
			List<Box> result = new ArrayList<>();
			int at = start;
			while (at + 8 <= _length) {
				long size = u32(at);
				String type = fourCC((int) u32(at + 4));
				int header = 8;
				if (size == 1) {
					size = (u32(at + 8) << 32) | u32(at + 12);
					header = 16;
				} else if (size == 0) {
					size = _length - at;
				}
				if (size < header || at + size > _length) {
					throw new IndexOutOfBoundsException("box '" + type + "' is cut off");
				}
				result.add(new Box(type, _data, _offset + at + header, (int) size - header));
				at += size;
			}
			return result;
		}

		static int u16(byte[] data, int at) {
			return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
		}

		static long u32(byte[] data, int at) {
			return ((long) (data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16) | ((data[at + 2] & 0xFF) << 8)
				| (data[at + 3] & 0xFF);
		}
	}
}
