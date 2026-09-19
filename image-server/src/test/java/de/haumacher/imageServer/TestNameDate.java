/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonToken;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import junit.framework.TestCase;

/**
 * Test case for the recording time a file name carries, see issue #102.
 *
 * <p>
 * The table is not written here but in {@link #FIXTURE}, because the app derives the same date
 * from the same name with a function of its own: one file says what the rule is, and both
 * toolchains are pinned by it. A name that is added there must therefore be answered the same way
 * by both — that is the whole point of the file.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestNameDate extends TestCase {

	/** The table both toolchains read. */
	public static final File FIXTURE = new File("src/test/fixtures/name-dates.json");

	/** One row of {@link #FIXTURE}. */
	public static final class Row {

		private final String _name;

		private final String _date;

		Row(String name, String date) {
			_name = name;
			_date = date;
		}

		/** The file name the rule is applied to. */
		public String getName() {
			return _name;
		}

		/** The expected local time, <code>null</code> when the name carries no date. */
		public String getDate() {
			return _date;
		}
	}

	/** Every name of the table is answered with exactly the date the table says. */
	public void testTheTable() throws Exception {
		List<Row> rows = rows();
		assertTrue("The fixture table is empty.", rows.size() > 20);
		for (Row row : rows) {
			Date actual = ImageData.nameDate(row.getName());
			if (row.getDate() == null) {
				assertNull("'" + row.getName() + "' carries no date, but " + actual + " was read.", actual);
			} else {
				assertNotNull("'" + row.getName() + "' must carry " + row.getDate() + ".", actual);
				assertEquals("'" + row.getName() + "'", local(row.getDate()), actual);
			}
		}
	}

	/** The table really holds both kinds of row, so that a broken reader cannot pass it. */
	public void testTheTableHoldsPositivesAndNegatives() throws Exception {
		int positive = 0;
		int negative = 0;
		for (Row row : rows()) {
			if (row.getDate() == null) {
				negative++;
			} else {
				positive++;
			}
		}
		assertTrue("The table must hold names that carry a date.", positive >= 15);
		assertTrue("The table must hold names that carry none.", negative >= 10);
	}

	/** A name without any digits at all, and the empty name. */
	public void testNothingToRead() throws Exception {
		assertNull(ImageData.nameDate(""));
		assertNull(ImageData.nameDate("Sonnenuntergang.jpg"));
		assertNull(ImageData.nameDate(null));
	}

	/**
	 * The year after the current one is still a recording time; the one after that is not.
	 *
	 * <p>
	 * Written with the current year, not with a year the test was written in, so that the bound
	 * keeps meaning what it says as the years pass.
	 * </p>
	 */
	public void testTheYearBound() throws Exception {
		int year = LocalDateTime.now().getYear();
		assertNotNull(ImageData.nameDate("VID_" + (year + 1) + "0315_142233.mp4"));
		assertNull(ImageData.nameDate("VID_" + (year + 2) + "0315_142233.mp4"));
		assertNotNull(ImageData.nameDate("VID_19900101_000000.mp4"));
		assertNull(ImageData.nameDate("VID_19891231_235959.mp4"));
	}

	/** The rows of {@link #FIXTURE}, for this test and for {@link TestNameDateProbe}. */
	public static List<Row> rows() throws Exception {
		assertTrue("Missing fixture: " + FIXTURE.getAbsolutePath(), FIXTURE.exists());
		String contents = new String(Files.readAllBytes(FIXTURE.toPath()), StandardCharsets.UTF_8);
		List<Row> result = new ArrayList<>();
		try (JsonReader json = new JsonReader(new ReaderAdapter(new StringReader(contents)))) {
			json.beginArray();
			while (json.hasNext()) {
				json.beginObject();
				String name = null;
				String date = null;
				while (json.hasNext()) {
					String field = json.nextName();
					if (json.peek() == JsonToken.NULL) {
						json.nextNull();
						continue;
					}
					String value = json.nextString();
					if ("name".equals(field)) {
						name = value;
					} else if ("date".equals(field)) {
						date = value;
					}
				}
				json.endObject();
				assertNotNull("A row without a name in " + FIXTURE, name);
				result.add(new Row(name, date));
			}
			json.endArray();
		}
		return result;
	}

	/** The given local wall-clock time, read in the zone the server reads a name date in. */
	public static Date local(String isoLocal) {
		return Date.from(LocalDateTime.parse(isoLocal).atZone(ZoneId.systemDefault()).toInstant());
	}

}
