/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

import java.util.List;

/**
 * The answer of {@link Places#lookup(double, double)}: the place tags of a position, or why there
 * are none yet, see issue #234.
 *
 * <p>
 * An available answer with no tags is an honest one (the open sea); an unavailable one says that
 * data is missing and the position is to be looked up again later.
 * </p>
 */
public final class PlaceResult {

	private final List<PlaceTag> _tags;

	private final String _version;

	private final String _reason;

	private final String _sentence;

	private PlaceResult(List<PlaceTag> tags, String version, String reason, String sentence) {
		_tags = tags;
		_version = version;
		_reason = reason;
		_sentence = sentence;
	}

	/**
	 * A result.
	 *
	 * @param tags
	 *        From the largest ({@link PlaceKind#COUNTRY}) to the smallest, empty where the position
	 *        lies in no country.
	 * @param version
	 *        The {@link Places#currentVersion(String) gazetteer version} it was made from.
	 */
	public static PlaceResult found(List<PlaceTag> tags, String version) {
		return new PlaceResult(List.copyOf(tags), version, null, null);
	}

	/** No result, because data is missing or broken: the reason says what. */
	public static PlaceResult unavailable(String reason) {
		return unavailable(reason, "Place names cannot be looked up: " + reason + ".");
	}

	/**
	 * No result, because data is missing or broken.
	 *
	 * @param reason
	 *        What is missing, for the log.
	 * @param sentence
	 *        What to tell a person, see {@link #getSentence()}.
	 */
	public static PlaceResult unavailable(String reason, String sentence) {
		return new PlaceResult(List.of(), null, reason, sentence);
	}

	/** Whether this is a result; <code>false</code> means "ask again later", see {@link #getReason()}. */
	public boolean isAvailable() {
		return _reason == null;
	}

	/** The tags from the country down; empty where unavailable or in no country. */
	public List<PlaceTag> getTags() {
		return _tags;
	}

	/** The tag of the given kind, <code>null</code> if there is none. */
	public PlaceTag get(PlaceKind kind) {
		for (PlaceTag tag : _tags) {
			if (tag.kind() == kind) {
				return tag;
			}
		}
		return null;
	}

	/**
	 * The gazetteer version the tags were made from, see {@link Places#isCurrent(String)};
	 * <code>null</code> where unavailable.
	 */
	public String getVersion() {
		return _version;
	}

	/** Why there is no result, <code>null</code> for a result. */
	public String getReason() {
		return _reason;
	}

	/**
	 * Why there is no result, as a sentence for the person looking at the photograph: "Place names
	 * for China are being loaded." or "Place names for China cannot be loaded: ..."; <code>null</code>
	 * for a result.
	 */
	public String getSentence() {
		return _sentence;
	}

	@Override
	public String toString() {
		return isAvailable() ? _tags + " @" + _version : "unavailable: " + _reason;
	}
}
