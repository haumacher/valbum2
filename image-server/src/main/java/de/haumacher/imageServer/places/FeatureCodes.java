/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The GeoNames feature codes kept in a {@link CountryIndex}, and what each is to a photo, see
 * issue #234.
 *
 * <p>
 * Everything else in a country file is dropped while it is read: a GeoNames entry is a point, and a
 * point is a fair stand-in for a place only where the radius it stands for can be told. A
 * castle's point is within a few hundred metres of everybody standing at the castle; a stream's
 * point is its mouth, and a photograph by the stream fifty kilometres up has nothing to do with it.
 * </p>
 *
 * <h2>Kept and dropped</h2>
 *
 * <ul>
 * <li>The administrative divisions <code>ADM1</code> to <code>ADM4</code>, found by their codes and
 * never by distance. <code>ADM5</code> is not exported with codes in the country files yet, and the
 * historical (<code>ADM1H</code>...) and unspecified (<code>ADMD</code>) divisions name no level.</li>
 * <li>The populated places that are a place today (<code>PPL</code>, <code>PPLA*</code>,
 * <code>PPLC</code>, <code>PPLG</code>, <code>PPLS</code>, <code>PPLL</code>, <code>PPLF</code>,
 * <code>PPLR</code>, <code>STLMT</code>), and the parts of town (<code>PPLX</code>); not the
 * abandoned, destroyed or historical ones (<code>PPLQ</code>, <code>PPLW</code>, <code>PPLH</code>,
 * <code>PPLCH</code>).</li>
 * <li>The features a photograph is taken at, each with the radius its point is good for (the
 * {@link #radius(int) radius}, in kilometres): sights and buildings worth a visit (a church,
 * monument, lighthouse, museum, ruin) in a few hundred metres; a castle, palace, zoo, stadium, garden or
 * park, a hill, peak or beach in under a kilometre or so; mountains, lakes, islands, reserves,
 * forests and airports in a few kilometres; a glacier, a lake district, a range, an archipelago or a
 * bay in more.</li>
 * <li>Dropped as noise: hotels, plain buildings, schools, hospitals, shops, post offices, stations
 * and stops, roads and streets, farms and estates, mines, wells, dams, cemeteries, huts, towers (in the United States thousands of radio masts,
 * named by their call signs); streams,
 * canals and ditches (a line, recorded as one point); valleys, regions, areas and localities (no
 * extent to tell); seas, oceans and gulfs (one point for a sea).</li>
 * </ul>
 */
public final class FeatureCodes {

	/** What an entry is to the lookup. */
	public enum Role {
		/** An administrative division, found by its codes. */
		ADMIN,
		/** A populated place, see {@link Places}. */
		PLACE,
		/** A part of town. */
		DISTRICT,
		/** A named feature. */
		FEATURE;
	}

	private static final List<String> CODES = new ArrayList<>();

	private static final List<Role> ROLES = new ArrayList<>();

	private static final List<Double> RADII = new ArrayList<>();

	private static final List<Character> CLASSES = new ArrayList<>();

	private static final Map<String, Integer> INDEX = new HashMap<>();

	static {
		admin("ADM1", "ADM2", "ADM3", "ADM4");
		place('P', 2.0, "PPL", "PPLA", "PPLA2", "PPLA3", "PPLA4", "PPLA5", "PPLC", "PPLG", "PPLS", "PPLR",
			"STLMT");
		place('P', 1.0, "PPLL", "PPLF");
		add('P', "PPLX", Role.DISTRICT, 1.5);

		// A spot: a building, a monument, a spring.
		feature('S', 0.15, "CH", "CTRR", "TMPL", "MSQE", "SHRN", "MNMT", "LTHSE", "OBS", "ARCH", "PYR");
		feature('H', 0.15, "SPNG");
		// A building or site of some size.
		feature('S', 0.3, "MUS", "THTR", "OPRA", "HSTS", "RUIN", "SQR", "BDG", "MSTY", "PYRS", "CAVE");
		feature('H', 0.3, "FLLS", "PND");
		// A complex, a hill.
		feature('S', 0.6, "CSTL", "PAL", "ZOO", "STDM", "AMTH", "GDN");
		feature('L', 0.6, "AMUS");
		feature('T', 0.6, "CLF", "BCH", "PASS", "HLL", "PK", "CAPE");
		// A park.
		feature('L', 1.0, "PRK");
		// A mountain, a lake, an island, a reserve, a forest, an airport.
		feature('T', 2.0, "MT", "VLC", "ISL", "GRGE", "CNYN");
		feature('H', 2.0, "LK", "LGN", "HBR", "RSV");
		feature('L', 2.0, "PRT", "RES", "RESN", "RESW", "RESF");
		feature('V', 1.5, "FRST");
		feature('S', 2.0, "AIRP");
		// A range, a glacier, a lake district, an archipelago, a bay.
		feature('T', 4.0, "MTS", "ISLS");
		feature('H', 4.0, "GLCR", "LKS", "BAY");
	}

	private static void admin(String... codes) {
		for (String code : codes) {
			add('A', code, Role.ADMIN, 0);
		}
	}

	private static void place(char featureClass, double radius, String... codes) {
		for (String code : codes) {
			add(featureClass, code, Role.PLACE, radius);
		}
	}

	private static void feature(char featureClass, double radius, String... codes) {
		for (String code : codes) {
			add(featureClass, code, Role.FEATURE, radius);
		}
	}

	private static void add(char featureClass, String code, Role role, double radius) {
		INDEX.put(featureClass + "." + code, Integer.valueOf(CODES.size()));
		CODES.add(code);
		CLASSES.add(Character.valueOf(featureClass));
		ROLES.add(role);
		RADII.add(Double.valueOf(radius));
	}

	private FeatureCodes() {
		// Only the table.
	}

	/**
	 * The index of a kept feature code, <code>-1</code> for one that is dropped.
	 *
	 * @param featureClass
	 *        The GeoNames feature class: <code>A</code>, <code>P</code>, <code>L</code>...
	 */
	public static int indexOf(String featureClass, String code) {
		Integer result = INDEX.get(featureClass + "." + code);
		return result == null ? -1 : result.intValue();
	}

	/** The number of codes kept; an index is below it. */
	public static int size() {
		return CODES.size();
	}

	/** The feature code of an index. */
	public static String code(int index) {
		return CODES.get(index);
	}

	/** What the feature of an index is to the lookup. */
	public static Role role(int index) {
		return ROLES.get(index);
	}

	/**
	 * The distance in kilometres an entry's point stands for: a feature further from a position
	 * is not named; a populated place's least radius, which grows with its population.
	 */
	public static double radius(int index) {
		return RADII.get(index).doubleValue();
	}

	/** The administrative level (1 to 4) of an index of {@link Role#ADMIN}. */
	public static int adminLevel(int index) {
		return CODES.get(index).charAt(3) - '0';
	}

	/**
	 * A fingerprint of the selection, part of a cache file's stamp: a changed selection rebuilds every
	 * index. The radii are no part of it; they are applied at lookup.
	 */
	static int fingerprint() {
		int result = 1;
		for (int n = 0; n < CODES.size(); n++) {
			result = 31 * result + (CLASSES.get(n) + "." + CODES.get(n) + "/" + ROLES.get(n))
				.hashCode();
		}
		return result;
	}
}
