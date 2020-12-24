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
import java.util.Arrays;
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
import de.haumacher.imageServer.shared.model.ImageInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;

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
		
		private static Resource loadListing(PathInfo dir) {
			File[] dirs = dir.toFile().listFiles(DIRECTORIES);
			if (dirs == null) {
				return new ErrorInfo("Cannot list files.");
			}
			
			Arrays.sort(dirs, (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));
			
			ListingInfo listing = new ListingInfo(dir.getDepth(), dir.getName());
			for (File dir1 : dirs) {
				listing.addFolder(dir1.getName());
			}
			return listing;
		}
		
		private static Resource loadAlbum(PathInfo dir, File[] files) {
			AlbumInfo album = new AlbumInfo();
			album.setDepth(dir.getDepth());
			
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
		
			File indexResource = new File(dir.toFile(), "index.json");
			AlbumProperties header = album.getHeader();
			if (indexResource.exists()) {
				try {
					try (InputStream in = new FileInputStream(indexResource)) {
						JsonReader json = new JsonReader(new InputStreamReader(in, "utf-8"));
						header.readFrom(json);
					}
				} catch (IOException ex) {
					LOG.log(Level.WARNING, "Faild to load album index.", ex);
					return new ErrorInfo("Cannot load album info.");
				}
			} else {
				String dirName = dir.getName();
				
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
	}

}
