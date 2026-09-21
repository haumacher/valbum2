/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.faces;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Grouping the faces of one album into the people they most likely are, see issue #124.
 *
 * <p>
 * Agglomerative clustering with average linkage on the cosine similarity of the SFace embeddings,
 * stopped at the threshold the model was published with ({@value #THRESHOLD}): two groups are
 * joined while the average similarity between their faces is at least that, and the moment the
 * best remaining pair is below it nothing is joined any more. Average linkage rather than single
 * linkage, because a single lucky pair would otherwise chain two people into one group and the
 * chain cannot be undone.
 * </p>
 *
 * <p>
 * Per album and not per space: one album is one occasion, the faces in it are few and the same
 * people recur, so the grouping is both cheap and unusually reliable there. Recognising a person
 * across the whole space is issue #127, and pinning a name to a group is issue #125 — this is
 * cache, and a group identifier says &quot;these are most likely the same person&quot; and nothing
 * about who that is.
 * </p>
 *
 * <p>
 * The identifiers are assigned deterministically — the largest group first, ties broken by the
 * first face in it — so the same set of faces in the same order is always grouped the same way. A
 * face that arrives later may renumber them, which is why nothing outside the cache ever keeps a
 * group identifier.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class Clustering {

	/**
	 * How similar two faces have to be to be the same person.
	 *
	 * <p>
	 * The cosine threshold SFace is published with (OpenCV's own sample uses the same number).
	 * </p>
	 */
	public static final double THRESHOLD = 0.363;

	private Clustering() {
		// Static utility.
	}

	/**
	 * Puts every face into a group and writes the group's identifier on it.
	 *
	 * @param faces
	 *        Every face of one album, in a stable order.
	 */
	public static void cluster(List<FaceCache.Face> faces) {
		int count = faces.size();
		double[][] similarity = new double[count][count];
		for (int i = 0; i < count; i++) {
			for (int j = i + 1; j < count; j++) {
				double value = cosine(faces.get(i).getEmbedding(), faces.get(j).getEmbedding());
				similarity[i][j] = value;
				similarity[j][i] = value;
			}
		}

		List<List<Integer>> groups = new ArrayList<>();
		for (int n = 0; n < count; n++) {
			List<Integer> group = new ArrayList<>();
			group.add(Integer.valueOf(n));
			groups.add(group);
		}

		for (;;) {
			int bestLeft = -1;
			int bestRight = -1;
			double best = THRESHOLD;
			for (int i = 0; i < groups.size(); i++) {
				for (int j = i + 1; j < groups.size(); j++) {
					double average = average(similarity, groups.get(i), groups.get(j));
					if (average >= best) {
						best = average;
						bestLeft = i;
						bestRight = j;
					}
				}
			}
			if (bestLeft < 0) {
				break;
			}
			groups.get(bestLeft).addAll(groups.get(bestRight));
			groups.remove(bestRight);
		}

		// The largest group first, ties broken by the first face in it: the same faces always
		// produce the same identifiers.
		groups.sort(Comparator
			.comparingInt((List<Integer> group) -> -group.size())
			.thenComparingInt(group -> group.stream().mapToInt(Integer::intValue).min().orElse(0)));

		for (int n = 0; n < groups.size(); n++) {
			String name = "c" + (n + 1);
			for (Integer member : groups.get(n)) {
				faces.get(member.intValue()).setCluster(name);
			}
		}
	}

	/** The average similarity between the faces of two groups. */
	private static double average(double[][] similarity, List<Integer> left, List<Integer> right) {
		double sum = 0;
		for (Integer one : left) {
			for (Integer other : right) {
				sum += similarity[one.intValue()][other.intValue()];
			}
		}
		return sum / (left.size() * right.size());
	}

	/**
	 * The cosine similarity of two embeddings, <code>-1</code> where there is nothing to compare.
	 *
	 * <p>
	 * Never a match: a face without an embedding stays a group of its own rather than joining an
	 * arbitrary one.
	 * </p>
	 */
	public static double cosine(float[] left, float[] right) {
		if (left == null || right == null || left.length == 0 || left.length != right.length) {
			return -1;
		}
		double dot = 0;
		double leftNorm = 0;
		double rightNorm = 0;
		for (int n = 0; n < left.length; n++) {
			dot += ((double) left[n]) * right[n];
			leftNorm += ((double) left[n]) * left[n];
			rightNorm += ((double) right[n]) * right[n];
		}
		if (leftNorm <= 0 || rightNorm <= 0) {
			return -1;
		}
		return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
	}
}
