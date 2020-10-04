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
 * Description of a directory listing containing links to other directories.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ListingInfo implements Resource {
	
	private String _name;
	private List<FolderInfo> _folders = new ArrayList<>();

	/** 
	 * Creates a {@link ListingInfo}.
	 *
	 */
	public ListingInfo(String name) {
		this();
		_name = name;
	}
	
	@Override
	public Type type() {
		return Type.listing;
	}
	
	/** 
	 * Creates a {@link ListingInfo}.
	 *
	 */
	public ListingInfo() {
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
	 * @param name
	 */
	public void addFolder(String name) {
		FolderInfo folder = new FolderInfo(name);
		addFolder(folder);
	}

	/** 
	 * TODO
	 *
	 * @param folder
	 */
	public void addFolder(FolderInfo folder) {
		_folders.add(folder);
	}
	
	/**
	 * TODO
	 */
	public List<FolderInfo> getFolders() {
		return _folders;
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
		json.name("name");
		json.value(getName());
		json.name("folders");
		json.beginArray();
		for (FolderInfo folder : getFolders()) {
			folder.writeTo(json);
		}
		json.endArray();
		json.endObject();
	}
	
	public static ListingInfo read(JsonReader json) throws IOException {
		ListingInfo result = new ListingInfo();
		result.readFrom(json);
		return result;
	}

	private void readFrom(JsonReader json) throws IOException {
		json.beginObject();
		while (json.hasNext()) {
			switch(json.nextName()) {
				case "name":
					_name = json.nextString();
					break;
				case "folders":
					json.beginArray();
					while (json.hasNext()) {
						addFolder(FolderInfo.readFrom(json));
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