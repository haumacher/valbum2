/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ShareLink;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.upload.HashCache;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Labels on the photographs of an album, and a share link showing one label, see issue #213.
 *
 * <p>
 * Every scenario stands on the stored form <code>src/test/fixtures/labels/index.json</code>: the
 * zoo album with two sections and a subsection, the label <code>Day</code> on four photographs of
 * every privacy level and rating, the label <code>Party</code> on two, and <code>other.jpg</code>
 * written without the field.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestLabels extends PersonalLinkTestCase {

	private static final File FIXTURE = new File("src/test/fixtures/labels/index.json");

	private static final String DAY = "Day";

	private static final String PARTY = "Party";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		SharingFixture.album(_base, SharingFixture.ZOO, "Zoo", "", "public.jpg", "members.jpg", "private.jpg",
			REJECTED, "other.jpg", "party.jpg");
		Files.copy(FIXTURE.toPath(), zoo().resolve("index.json"), StandardCopyOption.REPLACE_EXISTING);
		restartServer();
	}

	// --- The stored form. ---

	public void testTheStoredFormReadsAndRoundTrips() throws Exception {
		AlbumInfo album = album(get(ZOO, "json", SharingFixture.ALICE));
		assertEquals(Arrays.asList(DAY), labels(image(album, "public.jpg")));
		assertEquals(Arrays.asList(DAY, PARTY), labels(image(album, REJECTED)));
		assertEquals("A part written before the field existed carries none.", Arrays.asList(),
			labels(image(album, "other.jpg")));

		// read -> write -> read is equal.
		assertEquals(HttpServletResponse.SC_OK, put(ZOO, json(album), SharingFixture.ALICE).status());
		AlbumInfo again = album(get(ZOO, "json", SharingFixture.ALICE));
		assertEquals(json(album), json(again));
		String stored = sidecar();
		assertTrue(stored, stored.contains("\"labels\":[{\"name\":\"Day\"},{\"name\":\"Party\"}]"));
	}

	public void testAnAppThatDoesNotKnowLabelsKeepsThem() throws Exception {
		// What an app older than #213 writes back: the album without the field at all.
		String old = new String(Files.readAllBytes(FIXTURE.toPath()), StandardCharsets.UTF_8)
			.replaceAll(",\"labels\":\\[[^\\]]*\\]", "").replace("\"comment\":\"\",\"camera\":\"\",\"tags\":[],\"raw\":\"\"}],[\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"members.jpg\"",
				"\"comment\":\"rotated\",\"camera\":\"\",\"tags\":[],\"raw\":\"\"}],[\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"members.jpg\"");
		assertFalse(old, old.contains("labels"));
		assertEquals(HttpServletResponse.SC_OK, put(ZOO, old, SharingFixture.ALICE).status());

		AlbumInfo album = album(get(ZOO, "json", SharingFixture.ALICE));
		assertEquals("The edit is stored.", "rotated", image(album, "public.jpg").getComment());
		assertEquals("The labels are kept.", Arrays.asList(DAY, PARTY), labels(image(album, REJECTED)));
	}

	public void testAnAppThatKnowsLabelsRemovesThem() throws Exception {
		AlbumInfo album = album(get(ZOO, "json", SharingFixture.ALICE));
		image(album, "public.jpg").setLabels(new ArrayList<>());
		image(album, "other.jpg").addLabel(LabelName.create().setName(PARTY));
		assertEquals(HttpServletResponse.SC_OK, put(ZOO, json(album), SharingFixture.ALICE).status());

		AlbumInfo again = album(get(ZOO, "json", SharingFixture.ALICE));
		assertEquals(Arrays.asList(), labels(image(again, "public.jpg")));
		assertEquals(Arrays.asList(PARTY), labels(image(again, "other.jpg")));
	}

	// --- Rename and remove across the album. ---

	public void testARenameActsOnEveryPhotoAndCarriesTheLinkAlong() throws Exception {
		String token = issueLabeled(DAY, Privacy.PUBLIC, 0);

		FakeResponse response = relabel(ZOO, SharingFixture.ALICE, DAY, "Day with Anna");
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		AlbumInfo answer = album(response);
		assertEquals(Arrays.asList("Day with Anna"), labels(image(answer, "public.jpg")));
		assertEquals("The place among the labels is kept.", Arrays.asList("Day with Anna", PARTY),
			labels(image(answer, REJECTED)));
		assertEquals(Arrays.asList(PARTY), labels(image(answer, "party.jpg")));

		restartServer();
		assertEquals("The link follows the rename.", Arrays.asList("public.jpg"),
			names(album(get("/", "json", token))));
		assertEquals("Day with Anna", new ShareStore(_base).lookup(token).getPhotoLabel());
	}

	public void testARenameOntoALabelThePhotoCarriesKeepsItOnce() throws Exception {
		AlbumInfo answer = album(relabel(ZOO, SharingFixture.ALICE, PARTY, DAY));
		assertEquals(Arrays.asList(DAY), labels(image(answer, REJECTED)));
		assertEquals(Arrays.asList(DAY), labels(image(answer, "party.jpg")));
	}

	public void testARemovalLeavesTheLinkShowingNothing() throws Exception {
		String token = issueLabeled(DAY, Privacy.PUBLIC, 0);

		AlbumInfo answer = album(relabel(ZOO, SharingFixture.ALICE, DAY, ""));
		assertEquals(Arrays.asList(PARTY), labels(image(answer, REJECTED)));
		assertEquals(Arrays.asList(), labels(image(answer, "public.jpg")));
		assertFalse(sidecar().contains("\"Day\""));

		FakeResponse seen = get("/", "json", token);
		assertEquals(seen.body(), HttpServletResponse.SC_OK, seen.status());
		assertEquals("No photo carries the label any more.", Arrays.asList(), names(album(seen)));
		assertEquals(DAY, new ShareStore(_base).lookup(token).getPhotoLabel());
	}

	public void testARelabelIsRefusedToAnybodyButAnEditor() throws Exception {
		String before = sidecar();
		assertEquals(HttpServletResponse.SC_FORBIDDEN, relabel(ZOO, SharingFixture.DAVE, DAY, "x").status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, relabel(ZOO, SharingFixture.BOB, DAY, "x").status());
		FakeResponse link = relabel("/", issueLabeled("", Privacy.PUBLIC, 0, Rights.VIEW, Rights.CONTRIBUTE), DAY, "x");
		assertEquals(HttpServletResponse.SC_FORBIDDEN, link.status());
		assertEquals(Labels.RELABEL_REFUSED, errorMessage(link));
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, relabel(ZOO, null, DAY, "x").status());

		FakeResponse unknown = relabel(ZOO, SharingFixture.ALICE, "Nope", "x");
		assertEquals(HttpServletResponse.SC_NOT_FOUND, unknown.status());
		assertEquals(Labels.nothingLabeled("Nope"), errorMessage(unknown));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, relabel(ZOO, SharingFixture.ALICE, "", "x").status());
		assertEquals(HttpServletResponse.SC_BAD_REQUEST,
			relabel("/" + SharingFixture.YEAR + "/", SharingFixture.ALICE, DAY, "x").status());
		assertEquals("Nothing was written.", before, sidecar());

		assertEquals("An editor may.", HttpServletResponse.SC_OK,
			relabel(ZOO, SharingFixture.CAROL, DAY, "x").status());
	}

	// --- Creating a labeled link. ---

	public void testALabeledLinkIsCreatedListedAndStored() throws Exception {
		FakeResponse response = share(ZOO, SharingFixture.ALICE,
			shareBody("Friends", "", Privacy.PUBLIC, 0, Rights.VIEW).replace("{\"label\"",
				"{\"photoLabel\":\"Day\",\"label\""));
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		ShareLinkCreated created = created(response);
		assertEquals(DAY, created.getLink().getPhotoLabel());

		ShareLink listed = link(links(shares(ZOO, SharingFixture.ALICE)), created.getLink().getId());
		assertEquals(DAY, listed.getPhotoLabel());
		assertTrue(shareStoreContents(), shareStoreContents().contains("\"photoLabel\":\"Day\""));
		assertEquals(Arrays.asList("public.jpg"), names(album(get("/", "json", created.getToken()))));
	}

	public void testALinkWithoutALabelIsStoredAsBefore() throws Exception {
		issueLabeled("", Privacy.PUBLIC, 0);
		assertFalse(shareStoreContents(), shareStoreContents().contains("photoLabel"));
	}

	public void testALabelFilterOnAFolderOfFoldersIsRefused() throws Exception {
		FakeResponse response = share("/" + SharingFixture.YEAR + "/", SharingFixture.ALICE,
			shareBody("Friends", "", Privacy.PUBLIC, 0, Rights.VIEW).replace("{\"label\"",
				"{\"photoLabel\":\"Day\",\"label\""));
		assertEquals(response.body(), HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(Labels.LABEL_NEEDS_ALBUM, errorMessage(response));
		assertFalse(shareStoreContents().contains("Friends"));
	}

	public void testALabelNoPhotoCarriesIsRefused() throws Exception {
		FakeResponse response = share(ZOO, SharingFixture.ALICE,
			shareBody("Friends", "", Privacy.PUBLIC, 0, Rights.VIEW).replace("{\"label\"",
				"{\"photoLabel\":\"Dya\",\"label\""));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(Labels.unknownLabel("Dya"), errorMessage(response));
	}

	// --- What a labeled link shows. ---

	public void testALabeledLinkShowsItsPhotosAndTheirHeadingsOnly() throws Exception {
		String token = issueLabeled(DAY, Privacy.MEMBERS, 0);
		AlbumInfo album = album(get("/", "json", token));
		assertEquals("Privacy and rating still apply on top.", Arrays.asList("public.jpg", "members.jpg"),
			names(album));
		assertEquals("Day 2 and Morning show nothing of the label.", Arrays.asList("Day 1"), headings(album));
		assertEquals("A link is answered no label.", Arrays.asList(), labels(image(album, "public.jpg")));
	}

	public void testTheTrashFloorAppliesOnTop() throws Exception {
		Files.write(zoo().resolve("index.json"), sidecar().replace("\"name\":\"party.jpg\",\"date\":1714550400000,"
			+ "\"width\":4,\"height\":3,\"orientation\":\"IDENTITY\",\"rating\":0",
			"\"name\":\"party.jpg\",\"date\":1714550400000,\"width\":4,\"height\":3,\"orientation\":\"IDENTITY\","
				+ "\"rating\":-2").getBytes(StandardCharsets.UTF_8));
		String token = issueLabeled(PARTY, Privacy.PUBLIC, Ratings.MIN);
		AlbumInfo album = album(get("/", "json", token));
		assertEquals("Every rating, but not the trash.", Arrays.asList(REJECTED), names(album));
		assertEquals(Arrays.asList("Day 1", "Morning"), headings(album));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, get("/party.jpg", "tn", token).status());
	}

	public void testAPhotoWithoutTheLabelDoesNotExistForTheLink() throws Exception {
		String token = issueLabeled(DAY, Privacy.PUBLIC, 0, Rights.VIEW, Rights.DOWNLOAD);
		assertEquals(HttpServletResponse.SC_OK, get("/public.jpg", "tn", token).status());
		assertEquals(HttpServletResponse.SC_OK, get("/public.jpg", null, token).status());
		assertEquals(HttpServletResponse.SC_OK, get("/public.jpg", "json", token).status());

		for (String type : new String[] { "tn", null, "json", "media-url", "video" }) {
			FakeResponse response = get("/other.jpg", type, token);
			assertEquals(type + ": " + response.body(), HttpServletResponse.SC_NOT_FOUND, response.status());
			assertEquals(Labels.NOT_FOUND, errorMessage(response));
		}
		// The answer is the one of a photograph that is not there at all, but for its words.
		assertEquals(HttpServletResponse.SC_NOT_FOUND, get("/nowhere.jpg", "tn", token).status());
		// A labeled photo above the link's limits is refused as before.
		assertEquals(HttpServletResponse.SC_FORBIDDEN, get("/members.jpg", "tn", token).status());

		// The members see everything.
		assertEquals(HttpServletResponse.SC_OK, get(ZOO + "other.jpg", "tn", SharingFixture.DAVE).status());
	}

	public void testTheZipOfALabeledLinkHoldsItsPhotosOnly() throws Exception {
		String token = issueLabeled(DAY, Privacy.PUBLIC, 0, Rights.VIEW, Rights.DOWNLOAD);
		FakeResponse refused = zip("/", token, "public.jpg", "other.jpg");
		assertEquals(refused.body(), HttpServletResponse.SC_NOT_FOUND, refused.status());
		assertEquals(ImageServlet.notInAlbum("other.jpg"), errorMessage(refused));

		FakeResponse zipped = zip("/", token, "public.jpg");
		assertEquals(zipped.body(), HttpServletResponse.SC_OK, zipped.status());
		List<String> entries = new ArrayList<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zipped.bodyBytes()))) {
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				entries.add(entry.getName());
			}
		}
		assertEquals(Arrays.asList("public.jpg"), entries);
	}

	public void testAnIndexPictureWithoutTheLabelGivesWayToALabeledOne() throws Exception {
		Files.write(zoo().resolve("index.json"), sidecar().replace("{\"title\":\"Zoo\",",
			"{\"title\":\"Zoo\",\"indexPicture\":{\"image\":\"other.jpg\"},").getBytes(StandardCharsets.UTF_8));
		String token = issueLabeled(DAY, Privacy.PUBLIC, 0);
		AlbumInfo album = album(get("/", "json", token));
		assertEquals("public.jpg", album.getIndexPicture().getImage());

		// In the listing above, through a link on the year folder without a label, nothing changes.
		AlbumInfo owners = album(get(ZOO, "json", SharingFixture.ALICE));
		assertEquals("other.jpg", owners.getIndexPicture().getImage());
	}

	// --- Headings of a filtered view without labels. ---

	public void testARatingFilteredLinkWithoutLabelsDropsEmptySections() throws Exception {
		String token = issueLabeled("", Privacy.PUBLIC, 0);
		AlbumInfo album = album(get("/", "json", token));
		assertEquals(Arrays.asList("public.jpg", "other.jpg", "party.jpg"), names(album));
		assertEquals("Morning holds nothing the link shows.", Arrays.asList("Day 1", "Day 2"), headings(album));
	}

	public void testTheOwnerKeepsEveryHeading() throws Exception {
		Files.write(zoo().resolve("index.json"), sidecar().replace("\"parts\":[",
			"\"parts\":[[\"Heading\",{\"text\":\"Empty\",\"level\":1}],").getBytes(StandardCharsets.UTF_8));
		AlbumInfo album = album(get(ZOO, "json", SharingFixture.ALICE));
		assertEquals(Arrays.asList("Empty", "Day 1", "Morning", "Day 2"), headings(album));
		assertEquals("The public preview is a filtered view: the empty section drops.",
			Arrays.asList("Day 1", "Morning", "Day 2"),
			headings(album(get(ZOO, "json", SharingFixture.ALICE, "public"))));
	}

	// --- Who sees labels. ---

	public void testMembersSeeLabelsAndNobodyElse() throws Exception {
		assertEquals(Arrays.asList(DAY), labels(image(album(get(ZOO, "json", SharingFixture.DAVE)), "public.jpg")));
		assertEquals(Arrays.asList(DAY),
			labels(image(album(get(ZOO, "json", SharingFixture.ALICE, "members")), "public.jpg")));
		assertEquals(Arrays.asList(),
			labels(image(album(get(ZOO, "json", SharingFixture.ALICE, "public")), "public.jpg")));
		String token = issueLabeled("", Privacy.PUBLIC, 0);
		assertEquals(Arrays.asList(), labels(image(album(get("/", "json", token)), "party.jpg")));
		String single = body(get("/party.jpg", "json", token));
		assertFalse(single, single.contains(PARTY));
		assertTrue(body(get(ZOO + "party.jpg", "json", SharingFixture.DAVE)).contains(PARTY));
	}

	// --- An upload through a labeled link. ---

	public void testAnUploadThroughALabeledLinkCarriesTheLabelAndIsShownByIt() throws Exception {
		String token = issueLabeled(DAY, Privacy.PUBLIC, 0, Rights.VIEW, Rights.CONTRIBUTE);
		FakeResponse upload = upload("/", token, "guest.jpg", photo("guest"));
		assertEquals(upload.body(), HttpServletResponse.SC_OK, upload.status());
		assertTrue(upload.body(), upload.body().contains("\"status\":\"" + ImageServlet.STORED + "\""));

		assertEquals("The uploader sees it through the link at once.", Arrays.asList("public.jpg", "guest.jpg"),
			names(album(get("/", "json", token))));
		assertTrue(hashes(), hashes().contains("\"linkLabel\":\"Day\""));

		restartServer();
		assertEquals(Arrays.asList(DAY),
			labels(image(album(get(ZOO, "json", SharingFixture.ALICE)), "guest.jpg")));
		assertEquals(HttpServletResponse.SC_OK, get("/guest.jpg", "tn", token).status());

		// Stored by the album's first write, and the owner's to change from then on.
		AlbumInfo album = album(get(ZOO, "json", SharingFixture.ALICE));
		image(album, "guest.jpg").setLabels(new ArrayList<>());
		assertEquals(HttpServletResponse.SC_OK, put(ZOO, json(album), SharingFixture.ALICE).status());
		restartServer();
		assertEquals(Arrays.asList(), labels(image(album(get(ZOO, "json", SharingFixture.ALICE)), "guest.jpg")));
	}

	public void testTheLinkLabelIsAppliedOnlyToItsOwnUploads() throws Exception {
		Path folder = zoo();
		HashCache hashes = new HashCache(folder.toFile());
		File file = folder.resolve("other.jpg").toFile();
		hashes.put(file, HashCache.sha256(file), new HashCache.Attribution("token:x", "Friends", -1, Privacy.MEMBERS, DAY));
		hashes.flush();

		ImagePart fresh = ImagePart.create().setName("other.jpg");
		Contributors.applyLinkLimits(Arrays.asList(fresh), folder.toFile());
		assertEquals(Arrays.asList(DAY), labels(fresh));
		Contributors.applyLinkLimits(Arrays.asList(fresh), folder.toFile());
		assertEquals("Once.", Arrays.asList(DAY), labels(fresh));

		ImagePart member = ImagePart.create().setName("party.jpg");
		Contributors.applyLinkLimits(Arrays.asList(member), folder.toFile());
		assertEquals(Arrays.asList(), labels(member));

		// The record survives a read and a write of the hash sidecar.
		assertEquals(DAY, HashCache.recorded(folder.toFile()).get("other.jpg").getLinkLabel());
	}

	// --- Helpers. ---

	private String issueLabeled(String label, int maxPrivacy, int minRating, String... rights) throws Exception {
		ShareStore shares = new ShareStore(_base);
		ShareStore.Issued issued = shares.create("alice", SharingFixture.ZOO, "Friends", "", maxPrivacy, minRating,
			rights.length == 0 ? Arrays.asList(Rights.VIEW) : Arrays.asList(rights));
		shares.setPhotoLabel(issued.getLink(), label);
		restartServer();
		return issued.getToken();
	}

	private FakeResponse relabel(String pathInfo, String token, String from, String to) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.RELABEL_ACTION);
		return post(pathInfo, "{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}", token, parameters);
	}

	private FakeResponse zip(String pathInfo, String token, String... names) throws Exception {
		StringBuilder body = new StringBuilder("{\"target\":\"\",\"names\":[");
		for (int n = 0; n < names.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"name\":\"").append(names[n]).append("\"}");
		}
		body.append("]}");
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.ZIP_ACTION);
		return post(pathInfo, body.toString(), token, parameters);
	}

	private Path zoo() {
		return _base.resolve(SharingFixture.ZOO);
	}

	private String sidecar() throws Exception {
		return new String(Files.readAllBytes(zoo().resolve("index.json")), StandardCharsets.UTF_8);
	}

	private String hashes() throws Exception {
		return new String(Files.readAllBytes(zoo().resolve(HashCache.FILE_NAME)), StandardCharsets.UTF_8);
	}

	private static List<String> labels(ImagePart image) {
		List<String> result = new ArrayList<>();
		for (LabelName name : image.getLabels()) {
			result.add(name.getName());
		}
		return result;
	}

	private static List<String> names(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (ImagePart image : Labels.images(album)) {
			result.add(image.getName());
		}
		return result;
	}

	private static List<String> headings(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof Heading) {
				result.add(((Heading) part).getText());
			}
		}
		return result;
	}

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

	private static String json(Resource resource) throws Exception {
		StringWriter out = new StringWriter();
		try (de.haumacher.msgbuf.json.JsonWriter json =
			new de.haumacher.msgbuf.json.JsonWriter(new de.haumacher.msgbuf.server.io.WriterAdapter(out))) {
			resource.writeTo(json);
		}
		return out.toString();
	}
}
