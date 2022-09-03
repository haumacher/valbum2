/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.cache;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.logging.Logger;

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
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.util.Orientations;

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
		Date date = date(metadata);
		if (date == null) {
			date = new Date(file.lastModified());
		}
		result.setDate(date.getTime());

		JpegDirectory jpegDirectory = metadata.getFirstDirectoryOfType(JpegDirectory.class);
		if (jpegDirectory != null) {
			result.setKind(ImagePart.Kind.IMAGE);
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
			result.setKind(ImagePart.Kind.IMAGE);

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
				if (rotation < 0) {
					rotation += 360;
				}
				
				Mp4VideoDirectory mp4VideoDirectory = metadata.getFirstDirectoryOfType(Mp4VideoDirectory.class);
				if (mp4VideoDirectory != null) {
					result.setKind(Kind.VIDEO);
					
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
				result.setKind(Kind.QUICKTIME);
				
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

	private static Date date(Metadata metadata) {
		ExifSubIFDDirectory directory = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);								
		if (directory == null) {
			return null;
		}
		return directory.getDateOriginal();
	}

}
