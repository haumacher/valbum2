/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.faces.FaceIndex;
import de.haumacher.imageServer.pipeline.Background;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.pipeline.FolderPipeline.Outcome;
import de.haumacher.imageServer.pipeline.ReadOnlyFolders;
import de.haumacher.imageServer.raw.RawFile;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.CatchUpStatus;
import de.haumacher.imageServer.shared.model.CatchUpStep;
import de.haumacher.imageServer.shared.model.Crop;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Brings every album up to date in the background before its first visit, see issue #236.
 *
 * <p>
 * "My library contains hundreds or even thousands of albums &mdash; and if the first visit requires
 * thumbnail creation and face indexing, each first visit is a pain." So the background work of a
 * space (its {@link FolderPipeline}) does, behind hashing, what a first visit would otherwise wait
 * for, newest album first, in this order:
 * </p>
 *
 * <ol>
 * <li>{@link Previews}: the preview of every photograph and video poster, the cut preview of every
 * cropped photograph, and the display rendition of a HEIC, AVIF, JPEG XL or raw picture;</li>
 * <li>{@link Cover}: the picture of the album's tile in the listing, and of the tile of every folder
 * above it;</li>
 * <li>faces, where the space has them on ({@link FaceIndex#step()});</li>
 * <li>places ({@link de.haumacher.imageServer.places.PlacesStep}), installed by
 * {@link ImageServlet#setPlaces};</li>
 * <li>{@link Videos}: the teaser and the playback rendition of every video, one unit each, behind
 * the photographs of every album.</li>
 * </ol>
 *
 * <p>
 * Every step does nothing where its work is current: a preview and a rendition are judged by the
 * record of their original (issue #235), exactly as a request judges them. Nothing is written
 * beside a photograph but into <code>.vacache</code>. Every unit that decodes a picture waits for
 * requests and runs alone, see {@link Background}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class CatchUp {

	private static final Logger LOG = Logger.getLogger(CatchUp.class.getName());

	/** The name of the {@link Previews} step. */
	public static final String PREVIEWS = "previews";

	/** The name of the {@link Cover} step. */
	public static final String COVER = "cover";

	/** The name of the {@link Videos} step. */
	public static final String VIDEOS = "videos";

	private CatchUp() {
		// Static use only.
	}

	/**
	 * Adds the steps of issue #236 to the given pipeline behind hashing, the given places step (if
	 * any) behind faces, where it belongs.
	 */
	public static void install(FolderPipeline pipeline, Path root, FaceIndex faces, VideoRenditions videos,
			FolderPipeline.Step places) {
		if (places != null) {
			pipeline.removeStep(places);
		}
		pipeline.addStep(new Previews(pipeline));
		pipeline.addStep(new Cover(pipeline, root));
		if (faces.isEnabled()) {
			pipeline.addStep(faces.step());
		}
		if (places != null) {
			pipeline.addStep(places);
		}
		pipeline.addStep(new Videos(videos));
	}

	/** The status of the given pipeline as the protocol says it. */
	public static CatchUpStatus status(FolderPipeline pipeline) {
		FolderPipeline.Status status = pipeline.status();
		return CatchUpStatus.create()
			.setAlbumsDone(status.getDone())
			.setAlbumsTotal(status.getTotal())
			.setStep(step(status.getStep()))
			.setFolder(status.getFolder() == null ? "" : status.getFolder())
			.setVideosRemaining(status.getVideos())
			.setFailure(status.getFailure() == null ? "" : status.getFailure())
			.setFailureFolder(status.getFailureFolder() == null ? "" : status.getFailureFolder())
			.setYielding(status.getStep() != null && Background.busy());
	}

	private static CatchUpStep step(String name) {
		if (name == null) {
			return CatchUpStep.IDLE;
		}
		switch (name) {
			case de.haumacher.imageServer.upload.HashIndex.STEP:
				return CatchUpStep.HASH;
			case PREVIEWS:
				return CatchUpStep.PREVIEWS;
			case COVER:
				return CatchUpStep.COVER;
			case FaceIndex.STEP:
				return CatchUpStep.FACES;
			case de.haumacher.imageServer.places.PlacesStep.NAME:
				return CatchUpStep.PLACES;
			case VIDEOS:
				return CatchUpStep.VIDEOS;
			default:
				return CatchUpStep.OTHER;
		}
	}

	/** Whether the generated files of the given folder can be stored. */
	static boolean writable(File folder) {
		File cacheDir = CacheRefresh.cacheDir(folder);
		return ReadOnlyFolders.writable(cacheDir.isDirectory() ? cacheDir : folder);
	}

	/**
	 * Makes the given generated file of the given original unless it is current: as a background
	 * unit, see {@link Background#decode}.
	 *
	 * @return The reason it could not be made, <code>null</code> when it is there.
	 */
	static String make(File original, File target, PreviewMaker maker) throws InterruptedException {
		if (PreviewCache.upToDate(original, target)) {
			return null;
		}
		try {
			Background.decode(() -> {
				maker.make();
				return null;
			});
			return null;
		} catch (InterruptedException ex) {
			throw ex;
		} catch (PreviewException ex) {
			LOG.log(Level.FINE, ex.getMessage(), ex);
			return ex.getMessage();
		} catch (Exception ex) {
			LOG.log(Level.WARNING, "Cannot make '" + target.getName() + "': " + ex.getMessage(), ex);
			return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
		}
	}

	/** Makes one generated file. */
	interface PreviewMaker {

		/** Makes it. */
		void make() throws PreviewException;
	}

	/** The image and video files of the given folder, by name. */
	static File[] images(File folder) {
		File[] files = folder.listFiles(f -> f.isFile() && ResourceCache.isImage(f));
		if (files == null) {
			return new File[0];
		}
		Arrays.sort(files, (left, right) -> left.getName().compareTo(right.getName()));
		return files;
	}

	/**
	 * The previews of an album: what its page asks for first, see {@link CatchUp}.
	 *
	 * <p>
	 * A raw standing beside its JPEG is shown as the JPEG (issue #191) and gets nothing. One
	 * photograph that cannot be read is reported to the status and does not hold up the album: the
	 * step is done once every photograph was tried, and is tried again when the album changes.
	 * </p>
	 */
	static final class Previews implements FolderPipeline.Step {

		private final FolderPipeline _pipeline;

		Previews(FolderPipeline pipeline) {
			_pipeline = pipeline;
		}

		@Override
		public String name() {
			return PREVIEWS;
		}

		@Override
		public Outcome run(File folder) {
			File[] files = images(folder);
			if (files.length == 0) {
				return Outcome.DONE;
			}
			if (!writable(folder)) {
				return Outcome.UNWRITABLE;
			}
			FolderResource sidecar = ResourceCache.sidecar(folder);
			AlbumInfo album = sidecar instanceof AlbumInfo ? (AlbumInfo) sidecar : null;
			Set<String> pairedBases = new HashSet<>();
			for (File file : files) {
				if (RawPairs.isCompanionType(file.getName())) {
					pairedBases.add(RawPairs.base(file.getName()));
				}
			}
			try {
				for (File file : files) {
					if (Thread.currentThread().isInterrupted()) {
						return Outcome.WAITING;
					}
					if (RawFile.isRaw(file) && pairedBases.contains(RawPairs.base(file.getName()))) {
						continue;
					}
					report(folder, file, make(file, PreviewCache.previewFile(file),
						() -> PreviewCache.createPreview(file)));
					ImagePart part = album == null ? null : Crops.findImage(album, file.getName());
					double[] region = part == null ? null : Crops.renditionRegion(part);
					if (region != null) {
						report(folder, file, make(file, PreviewCache.croppedPreviewFile(file, region),
							() -> PreviewCache.createPreview(file, region)));
					}
					if (PreviewCache.needsDisplay(file)) {
						report(folder, file, make(file, PreviewCache.displayFile(file),
							() -> PreviewCache.createDisplay(file)));
					}
				}
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return Outcome.WAITING;
			}
			return Outcome.DONE;
		}

		private void report(File folder, File file, String failure) {
			if (failure != null) {
				_pipeline.reportFailure(folder, PREVIEWS + ": " + file.getName() + ": " + failure);
			}
		}
	}

	/**
	 * The tile of the album in the listing above it, and the tile of every folder above that, see
	 * {@link ResourceCache#tilePicture(File)} and {@link FolderCover}.
	 *
	 * <p>
	 * Not trusting the record: which picture a tile shows is the author's choice in a sidecar, which
	 * changes without the photographs changing, and finding out is a few sidecar reads.
	 * </p>
	 */
	static final class Cover implements FolderPipeline.Step {

		private final FolderPipeline _pipeline;

		private final Path _root;

		Cover(FolderPipeline pipeline, Path root) {
			_pipeline = pipeline;
			_root = root.toAbsolutePath().normalize();
		}

		@Override
		public String name() {
			return COVER;
		}

		@Override
		public boolean trustsRecord() {
			return false;
		}

		@Override
		public Outcome run(File folder) {
			try {
				for (File tile : tiles(folder)) {
					if (Thread.currentThread().isInterrupted()) {
						return Outcome.WAITING;
					}
					ThumbnailInfo picture = ResourceCache.tilePicture(tile);
					if (picture == null || picture.getImage() == null || picture.getImage().isEmpty()) {
						continue;
					}
					File image = new File(tile, picture.getImage());
					if (!image.isFile() || !ResourceCache.isImage(image) || !writable(image.getParentFile())) {
						continue;
					}
					String failure = make(image, PreviewCache.previewFile(image),
						() -> PreviewCache.createPreview(image));
					Crop crop = picture.getCrop();
					if (failure == null && crop != null && !PreviewCache.isVideoName(image.getName())) {
						double[] region = { crop.getX(), crop.getY(), crop.getW(), crop.getH() };
						failure = make(image, PreviewCache.croppedPreviewFile(image, region),
							() -> PreviewCache.createPreview(image, region));
					}
					if (failure != null) {
						_pipeline.reportFailure(tile, COVER + ": " + picture.getImage() + ": " + failure);
					}
				}
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return Outcome.WAITING;
			}
			return Outcome.DONE;
		}

		/**
		 * The given folder and every folder above it below the space root: the ones shown as a tile
		 * of a listing. The inbox is never a tile.
		 */
		private List<File> tiles(File folder) {
			List<File> result = new ArrayList<>();
			Path path = folder.toPath().toAbsolutePath().normalize();
			while (path != null && path.startsWith(_root) && !path.equals(_root)) {
				File each = path.toFile();
				if (!Inboxes.isInbox(each)) {
					result.add(each);
				}
				path = path.getParent();
			}
			return result;
		}
	}

	/**
	 * The renditions of the videos of an album, see {@link VideoRenditions}: every teaser first (what
	 * the album shows while pointing at a tile), then every playback rendition, one unit each.
	 *
	 * <p>
	 * A unit waits for requests before it starts and runs in the pipeline's thread, one transcode at a
	 * time with the requests' own (see {@link VideoRenditions#make}): a request for a rendition that is
	 * not there yet kills the background transcode, which is put back and made again later
	 * ({@link Outcome#AGAIN}); a request for the rendition the background is making waits for it.
	 * While requests for renditions keep coming, the videos wait, as the photographs do.
	 * </p>
	 */
	static final class Videos implements FolderPipeline.UnitStep {

		private static final char SEPARATOR = ':';

		private final VideoRenditions _videos;

		Videos(VideoRenditions videos) {
			_videos = videos;
		}

		@Override
		public String name() {
			return VIDEOS;
		}

		@Override
		public List<String> units(File folder) {
			List<String> teasers = new ArrayList<>();
			List<String> playback = new ArrayList<>();
			for (File file : images(folder)) {
				if (VideoRenditions.isVideo(file)) {
					teasers.add(VideoRenditions.Kind.TEASER.parameter() + SEPARATOR + file.getName());
					playback.add(VideoRenditions.Kind.PLAYBACK.parameter() + SEPARATOR + file.getName());
				}
			}
			teasers.addAll(playback);
			return teasers;
		}

		@Override
		public String itemOf(String unit) {
			return unit.substring(unit.indexOf(SEPARATOR) + 1);
		}

		@Override
		public Outcome runUnit(File folder, String unit) {
			int separator = unit.indexOf(SEPARATOR);
			if (separator < 0) {
				return Outcome.DONE;
			}
			VideoRenditions.Kind kind = VideoRenditions.Kind.TEASER.parameter().equals(unit.substring(0, separator))
				? VideoRenditions.Kind.TEASER : VideoRenditions.Kind.PLAYBACK;
			File video = new File(folder, unit.substring(separator + 1));
			if (!video.isFile()) {
				return Outcome.DONE;
			}
			if (PreviewCache.upToDate(video, VideoRenditions.file(video, kind))) {
				return Outcome.DONE;
			}
			if (VideoRenditions.unavailability() != null) {
				// Said once at start-up; nothing is recorded, so a server that can transcode later does.
				return Outcome.WAITING;
			}
			if (!writable(folder)) {
				return Outcome.UNWRITABLE;
			}
			VideoRenditions.Rendition rendition;
			try {
				Background.awaitQuiet();
				rendition = _videos.make(video, kind);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return Outcome.WAITING;
			}
			switch (rendition.getState()) {
				case READY:
					return Outcome.DONE;
				case FAILED:
					throw new IllegalStateException(rendition.getReason());
				case DEFERRED:
					// A request's transcode went first; this one is made again, from the start.
					return Outcome.AGAIN;
				default:
					return Outcome.WAITING;
			}
		}
	}
}
