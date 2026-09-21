/// The in-app picker over the phone's photo library (issue #64).
///
/// The system photo picker of Android 13+ hands out at most 100 items, and the
/// author of this album has more than that to add at once. This screen is the
/// way past that cap: it reads the device's own albums through the
/// [PhotoLibrary] abstraction — never through `photo_manager` directly, so
/// that a test drives it with a [FakePhotoLibrary] — and answers the items the
/// user selected, which the album's upload then transfers like any other file.
///
/// Selecting a thousand photos by hand is no better than a cap, so the items of
/// an album are grouped by the month they were taken in, and a month (or the
/// whole album) is selected in one tap.
library;

import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'l10n/app_localizations.dart';
import 'photo_library.dart';

/// The key of the screen itself.
const Key photoPickerKey = Key("photoPicker");

/// The key of the sentence saying why the library cannot be read.
const Key photoPickerProblemKey = Key("photoPicker.problem");

/// The key of the line saying how many items are selected.
const Key photoPickerSelectionKey = Key("photoPicker.selection");

/// The key of the button that ends the picking.
const Key photoPickerUploadKey = Key("photoPicker.upload");

/// The key of the button selecting (or clearing) the whole album.
const Key photoPickerAllKey = Key("photoPicker.all");

/// The key of the tile of one album of the device.
Key photoAlbumKey(String id) => Key("photoPicker.album:$id");

/// The key of the header of one month.
Key photoMonthKey(String month) => Key("photoPicker.month:$month");

/// The key of the button selecting all items of one month.
Key photoMonthAllKey(String month) => Key("photoPicker.monthAll:$month");

/// The key of the tile of one item.
Key photoItemKey(String id) => Key("photoPicker.item:$id");

/// The month [when] falls into, as the grouping key `yyyy-MM`.
String monthOf(DateTime when) =>
    "${when.year.toString().padLeft(4, "0")}-${when.month.toString().padLeft(2, "0")}";

/// The header a month is shown under, e.g. `March 2024`.
///
/// The month name is the locale's own (issue #108): [DateFormat.yMMMM] of
/// [locale], which is the language the screen is drawn in — never a table of
/// names typed into this file.
String monthLabel(String month, [String? locale]) {
  var parts = month.split("-");
  var index = int.tryParse(parts.length > 1 ? parts[1] : "") ?? 0;
  var year = int.tryParse(parts.isNotEmpty ? parts[0] : "") ?? 0;
  if (index < 1 || index > 12 || year <= 0) {
    return month;
  }
  return DateFormat.yMMMM(locale).format(DateTime(year, index));
}

/// Picks photos from the device's library, see [PhotoPickerScreen].
///
/// Answers what the user selected, an empty list where they left without
/// picking anything.
Future<List<PhotoItem>> pickFromLibrary(
  BuildContext context,
  PhotoLibrary library,
) async {
  var picked = await Navigator.of(context).push<List<PhotoItem>>(
    MaterialPageRoute<List<PhotoItem>>(
      builder: (context) => PhotoPickerScreen(library: library),
    ),
  );
  return picked ?? const [];
}

/// The screen picking photos out of the device's library.
class PhotoPickerScreen extends StatefulWidget {
  /// The library to pick from.
  final PhotoLibrary library;

  const PhotoPickerScreen({super.key, required this.library});

  @override
  State<PhotoPickerScreen> createState() => PhotoPickerScreenState();
}

class PhotoPickerScreenState extends State<PhotoPickerScreen> {
  /// The words of the screen, see issue #108.
  AppLocalizations get _l10n => AppLocalizations.of(context)!;

  /// Whether the library is being asked right now.
  bool _loading = true;

  /// Why the library cannot be read, `null` while nothing refused it.
  String? _problem;

  /// The albums of the device, `null` while they have not been read.
  List<PhotoAlbum> _albums = const [];

  /// The album whose items are on the screen, `null` in the album list.
  PhotoAlbum? _album;

  /// The items of [_album], newest first.
  List<PhotoItem> _items = const [];

  /// The items selected, by [PhotoItem.id]; kept across albums, so that a
  /// selection made in one album is not lost by looking into another.
  final Map<String, PhotoItem> _selected = {};

  @override
  void initState() {
    super.initState();
    _open();
  }

  /// Asks for access and reads the albums.
  Future<void> _open() async {
    var granted = await widget.library.requestAccess();
    if (!granted) {
      _publish(() {
        _loading = false;
        // Refusals speak: the library's own reason, and a plain sentence
        // where it gave none.
        _problem = widget.library.accessProblem ?? _l10n.photoLibraryNoAccess;
      });
      return;
    }
    List<PhotoAlbum> albums;
    try {
      albums = await widget.library.albums();
    } catch (error) {
      _publish(() {
        _loading = false;
        _problem = _l10n.photoLibraryUnreadable("$error");
      });
      return;
    }
    _publish(() {
      _loading = false;
      _problem = null;
      _albums = albums;
    });
  }

  /// Opens one album of the device.
  Future<void> _enter(PhotoAlbum album) async {
    _publish(() {
      _loading = true;
      _album = album;
      _items = const [];
    });
    List<PhotoItem> items;
    try {
      items = await widget.library.itemsOf(album);
    } catch (error) {
      _publish(() {
        _loading = false;
        _problem = _l10n.photoAlbumUnreadable("$error");
      });
      return;
    }
    items.sort((a, b) => b.takenAt.compareTo(a.takenAt));
    _publish(() {
      _loading = false;
      _items = items;
    });
  }

  /// Back to the album list, keeping what is selected.
  void _leaveAlbum() => _publish(() {
        _album = null;
        _items = const [];
      });

  void _publish(void Function() change) {
    if (!mounted) {
      return;
    }
    setState(change);
  }

  /// The items of the album by the month they were taken in, newest first.
  Map<String, List<PhotoItem>> get _months {
    var months = <String, List<PhotoItem>>{};
    for (var item in _items) {
      months.putIfAbsent(monthOf(item.takenAt), () => []).add(item);
    }
    return months;
  }

  void _toggle(PhotoItem item) => _publish(() {
        if (_selected.remove(item.id) == null) {
          _selected[item.id] = item;
        }
      });

  void _select(Iterable<PhotoItem> items, bool selected) => _publish(() {
        for (var item in items) {
          if (selected) {
            _selected[item.id] = item;
          } else {
            _selected.remove(item.id);
          }
        }
      });

  /// Whether every one of [items] is selected.
  bool _allSelected(Iterable<PhotoItem> items) =>
      items.isNotEmpty && items.every((item) => _selected.containsKey(item.id));

  @override
  Widget build(BuildContext context) {
    var album = _album;
    var l10n = _l10n;
    return Scaffold(
      key: photoPickerKey,
      appBar: AppBar(
        title: Text(album?.name ?? l10n.photoLibraryTitle),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          tooltip: album == null ? l10n.back : l10n.allAlbums,
          onPressed:
              album == null ? () => Navigator.of(context).pop() : _leaveAlbum,
        ),
        actions: [
          if (album != null && _items.isNotEmpty)
            TextButton(
              key: photoPickerAllKey,
              onPressed: () => _select(_items, !_allSelected(_items)),
              child: Text(
                _allSelected(_items) ? l10n.selectNone : l10n.selectAll,
              ),
            ),
        ],
      ),
      body: _body(),
      bottomNavigationBar: _footer(context),
    );
  }

  Widget _body() {
    var problem = _problem;
    if (problem != null) {
      return Padding(
        padding: const EdgeInsets.all(24),
        child: Center(
          child: Text(
            problem,
            key: photoPickerProblemKey,
            textAlign: TextAlign.center,
          ),
        ),
      );
    }
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_album == null) {
      return _albumList();
    }
    return _itemGrid();
  }

  /// The albums of the device.
  Widget _albumList() {
    if (_albums.isEmpty) {
      return Padding(
        padding: const EdgeInsets.all(24),
        child: Center(
          child: Text(
            _l10n.photoLibraryNoAlbums,
            key: photoPickerProblemKey,
            textAlign: TextAlign.center,
          ),
        ),
      );
    }
    return ListView(
      children: [
        for (var album in _albums)
          ListTile(
            key: photoAlbumKey(album.id),
            leading: const Icon(Icons.photo_album),
            title: Text(album.name),
            subtitle: Text(_l10n.photoCount(album.count)),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => _enter(album),
          ),
      ],
    );
  }

  /// The items of the open album, grouped by month.
  Widget _itemGrid() {
    var months = _months;
    if (months.isEmpty) {
      return Padding(
        padding: const EdgeInsets.all(24),
        child: Center(child: Text(_l10n.photoAlbumEmpty)),
      );
    }
    return ListView(
      children: [
        for (var month in months.keys)
          ..._month(month, months[month] ?? const []),
      ],
    );
  }

  List<Widget> _month(String month, List<PhotoItem> items) => [
        Padding(
          key: photoMonthKey(month),
          padding: const EdgeInsets.fromLTRB(12, 16, 4, 4),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  "${monthLabel(month, Localizations.localeOf(context)
                      .toLanguageTag())} (${items.length})",
                  style: Theme.of(context).textTheme.titleMedium,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              TextButton(
                key: photoMonthAllKey(month),
                onPressed: () => _select(items, !_allSelected(items)),
                child: Text(
                  _allSelected(items) ? _l10n.selectNone : _l10n.selectAll,
                ),
              ),
            ],
          ),
        ),
        GridView.extent(
          maxCrossAxisExtent: 120,
          mainAxisSpacing: 4,
          crossAxisSpacing: 4,
          padding: const EdgeInsets.symmetric(horizontal: 8),
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          children: [
            for (var item in items) _tile(item),
          ],
        ),
      ];

  Widget _tile(PhotoItem item) {
    var selected = _selected.containsKey(item.id);
    return GestureDetector(
      key: photoItemKey(item.id),
      onTap: () => _toggle(item),
      child: Stack(
        fit: StackFit.expand,
        children: [
          _PhotoTileImage(library: widget.library, item: item),
          if (selected)
            Container(
              color: Theme.of(context).colorScheme.primary.withAlpha(90),
              alignment: Alignment.topRight,
              child: const Padding(
                padding: EdgeInsets.all(2),
                child: Icon(Icons.check_circle, color: Colors.white, size: 20),
              ),
            ),
        ],
      ),
    );
  }

  /// What is selected, and the way out with it.
  Widget _footer(BuildContext context) {
    var count = _selected.length;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
        child: Row(
          children: [
            Expanded(
              child: Text(
                _l10n.photoPickerSelected(count),
                key: photoPickerSelectionKey,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            const SizedBox(width: 8),
            FilledButton.icon(
              key: photoPickerUploadKey,
              onPressed: count == 0
                  ? null
                  : () => Navigator.of(context).pop(_selected.values.toList()),
              icon: const Icon(Icons.cloud_upload),
              label: Text(_l10n.photoPickerUpload(count)),
            ),
          ],
        ),
      ),
    );
  }
}

/// The thumbnail of one item, asked for when the tile is built.
///
/// Lazily and once per tile: a phone album holds thousands of photos, and the
/// grid builds only the tiles that are on the screen.
class _PhotoTileImage extends StatefulWidget {
  final PhotoLibrary library;
  final PhotoItem item;

  const _PhotoTileImage({required this.library, required this.item});

  @override
  State<_PhotoTileImage> createState() => _PhotoTileImageState();
}

class _PhotoTileImageState extends State<_PhotoTileImage> {
  Uint8List? _bytes;
  bool _asked = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    Uint8List? bytes;
    try {
      bytes = await widget.library.thumbnail(widget.item);
    } catch (_) {
      bytes = null;
    }
    if (mounted) {
      setState(() {
        _bytes = bytes;
        _asked = true;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    var bytes = _bytes;
    if (bytes != null) {
      return Image.memory(bytes, fit: BoxFit.cover, gaplessPlayback: true);
    }
    return Container(
      color: Colors.black12,
      alignment: Alignment.center,
      child: _asked
          ? const Icon(Icons.image_not_supported, size: 20)
          : const SizedBox.shrink(),
    );
  }
}

/// Where the photos of an upload come from, see the upload menu of an album.
enum UploadSource {
  /// The device's photo library, through [PhotoPickerScreen].
  library,

  /// The system's file picker, which hands out at most 100 items.
  files,
}

/// The key of the menu entry opening the in-app picker.
const Key uploadFromLibraryKey = Key("upload.fromLibrary");

/// The key of the menu entry opening the system picker.
const Key uploadFromFilesKey = Key("upload.fromFiles");

/// Asks where the photos of an upload come from (issue #64).
///
/// Only where the device *has* a library: on the web and on a desktop there is
/// one way to pick a file, and a menu offering one entry is a menu that should
/// not be there, so the caller opens the system picker directly.
Future<UploadSource?> askUploadSource(BuildContext context) =>
    showModalBottomSheet<UploadSource>(
      context: context,
      builder: (context) {
        var l10n = AppLocalizations.of(context)!;
        return SafeArea(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              ListTile(
                key: uploadFromLibraryKey,
                leading: const Icon(Icons.photo_library),
                title: Text(l10n.photoPickerEntry),
                onTap: () => Navigator.of(context).pop(UploadSource.library),
              ),
              ListTile(
                key: uploadFromFilesKey,
                leading: const Icon(Icons.folder_open),
                title: Text(l10n.systemPickerEntry),
                onTap: () => Navigator.of(context).pop(UploadSource.files),
              ),
            ],
          ),
        );
      },
    );
