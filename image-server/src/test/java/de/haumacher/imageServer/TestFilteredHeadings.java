/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import junit.framework.TestCase;

/**
 * The headings of a filtered view, see issue #213 and {@link FilteredHeadings}.
 *
 * <p>
 * Pinned by the table <code>src/test/fixtures/filtered-headings.json</code>, which the app's
 * <code>test/filtered_headings_test.dart</code> reads too: every row lists the parts of an album —
 * <code>H&lt;level&gt; &lt;text&gt;</code> a heading, <code>+</code> a photograph the view shows,
 * <code>-</code> one it hides — and the headings the view shows.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFilteredHeadings extends TestCase {

	/** The table both toolchains read. */
	public static final File FIXTURE = new File("src/test/fixtures/filtered-headings.json");

	public void testTheSharedTable() throws Exception {
		int rows = 0;
		for (Row row : rows()) {
			List<AlbumPart> shown = new ArrayList<>();
			for (String token : row.parts) {
				if (token.equals("+")) {
					shown.add(ImagePart.create().setName("p" + shown.size() + ".jpg"));
				} else if (!token.equals("-")) {
					shown.add(heading(token));
				}
			}
			List<String> actual = new ArrayList<>();
			for (AlbumPart part : FilteredHeadings.prune(shown)) {
				if (part instanceof Heading) {
					actual.add(((Heading) part).getText());
				}
			}
			assertEquals(row.name, row.shown, actual);
			rows++;
		}
		assertTrue("The table must hold its cases.", rows >= 10);
	}

	public void testAGroupIsAPhotograph() {
		List<AlbumPart> parts = Arrays.asList(Heading.create().setText("A").setLevel(1),
			ImageGroup.create().addImage(ImagePart.create().setName("a.jpg")));
		assertSame("Nothing dropped is the list itself.", parts, FilteredHeadings.prune(parts));
	}

	private static Heading heading(String token) {
		int blank = token.indexOf(' ');
		assertTrue(token, token.startsWith("H") && blank > 1);
		return Heading.create().setLevel(Integer.parseInt(token.substring(1, blank))).setText(token.substring(blank + 1));
	}

	private static final class Row {
		String name;

		List<String> parts = new ArrayList<>();

		List<String> shown = new ArrayList<>();
	}

	private static List<Row> rows() throws Exception {
		assertTrue("Missing fixture: " + FIXTURE.getAbsolutePath(), FIXTURE.exists());
		String contents = new String(Files.readAllBytes(FIXTURE.toPath()), StandardCharsets.UTF_8);
		List<Row> result = new ArrayList<>();
		try (JsonReader json = new JsonReader(new ReaderAdapter(new StringReader(contents)))) {
			json.beginArray();
			while (json.hasNext()) {
				Row row = new Row();
				json.beginObject();
				while (json.hasNext()) {
					String field = json.nextName();
					if ("case".equals(field)) {
						row.name = json.nextString();
					} else {
						List<String> list = "parts".equals(field) ? row.parts : row.shown;
						json.beginArray();
						while (json.hasNext()) {
							list.add(json.nextString());
						}
						json.endArray();
					}
				}
				json.endObject();
				result.add(row);
			}
			json.endArray();
		}
		return result;
	}
}
