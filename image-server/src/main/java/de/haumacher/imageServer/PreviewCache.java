/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber.Exception;
import org.bytedeco.javacv.Java2DFrameConverter;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.png.PngDirectory;

import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.ui.layout.Content;
import de.haumacher.imageServer.shared.util.Orientations;
import de.haumacher.util.servlet.Util;

/**
 * Algorithm to generated and cache preview versions of image and video data.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class PreviewCache {

	private static final String MP4 = "mp4";

	private static final String PNG = "png";

	private static final String JPEG = "jpeg";

	private static final String JPG = "jpg";
	
	public static final Set<String> SUPPORTED_EXTENSIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(JPG, JPEG, PNG, MP4)));

	private static final int PREVIEW_HEIGHT = 600;
	
	private static final int PREVIEW_HEIGHT_PORTRAIT = 2 * PREVIEW_HEIGHT;
	
	private static final long LAST_UPDATE = lastUpdate();

	/**
	 * Time of the last update that required to re-build preview images.
	 */
	public static long lastUpdate() {
		try {
			return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzzz").parse("2022-05-15 13:30:00 MEST").getTime();
		} catch (ParseException ex) {
			Logger.getLogger(PreviewCache.class.getName()).log(Level.WARNING, "Failed to parse update time.", ex);
			return 0L;
		}
	}
	
	/** 
	 * Lookup or creates the preview data for the given image or video file.
	 */
	public static File createPreview(File file) throws PreviewException {
		String fileName = file.getName();
		String suffix = Util.suffix(fileName);
		String imageType = imageType(suffix);
		
		File cacheDir = new File(file.getParentFile(), ".vacache");
		File previewCache = new File(cacheDir, "preview-" + fileName + (suffix.equals(imageType) ? "" : "." + imageType));
		if (!previewCache.exists() || file.lastModified() > previewCache.lastModified() || previewCache.lastModified() < LAST_UPDATE) {
			if (!cacheDir.exists()) {
				cacheDir.mkdir();
			}
			switch (suffix) {
				case JPG:
				case JPEG:
				case PNG:
					try {
						createImagePreview(file, previewCache, imageType);
					} catch (ImageProcessingException | MetadataException | IOException ex) {
						throw new PreviewException("Cannot create image preview for '" + fileName  + "'.", ex);
					}
					break;
				case MP4:
					try {
						createVideoPreview(file, previewCache);
					} catch (ImageProcessingException | MetadataException | IOException ex) {
						throw new PreviewException("Cannot create video preview for '" + fileName  + "'.", ex);
					}
					break;
				default:
					throw new PreviewException("Unsupported format: " + fileName);
			}
		}
		return previewCache;
	}

	private static String imageType(String suffix) {
		switch (suffix) {
		case PNG: return PNG;
		}
		return JPG;
	}

	private static void createImagePreview(File file, File previewCache, String imgType)
			throws ImageProcessingException, IOException, MetadataException {
		Metadata metadata = ImageMetadataReader.readMetadata(file);
		ImageDimension dimension = getImageDimension(metadata);
		
		int origWidth = dimension.getWidth();
		int origHeight = dimension.getHeight();

		int previewHeight;

		double unitWidth = ((double) origWidth) / origHeight;
		if (unitWidth <= Content.MAX_PORTRAIT_UNIT_WIDTH) {
			previewHeight = PREVIEW_HEIGHT_PORTRAIT;
		} else {
			previewHeight = PREVIEW_HEIGHT;
		}
		
		BufferedImage orig = ImageIO.read(file);
		int rawWidth = orig.getWidth();
		int rawHeight = orig.getHeight();
		
		int previewWidth = ((int) Math.round(previewHeight / dimension.getRatio()));
		
		Orientation orientation = Orientations.fromCode(getImageOrientation(metadata));
		
		BufferedImage copy = new BufferedImage(previewWidth, previewHeight, orig.getType());
		Graphics2D g = (Graphics2D) copy.getGraphics();
		
		double scaleX = Math.min(1.0, ((double)previewWidth) / origWidth);
		double scaleY = Math.min(1.0, ((double)previewHeight) / origHeight);
		
		AffineTransform tx = new AffineTransform();
		tx.translate((previewWidth - rawWidth * scaleX) / 2, (previewHeight - rawHeight * scaleY) / 2);
		tx.scale(scaleX, scaleY);
		applyOrientation(tx, orientation, rawWidth / 2, rawHeight / 2);
		g.setTransform(tx);
		
		g.drawImage(orig, null, 0, 0);
		ImageIO.write(copy, imgType, previewCache);
	}

	private static void applyOrientation(AffineTransform tx, Orientation orientation, int centerX, int centerY) {
		int rotation = 0;
		boolean flip = false;
		switch (orientation) {
			case IDENTITY: 
				break;
			case FLIP_H:
				flip = true;
				break;
			case ROT_180:
				rotation = 180;
				break;
			case FLIP_V:
				rotation = 180;
				flip = true;
				break;
			case ROT_L_FLIP_V:
				rotation = -90;
				flip = true;
				break;
			case ROT_L:
				rotation = 90;
				break;
			case ROT_L_FLIP_H:
				rotation = 90;
				flip = true;
				break;
			case ROT_R:
				rotation = -90;
				break;
			default:
				break;
		}
		if (flip) {
			tx.scale(-1, 1);
		}
		tx.rotate(Math.toRadians(rotation), centerX, centerY);
	}

	private static ImageDimension getImageDimension(Metadata metadata) throws MetadataException {
		JpegDirectory jpegDirectory = metadata.getFirstDirectoryOfType(JpegDirectory.class);
		if (jpegDirectory != null) {
			int rawWidth = jpegDirectory.getImageWidth();
			int rawHeight = jpegDirectory.getImageHeight();
			int width, height;
			if (getImageOrientation(metadata) >= 5) {
				width = rawHeight;
				height = rawWidth;
			} else {
				width = rawWidth;
				height = rawHeight;
			}
			return new ImageDimension(width, height);
		}
		
		PngDirectory pngDirectory = metadata.getFirstDirectoryOfType(PngDirectory.class);
		if (pngDirectory != null) {
			int width = pngDirectory.getInt(PngDirectory.TAG_IMAGE_WIDTH);
			int height = pngDirectory.getInt(PngDirectory.TAG_IMAGE_HEIGHT);
			return new ImageDimension(width, height);
		}
		
		throw new IllegalArgumentException("Neither JPG nor PNG image.");
	}

	private static int getImageOrientation(Metadata metadata) throws MetadataException {
		ExifIFD0Directory exifIFD0Directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
		if (exifIFD0Directory != null && exifIFD0Directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
			return exifIFD0Directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
		}
		return 1;
	}

	private static void createVideoPreview(File file, File previewCache) throws Exception,
			ImageProcessingException, IOException, MetadataException {
		BufferedImage image = getPreviewFrame(file);
		
		Metadata metadata = ImageMetadataReader.readMetadata(file);
		Mp4Directory mp4Directory = metadata.getFirstDirectoryOfType(Mp4Directory.class);
		if (mp4Directory != null && mp4Directory.containsTag(Mp4Directory.TAG_ROTATION)) {
			int rotation = mp4Directory.getInt(Mp4Directory.TAG_ROTATION);
			while (rotation < 0) {
				rotation += 360;
			}
			if (rotation != 0) {
				// Apply transformation to the preview image.
				
				int rawWidth = image.getWidth();
				int rawHeight = image.getHeight();
				
				int width, height;
				if (rotation == 90 || rotation == 270) {
					width = rawHeight;
					height = rawWidth;
				} else {
					width = rawWidth;
					height = rawHeight;
				}
				
				BufferedImage copy = new BufferedImage(width, height, image.getType());
				Graphics2D g = (Graphics2D) copy.getGraphics();
				AffineTransform tx = new AffineTransform();
		        tx.translate((width - rawWidth) / 2, (height - rawHeight) / 2);
				tx.rotate(-Math.toRadians(rotation), rawWidth / 2, rawHeight / 2);
		        g.setTransform(tx);
		        g.drawImage(image, null, 0, 0);
		        
		        image = copy;
			}
		}
		ImageIO.write(image, JPG, previewCache);
	}

	private static BufferedImage getPreviewFrame(File file) throws Exception {
		try (FFmpegFrameGrabber g = new FFmpegFrameGrabber(file)) {
			g.start();
			Frame firstFrame = g.grabKeyFrame();
			try (Java2DFrameConverter converter = new Java2DFrameConverter()) {
				return converter.convert(firstFrame);
			}
		}
	}

}
