/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.cache;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.jpeg.JpegCommentDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mov.media.QuickTimeVideoDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.mp4.media.Mp4VideoDirectory;
import com.drew.metadata.png.PngDirectory;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.util.Orientations;
import java.io.File;
import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.logging.Logger;

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
	 * Loads {@link ImageData} from the given image file.
	 */
	public static ImageData analyze(AlbumInfo album, File file) throws ImageProcessingException, IOException, MetadataException {
		ImageData result = new ImageData(album, file, file.getName());

		Metadata metadata = ImageMetadataReader.readMetadata(file);
		result.setDate(date(metadata, file).getTime());

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
	 *         when neither the EXIF data nor the container say anything usable.
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

}
