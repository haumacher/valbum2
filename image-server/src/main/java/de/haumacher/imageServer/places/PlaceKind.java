/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

/**
 * The level of a {@link PlaceTag} in the hierarchy of a position, see issue #234, from the
 * largest to the smallest.
 */
public enum PlaceKind {

	/** The country: <code>countryInfo.txt</code>'s name and id. */
	COUNTRY,

	/** The first administrative level (<code>ADM1</code>): a German state, a US state. */
	ADM1,

	/** The second (<code>ADM2</code>): a German Regierungsbezirk, a US county. */
	ADM2,

	/** The third (<code>ADM3</code>): a German Kreis. */
	ADM3,

	/** The fourth (<code>ADM4</code>): a German Gemeinde. */
	ADM4,

	/** The populated place: a city, town or village (<code>PPL*</code>). */
	PLACE,

	/** The part of town (<code>PPLX</code>). */
	DISTRICT,

	/** A named feature close by: a park, lake, mountain, castle, church, see {@link FeatureCodes}. */
	FEATURE;

}
