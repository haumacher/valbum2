/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

/**
 * Distances on the earth, in kilometres.
 */
final class Geo {

	/** The mean radius of the earth. */
	static final double EARTH_RADIUS = 6371.0;

	/** The length of a degree of latitude. */
	static final double KM_PER_DEGREE = Math.PI * EARTH_RADIUS / 180;

	private Geo() {
		// Static only.
	}

	/** The great-circle distance between two positions (haversine). */
	static double distance(double lat1, double lon1, double lat2, double lon2) {
		double dLat = Math.toRadians(lat2 - lat1);
		double dLon = Math.toRadians(lon2 - lon1);
		double sinLat = Math.sin(dLat / 2);
		double sinLon = Math.sin(dLon / 2);
		double a = sinLat * sinLat + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sinLon * sinLon;
		return 2 * EARTH_RADIUS * Math.asin(Math.min(1, Math.sqrt(a)));
	}

	/**
	 * The distance between two close positions (a few tens of kilometres) in a plane tangent at the
	 * first: a thousandth off at most, and no trigonometry per entry.
	 *
	 * @param scale
	 *        The cosine of the first latitude.
	 */
	static double near(double lat1, double lon1, double scale, double lat2, double lon2) {
		double y = lat2 - lat1;
		double x = wrap(lon2 - lon1) * scale;
		return Math.sqrt(x * x + y * y) * KM_PER_DEGREE;
	}

	/**
	 * The distance of a position from a line segment, in a plane tangent at the position: good
	 * for the few tens of kilometres a border margin spans.
	 */
	static double distanceToSegment(double lat, double lon, double lat1, double lon1, double lat2, double lon2) {
		double scale = Math.cos(Math.toRadians(lat));
		double x1 = wrap(lon1 - lon) * scale;
		double y1 = lat1 - lat;
		double x2 = wrap(lon2 - lon) * scale;
		double y2 = lat2 - lat;
		double dx = x2 - x1;
		double dy = y2 - y1;
		double length = dx * dx + dy * dy;
		double t = length == 0 ? 0 : Math.max(0, Math.min(1, -(x1 * dx + y1 * dy) / length));
		double x = x1 + t * dx;
		double y = y1 + t * dy;
		return Math.sqrt(x * x + y * y) * KM_PER_DEGREE;
	}

	/** A difference of longitudes into [-180, 180). */
	static double wrap(double dLon) {
		double result = dLon;
		while (result >= 180) {
			result -= 360;
		}
		while (result < -180) {
			result += 360;
		}
		return result;
	}
}
