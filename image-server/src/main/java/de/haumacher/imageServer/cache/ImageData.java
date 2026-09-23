/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.cache;

import com.adobe.internal.xmp.XMPException;
import com.adobe.internal.xmp.XMPMeta;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.jpeg.JpegCommentDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mov.media.QuickTimeVideoDirectory;
import com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.mp4.media.Mp4VideoDirectory;
import com.drew.metadata.png.PngDirectory;
import com.drew.metadata.xmp.XmpDirectory;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.GeoLocation;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.util.Orientations;
import java.io.File;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ImageData extends ImagePart {

	private static final Logger LOG = Logger.getLogger(ImageData.class.getName());
	private String _contentType;
	private File _file;

	/**
	 * Creates a {@link ImageData}.
	 */
	public ImageData(AlbumInfo owner, File file, String name) {
		super();

		setOwner(owner);
		setName(name);

		_file = file;
	}

	/**
	 * The {@link File} this {@link ImageInfo} was built for.
	 */
	public File getFile() {
		return _file;
	}

	/**
	 * What else is read out of a photograph at the moment it is first analysed.
	 *
	 * <p>
	 * The one hook of this class, and the one place the analysis of a file meets something that is
	 * not a property of the file alone: taking over the named faces an older tool wrote into it
	 * needs the people register of the space, see {@link
	 * de.haumacher.imageServer.faces.FaceImport} and issue #129. Everything else here &mdash; the
	 * date, the camera, the position &mdash; is read out of the file and nothing else, and stays
	 * where it is.
	 * </p>
	 *
	 * <p>
	 * It is asked for a photograph and never for a video, and it is asked exactly where the
	 * {@link ImagePart} is created: once per file, before the album's sidecar lists it.
	 * </p>
	 */
	public interface Analysis {

		/** Reads nothing at all, for every caller that has no register. */
		Analysis NONE = (image, metadata, rawWidth, rawHeight) -> {
			// Nothing to take over.
		};

		/**
		 * Writes onto the given part what the given metadata says beyond the file's own
		 * properties.
		 *
		 * @param image
		 *        The part being built for the file.
		 * @param metadata
		 *        What was read out of the file.
		 * @param rawWidth
		 *        The width of the file's own raster, before the EXIF orientation.
		 * @param rawHeight
		 *        The height of the file's own raster, before the EXIF orientation.
		 */
		void read(ImagePart image, Metadata metadata, int rawWidth, int rawHeight);
	}

	/**
	 * Loads {@link ImageData} from the given image file.
	 */
	public static ImageData analyze(AlbumInfo album, File file)
			throws ImageProcessingException, IOException, MetadataException {
		return analyze(album, file, Analysis.NONE);
	}

	/**
	 * Loads {@link ImageData} from the given image file, taking over what the given
	 * {@link Analysis} finds in a photograph.
	 */
	public static ImageData analyze(AlbumInfo album, File file, Analysis more) throws ImageProcessingException, IOException, MetadataException {
		ImageData result = new ImageData(album, file, file.getName());

		Metadata metadata = ImageMetadataReader.readMetadata(file);
		result.setDate(date(metadata, file).getTime());
		result.setCamera(camera(metadata));
		result.setLocation(location(metadata));

		JpegDirectory jpegDirectory = metadata.getFirstDirectoryOfType(JpegDirectory.class);
		if (jpegDirectory != null) {
			result.setKind(ImageKind.IMAGE);
			int rawWidth = jpegDirectory.getImageWidth();
			int rawHeight = jpegDirectory.getImageHeight();

			ExifIFD0Directory exifIFD0Directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
			int orientation = exifIFD0Directory == null || !exifIFD0Directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION) ? 1 : exifIFD0Directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);

			Orientation tx = Orientations.fromCode(orientation);
			result.setWidth(Orientations.width(tx, rawWidth, rawHeight));
			result.setHeight(Orientations.height(tx, rawWidth, rawHeight));

			JpegCommentDirectory jpegCommentDirectory = metadata.getFirstDirectoryOfType(JpegCommentDirectory.class);
			if (jpegCommentDirectory != null) {
				result.setComment(jpegCommentDirectory.getString(JpegCommentDirectory.TAG_COMMENT));
			}

			more.read(result, metadata, rawWidth, rawHeight);
			return result;
		}

		PngDirectory pngDirectory = metadata.getFirstDirectoryOfType(PngDirectory.class);
		if (pngDirectory != null) {
			result.setKind(ImageKind.IMAGE);

			int rawWidth = pngDirectory.getInt(PngDirectory.TAG_IMAGE_WIDTH);
			int rawHeight = pngDirectory.getInt(PngDirectory.TAG_IMAGE_HEIGHT);
			String comment = pngDirectory.getString(PngDirectory.TAG_TEXTUAL_DATA);
			if (comment != null) {
				result.setComment(comment);
			}

			Orientation tx = Orientation.IDENTITY;
			result.setWidth(Orientations.width(tx, rawWidth, rawHeight));
			result.setHeight(Orientations.height(tx, rawWidth, rawHeight));

			more.read(result, metadata, rawWidth, rawHeight);
			return result;
		}

		Mp4Directory mp4Directory = metadata.getFirstDirectoryOfType(Mp4Directory.class);
		if (mp4Directory != null) {
			try {
				int rotation;
				if (mp4Directory.hasTagName(Mp4Directory.TAG_ROTATION)) {
					rotation = mp4Directory.getInt(Mp4Directory.TAG_ROTATION);
				} else {
					rotation = 0;
				}
				while (rotation < 0) {
					rotation += 360;
				}

				Mp4VideoDirectory mp4VideoDirectory = metadata.getFirstDirectoryOfType(Mp4VideoDirectory.class);
				if (mp4VideoDirectory != null) {
					result.setKind(ImageKind.VIDEO);

					int rawWidth = mp4VideoDirectory.getInt(Mp4VideoDirectory.TAG_WIDTH);
					int rawHeight = mp4VideoDirectory.getInt(Mp4VideoDirectory.TAG_HEIGHT);

					if (rotation == 90 || rotation == 270) {
						result.setWidth(rawHeight);
						result.setHeight(rawWidth);
					} else {
						result.setWidth(rawWidth);
						result.setHeight(rawHeight);
					}

					return result;
				}
			} catch (MetadataException ex) {
				throw new IllegalArgumentException("Cannot get MP4 meta data: " + file);
			}
		}

		QuickTimeDirectory movDirectory = metadata.getFirstDirectoryOfType(QuickTimeDirectory.class);
		if (movDirectory != null) {
			int rotation;
			if (movDirectory.hasTagName(QuickTimeDirectory.TAG_ROTATION)) {
				rotation = movDirectory.getInt(QuickTimeDirectory.TAG_ROTATION);
			} else {
				rotation = 0;
			}

			QuickTimeVideoDirectory movVideoDirectory = metadata.getFirstDirectoryOfType(QuickTimeVideoDirectory.class);
			if (movVideoDirectory != null) {
				result.setKind(ImageKind.QUICKTIME);

				int rawWidth = movVideoDirectory.getInt(QuickTimeVideoDirectory.TAG_WIDTH);
				int rawHeight = movVideoDirectory.getInt(QuickTimeVideoDirectory.TAG_HEIGHT);

				if (rotation == 90 || rotation == 270) {
					result.setWidth(rawHeight);
					result.setHeight(rawWidth);
				} else {
					result.setWidth(rawWidth);
					result.setHeight(rawHeight);
				}

				return result;
			}
		}

		throw new IllegalArgumentException("Neither JPG, PNG, MOV, nor MP4 file: " + file);
	}

	/**
	 * The earliest time a container time is taken for a recording time, 1990-01-01 UTC.
	 *
	 * <p>
	 * An mp4 or QuickTime time is counted from 1904-01-01, so a field that was never filled in
	 * reads as that date (metadata-extractor hands it out as a {@link Date} like any other, it
	 * does not leave the tag out): the fixture video FFmpeg writes without a
	 * <code>creation_time</code> is such a file. Nothing in a photo album was recorded before
	 * consumer camcorders, so a container time below this bound is not a recording time but an
	 * unset or broken field, and the file's modification time is the better guess. The bound is
	 * deliberately a long way below any digital video and a long way above the 1904 epoch, so that
	 * it never has to be adjusted for a real recording.
	 * </p>
	 */
	public static final long EARLIEST_RECORDING =
		ZonedDateTime.of(1990, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();

	/**
	 * When the given file was taken or recorded.
	 *
	 * <p>
	 * A photo says so in its EXIF data. A video has no EXIF data at all, so before issue #72 every
	 * video got its modification time — for an uploaded or moved video the time it arrived on the
	 * server, later than every photo of the trip, which sorted all videos behind all photos. The
	 * recording time of a video is in its container instead, see {@link #recordingTime(Metadata)}.
	 * </p>
	 *
	 * @return The date to sort the part by, never <code>null</code>: the file's modification time
	 *         when neither the EXIF data, nor the container, nor the file name say anything
	 *         usable.
	 */
	private static Date date(Metadata metadata, File file) {
		ExifSubIFDDirectory directory = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
		if (directory != null) {
			Date dateOriginal = directory.getDateOriginal();
			if (dateOriginal != null) {
				return dateOriginal;
			}
		}
		Date recorded = recordingTime(metadata);
		if (recorded != null) {
			return recorded;
		}
		Date named = nameDate(file.getName());
		if (named != null) {
			return named;
		}
		return new Date(file.lastModified());
	}

	/**
	 * When the video was recorded, from the <code>mvhd</code> creation time of its container.
	 *
	 * <p>
	 * The time is taken as the library hands it out, exactly as the EXIF date is: the box is
	 * defined as UTC, but phones write local time into it and there is nothing in the file that
	 * says which of the two it is, so guessing would only move the error around.
	 * </p>
	 *
	 * @return <code>null</code> if this is no video, or its container carries no usable time, see
	 *         {@link #EARLIEST_RECORDING}.
	 */
	private static Date recordingTime(Metadata metadata) {
		Mp4Directory mp4Directory = metadata.getFirstDirectoryOfType(Mp4Directory.class);
		if (mp4Directory != null) {
			Date created = plausible(mp4Directory.getDate(Mp4Directory.TAG_CREATION_TIME));
			if (created != null) {
				return created;
			}
		}
		QuickTimeDirectory movDirectory = metadata.getFirstDirectoryOfType(QuickTimeDirectory.class);
		if (movDirectory != null) {
			return plausible(movDirectory.getDate(QuickTimeDirectory.TAG_CREATION_TIME));
		}
		return null;
	}

	/**
	 * What took the given file, see {@link ImagePart#getCamera()}.
	 *
	 * <p>
	 * The EXIF make and model of a photo. A video says it in the metadata of its container where
	 * it says it at all — the phones of the reproduction album say nothing, so their videos carry
	 * no camera.
	 * </p>
	 *
	 * @return The label, never <code>null</code>; the empty string when nothing is known.
	 */
	private static String camera(Metadata metadata) {
		ExifIFD0Directory exifIFD0Directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
		if (exifIFD0Directory != null) {
			String label = cameraLabel(exifIFD0Directory.getString(ExifIFD0Directory.TAG_MAKE),
				exifIFD0Directory.getString(ExifIFD0Directory.TAG_MODEL));
			if (!label.isEmpty()) {
				return label;
			}
		}
		QuickTimeMetadataDirectory movMetadata =
			metadata.getFirstDirectoryOfType(QuickTimeMetadataDirectory.class);
		if (movMetadata != null) {
			return cameraLabel(movMetadata.getString(QuickTimeMetadataDirectory.TAG_MAKE),
				movMetadata.getString(QuickTimeMetadataDirectory.TAG_MODEL));
		}
		return "";
	}

	/**
	 * Where the given file was taken, <code>null</code> when it says nowhere.
	 *
	 * <p>
	 * The EXIF GPS IFD of a photo, which metadata-extractor resolves into decimal degrees for us:
	 * it applies the reference letters, so a southern latitude and a western longitude arrive
	 * negative, and it answers <code>null</code> where the tags are missing or unreadable. A video
	 * is answered from the very same directory, which the library fills from the ISO 6709 location
	 * of an mp4 or QuickTime container where the container carries one — so a video says where it
	 * was recorded exactly when its container does, and nothing is guessed for the rest.
	 * </p>
	 *
	 * <p>
	 * Where the GPS IFD says nothing, the XMP of the file is asked, see {@link #xmpLocation} &mdash;
	 * some cameras and most tools that geotag afterwards write the position there and nowhere else
	 * (issue #161).
	 * </p>
	 *
	 * <p>
	 * A position at <code>0/0</code> is <em>no</em> position (issue #161, reversing #112): a camera
	 * with geotagging switched on writes a GPS IFD even without a fix, and then it writes zeros.
	 * Taken for a place, that put every such photograph into the Gulf of Guinea; the one real
	 * photograph taken there is the smaller loss. So a zero position is skipped like a missing one
	 * and the next source is asked, see {@link #isZero(GeoLocation)}.
	 * </p>
	 */
	private static GeoLocation location(Metadata metadata) {
		for (GpsDirectory gps : metadata.getDirectoriesOfType(GpsDirectory.class)) {
			com.drew.lang.GeoLocation position = gps.getGeoLocation();
			if (position == null || position.isZero()) {
				continue;
			}
			return GeoLocation.create()
				.setLatitude(position.getLatitude())
				.setLongitude(position.getLongitude());
		}
		GeoLocation xmp = xmpLocation(metadata);
		if (xmp != null) {
			return xmp;
		}
		QuickTimeMetadataDirectory movMetadata =
			metadata.getFirstDirectoryOfType(QuickTimeMetadataDirectory.class);
		if (movMetadata != null) {
			GeoLocation position = iso6709(movMetadata.getString(QuickTimeMetadataDirectory.TAG_LOCATION_ISO6709));
			return isZero(position) ? null : position;
		}
		return null;
	}

	/**
	 * Whether the given position is the pair of zeroes that means "not filled in", see
	 * {@link #location(Metadata)}.
	 *
	 * <p>
	 * Public, because a sidecar written before issue #161 may store exactly that, and the loader
	 * drops it on read with this same test.
	 * </p>
	 */
	public static boolean isZero(GeoLocation position) {
		return position != null && position.getLatitude() == 0.0 && position.getLongitude() == 0.0;
	}

	/** The EXIF namespace of XMP, where <code>exif:GPSLatitude</code> lives. */
	public static final String XMP_EXIF = "http://ns.adobe.com/exif/1.0/";

	/**
	 * The position the XMP of the file states, <code>null</code> where it states none.
	 *
	 * <p>
	 * <code>exif:GPSLatitude</code> and <code>exif:GPSLongitude</code>, the XMP mirror of the EXIF
	 * GPS tags, in the form XMP defines for a GPS coordinate: degrees, a comma, minutes with a
	 * decimal fraction or minutes and seconds, and the hemisphere letter behind it &mdash;
	 * <code>48,7.40736N</code> or <code>48,7,24.4416N</code>, see {@link #xmpCoordinate}. Read
	 * through metadata-extractor's {@link XmpDirectory} and Adobe's {@link XMPMeta}, the way the
	 * face regions of issue #129 are. Nothing here throws: an unreadable packet or a coordinate of
	 * another shape is one line in the log and no position.
	 * </p>
	 */
	private static GeoLocation xmpLocation(Metadata metadata) {
		for (XmpDirectory directory : metadata.getDirectoriesOfType(XmpDirectory.class)) {
			XMPMeta meta = directory.getXMPMeta();
			if (meta == null) {
				continue;
			}
			String latitude;
			String longitude;
			try {
				latitude = meta.getPropertyString(XMP_EXIF, "GPSLatitude");
				longitude = meta.getPropertyString(XMP_EXIF, "GPSLongitude");
			} catch (XMPException | RuntimeException ex) {
				LOG.warning("Cannot read the XMP position, ignoring it: " + ex.getMessage());
				continue;
			}
			if (latitude == null || longitude == null) {
				continue;
			}
			GeoLocation position = xmpPosition(latitude, longitude);
			if (position != null) {
				return position;
			}
		}
		return null;
	}

	/**
	 * The position the two XMP coordinates spell, <code>null</code> where they spell none.
	 *
	 * <p>
	 * The latitude must name <code>N</code> or <code>S</code> and lie within 90 degrees, the
	 * longitude <code>E</code> or <code>W</code> and lie within 180 &mdash; a coordinate carrying
	 * the other axis' letter is a swapped pair, and a wrong place on a map is worse than none. A
	 * pair of zeroes is no position, see {@link #location(Metadata)}.
	 * </p>
	 */
	public static GeoLocation xmpPosition(String latitude, String longitude) {
		Double lat = xmpCoordinate(latitude, 'N', 'S', 90.0);
		Double lon = xmpCoordinate(longitude, 'E', 'W', 180.0);
		if (lat == null || lon == null) {
			LOG.warning("Not an XMP position, ignoring it: " + latitude + " " + longitude);
			return null;
		}
		GeoLocation result = GeoLocation.create().setLatitude(lat.doubleValue()).setLongitude(lon.doubleValue());
		return isZero(result) ? null : result;
	}

	/** One XMP GPS coordinate: <code>DDD,MM.mmmK</code> or <code>DDD,MM,SS.sK</code>. */
	private static final Pattern XMP_COORDINATE =
		Pattern.compile("^(\\d{1,3}),(\\d{1,2}(?:\\.\\d+)?)(?:,(\\d{1,2}(?:\\.\\d+)?))?([NSEWnsew])$");

	/**
	 * The signed decimal degrees of one XMP GPS coordinate, <code>null</code> where it is none.
	 *
	 * @param positive
	 *        The hemisphere letter of a positive value.
	 * @param negative
	 *        The hemisphere letter of a negative value.
	 * @param limit
	 *        The largest number of degrees the axis has.
	 */
	private static Double xmpCoordinate(String value, char positive, char negative, double limit) {
		if (value == null) {
			return null;
		}
		Matcher matcher = XMP_COORDINATE.matcher(value.trim());
		if (!matcher.matches()) {
			return null;
		}
		double degrees = Double.parseDouble(matcher.group(1));
		double minutes = Double.parseDouble(matcher.group(2));
		double seconds = matcher.group(3) == null ? 0.0 : Double.parseDouble(matcher.group(3));
		if (minutes >= 60.0 || seconds >= 60.0 || (matcher.group(3) != null && minutes != Math.floor(minutes))) {
			return null;
		}
		double result = degrees + minutes / 60.0 + seconds / 3600.0;
		if (result > limit) {
			return null;
		}
		char letter = Character.toUpperCase(matcher.group(4).charAt(0));
		if (letter == positive) {
			return Double.valueOf(result);
		}
		if (letter == negative) {
			return Double.valueOf(-result);
		}
		return null;
	}

	/**
	 * The position an ISO 6709 string of a QuickTime container states, see {@link #location}.
	 *
	 * <p>
	 * What a phone writes into <code>com.apple.quicktime.location.ISO6709</code> is decimal
	 * degrees with an explicit sign and a trailing solidus,
	 * <code>+48.1235+008.6543+123.456/</code> — latitude, longitude, and an altitude this album
	 * has no use for. The two numbers are read as they stand.
	 * </p>
	 *
	 * <p>
	 * The standard also allows degrees and minutes (<code>+4807.41+00834.49/</code>), which looks
	 * exactly the same and means something else. That form is <em>refused</em> rather than guessed
	 * at: a latitude above 90 or a longitude above 180 degrees is not a decimal position, so the
	 * file is answered as saying nowhere and the log says why. A wrong place on a map is worse
	 * than no place at all.
	 * </p>
	 *
	 * @return The position, or <code>null</code> when the string is missing or is not one.
	 */
	public static GeoLocation iso6709(String value) {
		if (value == null) {
			return null;
		}
		Matcher matcher = ISO_6709.matcher(value.trim());
		if (!matcher.find()) {
			return null;
		}
		double latitude = Double.parseDouble(matcher.group(1));
		double longitude = Double.parseDouble(matcher.group(2));
		if (Math.abs(latitude) > 90.0 || Math.abs(longitude) > 180.0) {
			LOG.warning("Not a position in decimal degrees, ignoring it: " + value);
			return null;
		}
		return GeoLocation.create().setLatitude(latitude).setLongitude(longitude);
	}

	/** The latitude and the longitude of an ISO 6709 string, see {@link #iso6709(String)}. */
	private static final Pattern ISO_6709 =
		Pattern.compile("^([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)");

	/**
	 * The camera label of the given make and model, see {@link ImagePart#getCamera()}.
	 *
	 * <p>
	 * Both are trimmed and joined with a single blank, except that the make is left out when the
	 * model already begins with it as a whole word, ignoring case: <code>Canon</code> and
	 * <code>Canon EOS 5D</code> make <code>Canon EOS 5D</code>. Nothing else is taken away. A make
	 * the model does not repeat literally stays in front of it, even where that reads redundantly
	 * (<code>NIKON CORPORATION</code> and <code>NIKON D750</code> make
	 * <code>NIKON CORPORATION NIKON D750</code>): which words of a make are the brand is not
	 * knowable from two strings, and a label that keeps too much is still exactly as good at
	 * saying "the same camera" as one that guessed right, while a label that dropped the wrong
	 * word would merge two cameras.
	 * </p>
	 *
	 * @return The label, never <code>null</code>; the empty string when neither is given.
	 */
	public static String cameraLabel(String make, String model) {
		String cleanMake = clean(make);
		String cleanModel = clean(model);
		if (cleanMake.isEmpty()) {
			return cleanModel;
		}
		if (cleanModel.isEmpty()) {
			return cleanMake;
		}
		if (startsWithWord(cleanModel, cleanMake)) {
			return cleanModel;
		}
		return cleanMake + " " + cleanModel;
	}

	/** The given EXIF string without its padding, and with any run of blanks collapsed into one. */
	private static String clean(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().replaceAll("\\s+", " ");
	}

	/** Whether the model begins with the make, as a whole word and ignoring case. */
	private static boolean startsWithWord(String model, String make) {
		if (!model.regionMatches(true, 0, make, 0, make.length())) {
			return false;
		}
		return model.length() == make.length() || model.charAt(make.length()) == ' ';
	}

	/** The given container time, or <code>null</code> if it cannot be a recording time. */
	private static Date plausible(Date date) {
		if (date == null) {
			return null;
		}
		if (date.getTime() < EARLIEST_RECORDING) {
			LOG.fine("Ignoring the container time " + date + ": before " + new Date(EARLIEST_RECORDING) + ".");
			return null;
		}
		return date;
	}

	/**
	 * The earliest year a date in a file name is taken for a recording time.
	 *
	 * <p>
	 * The same bound as {@link #EARLIEST_RECORDING}, and for the same reason: a run of digits that
	 * reads as 1970 or 1904 is a counter or an unset field, not a recording.
	 * </p>
	 */
	private static final int EARLIEST_NAME_YEAR = 1990;

	/**
	 * The date forms a file name is searched for, see {@link #nameDate(String)}.
	 *
	 * <p>
	 * One alternation, tried in this order at every position of the name, so that the longest and
	 * most specific form wins where several would match at the same place:
	 * </p>
	 *
	 * <ol>
	 * <li><code>YYYYMMDD</code>, an optional <code>_</code>, <code>-</code> or blank, and
	 * <code>HHMMSS</code> followed by any further digits — the sub-second digits of
	 * <code>PXL_20240315_142233123.mp4</code> and the shape of <code>VID_</code>,
	 * <code>IMG_</code>, <code>Screenshot_</code> and a bare
	 * <code>20240315_142233.mp4</code>.</li>
	 * <li><code>YYYY-MM-DD</code>, a separator of <code> at </code>, <code>_</code>, a blank or
	 * <code>T</code>, and a time whose two separators are the same character (<code>.</code>,
	 * <code>-</code>, <code>:</code> or nothing) — <code>WhatsApp Video 2024-03-15 at
	 * 14.22.33.mp4</code> and its relatives.</li>
	 * <li>A bare <code>YYYY-MM-DD</code> or <code>YYYYMMDD</code> not followed by a further digit
	 * — that day at midnight.</li>
	 * </ol>
	 *
	 * <p>
	 * Every form that begins with an undelimited run of digits is guarded by
	 * <code>(?&lt;!\d)</code>: a date starts where a digit run starts, so a sixteen-digit counter
	 * cannot be read as a date by chopping four digits off its front.
	 * </p>
	 */
	private static final Pattern NAME_DATE = Pattern.compile(
		// YYYYMMDD [sep] HHMMSS [further digits]
		"(?<!\\d)(\\d{4})(\\d{2})(\\d{2})[_\\- ]?(\\d{2})(\\d{2})(\\d{2})\\d*"
			// YYYY-MM-DD [sep] HH[.:-]MM[.:-]SS [further digits]
			+ "|(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?: at |[_ T])(\\d{2})([.:\\-]?)(\\d{2})\\11(\\d{2})\\d*"
			// A bare day, in either spelling.
			+ "|(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)"
			+ "|(?<!\\d)(\\d{4})(\\d{2})(\\d{2})(?!\\d)");

	/**
	 * The recording time a file name carries, see issue #102.
	 *
	 * <p>
	 * A camera, a phone and a messenger all write the moment of the recording into the name they
	 * give a file, and for a video that is often the only place it survives: the container time is
	 * lost in every re-encode, and the file's modification time is the time of the copy. So the
	 * name is asked after the file itself has been asked and before its modification time is
	 * taken, see {@link #date(Metadata, File)}.
	 * </p>
	 *
	 * <p>
	 * The first match of {@link #NAME_DATE} that is a real date wins, the search going on one
	 * character further where it is not: a name may well carry a number in front of the date, and
	 * a run of digits that is no date must not stop the one that is. A date is real when its year
	 * lies between {@value #EARLIEST_NAME_YEAR} and the year after the current one — nothing in an
	 * album was recorded before that, and nothing was recorded the year after next — and when the
	 * day exists and the time is a time of day.
	 * </p>
	 *
	 * <p>
	 * The result is read in the server's default zone, exactly as an EXIF date and a container
	 * time are taken as they come: the name carries no zone, so anything else would only move the
	 * error around.
	 * </p>
	 *
	 * @param fileName
	 *        The plain name of the file, without a path.
	 * @return The time the name says, or <code>null</code> when it says none.
	 */
	public static Date nameDate(String fileName) {
		if (fileName == null) {
			return null;
		}
		Matcher matcher = NAME_DATE.matcher(fileName);
		int from = 0;
		while (from <= fileName.length() && matcher.find(from)) {
			Date result = dateOf(matcher);
			if (result != null) {
				return result;
			}
			from = matcher.start() + 1;
		}
		return null;
	}

	/**
	 * The date of the group that matched, <code>null</code> when those digits are no date.
	 */
	private static Date dateOf(Matcher matcher) {
		if (matcher.group(1) != null) {
			return at(matcher, 1, 2, 3, 4, 5, 6);
		}
		if (matcher.group(7) != null) {
			return at(matcher, 7, 8, 9, 10, 12, 13);
		}
		if (matcher.group(14) != null) {
			return at(matcher, 14, 15, 16, -1, -1, -1);
		}
		return at(matcher, 17, 18, 19, -1, -1, -1);
	}

	/** The date the given groups spell, <code>null</code> when it is none. */
	private static Date at(Matcher matcher, int year, int month, int day, int hour, int minute, int second) {
		int y = number(matcher, year);
		if (y < EARLIEST_NAME_YEAR || y > ZonedDateTime.now().getYear() + 1) {
			return null;
		}
		try {
			LocalDateTime local = LocalDateTime.of(y, number(matcher, month), number(matcher, day),
				number(matcher, hour), number(matcher, minute), number(matcher, second));
			return Date.from(local.atZone(ZoneId.systemDefault()).toInstant());
		} catch (DateTimeException ex) {
			// A month, a day of a month, or a time of day that does not exist: these digits are
			// no date, and the search goes on.
			return null;
		}
	}

	/** The number the given group holds, zero for a group the form has not got. */
	private static int number(Matcher matcher, int group) {
		return group < 0 ? 0 : Integer.parseInt(matcher.group(group));
	}

}
