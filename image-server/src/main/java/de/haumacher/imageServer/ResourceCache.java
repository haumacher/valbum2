/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.MetadataException;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.gson.stream.JsonReader;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumProperties;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ImageInfo;
import de.haumacher.imageServer.shared.model.JsonSerializable;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ResourceCache {
	
	private static Set<String> ACCEPTED = new HashSet<>(Arrays.asList("jpg", "mp4"));
	
	static final FileFilter IMAGES = f -> {
		return f.isFile() && ACCEPTED.contains(Util.suffix(f.getName()));
	};

	private static LoadingCache<PathInfo, Resource> _cache;
	
	private static final String SEP = "[-_\\.]";
	static final Pattern DATE_PATTERN = Pattern.compile(
			"(" + "\\d{4}" + ")" + SEP + "(" + "\\d{2}" + ")" + SEP + "(" + "\\d{2}" + ")");

	static {
		CacheLoader<PathInfo, Resource> loader = new Loader();
		_cache = CacheBuilder.newBuilder().maximumSize(1000).build(loader);
	}

	public static boolean isImage(File file) {
		return IMAGES.accept(file);
	}

	public static Resource lookup(PathInfo pathInfo) {
		if (pathInfo.toFile().isDirectory()) {
			return _cache.getUnchecked(pathInfo);
		} else {
			Resource resource = _cache.getUnchecked(pathInfo.parent());
			if (resource instanceof AlbumInfo) {
				ImageInfo result = ((AlbumInfo) resource).getImage(pathInfo.getName());
				if (result != null) {
					return result;
				}
			}
			return new ErrorInfo("No such image '" + pathInfo + "'.");
		}
	}

	static final class Loader extends CacheLoader<PathInfo, Resource> {
		private static final FileFilter DIRECTORIES = f -> f.isDirectory();

		private static final Logger LOG = Logger.getLogger(ResourceCache.class.getName());

		@Override
		public Resource load(PathInfo pathInfo) {
			if (pathInfo.isDirectory()) {
				return loadDir(pathInfo);
			} else {
				throw new UnsupportedOperationException("Not a directory: " + pathInfo);
			}
		}

		private Resource loadDir(PathInfo dir) {
			File[] images = dir.toFile().listFiles(IMAGES);
			if (images == null) {
				return new ErrorInfo("Cannot list folder.");
			}
		
			if (images.length == 0) {
				return loadListing(dir);
			} else {
				return loadAlbum(dir, images);
			}
		}
		
		private static Resource loadListing(PathInfo pathInfo) {
			File dir = pathInfo.toFile();
			
			File[] dirs = dir.listFiles(DIRECTORIES);
			if (dirs == null) {
				return new ErrorInfo("Cannot list files.");
			}
			
			Arrays.sort(dirs, (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));
			
			String listingName = pathInfo.getName();
			ListingInfo listing = new ListingInfo(pathInfo.getDepth(), listingName, fromTechnicalName(listingName));
			for (File folder : dirs) {
				String folderName = folder.getName();
				FolderInfo folderInfo = new FolderInfo(folderName);
				
				File index = new File(folder, "index.json");
				if (index.isFile()) {
					try {
						loadJSON(index, folderInfo);
					} catch (IOException ex) {
						LOG.log(Level.WARNING, "Cannot read listing index '" + index + "'.", ex);
					}
				} else {
					File[] images = folder.listFiles(IMAGES);
					File indexPicture;
					ImageData imageData;
					if (images != null && images.length > 0) {
						indexPicture = images[0];
						
						double scale;
						try {
							imageData = ImageData.analyze(null, indexPicture);
							
							double width = imageData.getWidth();
							double height = imageData.getHeight();
							
							scale = width / height;
							double ty;
							if (scale < 1.0) {
								scale = 1.0 / scale;
								ty = (height - width) / height * 150;
							} else {
								ty = 0.0;
							}
							
							ThumbnailInfo thumbnail = new ThumbnailInfo(indexPicture.getName(), scale);
							thumbnail.setTy(ty);
							folderInfo.setIndexPicture(thumbnail);
						} catch (ImageProcessingException
								| MetadataException | IOException ex) {
							LOG.log(Level.WARNING, "Cannot analyze index picture: " + indexPicture, ex);
							imageData = null;
						}
					} else {
						indexPicture = null;
						imageData = null;
					}
					
					Matcher matcher = DATE_PATTERN.matcher(folderName);
					if (matcher.find()) {
						int year = Integer.parseInt(matcher.group(1));
						int month = Integer.parseInt(matcher.group(2));
						int day = Integer.parseInt(matcher.group(3));
						
						folderInfo.setSubTitle(dateString(year, month, day));
						folderName = removeMatch(folderName, matcher);
					} else {
						if (imageData != null) {
							Date date = imageData.getDate();
							if (date != null) {
								folderInfo.setSubTitle(formatDate(date));
							}
						}
					}
					folderInfo.setTitle(fromTechnicalName(folderName));
					
				}
				
				listing.addFolder(folderInfo);
			}
			return listing;
		}

		private static String dateString(int year, int month, int day) {
			GregorianCalendar calendar = new GregorianCalendar();
			calendar.set(Calendar.YEAR, year);
			calendar.set(Calendar.MONTH, month);
			calendar.set(Calendar.DAY_OF_MONTH, day);
			calendar.set(Calendar.HOUR, 0);
			calendar.set(Calendar.MINUTE, 0);
			calendar.set(Calendar.SECOND, 0);
			calendar.set(Calendar.MILLISECOND, 0);
			Date date = calendar.getTime();
			return formatDate(date);
		}

		private static String formatDate(Date date) {
			return DateFormat.getDateInstance(DateFormat.LONG).format(date);
		}

		private static String removeMatch(String name, Matcher match) {
			String before = name.substring(0, match.start()).trim();
			String after = name.substring(match.end()).trim();
			if (before.isEmpty()) {
				name = after;
			} else if (after.isEmpty()) {
				name = before;
			} else {
				name = before + " " + after;
			}
			return name;
		}
		
		private static String fromTechnicalName(String name) {
			return uppercaseStart(name.replaceAll("_+|(?<=\\p{javaLowerCase})(?=\\p{javaUpperCase})", " "));
		}

		private static String uppercaseStart(String expanded) {
			if (expanded.isEmpty()) {
				return expanded;
			}
			return Character.toUpperCase(expanded.charAt(0)) + expanded.substring(1);
		}

		private static Resource loadAlbum(PathInfo pathInfo, File[] files) {
			AlbumInfo album = new AlbumInfo();
			album.setDepth(pathInfo.getDepth());
			
			for (File file : files) {
				ImageData image;
				try {
					image = ImageData.analyze(album, file);
				} catch (IOException | ImageProcessingException | MetadataException ex) {
					LOG.log(Level.WARNING, "Cannot access '" + file + "': " + ex.getMessage(), ex);
					continue;
				}
				album.addImage(image);
			}
			
			album.sort((a, b) -> a.getDate().compareTo(b.getDate()));
		
			File dir = pathInfo.toFile();
			
			File indexResource = new File(dir, "index.json");
			AlbumProperties header = album.getHeader();
			if (indexResource.exists()) {
				try {
					loadJSON(indexResource, header);
				} catch (IOException ex) {
					LOG.log(Level.WARNING, "Faild to load album index.", ex);
					return new ErrorInfo("Cannot load album info.");
				}
			} else {
				String dirName = pathInfo.getName();
				
				Pattern prefixPattern = Pattern.compile("[-_\\.\\s0-9]*");
				Matcher matcher = prefixPattern.matcher(dirName);
				if (matcher.lookingAt()) {
					header.setTitle(dirName.substring(matcher.end()));
					header.setSubTitle(dirName.substring(0, matcher.end()));
				} else {
					header.setTitle(dirName);
				}
			}
			
			return album;
		}

		private static void loadJSON(File file, JsonSerializable resource) throws IOException {
			try (InputStream in = new FileInputStream(file)) {
				JsonReader json = new JsonReader(new InputStreamReader(in, "utf-8"));
				resource.readFrom(json);
			}
		}
	}

}
