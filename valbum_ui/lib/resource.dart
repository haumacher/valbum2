import 'package:jsontool/jsontool.dart';

/// Common functionality for JSON generation and parsing.
abstract class _JsonObject {
	@override
	String toString() {
		var buffer = StringBuffer();
		writeTo(jsonStringWriter(buffer));
		return buffer.toString();
	}

	/// The ID to announce the type of the object.
	String _jsonType();

	/// Reads the object contents (after the type information).
	void _readContent(JsonReader json) {
		json.expectObject();
		while (json.hasNextKey()) {
			var key = json.nextKey();
			_readProperty(key!, json);
		}
	}

	/// Reads the value of the property with the given name.
	void _readProperty(String key, JsonReader json) {
		json.skipAnyValue();
	}

	/// Writes this object to the given writer (including type information).
	void writeTo(JsonSink json) {
		json.startArray();
		json.addString(_jsonType());
		writeContent(json);
		json.endArray();
	}

	/// Writes the contents of this object to the given writer (excluding type information).
	void writeContent(JsonSink json) {
		json.startObject();
		_writeProperties(json);
		json.endObject();
	}

	/// Writes all key/value pairs of this object.
	void _writeProperties(JsonSink json) {
		// No properties.
	}
}

/// Visitor interface for Resource.
abstract class ResourceVisitor<R, A> implements FolderResourceVisitor<R, A>, AlbumPartVisitor<R, A> {
	R visitErrorInfo(ErrorInfo self, A arg);
}

///  Base class for a resource being displayed as view in a photo album.
abstract class Resource extends _JsonObject {
	/// Creates a Resource.
	Resource();

	/// Parses a Resource from a string source.
	static Resource? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a Resource instance from the given reader.
	static Resource? read(JsonReader json) {
		Resource? result;

		json.expectArray();
		if (!json.hasNext()) {
			return null;
		}

		switch (json.expectString()) {
			case "ErrorInfo": result = ErrorInfo(); break;
			case "AlbumInfo": result = AlbumInfo(); break;
			case "ListingInfo": result = ListingInfo(); break;
			case "Heading": result = Heading(); break;
			case "ImageGroup": result = ImageGroup(); break;
			case "ImagePart": result = ImagePart(); break;
			default: result = null;
		}

		if (!json.hasNext() || json.tryNull()) {
			return null;
		}

		if (result == null) {
			json.skipAnyValue();
		} else {
			result._readContent(json);
		}
		json.endArray();

		return result;
	}

	R visitResource<R, A>(ResourceVisitor<R, A> v, A arg);

}

/// Visitor interface for FolderResource.
abstract class FolderResourceVisitor<R, A> {
	R visitAlbumInfo(AlbumInfo self, A arg);
	R visitListingInfo(ListingInfo self, A arg);
}

///  {@link Resource} representing a directory.
abstract class FolderResource extends Resource {
	///  The path where the {@link Resource} is located on the server relative to it's base directory
	String path;

	/// Creates a FolderResource.
	FolderResource({
			this.path = "", 
	});

	/// Parses a FolderResource from a string source.
	static FolderResource? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a FolderResource instance from the given reader.
	static FolderResource? read(JsonReader json) {
		FolderResource? result;

		json.expectArray();
		if (!json.hasNext()) {
			return null;
		}

		switch (json.expectString()) {
			case "AlbumInfo": result = AlbumInfo(); break;
			case "ListingInfo": result = ListingInfo(); break;
			default: result = null;
		}

		if (!json.hasNext() || json.tryNull()) {
			return null;
		}

		if (result == null) {
			json.skipAnyValue();
		} else {
			result._readContent(json);
		}
		json.endArray();

		return result;
	}

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);
	}

	R visitFolderResource<R, A>(FolderResourceVisitor<R, A> v, A arg);

	@override
	R visitResource<R, A>(ResourceVisitor<R, A> v, A arg) => visitFolderResource(v, arg);

}

///  {@link Resource} describing a collection of {@link AlbumPart}s.
class AlbumInfo extends FolderResource {
	///  The title of this album.
	String title;

	///  The subtitle of this album.
	String subTitle;

	///  Description of the image used to display this whole album in a listing.
	ThumbnailInfo? indexPicture;

	///  The list of images in this album.
	List<AlbumPart> parts;

	///  All {@link ImagePart}s indexed by their {@link ImagePart#name}.
	Map<String, ImagePart> imageByName;

	///  The minimum {@link ImagePart#rating} of an {@link ImagePart} to be displayed.
	/// 
	///  <p>The value is set by the UI to remember the current display settings of an {@link AlbumInfo} while browsing its contents</p>
	int minRating;

	/// Creates a AlbumInfo.
	AlbumInfo({
			super.path, 
			this.title = "", 
			this.subTitle = "", 
			this.indexPicture, 
			this.parts = const [], 
			this.imageByName = const {}, 
			this.minRating = 0, 
	});

	/// Parses a AlbumInfo from a string source.
	static AlbumInfo? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a AlbumInfo instance from the given reader.
	static AlbumInfo read(JsonReader json) {
		AlbumInfo result = AlbumInfo();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "AlbumInfo";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "title": {
				title = json.expectString();
				break;
			}
			case "subTitle": {
				subTitle = json.expectString();
				break;
			}
			case "indexPicture": {
				indexPicture = json.tryNull() ? null : ThumbnailInfo.read(json);
				break;
			}
			case "parts": {
				json.expectArray();
				parts = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = AlbumPart.read(json);
						if (value != null) {
							parts.add(value);
						}
					}
				}
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("title");
		json.addString(title);

		json.addKey("subTitle");
		json.addString(subTitle);

		var _indexPicture = indexPicture;
		if (_indexPicture != null) {
			json.addKey("indexPicture");
			_indexPicture.writeContent(json);
		}

		json.addKey("parts");
		json.startArray();
		for (var _element in parts) {
			_element.writeTo(json);
		}
		json.endArray();
	}

	@override
	R visitFolderResource<R, A>(FolderResourceVisitor<R, A> v, A arg) => v.visitAlbumInfo(this, arg);

}

/// Visitor interface for AlbumPart.
abstract class AlbumPartVisitor<R, A> implements AbstractImageVisitor<R, A> {
	R visitHeading(Heading self, A arg);
}

///  Base class for contents of an {@link AlbumInfo}.
abstract class AlbumPart extends Resource {
	///  The {@link AlbumInfo}, this one is part of.
	AlbumInfo? owner;

	/// Creates a AlbumPart.
	AlbumPart({
			this.owner, 
	});

	/// Parses a AlbumPart from a string source.
	static AlbumPart? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a AlbumPart instance from the given reader.
	static AlbumPart? read(JsonReader json) {
		AlbumPart? result;

		json.expectArray();
		if (!json.hasNext()) {
			return null;
		}

		switch (json.expectString()) {
			case "Heading": result = Heading(); break;
			case "ImageGroup": result = ImageGroup(); break;
			case "ImagePart": result = ImagePart(); break;
			default: result = null;
		}

		if (!json.hasNext() || json.tryNull()) {
			return null;
		}

		if (result == null) {
			json.skipAnyValue();
		} else {
			result._readContent(json);
		}
		json.endArray();

		return result;
	}

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);
	}

	R visitAlbumPart<R, A>(AlbumPartVisitor<R, A> v, A arg);

	@override
	R visitResource<R, A>(ResourceVisitor<R, A> v, A arg) => visitAlbumPart(v, arg);

}

///  A heading row separating images in an album.
class Heading extends AlbumPart {
	///  The text to display.
	String text;

	/// Creates a Heading.
	Heading({
			super.owner, 
			this.text = "", 
	});

	/// Parses a Heading from a string source.
	static Heading? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a Heading instance from the given reader.
	static Heading read(JsonReader json) {
		Heading result = Heading();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "Heading";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "text": {
				text = json.expectString();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("text");
		json.addString(text);
	}

	@override
	R visitAlbumPart<R, A>(AlbumPartVisitor<R, A> v, A arg) => v.visitHeading(this, arg);

}

/// Visitor interface for AbstractImage.
abstract class AbstractImageVisitor<R, A> {
	R visitImageGroup(ImageGroup self, A arg);
	R visitImagePart(ImagePart self, A arg);
}

///  Part of an album that can be represented as an image.
abstract class AbstractImage extends AlbumPart {
	///  The previous image in the {@link #owner}.
	AbstractImage? previous;

	///  The next image in the {@link #owner}.
	AbstractImage? next;

	///  The first image of the {@link #owner}.
	AbstractImage? home;

	///  The last image of the {@link #owner}.
	AbstractImage? end;

	/// Creates a AbstractImage.
	AbstractImage({
			super.owner, 
			this.previous, 
			this.next, 
			this.home, 
			this.end, 
	});

	/// Parses a AbstractImage from a string source.
	static AbstractImage? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a AbstractImage instance from the given reader.
	static AbstractImage? read(JsonReader json) {
		AbstractImage? result;

		json.expectArray();
		if (!json.hasNext()) {
			return null;
		}

		switch (json.expectString()) {
			case "ImageGroup": result = ImageGroup(); break;
			case "ImagePart": result = ImagePart(); break;
			default: result = null;
		}

		if (!json.hasNext() || json.tryNull()) {
			return null;
		}

		if (result == null) {
			json.skipAnyValue();
		} else {
			result._readContent(json);
		}
		json.endArray();

		return result;
	}

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);
	}

	R visitAbstractImage<R, A>(AbstractImageVisitor<R, A> v, A arg);

	@override
	R visitAlbumPart<R, A>(AlbumPartVisitor<R, A> v, A arg) => visitAbstractImage(v, arg);

}

///  A group of multiple images showing the same content.
class ImageGroup extends AbstractImage {
	///  The index of the {@link ImagePart} in {@link #images} of the image that should be displayed when displaying this {@link ImageGroup} in an album.
	int representative;

	///  List of images that all show the same content. Only the image with  in this album.
	List<ImagePart> images;

	/// Creates a ImageGroup.
	ImageGroup({
			super.previous, 
			super.next, 
			super.home, 
			super.end, 
			super.owner, 
			this.representative = 0, 
			this.images = const [], 
	});

	/// Parses a ImageGroup from a string source.
	static ImageGroup? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a ImageGroup instance from the given reader.
	static ImageGroup read(JsonReader json) {
		ImageGroup result = ImageGroup();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "ImageGroup";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "representative": {
				representative = json.expectInt();
				break;
			}
			case "images": {
				json.expectArray();
				images = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = ImagePart.read(json);
						if (value != null) {
							images.add(value);
						}
					}
				}
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("representative");
		json.addNumber(representative);

		json.addKey("images");
		json.startArray();
		for (var _element in images) {
			_element.writeContent(json);
		}
		json.endArray();
	}

	@override
	R visitAbstractImage<R, A>(AbstractImageVisitor<R, A> v, A arg) => v.visitImageGroup(this, arg);

}

///  Kind of image.
enum ImageKind {
	///  A JPEG image.
	image,
	///  A mp4 video. 
	/// 
	///  <p>For historical reason, this kind is named "video" and not "mp4".</p>
	video,
	///  A quicktime video.
	quicktime,
}

/// Writes a value of ImageKind to a JSON stream.
void writeImageKind(JsonSink json, ImageKind value) {
	switch (value) {
		case ImageKind.image: json.addString("IMAGE"); break;
		case ImageKind.video: json.addString("VIDEO"); break;
		case ImageKind.quicktime: json.addString("QUICKTIME"); break;
		default: throw ("No such literal: " + value.name);
	}
}

/// Reads a value of ImageKind from a JSON stream.
ImageKind readImageKind(JsonReader json) {
	switch (json.expectString()) {
		case "IMAGE": return ImageKind.image;
		case "VIDEO": return ImageKind.video;
		case "QUICKTIME": return ImageKind.quicktime;
		default: return ImageKind.image;
	}
}

///  {@link Resource} describing a single image or video file.
class ImagePart extends AbstractImage {
	///  The kind of this {@link ImagePart}.
	ImageKind kind;

	///  The image (file) name.
	String name;

	///  The last modification date of the image in milliseconds since epoch.
	int date;

	///  The width of the original image in pixels.
	int width;

	///  The height of the original image in pixels.
	int height;

	///  A transformation applied to the image (in addition to the transformation encoded in the image itself).
	Orientation orientation;

	///  A rating of this image from -2 to 2.
	int rating;

	///  A privacy level from 0 to 2.
	int privacy;

	///  A comment describing what this image contains.
	String comment;

	///  The {@link ImageGroup}, this {@link ImagePart} is part of, or <code>null</code>, if this {@link ImagePart} is not part of a group.
	ImageGroup? group;

	/// Creates a ImagePart.
	ImagePart({
			super.previous, 
			super.next, 
			super.home, 
			super.end, 
			super.owner, 
			this.kind = ImageKind.image, 
			this.name = "", 
			this.date = 0, 
			this.width = 0, 
			this.height = 0, 
			this.orientation = Orientation.identity, 
			this.rating = 0, 
			this.privacy = 0, 
			this.comment = "", 
			this.group, 
	});

	/// Parses a ImagePart from a string source.
	static ImagePart? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a ImagePart instance from the given reader.
	static ImagePart read(JsonReader json) {
		ImagePart result = ImagePart();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "ImagePart";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "kind": {
				kind = readImageKind(json);
				break;
			}
			case "name": {
				name = json.expectString();
				break;
			}
			case "date": {
				date = json.expectInt();
				break;
			}
			case "width": {
				width = json.expectInt();
				break;
			}
			case "height": {
				height = json.expectInt();
				break;
			}
			case "orientation": {
				orientation = readOrientation(json);
				break;
			}
			case "rating": {
				rating = json.expectInt();
				break;
			}
			case "privacy": {
				privacy = json.expectInt();
				break;
			}
			case "comment": {
				comment = json.expectString();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("kind");
		writeImageKind(json, kind);

		json.addKey("name");
		json.addString(name);

		json.addKey("date");
		json.addNumber(date);

		json.addKey("width");
		json.addNumber(width);

		json.addKey("height");
		json.addNumber(height);

		json.addKey("orientation");
		writeOrientation(json, orientation);

		json.addKey("rating");
		json.addNumber(rating);

		json.addKey("privacy");
		json.addNumber(privacy);

		json.addKey("comment");
		json.addString(comment);
	}

	@override
	R visitAbstractImage<R, A>(AbstractImageVisitor<R, A> v, A arg) => v.visitImagePart(this, arg);

}

///  Values of a JPEG orientation tag.
/// 
///  <pre>
///    1        2       3      4         5            6           7          8
/// 
///  888888  888888      88  88      8888888888  88                  88  8888888888
///  88          88      88  88      88  88      88  88          88  88      88  88
///  8888      8888    8888  8888    88          8888888888  8888888888          88
///  88          88      88  88
///  88          88  888888  888888
///  </pre>
/// 
///  @see "http://sylvana.net/jpegcrop/exif_orientation.html"
enum Orientation {
	///  No transformation, use raw image data from top to bottom and left to right.
	/// 
	///  <pre>
	///  Value	0th Row		0th Column
	///  1		top			left side
	///  </pre>
	identity,
	///  <pre>
	///  Value: 2
	///  0th Row: top
	///  0th Column: right side
	///  </pre>
	flipH,
	///  <pre>
	///  Value: 3 
	///  0th Row: bottom
	///  0th Column: right side
	///  </pre>
	rot180,
	///  <pre>
	///  Value: 4
	///  0th Row: bottom 
	///  0th Column: left side
	///  </pre>
	flipV,
	///  <pre>
	///  Value: 5
	///  0th Row: left side
	///  0th Column: top
	///  </pre>
	rotLFlipV,
	///  <pre>
	///  Value: 6
	///  0th Row: right side
	///  0th Column: top
	///  </pre>
	rotL,
	///  <pre>
	///  Value: 7
	///  0th Row: right side
	///  0th Column: bottom
	///  </pre>
	rotLFlipH,
	///  <pre>
	///  Value: 8
	///  0th Row: left side
	///  0th Column: bottom
	///  </pre>
	rotR,
}

/// Writes a value of Orientation to a JSON stream.
void writeOrientation(JsonSink json, Orientation value) {
	switch (value) {
		case Orientation.identity: json.addString("IDENTITY"); break;
		case Orientation.flipH: json.addString("FLIP_H"); break;
		case Orientation.rot180: json.addString("ROT_180"); break;
		case Orientation.flipV: json.addString("FLIP_V"); break;
		case Orientation.rotLFlipV: json.addString("ROT_L_FLIP_V"); break;
		case Orientation.rotL: json.addString("ROT_L"); break;
		case Orientation.rotLFlipH: json.addString("ROT_L_FLIP_H"); break;
		case Orientation.rotR: json.addString("ROT_R"); break;
		default: throw ("No such literal: " + value.name);
	}
}

/// Reads a value of Orientation from a JSON stream.
Orientation readOrientation(JsonReader json) {
	switch (json.expectString()) {
		case "IDENTITY": return Orientation.identity;
		case "FLIP_H": return Orientation.flipH;
		case "ROT_180": return Orientation.rot180;
		case "FLIP_V": return Orientation.flipV;
		case "ROT_L_FLIP_V": return Orientation.rotLFlipV;
		case "ROT_L": return Orientation.rotL;
		case "ROT_L_FLIP_H": return Orientation.rotLFlipH;
		case "ROT_R": return Orientation.rotR;
		default: return Orientation.identity;
	}
}

///  {@link Resource} describing collection {@link FolderInfo}s found in a directory.
class ListingInfo extends FolderResource {
	///  The title to display for this {@link ListingInfo}.
	String title;

	///  Description of the folders within this {@link ListingInfo}.
	List<FolderInfo> folders;

	/// Creates a ListingInfo.
	ListingInfo({
			super.path, 
			this.title = "", 
			this.folders = const [], 
	});

	/// Parses a ListingInfo from a string source.
	static ListingInfo? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a ListingInfo instance from the given reader.
	static ListingInfo read(JsonReader json) {
		ListingInfo result = ListingInfo();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "ListingInfo";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "title": {
				title = json.expectString();
				break;
			}
			case "folders": {
				json.expectArray();
				folders = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = FolderInfo.read(json);
						if (value != null) {
							folders.add(value);
						}
					}
				}
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("title");
		json.addString(title);

		json.addKey("folders");
		json.startArray();
		for (var _element in folders) {
			_element.writeContent(json);
		}
		json.endArray();
	}

	@override
	R visitFolderResource<R, A>(FolderResourceVisitor<R, A> v, A arg) => v.visitListingInfo(this, arg);

}

///  Part of a {@link ListingInfo} describing a reference to a single album directory.
class FolderInfo extends _JsonObject {
	///  The directory name of this {@link FolderInfo}.
	String name;

	///  The title of the {@link AlbumInfo} referenced by this {@link FolderInfo}.
	String title;

	///  The subtitle of the {@link AlbumInfo} referenced by this {@link FolderInfo}.
	String subTitle;

	///  The index picture of the {@link AlbumInfo} referenced by this {@link FolderInfo}.
	ThumbnailInfo? indexPicture;

	/// Creates a FolderInfo.
	FolderInfo({
			this.name = "", 
			this.title = "", 
			this.subTitle = "", 
			this.indexPicture, 
	});

	/// Parses a FolderInfo from a string source.
	static FolderInfo? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a FolderInfo instance from the given reader.
	static FolderInfo read(JsonReader json) {
		FolderInfo result = FolderInfo();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "FolderInfo";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "name": {
				name = json.expectString();
				break;
			}
			case "title": {
				title = json.expectString();
				break;
			}
			case "subTitle": {
				subTitle = json.expectString();
				break;
			}
			case "indexPicture": {
				indexPicture = json.tryNull() ? null : ThumbnailInfo.read(json);
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("name");
		json.addString(name);

		json.addKey("title");
		json.addString(title);

		json.addKey("subTitle");
		json.addString(subTitle);

		var _indexPicture = indexPicture;
		if (_indexPicture != null) {
			json.addKey("indexPicture");
			_indexPicture.writeContent(json);
		}
	}

}

///  Part of a {@link FolderInfo} describing the thumbnail image for displaying this folder in a {@link ListingInfo}.
class ThumbnailInfo extends _JsonObject {
	///  Name of the image to use as thumbnail.
	String image;

	///  The factor to scale the original image for producing the thumbnail image.
	double scale;

	///  The translation in X to apply to the the original image for producing the thumbnail image.
	double tx;

	///  The translation in Y to apply to the the original image for producing the thumbnail image.
	double ty;

	/// Creates a ThumbnailInfo.
	ThumbnailInfo({
			this.image = "", 
			this.scale = 0.0, 
			this.tx = 0.0, 
			this.ty = 0.0, 
	});

	/// Parses a ThumbnailInfo from a string source.
	static ThumbnailInfo? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a ThumbnailInfo instance from the given reader.
	static ThumbnailInfo read(JsonReader json) {
		ThumbnailInfo result = ThumbnailInfo();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "ThumbnailInfo";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "image": {
				image = json.expectString();
				break;
			}
			case "scale": {
				scale = json.expectDouble();
				break;
			}
			case "tx": {
				tx = json.expectDouble();
				break;
			}
			case "ty": {
				ty = json.expectDouble();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("image");
		json.addString(image);

		json.addKey("scale");
		json.addNumber(scale);

		json.addKey("tx");
		json.addNumber(tx);

		json.addKey("ty");
		json.addNumber(ty);
	}

}

///  {@link Resource} that produced a server-side error while loading.
class ErrorInfo extends Resource {
	///  The error message.
	String message;

	/// Creates a ErrorInfo.
	ErrorInfo({
			this.message = "", 
	});

	/// Parses a ErrorInfo from a string source.
	static ErrorInfo? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a ErrorInfo instance from the given reader.
	static ErrorInfo read(JsonReader json) {
		ErrorInfo result = ErrorInfo();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "ErrorInfo";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "message": {
				message = json.expectString();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("message");
		json.addString(message);
	}

	@override
	R visitResource<R, A>(ResourceVisitor<R, A> v, A arg) => v.visitErrorInfo(this, arg);

}

///  Request to issue a device token, sent to <code>&lt;data&gt;/?action=pair</code>.
class PairRequest extends _JsonObject {
	///  The pairing secret the server was started with.
	String secret;

	///  The name the device announces itself with.
	String deviceName;

	/// Creates a PairRequest.
	PairRequest({
			this.secret = "", 
			this.deviceName = "", 
	});

	/// Parses a PairRequest from a string source.
	static PairRequest? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a PairRequest instance from the given reader.
	static PairRequest read(JsonReader json) {
		PairRequest result = PairRequest();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "PairRequest";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "secret": {
				secret = json.expectString();
				break;
			}
			case "deviceName": {
				deviceName = json.expectString();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("secret");
		json.addString(secret);

		json.addKey("deviceName");
		json.addString(deviceName);
	}

}

///  Answer to a successful {@link PairRequest}.
class PairResponse extends _JsonObject {
	///  The token to send as <code>Authorization: Bearer &lt;token&gt;</code> from now on.
	String token;

	///  The name the token was stored under.
	String deviceName;

	/// Creates a PairResponse.
	PairResponse({
			this.token = "", 
			this.deviceName = "", 
	});

	/// Parses a PairResponse from a string source.
	static PairResponse? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a PairResponse instance from the given reader.
	static PairResponse read(JsonReader json) {
		PairResponse result = PairResponse();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "PairResponse";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "token": {
				token = json.expectString();
				break;
			}
			case "deviceName": {
				deviceName = json.expectString();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("token");
		json.addString(token);

		json.addKey("deviceName");
		json.addString(deviceName);
	}

}

///  The authentication state of the caller, answered by <code>&lt;data&gt;/?type=auth</code>.
class AuthInfo extends _JsonObject {
	///  The authentication mode of the server: <code>off</code>, <code>writes</code>, or <code>all</code>.
	String mode;

	///  The name of the device the caller is paired as, empty if the caller is anonymous.
	String deviceName;

	///  Whether the caller may perform write requests.
	bool writeAllowed;

	/// Creates a AuthInfo.
	AuthInfo({
			this.mode = "", 
			this.deviceName = "", 
			this.writeAllowed = false, 
	});

	/// Parses a AuthInfo from a string source.
	static AuthInfo? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a AuthInfo instance from the given reader.
	static AuthInfo read(JsonReader json) {
		AuthInfo result = AuthInfo();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "AuthInfo";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "mode": {
				mode = json.expectString();
				break;
			}
			case "deviceName": {
				deviceName = json.expectString();
				break;
			}
			case "writeAllowed": {
				writeAllowed = json.expectBool();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("mode");
		json.addString(mode);

		json.addKey("deviceName");
		json.addString(deviceName);

		json.addKey("writeAllowed");
		json.addBool(writeAllowed);
	}

}

///  Request asking a folder which of the given contents it already holds, sent to
///  <code>&lt;folder&gt;/?action=check</code>.
/// 
///  <p>
///  Asking is a read: it only reveals what the folder contains. A client sends it before an upload
///  so that it can skip transferring what is already there.
///  </p>
class UploadCheck extends _JsonObject {
	///  The contents the client intends to upload.
	/// 
	///  <p>
	///  A list of messages, not a list of plain strings: the Dart backend of the model generator
	///  mis-types a <code>repeated string</code> field.
	///  </p>
	List<ContentHash> hashes;

	/// Creates a UploadCheck.
	UploadCheck({
			this.hashes = const [], 
	});

	/// Parses a UploadCheck from a string source.
	static UploadCheck? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a UploadCheck instance from the given reader.
	static UploadCheck read(JsonReader json) {
		UploadCheck result = UploadCheck();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "UploadCheck";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "hashes": {
				json.expectArray();
				hashes = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = ContentHash.read(json);
						if (value != null) {
							hashes.add(value);
						}
					}
				}
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("hashes");
		json.startArray();
		for (var _element in hashes) {
			_element.writeContent(json);
		}
		json.endArray();
	}

}

///  The hash of a single content, see {@link UploadCheck}.
class ContentHash extends _JsonObject {
	///  The SHA-256 hash (lower-case hex) of the content.
	String hash;

	/// Creates a ContentHash.
	ContentHash({
			this.hash = "", 
	});

	/// Parses a ContentHash from a string source.
	static ContentHash? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a ContentHash instance from the given reader.
	static ContentHash read(JsonReader json) {
		ContentHash result = ContentHash();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "ContentHash";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "hash": {
				hash = json.expectString();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("hash");
		json.addString(hash);
	}

}

///  Answer to an {@link UploadCheck} naming those of the asked hashes that the folder already holds.
class UploadCheckResult extends _JsonObject {
	///  The asked contents that are already present, in the order they were asked for.
	List<PresentFile> present;

	/// Creates a UploadCheckResult.
	UploadCheckResult({
			this.present = const [], 
	});

	/// Parses a UploadCheckResult from a string source.
	static UploadCheckResult? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a UploadCheckResult instance from the given reader.
	static UploadCheckResult read(JsonReader json) {
		UploadCheckResult result = UploadCheckResult();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "UploadCheckResult";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "present": {
				json.expectArray();
				present = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = PresentFile.read(json);
						if (value != null) {
							present.add(value);
						}
					}
				}
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("present");
		json.startArray();
		for (var _element in present) {
			_element.writeContent(json);
		}
		json.endArray();
	}

}

///  A content of an {@link UploadCheckResult} that the folder already holds.
class PresentFile extends _JsonObject {
	///  The SHA-256 hash (lower-case hex) that was asked for.
	String hash;

	///  The name of the file in the folder that has this content.
	String name;

	/// Creates a PresentFile.
	PresentFile({
			this.hash = "", 
			this.name = "", 
	});

	/// Parses a PresentFile from a string source.
	static PresentFile? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a PresentFile instance from the given reader.
	static PresentFile read(JsonReader json) {
		PresentFile result = PresentFile();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "PresentFile";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "hash": {
				hash = json.expectString();
				break;
			}
			case "name": {
				name = json.expectString();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("hash");
		json.addString(hash);

		json.addKey("name");
		json.addString(name);
	}

}

///  Answer to an upload, telling for every received file whether it was stored or was already
///  present.
/// 
///  <p>
///  An upload is idempotent: a retry after a lost connection reports the files as
///  {@link UploadedFile#getStatus() present} instead of storing them a second time.
///  </p>
class UploadResult extends _JsonObject {
	///  One entry per file of the upload request, in the order they were received.
	List<UploadedFile> files;

	/// Creates a UploadResult.
	UploadResult({
			this.files = const [], 
	});

	/// Parses a UploadResult from a string source.
	static UploadResult? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a UploadResult instance from the given reader.
	static UploadResult read(JsonReader json) {
		UploadResult result = UploadResult();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "UploadResult";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "files": {
				json.expectArray();
				files = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = UploadedFile.read(json);
						if (value != null) {
							files.add(value);
						}
					}
				}
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("files");
		json.startArray();
		for (var _element in files) {
			_element.writeContent(json);
		}
		json.endArray();
	}

}

///  What happened to a single file of an upload, see {@link UploadResult}.
class UploadedFile extends _JsonObject {
	///  The file name as it was sent by the client.
	String name;

	///  The name of the file on the server: the (potentially de-duplicated) name the contents were
	///  stored under, or the name of the existing file that already had these contents.
	String storedAs;

	///  The SHA-256 hash (lower-case hex) of the received contents, as computed by the server.
	String hash;

	///  <code>stored</code> if the contents were written to the album, <code>present</code> if the
	///  folder already held them and nothing was written.
	String status;

	/// Creates a UploadedFile.
	UploadedFile({
			this.name = "", 
			this.storedAs = "", 
			this.hash = "", 
			this.status = "", 
	});

	/// Parses a UploadedFile from a string source.
	static UploadedFile? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a UploadedFile instance from the given reader.
	static UploadedFile read(JsonReader json) {
		UploadedFile result = UploadedFile();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "UploadedFile";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "name": {
				name = json.expectString();
				break;
			}
			case "storedAs": {
				storedAs = json.expectString();
				break;
			}
			case "hash": {
				hash = json.expectString();
				break;
			}
			case "status": {
				status = json.expectString();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("name");
		json.addString(name);

		json.addKey("storedAs");
		json.addString(storedAs);

		json.addKey("hash");
		json.addString(hash);

		json.addKey("status");
		json.addString(status);
	}

}

