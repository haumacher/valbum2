/// The register of the people of a space, held for the lifetime of a client.
///
/// A [FaceInfo] carries the *id* of the person it was confirmed as, never the
/// name: an id is stable and a name is editable, and a merge answers the
/// surviving id (see issue #125). Whoever wants to write a name under a face
/// therefore has to know the register, which is one space-level request —
/// `?type=people`, [VAlbumClient.people].
///
/// The face editor of issue #126 asks for it when it is opened, because that
/// is what it is for. The viewer of issue #145 only names the person under the
/// mouse, so it asks **lazily**: the register is fetched the first time a
/// viewer of a client shows a confirmed face, and then held for the lifetime
/// of that client — one request for a whole session of paging through albums.
///
/// A refusal is no error here. The server governs who is told about a face at
/// all (`Faces.maySee`), and for a caller it refuses — a share link with 403,
/// an anonymous visitor with 401 — this is bookkeeping and not content: the
/// failure is remembered like an answer, so the register stays empty, no
/// tooltip is shown, nothing is said and nothing is asked a second time.
library;

import 'client.dart';
import 'resource.dart';

/// The people of a space, by the id a [FaceInfo.person] names them with.
///
/// One instance per [VAlbumClient], see [PeopleRegistry.of]: the register
/// belongs to the server and the token, which is exactly what a client is.
class PeopleRegistry {
  /// The registers built so far, keyed by their client.
  ///
  /// An [Expando] rather than a field, so that the transport stays what it is
  /// — a transport — and a client that is dropped takes its register with it.
  static final Expando<PeopleRegistry> _byClient = Expando<PeopleRegistry>();

  /// The register of the given client, created on first use.
  static PeopleRegistry of(VAlbumClient client) =>
      _byClient[client] ??= PeopleRegistry._(client);

  final VAlbumClient _client;

  PeopleRegistry._(this._client);

  /// What was loaded, empty while nothing was and after a refusal.
  Map<String, Person> _people = const {};

  /// The one running or finished load, `null` while nobody asked.
  Future<Map<String, Person>>? _loading;

  /// Whether the register was asked for already, successfully or not.
  bool get asked => _loading != null;

  /// The register as it stands, without asking for it.
  Map<String, Person> get people => _people;

  /// The person of the given id, `null` where the register does not know them.
  ///
  /// An empty id is nobody: a face nobody decided about carries one.
  Person? operator [](String id) => id.isEmpty ? null : _people[id];

  /// Loads the register once and answers it, see the library doc.
  ///
  /// Replaced, never merged — the answer of the server is the register — and
  /// asked exactly once per client: the memoised future is what a second
  /// caller waits for, and a refusal is memoised with it.
  Future<Map<String, Person>> load() => _loading ??= _fetch();

  Future<Map<String, Person>> _fetch() async {
    Map<String, Person> loaded;
    try {
      var answer = await _client.people();
      loaded = <String, Person>{};
      for (var person in answer.people) {
        loaded[person.id] = person;
        // A face read before a merge still names the id that was merged away,
        // and the server answers the aliases so that a client can recognise
        // it, see `Person.aliases`.
        for (var alias in person.aliases) {
          loaded[alias.id] = person;
        }
      }
    } catch (_) {
      // Refused (403 for a link, 401 for an anonymous caller) or unreachable:
      // there are no names to show, and asking again would be a retry storm
      // over something nobody asked for.
      loaded = const {};
    }
    _people = loaded;
    return loaded;
  }
}
