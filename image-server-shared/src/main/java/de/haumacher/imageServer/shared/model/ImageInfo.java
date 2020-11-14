/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.model;

import java.io.IOException;
import java.util.Date;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ImageInfo implements Resource {

	private String _name;
	private Date _date;
	private int _width;
	private int _height;
	private String _comment;
	
	/** 
	 * Creates an {@link ImageInfo}.
	 *
	 * @param name
	 */
	public ImageInfo(String name) {
		this();
		_name = name;
	}
	
	public ImageInfo() {
		super();
	}
	
	@Override
	public Type type() {
		return Type.image;
	}

	/**
	 * TODO
	 */
	public String getName() {
		return _name;
	}
	
	public void setName(String name) {
		_name = name;
	}
	
	/**
	 * TODO
	 */
	public Date getDate() {
		return _date;
	}
	
	public void setDate(Date date) {
		_date = date;
	}

	/**
	 * TODO
	 */
	public String getComment() {
		return _comment;
	}
	
	/** 
	 * TODO
	 *
	 * @param string
	 */
	public void setComment(String comment) {
		_comment = comment;
	}

	/**
	 * TODO
	 */
	public int getHeight() {
		return _height;
	}
	
	/** 
	 * TODO
	 *
	 * @param imageHeight
	 */
	public void setHeight(int height) {
		_height = height;
	}

	public void setWidth(int width) {
		_width = width;
	}
	
	/**
	 * TODO
	 */
	public int getWidth() {
		return _width;
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
		json.name("width");
		json.value(getWidth());
		json.name("height");
		json.value(getHeight());
		json.name("date");
		json.value(getDate().getTime());
		Json.value(json, "comment", getComment());
		json.endObject();
	}
	
	/**
	 * Reads an {@link ImageInfo} as JSON object from the given {@link JsonReader}.
	 */
	public static ImageInfo read(JsonReader json) throws IOException {
		ImageInfo result = new ImageInfo();
		result.readFrom(json);
		return result;
	}

	@Override
	public void readFrom(JsonReader json) throws IOException {
		json.beginObject();
		while (json.hasNext()) {
			switch (json.nextName()) {
				case "name": 
					setName(json.nextString());
					break;
				case "widht":
					setWidth(json.nextInt());
					break;
				case "height":
					setHeight(json.nextInt());
					break;
				case "date":
					setDate(new Date(json.nextLong()));
					break;
				case "comment":
					setComment(json.nextString());
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
