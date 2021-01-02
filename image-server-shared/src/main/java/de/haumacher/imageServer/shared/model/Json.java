/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.model;

import java.io.IOException;

import com.google.gson.stream.JsonWriter;

/**
 * Utilities for JSON serialization.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Json {

	/** 
	 * Writes an optional object property.
	 *
	 * @param json The {@link JsonWriter} to write to.
	 * @param value The value to write (may be <code>null</code>).
	 */
	public static void optionalProperty(JsonWriter json, String name, String value) throws IOException {
		if (value != null) {
			json.name(name);
			json.value(value);
		}
	}

}
