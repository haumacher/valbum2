/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import static de.haumacher.imageServer.TestImageServletPut.request;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.InviteMode;
import de.haumacher.imageServer.auth.SpaceMode;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * The recording time of a photograph, see issue #183.
 *
 * <p>
 * An EXIF <code>DateTimeOriginal</code> is a wall clock. It is a moment with the photo's own
 * <code>OffsetTimeOriginal</code>, else with the offset its GPS time says, else in the zone of the
 * space the file lies in (<code>space.json</code> <code>timeZone</code>, else the server's zone).
 * Before issue #183 the wall clock was read as UTC wherever the offset was missing.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestPhotoDate extends TestCase {

	private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

	private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

	private static final String SUMMER = "2020:06:15 12:00:00";

	private static final String WINTER = "2020:01:15 12:00:00";

	private Path _base;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-photo-date");
	}

	@Override
	protected void tearDown() throws Exception {
		for (ImageServlet servlet : _servlets) {
			servlet.destroy();
		}
		_servlets.clear();
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	// --- 1. The photo's own offset. ---

	public void testTheOffsetOfThePhotoIsApplied() throws Exception {
		File file = photo("offset.jpg", new Exif.Dates().original(SUMMER).offset("+02:00").subSeconds("250"));

		ImageData image = analyze(file, TOKYO);

		assertEquals("The zone of the space does not matter where the photo says its offset.",
			instant("2020-06-15T10:00:00.250Z"), image.getDate());
		assertNull("The old reading applied the offset, too.", image.getLegacyExifDate());
	}

	public void testTheOffsetWinsOverTheGpsTime() throws Exception {
		File file = photo("both.jpg", new Exif.Dates().original(SUMMER).offset("-03:00").gps("2020:06:15", 10, 0, 0));

		assertEquals(instant("2020-06-15T15:00:00Z"), analyze(file, BERLIN).getDate());
	}

	// --- 2. The GPS time. ---

	public void testTheGpsTimeSaysTheOffset() throws Exception {
		// A fix a few seconds before the shutter: the drift is rounded away.
		File file = photo("gps.jpg", new Exif.Dates().original(SUMMER).gps("2020:06:15", 10, 0, 7));

		ImageData image = analyze(file, TOKYO);

		assertEquals("The GPS offset wins over the zone of the space.", instant("2020-06-15T10:00:00Z"),
			image.getDate());
		assertEquals("The old reading took the wall clock as UTC.", Long.valueOf(instant("2020-06-15T12:00:00Z")),
			image.getLegacyExifDate());
	}

	public void testTheGpsOffsetIsRoundedToAQuarterOfAnHour() throws Exception {
		// Kathmandu, +05:45: the wall clock says 12:00, the GPS 06:14:50 UTC; the difference of
		// 5:45:10 is the offset 5:45.
		File file = photo("nepal.jpg", new Exif.Dates().original(SUMMER).gps("2020:06:15", 6, 14, 50));

		assertEquals(instant("2020-06-15T06:15:00Z"), analyze(file, BERLIN).getDate());
	}

	public void testAGpsTimeOfAnotherDayIsIgnored() throws Exception {
		File file = photo("stale.jpg", new Exif.Dates().original(SUMMER).gps("2020:06:12", 10, 0, 0));

		assertEquals("A fix three days old says no offset; the zone of the space decides.",
			instant("2020-06-15T03:00:00Z"), analyze(file, TOKYO).getDate());
	}

	public void testAGpsTimeBeyondFourteenHoursIsIgnored() throws Exception {
		// 12:00 against 21:30 of the previous day: 14:30 is more than any zone of the world.
		File file = photo("far.jpg", new Exif.Dates().original(SUMMER).gps("2020:06:14", 21, 30, 0));

		assertEquals(instant("2020-06-15T10:00:00Z"), analyze(file, BERLIN).getDate());
	}

	// --- 3. The zone of the space. ---

	public void testTheWallClockIsReadInTheZoneOfTheSpaceInSummer() throws Exception {
		File file = photo("summer.jpg", new Exif.Dates().original(SUMMER));

		ImageData image = analyze(file, BERLIN);

		assertEquals(instant("2020-06-15T10:00:00Z"), image.getDate());
		assertEquals(Long.valueOf(instant("2020-06-15T12:00:00Z")), image.getLegacyExifDate());
	}

	public void testTheWallClockIsReadInTheZoneOfTheSpaceInWinter() throws Exception {
		File file = photo("winter.jpg", new Exif.Dates().original(WINTER));

		assertEquals(instant("2020-01-15T11:00:00Z"), analyze(file, BERLIN).getDate());
	}

	public void testTheSubSecondsCountWithoutAnOffset() throws Exception {
		File file = photo("fraction.jpg", new Exif.Dates().original(SUMMER).subSeconds("500"));

		ImageData image = analyze(file, BERLIN);

		assertEquals(instant("2020-06-15T10:00:00.500Z"), image.getDate());
		assertEquals("The old reading counted them, too.", Long.valueOf(instant("2020-06-15T12:00:00.500Z")),
			image.getLegacyExifDate());
	}

	public void testASpaceWithoutTimeZoneFollowsTheServersZone() throws Exception {
		File file = photo("server.jpg", new Exif.Dates().original(SUMMER));
		TimeZone before = TimeZone.getDefault();
		TimeZone.setDefault(TimeZone.getTimeZone(NEW_YORK));
		try {
			SpaceStore.Config config = SpaceStore.load(_base, "plain");
			assertEquals("", config.getTimeZone());
			assertEquals(NEW_YORK, config.getZone());

			ResourceCache cache = new ResourceCache(ImageData.Analysis.NONE, null);
			try {
				assertEquals(NEW_YORK, cache.zone());
				assertEquals(instant("2020-06-15T16:00:00Z"),
					ImageData.analyze(null, file, ImageData.Analysis.NONE, cache.zone()).getDate());
			} finally {
				cache.close();
			}
			assertEquals("The analysis without a zone is the server's zone, too.", instant("2020-06-15T16:00:00Z"),
				ImageData.analyze(null, file).getDate());
		} finally {
			TimeZone.setDefault(before);
		}
	}

	public void testAnUnknownTimeZoneIsTheServersZone() throws Exception {
		SpaceStore.Config config = new SpaceStore.Config("x", SpaceStore.ANONYMOUS_NONE, "", SpaceStore.FACES_OFF,
			"Nowhere/Zone");

		assertEquals("What the file says is kept.", "Nowhere/Zone", config.getTimeZone());
		assertEquals(ZoneId.systemDefault(), config.getZone());
	}

	public void testAFileNameDateIsReadInTheZoneOfTheSpace() throws Exception {
		assertEquals(instant("2024-03-15T13:22:33Z"), ImageData.nameDate("IMG_20240315_142233.jpg", BERLIN).getTime());
		assertEquals(instant("2024-03-15T05:22:33Z"), ImageData.nameDate("IMG_20240315_142233.jpg", TOKYO).getTime());

		// And the chain asks the name in the same zone where the file itself says nothing.
		File png = new File(_base.toFile(), "Screenshot_20240315-142233.png");
		ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", png);
		assertEquals(instant("2024-03-15T13:22:33Z"), analyze(png, BERLIN).getDate());
	}

	// --- The zone reaches the analysis per space. ---

	public void testTwoSpacesDateTheSameFileEachInItsOwnZone() throws Exception {
		Path berlin = _base.resolve("berlin");
		Path newYork = _base.resolve("newyork");
		SpaceStore.create(berlin, "", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_OFF, "Europe/Berlin");
		SpaceStore.create(newYork, "", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_OFF, "America/New_York");
		for (Path space : List.of(berlin, newYork)) {
			Files.createDirectories(space.resolve("Trip"));
			Exif.writeDates(space.resolve("Trip").resolve("same.jpg").toFile(), new Exif.Dates().original(SUMMER));
		}

		Spaces spaces = Spaces.detect(_base, null, AuthMode.OFF, InviteMode.MEMBERS);
		assertEquals(SpaceMode.MULTI, spaces.getMode());
		Map<String, Long> dates = new HashMap<>();
		for (Spaces.Space space : spaces.getSpaces()) {
			ImageServlet servlet = new ImageServlet(space.getRoot().toFile(), space.getAuth(), space.getSegment(),
				space.getConfig());
			_servlets.add(servlet);
			dates.put(space.getSegment(), Long.valueOf(image(album(servlet, "/Trip/"), "same.jpg").getDate()));
		}

		assertEquals(Long.valueOf(instant("2020-06-15T10:00:00Z")), dates.get("berlin"));
		assertEquals(Long.valueOf(instant("2020-06-15T16:00:00Z")), dates.get("newyork"));
	}

	// --- space.json. ---

	public void testTheTimeZoneIsWrittenReadAndKept() throws Exception {
		Path space = _base.resolve("family");
		SpaceStore.create(space, "Family", SpaceStore.ANONYMOUS_PUBLIC, SpaceStore.FACES_ON, "Europe/Berlin");
		assertTrue(Files.readString(SpaceStore.file(space), StandardCharsets.UTF_8)
			.contains("\"timeZone\":\"Europe/Berlin\""));

		SpaceStore.Config config = SpaceStore.load(space, "family");
		assertEquals("Europe/Berlin", config.getTimeZone());
		assertEquals(BERLIN, config.getZone());

		// move-into-space rewrites the file and keeps the zone.
		SpaceStore.rewrite(space, "Renamed", SpaceStore.ANONYMOUS_NONE);
		assertEquals("Europe/Berlin", SpaceStore.load(space, "family").getTimeZone());
		assertEquals("Renamed", SpaceStore.load(space, "family").getName());

		// And a configuration stored whole keeps it, too.
		SpaceStore.store(space, SpaceStore.load(space, "family"));
		assertEquals("Europe/Berlin", SpaceStore.load(space, "family").getTimeZone());
	}

	public void testASpaceWithoutTimeZoneWritesNone() throws Exception {
		Path space = _base.resolve("plain");
		SpaceStore.create(space, "", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_OFF, null);

		assertFalse(Files.readString(SpaceStore.file(space), StandardCharsets.UTF_8).contains("timeZone"));
		SpaceStore.rewrite(space, "", SpaceStore.ANONYMOUS_NONE);
		assertFalse("A rewrite invents no zone.",
			Files.readString(SpaceStore.file(space), StandardCharsets.UTF_8).contains("timeZone"));
	}

	public void testAnUnknownTimeZoneIsNeverWritten() throws Exception {
		Path space = _base.resolve("nowhere");
		try {
			SpaceStore.create(space, "", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_OFF, "Nowhere/Zone");
			fail("An unknown zone must be refused.");
		} catch (IllegalArgumentException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("Nowhere/Zone"));
		}
		assertFalse(Files.exists(SpaceStore.file(space)));
	}

	// --- Helpers. ---

	private File photo(String name, Exif.Dates dates) throws Exception {
		File file = new File(_base.toFile(), name);
		Exif.writeDates(file, dates);
		return file;
	}

	private static ImageData analyze(File file, ZoneId zone) throws Exception {
		return ImageData.analyze(AlbumInfo.create(), file, ImageData.Analysis.NONE, zone);
	}

	private static long instant(String iso) {
		return Instant.parse(iso).toEpochMilli();
	}

	private static AlbumInfo album(ImageServlet servlet, String path) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		servlet.doGet(request(path, null, new byte[0], Map.of(), parameters), response.response());
		assertEquals("Unexpected answer: " + response.body(), HttpServletResponse.SC_OK, response.status());
		Resource resource = Resource.readResource(new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
		assertTrue("Expected an album, got: " + resource, resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
	}

	private static ImagePart image(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return (ImagePart) part;
			}
		}
		fail("No part '" + name + "' in the album.");
		return null;
	}
}
