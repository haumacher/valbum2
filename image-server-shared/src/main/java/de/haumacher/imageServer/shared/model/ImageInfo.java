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

	private AlbumInfo _owner;
	private String _name;
	private Date _date;
	private int _width;
	private int _height;
	private String _comment;
	private ImageInfo _previous;
	private ImageInfo _next;
	private Kind _kind;
	
	public enum Kind {
		IMAGE, VIDEO;
	}

	/** 
	 * Creates an {@link ImageInfo}.
	 */
	public ImageInfo(AlbumInfo owner, String name) {
		this();
		_owner = owner;
		_name = name;
	}
	
	public ImageInfo() {
		super();
	}
	
	/**
	 * The {@link AlbumInfo} this {@link ImageInfo} belongs to.
	 */
	public AlbumInfo getAlbum() {
		return _owner;
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
	public Kind getKind() {
		return _kind;
	}
	
	/** 
	 * TODO
	 *
	 */
	public void setKind(Kind kind) {
		_kind = kind;
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

	@Override
	public void writeContents(JsonWriter json) throws IOException {
		json.name("name");
		json.value(getName());
		json.name("width");
		json.value(getWidth());
		json.name("height");
		json.value(getHeight());
		json.name("date");
		json.value(getDate().getTime());
		Json.optionalProperty(json, "comment", getComment());
		Json.optionalProperty(json, "kind", getKind().name());
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
				case "kind":
					setKind(Kind.valueOf(json.nextString()));
					break;
			}
		}
		json.endObject();
	}

	@Override
	public <R, A, E extends Throwable> R visit(Visitor<R, A, E> v, A arg) throws E {
		return v.visit(this, arg);
	}
	
	/**
	 * The {@link ImageInfo} preceding this one in its {@link #getAlbum()}.
	 */
	public ImageInfo getPrevious() {
		return _previous;
	}

	void setPrevious(ImageInfo previous) {
		_previous = previous;
	}
	
	/**
	 * The {@link ImageInfo} following this one in its {@link #getAlbum()}.
	 */
	public ImageInfo getNext() {
		return _next;
	}

	void setNext(ImageInfo next) {
		_next = next;
	}

}
