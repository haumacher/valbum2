/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

import de.haumacher.imageServer.places.CountryShapes.Candidate;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The place tags of a position, from the offline GeoNames gazetteer, see issue #234.
 *
 * <p>
 * {@link #lookup(double, double)} turns a latitude and longitude into a hierarchy of GeoNames
 * entries: the country, its administrative divisions, the populated place, the part of town and a
 * named feature close by. Nothing leaves the server: the data are GeoNames' public dump files,
 * which a {@link GeoNamesStore} downloads by their names or an operator places by hand.
 * </p>
 *
 * <h2>The rules</h2>
 *
 * <ol>
 * <li><b>Country.</b> The outlines of <code>shapes_simplified_low.json</code> name the countries
 * whose outline contains the position or passes within {@link #BORDER_MARGIN} of it. One outline
 * that contains it with no other near: that country. Otherwise (close to a border, or off a coast
 * the simplified outline cuts) every candidate's file is read, and the country of the nearest
 * populated place, part of town or municipality within {@link #ANCHOR_RADIUS} wins &mdash; a
 * village is a far better witness of a border than an outline simplified to kilometres. The
 * outline still counts: a country whose outline does not hold the position needs a witness at
 * less than half the distance ({@link #OUTSIDE_PENALTY}) of the containing one's. Where none
 * of them has one, a containing outline wins, and outside every outline there is no country:
 * the open sea gets no tags at all, never a town a hundred kilometres away.</li>
 * <li><b>Administrative divisions.</b> Every GeoNames entry carries the codes of the divisions
 * it lies in, some down to the municipality, some (a part of town entered by hand) only to the
 * state. Of the entries within {@link #LOCAL_RADIUS} (else {@link #ADMIN_RADIUS}), the nearest of
 * those with the most levels names the divisions; each level
 * (<code>ADM1</code> to <code>ADM4</code>) is the country file's ADM entry of those codes, or the
 * name in <code>admin1CodesASCII.txt</code> / <code>admin2Codes.txt</code> where the file has none.
 * A level GeoNames has no code for in that country is left out.</li>
 * <li><b>Populated place.</b> Every populated place stands for a circle: its radius grows with the
 * square root of its population (the area of a town at {@link #PEOPLE_PER_KM2} people per square
 * kilometre: a town of 300&nbsp;000 is 10&nbsp;km, a village without a known population the
 * {@link FeatureCodes#radius(int) least radius}, 2&nbsp;km, a farm village or locality 1&nbsp;km),
 * capped at {@link #MAX_PLACE_RADIUS}. Of the places whose circle holds the position, the one it
 * lies deepest in (the least distance relative to the radius) is the place; a tie goes to the
 * larger one. Outside every circle there is no place: a photo on a mountain pass is not "in" the
 * town in the valley. A place that shares its municipality (the admin codes down to the third
 * level or deeper) with a larger one whose circle holds the position too is a part of that one,
 * and the larger is the place: Paris and not the arrondissement GeoNames records as a town.</li>
 * <li><b>Part of town.</b> The same among the parts of town (<code>PPLX</code>), with at least
 * 1.5&nbsp;km and at most 5&nbsp;km, and only one whose admin codes agree with the place's, so a
 * district of the neighbouring town is never named.</li>
 * <li><b>Feature.</b> The same among the {@link FeatureCodes} features, each kind with its own
 * radius; a tie goes to the smaller (more specific) one. One feature at most.</li>
 * </ol>
 *
 * <h2>Data and versions</h2>
 *
 * <p>
 * A country's index is loaded on its first lookup ({@link CountryIndex}), from a cache file
 * <code>&lt;CC&gt;.places</code> beside the dump where that matches the dump, else from the dump
 * (and the cache file written). A missing file makes the result
 * {@link PlaceResult#isAvailable() unavailable}, with the reason, and the store fetches it.
 * </p>
 *
 * <p>
 * Every result names the {@link PlaceResult#getVersion() version} it was made from: the rules
 * ({@link #RULES}), the country, and the {@link CountryIndex#getVersion() version of its data}
 * (<code>geonames/1/DE/2026-10-06-1a2b3c4d</code>), or the outlines' checksum for a position in no
 * country (<code>geonames/1/-/5e6f7a8b</code>). {@link #isCurrent(String)} tells whether a stored
 * version is still the one a lookup would use, so that only the photos of a refreshed country are
 * looked up again.
 * </p>
 */
public final class Places {

	private static final Logger LOG = Logger.getLogger(Places.class.getName());

	/** The version of the rules; a change of the rules makes every stored result outdated. */
	public static final int RULES = 1;

	/** The suffix of an index's cache file. */
	public static final String CACHE_SUFFIX = ".places";

	/**
	 * How far an outline may pass a position for its country to be considered, in kilometres: the
	 * error of the simplified outlines (measured against the populated places of Germany, France
	 * and Switzerland, see the report of issue #234) with room to spare, and a coast's waters.
	 */
	public static final double BORDER_MARGIN = 10;

	/**
	 * The factor on the distance of the witness of a country whose outline does not hold the
	 * position, see {@link Places}.
	 */
	static final double OUTSIDE_PENALTY = 2;

	/** How far the witness of a country may lie, in kilometres, see {@link Places}. */
	public static final double ANCHOR_RADIUS = 10;

	/** The density that turns a population into an area. */
	public static final double PEOPLE_PER_KM2 = 1000;

	/** The largest radius of a populated place, in kilometres. */
	public static final double MAX_PLACE_RADIUS = 35;

	/** The least and largest radius of a part of town, in kilometres. */
	static final double MIN_DISTRICT_RADIUS = 1.5, MAX_DISTRICT_RADIUS = 5;

	/** How far the nearest entry with admin codes may lie, in kilometres. */
	static final double ADMIN_RADIUS = 25;

	private final GeoNamesStore _store;

	private volatile Base _base;

	/**
	 * The countries read, the least recently used first; guarded by itself, together with
	 * {@link #_loadedBytes}. See {@link #setBudget(long)}.
	 */
	private final LinkedHashMap<String, Loaded> _countries = new LinkedHashMap<>(16, 0.75f, true);

	/**
	 * The names of a country in a language ("DE/de"), the least recently used first; guarded by
	 * {@link #_countries}, and counted in {@link #_loadedBytes}.
	 */
	private final LinkedHashMap<String, LoadedNames> _names = new LinkedHashMap<>(16, 0.75f, true);

	/** The memory of the countries in {@link #_countries} and of the names in {@link #_names}. */
	private long _loadedBytes;

	/** How many bytes of heap the countries held may take, see {@link #setBudget(long)}. */
	private volatile long _budget = defaultBudget();

	/** How many times a country was read from its files, for the tests and the report. */
	private final AtomicInteger _reads = new AtomicInteger();

	private final Map<String, Object> _locks = new ConcurrentHashMap<>();

	/** The countries and their outlines, and what they were read from. */
	private record Base(Countries countries, CountryShapes shapes, String shapesVersion, long stamp, String error) {
		// A tuple.
	}

	/** A country's index, or why it could not be read, and what it was read from. */
	private record Loaded(CountryIndex index, long stamp, String error) {
		long memory() {
			return index == null ? 0 : index.memory();
		}
	}

	/** The names of a country in a language, or why they could not be read, and what from. */
	private record LoadedNames(PlaceNames names, long stamp, String error) {
		long memory() {
			return names == null ? 0 : names.memory();
		}
	}

	/**
	 * Why a lookup has no answer: the file and the country it is missing for, and whether it is on
	 * its way.
	 *
	 * @param country
	 *        The name of the country ("China"), <code>null</code> for a file every lookup needs.
	 * @param reason
	 *        What is wrong, for the log: "place names for China (CN) are unavailable: ...".
	 * @param detail
	 *        What is wrong with the file, for a person: "the download of CN.zip failed (...)".
	 * @param loading
	 *        Whether the file is being downloaded.
	 */
	private record Problem(String country, String reason, String detail, boolean loading) {
		// A tuple.
	}

	/**
	 * The default of {@link #setBudget(long)}: an eighth of the largest heap the JVM may use, so that
	 * a NAS started with <code>-Xmx512m</code> holds 64&nbsp;MB of countries (Germany is some
	 * 8&nbsp;MB, France 15, China 36, the United States 45) and a world-travelling library cannot
	 * exhaust it.
	 */
	public static long defaultBudget() {
		return Runtime.getRuntime().maxMemory() / 8;
	}

	/**
	 * Creates a {@link Places} that reads the given store.
	 */
	public Places(GeoNamesStore store) {
		_store = store;
		store.setCheck(this::check);
		store.setListener(this::replaced);
	}

	/**
	 * A {@link Places} that reads the files lying in the given directory and downloads nothing.
	 */
	public static Places offline(Path directory) {
		return new Places(new GeoNamesStore(directory));
	}

	/** The store of the files. */
	public GeoNamesStore getStore() {
		return _store;
	}

	/**
	 * Sets how many bytes of heap the countries read may take, {@link #defaultBudget()} by default.
	 *
	 * <p>
	 * A country stays loaded while it is used; when a further one would exceed the budget, the least
	 * recently used are let go, and read again from their cache file (a tenth of a second for
	 * Germany) when they are asked for next. The country just read always stays, however large.
	 * </p>
	 */
	public void setBudget(long bytes) {
		_budget = bytes;
		synchronized (_countries) {
			trim(null);
		}
	}

	/** See {@link #setBudget(long)}. */
	public long getBudget() {
		return _budget;
	}

	/** The countries held in memory now, the least recently used first. */
	public List<String> loadedCountries() {
		synchronized (_countries) {
			List<String> result = new ArrayList<>();
			for (Map.Entry<String, Loaded> entry : _countries.entrySet()) {
				if (entry.getValue().index() != null) {
					result.add(entry.getKey());
				}
			}
			return result;
		}
	}

	/** The names held in memory now ("DE/de"), the least recently used first. */
	public List<String> loadedNames() {
		synchronized (_countries) {
			List<String> result = new ArrayList<>();
			for (Map.Entry<String, LoadedNames> entry : _names.entrySet()) {
				if (entry.getValue().names() != null) {
					result.add(entry.getKey());
				}
			}
			return result;
		}
	}

	/**
	 * The names of the places of the given country in the given language, see {@link PlaceNames};
	 * <code>null</code> while they are not there: the alternate names of the country are being
	 * downloaded (or failed to), or the country itself is missing. Then a place is called by its
	 * main name, and its photograph is not waiting for anything.
	 *
	 * <p>
	 * Read on the first request for a language, from a cache file where there is one; held like a
	 * country, within the same {@link #setBudget(long) budget}.
	 * </p>
	 */
	public PlaceNames names(String iso, String language) {
		List<Problem> problems = new ArrayList<>();
		Base base = base(problems);
		if (base == null) {
			return null;
		}
		CountryIndex index = country(iso, base, problems);
		if (index == null) {
			return null;
		}
		String fileName = GeoNamesStore.alternateNamesFile(iso);
		Path file = _store.get(fileName);
		if (file == null) {
			return null;
		}
		long stamp = stamp(file) * 31 + index.getVersion().hashCode();
		String key = iso + "/" + language;
		LoadedNames loaded = heldNames(key);
		if (loaded == null || loaded.stamp() != stamp) {
			synchronized (_locks.computeIfAbsent(key, k -> new Object())) {
				loaded = heldNames(key);
				if (loaded == null || loaded.stamp() != stamp) {
					loaded = loadNames(iso, language, file, index, base, stamp);
					holdNames(key, loaded);
				}
			}
		}
		return loaded.names();
	}

	private LoadedNames loadNames(String iso, String language, Path file, CountryIndex index, Base base, long stamp) {
		_reads.incrementAndGet();
		Path cache = file.resolveSibling(iso + "." + language + ".names");
		long start = System.nanoTime();
		PlaceNames names = PlaceNames.read(cache, stamp);
		if (names != null) {
			LOG.info("GeoNames: read the " + language + " names of " + iso + " (" + names.size() + ") in "
				+ (System.nanoTime() - start) / 1_000_000 + " ms.");
			return new LoadedNames(names, stamp, null);
		}
		int[] kept = index.keptIds();
		Countries.Country country = base.countries().get(iso);
		if (country != null && Arrays.binarySearch(kept, country.geonameId()) < 0) {
			kept = Arrays.copyOf(kept, kept.length + 1);
			kept[kept.length - 1] = country.geonameId();
			Arrays.sort(kept);
		}
		try (Source source = open(file, iso + ".txt")) {
			names = PlaceNames.parse(iso, language, source.in(), kept, country == null ? -1 : country.geonameId());
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.WARNING, "GeoNames: cannot read " + file + ".", ex);
			return new LoadedNames(null, stamp, "cannot read " + file.getFileName() + ": " + ex.getMessage());
		}
		LOG.info("GeoNames: built the " + language + " names of " + iso + " (" + names.size() + ") in "
			+ (System.nanoTime() - start) / 1_000_000 + " ms.");
		try {
			names.write(cache, stamp);
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "GeoNames: cannot write " + cache + ".", ex);
		}
		return new LoadedNames(names, stamp, null);
	}

	private LoadedNames heldNames(String key) {
		synchronized (_countries) {
			return _names.get(key);
		}
	}

	private void holdNames(String key, LoadedNames loaded) {
		synchronized (_countries) {
			LoadedNames before = _names.put(key, loaded);
			if (before != null) {
				_loadedBytes -= before.memory();
			}
			_loadedBytes += loaded.memory();
			trim(key);
		}
	}

	/** How many bytes of heap the countries held take, by {@link CountryIndex#memory()}. */
	public long loadedBytes() {
		synchronized (_countries) {
			return _loadedBytes;
		}
	}

	/** How many times a country was read from its files (cache file or dump). */
	public int reads() {
		return _reads.get();
	}

	/**
	 * A number that changes whenever an answer of this gazetteer may have changed from "not yet" to
	 * something else: a download ended (or failed), or a file appeared in the directory (placed by
	 * hand). Cheap: a counter and the modification time of the directory.
	 */
	public long epoch() {
		long modified;
		try {
			modified = Files.getLastModifiedTime(_store.getDirectory()).toMillis();
		} catch (IOException ex) {
			modified = -1;
		}
		return _store.generation() * 1_000_003L + modified;
	}

	/**
	 * Tells the given listener whenever a download ended, so that what waited for a file asks again,
	 * see {@link GeoNamesStore#addAttemptListener(Runnable)}.
	 */
	public void addListener(Runnable listener) {
		_store.addAttemptListener(listener);
	}

	/** Stops telling the given listener. */
	public void removeListener(Runnable listener) {
		_store.removeAttemptListener(listener);
	}

	/**
	 * Asks for every file a {@link #lookup(double, double)} of the given position needs, without
	 * reading any country: what warms the gazetteer up in the background, see issue #234.
	 *
	 * @return <code>null</code> when every file is there, else the sentence why not, see
	 *         {@link PlaceResult#getSentence()}; a missing file is being fetched then (or waits for
	 *         the back-off of a failure).
	 */
	public String prepare(double lat, double lon) {
		if (!(lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180)) {
			return null;
		}
		List<Problem> problems = new ArrayList<>();
		Base base = base(problems);
		if (base == null) {
			return sentence(problems);
		}
		List<Candidate> candidates = base.shapes().candidates(lat, lon, BORDER_MARGIN);
		for (Candidate candidate : candidates) {
			files(candidate.country(), base, problems);
			if (candidates.size() == 1 && candidate.inside()) {
				break;
			}
		}
		return problems.isEmpty() ? null : sentence(problems);
	}

	/**
	 * The place tags of a position.
	 *
	 * <p>
	 * Thread-safe. The first lookup in a country reads its index (seconds for a large country
	 * without a cache file); every further one takes microseconds.
	 * </p>
	 *
	 * @return The tags, or why there are none yet: a file is missing (and being fetched) or broken.
	 */
	public PlaceResult lookup(double lat, double lon) {
		if (!(lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180)) {
			return PlaceResult.unavailable("not a position: " + lat + ", " + lon);
		}
		List<Problem> problems = new ArrayList<>();
		Base base = base(problems);
		if (base == null) {
			return unavailable(problems);
		}
		List<Candidate> candidates = base.shapes().candidates(lat, lon, BORDER_MARGIN);
		if (candidates.isEmpty()) {
			return PlaceResult.found(List.of(), noCountryVersion(base));
		}
		List<CountryIndex> indexes = new ArrayList<>();
		for (Candidate candidate : candidates) {
			CountryIndex index = country(candidate.country(), base, problems);
			if (index != null) {
				indexes.add(index);
			}
			if (candidates.size() == 1 && candidate.inside()) {
				break;
			}
		}
		if (!problems.isEmpty()) {
			return unavailable(problems);
		}
		CountryIndex chosen;
		if (indexes.size() == 1 && candidates.get(0).inside()) {
			chosen = indexes.get(0);
		} else {
			chosen = null;
			double best = Double.MAX_VALUE;
			for (int n = 0; n < indexes.size(); n++) {
				CountryIndex index = indexes.get(n);
				double distance = nearestAnchor(index, lat, lon);
				if (distance < Double.MAX_VALUE && !candidates.get(n).inside()) {
					// The outline is a witness as well: the country it puts the position in is
					// overruled only by a witness at half the distance.
					distance *= OUTSIDE_PENALTY;
				}
				if (distance < best) {
					best = distance;
					chosen = index;
				}
			}
			if (chosen == null) {
				for (int n = 0; n < candidates.size(); n++) {
					if (candidates.get(n).inside()) {
						chosen = indexes.get(n);
						break;
					}
				}
			}
			if (chosen == null) {
				return PlaceResult.found(List.of(), noCountryVersion(base));
			}
		}
		return PlaceResult.found(tags(base.countries(), chosen, lat, lon), version(chosen));
	}

	/**
	 * The version a lookup in the given country makes its results with, see {@link Places};
	 * <code>null</code> while the country's data is unavailable.
	 */
	public String currentVersion(String iso) {
		List<Problem> problems = new ArrayList<>();
		Base base = base(problems);
		if (base == null) {
			return null;
		}
		if (iso == null || iso.equals("-")) {
			return noCountryVersion(base);
		}
		CountryIndex index = country(iso, base, problems);
		return index == null ? null : version(index);
	}

	/**
	 * Whether a stored version is the one a lookup would make its result with now, see
	 * {@link Places}.
	 *
	 * <p>
	 * A result of other rules is outdated. A version of a country whose data cannot be read now
	 * counts as current: there is nothing better to look it up with.
	 * </p>
	 */
	public boolean isCurrent(String version) {
		if (version == null) {
			return false;
		}
		String[] parts = version.split("/");
		if (parts.length != 4 || !parts[0].equals("geonames") || !parts[1].equals(Integer.toString(RULES))) {
			return false;
		}
		String current = currentVersion(parts[2]);
		return current == null || current.equals(version);
	}

	/**
	 * What the store says about its files, for a report: "place names unavailable: ...".
	 */
	public List<GeoNamesStore.FileStatus> status() {
		return _store.status();
	}

	private static PlaceResult unavailable(List<Problem> problems) {
		List<String> reasons = new ArrayList<>();
		for (Problem problem : problems) {
			reasons.add(problem.reason());
		}
		return PlaceResult.unavailable(String.join("; ", reasons), sentence(problems));
	}

	/**
	 * What to tell a person about the given problems, as the app shows it: "Place names for China
	 * are being loaded." A failure is the news where there is one; a download going on beside it
	 * will be done soon.
	 */
	private static String sentence(List<Problem> problems) {
		Problem first = problems.get(0);
		for (Problem problem : problems) {
			if (!problem.loading()) {
				first = problem;
				break;
			}
		}
		String subject = first.country() == null ? "Place names" : "Place names for " + first.country();
		if (first.loading()) {
			return subject + " are being loaded.";
		}
		String detail = first.detail();
		while (detail.endsWith(".")) {
			detail = detail.substring(0, detail.length() - 1);
		}
		return subject + " cannot be loaded: " + detail + ".";
	}

	private static String version(CountryIndex index) {
		return "geonames/" + RULES + "/" + index.getCountry() + "/" + index.getVersion();
	}

	private static String noCountryVersion(Base base) {
		return "geonames/" + RULES + "/-/" + base.shapesVersion();
	}

	private static double nearestAnchor(CountryIndex index, double lat, double lon) {
		double[] best = { Double.MAX_VALUE };
		index.near(lat, lon, ANCHOR_RADIUS, (entry, distance) -> {
			int code = index.code(entry);
			FeatureCodes.Role role = FeatureCodes.role(code);
			boolean anchor = role == FeatureCodes.Role.PLACE || role == FeatureCodes.Role.DISTRICT
				|| (role == FeatureCodes.Role.ADMIN && FeatureCodes.adminLevel(code) >= 3);
			if (anchor && distance < best[0]) {
				best[0] = distance;
			}
		});
		return best[0];
	}

	/** The radius of a populated place or part of town, see {@link Places}. */
	static double placeRadius(int code, int population) {
		double byPopulation = Math.sqrt(population / (Math.PI * PEOPLE_PER_KM2));
		return Math.min(MAX_PLACE_RADIUS, Math.max(FeatureCodes.radius(code), byPopulation));
	}

	/** What one search of the surroundings of a position finds, see {@link Places#tags}. */
	private static final class Search implements CountryIndex.Visitor {

		private final CountryIndex _index;

		int _admin = -1;

		int _adminDepth;

		double _adminDistance = Double.MAX_VALUE;

		int _place = -1;

		double _placeScore = Double.MAX_VALUE;

		final List<Integer> _places = new ArrayList<>();

		final List<Integer> _districts = new ArrayList<>();

		final List<Double> _districtScores = new ArrayList<>();

		int _feature = -1;

		double _featureScore = Double.MAX_VALUE;

		Search(CountryIndex index) {
			_index = index;
		}

		@Override
		public void visit(int entry, double distance) {
			int code = _index.code(entry);
			int depth = depth(_index.adminCodes(entry));
			if (depth > _adminDepth || (depth == _adminDepth && depth > 0 && distance < _adminDistance)) {
				_adminDepth = depth;
				_adminDistance = distance;
				_admin = entry;
			}
			switch (FeatureCodes.role(code)) {
				case PLACE:
					// The populous ones are offered by Places#tags, from the whole country.
					if (_index.population(entry) < CountryIndex.POPULOUS) {
						place(entry, distance);
					}
					break;
				case DISTRICT: {
					double radius = Math.min(MAX_DISTRICT_RADIUS,
						Math.max(MIN_DISTRICT_RADIUS, placeRadius(code, _index.population(entry))));
					if (distance <= radius) {
						_districts.add(Integer.valueOf(entry));
						_districtScores.add(Double.valueOf(distance / radius));
					}
					break;
				}
				case FEATURE: {
					double radius = FeatureCodes.radius(code);
					if (distance <= radius) {
						double score = distance / radius;
						if (score < _featureScore || (score == _featureScore
							&& radius < FeatureCodes.radius(_index.code(_feature)))) {
							_featureScore = score;
							_feature = entry;
						}
					}
					break;
				}
				default:
					break;
			}
		}

		void place(int entry, double distance) {
			double radius = placeRadius(_index.code(entry), _index.population(entry));
			if (distance > radius) {
				return;
			}
			_places.add(Integer.valueOf(entry));
			double score = distance / radius;
			if (score < _placeScore
				|| (score == _placeScore && _index.population(entry) > _index.population(_place))) {
				_placeScore = score;
				_place = entry;
			}
		}
	}

	/**
	 * The radius of the search of the surroundings, in kilometres: the largest radius of a feature,
	 * a part of town, and of a place below {@link CountryIndex#POPULOUS} (4&nbsp;km).
	 */
	static final double LOCAL_RADIUS = 5;

	private static List<PlaceTag> tags(Countries countries, CountryIndex index, double lat, double lon) {
		String iso = index.getCountry();
		Search search = new Search(index);
		index.near(lat, lon, LOCAL_RADIUS, search);
		for (int entry : index.populous()) {
			search.place(entry, index.distance(entry, lat, lon));
		}
		if (search._admin < 0) {
			// Far from everything: the nearest entry with codes further away still names the divisions.
			Search wide = new Search(index);
			index.near(lat, lon, ADMIN_RADIUS, wide);
			search._admin = wide._admin;
		}

		List<PlaceTag> result = new ArrayList<>();
		Countries.Country country = countries.get(iso);
		if (country != null) {
			result.add(new PlaceTag(country.geonameId(), country.name(), PlaceKind.COUNTRY, "PCL", iso));
		}
		if (search._admin >= 0) {
			String[] codes = index.adminCodes(search._admin);
			for (int level = 1; level <= 4; level++) {
				PlaceTag admin = index.admin(codes, level);
				if (admin != null) {
					result.add(admin);
				}
			}
		}
		int place = search._place;
		if (place >= 0) {
			// A place in the same municipality as a larger one whose circle holds the position as
			// well is a part of it (a Paris arrondissement, recorded as a PPL): the larger is the place.
			int chosen = place;
			for (int candidate : search._places) {
				if (index.population(candidate) > index.population(chosen)
					&& sameMunicipality(index.adminCodes(candidate), index.adminCodes(place))) {
					chosen = candidate;
				}
			}
			place = chosen;
			result.add(tag(index, place, PlaceKind.PLACE));
			int district = -1;
			double best = Double.MAX_VALUE;
			for (int n = 0; n < search._districts.size(); n++) {
				int entry = search._districts.get(n).intValue();
				double score = search._districtScores.get(n).doubleValue();
				if (score < best && agree(index.adminCodes(place), index.adminCodes(entry))) {
					best = score;
					district = entry;
				}
			}
			if (district >= 0) {
				result.add(tag(index, district, PlaceKind.DISTRICT));
			}
		}
		if (search._feature >= 0) {
			result.add(tag(index, search._feature, PlaceKind.FEATURE));
		}
		return result;
	}

	private static PlaceTag tag(CountryIndex index, int entry, PlaceKind kind) {
		return new PlaceTag(index.id(entry), index.name(entry), kind, FeatureCodes.code(index.code(entry)),
			index.getCountry());
	}

	/** The number of levels a tuple of admin codes names, up to the first gap. */
	static int depth(String[] codes) {
		int result = 0;
		while (result < 4 && !codes[result].isEmpty()) {
			result++;
		}
		return result;
	}

	/**
	 * Whether two tuples of admin codes name the same division down to the third level at least
	 * (a municipality): never in a country whose codes end at the county.
	 */
	static boolean sameMunicipality(String[] a, String[] b) {
		int depth = 0;
		for (int n = 0; n < 4; n++) {
			if (!a[n].equals(b[n])) {
				return false;
			}
			if (!a[n].isEmpty()) {
				depth = n + 1;
			}
		}
		return depth >= 3;
	}

	/** Whether two tuples of admin codes name no different division on a level both have. */
	static boolean agree(String[] a, String[] b) {
		for (int n = 0; n < 4; n++) {
			if (!a[n].isEmpty() && !b[n].isEmpty() && !a[n].equals(b[n])) {
				return false;
			}
		}
		return true;
	}

	// --- Loading ---------------------------------------------------------------------------------

	private Base base(List<Problem> problems) {
		Path info = _store.get(GeoNamesStore.COUNTRY_INFO);
		Path shapes = _store.get(GeoNamesStore.SHAPES);
		if (info == null || shapes == null) {
			if (info == null) {
				problems.add(missing(null, "", GeoNamesStore.COUNTRY_INFO));
			}
			if (shapes == null) {
				problems.add(missing(null, "", GeoNamesStore.SHAPES));
			}
			return null;
		}
		long stamp = stamp(info, shapes);
		Base base = _base;
		if (base == null || base.stamp() != stamp) {
			synchronized (this) {
				base = _base;
				if (base == null || base.stamp() != stamp) {
					base = loadBase(info, shapes, stamp);
					_base = base;
				}
			}
		}
		if (base.error() != null) {
			problems.add(new Problem(null, base.error(), base.error(), false));
			return null;
		}
		return base;
	}

	/** The problem of a missing file. */
	private Problem missing(String country, String prefix, String fileName) {
		String detail = _store.problem(fileName);
		if (detail == null) {
			// Arrived meanwhile: the next lookup reads it.
			detail = fileName + " has just arrived";
		}
		return new Problem(country, prefix + detail, detail, _store.isLoading(fileName));
	}

	/** The name of a country for a person, its code where the country is unknown. */
	private static String countryName(Base base, String iso) {
		Countries.Country country = base.countries().get(iso);
		return country == null ? iso : country.name();
	}

	/**
	 * The files of a country, each asked for; <code>null</code> (and the problems added) where one
	 * is missing.
	 */
	private Path[] files(String iso, Base base, List<Problem> problems) {
		String fileName = GeoNamesStore.countryFile(iso);
		Path file = _store.get(fileName);
		Path admin1 = _store.get(GeoNamesStore.ADMIN1);
		Path admin2 = _store.get(GeoNamesStore.ADMIN2);
		if (file == null || admin1 == null || admin2 == null) {
			String what = "place names for " + base.countries().describe(iso) + " are unavailable: ";
			String name = countryName(base, iso);
			if (file == null) {
				problems.add(missing(name, what, fileName));
			}
			if (admin1 == null) {
				problems.add(missing(name, what, GeoNamesStore.ADMIN1));
			}
			if (admin2 == null) {
				problems.add(missing(name, what, GeoNamesStore.ADMIN2));
			}
			return null;
		}
		return new Path[] { file, admin1, admin2 };
	}

	private static Base loadBase(Path info, Path shapes, long stamp) {
		Countries countries;
		try (InputStream in = Files.newInputStream(info)) {
			countries = Countries.parse(in);
		} catch (IOException ex) {
			return new Base(null, null, null, stamp, "cannot read " + info.getFileName() + ": " + ex.getMessage());
		}
		try (Source source = open(shapes, ".json")) {
			CRC32 crc = new CRC32();
			CountryShapes outlines = CountryShapes.parse(new CheckedInputStream(source.in(), crc), countries.isoById());
			return new Base(countries, outlines, String.format("%08x", crc.getValue()), stamp, null);
		} catch (IOException ex) {
			return new Base(null, null, null, stamp, "cannot read " + shapes.getFileName() + ": " + ex.getMessage());
		}
	}

	private CountryIndex country(String iso, Base base, List<Problem> problems) {
		Path[] files = files(iso, base, problems);
		if (files == null) {
			return null;
		}
		Path file = files[0];
		Path admin1 = files[1];
		Path admin2 = files[2];
		long stamp = stamp(file, admin1, admin2);
		Loaded loaded = held(iso);
		if (loaded == null || loaded.stamp() != stamp) {
			synchronized (_locks.computeIfAbsent(iso, k -> new Object())) {
				loaded = held(iso);
				if (loaded == null || loaded.stamp() != stamp) {
					loaded = load(iso, file, admin1, admin2, stamp);
					hold(iso, loaded);
				}
			}
		}
		if (loaded.error() != null) {
			String reason = "place names for " + base.countries().describe(iso) + " are unavailable: " + loaded.error();
			problems.add(new Problem(countryName(base, iso), reason, loaded.error(), false));
			return null;
		}
		return loaded.index();
	}

	/** The country held, marked as the most recently used; <code>null</code> where none is. */
	private Loaded held(String iso) {
		synchronized (_countries) {
			return _countries.get(iso);
		}
	}

	/** Holds a country read, and lets go of the least recently used ones beyond the budget. */
	private void hold(String iso, Loaded loaded) {
		synchronized (_countries) {
			Loaded before = _countries.put(iso, loaded);
			if (before != null) {
				_loadedBytes -= before.memory();
			}
			_loadedBytes += loaded.memory();
			trim(iso);
		}
	}

	/**
	 * Lets go of the least recently used names, then countries, but the given one while over the
	 * budget: names are the cheaper to read again.
	 */
	private void trim(String keep) {
		long budget = _budget;
		for (var entries = _names.entrySet().iterator(); _loadedBytes > budget && entries.hasNext();) {
			Map.Entry<String, LoadedNames> entry = entries.next();
			if (entry.getKey().equals(keep) || entry.getValue().names() == null) {
				continue;
			}
			_loadedBytes -= entry.getValue().memory();
			entries.remove();
		}
		for (var entries = _countries.entrySet().iterator(); _loadedBytes > budget && entries.hasNext();) {
			Map.Entry<String, Loaded> entry = entries.next();
			if (entry.getKey().equals(keep) || entry.getValue().index() == null) {
				continue;
			}
			_loadedBytes -= entry.getValue().memory();
			entries.remove();
			LOG.fine("GeoNames: let go of " + entry.getKey() + " to stay within " + budget / (1024 * 1024)
				+ " MB; it is read from its cache file when asked for again.");
		}
	}

	private Loaded load(String iso, Path file, Path admin1, Path admin2, long stamp) {
		_reads.incrementAndGet();
		Path cache = _store.getDirectory().resolve(iso + CACHE_SUFFIX);
		long start = System.nanoTime();
		CountryIndex index = CountryIndex.read(cache, stamp);
		if (index != null && index.getCountry().equals(iso)) {
			LOG.info("GeoNames: read the index of " + iso + " (" + index.size() + " entries) in "
				+ (System.nanoTime() - start) / 1_000_000 + " ms.");
			return new Loaded(index, stamp, null);
		}
		try {
			index = parseCountry(iso, file, admin1, admin2);
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.WARNING, "GeoNames: cannot read " + file + ".", ex);
			return new Loaded(null, stamp, "cannot read " + file.getFileName() + ": " + ex.getMessage());
		}
		LOG.info("GeoNames: built the index of " + iso + " (" + index.size() + " entries) in "
			+ (System.nanoTime() - start) / 1_000_000 + " ms.");
		writeCache(index, cache, stamp);
		return new Loaded(index, stamp, null);
	}

	private static void writeCache(CountryIndex index, Path cache, long stamp) {
		try {
			index.write(cache, stamp);
		} catch (IOException ex) {
			// Read from the dump next time as well.
			LOG.log(Level.WARNING, "GeoNames: cannot write " + cache + ".", ex);
		}
	}

	/**
	 * Builds the index of a country from its dump (zipped or not) and the admin tables.
	 */
	static CountryIndex parseCountry(String iso, Path file, Path admin1, Path admin2) throws IOException {
		Map<String, CountryIndex.Fallback> fallbacks = new HashMap<>();
		CRC32 crc = new CRC32();
		if (admin1 != null) {
			readFallbacks(admin1, iso, fallbacks, crc);
		}
		if (admin2 != null) {
			readFallbacks(admin2, iso, fallbacks, crc);
		}
		try (Source source = open(file, iso + ".txt")) {
			return CountryIndex.parse(iso, source.in(), fallbacks, crc.getValue());
		}
	}

	/**
	 * Reads the lines of an admin table that belong to a country: <code>DE.01</code> or
	 * <code>DE.01.082</code>, name, ASCII name, GeoNames id.
	 */
	private static void readFallbacks(Path table, String iso, Map<String, CountryIndex.Fallback> fallbacks, CRC32 crc)
			throws IOException {
		String prefix = iso + ".";
		String[] fields = new String[4];
		try (BufferedReader reader = Files.newBufferedReader(table, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.startsWith(prefix)) {
					continue;
				}
				crc.update(line.getBytes(StandardCharsets.UTF_8));
				if (CountryIndex.split(line, fields) < 4) {
					continue;
				}
				try {
					fallbacks.put(fields[0].substring(prefix.length()),
						new CountryIndex.Fallback(Integer.parseInt(fields[3].trim()), fields[1]));
				} catch (NumberFormatException ex) {
					// A line without an id names nothing to tag.
				}
			}
		}
	}

	/** Checks alternate names: id, GeoNames id, language and name on every line. */
	private static void checkAlternateNames(InputStream in) throws IOException {
		BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
		String[] fields = new String[4];
		int lines = 0;
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.isEmpty()) {
				continue;
			}
			lines++;
			if (CountryIndex.split(line, fields) < 4) {
				throw new IOException("Not GeoNames alternate names: line " + lines + ".");
			}
			try {
				Integer.parseInt(fields[1].trim());
			} catch (NumberFormatException ex) {
				throw new IOException("Not GeoNames alternate names: line " + lines + " has no GeoNames id.");
			}
		}
		if (lines == 0) {
			throw new IOException("The GeoNames alternate names are empty.");
		}
	}

	/** Checks an admin table: code, name, ASCII name and id on every line. */
	private static void checkAdminTable(Path table) throws IOException {
		String[] fields = new String[4];
		int lines = 0;
		try (BufferedReader reader = Files.newBufferedReader(table, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty()) {
					continue;
				}
				lines++;
				if (CountryIndex.split(line, fields) < 4 || fields[0].indexOf('.') != 2) {
					throw new IOException("Not a GeoNames admin table: line " + lines + ".");
				}
				try {
					Integer.parseInt(fields[3].trim());
				} catch (NumberFormatException ex) {
					throw new IOException("Not a GeoNames admin table: line " + lines + " has no id.");
				}
			}
		}
		if (lines == 0) {
			throw new IOException("The GeoNames admin table is empty.");
		}
	}

	/** A file's content: the file itself, or the entry of a zip. */
	private record Source(InputStream in, ZipFile zip) implements AutoCloseable {
		@Override
		public void close() throws IOException {
			in.close();
			if (zip != null) {
				zip.close();
			}
		}
	}

	/**
	 * Opens a file, or where it is a zip the entry of the given name (or ending in it).
	 */
	private static Source open(Path file, String entryName) throws IOException {
		if (!file.getFileName().toString().endsWith(".zip")) {
			return new Source(Files.newInputStream(file), null);
		}
		ZipFile zip = new ZipFile(file.toFile());
		try {
			ZipEntry entry = zip.getEntry(entryName);
			if (entry == null) {
				for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
					ZipEntry candidate = entries.nextElement();
					if (candidate.getName().endsWith(entryName)) {
						entry = candidate;
						break;
					}
				}
			}
			if (entry == null) {
				throw new IOException(file.getFileName() + " holds no " + entryName + ".");
			}
			return new Source(new java.io.BufferedInputStream(zip.getInputStream(entry), 1 << 16), zip);
		} catch (IOException | RuntimeException ex) {
			zip.close();
			throw ex;
		}
	}

	/** What the given files are now: their sizes and modification times. */
	private static long stamp(Path... files) {
		long result = 17;
		for (Path file : files) {
			try {
				result = 31 * result + file.getFileName().toString().hashCode();
				result = 31 * result + Files.size(file);
				result = 31 * result + Files.getLastModifiedTime(file).toMillis();
			} catch (IOException ex) {
				result = 31 * result - 1;
			}
		}
		return result;
	}

	// --- Downloads -------------------------------------------------------------------------------

	/** Reads a downloaded file completely before it replaces the one in use, see {@link GeoNamesStore}. */
	private Object check(String name, Path candidate) throws IOException {
		switch (name) {
			case GeoNamesStore.COUNTRY_INFO:
				try (InputStream in = Files.newInputStream(candidate)) {
					return Countries.parse(in);
				}
			case GeoNamesStore.SHAPES:
				try (Source source = openZip(candidate, ".json")) {
					Base base = _base;
					Map<Integer, String> ids = base != null && base.countries() != null ? base.countries().isoById() : null;
					return CountryShapes.parse(source.in(), ids);
				}
			case GeoNamesStore.ADMIN1:
			case GeoNamesStore.ADMIN2:
				checkAdminTable(candidate);
				return null;
			default:
				if (name.startsWith(GeoNamesStore.ALTERNATE_NAMES + "/") && name.endsWith(".zip")) {
					String iso = name.substring(GeoNamesStore.ALTERNATE_NAMES.length() + 1, name.length() - 4);
					try (Source source = openZip(candidate, iso + ".txt")) {
						checkAlternateNames(source.in());
					}
					return null;
				}
				if (name.length() == 6 && name.endsWith(".zip")) {
					String iso = name.substring(0, 2);
					Path admin1 = existing(GeoNamesStore.ADMIN1);
					Path admin2 = existing(GeoNamesStore.ADMIN2);
					CountryIndex index;
					try (Source source = openZip(candidate, iso + ".txt")) {
						index = parseCountry(iso, source, admin1, admin2);
					}
					if (index.size() == 0) {
						throw new IOException(name + " has no entry this server uses.");
					}
					return index;
				}
				throw new IOException("Not a GeoNames file this server reads: " + name);
		}
	}

	private Path existing(String name) {
		Path file = _store.getDirectory().resolve(name);
		return Files.isRegularFile(file) ? file : null;
	}

	private static CountryIndex parseCountry(String iso, Source source, Path admin1, Path admin2) throws IOException {
		Map<String, CountryIndex.Fallback> fallbacks = new HashMap<>();
		CRC32 crc = new CRC32();
		if (admin1 != null) {
			readFallbacks(admin1, iso, fallbacks, crc);
		}
		if (admin2 != null) {
			readFallbacks(admin2, iso, fallbacks, crc);
		}
		return CountryIndex.parse(iso, source.in(), fallbacks, crc.getValue());
	}

	/** A downloaded <code>.part</code> file is a zip whatever its name says. */
	private static Source openZip(Path file, String entryName) throws IOException {
		ZipFile zip = new ZipFile(file.toFile());
		try {
			for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
				ZipEntry entry = entries.nextElement();
				if (entry.getName().endsWith(entryName)) {
					return new Source(new java.io.BufferedInputStream(zip.getInputStream(entry), 1 << 16), zip);
				}
			}
			throw new IOException("The download holds no " + entryName + ".");
		} catch (IOException | RuntimeException ex) {
			zip.close();
			throw ex;
		}
	}

	/** Takes in a file the store replaced: the new data is used from the next lookup on. */
	private void replaced(String name, Object checked) {
		if (checked instanceof CountryIndex index) {
			Path file = existing(name);
			Path admin1 = existing(GeoNamesStore.ADMIN1);
			Path admin2 = existing(GeoNamesStore.ADMIN2);
			if (file != null && admin1 != null && admin2 != null) {
				long stamp = stamp(file, admin1, admin2);
				writeCache(index, _store.getDirectory().resolve(index.getCountry() + CACHE_SUFFIX), stamp);
				synchronized (_locks.computeIfAbsent(index.getCountry(), k -> new Object())) {
					hold(index.getCountry(), new Loaded(index, stamp, null));
				}
			}
		}
		// Everything else is read again on the next lookup: the stamp of what it was read from
		// changed. A country's index holds its fallback names, so a new admin table rebuilds it.
	}
}
