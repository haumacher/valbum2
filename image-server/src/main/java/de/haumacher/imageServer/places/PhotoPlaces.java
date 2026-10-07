/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.GeoLocation;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.PlaceInfo;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The place tags of the photographs of an album, see issue #234 and {@link ImagePart#getPlaces()}.
 *
 * <p>
 * <b>Derived on every read, never stored.</b> The album loader asks {@link #derive(AlbumInfo, Places)}
 * after an album was read, from each photograph's {@link ImagePart#getLocation() position} &mdash;
 * the one the sidecar stores where it has one, else the one read from the file &mdash; and
 * {@link #clear(FolderResource)} takes the tags out again before any <code>index.json</code> is
 * written (see <code>AlbumDate.clearDerived</code>). So nothing is ever retagged: a refreshed
 * gazetteer simply answers differently on the next read, and a photograph whose country is not
 * loaded yet says why ({@link PlaceInfo#getPending()}) until its album is read again.
 * </p>
 */
public final class PhotoPlaces {

	private PhotoPlaces() {
		// Static only.
	}

	/**
	 * Gives every photograph of the given album its places.
	 *
	 * @return Whether a photograph is waiting for its places: its album is to be read again once
	 *         the gazetteer has more to say, see {@link Places#epoch()}.
	 */
	public static boolean derive(AlbumInfo album, Places places) {
		boolean pending = false;
		for (ImagePart image : images(album)) {
			PlaceInfo info = of(image.getLocation(), places);
			image.setPlaces(info);
			pending |= info != null && !info.getPending().isEmpty();
		}
		return pending;
	}

	/**
	 * The places of a position; <code>null</code> where there is nothing to say: no position, a pair
	 * of zeroes (no fix), the open sea.
	 */
	public static PlaceInfo of(GeoLocation location, Places places) {
		if (location == null) {
			return null;
		}
		double lat = location.getLatitude();
		double lon = location.getLongitude();
		if ((lat == 0 && lon == 0) || !(lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180)) {
			return null;
		}
		PlaceResult result = places.lookup(lat, lon);
		if (!result.isAvailable()) {
			return PlaceInfo.create().setPending(result.getSentence());
		}
		if (result.getTags().isEmpty()) {
			return null;
		}
		PlaceInfo info = PlaceInfo.create();
		for (PlaceTag tag : result.getTags()) {
			info.addTag(de.haumacher.imageServer.shared.model.PlaceTag.create()
				.setGeonameId(tag.geonameId())
				.setName(tag.name())
				.setKind(de.haumacher.imageServer.shared.model.PlaceKind.valueOfProtocol(tag.kind().name()))
				.setFeatureCode(tag.featureCode())
				.setCountry(tag.countryCode()));
		}
		return info;
	}

	/**
	 * The given answer with its place names in the given language, see {@link PlaceNames}: a copy
	 * where a name changes, the answer itself where none does.
	 *
	 * <p>
	 * Per caller, so never on the cached album (see <code>faq/derived-fields.md</code>): an album
	 * answer must be the caller's own shell (as <code>ImageServlet#withRights</code> makes one),
	 * whose list of parts is replaced here; a photograph whose names change is copied, and so is a
	 * group holding one. Names not there yet (still being downloaded) leave the main names.
	 * </p>
	 *
	 * @param language
	 *        The ISO 639 code, see {@link PlaceNames#language(String)}; <code>null</code> for the
	 *        main names.
	 */
	public static <T> T localize(T answer, String language, Places places) {
		if (language == null || places == null) {
			return answer;
		}
		Map<String, PlaceNames> byCountry = new HashMap<>();
		if (answer instanceof ImagePart) {
			ImagePart localized = localized((ImagePart) answer, language, places, byCountry);
			@SuppressWarnings("unchecked")
			T result = (T) localized;
			return result;
		}
		if (!(answer instanceof AlbumInfo)) {
			return answer;
		}
		AlbumInfo album = (AlbumInfo) answer;
		List<AlbumPart> parts = new ArrayList<>(album.getParts().size());
		boolean changed = false;
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				ImagePart localized = localized((ImagePart) part, language, places, byCountry);
				changed |= localized != part;
				parts.add(localized);
			} else if (part instanceof ImageGroup) {
				ImageGroup group = (ImageGroup) part;
				List<ImagePart> images = new ArrayList<>();
				boolean groupChanged = false;
				for (ImagePart image : group.getImages()) {
					ImagePart localized = localized(image, language, places, byCountry);
					groupChanged |= localized != image;
					images.add(localized);
				}
				if (groupChanged) {
					parts.add(ImageGroup.create().setRepresentative(group.getRepresentative()).setImages(images));
					changed = true;
				} else {
					parts.add(group);
				}
			} else {
				parts.add(part);
			}
		}
		if (changed) {
			album.setParts(parts);
		}
		return answer;
	}

	/** The given photograph with names in the language: itself where no name changes, else a copy. */
	private static ImagePart localized(ImagePart image, String language, Places places,
			Map<String, PlaceNames> byCountry) {
		PlaceInfo info = image.getPlaces();
		if (info == null || info.getTags().isEmpty()) {
			return image;
		}
		List<de.haumacher.imageServer.shared.model.PlaceTag> tags = new ArrayList<>();
		boolean changed = false;
		for (de.haumacher.imageServer.shared.model.PlaceTag tag : info.getTags()) {
			String country = tag.getCountry();
			PlaceNames names = byCountry.computeIfAbsent(country, iso -> places.names(iso, language));
			String name = names == null ? null : names.name(tag.getGeonameId());
			if (name == null || name.equals(tag.getName())) {
				tags.add(tag);
			} else {
				tags.add(de.haumacher.imageServer.shared.model.PlaceTag.create()
					.setGeonameId(tag.getGeonameId())
					.setName(name)
					.setKind(tag.getKind())
					.setFeatureCode(tag.getFeatureCode())
					.setCountry(country));
				changed = true;
			}
		}
		if (!changed) {
			return image;
		}
		ImagePart copy = copy(image);
		copy.setPlaces(PlaceInfo.create().setTags(tags).setPending(info.getPending()));
		return copy;
	}

	private static ImagePart copy(ImagePart image) {
		try {
			StringWriter buffer = new StringWriter();
			try (JsonWriter out = new JsonWriter(new WriterAdapter(buffer))) {
				image.writeContent(out);
			}
			ImagePart result = ImagePart.create();
			try (JsonReader in = new JsonReader(new ReaderAdapter(new StringReader(buffer.toString())))) {
				result.readContent(in);
			}
			return result;
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot copy the image '" + image.getName() + "'.", ex);
		}
	}

	/**
	 * Takes the places out of every photograph of the given resource, see
	 * <code>AlbumDate.clearDerived</code>.
	 *
	 * @return Whether there were any.
	 */
	public static boolean clear(FolderResource resource) {
		if (!(resource instanceof AlbumInfo)) {
			return false;
		}
		boolean changed = false;
		for (ImagePart image : images((AlbumInfo) resource)) {
			if (image.hasPlaces()) {
				image.setPlaces(null);
				changed = true;
			}
		}
		return changed;
	}

	private static List<ImagePart> images(AlbumInfo album) {
		List<ImagePart> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add((ImagePart) part);
			} else if (part instanceof ImageGroup) {
				result.addAll(((ImageGroup) part).getImages());
			}
		}
		return result;
	}
}
