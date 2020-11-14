/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class AlbumInfo implements Resource {

	private final AlbumProperties _header = new AlbumProperties();

	private final List<ImageInfo> _images = new ArrayList<>();
	
	/** 
	 * Creates a {@link AlbumInfo}.
	 *
	 */
	public AlbumInfo() {
		super();
	}
	
	@Override
	public Type type() {
		return Type.album;
	}

	/**
	 * TODO
	 *
	 * @param image
	 */
	public void addImage(ImageInfo image) {
		_images.add(image);
	}

	/**
	 * TODO
	 *
	 * @return
	 */
	public List<ImageInfo> getImages() {
		return _images;
	}

	/**
	 * TODO
	 *
	 * @return
	 */
	public AlbumProperties getHeader() {
		return _header;
	}

	/**
	 * TODO
	 *
	 * @param json
	 * @throws IOException
	 */
	@Override
	public void writeTo(JsonWriter json) throws IOException {
		json.beginObject();
		json.name("index");
		_header.writeTo(json);

		json.name("images");
		json.beginArray();
		for (ImageInfo image : getImages()) {
			image.writeTo(json);
		}
		json.endArray();
		json.endObject();
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
				case "index":
					_header.readFrom(json);
					break;
				case "images":
					json.beginArray();
					while (json.hasNext()) {
						addImage(ImageInfo.read(json));
					}
					json.endArray();
					break;
			}
		}
		json.endObject();
	}
	
	@Override
	public <R, A, E extends Throwable> R visit(Visitor<R, A, E> v, A arg) throws E {
		return v.visit(this, arg);
	}
}
