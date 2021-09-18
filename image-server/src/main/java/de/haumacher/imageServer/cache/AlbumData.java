/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.cache;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ImageInfo;
import de.haumacher.imageServer.shared.model.Resource;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class AlbumData extends Resource {

	private AlbumInfo _album;

	private final Map<String, ImageInfo> _imageByName = new HashMap<>();

	/** 
	 * Creates a {@link AlbumData}.
	 */
	public AlbumData(AlbumInfo album) {
		_album = album;
		updateLinks();
	}
	
	/**
	 * TODO
	 */
	public AlbumInfo getAlbum() {
		return _album;
	}

	@Override
	public String jsonType() {
		return _album.jsonType();
	}

	@Override
	public int typeId() {
		return _album.typeId();
	}

	@Override
	public <R, A> R visit(Visitor<R, A> v, A arg) {
		return _album.visit(v, arg);
	}

	/**
	 * The {@link ImageInfo} with the given name.
	 * 
	 * @param name
	 *        The name to search.
	 * @return The {@link ImageInfo} with the the given name, or
	 *         <code>null</code> if no such {@link ImageInfo} exists in this
	 *         {@link AlbumInfo}.
	 */
	public ImageInfo getImage(String name) {
		return _imageByName.get(name);
	}

	/**
	 * Updates internal links.
	 */
	private void updateLinks() {
		List<ImageInfo> images = _album.getImages();
		String home= images.get(0).getName();
		String end = images.get(images.size() - 1).getName();

		for (int n = 0, size = images.size(); n < size; n++) {
			ImageInfo current = images.get(n);
			
			ImageInfo clash = _imageByName.put(current.getName(), current);
			assert clash == null : "Duplicate name '" + current.getName() + "'.";

			current.setHome(home);
			current.setEnd(end);
			if (n > 0) {
				current.setPrevious(images.get(n - 1).getName());
			} else {
				current.setPrevious(null);
			}
			if (n + 1 < size) {
				current.setNext(images.get(n + 1).getName());
			} else {
				current.setNext(null);
			}
			
			current.setDepth(_album.getDepth() + 1);
		}
	}

}
