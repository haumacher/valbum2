/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FaceTag;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Test case for issue #296: what the caller of a sidecar <code>PUT</code> was not answered is not
 * theirs to write.
 *
 * <p>
 * The zoo album of the {@link SharingFixture} is written anew here with everything an answer can
 * leave out for one caller and not for another: labels and face tags (answered to members alone),
 * photographs above a clearance (<code>members.jpg</code>, <code>private.jpg</code>, a private
 * member of a group), a photograph below a rating floor (<code>rejected.jpg</code>), a section
 * whose photographs are all private (the heading <code>Night</code>), a private album picture and a
 * video with a chosen poster.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestPutKeepsWhatIsNotAnswered extends ShareTestCase {

	private static final String CROWD = "crowd.jpg";

	private static final String HIDDEN = "hidden.jpg";

	private static final String VIDEO = "clip.mp4";

	private static final String TAG = "{\"x\":0.1,\"y\":0.1,\"w\":0.2,\"h\":0.2,\"person\":\"anna\",\"state\":\"CONFIRMED\"}";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		SharingFixture.album(_base, SharingFixture.ZOO, "Zoo",
			"[\"Heading\",{\"text\":\"Day\",\"level\":1}],"
				+ "[\"ImagePart\",{\"name\":\"public.jpg\",\"width\":4,\"height\":3,\"comment\":\"Gate\","
				+ "\"labels\":[{\"name\":\"Day\"}],\"tags\":[" + TAG + "]}],"
				+ "[\"ImagePart\",{\"name\":\"members.jpg\",\"width\":4,\"height\":3,\"privacy\":1,"
				+ "\"labels\":[{\"name\":\"Day\"}]}],"
				+ "[\"ImageGroup\",{\"representative\":1,\"images\":["
				+ "{\"name\":\"" + CROWD + "\",\"width\":4,\"height\":3},"
				+ "{\"name\":\"" + HIDDEN + "\",\"width\":4,\"height\":3,\"privacy\":2,"
				+ "\"comment\":\"Behind\"}]}],"
				+ "[\"ImagePart\",{\"name\":\"" + VIDEO + "\",\"kind\":\"VIDEO\",\"width\":4,\"height\":3,"
				+ "\"poster\":{\"millis\":1200}}],"
				+ "[\"Heading\",{\"text\":\"Night\",\"level\":1}],"
				+ "[\"ImagePart\",{\"name\":\"private.jpg\",\"width\":4,\"height\":3,\"privacy\":2,"
				+ "\"comment\":\"Secret\",\"labels\":[{\"name\":\"Night\"}],\"tags\":[" + TAG + "]}],"
				+ "[\"ImagePart\",{\"name\":\"" + REJECTED + "\",\"width\":4,\"height\":3,\"rating\":-1,"
				+ "\"comment\":\"Blurred\"}]",
			"public.jpg", "members.jpg", CROWD, HIDDEN, VIDEO, "private.jpg", REJECTED);
		// The album picture is a private photograph.
		Path sidecar = _base.resolve(SharingFixture.ZOO).resolve("index.json");
		String contents = new String(Files.readAllBytes(sidecar), StandardCharsets.UTF_8);
		Files.write(sidecar, contents.replace("{\"title\":\"Zoo\",",
			"{\"title\":\"Zoo\",\"indexPicture\":{\"image\":\"private.jpg\"},").getBytes(StandardCharsets.UTF_8));
		restartServer();
	}

	// --- Fields stripped from the answer. ---

	public void testALinkKeepsTheLabelsAndTheFaceTags() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Grandma", "", Privacy.PRIVATE, Ratings.MIN, Rights.EDIT);
		AlbumInfo answer = album(get("/", "json", token));
		assertTrue("A link is answered no label.", image(answer, "public.jpg").getLabels().isEmpty());
		assertTrue("A link is answered no face tag.", image(answer, "public.jpg").getTags().isEmpty());

		image(answer, "public.jpg").setComment("The gate");
		assertOk(put("/", write(answer), token));

		AlbumInfo stored = stored();
		assertEquals("The gate", image(stored, "public.jpg").getComment());
		assertEquals(Arrays.asList("Day"), labels(image(stored, "public.jpg")));
		assertEquals(Arrays.asList("Day"), labels(image(stored, "members.jpg")));
		assertEquals(Arrays.asList("Night"), labels(image(stored, "private.jpg")));
		assertEquals(1, image(stored, "public.jpg").getTags().size());
		assertEquals("anna", image(stored, "public.jpg").getTags().get(0).getPerson());
		assertEquals(1, image(stored, "private.jpg").getTags().size());
	}

	public void testALinkWritesNoLabelOrTagItWasNotAnswered() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Grandma", "", Privacy.PRIVATE, Ratings.MIN, Rights.EDIT);
		AlbumInfo answer = album(get("/", "json", token));
		image(answer, "public.jpg").setLabels(new ArrayList<>(Arrays.asList(LabelName.create().setName("Mine"))));
		image(answer, CROWD).addTag(FaceTag.create().setX(0.5).setY(0.5).setW(0.1).setH(0.1).setPerson("ben"));
		assertOk(put("/", write(answer), token));

		AlbumInfo stored = stored();
		assertEquals(Arrays.asList("Day"), labels(image(stored, "public.jpg")));
		assertTrue(image(stored, CROWD).getTags().isEmpty());
	}

	/** Probe: a link showing one label keeps every photograph without it, unchanged. */
	public void testALabelledLinkKeepsWhatItDoesNotShow() throws Exception {
		de.haumacher.imageServer.auth.ShareStore shares = new de.haumacher.imageServer.auth.ShareStore(_base);
		de.haumacher.imageServer.auth.ShareStore.Issued issued = shares.create("alice", SharingFixture.ZOO, "Grandma", "", Privacy.PRIVATE,
			Ratings.MIN, Arrays.asList(Rights.VIEW, Rights.EDIT));
		shares.setPhotoLabel(issued.getLink(), "Day");
		restartServer();
		String token = issued.getToken();
		AlbumInfo answer = album(get("/", "json", token));
		assertEquals("The link shows the photographs labelled Day alone.", 0, count(answer, CROWD));
		image(answer, "public.jpg").setComment("The gate");
		assertOk(put("/", write(answer), token));

		AlbumInfo stored = stored();
		assertEquals("The gate", image(stored, "public.jpg").getComment());
		assertEquals("Secret", image(stored, "private.jpg").getComment());
		assertEquals("Blurred", image(stored, REJECTED).getComment());
		assertEquals(1, count(stored, CROWD));
		assertEquals(1, count(stored, HIDDEN));
		assertEquals(1, count(stored, VIDEO));
		assertEquals(1200, image(stored, VIDEO).getPoster().getMillis());
	}

	// --- Photographs hidden from the answer. ---

	public void testAnEditorBelowPrivateKeepsThePrivatePhotographs() throws Exception {
		// carol may edit everything and is not shown what is private.
		AlbumInfo answer = album(get(zoo(), "json", SharingFixture.CAROL));
		assertNull(Crops.findImage(answer, "private.jpg"));
		assertNull(Crops.findImage(answer, HIDDEN));

		image(answer, "public.jpg").setComment("The gate");
		assertOk(put(zoo(), write(answer), SharingFixture.CAROL));

		assertKept(stored(), "private.jpg", HIDDEN);
		assertEquals("The gate", image(stored(), "public.jpg").getComment());
		assertEquals("carol is a member: she was answered the labels and wrote them back.", Arrays.asList("Day"),
			labels(image(stored(), "public.jpg")));
	}

	public void testALinkBelowPrivateKeepsThePrivatePhotographs() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Grandma", "", Privacy.MEMBERS, Ratings.MIN, Rights.EDIT);
		AlbumInfo answer = album(get("/", "json", token));
		assertNull(Crops.findImage(answer, "private.jpg"));

		image(answer, "public.jpg").setComment("The gate");
		assertOk(put("/", write(answer), token));

		AlbumInfo stored = stored();
		assertKept(stored, "private.jpg", HIDDEN);
		assertEquals("The gate", image(stored, "public.jpg").getComment());
	}

	public void testAHiddenPhotographIsNotChangedByNamingIt() throws Exception {
		AlbumInfo answer = album(get(zoo(), "json", SharingFixture.CAROL));
		// carol writes the private photograph she was not shown, made public.
		answer.addPart(ImagePart.create().setName("private.jpg").setWidth(4).setHeight(3).setComment("Mine"));
		assertOk(put(zoo(), write(answer), SharingFixture.CAROL));

		AlbumInfo stored = stored();
		assertKept(stored, "private.jpg", HIDDEN);
		assertEquals("Only once.", 1, count(stored, "private.jpg"));
	}

	public void testALinkWithARatingFloorKeepsWhatIsBelowIt() throws Exception {
		String token = zooToken(Rights.VIEW, Rights.EDIT);
		AlbumInfo answer = album(get("/", "json", token));
		assertNull(Crops.findImage(answer, REJECTED));
		assertNull(Crops.findImage(answer, "members.jpg"));

		image(answer, "public.jpg").setComment("The gate");
		assertOk(put("/", write(answer), token));

		AlbumInfo stored = stored();
		assertKept(stored, "private.jpg", HIDDEN);
		ImagePart rejected = image(stored, REJECTED);
		assertEquals(-1, rejected.getRating());
		assertEquals("Blurred", rejected.getComment());
		ImagePart members = image(stored, "members.jpg");
		assertEquals(Privacy.MEMBERS, members.getPrivacy());
		assertEquals(Arrays.asList("Day"), labels(members));
		assertEquals("The order is the album's: members.jpg stays after public.jpg.",
			Arrays.asList("Day", "public.jpg", "members.jpg", "[crowd.jpg, hidden.jpg]", VIDEO, "Night", "private.jpg",
				REJECTED),
			arrangement(stored));
	}

	public void testALinkReordersWhatItWasShown() throws Exception {
		String token = zooToken(Rights.VIEW, Rights.EDIT);
		AlbumInfo answer = album(get("/", "json", token));
		// The video before the gate.
		List<AlbumPart> parts = answer.getParts();
		AlbumPart video = parts.remove(parts.size() - 1);
		assertEquals(VIDEO, ((ImagePart) video).getName());
		parts.add(1, video);
		assertOk(put("/", write(answer), token));

		assertEquals(
			Arrays.asList("Day", VIDEO, "public.jpg", "members.jpg", "[crowd.jpg, hidden.jpg]", "Night", "private.jpg",
				REJECTED),
			arrangement(stored()));
	}

	public void testAHiddenAlbumPictureIsKept() throws Exception {
		AlbumInfo answer = album(get(zoo(), "json", SharingFixture.CAROL));
		assertFalse("carol is answered a substitute.", "private.jpg".equals(answer.getIndexPicture().getImage()));
		assertOk(put(zoo(), write(answer), SharingFixture.CAROL));
		assertEquals("private.jpg", stored().getIndexPicture().getImage());
	}

	public void testAPictureChosenInPlaceOfAHiddenOneIsTaken() throws Exception {
		AlbumInfo answer = album(get(zoo(), "json", SharingFixture.CAROL));
		String substitute = answer.getIndexPicture().getImage();
		String chosen = substitute.equals("public.jpg") ? CROWD : "public.jpg";
		answer.setIndexPicture(ThumbnailInfo.create().setImage(chosen));
		assertOk(put(zoo(), write(answer), SharingFixture.CAROL));
		assertEquals(chosen, stored().getIndexPicture().getImage());
	}

	public void testAContributorWritesNoInbox() throws Exception {
		SharingFixture.album(_base, "Inbox", "",
			"[\"ImagePart\",{\"name\":\"theirs.jpg\",\"width\":4,\"height\":3,\"comment\":\"Not bob's\"}]",
			"theirs.jpg");
		restartServer();
		String before = sidecar("Inbox");
		FakeResponse response = put("/Inbox/",
			"[\"AlbumInfo\",{\"title\":\"\",\"parts\":[]}]", SharingFixture.BOB);
		assertFalse(response.body(), response.status() == HttpServletResponse.SC_OK);
		assertEquals(before, sidecar("Inbox"));
	}

	// --- An editor answered everything. ---

	public void testAnEditorAnsweredEverythingChangesEverything() throws Exception {
		AlbumInfo answer = album(get(zoo(), "json", SharingFixture.ALICE));
		image(answer, "public.jpg").setLabels(new ArrayList<>(Arrays.asList(LabelName.create().setName("Gate"))));
		image(answer, "private.jpg").setTags(new ArrayList<>());
		image(answer, "private.jpg").setComment("Not so secret");
		answer.setIndexPicture(ThumbnailInfo.create().setImage("public.jpg"));
		// The rejected photograph leaves the order.
		for (Iterator<AlbumPart> it = answer.getParts().iterator(); it.hasNext();) {
			AlbumPart part = it.next();
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(REJECTED)) {
				it.remove();
			}
		}
		assertOk(put(zoo(), write(answer), SharingFixture.ALICE));

		AlbumInfo stored = stored();
		assertEquals(Arrays.asList("Gate"), labels(image(stored, "public.jpg")));
		assertTrue(image(stored, "private.jpg").getTags().isEmpty());
		assertEquals("Not so secret", image(stored, "private.jpg").getComment());
		assertEquals("public.jpg", stored.getIndexPicture().getImage());
		assertNull(Crops.findImage(stored, REJECTED));
	}

	public void testAnEditorsRoundTripChangesNothing() throws Exception {
		String before = get(zoo(), "json", SharingFixture.ALICE).body();
		assertOk(put(zoo(), write(album(get(zoo(), "json", SharingFixture.ALICE))), SharingFixture.ALICE));
		restartServer();
		assertEquals(before, get(zoo(), "json", SharingFixture.ALICE).body());
	}

	public void testThePosterIsKept() throws Exception {
		AlbumInfo answer = album(get(zoo(), "json", SharingFixture.ALICE));
		image(answer, VIDEO).setPoster(null);
		assertOk(put(zoo(), write(answer), SharingFixture.ALICE));
		assertEquals(1200, image(stored(), VIDEO).getPoster().getMillis());
	}

	// --- Helpers. ---

	private static String zoo() {
		return "/" + SharingFixture.ZOO + "/";
	}

	private static void assertOk(FakeResponse response) {
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
	}

	/** The private photographs of the fixture, as they were. */
	private static void assertKept(AlbumInfo stored, String privateName, String hiddenName) {
		ImagePart secret = image(stored, privateName);
		assertEquals(Privacy.PRIVATE, secret.getPrivacy());
		assertEquals("Secret", secret.getComment());
		assertEquals(Arrays.asList("Night"), labels(secret));
		assertEquals(1, secret.getTags().size());
		ImagePart hidden = image(stored, hiddenName);
		assertEquals(Privacy.PRIVATE, hidden.getPrivacy());
		assertEquals("Behind", hidden.getComment());
		ImageGroup group = groupOf(stored, hiddenName);
		assertNotNull("The hidden photograph stays in its group.", group);
		assertEquals("The group is headed as before.", hiddenName,
			group.getImages().get(group.getRepresentative()).getName());
		List<String> arrangement = arrangement(stored);
		assertTrue(arrangement.toString(), arrangement.indexOf("Night") >= 0);
		assertEquals("The private photograph stays in its section.", arrangement.indexOf("Night") + 1,
			arrangement.indexOf(privateName));
	}

	private static ImageGroup groupOf(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					if (image.getName().equals(name)) {
						return (ImageGroup) part;
					}
				}
			}
		}
		return null;
	}

	private static int count(AlbumInfo album, String name) {
		int result = 0;
		for (String entry : arrangement(album)) {
			result += entry.equals(name) || entry.contains(name + ",") || entry.contains(name + "]") ? 1 : 0;
		}
		return result;
	}

	/** The parts of the given album: headings by their text, groups as the list of their names. */
	private static List<String> arrangement(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof Heading) {
				result.add(((Heading) part).getText());
			} else if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			} else if (part instanceof ImageGroup) {
				List<String> names = new ArrayList<>();
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					names.add(image.getName());
				}
				result.add(names.toString());
			}
		}
		return result;
	}

	private String sidecar(String folder) throws Exception {
		return new String(Files.readAllBytes(_base.resolve(folder).resolve("index.json")), StandardCharsets.UTF_8);
	}

	private AlbumInfo stored() throws Exception {
		return (AlbumInfo) Resource.readResource(reader(sidecar(SharingFixture.ZOO)));
	}

	private static ImagePart image(AlbumInfo album, String name) {
		ImagePart result = Crops.findImage(album, name);
		assertNotNull("No part '" + name + "'.", result);
		return result;
	}

	private static List<String> labels(ImagePart image) {
		List<String> result = new ArrayList<>();
		for (LabelName label : image.getLabels()) {
			result.add(label.getName());
		}
		return result;
	}

	private static String write(Resource resource) throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8)))) {
			resource.writeTo(json);
		}
		return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
	}
}
