/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.Crop;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.model.PlaceCaption;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.SearchAnd;
import de.haumacher.imageServer.shared.model.SearchQuery;
import de.haumacher.imageServer.shared.model.SearchText;
import de.haumacher.imageServer.shared.model.VideoPoster;
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
import java.util.HashMap;
import java.util.Map;

/**
 * Test case for issue #292: a write made through a collection (issue #221) or a saved search (issue
 * #227) lands in the album the photograph lies in, and is authorised against that album.
 *
 * <p>
 * In a space a member's rights are their role's, the same in every folder, so a member who may
 * edit a collection may edit every album it shows, and a member who may only look may edit no
 * collection either. The caller who may edit the collection or the saved search but not the album
 * is a share link: it opens its target and nothing outside it. A link holding the edit right on a
 * collection or a saved search reaches the photographs it shows, but not the albums they lie in.
 * </p>
 *
 * <p>
 * The library is the {@link SharingFixture} with one more album, <code>Trips/Lake</code>, holding
 * a photograph and a video with a chosen poster; the collection <code>Best of</code> (at the top)
 * and <code>Trips/Mine</code> reference both, and the saved searches <code>Trips/Found</code> and
 * <code>Found</code> find both by the album's title.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestSourceWriteRights extends ShareTestCase {

	private static final String LAKE = "Trips/Lake";

	private static final String PHOTO = "lake.jpg";

	private static final String VIDEO = "clip.mp4";

	/** The name of the photograph in a saved search below <code>Trips</code>. */
	private static final String FOUND_PHOTO = "Lake/" + PHOTO;

	private static final String FOUND_VIDEO = "Lake/" + VIDEO;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		SharingFixture.album(_base, LAKE, "Lake",
			"[\"ImagePart\",{\"name\":\"" + PHOTO + "\",\"width\":4,\"height\":3,\"rating\":1,\"comment\":\"Shore\","
				+ "\"labels\":[{\"name\":\"Holiday\"}]}],"
				+ "[\"ImagePart\",{\"name\":\"" + VIDEO + "\",\"kind\":\"VIDEO\",\"width\":4,\"height\":3,"
				+ "\"poster\":{\"millis\":1200}}]",
			PHOTO, VIDEO);
		Files.createDirectories(_base.resolve("Trips"));
		restartServer();
		createCollection("/Best of");
		createCollection("/Trips/Mine");
		collect("/" + LAKE + "/", "Best of", PHOTO, VIDEO);
		collect("/" + LAKE + "/", "Trips/Mine", PHOTO, VIDEO);
		createSearch("/Trips/Found");
		createSearch("/Found");
	}

	// --- A member: the role decides, the same everywhere. ---

	public void testAViewerEditsNothingThroughACollectionOrASavedSearch() throws Exception {
		String lake = lakeSidecar();

		for (String folder : new String[] { "/Best of/", "/Trips/Found/" }) {
			boolean search = folder.contains("Found");
			String photo = search ? FOUND_PHOTO : PHOTO;
			String video = search ? FOUND_VIDEO : VIDEO;
			assertEquals(HttpServletResponse.SC_FORBIDDEN, crop(folder, photo, SharingFixture.DAVE).status());
			assertEquals(HttpServletResponse.SC_FORBIDDEN, poster(folder, video, SharingFixture.DAVE).status());
			assertEquals(HttpServletResponse.SC_FORBIDDEN,
				put(folder, write(edited(album(get(folder, "json", SharingFixture.DAVE)), photo)), SharingFixture.DAVE)
					.status());
		}

		assertEquals("Nothing of the album was written.", lake, lakeSidecar());
	}

	public void testAnEditorEditsThroughACollection() throws Exception {
		assertEquals(HttpServletResponse.SC_OK, crop("/Best of/", PHOTO, SharingFixture.CAROL).status());
		assertNotNull(lakeImage(PHOTO).getCrop());
		assertEquals(HttpServletResponse.SC_OK, poster("/Best of/", VIDEO, SharingFixture.CAROL).status());
		assertNull("The poster is taken away.", lakeImage(VIDEO).getPoster());

		FakeResponse put = put("/Best of/",
			write(edited(album(get("/Best of/", "json", SharingFixture.CAROL)), PHOTO)), SharingFixture.CAROL);
		assertEquals(put.body(), HttpServletResponse.SC_OK, put.status());
		assertEdited(lakeImage(PHOTO));
	}

	public void testAnEditorEditsThroughASavedSearch() throws Exception {
		assertEquals(HttpServletResponse.SC_OK, crop("/Trips/Found/", FOUND_PHOTO, SharingFixture.CAROL).status());
		assertNotNull(lakeImage(PHOTO).getCrop());
		assertEquals(HttpServletResponse.SC_OK, poster("/Trips/Found/", FOUND_VIDEO, SharingFixture.CAROL).status());
		assertNull(lakeImage(VIDEO).getPoster());

		FakeResponse put = put("/Trips/Found/",
			write(edited(album(get("/Trips/Found/", "json", SharingFixture.CAROL)), FOUND_PHOTO)),
			SharingFixture.CAROL);
		assertEquals(put.body(), HttpServletResponse.SC_OK, put.status());
		assertEdited(lakeImage(PHOTO));
		assertEquals("A saved search's labels are the photograph's own.", Arrays.asList("Seen"),
			labels(lakeImage(PHOTO)));
	}

	// --- A link: it reaches what it opens, and nothing outside. ---

	public void testALinkOnACollectionEditsNoAlbumOutsideIt() throws Exception {
		String token = link("Best of");
		String lake = lakeSidecar();

		assertEquals("A link crops nothing.", HttpServletResponse.SC_FORBIDDEN, crop("/", PHOTO, token).status());
		assertEquals("Nor chooses a poster.", HttpServletResponse.SC_FORBIDDEN, poster("/", VIDEO, token).status());
		FakeResponse put = put("/", write(edited(album(get("/", "json", token)), PHOTO)), token);
		assertEquals(put.body(), HttpServletResponse.SC_FORBIDDEN, put.status());
		assertEquals(ImageServlet.sourceEditRefused(PHOTO), errorMessage(put));

		assertEquals("Nothing of the album was written.", lake, lakeSidecar());
	}

	public void testALinkOnASavedSearchEditsNoAlbumOutsideIt() throws Exception {
		String token = link("Trips/Found");
		String lake = lakeSidecar();

		assertEquals(HttpServletResponse.SC_FORBIDDEN, crop("/", FOUND_PHOTO, token).status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, poster("/", FOUND_VIDEO, token).status());
		FakeResponse put = put("/", write(edited(album(get("/", "json", token)), FOUND_PHOTO)), token);
		assertEquals(put.body(), HttpServletResponse.SC_FORBIDDEN, put.status());
		assertEquals(ImageServlet.sourceEditRefused(FOUND_PHOTO), errorMessage(put));

		assertEquals("Nothing of the album was written.", lake, lakeSidecar());
	}

	public void testALinkKeepsWritingWhatIsItsCollectionsOwn() throws Exception {
		String token = link("Best of");
		String lake = lakeSidecar();
		AlbumInfo answer = album(get("/", "json", token));
		answer.setTitle("Our best");
		java.util.Collections.reverse(answer.getParts());

		FakeResponse put = put("/", write(answer), token);
		assertEquals(put.body(), HttpServletResponse.SC_OK, put.status());

		assertEquals("Our best", stored("Best of").getTitle());
		assertEquals("Nothing of the photographs changed: the album is not written.", lake, lakeSidecar());
	}

	public void testALinkKeepsWritingWhatIsItsSavedSearchsOwn() throws Exception {
		String token = link("Trips/Found");
		String lake = lakeSidecar();
		AlbumInfo answer = album(get("/", "json", token));
		answer.setTitle("Lakes");

		FakeResponse put = put("/", write(answer), token);
		assertEquals(put.body(), HttpServletResponse.SC_OK, put.status());

		assertEquals("Lakes", stored("Trips/Found").getTitle());
		assertEquals(lake, lakeSidecar());
	}

	public void testALinkEditsTheAlbumsItOpens() throws Exception {
		String token = link("Trips");

		FakeResponse put = put("/Mine/", write(edited(album(get("/Mine/", "json", token)), PHOTO)), token);
		assertEquals(put.body(), HttpServletResponse.SC_OK, put.status());
		assertEdited(lakeImage(PHOTO));

		AlbumInfo found = labelled(album(get("/Found/", "json", token)), FOUND_PHOTO);
		image(found, FOUND_PHOTO).setRating(-1);
		FakeResponse again = put("/Found/", write(found), token);
		assertEquals(again.body(), HttpServletResponse.SC_OK, again.status());
		assertEquals(-1, lakeImage(PHOTO).getRating());
		assertEquals("A link is answered no labels and writes none back.", Arrays.asList("Holiday"),
			labels(lakeImage(PHOTO)));
	}

	public void testALinkTakesNoLabelsAwayThroughACollection() throws Exception {
		AlbumInfo collection = stored("Best of");
		image(collection, PHOTO).setLabels(new ArrayList<>(Arrays.asList(LabelName.create().setName("Best"))));
		Files.write(_base.resolve("Best of").resolve("index.json"), write(collection).getBytes(StandardCharsets.UTF_8));
		restartServer();
		String token = link("Best of");
		AlbumInfo answer = album(get("/", "json", token));
		assertEquals("A link is answered no labels.", java.util.Collections.emptyList(), labels(image(answer, PHOTO)));

		FakeResponse put = put("/", write(answer.setTitle("Our best")), token);
		assertEquals(put.body(), HttpServletResponse.SC_OK, put.status());

		assertEquals("Our best", stored("Best of").getTitle());
		assertEquals("Nor writes any back.", Arrays.asList("Best"), labels(image(stored("Best of"), PHOTO)));
	}

	public void testALinkMayNotEditDoesNotWriteThroughIt() throws Exception {
		String token = issue("alice", "Trips", "Grandma", "", Privacy.PRIVATE, Ratings.MIN, Rights.CONTRIBUTE);
		String lake = lakeSidecar();

		FakeResponse put = put("/Mine/", write(edited(album(get("/Mine/", "json", token)), PHOTO)), token);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, put.status());
		assertEquals(lake, lakeSidecar());
	}

	// --- Helpers. ---

	/** A link with every right on the given folder, showing everything. */
	private String link(String path) throws Exception {
		return issue("alice", path, "Grandma", "", Privacy.PRIVATE, Ratings.MIN, Rights.EDIT);
	}

	/** The given answer with every statement a part makes about the photograph changed. */
	private static AlbumInfo edited(AlbumInfo answer, String name) {
		ImagePart image = image(answer, name);
		image.setOrientation(Orientation.ROT_R).setRating(2).setComment("The lake").setPrivacy(Privacy.MEMBERS)
			.setDate(image.getDate() + 60_000).setPlaceCaption(PlaceCaption.HIDDEN)
			.setCrop(Crop.create().setX(0.25).setY(0.25).setW(0.5).setH(0.5))
			.setLabels(new ArrayList<>(Arrays.asList(LabelName.create().setName("Seen"))));
		return answer;
	}

	/** The given answer with the labels of the given part changed alone. */
	private static AlbumInfo labelled(AlbumInfo answer, String name) {
		image(answer, name).setLabels(new ArrayList<>(Arrays.asList(LabelName.create().setName("Seen"))));
		return answer;
	}

	private static void assertEdited(ImagePart image) {
		assertEquals(Orientation.ROT_R, image.getOrientation());
		assertEquals(2, image.getRating());
		assertEquals("The lake", image.getComment());
		assertEquals(Privacy.MEMBERS, image.getPrivacy());
		assertEquals(PlaceCaption.HIDDEN, image.getPlaceCaption());
		assertNotNull(image.getCrop());
	}

	private FakeResponse crop(String folder, String name, String token) throws Exception {
		ImagePart request = ImagePart.create().setName(name)
			.setCrop(Crop.create().setX(0.1).setY(0.1).setW(0.5).setH(0.5));
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.CROP_ACTION);
		return post(folder, write(request), token, parameters);
	}

	/** Takes the chosen poster of the given video away: a write that needs no video to probe. */
	private FakeResponse poster(String folder, String name, String token) throws Exception {
		ImagePart request = ImagePart.create().setName(name).setPoster(VideoPoster.create().setMillis(0));
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.POSTER_ACTION);
		return post(folder, write(request), token, parameters);
	}

	private void createCollection(String path) throws Exception {
		FakeResponse response = put(path, "[\"AlbumInfo\",{\"kind\":\"COLLECTION\",\"title\":\"Best\"}]",
			SharingFixture.ALICE);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
	}

	private void collect(String from, String target, String... names) throws Exception {
		StringBuilder body = new StringBuilder("{\"target\":\"").append(target).append("\",\"names\":[");
		for (int n = 0; n < names.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"name\":\"").append(names[n]).append("\"}");
		}
		body.append("]}");
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.COLLECT_ACTION);
		FakeResponse response = post(from, body.toString(), SharingFixture.ALICE, parameters);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
	}

	private void createSearch(String path) throws Exception {
		SearchQuery query = SearchQuery.create().setVersion(1).setRoot(
			SearchAnd.create().setCriteria(new ArrayList<>(Arrays.asList(SearchText.create().setText("Lake")))));
		AlbumInfo search = AlbumInfo.create().setKind(AlbumKind.SEARCH).setTitle("Found").setQuery(query);
		FakeResponse response = put(path, write(search), SharingFixture.ALICE);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
	}

	private String lakeSidecar() throws Exception {
		return new String(Files.readAllBytes(_base.resolve(LAKE).resolve("index.json")), StandardCharsets.UTF_8);
	}

	private ImagePart lakeImage(String name) throws Exception {
		return image(stored(LAKE), name);
	}

	private AlbumInfo stored(String folder) throws Exception {
		Path sidecar = _base.resolve(folder).resolve("index.json");
		return (AlbumInfo) Resource.readResource(reader(new String(Files.readAllBytes(sidecar), StandardCharsets.UTF_8)));
	}

	private static ImagePart image(AlbumInfo album, String name) {
		ImagePart result = Crops.findImage(album, name);
		assertNotNull("No part '" + name + "'.", result);
		return result;
	}

	private static java.util.List<String> labels(ImagePart image) {
		java.util.List<String> result = new ArrayList<>();
		for (LabelName label : image.getLabels()) {
			result.add(label.getName());
		}
		return result;
	}

	private static String write(Object object) throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8)))) {
			if (object instanceof Resource) {
				((Resource) object).writeTo(json);
			} else {
				((SearchQuery) object).writeTo(json);
			}
		}
		return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
	}
}
