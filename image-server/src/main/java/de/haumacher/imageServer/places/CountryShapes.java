/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonToken;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The outlines of the countries, from GeoNames' <code>shapes_simplified_low.json</code>: the first
 * pass of a lookup, which tells the country (and so the country file to read) of a position, see
 * issue #234.
 *
 * <p>
 * The outlines are simplified to a few kilometres, so a position close to a border may lie in the
 * wrong outline, a beach outside every outline, and a small island in none at all. That is why
 * {@link #candidates(double, double, double)} answers every country whose outline lies within a
 * margin, and {@link Places} settles between them with the entries of their country files.
 * </p>
 */
public final class CountryShapes {

	/** A country close to a position. */
	public record Candidate(String country, boolean inside, double distance) {
		// A triple.
	}

	private final List<Shape> _shapes;

	private static final class Shape {

		final String _country;

		final List<Ring> _rings = new ArrayList<>();

		float _latMin = Float.MAX_VALUE, _latMax = -Float.MAX_VALUE, _lonMin = Float.MAX_VALUE,
				_lonMax = -Float.MAX_VALUE;

		Shape(String country) {
			_country = country;
		}

		void add(float[] points) {
			Ring ring = new Ring(points);
			_rings.add(ring);
			_latMin = Math.min(_latMin, ring._latMin);
			_latMax = Math.max(_latMax, ring._latMax);
			_lonMin = Math.min(_lonMin, ring._lonMin);
			_lonMax = Math.max(_lonMax, ring._lonMax);
		}

		/** Whether the box of all rings, widened by the margin, holds the position. */
		boolean near(double lat, double lon, double dLat, double dLon) {
			return lat >= _latMin - dLat && lat <= _latMax + dLat && lon >= _lonMin - dLon && lon <= _lonMax + dLon;
		}

		/**
		 * Whether the position lies inside, by the even-odd rule over all rings (a hole is a ring
		 * inside a ring).
		 */
		boolean contains(double lat, double lon) {
			boolean inside = false;
			for (Ring ring : _rings) {
				inside ^= ring.crossings(lat, lon);
			}
			return inside;
		}

		/**
		 * The distance to the outline where it is below the margin, else {@link Double#MAX_VALUE}.
		 *
		 * @param dLat
		 *        The margin in degrees of latitude.
		 * @param dLon
		 *        The margin in degrees of longitude at the position.
		 */
		double distance(double lat, double lon, double dLat, double dLon) {
			double result = Double.MAX_VALUE;
			for (Ring ring : _rings) {
				result = Math.min(result, ring.distance(lat, lon, dLat, dLon));
			}
			return result;
		}
	}

	/**
	 * A closed ring of an outline, its edges filed by bands of one degree of latitude: a test
	 * looks at the edges of the band of the position only, not at the 20&nbsp;000 edges of a
	 * continent's coast.
	 */
	private static final class Ring {

		/** Latitude, longitude, latitude... */
		final float[] _points;

		final float _latMin, _latMax, _lonMin, _lonMax;

		final int _firstBand;

		/** The edges (by the index of their end point) that reach into each band. */
		final int[][] _bands;

		Ring(float[] points) {
			_points = points;
			float latMin = Float.MAX_VALUE, latMax = -Float.MAX_VALUE, lonMin = Float.MAX_VALUE,
					lonMax = -Float.MAX_VALUE;
			for (int n = 0; n < points.length; n += 2) {
				latMin = Math.min(latMin, points[n]);
				latMax = Math.max(latMax, points[n]);
				lonMin = Math.min(lonMin, points[n + 1]);
				lonMax = Math.max(lonMax, points[n + 1]);
			}
			_latMin = latMin;
			_latMax = latMax;
			_lonMin = lonMin;
			_lonMax = lonMax;
			_firstBand = band(latMin);
			int bandCount = band(latMax) - _firstBand + 1;
			int[] sizes = new int[bandCount];
			int count = points.length / 2;
			for (int i = 0, j = count - 1; i < count; j = i++) {
				for (int b = band(Math.min(points[2 * i], points[2 * j])); b <= band(
					Math.max(points[2 * i], points[2 * j])); b++) {
					sizes[b - _firstBand]++;
				}
			}
			_bands = new int[bandCount][];
			for (int b = 0; b < bandCount; b++) {
				_bands[b] = new int[sizes[b]];
				sizes[b] = 0;
			}
			for (int i = 0, j = count - 1; i < count; j = i++) {
				for (int b = band(Math.min(points[2 * i], points[2 * j])); b <= band(
					Math.max(points[2 * i], points[2 * j])); b++) {
					_bands[b - _firstBand][sizes[b - _firstBand]++] = i;
				}
			}
		}

		private static int band(double lat) {
			return (int) Math.floor(lat);
		}

		/** Whether a ray from the position eastwards crosses this ring an odd number of times. */
		boolean crossings(double lat, double lon) {
			if (lat < _latMin || lat > _latMax || lon < _lonMin || lon > _lonMax) {
				// Outside the box: an even number.
				return false;
			}
			int count = _points.length / 2;
			boolean odd = false;
			for (int i : _bands[band(lat) - _firstBand]) {
				int j = i == 0 ? count - 1 : i - 1;
				double latI = _points[2 * i];
				double lonI = _points[2 * i + 1];
				double latJ = _points[2 * j];
				double lonJ = _points[2 * j + 1];
				if ((latI > lat) != (latJ > lat) && lon < (lonJ - lonI) * (lat - latI) / (latJ - latI) + lonI) {
					odd = !odd;
				}
			}
			return odd;
		}

		/** The distance to this ring where it is below the margin, else {@link Double#MAX_VALUE}. */
		double distance(double lat, double lon, double dLat, double dLon) {
			if (lat < _latMin - dLat || lat > _latMax + dLat || lon < _lonMin - dLon || lon > _lonMax + dLon) {
				return Double.MAX_VALUE;
			}
			int count = _points.length / 2;
			double result = Double.MAX_VALUE;
			int from = Math.max(0, band(lat - dLat) - _firstBand);
			int to = Math.min(_bands.length - 1, band(lat + dLat) - _firstBand);
			for (int b = from; b <= to; b++) {
				for (int i : _bands[b]) {
					int j = i == 0 ? count - 1 : i - 1;
					double lat1 = _points[2 * j];
					double lat2 = _points[2 * i];
					if (Math.min(lat1, lat2) - lat > dLat || lat - Math.max(lat1, lat2) > dLat) {
						continue;
					}
					double lon1 = _points[2 * j + 1];
					double lon2 = _points[2 * i + 1];
					if (Math.min(lon1, lon2) - lon > dLon || lon - Math.max(lon1, lon2) > dLon) {
						continue;
					}
					result = Math.min(result, Geo.distanceToSegment(lat, lon, lat1, lon1, lat2, lon2));
				}
			}
			return result;
		}
	}

	private CountryShapes(List<Shape> shapes) {
		_shapes = shapes;
	}

	/** The number of countries with an outline. */
	public int size() {
		return _shapes.size();
	}

	/**
	 * The countries whose outline contains the position or lies within the margin of it, the
	 * nearest first.
	 *
	 * @param margin
	 *        In kilometres.
	 */
	public List<Candidate> candidates(double lat, double lon, double margin) {
		double dLat = margin / Geo.KM_PER_DEGREE;
		double dLon = dLat / Math.max(0.01, Math.cos(Math.toRadians(Math.min(89, Math.abs(lat) + dLat))));
		List<Candidate> result = new ArrayList<>(2);
		for (Shape shape : _shapes) {
			if (!shape.near(lat, lon, dLat, dLon)) {
				continue;
			}
			boolean inside = shape.contains(lat, lon);
			double distance = inside ? 0 : shape.distance(lat, lon, dLat, dLon);
			if (inside || distance <= margin) {
				result.add(new Candidate(shape._country, inside, distance));
			}
		}
		if (result.size() > 1) {
			result.sort((a, b) -> Double.compare(a.distance(), b.distance()));
		}
		return result;
	}

	/**
	 * Reads the GeoJSON of <code>shapes_simplified_low.json</code>.
	 *
	 * @param in
	 *        The JSON, UTF-8; not closed.
	 * @param isoById
	 *        The ISO code of each country's GeoNames id, from {@link Countries}; an outline of
	 *        another id is skipped. <code>null</code> to check the file only: every outline is
	 *        kept under its id.
	 */
	public static CountryShapes parse(InputStream in, Map<Integer, String> isoById) throws IOException {
		JsonReader json = new JsonReader(new ReaderAdapter(new InputStreamReader(in, StandardCharsets.UTF_8)));
		List<Shape> shapes = new ArrayList<>();
		try {
			json.beginObject();
			while (json.hasNext()) {
				if (!"features".equals(json.nextName())) {
					json.skipValue();
					continue;
				}
				json.beginArray();
				while (json.hasNext()) {
					Shape shape = feature(json, isoById);
					if (shape != null) {
						shapes.add(shape);
					}
				}
				json.endArray();
			}
			json.endObject();
		} catch (IllegalStateException | NumberFormatException ex) {
			throw new IOException("Not a GeoNames shapes file: " + ex.getMessage(), ex);
		}
		if (shapes.isEmpty()) {
			throw new IOException("The GeoNames shapes file has no outline of a known country.");
		}
		return new CountryShapes(shapes);
	}

	private static Shape feature(JsonReader json, Map<Integer, String> isoById) throws IOException {
		String id = null;
		List<float[]> rings = new ArrayList<>();
		json.beginObject();
		while (json.hasNext()) {
			switch (json.nextName()) {
				case "properties":
					json.beginObject();
					while (json.hasNext()) {
						if ("geoNameId".equals(json.nextName())) {
							id = json.peek() == JsonToken.NUMBER ? Long.toString(json.nextLong()) : json.nextString();
						} else {
							json.skipValue();
						}
					}
					json.endObject();
					break;
				case "geometry":
					geometry(json, rings);
					break;
				default:
					json.skipValue();
			}
		}
		json.endObject();
		String country;
		try {
			country = id == null ? null : isoById == null ? id.trim() : isoById.get(Integer.valueOf(id.trim()));
		} catch (NumberFormatException ex) {
			country = null;
		}
		if (country == null || rings.isEmpty()) {
			return null;
		}
		Shape result = new Shape(country);
		for (float[] ring : rings) {
			result.add(ring);
		}
		return result;
	}

	private static void geometry(JsonReader json, List<float[]> rings) throws IOException {
		String type = null;
		json.beginObject();
		while (json.hasNext()) {
			switch (json.nextName()) {
				case "type":
					type = json.nextString();
					break;
				case "coordinates":
					// Polygon: [ring...]; MultiPolygon: [[ring...]...]. A ring is [[lon, lat]...].
					// Both are read as nested arrays down to the positions, whatever the type says.
					nested(json, rings);
					break;
				default:
					json.skipValue();
			}
		}
		json.endObject();
		if (type != null && !type.equals("Polygon") && !type.equals("MultiPolygon")) {
			rings.clear();
		}
	}

	/**
	 * Reads nested coordinate arrays (a Polygon's rings or a MultiPolygon's polygons) into the
	 * given list: an array of positions is a ring, whatever depth it is found at.
	 */
	private static void nested(JsonReader json, List<float[]> rings) throws IOException {
		collect(array(json), rings);
	}

	private static void collect(Object value, List<float[]> rings) {
		if (!(value instanceof List<?> list) || list.isEmpty()) {
			return;
		}
		if (list.get(0) instanceof double[]) {
			float[] ring = new float[2 * list.size()];
			int size = 0;
			for (Object element : list) {
				if (element instanceof double[] position && position.length >= 2) {
					ring[size++] = (float) position[1];
					ring[size++] = (float) position[0];
				}
			}
			if (size >= 6) {
				rings.add(Arrays.copyOf(ring, size));
			}
			return;
		}
		for (Object element : list) {
			collect(element, rings);
		}
	}

	/** An array of numbers as a <code>double[]</code>, an array of arrays as a list. */
	private static Object array(JsonReader json) throws IOException {
		json.beginArray();
		if (json.hasNext() && json.peek() == JsonToken.NUMBER) {
			double[] numbers = new double[3];
			int size = 0;
			while (json.hasNext()) {
				double number = json.nextDouble();
				if (size < numbers.length) {
					numbers[size++] = number;
				}
			}
			json.endArray();
			return Arrays.copyOf(numbers, size);
		}
		List<Object> result = new ArrayList<>();
		while (json.hasNext()) {
			if (json.peek() == JsonToken.BEGIN_ARRAY) {
				result.add(array(json));
			} else {
				json.skipValue();
			}
		}
		json.endArray();
		return result;
	}
}
