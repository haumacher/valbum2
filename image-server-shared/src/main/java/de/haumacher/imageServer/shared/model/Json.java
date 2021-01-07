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
	 * @param name The property name.
	 * @param value The value to write (may be <code>null</code>).
	 */
	public static void propertyOptional(JsonWriter json, String name, String value) throws IOException {
		if (value != null) {
			property(json, name, value);
		}
	}

	/** 
	 * Writes an object property.
	 *
	 * @param json The {@link JsonWriter} to write to.
	 * @param name The property name.
	 * @param value The value to write (may be <code>null</code>).
	 */
	public static void property(JsonWriter json, String name, String value) throws IOException {
		json.name(name);
		json.value(value);
	}

	/** 
	 * Writes an object property.
	 *
	 * @param json The {@link JsonWriter} to write to.
	 * @param name The property name.
	 * @param value The value to write (may be <code>null</code>).
	 */
	public static void property(JsonWriter json, String name, boolean value) throws IOException {
		json.name(name);
		json.value(value);
	}
	
	/** 
	 * Writes an object property.
	 *
	 * @param json The {@link JsonWriter} to write to.
	 * @param name The property name.
	 * @param value The value to write (may be <code>null</code>).
	 */
	public static void property(JsonWriter json, String name, double value) throws IOException {
		json.name(name);
		json.value(value);
	}
	
	/** 
	 * Writes an object property.
	 *
	 * @param json The {@link JsonWriter} to write to.
	 * @param name The property name.
	 * @param value The value to write (may be <code>null</code>).
	 */
	public static void property(JsonWriter json, String name, long value) throws IOException {
		json.name(name);
		json.value(value);
	}
	
	/** 
	 * Writes an optional object property.
	 *
	 * @param json The {@link JsonWriter} to write to.
	 * @param value The value to write (may be <code>null</code>).
	 */
	public static void optionalProperty(JsonWriter json, String name, JsonSerializable value) throws IOException {
		if (value != null) {
			json.name(name);
			value.writeTo(json);
		}
	}
	
}
