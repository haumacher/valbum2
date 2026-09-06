/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hides what a request must not see, see issue #46.
 *
 * <p>
 * The filter is the only place that removes an {@link ImagePart} from an answer, and it works on
 * the way out: what {@link ResourceCache} holds is always the complete album, and nothing here is
 * ever written back into the cache or into a sidecar. A caller whose clearance is
 * {@link Privacy#PRIVATE} — the owner of the space — is answered with the cached resource itself,
 * so a listing followed by a save by the owner is exactly the round trip it was before.
 * </p>
 *
 * <p>
 * The copy shares the {@link ImagePart}s of the cached album; it is only ever read (serialised into
 * the response), and its transient fields are deliberately not computed, so that the cached album
 * keeps its own.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class PrivacyFilter {

	private final ResourceCache _cache;

	/**
	 * Creates a {@link PrivacyFilter}.
	 *
	 * @param cache
	 *        Where the unfiltered albums come from; a listing needs the album of a folder whose
	 *        index picture turns out to be hidden.
	 */
	public PrivacyFilter(ResourceCache cache) {
		_cache = cache;
	}

	/**
	 * The given resource as the request may see it.
	 *
	 * @param resource
	 *        What the cache holds; never modified.
	 * @param path
	 *        Where the resource lies, needed to reach the folders of a listing.
	 * @param clearance
	 *        What the request may see, see
	 *        {@link de.haumacher.imageServer.auth.AuthService#clearance(de.haumacher.imageServer.auth.AuthService.Caller, PathInfo)}.
	 * @return The resource itself if nothing has to be hidden, a filtered copy otherwise.
	 */
	public Resource filter(Resource resource, PathInfo path, int clearance) {
		if (clearance >= Privacy.PRIVATE) {
			return resource;
		}
		if (resource instanceof AlbumInfo) {
			return filterAlbum((AlbumInfo) resource, clearance);
		}
		if (resource instanceof ListingInfo) {
			return filterListing((ListingInfo) resource, path, clearance);
		}
		return resource;
	}

	/**
	 * The given album without the parts above the given clearance.
	 *
	 * <p>
	 * A {@link de.haumacher.imageServer.shared.model.Heading} is kept: a section title is not a secret, and dropping it would tell the
	 * caller that something was removed. The album's own index picture follows the same rule as
	 * the cover in a listing: if it names an image the caller must not see, the first visible
	 * image takes its place, see {@link #cover(AlbumInfo, ThumbnailInfo, Set)}.
	 * </p>
	 */
	AlbumInfo filterAlbum(AlbumInfo album, int clearance) {
		AlbumInfo result = AlbumInfo.create()
			.setTitle(album.getTitle())
			.setSubTitle(album.getSubTitle())
			// Hiding an image does not change when the album happened.
			.setDate(album.getDate())
			.setEffectiveDate(album.getEffectiveDate());

		Set<String> visibleNames = new HashSet<>();
		boolean hidden = false;
		for (AlbumPart part : album.getParts()) {
			AlbumPart visible = filterPart(part, clearance);
			if (visible == null) {
				hidden = true;
				continue;
			}
			if (visible != part) {
				hidden = true;
			}
			collectNames(visible, visibleNames);
			result.addPart(visible);
		}

		if (!hidden) {
			// Nothing is hidden here: the cached album is the answer, index picture included.
			return album;
		}

		ThumbnailInfo indexPicture = cover(result, album.getIndexPicture(), visibleNames);
		if (indexPicture != null) {
			result.setIndexPicture(indexPicture);
		}
		return result;
	}

	/**
	 * The index picture of a filtered album.
	 *
	 * @param filtered
	 *        The album as the caller sees it.
	 * @param original
	 *        The index picture the album carries, <code>null</code> if it has none.
	 * @param visibleNames
	 *        The names of the images the caller may see.
	 * @return The original index picture while it names a visible image, one built for the first
	 *         visible image otherwise, <code>null</code> if there is no visible image at all.
	 */
	private static ThumbnailInfo cover(AlbumInfo filtered, ThumbnailInfo original, Set<String> visibleNames) {
		if (original != null && visibleNames.contains(original.getImage())) {
			return original;
		}
		ImagePart first = firstImage(filtered);
		return first == null ? null : thumbnail(first);
	}

	/**
	 * The given part as the caller may see it.
	 *
	 * @return The part itself while it is untouched, a filtered copy of a group that lost members,
	 *         <code>null</code> if the caller must not see it at all.
	 */
	private static AlbumPart filterPart(AlbumPart part, int clearance) {
		if (part instanceof ImagePart) {
			return visible((ImagePart) part, clearance) ? part : null;
		}
		if (part instanceof ImageGroup) {
			return filterGroup((ImageGroup) part, clearance);
		}
		return part;
	}

	/**
	 * The given group as the caller may see it.
	 *
	 * <p>
	 * A group whose representative is hidden is re-headed by its best visible member: the highest
	 * {@link ImagePart#getRating() rating} wins, the stored order breaks a tie. A group without a
	 * visible member disappears entirely — an empty group would be a hole in the album saying that
	 * something is there.
	 * </p>
	 */
	private static ImageGroup filterGroup(ImageGroup group, int clearance) {
		List<ImagePart> images = group.getImages();
		List<ImagePart> visible = new ArrayList<>(images.size());
		for (ImagePart image : images) {
			if (visible(image, clearance)) {
				visible.add(image);
			}
		}
		if (visible.isEmpty()) {
			return null;
		}
		if (visible.size() == images.size()) {
			return group;
		}

		int representative = group.getRepresentative();
		ImagePart head = representative >= 0 && representative < images.size() ? images.get(representative) : null;
		if (head == null || !visible.contains(head)) {
			head = best(visible);
		}
		return ImageGroup.create().setImages(visible).setRepresentative(visible.indexOf(head));
	}

	/** The best of the given images: the highest rating, the stored order breaking a tie. */
	private static ImagePart best(List<ImagePart> images) {
		ImagePart result = images.get(0);
		for (ImagePart image : images) {
			if (image.getRating() > result.getRating()) {
				result = image;
			}
		}
		return result;
	}

	/** Whether an image with this privacy level may be shown to a request with the given clearance. */
	private static boolean visible(ImagePart image, int clearance) {
		return Privacy.visible(image.getPrivacy(), clearance);
	}

	/** Adds the names of the images the given part shows to the given set. */
	private static void collectNames(AlbumPart part, Set<String> names) {
		if (part instanceof ImagePart) {
			names.add(((ImagePart) part).getName());
		} else if (part instanceof ImageGroup) {
			for (ImagePart image : ((ImageGroup) part).getImages()) {
				names.add(image.getName());
			}
		}
	}

	/** The image shown first in the given album, <code>null</code> if it shows none. */
	static ImagePart firstImage(AlbumInfo album) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				return (ImagePart) part;
			}
			if (part instanceof ImageGroup) {
				ImageGroup group = (ImageGroup) part;
				List<ImagePart> images = group.getImages();
				if (images.isEmpty()) {
					continue;
				}
				int representative = group.getRepresentative();
				return images.get(representative >= 0 && representative < images.size() ? representative : 0);
			}
		}
		return null;
	}

	/**
	 * A {@link ThumbnailInfo} showing the given image, computed exactly as the one a listing builds
	 * for a folder without a sidecar (see <code>ResourceCache.Loader#loadFolderInfo</code>): the
	 * image is scaled to fill the square tile and a portrait image is shifted to its top.
	 */
	static ThumbnailInfo thumbnail(ImagePart image) {
		double width = image.getWidth();
		double height = image.getHeight();
		double scale;
		double ty;
		if (width <= 0 || height <= 0) {
			// Dimensions the analysis could not determine; the tile still shows the image.
			scale = 1.0;
			ty = 0.0;
		} else {
			scale = width / height;
			if (scale < 1.0) {
				scale = 1.0 / scale;
				ty = (height - width) / height * 150;
			} else {
				ty = 0.0;
			}
		}
		return ThumbnailInfo.create().setImage(image.getName()).setScale(scale).setTy(ty);
	}

	/**
	 * The given listing with a cover no caller may see replaced.
	 *
	 * <p>
	 * A folder stays in the listing whatever its images are: its title is not a secret, and a
	 * folder vanishing would say more than a folder without a cover does.
	 * </p>
	 */
	private ListingInfo filterListing(ListingInfo listing, PathInfo path, int clearance) {
		List<FolderInfo> folders = listing.getFolders();
		List<FolderInfo> filtered = null;
		for (int n = 0, size = folders.size(); n < size; n++) {
			FolderInfo folder = folders.get(n);
			FolderInfo visible = filterFolder(folder, path, clearance);
			if (visible != folder && filtered == null) {
				filtered = new ArrayList<>(folders.subList(0, n));
			}
			if (filtered != null) {
				filtered.add(visible);
			}
		}
		if (filtered == null) {
			return listing;
		}
		return ListingInfo.create()
			.setTitle(listing.getTitle())
			// The folder's placement rule is not a secret, and a copy that lost it would tell the
			// app that this folder files nothing, see issue #48.
			.setPlacement(listing.getPlacement())
			.setFolders(filtered);
	}

	/**
	 * The given folder of a listing with a cover the caller must not see replaced.
	 *
	 * <p>
	 * Whether the cover is hidden is decided from the folder's own sidecar alone — a small JSON
	 * file, no image is opened. Only when it turns out to be hidden is the folder's album loaded to
	 * find the image that takes its place, so a listing of untouched albums costs what it always
	 * did.
	 * </p>
	 */
	private FolderInfo filterFolder(FolderInfo folder, PathInfo path, int clearance) {
		ThumbnailInfo indexPicture = folder.getIndexPicture();
		if (indexPicture == null) {
			return folder;
		}
		PathInfo childPath = path.child(folder.getName());
		File child = childPath.toFile();
		if (!child.isDirectory()) {
			return folder;
		}

		FolderResource sidecar = ResourceCache.sidecar(child);
		if (!(sidecar instanceof AlbumInfo)) {
			// No sidecar, no privacy: a folder the server described by itself holds public images.
			return folder;
		}
		if (Privacy.visible(privacyOf((AlbumInfo) sidecar, indexPicture.getImage()), clearance)) {
			return folder;
		}

		FolderInfo result = FolderInfo.create()
			.setName(folder.getName())
			.setTitle(folder.getTitle())
			.setSubTitle(folder.getSubTitle())
			// The listing keeps its order whoever is looking at it.
			.setEffectiveDate(folder.getEffectiveDate());
		Resource album = _cache.lookup(childPath);
		if (album instanceof AlbumInfo) {
			ThumbnailInfo cover = filterAlbum((AlbumInfo) album, clearance).getIndexPicture();
			if (cover != null) {
				result.setIndexPicture(cover);
			}
		}
		return result;
	}

	/**
	 * The privacy level the given album records for the image of the given name.
	 *
	 * <p>
	 * An image the sidecar does not mention is {@link Privacy#PUBLIC}: it was never edited, so
	 * nobody ever restricted it.
	 * </p>
	 */
	private static int privacyOf(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				ImagePart image = (ImagePart) part;
				if (image.getName().equals(name)) {
					return image.getPrivacy();
				}
			} else if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					if (image.getName().equals(name)) {
						return image.getPrivacy();
					}
				}
			}
		}
		return Privacy.PUBLIC;
	}
}
