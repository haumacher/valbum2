/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import static de.haumacher.imageServer.TestImageServletPut.request;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.index.SpaceIndex;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.SearchAnd;
import de.haumacher.imageServer.shared.model.SearchCriterion;
import de.haumacher.imageServer.shared.model.SearchDate;
import de.haumacher.imageServer.shared.model.SearchLabel;
import de.haumacher.imageServer.shared.model.SearchNot;
import de.haumacher.imageServer.shared.model.SearchPerson;
import de.haumacher.imageServer.shared.model.SearchQuery;
import de.haumacher.imageServer.shared.model.SearchText;
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
import java.util.List;
import java.util.Map;
import junit.framework.TestCase;

/**
 * How long a search of issue #227 takes on a generated library, measured rather than guessed, and
 * what its index costs in heap (issue #264).
 *
 * <p>
 * The normal run measures a small library (30 albums of 50 photographs) and only checks that the
 * search answers. <code>-Dvalbum.searchAlbums=2000</code> measures 100 000 photographs and prints
 * the times: the build of the index (every album read into the cache from its sidecar), the heap
 * the index holds once reopened and asked, and four queries &mdash; selective, broad, a text and a
 * label without a person &mdash; asked of the index alone and of the servlet, which then answers
 * each match as its album does.
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
					.append(p % 10 == 0 ? ", the lakes at sunset" : "").append("\"").append(tags).append("}]");
			}
			Files.write(album.resolve("index.json"),
				("[\"AlbumInfo\",{\"title\":\"Album " + a + "\",\"parts\":[" + parts + "]}]").getBytes(StandardCharsets.UTF_8));
		}
		_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.OFF, _base));
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
		}
		TestPlaceTags.delete(_base);
		super.tearDown();
	}

	public void testSearchTimes() throws Exception {
		SearchQuery selective = and(SearchPerson.create().setPerson("anne"), SearchPerson.create().setPerson("philipp"),
			// The later half of the library.
			SearchDate.create().setFrom(START + ALBUMS * PHOTOS / 2 * STEP));
		SearchQuery broad = and();
		SearchQuery text = and(SearchText.create().setText("Lake"));
		SearchQuery label = and(SearchLabel.create().setLabel("Label 3"),
			SearchNot.create().setCriterion(SearchPerson.create().setPerson("anne")));
		List<SearchQuery> queries = Arrays.asList(selective, broad, text, label);
		List<String> names = Arrays.asList("selective", "broad", "text", "label without person");
		PathInfo root = new PathInfo(_base);

		// The build: every album read into the cache and the index, as the background's first walk.
		long begin = System.nanoTime();
		_servlet.searchIndex().build();
		long built = (System.nanoTime() - begin) / 1_000_000;
		assertEquals(ALBUMS * PHOTOS, _servlet.searchIndex().size()[1]);
		_servlet.destroy();
		_servlet = null;

		// The heap of the index: reopened as after a restart, asked every query, the album cache
		// left out (nothing here reads an album).
		long heapBefore = heap();
		SpaceIndex store = SpaceIndex.open(_base);
		// VALBUM_INDEX_MEMORY in megabytes, see SpaceIndex#setBudget(long).
		long budget = Long.getLong("valbum.indexMemory", 0).longValue() * 1024 * 1024;
		if (budget > 0) {
			store.setBudget(budget);
		}
		SearchIndex index = new SearchIndex(_base, store, path -> null, () -> -1, () -> true);
		int[] counts = new int[queries.size()];
		long[] indexTimes = new long[queries.size()];
		for (int q = 0; q < queries.size(); q++) {
			indexTimes[q] = Long.MAX_VALUE;
			for (int n = 0; n < 5; n++) {
				begin = System.nanoTime();
				counts[q] = PhotoSearch.find(root, queries.get(q), index, null, SearchIndex.Visibility.ALL).size();
				indexTimes[q] = Math.min(indexTimes[q], (System.nanoTime() - begin) / 1_000);
			}
		}
		long heap = heap() - heapBefore;
		// H2 keeps more pages than its cache holds while a search reads them again and again; the
		// index trims it after a search, at most once a second, see SpaceIndex#trimCache().
		Thread.sleep(1100);
		PhotoSearch.find(root, selective, index, null, SearchIndex.Visibility.ALL);
		long rest = heap() - heapBefore;
		assertEquals("Read from disk, not built again.", 0, index.reads());
		index.close();
		store.close();

		// And through the servlet, which answers every match as its album does.
		_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.OFF, _base));
		long[] servletTimes = new long[queries.size()];
		for (int q = 0; q < queries.size(); q++) {
			long first = time(queries.get(q));
			servletTimes[q] = first;
			for (int n = 0; n < 2; n++) {
				servletTimes[q] = Math.min(servletTimes[q], time(queries.get(q)));
			}
			assertEquals(names.get(q), counts[q], count(queries.get(q)));
		}

		StringBuilder report = new StringBuilder("Search over " + ALBUMS + " albums x " + PHOTOS + " photos: index built in "
			+ built + " ms; reopened and asked, " + heap / 1024 + " KB heap, " + rest / 1024 + " KB at rest (budget "
			+ budget(budget) / 1024 + " KB).");
		for (int q = 0; q < queries.size(); q++) {
			report.append("\n  ").append(names.get(q)).append(": ").append(counts[q]).append(" matches, index ")
				.append(indexTimes[q] / 1000.0).append(" ms, servlet ").append(servletTimes[q]).append(" ms");
		}
		System.out.println(report);
		assertEquals(ALBUMS * PHOTOS, counts[1]);
		assertTrue(counts[0] > 0);
		assertEquals("Every tenth photograph speaks of lakes.", ALBUMS * PHOTOS / 10, counts[2]);
		assertTrue(counts[3] > 0);
	}

	private static long budget(long set) {
		return set > 0 ? set : SpaceIndex.defaultBudget();
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
