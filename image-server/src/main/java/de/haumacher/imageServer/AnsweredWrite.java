/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.faces.FaceIndex;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.io.StringR;
import de.haumacher.msgbuf.io.StringW;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the writer of a sidecar <code>PUT</code> was not answered is not theirs to write, see issue
 * #296.
 *
 * <p>
 * A caller is answered a folder narrowed to them: a share link and the public are answered no
 * label and no face tag, a clearance below <code>private</code> is answered no private photograph,
 * a link's rating floor and label hide what lies below or outside them, a hidden album picture is
 * answered as a substitute, the place names are put into the caller's language, a search answers
 * its photographs slim. A client writes back what it was answered. Taken as it stands, the body
 * would erase what was left out and freeze what was derived for this one caller.
 * </p>
 *
 * <p>
 * So the body is merged onto the stored folder through the very answer its writer was given: for
 * each field, the body is authoritative where the answer showed the stored value as it is. A field
 * the answer put otherwise for this caller (substituted, localized, derived) keeps its stored value
 * where the body writes back what was answered, and takes the body's where the caller changed it:
 * that is their choice. A field this caller is never answered (the labels and the face tags of a
 * share link: <code>withheld</code>) keeps its stored value whatever the body says, empty or not.
 * The same holds part by part: a
 * photograph, a member of a group or a heading the answer did not hold is neither dropped because
 * the body lacks it nor changed because the body names it; it stays where it stood, after the part
 * it followed before. An editor answered everything is answered every stored value as it is and
 * writes all of it, as before.
 * </p>
 *
 * <p>
 * One mechanism for every field, present and future: the fields are compared as the model writes
 * them, never listed here. Never changes the stored or the answered resource.
 * </p>
 */
final class AnsweredWrite {

	/** The field of an album holding its parts, merged part by part rather than as a value. */
	private static final String PARTS = "parts";

	private final Set<String> _withheld;

	private boolean _changed;

	private AnsweredWrite(Set<String> withheld) {
		_withheld = withheld;
	}

	/**
	 * The resource to store for the given body.
	 *
	 * @param received
	 *        The parsed body; never changed.
	 * @param stored
	 *        What the server holds for the folder (the cache); never changed.
	 * @param answered
	 *        What the writer of the body is answered for the folder; never changed.
	 * @param withheld
	 *        The fields of a photograph the writer is never answered, whatever they hold (a share
	 *        link is answered no label, empty or not): kept as stored, see
	 *        <code>ImageServlet#withheld</code>.
	 * @return The given body itself where it writes nothing that was not answered, a merged copy
	 *         otherwise.
	 */
	static FolderResource merge(FolderResource received, Resource stored, Resource answered, Set<String> withheld) {
		if (answered == stored || answered == null || stored == null || received.getClass() != stored.getClass()
			|| answered.getClass() != stored.getClass()) {
			return received;
		}
		AnsweredWrite merge = new AnsweredWrite(withheld);
		FolderResource result = (FolderResource) merge.fields(stored, answered, received, PARTS, Collections.emptySet());
		if (received instanceof AlbumInfo) {
			AlbumInfo album = (AlbumInfo) received;
			AlbumInfo before = (AlbumInfo) stored;
			AlbumInfo shown = (AlbumInfo) answered;
			((AlbumInfo) result).setParts(
				shown.getParts() == before.getParts() ? album.getParts() : merge.parts(album, before, shown));
		}
		return merge._changed ? result : received;
	}

	/**
	 * The given photograph to store for the given received one, see
	 * {@link #merge(FolderResource, Resource, Resource)}.
	 *
	 * @return The received photograph itself where it writes nothing that was not answered, a
	 *         merged copy otherwise.
	 */
	static ImagePart image(ImagePart received, ImagePart stored, ImagePart answered, Set<String> withheld) {
		if (stored == null || answered == null || (stored == answered && withheld.isEmpty())) {
			return received;
		}
		AnsweredWrite merge = new AnsweredWrite(withheld);
		ImagePart result = (ImagePart) merge.fields(stored, answered, received, null, withheld);
		return merge._changed ? result : received;
	}

	/**
	 * Whether the given answer holds every photograph the given stored album holds, see
	 * {@link #merge(FolderResource, Resource, Resource)}.
	 */
	static boolean showsAllImages(Resource stored, Resource answered) {
		if (!(stored instanceof AlbumInfo)) {
			return true;
		}
		if (!(answered instanceof AlbumInfo)) {
			return false;
		}
		Set<String> shown = new HashSet<>();
		for (ImagePart image : PhotoSearch.images((AlbumInfo) answered)) {
			shown.add(image.getName());
		}
		for (ImagePart image : PhotoSearch.images((AlbumInfo) stored)) {
			if (!shown.contains(image.getName())) {
				return false;
			}
		}
		return true;
	}

	// --- Field by field. ---

	/**
	 * A copy of the received resource whose fields are taken from it where the answer showed the
	 * stored value as it is, and from the stored resource everywhere else.
	 *
	 * @param skip
	 *        A field left as the received resource has it, merged by the caller; <code>null</code>
	 *        for none.
	 * @param withheld
	 *        Fields kept as stored whatever the answer showed.
	 */
	private Resource fields(Resource stored, Resource answered, Resource received, String skip,
			Set<String> withheld) {
		Map<String, String> before = fieldsOf(stored);
		Map<String, String> shown = fieldsOf(answered);
		Map<String, String> wanted = fieldsOf(received);
		Map<String, String> result = new LinkedHashMap<>(wanted);
		Set<String> names = new HashSet<>(before.keySet());
		names.addAll(shown.keySet());
		for (String name : names) {
			if (name.equals(skip)) {
				continue;
			}
			String value = before.get(name);
			String answer = shown.get(name);
			if (!withheld.contains(name)) {
				if (Objects.equals(value, answer)) {
					// Answered as it is stored: the body says what it is to be.
					continue;
				}
				if (!Objects.equals(wanted.get(name), answer)) {
					// Answered otherwise (a substitute for a picture the caller may not see) and
					// changed: what they put in its place is their choice.
					continue;
				}
			}
			// Never answered to this caller, or written back as it was answered for them alone
			// (left out, substituted, localized, derived): not theirs to write.
			if (value == null) {
				result.remove(name);
			} else {
				result.put(name, value);
			}
			_changed |= !Objects.equals(value, wanted.get(name));
		}
		if (skip != null) {
			result.put(skip, "[]");
		}
		return read(blank(received), result);
	}

	/** The fields of the given resource, each as the JSON the model writes it in. */
	private static Map<String, String> fieldsOf(Resource resource) {
		try {
			StringW buffer = new StringW();
			try (JsonWriter out = new JsonWriter(buffer)) {
				resource.writeContent(out);
			}
			Map<String, String> result = new LinkedHashMap<>();
			try (JsonReader in = new JsonReader(new StringR(buffer.toString()))) {
				in.beginObject();
				while (in.hasNext()) {
					String name = in.nextName();
					StringW value = new StringW();
					try (JsonWriter out = new JsonWriter(value)) {
						copy(in, out);
					}
					result.put(name, value.toString());
				}
				in.endObject();
			}
			return result;
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void copy(JsonReader in, JsonWriter out) throws IOException {
		switch (in.peek()) {
			case BEGIN_ARRAY:
				in.beginArray();
				out.beginArray();
				while (in.hasNext()) {
					copy(in, out);
				}
				in.endArray();
				out.endArray();
				break;
			case BEGIN_OBJECT:
				in.beginObject();
				out.beginObject();
				while (in.hasNext()) {
					out.name(in.nextName());
					copy(in, out);
				}
				in.endObject();
				out.endObject();
				break;
			case NUMBER:
				// As it is written: a long stays a long.
				out.jsonValue(in.nextString());
				break;
			case BOOLEAN:
				out.value(in.nextBoolean());
				break;
			case NULL:
				in.nextNull();
				out.nullValue();
				break;
			default:
				out.value(in.nextString());
				break;
		}
	}

	private static <T extends Resource> T read(T result, Map<String, String> fields) {
		try {
			StringW buffer = new StringW();
			try (JsonWriter out = new JsonWriter(buffer)) {
				out.beginObject();
				for (Map.Entry<String, String> field : fields.entrySet()) {
					out.name(field.getKey());
					out.jsonValue(field.getValue());
				}
				out.endObject();
			}
			try (JsonReader in = new JsonReader(new StringR(buffer.toString()))) {
				result.readContent(in);
			}
			return result;
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static Resource blank(Resource like) {
		if (like instanceof AlbumInfo) {
			return AlbumInfo.create();
		}
		if (like instanceof ListingInfo) {
			return ListingInfo.create();
		}
		if (like instanceof ImagePart) {
			return ImagePart.create();
		}
		throw new IllegalArgumentException("No sidecar resource: " + like);
	}

	// --- Part by part. ---

	/**
	 * The parts to store: the received ones, each photograph merged field by field, and every part
	 * the answer did not hold where it stood.
	 */
	private List<AlbumPart> parts(AlbumInfo received, AlbumInfo stored, AlbumInfo answered) {
		Map<String, ImagePart> storedImages = byName(stored);
		Map<String, ImagePart> shownImages = byName(answered);
		List<AlbumPart> order = stored.getParts();

		// The headings the answer kept, a subsequence of the stored ones (a filter drops a heading
		// whose section shows nothing; an inbox answers none).
		List<Heading> shownHeadings = new ArrayList<>();
		for (AlbumPart part : answered.getParts()) {
			if (part instanceof Heading) {
				shownHeadings.add((Heading) part);
			}
		}
		int[] headingOrdinal = new int[order.size()];
		int next = 0;
		for (int n = 0; n < order.size(); n++) {
			headingOrdinal[n] = -1;
			AlbumPart part = order.get(n);
			if (part instanceof Heading && next < shownHeadings.size() && sameHeading((Heading) part, shownHeadings.get(next))) {
				headingOrdinal[n] = next++;
			}
		}

		// The received parts, without what this caller was not answered.
		List<AlbumPart> result = new ArrayList<>();
		List<Heading> receivedHeadings = new ArrayList<>();
		for (AlbumPart part : received.getParts()) {
			if (part instanceof ImagePart) {
				ImagePart image = mergeImage((ImagePart) part, storedImages, shownImages);
				if (image != null) {
					result.add(image);
				}
			} else if (part instanceof ImageGroup) {
				ImageGroup group = (ImageGroup) part;
				List<ImagePart> images = new ArrayList<>();
				for (ImagePart member : group.getImages()) {
					ImagePart image = mergeImage(member, storedImages, shownImages);
					if (image != null) {
						images.add(image);
					}
				}
				if (images.size() != group.getImages().size()) {
					_changed = true;
				}
				if (!images.isEmpty()) {
					String head = representative(group);
					result.add(ImageGroup.create().setImages(images).setRepresentative(Math.max(0, indexOf(images, head))));
				}
			} else {
				if (part instanceof Heading) {
					receivedHeadings.add((Heading) part);
				}
				result.add(part);
			}
		}
		// A heading of the answer is the received heading of its rank, while the caller added or
		// removed none; a heading they renamed is still that heading.
		boolean headingsKept = receivedHeadings.size() == shownHeadings.size();

		// Where each stored part stands in the result, for what was not answered to follow.
		Map<String, AlbumPart> containerOf = new HashMap<>();
		for (AlbumPart part : result) {
			for (ImagePart image : imagesOf(part)) {
				containerOf.putIfAbsent(image.getName(), part);
			}
		}
		AlbumPart[] counterpart = new AlbumPart[order.size()];
		for (int n = 0; n < order.size(); n++) {
			AlbumPart part = order.get(n);
			if (part instanceof Heading) {
				if (headingOrdinal[n] >= 0 && headingsKept) {
					counterpart[n] = receivedHeadings.get(headingOrdinal[n]);
				}
			} else {
				for (ImagePart image : imagesOf(part)) {
					if (shownImages.containsKey(image.getName()) && containerOf.containsKey(image.getName())) {
						counterpart[n] = containerOf.get(image.getName());
						break;
					}
				}
			}
		}

		for (int n = 0; n < order.size(); n++) {
			AlbumPart part = order.get(n);
			if (part instanceof Heading) {
				if (headingOrdinal[n] < 0) {
					AlbumPart copy = Heading.create().setText(((Heading) part).getText()).setLevel(((Heading) part).getLevel());
					// A section begins before what follows it.
					counterpart[n] = insert(result, anchorBefore(result, counterpart, n), copy);
				}
				continue;
			}
			if (part instanceof ImagePart) {
				if (!shownImages.containsKey(((ImagePart) part).getName())) {
					counterpart[n] = insert(result, anchor(result, counterpart, n), FaceIndex.copyOf((ImagePart) part));
				}
				continue;
			}
			if (!(part instanceof ImageGroup)) {
				continue;
			}
			ImageGroup group = (ImageGroup) part;
			List<ImagePart> hidden = new ArrayList<>();
			for (ImagePart image : group.getImages()) {
				if (!shownImages.containsKey(image.getName())) {
					hidden.add(image);
				}
			}
			if (hidden.isEmpty()) {
				continue;
			}
			String head = representative(group);
			boolean hiddenHead = head != null && !shownImages.containsKey(head);
			String shownHead = shownHead(answered, group);
			AlbumPart target = counterpart[n];
			if (target instanceof ImageGroup && indexOfPart(result, target) >= 0) {
				// The group the caller was answered (every group of the result is this merge's own
				// copy): its hidden members go back in after the member they followed.
				ImageGroup into = (ImageGroup) target;
				String receivedHead = representative(into);
				// A head the caller may not see stays, unless they chose another one.
				String wantedHead =
					hiddenHead && (receivedHead == null || receivedHead.equals(shownHead)) ? head : receivedHead;
				List<ImagePart> images = new ArrayList<>(into.getImages());
				List<ImagePart> members = group.getImages();
				for (int m = 0; m < members.size(); m++) {
					ImagePart member = members.get(m);
					if (shownImages.containsKey(member.getName())) {
						continue;
					}
					int at = 0;
					for (int before = m - 1; before >= 0; before--) {
						int index = indexOf(images, members.get(before).getName());
						if (index >= 0) {
							at = index + 1;
							break;
						}
					}
					images.add(at, FaceIndex.copyOf(member));
				}
				into.setImages(images);
				into.setRepresentative(Math.max(0, indexOf(images, wantedHead)));
				_changed = true;
			} else {
				// The caller took the group apart or left out what they were shown of it: what they
				// were not shown stays together.
				AlbumPart rest;
				if (hidden.size() == 1) {
					rest = FaceIndex.copyOf(hidden.get(0));
				} else {
					List<ImagePart> images = new ArrayList<>();
					for (ImagePart image : hidden) {
						images.add(FaceIndex.copyOf(image));
					}
					rest = ImageGroup.create().setImages(images)
						.setRepresentative(Math.max(0, indexOf(images, hiddenHead ? head : null)));
				}
				int at = target != null && indexOfPart(result, target) >= 0 ? indexOfPart(result, target) + 1
					: anchor(result, counterpart, n);
				AlbumPart placed = insert(result, at, rest);
				if (counterpart[n] == null) {
					counterpart[n] = placed;
				}
			}
		}
		return result;
	}

	/**
	 * The received photograph to store: merged with the stored one where it was answered,
	 * <code>null</code> where it is one this caller was not answered, as it came for a name the
	 * folder does not hold.
	 */
	private ImagePart mergeImage(ImagePart image, Map<String, ImagePart> stored, Map<String, ImagePart> shown) {
		ImagePart before = stored.get(image.getName());
		if (before == null) {
			return image;
		}
		ImagePart answer = shown.get(image.getName());
		if (answer == null) {
			// Not theirs: it stays as it is, where it is.
			_changed = true;
			return null;
		}
		if (answer == before && _withheld.isEmpty()) {
			return image;
		}
		return (ImagePart) fields(before, answer, image, null, _withheld);
	}

	private AlbumPart insert(List<AlbumPart> result, int at, AlbumPart part) {
		result.add(at, part);
		_changed = true;
		return part;
	}

	/** Where a part the answer did not hold goes: after the stored part before it that is placed. */
	private static int anchor(List<AlbumPart> result, AlbumPart[] counterpart, int n) {
		for (int before = n - 1; before >= 0; before--) {
			AlbumPart placed = counterpart[before];
			if (placed != null) {
				int index = indexOfPart(result, placed);
				if (index >= 0) {
					return index + 1;
				}
			}
		}
		return 0;
	}

	/** Where a heading the answer did not hold goes: before the stored part after it that is placed. */
	private static int anchorBefore(List<AlbumPart> result, AlbumPart[] counterpart, int n) {
		for (int after = n + 1; after < counterpart.length; after++) {
			AlbumPart placed = counterpart[after];
			if (placed != null) {
				int index = indexOfPart(result, placed);
				if (index >= 0) {
					return index;
				}
			}
		}
		return result.size();
	}

	private static int indexOfPart(List<AlbumPart> parts, AlbumPart part) {
		for (int n = 0; n < parts.size(); n++) {
			if (parts.get(n) == part) {
				return n;
			}
		}
		return -1;
	}

	private static int indexOf(List<ImagePart> images, String name) {
		if (name == null) {
			return -1;
		}
		for (int n = 0; n < images.size(); n++) {
			if (images.get(n).getName().equals(name)) {
				return n;
			}
		}
		return -1;
	}

	/**
	 * The name of the image the answer shows the given stored group by, <code>null</code> where
	 * it shows none of it as a group.
	 */
	private static String shownHead(AlbumInfo answered, ImageGroup group) {
		Set<String> names = new HashSet<>();
		for (ImagePart image : group.getImages()) {
			names.add(image.getName());
		}
		for (AlbumPart part : answered.getParts()) {
			if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					if (names.contains(image.getName())) {
						return representative((ImageGroup) part);
					}
				}
			}
		}
		return null;
	}

	/** The name of the image the given group is shown by, <code>null</code> for none. */
	private static String representative(ImageGroup group) {
		int index = group.getRepresentative();
		List<ImagePart> images = group.getImages();
		return index >= 0 && index < images.size() ? images.get(index).getName() : null;
	}

	private static boolean sameHeading(Heading a, Heading b) {
		return a == b || (a.getText().equals(b.getText()) && a.getLevel() == b.getLevel());
	}

	private static Map<String, ImagePart> byName(AlbumInfo album) {
		Map<String, ImagePart> result = new HashMap<>();
		for (ImagePart image : PhotoSearch.images(album)) {
			result.putIfAbsent(image.getName(), image);
		}
		return result;
	}

	private static List<ImagePart> imagesOf(AlbumPart part) {
		if (part instanceof ImagePart) {
			return Collections.singletonList((ImagePart) part);
		}
		if (part instanceof ImageGroup) {
			return ((ImageGroup) part).getImages();
		}
		return Collections.emptyList();
	}
}
