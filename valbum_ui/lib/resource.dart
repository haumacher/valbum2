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

///  What an {@link AlbumInfo} is: an ordinary album, or an inbox, see issue #131.
/// 
///  <p>
///  An inbox is a <em>kind of album</em> and not a resource of its own: the same folder, the same
///  <code>index.json</code>, the same parts, so that every mechanism an album has — upload, hashes,
///  moving, deleting, thumbnails, attribution — works there unchanged. One stored flag says what it
///  is, and everything an inbox does differently follows from that flag alone.
///  </p>
/// 
///  <p>
///  {@link #ALBUM} is the first constant and therefore what every sidecar written before this field
///  existed reads as: an album, exactly as it always was. Switching an album to an inbox and back is
///  an ordinary properties write and loses nothing — what an inbox derives (its order, its date) is
///  derived on the way out and never stored, so the album is itself again the moment the flag is
///  cleared.
///  </p>
enum AlbumKind {
	///  An ordinary album: the author's order, the author's groups, the author's headings.
	album,
	///  An inbox: photographs waiting to be sorted into albums.
	/// 
	///  <p>
	///  The server answers an inbox {@link AlbumInfo#parts flat and by date} whatever the sidecar
	///  lists, gives it no {@link AlbumInfo#effectiveDate date} of its own, files it nowhere, and
	///  never shows it to anybody but a caller who may {@link RightName edit} it — a contributor
	///  sees their own contributions there and nobody else sees that it exists at all. It cannot be
	///  shared by a link.
	///  </p>
	inbox,
}

/// Writes a value of AlbumKind to a JSON stream.
void writeAlbumKind(JsonSink json, AlbumKind value) {
	switch (value) {
		case AlbumKind.album: json.addString("ALBUM"); break;
		case AlbumKind.inbox: json.addString("INBOX"); break;
		default: throw ("No such literal: " + value.name);
	}
}

/// Reads a value of AlbumKind from a JSON stream.
AlbumKind readAlbumKind(JsonReader json) {
	switch (json.expectString()) {
		case "ALBUM": return AlbumKind.album;
		case "INBOX": return AlbumKind.inbox;
		default: return AlbumKind.album;
	}
}

///  {@link Resource} describing a collection of {@link AlbumPart}s.
class AlbumInfo extends FolderResource {
	///  Whether this is an ordinary album or an inbox, see issue #131.
	/// 
	///  <p>
	///  Stored in <code>index.json</code> like the title: it is a statement the author made about
	///  this folder, not something the server derives. An absent value is {@link AlbumKind#ALBUM},
	///  so every sidecar written before this field existed reads as the album it always was.
	///  </p>
	AlbumKind kind;

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

	///  Whether the server is still looking for faces in this album, see issue #124.
	/// 
	///  <p>
	///  <code>true</code> while the space has the face index switched on
	///  (<code>space.json</code> <code>faces: on</code>) and not every photograph of this album is
	///  indexed yet, so that the application can say &quot;still looking&quot; and come back, exactly
	///  as it comes back for a video rendition that is not ready (issue #74). The
	///  {@link ImagePart#faces} that are already known are answered meanwhile.
	///  </p>
	/// 
	///  <p>
	///  On the album and not on a listing entry, because the album is what the face editor of issue
	///  #126 stands in: a listing shows folders, and a folder tile has nothing to do with a face.
	///  Derived on every read and never stored, exactly like {@link #effectiveDate}, and answered
	///  only to a caller that is answered faces at all.
	///  </p>
	bool facesPending;

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
			this.kind = AlbumKind.album, 
			this.title = "", 
			this.subTitle = "", 
			this.date = 0, 
			this.effectiveDate = 0, 
			this.facesPending = false, 
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
			case "kind": {
				kind = readAlbumKind(json);
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
			case "date": {
				date = json.expectInt();
				break;
			}
			case "effectiveDate": {
				effectiveDate = json.expectInt();
				break;
			}
			case "facesPending": {
				facesPending = json.expectBool();
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

		json.addKey("kind");
		writeAlbumKind(json, kind);

		json.addKey("title");
		json.addString(title);

		json.addKey("subTitle");
		json.addString(subTitle);

		json.addKey("date");
		json.addNumber(date);

		json.addKey("effectiveDate");
		json.addNumber(effectiveDate);

		json.addKey("facesPending");
		json.addBool(facesPending);

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

	///  The level of this heading, see issue #158: <code>1</code> a section, <code>2</code> a
	///  subsection below it.
	/// 
	///  <p>
	///  Stored in the album's sidecar like {@link #text}. A sidecar written before the level existed
	///  carries no value, which reads as <code>0</code> and means <code>1</code>, so every older
	///  heading keeps its look; any value other than <code>2</code> is read as a section. In the edit
	///  mode a heading selects the images below it up to the next heading of the same or a higher
	///  level (a smaller number).
	///  </p>
	int level;

	/// Creates a Heading.
	Heading({
			super.owner, 
			this.text = "", 
			this.level = 0, 
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
			case "level": {
				level = json.expectInt();
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

		json.addKey("level");
		json.addNumber(level);
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

///  Where a photo was taken, see issue #112.
/// 
///  <p>
///  Decimal degrees in WGS 84, positive to the north and to the east, exactly as the EXIF GPS tags
///  of the original say it (<code>GPSLatitude</code>/<code>GPSLongitude</code> with their reference
///  letters, resolved into one signed number each by metadata-extractor).
///  </p>
/// 
///  <p>
///  "No position" is the absent {@link ImagePart#getLocation() location}. A pair of zeroes is no
///  position either (issue #161): a camera with geotagging switched on and no fix yet writes a GPS
///  IFD of zeroes, so <code>0/0</code> says "not filled in" far more often than it says "the Gulf of
///  Guinea". The analysis never answers it, the loader drops it from an older sidecar, and the app
///  shows nothing for it.
///  </p>
class GeoLocation extends _JsonObject {
	///  The latitude in decimal degrees, positive to the north of the equator.
	double latitude;

	///  The longitude in decimal degrees, positive to the east of Greenwich.
	double longitude;

	/// Creates a GeoLocation.
	GeoLocation({
			this.latitude = 0.0, 
			this.longitude = 0.0, 
	});

	/// Parses a GeoLocation from a string source.
	static GeoLocation? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a GeoLocation instance from the given reader.
	static GeoLocation read(JsonReader json) {
		GeoLocation result = GeoLocation();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "GeoLocation";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "latitude": {
				latitude = json.expectDouble();
				break;
			}
			case "longitude": {
				longitude = json.expectDouble();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("latitude");
		json.addNumber(latitude);

		json.addKey("longitude");
		json.addNumber(longitude);
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

	///  Where this image was taken, <code>null</code> when the file says nowhere (issue #112).
	/// 
	///  <p>
	///  Read from the EXIF GPS tags of the original when the image is analysed, and from the
	///  container of a video where that carries a position, and from the XMP
	///  <code>exif:GPSLatitude</code>/<code>exif:GPSLongitude</code> of a file whose GPS IFD says
	///  nothing (issue #161). The absent message is what "the file carries no position" means, and a
	///  pair of zeroes means the same and is never stored, see {@link GeoLocation}.
	///  </p>
	/// 
	///  <p>
	///  <em>Stored</em> in the sidecar, exactly like {@link #getDate() date} and {@link #getCamera()
	///  camera}: a part a sidecar already lists is never analysed again, so an album written before
	///  this field existed keeps its parts without a position until they are analysed afresh, which
	///  <code>?action=reanalyze</code> does on request (issue #161, {@link ReanalyzeResult}). A
	///  round trip read &rarr; write &rarr; read keeps it unchanged, so a client that stores an
	///  album back never loses where its photos were taken.
	///  </p>
	/// 
	///  <p>
	///  It is answered to whoever may see the image and to nobody else: the position follows the
	///  image's own {@link #getPrivacy() privacy level} and nothing besides, so a caller the
	///  {@link #getPrivacy() privacy} filter hands the image to is handed its position with it.
	///  </p>
	GeoLocation? location;

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

	///  The faces the server found in this photograph, see issue #124.
	/// 
	///  <p>
	///  Empty for a video (videos are never looked at), for a space whose face index is switched off,
	///  and for every caller that is not a signed-in member: an anonymous visitor of an open space and
	///  a share link are answered no face at all, in the spirit of issue #96.
	///  </p>
	/// 
	///  <p>
	///  Derived on every read from the album's <code>.vacache/faces.json</code> and never stored: the
	///  server clears this field before an <code>index.json</code> is written, exactly like
	///  {@link #contributor}, so a round trip through a client can neither freeze a detection into the
	///  album nor lose one. The embeddings the detection produced never leave the server.
	///  </p>
	List<FaceInfo> faces;

	///  What somebody said about the faces of this photograph, see issue #125.
	/// 
	///  <p>
	///  <b>Stored</b>, and the one piece of the face feature that is: a detection is a guess of a
	///  model and lives in the album's cache, a tag is a human decision and belongs beside the
	///  photograph it is about. Nothing clears this field before an <code>index.json</code> is
	///  written &mdash; unlike {@link #faces}, which is derived on every read.
	///  </p>
	/// 
	///  <p>
	///  The stored statement and the derived answer deliberately do <em>not</em> share a name:
	///  {@link #tags} is what this album says, {@link #faces} is what the server answers, and the
	///  second is built from the first plus the detections of the moment.
	///  </p>
	/// 
	///  <p>
	///  It is written by <code>?action=tag-faces</code> alone, and answered to signed-in members
	///  alone &mdash; exactly like {@link #faces}, see {@link FaceInfo}. A client that reads an album
	///  and writes it back therefore round-trips the tags it was answered; a client too old to know
	///  this field would drop them, which is why the tagging action never goes through a
	///  <code>PUT</code>.
	///  </p>
	List<FaceTag> tags;

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
			this.location, 
			this.group, 
			this.contributor = "", 
			this.contributorLabel = "", 
			this.faces = const [], 
			this.tags = const [], 
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
			case "location": {
				location = json.tryNull() ? null : GeoLocation.read(json);
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
			case "faces": {
				json.expectArray();
				faces = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = FaceInfo.read(json);
						if (value != null) {
							faces.add(value);
						}
					}
				}
				break;
			}
			case "tags": {
				json.expectArray();
				tags = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = FaceTag.read(json);
						if (value != null) {
							tags.add(value);
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

		var _location = location;
		if (_location != null) {
			json.addKey("location");
			_location.writeContent(json);
		}

		json.addKey("contributor");
		json.addString(contributor);

		json.addKey("contributorLabel");
		json.addString(contributorLabel);

		json.addKey("faces");
		json.startArray();
		for (var _element in faces) {
			_element.writeContent(json);
		}
		json.endArray();

		json.addKey("tags");
		json.startArray();
		for (var _element in tags) {
			_element.writeContent(json);
		}
		json.endArray();
	}

	@override
	R visitAbstractImage<R, A>(AbstractImageVisitor<R, A> v, A arg) => v.visitImagePart(this, arg);

}

///  One face found in an {@link ImagePart}, see issue #124.
class FaceInfo extends _JsonObject {
	///  The position of this face among the faces of its image, counting from zero.
	/// 
	///  <p>
	///  What <code>?type=face&amp;face=&lt;index&gt;</code> asks the crop of. It is the position in
	///  {@link ImagePart#faces} as the server found them and is stable while the image and the model
	///  are; a re-detection may renumber them, which is why it is never a name.
	///  </p>
	int index;

	///  The left edge of the face, as a fraction of the image width.
	/// 
	///  <p>
	///  The box is normalised to <code>0..1</code> in the <em>raw raster of the file</em> — the pixels
	///  as they are stored, before the EXIF orientation and before
	///  {@link ImagePart#orientation}. So a rotation the user applies changes nothing stored, and a
	///  client draws the box by applying to it the very transform it applies to the picture.
	///  </p>
	double x;

	///  The top edge of the face, as a fraction of the image height, see {@link #x}.
	double y;

	///  The width of the face, as a fraction of the image width, see {@link #x}.
	double w;

	///  The height of the face, as a fraction of the image height, see {@link #x}.
	double h;

	///  Which group of faces of this album the server believes this face belongs to.
	/// 
	///  <p>
	///  An identifier of the album's clustering, not of a person: it says &quot;these faces are most
	///  likely the same person&quot; and nothing about who that is. It is stable only as far as the
	///  set of faces of the album is; naming a person is issue #125. Empty while the album is not
	///  clustered yet.
	///  </p>
	String cluster;

	///  The person this face is, <code>id</code> of a {@link Person} of the space (issue #125).
	/// 
	///  <p>
	///  Derived on every read from the {@link ImagePart#tags} of the photograph: a detection whose
	///  box overlaps a stored tag is answered that tag's person. Empty when nobody said anything
	///  about this face, and empty for a {@link FaceState#NOT_A_FACE} tag, which is about nobody.
	///  </p>
	/// 
	///  <p>
	///  Always the <em>surviving</em> person: a tag naming a person that was merged into another one
	///  is answered with the one it was merged into, so that a merge never has to rewrite an album,
	///  see {@link Person#id}.
	///  </p>
	/// 
	///  <p>
	///  A suggestion is not a person: what a recogniser believes is issue #127 and will be a field of
	///  its own. This one is somebody's decision and nothing else.
	///  </p>
	String person;

	///  Whether {@link #person} is a confirmation rather than anything else, see {@link #state}.
	/// 
	///  <p>
	///  The plain question the application asks most often &mdash; &quot;may I write this name under
	///  this face?&quot; &mdash; answered without reading the state: <code>true</code> exactly for
	///  {@link FaceState#CONFIRMED}.
	///  </p>
	bool confirmed;

	///  What was decided about this face, {@link FaceState#UNDECIDED} while nothing was (issue #125).
	/// 
	///  <p>
	///  {@link FaceState#REJECTED} says that this face is <em>not</em> {@link #person} &mdash; the
	///  person stands in the answer so that the application can go on hiding the suggestion and issue
	///  #127 never offers it again. {@link FaceState#NOT_A_FACE} is not answered since issue #155:
	///  a face somebody called no face is left out of every answer, so a client only ever sees this
	///  state on a face it has just decided about itself, before it reads the album again.
	///  </p>
	FaceState state;

	/// Creates a FaceInfo.
	FaceInfo({
			this.index = 0, 
			this.x = 0.0, 
			this.y = 0.0, 
			this.w = 0.0, 
			this.h = 0.0, 
			this.cluster = "", 
			this.person = "", 
			this.confirmed = false, 
			this.state = FaceState.undecided, 
	});

	/// Parses a FaceInfo from a string source.
	static FaceInfo? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a FaceInfo instance from the given reader.
	static FaceInfo read(JsonReader json) {
		FaceInfo result = FaceInfo();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "FaceInfo";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "index": {
				index = json.expectInt();
				break;
			}
			case "x": {
				x = json.expectDouble();
				break;
			}
			case "y": {
				y = json.expectDouble();
				break;
			}
			case "w": {
				w = json.expectDouble();
				break;
			}
			case "h": {
				h = json.expectDouble();
				break;
			}
			case "cluster": {
				cluster = json.expectString();
				break;
			}
			case "person": {
				person = json.expectString();
				break;
			}
			case "confirmed": {
				confirmed = json.expectBool();
				break;
			}
			case "state": {
				state = readFaceState(json);
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("index");
		json.addNumber(index);

		json.addKey("x");
		json.addNumber(x);

		json.addKey("y");
		json.addNumber(y);

		json.addKey("w");
		json.addNumber(w);

		json.addKey("h");
		json.addNumber(h);

		json.addKey("cluster");
		json.addString(cluster);

		json.addKey("person");
		json.addString(person);

		json.addKey("confirmed");
		json.addBool(confirmed);

		json.addKey("state");
		writeFaceState(json, state);
	}

}

///  What somebody decided about one face, see {@link FaceTag} and issue #125.
/// 
///  <p>
///  A rejection is a decision too: without it, a suggestion that was turned down would come back at
///  the next pass.
///  </p>
enum FaceState {
	///  Nothing was decided about this face.
	/// 
	///  <p>
	///  The first constant and therefore what a {@link FaceInfo} of an untouched detection answers,
	///  and what a client that does not know a value reads.
	///  </p>
	/// 
	///  <p>
	///  Stored since issue #155, and then only on a region somebody marked by hand: a stored
	///  tag ({@link FaceTag}) in this state says "there is a face here and nobody has said who it is" &mdash;
	///  what a hand-marked face falls back to when its decision is forgotten, and what a marked region
	///  the detector finds nothing in is stored as. An album written before issue #155 holds no such
	///  tag.
	///  </p>
	/// 
	///  <p>
	///  In a {@link FaceAssignment} it means <em>forget the decision on this face</em> (issue #138):
	///  on a detection the stored tag is removed and the face goes back to being a plain detection,
	///  which issue #127 may suggest for again; on a hand-marked face the region stays, undecided. It
	///  is the one way back out of a decision &mdash; every other state replaces one. With a box that
	///  meets none of the answered faces it means <em>mark a region here</em> (issue #155): the server
	///  looks for a face in the original around the box and stores what it finds, or the box itself
	///  as an undecided region where it finds nothing.
	///  </p>
	undecided,
	///  This face is {@link FaceTag#person}: somebody said so.
	confirmed,
	///  This face is <em>not</em> {@link FaceTag#person}: somebody said so.
	/// 
	///  <p>
	///  The person stands in the tag, because that is what was rejected. A face may carry only one
	///  decision at a time, so a rejection is replaced by a confirmation when somebody names the
	///  face after all.
	///  </p>
	rejected,
	///  There is no face here at all: what the detector found is a false positive.
	/// 
	///  <p>
	///  Since issue #155 this removes the region from every answer: a detection carrying such a tag
	///  is stored as that statement and no longer answered, and a hand-marked region called no face
	///  is removed from the album outright. Marking the spot again replaces the tag and brings the
	///  region back.
	///  </p>
	notAFace,
}

/// Writes a value of FaceState to a JSON stream.
void writeFaceState(JsonSink json, FaceState value) {
	switch (value) {
		case FaceState.undecided: json.addString("UNDECIDED"); break;
		case FaceState.confirmed: json.addString("CONFIRMED"); break;
		case FaceState.rejected: json.addString("REJECTED"); break;
		case FaceState.notAFace: json.addString("NOT_A_FACE"); break;
		default: throw ("No such literal: " + value.name);
	}
}

/// Reads a value of FaceState from a JSON stream.
FaceState readFaceState(JsonReader json) {
	switch (json.expectString()) {
		case "UNDECIDED": return FaceState.undecided;
		case "CONFIRMED": return FaceState.confirmed;
		case "REJECTED": return FaceState.rejected;
		case "NOT_A_FACE": return FaceState.notAFace;
		default: return FaceState.undecided;
	}
}

///  What somebody said about one face of one photograph, stored in the album, see issue #125.
/// 
///  <p>
///  The box is the detector's box <em>as it stood when the tag was made</em>, copied out of the
///  {@link FaceInfo} the tagging request named and written in the same frame: normalised to
///  <code>0..1</code> in the raw raster of the file, before the EXIF orientation and before
///  {@link ImagePart#orientation}. So the tag outlives the cache it came from, the model that found
///  it and any rotation the user applies; a later detection is matched to it by the overlap of the
///  two boxes.
///  </p>
class FaceTag extends _JsonObject {
	///  The left edge of the face, as a fraction of the image width, see {@link FaceInfo#x}.
	double x;

	///  The top edge of the face, as a fraction of the image height, see {@link FaceInfo#x}.
	double y;

	///  The width of the face, as a fraction of the image width, see {@link FaceInfo#x}.
	double w;

	///  The height of the face, as a fraction of the image height, see {@link FaceInfo#x}.
	double h;

	///  The {@link Person#id} this decision is about; empty for {@link FaceState#NOT_A_FACE}.
	/// 
	///  <p>
	///  Stored as the person's own id at the moment of the tagging. A person that is merged into
	///  another one afterwards keeps this album untouched: the read path resolves the id through the
	///  {@link Person#aliases} of the register, see {@link FaceInfo#person}.
	///  </p>
	String person;

	///  What was decided.
	/// 
	///  <p>
	///  Undecided ({@link FaceState#UNDECIDED}) only on a region somebody marked by hand and nobody
	///  decided about (issue #155), see there; before that issue a stored tag was never undecided.
	///  </p>
	FaceState state;

	/// Creates a FaceTag.
	FaceTag({
			this.x = 0.0, 
			this.y = 0.0, 
			this.w = 0.0, 
			this.h = 0.0, 
			this.person = "", 
			this.state = FaceState.undecided, 
	});

	/// Parses a FaceTag from a string source.
	static FaceTag? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a FaceTag instance from the given reader.
	static FaceTag read(JsonReader json) {
		FaceTag result = FaceTag();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "FaceTag";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "x": {
				x = json.expectDouble();
				break;
			}
			case "y": {
				y = json.expectDouble();
				break;
			}
			case "w": {
				w = json.expectDouble();
				break;
			}
			case "h": {
				h = json.expectDouble();
				break;
			}
			case "person": {
				person = json.expectString();
				break;
			}
			case "state": {
				state = readFaceState(json);
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("x");
		json.addNumber(x);

		json.addKey("y");
		json.addNumber(y);

		json.addKey("w");
		json.addNumber(w);

		json.addKey("h");
		json.addNumber(h);

		json.addKey("person");
		json.addString(person);

		json.addKey("state");
		writeFaceState(json, state);
	}

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

///  What a {@link FolderInfo} of a listing stands for, see {@link FolderInfo#kind} and issue #133.
/// 
///  <p>
///  The kind is derived from the disk on every read, exactly like {@link FolderInfo#effectiveDate}:
///  the folder's own sidecar says it ({@link AlbumInfo} or {@link ListingInfo}), and a folder without
///  one is what the server would answer for it — an album as soon as it holds images.
///  </p>
/// 
///  <p>
///  {@link #ALBUM} is the first constant and therefore the value a listing from a server that does
///  not know this field yet reads as: such a listing behaves exactly as it did before, every entry
///  an album.
///  </p>
enum FolderKind {
	///  The entry is an album: a folder of photographs, and the only kind that has a date.
	album,
	///  The entry is a folder of folders: it holds albums (and further folders), and it has no date
	///  of its own.
	/// 
	///  <p>
	///  Such a folder still carries an {@link FolderInfo#effectiveDate}, because that is what the
	///  listing is sorted by — a folder named <code>2026</code> sorts with the year it names. It is a
	///  sort key and not a day anything happened on, so nothing shows it as a date, see issue #133.
	///  </p>
	folder,
	///  The entry is an inbox: an album (see {@link AlbumKind#INBOX}) holding photographs that wait
	///  to be sorted.
	/// 
	///  <p>
	///  It has no date, it stands first in its listing whatever else lies there, and it is only ever
	///  an entry of a listing answered to a caller that may see it at all — to everybody else the
	///  entry is simply not there, see issue #131.
	///  </p>
	inbox,
}

/// Writes a value of FolderKind to a JSON stream.
void writeFolderKind(JsonSink json, FolderKind value) {
	switch (value) {
		case FolderKind.album: json.addString("ALBUM"); break;
		case FolderKind.folder: json.addString("FOLDER"); break;
		case FolderKind.inbox: json.addString("INBOX"); break;
		default: throw ("No such literal: " + value.name);
	}
}

/// Reads a value of FolderKind from a JSON stream.
FolderKind readFolderKind(JsonReader json) {
	switch (json.expectString()) {
		case "ALBUM": return FolderKind.album;
		case "FOLDER": return FolderKind.folder;
		case "INBOX": return FolderKind.inbox;
		default: return FolderKind.album;
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

	///  The name of the child entry whose picture stands for this folder, see issue #110.
	/// 
	///  <p>
	///  A folder of folders holds no photograph of its own, so it can only be shown by one that
	///  lies below it. Which one is a statement of the author and nothing the server guesses: this
	///  field names a direct child of this folder — an album, whose
	///  {@link AlbumInfo#getIndexPicture() index picture} is taken, or a further folder, which is
	///  asked the same question again. The empty string is the answer "none", and then the folder is
	///  drawn with the folder icon as it always was.
	///  </p>
	/// 
	///  <p>
	///  Stored in this folder's own <code>index.json</code> beside the {@link #getPlacement()
	///  placement rule}, and written by the ordinary sidecar <code>PUT</code>. What is derived from
	///  it is the {@link FolderInfo#getIndexPicture() cover} of the tile this folder is shown with,
	///  whose {@link ThumbnailInfo#getImage() image} then carries the path from this folder down to
	///  the photograph (<code>A/a.jpg</code>). A choice that leads nowhere — a child that is gone,
	///  one without a sidecar, one that shows no picture — is simply no picture; nothing fails and
	///  nothing is rewritten.
	///  </p>
	String index;

	///  Description of the folders within this {@link ListingInfo}.
	List<FolderInfo> folders;

	/// Creates a ListingInfo.
	ListingInfo({
			super.path, 
			super.rights, 
			this.title = "", 
			this.placement = Placement.none, 
			this.index = "", 
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
			case "index": {
				index = json.expectString();
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

		json.addKey("index");
		json.addString(index);

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

	///  Whether this entry is an album or a folder of folders, see issue #133.
	/// 
	///  <p>
	///  Derived on every read like {@link #effectiveDate} and never stored in a sidecar: what a
	///  folder is, is a question about the disk, and the answer is rebuilt whenever the listing is.
	///  </p>
	/// 
	///  <p>
	///  The one thing that tells a reader whether {@link #effectiveDate} is a date to show or merely
	///  the key this entry is sorted by: only an album happened on a day.
	///  </p>
	FolderKind kind;

	///  How many photographs an inbox holds, <code>0</code> for everything else, see issue #137.
	/// 
	///  <p>
	///  The number of {@link ImagePart}s the folder's own sidecar lists, the members of an
	///  {@link ImageGroup} counted one by one. Derived on every read like {@link #kind} and read from
	///  the very sidecar the listing opens anyway, so it costs a listing nothing: not one image file
	///  is opened for it, which is the rule {@link #effectiveDate} is bound by too.
	///  </p>
	/// 
	///  <p>
	///  <b>Only an inbox carries it.</b> An album and a folder of folders answer <code>0</code>, and
	///  deliberately so: an inbox is a pile of work and its tile says how much is left, while the
	///  count of an album is no part of what an album is. A folder of folders would have to be walked
	///  for one, which a listing never does.
	///  </p>
	/// 
	///  <p>
	///  It is what the folder holds, not what the caller may see: a member who may only
	///  {@link #kind contribute} is answered the whole inbox's count although they are shown their
	///  own contributions inside it (issue #135). The sidecar knows no contributors &mdash; the hash
	///  sidecar does &mdash; so a count of one's own would cost a second file per entry of every
	///  listing, and the number the tile shows is "this much is waiting here", which is true for
	///  everybody.
	///  </p>
	int imageCount;

	///  The picture this entry is shown with, <code>null</code> where it is shown with none.
	/// 
	///  <p>
	///  For an album, the index picture of the {@link AlbumInfo} referenced by this
	///  {@link FolderInfo}, and its {@link ThumbnailInfo#getImage() image} is the file name of a
	///  photograph lying in that album.
	///  </p>
	/// 
	///  <p>
	///  For a folder of folders, the picture of the child the folder
	///  {@link ListingInfo#getIndex() chose}, and then the image is the <em>path</em> from this
	///  entry down to the photograph (<code>A/a.jpg</code>), with the crop and the
	///  {@link ThumbnailInfo#getOrientation() frame} of the album it comes from, see issue #110. The
	///  address the client builds is the same either way — <code>&lt;listing&gt;/&lt;name&gt;/&lt;
	///  image&gt;</code> — because the extra segments are part of the image.
	///  </p>
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
			this.kind = FolderKind.album, 
			this.imageCount = 0, 
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
			case "kind": {
				kind = readFolderKind(json);
				break;
			}
			case "imageCount": {
				imageCount = json.expectInt();
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

		json.addKey("kind");
		writeFolderKind(json, kind);

		json.addKey("imageCount");
		json.addNumber(imageCount);

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

	///  The {@link Orientation} the crop was made in, and the one to apply to the server's rendition
	///  before the crop transform, see issue #115.
	/// 
	///  <p>
	///  A rendition the server makes is upright by the <em>file</em>; the rotation an author stored
	///  beside the image ({@link ImagePart#getOrientation()}) is applied on top of it by the client.
	///  A crop is therefore only meaningful together with the orientation it was framed in: the
	///  {@link #getScale() scale} and the {@link #getTx() offsets} are measured in the frame this
	///  field names, and the tile turns the rendition by it before applying them.
	///  </p>
	/// 
	///  <p>
	///  Absent in every sidecar written before this field existed, which reads as
	///  {@link Orientation#IDENTITY} — the frame such a crop was made in.
	///  </p>
	Orientation orientation;

	/// Creates a ThumbnailInfo.
	ThumbnailInfo({
			this.image = "", 
			this.scale = 0.0, 
			this.tx = 0.0, 
			this.ty = 0.0, 
			this.orientation = Orientation.identity, 
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
			case "orientation": {
				orientation = readOrientation(json);
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

		json.addKey("orientation");
		writeOrientation(json, orientation);
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

	///  The token of an {@link Invitation}; an alias of {@link #deviceCode}, retired by issue #89.
	/// 
	///  <p>
	///  An invitation is a pending user carrying a code: the user is created when the invitation is
	///  issued and the link carries the single-use code that adds their first device, so accepting
	///  an invitation is the ordinary pairing and the token belongs in {@link #deviceCode}. This
	///  field is read for one release &mdash; a request carrying it and no {@link #deviceCode} is
	///  redeemed exactly as if it had carried one &mdash; so that an app from before the change
	///  keeps joining.
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

	///  How a position is shown on a map in this space, see issue #112.
	/// 
	///  <p>
	///  A URL template carrying <code>{lat}</code> and <code>{lon}</code>, which the app substitutes
	///  with the decimal degrees of an {@link ImagePart#getLocation() image's position} — a dot as
	///  the decimal separator, whatever the locale of the device. The default is
	///  <code>https://www.google.com/maps?q={lat},{lon}</code>; OpenStreetMap, Apple Maps or a map
	///  of one's own are simply other templates, which is why there is no provider to choose from.
	///  </p>
	/// 
	///  <p>
	///  A property of the <em>space</em>, read from its <code>.valbum/space.json</code> beside the
	///  name and the anonymous access, and answered here because <code>?type=auth</code> is the one
	///  request the app makes anyway. An older server answers nothing, and the app then applies the
	///  same default itself.
	///  </p>
	String mapUrl;

	///  Whether this space has the face index switched on, see issue #124.
	/// 
	///  <p>
	///  A property of the <em>space</em>, read from its <code>.valbum/space.json</code>
	///  (<code>faces: off|on</code>, missing means off) and answered here because
	///  <code>?type=auth</code> is the one request the application makes anyway. Processing the
	///  biometrics of one's family is the administrator's decision, so nothing is detected and nothing
	///  is offered until they made it. <code>false</code> also where the space asked for it but the
	///  machine cannot load the detector at all.
	///  </p>
	bool faces;

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
			this.mapUrl = "", 
			this.faces = false, 
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
			case "mapUrl": {
				mapUrl = json.expectString();
				break;
			}
			case "faces": {
				faces = json.expectBool();
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

		json.addKey("mapUrl");
		json.addString(mapUrl);

		json.addKey("faces");
		json.addBool(faces);

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

///  Answer to an {@link UploadCheck} naming those of the asked hashes the space already holds.
/// 
///  <p>
///  Since issue #118 the answer is not limited to the addressed folder any more: a photo that was
///  synced into the inbox and then moved into an album is still <em>present</em>, wherever it went,
///  see {@link PresentFile#name}. A contribution through a share link is the exception and stays
///  confined to the shared folder: a link sees no more of the space than its folder.
///  </p>
class UploadCheckResult extends _JsonObject {
	///  The asked contents that are already present, in the order they were asked for.
	List<PresentFile> present;

	///  How far the space's hash index has got, see issue #118; <code>null</code> where the server
	///  keeps no index (an older build, or a share-link caller, which is answered from the shared
	///  folder alone).
	/// 
	///  <p>
	///  A client that syncs a camera roll reads it before it transfers anything: while the index is
	///  incomplete, a photo that lies in a not-yet-indexed album is not recognised and would be
	///  uploaded a second time.
	///  </p>
	IndexProgress? indexed;

	/// Creates a UploadCheckResult.
	UploadCheckResult({
			this.present = const [], 
			this.indexed, 
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
			case "indexed": {
				indexed = json.tryNull() ? null : IndexProgress.read(json);
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

		var _indexed = indexed;
		if (_indexed != null) {
			json.addKey("indexed");
			_indexed.writeContent(json);
		}
	}

}

///  How far the hash index of a space has got, see {@link UploadCheckResult#indexed} and issue #118.
/// 
///  <p>
///  Folders, not photos: the index walks the space one folder at a time, and a folder is the unit a
///  person recognises in a progress line. {@link #done} equals {@link #total} exactly when the index
///  is complete; while the index has not even said how much there is to do, {@link #total} is one
///  more than {@link #done}, so that an incomplete index never reads as a complete one.
///  </p>
class IndexProgress extends _JsonObject {
	///  The number of folders that are indexed.
	int done;

	///  The number of folders holding images; never less than {@link #done}.
	int total;

	/// Creates a IndexProgress.
	IndexProgress({
			this.done = 0, 
			this.total = 0, 
	});

	/// Parses a IndexProgress from a string source.
	static IndexProgress? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a IndexProgress instance from the given reader.
	static IndexProgress read(JsonReader json) {
		IndexProgress result = IndexProgress();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "IndexProgress";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "done": {
				done = json.expectInt();
				break;
			}
			case "total": {
				total = json.expectInt();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("done");
		json.addNumber(done);

		json.addKey("total");
		json.addNumber(total);
	}

}

///  A content of an {@link UploadCheckResult} that the space already holds.
class PresentFile extends _JsonObject {
	///  The SHA-256 hash (lower-case hex) that was asked for.
	String hash;

	///  Where the content is, relative to the root of the caller's space.
	/// 
	///  <p>
	///  A bare file name when the addressed folder itself holds the content, exactly as before issue
	///  #118; a path with <code>/</code> as separator (<code>2020/Trip/IMG_1.jpg</code>) when it
	///  lies elsewhere in the space. The client only shows it — nothing is addressed by it.
	///  </p>
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

///  Answer to <code>&lt;folder&gt;/?action=reanalyze</code>, see issue #161.
/// 
///  <p>
///  A part a sidecar already lists is never analysed again (issue #78), so a library described
///  before the camera (#78) or the position (#112) existed carries neither. On request the server
///  reads the headers of every photograph and video below the folder once more &mdash; never a pixel
///  &mdash; and fills in what the sidecar lacks: an empty {@link ImagePart#getCamera() camera} and an
///  absent (or <code>0/0</code>) {@link ImagePart#getLocation() location}. Nothing that is stored is
///  ever changed, least of all a date the author corrected.
///  </p>
/// 
///  <p>
///  An album is re-read while the request waits. A folder of folders is re-read one album after the
///  other on a low-priority thread of the space; the request waits a few seconds for it and, where
///  that is not enough, is answered <code>202</code> with the counts so far and {@link #running} set.
///  The same counts are read back with <code>GET &lt;folder&gt;/?type=reanalyze</code>, and asking
///  for the same folder again while it runs joins that run instead of starting a second one.
///  </p>
class ReanalyzeResult extends _JsonObject {
	///  How many photographs and videos were looked at.
	int examined;

	///  How many of them gained a camera, a position or both.
	int filled;

	///  How many album sidecars were written; an album that gained nothing is not written.
	int albums;

	///  Whether the run goes on in the background, so the counts are the counts so far.
	bool running;

	/// Creates a ReanalyzeResult.
	ReanalyzeResult({
			this.examined = 0, 
			this.filled = 0, 
			this.albums = 0, 
			this.running = false, 
	});

	/// Parses a ReanalyzeResult from a string source.
	static ReanalyzeResult? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a ReanalyzeResult instance from the given reader.
	static ReanalyzeResult read(JsonReader json) {
		ReanalyzeResult result = ReanalyzeResult();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "ReanalyzeResult";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "examined": {
				examined = json.expectInt();
				break;
			}
			case "filled": {
				filled = json.expectInt();
				break;
			}
			case "albums": {
				albums = json.expectInt();
				break;
			}
			case "running": {
				running = json.expectBool();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("examined");
		json.addNumber(examined);

		json.addKey("filled");
		json.addNumber(filled);

		json.addKey("albums");
		json.addNumber(albums);

		json.addKey("running");
		json.addBool(running);
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

	///  Whether this user is an invitation nobody has accepted yet (issue #89).
	/// 
	///  <p>
	///  An invitation <em>is</em> a pending user: the user is created when the invitation is
	///  issued, with the permission it carries and no name and no device, and the invitation's
	///  link carries the single-use code that adds the first one. Such a user holds nothing until
	///  somebody redeems the code &mdash; they have no device and therefore no token &mdash; and
	///  withdrawing the invitation removes them again.
	///  </p>
	bool pending;

	///  Whom the inviter meant this invitation for, empty where nobody said (issue #89).
	/// 
	///  <p>
	///  The inviter's own memento, see {@link Invitation#recipient}; it stays beside the name once
	///  the person has chosen one, so that "who is 'bob42' again?" has an answer.
	///  </p>
	String recipient;

	///  The name of the user who invited this one, empty for everybody else (issue #89).
	String invitedBy;

	///  The id of the invitation this user came in by, empty for everybody else (issue #89).
	/// 
	///  <p>
	///  What names the invitation at <code>?action=uninvite</code>, so that a pending user can be
	///  withdrawn from the users list itself. It stays as history once the invitation was accepted.
	///  </p>
	String invitation;

	///  The {@link Person#id} of the person of the register this user is, empty for nobody (issue #128).
	/// 
	///  <p>
	///  Derived on every read from the register, where the link is stored as {@link Person#user}:
	///  the two ends of one link, answered from whichever end was asked. An id and not a
	///  {@link Person}, because the client that shows this list has the register already &mdash;
	///  copying name, cover and aliases into every user would say the same thing twice and go stale
	///  the moment somebody is renamed.
	///  </p>
	String person;

	///  What that person is called, empty where {@link #person} is (issue #128).
	/// 
	///  <p>
	///  The one thing a management screen needs beside the id &mdash; "Appears in photos as Anna"
	///  &mdash; so that the users list reads without fetching the register as well. Everything else
	///  about that person is asked of <code>?type=people</code>.
	///  </p>
	String personName;

	/// Creates a UserEntry.
	UserEntry({
			this.name = "", 
			this.role = "", 
			this.space = "", 
			this.created = "", 
			this.devices = 0, 
			this.clearance = "", 
			this.mayShare = false, 
			this.pending = false, 
			this.recipient = "", 
			this.invitedBy = "", 
			this.invitation = "", 
			this.person = "", 
			this.personName = "", 
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
			case "pending": {
				pending = json.expectBool();
				break;
			}
			case "recipient": {
				recipient = json.expectString();
				break;
			}
			case "invitedBy": {
				invitedBy = json.expectString();
				break;
			}
			case "invitation": {
				invitation = json.expectString();
				break;
			}
			case "person": {
				person = json.expectString();
				break;
			}
			case "personName": {
				personName = json.expectString();
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

		json.addKey("pending");
		json.addBool(pending);

		json.addKey("recipient");
		json.addString(recipient);

		json.addKey("invitedBy");
		json.addString(invitedBy);

		json.addKey("invitation");
		json.addString(invitation);

		json.addKey("person");
		json.addString(person);

		json.addKey("personName");
		json.addString(personName);
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

	///  When the caller's backup code was made, empty while they have none (issue #92).
	/// 
	///  <p>
	///  Never the code itself — that is answered exactly once, when it is made. What the list
	///  says is only whether there <em>is</em> one and since when, which is what the devices section
	///  shows and what the sign-out warning needs: signing out of one's last device is a door that
	///  locks behind one, unless a backup code is lying in a drawer.
	///  </p>
	String backupCodeCreated;

	/// Creates a DeviceList.
	DeviceList({
			this.devices = const [], 
			this.backupCodeCreated = "", 
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
			case "backupCodeCreated": {
				backupCodeCreated = json.expectString();
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

		json.addKey("backupCodeCreated");
		json.addString(backupCodeCreated);
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
	/// 
	///  <p>
	///  <b>Empty means never</b> (issue #92): a <em>backup code</em> is the one code that does not
	///  run out, because it is written down today for a day nobody can foresee. It is sixteen
	///  characters instead of eight for exactly that reason, it is still single-use, and it is
	///  withdrawn by making a new one or by
	///  <code>&lt;data&gt;/?action=revoke-backup-code</code>.
	///  </p>
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

	///  Whom this invitation was meant for, the inviter's own memento (issue #89).
	/// 
	///  <p>
	///  Optional and free text: "Grandma", "Bob from the choir" &mdash; what the inviter needs in
	///  order to tell one open invitation from another weeks later, and what the users list keeps
	///  beside the name once the person has chosen one. It is stored on the pending user the
	///  invitation creates, not on a store of its own.
	///  </p>
	String recipient;

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
			this.recipient = "", 
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
			case "recipient": {
				recipient = json.expectString();
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

		json.addKey("recipient");
		json.addString(recipient);

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

	///  Whom the inviter wrote this invitation for, empty if they wrote nobody (issue #89).
	/// 
	///  <p>
	///  The inviter's memento, see {@link Invitation#recipient}. The application does not show it
	///  to the person who opened the link &mdash; it is a note the inviter made to themselves, not
	///  a greeting &mdash; and it travels only so that a client that wants to greet by name could.
	///  </p>
	String recipient;

	/// Creates a InvitationInfo.
	InvitationInfo({
			this.role = "", 
			this.invitedBy = "", 
			this.note = "", 
			this.expires = "", 
			this.recipient = "", 
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
			case "recipient": {
				recipient = json.expectString();
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

		json.addKey("recipient");
		json.addString(recipient);
	}

}

///  Somebody the photographs of this space are of, see issue #125.
/// 
///  <p>
///  The register lives per space in <code>.valbum/people.json</code> and is the only place a person
///  is named: a {@link FaceTag} carries an {@link #id} and never a name, so renaming a person is one
///  write and merging two is one write, whatever either of them is tagged in.
///  </p>
class Person extends _JsonObject {
	///  The identifier of this person, unique within the space and never reused.
	/// 
	///  <p>
	///  Sixteen random bytes, Base64url without padding &mdash; opaque, and deliberately not derived
	///  from the name, which is editable. A person that was merged into another one keeps their id as
	///  an alias of the surviving one, so an id that was ever handed out goes on resolving, see
	///  {@link #aliases}.
	///  </p>
	String id;

	///  The canonical name of this person; editable, and unique within the space ignoring case.
	/// 
	///  <p>
	///  The full name, the one a person is <em>identified</em> by &mdash; it is what
	///  {@link UserEntry#personName} answers and what a chooser shows beside the
	///  {@link #nickname}. What is written under a face in an album is {@link #nickname} where there
	///  is one and this otherwise, see issue #146.
	///  </p>
	String name;

	///  What to call this person on a photograph, empty where they are called by their
	///  {@link #name} (issue #146).
	/// 
	///  <p>
	///  Typed with the {@link #name} in one field &mdash; <code>Berta M&uuml;ller (Tante
	///  Berta)</code> &mdash; and split by the server, which is the one place the convention lives
	///  (<code>PeopleStore.parseName</code>); <code>?action=create-person</code> and
	///  <code>?action=rename-person</code> therefore go on carrying one string.
	///  </p>
	/// 
	///  <p>
	///  Unlike the {@link #name} a nickname need <b>not</b> be unique &mdash; two grandmothers are
	///  both &quot;Oma&quot; &mdash; so a client that shows two such people side by side names them
	///  by both.
	///  </p>
	String nickname;

	///  The face to show this person by, <code>null</code> while nobody chose one (issue #126).
	PersonCover? cover;

	///  The member of the space this person is, empty when they are nobody in particular.
	/// 
	///  <p>
	///  The {@link UserEntry#name} of a user. Written by issue #128 and carried here unread: this
	///  build stores it and answers it and draws no conclusion from it.
	///  </p>
	String user;

	///  The ids that were merged into this person, see <code>?action=merge-persons</code>.
	/// 
	///  <p>
	///  Answered so that a client can recognise a tag it read before a merge. The server resolves
	///  them itself on every read, so nothing has to.
	///  </p>
	List<PersonAlias> aliases;

	/// Creates a Person.
	Person({
			this.id = "", 
			this.name = "", 
			this.nickname = "", 
			this.cover, 
			this.user = "", 
			this.aliases = const [], 
	});

	/// Parses a Person from a string source.
	static Person? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a Person instance from the given reader.
	static Person read(JsonReader json) {
		Person result = Person();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "Person";

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
			case "nickname": {
				nickname = json.expectString();
				break;
			}
			case "cover": {
				cover = json.tryNull() ? null : PersonCover.read(json);
				break;
			}
			case "user": {
				user = json.expectString();
				break;
			}
			case "aliases": {
				json.expectArray();
				aliases = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = PersonAlias.read(json);
						if (value != null) {
							aliases.add(value);
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

		json.addKey("id");
		json.addString(id);

		json.addKey("name");
		json.addString(name);

		json.addKey("nickname");
		json.addString(nickname);

		var _cover = cover;
		if (_cover != null) {
			json.addKey("cover");
			_cover.writeContent(json);
		}

		json.addKey("user");
		json.addString(user);

		json.addKey("aliases");
		json.startArray();
		for (var _element in aliases) {
			_element.writeContent(json);
		}
		json.endArray();
	}

}

///  One identifier a {@link Person} answers to besides their own, see {@link Person#aliases}.
/// 
///  <p>
///  A message and not a plain string: the Dart backend of the model generator mis-types a
///  <code>repeated string</code> field, see {@link UploadCheck#hashes}.
///  </p>
class PersonAlias extends _JsonObject {
	///  The identifier that was merged away.
	String id;

	/// Creates a PersonAlias.
	PersonAlias({
			this.id = "", 
	});

	/// Parses a PersonAlias from a string source.
	static PersonAlias? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a PersonAlias instance from the given reader.
	static PersonAlias read(JsonReader json) {
		PersonAlias result = PersonAlias();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "PersonAlias";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "id": {
				id = json.expectString();
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
	}

}

///  The face a {@link Person} is shown by, see {@link Person#cover}.
class PersonCover extends _JsonObject {
	///  The photograph, as a path relative to the space root, <code>/</code> as separator.
	String path;

	///  Which face of it, see {@link FaceInfo#index}.
	int face;

	/// Creates a PersonCover.
	PersonCover({
			this.path = "", 
			this.face = 0, 
	});

	/// Parses a PersonCover from a string source.
	static PersonCover? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a PersonCover instance from the given reader.
	static PersonCover read(JsonReader json) {
		PersonCover result = PersonCover();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "PersonCover";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "path": {
				path = json.expectString();
				break;
			}
			case "face": {
				face = json.expectInt();
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

		json.addKey("face");
		json.addNumber(face);
	}

}

///  The people of a space, answered by <code>?type=people</code>.
class PersonList extends _JsonObject {
	///  Every person of the register, in the order they were created; merged ones are not here.
	List<Person> people;

	/// Creates a PersonList.
	PersonList({
			this.people = const [], 
	});

	/// Parses a PersonList from a string source.
	static PersonList? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a PersonList instance from the given reader.
	static PersonList read(JsonReader json) {
		PersonList result = PersonList();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "PersonList";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "people": {
				json.expectArray();
				people = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = Person.read(json);
						if (value != null) {
							people.add(value);
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

		json.addKey("people");
		json.startArray();
		for (var _element in people) {
			_element.writeContent(json);
		}
		json.endArray();
	}

}

///  What <code>?action=create-person</code> asks for.
class PersonCreate extends _JsonObject {
	///  The name of the new person; blanks are trimmed and an empty name is refused.
	/// 
	///  <p>
	///  The typed string, which may carry the {@link Person#nickname} in the convention
	///  <code>&lt;name&gt; (&lt;nickname&gt;)</code>; the server splits it, see issue #146.
	///  </p>
	String name;

	/// Creates a PersonCreate.
	PersonCreate({
			this.name = "", 
	});

	/// Parses a PersonCreate from a string source.
	static PersonCreate? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a PersonCreate instance from the given reader.
	static PersonCreate read(JsonReader json) {
		PersonCreate result = PersonCreate();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "PersonCreate";

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

///  What <code>?action=rename-person</code> asks for.
class PersonRename extends _JsonObject {
	///  The {@link Person#id} to rename; an alias of a person names that person.
	String id;

	///  The new name; blanks are trimmed and an empty name is refused.
	/// 
	///  <p>
	///  The typed string, split like {@link PersonCreate#name}: a name without a parenthesis
	///  clears the {@link Person#nickname} the person had, see issue #146.
	///  </p>
	String name;

	/// Creates a PersonRename.
	PersonRename({
			this.id = "", 
			this.name = "", 
	});

	/// Parses a PersonRename from a string source.
	static PersonRename? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a PersonRename instance from the given reader.
	static PersonRename read(JsonReader json) {
		PersonRename result = PersonRename();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "PersonRename";

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
	}

}

///  What <code>?action=merge-persons</code> asks for: two people who are one.
/// 
///  <p>
///  The one that is kept keeps its id, its name and its cover; the other becomes an alias of it and
///  is gone from every listing. No album is rewritten, see {@link Person#aliases}.
///  </p>
class PersonMerge extends _JsonObject {
	///  The {@link Person#id} that survives.
	String into;

	///  The {@link Person#id} that becomes an alias of {@link #into}.
	String from;

	/// Creates a PersonMerge.
	PersonMerge({
			this.into = "", 
			this.from = "", 
	});

	/// Parses a PersonMerge from a string source.
	static PersonMerge? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a PersonMerge instance from the given reader.
	static PersonMerge read(JsonReader json) {
		PersonMerge result = PersonMerge();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "PersonMerge";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "into": {
				into = json.expectString();
				break;
			}
			case "from": {
				from = json.expectString();
				break;
			}
			default: super._readProperty(key, json);
		}
	}

	@override
	void _writeProperties(JsonSink json) {
		super._writeProperties(json);

		json.addKey("into");
		json.addString(into);

		json.addKey("from");
		json.addString(from);
	}

}

///  What <code>?action=link-person</code> asks for: that a person of the register <em>is</em> a
///  member of the space, see issue #128.
/// 
///  <p>
///  The link is stored on the person ({@link Person#user}) and nowhere else, so there is one place
///  to write and one to read; {@link UserEntry#person} is the same link answered from the other end.
///  </p>
/// 
///  <p>
///  An administrator links anybody to anybody. A member who may edit links a person to
///  <em>themselves</em> and to nobody else: saying "this is me" is a statement about oneself, and
///  saying "this is Anna" about somebody else's account is not.
///  </p>
class PersonLink extends _JsonObject {
	///  The {@link Person#id} to link; an alias of a person names that person.
	String id;

	///  The {@link UserEntry#name} of the member this person is; the empty string unlinks.
	/// 
	///  <p>
	///  One member is at most one person: linking a member that another person already claims is
	///  refused, and so is merging two people who are both linked &mdash; whoever wants that says
	///  first which of the two links is the wrong one.
	///  </p>
	String user;

	/// Creates a PersonLink.
	PersonLink({
			this.id = "", 
			this.user = "", 
	});

	/// Parses a PersonLink from a string source.
	static PersonLink? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a PersonLink instance from the given reader.
	static PersonLink read(JsonReader json) {
		PersonLink result = PersonLink();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "PersonLink";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "id": {
				id = json.expectString();
				break;
			}
			case "user": {
				user = json.expectString();
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

		json.addKey("user");
		json.addString(user);
	}

}

///  What <code>?action=tag-faces</code> asks for: decisions about the faces of one album.
/// 
///  <p>
///  Every assignment is carried out on its own and the whole request is refused if any one of them
///  cannot be: nothing is written until every name, index and person in it is known, so an album is
///  never left half tagged.
///  </p>
class TagFaces extends _JsonObject {
	///  The decisions to store.
	List<FaceAssignment> faces;

	/// Creates a TagFaces.
	TagFaces({
			this.faces = const [], 
	});

	/// Parses a TagFaces from a string source.
	static TagFaces? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a TagFaces instance from the given reader.
	static TagFaces read(JsonReader json) {
		TagFaces result = TagFaces();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "TagFaces";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "faces": {
				json.expectArray();
				faces = [];
				while (json.hasNext()) {
					if (!json.tryNull()) {
						var value = FaceAssignment.read(json);
						if (value != null) {
							faces.add(value);
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

		json.addKey("faces");
		json.startArray();
		for (var _element in faces) {
			_element.writeContent(json);
		}
		json.endArray();
	}

}

///  One decision about one face, see {@link TagFaces}.
class FaceAssignment extends _JsonObject {
	///  The {@link ImagePart#name} of the photograph in the addressed album.
	String image;

	///  Which of its faces, the {@link FaceInfo#index} of the answer this client read.
	/// 
	///  <p>
	///  The box is copied from that {@link FaceInfo} into the stored {@link FaceTag}, so a client
	///  that decides about a face the server found never sends coordinates at all.
	///  </p>
	/// 
	///  <p>
	///  Ignored where a box is given, see {@link #x}: a hand-marked face is <em>not</em> one of the
	///  answered faces, so there is no index that could name it.
	///  </p>
	int face;

	///  The left edge of a hand-marked face, as a fraction of the width of the picture as it is
	///  shown; issue #147.
	/// 
	///  <p>
	///  <b>A box is given exactly when any of {@link #x}, {@link #y}, {@link #w} and {@link #h} is
	///  not zero</b>, and it is then read instead of {@link #face}. That is how somebody marks a face
	///  the detector missed: the viewer's edit-persons mode draws a rectangle on the photograph and
	///  names it, and what arrives here is that rectangle.
	///  </p>
	/// 
	///  <p>
	///  The frame is the one the client draws in and the one every {@link FaceInfo} is answered in
	///  (issue #142): normalised to <code>0..1</code> of the picture <em>upright</em> — the EXIF
	///  orientation of the file applied, {@link ImagePart#orientation} not. The server turns it into
	///  the raw raster of the file, which is the one frame a {@link FaceTag} is ever stored in.
	///  </p>
	/// 
	///  <p>
	///  A box that meets one of the answered faces by the overlap that makes two boxes the same face
	///  is that face's decision and carries that face's own box, so marking a face that was found
	///  after all is the very same thing as naming it. A box nothing meets becomes a tag of its own,
	///  which is answered as a {@link FaceInfo} without a cluster, with a crop cut from the original
	///  (issue #155).
	///  </p>
	/// 
	///  <p>
	///  With {@link FaceState#UNDECIDED} a box nothing meets is <em>mark a region here</em> (issue
	///  #155): the server looks for a face in a region of the original around the box &mdash; at least
	///  a square of a fifth of the picture's long side, so that a click sent as a small box will do
	///  &mdash; and stores the face it finds there as a detection of its own, or the box itself as an
	///  undecided region where it finds none. Either way the answer carries the region as a face.
	///  </p>
	/// 
	///  <p>
	///  A box outside <code>0..1</code>, or one with a width or a height that is not positive, is
	///  refused: a face is somewhere on the photograph or it is nowhere.
	///  </p>
	double x;

	///  The top edge of a hand-marked face, as a fraction of the height, see {@link #x}.
	double y;

	///  The width of a hand-marked face, as a fraction of the width, see {@link #x}.
	double w;

	///  The height of a hand-marked face, as a fraction of the height, see {@link #x}.
	double h;

	///  The {@link Person#id} the decision is about; empty exactly for {@link FaceState#NOT_A_FACE}.
	String person;

	///  What is decided; {@link FaceState#UNDECIDED} takes the decision on this box back (issue #138).
	/// 
	///  <p>
	///  Forgetting is idempotent: an <code>UNDECIDED</code> for a box that carries no tag changes
	///  nothing and is no error. The {@link #person} is ignored for it &mdash; what is forgotten is
	///  the decision, whoever it was about.
	///  </p>
	FaceState state;

	/// Creates a FaceAssignment.
	FaceAssignment({
			this.image = "", 
			this.face = 0, 
			this.x = 0.0, 
			this.y = 0.0, 
			this.w = 0.0, 
			this.h = 0.0, 
			this.person = "", 
			this.state = FaceState.undecided, 
	});

	/// Parses a FaceAssignment from a string source.
	static FaceAssignment? fromString(String source) {
		return read(JsonReader.fromString(source));
	}

	/// Reads a FaceAssignment instance from the given reader.
	static FaceAssignment read(JsonReader json) {
		FaceAssignment result = FaceAssignment();
		result._readContent(json);
		return result;
	}

	@override
	String _jsonType() => "FaceAssignment";

	@override
	void _readProperty(String key, JsonReader json) {
		switch (key) {
			case "image": {
				image = json.expectString();
				break;
			}
			case "face": {
				face = json.expectInt();
				break;
			}
			case "x": {
				x = json.expectDouble();
				break;
			}
			case "y": {
				y = json.expectDouble();
				break;
			}
			case "w": {
				w = json.expectDouble();
				break;
			}
			case "h": {
				h = json.expectDouble();
				break;
			}
			case "person": {
				person = json.expectString();
				break;
			}
			case "state": {
				state = readFaceState(json);
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

		json.addKey("face");
		json.addNumber(face);

		json.addKey("x");
		json.addNumber(x);

		json.addKey("y");
		json.addNumber(y);

		json.addKey("w");
		json.addNumber(w);

		json.addKey("h");
		json.addNumber(h);

		json.addKey("person");
		json.addString(person);

		json.addKey("state");
		writeFaceState(json, state);
	}

}

