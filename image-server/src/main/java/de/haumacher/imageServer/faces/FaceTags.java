/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.faces;

import de.haumacher.imageServer.shared.model.FaceInfo;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.FaceTag;
import de.haumacher.imageServer.shared.model.ImagePart;
import java.util.ArrayList;
import java.util.List;

/**
 * How what somebody decided about a face meets what the detector found, see issue #125.
 *
 * <p>
 * Two things describe the same face and neither can be derived from the other: a
 * {@link FaceTag} is a human decision, stored beside the photograph for ever, and a
 * {@link FaceCache.Face} is a guess of one version of one model, thrown away whenever the cache is.
 * They are brought together here, on every read, by the only thing they have in common — the box
 * they are both written in, the raw raster of the file, see {@link Faces}.
 * </p>
 *
 * <p>
 * Two boxes are the same face when they overlap by more than {@link #IOU_MATCH}. Neither side is
 * ever rewritten to fit the other: a new model that finds the same face a pixel further on is
 * matched to the tag that is there, and the tag keeps the box it was made with.
 * </p>
 *
 * <p>
 * <b>A tag no detection matches is answered all the same</b>, as a {@link FaceInfo} of its own
 * behind the detected ones and without a {@link FaceInfo#getCluster() cluster}. That is the point
 * of storing the box: a confirmed name survives a model that stops finding that face, a cache that
 * was thrown away and a detector that is not installed on this machine at all. Since issue #155
 * such a face has a crop too, cut from the original by the tag's own box (see
 * {@link FaceIndex#crop(java.io.File, int, List)}).
 * </p>
 *
 * <h2>A region is a region (issue #155)</h2>
 *
 * <p>
 * A face the detector found and a face somebody marked by hand behave the same:
 * </p>
 *
 * <ul>
 * <li><b>{@link FaceState#UNDECIDED} may be stored.</b> A stored tag used to be a decision and
 * nothing else; now a tag <code>{box, person: "", state: UNDECIDED}</code> is also "a region
 * somebody marked and nobody decided about yet". It is what a hand-marked face falls back to when
 * its decision is forgotten, and what a marked region is stored as where the detector finds no face
 * in it. Such a tag is answered like a detection nobody decided about: state
 * <code>UNDECIDED</code>, no person, no cluster. Where it meets a detection it says nothing a
 * detection does not already say. An <code>index.json</code> written before issue #155 holds no
 * such tag and reads exactly as before.</li>
 * <li><b>{@link FaceState#NOT_A_FACE} removes the region from every answer.</b> A detection
 * carrying that tag is still stored as the album's statement — the tag is what keeps the next
 * model's detection of the same spot away — but it is <em>not answered</em> any more, and so it is
 * never suggested anybody either; an unmatched tag of that state is not answered either (a
 * hand-marked region called "no face" is removed from the sidecar outright, so such a tag is only
 * found in an album written before issue #155).</li>
 * </ul>
 *
 * <h2>The numbering</h2>
 *
 * <p>
 * Every face keeps the index it would have had with nothing hidden: a detection is numbered by
 * its position in the {@link FaceCache}, and a tag no detection matches by the number of
 * detections plus its position among the unmatched tags. A face that is hidden keeps its slot and
 * is simply not answered, so the numbers of the others do not move when a false detection is
 * called one — an answer may therefore skip a number — and a request naming a hidden number is
 * answered as one naming no face at all. {@link #answer(List, List, PeopleStore)} is the one place
 * this numbering is made, for the album answer, for the numbering a tagging request names a face by
 * and for the crop of <code>?type=face</code> alike.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class FaceTags {

	/**
	 * How much two boxes must overlap to be the same face: their intersection over their union.
	 *
	 * <p>
	 * One half, the value object detection has used for "the same thing" since it was first
	 * measured that way. It is deliberately generous in both directions: a detector that shifts a
	 * box by a few percent between two model versions still finds its tag, and two faces that
	 * really are two faces — two people side by side, a face and the head behind it — overlap by
	 * far less than this, so a decision never moves to the wrong person.
	 * </p>
	 *
	 * <p>
	 * The same threshold decides on the way in and on the way out: a tag written on a box that
	 * matches an existing one <em>replaces</em> it rather than standing beside it, so a face never
	 * carries two decisions at once.
	 * </p>
	 */
	public static final double IOU_MATCH = 0.5;

	private FaceTags() {
		// Static utility.
	}

	/**
	 * The faces of one photograph as they are answered: what was found, what was decided, and what
	 * was decided about something that was not found.
	 *
	 * @param detected
	 *        What the detector found, already numbered from zero; modified in place.
	 * @param tags
	 *        What the album stores about this photograph.
	 * @param people
	 *        The register the person of a tag is resolved through, <code>null</code> when there is
	 *        none.
	 * @return The list to answer, the detected faces first and in their own order.
	 */
	public static List<FaceInfo> merge(List<FaceInfo> detected, List<FaceTag> tags, PeopleStore people) {
		return answer(detected, tags, people).getFaces();
	}

	/**
	 * The faces of one photograph as they are answered, and what a request naming one of them needs
	 * to know besides, see "The numbering" above.
	 */
	public static final class Answer {

		private final List<FaceInfo> _faces;

		private final int _detections;

		private final List<FaceInfo> _hidden;

		Answer(List<FaceInfo> faces, int detections, List<FaceInfo> hidden) {
			_faces = faces;
			_detections = detections;
			_hidden = hidden;
		}

		/** The faces to answer, in their order; the index of each is its own number. */
		public List<FaceInfo> getFaces() {
			return _faces;
		}

		/** How many numbers the detections take; every number from here on is a tag's. */
		public int getDetections() {
			return _detections;
		}

		/**
		 * The detections that are not answered because somebody said they are no face, with their
		 * numbers and boxes.
		 */
		public List<FaceInfo> getHidden() {
			return _hidden;
		}

		/** The answered face of the given number, <code>null</code> where none is answered. */
		public FaceInfo byIndex(int index) {
			for (FaceInfo face : _faces) {
				if (face.getIndex() == index) {
					return face;
				}
			}
			return null;
		}

		/** Whether the given answered face is one the detector found, not only a stored tag. */
		public boolean isDetection(FaceInfo face) {
			return face.getIndex() < _detections;
		}
	}

	/**
	 * The faces of one photograph, numbered, see "The numbering" above.
	 *
	 * @param detected
	 *        What the detector found, numbered from zero in the order of the cache; modified in
	 *        place.
	 * @param tags
	 *        What the album stores about this photograph.
	 * @param people
	 *        The register the person of a tag is resolved through, <code>null</code> when there is
	 *        none.
	 */
	public static Answer answer(List<FaceInfo> detected, List<FaceTag> tags, PeopleStore people) {
		List<FaceInfo> result = new ArrayList<>(detected.size() + tags.size());
		List<FaceInfo> hidden = new ArrayList<>();
		boolean[] used = new boolean[tags.size()];
		int slot = 0;
		for (FaceInfo face : detected) {
			int best = -1;
			double bestOverlap = IOU_MATCH;
			for (int n = 0; n < tags.size(); n++) {
				FaceTag tag = tags.get(n);
				double overlap = iou(face.getX(), face.getY(), face.getW(), face.getH(),
					tag.getX(), tag.getY(), tag.getW(), tag.getH());
				if (overlap > bestOverlap) {
					bestOverlap = overlap;
					best = n;
				}
			}
			face.setIndex(slot++);
			if (best >= 0) {
				used[best] = true;
				apply(face, tags.get(best), people);
				if (tags.get(best).getState() == FaceState.NOT_A_FACE) {
					// Somebody said this is no face: the region is gone from every answer, and the
					// tag stays to keep the next detection of the same spot away.
					hidden.add(face);
					continue;
				}
			}
			result.add(face);
		}
		int detections = slot;
		for (int n = 0; n < tags.size(); n++) {
			if (used[n]) {
				continue;
			}
			FaceTag tag = tags.get(n);
			int index = slot++;
			if (tag.getState() == FaceState.NOT_A_FACE) {
				// A region called no face that nothing was detected in, as an album written before
				// issue #155 may hold one: it keeps its number and is not answered.
				continue;
			}
			FaceInfo face = FaceInfo.create()
				.setIndex(index)
				.setX(tag.getX())
				.setY(tag.getY())
				.setW(tag.getW())
				.setH(tag.getH());
			apply(face, tag, people);
			result.add(face);
		}
		return new Answer(result, detections, hidden);
	}

	/** Writes what the given tag decided onto the given answer. */
	private static void apply(FaceInfo face, FaceTag tag, PeopleStore people) {
		face.setState(tag.getState());
		face.setConfirmed(tag.getState() == FaceState.CONFIRMED);
		face.setPerson(person(tag.getPerson(), people));
	}

	/**
	 * The person the given stored id names today.
	 *
	 * <p>
	 * The survivor of every merge the id went through, see {@link PeopleStore#resolve(String)}. An
	 * id the register does not know at all is answered as it stands: a tag says what it says, and
	 * an answer that silently dropped it would hide a decision somebody made.
	 * </p>
	 */
	public static String person(String id, PeopleStore people) {
		if (id == null || id.isEmpty() || people == null) {
			return id == null ? "" : id;
		}
		PeopleStore.Entry entry = people.resolve(id);
		return entry == null ? id : entry.getId();
	}

	/**
	 * Which of the given tags is about the given box, <code>-1</code> when none is.
	 *
	 * <p>
	 * What makes a second decision about one face replace the first instead of standing beside it.
	 * </p>
	 */
	public static int indexOf(List<FaceTag> tags, double x, double y, double w, double h) {
		int best = -1;
		double bestOverlap = IOU_MATCH;
		for (int n = 0; n < tags.size(); n++) {
			FaceTag tag = tags.get(n);
			double overlap = iou(x, y, w, h, tag.getX(), tag.getY(), tag.getW(), tag.getH());
			if (overlap > bestOverlap) {
				bestOverlap = overlap;
				best = n;
			}
		}
		return best;
	}

	/** The intersection of the two boxes over their union, <code>0..1</code>. */
	public static double iou(double x1, double y1, double w1, double h1,
			double x2, double y2, double w2, double h2) {
		double left = Math.max(x1, x2);
		double top = Math.max(y1, y2);
		double right = Math.min(x1 + w1, x2 + w2);
		double bottom = Math.min(y1 + h1, y2 + h2);
		if (right <= left || bottom <= top) {
			return 0;
		}
		double intersection = (right - left) * (bottom - top);
		double union = w1 * h1 + w2 * h2 - intersection;
		return union <= 0 ? 0 : intersection / union;
	}

	/** Whether the given photograph carries any decision at all. */
	public static boolean tagged(ImagePart image) {
		return !image.getTags().isEmpty();
	}
}
