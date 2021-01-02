/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.model;

import java.io.IOException;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * Properties for a album as a whole.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class AlbumProperties implements JsonSerializable {
	
	private String title;
	private String subTitle;
	private String indexPicture;
	
	/** 
	 * Creates an {@link AlbumProperties}.
	 */
	public AlbumProperties() {
		super();
	}

	/**
	 * TODO
	 */
	public String getTitle() {
		return title;
	}
	
	public void setTitle(String title) {
		this.title = title;
	}

	/**
	 * TODO
	 */
	public String getSubTitle() {
		return subTitle;
	}


	public void setSubTitle(String subTitle) {
		this.subTitle = subTitle;
	}
	
	/**
	 * Name of the resource to display, if this album is shown in a listing.
	 */
	public String getIndexPicture() {
		return indexPicture;
	}
	
	/**
	 * @see #getIndexPicture()
	 */
	public void setIndexPicture(String indexPicture) {
		this.indexPicture = indexPicture;
	}

	/** 
	 * TODO
	 *
	 * @param json
	 * @return
	 * @throws IOException 
	 */
	public static AlbumProperties read(JsonReader json) throws IOException {
		AlbumProperties result = new AlbumProperties();
		result.readFrom(json);
		return result;
	}

	@Override
	public void readProperty(JsonReader json, String property) throws IOException {
		switch (property) {
			case "title": setTitle(json.nextString()); break;
			case "sub-title": setSubTitle(json.nextString()); break;
			case "index-picture": setIndexPicture(json.nextString()); break;
			default: JsonSerializable.super.readProperty(json, property);
		}
	}

	@Override
	public void writeContents(JsonWriter json) throws IOException {
		json.name("title");
		json.value(getTitle());
		Json.optionalProperty(json, "sub-title", getSubTitle());
		Json.optionalProperty(json, "index-picture", getIndexPicture());
	}

}
