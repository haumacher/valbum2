/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.model;

import java.io.IOException;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * Common interface for types describing resources being served.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface Resource {
	
	/**
	 * The type of a {@link Resource}.
	 * 
	 * @see Resource#type()
	 */
	enum Type {
		/**
		 * A directory containing no images but other directories.
		 * 
		 * {@link ListingInfo}
		 */
		listing, 
		
		/**
		 * A directory containing images.
		 * 
		 * @see AlbumInfo
		 */
		album, 
		
		/**
		 * A single image.
		 * 
		 * @see ImageInfo
		 */
		image, 
		
		/**
		 * A resource that does not exist or cannot be retrieved. 
		 * 
		 * @see ErrorInfo
		 */
		error;
	}

	/**
	 * The resource type.
	 */
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
	
	/**
	 * Reads a polymorphic {@link Resource} as JSON object encoding the concrete
	 * type of the Resource and the {@link Resource} data as JSON array.
	 * 
	 * <p>
	 * <code>
	 * { "resource": [ "resource-type", { resource-data } ] }
	 * </code>
	 * </p>
	 */
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
			case image: result = ImageInfo.read(json); break;
			case error: result = ErrorInfo.read(json); break;
			default: throw new IllegalArgumentException("No such resource type: " + type);
		}
		json.endArray();
		json.endObject();
		return result;
	}

	/** 
	 * Serializes the specific contents of this {@link Resource}.
	 *
	 * @param json The {@link JsonWriter} to write to.
	 */
	void writeTo(JsonWriter json) throws IOException;

	/** 
	 * Reads specific contents of this {@link Resource} from a JSON stream produced by {@link #writeTo(JsonWriter)}.
	 *
	 * @param json {@link JsonReader} containing contents produced by {@link #writeTo(JsonWriter)}.
	 */
	void readFrom(JsonReader json) throws IOException;
}
