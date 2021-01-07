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

	private transient AlbumInfo _owner;
	
	private int _depth;
	private Kind _kind;
	private String _name;
	private Date _date;
	private int _width;
	private int _height;
	private String _comment;
	
	private String _previous;
	private String _next;
	private String _home;
	private String _end;
	
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
		_depth = owner != null ? owner.getDepth() : 0;
	}
	
	public ImageInfo() {
		super();
	}
	
	/**
	 * Number of parent resources of this resource.
	 */
	public int getDepth() {
		return _depth;
	}
	
	/**
	 * @see #getDepth()
	 */
	public void setDepth(int depth) {
		_depth = depth;
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

	/**
	 * Name of the resource preceding this one in its {@link #getAlbum()}.
	 */
	public String getPrevious() {
		return _previous;
	}

	void setPrevious(String name) {
		_previous = name;
	}

	/**
	 * Name of the resource following this one in its {@link #getAlbum()}.
	 */
	public String getNext() {
		return _next;
	}

	void setNext(String name) {
		_next = name;		
	}

	/** 
	 * Name of the first resource of the album of this image.
	 */
	public String getHome() {
		return _home;
	}

	void setHome(String home) {
		_home = home;
	}

	/** 
	 * Name of the last resource of the album of this image.
	 */
	public String getEnd() {
		return _end;
	}

	void setEnd(String end) {
		_end = end;
	}

	@Override
	public void writeContents(JsonWriter json) throws IOException {
		Json.property(json, "name", getName());
		Json.property(json, "width", getWidth());
		Json.property(json, "height", getHeight());
		Json.property(json, "date", getDate().getTime());
		Json.property(json, "depth", getDepth());
		Json.propertyOptional(json, "comment", getComment());
		Json.propertyOptional(json, "kind", getKind().name());
		Json.propertyOptional(json, "prev", getPrevious());
		Json.propertyOptional(json, "next", getNext());
		Json.property(json, "home", getHome());
		Json.property(json, "end", getEnd());
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
	public void readProperty(JsonReader json, String property)
			throws IOException {
		switch (property) {
			case "name": 
				setName(json.nextString());
				break;
			case "width":
				setWidth(json.nextInt());
				break;
			case "height":
				setHeight(json.nextInt());
				break;
			case "date":
				setDate(new Date(json.nextLong()));
				break;
			case "depth":
				setDepth(json.nextInt());
				break;
			case "comment":
				setComment(json.nextString());
				break;
			case "kind":
				setKind(Kind.valueOf(json.nextString()));
				break;
			case "prev":
				setPrevious(json.nextString());
				break;
			case "next":
				setNext(json.nextString());
				break;
			case "home":
				setHome(json.nextString());
				break;
			case "end":
				setEnd(json.nextString());
				break;
			default:
				Resource.super.readProperty(json, property);
		}
	}

	@Override
	public <R, A, E extends Throwable> R visit(Visitor<R, A, E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
