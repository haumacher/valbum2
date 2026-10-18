/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.faces.FaceTags;
import de.haumacher.imageServer.faces.PeopleStore;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.PhotoRef;
import de.haumacher.imageServer.shared.model.PlaceInfo;
import de.haumacher.imageServer.shared.model.PlaceKind;
import de.haumacher.imageServer.shared.model.PlaceTag;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.SearchAnd;
import de.haumacher.imageServer.shared.model.SearchCamera;
import de.haumacher.imageServer.shared.model.SearchContent;
import de.haumacher.imageServer.shared.model.SearchCriterion;
import de.haumacher.imageServer.shared.model.SearchDate;
import de.haumacher.imageServer.shared.model.SearchFolder;
import de.haumacher.imageServer.shared.model.SearchLabel;
import de.haumacher.imageServer.shared.model.SearchMedia;
import de.haumacher.imageServer.shared.model.SearchNot;
import de.haumacher.imageServer.shared.model.SearchOr;
import de.haumacher.imageServer.shared.model.SearchPerson;
import de.haumacher.imageServer.shared.model.SearchPlace;
import de.haumacher.imageServer.shared.model.SearchQuery;
import de.haumacher.imageServer.shared.model.SearchRating;
import de.haumacher.imageServer.shared.model.SearchSimilar;
import de.haumacher.imageServer.shared.model.SearchText;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The search of issue #227: the photographs below a folder that match a {@link SearchQuery}.
 *
 * <p>
 * The search reads what the {@link ResourceCache} holds for every album below the folder &mdash;
 * the stored sidecar with what the server derives on every read (the places of issue #234, the
 * contributors) &mdash; and asks each photograph the query. It never writes anything. What the
 * caller may see of a match is decided afterwards, by the photograph's own album, see
 * <code>ImageServlet#searchAnswer</code>.
 * </p>
 *
 * <p>
 * A photograph is named by its path below the folder searched, <code>/</code>-separated
 * (<code>2024/Rome/IMG_1.jpg</code>): unique, stable while the photograph stays where it is, and an
 * address of the photograph below that folder &mdash; and below a saved search in it, which serves
 * the photograph from there.
 * </p>
 */
public final class PhotoSearch {

	/**
	 * The newest version of the {@link SearchQuery} format this build reads and writes: version
	 * <code>2</code> is a query whose ranking criterion states its own cut (issue #302), see
	 * {@link #versionOf(SearchQuery)}.
	 */
	public static final int VERSION = 2;

	/** The version of a query that states nothing a build of the first version cannot answer. */
	public static final int FIRST_VERSION = 1;

	/** The message a query of a newer build, or with a criterion this build does not know, is answered with. */
	public static final String NEWER =
		"This search was saved by a newer version of VAlbum and cannot be shown by this one. Update the server to see its photos.";

	/** The message a search by somebody who is no member of the space is refused with. */
	public static final String MEMBERS_ONLY = "Searching is for the members of this space.";

	/** The message an unreadable search request is refused with. */
	public static final String UNREADABLE = "The search cannot be read.";

	/** The message a search where there is no folder is refused with. */
	public static final String NOT_A_FOLDER = "A search looks below a folder.";

	/** The message an upload into a saved search is refused with. */
	public static final String UPLOAD_REFUSED =
		"A saved search holds no files of its own: upload into an album, and the search shows what matches.";

	/** The message a move into or out of a saved search is refused with. */
	public static final String MOVE_REFUSED =
		"A saved search shows the photos that match it: move a photo in the album it lies in.";

	/** The message a folder created inside a saved search is refused with. */
	public static final String FOLDER_REFUSED = "A saved search holds no albums or folders.";

	/** The message a delete in a saved search is refused with. */
	public static final String DELETE_REFUSED =
		"A saved search holds no photos of its own: change the search, or delete a photo in the album it lies in.";

	/** The message the face editing of a saved search is refused with. */
	public static final String FACES_REFUSED = "Faces are named in the photo's own album, not in a saved search.";

	/** The message a relabelling of a saved search is refused with. */
	public static final String RELABEL_REFUSED = "Labels are renamed in the album the photos lie in, not in a saved search.";

	/** The message a photograph below a saved search that does not match it is answered with. */
	public static final String NO_MATCH = "This photo does not match the search.";

	private PhotoSearch() {
		// Static helpers only.
	}

	/** Whether the given folder resource is a saved search. */
	public static boolean isSearch(Resource resource) {
		return resource instanceof AlbumInfo && ((AlbumInfo) resource).getKind() == AlbumKind.SEARCH;
	}

	/** Whether the given folder is a saved search, read from its sidecar alone. */
	public static boolean isSearch(File folder) {
		return folder != null && folder.isDirectory() && isSearch(ResourceCache.sidecar(folder));
	}

	/**
	 * Whether the given folder resource holds no files of its own: a saved search. A collection is
	 * an album since issue #307, whose files are its own and whose references are not, see
	 * {@link PhotoCollections#ownImages(AlbumInfo)}.
	 */
	public static boolean holdsNoFiles(Resource resource) {
		return isSearch(resource);
	}

	/**
	 * The folder a saved search at the given path looks below: the folder it lies in, see issue
	 * #227.
	 */
	public static PathInfo scopeOf(PathInfo search) {
		return search.isRoot() ? search : search.parent();
	}

	/**
	 * Why the given query cannot be evaluated by this build, <code>null</code> where it can.
	 *
	 * <p>
	 * A query of a {@link SearchQuery#getVersion() version} beyond {@link #VERSION}, and one holding
	 * a criterion this build does not know (read as <code>null</code>), is refused rather than
	 * answered narrower or wider than its author meant. The {@link SearchQuery#getGroupBy() grouping}
	 * (issue #244) is no criterion: it is presentation, derived by the app, and never refused.
	 * </p>
	 *
	 * <p>
	 * A {@link SearchContent} or {@link SearchSimilar} criterion (issue #257) is refused where it
	 * cannot rank: below an OR or a NOT, deeper than the root's AND, more than once (a text and a
	 * photograph alike), or without a text.
	 * </p>
	 */
	public static String refusal(SearchQuery query) {
		if (query == null) {
			return null;
		}
		if (query.getVersion() > VERSION || query.getVersion() < 0) {
			return NEWER;
		}
		// A criterion of a newer build is read as nothing: an absent one is never "everything".
		if (!known(query.getRoot(), false)) {
			return NEWER;
		}
		return contentRefusal(query.getRoot());
	}

	/**
	 * Why the ranking criteria ({@link SearchContent}, {@link SearchSimilar}) of the given root
	 * cannot be answered, <code>null</code> where they can: one at most, as the root or as a member
	 * of the root's {@link SearchAnd}, a text with a text. A ranking criterion ranks; below an OR or
	 * a NOT there is nothing it could rank, and a query ranks by one thing alone.
	 */
	private static String contentRefusal(SearchCriterion root) {
		List<SearchCriterion> placed = new ArrayList<>();
		if (ranks(root)) {
			placed.add(root);
		} else if (root instanceof SearchAnd) {
			for (SearchCriterion member : ((SearchAnd) root).getCriteria()) {
				if (ranks(member)) {
					placed.add(member);
				} else if (holdsContent(member)) {
					return de.haumacher.imageServer.content.ContentSearch.COMBINED;
				}
			}
		} else if (holdsContent(root)) {
			return de.haumacher.imageServer.content.ContentSearch.COMBINED;
		}
		if (placed.size() > 1) {
			boolean text = placed.stream().anyMatch(SearchContent.class::isInstance);
			boolean photo = placed.stream().anyMatch(SearchSimilar.class::isInstance);
			return text && photo ? de.haumacher.imageServer.content.ContentSearch.TEXT_AND_PHOTO
				: de.haumacher.imageServer.content.ContentSearch.COMBINED;
		}
		if (placed.size() == 1 && placed.get(0) instanceof SearchContent
			&& text((SearchContent) placed.get(0)).isEmpty()) {
			return de.haumacher.imageServer.content.ContentSearch.EMPTY;
		}
		return null;
	}

	/** Whether the given criterion ranks, see issue #257: a {@link SearchContent} or a {@link SearchSimilar}. */
	private static boolean ranks(SearchCriterion criterion) {
		return criterion instanceof SearchContent || criterion instanceof SearchSimilar;
	}

	/** Whether the given criterion is or holds one that {@link #ranks(SearchCriterion) ranks}. */
	private static boolean holdsContent(SearchCriterion criterion) {
		if (ranks(criterion)) {
			return true;
		}
		if (criterion instanceof SearchAnd) {
			return ((SearchAnd) criterion).getCriteria().stream().anyMatch(PhotoSearch::holdsContent);
		}
		if (criterion instanceof SearchOr) {
			return ((SearchOr) criterion).getCriteria().stream().anyMatch(PhotoSearch::holdsContent);
		}
		if (criterion instanceof SearchNot) {
			SearchCriterion negated = ((SearchNot) criterion).getCriterion();
			return negated != null && holdsContent(negated);
		}
		return false;
	}

	private static String text(SearchContent content) {
		return content.getText() == null ? "" : content.getText().strip();
	}

	/**
	 * A query split into what its ranking criterion ranks by and what narrows it, see
	 * {@link PhotoSearch#content(SearchQuery)}: exactly one of <code>text</code> and
	 * <code>similar</code> is given.
	 *
	 * @param text
	 *        The text of a {@link SearchContent}, its blanks stripped; <code>null</code> for a
	 *        {@link SearchSimilar}.
	 * @param similar
	 *        The photograph of a {@link SearchSimilar}, never <code>null</code> but possibly empty;
	 *        <code>null</code> for a {@link SearchContent}.
	 * @param filter
	 *        The query without it: every other member of the root's AND, or every photograph.
	 * @param minSimilarity
	 *        The cut the criterion states (issue #302), <code>0</code> for the space's.
	 */
	public record ContentQuery(String text, PhotoRef similar, SearchQuery filter, double minSimilarity) {
		// The parts of a ranking query.
	}

	/**
	 * The given query split into what it ranks by (a text or a photograph) and the criteria that
	 * narrow it, see issue #257; <code>null</code> for a query without a ranking criterion. Only for
	 * a query that passed {@link #refusal(SearchQuery)}. The given query is never changed.
	 */
	public static ContentQuery content(SearchQuery query) {
		if (query == null) {
			return null;
		}
		SearchCriterion root = query.getRoot();
		if (ranks(root)) {
			return contentQuery(root, filter(query, new ArrayList<>()));
		}
		if (!(root instanceof SearchAnd)) {
			return null;
		}
		SearchCriterion ranking = null;
		List<SearchCriterion> rest = new ArrayList<>();
		for (SearchCriterion member : ((SearchAnd) root).getCriteria()) {
			if (ranks(member) && ranking == null) {
				ranking = member;
			} else {
				rest.add(member);
			}
		}
		return ranking == null ? null : contentQuery(ranking, filter(query, rest));
	}

	private static ContentQuery contentQuery(SearchCriterion ranking, SearchQuery filter) {
		if (ranking instanceof SearchContent) {
			SearchContent content = (SearchContent) ranking;
			return new ContentQuery(text(content), null, filter, content.getMinSimilarity());
		}
		SearchSimilar similar = (SearchSimilar) ranking;
		PhotoRef ref = similar.getRef();
		return new ContentQuery(null, ref == null ? PhotoRef.create() : ref, filter, similar.getMinSimilarity());
	}

	/**
	 * A copy of the given query whose {@link SearchSimilar} refers to the given photograph, see
	 * issue #257; the given query itself where it holds none. The given query is never changed: what
	 * the cache holds is shared by every request.
	 *
	 * <p>
	 * What the answer of a saved search says of its photograph: its current path where the caller
	 * may see it, an empty reference where they may not, see
	 * {@link de.haumacher.imageServer.shared.model.SearchProblem#PHOTO_NOT_FOUND}.
	 * </p>
	 */
	public static SearchQuery withSimilar(SearchQuery query, PhotoRef ref) {
		if (query == null) {
			return null;
		}
		SearchCriterion root = query.getRoot();
		SearchCriterion replaced;
		if (root instanceof SearchSimilar) {
			replaced = SearchSimilar.create().setRef(ref).setMinSimilarity(((SearchSimilar) root).getMinSimilarity());
		} else if (root instanceof SearchAnd
			&& ((SearchAnd) root).getCriteria().stream().anyMatch(SearchSimilar.class::isInstance)) {
			List<SearchCriterion> members = new ArrayList<>();
			for (SearchCriterion member : ((SearchAnd) root).getCriteria()) {
				members.add(member instanceof SearchSimilar
					? SearchSimilar.create().setRef(ref).setMinSimilarity(((SearchSimilar) member).getMinSimilarity())
					: member);
			}
			replaced = SearchAnd.create().setCriteria(members);
		} else {
			return query;
		}
		return SearchQuery.create()
			.setVersion(query.getVersion())
			.setRoot(replaced)
			.setGroupBy(new ArrayList<>(query.getGroupBy()));
	}

	private static SearchQuery filter(SearchQuery query, List<SearchCriterion> rest) {
		return SearchQuery.create()
			.setVersion(query.getVersion())
			.setRoot(SearchAnd.create().setCriteria(rest))
			.setGroupBy(new ArrayList<>(query.getGroupBy()));
	}

	private static boolean known(SearchCriterion criterion, boolean mayBeAbsent) {
		if (criterion == null) {
			return mayBeAbsent;
		}
		if (!KNOWN.contains(criterion.getClass())) {
			return false;
		}
		if (criterion instanceof SearchAnd) {
			return allKnown(((SearchAnd) criterion).getCriteria());
		}
		if (criterion instanceof SearchOr) {
			return allKnown(((SearchOr) criterion).getCriteria());
		}
		if (criterion instanceof SearchNot) {
			return known(((SearchNot) criterion).getCriterion(), false);
		}
		if (criterion instanceof SearchRating) {
			return Ratings.isKnown(((SearchRating) criterion).getMin());
		}
		return true;
	}

	/** The criteria this build evaluates; a later build may read more. */
	private static final Set<Class<?>> KNOWN = new HashSet<>(Arrays.asList(SearchAnd.class, SearchOr.class,
		SearchNot.class, SearchPerson.class, SearchDate.class, SearchPlace.class, SearchLabel.class,
		SearchRating.class, SearchMedia.class, SearchText.class, SearchCamera.class, SearchFolder.class,
		SearchContent.class, SearchSimilar.class));

	private static boolean allKnown(List<SearchCriterion> criteria) {
		for (SearchCriterion criterion : criteria) {
			if (!known(criterion, false)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The given query as it is stored and answered: the version and the root stated. Only for a
	 * query that passed {@link #refusal(SearchQuery)} or none at all.
	 */
	public static SearchQuery normalized(SearchQuery query) {
		SearchQuery result = query == null ? SearchQuery.create() : query;
		if (result.getRoot() == null) {
			// Every photograph, stated as such: an absent root is what a newer criterion reads as.
			result.setRoot(SearchAnd.create());
		}
		// At least the version of what it states, so that an older build refuses what it would
		// answer otherwise (issue #302).
		result.setVersion(Math.max(result.getVersion(), versionOf(result)));
		return result;
	}

	/**
	 * The lowest version of the format that states what the given query states: {@link #VERSION}
	 * for a query whose ranking criterion states its own cut (issue #302), {@link #FIRST_VERSION}
	 * for every other one.
	 */
	public static int versionOf(SearchQuery query) {
		ContentQuery content = refusal(query) == null ? content(query) : null;
		return content != null && content.minSimilarity() != 0 ? VERSION : FIRST_VERSION;
	}

	/** A photograph the search found. */
	public static final class Match {
		final long _id;

		final PathInfo _folder;

		/** The path of {@link #_folder} below the folder searched, empty or ending in <code>/</code>. */
		final String _below;

		final String _name;

		final long _date;

		/**
		 * {@link #getRelative()} once asked: a search of a hundred thousand photographs answers
		 * them by name, and the sort reads the path only where two dates are equal.
		 */
		private String _relative;

		Match(long id, PathInfo folder, String below, String name, long date) {
			_id = id;
			_folder = folder;
			_below = below;
			_name = name;
			_date = date;
		}

		/** The row of the photograph in the {@link SearchIndex}, see {@link SearchIndex#facets}. */
		public long getId() {
			return _id;
		}

		/** The album the photograph lies in. */
		public PathInfo getFolder() {
			return _folder;
		}

		/** The photograph's path below the folder searched, <code>/</code>-separated. */
		public String getRelative() {
			String result = _relative;
			if (result == null) {
				// Racy but safe: a String is immutable, and every thread computes the same one.
				result = _below.isEmpty() ? _name : _below + _name;
				_relative = result;
			}
			return result;
		}

		/** The photograph's file name in its album. */
		public String getName() {
			return _name;
		}

		/** When it was taken, <code>0</code> for unknown. */
		public long getDate() {
			return _date;
		}
	}

	/** Every id that resolves to the person the given id names, see {@link FaceTags#person}. */
	static Set<String> idsOf(String id, PeopleStore people) {
		Set<String> result = new HashSet<>();
		if (id == null || id.isEmpty()) {
			return result;
		}
		result.add(id);
		PeopleStore.Entry entry = people == null ? null : people.resolve(id);
		if (entry != null) {
			result.add(entry.getId());
			result.addAll(entry.getAliases());
		}
		return result;
	}

	static String trimSlashes(String path) {
		return path == null ? "" : path.replaceAll("^/+|/+$", "");
	}

	/**
	 * Every photograph below the given folder that matches the given query, by the date it was
	 * taken (one without a date last), then by its path.
	 *
	 * <p>
	 * A photograph in the {@link Ratings#TRASH trash} never matches, whoever asks: a search shows
	 * photographs, and the trash is the editors' business in the album the photograph lies in. A
	 * collection and a saved search hold no photographs of their own and are passed over, as is
	 * everything the library does not show (dot folders, the litter of other systems).
	 * </p>
	 *
	 * <p>
	 * Asked of the {@link SearchIndex} of the space (issue #264), which answers what it holds: the
	 * albums are not asked.
	 * </p>
	 *
	 * @param scope
	 *        The folder to search below, in the coordinates of its space.
	 * @param query
	 *        What to look for; must have passed {@link #refusal(SearchQuery)}; <code>null</code> for
	 *        everything.
	 * @param visibility
	 *        What the caller may be shown at all, see {@link SearchIndex.Visibility}.
	 */
	public static List<Match> find(PathInfo scope, SearchQuery query, SearchIndex index, PeopleStore people,
			SearchIndex.Visibility visibility) {
		return index.find(scope, query, people, visibility);
	}

	/** By date, a photograph without one last, then by path. */
	public static final Comparator<Match> ORDER = (a, b) -> {
		long da = a._date;
		long db = b._date;
		if (da != db) {
			if (da == 0) {
				return 1;
			}
			if (db == 0) {
				return -1;
			}
			return Long.compare(da, db);
		}
		return a.getRelative().compareTo(b.getRelative());
	};

	/** Whether a directory entry of the given name is part of the library at all. */
	static boolean isLibraryEntry(String name) {
		return !name.startsWith(".") && !LibraryFiles.isIgnored(name);
	}

	/**
	 * The album of the given folder as the cache holds it, <code>null</code> for a folder of folders.
	 *
	 * @param holdsFiles
	 *        Whether the folder holds any file that is a library entry: a folder of folders without a
	 *        sidecar is never asked of the cache.
	 */
	static AlbumInfo albumAt(PathInfo folder, boolean holdsFiles, Function<PathInfo, Resource> albums) {
		if (!holdsFiles && !new File(folder.toFile(), "index.json").isFile()) {
			return null;
		}
		Resource resource = albums.apply(folder);
		return resource instanceof AlbumInfo ? (AlbumInfo) resource : null;
	}

	/** The photographs of the given album, the members of a group one by one. */
	public static List<ImagePart> images(AlbumInfo album) {
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

	/**
	 * Whether the photograph at the given path below the folder searched matches the given query.
	 *
	 * @param relative
	 *        The photograph's path below the folder searched, as a match is named.
	 * @return The album the photograph lies in where it matches, <code>null</code> otherwise.
	 */
	public static PathInfo matching(PathInfo scope, String relative, SearchQuery query,
			Function<PathInfo, Resource> albums, PeopleStore people, SearchIndex index) {
		List<String> segments = segments(relative);
		if (segments == null) {
			return null;
		}
		PathInfo folder = scope;
		for (int n = 0; n < segments.size() - 1; n++) {
			folder = folder.child(segments.get(n));
		}
		String name = segments.get(segments.size() - 1);
		if (!folder.toFile().isDirectory()) {
			return null;
		}
		Resource resource = albums.apply(folder);
		if (!(resource instanceof AlbumInfo) || holdsNoFiles(resource)) {
			return null;
		}
		AlbumInfo album = (AlbumInfo) resource;
		ImagePart image = Crops.findImage(album, name);
		if (image == null || PhotoCollections.isReference(image) || image.getRating() == Ratings.TRASH) {
			// A reference is found where its photograph lies, once, see issue #307.
			return null;
		}
		return index.matches(folder, name, query, people) ? folder : null;
	}

	/**
	 * The segments of a path below a folder, <code>null</code> where it is no such path: empty, with
	 * an empty segment, or with a segment no library entry may have (<code>..</code>, a dot name,
	 * litter).
	 */
	public static List<String> segments(String relative) {
		if (relative == null || relative.isEmpty() || relative.indexOf('\\') >= 0) {
			return null;
		}
		List<String> result = Arrays.asList(relative.split("/", -1));
		for (String segment : result) {
			if (segment.isEmpty() || !isLibraryEntry(segment)) {
				return null;
			}
		}
		return result;
	}

	/** The given labels, each once, sorted. */
	public static List<LabelName> labelsOf(Collection<String> labels) {
		java.util.TreeSet<String> names = new java.util.TreeSet<>(labels);
		List<LabelName> result = new ArrayList<>();
		for (String name : names) {
			result.add(LabelName.create().setName(name));
		}
		return result;
	}

	/**
	 * A copy of the given photograph for an answer of the search, which renames it and says where
	 * it lies: every stored and derived field taken over, the lists copied, their elements shared
	 * (an answer is only ever written out). The transient links of the album model are left out.
	 *
	 * <p>
	 * A copy through the model's reader and writer ({@link PhotoCollections#copy(ImagePart)}) costs
	 * a tenth of the answer of a search finding ten thousand photographs; this one nothing.
	 * <code>TestSavedSearch#testTheAnswerCopyHoldsEveryField</code> holds it to that copy.
	 * </p>
	 */
	public static ImagePart answerCopy(ImagePart image) {
		return ImagePart.create()
			.setKind(image.getKind())
			.setName(image.getName())
			.setDate(image.getDate())
			.setWidth(image.getWidth())
			.setHeight(image.getHeight())
			.setOrientation(image.getOrientation())
			.setRating(image.getRating())
			.setPrivacy(image.getPrivacy())
			.setComment(image.getComment())
			.setCamera(image.getCamera())
			.setLocation(image.getLocation())
			.setContributor(image.getContributor())
			.setContributorLabel(image.getContributorLabel())
			.setFaces(image.getFaces())
			.setTags(image.getTags())
			.setRaw(image.getRaw())
			.setCrop(image.getCrop())
			.setPoster(image.getPoster())
			.setLabels(image.getLabels())
			.setRef(image.getRef())
			.setMissing(image.isMissing())
			.setPlaces(image.getPlaces())
			.setPlaceCaption(image.getPlaceCaption())
			.setPartial(image.isPartial())
			.setScore(image.getScore());
	}

	/**
	 * The given answer of a search with every photograph in it made {@link #partialCopy(ImagePart)
	 * partial}; any other resource as it is.
	 *
	 * <p>
	 * Applied last, to what is written out: the place names are put into the caller's language by
	 * their GeoNames ids before, and the search's own options are read off the full copies.
	 * </p>
	 */
	public static Resource partial(Resource answer) {
		if (!isSearch(answer)) {
			return answer;
		}
		AlbumInfo album = (AlbumInfo) answer;
		List<AlbumPart> parts = new ArrayList<>(album.getParts().size());
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				parts.add(partialCopy((ImagePart) part));
			} else if (part instanceof ImageGroup) {
				ImageGroup group = (ImageGroup) part;
				List<ImagePart> images = new ArrayList<>(group.getImages().size());
				for (ImagePart image : group.getImages()) {
					images.add(partialCopy(image));
				}
				parts.add(ImageGroup.create().setRepresentative(group.getRepresentative()).setImages(images));
			} else {
				parts.add(part);
			}
		}
		album.setParts(parts);
		return album;
	}

	/**
	 * The slim copy of a photograph a search answers, see {@link ImagePart#isPartial()}: every field
	 * of {@link #answerCopy(ImagePart)} but the faces and the tags, and the places trimmed to the
	 * {@link #placeLine(PlaceInfo) place line}.
	 */
	public static ImagePart partialCopy(ImagePart image) {
		return answerCopy(image)
			.setFaces(java.util.Collections.emptyList())
			.setTags(java.util.Collections.emptyList())
			.setPlaces(placeLine(image.getPlaces()))
			.setPartial(true);
	}

	/**
	 * The given places trimmed to what the app's place line reads, <code>null</code> for none.
	 *
	 * <p>
	 * Tied to <code>placeLineText</code> in <code>valbum_ui/lib/image_properties.dart</code>, which
	 * must be changed together with this: the line reads, of each kind, the first tag with a name
	 * &mdash; the {@link PlaceKind#FEATURE feature}, the {@link PlaceKind#DISTRICT district}, the
	 * {@link PlaceKind#ADM_1 state} and the {@link PlaceKind#COUNTRY country}, and as the town the
	 * first of {@link PlaceKind#PLACE}, {@link PlaceKind#ADM_4}, {@link PlaceKind#ADM_3} and
	 * {@link PlaceKind#ADM_2} that there is. Exactly those tags are kept, in their order, each with
	 * its name and kind alone; the sentence why there are no tags yet is kept as it stands.
	 * </p>
	 */
	public static PlaceInfo placeLine(PlaceInfo places) {
		if (places == null) {
			return null;
		}
		EnumMap<PlaceKind, PlaceTag> first = new EnumMap<>(PlaceKind.class);
		for (PlaceTag tag : places.getTags()) {
			if (tag.getKind() != null && tag.getName() != null && !tag.getName().isEmpty()) {
				first.putIfAbsent(tag.getKind(), tag);
			}
		}
		Set<PlaceTag> read = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (PlaceKind kind : PLACE_LINE_KINDS) {
			PlaceTag tag = first.get(kind);
			if (tag != null) {
				read.add(tag);
			}
		}
		for (PlaceKind kind : PLACE_LINE_TOWN) {
			PlaceTag tag = first.get(kind);
			if (tag != null) {
				read.add(tag);
				break;
			}
		}
		List<PlaceTag> tags = new ArrayList<>(read.size());
		for (PlaceTag tag : places.getTags()) {
			if (read.contains(tag)) {
				tags.add(PlaceTag.create().setName(tag.getName()).setKind(tag.getKind()));
			}
		}
		return PlaceInfo.create().setTags(tags).setPending(places.getPending());
	}

	/**
	 * The kinds the app's place line reads one each of, see {@link #placeLine(PlaceInfo)}; tied to
	 * <code>placeLineText</code> in <code>image_properties.dart</code>.
	 */
	public static final List<PlaceKind> PLACE_LINE_KINDS =
		List.of(PlaceKind.FEATURE, PlaceKind.DISTRICT, PlaceKind.ADM_1, PlaceKind.COUNTRY);

	/**
	 * The kinds the app's place line takes the town from, the first there is, see
	 * {@link #placeLine(PlaceInfo)}; tied to <code>placeLineText</code> in
	 * <code>image_properties.dart</code>.
	 */
	public static final List<PlaceKind> PLACE_LINE_TOWN =
		List.of(PlaceKind.PLACE, PlaceKind.ADM_4, PlaceKind.ADM_3, PlaceKind.ADM_2);

	/**
	 * The given album with the given photographs alone, a group by those of its members: a shell
	 * sharing the cached parts, never changing them.
	 */
	public static AlbumInfo only(AlbumInfo album, Set<String> names) {
		List<AlbumPart> parts = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				if (names.contains(((ImagePart) part).getName())) {
					parts.add(part);
				}
			} else if (part instanceof ImageGroup) {
				ImageGroup group = (ImageGroup) part;
				for (ImagePart member : group.getImages()) {
					if (names.contains(member.getName())) {
						// Each member on its own: a search shows photographs, never groups.
						parts.add(member);
					}
				}
			}
		}
		return AlbumInfo.create()
			.setKind(album.getKind())
			.setTitle(album.getTitle())
			.setSubTitle(album.getSubTitle())
			.setStarred(album.isStarred())
			.setPlaceCaption(album.getPlaceCaption())
			.setDate(album.getDate())
			.setEffectiveDate(album.getEffectiveDate())
			.setParts(parts);
	}
}
