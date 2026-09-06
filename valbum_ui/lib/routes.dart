/// The URL grammar of the app: the [VAlbumRoute] a location denotes, and the
/// location a route is shown at.
///
/// This library is pure: it knows nothing about widgets, the `Navigator` or
/// the album model, so the mapping can be unit-tested on its own. The router
/// wiring lives in `app.dart`.
///
/// ## The grammar
///
/// The routes are the views the retired GWT client addressed in its URL hash
/// (`App`, `ResourcePath`, `ToPath`), written as real paths instead:
///
/// | location                                    | route                                     |
/// | ------------------------------------------- | ----------------------------------------- |
/// | `/`                                          | [ListingOrAlbumRoute] of the root         |
/// | `/2005-08-24 Blumen/`                        | [ListingOrAlbumRoute] (`[2005-08-24 Blumen]`) |
/// | `/2005-08-24 Blumen/IMG_0417.JPG`            | [ImageRoute]                              |
/// | `/2005-08-24 Blumen/IMG_0417.JPG/alternatives/` | [AlternativesRoute]                    |
/// | `/2005-08-24 Blumen/IMG_0417.JPG/alternatives/IMG_0418.JPG` | [MemberRoute]          |
///
/// A folder or album is therefore addressed **with** a trailing slash and an
/// image **without** one, exactly as the GWT client did. The segment
/// [alternativesSegment] is reserved: a folder of that name is not
/// addressable.
///
/// ## The base path
///
/// A route path is relative to the *app base*, the directory the app was
/// loaded from — the same derivation `urls.dart` uses for the data URL, and
/// the value the Flutter web build writes into `<base href="...">` of
/// `index.html`. Served under the context path `/valbum/`, the album
/// `2005-08-24 Blumen` therefore lives at
/// `/valbum/2005-08-24%20Blumen/`, and [parseRoute] with `basePath: "/valbum/"`
/// maps that location back to `ListingOrAlbumRoute(["2005-08-24 Blumen"])`.
///
/// On the web the base is stripped by Flutter's path URL strategy before the
/// location reaches the app (see `usePathUrlStrategy` in `main.dart`), so the
/// router itself works with `basePath: "/"`; [parseRoute] and [routeToUri] still take
/// the base explicitly, so that a full browser location can be mapped
/// directly.
library;

import 'package:flutter/foundation.dart';

/// The reserved path segment introducing the "alternatives" view of a group.
const String alternativesSegment = "alternatives";

/// One addressable view of the app.
///
/// Every route names the enclosing listing or album by its [albumPath] (the
/// folder names below the album root) and, where it shows something inside an
/// album, the image by its file name.
@immutable
sealed class VAlbumRoute {
  const VAlbumRoute();

  /// The path of the listing or album the route lives in.
  List<String> get albumPath;

  /// The route reached by the "up" action, `null` at the root.
  ///
  /// The image returns to its album, the alternatives view to the image it was
  /// opened from, a group member to the alternatives view, and a listing or
  /// album to its parent folder.
  VAlbumRoute? get up;

  /// The path segments of this route below the app base.
  ///
  /// A trailing empty segment marks the trailing slash of a folder location.
  List<String> get segments;

  /// The location of this route below the app base, e.g. `/a/b.jpg`.
  String get path => "/${Uri(pathSegments: segments).path}";
}

/// A folder listing or an album, addressed with a trailing slash.
class ListingOrAlbumRoute extends VAlbumRoute {
  @override
  final List<String> albumPath;

  const ListingOrAlbumRoute(this.albumPath);

  /// The root listing.
  static const ListingOrAlbumRoute root = ListingOrAlbumRoute([]);

  @override
  VAlbumRoute? get up => albumPath.isEmpty
      ? null
      : ListingOrAlbumRoute(albumPath.sublist(0, albumPath.length - 1));

  @override
  List<String> get segments => [...albumPath, ""];

  @override
  bool operator ==(Object other) =>
      other is ListingOrAlbumRoute && listEquals(albumPath, other.albumPath);

  @override
  int get hashCode => Object.hashAll(albumPath);

  @override
  String toString() => "ListingOrAlbumRoute($path)";
}

/// A single image of an album, shown in the viewer.
///
/// If the image belongs to an `ImageGroup`, the viewer shows the group (and
/// offers the way down to its [AlternativesRoute]), as the GWT client did.
class ImageRoute extends VAlbumRoute {
  @override
  final List<String> albumPath;

  /// The file name of the image within its album.
  final String name;

  const ImageRoute(this.albumPath, this.name);

  @override
  VAlbumRoute? get up => ListingOrAlbumRoute(albumPath);

  @override
  List<String> get segments => [...albumPath, name];

  @override
  bool operator ==(Object other) =>
      other is ImageRoute &&
      name == other.name &&
      listEquals(albumPath, other.albumPath);

  @override
  int get hashCode => Object.hash(Object.hashAll(albumPath), name);

  @override
  String toString() => "ImageRoute($path)";
}

/// The "alternatives" view of the group represented by an image.
class AlternativesRoute extends VAlbumRoute {
  @override
  final List<String> albumPath;

  /// The file name of the image whose group is shown.
  final String name;

  const AlternativesRoute(this.albumPath, this.name);

  @override
  VAlbumRoute? get up => ImageRoute(albumPath, name);

  @override
  List<String> get segments => [...albumPath, name, alternativesSegment, ""];

  @override
  bool operator ==(Object other) =>
      other is AlternativesRoute &&
      name == other.name &&
      listEquals(albumPath, other.albumPath);

  @override
  int get hashCode => Object.hash(Object.hashAll(albumPath), name);

  @override
  String toString() => "AlternativesRoute($path)";
}

/// One image of a group, shown in the viewer in "detail mode".
class MemberRoute extends VAlbumRoute {
  @override
  final List<String> albumPath;

  /// The file name of the image the group is addressed by.
  final String name;

  /// The file name of the group member shown.
  final String member;

  const MemberRoute(this.albumPath, this.name, this.member);

  @override
  VAlbumRoute? get up => AlternativesRoute(albumPath, name);

  @override
  List<String> get segments =>
      [...albumPath, name, alternativesSegment, member];

  @override
  bool operator ==(Object other) =>
      other is MemberRoute &&
      name == other.name &&
      member == other.member &&
      listEquals(albumPath, other.albumPath);

  @override
  int get hashCode => Object.hash(Object.hashAll(albumPath), name, member);

  @override
  String toString() => "MemberRoute($path)";
}

/// The route the given location denotes.
///
/// [basePath] is the app base the location is relative to (see the library
/// documentation); a location outside that base is read as if it were the
/// root; the empty location is the root listing.
VAlbumRoute parseRoute(Uri uri, {String basePath = "/"}) {
  var segments = [...uri.pathSegments];

  // Strip the app base.
  for (var base in _baseSegments(basePath)) {
    if (segments.isNotEmpty && segments.first == base) {
      segments.removeAt(0);
    } else {
      break;
    }
  }

  // `/a/b/` yields a trailing empty segment, `/a/b` does not. Empty segments
  // in the middle (a `//` in the location) carry no meaning.
  var folder = segments.isNotEmpty && segments.last.isEmpty;
  segments.removeWhere((segment) => segment.isEmpty);

  if (folder) {
    // `.../<image>/alternatives/`
    if (segments.length >= 2 && segments.last == alternativesSegment) {
      var name = segments[segments.length - 2];
      return AlternativesRoute(segments.sublist(0, segments.length - 2), name);
    }
    return ListingOrAlbumRoute(segments);
  }

  if (segments.isEmpty) {
    return ListingOrAlbumRoute.root;
  }

  // `.../<image>/alternatives/<member>`
  if (segments.length >= 3 &&
      segments[segments.length - 2] == alternativesSegment) {
    return MemberRoute(
      segments.sublist(0, segments.length - 3),
      segments[segments.length - 3],
      segments.last,
    );
  }

  return ImageRoute(
    segments.sublist(0, segments.length - 1),
    segments.last,
  );
}

/// The location the given route is shown at, below [basePath].
Uri routeToUri(VAlbumRoute route, {String basePath = "/"}) {
  var segments = [..._baseSegments(basePath), ...route.segments];
  return Uri.parse("/${Uri(pathSegments: segments).path}");
}

/// The non-empty segments of the app base, e.g. `[valbum]` for `/valbum/`.
List<String> _baseSegments(String basePath) =>
    basePath.split("/").where((segment) => segment.isNotEmpty).toList();

/// The app base of a web app loaded at [location] showing [routeName].
///
/// With the path URL strategy the document location no longer *is* the app
/// base: opening `/valbum/2005-08-24%20Blumen/IMG_0417.JPG` still loads the
/// app from `/valbum/`, but the directory part of the location is
/// `/valbum/2005-08-24%20Blumen/`. Deriving the data URL from the location
/// alone (as `deriveDataUrl` does by default) would therefore ask the wrong
/// server path.
///
/// The engine hands the app the location with the `<base href>` already
/// stripped ([routeName], normally
/// `PlatformDispatcher.defaultRouteName`), so the base is what remains of the
/// location when that route is cut off its end. Where the two do not fit
/// together (an unusual location, a route naming another folder), the
/// directory of the location is used, which is right for the app's start page.
///
/// The two are compared **decoded and segment by segment**: the browser hands
/// over the location percent-encoded (`/valbum/Haui's%20inbox/`) while the
/// engine hands over the route name decoded (`/Haui's inbox/`), and an
/// apostrophe may be encoded on one side and not on the other. A comparison of
/// the raw strings therefore fails exactly where the folder name is not plain
/// ASCII — and the app then asks a data URL that does not exist, see issue
/// #35. The result is the decoded base (`/valbum/`), which is what
/// [parseRoute] and `deriveDataUrl` want.
String appBasePath(String location, String routeName) {
  var locationSegments = _decodedSegments(location);
  var routeSegments = _decodedSegments(routeName);

  if (routeSegments.isNotEmpty && _endsWith(locationSegments, routeSegments)) {
    return _folderPath(
      locationSegments.sublist(
        0,
        locationSegments.length - routeSegments.length,
      ),
    );
  }

  // The directory of the location: its last segment is the file name (or the
  // empty segment of a trailing slash).
  return _folderPath(
    locationSegments.isEmpty
        ? locationSegments
        : locationSegments.sublist(0, locationSegments.length - 1),
  );
}

/// The decoded path segments of a location or a route name.
///
/// A trailing slash yields a trailing empty segment, as [Uri] does; a query or
/// fragment is dropped. A value [Uri] cannot read at all yields the segments
/// of its raw string, so that nothing throws on an exotic location.
List<String> _decodedSegments(String value) {
  var uri = Uri.tryParse(value);
  if (uri != null) {
    return uri.pathSegments;
  }
  var path = value.split("#").first.split("?").first;
  var segments = path.split("/");
  if (segments.isNotEmpty && segments.first.isEmpty) {
    segments.removeAt(0);
  }
  return segments;
}

/// Whether [segments] ends with [tail].
bool _endsWith(List<String> segments, List<String> tail) {
  if (tail.length > segments.length) {
    return false;
  }
  var offset = segments.length - tail.length;
  for (var index = 0; index < tail.length; index++) {
    if (segments[offset + index] != tail[index]) {
      return false;
    }
  }
  return true;
}

/// The path of the folder made of the given decoded segments, e.g.
/// `/valbum/` for `[valbum]` and `/` for the empty list.
String _folderPath(List<String> segments) {
  var named = segments.where((segment) => segment.isNotEmpty);
  return named.isEmpty ? "/" : "/${named.join("/")}/";
}
