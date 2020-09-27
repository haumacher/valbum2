/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.model;

import java.io.IOException;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * Reference to a sub-folder in a {@link ListingInfo}.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class FolderInfo {

	private String _name;

	/** 
	 * Creates a {@link FolderInfo}.
	 *
	 * @param name
	 */
	public FolderInfo(String name) {
		this();
		_name = name;
	}
	
	/** 
	 * Creates a {@link FolderInfo}.
	 *
	 */
	public FolderInfo() {
		super();
	}

	/**
	 * TODO
	 */
	public String getName() {
		return _name;
	}

	/** 
	 * TODO
	 *
	 * @param json
	 * @throws IOException 
	 */
	public void writeTo(JsonWriter json) throws IOException {
		json.beginObject();
		json.name("name");
		json.value(getName());
		json.endObject();
	}

	/** 
	 * TODO
	 *
	 * @param json
	 * @return
	 * @throws IOException 
	 */
	public static FolderInfo readFrom(JsonReader json) throws IOException {
		FolderInfo result = new FolderInfo();
		result.doReadFrom(json);
		return result;
	}

	private void doReadFrom(JsonReader json) throws IOException {
		json.beginObject();
		while (json.hasNext()) {
			switch (json.nextName()) {
				case "name": _name=json.nextString();
			}
		}
		json.endObject();
	}

}
