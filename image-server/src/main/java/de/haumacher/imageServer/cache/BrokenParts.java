/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.cache;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import java.util.ArrayList;

/**
 * The parts of an album that stand for a file that cannot be read, see issue #253 and
 * {@link ImagePart#getBroken()}.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class BrokenParts {

	private BrokenParts() {
		// Static use only.
	}

	/** Whether the given part stands for a file that cannot be read. */
	public static boolean isBroken(ImagePart image) {
		return image != null && image.getBroken() != null && !image.getBroken().isEmpty();
	}

	/**
	 * Whether the given part says nothing about a picture: no size in pixels. A part an album stores
	 * for a file that could not be read when it was written (see {@link #clear(AlbumInfo)}); the
	 * analysis record says what the file is now, see {@link AnalysisRecord}.
	 */
	public static boolean hasNoPicture(ImagePart image) {
		return image.getWidth() <= 0 || image.getHeight() <= 0;
	}

	/**
	 * Clears what a broken part derives before the album is written: the failure and the size of
	 * the file are answered from the analysis record of the folder on every read and never stored,
	 * so that a file that is repaired is analysed again and becomes an ordinary part, see issue
	 * #253.
	 *
	 * <p>
	 * The part itself stays: what was said about it (a rating, the trash above all, a description)
	 * is the author's, and is kept by its file name like that of every other part. It is stored
	 * without a size in pixels, which is what makes the analysis look at its file again, see
	 * {@link #hasNoPicture(ImagePart)}.
	 * </p>
	 *
	 * @return Whether anything was cleared.
	 */
	public static boolean clear(AlbumInfo album) {
		boolean changed = false;
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				changed |= clear((ImagePart) part);
			} else if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					changed |= clear(image);
				}
			}
		}
		return changed;
	}

	private static boolean clear(ImagePart image) {
		boolean changed = false;
		if (isBroken(image)) {
			image.setBroken("");
			changed = true;
		}
		if (image.getFileSize() != 0L) {
			image.setFileSize(0L);
			changed = true;
		}
		return changed;
	}

	/**
	 * Takes what the analysis found into a stored part that says nothing about its picture: a file
	 * that was broken when the album was written, and is read now. What the author said stays; the
	 * kind, the size in pixels and the date are the file's, and what the part leaves empty (the
	 * camera, the position, a description, face tags an older tool wrote) is taken from the file.
	 */
	public static void adopt(ImagePart stored, ImagePart analysed) {
		stored.setKind(analysed.getKind());
		stored.setWidth(analysed.getWidth());
		stored.setHeight(analysed.getHeight());
		stored.setDate(analysed.getDate());
		if (stored.getCamera() == null || stored.getCamera().isEmpty()) {
			stored.setCamera(analysed.getCamera());
		}
		if (stored.getLocation() == null && analysed.getLocation() != null) {
			stored.setLocation(analysed.getLocation());
		}
		if (stored.getComment() == null || stored.getComment().isEmpty()) {
			stored.setComment(analysed.getComment());
		}
		if (stored.getTags().isEmpty() && !analysed.getTags().isEmpty()) {
			stored.setTags(new ArrayList<>(analysed.getTags()));
		}
	}
}
