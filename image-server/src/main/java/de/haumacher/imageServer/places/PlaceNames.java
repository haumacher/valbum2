/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The names of the places of one country in one language, from GeoNames' alternate names, see
 * issue #234.
 *
 * <h2>Which name</h2>
 *
 * <p>
 * Of the lines of <code>alternatenames/&lt;CC&gt;.txt</code> in the language (alternate name id,
 * GeoNames id, language, name, <code>isPreferredName</code>, <code>isShortName</code>,
 * <code>isColloquial</code>, <code>isHistoric</code>, ...), a historic or colloquial name is never
 * taken. A preferred name wins over any other; among the preferred and among the others a short name
 * is taken only where there is no other, and the first line wins among equals. So Bavaria is
 * "Bavaria" in English (preferred and short) and not "Free State of Bavaria" (neither). The one
 * exception is the country itself, whose main name is the short one (<code>countryInfo.txt</code>
 * says "Germany"): there the preferred short name wins, "Deutschland" and not "Bundesrepublik
 * Deutschland". Only the entries a tag can carry are kept
 * ({@link CountryIndex#keptIds()} and the country itself): of Germany's alternate names in German a
 * few tens of thousands. A place without a name in the language is called by its main name.
 * </p>
 *
 * <h2>Cache file</h2>
 *
 * <p>
 * Reading the alternate names of a country takes a second or more, so the names of one language
 * are written to <code>alternatenames/&lt;CC&gt;.&lt;lang&gt;.names</code> once and read back from
 * there (sorted ids, then the names), as long as it was made from the same files.
 * </p>
 */
public final class PlaceNames {

	private static final int MAGIC = 0x56414E4D; // "VANM"

	private static final int FORMAT = 1;

	/** A language GeoNames' alternate names are filed under: ISO 639, two or three letters. */
	private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");

	private final String _country;

	private final String _language;

	private final int[] _ids;

	private final String[] _names;

	private PlaceNames(String country, String language, int[] ids, String[] names) {
		_country = country;
		_language = language;
		_ids = ids;
		_names = names;
	}

	/** The country, its ISO code. */
	public String getCountry() {
		return _country;
	}

	/** The language, its ISO 639 code. */
	public String getLanguage() {
		return _language;
	}

	/** How many places have a name in the language. */
	public int size() {
		return _ids.length;
	}

	/** The name of the given GeoNames entry in the language, <code>null</code> where it has none. */
	public String name(int geonameId) {
		int index = Arrays.binarySearch(_ids, geonameId);
		return index < 0 ? null : _names[index];
	}

	/** About how many bytes of heap these names hold, see {@link Places#setBudget(long)}. */
	public long memory() {
		long result = 64 + 4L * _ids.length + 16L + 4L * _names.length;
		for (String name : _names) {
			// A string object and its array, a byte per character for Latin, two for the rest.
			result += 40 + (isLatin1(name) ? name.length() : 2L * name.length());
		}
		return result;
	}

	private static boolean isLatin1(String name) {
		for (int n = 0; n < name.length(); n++) {
			if (name.charAt(n) > 0xFF) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The language a caller asked for in an <code>Accept-Language</code> header: of the languages
	 * with the highest weight the first, as its primary tag (<code>en-GB</code> is <code>en</code>);
	 * <code>null</code> where it names none (then the main names are answered).
	 */
	public static String language(String acceptLanguage) {
		if (acceptLanguage == null || acceptLanguage.isBlank()) {
			return null;
		}
		String best = null;
		double bestWeight = 0;
		for (String range : acceptLanguage.split(",")) {
			String[] parts = range.trim().split(";");
			String tag = parts[0].trim().toLowerCase(Locale.ROOT);
			double weight = 1;
			for (int n = 1; n < parts.length; n++) {
				String parameter = parts[n].trim();
				if (parameter.startsWith("q=")) {
					try {
						weight = Double.parseDouble(parameter.substring(2).trim());
					} catch (NumberFormatException ex) {
						weight = 0;
					}
				}
			}
			int dash = tag.indexOf('-');
			String primary = dash < 0 ? tag : tag.substring(0, dash);
			int underscore = primary.indexOf('_');
			if (underscore >= 0) {
				primary = primary.substring(0, underscore);
			}
			if (weight > bestWeight && LANGUAGE.matcher(primary).matches()) {
				best = primary;
				bestWeight = weight;
			}
		}
		return best;
	}

	/**
	 * Reads the names of one language out of a country's alternate names.
	 *
	 * @param in
	 *        The lines of <code>alternatenames/&lt;CC&gt;.txt</code>; not closed.
	 * @param kept
	 *        The ids to keep, sorted.
	 * @param countryId
	 *        The GeoNames id of the country itself, see {@link PlaceNames}.
	 */
	public static PlaceNames parse(String country, String language, InputStream in, int[] kept, int countryId)
			throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
		// Per kept id: the rank of the name chosen so far (0 none) and the name.
		int[] ranks = new int[kept.length];
		String[] names = new String[kept.length];
		String[] fields = new String[10];
		String line;
		while ((line = reader.readLine()) != null) {
			// The language is the third column: a cheap test before the line is split.
			int first = line.indexOf('\t');
			int second = first < 0 ? -1 : line.indexOf('\t', first + 1);
			int third = second < 0 ? -1 : line.indexOf('\t', second + 1);
			if (third < 0 || third - second - 1 != language.length() || !line.startsWith(language, second + 1)) {
				continue;
			}
			int count = CountryIndex.split(line, fields);
			if (count < 4 || fields[3].isEmpty()) {
				continue;
			}
			if (flag(fields, count, 6) || flag(fields, count, 7)) {
				// Colloquial or historic.
				continue;
			}
			int id;
			try {
				id = Integer.parseInt(fields[1]);
			} catch (NumberFormatException ex) {
				continue;
			}
			int index = Arrays.binarySearch(kept, id);
			if (index < 0) {
				continue;
			}
			boolean preferred = flag(fields, count, 4);
			boolean shortName = flag(fields, count, 5);
			int rank = preferred ? (shortName == (id == countryId) ? 4 : 3) : (shortName ? 1 : 2);
			if (rank > ranks[index]) {
				ranks[index] = rank;
				names[index] = fields[3];
			}
		}
		List<Integer> found = new ArrayList<>();
		for (int n = 0; n < kept.length; n++) {
			if (names[n] != null) {
				found.add(Integer.valueOf(n));
			}
		}
		int[] ids = new int[found.size()];
		String[] chosen = new String[found.size()];
		for (int n = 0; n < ids.length; n++) {
			int index = found.get(n).intValue();
			ids[n] = kept[index];
			chosen[n] = names[index];
		}
		return new PlaceNames(country, language, ids, chosen);
	}

	private static boolean flag(String[] fields, int count, int column) {
		return column < count && "1".equals(fields[column].trim());
	}

	/** Writes the names to a cache file, atomically; see {@link #read(Path, long)}. */
	public void write(Path file, long stamp) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			try (OutputStream stream = Files.newOutputStream(tmp);
					DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(stream, 1 << 16))) {
				out.writeInt(MAGIC);
				out.writeInt(FORMAT);
				out.writeLong(stamp);
				out.writeUTF(_country);
				out.writeUTF(_language);
				out.writeInt(_ids.length);
				for (int id : _ids) {
					out.writeInt(id);
				}
				for (String name : _names) {
					byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
					out.writeInt(bytes.length);
					out.write(bytes);
				}
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/**
	 * Reads names from their cache file.
	 *
	 * @param stamp
	 *        What the source files are now.
	 * @return The names, <code>null</code> where the file is missing, stale or broken.
	 */
	public static PlaceNames read(Path file, long stamp) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
			if (in.readInt() != MAGIC || in.readInt() != FORMAT || in.readLong() != stamp) {
				return null;
			}
			String country = in.readUTF();
			String language = in.readUTF();
			int size = in.readInt();
			int[] ids = new int[size];
			for (int n = 0; n < size; n++) {
				ids[n] = in.readInt();
			}
			String[] names = new String[size];
			for (int n = 0; n < size; n++) {
				byte[] bytes = new byte[in.readInt()];
				in.readFully(bytes);
				names[n] = new String(bytes, StandardCharsets.UTF_8);
			}
			return new PlaceNames(country, language, ids, names);
		} catch (IOException | RuntimeException ex) {
			return null;
		}
	}
}
