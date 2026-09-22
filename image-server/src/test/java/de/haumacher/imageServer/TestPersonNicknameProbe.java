/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.Person;
import de.haumacher.imageServer.shared.model.PersonList;

/**
 * Probe of issue #146 composed with #125's merge and a restart: two people sharing a nickname,
 * the survivor of a merge keeping theirs, a nickname cleared by the empty bracket, and the
 * register surviving a restart with both fields.
 */
@SuppressWarnings("javadoc")
public class TestPersonNicknameProbe extends FacesTestCase {

	public void testTwoOmasMergeRenameAndRestart() throws Exception {
		createSpace();

		Person anna = created("Anna Schmidt (Oma)");
		Person berta = created("Berta Müller (Oma)");
		Person tante = created("Berta Müller-Schmidt (Tante Berta)");
		assertEquals("Oma", anna.getNickname());
		assertEquals("Oma", berta.getNickname());
		assertEquals("Berta Müller-Schmidt", tante.getName());

		// The nickname is no name: a third "Oma" as a canonical name is free too.
		Person plainOma = created("Oma");
		assertEquals("Oma", plainOma.getName());
		assertEquals("", plainOma.getNickname());

		// Merging Berta into Anna: the survivor keeps their own nickname.
		Person survivor = merged(anna.getId(), berta.getId());
		assertEquals("Anna Schmidt", survivor.getName());
		assertEquals("Oma", survivor.getNickname());

		// Merging a nicknamed person into one without: the survivor takes it.
		Person taker = merged(plainOma.getId(), tante.getId());
		assertEquals("Oma", taker.getName());
		assertEquals("Tante Berta", taker.getNickname());

		// The empty bracket clears the nickname and keeps the name.
		Person cleared = renamed(survivor.getId(), "Anna Schmidt ()");
		assertEquals("Anna Schmidt", cleared.getName());
		assertEquals("", cleared.getNickname());

		// A canonical name taken by another person's *nickname* is not taken.
		Person tanteBerta = created("Tante Berta");
		assertEquals("Tante Berta", tanteBerta.getName());

		restart();
		PersonList people = people(_adminToken);
		assertEquals(3, people.getPeople().size());
		for (Person person : people.getPeople()) {
			if (person.getId().equals(taker.getId())) {
				assertEquals("Tante Berta", person.getNickname());
			}
			if (person.getId().equals(survivor.getId())) {
				assertEquals("", person.getNickname());
			}
		}
	}

	private Person created(String name) throws Exception {
		FakeResponse response = post("/", "create-person", "{\"name\":\"" + name + "\"}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return Person.readPerson(reader(response.body()));
	}

	private Person renamed(String id, String name) throws Exception {
		FakeResponse response = post("/", "rename-person",
			"{\"id\":\"" + id + "\",\"name\":\"" + name + "\"}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return Person.readPerson(reader(response.body()));
	}

	private Person merged(String into, String from) throws Exception {
		FakeResponse response = post("/", "merge-persons",
			"{\"into\":\"" + into + "\",\"from\":\"" + from + "\"}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return Person.readPerson(reader(response.body()));
	}

	private PersonList people(String token) throws Exception {
		FakeResponse response = get("/", "people", token);
		assertEquals(response.body(), 200, response.status());
		return PersonList.readPersonList(reader(response.body()));
	}
}
