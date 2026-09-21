/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.AuthService.Caller;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderKind;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.imageServer.upload.HashCache;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an inbox is, and what tells it from an album, see issue #131.
 *
 * <p>
 * An inbox is a {@link AlbumKind#INBOX kind of album} and not a resource of its own: the same
 * folder, the same <code>index.json</code>, the same parts. Everything that makes it an inbox
 * happens on the way out, here:
 * </p>
 * <ul>
 * <li>its parts are answered {@link #flatten(AlbumInfo, String) flat and by date}, whatever order
 * and grouping the sidecar lists — derived on every read, exactly like
 * {@link AlbumInfo#getEffectiveDate()}, so the author's album is still there when the flag is
 * cleared again;</li>
 * <li>it is {@link #visibility(AuthService, Caller, PathInfo, int) shown} to a caller that may
 * edit it, in part to a caller that may contribute to it, and to nobody else — not even as an
 * entry of the listing above it;</li>
 * <li>it has no date and it is filed nowhere, see {@link AlbumDate#ofFolder(FolderResource,
 * String)} and {@link FolderNames#of(FolderResource, String)}.</li>
 * </ul>
 *
 * <p>
 * Nothing here ever writes: the {@link ResourceCache} holds the album as it lies on disk — that is
 * the album a move rewrites the sidecar from — and a copy is what leaves the server, exactly as in
 * the {@link PrivacyFilter}. The one write that concerns an inbox is the other direction, see
 * {@link #restoreArrangement(File, FolderResource)}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class Inboxes {

	/**
	 * What a request for an inbox it may not see is answered with.
	 *
	 * <p>
	 * A <code>404</code> and not a <code>403</code>: an inbox the caller may not see does not
	 * exist for them, and a refusal that named it would say that there is something to be refused.
	 * </p>
	 */
	public static final String NOT_FOUND = "There is nothing at this address.";

	/** What an attempt to hand out a share link on an inbox is refused with. */
	public static final String INBOX_NOT_SHARED =
		"An inbox cannot be shared: it holds photographs nobody has sorted yet.";

	private Inboxes() {
		// Static utility.
	}

	/** How much of an inbox a request is answered. */
	public enum Visibility {
		/** All of it: the caller may edit this folder. */
		FULL,

		/** The caller's own contributions and nothing else: they may add here, not change. */
		OWN,

		/** Nothing at all: the inbox does not exist for this caller. */
		NONE;
	}

	/** Whether the given folder resource is an inbox. */
	public static boolean isInbox(Resource resource) {
		return resource instanceof AlbumInfo && ((AlbumInfo) resource).getKind() == AlbumKind.INBOX;
	}

	/**
	 * Whether the folder on disk describes itself as an inbox.
	 *
	 * <p>
	 * The cheap look, the one a listing uses: the sidecar and nothing else, no image is opened.
	 * </p>
	 */
	public static boolean isInbox(File folder) {
		return isInbox(ResourceCache.sidecar(folder));
	}

	/**
	 * How much of an inbox at the given path the given caller is answered.
	 *
	 * <p>
	 * The safety rule of issue #131, and the only place it is decided:
	 * </p>
	 * <ul>
	 * <li>whoever may {@link de.haumacher.imageServer.auth.Rights#EDIT edit} the folder sees all
	 * of it — an inbox is a working place, and sorting it is editing it;</li>
	 * <li>a signed-in member who may only {@link de.haumacher.imageServer.auth.Rights#CONTRIBUTE
	 * contribute} sees what they contributed themselves (issue #53), so that the owner of a device
	 * can sort what that device uploaded into a family inbox without looking at everybody
	 * else's;</li>
	 * <li>a share link, an anonymous caller and a member who may only look see nothing: the folder
	 * is answered <code>404</code> and the entry is dropped from the listing above it.</li>
	 * </ul>
	 *
	 * <p>
	 * A <code>viewAs</code> preview lowers this to {@link Visibility#NONE}: the owner asking what
	 * somebody else sees is answered what somebody else sees, and nobody else ever sees an inbox.
	 * </p>
	 */
	public static Visibility visibility(AuthService auth, Caller caller, PathInfo path, int viewAs) {
		if (viewAs < Privacy.PRIVATE) {
			// "Show me what they see": what they see of an inbox is that there is none.
			return Visibility.NONE;
		}
		if (auth.mayEdit(caller, path)) {
			return Visibility.FULL;
		}
		if (caller.isShareLink()) {
			// A link is never a person; it has no contributions of its own to be shown.
			return Visibility.NONE;
		}
		if (caller.isPaired() && auth.mayContribute(caller, path)) {
			return Visibility.OWN;
		}
		return Visibility.NONE;
	}

	/**
	 * The given resource as a request of the given {@link Visibility} may see the inboxes in it.
	 *
	 * <p>
	 * An inbox itself is flattened (and cut to the caller's own contributions), and an inbox among
	 * the entries of a listing is dropped where the caller may not see it. Everything else is
	 * answered unchanged — the very object the cache holds, as before.
	 * </p>
	 *
	 * @param resource
	 *        What the cache holds; never modified.
	 * @param path
	 *        Where that resource lies, needed to reach the folders of a listing.
	 * @param visibility
	 *        What this caller may see of an inbox, see
	 *        {@link #visibility(AuthService, Caller, PathInfo, int)}.
	 * @param subject
	 *        Who the caller is, see {@link Caller#subject()}; only read for
	 *        {@link Visibility#OWN}.
	 * @return The resource to answer, <code>null</code> if it is an inbox this caller may not see.
	 */
	public static Resource filter(Resource resource, PathInfo path, Visibility visibility, String subject) {
		if (resource instanceof AlbumInfo) {
			AlbumInfo album = (AlbumInfo) resource;
			if (!isInbox(album)) {
				return album;
			}
			if (visibility == Visibility.NONE) {
				return null;
			}
			return flatten(album, visibility == Visibility.OWN ? subject : null);
		}
		if (resource instanceof ListingInfo) {
			return filterListing((ListingInfo) resource, path, visibility, subject);
		}
		return resource;
	}

	/**
	 * The given listing without the inboxes the caller may not see.
	 *
	 * <p>
	 * A folder that vanishes is otherwise exactly what this server does not do (see
	 * the {@link PrivacyFilter}, where a folder whose cover is hidden keeps its tile).
	 * An inbox is the one exception, and it is the whole point: a photograph nobody has looked at
	 * yet must not be one click away for a visitor, and a tile saying "Inbox (12)" would be that
	 * click.
	 * </p>
	 */
	private static ListingInfo filterListing(ListingInfo listing, PathInfo path, Visibility visibility,
			String subject) {
		List<FolderInfo> folders = listing.getFolders();
		List<FolderInfo> filtered = null;
		for (int n = 0, size = folders.size(); n < size; n++) {
			FolderInfo folder = folders.get(n);
			FolderInfo visible = folder.getKind() == FolderKind.INBOX
				? entry(folder, path.child(folder.getName()), visibility, subject) : folder;
			if (visible != folder && filtered == null) {
				filtered = new ArrayList<>(folders.subList(0, n));
			}
			if (filtered != null && visible != null) {
				filtered.add(visible);
			}
		}
		if (filtered == null) {
			return listing;
		}
		return ListingInfo.create()
			.setTitle(listing.getTitle())
			.setPlacement(listing.getPlacement())
			.setFolders(filtered);
	}

	/**
	 * The tile of an inbox as the caller may see it, <code>null</code> where they may not see it
	 * at all.
	 *
	 * <p>
	 * A contributor keeps the tile and loses the cover unless it shows a photograph of their own:
	 * the tile says "there is an inbox and you may put something in it", which is true for them,
	 * while the cover would show somebody else's picture.
	 * </p>
	 */
	private static FolderInfo entry(FolderInfo folder, PathInfo childPath, Visibility visibility, String subject) {
		if (visibility == Visibility.NONE) {
			return null;
		}
		if (visibility == Visibility.FULL) {
			return folder;
		}
		ThumbnailInfo cover = folder.getIndexPicture();
		if (cover == null || contributedBy(childPath.toFile(), cover.getImage(), subject)) {
			return folder;
		}
		return FolderInfo.create()
			.setName(folder.getName())
			.setKind(folder.getKind())
			.setTitle(folder.getTitle())
			.setSubTitle(folder.getSubTitle())
			.setLink(folder.getLink())
			.setEffectiveDate(folder.getEffectiveDate());
	}

	/**
	 * The given inbox as it is answered: one {@link ImagePart} per photograph, in the order of
	 * {@link ImagePart#getDate()}.
	 *
	 * <p>
	 * The order is derived and the stored one is untouched: the sidecar goes on saying what its
	 * author said, groups and headings included, and an inbox turned back into an album is the
	 * album it was. What is answered is a copy sharing the parts of the cached album, exactly as
	 * the copy of the {@link PrivacyFilter} does — it is only ever serialised into the response.
	 * </p>
	 *
	 * <p>
	 * A {@link Heading} is dropped rather than kept (which is what the privacy filter does with
	 * one): a section title means an order, and this answer has another one. A group is dissolved
	 * into its members, each of which stands on its own date.
	 * </p>
	 *
	 * @param subject
	 *        Whose photographs to answer, <code>null</code> for all of them, see
	 *        {@link Caller#subject()} and issue #53.
	 */
	public static AlbumInfo flatten(AlbumInfo album, String subject) {
		List<ImagePart> images = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				add(images, (ImagePart) part, subject);
			} else if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					add(images, image, subject);
				}
			}
		}
		Collections.sort(images, BY_DATE);

		AlbumInfo result = AlbumInfo.create()
			.setKind(album.getKind())
			.setTitle(album.getTitle())
			.setSubTitle(album.getSubTitle())
			.setDate(album.getDate())
			// An inbox has no date; it is derived as 0 and answered as it is derived.
			.setEffectiveDate(album.getEffectiveDate())
			.setParts(new ArrayList<AlbumPart>(images));
		ThumbnailInfo indexPicture = indexPicture(album, images);
		if (indexPicture != null) {
			result.setIndexPicture(indexPicture);
		}
		return result;
	}

	private static void add(List<ImagePart> images, ImagePart image, String subject) {
		if (subject == null || subject.equals(image.getContributor())) {
			images.add(image);
		}
	}

	/**
	 * The cover of an answered inbox: the stored one while it names a picture that is still shown,
	 * the first shown picture otherwise.
	 */
	private static ThumbnailInfo indexPicture(AlbumInfo album, List<ImagePart> shown) {
		ThumbnailInfo stored = album.getIndexPicture();
		if (stored != null) {
			for (ImagePart image : shown) {
				if (image.getName().equals(stored.getImage())) {
					return stored;
				}
			}
		}
		return shown.isEmpty() ? null : PrivacyFilter.thumbnail(shown.get(0));
	}

	/**
	 * The order an inbox is answered in: by the day the photograph was taken, the oldest first.
	 *
	 * <p>
	 * A photograph nothing says a time about stands at the end rather than at the beginning of
	 * 1970, where its epoch date would otherwise put it, and the file name breaks a tie so that
	 * the answer is the same on every read.
	 * </p>
	 */
	static final Comparator<ImagePart> BY_DATE = Comparator
		.comparingLong((ImagePart image) -> image.getDate() > 0L ? 0L : 1L)
		.thenComparingLong(ImagePart::getDate)
		.thenComparing(ImagePart::getName, String.CASE_INSENSITIVE_ORDER);

	/** Whether the named image of the given folder was contributed by the given subject. */
	private static boolean contributedBy(File folder, String name, String subject) {
		if (subject == null || subject.isEmpty() || name == null || name.isEmpty()) {
			return false;
		}
		HashCache.Attribution attribution = HashCache.recorded(folder).get(name);
		return attribution != null && subject.equals(attribution.getContributor());
	}

	/**
	 * Whether the image at the given path is one the given caller may see in an inbox of the given
	 * {@link Visibility}.
	 *
	 * <p>
	 * The same answer the album gives, for the one image: an image of somebody else's in an inbox
	 * the caller may only contribute to is not there for them, whether they ask for its
	 * description, its thumbnail, its rendition or the original.
	 * </p>
	 */
	public static boolean shows(Visibility visibility, ImagePart image, String subject) {
		switch (visibility) {
			case FULL:
				return true;
			case OWN:
				return subject != null && !subject.isEmpty() && subject.equals(image.getContributor());
			default:
				return false;
		}
	}

	/**
	 * Whether naming an image of the given album names the group it belongs to, see issue #131.
	 *
	 * <p>
	 * In an album it does: an album shows a group by its representative, so moving that one
	 * photograph moves what the mover sees, namely the group (issue #47). In an inbox there are no
	 * groups to see — it is answered {@link #flatten(AlbumInfo, String) flat}, every photograph
	 * standing on its own date — so naming one names one. A group the sidecar still remembers
	 * behind that flat answer must not carry a second photograph out of the inbox, into a target
	 * album or into the trash, that the caller never named and never saw as part of anything.
	 * </p>
	 *
	 * <p>
	 * What happens to the remembered group is what already happens to it when a non-representative
	 * member is taken out (see <code>MoveService.detach</code>, unchanged): the member leaves, and
	 * a group left with a single member is replaced by that member as a plain part, because a
	 * group of one is not a group. The photograph arrives at the target as one plain
	 * {@link ImagePart}, never as a group.
	 * </p>
	 *
	 * @param album
	 *        The album the entries are taken out of, <code>null</code> if there is none.
	 */
	public static boolean groupsTravel(AlbumInfo album) {
		return !isInbox(album);
	}

	/**
	 * Restores the stored arrangement of an inbox onto a received sidecar, see issue #131.
	 *
	 * <p>
	 * The one write an inbox needs. An inbox is answered flat and by date, so a client that reads
	 * it and writes it back — which is what editing the album properties does — would store that
	 * derived order as if it were the author's, dissolving the groups and the headings the album
	 * still has. Derived data is never stored (see {@link AlbumDate#clearDerived(FolderResource)}),
	 * and this is the same rule for the one piece of derived data that is not a field but an
	 * order.
	 * </p>
	 *
	 * <p>
	 * The rule is deliberately narrow, so that it can never silently drop an edit: it applies only
	 * where an inbox is involved — the folder on disk is one, or the received properties make it
	 * one — and only while the received parts name <em>exactly</em> the same images as the stored
	 * ones. Then the stored arrangement is rebuilt with the received images in the place of their
	 * namesakes, so everything the client did write (a rotation, a corrected time, a privacy
	 * level, a comment) is kept while the order and the grouping stay the author's. If the images
	 * differ in any way — one added, one removed — the received body is stored as it came, because
	 * then it is not a round trip but a statement.
	 * </p>
	 *
	 * <p>
	 * What this deliberately refuses to take from the client, for as long as an inbox is involved,
	 * is the <em>arrangement</em>: an order and a grouping it sends for the same images are
	 * ignored, because the order it read was the server's and not the album's. That is exactly
	 * what an inbox is (no reordering, no groups, see issue #131), and it is why the rule is tied
	 * to the flag rather than applied to every album.
	 * </p>
	 *
	 * @param folder
	 *        The folder whose sidecar is about to be overwritten.
	 * @param received
	 *        The properties the client sent; modified in place.
	 * @return Whether anything was changed and the body has to be re-serialised.
	 */
	public static boolean restoreArrangement(File folder, FolderResource received) {
		if (!(received instanceof AlbumInfo)) {
			return false;
		}
		AlbumInfo album = (AlbumInfo) received;
		FolderResource sidecar = ResourceCache.sidecar(folder);
		if (!(sidecar instanceof AlbumInfo)) {
			return false;
		}
		AlbumInfo stored = (AlbumInfo) sidecar;
		if (!isInbox(stored) && !isInbox(album)) {
			return false;
		}
		Map<String, ImagePart> receivedImages = byName(album);
		Map<String, ImagePart> storedImages = byName(stored);
		if (!receivedImages.keySet().equals(storedImages.keySet())) {
			// Not a round trip: the client says which images this album holds, and that is stored.
			return false;
		}

		List<AlbumPart> arranged = new ArrayList<>();
		boolean changed = false;
		for (AlbumPart part : stored.getParts()) {
			if (part instanceof ImagePart) {
				arranged.add(receivedImages.get(((ImagePart) part).getName()));
			} else if (part instanceof ImageGroup) {
				ImageGroup group = (ImageGroup) part;
				ImageGroup copy = ImageGroup.create().setRepresentative(group.getRepresentative());
				for (ImagePart image : group.getImages()) {
					copy.addImage(receivedImages.get(image.getName()));
				}
				arranged.add(copy);
				changed = true;
			} else {
				arranged.add(part);
				changed = true;
			}
		}
		if (!changed) {
			// Nothing but plain images, stored in some order: only the order can differ.
			List<AlbumPart> parts = album.getParts();
			if (parts.size() == arranged.size()) {
				changed = false;
				for (int n = 0, size = parts.size(); n < size; n++) {
					if (parts.get(n) != arranged.get(n)) {
						changed = true;
						break;
					}
				}
			} else {
				changed = true;
			}
		}
		if (!changed) {
			return false;
		}
		album.setParts(arranged);
		return true;
	}

	/** Every image the given album holds, by its name; a group's members count as its images. */
	private static Map<String, ImagePart> byName(AlbumInfo album) {
		Map<String, ImagePart> result = new LinkedHashMap<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				ImagePart image = (ImagePart) part;
				result.put(image.getName(), image);
			} else if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					result.put(image.getName(), image);
				}
			}
		}
		return result;
	}

}
