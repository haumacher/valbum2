/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonToken;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import junit.framework.TestCase;

/**
 * Test case for the name a folder is given by its properties, see issue #130.
 *
 * <p>
 * The table is not written here but in {@link #FIXTURE}, because the app composes the very same
 * name with a function of its own (<code>valbum_ui/lib/album_date.dart</code>): one file says what
 * the rule is, and both toolchains are pinned by it, exactly as issue #102 pins the date a file
 * name carries. A row added there must be answered the same way by both.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFolderNames extends TestCase {

	/** The table both toolchains read. */
	public static final File FIXTURE = new File("src/test/fixtures/folder-names.json");

	/** One row of the <code>compose</code> table. */
	public static final class Composition {

		private final String _date;

		private final String _title;

		private final String _name;

		Composition(String date, String title, String name) {
			_date = date;
			_title = title;
			_name = name;
		}

		/** The day of the album as <code>yyyy-MM-dd</code>, <code>null</code> for no date. */
		public String getDate() {
			return _date;
		}

		/** The title of the album. */
		public String getTitle() {
			return _title;
		}

		/** The folder name the two compose. */
		public String getName() {
			return _name;
		}

		/** The explicit date of the album, local midnight of {@link #getDate()}. */
		public long millis() {
			return _date == null ? 0L
				: LocalDate.parse(_date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
		}
	}

	/** One row of the <code>legal</code> table. */
	public static final class Legality {

		private final String _name;

		private final boolean _legal;

		Legality(String name, boolean legal) {
			_name = name;
			_legal = legal;
		}

		/** The composed name. */
		public String getName() {
			return _name;
		}

		/** Whether a folder on disk may carry it. */
		public boolean isLegal() {
			return _legal;
		}
	}

	/** Every row of the table composes exactly the name the table says. */
	public void testTheCompositionTable() throws Exception {
		List<Composition> rows = compositions();
		assertTrue("The fixture table is empty.", rows.size() > 10);
		for (Composition row : rows) {
			assertEquals("date " + row.getDate() + ", title '" + row.getTitle() + "'",
				row.getName(), FolderNames.albumFolderName(row.millis(), row.getTitle()));
		}
	}

	/** Every row of the legality table is judged exactly as the table says. */
	public void testTheLegalityTable() throws Exception {
		List<Legality> rows = legalities();
		assertTrue("The fixture table is empty.", rows.size() > 10);
		for (Legality row : rows) {
			assertEquals("'" + row.getName() + "'", row.isLegal(), FolderNames.isLegal(row.getName()));
		}
	}

	/** The table really holds both kinds of row, so that a broken reader cannot pass it. */
	public void testTheTableHoldsBothKinds() throws Exception {
		int dated = 0;
		int undated = 0;
		for (Composition row : compositions()) {
			if (row.getDate() == null) {
				undated++;
			} else {
				dated++;
			}
		}
		assertTrue("The table must hold dated albums.", dated >= 5);
		assertTrue("The table must hold undated albums.", undated >= 3);

		int legal = 0;
		int illegal = 0;
		for (Legality row : legalities()) {
			if (row.isLegal()) {
				legal++;
			} else {
				illegal++;
			}
		}
		assertTrue("The table must hold names a folder may carry.", legal >= 4);
		assertTrue("The table must hold names it may not.", illegal >= 5);
	}

	/** An album is named by its title and its explicit date, never by the derived one. */
	public void testAnAlbumIsNamedByWhatItSaysAboutItself() throws Exception {
		AlbumInfo album = AlbumInfo.create().setTitle("Schlosspark");
		album.setDate(LocalDate.of(2002, 3, 4).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
		assertEquals("2002-03-04 Schlosspark", FolderNames.of(album));

		AlbumInfo derived = AlbumInfo.create().setTitle("Schlosspark");
		derived.setEffectiveDate(album.getDate());
		assertEquals("A date the server derived is nobody's statement; it never names a folder.",
			"Schlosspark", FolderNames.of(derived));
	}

	/** A folder of folders is named by its title alone; a date on it would be a date nobody set. */
	public void testAFolderOfFoldersIsNamedByItsTitle() throws Exception {
		assertEquals("Reisen", FolderNames.of(ListingInfo.create().setTitle("Reisen")));
	}

	/** A resource without a title composes no name at all; its folder keeps the name it has. */
	public void testNothingToNameAFolderAfter() throws Exception {
		assertEquals("", FolderNames.of(ListingInfo.create()));
		assertEquals("", FolderNames.of(AlbumInfo.create()));
		assertEquals("", FolderNames.of(AlbumInfo.create().setTitle("   ")));
	}

	/** The rows of the <code>compose</code> table, for this test and for {@link TestFolderRenameProbe}. */
	public static List<Composition> compositions() throws Exception {
		List<Composition> result = new ArrayList<>();
		try (JsonReader json = reader()) {
			json.beginObject();
			while (json.hasNext()) {
				if (!"compose".equals(json.nextName())) {
					json.skipValue();
					continue;
				}
				json.beginArray();
				while (json.hasNext()) {
					json.beginObject();
					String date = null;
					String title = null;
					String name = null;
					while (json.hasNext()) {
						String field = json.nextName();
						if (json.peek() == JsonToken.NULL) {
							json.nextNull();
							continue;
						}
						String value = json.nextString();
						if ("date".equals(field)) {
							date = value;
						} else if ("title".equals(field)) {
							title = value;
						} else if ("name".equals(field)) {
							name = value;
						}
					}
					json.endObject();
					assertNotNull("A row without a title in " + FIXTURE, title);
					assertNotNull("A row without a name in " + FIXTURE, name);
					result.add(new Composition(date, title, name));
				}
				json.endArray();
			}
			json.endObject();
		}
		return result;
	}

	/** The rows of the <code>legal</code> table. */
	public static List<Legality> legalities() throws Exception {
		List<Legality> result = new ArrayList<>();
		try (JsonReader json = reader()) {
			json.beginObject();
			while (json.hasNext()) {
				if (!"legal".equals(json.nextName())) {
					json.skipValue();
					continue;
				}
				json.beginArray();
				while (json.hasNext()) {
					json.beginObject();
					String name = null;
					Boolean legal = null;
					while (json.hasNext()) {
						String field = json.nextName();
						if ("name".equals(field)) {
							name = json.nextString();
						} else if ("legal".equals(field)) {
							legal = Boolean.valueOf(json.nextBoolean());
						} else {
							json.skipValue();
						}
					}
					json.endObject();
					assertNotNull("A row without a name in " + FIXTURE, name);
					assertNotNull("A row without a verdict in " + FIXTURE, legal);
					result.add(new Legality(name, legal.booleanValue()));
				}
				json.endArray();
			}
			json.endObject();
		}
		return result;
	}

	private static JsonReader reader() throws Exception {
		assertTrue("Missing fixture: " + FIXTURE.getAbsolutePath(), FIXTURE.exists());
		String contents = new String(Files.readAllBytes(FIXTURE.toPath()), StandardCharsets.UTF_8);
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

}
