/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.content.ContentCache;
import de.haumacher.imageServer.content.ContentSearch;
import de.haumacher.imageServer.content.ContentText;
import de.haumacher.imageServer.content.Embedder;
import de.haumacher.imageServer.content.Stacks;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.BurstThreshold;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.PhotoStack;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.SpaceSettings;
import de.haumacher.imageServer.shared.model.StackMember;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the space's cut of the bursts, see issue #315 and {@link Stacks}: the stacks of an
 * album follow the <code>burstThreshold</code> of the space's <code>space.json</code>, else
 * {@link ContentSearch#BURST_THRESHOLD}, and a cut changed in the settings holds from the next read
 * of the album on.
 *
 * <p>
 * The album holds two pairs of shots a minute apart, the pairs ten minutes apart and unlike each
 * other: {@link #NEAR} at a cosine between the built-in cut and <code>0.97</code>, {@link #CLOSE}
 * at <code>0.98</code>.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestBurstThreshold extends TestCase {

	private static final String STAMP = "fake@0000001/256/fp32/1";

	private static final String ALBUM = "Lib/Burst";

	private static final long DATE = 1_600_000_000_000L;

	private static final long MINUTE = 60_000L;

	private static final String ADMIN = "admin-token";

	/** The cosine of the first pair: above the built-in cut, below <code>0.97</code>. */
	private static final double NEAR = (ContentSearch.BURST_THRESHOLD + 0.97) / 2;

	/** The cosine of the second pair. */
	private static final double CLOSE = 0.98;

	private static final List<String> NEAR_PAIR = List.of("n0.jpg", "n1.jpg");

	private static final List<String> CLOSE_PAIR = List.of("c0.jpg", "c1.jpg");

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		assertTrue("The fixture needs a built-in cut below 0.97.", ContentSearch.BURST_THRESHOLD < 0.96f);
		_base = Files.createTempDirectory("valbum-burst-threshold");
		Stacks.forget();
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
			_servlet = null;
		}
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		Stacks.forget();
		super.tearDown();
	}

	// --- The computation. ---

	public void testTheCutDecidesWhatIsABurst() {
		AlbumInfo album = AlbumInfo.create();
		Map<String, Stacks.Shot> shots = new HashMap<>();
		shot(album, shots, "a0.jpg", DATE, axis(0));
		shot(album, shots, "a1.jpg", DATE + MINUTE, cosine(0, 1, 0.95));
		shot(album, shots, "b0.jpg", DATE + 10 * MINUTE, axis(10));
		shot(album, shots, "b1.jpg", DATE + 11 * MINUTE, cosine(10, 11, 0.98));
		assertEquals("At 0.97, a pair at 0.95 is no stack, a pair at 0.98 is.",
			List.of(List.of("b0.jpg", "b1.jpg")), stacks(Stacks.compute(album, shots, 0.97f)));
		assertEquals(List.of(List.of("a0.jpg", "a1.jpg"), List.of("b0.jpg", "b1.jpg")),
			stacks(Stacks.compute(album, shots, 0.9f)));
		assertEquals(List.of(), stacks(Stacks.compute(album, shots, 0.99f)));
	}

	// --- The space's cut. ---

	public void testWithoutACutOfItsOwnTheBuiltInHolds() throws Exception {
		library(null);
		assertEquals(List.of(NEAR_PAIR, CLOSE_PAIR), stacks(album()));
	}

	public void testTheCutOfSpaceJsonHolds() throws Exception {
		library(Double.valueOf(0.97));
		assertEquals("The pair below the space's cut is no stack.", List.of(CLOSE_PAIR), stacks(album()));
	}

	public void testACutOutOfRangeInTheFileIsReadAsNone() throws Exception {
		library(Double.valueOf(0.3));
		assertEquals(0.0, _servlet.config().getBurstThreshold());
		assertEquals(List.of(NEAR_PAIR, CLOSE_PAIR), stacks(album()));
	}

	public void testACutChangedInTheSettingsHoldsFromTheNextRead() throws Exception {
		library(null);
		assertEquals(List.of(NEAR_PAIR, CLOSE_PAIR), stacks(album()));

		SpaceSettings settings = settings(request("GET", null));
		assertEquals(0.0, settings.getBurst().getCut());
		settings.getBurst().setCut(0.97);
		FakeResponse saved = request("POST", settings);
		assertEquals(saved.body(), 200, saved.status());
		assertEquals("No restart, nothing stale.", List.of(CLOSE_PAIR), stacks(album()));

		settings.getBurst().setCut(0.99);
		assertEquals(200, request("POST", settings).status());
		assertEquals(List.of(), stacks(album()));

		settings.getBurst().setCut(0);
		assertEquals(200, request("POST", settings).status());
		assertEquals("Empty: the built-in cut again.", List.of(NEAR_PAIR, CLOSE_PAIR), stacks(album()));
	}

	public void testACutBelowHalfOrAboveOneIsRefused() throws Exception {
		library(Double.valueOf(0.97));
		byte[] before = Files.readAllBytes(SpaceStore.file(_base));
		for (double cut : new double[] { 0.3, 1.5, -0.2 }) {
			SpaceSettings settings = settings(request("GET", null));
			settings.getBurst().setCut(cut);
			FakeResponse refused = request("POST", settings);
			assertEquals(refused.body(), 400, refused.status());
			assertEquals(SpaceConfiguration.BURST_RANGE, error(refused));
			assertTrue("Nothing is written.", java.util.Arrays.equals(before, Files.readAllBytes(SpaceStore.file(_base))));
		}
		assertEquals("The space's cut still holds.", List.of(CLOSE_PAIR), stacks(album()));
	}

	// --- The library. ---

	private void library(Double burst) throws Exception {
		Files.createDirectories(SpaceStore.file(_base).getParent());
		Files.writeString(SpaceStore.file(_base), "{\"version\":1,\"name\":\"Bursts\",\"anonymous\":\"none\","
			+ "\"faces\":\"off\",\"content\":\"on\"" + (burst == null ? "" : ",\"burstThreshold\":" + burst) + "}");
		UserStore users = new UserStore(_base);
		UserStore.User admin = new UserStore.User("haui", Roles.ADMIN, "", Instant.now().toString());
		admin.addDevice(new UserStore.Device("Phone", UserStore.hash(ADMIN), Instant.now().toString()));
		users.addUser(admin);
		users.nameOwner("haui");
		users.store();

		Map<String, float[]> photos = new java.util.LinkedHashMap<>();
		photos.put("n0.jpg", axis(0));
		photos.put("n1.jpg", cosine(0, 1, NEAR));
		photos.put("c0.jpg", axis(10));
		photos.put("c1.jpg", cosine(10, 11, CLOSE));
		writeAlbum(photos);

		_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, _base), "",
			SpaceStore.load(_base, ""));
		_servlet.init();
	}

	private void writeAlbum(Map<String, float[]> photos) throws IOException {
		Path folder = _base.resolve(ALBUM);
		Files.createDirectories(folder);
		StringBuilder parts = new StringBuilder();
		int n = 0;
		for (String name : photos.keySet()) {
			Files.write(folder.resolve(name), jpeg(name));
			if (parts.length() > 0) {
				parts.append(',');
			}
			// A minute apart within a pair, ten minutes between the pairs.
			long date = DATE + (n / 2) * 10 * MINUTE + (n % 2) * MINUTE;
			parts.append("[\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"").append(name)
				.append("\",\"width\":16,\"height\":16,\"date\":").append(date).append("}]");
			n++;
		}
		Files.writeString(folder.resolve("index.json"),
			"[\"AlbumInfo\",{\"title\":\"Burst\",\"parts\":[" + parts + "]}]");
		Map<String, String> hashes = new HashCache(folder.toFile()).hashByName();
		ContentCache.update(folder.toFile(), STAMP, ContentText.stamp(STAMP), cache -> {
			for (Map.Entry<String, float[]> photo : photos.entrySet()) {
				ContentCache.ImageKey key = new ContentCache.ImageKey(hashes.get(photo.getKey()), 0);
				String text = ContentCache.textHash("text of " + photo.getKey());
				cache.putImage(key, photo.getValue());
				cache.putText(text, axis(255));
				cache.putPhoto(photo.getKey(), new ContentCache.Photo(key, text));
			}
		});
	}

	private static byte[] jpeg(String seed) throws IOException {
		Random random = new Random(seed.hashCode());
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

	// --- Vectors. ---

	private static void shot(AlbumInfo album, Map<String, Stacks.Shot> shots, String name, long date,
			float[] vector) {
		album.addPart(ImagePart.create().setName(name).setKind(ImageKind.IMAGE).setDate(date));
		shots.put(name, new Stacks.Shot(vector, Float.NaN));
	}

	private static float[] axis(int n) {
		float[] result = new float[Embedder.DIMENSION];
		result[n] = 1;
		return result;
	}

	/** A unit vector at the given cosine to the axis <code>main</code>, tilted towards <code>other</code>. */
	private static float[] cosine(int main, int other, double cosine) {
		float[] result = new float[Embedder.DIMENSION];
		result[main] = (float) cosine;
		result[other] = (float) Math.sqrt(1 - cosine * cosine);
		return result;
	}

	// --- Asking. ---

	private List<PhotoStack> album() throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		_servlet.doGet(request("/" + ALBUM + "/", "", parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue(response.body(), resource instanceof AlbumInfo);
		return ((AlbumInfo) resource).getStacks();
	}

	/** Reads (<code>GET</code>, no settings) or saves the space settings as the administrator. */
	private FakeResponse request(String method, SpaceSettings settings) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put(settings == null ? "type" : "action", SpaceConfiguration.TYPE);
		FakeResponse response = new FakeResponse();
		if ("GET".equals(method)) {
			_servlet.doGet(request("/", "", parameters), response.response());
		} else {
			_servlet.doPost(request("/", write(settings), parameters), response.response());
		}
		return response;
	}

	private static jakarta.servlet.http.HttpServletRequest request(String path, String body,
			Map<String, String> parameters) {
		Map<String, String> headers = new HashMap<>();
		headers.put("Authorization", "Bearer " + ADMIN);
		return TestImageServletPut.request(path, "application/json", body.getBytes(StandardCharsets.UTF_8), headers,
			parameters);
	}

	private static SpaceSettings settings(FakeResponse response) throws Exception {
		assertEquals(response.body(), 200, response.status());
		SpaceSettings result = SpaceSettings.readSpaceSettings(reader(response.body()));
		assertNotNull("Always answered.", result.getBurst());
		BurstThreshold burst = result.getBurst();
		assertEquals(Double.parseDouble(Float.toString(ContentSearch.BURST_THRESHOLD)), burst.getDefaultCut(), 1e-12);
		return result;
	}

	private static String error(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an error, got: " + response.body(), resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	private static String write(SpaceSettings settings) throws Exception {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(buffer))) {
			settings.writeTo(json);
		}
		return buffer.toString();
	}

	private static List<List<String>> stacks(List<PhotoStack> stacks) {
		List<List<String>> result = new ArrayList<>();
		for (PhotoStack stack : stacks) {
			List<String> names = new ArrayList<>();
			for (StackMember member : stack.getMembers()) {
				names.add(member.getName());
			}
			result.add(names);
		}
		return result;
	}

	private static JsonReader reader(String json) {
		return new JsonReader(new ReaderAdapter(new StringReader(json)));
	}
}
