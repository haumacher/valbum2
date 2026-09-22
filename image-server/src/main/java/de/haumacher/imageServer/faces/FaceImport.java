/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.faces;

import com.drew.metadata.Metadata;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.FaceTag;
import de.haumacher.imageServer.shared.model.ImagePart;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Taking over the named faces an older tool wrote into a photograph, see issue #129.
 *
 * <h2>When</h2>
 *
 * <p>
 * At the one moment a photograph is first looked at: when the loader builds an {@link ImagePart}
 * for a file the album's sidecar does not list yet (see {@code ResourceCache.Loader}). That is the
 * same moment the date, the camera and the position are read out of the file, and it is the same
 * rule that makes the import run exactly once: <b>a part the sidecar already lists is never
 * analysed again</b>, so the sidecar itself is the marker and no file of our own is ever written
 * for it.
 * </p>
 *
 * <p>
 * Which is also why an import is never undone by a second one. Somebody who takes an imported tag
 * back (issue #138) leaves the part in the sidecar with an empty tag list, and that part is never
 * analysed again &mdash; the name does not come creeping back on the next restart.
 * </p>
 *
 * <h2>What</h2>
 *
 * <p>
 * A named face region of the file becomes a {@link FaceState#CONFIRMED} {@link FaceTag}: somebody
 * once sat in front of Picasa or digiKam and said who that is, which is a decision and not a
 * guess. The box is the region's, converted from the centre form of the Metadata Working Group
 * into the corner form of a tag and left in the frame it was written in &mdash; the raw raster of
 * the file, which is the frame {@link FaceTag} stores, see {@link XmpFaces} and {@link Faces}.
 * Nothing is scaled and nothing is turned, so the imported tag and the box the detector finds for
 * the same face are in one frame and meet each other by {@link FaceTags#IOU_MATCH} like any other
 * pair.
 * </p>
 *
 * <h2>Who</h2>
 *
 * <p>
 * The name of a region is looked up in the register of the space, ignoring case and outer blanks,
 * and an unknown one <b>creates a person</b> with {@link #CREATED_BY} as their creator, so a
 * library that names fifty people arrives with fifty people in it and the names in two albums are
 * the same person. Two regions naming the same person in one picture are two tags of that one
 * person &mdash; a photograph may well show somebody twice, in a mirror or in a picture on the
 * wall, and the file says two, so two are stored.
 * </p>
 *
 * <p>
 * Nothing here ever throws and nothing is ever written into the original: a register that cannot
 * be written, a name it refuses and an XMP packet of another shape are each one line in the log
 * and no tag, see {@link XmpFaces}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class FaceImport {

	private static final Logger LOG = Logger.getLogger(FaceImport.class.getName());

	/**
	 * Who a person created by an import was created by.
	 *
	 * <p>
	 * Not a {@code Caller.subject()} like every other creator, because nobody asked: the name
	 * comes out of the file, and the register says so rather than pretending that whoever happened
	 * to open the album named them.
	 * </p>
	 */
	public static final String CREATED_BY = "import";

	private final PeopleStore _people;

	private final boolean _enabled;

	/**
	 * Creates a {@link FaceImport}.
	 *
	 * @param people
	 *        The register of the space the names are looked up in, <code>null</code> where there
	 *        is none.
	 * @param enabled
	 *        Whether this space asked for faces at all, see {@link
	 *        de.haumacher.imageServer.auth.SpaceStore.Config#isFacesEnabled()}. A space that did
	 *        not is not given people it never asked for.
	 */
	public FaceImport(PeopleStore people, boolean enabled) {
		_people = people;
		_enabled = enabled && people != null;
	}

	/** Whether anything is imported at all, see {@link #FaceImport(PeopleStore, boolean)}. */
	public boolean isEnabled() {
		return _enabled;
	}

	/**
	 * Writes the named faces of the given file onto the given part, at analysis time.
	 *
	 * @param image
	 *        The part that is being built for the file; it carries no tag yet.
	 * @param metadata
	 *        What was read out of the file.
	 * @param rawWidth
	 *        The width of the file's own raster, before the EXIF orientation.
	 * @param rawHeight
	 *        The height of the file's own raster, before the EXIF orientation.
	 */
	public void read(ImagePart image, Metadata metadata, int rawWidth, int rawHeight) {
		if (!_enabled || image == null || !image.getTags().isEmpty()) {
			return;
		}
		List<XmpFaces.Region> regions;
		try {
			regions = XmpFaces.read(metadata, rawWidth, rawHeight, image.getName());
		} catch (RuntimeException ex) {
			LOG.log(Level.WARNING,
				"Cannot read the face regions of '" + image.getName() + "': " + ex.getMessage(), ex);
			return;
		}
		if (regions.isEmpty()) {
			return;
		}
		List<FaceTag> tags = new ArrayList<>(regions.size());
		for (XmpFaces.Region region : regions) {
			String person = person(region.getName(), image.getName());
			if (person == null) {
				continue;
			}
			tags.add(FaceTag.create()
				.setX(region.getX())
				.setY(region.getY())
				.setW(region.getW())
				.setH(region.getH())
				.setPerson(person)
				.setState(FaceState.CONFIRMED));
		}
		if (tags.isEmpty()) {
			return;
		}
		image.setTags(tags);
		LOG.info("Took over " + tags.size() + " named face(s) from the metadata of '"
			+ image.getName() + "'.");
	}

	/**
	 * The id of the person of the given name, creating them where the register has none.
	 *
	 * @return <code>null</code> when the register refuses the name or cannot be written.
	 */
	private String person(String name, String file) {
		try {
			// Unsplit: a tool wrote this name, so a bracket in it belongs to the name and the
			// nickname convention of issue #146 does not apply, see PeopleStore.named(...).
			PeopleStore.Entry entry = _people.named(name, CREATED_BY);
			return entry == null ? null : entry.getId();
		} catch (PeopleStore.PersonRefused | java.io.IOException ex) {
			LOG.warning("Cannot take over the face of '" + name + "' in '" + file + "': "
				+ ex.getMessage());
			return null;
		}
	}
}
