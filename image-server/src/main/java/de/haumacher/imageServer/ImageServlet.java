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
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.eclipse.jetty.http.HttpStatus;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifThumbnailDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import de.haumacher.util.xml.XmlWriter;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ImageServlet extends HttpServlet {

	private static Set<String> ACCEPTED = new HashSet<>(Arrays.asList("jpg", "mp4"));
	
	private static final FileFilter IMAGES = f -> {
		return f.isFile() && ACCEPTED.contains(Util.suffix(f.getName()));
	};

	private static final Logger LOG = Logger.getLogger(ImageServlet.class.getName());

	private static final FileFilter DIRECTORIES = f -> f.isDirectory();
	
	static {
		LOG.info("Loading: " + ExifReaderPatch.class);
	}

	private Path _basePath;

	/** 
	 * Creates a {@link ImageServlet}.
	 *
	 * @param basePath
	 */
	public ImageServlet(File basePath) {
		_basePath = basePath.toPath();
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String pathInfo = request.getPathInfo();
		
		Path resourcePath;
		if (pathInfo == null) {
			resourcePath = _basePath;
		} else {
			Path path = Paths.get(pathInfo.substring(1)).normalize();
			if (path.startsWith("..")) {
				error404(response);
				return;
			}
			resourcePath = _basePath.resolve(path);
		}
		
		File file = resourcePath.toFile();
		if (!file.exists()) {
			error404(response);
			return;
		}

		if (file.isDirectory()) {
			if (pathInfo == null) {
				response.sendRedirect(request.getContextPath() + "/");
				return;
			}
			if (!pathInfo.endsWith("/")) {
				response.sendRedirect(request.getContextPath() + pathInfo + "/");
				return;
			}
			
			serveFolder(request, response, file);
		} else if (IMAGES.accept(file)) {
			serveImage(request, response, file);
		} else {
			error404(response);
		}
	}

	private void serveListing(HttpServletResponse response, File dir) throws IOException {
		File[] dirs = dir.listFiles(DIRECTORIES);
		if (dirs == null) {
			error404(response);
			return;
		}
		
		Arrays.sort(dirs, (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));
		
		response.setContentType("text/html");
		response.setCharacterEncoding("utf-8");
		try (Writer w = new OutputStreamWriter(response.getOutputStream(), "utf-8")) {
			try (XmlWriter out = new XmlWriter(w)) {
				out.begin(HTML);
				{
					out.begin(HEAD);
					{
						out.begin(META);
						out.attr(NAME_ATTR, "viewport");
						out.attr(CONTENT_ATTR, "width=device-width, initial-scale=1.0");
						out.endEmpty();

						out.begin(TITLE); out.append("VAlbum"); out.end();
					}
					out.end();
					
					out.begin(BODY);
					{
						out.begin(UL);
						{
							out.begin(LI);
							{
								out.begin(A);
								out.attr(HREF_ATTR, "..");
								{
									out.append("<- Go back");
								}
								out.end();
							}
							out.end();
	
							for (File sub : dirs) {
								out.begin(LI);
								{
									out.begin(A);
									out.openAttr(HREF_ATTR);
									out.append(sub.getName());
									out.append('/');
									out.closeAttr();
									{
										out.append(sub.getName());
									}
									out.end();
								}
								out.end();
							}
						}
						out.end();
					}
					out.end();
				}
				out.end();
			}
		}
	}

	private void serveFolder(HttpServletRequest request, HttpServletResponse response, File dir) throws IOException {
		File[] files = dir.listFiles(IMAGES);
		if (files == null) {
			error404(response);
			return;
		}

		if (files.length == 0) {
			serveListing(response, dir);
			return;
		}
		
		List<ImageData> images = new ArrayList<>();
		for (File file : files) {
			try {
				ImageData image = ImageData.analyze(file);
				if (image == null) {
					continue;
				}
				images.add(image);
			} catch (ImageProcessingException | IOException
					| MetadataException ex) {
				LOG.log(Level.WARNING,
						"Cannot access '" + file + "': " + ex.getMessage(), ex);
				continue;
			}
		}
		
		Collections.sort(images, (a, b) -> a.getDate().compareTo(b.getDate()));

		AlbumIndex index;
		File indexResource = new File(dir, "index.json");
		if (indexResource.exists()) {
			try (InputStream in = new FileInputStream(indexResource)) {
				JsonReader json = new JsonReader(
						new InputStreamReader(in, "utf-8"));
				index = AlbumIndex.read(json);
			}
		} else {
			index = new AlbumIndex();
			String dirName = dir.getName();
			
			Pattern prefixPattern = Pattern.compile("[-_\\.\\s0-9]*");
			Matcher matcher = prefixPattern.matcher(dirName);
			if (matcher.lookingAt()) {
				index.setTitle(dirName.substring(matcher.end()));
				index.setSubTitle(dirName.substring(0, matcher.end()));
			} else {
				index.setTitle(dirName);
			}
		}
		
		if ("json".equals(request.getParameter("type"))) {
			serveFolderJson(response, index, images);
		} else {
			serveFolderHtml(request, response, index, images);
		}
	}

	private void serveFolderJson(HttpServletResponse response, AlbumIndex index, List<ImageData> images) throws IOException {
		response.setContentType("application/json");
		try (JsonWriter json = new JsonWriter(new OutputStreamWriter(response.getOutputStream(), "utf-8"))) {
			json.beginObject();
			json.name("index");
			index.writeTo(json);
			
			json.name("images");
			json.beginArray();
			for (ImageData image : images) {
				image.writeTo(json);
			}
			json.endArray();
			json.endObject();
		}
	}

	private void serveFolderHtml(HttpServletRequest request, HttpServletResponse response, AlbumIndex index, List<ImageData> images) throws IOException {
		response.setContentType("text/html");
		response.setCharacterEncoding("utf-8");
		try (Writer w = new OutputStreamWriter(response.getOutputStream(), "utf-8")) {
			try (XmlWriter out = new XmlWriter(w)) {
				out.begin(HTML);
				{
					out.begin(HEAD);
					{
						out.begin(META);
						out.attr(NAME_ATTR, "viewport");
						out.attr(CONTENT_ATTR, "width=device-width, initial-scale=1.0");
						out.endEmpty();
						
						// <link rel="stylesheet" type="text/css" href="https://cdn.sstatic.net/Sites/stackoverflow/primary.css?v=905b10e527f0">
						out.begin(LINK);
						out.attr(REL_ATTR, "stylesheet");
						out.attr(TYPE_ATTR, "text/css");
						out.attr(HREF_ATTR, request.getContextPath() + "/static/style/valbum.css");
						out.endEmpty();
						
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
				ImageData image = row.getImage(n);

				out.begin(DIV);
				out.openAttr(STYLE_ATTR);
				out.append("display: table-cell;");
				out.closeAttr();
				{
					out.begin(A);
					out.openAttr(STYLE_ATTR);
					out.append("display: block;");
					if (n > 0) {
						out.append("margin-left: ");
						out.append(spacing);
						out.append("px;");
					}
					out.closeAttr();
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
		out.end();
	}

	private void serveImage(HttpServletRequest request, HttpServletResponse response, File file) throws IOException {
		String thumbnail = request.getParameter("tn");
		if (thumbnail == null) {
			serveImageData(request, response, file);
			return;
		}
		
		String name = file.getName();
		String suffix = Util.suffix(name);
		if (suffix.equals("jpg")) {
			Metadata metadata;
			try {
				metadata = ImageMetadataReader.readMetadata(file);
				ExifThumbnailDirectory tnDirectory = metadata.getFirstDirectoryOfType(ExifThumbnailDirectory.class);
				if (tnDirectory != null) {
					byte[] data = (byte[]) tnDirectory.getObject(ExifReaderPatch.TAG_THUMBNAIL_DATA);
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
										return;
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
							return;
						}
					}
				}			
				serveImageData(request, response, file);
				return;
			} catch (ImageProcessingException | IOException | MetadataException ex) {
				serveImageData(request, response, file);
				return;
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
			return;
		} else {
			error404(response);
			return;
		}
	}

	private void serveImageData(HttpServletRequest request, HttpServletResponse response, File file) throws IOException {
		String mimeType = request.getServletContext().getMimeType(file.getName());
		response.setContentType(mimeType);
		long length = file.length();
		if (length <= Integer.MAX_VALUE) {
			response.setContentLength((int) length);
		}
		try (FileInputStream in = new FileInputStream(file)) {
			Util.sendBytes(response, in);
		}
	}

	/** 
	 * TODO
	 *
	 * @param response
	 */
	private void error404(HttpServletResponse response) {
		response.setStatus(HttpStatus.NOT_FOUND_404);
	}
}
