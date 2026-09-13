/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.GrantStore;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Subjects;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.upload.HashCache;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Who contributed which photo, see issue #53.
 *
 * <p>
 * Attribution is recorded at the upload in the hash sidecar beside the photos, answered as a
 * derived field of every {@link ImagePart}, never stored in <code>index.json</code>, carried along
 * by every move — and it is what lets a contributor take their own contribution back out of an
 * album they may add to but not edit.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestAttribution extends ShareTestCase {

	/** The zoo album in alice's own coordinates. */
	private static final String ZOO = "/" + SharingFixture.ZOO + "/";

	/** The zoo album as somebody else reaches it. */
	private static final String ALICES_ZOO = "/~alice/" + SharingFixture.ZOO + "/";

	/** The album in bob's space he takes his contribution back into. */
	private static final String INBOX = "Inbox";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Files.createDirectories(_base.resolve("bob").resolve(INBOX));
		Files.createDirectories(_base.resolve("alice").resolve("Archive"));
	}

	// --- Recording an attribution. ---

	public void testAMembersContributionNamesTheMember() throws Exception {
		assertUploaded(upload(ALICES_ZOO, SharingFixture.BOB, "bobs.jpg", photo("bobs.jpg")));

		ImagePart image = image(album(get(ZOO, "json", SharingFixture.ALICE)), "bobs.jpg");
		assertEquals("user:bob", image.getContributor());
		assertEquals("bob", image.getContributorLabel());
	}

	public void testTheOwnersOwnUploadCarriesTheOwner() throws Exception {
		assertUploaded(upload(ZOO, SharingFixture.ALICE, "alices.jpg", photo("alices.jpg")));

		ImagePart image = image(album(get(ZOO, "json", SharingFixture.ALICE)), "alices.jpg");
		assertEquals("user:alice", image.getContributor());
		assertEquals("alice", image.getContributorLabel());
	}

	public void testAShareLinksContributionIsNamedByTheLink() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Party", "", Privacy.PUBLIC, 0, Rights.VIEW,
			Rights.CONTRIBUTE);
		assertUploaded(upload("/", token, "guests.jpg", photo("guests.jpg")));

		ImagePart image = image(album(get(ZOO, "json", SharingFixture.ALICE)), "guests.jpg");
		assertEquals("A link's contribution is named by the link, not by a person.",
			Subjects.token(idOf(token)), image.getContributor());
		assertEquals("Party", image.getContributorLabel());
	}

	public void testALinksLabelIsCopiedAndOutlivesTheLink() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Party", "", Privacy.PUBLIC, 0, Rights.VIEW,
			Rights.CONTRIBUTE);
		String id = idOf(token);
		assertUploaded(upload("/", token, "guests.jpg", photo("guests.jpg")));

		assertEquals(HttpServletResponse.SC_OK, unshare(ZOO, SharingFixture.ALICE, id).status());
		restartServer();

		ImagePart image = image(album(get(ZOO, "json", SharingFixture.ALICE)), "guests.jpg");
		assertEquals(Subjects.token(id), image.getContributor());
		assertEquals("A withdrawn link still says who contributed.", "Party", image.getContributorLabel());
	}

	public void testAnAnonymousUploadOnAServerWithoutAuthentication() throws Exception {
		_authMode = AuthMode.OFF;
		restartServer();
		String folder = "/alice/" + SharingFixture.ZOO + "/";
		assertUploaded(upload(folder, null, "someones.jpg", photo("someones.jpg")));

		ImagePart image = image(album(get(folder, "json", null)), "someones.jpg");
		assertEquals(Subjects.ANONYMOUS, image.getContributor());
		assertEquals("", image.getContributorLabel());
	}

	public void testAPhotoThatNeverCameThroughAnUploadHasNoContributor() throws Exception {
		ImagePart image = image(album(get(ZOO, "json", SharingFixture.ALICE)), "public.jpg");

		assertEquals("", image.getContributor());
		assertEquals("", image.getContributorLabel());
	}

	public void testAnUploadThatIsAlreadyPresentKeepsTheFirstContributor() throws Exception {
		byte[] contents = photo("bobs.jpg");
		assertUploaded(upload(ALICES_ZOO, SharingFixture.BOB, "bobs.jpg", contents));

		// carol sends the very same bytes; the server stores nothing and says so.
		FakeResponse again = upload(ALICES_ZOO, SharingFixture.CAROL, "carols-copy.jpg", contents);
		assertEquals(HttpServletResponse.SC_OK, again.status());
		assertTrue("The second upload is recognised as present.",
			again.body().contains("\"status\":\"" + ImageServlet.PRESENT + "\""));

		ImagePart image = image(album(get(ZOO, "json", SharingFixture.ALICE)), "bobs.jpg");
		assertEquals("Whoever brought the photo here is who brought it here.", "user:bob", image.getContributor());
		assertEquals("bob", image.getContributorLabel());
	}

	// --- Derived on the wire, never stored. ---

	public void testTheAppsSidecarNeverKeepsAnAttribution() throws Exception {
		assertUploaded(upload(ALICES_ZOO, SharingFixture.BOB, "bobs.jpg", photo("bobs.jpg")));

		// The album as the app received it, sent straight back: the attribution is in the body.
		String answered = body(get(ZOO, "json", SharingFixture.ALICE));
		assertTrue(answered.contains("\"contributor\":\"user:bob\""));
		assertEquals(HttpServletResponse.SC_OK, put(ZOO, answered, SharingFixture.ALICE).status());

		String stored = sidecar(SharingFixture.ZOO);
		assertFalse("An attribution the server derived is never frozen into the album.",
			stored.contains("user:bob"));
		assertFalse(stored.contains("\"contributorLabel\":\"bob\""));

		assertEquals("The next read answers it from the hashes all the same.", "user:bob",
			image(album(get(ZOO, "json", SharingFixture.ALICE)), "bobs.jpg").getContributor());
	}

	public void testASidecarWithoutTheFieldsIsStoredVerbatim() throws Exception {
		assertUploaded(upload(ALICES_ZOO, SharingFixture.BOB, "bobs.jpg", photo("bobs.jpg")));

		String body = "[\"AlbumInfo\",{\"title\":\"Zoo\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"bobs.jpg\",\"width\":4,\"height\":3}]]}]";
		assertEquals(HttpServletResponse.SC_OK, put(ZOO, body, SharingFixture.ALICE).status());

		assertEquals("A body carrying nothing derived is stored byte for byte.", body, sidecar(SharingFixture.ZOO));
		assertEquals("user:bob", image(album(get(ZOO, "json", SharingFixture.ALICE)), "bobs.jpg").getContributor());
	}

	public void testASidecarClaimingAnAttributionIsNotBelieved() throws Exception {
		String body = "[\"AlbumInfo\",{\"title\":\"Zoo\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"public.jpg\",\"width\":4,\"height\":3,"
			+ "\"contributor\":\"user:dave\",\"contributorLabel\":\"dave\"}]]}]";
		assertEquals(HttpServletResponse.SC_OK, put(ZOO, body, SharingFixture.ALICE).status());

		assertFalse("What a client claims about a contributor is cleared before the write.",
			sidecar(SharingFixture.ZOO).contains("user:dave"));
		assertEquals("Nobody uploaded this photo, so nobody is named.", "",
			image(album(get(ZOO, "json", SharingFixture.ALICE)), "public.jpg").getContributor());
	}

	// --- Moving carries it along. ---

	public void testAttributionTravelsWithAMovedImage() throws Exception {
		assertUploaded(upload(ALICES_ZOO, SharingFixture.BOB, "bobs.jpg", photo("bobs.jpg")));

		moved(move(ZOO, SharingFixture.PUBLIC, SharingFixture.ALICE, "bobs.jpg"), "bobs.jpg");

		ImagePart image = image(album(get("/" + SharingFixture.PUBLIC + "/", "json", SharingFixture.ALICE)),
			"bobs.jpg");
		assertEquals("user:bob", image.getContributor());
		assertEquals("bob", image.getContributorLabel());
	}

	public void testAttributionTravelsWithAMovedFolder() throws Exception {
		assertUploaded(upload(ALICES_ZOO, SharingFixture.BOB, "bobs.jpg", photo("bobs.jpg")));

		moved(move("/" + SharingFixture.YEAR + "/", "Archive", SharingFixture.ALICE, "2024-05-01 Zoo"),
			"2024-05-01 Zoo");

		ImagePart image = image(album(get("/Archive/2024-05-01 Zoo/", "json", SharingFixture.ALICE)), "bobs.jpg");
		assertEquals("A folder moves by one rename; its sidecars ride along.", "user:bob", image.getContributor());
	}

	public void testAttributionTravelsWithAMovedGroup() throws Exception {
		assertUploaded(upload(ALICES_ZOO, SharingFixture.BOB, "one.jpg", photo("one.jpg")));
		assertUploaded(upload(ALICES_ZOO, SharingFixture.BOB, "two.jpg", photo("two.jpg")));
		group("one.jpg", "two.jpg");
		System.out.println("DBG hashes: " + new String(Files.readAllBytes(_base.resolve("alice").resolve(SharingFixture.ZOO).resolve(HashCache.FILE_NAME))));
		System.out.println("DBG sidecar: " + sidecar(SharingFixture.ZOO));

		moved(move(ZOO, SharingFixture.PUBLIC, SharingFixture.ALICE, "one.jpg"), "one.jpg");

		AlbumInfo album = album(get("/" + SharingFixture.PUBLIC + "/", "json", SharingFixture.ALICE));
		assertEquals("user:bob", image(album, "one.jpg").getContributor());
		assertEquals("The member that travelled on the group's back keeps its contributor, too.", "user:bob",
			image(album, "two.jpg").getContributor());
	}

	// --- Taking a contribution back. ---

	public void testAContributorTakesTheirOwnContributionBack() throws Exception {
		assertUploaded(upload(ALICES_ZOO, SharingFixture.BOB, "bobs.jpg", photo("bobs.jpg")));

		FakeResponse response = move(ALICES_ZOO, "~bob/" + INBOX, SharingFixture.BOB, "bobs.jpg");

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertEquals("", outcome(moveResult(response), "bobs.jpg").getMessage());
		assertFalse("The photo left the album it was contributed to.",
			Files.exists(_base.resolve("alice/" + SharingFixture.ZOO + "/bobs.jpg")));
		assertTrue("Taking back is a rename, never a delete.",
			Files.exists(_base.resolve("bob/" + INBOX + "/bobs.jpg")));
		assertEquals("The record travels with the file.", "user:bob",
			HashCache.recorded(_base.resolve("bob").resolve(INBOX).toFile()).get("bobs.jpg").getContributor());
		assertFalse("And it no longer stands in the album it left.",
			HashCache.recorded(_base.resolve("alice").resolve(SharingFixture.ZOO).toFile())
				.containsKey("bobs.jpg"));
	}

	public void testAContributorMovesNobodyElsesPhoto() throws Exception {
		FakeResponse response = move(ALICES_ZOO, "~bob/" + INBOX, SharingFixture.BOB, "public.jpg");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(MoveService.CONTRIBUTION_REFUSED, errorMessage(response));
		assertTrue(Files.exists(_base.resolve("alice/" + SharingFixture.ZOO + "/public.jpg")));
	}

	public void testAMixedSelectionMovesNothingAtAll() throws Exception {
		assertUploaded(upload(ALICES_ZOO, SharingFixture.BOB, "bobs.jpg", photo("bobs.jpg")));

		FakeResponse response =
			move(ALICES_ZOO, "~bob/" + INBOX, SharingFixture.BOB, "bobs.jpg", "public.jpg");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(MoveService.CONTRIBUTION_REFUSED, errorMessage(response));
		assertTrue("Not even the contributor's own photo moves.",
			Files.exists(_base.resolve("alice/" + SharingFixture.ZOO + "/bobs.jpg")));
		assertTrue(Files.exists(_base.resolve("alice/" + SharingFixture.ZOO + "/public.jpg")));
	}

	public void testAFolderIsNobodysContribution() throws Exception {
		new GrantStore(_base).grant("alice", SharingFixture.YEAR, Subjects.user("bob"),
			Collections.singletonList(Rights.CONTRIBUTE));
		restartServer();

		FakeResponse response = move("/~alice/" + SharingFixture.YEAR + "/", "~bob/" + INBOX,
			SharingFixture.BOB, "2024-05-01 Zoo");

		assertEquals("An album is nobody's contribution, however much is in it.",
			HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(MoveService.CONTRIBUTION_REFUSED, errorMessage(response));
		assertTrue(Files.isDirectory(_base.resolve("alice/" + SharingFixture.ZOO)));
	}

	public void testAGroupTravelsWholeSoEveryMemberMustBeTheContributorsOwn() throws Exception {
		assertUploaded(upload(ALICES_ZOO, SharingFixture.BOB, "one.jpg", photo("one.jpg")));
		assertUploaded(upload(ZOO, SharingFixture.ALICE, "two.jpg", photo("two.jpg")));
		group("one.jpg", "two.jpg");

		FakeResponse response = move(ALICES_ZOO, "~bob/" + INBOX, SharingFixture.BOB, "one.jpg");

		assertEquals("The whole group would travel, and half of it is alice's.",
			HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(MoveService.CONTRIBUTION_REFUSED, errorMessage(response));
		assertTrue(Files.exists(_base.resolve("alice/" + SharingFixture.ZOO + "/one.jpg")));
		assertTrue(Files.exists(_base.resolve("alice/" + SharingFixture.ZOO + "/two.jpg")));
	}

	public void testAShareLinkCannotTakeItsContributionBack() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Party", "", Privacy.PUBLIC, 0, Rights.VIEW,
			Rights.CONTRIBUTE);
		assertUploaded(upload("/", token, "guests.jpg", photo("guests.jpg")));

		FakeResponse response = move("/", "", token, "guests.jpg");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.SHARE_MOVE_REFUSED, errorMessage(response));
		assertTrue(Files.exists(_base.resolve("alice/" + SharingFixture.ZOO + "/guests.jpg")));
	}

	public void testAGuestCannotTakeAContributionHome() throws Exception {
		new GrantStore(_base).grant("alice", SharingFixture.ZOO, Subjects.user("eve"),
			Collections.singletonList(Rights.CONTRIBUTE));
		restartServer();
		assertUploaded(upload(ALICES_ZOO, SharingFixture.EVE, "eves.jpg", photo("eves.jpg")));

		FakeResponse response = move(ALICES_ZOO, "~eve", SharingFixture.EVE, "eves.jpg");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals("A guest's root is photo-free, so there is nowhere to take it.",
			AuthService.GUEST_SPACE_REFUSED, errorMessage(response));
		assertTrue(Files.exists(_base.resolve("alice/" + SharingFixture.ZOO + "/eves.jpg")));
	}

	public void testTheOwnersLibraryIsExactlyWhatItWasBesidesThePhotoAndTheHashes() throws Exception {
		Path alice = _base.resolve("alice");
		Map<String, String> photosBefore = photoContents(alice);
		TreeSet<String> filesBefore = fileNames(alice);

		assertUploaded(upload(ALICES_ZOO, SharingFixture.BOB, "bobs.jpg", photo("bobs.jpg")));

		TreeSet<String> uploaded = new TreeSet<>(filesBefore);
		uploaded.add(SharingFixture.ZOO + "/bobs.jpg");
		uploaded.add(SharingFixture.ZOO + "/" + HashCache.FILE_NAME);
		assertEquals("An upload adds the photo and the hash sidecar, and writes nothing else.", uploaded,
			fileNames(alice));
		assertUntouched(photosBefore, photoContents(alice));

		assertEquals(HttpServletResponse.SC_OK,
			move(ALICES_ZOO, "~bob/" + INBOX, SharingFixture.BOB, "bobs.jpg").status());

		TreeSet<String> takenBack = new TreeSet<>(filesBefore);
		takenBack.add(SharingFixture.ZOO + "/" + HashCache.FILE_NAME);
		// Taking the photo back rewrites the album's index, which keeps its predecessor as a
		// timestamped backup, exactly as every other sidecar write does.
		takenBack.add(SharingFixture.ZOO + "/index.json~");
		assertEquals("The photo is gone, and nothing of the owner's went with it.", takenBack, fileNames(alice));
		assertEquals("Every photo of the owner's is still there, byte for byte.", photosBefore,
			photoContents(alice));
	}

	/** Asserts that every photo of the given fingerprint is still there with the same contents. */
	private static void assertUntouched(Map<String, String> before, Map<String, String> now) {
		for (Map.Entry<String, String> photo : before.entrySet()) {
			assertEquals("The photo '" + photo.getKey() + "' is not what it was.", photo.getValue(),
				now.get(photo.getKey()));
		}
	}

	// --- Helpers. ---

	/**
	 * A small JPEG whose contents nothing else in the fixture has.
	 *
	 * <p>
	 * Noise rather than a single coloured pixel: an upload of contents the folder already holds is
	 * reported as present and stores nothing, and two tiny flat images compress to the very same
	 * bytes far too easily.
	 * </p>
	 */
	private static byte[] photo(String seed) throws Exception {
		Random random = new Random(("attribution/" + seed).hashCode());
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

	private static void assertUploaded(FakeResponse response) {
		assertEquals("Expected a stored upload, got: " + response.body(), HttpServletResponse.SC_OK,
			response.status());
		assertTrue("Expected a stored upload, got: " + response.body(),
			response.body().contains("\"status\":\"" + ImageServlet.STORED + "\""));
	}

	private static void moved(FakeResponse response, String name) throws Exception {
		assertEquals("Expected a successful move, got: " + response.body(), HttpServletResponse.SC_OK,
			response.status());
		assertEquals("", outcome(moveResult(response), name).getMessage());
	}

	/** The image of the given name in the album, group members included. */
	private static ImagePart image(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return (ImagePart) part;
			}
			if (part instanceof ImageGroup) {
				for (ImagePart member : ((ImageGroup) part).getImages()) {
					if (member.getName().equals(name)) {
						return member;
					}
				}
			}
		}
		fail("No image '" + name + "' in " + album.getParts());
		return null;
	}

	/** Replaces the zoo album's sidecar with one holding the two named photos as a group. */
	private void group(String first, String second) throws Exception {
		String body = "[\"AlbumInfo\",{\"title\":\"Zoo\",\"parts\":[[\"ImageGroup\",{\"representative\":0,"
			+ "\"images\":[{\"name\":\"" + first + "\",\"width\":4,\"height\":3},"
			+ "{\"name\":\"" + second + "\",\"width\":4,\"height\":3}]}]]}]";
		assertEquals(HttpServletResponse.SC_OK, put(ZOO, body, SharingFixture.ALICE).status());
	}

	/** The <code>index.json</code> of the folder at the given path below alice's space. */
	private String sidecar(String path) throws Exception {
		return new String(Files.readAllBytes(_base.resolve("alice").resolve(path).resolve("index.json")),
			StandardCharsets.UTF_8);
	}

	/**
	 * The names of every file below the given root, with a timestamped sidecar backup normalised
	 * to <code>index.json~</code> so that a set can be compared.
	 */
	private static TreeSet<String> fileNames(Path root) throws Exception {
		TreeSet<String> result = new TreeSet<>();
		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(Files::isRegularFile).forEach(file -> {
				String name = root.relativize(file).toString().replace(File.separatorChar, '/');
				result.add(name.replaceAll("index\\.json\\.\\d+$", "index.json~"));
			});
		}
		return result;
	}

	/** The contents of every photo below the given root, by name. */
	private static Map<String, String> photoContents(Path root) throws Exception {
		Map<String, String> result = new TreeMap<>();
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path file : walk.filter(Files::isRegularFile).toArray(Path[]::new)) {
				String name = root.relativize(file).toString().replace(File.separatorChar, '/');
				if (name.endsWith(".jpg")) {
					result.put(name, HashCache.sha256(file.toFile()));
				}
			}
		}
		return new LinkedHashMap<>(result);
	}
}
