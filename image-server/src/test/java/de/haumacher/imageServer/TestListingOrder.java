/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the order a listing shows its folders in (issue #48): the newest first, the undated
 * behind them by name -- and, since issue #270, the oldest first in a listing whose own folder has a
 * date.
 *
 * <p>
 * This is a change of what a listing looked like before: every folder sorted by name, so an album
 * tree named by date read oldest first. The date a listing sorts by is the cheap one — a sidecar
 * date or a date in the folder name — see {@link AlbumDate}.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestListingOrder extends TestCase {

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-order-test");
		_servlet = new ImageServlet(_base.toFile());
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
		}
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	public void testTheNewestComesFirst() throws Exception {
		album("2019-07-04 Lake");
		album("2021-01-02 Snow");
		album("2020-06 Garden");
		album("2020 Nothing more");

		assertEquals(Arrays.asList("2021-01-02 Snow", "2020-06 Garden", "2020 Nothing more", "2019-07-04 Lake"),
			names(listing("/")));
	}

	public void testTheUndatedFollowByName() throws Exception {
		album("beta");
		album("2020-06 Garden");
		album("Alpha");
		album("2021-01-02 Snow");

		assertEquals("Dated first, newest to oldest; the rest by name, however it is capitalised.",
			Arrays.asList("2021-01-02 Snow", "2020-06 Garden", "Alpha", "beta"), names(listing("/")));
	}

	public void testAFolderWithoutADateSortsAsItAlwaysDid() throws Exception {
		album("Zebra");
		album("apple");
		album("Banana");

		assertEquals(Arrays.asList("apple", "Banana", "Zebra"), names(listing("/")));
	}

	public void testTheSidecarDateDecidesOverTheName() throws Exception {
		// "Trip" has no date in its name, but its sidecar states one; it must sort by that.
		album("2019-07-04 Lake");
		Path trip = _base.resolve("Trip");
		Files.createDirectories(trip);
		Files.write(trip.resolve("index.json"),
			("[\"AlbumInfo\",{\"title\":\"Trip\",\"date\":" + AlbumDate.ofFolderName("2022-03-03").millis()
				+ ",\"parts\":[]}]").getBytes(StandardCharsets.UTF_8));

		assertEquals(Arrays.asList("Trip", "2019-07-04 Lake"), names(listing("/")));
	}

	public void testTheRootListsTheLatestYearFirst() throws Exception {
		album("2023/2023-05-01 May");
		album("2024/2024-05-01 May");
		album("2025/2025-05-01 May");

		assertEquals("The start page opens on the latest year.", Arrays.asList("2025", "2024", "2023"),
			names(listing("/")));
	}

	public void testAYearIsReadThroughLikeAnAlbum() throws Exception {
		album("2025/2025-12-24 Christmas");
		album("2025/Misc");
		album("2025/2025-01-06 Snow");
		album("2025/2025-07 Summer");

		assertEquals("A dated listing starts in January; the undated stays behind by name.",
			Arrays.asList("2025-01-06 Snow", "2025-07 Summer", "2025-12-24 Christmas", "Misc"),
			names(listing("/2025/")));
	}

	public void testAMonthIsReadThroughLikeAnAlbum() throws Exception {
		album("2025/2025-07 July/2025-07-30 Lake");
		album("2025/2025-07 July/2025-07-02 Garden");

		assertEquals(Arrays.asList("2025-07-02 Garden", "2025-07-30 Lake"), names(listing("/2025/2025-07 July/")));
	}

	public void testAnUndatedFolderKeepsTheNewestFirst() throws Exception {
		album("Trips/2019-07-04 Lake");
		album("Trips/Somewhere");
		album("Trips/2023-02-01 Snow");
		album("Trips/2021-08 Sea");

		assertEquals(Arrays.asList("2023-02-01 Snow", "2021-08 Sea", "2019-07-04 Lake", "Somewhere"),
			names(listing("/Trips/")));
	}

	public void testTheListingSidecarKeepsTheDateOfTheName() throws Exception {
		// A year folder that was given a title: its sidecar states no date, its name still does.
		album("2025/2025-12-24 Christmas");
		album("2025/2025-01-06 Snow");
		Files.write(_base.resolve("2025").resolve("index.json"),
			"[\"ListingInfo\",{\"title\":\"The year\"}]".getBytes(StandardCharsets.UTF_8));

		ListingInfo listing = listing("/2025/");
		assertEquals("The year", listing.getTitle());
		assertEquals(Arrays.asList("2025-01-06 Snow", "2025-12-24 Christmas"), names(listing));
	}

	public void testTheOrderIsChosenByTheDateTheFolderSortsByInItsParent() {
		FolderInfo jan = FolderInfo.create().setName("Jan").setEffectiveDate(AlbumDate.ofFolderName("2025-01").millis());
		FolderInfo dec = FolderInfo.create().setName("Dec").setEffectiveDate(AlbumDate.ofFolderName("2025-12").millis());
		FolderInfo old = FolderInfo.create().setName("Old").setEffectiveDate(AlbumDate.ofFolderName("1960").millis());
		FolderInfo none = FolderInfo.create().setName("none");

		// A dated sidecar decides over an undated name, exactly as for the folder's place in its
		// parent.
		AlbumInfo sidecar = AlbumInfo.create().setDate(AlbumDate.ofFolderName("2025").millis());
		List<FolderInfo> folders = new ArrayList<>(Arrays.asList(none, dec, old, jan));
		folders.sort(ResourceCache.listingOrder(AlbumDate.ofFolder(sidecar, "Trip")));
		assertEquals("Oldest first, the undated behind.", Arrays.asList(old, jan, dec, none), folders);

		folders.sort(ResourceCache.listingOrder(AlbumDate.ofFolder(null, "Trip")));
		assertEquals("Newest first; a date before 1970 still before the undated.",
			Arrays.asList(dec, jan, old, none), folders);
	}

	public void testTheRootIsUndatedWhateverItsFolderIsCalled() throws Exception {
		album("2025/2025-01-06 Snow");
		album("2025/2025-12-24 Christmas");
		_servlet.destroy();
		_servlet = new ImageServlet(_base.resolve("2025").toFile());

		assertEquals(Arrays.asList("2025-12-24 Christmas", "2025-01-06 Snow"), names(listing("/")));
	}

	// --- Helpers. ---

	private static List<String> names(ListingInfo listing) {
		return listing.getFolders().stream().map(FolderInfo::getName).collect(Collectors.toList());
	}

	/** An album folder of the given name with one tiny JPEG in it. */
	private void album(String name) throws IOException {
		Path folder = _base.resolve(name);
		Files.createDirectories(folder);
		ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR), "jpg",
			folder.resolve("a.jpg").toFile());
	}

	private ListingInfo listing(String pathInfo) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		_servlet.doGet(TestImageServletPut.request(pathInfo, null, new byte[0], new HashMap<>(), parameters),
			response.response());
		assertEquals("Reading failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		Resource resource = Resource.readResource(
			new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
		assertTrue("Expected a listing, got: " + resource, resource instanceof ListingInfo);
		return (ListingInfo) resource;
	}
}
