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

	///  What the caller may do with this folder, see issue #49.
	/// 
	///  <p>
	///  The {@link RightName#name names} of the rights the caller holds here: <code>view</code>,
	///  <code>download</code>, <code>contribute</code>, <code>edit</code>. The stronger rights imply
	///  the weaker ones, so an editor is answered with all four and a reader with
	///  <code>view</code> alone. It travels with every folder answer, so that the app can show what
	///  the caller may do without asking a second time.
	///  </p>
	/// 
	///  <p>
	///  Derived by the server on every read from the grants on this folder and its ancestors, exactly
	///  like {@link AlbumInfo#effectiveDate}, and never stored: the server clears this field before a
	///  sidecar is written, so that a round trip through a client cannot freeze somebody's rights into
	///  <code>index.json</code>. A sidecar that carries it nevertheless is read without complaint and
	///  answered with the derived value.
	///  </p>
	/// 
	///  <p>
	///  A list of messages, not a list of plain strings: the Dart backend of the model generator
	///  mis-types a <code>repeated string</code> field, see {@link UploadCheck#hashes}.
	///  </p>
	List<RightName> rights;

	/// Creates a FolderResource.
	FolderResource({
			this.path = "", 
			this.rights = const [], 
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
			case "rights": {
				json.expectArray();
				rights = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = RightName.read(json);
						if (value != null) {
							rights.add(value);
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

		json.addKey("rights");
		json.startArray();
		for (var _element in rights) {
			_element.writeContent(json);
		}
		json.endArray();
	}

	R visitFolderResource<R, A>(FolderResourceVisitor<R, A> v, A arg);

	@override
	R visitResource<R, A>(ResourceVisitor<R, A> v, A arg) => visitFolderResource(v, arg);

}

///  The name of a single right, see {@link FolderResource#rights}.
class RightName extends _JsonObject {
	///  One of <code>view</code>, <code>download</code>, <code>contribute</code>, <code>edit</code>.
	String name;

	/// Creates a RightName.
	RightName({
			this.name = "", 
	});

	/// Parses a RightName from a string source.
	static RightName? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a RightName instance from the given reader.
	static RightName read(JsonReader json) {
		RightName result = RightName();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "RightName";

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
			super.rights, 
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

	///  The camera that took this image, see issue #78.
	/// 
	///  <p>
	///  A short label built from the EXIF <code>Make</code> and <code>Model</code> of the original:
	///  both trimmed, joined with a single blank, and the make left out when the model already
	///  starts with it (<code>Canon</code> and <code>Canon EOS 5D</code> make
	///  <code>Canon EOS 5D</code>, not <code>Canon Canon EOS 5D</code>). Phones say the same way
	///  what they are (<code>SAMSUNG SM-G991B</code>). The empty string when the file says neither,
	///  which is the case for every video whose container carries no make and model.
	///  </p>
	/// 
	///  <p>
	///  A label, not an identifier: it is only ever compared for equality, to select every image of
	///  one camera, and an empty label never matches another empty one.
	///  </p>
	/// 
	///  <p>
	///  Read when the image is analysed and <em>stored</em> in the sidecar, exactly like
	///  {@link #date}: a part a sidecar already lists is never analysed again, so an album written
	///  before this field existed keeps its parts without one until they are analysed afresh.
	///  </p>
	String camera;

	///  The {@link ImageGroup}, this {@link ImagePart} is part of, or <code>null</code>, if this {@link ImagePart} is not part of a group.
	ImageGroup? group;

	///  Who uploaded this image, see issue #53.
	/// 
	///  <p>
	///  The subject of the caller that stored the file: <code>user:&lt;name&gt;</code>,
	///  <code>token:&lt;id&gt;</code> for a contribution made through a share link, or
	///  <code>anonymous</code> on a server running without authentication. The empty string for a
	///  photo that never came through an upload — one that was in the folder before this build, or
	///  that was copied in with a file manager.
	///  </p>
	/// 
	///  <p>
	///  Recorded once, at the upload, in the hash sidecar beside the photos, and carried along when
	///  the photo is moved to another folder. An upload of contents the folder already holds keeps
	///  the first contributor: whoever brought the photo here is who brought it here.
	///  </p>
	/// 
	///  <p>
	///  Derived by the server on every read, exactly like {@link AlbumInfo#effectiveDate}, and never
	///  stored: the server clears this field before an <code>index.json</code> is written, so that a
	///  round trip through a client can neither freeze an attribution into the album nor lose one.
	///  </p>
	String contributor;

	///  What to show as the contributor of this image, see issue #53 and {@link #contributor}.
	/// 
	///  <p>
	///  The name of the user, or the label of the share link a guest contributed through, as it
	///  stood at the moment of the upload; the empty string when nothing is known. The label is
	///  copied rather than looked up, so a link that was renamed or withdrawn still says who
	///  contributed.
	///  </p>
	/// 
	///  <p>
	///  Derived on every read and never stored, exactly like {@link #contributor}.
	///  </p>
	String contributorLabel;

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
			this.camera = "", 
			this.group, 
			this.contributor = "", 
			this.contributorLabel = "", 
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
			case "camera": {
				camera = json.expectString();
				break;
			}
			case "contributor": {
				contributor = json.expectString();
				break;
			}
			case "contributorLabel": {
				contributorLabel = json.expectString();
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

		json.addKey("camera");
		json.addString(camera);

		json.addKey("contributor");
		json.addString(contributor);

		json.addKey("contributorLabel");
		json.addString(contributorLabel);
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
			super.rights, 
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

	///  The album this entry is a link to, in its owner's coordinates, see issue #50.
	/// 
	///  <p>
	///  Empty for an ordinary folder on disk, and <code>~&lt;owner&gt;/&lt;path&gt;</code> for a link:
	///  a shared album somebody granted the caller a right on, showing in the caller's own tree under
	///  the {@link #name} the caller gave it. Everything else this tile carries (its {@link #title},
		///  its {@link #subTitle}, its {@link #indexPicture} and its {@link #effectiveDate}) is read from
	///  the target, so a link looks like what it points at.
	///  </p>
	/// 
	///  <p>
	///  The link is what the app marks the tile as shared with and what it names the owner from; it
	///  is never a path the app has to follow. Navigating into a link is ordinary navigation:
	///  <code>&lt;listing&gt;/&lt;name&gt;/</code> resolves through the link on the server, so the URL
	///  the app shows stays the viewer's own path. This is the canonical form a share link and a
	///  copied URL use, see issue #49.
	///  </p>
	/// 
	///  <p>
	///  Derived on every read like {@link FolderResource#rights}, and never stored in a sidecar: a
	///  link lives in the folder's <code>.links.json</code>, not in its <code>index.json</code>.
	///  </p>
	String link;

	/// Creates a FolderInfo.
	FolderInfo({
			this.name = "", 
			this.title = "", 
			this.subTitle = "", 
			this.effectiveDate = 0, 
			this.indexPicture, 
			this.link = "", 
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
			case "link": {
				link = json.expectString();
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

		json.addKey("link");
		json.addString(link);
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
	///  The pairing secret the server was started with; retired by issue #89.
	/// 
	///  <p>
	///  There is no pairing secret any more: the server issues an ordinary single-use
	///  {@link #deviceCode} for the administrator of a space that has no signed-in device yet and
	///  prints it at start-up. The field is read for one release and a request carrying it — and no
	///  {@link #deviceCode} — is answered <code>410 Gone</code> with an {@link ErrorInfo} naming the
	///  code, so that an app that was not updated says something useful instead of failing silently.
	///  </p>
	String secret;

	///  The name the device announces itself with.
	String deviceName;

	///  The name of the user signing in, empty where the code already says who.
	/// 
	///  <p>
	///  A {@link #deviceCode} names its own user, so the name is not a choice but a check: a name
	///  that is not the code's user is refused. The one exception is a code for a user who has
	///  <em>no name yet</em> — the seat code the server prints for the administrator of a fresh
	///  space (issue #89), and the code of an invitation. There the name is what the user will be
	///  known by in the space, it must be free, and a pairing without it is refused
	///  <code>400</code> so that the app can ask for one.
	///  </p>
	/// 
	///  <p>
	///  With an {@link #invitation} it is the name of the user to create, which must be free and must
	///  pass the server's name rule; it is not optional there.
	///  </p>
	String userName;

	///  The token of an {@link Invitation}, the alternative to the {@link #secret} (issue #52).
	/// 
	///  <p>
	///  Accepting an invitation is pairing: a live, unused invitation together with a free
	///  {@link #userName} creates the user with the invitation's role, issues this device's token and
	///  marks the invitation used. Empty in every other request; a request carrying both is read as
	///  an invitation.
	///  </p>
	String invitation;

	///  The code shown on a device that is already signed in, see {@link DeviceCodeCreated} (issue #65).
	/// 
	///  <p>
	///  Adding a further device of one's own: the code is typed on the new device and pairs it as the
	///  <em>same user</em> as the device that showed it, which is exactly what an
	///  {@link #invitation} must never do. It is no link and no bearer — it travels in this one
	///  request and nowhere else — it lives ten minutes and it works once. Spelled with or without
	///  the dash the other device shows, in any case.
	///  </p>
	/// 
	///  <p>
	///  Since issue #89 this is the one way a device is signed in: the seat code the server prints
	///  for the administrator of a space that has no device yet, a code from a device of one's own,
	///  and the recovery code an administrator makes for somebody who lost theirs are all the same
	///  single-use secret with the same lifetime, told apart only by who issued them.
	///  </p>
	/// 
	///  <p>
	///  Empty in every other request. A request carrying an {@link #invitation} as well is read as an
	///  invitation; one carrying a {@link #secret} as well is read as a device code, and a
	///  {@link #userName} naming somebody other than the code's user is refused.
	///  </p>
	String deviceCode;

	/// Creates a PairRequest.
	PairRequest({
			this.secret = "", 
			this.deviceName = "", 
			this.userName = "", 
			this.invitation = "", 
			this.deviceCode = "", 
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
			case "invitation": {
				invitation = json.expectString();
				break;
			}
			case "deviceCode": {
				deviceCode = json.expectString();
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

		json.addKey("invitation");
		json.addString(invitation);

		json.addKey("deviceCode");
		json.addString(deviceCode);
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

	///  The space the caller is in: the folder below the server's base folder their requests are
	///  resolved against, empty for the base folder itself.
	/// 
	///  <p>
	///  On a multi-space server (issue #82) this is the space of the address the request was sent to
	///  — the first segment of <code>&lt;context&gt;/&lt;space&gt;/data/...</code> — and every user,
	///  device and album the answer speaks of belongs to it. On a single-space server it is empty,
	///  as it was for a library that was never migrated.
	///  </p>
	String space;

	///  Which privacy levels the caller may see: <code>public</code>, <code>nonPrivate</code> or
	///  <code>all</code>; empty for an anonymous caller (issue #82).
	/// 
	///  <p>
	///  One of the two axes of the permission model of Phase 6, stored with the user and enforced by
	///  issue #83. It is compared against an image's privacy level, which does not change.
	///  </p>
	String clearance;

	///  Whether the caller may create share links, see issue #82; false for an anonymous caller.
	bool mayShare;

	///  The share link this caller opened, <code>null</code> for everybody else (issue #51).
	/// 
	///  <p>
	///  Its presence is what tells the app that it is a session inside one shared subtree: the
	///  link's target is the root of the tree, there is no edit mode and no settings prompt, and
	///  {@link #writeAllowed} says whether the link allows contributions.
	///  </p>
	ShareInfo? share;

	///  The invitation this caller presented, <code>null</code> for everybody else (issue #52).
	/// 
	///  <p>
	///  An invitation token is no login: the caller is anonymous on every other endpoint, and this
	///  is the one place the server says what the invitation offers, so that the app can name the
	///  inviter and the role before it asks for a user name. An invitation that expired, was used or
	///  was withdrawn is answered <code>410 Gone</code> here instead.
	///  </p>
	InvitationInfo? invitation;

	/// Creates a AuthInfo.
	AuthInfo({
			this.mode = "", 
			this.deviceName = "", 
			this.writeAllowed = false, 
			this.userName = "", 
			this.role = "", 
			this.space = "", 
			this.clearance = "", 
			this.mayShare = false, 
			this.share, 
			this.invitation, 
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
			case "clearance": {
				clearance = json.expectString();
				break;
			}
			case "mayShare": {
				mayShare = json.expectBool();
				break;
			}
			case "share": {
				share = json.tryNull() ? null : ShareInfo.read(json);
				break;
			}
			case "invitation": {
				invitation = json.tryNull() ? null : InvitationInfo.read(json);
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

		json.addKey("clearance");
		json.addString(clearance);

		json.addKey("mayShare");
		json.addBool(mayShare);

		var _share = share;
		if (_share != null) {
			json.addKey("share");
			_share.writeContent(json);
		}

		var _invitation = invitation;
		if (_invitation != null) {
			json.addKey("invitation");
			_invitation.writeContent(json);
		}
	}

}

///  The share link the caller opened, see {@link AuthInfo#share} and issue #51.
/// 
///  <p>
///  What the app needs to confine itself and to name what it shows; the token itself is never
///  answered, and neither are the link's privacy and rating limits — those are applied on the
///  server, on the way out.
///  </p>
class ShareInfo extends _JsonObject {
	///  The label the link was created with, empty if it was created without one.
	String label;

	///  When the link expires, an ISO-8601 instant; empty if it never does.
	String expires;

	///  What the link allows: <code>view</code>, <code>download</code>, <code>contribute</code>.
	List<RightName> rights;

	///  The canonical <code>~&lt;owner&gt;/&lt;path&gt;</code> of the link's target, so the app can name it.
	String path;

	/// Creates a ShareInfo.
	ShareInfo({
			this.label = "", 
			this.expires = "", 
			this.rights = const [], 
			this.path = "", 
	});

	/// Parses a ShareInfo from a string source.
	static ShareInfo? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a ShareInfo instance from the given reader.
	static ShareInfo read(JsonReader json) {
		ShareInfo result = ShareInfo();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "ShareInfo";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "label": {
				label = json.expectString();
				break;
			}
			case "expires": {
				expires = json.expectString();
				break;
			}
			case "rights": {
				json.expectArray();
				rights = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = RightName.read(json);
						if (value != null) {
							rights.add(value);
						}
					}
				}
				break;
			}
			case "path": {
				path = json.expectString();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("label");
		json.addString(label);

		json.addKey("expires");
		json.addString(expires);

		json.addKey("rights");
		json.startArray();
		for (var _element in rights) {
			_element.writeContent(json);
		}
		json.endArray();

		json.addKey("path");
		json.addString(path);
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

///  Answer to <code>&lt;folder&gt;/?action=refresh-cache</code>, see issue #98.
/// 
///  <p>
///  An administrator throws the generated files of one folder away — the thumbnails and the video
///  renditions the server made itself — and the server makes them anew the next time they are asked
///  for. Nothing else in the folder is touched, so the number below is the whole of what happened.
///  </p>
class CacheRefreshed extends _JsonObject {
	///  How many generated files were deleted; zero when the folder had no cache at all.
	/// 
	///  <p>
	///  Worth showing: it is the only evidence the caller gets that the broken thumbnail they were
	///  looking at is really gone.
	///  </p>
	int removed;

	/// Creates a CacheRefreshed.
	CacheRefreshed({
			this.removed = 0, 
	});

	/// Parses a CacheRefreshed from a string source.
	static CacheRefreshed? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a CacheRefreshed instance from the given reader.
	static CacheRefreshed read(JsonReader json) {
		CacheRefreshed result = CacheRefreshed();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "CacheRefreshed";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "removed": {
				removed = json.expectInt();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("removed");
		json.addNumber(removed);
	}

}

///  A sharing grant: who may do what on which subtree, see issue #49.
/// 
///  <p>
///  The one sharing mechanism of this server. A grant is identified by its {@link #owner}, its
///  {@link #path} and its {@link #subject}; granting again replaces the {@link #rights}, revoking
///  removes it. Grants are inherited downwards: a grant on a folder covers everything below it.
///  </p>
/// 
///  <p>
///  Sent to <code>&lt;folder&gt;/?action=grant</code> and <code>&lt;folder&gt;/?action=revoke</code>,
///  where the {@link #owner} and the {@link #path} are taken from the URL and whatever the body says
///  about them is ignored.
///  </p>
class Grant extends _JsonObject {
	///  The name of the user in whose space the granted subtree lies.
	String owner;

	///  The granted folder, as a path relative to the owner's space; the empty string is the whole
	///  space.
	String path;

	///  Who is granted: <code>user:&lt;name&gt;</code>, <code>group:&lt;name&gt;</code>,
	///  <code>anonymous</code> (everybody, signed in or not), or <code>token:&lt;id&gt;</code> (a
	///  share link, issue #51).
	String subject;

	///  What is granted: <code>view</code>, <code>download</code>, <code>contribute</code>,
	///  <code>edit</code>.
	/// 
	///  <p>
	///  A list of messages, not a list of plain strings, see {@link FolderResource#rights}.
	///  </p>
	List<RightName> rights;

	///  When the grant was made, an ISO-8601 instant; answered by the server, ignored in a request.
	String created;

	/// Creates a Grant.
	Grant({
			this.owner = "", 
			this.path = "", 
			this.subject = "", 
			this.rights = const [], 
			this.created = "", 
	});

	/// Parses a Grant from a string source.
	static Grant? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a Grant instance from the given reader.
	static Grant read(JsonReader json) {
		Grant result = Grant();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "Grant";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "owner": {
				owner = json.expectString();
				break;
			}
			case "path": {
				path = json.expectString();
				break;
			}
			case "subject": {
				subject = json.expectString();
				break;
			}
			case "rights": {
				json.expectArray();
				rights = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = RightName.read(json);
						if (value != null) {
							rights.add(value);
						}
					}
				}
				break;
			}
			case "created": {
				created = json.expectString();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("owner");
		json.addString(owner);

		json.addKey("path");
		json.addString(path);

		json.addKey("subject");
		json.addString(subject);

		json.addKey("rights");
		json.startArray();
		for (var _element in rights) {
			_element.writeContent(json);
		}
		json.endArray();

		json.addKey("created");
		json.addString(created);
	}

}

///  The grants on a folder and its ancestors, answered by
///  <code>&lt;folder&gt;/?type=grants</code>.
/// 
///  <p>
///  Only the owner of the space and the admin may ask: a grant says who else is let in, which is
///  nobody else's business.
///  </p>
class GrantList extends _JsonObject {
	///  The grants covering the addressed folder, the nearest one first.
	List<Grant> grants;

	/// Creates a GrantList.
	GrantList({
			this.grants = const [], 
	});

	/// Parses a GrantList from a string source.
	static GrantList? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a GrantList instance from the given reader.
	static GrantList read(JsonReader json) {
		GrantList result = GrantList();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "GrantList";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "grants": {
				json.expectArray();
				grants = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = Grant.read(json);
						if (value != null) {
							grants.add(value);
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

		json.addKey("grants");
		json.startArray();
		for (var _element in grants) {
			_element.writeContent(json);
		}
		json.endArray();
	}

}

///  A named list of users, usable wherever a single user can be named, see issue #49.
/// 
///  <p>
///  Created by any member and owned by its creator; sent to <code>&lt;data&gt;/?action=group</code>
///  to create it or to replace its members, and to <code>&lt;data&gt;/?action=ungroup</code> to
///  remove it.
///  </p>
class Group extends _JsonObject {
	///  The name of the group, following the rules a user name follows.
	String name;

	///  The name of the user who owns the group; answered by the server, ignored in a request.
	String owner;

	///  The names of the users in the group.
	/// 
	///  <p>
	///  A list of messages, not a list of plain strings, see {@link FolderResource#rights}.
	///  </p>
	List<MemberName> members;

	///  When the group was created, an ISO-8601 instant; answered by the server, ignored in a request.
	String created;

	/// Creates a Group.
	Group({
			this.name = "", 
			this.owner = "", 
			this.members = const [], 
			this.created = "", 
	});

	/// Parses a Group from a string source.
	static Group? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a Group instance from the given reader.
	static Group read(JsonReader json) {
		Group result = Group();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "Group";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "name": {
				name = json.expectString();
				break;
			}
			case "owner": {
				owner = json.expectString();
				break;
			}
			case "members": {
				json.expectArray();
				members = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = MemberName.read(json);
						if (value != null) {
							members.add(value);
						}
					}
				}
				break;
			}
			case "created": {
				created = json.expectString();
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

		json.addKey("owner");
		json.addString(owner);

		json.addKey("members");
		json.startArray();
		for (var _element in members) {
			_element.writeContent(json);
		}
		json.endArray();

		json.addKey("created");
		json.addString(created);
	}

}

///  The name of a single group member, see {@link Group#members}.
class MemberName extends _JsonObject {
	///  The name of the user.
	String name;

	/// Creates a MemberName.
	MemberName({
			this.name = "", 
	});

	/// Parses a MemberName from a string source.
	static MemberName? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a MemberName instance from the given reader.
	static MemberName read(JsonReader json) {
		MemberName result = MemberName();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "MemberName";

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

///  What a user of this space may do, sent to <code>&lt;data&gt;/?action=set-permission</code>
///  (issue #83).
/// 
///  <p>
///  The administrator's decision, and the only way a permission ever changes. The last administrator
///  of a space cannot be demoted.
///  </p>
class UserPermission extends _JsonObject {
	///  The name of the user whose permission is set.
	String name;

	///  The role to give them: <code>admin</code>, <code>edit</code>, <code>contribute</code> or <code>view</code>.
	String role;

	///  The clearance to give them: <code>public</code>, <code>nonPrivate</code> or <code>all</code>.
	String clearance;

	///  Whether they may create share links.
	bool mayShare;

	/// Creates a UserPermission.
	UserPermission({
			this.name = "", 
			this.role = "", 
			this.clearance = "", 
			this.mayShare = false, 
	});

	/// Parses a UserPermission from a string source.
	static UserPermission? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a UserPermission instance from the given reader.
	static UserPermission read(JsonReader json) {
		UserPermission result = UserPermission();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "UserPermission";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "name": {
				name = json.expectString();
				break;
			}
			case "role": {
				role = json.expectString();
				break;
			}
			case "clearance": {
				clearance = json.expectString();
				break;
			}
			case "mayShare": {
				mayShare = json.expectBool();
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

		json.addKey("role");
		json.addString(role);

		json.addKey("clearance");
		json.addString(clearance);

		json.addKey("mayShare");
		json.addBool(mayShare);
	}

}

///  The groups the caller owns and the groups they are in, answered by
///  <code>&lt;data&gt;/?type=groups</code>.
class GroupList extends _JsonObject {
	///  The groups, those the caller owns first.
	List<Group> groups;

	/// Creates a GroupList.
	GroupList({
			this.groups = const [], 
	});

	/// Parses a GroupList from a string source.
	static GroupList? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a GroupList instance from the given reader.
	static GroupList read(JsonReader json) {
		GroupList result = GroupList();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "GroupList";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "groups": {
				json.expectArray();
				groups = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = Group.read(json);
						if (value != null) {
							groups.add(value);
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

		json.addKey("groups");
		json.startArray();
		for (var _element in groups) {
			_element.writeContent(json);
		}
		json.endArray();
	}

}

///  A user of this server as another user may see them, see {@link UserList}.
/// 
///  <p>
///  The name and the role, and since issue #55 the three things a management screen shows beside
///  them: where the user's library lies, since when they are here, and how many devices they signed
///  in on. Never a token and never a device of theirs — what a device is called and when it was
///  paired is answered to its own owner only, see {@link DeviceList}.
///  </p>
class UserEntry extends _JsonObject {
	///  The user's name, which is what a <code>user:&lt;name&gt;</code> subject names.
	String name;

	///  The user's role: <code>admin</code>, <code>member</code> or <code>guest</code>.
	String role;

	///  The folder below the server's base folder this user's requests are resolved against (issue #55).
	/// 
	///  <p>
	///  Empty for a guest, who has no library of their own, and for the owner of a library that was
	///  never migrated, whose space is the base folder itself.
	///  </p>
	String space;

	///  When the user was created, an ISO-8601 instant; empty if the server never recorded one (issue #55).
	String created;

	///  How many devices the user is signed in on, answered by the server (issue #55).
	int devices;

	///  Which privacy levels this user may see: <code>public</code>, <code>nonPrivate</code> or
	///  <code>all</code> (issue #82).
	String clearance;

	///  Whether this user may create share links (issue #82).
	bool mayShare;

	/// Creates a UserEntry.
	UserEntry({
			this.name = "", 
			this.role = "", 
			this.space = "", 
			this.created = "", 
			this.devices = 0, 
			this.clearance = "", 
			this.mayShare = false, 
	});

	/// Parses a UserEntry from a string source.
	static UserEntry? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a UserEntry instance from the given reader.
	static UserEntry read(JsonReader json) {
		UserEntry result = UserEntry();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "UserEntry";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "name": {
				name = json.expectString();
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
			case "created": {
				created = json.expectString();
				break;
			}
			case "devices": {
				devices = json.expectInt();
				break;
			}
			case "clearance": {
				clearance = json.expectString();
				break;
			}
			case "mayShare": {
				mayShare = json.expectBool();
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

		json.addKey("role");
		json.addString(role);

		json.addKey("space");
		json.addString(space);

		json.addKey("created");
		json.addString(created);

		json.addKey("devices");
		json.addNumber(devices);

		json.addKey("clearance");
		json.addString(clearance);

		json.addKey("mayShare");
		json.addBool(mayShare);
	}

}

///  The users of this server, answered by <code>&lt;data&gt;/?type=users</code>.
/// 
///  <p>
///  Needed to share: a member picks whom to grant something to. Members and the admin may ask,
///  guests and anonymous callers may not.
///  </p>
class UserList extends _JsonObject {
	///  The users, in the order they were created.
	List<UserEntry> users;

	///  How many share links were withdrawn along with a removed user (issue #84).
	/// 
	///  <p>
	///  Answered by <code>&lt;data&gt;/?action=remove-user</code> only, and <code>0</code>
	///  everywhere else: removing somebody takes back what they handed out, and the answer says how
	///  much that was, so that nobody has to guess.
	///  </p>
	int revokedLinks;

	/// Creates a UserList.
	UserList({
			this.users = const [], 
			this.revokedLinks = 0, 
	});

	/// Parses a UserList from a string source.
	static UserList? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a UserList instance from the given reader.
	static UserList read(JsonReader json) {
		UserList result = UserList();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "UserList";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "users": {
				json.expectArray();
				users = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = UserEntry.read(json);
						if (value != null) {
							users.add(value);
						}
					}
				}
				break;
			}
			case "revokedLinks": {
				revokedLinks = json.expectInt();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("users");
		json.startArray();
		for (var _element in users) {
			_element.writeContent(json);
		}
		json.endArray();

		json.addKey("revokedLinks");
		json.addNumber(revokedLinks);
	}

}

///  A device somebody paired with this server, see issue #55.
/// 
///  <p>
///  Answered by <code>&lt;data&gt;/?type=devices</code> and sent to
///  <code>&lt;data&gt;/?action=unpair</code>, which names the device to sign out by its {@link #id}.
///  A caller only ever sees and unpairs devices of their own: the administrator manages the users of
///  this server, not other people's phones.
///  </p>
/// 
///  <p>
///  The token is never part of this message, and neither is its hash: a device is named by its id,
///  which is a name and not a secret.
///  </p>
class DeviceEntry extends _JsonObject {
	///  The short id of the device, assigned when it was paired; what names it in a request.
	String id;

	///  The name the device announced itself with when it was paired.
	String name;

	///  When the device was paired, an ISO-8601 instant; answered by the server, ignored in a request.
	String created;

	///  Whether this is the device the request came from; answered by the server, ignored in a request.
	/// 
	///  <p>
	///  True on exactly one entry of a listing, so that the app can say "this device" and warn before
	///  signing it out — which is allowed, and is how a device signs itself out for good.
	///  </p>
	bool current;

	/// Creates a DeviceEntry.
	DeviceEntry({
			this.id = "", 
			this.name = "", 
			this.created = "", 
			this.current = false, 
	});

	/// Parses a DeviceEntry from a string source.
	static DeviceEntry? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a DeviceEntry instance from the given reader.
	static DeviceEntry read(JsonReader json) {
		DeviceEntry result = DeviceEntry();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "DeviceEntry";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "id": {
				id = json.expectString();
				break;
			}
			case "name": {
				name = json.expectString();
				break;
			}
			case "created": {
				created = json.expectString();
				break;
			}
			case "current": {
				current = json.expectBool();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("id");
		json.addString(id);

		json.addKey("name");
		json.addString(name);

		json.addKey("created");
		json.addString(created);

		json.addKey("current");
		json.addBool(current);
	}

}

///  The caller's own devices, answered by <code>&lt;data&gt;/?type=devices</code> and by
///  <code>&lt;data&gt;/?action=unpair</code>, see issue #55.
class DeviceList extends _JsonObject {
	///  The devices, in the order they were paired.
	List<DeviceEntry> devices;

	/// Creates a DeviceList.
	DeviceList({
			this.devices = const [], 
	});

	/// Parses a DeviceList from a string source.
	static DeviceList? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a DeviceList instance from the given reader.
	static DeviceList read(JsonReader json) {
		DeviceList result = DeviceList();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "DeviceList";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "devices": {
				json.expectArray();
				devices = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = DeviceEntry.read(json);
						if (value != null) {
							devices.add(value);
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

		json.addKey("devices");
		json.startArray();
		for (var _element in devices) {
			_element.writeContent(json);
		}
		json.endArray();
	}

}

///  What a request to <code>&lt;data&gt;/?action=device-code</code> may carry, see issue #89.
/// 
///  <p>
///  Nothing, and that is the ordinary case: a code for a further device of one's own needs no body,
///  because the token already says who is asking. An administrator may name somebody else instead —
///  the <em>recovery code</em> for a person who cleared their browser or reinstalled the app and
///  lost every device they had. It is the same code with the same ten minutes and the same single
///  use; only its target differs, and it still dies with the device that issued it.
///  </p>
class DeviceCodeRequest extends _JsonObject {
	///  The user the code signs in, empty for the caller themselves.
	/// 
	///  <p>
	///  Only an administrator of the space may name somebody other than themselves; a user this
	///  space does not know is answered <code>404</code>.
	///  </p>
	String userName;

	/// Creates a DeviceCodeRequest.
	DeviceCodeRequest({
			this.userName = "", 
	});

	/// Parses a DeviceCodeRequest from a string source.
	static DeviceCodeRequest? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a DeviceCodeRequest instance from the given reader.
	static DeviceCodeRequest read(JsonReader json) {
		DeviceCodeRequest result = DeviceCodeRequest();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "DeviceCodeRequest";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
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

		json.addKey("userName");
		json.addString(userName);
	}

}

///  The answer to <code>&lt;data&gt;/?action=device-code</code>: a code to type on a further device
///  of one's own, see issue #65.
/// 
///  <p>
///  A device credential, never an invitation: whoever types this code is signed in as the user who
///  asked for it, so it is deliberately nothing that can be forwarded — no link, no URL and no
///  bearer token, but eight characters shown on the screen of a device that is already signed in.
///  It lives ten minutes and it works once, and the device it pairs appears in
///  {@link DeviceList} at once, where it can be signed out again.
///  </p>
/// 
///  <p>
///  The one and only time the {@link #code} is answered; the server keeps its hash and can never
///  show it again. A code that was not typed in time is simply asked for anew.
///  </p>
class DeviceCodeCreated extends _JsonObject {
	///  The code to type on the other device, grouped as <code>XXXX-XXXX</code>; the dash is decoration.
	String code;

	///  When the code stops working, an ISO-8601 instant; ten minutes after it was issued.
	String expires;

	/// Creates a DeviceCodeCreated.
	DeviceCodeCreated({
			this.code = "", 
			this.expires = "", 
	});

	/// Parses a DeviceCodeCreated from a string source.
	static DeviceCodeCreated? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a DeviceCodeCreated instance from the given reader.
	static DeviceCodeCreated read(JsonReader json) {
		DeviceCodeCreated result = DeviceCodeCreated();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "DeviceCodeCreated";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "code": {
				code = json.expectString();
				break;
			}
			case "expires": {
				expires = json.expectString();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("code");
		json.addString(code);

		json.addKey("expires");
		json.addString(expires);
	}

}

///  The renaming of a {@link Group}, sent to <code>&lt;data&gt;/?action=regroup</code>, see issue #55.
/// 
///  <p>
///  A rename is its own request because it is more than a change of the group's name: every grant
///  made out to the group is rewritten in the same step, so that nothing that was shared with the
///  group stops working because it was given a better name. The answer is the renamed group, as
///  <code>?action=group</code> answers it.
///  </p>
class GroupRename extends _JsonObject {
	///  The name of the group to rename.
	String name;

	///  The name it should have, following the rules a user name follows.
	String newName;

	/// Creates a GroupRename.
	GroupRename({
			this.name = "", 
			this.newName = "", 
	});

	/// Parses a GroupRename from a string source.
	static GroupRename? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a GroupRename instance from the given reader.
	static GroupRename read(JsonReader json) {
		GroupRename result = GroupRename();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "GroupRename";

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
	}

}

///  A share link: a scoped token opening one subtree to whoever holds it, see issue #51.
/// 
///  <p>
///  Sent to <code>&lt;folder&gt;/?action=share</code> to create one, where the target is taken from
///  the URL and whatever the body says about {@link #path} is ignored; answered by
///  <code>&lt;folder&gt;/?type=shares</code> and by <code>&lt;folder&gt;/?action=unshare</code>,
///  which names the link to withdraw by its {@link #id}.
///  </p>
/// 
///  <p>
///  The token is never part of this message: it is answered exactly once, in a
///  {@link ShareLinkCreated}, and the server stores nothing but its hash.
///  </p>
class ShareLink extends _JsonObject {
	///  The short id of the link; answered by the server, and what names it in a request.
	String id;

	///  The label the link was created with, shown wherever the link is listed.
	String label;

	///  When the link expires, an ISO-8601 instant; empty if it never does.
	String expires;

	///  The highest {@link ImagePart#privacy} the link shows: <code>0</code>..<code>2</code>.
	int maxPrivacy;

	///  The lowest {@link ImagePart#rating} the link shows: <code>-2</code>..<code>2</code>.
	int minRating;

	///  What the link allows: <code>view</code>, <code>download</code>, <code>contribute</code>.
	/// 
	///  <p>
	///  Never <code>edit</code>: a link is not an account. An empty list means <code>view</code>.
	///  </p>
	List<RightName> rights;

	///  The path of the link's target inside the space; answered by the server.
	String path;

	///  The name of the user who created the link; answered by the server (issue #84).
	/// 
	///  <p>
	///  A link belongs to whoever handed it out: they see it in the listing of the folder and may
	///  withdraw it, and so may an administrator of the space. Empty for a link made before this
	///  field existed, which only an administrator sees.
	///  </p>
	String createdBy;

	///  When the link was created, an ISO-8601 instant; answered by the server.
	String created;

	///  When the link was withdrawn, an ISO-8601 instant; empty while the link is live.
	String revoked;

	/// Creates a ShareLink.
	ShareLink({
			this.id = "", 
			this.label = "", 
			this.expires = "", 
			this.maxPrivacy = 0, 
			this.minRating = 0, 
			this.rights = const [], 
			this.path = "", 
			this.createdBy = "", 
			this.created = "", 
			this.revoked = "", 
	});

	/// Parses a ShareLink from a string source.
	static ShareLink? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a ShareLink instance from the given reader.
	static ShareLink read(JsonReader json) {
		ShareLink result = ShareLink();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "ShareLink";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "id": {
				id = json.expectString();
				break;
			}
			case "label": {
				label = json.expectString();
				break;
			}
			case "expires": {
				expires = json.expectString();
				break;
			}
			case "maxPrivacy": {
				maxPrivacy = json.expectInt();
				break;
			}
			case "minRating": {
				minRating = json.expectInt();
				break;
			}
			case "rights": {
				json.expectArray();
				rights = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = RightName.read(json);
						if (value != null) {
							rights.add(value);
						}
					}
				}
				break;
			}
			case "path": {
				path = json.expectString();
				break;
			}
			case "createdBy": {
				createdBy = json.expectString();
				break;
			}
			case "created": {
				created = json.expectString();
				break;
			}
			case "revoked": {
				revoked = json.expectString();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("id");
		json.addString(id);

		json.addKey("label");
		json.addString(label);

		json.addKey("expires");
		json.addString(expires);

		json.addKey("maxPrivacy");
		json.addNumber(maxPrivacy);

		json.addKey("minRating");
		json.addNumber(minRating);

		json.addKey("rights");
		json.startArray();
		for (var _element in rights) {
			_element.writeContent(json);
		}
		json.endArray();

		json.addKey("path");
		json.addString(path);

		json.addKey("createdBy");
		json.addString(createdBy);

		json.addKey("created");
		json.addString(created);

		json.addKey("revoked");
		json.addString(revoked);
	}

}

///  The share links on a folder and its ancestors, answered by
///  <code>&lt;folder&gt;/?type=shares</code>.
/// 
///  <p>
///  Only the owner of the space and the admin may ask, and no answer ever carries a token.
///  </p>
class ShareLinkList extends _JsonObject {
	///  The links covering the addressed folder, the nearest one first.
	List<ShareLink> links;

	/// Creates a ShareLinkList.
	ShareLinkList({
			this.links = const [], 
	});

	/// Parses a ShareLinkList from a string source.
	static ShareLinkList? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a ShareLinkList instance from the given reader.
	static ShareLinkList read(JsonReader json) {
		ShareLinkList result = ShareLinkList();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "ShareLinkList";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "links": {
				json.expectArray();
				links = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = ShareLink.read(json);
						if (value != null) {
							links.add(value);
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

		json.addKey("links");
		json.startArray();
		for (var _element in links) {
			_element.writeContent(json);
		}
		json.endArray();
	}

}

///  The answer to <code>&lt;folder&gt;/?action=share</code>: the new link, with its token.
/// 
///  <p>
///  The one and only time the {@link #token} is answered; the server keeps its hash and can never
///  show it again. A lost link is withdrawn and created anew.
///  </p>
class ShareLinkCreated extends _JsonObject {
	///  The link that was created, as {@link ShareLinkList} lists it.
	ShareLink? link;

	///  The token to open the link with, answered exactly once and never stored.
	String token;

	///  The link's path on this server: <code>&lt;context&gt;/s/&lt;token&gt;/</code>.
	String url;

	/// Creates a ShareLinkCreated.
	ShareLinkCreated({
			this.link, 
			this.token = "", 
			this.url = "", 
	});

	/// Parses a ShareLinkCreated from a string source.
	static ShareLinkCreated? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a ShareLinkCreated instance from the given reader.
	static ShareLinkCreated read(JsonReader json) {
		ShareLinkCreated result = ShareLinkCreated();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "ShareLinkCreated";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "link": {
				link = json.tryNull() ? null : ShareLink.read(json);
				break;
			}
			case "token": {
				token = json.expectString();
				break;
			}
			case "url": {
				url = json.expectString();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		var _link = link;
		if (_link != null) {
			json.addKey("link");
			_link.writeContent(json);
		}

		json.addKey("token");
		json.addString(token);

		json.addKey("url");
		json.addString(url);
	}

}

///  An invitation: a single-use token that creates a user, see issue #52.
/// 
///  <p>
///  Sent to <code>&lt;data&gt;/?action=invite</code> to create one, where only {@link #role},
///  {@link #expires} and {@link #note} are read and everything else is answered by the server;
///  answered by <code>&lt;data&gt;/?type=invitations</code> and used to name the invitation to
///  withdraw at <code>&lt;data&gt;/?action=uninvite</code>, which reads nothing but the {@link #id}.
///  </p>
/// 
///  <p>
///  The token is never part of this message: it is answered exactly once, in an
///  {@link InvitationCreated}, and the server stores nothing but its hash.
///  </p>
class Invitation extends _JsonObject {
	///  The short id of the invitation; answered by the server, and what names it in a request.
	String id;

	///  The role the accepting user is created with: <code>member</code> or <code>guest</code>.
	/// 
	///  <p>
	///  Never <code>admin</code>: the library has exactly one owner and nobody is invited into that
	///  seat. An empty role is read as <code>member</code>.
	///  </p>
	String role;

	///  Which privacy levels the accepting user may see: <code>public</code>,
	///  <code>nonPrivate</code> or <code>all</code> (issue #82).
	/// 
	///  <p>
	///  Empty means what the role implies. Never above the inviter's own clearance; stored with the
	///  created user and enforced by issue #83.
	///  </p>
	String clearance;

	///  Whether the accepting user may create share links (issue #82).
	bool mayShare;

	///  A note the inviter wrote for themselves, shown wherever the invitation is listed; may be empty.
	String note;

	///  When the invitation expires, an ISO-8601 instant.
	/// 
	///  <p>
	///  Empty in a request means "in seven days"; the answer always carries the instant the server
	///  settled on, so an invitation never lives forever.
	///  </p>
	String expires;

	///  The name of the user who issued the invitation; answered by the server.
	String invitedBy;

	///  When the invitation was issued, an ISO-8601 instant; answered by the server.
	String created;

	///  When the invitation was accepted, an ISO-8601 instant; empty while it is unused.
	String used;

	///  The name of the user the invitation created, empty while it is unused.
	String usedBy;

	///  When the invitation was withdrawn, an ISO-8601 instant; empty while it stands.
	String revoked;

	/// Creates a Invitation.
	Invitation({
			this.id = "", 
			this.role = "", 
			this.clearance = "", 
			this.mayShare = false, 
			this.note = "", 
			this.expires = "", 
			this.invitedBy = "", 
			this.created = "", 
			this.used = "", 
			this.usedBy = "", 
			this.revoked = "", 
	});

	/// Parses a Invitation from a string source.
	static Invitation? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a Invitation instance from the given reader.
	static Invitation read(JsonReader json) {
		Invitation result = Invitation();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "Invitation";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "id": {
				id = json.expectString();
				break;
			}
			case "role": {
				role = json.expectString();
				break;
			}
			case "clearance": {
				clearance = json.expectString();
				break;
			}
			case "mayShare": {
				mayShare = json.expectBool();
				break;
			}
			case "note": {
				note = json.expectString();
				break;
			}
			case "expires": {
				expires = json.expectString();
				break;
			}
			case "invitedBy": {
				invitedBy = json.expectString();
				break;
			}
			case "created": {
				created = json.expectString();
				break;
			}
			case "used": {
				used = json.expectString();
				break;
			}
			case "usedBy": {
				usedBy = json.expectString();
				break;
			}
			case "revoked": {
				revoked = json.expectString();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("id");
		json.addString(id);

		json.addKey("role");
		json.addString(role);

		json.addKey("clearance");
		json.addString(clearance);

		json.addKey("mayShare");
		json.addBool(mayShare);

		json.addKey("note");
		json.addString(note);

		json.addKey("expires");
		json.addString(expires);

		json.addKey("invitedBy");
		json.addString(invitedBy);

		json.addKey("created");
		json.addString(created);

		json.addKey("used");
		json.addString(used);

		json.addKey("usedBy");
		json.addString(usedBy);

		json.addKey("revoked");
		json.addString(revoked);
	}

}

///  The answer to <code>&lt;data&gt;/?action=invite</code>: the new invitation, with its token.
/// 
///  <p>
///  The one and only time the {@link #token} is answered; the server keeps its hash and can never
///  show it again. A lost invitation is withdrawn and issued anew.
///  </p>
class InvitationCreated extends _JsonObject {
	///  The invitation that was issued, as {@link InvitationList} lists it.
	Invitation? invitation;

	///  The token to accept the invitation with, answered exactly once and never stored.
	String token;

	///  The invitation's path on this server: <code>&lt;context&gt;/i/&lt;token&gt;/</code>.
	String url;

	/// Creates a InvitationCreated.
	InvitationCreated({
			this.invitation, 
			this.token = "", 
			this.url = "", 
	});

	/// Parses a InvitationCreated from a string source.
	static InvitationCreated? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a InvitationCreated instance from the given reader.
	static InvitationCreated read(JsonReader json) {
		InvitationCreated result = InvitationCreated();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "InvitationCreated";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "invitation": {
				invitation = json.tryNull() ? null : Invitation.read(json);
				break;
			}
			case "token": {
				token = json.expectString();
				break;
			}
			case "url": {
				url = json.expectString();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		var _invitation = invitation;
		if (_invitation != null) {
			json.addKey("invitation");
			_invitation.writeContent(json);
		}

		json.addKey("token");
		json.addString(token);

		json.addKey("url");
		json.addString(url);
	}

}

///  The invitations of this server, answered by <code>&lt;data&gt;/?type=invitations</code>.
/// 
///  <p>
///  The admin is answered every invitation, a member the ones they issued themselves; a guest and an
///  anonymous caller are answered none at all. No answer ever carries a token.
///  </p>
class InvitationList extends _JsonObject {
	///  The invitations, newest last, in the order they were issued.
	List<Invitation> invitations;

	/// Creates a InvitationList.
	InvitationList({
			this.invitations = const [], 
	});

	/// Parses a InvitationList from a string source.
	static InvitationList? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a InvitationList instance from the given reader.
	static InvitationList read(JsonReader json) {
		InvitationList result = InvitationList();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "InvitationList";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "invitations": {
				json.expectArray();
				invitations = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = Invitation.read(json);
						if (value != null) {
							invitations.add(value);
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

		json.addKey("invitations");
		json.startArray();
		for (var _element in invitations) {
			_element.writeContent(json);
		}
		json.endArray();
	}

}

///  The invitation the caller presented, see {@link AuthInfo#invitation} and issue #52.
/// 
///  <p>
///  What the app needs to say "you were invited by alice as a member" before it asks for a name. An
///  invitation token is no login: it says what would be created if it were accepted, and nothing
///  more.
///  </p>
class InvitationInfo extends _JsonObject {
	///  The role the accepting user would be created with: <code>member</code> or <code>guest</code>.
	String role;

	///  The name of the user who issued the invitation.
	String invitedBy;

	///  The note the inviter wrote, empty if they wrote none.
	String note;

	///  When the invitation expires, an ISO-8601 instant.
	String expires;

	/// Creates a InvitationInfo.
	InvitationInfo({
			this.role = "", 
			this.invitedBy = "", 
			this.note = "", 
			this.expires = "", 
	});

	/// Parses a InvitationInfo from a string source.
	static InvitationInfo? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a InvitationInfo instance from the given reader.
	static InvitationInfo read(JsonReader json) {
		InvitationInfo result = InvitationInfo();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "InvitationInfo";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "role": {
				role = json.expectString();
				break;
			}
			case "invitedBy": {
				invitedBy = json.expectString();
				break;
			}
			case "note": {
				note = json.expectString();
				break;
			}
			case "expires": {
				expires = json.expectString();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("role");
		json.addString(role);

		json.addKey("invitedBy");
		json.addString(invitedBy);

		json.addKey("note");
		json.addString(note);

		json.addKey("expires");
		json.addString(expires);
	}

}

