/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.IOException;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class AlbumIndex {
	
	private String title;
	private String subTitle;

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
	public static AlbumIndex read(JsonReader json) throws IOException {
		AlbumIndex result = new AlbumIndex();
		json.beginObject();
		while (json.peek() == JsonToken.NAME) {
			switch (json.nextName()) {
				case "title": result.setTitle(json.nextString()); break;
				case "sub-title": result.setSubTitle(json.nextString()); break;
				default: json.skipValue();
			}
		}
		json.endObject();
		return result;
	}

	public void writeTo(JsonWriter json) throws IOException {
		json.beginObject();
		json.name("title");
		json.value(getTitle());
		Json.value(json, "sub-title", getSubTitle());
		json.endObject();
	}

}
