/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.cache.BrokenParts;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.PhotoStack;
import de.haumacher.imageServer.shared.model.StackMember;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bursts of an album, see issue #257 and {@link AlbumInfo#getStacks()}: derived when the album
 * is answered, never stored.
 *
 * <h2>The rule</h2>
 *
 * <p>
 * The photographs of each section of the album (the parts between two {@link Heading}s: a stack
 * that pulled a photograph out of its section would rearrange the album) are taken in the order
 * they were recorded, and each is compared with the one before it: where the cosine of their
 * pictures' vectors is at least {@link ContentSearch#BURST_THRESHOLD} and they were taken at most
 * {@link ContentSearch#BURST_GAP} apart, they belong to one stack. A chain of such neighbours is
 * one stack, however long it is: a burst of a minute is a run of shots each like the last.
 * </p>
 *
 * <p>
 * Nothing but a photograph of the album's own parts joins: never a video (whose vector is its
 * poster's, and whose tile plays), a photograph without a vector (not described yet) or without a
 * date (no recording order), a member of an {@link de.haumacher.imageServer.shared.model.ImageGroup}
 * (a grouping of its author's already), the trash, a file that cannot be read.
 * </p>
 *
 * <h2>The best shot on top</h2>
 *
 * <p>
 * The highest {@link ImagePart#getRating() rating} first, wherever a member is rated: the author
 * said so. Among the equally rated (every member unrated, most often), the highest {@link Quality}
 * (sharp and well exposed), measured on the previews and kept beside the vectors; among those, or
 * where nothing is measured, the first taken. The others follow in the order they were taken.
 * </p>
 *
 * <h2>Cost</h2>
 *
 * <p>
 * The vectors are read from the album's own {@link ContentCache}, which names them by file name,
 * as the album does; the file is read once per change (kept by its time and size), so answering an
 * album again costs one cosine per photograph.
 * </p>
 */
public final class Stacks {

	/** How many albums' vectors are kept, see {@link #shots(File)}. */
	private static final int KEPT = 32;

	/** What is kept of an album's cache, see {@link #shots(File)}. */
	private record Kept(long modified, long length, Map<String, Shot> shots) {
		// A triple.
	}

	/** What a stack needs of one photograph of the cache: its vector, normalised, and its quality. */
	public record Shot(float[] vector, float quality) {
		// A pair.
	}

	private static final Map<String, Kept> CACHES = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Kept> eldest) {
			return size() > KEPT;
		}
	};

	private Stacks() {
		// Static only.
	}

	/**
	 * Whether the given answer is one that carries stacks: an ordinary album, never a saved
	 * search, a collection (whose photographs lie in other albums) or the inbox (which has a view of
	 * its own, by day and camera).
	 */
	public static boolean applies(AlbumInfo answer) {
		return answer.getKind() == AlbumKind.ALBUM;
	}

	/**
	 * The stacks of the given answer of the album in the given folder, see {@link Stacks}: from the
	 * album's own cache of vectors.
	 */
	public static List<PhotoStack> of(AlbumInfo answer, File folder) {
		return of(answer, folder, java.util.Collections.emptySet());
	}

	/**
	 * {@link #of(AlbumInfo, File)} without the photographs of the given names: the references of
	 * the album, see issue #307. A reference stands for a photograph of another album, whose bursts
	 * are that album's; it never joins a stack here.
	 */
	public static List<PhotoStack> of(AlbumInfo answer, File folder, java.util.Set<String> excluded) {
		Map<String, Shot> shots = shots(folder);
		if (!excluded.isEmpty()) {
			// The cached map is shared; a copy is narrowed.
			shots = new java.util.HashMap<>(shots);
			shots.keySet().removeAll(excluded);
		}
		if (shots.isEmpty()) {
			return new ArrayList<>();
		}
		return compute(answer, shots);
	}

	/**
	 * The stacks of the given answer, the vectors and qualities by file name given, see
	 * {@link Stacks}.
	 */
	public static List<PhotoStack> compute(AlbumInfo answer, Map<String, Shot> shots) {
		List<PhotoStack> result = new ArrayList<>();
		List<Candidate> section = new ArrayList<>();
		int index = 0;
		for (AlbumPart part : answer.getParts()) {
			if (part instanceof Heading) {
				chain(section, result);
				section.clear();
			} else if (part instanceof ImagePart) {
				ImagePart image = (ImagePart) part;
				Shot shot = joins(image) ? shots.get(image.getName()) : null;
				if (shot != null) {
					section.add(new Candidate(image, shot, index));
				}
			}
			index++;
		}
		chain(section, result);
		return result;
	}

	/** Whether the given photograph may join a stack at all, see {@link Stacks}. */
	private static boolean joins(ImagePart image) {
		return image.getKind() == ImageKind.IMAGE && image.getDate() != 0 && !image.isMissing()
			&& image.getRef() == null
			&& !BrokenParts.isBroken(image) && image.getRating() > Ratings.TRASH;
	}

	/** A photograph of a section that may join a stack, and where it stands in the album. */
	private record Candidate(ImagePart image, Shot shot, int index) {
		// A triple.
	}

	/** Adds the stacks of the given section to the given list. */
	private static void chain(List<Candidate> section, List<PhotoStack> result) {
		if (section.size() < 2) {
			return;
		}
		List<Candidate> taken = new ArrayList<>(section);
		taken.sort(Comparator.comparingLong((Candidate c) -> c.image().getDate()).thenComparingInt(Candidate::index));
		List<Candidate> run = new ArrayList<>();
		for (Candidate candidate : taken) {
			if (!run.isEmpty()) {
				Candidate last = run.get(run.size() - 1);
				boolean near = candidate.image().getDate() - last.image().getDate() <= ContentSearch.BURST_GAP;
				if (!near || dot(last.shot().vector(), candidate.shot().vector()) < ContentSearch.BURST_THRESHOLD) {
					stack(run, result);
					run = new ArrayList<>();
				}
			}
			run.add(candidate);
		}
		stack(run, result);
	}

	/** Adds the given run as a stack where it is one, the best shot first. */
	private static void stack(List<Candidate> run, List<PhotoStack> result) {
		if (run.size() < 2) {
			return;
		}
		Candidate best = run.get(0);
		for (Candidate candidate : run) {
			if (better(candidate, best)) {
				best = candidate;
			}
		}
		PhotoStack stack = PhotoStack.create();
		stack.addMember(StackMember.create().setName(best.image().getName()));
		for (Candidate candidate : run) {
			if (candidate != best) {
				stack.addMember(StackMember.create().setName(candidate.image().getName()));
			}
		}
		result.add(stack);
	}

	/** Whether the given shot is a better top of its stack than the other, see {@link Stacks}. */
	private static boolean better(Candidate shot, Candidate other) {
		int rating = shot.image().getRating();
		int otherRating = other.image().getRating();
		if (rating != otherRating) {
			return rating > otherRating;
		}
		// The first taken wins a tie, and a quality unknown or not measurable loses to any measured.
		return quality(shot) > quality(other);
	}

	private static float quality(Candidate candidate) {
		float quality = candidate.shot().quality();
		return Float.isNaN(quality) || quality < 0 ? -1 : quality;
	}

	/**
	 * The vectors and qualities of the given album's photographs by file name, from its
	 * {@link ContentCache}; empty where it has none. Read once per change of the file.
	 */
	static Map<String, Shot> shots(File folder) {
		File file = ContentCache.file(folder);
		if (!file.isFile()) {
			return Map.of();
		}
		String key = file.getAbsolutePath();
		long modified;
		try {
			// To the nanosecond where the file system keeps it: a quality measured changes no length.
			modified = java.nio.file.Files.getLastModifiedTime(file.toPath()).to(java.util.concurrent.TimeUnit.NANOSECONDS);
		} catch (java.io.IOException ex) {
			return Map.of();
		}
		long length = file.length();
		synchronized (CACHES) {
			Kept kept = CACHES.get(key);
			if (kept != null && kept.modified() == modified && kept.length() == length) {
				return kept.shots();
			}
		}
		ContentCache cache = ContentCache.read(folder);
		Map<String, Shot> result = new HashMap<>();
		for (Map.Entry<String, ContentCache.Photo> entry : cache.photos().entrySet()) {
			ContentCache.ImageKey image = entry.getValue().image();
			float[] vector = cache.image(image);
			float[] normal = vector == null ? null : normalized(vector);
			if (normal != null) {
				result.put(entry.getKey(), new Shot(normal, cache.quality(image)));
			}
		}
		synchronized (CACHES) {
			CACHES.put(key, new Kept(modified, length, result));
		}
		return result;
	}

	/** Forgets every album's vectors kept, for the tests. */
	public static void forget() {
		synchronized (CACHES) {
			CACHES.clear();
		}
	}

	private static float[] normalized(float[] vector) {
		double sum = 0;
		for (float value : vector) {
			sum += value * value;
		}
		if (!(sum > 0) || Double.isInfinite(sum)) {
			return null;
		}
		double length = Math.sqrt(sum);
		float[] result = new float[vector.length];
		for (int n = 0; n < vector.length; n++) {
			result[n] = (float) (vector[n] / length);
		}
		return result;
	}

	private static float dot(float[] left, float[] right) {
		if (left.length != right.length) {
			return 0;
		}
		double result = 0;
		for (int n = 0; n < left.length; n++) {
			result += left[n] * right[n];
		}
		return (float) result;
	}
}
