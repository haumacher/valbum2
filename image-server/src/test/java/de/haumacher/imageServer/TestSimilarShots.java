/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.content.ContentCache;
import de.haumacher.imageServer.content.ContentSearch;
import de.haumacher.imageServer.content.ContentText;
import de.haumacher.imageServer.content.Embedder;
import de.haumacher.imageServer.content.Quality;
import de.haumacher.imageServer.content.Stacks;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.PhotoStack;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.shared.model.SimilarGroup;
import de.haumacher.imageServer.shared.model.SimilarList;
import de.haumacher.imageServer.shared.model.SimilarPhoto;
import de.haumacher.imageServer.shared.model.StackMember;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the similar shots of issue #257 (package 6): the bursts of an album, stacked when
 * the album is answered and never stored, and the overview of the shots of the same scenery across
 * the space beside the duplicates of #220.
 *
 * <p>
 * The library is made of vectors written for the test, without an embedder, in each album's
 * {@link ContentCache} keyed by the real content hash of each file, exactly as the content step
 * keys them. A "burst" vector is the first axis tilted a little towards an axis of its own: two of
 * them have a cosine of about <code>0.99</code>, above {@link ContentSearch#BURST_THRESHOLD}; an
 * "unlike" vector is an axis of its own, orthogonal to every other.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestSimilarShots extends TestCase {

	private static final String STAMP = "fake@0000001/256/fp32/1";

	private static final String BURST = "Lib/Burst";

	private static final long DATE = 1_600_000_000_000L;

	private static final long MINUTE = 60_000L;

	private static final String ADMIN = "admin-token";

	private static final String GUEST = "guest-token";

	private Path _base;

	private ImageServlet _servlet;

	/** The photographs of the library, by album, in the order the albums list them. */
	private final Map<String, List<Photo>> _albums = new LinkedHashMap<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-similar-shots");
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

	// --- Bursts within an album. ---

	/**
	 * Five photos a minute apart that look alike are one stack; one as alike but an hour later is
	 * not, one within a minute but unlike is not, a video never joins.
	 */
	public void testAlikeShotsMinutesApartAreOneStack() throws Exception {
		burst();
		library(SpaceStore.CONTENT_ON);

		AlbumInfo album = album(BURST, ADMIN);
		assertEquals("One stack: " + stacks(album), List.of(List.of("p0.jpg", "p1.jpg", "p2.jpg", "p3.jpg", "p4.jpg")),
			stacks(album));
		List<String> all = names(album);
		assertTrue("A stack hides nothing from the album's answer: " + all,
			all.containsAll(List.of("u.jpg", "p0.jpg", "p1.jpg", "p2.jpg", "p3.jpg", "p4.jpg", "late.jpg", "v.mp4")));
	}

	/** A shot moved out of a burst leaves its stack at once; alone where it lands, it is none. */
	public void testAShotMovedAwayLeavesItsStack() throws Exception {
		burst();
		add("Lib/Other", new Photo("o.jpg", DATE - 30 * 86_400_000L, axis(150)));
		library(SpaceStore.CONTENT_ON);
		assertEquals(5, stacks(album(BURST, ADMIN)).get(0).size());

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		FakeResponse move = post("/" + BURST + "/", "{\"target\":\"Lib/Other\",\"names\":[{\"name\":\"p2.jpg\"}]}",
			ADMIN, parameters);
		assertEquals(move.body(), 200, move.status());

		assertEquals("The rest is still one burst, two minutes apart at most.",
			List.of(List.of("p0.jpg", "p1.jpg", "p3.jpg", "p4.jpg")), stacks(album(BURST, ADMIN)));
		AlbumInfo other = album("Lib/Other", ADMIN);
		assertTrue(names(other).contains("p2.jpg"));
		assertTrue("Alone, no stack: " + stacks(other), stacks(other).isEmpty());
	}

	/** A video never joins a stack, however alike its poster and however near in time. */
	public void testAVideoNeverJoins() {
		AlbumInfo album = AlbumInfo.create();
		Map<String, Stacks.Shot> shots = new HashMap<>();
		for (int n = 0; n < 3; n++) {
			String name = n == 1 ? "v.mp4" : "p" + n + ".jpg";
			album.addPart(ImagePart.create().setName(name).setKind(n == 1 ? ImageKind.VIDEO : ImageKind.IMAGE)
				.setDate(DATE + n * MINUTE));
			shots.put(name, new Stacks.Shot(normalized(burstVector(n)), Float.NaN));
		}
		assertEquals("The photos beside the video stack, the video is no member.",
			List.of(List.of("p0.jpg", "p2.jpg")), stacks(Stacks.compute(album, shots)));

		// A photograph without a date has no place in the order a burst is taken in.
		((ImagePart) album.getParts().get(2)).setDate(0);
		assertEquals(List.of(), stacks(Stacks.compute(album, shots)));
	}

	/**
	 * A reference of issue #307 never joins a stack, however alike and however near in time: a burst
	 * is a run of shots of the album they lie in, which stacks them there.
	 */
	public void testAReferenceNeverJoins() {
		AlbumInfo album = AlbumInfo.create();
		Map<String, Stacks.Shot> shots = new HashMap<>();
		for (int n = 0; n < 3; n++) {
			String name = "p" + n + ".jpg";
			ImagePart image = ImagePart.create().setName(name).setKind(ImageKind.IMAGE).setDate(DATE + n * MINUTE);
			if (n == 1) {
				image.setRef(de.haumacher.imageServer.shared.model.PhotoRef.create().setHash("ab").setPath("Other/p1.jpg"));
			}
			album.addPart(image);
			shots.put(name, new Stacks.Shot(normalized(burstVector(n)), Float.NaN));
		}
		assertEquals("The photos beside the reference stack, the reference is no member.",
			List.of(List.of("p0.jpg", "p2.jpg")), stacks(Stacks.compute(album, shots)));
	}

	/** A heading ends a stack: a stack never pulls a photograph out of its section. */
	public void testAHeadingSeparatesStacks() {
		AlbumInfo album = AlbumInfo.create();
		Map<String, Stacks.Shot> shots = new HashMap<>();
		for (int n = 0; n < 4; n++) {
			if (n == 2) {
				album.addPart(de.haumacher.imageServer.shared.model.Heading.create().setText("Later"));
			}
			String name = "p" + n + ".jpg";
			album.addPart(ImagePart.create().setName(name).setKind(ImageKind.IMAGE).setDate(DATE + n * MINUTE));
			shots.put(name, new Stacks.Shot(normalized(burstVector(n)), Float.NaN));
		}
		assertEquals(List.of(List.of("p0.jpg", "p1.jpg"), List.of("p2.jpg", "p3.jpg")),
			stacks(Stacks.compute(album, shots)));
	}

	/** The stack is derived: written back, the album stores none, and reads the same again. */
	public void testAStackIsNeverStored() throws Exception {
		burst();
		library(SpaceStore.CONTENT_ON);

		FakeResponse read = get("/" + BURST + "/", ADMIN);
		AlbumInfo before = album(read);
		assertFalse(before.getStacks().isEmpty());

		FakeResponse written = put("/" + BURST + "/", read.body(), ADMIN);
		assertEquals(written.body(), HttpServletResponse.SC_OK, written.status());
		String stored = Files.readString(_base.resolve(BURST).resolve("index.json"));
		AlbumInfo sidecar = (AlbumInfo) Resource.readResource(reader(stored));
		assertTrue("No stack in the sidecar: " + stored, sidecar.getStacks().isEmpty());
		assertFalse("Not a member named under stacks: " + stored, stored.contains("\"members\""));

		AlbumInfo after = album(BURST, ADMIN);
		assertEquals("The read again is the same.", stacks(before), stacks(after));
		assertEquals(names(before), names(after));
	}

	/** A rated member is on top; the others follow in the order they were taken. */
	public void testARatedShotIsOnTop() throws Exception {
		burst();
		photo(BURST, "p3.jpg").rating = 1;
		library(SpaceStore.CONTENT_ON);
		assertEquals(List.of(List.of("p3.jpg", "p0.jpg", "p1.jpg", "p2.jpg", "p4.jpg")), stacks(album(BURST, ADMIN)));
	}

	/** Without a rating, the sharpest and best exposed is on top; without a measure, the first. */
	public void testTheSharpestShotIsOnTopWithoutRatings() throws Exception {
		burst();
		for (Photo photo : _albums.get(BURST)) {
			photo.quality = 10;
		}
		photo(BURST, "p2.jpg").quality = 50;
		library(SpaceStore.CONTENT_ON);
		assertEquals(List.of(List.of("p2.jpg", "p0.jpg", "p1.jpg", "p3.jpg", "p4.jpg")), stacks(album(BURST, ADMIN)));
	}

	public void testTheFirstShotIsOnTopWithoutAMeasure() throws Exception {
		burst();
		library(SpaceStore.CONTENT_ON);
		assertEquals("p0.jpg", stacks(album(BURST, ADMIN)).get(0).get(0));
	}

	/** The measure itself: a sharp picture beats the same picture blurred, a burnt-out one loses. */
	public void testTheMeasurePrefersSharpAndExposed() {
		BufferedImage sharp = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
		BufferedImage blurred = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
		BufferedImage burnt = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < 64; x++) {
			for (int y = 0; y < 64; y++) {
				int sharpGray = ((x / 4 + y / 4) % 2) == 0 ? 40 : 200;
				// The same edges, smeared over eight pixels.
				int blurredGray = 120 + (int) (80 * Math.sin(Math.PI * x / 8) * Math.sin(Math.PI * y / 8));
				int burntGray = sharpGray == 40 ? 40 : 255;
				sharp.setRGB(x, y, gray(sharpGray));
				blurred.setRGB(x, y, gray(blurredGray));
				burnt.setRGB(x, y, gray(burntGray));
			}
		}
		float sharpQuality = Quality.measure(sharp);
		assertTrue(sharpQuality + " > " + Quality.measure(blurred), sharpQuality > Quality.measure(blurred));
		assertTrue(sharpQuality + " > " + Quality.measure(burnt), sharpQuality > Quality.measure(burnt));
	}

	/** The cache of the build before the quality is read with its vectors, every quality unknown. */
	public void testACacheWithoutQualitiesKeepsItsVectors() throws Exception {
		File folder = _base.resolve("Old").toFile();
		folder.mkdirs();
		String hash = ContentCache.textHash("old");
		ContentCache.update(folder, STAMP, ContentText.stamp(STAMP), cache -> {
			ContentCache.ImageKey key = new ContentCache.ImageKey(hash, 0);
			cache.putImage(key, burstVector(0));
			cache.putQuality(key, 42);
			cache.putText(hash, burstVector(1));
			cache.putPhoto("old.jpg", new ContentCache.Photo(key, hash));
		});
		byte[] v2 = Files.readAllBytes(ContentCache.file(folder).toPath());
		Files.write(ContentCache.file(folder).toPath(), version1(v2));

		ContentCache old = ContentCache.read(folder);
		ContentCache.ImageKey key = new ContentCache.ImageKey(hash, 0);
		assertNotNull("The vector is kept.", old.image(key));
		assertTrue("Not measured yet.", Float.isNaN(old.quality(key)));
		assertEquals(1, old.photos().size());
	}

	/** A space with the content search off answers no stack at all. */
	public void testNoStacksWithContentSearchOff() throws Exception {
		burst();
		library(SpaceStore.CONTENT_OFF);
		assertEquals(List.of(), album(BURST, ADMIN).getStacks());
	}

	/** A guest is answered no stack naming a photograph they are not shown. */
	public void testAStackNamesOnlyWhatTheCallerSees() throws Exception {
		burst();
		photo(BURST, "p2.jpg").privacy = Privacy.PRIVATE;
		library(SpaceStore.CONTENT_ON);
		List<List<String>> stacks = stacks(album(BURST, GUEST));
		assertEquals("The private one is no member, and the chain goes on without it: " + stacks,
			List.of(List.of("p0.jpg", "p1.jpg", "p3.jpg", "p4.jpg")), stacks);
	}

	// --- The same scenery across the space. ---

	public void testAlikePhotosOfTwoAlbumsAreAGroup() throws Exception {
		scenery();
		library(SpaceStore.CONTENT_ON);
		SimilarList list = similar(ADMIN);
		assertTrue(list.isContent());
		assertEquals("Everything is described.", list.getDescribed().getTotal(), list.getDescribed().getDone());
		List<List<String>> groups = groups(list);
		assertTrue("The hill of A and B: " + groups, groups.contains(List.of("Lib/A/hill.jpg", "Lib/B/hill.jpg")));
		assertTrue("The private lake for the owner: " + groups,
			groups.contains(List.of("Lib/A/lake.jpg", "Lib/B/lake.jpg")));
		for (List<String> group : groups) {
			assertFalse("A duplicate is no similar shot: " + groups,
				group.contains("Lib/A/copy.jpg") && group.contains("Lib/B/copy.jpg"));
			assertFalse("Unlike shots are no group: " + groups, group.contains("Lib/A/alone.jpg"));
		}
		assertEquals("Exactly the two: " + groups, 2, groups.size());
		SimilarPhoto first = list.getGroups().get(0).getPhotos().get(0);
		assertEquals("The album's title rides along.", "A", first.getTitle());
		assertNotNull("And a thumbnail.", first.getImage());
	}

	public void testAGuestSeesNoGroupRevealingAPrivatePhoto() throws Exception {
		scenery();
		library(SpaceStore.CONTENT_ON);
		List<List<String>> groups = groups(similar(GUEST));
		assertEquals("The hill alone, never a lake of one: " + groups,
			List.of(List.of("Lib/A/hill.jpg", "Lib/B/hill.jpg")), groups);
	}

	public void testAShareLinkAndAnAnonymousCallerAreRefused() throws Exception {
		scenery();
		library(SpaceStore.CONTENT_ON);
		FakeResponse shared = post("/Lib/A/", "{\"label\":\"Grandma\",\"expires\":\"\",\"maxPrivacy\":0,\"minRating\":0,"
			+ "\"rights\":[{\"name\":\"view\"}]}", ADMIN, Map.of("action", "share"));
		assertEquals(shared.body(), HttpServletResponse.SC_OK, shared.status());
		String link = ShareLinkCreated.readShareLinkCreated(reader(shared.body())).getToken();
		for (String token : new String[] { link, null }) {
			FakeResponse response = similarResponse(token);
			assertEquals(response.body(), HttpServletResponse.SC_FORBIDDEN, response.status());
			assertEquals(ImageServlet.SIMILAR_REFUSED, errorMessage(response));
		}
	}

	public void testTheOverviewIsEmptyWithContentSearchOff() throws Exception {
		scenery();
		library(SpaceStore.CONTENT_OFF);
		SimilarList list = similar(ADMIN);
		assertFalse(list.isContent());
		assertEquals(List.of(), list.getGroups());
	}

	/** While photographs wait to be described, the overview says how far it got. */
	public void testTheOverviewSaysWhatIsNotDescribedYet() throws Exception {
		scenery();
		photo("Lib/A", "alone.jpg").vector = null;
		library(SpaceStore.CONTENT_ON);
		SimilarList list = similar(ADMIN);
		assertEquals(list.getDescribed().getTotal() - 1, list.getDescribed().getDone());
	}

	/**
	 * Ten thousand photographs in twenty albums, in clusters of five alike across the albums: the
	 * overview is built from a query of the graph per photograph, in bounded time, and asked again
	 * it is the one kept.
	 */
	public void testTenThousandPhotosAreBoundedAndKept() throws Exception {
		int count = 10_000;
		int albums = 20;
		Random random = new Random(257);
		float[][] bases = new float[count / 5][];
		for (int c = 0; c < bases.length; c++) {
			bases[c] = randomUnit(random);
		}
		for (int n = 0; n < count; n++) {
			float[] noise = randomUnit(random);
			float[] base = bases[n / 5];
			float[] vector = new float[Embedder.DIMENSION];
			for (int d = 0; d < vector.length; d++) {
				vector[d] = base[d] + 0.3f * noise[d];
			}
			add("Many/A" + String.format("%02d", Integer.valueOf(n % albums)),
				new Photo("m" + String.format("%05d", Integer.valueOf(n)) + ".jpg", DATE + n * 3_600_000L, vector));
		}
		long built = System.nanoTime();
		library(SpaceStore.CONTENT_ON, false);
		long ready = System.nanoTime();

		int before = _servlet.searchIndex().sceneryComputations();
		long start = System.nanoTime();
		SimilarList list = similar(ADMIN);
		long first = System.nanoTime() - start;
		start = System.nanoTime();
		SimilarList again = similar(ADMIN);
		long second = System.nanoTime() - start;

		System.out.println("Similar shots of " + count + " photos: library built in " + (ready - built) / 1_000_000
			+ " ms, overview in " + first / 1_000_000 + " ms, again in " + second / 1_000_000 + " ms, "
			+ list.getGroups().size() + " groups.");
		assertEquals(count, list.getDescribed().getTotal());
		assertTrue("The clusters are found: " + list.getGroups().size(), list.getGroups().size() >= count / 5 * 99 / 100);
		for (SimilarGroup group : list.getGroups()) {
			assertTrue("No group beyond its cluster: " + group.getPhotos().size(), group.getPhotos().size() <= 5);
		}
		assertTrue("Bounded: " + first / 1_000_000 + " ms", first < 120_000_000_000L);
		assertEquals("Computed once, kept for the second request.", before + 1,
			_servlet.searchIndex().sceneryComputations());
		assertEquals(groups(list), groups(again));
	}

	// --- The library. ---

	/** One photograph of the library, see {@link #library(String)}. */
	private static final class Photo {
		final String name;

		final long date;

		float[] vector;

		ImageKind kind = ImageKind.IMAGE;

		int privacy = Privacy.PUBLIC;

		int rating;

		float quality = Float.NaN;

		/** What the file's contents are made of: two of the same seed are one content. */
		String seed;

		Photo(String name, long date, float[] vector) {
			this.name = name;
			this.date = date;
			this.vector = vector;
			this.seed = name + "/" + date;
		}
	}

	private void add(String album, Photo photo) {
		_albums.computeIfAbsent(album, x -> new ArrayList<>()).add(photo);
	}

	private Photo photo(String album, String name) {
		for (Photo photo : _albums.get(album)) {
			if (photo.name.equals(name)) {
				return photo;
			}
		}
		throw new AssertionError("No " + name + " in " + album);
	}

	/**
	 * The burst album: an unlike shot half a minute before the burst, five alike a minute apart
	 * (p0 to p4), a video as alike in the middle, and one as alike an hour after the last.
	 */
	private void burst() {
		add(BURST, new Photo("u.jpg", DATE - MINUTE / 2, axis(200)));
		for (int n = 0; n < 5; n++) {
			add(BURST, new Photo("p" + n + ".jpg", DATE + n * MINUTE, burstVector(n)));
			if (n == 2) {
				Photo video = new Photo("v.mp4", DATE + n * MINUTE + MINUTE / 2, burstVector(10));
				video.kind = ImageKind.VIDEO;
				add(BURST, video);
			}
		}
		add(BURST, new Photo("late.jpg", DATE + 4 * MINUTE + 60 * MINUTE, burstVector(5)));
	}

	/**
	 * Two albums: a hill shot in each (alike), a lake in each (alike, the one of A private), one
	 * content lying in both (a duplicate), and an unlike shot in A.
	 */
	private void scenery() {
		add("Lib/A", new Photo("hill.jpg", DATE, tilted(0, 1, 0.4f)));
		add("Lib/B", new Photo("hill.jpg", DATE + 86_400_000L, tilted(0, 2, 0.4f)));
		Photo lake = new Photo("lake.jpg", DATE + 1000, tilted(10, 11, 0.4f));
		lake.privacy = Privacy.PRIVATE;
		add("Lib/A", lake);
		add("Lib/B", new Photo("lake.jpg", DATE + 86_400_000L + 1000, tilted(10, 12, 0.4f)));
		Photo copyA = new Photo("copy.jpg", DATE + 2000, axis(20));
		copyA.seed = "the copy";
		add("Lib/A", copyA);
		Photo copyB = new Photo("copy.jpg", DATE + 2000, axis(20));
		copyB.seed = "the copy";
		add("Lib/B", copyB);
		add("Lib/A", new Photo("alone.jpg", DATE + 3000, axis(30)));
	}

	private void library(String content) throws Exception {
		library(content, true);
	}

	private void library(String content, boolean check) throws Exception {
		Files.createDirectories(_base.resolve(UserStore.DIRECTORY_NAME));
		SpaceStore.store(_base, new SpaceStore.Config("Similar", SpaceStore.ANONYMOUS_PUBLIC, "", SpaceStore.FACES_OFF,
			"", "", content));
		UserStore users = new UserStore(_base);
		UserStore.User admin = new UserStore.User("haui", Roles.ADMIN, "", Instant.now().toString());
		admin.addDevice(new UserStore.Device("Phone", UserStore.hash(ADMIN), Instant.now().toString()));
		users.addUser(admin);
		UserStore.User guest = new UserStore.User("gus", Roles.VIEW, "", Instant.now().toString(), Clearances.PUBLIC,
			false);
		guest.addDevice(new UserStore.Device("Gus's phone", UserStore.hash(GUEST), Instant.now().toString()));
		users.addUser(guest);
		users.nameOwner("haui");
		users.store();
		for (Map.Entry<String, List<Photo>> album : _albums.entrySet()) {
			writeAlbum(album.getKey(), album.getValue());
		}
		AuthService auth = new AuthService(AuthMode.WRITES, _base);
		_servlet = new ImageServlet(_base.toFile(), auth, "", SpaceStore.load(_base, ""));
		_servlet.init();
		_servlet.searchIndex().build();
		_servlet.vectors().fill();
		if (check) {
			int described = 0;
			for (List<Photo> photos : _albums.values()) {
				for (Photo photo : photos) {
					described += photo.vector == null ? 0 : 1;
				}
			}
			assertEquals(described, _servlet.spaceIndex().vectors().size());
		}
	}

	private void writeAlbum(String path, List<Photo> photos) throws IOException {
		Path folder = _base.resolve(path);
		Files.createDirectories(folder);
		StringBuilder parts = new StringBuilder();
		for (Photo photo : photos) {
			Files.write(folder.resolve(photo.name), contents(photo));
			if (parts.length() > 0) {
				parts.append(',');
			}
			parts.append("[\"ImagePart\",{\"kind\":\"").append(photo.kind.name()).append("\",\"name\":\"")
				.append(photo.name).append("\",\"width\":16,\"height\":16,\"privacy\":").append(photo.privacy)
				.append(",\"rating\":").append(photo.rating).append(",\"date\":").append(photo.date).append("}]");
		}
		String title = path.substring(path.lastIndexOf('/') + 1);
		Files.writeString(folder.resolve("index.json"),
			"[\"AlbumInfo\",{\"title\":\"" + title + "\",\"parts\":[" + parts + "]}]");
		// The hashes the content step keys the vectors by, and the duplicates are told by.
		Map<String, String> hashes = new HashCache(folder.toFile()).hashByName();
		ContentCache.update(folder.toFile(), STAMP, ContentText.stamp(STAMP), cache -> {
			for (Photo photo : photos) {
				if (photo.vector == null) {
					continue;
				}
				ContentCache.ImageKey key = new ContentCache.ImageKey(hashes.get(photo.name), 0);
				String text = ContentCache.textHash("text of " + path + "/" + photo.name);
				cache.putImage(key, photo.vector);
				if (!Float.isNaN(photo.quality)) {
					cache.putQuality(key, photo.quality);
				}
				cache.putText(text, axis(255));
				cache.putPhoto(photo.name, new ContentCache.Photo(key, text));
			}
		});
	}

	/** The contents of a photograph's file: a tiny JPEG of its own, or a few bytes of a video. */
	private static byte[] contents(Photo photo) throws IOException {
		Random random = new Random(photo.seed.hashCode());
		if (photo.kind != ImageKind.IMAGE) {
			byte[] result = new byte[64];
			random.nextBytes(result);
			return result;
		}
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

	/** The first axis tilted towards an axis of its own: about 0.99 to every other of its kind. */
	private static float[] burstVector(int n) {
		return tilted(0, 100 + n, 0.1f);
	}

	private static float[] tilted(int main, int other, float amount) {
		float[] result = new float[Embedder.DIMENSION];
		result[main] = 1;
		result[other] = amount;
		return result;
	}

	private static float[] axis(int n) {
		float[] result = new float[Embedder.DIMENSION];
		result[n] = 1;
		return result;
	}

	private static float[] randomUnit(Random random) {
		float[] result = new float[Embedder.DIMENSION];
		return normalized(fill(result, random));
	}

	private static float[] fill(float[] vector, Random random) {
		for (int d = 0; d < vector.length; d++) {
			vector[d] = (float) random.nextGaussian();
		}
		return vector;
	}

	private static float[] normalized(float[] vector) {
		double sum = 0;
		for (float value : vector) {
			sum += value * value;
		}
		float[] result = new float[vector.length];
		for (int d = 0; d < vector.length; d++) {
			result[d] = (float) (vector[d] / Math.sqrt(sum));
		}
		return result;
	}

	private static int gray(int value) {
		return (value << 16) | (value << 8) | value;
	}

	/** The given content cache of version 2 as version 1 wrote it: without the qualities. */
	static byte[] version1(byte[] v2) {
		java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(v2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(v2, 0, 4);
		out.write(1);
		int dimension = in.getShort(5) & 0xFFFF;
		int at = 7;
		int stamp = in.getShort(at) & 0xFFFF;
		out.write(v2, 5, 2 + 2 + stamp);
		at += 2 + stamp;
		int images = in.getInt(at);
		out.write(v2, at, 4);
		at += 4;
		for (int n = 0; n < images; n++) {
			out.write(v2, at, 32 + 8);
			at += 32 + 8 + 4;
			out.write(v2, at, dimension * 4);
			at += dimension * 4;
		}
		out.write(v2, at, v2.length - 4 - at);
		byte[] body = out.toByteArray();
		java.util.zip.CRC32 crc = new java.util.zip.CRC32();
		crc.update(body);
		byte[] result = Arrays.copyOf(body, body.length + 4);
		java.nio.ByteBuffer.wrap(result, body.length, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
			.putInt((int) crc.getValue());
		return result;
	}

	// --- Asking. ---

	private AlbumInfo album(String path, String token) throws Exception {
		return album(get("/" + path + "/", token));
	}

	private static AlbumInfo album(FakeResponse response) throws IOException {
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue(response.body(), resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
	}

	private SimilarList similar(String token) throws Exception {
		FakeResponse response = similarResponse(token);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return SimilarList.readSimilarList(reader(response.body()));
	}

	private FakeResponse similarResponse(String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", ImageServlet.SIMILAR_TYPE);
		FakeResponse response = new FakeResponse();
		_servlet.doGet(request("/", "", token, parameters), response.response());
		return response;
	}

	private static List<List<String>> stacks(AlbumInfo album) {
		return stacks(album.getStacks());
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

	private static List<List<String>> groups(SimilarList list) {
		List<List<String>> result = new ArrayList<>();
		for (SimilarGroup group : list.getGroups()) {
			List<String> paths = new ArrayList<>();
			for (SimilarPhoto photo : group.getPhotos()) {
				paths.add(photo.getAlbum() + "/" + photo.getName());
			}
			Collections.sort(paths);
			result.add(paths);
		}
		return result;
	}

	private static List<String> names(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (de.haumacher.imageServer.shared.model.AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			}
		}
		return result;
	}

	private FakeResponse get(String path, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		_servlet.doGet(request(path, "", token, parameters), response.response());
		return response;
	}

	private FakeResponse put(String path, String body, String token) throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doPut(request(path, body, token, new HashMap<>()), response.response());
		return response;
	}

	private FakeResponse post(String path, String body, String token, Map<String, String> parameters)
			throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doPost(request(path, body, token, new HashMap<>(parameters)), response.response());
		return response;
	}

	private static jakarta.servlet.http.HttpServletRequest request(String path, String body, String token,
			Map<String, String> parameters) {
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		return TestImageServletPut.request(path, "application/json", body.getBytes(StandardCharsets.UTF_8), headers,
			parameters);
	}

	private static String errorMessage(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an error, got: " + response.body(), resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	private static JsonReader reader(String json) {
		return new JsonReader(new ReaderAdapter(new StringReader(json)));
	}
}
