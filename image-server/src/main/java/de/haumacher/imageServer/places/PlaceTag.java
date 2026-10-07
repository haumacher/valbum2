/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

/**
 * One GeoNames entry a position lies in or close to, see issue #234.
 *
 * <p>
 * An entry and not a string: two places of one name are two ids, so a filter can ask for
 * everything in one of them.
 * </p>
 *
 * @param geonameId
 *        The GeoNames id; for {@link PlaceKind#COUNTRY} the one <code>countryInfo.txt</code> names.
 * @param name
 *        The GeoNames name (the <code>name</code> column, in Latin script).
 * @param kind
 *        The level in the hierarchy.
 * @param featureCode
 *        The GeoNames feature code, e.g. <code>PPLA2</code>, <code>PRK</code>; <code>PCLI</code>
 *        for a country.
 * @param countryCode
 *        The ISO code of the country the entry belongs to.
 */
public record PlaceTag(int geonameId, String name, PlaceKind kind, String featureCode, String countryCode) {

	@Override
	public String toString() {
		return kind + ":" + name + "(" + geonameId + "," + featureCode + ")";
	}
}
