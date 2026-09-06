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

	///  The date this album is filed under, in milliseconds since the epoch, <code>0</code> when the
	///  author has set none.
	/// 
	///  <p>
	///  The explicit date and only the explicit one: this is what <code>index.json</code> stores and
	///  what the album's properties edit. What the album is actually sorted and placed by is
	///  {@link #effectiveDate}, see issue #48.
	///  </p>
	int date;

	///  The date this album is sorted and placed by, in milliseconds since the epoch, <code>0</code>
	///  when nothing says when it happened.
	/// 
	///  <p>
	///  Derived by the server on every read: the explicit {@link #date}, else the leading date of the
	///  album's folder name (<code>YYYY-MM-DD</code>, <code>YYYY-MM</code> or <code>YYYY</code>),
	///  else the earliest {@link ImagePart#date} of the images the album holds.
	///  </p>
	/// 
	///  <p>
	///  Derived data is never stored: the server clears this field before a sidecar is written, so
	///  that a round trip through a client cannot freeze a derived date into <code>index.json</code>.
	///  A sidecar that carries one nevertheless is read without complaint and answered with the
	///  derived value.
	///  </p>
	int effectiveDate;

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
			this.date = 0, 
			this.effectiveDate = 0, 
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
			case "date": {
				date = json.expectInt();
				break;
			}
			case "effectiveDate": {
				effectiveDate = json.expectInt();
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

		json.addKey("date");
		json.addNumber(date);

		json.addKey("effectiveDate");
		json.addNumber(effectiveDate);

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

///  How a {@link ListingInfo} files the albums that land in it, see {@link ListingInfo#placement}.
/// 
///  <p>
///  A folder named after a year or a month is an ordinary folder: it is created on demand and carries
///  no rule of its own.
///  </p>
enum Placement {
	///  Nothing is filed: an album stays where it was created or moved to.
	none,
	///  An album with a date lands in a folder named after its year (<code>2020</code>).
	byYear,
	///  An album with a date lands in a folder named after its month, inside its year folder
	///  (<code>2020/2020-05</code>).
	/// 
	///  <p>
	///  The month folder names its year too, so that it reads on its own and sorts anywhere. An album
	///  whose date is only known to the year (its folder is named <code>2020 Trip</code>) lands in the
	///  year folder itself: the server does not invent a month it was not told.
	///  </p>
	byYearMonth,
}

/// Writes a value of Placement to a JSON stream.
void writePlacement(JsonSink json, Placement value) {
	switch (value) {
		case Placement.none: json.addString("NONE"); break;
		case Placement.byYear: json.addString("BY_YEAR"); break;
		case Placement.byYearMonth: json.addString("BY_YEAR_MONTH"); break;
		default: throw ("No such literal: " + value.name);
	}
}

/// Reads a value of Placement from a JSON stream.
Placement readPlacement(JsonReader json) {
	switch (json.expectString()) {
		case "NONE": return Placement.none;
		case "BY_YEAR": return Placement.byYear;
		case "BY_YEAR_MONTH": return Placement.byYearMonth;
		default: return Placement.none;
	}
}

///  {@link Resource} describing collection {@link FolderInfo}s found in a directory.
class ListingInfo extends FolderResource {
	///  The title to display for this {@link ListingInfo}.
	String title;

	///  How this folder files what lands in it, see issue #48.
	/// 
	///  <p>
	///  Stored in this folder's own <code>index.json</code>. The rule places, it does not police: it
	///  is applied to an album created in this folder and to an entry moved into it, and to what is
	///  already here only when the owner asks for it (<code>&lt;folder&gt;/?action=place</code>).
	///  Whatever is filed by hand afterwards stays where it was put.
	///  </p>
	Placement placement;

	///  Description of the folders within this {@link ListingInfo}.
	List<FolderInfo> folders;

	/// Creates a ListingInfo.
	ListingInfo({
			super.path, 
			this.title = "", 
			this.placement = Placement.none, 
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
			case "placement": {
				placement = readPlacement(json);
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

		json.addKey("placement");
		writePlacement(json, placement);

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

	///  The date this folder is sorted by in its {@link ListingInfo}, in milliseconds since the epoch,
	///  <code>0</code> when nothing cheap says when it happened.
	/// 
	///  <p>
	///  Read from the folder's own sidecar (an explicit {@link AlbumInfo#date}) and from the folder
	///  name, and from nothing else: building a listing never opens the images of the albums it shows.
	///  An album with neither therefore carries <code>0</code> here although the album itself answers
	///  with an {@link AlbumInfo#effectiveDate} derived from its images; that is what a listing of a
	///  thousand albums costs, see issue #48.
	///  </p>
	int effectiveDate;

	///  The index picture of the {@link AlbumInfo} referenced by this {@link FolderInfo}.
	ThumbnailInfo? indexPicture;

	/// Creates a FolderInfo.
	FolderInfo({
			this.name = "", 
			this.title = "", 
			this.subTitle = "", 
			this.effectiveDate = 0, 
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
			case "effectiveDate": {
				effectiveDate = json.expectInt();
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

		json.addKey("effectiveDate");
		json.addNumber(effectiveDate);

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

	///  The name of the user signing in, empty for "the library owner".
	/// 
	///  <p>
	///  A request carrying the pairing secret signs in the library owner (the <code>admin</code>): an
	///  empty name means the owner, a non-empty one names the owner when it has no name yet and must
	///  match the stored name afterwards. An app from before issue #45 sends no name at all.
	///  </p>
	String userName;

	/// Creates a PairRequest.
	PairRequest({
			this.secret = "", 
			this.deviceName = "", 
			this.userName = "", 
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
			case "userName": {
				userName = json.expectString();
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

		json.addKey("userName");
		json.addString(userName);
	}

}

///  Answer to a successful {@link PairRequest}.
class PairResponse extends _JsonObject {
	///  The token to send as <code>Authorization: Bearer &lt;token&gt;</code> from now on.
	String token;

	///  The name the token was stored under.
	String deviceName;

	///  The name of the user this device now belongs to, empty while the owner is unnamed.
	String userName;

	///  The role of the signed-in user: <code>admin</code>, <code>member</code> or <code>guest</code>.
	String role;

	///  The folder below the server's base folder the user's library is rooted at, empty for the base folder itself.
	String space;

	/// Creates a PairResponse.
	PairResponse({
			this.token = "", 
			this.deviceName = "", 
			this.userName = "", 
			this.role = "", 
			this.space = "", 
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
			case "userName": {
				userName = json.expectString();
				break;
			}
			case "role": {
				role = json.expectString();
				break;
			}
			case "space": {
				space = json.expectString();
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

		json.addKey("userName");
		json.addString(userName);

		json.addKey("role");
		json.addString(role);

		json.addKey("space");
		json.addString(space);
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

	///  The name of the signed-in user, empty for an anonymous caller or an owner without a name yet.
	String userName;

	///  The caller's role: <code>admin</code>, <code>member</code> or <code>guest</code>; empty for an anonymous caller.
	String role;

	///  The folder below the server's base folder the caller's requests are resolved against, empty for the base folder itself.
	String space;

	/// Creates a AuthInfo.
	AuthInfo({
			this.mode = "", 
			this.deviceName = "", 
			this.writeAllowed = false, 
			this.userName = "", 
			this.role = "", 
			this.space = "", 
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
			case "userName": {
				userName = json.expectString();
				break;
			}
			case "role": {
				role = json.expectString();
				break;
			}
			case "space": {
				space = json.expectString();
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

		json.addKey("userName");
		json.addString(userName);

		json.addKey("role");
		json.addString(role);

		json.addKey("space");
		json.addString(space);
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

///  Request to move entries of one folder into another one, sent to
///  <code>&lt;source folder&gt;/?action=move</code>.
/// 
///  <p>
///  Moving is a rename: the pixels of an original are never touched, and everything the album knows
///  about a moved image (rating, privacy level, comment, orientation) travels with it, see issue
///  #47.
///  </p>
class MoveRequest extends _JsonObject {
	///  The folder the named entries are moved into, as a path relative to the caller's space.
	/// 
	///  <p>
	///  The empty string is the space root itself. A path leaving the caller's space is refused, as
	///  it is on every other endpoint.
	///  </p>
	String target;

	///  The entries of the addressed folder to move.
	/// 
	///  <p>
	///  Either the {@link ImagePart#name} of an image or video file, or the name of a sub-folder (an
	///  album or a folder of folders). Naming the representative of an {@link ImageGroup} moves the
	///  whole group; naming another member of it takes only that member out of the group.
	///  </p>
	/// 
	///  <p>
	///  A list of messages, not a list of plain strings: the Dart backend of the model generator
	///  mis-types a <code>repeated string</code> field, see {@link UploadCheck#hashes}.
	///  </p>
	List<MoveName> names;

	/// Creates a MoveRequest.
	MoveRequest({
			this.target = "", 
			this.names = const [], 
	});

	/// Parses a MoveRequest from a string source.
	static MoveRequest? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a MoveRequest instance from the given reader.
	static MoveRequest read(JsonReader json) {
		MoveRequest result = MoveRequest();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "MoveRequest";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "target": {
				target = json.expectString();
				break;
			}
			case "names": {
				json.expectArray();
				names = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = MoveName.read(json);
						if (value != null) {
							names.add(value);
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

		json.addKey("target");
		json.addString(target);

		json.addKey("names");
		json.startArray();
		for (var _element in names) {
			_element.writeContent(json);
		}
		json.endArray();
	}

}

///  The name of a single entry to move, see {@link MoveRequest#names}.
class MoveName extends _JsonObject {
	///  The name of the entry in the source folder.
	String name;

	/// Creates a MoveName.
	MoveName({
			this.name = "", 
	});

	/// Parses a MoveName from a string source.
	static MoveName? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a MoveName instance from the given reader.
	static MoveName read(JsonReader json) {
		MoveName result = MoveName();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "MoveName";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
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

		json.addKey("name");
		json.addString(name);
	}

}

///  Answer to a {@link MoveRequest}: what happened to every name it asked for.
/// 
///  <p>
///  A refusal that concerns a single entry is reported here, not as an error: the other entries did
///  move. Only a request that could not be carried out at all (an unreadable body, a folder that
///  does not exist, a caller that may not write) is answered with an {@link ErrorInfo}.
///  </p>
class MoveResult extends _JsonObject {
	///  One entry per {@link MoveRequest#names}, in the order they were asked for.
	List<MoveOutcome> outcomes;

	/// Creates a MoveResult.
	MoveResult({
			this.outcomes = const [], 
	});

	/// Parses a MoveResult from a string source.
	static MoveResult? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a MoveResult instance from the given reader.
	static MoveResult read(JsonReader json) {
		MoveResult result = MoveResult();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "MoveResult";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "outcomes": {
				json.expectArray();
				outcomes = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = MoveOutcome.read(json);
						if (value != null) {
							outcomes.add(value);
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

		json.addKey("outcomes");
		json.startArray();
		for (var _element in outcomes) {
			_element.writeContent(json);
		}
		json.endArray();
	}

}

///  What happened to a single entry of a {@link MoveRequest}.
class MoveOutcome extends _JsonObject {
	///  The name as it was asked for in {@link MoveRequest#names}.
	String name;

	///  The name the entry has in the target folder now, empty if it was not moved.
	/// 
	///  <p>
	///  It differs from {@link #name} when the target folder already held that name with different
	///  contents: the moved file is renamed exactly as a colliding upload is. When the target
	///  already held the very same contents, this is the name of the file that has them there.
	///  </p>
	/// 
	///  <p>
	///  A folder that the target's {@link ListingInfo#placement} rule filed away reports the path it
	///  has below the target folder (<code>2020/2020 Trip</code>), not just its name, see issue #48.
	///  </p>
	String newName;

	///  Why the entry was not moved, or what happened to it besides being moved; empty when it moved
	///  plainly.
	/// 
	///  <p>
	///  Nothing declines silently: an entry that did not move always says why here.
	///  </p>
	String message;

	/// Creates a MoveOutcome.
	MoveOutcome({
			this.name = "", 
			this.newName = "", 
			this.message = "", 
	});

	/// Parses a MoveOutcome from a string source.
	static MoveOutcome? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a MoveOutcome instance from the given reader.
	static MoveOutcome read(JsonReader json) {
		MoveOutcome result = MoveOutcome();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "MoveOutcome";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "name": {
				name = json.expectString();
				break;
			}
			case "newName": {
				newName = json.expectString();
				break;
			}
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

		json.addKey("name");
		json.addString(name);

		json.addKey("newName");
		json.addString(newName);

		json.addKey("message");
		json.addString(message);
	}

}

///  Answer to a <code>PUT</code> that created a new album folder, telling where the album landed, see
///  issue #48.
/// 
///  <p>
///  The folder a client asks for is not necessarily the folder the album ends up in: when the folder
///  above it carries a {@link ListingInfo#placement} rule, the album is filed into its year (or
///  month) folder. A client that ignores this answer would look for its new album where it is not.
///  </p>
class CreateResult extends _JsonObject {
	///  The path of the created folder, relative to the caller's space; never empty.
	/// 
	///  <p>
	///  The same coordinates a {@link MoveRequest#target} is given in.
	///  </p>
	String path;

	///  Why the album is not where it was asked for; empty when it was created exactly there.
	/// 
	///  <p>
	///  Nothing happens silently: an album that was filed away by a rule says so here.
	///  </p>
	String message;

	/// Creates a CreateResult.
	CreateResult({
			this.path = "", 
			this.message = "", 
	});

	/// Parses a CreateResult from a string source.
	static CreateResult? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a CreateResult instance from the given reader.
	static CreateResult read(JsonReader json) {
		CreateResult result = CreateResult();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "CreateResult";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "path": {
				path = json.expectString();
				break;
			}
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

		json.addKey("path");
		json.addString(path);

		json.addKey("message");
		json.addString(message);
	}

}

