/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

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
import com.drew.metadata.exif.ExifThumbnailDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.mp4.Mp4Directory;

/**
 * Algorithm to generated and cache preview versions of image and video data.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class PreviewCache {

	private static final int PREVIEW_HEIGHT = 600;
	
	/** 
	 * Lookup or creates the preview data for the given image or video file.
	 */
	public static File createPreview(File file) throws PreviewException {
		String name = file.getName();
		String suffix = Util.suffix(name);
		
		File cacheDir = new File(file.getParentFile(), ".vacache");
		File previewCache = new File(cacheDir, "preview-" + file.getName() + (suffix.equals("jpg") ? "" : ".jpg"));
		if (!previewCache.exists() || file.lastModified() > previewCache.lastModified()) {
			if (!cacheDir.exists()) {
				cacheDir.mkdir();
			}
			switch (suffix) {
				case "jpg":
				case "jpeg":
					try {
						createImagePreview(file, previewCache);
					} catch (ImageProcessingException | MetadataException | IOException ex) {
						throw new PreviewException("Cannot create image preview for '" + name  + "'.", ex);
					}
					break;
				case "mp4":
					try {
						createVideoPreview(file, previewCache);
					} catch (ImageProcessingException | MetadataException | IOException ex) {
						throw new PreviewException("Cannot create video preview for '" + name  + "'.", ex);
					}
					break;
				default:
					throw new PreviewException("Unsupported format: " + name);
			}
		}
		return previewCache;
	}

	private static void createImagePreview(File file, File previewCache)
			throws ImageProcessingException, IOException, MetadataException {
		Metadata metadata = ImageMetadataReader.readMetadata(file);
		ImageDimension dimension = getImageDimension(metadata);
		
		if (dimension.getHeight() <= PREVIEW_HEIGHT) {
			// Use orig as preview.
			Util.copy(file, previewCache);
		} else {
			BufferedImage orig = ImageIO.read(file);
			int rawWidth = orig.getWidth();
			int rawHeight = orig.getHeight();
			
			int previewHeight = PREVIEW_HEIGHT;
			int previewWidth = ((int) Math.round(previewHeight / dimension.getRatio()));
			
			int orientation = getImageOrientation(metadata);
			
			BufferedImage copy = new BufferedImage(previewWidth, previewHeight, orig.getType());
			Graphics2D g = (Graphics2D) copy.getGraphics();
			
			double scaleX = ((double)previewWidth) / dimension.getWidth();
			double scaleY = ((double)previewHeight) / dimension.getHeight();
			
			AffineTransform tx = new AffineTransform();
			tx.translate((previewWidth - rawWidth * scaleX) / 2, (previewHeight - rawHeight * scaleY) / 2);
			tx.scale(scaleX, scaleY);
			applyOrientation(tx, orientation, rawWidth / 2, rawHeight / 2);
			g.setTransform(tx);
			
			g.drawImage(orig, null, 0, 0);
			ImageIO.write(copy, "jpg", previewCache);
		}
	}

	private static void applyOrientation(AffineTransform tx, int orientation, int centerX, int centerY) {
		int rotation = 0;
		boolean flip = false;
		switch (orientation) {
			case 1: 
				break;
			case 2:
				flip = true;
				break;
			case 3:
				rotation = 180;
				break;
			case 4:
				rotation = 180;
				flip = true;
				break;
			case 5:
				rotation = -90;
				flip = true;
				break;
			case 6:
				rotation = 90;
				break;
			case 7:
				rotation = 90;
				flip = true;
				break;
			case 8:
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

	private static int getThumbnailOrientation(Metadata metadata,
			ExifThumbnailDirectory tnDirectory) throws MetadataException {
		int tnOrientation = 1;
		if (tnDirectory.containsTag(ExifThumbnailDirectory.TAG_ORIENTATION)) {
			tnOrientation = tnDirectory.getInt(ExifThumbnailDirectory.TAG_ORIENTATION);
		} else {
			// At least images from Canon EOS have no extra thumbnail information. Instead, the thumbnail is oriented the same as the original image.
			tnOrientation = getImageOrientation(metadata);
		}
		return tnOrientation;
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
				tx.rotate(Math.toRadians(rotation), rawWidth / 2, rawHeight / 2);
		        g.setTransform(tx);
		        g.drawImage(image, null, 0, 0);
		        
		        image = copy;
			}
		}
		ImageIO.write(image, "jpg", previewCache);
	}

	private static BufferedImage getPreviewFrame(File file) throws Exception {
		try (FFmpegFrameGrabber g = new FFmpegFrameGrabber(file)) {
			g.start();
			Frame firstFrame = g.grabKeyFrame();
			return new Java2DFrameConverter().convert(firstFrame);
		}
	}

}
