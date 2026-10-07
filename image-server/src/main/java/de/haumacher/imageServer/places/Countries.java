/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The countries of GeoNames' <code>countryInfo.txt</code>: ISO code, name and GeoNames id.
 */
public final class Countries {

	/** A country. */
	public record Country(String iso, String name, int geonameId) {
		// A triple.
	}

	private final Map<String, Country> _byIso;

	private final Map<Integer, String> _isoById;

	private Countries(Map<String, Country> byIso) {
		_byIso = Map.copyOf(byIso);
		Map<Integer, String> isoById = new HashMap<>();
		for (Country country : byIso.values()) {
			isoById.put(Integer.valueOf(country.geonameId()), country.iso());
		}
		_isoById = Map.copyOf(isoById);
	}

	/** The country of an ISO code, <code>null</code> for an unknown one. */
	public Country get(String iso) {
		return _byIso.get(iso);
	}

	/** The ISO code of each country's GeoNames id. */
	public Map<Integer, String> isoById() {
		return _isoById;
	}

	/** What to call a country in a message: "Germany (DE)". */
	public String describe(String iso) {
		Country country = get(iso);
		return country == null ? iso : country.name() + " (" + iso + ")";
	}

	/** Reads <code>countryInfo.txt</code>; the stream is not closed. */
	public static Countries parse(InputStream in) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		Map<String, Country> result = new HashMap<>();
		String[] fields = new String[19];
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			int count = CountryIndex.split(line, fields);
			if (count < 17 || fields[0].length() != 2) {
				throw new IOException("Not a GeoNames countryInfo.txt: '" + abbreviate(line) + "'.");
			}
			try {
				result.put(fields[0], new Country(fields[0], fields[4], Integer.parseInt(fields[16])));
			} catch (NumberFormatException ex) {
				throw new IOException("Not a GeoNames countryInfo.txt: '" + abbreviate(line) + "'.");
			}
		}
		if (result.isEmpty()) {
			throw new IOException("The GeoNames countryInfo.txt names no country.");
		}
		return new Countries(result);
	}

	private static String abbreviate(String line) {
		return line.length() > 60 ? line.substring(0, 60) + "..." : line;
	}
}
