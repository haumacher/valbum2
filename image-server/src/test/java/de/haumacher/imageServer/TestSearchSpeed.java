/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import static de.haumacher.imageServer.TestImageServletPut.request;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.SearchAnd;
import de.haumacher.imageServer.shared.model.SearchCriterion;
import de.haumacher.imageServer.shared.model.SearchDate;
import de.haumacher.imageServer.shared.model.SearchPerson;
import de.haumacher.imageServer.shared.model.SearchQuery;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import junit.framework.TestCase;

/**
 * How long a search of issue #227 takes on a generated library, measured rather than guessed.
 *
 * <p>
 * The normal run measures a small library (30 albums of 50 photographs) and only checks that the
 * search answers. <code>-Dvalbum.searchSpeed=true</code> measures the library the issue names, 300
 * albums of 50 photographs, and prints the times: the first search (every album read into the cache
 * from its sidecar), and then a selective and a broad search once everything is cached.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestSearchSpeed extends TestCase {

	private static final int ALBUMS = Integer.getInteger("valbum.searchAlbums", 30).intValue();

	private static final int PHOTOS = 50;

	/** When the first photograph was taken. */
	private static final long START = LocalDate.of(2015, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();

	/** The time between two photographs: five hours. */
	private static final long STEP = 5 * 3_600_000L;

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-search-speed");
		for (int a = 0; a < ALBUMS; a++) {
			Path album = _base.resolve("Year " + (2015 + a % 10)).resolve("Album " + a);
			Files.createDirectories(album);
			StringBuilder parts = new StringBuilder();
			for (int p = 0; p < PHOTOS; p++) {
				String name = "IMG_" + p + ".jpg";
				Files.write(album.resolve(name), new byte[] { (byte) a, (byte) p });
				long date = START + (a * PHOTOS + p) * STEP;
				String tags = "";
				if (p % 4 == 0) {
					tags = ",\"tags\":[{\"x\":0.1,\"y\":0.1,\"w\":0.2,\"h\":0.2,\"person\":\"anne\",\"state\":\"CONFIRMED\"}"
						+ (p % 8 == 0 ? ",{\"x\":0.5,\"y\":0.1,\"w\":0.2,\"h\":0.2,\"person\":\"philipp\",\"state\":\"CONFIRMED\"}" : "")
						+ "]";
				}
				parts.append(p == 0 ? "" : ",").append("[\"ImagePart\",{\"name\":\"").append(name)
					.append("\",\"width\":4000,\"height\":3000,\"date\":").append(date)
					.append(",\"camera\":\"Camera ").append(a % 3).append("\",\"labels\":[{\"name\":\"Label ")
					.append(p % 5).append("\"}],\"comment\":\"Photo ").append(p).append(" of album ").append(a)
					.append("\"").append(tags).append("}]");
			}
			Files.write(album.resolve("index.json"),
				("[\"AlbumInfo\",{\"title\":\"Album " + a + "\",\"parts\":[" + parts + "]}]").getBytes(StandardCharsets.UTF_8));
		}
		_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.OFF, _base));
	}

	@Override
	protected void tearDown() throws Exception {
		_servlet.destroy();
		TestPlaceTags.delete(_base);
		super.tearDown();
	}

	public void testSearchTimes() throws Exception {
		SearchQuery selective = and(SearchPerson.create().setPerson("anne"), SearchPerson.create().setPerson("philipp"),
			// The later half of the library.
			SearchDate.create().setFrom(START + ALBUMS * PHOTOS / 2 * STEP));
		SearchQuery broad = and();
		PathInfo root = new PathInfo(_base);

		// Before the index: every album asked.
		long cold = time(selective);
		long scanned = Long.MAX_VALUE;
		for (int n = 0; n < 2; n++) {
			long begin = System.nanoTime();
			PhotoSearch.find(root, selective, _servlet.cache()::lookup, null);
			scanned = Math.min(scanned, (System.nanoTime() - begin) / 1_000_000);
		}

		// The index, and what it costs.
		SearchIndex index = _servlet.searchIndex();
		long begin = System.nanoTime();
		index.build();
		long built = (System.nanoTime() - begin) / 1_000_000;
		assertTrue(index.isComplete());
		// The heap of an index: a second one built over the same library, the cache left as the
		// first build left it.
		long heapBefore = heap();
		SearchIndex second = new SearchIndex(_base, _servlet.cache()::lookup, () -> -1);
		second.build();
		long heap = heap() - heapBefore;
		assertEquals(ALBUMS * PHOTOS, second.size()[1]);
		second.close();

		long first = time(selective);
		long warm = Long.MAX_VALUE;
		for (int n = 0; n < 3; n++) {
			warm = Math.min(warm, time(selective));
		}
		long all = Long.MAX_VALUE;
		for (int n = 0; n < 3; n++) {
			all = Math.min(all, time(broad));
		}
		long lookup = Long.MAX_VALUE;
		for (int n = 0; n < 3; n++) {
			begin = System.nanoTime();
			PhotoSearch.find(root, selective, _servlet.cache()::lookup, null, index);
			lookup = Math.min(lookup, (System.nanoTime() - begin) / 1_000_000);
		}
		int found = count(selective);
		int everything = count(broad);
		System.out.println("Search over " + ALBUMS + " albums x " + PHOTOS + " photos: without index first " + cold
			+ " ms, scan " + scanned + " ms; index built in " + built + " ms, " + heap / 1024 + " KB heap; then "
			+ found + " matches first " + first + " ms, cached " + warm + " ms (index lookup " + lookup + " ms); all "
			+ everything + " photos " + all + " ms.");
		assertEquals(ALBUMS * PHOTOS, everything);
		assertTrue(found > 0);
		assertEquals(found, PhotoSearch.find(root, selective, _servlet.cache()::lookup, null).size());
	}

	private static long heap() throws InterruptedException {
		Runtime runtime = Runtime.getRuntime();
		for (int n = 0; n < 3; n++) {
			System.gc();
			Thread.sleep(50);
		}
		return runtime.totalMemory() - runtime.freeMemory();
	}

	private long time(SearchQuery query) throws Exception {
		long start = System.nanoTime();
		count(query);
		return (System.nanoTime() - start) / 1_000_000;
	}

	private int count(SearchQuery query) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.SEARCH_ACTION);
		FakeResponse response = new FakeResponse();
		_servlet.doPost(request("/", "application/json", TestSavedSearch.write(query).getBytes(StandardCharsets.UTF_8),
			Map.of(), parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		AlbumInfo album = (AlbumInfo) Resource.readResource(
			new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
		return album.getParts().size();
	}

	private static SearchQuery and(SearchCriterion... criteria) {
		return SearchQuery.create().setVersion(1)
			.setRoot(SearchAnd.create().setCriteria(new ArrayList<>(Arrays.asList(criteria))));
	}
}
