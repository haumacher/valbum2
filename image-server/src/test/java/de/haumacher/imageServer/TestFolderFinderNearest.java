/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.shared.model.FolderMatch;
import de.haumacher.imageServer.shared.model.FolderMatches;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the album a move suggests, issue #279:
 * {@link FolderFinder#nearest(PathInfo, long, String, int, Predicate, Predicate)} answers the
 * albums nearest to the day the first photograph was taken.
 *
 * <pre>
 * 2019/2019-07-14 Rom        dated by its name
 * 2023/2023-12-30 Silvester  dated by its name
 * 2024/2024-01-10 Ski        dated by its name
 * 2024/2024-03-05 Party      dated by its name
 * 2024/Hike                  undated, photos from March 1st to 20th (and a private one of December 2023)
 * 2024/Scans                 undated, photos without a date: never answered
 * 2024/2024-02-01 Notes      a collection: never answered
 * </pre>
 */
@SuppressWarnings("javadoc")
public class TestFolderFinderNearest extends TestCase {

	static final String ROM = "2019/2019-07-14 Rom";

	static final String SILVESTER = "2023/2023-12-30 Silvester";

	static final String SKI = "2024/2024-01-10 Ski";

	static final String PARTY = "2024/2024-03-05 Party";

	static final String HIKE = "2024/Hike";

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-nearest");
		album(ROM, "");
		album(SILVESTER, "");
		album(SKI, "");
		album(PARTY, "");
		album(HIKE, part("a.jpg", day(2024, 3, 1), 0) + "," + part("b.jpg", day(2024, 3, 20), 1) + ","
			+ "[\"ImageGroup\",{\"images\":[" + member("c.jpg", day(2024, 3, 10), 0) + "]}]," + part("x.jpg",
				day(2023, 12, 1), 2));
		album("2024/Scans", part("s.jpg", 0, 0));
		Files.createDirectories(_base.resolve("2024/2024-02-01 Notes"));
		Files.write(_base.resolve("2024/2024-02-01 Notes/index.json"),
			"[\"AlbumInfo\",{\"kind\":\"COLLECTION\",\"title\":\"Notes\",\"parts\":[]}]"
				.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testTheNearestAcrossYears() {
		assertEquals("Three days back into the old year beat eight days ahead.", Arrays.asList(SILVESTER),
			nearest(day(2024, 1, 2), null));
		assertEquals(Arrays.asList(SKI), nearest(day(2024, 1, 8), null));
		assertEquals("Long before everything: the oldest.", Arrays.asList(ROM), nearest(day(2001, 1, 1), null));
	}

	public void testAnAlbumCoveringTheDayBeatsANearerDate() {
		// Party (March 5th) is nearer by its date than Hike begins, but Hike's photos span the day.
		assertEquals(Arrays.asList(HIKE), nearest(day(2024, 3, 6), null));
		assertEquals("Its photos date an album its name does not.", Arrays.asList(HIKE),
			nearest(day(2024, 3, 25), null));
	}

	public void testEquallyNearTheNewerFirst() {
		assertEquals("Both cover the day: the one that began later first.", Arrays.asList(PARTY, HIKE),
			nearest(midnight(2024, 3, 5), null, 2));
	}

	public void testOnlyAlbumsADateSaysAnythingAbout() {
		List<String> all = nearest(day(2024, 2, 1), null, 50);
		assertEquals(Arrays.asList(SKI, HIKE, PARTY, SILVESTER, ROM), all);
		assertFalse("No folder of folders, no collection, no album nothing dates.", all.contains("2024/Scans"));
	}

	public void testAPrivatePhotoDoesNotCount() {
		assertEquals("Hike begins in March: its private photo of December is not counted.",
			Arrays.asList(SILVESTER), nearest(day(2023, 12, 2), null));
	}

	public void testTheSourceIsNoTarget() {
		assertEquals(Arrays.asList(SKI), nearest(day(2023, 12, 30), SILVESTER));
	}

	public void testTheSourceWhereThereIsNoOther() {
		PathInfo root = new PathInfo(_base);
		PathInfo silvester = root.child("2023").child("2023-12-30 Silvester");
		assertEquals("Moving into it moves nothing, but nothing else is there to suggest.",
			Arrays.asList(SILVESTER), paths(new FolderFinder().nearest(root, day(2024, 1, 1), SILVESTER, 1,
				path -> true, path -> path.equals(silvester))));
	}

	public void testOnlyWhatTheCallerMaySeeAndAddTo() {
		PathInfo root = new PathInfo(_base);
		PathInfo year = root.child("2023");
		PathInfo ski = root.child("2024").child("2024-01-10 Ski");
		FolderFinder finder = new FolderFinder();
		assertEquals("Nothing below a folder the caller may not see.", Arrays.asList(SKI),
			paths(finder.nearest(root, day(2024, 1, 1), null, 1, path -> !path.equals(year), path -> true)));
		assertEquals("No album the caller may not add to.", Arrays.asList(SILVESTER),
			paths(finder.nearest(root, day(2024, 1, 9), null, 1, path -> true, path -> !path.equals(ski))));
		assertTrue(paths(finder.nearest(root, day(2024, 1, 9), null, 1, path -> true, path -> false)).isEmpty());
	}

	public void testNoAlbumsNothingSuggested() throws Exception {
		Path empty = Files.createDirectories(_base.resolve("2024/Empty"));
		FolderMatches found = new FolderFinder().nearest(new PathInfo(empty), day(2024, 1, 1), null, 1,
			path -> true, path -> true);
		assertTrue(found.getFolders().isEmpty());
		assertFalse(found.isMore());
	}

	public void testAChangedAlbumIsReadAgain() throws Exception {
		FolderFinder finder = new FolderFinder();
		PathInfo root = new PathInfo(_base);
		assertEquals(Arrays.asList(SKI), paths(finder.nearest(root, day(2024, 1, 20), null, 1, p -> true, p -> true)));
		File sidecar = _base.resolve(HIKE).resolve("index.json").toFile();
		long before = sidecar.lastModified();
		Files.write(sidecar.toPath(), ("[\"AlbumInfo\",{\"title\":\"Hike\",\"parts\":[" + part("a.jpg", day(2024, 1, 19), 0)
			+ "]}]").getBytes(StandardCharsets.UTF_8));
		assertTrue(sidecar.setLastModified(before + 2000));
		assertEquals("The same finder, the new photos.", Arrays.asList(HIKE),
			paths(finder.nearest(root, day(2024, 1, 20), null, 1, p -> true, p -> true)));
	}

	private List<String> nearest(long day, String source) {
		return nearest(day, source, 1);
	}

	private List<String> nearest(long day, String source, int limit) {
		return paths(new FolderFinder().nearest(new PathInfo(_base), day, source, limit, path -> true, path -> true));
	}

	private void album(String path, String parts) throws Exception {
		Path folder = Files.createDirectories(_base.resolve(path));
		Files.write(folder.resolve("index.json"),
			("[\"AlbumInfo\",{\"title\":\"" + folder.getFileName() + "\",\"parts\":[" + parts + "]}]")
				.getBytes(StandardCharsets.UTF_8));
	}

	private static String part(String name, long date, int privacy) {
		return "[\"ImagePart\"," + member(name, date, privacy) + "]";
	}

	/** A photo of a group, which names no type. */
	private static String member(String name, long date, int privacy) {
		return "{\"name\":\"" + name + "\",\"width\":4,\"height\":3,\"date\":" + date
			+ ",\"privacy\":" + privacy + "}";
	}

	/** The start of the given day, as a folder name dates it. */
	private static long midnight(int year, int month, int day) {
		return LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	/** Noon of the given day, in the zone the server reads folder names in. */
	private static long day(int year, int month, int day) {
		return LocalDate.of(year, month, day).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	private static List<String> paths(FolderMatches matches) {
		List<String> result = new ArrayList<>();
		for (FolderMatch match : matches.getFolders()) {
			result.add(match.getPath());
		}
		return result;
	}
}
