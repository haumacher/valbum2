/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.model;

import java.io.IOException;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/**
 * Object that can store/load its contents to/from a JSON stream.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface JsonSerializable {

	/** 
	 * Loads the contents of this instance from the given {@link JsonReader}.
	 */
	default void readFrom(JsonReader json) throws IOException {
		json.beginObject();
		while (json.peek() == JsonToken.NAME) {
			readProperty(json, json.nextName());
		}
		json.endObject();
	}

	/** 
	 * Writes a JSON object with the contents created by {@link #writeContents(JsonWriter)}.
	 */
	default void writeTo(JsonWriter json) throws IOException {
		json.beginObject();
		writeContents(json);
		json.endObject();
	}

	/** 
	 * Stores the contents of this instance to the given {@link JsonWriter}.
	 */
	void writeContents(JsonWriter json) throws IOException;

	/** 
	 * Consumes the value of the property with the given name.
	 * 
	 * @param json The {@link JsonReader} to read from.
	 * @param property The name of the property to process.
	 */
	default void readProperty(JsonReader json, String property) throws IOException {
		json.skipValue();
	}

}
