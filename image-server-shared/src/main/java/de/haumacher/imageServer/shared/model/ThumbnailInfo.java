/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.model;

import java.io.IOException;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * Description of the thumbnail image of an album in a listing.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ThumbnailInfo implements JsonSerializable {
	
	private String _image;
	
	private double _scale = 1.0;
	private double _tx = 0.0;
	private double _ty = 0.0;

	/** 
	 * Creates a {@link ThumbnailInfo}.
	 */
	public ThumbnailInfo(String image, double scale) {
		this();
		this._image = image;
		this._scale = scale;
	}

	/** 
	 * Creates a {@link ThumbnailInfo}.
	 */
	public ThumbnailInfo() {
		super();
	}

	/**
	 * Name of the image resource.
	 */
	public String getImage() {
		return _image;
	}
	
	/**
	 * @see #getImage()
	 */
	public void setImage(String image) {
		this._image = image;
	}
	
	/**
	 * Scaling factor that displays the image in the listing.
	 */
	public double getScale() {
		return _scale;
	}
	
	/**
	 * Translation in X direction.
	 */
	public double getTx() {
		return _tx;
	}
	
	/**
	 * @see #getTx()
	 */
	public void setTx(double tx) {
		_tx = tx;
	}
	
	/**
	 * Translation in Y direction.
	 */
	public double getTy() {
		return _ty;
	}
	
	/**
	 * @see #getTy()
	 */
	public void setTy(double ty) {
		_ty = ty;
	}
	
	/**
	 * @see #getScale()
	 */
	public void setScale(double scale) {
		this._scale = scale;
	}

	@Override
	public void writeContents(JsonWriter json) throws IOException {
		json.name("image");
		json.value(_image);
		json.name("scale");
		json.value(_scale);
		json.name("tx");
		json.value(_tx);
		json.name("ty");
		json.value(_ty);
	}
	
	/**
	 * Reads a {@link ThumbnailInfo} from the given reader.
	 */
	public static ThumbnailInfo read(JsonReader json) throws IOException {
		ThumbnailInfo result = new ThumbnailInfo();
		result.readFrom(json);
		return result;
	}
	
	@Override
	public void readFrom(JsonReader json) throws IOException {
		_scale = 1.0;
		JsonSerializable.super.readFrom(json);
	}
	
	@Override
	public void readProperty(JsonReader json, String property)
			throws IOException {
		switch (property) {
			case "image":
				setImage(json.nextString());
				break;
			case "scale":
				setScale(json.nextDouble());
				break;
			case "tx":
				setTx(json.nextDouble());
				break;
			case "ty":
				setTy(json.nextDouble());
				break;
			default:
				JsonSerializable.super.readProperty(json, property);
		}
	}

}
