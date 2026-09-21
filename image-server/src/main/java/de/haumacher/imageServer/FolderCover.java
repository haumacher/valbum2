/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import java.io.File;

/**
 * The picture a folder of folders is shown with, see issue #110.
 *
 * <p>
 * A folder holds no photograph of its own, so what it is shown with can only be a picture of
 * something below it. Which one is a statement of the author — {@link ListingInfo#getIndex()},
 * stored in the folder's own <code>index.json</code> — and never a guess of the server's: a folder
 * that chose nothing keeps the folder icon it always had.
 * </p>
 *
 * <p>
 * The choice is resolved through the sidecars and through nothing else: the chosen child's
 * <code>index.json</code> says either "I am an album and this is my index picture" or "I am a
 * folder and I chose this child of mine", and the second is asked the same question again. No
 * image is ever opened, which is the rule a listing has lived by since issue #48 — a listing of a
 * thousand folders opens no photograph.
 * </p>
 *
 * <p>
 * What comes out is the album's own {@link ThumbnailInfo} — its crop and the
 * {@link ThumbnailInfo#getOrientation() frame} that crop was measured in, see issue #115 — with
 * the path from the folder down to the photograph written into
 * {@link ThumbnailInfo#getImage()}. The client builds its address exactly as it always did
 * (<code>&lt;listing&gt;/&lt;name&gt;/&lt;image&gt;</code>), because the extra segments are part
 * of the image.
 * </p>
 *
 * <p>
 * Nothing here ever fails: a choice naming a child that is gone, that has no sidecar, that shows
 * no picture, or that leads in a circle is answered with no picture at all. A dangling name is
 * left standing — the author may have moved the album away and back — and it is never rewritten
 * behind the author's back; the one exception is the {@link MoveService#renameFolder rename} of
 * the chosen child itself, which carries the name along in the same step.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class FolderCover {

	/**
	 * How many folders deep a choice is followed before the server gives up.
	 *
	 * <p>
	 * A cap and not a cycle detector: a chain of folders on disk cannot be a circle, but a
	 * symbolic link can make one, and there is no depth at which following further would answer a
	 * better picture than giving up does.
	 * </p>
	 */
	public static final int MAX_DEPTH = 8;

	private FolderCover() {
		// Static utility.
	}

	/**
	 * The picture the folder of folders at the given place is shown with.
	 *
	 * @param folder
	 *        The directory the listing describes.
	 * @param listing
	 *        Its sidecar, which says what it chose.
	 * @return The cover to put on its tile, <code>null</code> where it has none.
	 */
	public static ThumbnailInfo of(File folder, ListingInfo listing) {
		return of(folder, listing, MAX_DEPTH);
	}

	private static ThumbnailInfo of(File folder, ListingInfo listing, int depth) {
		String name = chosen(listing);
		if (name == null) {
			return null;
		}
		File child = new File(folder, name);
		if (!child.isDirectory()) {
			// The chosen entry is gone: no picture, and the choice is left standing.
			return null;
		}
		FolderResource sidecar = ResourceCache.sidecar(child);
		if (sidecar instanceof AlbumInfo) {
			ThumbnailInfo picture = ((AlbumInfo) sidecar).getIndexPicture();
			if (picture == null || picture.getImage().isEmpty()) {
				return null;
			}
			if (!new File(child, picture.getImage()).isFile()) {
				// The album names a photograph that is not there any more.
				return null;
			}
			return below(name, picture);
		}
		if (sidecar instanceof ListingInfo) {
			if (depth <= 0) {
				return null;
			}
			ThumbnailInfo nested = of(child, (ListingInfo) sidecar, depth - 1);
			return nested == null ? null : below(name, nested);
		}
		// A folder without a sidecar has chosen nothing and says nothing: the picture comes from
		// an explicit choice only.
		return null;
	}

	/**
	 * The name of the child the given folder chose, <code>null</code> where it chose none or named
	 * something that is not a child of it.
	 *
	 * <p>
	 * Only a plain name is a choice. A path, a <code>..</code> or a name starting with a dot would
	 * be an address, and an address is not what this field is: it names one of the entries the
	 * listing itself shows.
	 * </p>
	 */
	public static String chosen(ListingInfo listing) {
		String name = listing.getIndex();
		if (name == null || name.isEmpty()) {
			return null;
		}
		if (name.startsWith(".") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
			return null;
		}
		return name;
	}

	/** The given picture as the folder above sees it: the same crop, one path segment deeper. */
	private static ThumbnailInfo below(String name, ThumbnailInfo picture) {
		// A copy, always: what the sidecar of the child holds is the child's and is never written
		// into by the folder above it.
		return ThumbnailInfo.create()
			.setImage(name + "/" + picture.getImage())
			.setScale(picture.getScale())
			.setTx(picture.getTx())
			.setTy(picture.getTy())
			.setOrientation(picture.getOrientation());
	}

	/**
	 * The folder holding the photograph the given cover shows.
	 *
	 * @param entry
	 *        Where the tile's own folder lies.
	 * @param image
	 *        The {@link ThumbnailInfo#getImage() image} of its cover, a bare file name for an
	 *        album and a path for a folder of folders.
	 * @return The album the photograph lies in; the entry itself where the image is a bare name.
	 */
	public static PathInfo albumOf(PathInfo entry, String image) {
		int slash = image.lastIndexOf('/');
		return slash < 0 ? entry : entry.child(image.substring(0, slash));
	}

	/** The file name of the photograph the given cover shows, without the folders above it. */
	public static String imageName(String image) {
		int slash = image.lastIndexOf('/');
		return slash < 0 ? image : image.substring(slash + 1);
	}

	/**
	 * Whether the photograph the given cover shows lies in — or below — an inbox.
	 *
	 * <p>
	 * A cover reaching into an inbox is only ever answered to a caller that may see that inbox,
	 * see issue #135: the picture a folder is shown with must not be a photograph nobody has
	 * looked at yet. Every folder on the way is asked, because a folder may have chosen a child
	 * that chose the inbox.
	 * </p>
	 *
	 * @param entry
	 *        Where the tile's own folder lies.
	 * @param image
	 *        The {@link ThumbnailInfo#getImage() image} of its cover.
	 */
	public static boolean throughInbox(PathInfo entry, String image) {
		PathInfo album = albumOf(entry, image);
		PathInfo path = album;
		while (true) {
			if (Inboxes.isInbox(path.toFile())) {
				return true;
			}
			if (path.equals(entry) || path.isRoot()) {
				return false;
			}
			path = path.parent();
		}
	}
}
