/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.shared.model.DuplicateCopy;
import de.haumacher.imageServer.shared.model.DuplicateGroup;
import de.haumacher.imageServer.shared.model.DuplicateList;
import de.haumacher.imageServer.shared.model.IndexProgress;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Test case for the read-only overview of the photographs in several albums, see issue #220.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestDuplicates extends ShareTestCase {

	private static final String TRIP = "2023-07-01 Trip";

	private static final String BEST = "2023-08-01 Best of";

	private static final String FAMILY = "Family";

	// --- Groups. ---

	public void testAPhotoInTwoAlbumsIsOneGroupNamingBoth() throws Exception {
		byte[] photo = photo("beach");
		album(BEST, "Best of", "2023-08-01", part("beach.jpg", 0, 0), "beach.jpg", photo);
		album(TRIP, "Trip", "2023-07-01", part("IMG_1.jpg", 0, 0), "IMG_1.jpg", photo);
		index();

		DuplicateList list = duplicates(SharingFixture.ALICE);

		assertEquals(1, list.getGroups().size());
		DuplicateGroup group = list.getGroups().get(0);
		assertEquals("The earlier album first.", Arrays.asList(TRIP + "/IMG_1.jpg", BEST + "/beach.jpg"),
			paths(group));
		DuplicateCopy first = group.getCopies().get(0);
		assertEquals("Trip", first.getTitle());
		assertTrue("The album's date is answered.", first.getAlbumDate() != 0);
		assertEquals(64, group.getHash().length());
		assertEquals("The thumbnail of the group is the first copy's photograph.", "IMG_1.jpg",
			group.getImage().getName());
		assertEquals("Nothing of the description but what the thumbnail needs.", "",
			group.getImage().getComment());
		IndexProgress indexed = list.getIndexed();
		assertEquals("A finished index says so.", indexed.getTotal(), indexed.getDone());
	}

	public void testThePhotographsOfTheFixtureAreNoGroup() throws Exception {
		index();

		assertEquals("Every photo of the fixture lies in one album only.", Collections.emptyList(),
			duplicates(SharingFixture.ALICE).getGroups());
	}

	public void testMoreCopiesComeFirst() throws Exception {
		byte[] two = photo("two");
		byte[] three = photo("three");
		album(TRIP, "Trip", "2023-07-01", part("two.jpg", 0, 0) + "," + part("three.jpg", 0, 0), null, null);
		write(TRIP, "two.jpg", two);
		write(TRIP, "three.jpg", three);
		album(BEST, "Best of", "2023-08-01", part("two.jpg", 0, 0) + "," + part("three.jpg", 0, 0), null, null);
		write(BEST, "two.jpg", two);
		write(BEST, "three.jpg", three);
		album(FAMILY, "Family", null, part("three.jpg", 0, 0), "three.jpg", three);
		index();

		List<DuplicateGroup> groups = duplicates(SharingFixture.ALICE).getGroups();

		assertEquals(2, groups.size());
		assertEquals(3, groups.get(0).getCopies().size());
		assertEquals("three.jpg", groups.get(0).getCopies().get(0).getName());
		assertEquals(2, groups.get(1).getCopies().size());
		assertEquals("An album without a date comes last among the copies.", FAMILY,
			groups.get(0).getCopies().get(2).getAlbum());
	}

	public void testAskingMovesAndWritesNothing() throws Exception {
		byte[] photo = photo("beach");
		album(BEST, "Best of", "2023-08-01", part("beach.jpg", 0, 0), "beach.jpg", photo);
		album(TRIP, "Trip", "2023-07-01", part("IMG_1.jpg", 0, 0), "IMG_1.jpg", photo);
		index();
		String before = fingerprint(_base.resolve(TRIP)) + fingerprint(_base.resolve(BEST));

		duplicates(SharingFixture.ALICE);

		assertEquals("Nothing is moved, deleted or rewritten.", before,
			fingerprint(_base.resolve(TRIP)) + fingerprint(_base.resolve(BEST)));
	}

	// --- Visibility per copy. ---

	public void testAPrivateCopyIsAnsweredOnlyToWhoMaySeeIt() throws Exception {
		byte[] photo = photo("secret");
		album(TRIP, "Trip", "2023-07-01", part("a.jpg", 0, 0), "a.jpg", photo);
		album(BEST, "Best of", "2023-08-01", part("b.jpg", 0, 0), "b.jpg", photo);
		album(FAMILY, "Family", null, part("c.jpg", 2, 0), "c.jpg", photo);
		index();

		assertEquals("The administrator sees every copy.", 3,
			duplicates(SharingFixture.ALICE).getGroups().get(0).getCopies().size());
		assertEquals("An editor without the private clearance does not see the private copy.",
			Arrays.asList(TRIP + "/a.jpg", BEST + "/b.jpg"),
			paths(duplicates(SharingFixture.CAROL).getGroups().get(0)));
	}

	/** Review probe: the author's "view as public" preview sees only the public copies. */
	public void testViewAsPublicSeesThePublicCopiesOnly() throws Exception {
		byte[] photo = photo("preview");
		album(TRIP, "Trip", "2023-07-01", part("a.jpg", 0, 0), "a.jpg", photo);
		album(BEST, "Best of", "2023-08-01", part("b.jpg", 0, 0), "b.jpg", photo);
		album(FAMILY, "Family", null, part("c.jpg", 1, 0), "c.jpg", photo);
		index();

		FakeResponse response = get("/", ImageServlet.DUPLICATES_TYPE, SharingFixture.ALICE, "public");
		DuplicateList list = DuplicateList.readDuplicateList(reader(body(response)));
		assertEquals(Arrays.asList(TRIP + "/a.jpg", BEST + "/b.jpg"), paths(list.getGroups().get(0)));
	}

	public void testAGroupWithOneVisibleCopyIsNoGroup() throws Exception {
		byte[] photo = photo("members");
		album(TRIP, "Trip", "2023-07-01", part("a.jpg", 0, 0), "a.jpg", photo);
		album(BEST, "Best of", "2023-08-01", part("b.jpg", 1, 0), "b.jpg", photo);
		index();

		assertEquals(1, duplicates(SharingFixture.DAVE).getGroups().size());
		assertEquals("A member who sees one copy only is told of no group.", Collections.emptyList(),
			duplicates(SharingFixture.EVE).getGroups());
	}

	public void testATrashedCopyIsNoCopyForAnybody() throws Exception {
		byte[] photo = photo("trash");
		album(TRIP, "Trip", "2023-07-01", part("a.jpg", 0, 0), "a.jpg", photo);
		album(BEST, "Best of", "2023-08-01", part("b.jpg", 0, -2), "b.jpg", photo);
		index();

		assertEquals("A photograph rated trash is on its way out, even for its editors.",
			Collections.emptyList(), duplicates(SharingFixture.ALICE).getGroups());
	}

	public void testAnInboxCopyIsAnsweredAsTheInboxShowsIt() throws Exception {
		byte[] bobs = photo("bobs");
		byte[] alices = photo("alices");
		assertEquals(HttpServletResponse.SC_OK, upload("/Inbox/", SharingFixture.BOB, "bob.jpg", bobs).status());
		assertEquals(HttpServletResponse.SC_OK,
			upload("/Inbox/", SharingFixture.ALICE, "alice.jpg", alices).status());
		album(TRIP, "Trip", "2023-07-01", part("bob.jpg", 0, 0) + "," + part("alice.jpg", 0, 0), null, null);
		write(TRIP, "bob.jpg", bobs);
		write(TRIP, "alice.jpg", alices);
		index();

		assertEquals("An editor sees both photographs in the inbox too.", 2,
			duplicates(SharingFixture.CAROL).getGroups().size());
		List<DuplicateGroup> bob = duplicates(SharingFixture.BOB).getGroups();
		assertEquals("A contributor sees their own contribution in the inbox, and nobody else's.", 1, bob.size());
		assertEquals(Arrays.asList(TRIP + "/bob.jpg", "Inbox/bob.jpg"), sorted(paths(bob.get(0))));
		assertEquals("A member who may only look sees no inbox.", Collections.emptyList(),
			duplicates(SharingFixture.DAVE).getGroups());
	}

	// --- A raw and its JPEG, see issue #191. ---

	public void testARawPairIsOnePhotograph() throws Exception {
		byte[] jpeg = RawFixtures.stored(60, 40, 1);
		byte[] raw = RawFixtures.cr2(60, 40, 1);
		write(TRIP, "IMG_7.jpg", jpeg);
		write(TRIP, "IMG_7.CR2", raw);
		write(BEST, "IMG_7.jpg", jpeg);
		write(BEST, "IMG_7.CR2", raw);
		write(FAMILY, "IMG_7.CR2", raw);
		index();

		List<DuplicateGroup> groups = duplicates(SharingFixture.ALICE).getGroups();

		assertEquals("The JPEG's group and the raw's are one photograph: " + groups, 1, groups.size());
		assertEquals("A pair is named by its JPEG, a raw alone by itself.",
			Arrays.asList(TRIP + "/IMG_7.jpg", BEST + "/IMG_7.jpg", FAMILY + "/IMG_7.CR2"),
			(paths(groups.get(0))));
	}

	// --- The index. ---

	public void testWhileTheIndexIsBuildingTheAnswerSaysSo() throws Exception {
		byte[] photo = photo("beach");
		album(BEST, "Best of", "2023-08-01", part("beach.jpg", 0, 0), "beach.jpg", photo);
		album(TRIP, "Trip", "2023-07-01", part("IMG_1.jpg", 0, 0), "IMG_1.jpg", photo);

		DuplicateList list = duplicates(SharingFixture.ALICE);

		assertEquals("Nothing is known yet.", Collections.emptyList(), list.getGroups());
		assertTrue("An unfinished index never reads as a finished one.",
			list.getIndexed().getDone() < list.getIndexed().getTotal());
	}

	// --- Who may ask. ---

	public void testAShareLinkAndAnAnonymousCallerAreRefused() throws Exception {
		index();
		String link = created(share("/" + SharingFixture.ZOO + "/", SharingFixture.ALICE,
			shareBody("Grandma", "", 1, 0, Rights.VIEW))).getToken();

		for (String token : new String[] { link, null }) {
			FakeResponse response = get("/", ImageServlet.DUPLICATES_TYPE, token);
			assertEquals(response.body(), HttpServletResponse.SC_FORBIDDEN, response.status());
			assertEquals(ImageServlet.DUPLICATES_REFUSED, errorMessage(response));
		}
	}

	public void testEveryMemberMayAsk() throws Exception {
		index();
		for (String token : new String[] { SharingFixture.ALICE, SharingFixture.BOB, SharingFixture.CAROL,
			SharingFixture.DAVE, SharingFixture.EVE }) {
			assertEquals(HttpServletResponse.SC_OK, get("/", ImageServlet.DUPLICATES_TYPE, token).status());
		}
	}

	// --- Helpers. ---

	private void index() throws Exception {
		servlet().index().indexNow();
	}

	private DuplicateList duplicates(String token) throws Exception {
		FakeResponse response = get("/", ImageServlet.DUPLICATES_TYPE, token);
		return DuplicateList.readDuplicateList(reader(body(response)));
	}

	private static List<String> paths(DuplicateGroup group) {
		List<String> result = new ArrayList<>();
		for (DuplicateCopy copy : group.getCopies()) {
			result.add(copy.getAlbum() + "/" + copy.getName());
		}
		return result;
	}

	private static List<String> sorted(List<String> list) {
		List<String> result = new ArrayList<>(list);
		Collections.sort(result);
		return result;
	}

	private static String part(String name, int privacy, int rating) {
		return "[\"ImagePart\",{\"name\":\"" + name + "\",\"width\":16,\"height\":16,\"privacy\":" + privacy
			+ ",\"rating\":" + rating + "}]";
	}

	/** An album with the given parts, and the one file given. */
	private void album(String path, String title, String date, String parts, String name, byte[] contents)
			throws Exception {
		Path folder = _base.resolve(path);
		Files.createDirectories(folder);
		String dated = date == null ? ""
			: ",\"date\":" + java.time.LocalDate.parse(date).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
				.toEpochMilli();
		Files.write(folder.resolve("index.json"),
			("[\"AlbumInfo\",{\"title\":\"" + title + "\"" + dated + ",\"parts\":[" + parts + "]}]")
				.getBytes(StandardCharsets.UTF_8));
		if (name != null) {
			write(path, name, contents);
		}
	}

	private void write(String folder, String name, byte[] contents) throws Exception {
		Files.createDirectories(_base.resolve(folder));
		Files.write(_base.resolve(folder).resolve(name), contents);
	}

	/** A tiny JPEG with contents of its own. */
	private static byte[] photo(String seed) throws Exception {
		Random random = new Random(("duplicates/" + seed).hashCode());
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_3BYTE_BGR);
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				image.setRGB(x, y, random.nextInt(0xFFFFFF));
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}
}
