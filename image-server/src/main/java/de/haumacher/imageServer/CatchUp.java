/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.AnalysisRecord;
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
import de.haumacher.imageServer.shared.model.FolderInfo;
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
 * above it, and the large preview of a starred tile's picture (issue #241);</li>
 * <li>faces, where the space has them on ({@link FaceIndex#step()}), the prototypes of the
 * recognition in the index of the space ({@link FaceIndex#prototypeStep()}, issue #264), and the
 * crop of every face an album answers ({@link FaceIndex#cropStep()}, issue #256);</li>
 * <li>places ({@link de.haumacher.imageServer.places.PlacesStep}), installed by
 * {@link ImageServlet#setPlaces};</li>
 * <li>{@link Videos}: the teaser and the playback rendition of every video, one unit each, behind
 * the photographs of every album.</li>
 * </ol>
 *
 * <p>
 * Every step does nothing where its work is there: a request never makes a preview, it serves the
 * one the background made or answers "not there yet" (issue #256), so every kind of generated file a
 * request may ask for is made here. Whether a generated file still describes its original is
 * decided before these steps, by the analysis ({@link AnalysisRecord}): it drops everything made
 * of an original whose size or modification time changed, and these steps make it again. Nothing is written
 * beside a photograph but into <code>.vacache</code>. Every unit that decodes a picture waits for
 * requests and runs alone, see {@link Background}.
 * </p>
 *
 * <p>
 * What a person waits for goes first, in the fast lane of the pipeline (issue #258): the thumbnail
 * and display rendition of a file just uploaded, file by file ({@link Previews}, see
 * {@link FolderPipeline.Step#runFile(File, FolderPipeline.Pass)}), and a generated file a request
 * was answered "not there yet" for.
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
			// The prototypes of the recognition in the index of the space, see issue #264.
			pipeline.addStep(faces.prototypeStep());
			// The crop of every answered face, which a request never cuts, see issue #256.
			pipeline.addStep(faces.cropStep());
		}
		if (places != null) {
			pipeline.addStep(places);
		}
		pipeline.addStep(new Videos(videos));
	}

	/**
	 * Follows the administrator's switch of the faces of a space while the server runs, see issue
	 * #297: on, the detection and the crops join the pipeline where {@link #install} puts them,
	 * and every album of the space is handed to the catch-up, newest first, so that the background
	 * looks at it as it would after a start; off, they leave it again. Nothing stored is touched
	 * either way.
	 *
	 * @param installed
	 *        Whether {@link #install} ran, i.e. whether this space has a background at all; without
	 *        one (a test's servlet) only the index itself is switched.
	 */
	public static void switchFaces(FolderPipeline pipeline, Path root, FaceIndex faces, boolean on,
			boolean installed) {
		faces.setEnabled(on);
		if (!installed) {
			return;
		}
		if (on && faces.isEnabled()) {
			pipeline.addStepAfter(COVER, faces.step());
			pipeline.addStepAfter(FaceIndex.STEP, faces.prototypeStep());
			pipeline.addStepAfter(FaceIndex.PROTOTYPES, faces.cropStep());
			if (pipeline.isStarted()) {
				// The walk on the pipeline's thread, not on the request that saved the switch.
				try {
					pipeline.execute(
						() -> pipeline.catchUp(FolderPipeline.newestFirst(FolderPipeline.tree(root.toFile()))));
				} catch (IllegalStateException ex) {
					// Stopped meanwhile: the next start walks the space anyway.
				}
			}
		} else {
			pipeline.removeStep(faces.step());
			pipeline.removeStep(faces.prototypeStep());
			pipeline.removeStep(faces.cropStep());
		}
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
			case de.haumacher.imageServer.cache.AnalysisStep.NAME:
				return CatchUpStep.ANALYSIS;
			case de.haumacher.imageServer.upload.HashIndex.STEP:
				return CatchUpStep.HASH;
			case PREVIEWS:
				return CatchUpStep.PREVIEWS;
			case COVER:
				return CatchUpStep.COVER;
			case FaceIndex.STEP:
			case FaceIndex.CROP_STEP:
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
		if (PreviewCache.present(target)) {
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
		public boolean trustsRecord() {
			// A preview a request asks for that is not there is made by nobody else (issue #256): the
			// crop of a photograph is the author's choice in a sidecar, which changes no fingerprint,
			// and a preview thrown away by hand must come back. The check is a stat per file.
			return false;
		}

		@Override
		public Outcome run(File folder) {
			// The records of the originals of issue #235 are litter since issue #256.
			PreviewCache.dropLeftoverRecords(folder);
			File[] files = images(folder);
			if (files.length == 0) {
				return Outcome.DONE;
			}
			if (!writable(folder)) {
				return Outcome.UNWRITABLE;
			}
			FolderResource sidecar = ResourceCache.sidecar(folder);
			AlbumInfo album = sidecar instanceof AlbumInfo ? (AlbumInfo) sidecar : null;
			// A file that cannot be read gets no preview: it is shown as broken, see issue #253.
			Set<String> broken = AnalysisRecord.broken(folder);
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
					if (broken.contains(file.getName())) {
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
					// The poster a video's author chose, see issue #285: the moment is the author's
					// choice in the sidecar, like the crop.
					long poster = Posters.millis(part);
					if (poster > 0 && PreviewCache.isVideoName(file.getName())) {
						report(folder, file, make(file, PreviewCache.posterFile(file, poster),
							() -> PreviewCache.createPoster(file, poster)));
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

		/**
		 * The fast lane of issue #258: the thumbnail of a file just uploaded (the preview of a
		 * photograph, the poster of a video), then its display rendition, before the folder as a
		 * whole. A raw beside its JPEG and a file that cannot be read get nothing, as above.
		 */
		@Override
		public void runFile(File file, FolderPipeline.Pass pass) throws InterruptedException {
			File folder = file.getParentFile();
			if (!ResourceCache.isImage(file) || AnalysisRecord.broken(folder).contains(file.getName())
				|| !writable(folder)) {
				return;
			}
			if (RawFile.isRaw(file) && hasCompanion(file)) {
				return;
			}
			switch (pass) {
				case THUMBNAIL:
					report(folder, file, make(file, PreviewCache.previewFile(file), () -> PreviewCache.createPreview(file)));
					break;
				case DISPLAY:
					if (PreviewCache.needsDisplay(file)) {
						report(folder, file, make(file, PreviewCache.displayFile(file),
							() -> PreviewCache.createDisplay(file)));
					}
					break;
				default:
					break;
			}
		}

		/** Whether a picture of the name of the given raw stands beside it, see issue #191. */
		private static boolean hasCompanion(File raw) {
			String base = RawPairs.base(raw.getName());
			for (File other : images(raw.getParentFile())) {
				if (RawPairs.isCompanionType(other.getName()) && RawPairs.base(other.getName()).equals(base)) {
					return true;
				}
			}
			return false;
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
					FolderInfo info = ResourceCache.tile(tile);
					ThumbnailInfo picture = info.getIndexPicture();
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
					boolean video = PreviewCache.isVideoName(image.getName());
					double[] region = crop == null || video ? null
						: new double[] { crop.getX(), crop.getY(), crop.getW(), crop.getH() };
					if (failure == null && region != null) {
						failure = make(image, PreviewCache.croppedPreviewFile(image, region),
							() -> PreviewCache.createPreview(image, region));
					}
					long poster = video ? Posters.millis(picture) : 0;
					if (failure == null && poster > 0) {
						failure = make(image, PreviewCache.posterFile(image, poster),
							() -> PreviewCache.createPoster(image, poster));
					}
					if (failure == null && info.isStarred() && !video) {
						// A starred tile is 2x2 and asks for the large preview on a dense screen,
						// see issue #241. A video's poster has no larger size. A tile that loses
						// its star keeps what was made for it.
						failure = make(image, PreviewCache.largePreviewFile(image, region),
							() -> PreviewCache.createLargePreview(image, region));
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
			// A video that cannot be read is never transcoded: it is shown as broken, see issue #253.
			Set<String> broken = AnalysisRecord.broken(folder);
			for (File file : images(folder)) {
				if (VideoRenditions.isVideo(file) && !broken.contains(file.getName())) {
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
		public void putAside() {
			// For the fast lane, see issue #258: made again from the start, later.
			_videos.putAsideBackground();
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
			if (PreviewCache.present(VideoRenditions.file(video, kind))) {
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
