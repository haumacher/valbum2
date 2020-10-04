/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.model;

import java.io.IOException;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface Resource {
	
	enum Type {
		listing, album, image, error;
	}

	Type type();
	
	<R,A,E extends Throwable> R visit(Visitor<R,A,E> v, A arg) throws E;
	
	interface Visitor<R,A,E extends Throwable> {
		R visit(AlbumInfo album, A arg) throws E;
		R visit(ListingInfo listing, A arg) throws E;
		R visit(ImageInfo image, A arg) throws E;
		R visit(ErrorInfo error, A arg) throws E;
	}
	
	default void writePolymorphic(JsonWriter json) throws IOException {
		json.beginObject();
		json.name("resource");
		json.beginArray();
		json.value(type().name());
		writeTo(json);
		json.endArray();
		json.endObject();
	}
	
	static Resource readPolymorphic(JsonReader json) throws IOException {
		Resource result;
		json.beginObject();
		String name = json.nextName();
		assert name.equals("resource");
		json.beginArray();
		Type type = Type.valueOf(json.nextString());
		switch (type) {
			case album: result = AlbumInfo.read(json); break;
			case listing: result = ListingInfo.read(json); break;
			default: throw new IllegalArgumentException("No such resource type: " + type);
		}
		json.endArray();
		json.endObject();
		return result;
	}

	/** 
	 * TODO
	 *
	 * @param json
	 */
	void writeTo(JsonWriter json) throws IOException;
}
