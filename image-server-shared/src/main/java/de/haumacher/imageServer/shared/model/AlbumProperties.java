/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.model;

import java.io.IOException;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/**
 * Properties for a album as a whole.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class AlbumProperties {
	
	private String title;
	private String subTitle;
	
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

	/** 
	 * TODO
	 *
	 * @param json
	 * @param result
	 * @return
	 * @throws IOException
	 */
	public void readFrom(JsonReader json) throws IOException {
		json.beginObject();
		while (json.peek() == JsonToken.NAME) {
			switch (json.nextName()) {
				case "title": setTitle(json.nextString()); break;
				case "sub-title": setSubTitle(json.nextString()); break;
				default: json.skipValue();
			}
		}
		json.endObject();
	}

	public void writeTo(JsonWriter json) throws IOException {
		json.beginObject();
		json.name("title");
		json.value(getTitle());
		Json.value(json, "sub-title", getSubTitle());
		json.endObject();
	}

}
