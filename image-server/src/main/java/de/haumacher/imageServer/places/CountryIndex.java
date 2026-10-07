/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

/**
 * The entries of one GeoNames country file a lookup needs, in a compact spatial index, see issue
 * #234.
 *
 * <h2>Layout</h2>
 *
 * <p>
 * One entry is a slot in a handful of parallel arrays (id, latitude and longitude as
 * <code>float</code> &mdash; a metre's precision &mdash;, the {@link FeatureCodes} index as a byte,
 * the population, the index of its tuple of admin codes, and the start of its UTF-8 name in one
 * byte array): some 25 bytes and the name per entry, no object per entry. Only the codes
 * {@link FeatureCodes} keeps are read at all; of Germany's 220&nbsp;000 lines that leaves about
 * 150&nbsp;000.
 * </p>
 *
 * <p>
 * The arrays are ordered as an implicit k-d tree: the middle slot of a range splits it, by
 * latitude at even depths and by longitude at odd ones, with nothing smaller on its right and
 * nothing greater on its left. A box query descends only into the halves the box reaches, so a
 * lookup visits the few hundred entries around a position, not the country.
 * </p>
 *
 * <h2>Cache file</h2>
 *
 * <p>
 * Reading the text dump of a large country takes seconds (the United States' 2.2 million lines),
 * so the index is written to a cache file once and read back with a few bulk reads, see
 * {@link #write(Path, long)} and {@link #read(Path, long)}: a header (magic, format, the
 * {@link FeatureCodes#fingerprint() selection}, the stamp of the source files, country code and
 * version), then each array as a block. A stamp that does not match is a stale file, and the
 * index is built from the dump again.
 * </p>
 *
 * <p>
 * An index is never changed once built; every lookup method may be called from any thread.
 * </p>
 */
public final class CountryIndex {

	private static final int MAGIC = 0x5641504C; // "VAPL"

	private static final int FORMAT = 1;

	/** The GeoNames columns, see readme.txt of the dump. */
	private static final int ID = 0, NAME = 1, LAT = 4, LON = 5, CLASS = 6, CODE = 7, ADMIN1 = 10,
			POPULATION = 14, MODIFIED = 18;

	private final String _country;

	private final String _version;

	private final int _size;

	private final int[] _ids;

	private final float[] _lats;

	private final float[] _lons;

	private final byte[] _codes;

	private final int[] _populations;

	private final int[] _admins;

	private final int[] _nameStarts;

	private final byte[] _names;

	/** The distinct tuples of admin codes, each four codes (an empty one for a missing level). */
	private final String[][] _adminPaths;

	/** The names of divisions the country file has no ADM entry for, by {@link #adminKey}. */
	private final Map<String, Fallback> _fallbacks;

	/**
	 * The least population of a place in {@link #populous()}: a smaller one stands for a circle of
	 * 4&nbsp;km at most (see {@link Places}), which a search of the surroundings finds.
	 */
	public static final int POPULOUS = 50_000;

	/** The populated places of {@link #POPULOUS} people or more; derived at construction. */
	private final int[] _populous;

	/** The ADM entry of each division, by {@link #adminKey}; derived at construction. */
	private final Map<String, Integer> _adminEntries = new HashMap<>();

	/**
	 * A division named by <code>admin1CodesASCII.txt</code> or <code>admin2Codes.txt</code> only.
	 *
	 * @param id
	 *        Its GeoNames id.
	 * @param name
	 *        Its name.
	 */
	public record Fallback(int id, String name) {
		// A pair.
	}

	/** Receives the entries a {@link CountryIndex#near(double, double, double, Visitor)} finds. */
	public interface Visitor {
		/**
		 * Called for every entry within the radius.
		 *
		 * @param entry
		 *        The slot of the entry, for the accessors of the index.
		 * @param distance
		 *        Its distance in kilometres.
		 */
		void visit(int entry, double distance);
	}

	private CountryIndex(String country, String version, int size, int[] ids, float[] lats, float[] lons,
			byte[] codes, int[] populations, int[] admins, int[] nameStarts, byte[] names, String[][] adminPaths,
			Map<String, Fallback> fallbacks) {
		_country = country;
		_version = version;
		_size = size;
		_ids = ids;
		_lats = lats;
		_lons = lons;
		_codes = codes;
		_populations = populations;
		_admins = admins;
		_nameStarts = nameStarts;
		_names = names;
		_adminPaths = adminPaths;
		_fallbacks = fallbacks;
		int[] populous = new int[16];
		int populousCount = 0;
		for (int n = 0; n < size; n++) {
			if (FeatureCodes.role(_codes[n]) == FeatureCodes.Role.PLACE && _populations[n] >= POPULOUS) {
				if (populousCount == populous.length) {
					populous = Arrays.copyOf(populous, populousCount * 2);
				}
				populous[populousCount++] = n;
			}
			if (FeatureCodes.role(_codes[n]) == FeatureCodes.Role.ADMIN) {
				String key = adminKey(_adminPaths[_admins[n]], FeatureCodes.adminLevel(_codes[n]));
				if (key != null) {
					_adminEntries.putIfAbsent(key, Integer.valueOf(n));
				}
			}
		}
		_populous = Arrays.copyOf(populous, populousCount);
	}

	/**
	 * The populated places of {@link #POPULOUS} people or more, whose circle may reach further than
	 * a search of the surroundings: a few dozen in Germany, a few hundred in the United States. The
	 * array is the index's own and must not be changed.
	 */
	int[] populous() {
		return _populous;
	}

	/** The distance of an entry from a position, in kilometres. */
	public double distance(int entry, double lat, double lon) {
		return Geo.distance(lat, lon, _lats[entry], _lons[entry]);
	}

	/** The ISO code of the country. */
	public String getCountry() {
		return _country;
	}

	/**
	 * The version of the data: the latest modification date in the country file and a checksum of
	 * its content and of the country's lines of the admin tables, e.g.
	 * <code>2026-10-06-1a2b3c4d</code>. Changes exactly where the data does.
	 */
	public String getVersion() {
		return _version;
	}

	/** The number of entries kept. */
	public int size() {
		return _size;
	}

	/** The GeoNames id of an entry. */
	public int id(int entry) {
		return _ids[entry];
	}

	/** The name of an entry. */
	public String name(int entry) {
		return new String(_names, _nameStarts[entry], _nameStarts[entry + 1] - _nameStarts[entry],
			StandardCharsets.UTF_8);
	}

	/** The {@link FeatureCodes} index of an entry. */
	public int code(int entry) {
		return _codes[entry];
	}

	/** The population of an entry, <code>0</code> where GeoNames knows none. */
	public int population(int entry) {
		return _populations[entry];
	}

	/** The latitude of an entry. */
	public double lat(int entry) {
		return _lats[entry];
	}

	/** The longitude of an entry. */
	public double lon(int entry) {
		return _lons[entry];
	}

	/** The admin codes of an entry, four of them, an empty one for a level it has no code for. */
	public String[] adminCodes(int entry) {
		return _adminPaths[_admins[entry]];
	}

	/**
	 * The division of the given level the given admin codes name.
	 *
	 * @return The tag, <code>null</code> where a code up to that level is missing or the division
	 *         is unknown.
	 */
	public PlaceTag admin(String[] codes, int level) {
		String key = adminKey(codes, level);
		if (key == null) {
			return null;
		}
		PlaceKind kind = PlaceKind.values()[PlaceKind.ADM1.ordinal() + level - 1];
		Integer entry = _adminEntries.get(key);
		if (entry != null) {
			int n = entry.intValue();
			return new PlaceTag(id(n), name(n), kind, FeatureCodes.code(code(n)), _country);
		}
		Fallback fallback = _fallbacks.get(key);
		if (fallback != null) {
			return new PlaceTag(fallback.id(), fallback.name(), kind, "ADM" + level, _country);
		}
		return null;
	}

	/** The key of a division: its codes up to the level joined by dots, <code>null</code> for a gap. */
	static String adminKey(String[] codes, int level) {
		StringBuilder result = new StringBuilder();
		for (int n = 0; n < level; n++) {
			String code = codes[n];
			if (code.isEmpty()) {
				return null;
			}
			if (n > 0) {
				result.append('.');
			}
			result.append(code);
		}
		return result.toString();
	}

	/**
	 * Visits every entry within the given distance of a position.
	 *
	 * @param radius
	 *        In kilometres.
	 */
	public void near(double lat, double lon, double radius, Visitor visitor) {
		double dLat = radius / Geo.KM_PER_DEGREE;
		double latMin = lat - dLat;
		double latMax = lat + dLat;
		double maxAbsLat = Math.min(89.9, Math.max(Math.abs(latMin), Math.abs(latMax)));
		double dLon = radius / (Geo.KM_PER_DEGREE * Math.cos(Math.toRadians(maxAbsLat)));
		if (dLon >= 180) {
			box(lat, lon, radius, latMin, latMax, -180, 180, visitor);
			return;
		}
		double lonMin = lon - dLon;
		double lonMax = lon + dLon;
		box(lat, lon, radius, latMin, latMax, lonMin, lonMax, visitor);
		// A box across the date line is the part on the other side as well.
		if (lonMin < -180) {
			box(lat, lon, radius, latMin, latMax, lonMin + 360, 180, visitor);
		}
		if (lonMax > 180) {
			box(lat, lon, radius, latMin, latMax, -180, lonMax - 360, visitor);
		}
	}

	private void box(double lat, double lon, double radius, double latMin, double latMax, double lonMin,
			double lonMax, Visitor visitor) {
		visit(0, _size, 0, lat, lon, Math.cos(Math.toRadians(lat)), radius, latMin, latMax, lonMin, lonMax, visitor);
	}

	private void visit(int lo, int hi, int depth, double lat, double lon, double scale, double radius,
			double latMin, double latMax, double lonMin, double lonMax, Visitor visitor) {
		while (lo < hi) {
			int mid = (lo + hi) >>> 1;
			float entryLat = _lats[mid];
			float entryLon = _lons[mid];
			if (entryLat >= latMin && entryLat <= latMax && entryLon >= lonMin && entryLon <= lonMax) {
				double distance = Geo.near(lat, lon, scale, entryLat, entryLon);
				if (distance <= radius) {
					visitor.visit(mid, distance);
				}
			}
			float key = (depth & 1) == 0 ? entryLat : entryLon;
			double min = (depth & 1) == 0 ? latMin : lonMin;
			double max = (depth & 1) == 0 ? latMax : lonMax;
			boolean left = min <= key;
			boolean right = max >= key;
			if (left && right) {
				visit(lo, mid, depth + 1, lat, lon, scale, radius, latMin, latMax, lonMin, lonMax, visitor);
				lo = mid + 1;
			} else if (left) {
				hi = mid;
			} else {
				lo = mid + 1;
			}
			depth++;
		}
	}

	/**
	 * Reads the index of a country from its GeoNames dump.
	 *
	 * @param country
	 *        The ISO code.
	 * @param in
	 *        The text of <code>&lt;CC&gt;.txt</code>, UTF-8; not closed.
	 * @param fallbacks
	 *        The divisions <code>admin1CodesASCII.txt</code> and <code>admin2Codes.txt</code> name
	 *        for this country, by {@link #adminKey}; used where the dump has no ADM entry.
	 * @param fallbackChecksum
	 *        The checksum of the lines the fallbacks were read from, part of the version.
	 */
	public static CountryIndex parse(String country, InputStream in, Map<String, Fallback> fallbacks,
			long fallbackChecksum) throws IOException {
		Builder builder = new Builder();
		CRC32 crc = new CRC32();
		String latest = "";
		BufferedReader reader = new BufferedReader(
			new InputStreamReader(new CheckedInputStream(in, crc), StandardCharsets.UTF_8), 1 << 16);
		String[] fields = new String[19];
		int lineNumber = 0;
		String line;
		while ((line = reader.readLine()) != null) {
			lineNumber++;
			if (line.isEmpty()) {
				continue;
			}
			int count = split(line, fields);
			if (count < 15) {
				throw new IOException("Not a GeoNames country file: line " + lineNumber + " has " + count
					+ " columns.");
			}
			if (count > MODIFIED && fields[MODIFIED].compareTo(latest) > 0) {
				latest = fields[MODIFIED];
			}
			int code = FeatureCodes.indexOf(fields[CLASS], fields[CODE]);
			if (code < 0) {
				continue;
			}
			int id;
			float lat;
			float lon;
			try {
				id = Integer.parseInt(fields[ID]);
				lat = Float.parseFloat(fields[LAT]);
				lon = Float.parseFloat(fields[LON]);
			} catch (NumberFormatException ex) {
				throw new IOException("Not a GeoNames country file: line " + lineNumber + " is no entry.");
			}
			long population = parsePopulation(fields[POPULATION]);
			builder.add(id, fields[NAME], lat, lon, code, (int) Math.min(Integer.MAX_VALUE, population),
				fields[ADMIN1], fields[ADMIN1 + 1], fields[ADMIN1 + 2], fields[ADMIN1 + 3]);
		}
		if (lineNumber == 0) {
			throw new IOException("The GeoNames country file is empty.");
		}
		long sum = crc.getValue() * 31 + fallbackChecksum;
		String version = (latest.isEmpty() ? "undated" : latest) + "-" + String.format("%08x", (int) (sum ^ (sum >>> 32)));
		return builder.build(country, version, fallbacks);
	}

	private static long parsePopulation(String text) {
		if (text.isEmpty()) {
			return 0;
		}
		try {
			return Math.max(0, Long.parseLong(text));
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	/** Splits a line at its tabs into the given array; answers the number of fields. */
	static int split(String line, String[] fields) {
		int count = 0;
		int start = 0;
		while (count < fields.length) {
			int end = line.indexOf('\t', start);
			if (end < 0) {
				fields[count++] = line.substring(start);
				break;
			}
			fields[count++] = line.substring(start, end);
			start = end + 1;
		}
		for (int n = count; n < fields.length; n++) {
			fields[n] = "";
		}
		return count;
	}

	/** Collects entries in growing arrays and orders them into the tree. */
	private static final class Builder {

		private int _size;

		private int[] _ids = new int[1024];

		private float[] _lats = new float[1024];

		private float[] _lons = new float[1024];

		private byte[] _codes = new byte[1024];

		private int[] _populations = new int[1024];

		private int[] _admins = new int[1024];

		private String[] _names = new String[1024];

		private final Map<String, Integer> _adminIndex = new HashMap<>();

		private String[][] _adminPaths = new String[64][];

		void add(int id, String name, float lat, float lon, int code, int population, String a1, String a2,
				String a3, String a4) {
			if (_size == _ids.length) {
				int capacity = _size * 2;
				_ids = Arrays.copyOf(_ids, capacity);
				_lats = Arrays.copyOf(_lats, capacity);
				_lons = Arrays.copyOf(_lons, capacity);
				_codes = Arrays.copyOf(_codes, capacity);
				_populations = Arrays.copyOf(_populations, capacity);
				_admins = Arrays.copyOf(_admins, capacity);
				_names = Arrays.copyOf(_names, capacity);
			}
			_ids[_size] = id;
			_lats[_size] = lat;
			_lons[_size] = lon;
			_codes[_size] = (byte) code;
			_populations[_size] = population;
			_admins[_size] = admin(a1, a2, a3, a4);
			_names[_size] = name;
			_size++;
		}

		private int admin(String a1, String a2, String a3, String a4) {
			String key = a1 + '\t' + a2 + '\t' + a3 + '\t' + a4;
			Integer existing = _adminIndex.get(key);
			if (existing != null) {
				return existing.intValue();
			}
			int index = _adminIndex.size();
			if (index == _adminPaths.length) {
				_adminPaths = Arrays.copyOf(_adminPaths, index * 2);
			}
			_adminPaths[index] = new String[] { unique(a1), unique(a2), unique(a3), unique(a4) };
			_adminIndex.put(key, Integer.valueOf(index));
			return index;
		}

		private final Map<String, String> _codeStrings = new HashMap<>();

		/** One string object per distinct code: a tuple shares its codes with its neighbours'. */
		private String unique(String code) {
			return _codeStrings.computeIfAbsent(code, c -> c);
		}

		CountryIndex build(String country, String version, Map<String, Fallback> fallbacks) {
			int[] order = new int[_size];
			for (int n = 0; n < _size; n++) {
				order[n] = n;
			}
			tree(order, 0, _size, 0);
			int[] ids = new int[_size];
			float[] lats = new float[_size];
			float[] lons = new float[_size];
			byte[] codes = new byte[_size];
			int[] populations = new int[_size];
			int[] admins = new int[_size];
			int[] nameStarts = new int[_size + 1];
			ByteBuffer names = ByteBuffer.allocate(Math.max(16, _size * 12));
			for (int n = 0; n < _size; n++) {
				int from = order[n];
				ids[n] = _ids[from];
				lats[n] = _lats[from];
				lons[n] = _lons[from];
				codes[n] = _codes[from];
				populations[n] = _populations[from];
				admins[n] = _admins[from];
				byte[] name = _names[from].getBytes(StandardCharsets.UTF_8);
				if (names.remaining() < name.length) {
					ByteBuffer larger = ByteBuffer.allocate(Math.max(names.capacity() * 2, names.position() + name.length));
					names.flip();
					larger.put(names);
					names = larger;
				}
				nameStarts[n] = names.position();
				names.put(name);
			}
			nameStarts[_size] = names.position();
			byte[] nameBytes = Arrays.copyOf(names.array(), names.position());
			return new CountryIndex(country, version, _size, ids, lats, lons, codes, populations, admins,
				nameStarts, nameBytes, Arrays.copyOf(_adminPaths, _adminIndex.size()), Map.copyOf(fallbacks));
		}

		/** Orders the slots of the range into an implicit k-d tree, see {@link CountryIndex}. */
		private void tree(int[] order, int lo, int hi, int depth) {
			while (hi - lo > 1) {
				int mid = (lo + hi) >>> 1;
				float[] keys = (depth & 1) == 0 ? _lats : _lons;
				select(order, keys, lo, hi - 1, mid);
				tree(order, lo, mid, depth + 1);
				lo = mid + 1;
				depth++;
			}
		}

		/** Quickselect: puts the k-th smallest key of the range at k, smaller left, greater right. */
		private static void select(int[] order, float[] keys, int left, int right, int k) {
			while (right > left) {
				int pivotIndex = (left + right) >>> 1;
				float pivot = keys[order[pivotIndex]];
				int i = left;
				int j = right;
				while (i <= j) {
					while (keys[order[i]] < pivot) {
						i++;
					}
					while (keys[order[j]] > pivot) {
						j--;
					}
					if (i <= j) {
						int tmp = order[i];
						order[i] = order[j];
						order[j] = tmp;
						i++;
						j--;
					}
				}
				if (k <= j) {
					right = j;
				} else if (k >= i) {
					left = i;
				} else {
					return;
				}
			}
		}
	}

	/**
	 * Writes the index to a cache file, atomically: a reader finds the old file or the whole new
	 * one.
	 *
	 * @param stamp
	 *        What the source files were when this was built, see {@link #read(Path, long)}.
	 */
	public void write(Path file, long stamp) throws IOException {
		byte[] country = _country.getBytes(StandardCharsets.UTF_8);
		byte[] version = _version.getBytes(StandardCharsets.UTF_8);
		int adminBytes = 4;
		byte[][] paths = new byte[_adminPaths.length][];
		for (int n = 0; n < _adminPaths.length; n++) {
			paths[n] = String.join("\t", _adminPaths[n]).getBytes(StandardCharsets.UTF_8);
			adminBytes += 4 + paths[n].length;
		}
		byte[][] fallbackKeys = new byte[_fallbacks.size()][];
		byte[][] fallbackNames = new byte[_fallbacks.size()][];
		int[] fallbackIds = new int[_fallbacks.size()];
		int fallbackBytes = 4;
		int f = 0;
		for (Map.Entry<String, Fallback> entry : _fallbacks.entrySet()) {
			fallbackKeys[f] = entry.getKey().getBytes(StandardCharsets.UTF_8);
			fallbackNames[f] = entry.getValue().name().getBytes(StandardCharsets.UTF_8);
			fallbackIds[f] = entry.getValue().id();
			fallbackBytes += 12 + fallbackKeys[f].length + fallbackNames[f].length;
			f++;
		}
		long length = 4L * 4 + 8 + 8 + country.length + version.length + 4 + (long) _size * (4 + 4 + 4 + 1 + 4 + 4 + 4)
			+ 4 + 4 + _names.length + adminBytes + fallbackBytes;
		if (length > Integer.MAX_VALUE) {
			throw new IOException("The index of " + _country + " is too large for a cache file.");
		}
		ByteBuffer out = ByteBuffer.allocate((int) length);
		out.putInt(MAGIC).putInt(FORMAT).putInt(FeatureCodes.fingerprint()).putLong(stamp);
		putBytes(out, country);
		putBytes(out, version);
		out.putInt(_size);
		out.asIntBuffer().put(_ids, 0, _size);
		out.position(out.position() + 4 * _size);
		out.asFloatBuffer().put(_lats, 0, _size);
		out.position(out.position() + 4 * _size);
		out.asFloatBuffer().put(_lons, 0, _size);
		out.position(out.position() + 4 * _size);
		out.put(_codes, 0, _size);
		out.asIntBuffer().put(_populations, 0, _size);
		out.position(out.position() + 4 * _size);
		out.asIntBuffer().put(_admins, 0, _size);
		out.position(out.position() + 4 * _size);
		out.asIntBuffer().put(_nameStarts, 0, _size + 1);
		out.position(out.position() + 4 * (_size + 1));
		putBytes(out, _names);
		out.putInt(paths.length);
		for (byte[] path : paths) {
			putBytes(out, path);
		}
		out.putInt(fallbackIds.length);
		for (int n = 0; n < fallbackIds.length; n++) {
			putBytes(out, fallbackKeys[n]);
			out.putInt(fallbackIds[n]);
			putBytes(out, fallbackNames[n]);
		}
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.write(tmp, Arrays.copyOf(out.array(), out.position()));
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	private static void putBytes(ByteBuffer out, byte[] bytes) {
		out.putInt(bytes.length);
		out.put(bytes);
	}

	/**
	 * Reads an index from its cache file.
	 *
	 * @param stamp
	 *        What the source files are now; a file built from others is stale.
	 * @return The index, <code>null</code> where the file is missing, stale, of another format or
	 *         broken: then it is to be built from the dump.
	 */
	public static CountryIndex read(Path file, long stamp) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			ByteBuffer in = ByteBuffer.wrap(Files.readAllBytes(file));
			if (in.getInt() != MAGIC || in.getInt() != FORMAT || in.getInt() != FeatureCodes.fingerprint()
				|| in.getLong() != stamp) {
				return null;
			}
			String country = getString(in);
			String version = getString(in);
			int size = in.getInt();
			int[] ids = new int[size];
			in.asIntBuffer().get(ids);
			in.position(in.position() + 4 * size);
			float[] lats = new float[size];
			in.asFloatBuffer().get(lats);
			in.position(in.position() + 4 * size);
			float[] lons = new float[size];
			in.asFloatBuffer().get(lons);
			in.position(in.position() + 4 * size);
			byte[] codes = new byte[size];
			in.get(codes);
			for (byte code : codes) {
				if (code < 0 || code >= FeatureCodes.size()) {
					return null;
				}
			}
			int[] populations = new int[size];
			in.asIntBuffer().get(populations);
			in.position(in.position() + 4 * size);
			int[] admins = new int[size];
			in.asIntBuffer().get(admins);
			in.position(in.position() + 4 * size);
			int[] nameStarts = new int[size + 1];
			in.asIntBuffer().get(nameStarts);
			in.position(in.position() + 4 * (size + 1));
			byte[] names = getBytes(in);
			int pathCount = in.getInt();
			String[][] paths = new String[pathCount][];
			Map<String, String> codeStrings = new HashMap<>();
			for (int n = 0; n < pathCount; n++) {
				String[] codesOfPath = new String[4];
				split(getString(in), codesOfPath);
				for (int level = 0; level < 4; level++) {
					codesOfPath[level] = codeStrings.computeIfAbsent(codesOfPath[level], c -> c);
				}
				paths[n] = codesOfPath;
			}
			for (int admin : admins) {
				if (admin < 0 || admin >= pathCount) {
					return null;
				}
			}
			int fallbackCount = in.getInt();
			Map<String, Fallback> fallbacks = new HashMap<>();
			for (int n = 0; n < fallbackCount; n++) {
				String key = getString(in);
				int id = in.getInt();
				fallbacks.put(key, new Fallback(id, getString(in)));
			}
			if (in.hasRemaining() || nameStarts[size] != names.length) {
				return null;
			}
			return new CountryIndex(country, version, size, ids, lats, lons, codes, populations, admins,
				nameStarts, names, paths, Map.copyOf(fallbacks));
		} catch (IOException | RuntimeException ex) {
			// Truncated or garbled: built anew from the dump.
			return null;
		}
	}

	private static byte[] getBytes(ByteBuffer in) {
		byte[] result = new byte[in.getInt()];
		in.get(result);
		return result;
	}

	private static String getString(ByteBuffer in) {
		return new String(getBytes(in), StandardCharsets.UTF_8);
	}
}
