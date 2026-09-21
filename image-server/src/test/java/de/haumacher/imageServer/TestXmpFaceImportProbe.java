/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.Person;
import de.haumacher.imageServer.shared.model.PersonList;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Review probe of the XMP import (issue #129), composed with the album's first write and with the
 * member link of #128: what an older tool named is a tag like any other from then on.
 */
@SuppressWarnings("javadoc")
public class TestXmpFaceImportProbe extends FacesTestCase {

	public void testImportedTagsSurviveTheFirstWriteAndTheirPersonCanBeAMember() throws Exception {
		createSpace();
		Files.copy(new File(Xmp.FIXTURE).toPath(), album().toPath().resolve("named.jpg"));

		AlbumInfo first = album("/" + ALBUM + "/", _adminToken);
		assertEquals(2, image(first, "named.jpg").getTags().size());
		assertFalse("Nothing was written yet: the import is derived until the album is.",
			new File(album(), "index.json").exists());

		// The album as the app writes it back, the tags riding along.
		FakeResponse stored = put("/" + ALBUM + "/", write(first), _adminToken);
		assertEquals(stored.body(), 200, stored.status());
		String sidecar = new String(Files.readAllBytes(new File(album(), "index.json").toPath()), StandardCharsets.UTF_8);
		assertEquals("Both names are in the sidecar now.", 2, count(sidecar, "\"state\":\"CONFIRMED\""));

		AlbumInfo again = album("/" + ALBUM + "/", _adminToken);
		assertEquals("Read from the sidecar, not imported a second time.", 2, image(again, "named.jpg").getTags().size());
		FakeResponse peopleResponse = get("/", "people", _adminToken);
		PersonList people = PersonList.readPersonList(reader(peopleResponse.body()));
		assertEquals("And not four people.", 2, people.getPeople().size());

		// An imported person is a person: the administrator may say who they are.
		Person alice = people.getPeople().get(0);
		FakeResponse linked = post("/", "link-person",
			"{\"id\":\"" + alice.getId() + "\",\"user\":\"haui\"}", _adminToken);
		assertEquals(linked.body(), 200, linked.status());
		assertEquals("haui", Person.readPerson(reader(linked.body())).getUser());
	}

	private static int count(String text, String needle) {
		int result = 0;
		for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
			result++;
		}
		return result;
	}

	private static String write(AlbumInfo album) throws Exception {
		java.io.StringWriter out = new java.io.StringWriter();
		try (de.haumacher.msgbuf.json.JsonWriter json =
			new de.haumacher.msgbuf.json.JsonWriter(new de.haumacher.msgbuf.server.io.WriterAdapter(out))) {
			album.writeTo(json);
		}
		return out.toString();
	}
}
