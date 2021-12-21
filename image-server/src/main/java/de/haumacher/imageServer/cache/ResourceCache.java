/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.cache;

import static java.nio.file.StandardWatchEventKinds.*;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.FileSystems;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

import de.haumacher.imageServer.PathInfo;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.imageServer.shared.util.ToImage;
import de.haumacher.imageServer.shared.util.UpdateTransient;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.util.servlet.Util;

/**
 * Cache of {@link Resource}s representing directories and files in a photo album.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ResourceCache {
	
	private static Set<String> ACCEPTED = new HashSet<>(Arrays.asList("jpg", "jpeg", "mp4"));
	
	static final FileFilter IMAGES = f -> {
		return f.isFile() && ACCEPTED.contains(Util.suffix(f.getName()));
	};

	private static final String SEP = "[-_\\.]";
	static final Pattern DATE_PATTERN = Pattern.compile(
			"(" + "\\d{4}" + ")" + SEP + "(" + "\\d{2}" + ")" + SEP + "(" + "\\d{2}" + ")");

	private Loader _loader;

	private LoadingCache<PathInfo, Resource> _cache;
	
	/** 
	 * Creates a {@link ResourceCache}.
	 */
	public ResourceCache() throws IOException {
		_loader = new Loader();
		_cache = CacheBuilder.newBuilder().maximumSize(1000).build(_loader);
	}

	/**
	 * Whether the given {@link File} is a supported image or video file.
	 */
	public static boolean isImage(File file) {
		return IMAGES.accept(file);
	}

	/**
	 * Retrieves the {@link Resource} description for the given {@link PathInfo}.
	 *
	 * @param pathInfo The file-system resource to analyze.
	 * @return The {@link Resource} describing the system resource.
	 */
	public Resource lookup(PathInfo pathInfo) {
		_loader.processEvents(_cache);
		if (pathInfo.toFile().isDirectory()) {
			return _cache.getUnchecked(pathInfo);
		} else {
			AlbumInfo container = (AlbumInfo) _cache.getUnchecked(pathInfo.parent());
			return container.getImageByName().get(pathInfo.getName());
		}
	}

	static final class Loader extends CacheLoader<PathInfo, Resource> {
		private static final FileFilter DIRECTORIES = f -> f.isDirectory();

		private static final Logger LOG = Logger.getLogger(ResourceCache.class.getName());

		private final WatchService _watcher;
		
		private final Map<WatchKey, PathInfo> _watchedDirs = new HashMap<>();
		
		/** 
		 * Creates a {@link ResourceCache.Loader}.
		 */
		public Loader() throws IOException {
			_watcher = FileSystems.getDefault().newWatchService();
		}

		@Override
		public Resource load(PathInfo pathInfo) {
			if (pathInfo.isDirectory()) {
				Resource result = loadDir(pathInfo);
				return result;
			} else {
				throw new UnsupportedOperationException("Not a directory: " + pathInfo);
			}
		}
		
		public void processEvents(LoadingCache<PathInfo, Resource> cache) {
			while (true) {
				WatchKey key = _watcher.poll();
				if (key == null) {
					break;
				}
				if (!key.isValid()) {
					continue;
				}
				
				PathInfo path = _watchedDirs.remove(key);
				if (path != null) {
					cache.invalidate(path);
				}
				key.cancel();
			}
		}

		private Resource loadDir(PathInfo path) {
			File dir = path.toFile();
			
			File[] images = dir.listFiles(IMAGES);
			if (images == null) {
				return ErrorInfo.create().setMessage("Cannot list folder.");
			}
			
			try {
				WatchKey key = dir.toPath().register(_watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
				_watchedDirs.put(key, path);
			} catch (IOException ex) {
				LOG.log(Level.WARNING, "Cannot register directory watcher on '" + dir + "'.", ex);
			}
		
			if (images.length == 0) {
				return loadListing(path);
			} else {
				return loadAlbum(path, images);
			}
		}
		
		private static Resource loadListing(PathInfo pathInfo) {
			File dir = pathInfo.toFile();
			
			File[] dirs = dir.listFiles(DIRECTORIES);
			if (dirs == null) {
				return ErrorInfo.create().setMessage("Cannot list files.");
			}
			
			Arrays.sort(dirs, (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));
			
			String listingName = pathInfo.getName();
			ListingInfo listing = ListingInfo.create().setTitle(fromTechnicalName(listingName));
			for (File folder : dirs) {
				String folderName = folder.getName();
				FolderInfo folderInfo;
				
				File index = new File(folder, "index.json");
				if (false) {
					try {
						folderInfo = loadJSON(index, FolderInfo::readFolderInfo);
					} catch (IOException ex) {
						LOG.log(Level.WARNING, "Cannot read listing index '" + index + "'.", ex);
						folderInfo = FolderInfo.create();
					}
				} else {
					folderInfo = FolderInfo.create();
				}
				folderInfo.setName(folderName);
				
				if (folderInfo.getIndexPicture() == null) {
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
							
							ThumbnailInfo thumbnail = ThumbnailInfo.create().setImage(indexPicture.getName()).setScale(scale);
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
							long imageDate = imageData.getDate();
							if (imageDate > 0L) {
								Date date = new Date(imageDate);
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
			calendar.set(Calendar.MONTH, month - 1 + Calendar.JANUARY);
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

		private static AlbumInfo loadAlbum(PathInfo pathInfo, File[] files) {
			AlbumInfo album;
			
			File dir = pathInfo.toFile();
			File indexResource = new File(dir, "index.json");
			if (indexResource.exists()) {
				try {
					album = (AlbumInfo) loadJSON(indexResource, AlbumInfo::readResource);
				} catch (IOException ex) {
					LOG.log(Level.WARNING, "Faild to load album index.", ex);
					album = createGenericAlbumInfo(pathInfo);
				}
			} else {
				album = createGenericAlbumInfo(pathInfo);
			}
			
			// Update early to be able to match new images against existing image.
			UpdateTransient.updateTransient(album);
			
			Set<String> existingNames = new HashSet<>();
			List<ImageData> newImages = new ArrayList<>();
			for (File file : files) {
				String name = file.getName();
				existingNames.add(name);
				
				ImagePart existing = album.getImageByName().get(name);
				if (existing != null) {
					continue;
				}
				
				ImageData image;
				try {
					image = ImageData.analyze(album, file);
				} catch (IOException | ImageProcessingException | MetadataException ex) {
					LOG.log(Level.WARNING, "Cannot access '" + file + "': " + ex.getMessage(), ex);
					continue;
				}
				
				newImages.add(image);
			}
			
			if (!newImages.isEmpty()) {
				Collections.sort(newImages, (a, b) -> Long.compare(ToImage.toImage(a).getDate(), ToImage.toImage(b).getDate()));
				for (ImageData newImage : newImages) {
					album.addPart(newImage);
					album.putImageByName(newImage.getName(), newImage);
				}

				// Update again to fix internal linking structure.
				UpdateTransient.updateTransient(album);
			}
			
			return album;
		}

		private static AlbumInfo createGenericAlbumInfo(PathInfo pathInfo) {
			AlbumInfo album = AlbumInfo.create();
			String dirName = pathInfo.getName();
			
			Pattern prefixPattern = Pattern.compile("[-_\\.\\s0-9]*");
			Matcher matcher = prefixPattern.matcher(dirName);
			if (matcher.lookingAt()) {
				album.setTitle(fromTechnicalName(dirName.substring(matcher.end())));
				album.setSubTitle(dirName.substring(0, matcher.end()));
			} else {
				album.setTitle(dirName);
			}
			return album;
		}

		interface LoaderFunction<T> {
			T load(JsonReader json) throws IOException;
		}
		
		private static <T> T loadJSON(File file, LoaderFunction<T> loader) throws IOException {
			try (InputStream in = new FileInputStream(file)) {
				JsonReader json = new JsonReader(new ReaderAdapter(new InputStreamReader(in, "utf-8")));
				return loader.load(json);
			}
		}
	}

}
