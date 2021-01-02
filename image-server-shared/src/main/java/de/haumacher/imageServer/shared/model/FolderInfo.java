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
public class FolderInfo extends AlbumProperties {

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

	@Override
	public void writeContents(JsonWriter json) throws IOException {
		super.writeContents(json);
		
		json.name("name");
		json.value(getName());
	}

	public static FolderInfo read(JsonReader json) throws IOException {
		FolderInfo result = new FolderInfo();
		result.readFrom(json);
		return result;
	}

	@Override
	public void readProperty(JsonReader json, String property)
			throws IOException {
		switch (property) {
			case "name": _name = json.nextString(); break;
			default: super.readProperty(json, property);
		}
	}

}
