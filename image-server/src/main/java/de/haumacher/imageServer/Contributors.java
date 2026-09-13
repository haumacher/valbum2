/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.upload.HashCache;
import java.io.File;
import java.util.Map;

/**
 * Who contributed which photo, answered on every read and never stored, see issue #53.
 *
 * <p>
 * Attribution is recorded once, where the upload already writes: in the {@link HashCache} sidecar
 * beside the photos, keyed by file name. It is not in <code>index.json</code>, because an upload
 * does not write <code>index.json</code> at all — the album index is built from the disk and
 * written by the app — so an attribution kept there would be at the mercy of the next round trip
 * through a client.
 * </p>
 *
 * <p>
 * On the wire the two fields are derived exactly like {@link AlbumInfo#getEffectiveDate()}: filled
 * in here when the album is loaded, and cleared before any sidecar is written, see
 * {@link AlbumDate#clearDerived(FolderResource)}. A client can therefore neither freeze an
 * attribution into an album nor lose one by sending the album back.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class Contributors {

	private Contributors() {
		// Static utility.
	}

	/**
	 * Says of every image of the given album who contributed it.
	 *
	 * <p>
	 * One small JSON file is read and nothing else happens: no image is opened, no hash is
	 * computed and nothing is written back, so this costs a folder load the sidecar it reads, see
	 * {@link HashCache#recorded(File)}. Every image is written, the unknown ones with the empty
	 * string, so that an attribution somebody put into <code>index.json</code> by hand never
	 * becomes an answer.
	 * </p>
	 *
	 * @param album
	 *        The album as it was loaded from the given folder.
	 * @param folder
	 *        The folder the album's photos lie in.
	 */
	public static void derive(AlbumInfo album, File folder) {
		Map<String, HashCache.Attribution> recorded = HashCache.recorded(folder);
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				apply((ImagePart) part, recorded);
			} else if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					apply(image, recorded);
				}
			}
		}
	}

	private static void apply(ImagePart image, Map<String, HashCache.Attribution> recorded) {
		HashCache.Attribution attribution = recorded.get(image.getName());
		if (attribution == null) {
			attribution = HashCache.Attribution.NONE;
		}
		image.setContributor(attribution.getContributor());
		image.setContributorLabel(attribution.getLabel());
	}

	/**
	 * Removes from the given resource what {@link #derive(AlbumInfo, File)} put there, see
	 * {@link AlbumDate#clearDerived(FolderResource)}.
	 *
	 * @return Whether anything had to be cleared.
	 */
	public static boolean clear(FolderResource resource) {
		if (!(resource instanceof AlbumInfo)) {
			return false;
		}
		boolean changed = false;
		for (AlbumPart part : ((AlbumInfo) resource).getParts()) {
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
		if (!image.getContributor().isEmpty()) {
			image.setContributor("");
			changed = true;
		}
		if (!image.getContributorLabel().isEmpty()) {
			image.setContributorLabel("");
			changed = true;
		}
		return changed;
	}
}
