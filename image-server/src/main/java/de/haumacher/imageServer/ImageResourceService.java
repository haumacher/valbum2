/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import static de.haumacher.util.xml.HTML.*;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.server.ResourceService;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.imaging.jpeg.JpegSegmentMetadataReader;
import com.drew.imaging.jpeg.JpegSegmentType;
import com.drew.lang.annotations.NotNull;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifReader;
import com.drew.metadata.exif.ExifThumbnailDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.google.gson.stream.JsonReader;

import de.haumacher.util.xml.XmlWriter;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ImageResourceService extends ResourceService {

	private static final Logger LOG = Logger.getLogger(ImageResourceService.class.getName());
	
	
	public static int TAG_THUMBNAIL_DATA = 0x10000;
	
	// Workaround for extracting thumbnail images from JPEG files, see https://github.com/drewnoakes/metadata-extractor/issues/276#issuecomment-677767368
	static {
		List<JpegSegmentMetadataReader> allReaders = (List<JpegSegmentMetadataReader>) JpegMetadataReader.ALL_READERS;
		for (int n = 0, cnt = allReaders.size(); n < cnt; n++) {
			if (allReaders.get(n).getClass() != ExifReader.class) {
				continue;
			}
			
			allReaders.set(n, new ExifReader() {
				@Override
				public void readJpegSegments(@NotNull final Iterable<byte[]> segments, @NotNull final Metadata metadata, @NotNull final JpegSegmentType segmentType) {
					super.readJpegSegments(segments, metadata, segmentType);

				    for (byte[] segmentBytes : segments) {
				        // Filter any segments containing unexpected preambles
				        if (!startsWithJpegExifPreamble(segmentBytes)) {
				        	continue;
				        }
				        
				        // Extract the thumbnail
				        try {
				            ExifThumbnailDirectory tnDirectory = metadata.getFirstDirectoryOfType(ExifThumbnailDirectory.class);
				            if (tnDirectory != null && tnDirectory.containsTag(ExifThumbnailDirectory.TAG_THUMBNAIL_OFFSET)) {
				            	int offset = tnDirectory.getInt(ExifThumbnailDirectory.TAG_THUMBNAIL_OFFSET);
				            	int length = tnDirectory.getInt(ExifThumbnailDirectory.TAG_THUMBNAIL_LENGTH);
				            	
				            	byte[] tnData = new byte[length];
				            	System.arraycopy(segmentBytes, JPEG_SEGMENT_PREAMBLE.length() + offset, tnData, 0, length);
				            	tnDirectory.setObject(TAG_THUMBNAIL_DATA, tnData);
				            }
				        } catch (MetadataException e) {
				            e.printStackTrace();
				        }
				    }
				}				
			});
			break;
		}
	}

	@Override
	protected boolean sendData(HttpServletRequest request,
			HttpServletResponse response, boolean include, HttpContent content,
			Enumeration<String> reqRanges) throws IOException {
		
		String thumbnail = request.getParameter("tn");
		if (thumbnail == null) {
			return super.sendData(request, response, include, content, reqRanges);
		}
		
		Resource resource = content.getResource();
		String name = resource.getName();
		String suffix = suffix(name);
		File file = resource.getFile();
		if (suffix.equals("jpg")) {
			Metadata metadata;
			try {
				metadata = ImageMetadataReader.readMetadata(file);
				ExifThumbnailDirectory tnDirectory = metadata.getFirstDirectoryOfType(ExifThumbnailDirectory.class);
				if (tnDirectory != null) {
					byte[] data = (byte[]) tnDirectory.getObject(TAG_THUMBNAIL_DATA);
					if (data != null) {
						int origOrientation = 1;
						ExifIFD0Directory exifIFD0Directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
						if (exifIFD0Directory != null && exifIFD0Directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
							origOrientation = exifIFD0Directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
						}
						
						int tnOrientation = 1;
						if (tnDirectory.containsTag(ExifThumbnailDirectory.TAG_ORIENTATION)) {
							tnOrientation = tnDirectory.getInt(ExifThumbnailDirectory.TAG_ORIENTATION);
						} else {
							// At least images from Canon EOS have no extra thumbnail information. Instead, the thumbnail is oriented the same as the original image.
							tnOrientation = origOrientation;
						}

						{
							// The thumbnail cannot be delivered directly,
							// since the image data has no orientation
							// information, to make it display correctly, an
							// orientation tag must be added or the data
							// must be rotated.
							BufferedImage raw = ImageIO.read(new ByteArrayInputStream(data));
							
							// See http://sylvana.net/jpegcrop/exif_orientation.html
							int rawWidth = raw.getWidth();
							int rawHeight = raw.getHeight();
							
							int width, height;
							if (tnOrientation >= 5) {
								width = rawHeight;
								height = rawWidth;
							} else {
								width = rawWidth;
								height = rawHeight;
							}
							
							JpegDirectory jpegDirectory = metadata.getFirstDirectoryOfType(JpegDirectory.class);
							int origRawWidth = jpegDirectory.getImageWidth();
							int origRawHeight = jpegDirectory.getImageHeight();
							int origWidth, origHeight;
							if (origOrientation >= 5) {
								origWidth = origRawHeight;
								origHeight = origRawWidth;
							} else {
								origWidth = origRawWidth;
								origHeight = origRawHeight;
							}
							double origRatio = ((double) origHeight) / origWidth;
							
							// At least Canon EOS thumbnails have another aspect ratio as the original. The thumbnail image has black bars, that must be removed.
							int expectedTnHeight = (int) (width * origRatio);
							if (expectedTnHeight < height) {
								height = expectedTnHeight;
							} else {
								int expectedTnWidth = (int) (height / origRatio);
								if (expectedTnWidth < width) {
									width = expectedTnWidth;
								} else {
									if (tnOrientation == 1) {
										// No change required.
										
										response.getOutputStream().write(data);
										return true;
									}
								}
							}
							
							int rotation = 0;
							boolean flip = false;
							switch (tnOrientation) {
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
							
							BufferedImage copy = new BufferedImage(width, height, raw.getType());
							Graphics2D g = (Graphics2D) copy.getGraphics();
							AffineTransform tx = new AffineTransform();
				            tx.translate((width - rawWidth) / 2, (height - rawHeight) / 2);
				            if (flip) {
				            	tx.scale(-1, 1);
				            }
							tx.rotate(Math.toRadians(rotation), rawWidth / 2, rawHeight / 2);
				            g.setTransform(tx);
				            g.drawImage(raw, null, 0, 0);
							
							response.setContentType("image/jpeg");
							ImageIO.write(copy, "jpg", response.getOutputStream());
							return true;
						}
					}
				}			
				return super.sendData(request, response, include, content, reqRanges);
			} catch (ImageProcessingException | IOException | MetadataException ex) {
				return super.sendData(request, response, include, content, reqRanges);
			}
		} else if (suffix.equals("mp4")) {
			BufferedImage image;
			
			try (FFmpegFrameGrabber g = new FFmpegFrameGrabber(file)) {
				g.start();
				Frame firstFrame = g.grabKeyFrame();
				image = new Java2DFrameConverter().convert(firstFrame);
			}
			
			try {
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
			} catch (ImageProcessingException | MetadataException | IOException ex) {
				Logger.getLogger(ImageData.class.getName()).log(Level.WARNING, "Cannot get MP4 meta data from '" + file.getName()  + "'.");
			}
			
			response.setContentType("image/jpeg");
			ImageIO.write(image, "jpg", response.getOutputStream());
			return true;
		} else {
			return super.sendData(request, response, include, content, reqRanges);
		}
	}
	
	@Override
	protected void sendDirectory(HttpServletRequest request,
			HttpServletResponse response, Resource resource,
			String pathInContext) throws IOException {
		if (!resource.isDirectory()) {
			super.sendDirectory(request, response, resource, pathInContext);
			return;
		}

		String base = URIUtil.canonicalPath(URIUtil
				.addEncodedPaths(request.getRequestURI(), URIUtil.SLASH));
		if (base == null) {
			super.sendDirectory(request, response, resource, pathInContext);
			return;
		}
		
		String[] rawListing = resource.list();
		if (rawListing == null) {
			super.sendDirectory(request, response, resource, pathInContext);
			return;
		}

		List<ImageData> images = new ArrayList<>();
		for (String name : rawListing) {
			String suffix = suffix(name);
			if (suffix == null || (!suffix.equals("jpg") && !suffix.equals("mp4"))) {
				continue;
			}

			Resource imageResource = resource.getResource(name);
			try {
				ImageData image = ImageData.analyze(imageResource.getFile());
				if (image == null) {
					continue;
				}
				images.add(image);
			} catch (ImageProcessingException | IOException
					| MetadataException ex) {
				LOG.log(Level.WARNING,
						"Cannot access '" + name + "': " + ex.getMessage(), ex);
				continue;
			}
		}
		
		if (images.isEmpty()) {
			super.sendDirectory(request, response, resource, pathInContext);
			return;
		}

		Collections.sort(images, (a, b) -> a.getDate().compareTo(b.getDate()));

		AlbumIndex index;
		Resource indexResource = resource.getResource("index.json");
		if (indexResource.exists()) {
			try (InputStream in = indexResource.getInputStream()) {
				JsonReader json = new JsonReader(
						new InputStreamReader(in, "utf-8"));
				index = AlbumIndex.read(json);
			}
		} else {
			index = new AlbumIndex();
			String dirName = resource.getFile().getName();
			
			Pattern prefixPattern = Pattern.compile("[-_\\.\\s0-9]*");
			Matcher matcher = prefixPattern.matcher(dirName);
			if (matcher.lookingAt()) {
				index.setTitle(dirName.substring(matcher.end()));
				index.setSubTitle(dirName.substring(0, matcher.end()));
			} else {
				index.setTitle(dirName);
			}
		}
		
		response.setContentType("text/html");
		response.setCharacterEncoding("utf-8");
		try (Writer w = new OutputStreamWriter(response.getOutputStream(), "utf-8")) {
			try (XmlWriter out = new XmlWriter(w)) {
				out.begin(HTML);
				{
					out.begin(HEAD);
					{
						out.begin(TITLE);
						out.append("VAlbum");
						out.end();
					}
					out.end();
					out.begin(BODY);
					{
						out.begin(H1);
						out.append(index.getTitle());
						out.end();
						out.begin(H2);
						out.append(index.getSubTitle());
						out.end();

						ImageRow row = new ImageRow(1280, 400);
						for (ImageData image : images) {
							if (row.isComplete()) {
								writeRow(out, row);
								row.clear();
							}
							row.add(image);
						}
						writeRow(out, row);
					}
					out.end();
				}
				out.end();
			}
		}
	}

	private static String suffix(String name) {
		int sepIndex = name.lastIndexOf('.');
		if (sepIndex < 0) {
			return null;
		}

		return name.substring(sepIndex + 1).toLowerCase();
	}

	/**
	 * TODO
	 * 
	 * @param out
	 *
	 * @param row
	 * @throws IOException
	 */
	private void writeRow(XmlWriter out, ImageRow row) throws IOException {
		if (row.getSize() == 0) {
			return;
		}
		double rowHeight = row.getHeight();
		int spacing = row.getSpacing();

		out.begin(DIV);
		out.attr("style", "display: table; margin-top: " + spacing + "px;");
		{
			out.begin(DIV);
			out.attr(STYLE_ATTR, "display: table-row;");
			for (int n = 0, cnt = row.getSize(); n < cnt; n++) {
				if (n > 0) {
					out.begin(DIV);
					out.attr(STYLE_ATTR, "display: table-cell; width: " + spacing + "px;");
					out.end();
				}
				
				ImageData image = row.getImage(n);

				out.begin(A);
				out.attr(STYLE_ATTR, "display: table-cell;");
				out.attr(HREF_ATTR, image.getName());
				{
					out.begin(IMG);

					out.openAttr(SRC_ATTR);
					out.append(image.getName());
					out.append("?tn=true");
					out.closeAttr();
					
					out.attr(WIDTH_ATTR, row.getScaledWidth(n));
					out.attr(HEIGHT_ATTR, rowHeight);
					out.end();
				}
				out.end();
			}
			out.end();
		}
		out.end();
	}
}
