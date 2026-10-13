/// The bell of issue #268: what arrived from others — uploads by members,
/// by contacts through a link, by a device into a shared album, and moves out
/// of the inbox into an album — one entry per person, album and burst of
/// time, read per entry.
///
/// The server keeps the entries and the read marks of every member (the
/// index of the space, `Arrivals`), so the phone and the browser agree. The
/// app asks for the number of unread entries on start, when it comes back to
/// the foreground and every few minutes ([NewsBell]); no push. Opening the
/// menu marks nothing; visiting an entry marks it, and "Mark all as read"
/// marks all of them.
///
/// An entry opens as a view of its photographs alone, in the order of their
/// album ([NewsRoute], [newsAlbumOf]): the viewer pages through just them,
/// and the way back leads to the album itself, scrolled to them and with
/// them marked as new for that visit (`ReturnReveal`, [NewsSession]).
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:jsontool/jsontool.dart';

import 'album_model.dart';
import 'app.dart';
import 'client.dart';
import 'l10n/app_localizations.dart';
import 'manage_view.dart' show refusalMessage;
import 'resource.dart';
import 'selectable.dart';
import 'routes.dart';
import 'thumbnails.dart';

/// How often the app asks for the number of unread entries while it runs.
const Duration newsPollInterval = Duration(minutes: 3);

/// How many thumbnails an entry of the menu shows at most.
const int newsStripLength = 5;

/// The side of a thumbnail of an entry of the menu, in logical pixels.
const double newsThumbnailSize = 48;

/// The key of the bell in an app bar.
const Key newsBellKey = Key("news-bell");

/// The key of "Mark all as read" in the menu.
const Key newsMarkAllKey = Key("news-mark-all");

/// The key of the entry of [key] in the menu.
Key newsEntryKey(String key) => ValueKey("news-entry-$key");

/// The key of the mark of an unread entry of [key] in the menu.
Key newsUnreadKey(String key) => ValueKey("news-unread-$key");

/// The key of the mark on a tile of the album showing a photograph that is new
/// for this visit.
const Key newsFreshMarkerKey = Key("news-fresh-marker");

/// What the server says arrived from others, for the signed-in member, see
/// the library comment.
///
/// Attached to the client of a member ([attach]); `null` for everybody else —
/// a share link, a contact, an anonymous caller has no bell. Asks for the
/// count at once, every [interval] and whenever the app is resumed.
class NewsBell extends ChangeNotifier {
  /// How often the count is asked for.
  final Duration interval;

  NewsBell({this.interval = newsPollInterval});

  VAlbumClient? _client;

  Timer? _timer;

  AppLifecycleListener? _lifecycle;

  /// How many entries are unread, as last answered.
  int count = 0;

  /// The entries as last answered in full, `null` before the first full
  /// answer.
  NewsInfo? news;

  /// Why the server could not be asked last, `null` while it answers.
  String? problem;

  bool _disposed = false;

  /// The client the bell asks, `null` where there is no bell.
  VAlbumClient? get client => _client;

  /// Whether there is a bell: the app is signed in as a member.
  bool get active => _client != null;

  /// Starts asking [client], or stops asking where it is `null`.
  void attach(VAlbumClient? client) {
    if (identical(client, _client)) {
      return;
    }
    _client = client;
    count = 0;
    news = null;
    problem = null;
    _timer?.cancel();
    _timer = null;
    if (client != null) {
      _timer = Timer.periodic(interval, (_) => poll());
      _lifecycle ??= AppLifecycleListener(onResume: poll);
      poll();
    }
    notifyListeners();
  }

  /// Asks for the number of unread entries, the one small request.
  Future<void> poll() async {
    var client = _client;
    if (client == null) {
      return;
    }
    try {
      var answer = await client.news(countOnly: true);
      if (_disposed || !identical(client, _client)) {
        return;
      }
      problem = null;
      if (answer.count != count) {
        count = answer.count;
        notifyListeners();
      }
    } catch (error) {
      // The badge keeps what it said; the next question may be answered.
      if (!_disposed && identical(client, _client)) {
        problem = refusalMessage(error);
      }
    }
  }

  /// Asks for every entry; marks nothing.
  Future<NewsInfo?> load() async {
    var client = _client;
    if (client == null) {
      return null;
    }
    try {
      var answer = await client.news();
      _take(client, answer);
      return answer;
    } catch (error) {
      if (!_disposed && identical(client, _client)) {
        problem = refusalMessage(error);
        notifyListeners();
      }
      rethrow;
    }
  }

  /// Marks the entries of [keys] as read — every entry where [all] — and
  /// takes the bell as the server answers it then.
  Future<void> markRead(
      {List<String> keys = const [], bool all = false}) async {
    var client = _client;
    if (client == null) {
      return;
    }
    var answer = await client.markNewsRead(keys: keys, all: all);
    _take(client, answer);
  }

  void _take(VAlbumClient client, NewsInfo answer) {
    if (_disposed || !identical(client, _client)) {
      return;
    }
    news = answer;
    count = answer.count;
    problem = null;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _timer?.cancel();
    _lifecycle?.dispose();
    super.dispose();
  }
}

/// Publishes the [NewsBell] of the app.
class NewsScope extends InheritedNotifier<NewsBell> {
  const NewsScope({super.key, required NewsBell bell, required super.child})
      : super(notifier: bell);

  /// The bell of the enclosing app, `null` where there is none (a view pumped
  /// on its own in a test).
  static NewsBell? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<NewsScope>()?.notifier;
}

/// The bell in an app bar: the number of unread entries, and the menu of
/// them on a tap. Nothing where the app is no member's.
class NewsBellButton extends StatelessWidget {
  const NewsBellButton({super.key});

  @override
  Widget build(BuildContext context) {
    var bell = NewsScope.maybeOf(context);
    if (bell == null || !bell.active) {
      return const SizedBox.shrink();
    }
    var count = bell.count;
    return IconButton(
      key: newsBellKey,
      tooltip: AppLocalizations.of(context)!.newsTooltip(count),
      onPressed: () => showNewsMenu(context, bell),
      icon: Badge.count(
        key: const Key("news-badge"),
        count: count,
        maxCount: 99,
        isLabelVisible: count > 0,
        child: Icon(
            count > 0 ? Icons.notifications_active : Icons.notifications_none),
      ),
    );
  }
}

/// Shows the menu of the entries of [bell]; opening it marks nothing.
Future<void> showNewsMenu(BuildContext context, NewsBell bell) {
  var navigator = VAlbumNavigator.of(context);
  return showAppDialog<void>(
    context: context,
    builder: (dialogContext) => NewsMenu(
      bell: bell,
      onOpen: (entry) {
        Navigator.of(dialogContext).pop();
        openNewsEntry(navigator, bell, entry);
      },
    ),
  );
}

/// Opens [entry]: marks it as read, and shows its photographs, see the
/// library comment.
void openNewsEntry(VAlbumNavigator navigator, NewsBell bell, NewsEntry entry) {
  navigator.delegate.newsSession(entry.id).open(entry);
  if (entry.unread) {
    // Visiting is reading; a failure leaves it unread, which the next visit
    // mends.
    bell.markRead(keys: [entry.id]).catchError((Object _) {});
  }
  navigator.go(NewsRoute(splitAlbumPath(entry.album), entry.id));
}

/// The folder names of the album path the server spells with `/`.
List<String> splitAlbumPath(String path) => [
      for (var segment in path.split("/"))
        if (segment.isNotEmpty) segment
    ];

/// The menu of the bell: the entries, the unread first, and "Mark all as
/// read".
class NewsMenu extends StatefulWidget {
  final NewsBell bell;

  /// Opens an entry.
  final void Function(NewsEntry entry) onOpen;

  const NewsMenu({super.key, required this.bell, required this.onOpen});

  @override
  State<NewsMenu> createState() => NewsMenuState();
}

class NewsMenuState extends State<NewsMenu> {
  /// Why the entries could not be loaded, `null` while they could.
  String? _problem;

  bool _loading = true;

  bool _marking = false;

  @override
  void initState() {
    super.initState();
    widget.bell.addListener(_changed);
    widget.bell.load().then((_) {
      if (mounted) {
        setState(() => _loading = false);
      }
    }, onError: (Object error) {
      if (mounted) {
        setState(() {
          _loading = false;
          _problem = refusalMessage(error);
        });
      }
    });
  }

  @override
  void dispose() {
    widget.bell.removeListener(_changed);
    super.dispose();
  }

  void _changed() {
    if (mounted) {
      setState(() {});
    }
  }

  Future<void> _markAll() async {
    setState(() => _marking = true);
    try {
      await widget.bell.markRead(all: true);
    } catch (error) {
      if (mounted) {
        setState(() => _problem = refusalMessage(error));
      }
    } finally {
      if (mounted) {
        setState(() => _marking = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var entries = widget.bell.news?.entries ?? const <NewsEntry>[];
    var client = widget.bell.client;
    Widget body;
    if (_problem != null) {
      body = _sentence(_problem!, const Key("news-problem"));
    } else if (_loading && widget.bell.news == null) {
      body = const Padding(
        padding: EdgeInsets.all(24),
        child: Center(child: CircularProgressIndicator()),
      );
    } else if (entries.isEmpty) {
      body = _sentence(l10n.newsNothing, const Key("news-nothing"));
    } else {
      var now = DateTime.now();
      body = Flexible(
        child: ListView.separated(
          shrinkWrap: true,
          itemCount: entries.length,
          separatorBuilder: (context, index) => const Divider(height: 1),
          itemBuilder: (context, index) =>
              _entry(context, l10n, client, entries[index], now),
        ),
      );
    }
    return Dialog(
      key: const Key("news-menu"),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 480, maxHeight: 640),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 8, 8),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      l10n.newsTitle,
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                  ),
                  TextButton(
                    key: newsMarkAllKey,
                    onPressed:
                        widget.bell.count == 0 || _marking ? null : _markAll,
                    child: Text(l10n.newsMarkAllRead),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            body,
          ],
        ),
      ),
    );
  }

  static Widget _sentence(String text, Key key) => Padding(
        padding: const EdgeInsets.all(24),
        child: Text(text, key: key, textAlign: TextAlign.center),
      );

  Widget _entry(BuildContext context, AppLocalizations l10n,
      VAlbumClient? client, NewsEntry entry, DateTime now) {
    var who = entry.who.trim().isEmpty ? l10n.newsSomebody : entry.who;
    var shown = entry.photos.take(newsStripLength).toList();
    var path = splitAlbumPath(entry.album);
    var weight = entry.unread ? FontWeight.bold : FontWeight.normal;
    return InkWell(
      key: newsEntryKey(entry.id),
      onTap: () => widget.onOpen(entry),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 16,
              child: entry.unread
                  ? Padding(
                      padding: const EdgeInsets.only(top: 6),
                      child: Icon(
                        Icons.circle,
                        key: newsUnreadKey(entry.id),
                        size: 10,
                        color: Theme.of(context).colorScheme.primary,
                      ),
                    )
                  : null,
            ),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text.rich(
                    TextSpan(
                      children: [
                        TextSpan(
                          text: who,
                          style: const TextStyle(fontWeight: FontWeight.bold),
                        ),
                        const TextSpan(text: " "),
                        TextSpan(
                          text:
                              l10n.newsAdded(entry.title, entry.photos.length),
                          style: TextStyle(fontWeight: weight),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    newsAge(l10n, now, entry.last),
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  if (client != null && shown.isNotEmpty) ...[
                    const SizedBox(height: 6),
                    Wrap(
                      spacing: 4,
                      runSpacing: 4,
                      children: [
                        for (var photo in shown)
                          SizedBox(
                            width: newsThumbnailSize,
                            height: newsThumbnailSize,
                            child: ClipRRect(
                              borderRadius: BorderRadius.circular(4),
                              child: ColoredBox(
                                color: Colors.black12,
                                child: thumbnail(
                                  client,
                                  "${client.folderUrl(path)}${photo.name}",
                                  width: newsThumbnailSize,
                                  height: newsThumbnailSize,
                                  fit: BoxFit.cover,
                                ),
                              ),
                            ),
                          ),
                      ],
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// How long ago [millis] was, from [now], in words.
String newsAge(AppLocalizations l10n, DateTime now, int millis) {
  var ago = now.difference(DateTime.fromMillisecondsSinceEpoch(millis));
  if (ago.inMinutes < 1) {
    return l10n.newsJustNow;
  }
  if (ago.inHours < 1) {
    return l10n.newsMinutesAgo(ago.inMinutes);
  }
  if (ago.inDays < 1) {
    return l10n.newsHoursAgo(ago.inHours);
  }
  return l10n.newsDaysAgo(ago.inDays);
}

/// An entry of the bell as it was opened, kept by the router under its key:
/// the photographs its view shows, decided once, when it was opened.
///
/// An unread entry shows its unread photographs alone — what the member has
/// not seen yet; a read one shows all of them. Marking it as read on the way
/// in must not change what the view shows, so the names are taken at the
/// moment it is opened. A view reached by its address (a reload, a bookmark)
/// asks the server for the entry.
class NewsSession {
  final String key;

  NewsSession(this.key);

  NewsEntry? _entry;

  List<String>? _names;

  Future<NewsEntry?>? _loading;

  /// The entry, `null` before it was opened or found.
  NewsEntry? get entry => _entry;

  /// The names the view shows, `null` before the entry was opened or found.
  List<String>? get names => _names;

  /// Takes [entry] as it is opened.
  void open(NewsEntry entry) {
    _entry = entry;
    _names = shownNames(entry);
    _loading = null;
  }

  /// The entry, asked of [client] where it was not opened from the menu;
  /// `null` where the server knows no entry of this key (any more).
  Future<NewsEntry?> find(VAlbumClient client) {
    var entry = _entry;
    if (entry != null) {
      return Future.value(entry);
    }
    return _loading ??= client.news().then((news) {
      for (var candidate in news.entries) {
        if (candidate.id == key) {
          open(candidate);
          return candidate;
        }
      }
      return null;
    }).whenComplete(() => _loading = null);
  }

  /// The names of the photographs the view of [entry] shows, in album order.
  static List<String> shownNames(NewsEntry entry) {
    var unread = [
      for (var photo in entry.photos)
        if (photo.unread) photo.name
    ];
    return unread.isNotEmpty
        ? unread
        : [for (var photo in entry.photos) photo.name];
  }
}

/// A copy of [album] holding the photographs of [names] alone, in its own
/// order, without its headings: the album of the view of an entry, which the
/// viewer pages through. A group is kept where any of its members is named.
///
/// A copy, never the album itself: the album beneath stays as it is, and the
/// copy's photographs are chained among themselves alone (`AlbumInitializer`).
AlbumInfo newsAlbumOf(
  AlbumInfo album,
  Iterable<String> names, {
  required String subTitle,
}) {
  var wanted = names.toSet();
  var copy =
      Resource.read(JsonReader.fromString(album.toString()))! as AlbumInfo;
  copy.parts = [
    for (var part in copy.parts)
      if ((part is ImagePart && wanted.contains(part.name)) ||
          (part is ImageGroup &&
              part.images.any((image) => wanted.contains(image.name))))
        part
  ];
  copy.subTitle = subTitle;
  // Its photographs chained among themselves alone: the viewer pages through
  // the entry, not through the album.
  AlbumInitializer().init(copy);
  return copy;
}
