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
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.FaceTag;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.PlaceTag;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.SearchAnd;
import de.haumacher.imageServer.shared.model.SearchCamera;
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
import de.haumacher.imageServer.shared.model.SearchText;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

	/** The version of the {@link SearchQuery} format this build reads and writes. */
	public static final int VERSION = 1;

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

	/** Whether the given folder resource holds no files of its own: a collection or a saved search. */
	public static boolean holdsNoFiles(Resource resource) {
		return isSearch(resource) || PhotoCollections.isCollection(resource);
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
	 * answered narrower or wider than its author meant.
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
		return known(query.getRoot(), false) ? null : NEWER;
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
		SearchRating.class, SearchMedia.class, SearchText.class, SearchCamera.class, SearchFolder.class));

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
		if (result.getVersion() == 0) {
			result.setVersion(VERSION);
		}
		if (result.getRoot() == null) {
			// Every photograph, stated as such: an absent root is what a newer criterion reads as.
			result.setRoot(SearchAnd.create());
		}
		return result;
	}

	/**
	 * What the search asks of one photograph, see issue #227: everything a criterion reads, and
	 * nothing else, so that the {@link SearchIndex} can keep it for every photograph of a space.
	 *
	 * <p>
	 * Built by {@link #of(ImagePart, Interner)} from the photograph as the cache holds it &mdash; for
	 * the index and for the scan alike, so both answer exactly the same. Never changed afterwards.
	 * </p>
	 */
	public static final class Photo {
		static final String[] NONE = new String[0];

		static final int[] NO_PLACES = new int[0];

		final String _name;

		final long _date;

		/** The person ids of the confirmed faces, as the tags store them (merges not resolved). */
		final String[] _persons;

		final String[] _labels;

		/** The GeoNames ids of the place tags, every level. */
		final int[] _places;

		final String _camera;

		final boolean _video;

		final byte _rating;

		final byte _privacy;

		final String _comment;

		Photo(String name, long date, String[] persons, String[] labels, int[] places, String camera,
				boolean video, int rating, int privacy, String comment) {
			_name = name;
			_date = date;
			_persons = persons;
			_labels = labels;
			_places = places;
			_camera = camera;
			_video = video;
			_rating = (byte) rating;
			_privacy = (byte) privacy;
			_comment = comment;
		}

		/** The photograph's file name in its album. */
		public String getName() {
			return _name;
		}

		/** When it was taken, <code>0</code> for unknown. */
		public long getDate() {
			return _date;
		}

		/** Its camera, the empty string for none. */
		public String getCamera() {
			return _camera;
		}

		/** Its labels. */
		public List<String> getLabels() {
			return Arrays.asList(_labels);
		}

		/** The persons of its confirmed faces, each the survivor of every merge. */
		public Set<String> persons(PeopleStore people) {
			Set<String> result = new HashSet<>();
			for (String person : _persons) {
				result.add(FaceTags.person(person, people));
			}
			return result;
		}

		/** Whether it is in the trash, which no search ever finds. */
		boolean isTrash() {
			return _rating == Ratings.TRASH;
		}

		/** What the search asks of the given photograph. */
		static Photo of(ImagePart image, Interner strings) {
			List<String> persons = new ArrayList<>();
			for (FaceTag tag : image.getTags()) {
				if (tag.getState() == FaceState.CONFIRMED && !tag.getPerson().isEmpty()) {
					persons.add(strings.intern(tag.getPerson()));
				}
			}
			List<LabelName> labelNames = image.getLabels();
			String[] labels = labelNames.isEmpty() ? NONE : new String[labelNames.size()];
			for (int n = 0; n < labels.length; n++) {
				labels[n] = strings.intern(labelNames.get(n).getName());
			}
			int[] places = NO_PLACES;
			if (image.getPlaces() != null && !image.getPlaces().getTags().isEmpty()) {
				List<PlaceTag> tags = image.getPlaces().getTags();
				places = new int[tags.size()];
				for (int n = 0; n < places.length; n++) {
					places[n] = tags.get(n).getGeonameId();
				}
			}
			String comment = image.getComment() == null ? "" : image.getComment();
			return new Photo(image.getName(), image.getDate(),
				persons.isEmpty() ? NONE : persons.toArray(new String[persons.size()]), labels, places,
				strings.intern(image.getCamera() == null ? "" : image.getCamera()), image.getKind() != ImageKind.IMAGE,
				image.getRating(), image.getPrivacy(), comment.isEmpty() ? "" : comment);
		}
	}

	/** Shares the strings many photographs carry (person ids, labels, cameras). */
	public interface Interner {
		/** The one instance of the given string. */
		String intern(String value);

		/** No sharing: for a scan that throws its photographs away. */
		Interner NONE = value -> value;
	}

	/** What the search asks of an album: its texts and where it lies. */
	public static final class Album {
		final String _title;

		final String _subTitle;

		/** The folder relative to the root of the space, <code>/</code>-separated. */
		final String _path;

		Album(String title, String subTitle, String path) {
			_title = title == null ? "" : title;
			_subTitle = subTitle == null ? "" : subTitle;
			_path = path;
		}
	}

	/** A photograph the search found. */
	public static final class Match {
		final PathInfo _folder;

		final String _relative;

		final Photo _photo;

		Match(PathInfo folder, String relative, Photo photo) {
			_folder = folder;
			_relative = relative;
			_photo = photo;
		}

		/** The album the photograph lies in. */
		public PathInfo getFolder() {
			return _folder;
		}

		/** The photograph's path below the folder searched, <code>/</code>-separated. */
		public String getRelative() {
			return _relative;
		}

		/** The photograph's file name in its album. */
		public String getName() {
			return _photo._name;
		}

		/** What the search knows of the photograph. */
		public Photo getPhoto() {
			return _photo;
		}
	}

	/** What a criterion is asked about: one photograph in its album. */
	interface Test {
		boolean test(Photo photo, Album album);
	}

	/**
	 * The given query as a test of a photograph; the query must have passed {@link #refusal}.
	 *
	 * @param people
	 *        The register of the space, through whose merges persons are compared; <code>null</code>
	 *        compares the ids as they stand.
	 */
	static Test matcher(SearchQuery query, PeopleStore people) {
		SearchCriterion root = query == null ? null : query.getRoot();
		return root == null ? (photo, album) -> true : compile(root, people);
	}

	private static Test compile(SearchCriterion criterion, PeopleStore people) {
		if (criterion == null) {
			return (photo, album) -> false;
		}
		if (criterion instanceof SearchAnd) {
			Test[] all = compileAll(((SearchAnd) criterion).getCriteria(), people);
			return (photo, album) -> {
				for (Test test : all) {
					if (!test.test(photo, album)) {
						return false;
					}
				}
				return true;
			};
		}
		if (criterion instanceof SearchOr) {
			Test[] any = compileAll(((SearchOr) criterion).getCriteria(), people);
			return (photo, album) -> {
				for (Test test : any) {
					if (test.test(photo, album)) {
						return true;
					}
				}
				return false;
			};
		}
		if (criterion instanceof SearchNot) {
			Test inner = compile(((SearchNot) criterion).getCriterion(), people);
			return (photo, album) -> !inner.test(photo, album);
		}
		if (criterion instanceof SearchPerson) {
			// Every id that names the person: the survivor and everybody merged into them.
			Set<String> ids = idsOf(((SearchPerson) criterion).getPerson(), people);
			return (photo, album) -> {
				for (String person : photo._persons) {
					if (ids.contains(person)) {
						return true;
					}
				}
				return false;
			};
		}
		if (criterion instanceof SearchDate) {
			long from = ((SearchDate) criterion).getFrom();
			long to = ((SearchDate) criterion).getTo();
			return (photo, album) -> {
				long date = photo._date;
				return date != 0 && (from == 0 || date >= from) && (to == 0 || date < to);
			};
		}
		if (criterion instanceof SearchPlace) {
			int id = ((SearchPlace) criterion).getGeonameId();
			return (photo, album) -> {
				for (int place : photo._places) {
					if (place == id) {
						return true;
					}
				}
				return false;
			};
		}
		if (criterion instanceof SearchLabel) {
			String label = ((SearchLabel) criterion).getLabel();
			return (photo, album) -> {
				for (String carried : photo._labels) {
					if (carried.equals(label)) {
						return true;
					}
				}
				return false;
			};
		}
		if (criterion instanceof SearchRating) {
			int min = ((SearchRating) criterion).getMin();
			return (photo, album) -> photo._rating >= min;
		}
		if (criterion instanceof SearchMedia) {
			boolean video = ((SearchMedia) criterion).isVideo();
			return (photo, album) -> photo._video == video;
		}
		if (criterion instanceof SearchText) {
			String text = ((SearchText) criterion).getText().trim().toLowerCase(Locale.ROOT);
			return (photo, album) -> text.isEmpty() || contains(photo._comment, text)
				|| contains(album._title, text) || contains(album._subTitle, text);
		}
		if (criterion instanceof SearchCamera) {
			String camera = ((SearchCamera) criterion).getCamera();
			return (photo, album) -> !camera.isEmpty() && camera.equals(photo._camera);
		}
		if (criterion instanceof SearchFolder) {
			String path = trimSlashes(((SearchFolder) criterion).getPath());
			return (photo, album) -> path.isEmpty() || album._path.equals(path)
				|| album._path.startsWith(path + "/");
		}
		// Never reached for a query that passed refusal(SearchQuery).
		return (photo, album) -> false;
	}

	private static Test[] compileAll(List<SearchCriterion> criteria, PeopleStore people) {
		Test[] result = new Test[criteria.size()];
		for (int n = 0; n < result.length; n++) {
			result[n] = compile(criteria.get(n), people);
		}
		return result;
	}

	/** Every id that resolves to the person the given id names, see {@link FaceTags#person}. */
	private static Set<String> idsOf(String id, PeopleStore people) {
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

	private static boolean contains(String value, String lowerCaseText) {
		return value != null && !value.isEmpty() && value.toLowerCase(Locale.ROOT).contains(lowerCaseText);
	}

	private static String trimSlashes(String path) {
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
	 * Asked of the {@link SearchIndex} where it is complete, else of the albums themselves &mdash;
	 * the same answer, only slower.
	 * </p>
	 *
	 * @param scope
	 *        The folder to search below, in the coordinates of its space.
	 * @param query
	 *        What to look for; must have passed {@link #refusal(SearchQuery)}.
	 * @param albums
	 *        The folder as the cache holds it, see {@link ResourceCache#lookup(PathInfo)}.
	 * @param index
	 *        The index of the space, <code>null</code> for none.
	 */
	public static List<Match> find(PathInfo scope, SearchQuery query, Function<PathInfo, Resource> albums,
			PeopleStore people, SearchIndex index) {
		Test test = matcher(query, people);
		List<Match> result = index != null && index.isComplete() ? index.find(scope, test) : scan(scope, test, albums);
		result.sort(ORDER);
		return result;
	}

	/** {@link #find(PathInfo, SearchQuery, Function, PeopleStore, SearchIndex)} without an index. */
	public static List<Match> find(PathInfo scope, SearchQuery query, Function<PathInfo, Resource> albums,
			PeopleStore people) {
		return find(scope, query, albums, people, null);
	}

	/** By date, a photograph without one last, then by path. */
	public static final Comparator<Match> ORDER = (a, b) -> {
		long da = a._photo._date;
		long db = b._photo._date;
		if (da != db) {
			if (da == 0) {
				return 1;
			}
			if (db == 0) {
				return -1;
			}
			return Long.compare(da, db);
		}
		return a._relative.compareTo(b._relative);
	};

	/** The search asking every album below the folder, see {@link #find}. */
	static List<Match> scan(PathInfo scope, Test test, Function<PathInfo, Resource> albums) {
		List<Match> result = new ArrayList<>();
		walk(scope, "", test, albums, result);
		return result;
	}

	private static void walk(PathInfo folder, String prefix, Test test, Function<PathInfo, Resource> albums,
			List<Match> result) {
		File dir = folder.toFile();
		File[] children = dir.listFiles();
		if (children == null) {
			return;
		}
		boolean holdsFiles = false;
		List<String> folders = new ArrayList<>();
		for (File child : children) {
			if (!isLibraryEntry(child.getName())) {
				continue;
			}
			if (child.isDirectory()) {
				folders.add(child.getName());
			} else {
				holdsFiles = true;
			}
		}
		AlbumInfo album = albumAt(folder, holdsFiles, albums);
		if (album != null) {
			if (holdsNoFiles(album)) {
				// A collection or a saved search: nothing of its own, nothing below it.
				return;
			}
			Album texts = albumOf(album, folder);
			for (ImagePart image : images(album)) {
				Photo photo = Photo.of(image, Interner.NONE);
				if (!photo.isTrash() && test.test(photo, texts)) {
					result.add(new Match(folder, prefix + photo._name, photo));
				}
			}
		}
		folders.sort(null);
		for (String child : folders) {
			walk(folder.child(child), prefix + child + "/", test, albums, result);
		}
	}

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

	/** What the search asks of the given album at the given folder. */
	static Album albumOf(AlbumInfo album, PathInfo folder) {
		return new Album(album.getTitle(), album.getSubTitle(), folder.relativePath());
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
			Function<PathInfo, Resource> albums, PeopleStore people) {
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
		if (image == null || image.getRating() == Ratings.TRASH) {
			return null;
		}
		return matcher(query, people).test(Photo.of(image, Interner.NONE), albumOf(album, folder)) ? folder : null;
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

	/** The labels of the given photographs, each once, sorted. */
	public static List<LabelName> labelsOf(Iterable<Photo> photos) {
		java.util.TreeSet<String> names = new java.util.TreeSet<>();
		for (Photo photo : photos) {
			names.addAll(photo.getLabels());
		}
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
			.setLabels(image.getLabels())
			.setRef(image.getRef())
			.setMissing(image.isMissing())
			.setPlaces(image.getPlaces());
	}

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
			.setDate(album.getDate())
			.setEffectiveDate(album.getEffectiveDate())
			.setParts(parts);
	}
}
