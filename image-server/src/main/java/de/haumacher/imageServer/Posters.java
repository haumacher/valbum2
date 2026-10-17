/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.imageServer.shared.model.VideoPoster;
import java.io.File;

/**
 * The chosen poster of a video, see issue #285 and {@link ImagePart#getPoster()}.
 *
 * <p>
 * A moment in milliseconds from the start of the video; <code>0</code> stands for "none chosen",
 * the first frame, which is what every video showed before. The moment is part of the name of the
 * poster in the cache ({@link PreviewCache#posterFile(File, long)}) and of the address a tile asks
 * for it at (<code>?type=tn&amp;poster=&lt;millis&gt;</code>), exactly as the region of a crop is
 * (issue #212): another moment is another picture, never a stale one.
 * </p>
 */
public final class Posters {

	private Posters() {
		// Static helpers only.
	}

	/** The moment the given part's poster shows, <code>0</code> for the first frame. */
	public static long millis(ImagePart image) {
		return image == null ? 0 : millis(image.getPoster());
	}

	/** The moment the given cover shows, <code>0</code> for the first frame. */
	public static long millis(ThumbnailInfo cover) {
		return cover == null ? 0 : millis(cover.getPoster());
	}

	/** The moment of the given poster, <code>0</code> for none. */
	public static long millis(VideoPoster poster) {
		return poster == null ? 0 : Math.max(0, poster.getMillis());
	}

	/** The poster at the given moment, <code>null</code> for the first frame. */
	public static VideoPoster of(long millis) {
		return millis <= 0 ? null : VideoPoster.create().setMillis(millis);
	}

	/** Whether the given part is a video, which alone has a poster to choose. */
	public static boolean isVideo(ImagePart image) {
		return image.getKind() != ImageKind.IMAGE || PreviewCache.isVideoName(image.getName());
	}

	/**
	 * Reads the stored poster as it is meant: none on a photograph, none for a moment of
	 * <code>0</code> or less (a hand-edited or client-written sidecar never makes a poster of
	 * nothing).
	 *
	 * @return Whether anything was dropped.
	 */
	public static boolean sanitize(ImagePart image) {
		if (image.getPoster() != null && (millis(image) <= 0 || !isVideo(image))) {
			image.setPoster(null);
			return true;
		}
		return false;
	}

	/**
	 * The moment the album the given video lies in stores for it, <code>0</code> where it stores
	 * none, read from the sidecar beside it.
	 */
	public static long stored(File video) {
		if (!PreviewCache.isVideoName(video.getName())) {
			return 0;
		}
		FolderResource sidecar = ResourceCache.sidecar(video.getParentFile());
		if (!(sidecar instanceof AlbumInfo)) {
			return 0;
		}
		return millis(Crops.findImage((AlbumInfo) sidecar, video.getName()));
	}

	/**
	 * Parses the <code>poster</code> parameter of a <code>?type=tn</code>: a whole number of
	 * milliseconds, <code>-1</code> where it is none.
	 */
	public static long parse(String parameter) {
		if (parameter == null || parameter.isEmpty() || parameter.length() > 15) {
			return -1;
		}
		for (int n = 0; n < parameter.length(); n++) {
			char ch = parameter.charAt(n);
			if (ch < '0' || ch > '9') {
				return -1;
			}
		}
		return Long.parseLong(parameter);
	}
}
