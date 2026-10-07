/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.GeoLocation;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The warm-up of the gazetteer as a step of the {@link FolderPipeline}, behind hashing, see issue
 * #234.
 *
 * <p>
 * Asks the {@link Places} for the files the positions of a folder's photographs need
 * ({@link Places#prepare(double, double)}), so that the countries are downloaded in the
 * background before anybody opens the album. It stores nothing: the places themselves are derived
 * whenever an album is read (see {@link PhotoPlaces}).
 * </p>
 *
 * <ul>
 * <li>Every file there: {@link FolderPipeline.Outcome#DONE}, recorded, and not asked again until
 * the folder's photographs change.</li>
 * <li>A file missing: {@link FolderPipeline.Outcome#WAITING}. The folder is remembered and noticed
 * again when a download ends; then it is asked again. A download that failed waits for its
 * back-off in the store, so the folder asked again simply waits once more: no request, no
 * loop.</li>
 * </ul>
 *
 * <p>
 * The position of a photograph is the one its album answers: the sidecar's where the sidecar lists
 * the photograph, else the one in the file.
 * </p>
 */
public final class PlacesStep implements FolderPipeline.Step {

	private static final Logger LOG = Logger.getLogger(PlacesStep.class.getName());

	/** The name of the step in the pipeline's record. */
	public static final String NAME = "places";

	private final Places _places;

	private final FolderPipeline _pipeline;

	/** The folders waiting for a download. */
	private final Set<File> _waiting = new LinkedHashSet<>();

	private final Runnable _listener = this::downloadEnded;

	/**
	 * Creates the step of the given pipeline, listening to the downloads of the given gazetteer
	 * until {@link #close()}.
	 */
	public PlacesStep(Places places, FolderPipeline pipeline) {
		_places = places;
		_pipeline = pipeline;
		places.addListener(_listener);
	}

	/** Stops listening to the downloads. */
	public void close() {
		_places.removeListener(_listener);
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public FolderPipeline.Outcome run(File folder) {
		File[] files = folder.listFiles(ResourceCache::isImage);
		if (files == null || files.length == 0) {
			return FolderPipeline.Outcome.DONE;
		}
		FolderResource sidecar = ResourceCache.sidecar(folder);
		if (sidecar instanceof AlbumInfo && ((AlbumInfo) sidecar).getKind() == AlbumKind.COLLECTION) {
			return FolderPipeline.Outcome.DONE;
		}
		Map<String, ImagePart> listed = listed(sidecar);
		Set<String> asked = new LinkedHashSet<>();
		boolean waiting = false;
		for (File file : files) {
			if (Thread.currentThread().isInterrupted()) {
				return FolderPipeline.Outcome.WAITING;
			}
			GeoLocation location = position(listed.get(file.getName()), file);
			if (location == null || (location.getLatitude() == 0 && location.getLongitude() == 0)) {
				continue;
			}
			// One question per square of about a kilometre: an album's photos lie close together.
			String square = Math.round(location.getLatitude() * 100) + "/" + Math.round(location.getLongitude() * 100);
			if (!asked.add(square)) {
				continue;
			}
			waiting |= _places.prepare(location.getLatitude(), location.getLongitude()) != null;
		}
		if (!waiting) {
			return FolderPipeline.Outcome.DONE;
		}
		synchronized (_waiting) {
			_waiting.add(folder);
		}
		return FolderPipeline.Outcome.WAITING;
	}

	/** The folders waiting for a download, for the tests. */
	public List<File> waiting() {
		synchronized (_waiting) {
			return new ArrayList<>(_waiting);
		}
	}

	private void downloadEnded() {
		List<File> folders;
		synchronized (_waiting) {
			folders = new ArrayList<>(_waiting);
			_waiting.clear();
		}
		for (File folder : folders) {
			_pipeline.notice(folder, false);
		}
	}

	private static GeoLocation position(ImagePart part, File file) {
		if (part != null) {
			return part.getLocation();
		}
		try {
			return ImageData.analyze(null, file).getLocation();
		} catch (Exception ex) {
			LOG.log(Level.FINE, "Cannot read the position of '" + file + "': " + ex.getMessage(), ex);
			return null;
		}
	}

	private static Map<String, ImagePart> listed(FolderResource sidecar) {
		Map<String, ImagePart> result = new HashMap<>();
		if (!(sidecar instanceof AlbumInfo)) {
			return result;
		}
		for (AlbumPart part : ((AlbumInfo) sidecar).getParts()) {
			if (part instanceof ImagePart) {
				result.put(((ImagePart) part).getName(), (ImagePart) part);
			} else if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					result.put(image.getName(), image);
				}
			}
		}
		return result;
	}
}
