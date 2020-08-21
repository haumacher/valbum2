/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.IOException;

import com.google.gson.stream.JsonWriter;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Json {

	/** 
	 * TODO
	 *
	 * @param json
	 * @param value TODO
	 * @throws IOException
	 */
	static void value(JsonWriter json, String name, String value) throws IOException {
		if (value != null) {
			json.name(name);
			json.value(value);
		}
	}

}
