/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import de.haumacher.imageServer.shared.ui.Controls;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class AlbumInfo implements Resource {

	private int _depth;
	
	private final AlbumProperties _header = new AlbumProperties();

	private final List<ImageInfo> _images = new ArrayList<>();
	
	private final Map<String, ImageInfo> _imageByName = new HashMap<>();
	
	private final List<ImageInfo> _imagesUnmodifiable = Collections.unmodifiableList(_images);

	/** 
	 * Creates a {@link AlbumInfo}.
	 */
	public AlbumInfo() {
		super();
	}
	
	@Override
	public String getHandler() {
		return Controls.ALBUM_CONTROL;
	}
	
	@Override
	public Type type() {
		return Type.album;
	}
	
	/**
	 * The number of ancestor resources that exist.
	 */
	public int getDepth() {
		return _depth;
	}

	/**
	 * @see #getDepth()
	 */
	public void setDepth(int depth) {
		_depth = depth;
	}
	
	/**
	 * Adds the given image.
	 * 
	 * <p>
	 * This is part of a bulk operation that must be completed with a call to {@link #updateLinks()}.
	 * </p>
	 */
	public void bulkAddImage(ImageInfo image) {
		ImageInfo clash = _imageByName.put(image.getName(), image);
		assert clash == null : "Duplicate name '" + image.getName() + "'.";
		_images.add(image);
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
	 * All {@link ImageInfo}s in this {@link AlbumInfo}.
	 */
	public List<ImageInfo> getImages() {
		return _imagesUnmodifiable;
	}

	/** 
	 * Sorts this {@link AlbumInfo} according to the given {@link Comparator order}.
	 */
	public void sort(Comparator<? super ImageInfo> order) {
		_images.sort(order);
		updateLinks();
	}

	/**
	 * Updates internal links after a number of {@link #bulkAddImage(ImageInfo)} operations.
	 */
	public void updateLinks() {
		List<ImageInfo> images = _images;
		String home= images.get(0).getName();
		String end = images.get(images.size() - 1).getName();

		for (int n = 0, size = _images.size(); n < size; n++) {
			ImageInfo current = _images.get(n);
			current.setHome(home);
			current.setEnd(end);
			if (n > 0) {
				current.setPrevious(_images.get(n - 1).getName());
			} else {
				current.setPrevious(null);
			}
			if (n + 1 < size) {
				current.setNext(_images.get(n + 1).getName());
			} else {
				current.setNext(null);
			}
		}
	}

	/**
	 * TODO
	 *
	 * @return
	 */
	public AlbumProperties getHeader() {
		return _header;
	}

	@Override
	public void writeContents(JsonWriter json) throws IOException {
		json.name("depth");
		json.value(getDepth());
		json.name("index");
		_header.writeTo(json);

		json.name("images");
		json.beginArray();
		for (ImageInfo image : getImages()) {
			image.writeTo(json);
		}
		json.endArray();
	}

	/**
	 * Reads an {@link AlbumInfo} as JSON object from the given {@link JsonReader}.
	 */
	public static AlbumInfo read(JsonReader json) throws IOException {
		AlbumInfo result = new AlbumInfo();
		result.readFrom(json);
		return result;
	}

	@Override
	public void readFrom(JsonReader json) throws IOException {
		json.beginObject();
		while (json.hasNext()) {
			switch (json.nextName()) {
				case "depth":
					setDepth(json.nextInt());
					break;
				case "index":
					_header.readFrom(json);
					break;
				case "images":
					json.beginArray();
					while (json.hasNext()) {
						bulkAddImage(ImageInfo.read(json));
					}
					json.endArray();
					break;
			}
		}
		json.endObject();
		
		updateLinks();
	}
	
	@Override
	public <R, A, E extends Throwable> R visit(Visitor<R, A, E> v, A arg) throws E {
		return v.visit(this, arg);
	}
}
