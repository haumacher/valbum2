/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.faces.FaceImport;
import de.haumacher.imageServer.faces.FaceTags;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FaceInfo;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.FaceTag;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Person;
import de.haumacher.imageServer.shared.model.PersonList;
import de.haumacher.imageServer.shared.model.Resource;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Taking over the named faces an older library wrote into its photographs, see issue #129.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestXmpFaceImport extends FacesTestCase {

	/** The checked-in picture that names Alice and Bob, see <code>xmp-faces/README.md</code>. */
	private static final File FIXTURE = new File(Xmp.FIXTURE);

	private static final String NAMED = "named-faces.jpg";

	/**
	 * The two named faces of a file become two confirmed tags of two people, exactly once.
	 *
	 * <p>
	 * The whole story of the issue in one test: the first read takes the names over, a second read
	 * finds them as they are and creates nobody twice, and a name that somebody takes back stays
	 * gone &mdash; the sidecar lists the photograph, so it is never analysed again.
	 * </p>
	 */
	public void testTheNamesOfAFileAreTakenOverOnce() throws Exception {
		createSpace();
		Files.copy(FIXTURE.toPath(), album().toPath().resolve(NAMED));

		List<FaceTag> tags = tagsOf(NAMED);
		assertEquals("Both names were taken over.", 2, tags.size());
		assertBox("Alice", Xmp.ALICE, tags.get(0));
		assertBox("Bob", Xmp.BOB, tags.get(1));
		for (FaceTag tag : tags) {
			assertEquals("A name somebody wrote is a decision, not a guess.",
				FaceState.CONFIRMED, tag.getState());
		}

		List<Person> people = people();
		assertEquals("Two people arrived with the picture: " + people, 2, people.size());
		assertEquals("Alice", people.get(0).getName());
		assertEquals("Bob", people.get(1).getName());
		assertEquals(people.get(0).getId(), tags.get(0).getPerson());
		assertEquals(people.get(1).getId(), tags.get(1).getPerson());
		assertEquals("Nobody asked for them.", FaceImport.CREATED_BY, createdBy(people.get(0).getId()));

		assertTrue("The original was not touched.", java.util.Arrays.equals(
			Files.readAllBytes(FIXTURE.toPath()),
			Files.readAllBytes(new File(album(), NAMED).toPath())));

		// Read again: the same two tags, and not four people.
		List<FaceTag> again = tagsOf(NAMED);
		assertEquals(2, again.size());
		assertEquals(tags.get(0).getPerson(), again.get(0).getPerson());
		assertEquals(tags.get(1).getPerson(), again.get(1).getPerson());
		assertEquals(2, people().size());

		// Somebody takes both names back; the sidecar now lists the photograph without them.
		forget(NAMED);
		assertEquals(1, stored(NAMED).getTags().size());
		forget(NAMED);
		assertEquals("Both decisions are gone.", 0, stored(NAMED).getTags().size());

		restart();
		assertEquals("A name taken back never comes creeping back.", 0, tagsOf(NAMED).size());
		assertEquals("And nobody was created a second time.", 2, people().size());
	}

	/** One name twice is one person and two tags; a name the register knows is reused. */
	public void testOneNameTwiceIsOnePerson() throws Exception {
		createSpace();
		// The register already knows her, spelled differently.
		FakeResponse created = post("/", "create-person", "{\"name\":\"ALICE\"}", _adminToken);
		assertEquals(created.body(), 200, created.status());
		String alice = Person.readPerson(reader(created.body())).getId();

		write("twice.jpg", Xmp.FIXTURE_WIDTH, Xmp.FIXTURE_HEIGHT,
			Xmp.Region.corners("alice", 0.1, 0.1, 0.2, 0.2),
			Xmp.Region.corners("Alice ", 0.6, 0.1, 0.2, 0.2));

		List<FaceTag> tags = tagsOf("twice.jpg");
		assertEquals("A picture may show somebody twice.", 2, tags.size());
		assertEquals(alice, tags.get(0).getPerson());
		assertEquals("The very same person, whatever the spelling.", alice, tags.get(1).getPerson());
		assertEquals("Nobody was created beside her.", 1, people().size());
		assertEquals("ALICE", people().get(0).getName());
	}

	/** A raster that is not this file's is no raster at all: the whole list is dropped. */
	public void testAppliedToDimensionsMustMatch() throws Exception {
		createSpace();
		write("stale.jpg", Xmp.FIXTURE_WIDTH + 1, Xmp.FIXTURE_HEIGHT,
			Xmp.Region.corners("Alice", 0.2, 0.2, 0.2, 0.2));
		assertEquals("A resized copy carries regions of a picture that is gone.",
			0, tagsOf("stale.jpg").size());
		assertEquals(0, people().size());
	}

	/** A pet, an unnamed face and a region of no extent are not people. */
	public void testOnlyNamedFacesAreTakenOver() throws Exception {
		createSpace();
		write("mixed.jpg", Xmp.FIXTURE_WIDTH, Xmp.FIXTURE_HEIGHT,
			new Xmp.Region("Rex", "Pet", 0.2, 0.2, 0.1, 0.1),
			new Xmp.Region("", "Face", 0.5, 0.5, 0.1, 0.1),
			new Xmp.Region(null, "Face", 0.7, 0.5, 0.1, 0.1),
			new Xmp.Region("Nowhere", "Focus", 0.3, 0.3, 0.1, 0.1),
			Xmp.Region.corners("Bob", Xmp.BOB[0], Xmp.BOB[1], Xmp.BOB[2], Xmp.BOB[3]));

		List<FaceTag> tags = tagsOf("mixed.jpg");
		assertEquals("Only the named face of a person: " + tags, 1, tags.size());
		assertBox("Bob", Xmp.BOB, tags.get(0));
		assertEquals(1, people().size());
		assertEquals("Bob", people().get(0).getName());
	}

	/** An XMP packet of another shape is one line in the log and no tag, never an exception. */
	public void testMalformedXmpIsNoFailure() throws Exception {
		createSpace();
		File plain = plain("broken.jpg");
		Xmp.writePacket(plain, new File(album(), "broken.jpg"),
			"<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF>this is not XMP</x:xmpmeta>");

		File empty = plain("nothing.jpg");
		Xmp.writePacket(empty, new File(album(), "nothing.jpg"), "");

		AlbumInfo album = album("/" + ALBUM + "/", _adminToken);
		assertEquals("The album opens all the same.", 2, images(album).size());
		assertEquals(0, tagsOf("broken.jpg").size());
		assertEquals(0, tagsOf("nothing.jpg").size());
		assertEquals(0, people().size());
	}

	/** A space that did not ask for faces is given no people it never asked for. */
	public void testNothingIsImportedWhereFacesAreOff() throws Exception {
		_faces = false;
		createSpace();
		Files.copy(FIXTURE.toPath(), album().toPath().resolve(NAMED));

		assertEquals(0, tagsOf(NAMED).size());
		assertEquals(0, people().size());
	}

	/** A video is never asked what it says about people. */
	public void testAVideoIsUntouched() throws Exception {
		createSpace(VIDEO);
		Files.copy(FIXTURE.toPath(), album().toPath().resolve(NAMED));

		assertEquals("The picture named two.", 2, tagsOf(NAMED).size());
		assertEquals("The video named nobody.", 0, tagsOf(VIDEO).size());
		assertEquals("Nobody came out of the container.", 2, people().size());
	}

	/**
	 * A region is written in the raster of the file and stored in it, turned file and all.
	 *
	 * <p>
	 * The frame of a {@link FaceTag} is the raw raster, before the EXIF orientation, and so is an
	 * MWG area: a photograph whose pixels lie on their side carries its regions the same way its
	 * pixels lie. So the box is stored as it stands and the imported tag falls onto the face the
	 * detector finds there &mdash; which is what the second half of this test measures, where
	 * there is a detector.
	 * </p>
	 */
	public void testARotatedFileKeepsItsRaster() throws Exception {
		createSpace();
		File turned = new File(album(), "turned.jpg");
		File upright = plain("turned.jpg");
		Exif.writeOrientation(upright, turned, 6);
		File withRegions = File.createTempFile("valbum-turned", ".jpg");
		try {
			Files.copy(turned.toPath(), withRegions.toPath(),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			// The file lies on its side: its raw raster is still the one the picture was written
			// in, and that is what the regions are measured on.
			Xmp.writeRegions(withRegions, turned, Xmp.FIXTURE_WIDTH, Xmp.FIXTURE_HEIGHT,
				Xmp.Region.corners("Alice", Xmp.ALICE[0], Xmp.ALICE[1], Xmp.ALICE[2], Xmp.ALICE[3]));
		} finally {
			withRegions.delete();
		}

		ImagePart image = image(album("/" + ALBUM + "/", _adminToken), "turned.jpg");
		assertEquals("The picture is shown upright.", Xmp.FIXTURE_HEIGHT, image.getWidth());
		assertEquals(Xmp.FIXTURE_WIDTH, image.getHeight());

		List<FaceTag> tags = tagsOf("turned.jpg");
		assertEquals(1, tags.size());
		assertBox("Alice", Xmp.ALICE, tags.get(0));
	}

	/** What a file says and what the detector finds are one box: they meet by their overlap. */
	public void testAnImportedTagMeetsTheDetection() throws Exception {
		createSpace(B);
		if (!detectorAvailable()) {
			return;
		}
		index();
		List<FaceInfo> found = image(album("/" + ALBUM + "/", _adminToken), B).getFaces();
		assertEquals("The fixture shows one face.", 1, found.size());
		FaceInfo face = found.get(0);

		// The very box the detector measured, written into a copy of the file as an MWG region.
		File source = new File(PORTRAITS, B);
		com.drew.metadata.Metadata metadata = com.drew.imaging.ImageMetadataReader.readMetadata(source);
		com.drew.metadata.jpeg.JpegDirectory jpeg =
			metadata.getFirstDirectoryOfType(com.drew.metadata.jpeg.JpegDirectory.class);
		File target = new File(album(), "tagged.jpg");
		Xmp.writeRegions(source, target, jpeg.getImageWidth(), jpeg.getImageHeight(),
			Xmp.Region.corners("Bob", face.getX(), face.getY(), face.getW(), face.getH()));
		index();

		List<FaceTag> tags = tagsOf("tagged.jpg");
		assertEquals(1, tags.size());
		FaceTag tag = tags.get(0);
		double overlap = FaceTags.iou(tag.getX(), tag.getY(), tag.getW(), tag.getH(),
			face.getX(), face.getY(), face.getW(), face.getH());
		assertTrue("The name and the detection are about one face, overlap " + overlap,
			overlap > FaceTags.IOU_MATCH);

		List<FaceInfo> answered = image(album("/" + ALBUM + "/", _adminToken), "tagged.jpg").getFaces();
		assertEquals("One face, not two.", 1, answered.size());
		assertTrue("And it carries the name out of the file.", answered.get(0).isConfirmed());
		assertEquals(people().get(0).getId(), answered.get(0).getPerson());
	}

	// --- Helpers. ---

	/** Takes the first decision of the given photograph back, as the application does. */
	private void forget(String name) throws Exception {
		FakeResponse response = post("/" + ALBUM + "/", "tag-faces",
			"{\"faces\":[{\"image\":\"" + name
				+ "\",\"face\":0,\"person\":\"\",\"state\":\"UNDECIDED\"}]}",
			_adminToken);
		assertEquals(response.body(), 200, response.status());
	}

	/** Writes a copy of the fixture picture carrying the given regions into the album. */
	private void write(String name, int appliedWidth, int appliedHeight, Xmp.Region... regions)
			throws Exception {
		File plain = plain(name);
		Xmp.writeRegions(plain, new File(album(), name), appliedWidth, appliedHeight, regions);
	}

	/** A copy of the fixture picture without any XMP at all, in a temporary place. */
	private File plain(String name) throws Exception {
		File folder = new File(_base.toFile(), ".plain");
		folder.mkdirs();
		File result = new File(folder, name);
		// The fixture carries an XMP packet; a picture without one is the same pixels, so the
		// checked-in file is copied and the packet is replaced by whatever a test wants to say.
		Files.copy(FIXTURE.toPath(), result.toPath(),
			java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		byte[] jpeg = Files.readAllBytes(result.toPath());
		Files.write(result.toPath(), withoutApp1(jpeg));
		return result;
	}

	/** The given JPEG without any <code>APP1</code> segment, so that a test can write its own. */
	private static byte[] withoutApp1(byte[] jpeg) {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		out.write(jpeg, 0, 2);
		int at = 2;
		while (at + 4 <= jpeg.length && (jpeg[at] & 0xFF) == 0xFF) {
			int marker = jpeg[at + 1] & 0xFF;
			if (marker == 0xDA || marker == 0xD9) {
				break;
			}
			int length = ((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF);
			if (marker != 0xE1) {
				out.write(jpeg, at, 2 + length);
			}
			at += 2 + length;
		}
		out.write(jpeg, at, jpeg.length - at);
		return out.toByteArray();
	}

	/** The tags of the given photograph, as the album answers them. */
	private List<FaceTag> tagsOf(String name) throws Exception {
		return image(album("/" + ALBUM + "/", _adminToken), name).getTags();
	}

	/** The image of the given name as the album's own sidecar has it. */
	private ImagePart stored(String name) throws Exception {
		File file = new File(album(), "index.json");
		assertTrue("Nothing was written beside the photographs.", file.isFile());
		Resource resource = Resource.readResource(
			reader(Files.readString(file.toPath(), StandardCharsets.UTF_8)));
		for (ImagePart image : images((de.haumacher.imageServer.shared.model.FolderResource) resource)) {
			if (image.getName().equals(name)) {
				return image;
			}
		}
		fail("No image '" + name + "' in the sidecar.");
		return null;
	}

	/** The register of the space, in the order the people were created. */
	private List<Person> people() throws Exception {
		FakeResponse response = get("/", "people", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return new ArrayList<>(PersonList.readPersonList(reader(response.body())).getPeople());
	}

	/** What the register file says created the person of the given id. */
	private String createdBy(String id) throws Exception {
		String people = Files.readString(
			_base.resolve(".valbum").resolve("people.json"), StandardCharsets.UTF_8);
		int at = people.indexOf("\"id\":\"" + id + "\"");
		assertTrue("No person '" + id + "' in " + people, at >= 0);
		int from = people.indexOf("\"createdBy\":\"", at);
		assertTrue("No creator of '" + id + "' in " + people, from >= 0);
		from += "\"createdBy\":\"".length();
		return people.substring(from, people.indexOf('"', from));
	}

	private static void assertBox(String who, double[] expected, FaceTag tag) {
		assertEquals(who + "'s left edge", expected[0], tag.getX(), 1e-6);
		assertEquals(who + "'s top edge", expected[1], tag.getY(), 1e-6);
		assertEquals(who + "'s width", expected[2], tag.getW(), 1e-6);
		assertEquals(who + "'s height", expected[3], tag.getH(), 1e-6);
	}
}
