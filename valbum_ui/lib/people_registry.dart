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
/// refusal is remembered as the answer, so no tooltip is shown, nothing is
/// said and nothing is asked a second time by itself. A chooser opened over
/// it says the refusal, and its Retry asks again.
///
/// Any other failure (the server down, a 500) is remembered as a failure,
/// never as an empty register (issue #291): an empty register lets a chooser
/// say "Nobody yet" and create a person the space already knows. The next
/// [PeopleRegistry.load] asks again.
library;

import 'client.dart';
import 'loadable.dart';
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

  PeopleRegistry._(VAlbumClient client)
      : persons = Loadable<List<Person>>(
          () async => (await client.people()).people,
          keepsFailure: isRefusal,
        ) {
    persons.addListener(_index);
  }

  /// Whether [error] is the refusal of a caller who may not see persons,
  /// see the library doc.
  static bool isRefusal(Object error) =>
      error is VAlbumException && (error.status == 401 || error.status == 403);

  /// The persons of the register, each of them once, as a chooser offers
  /// them: waiting while they are on their way, failed where they failed.
  final Loadable<List<Person>> persons;

  /// What was loaded, by every id a face may name, empty while nothing was
  /// and after a failure.
  Map<String, Person> _people = const {};

  /// The register as it stands, without asking for it.
  Map<String, Person> get people => _people;

  /// The person of the given id, `null` where the register does not know them.
  ///
  /// An empty id is nobody: a face nobody decided about carries one.
  Person? operator [](String id) => id.isEmpty ? null : _people[id];

  /// Loads the register and answers it, see the library doc.
  ///
  /// Replaced, never merged — the answer of the server is the register — and
  /// asked once per client: a second caller waits for the same answer, and a
  /// refusal is the answer. Another failure fails this future, and the next
  /// call asks again.
  Future<Map<String, Person>> load() async {
    await persons.load();
    return _people;
  }

  /// Writes a person the register does not know yet into it (issue #147).
  ///
  /// A person created while a face is being named is a person of the space
  /// from that moment on, and the label under the face has to be written at
  /// once; asking the whole register again for one name it just handed out
  /// would be a second request for something this client already holds.
  void remember(Person person) {
    if (persons.hasValue) {
      persons.replace([
        for (var known in persons.value!)
          if (known.id != person.id) known,
        person,
      ]);
    } else {
      // Nothing was loaded: the label is written all the same, and the
      // register the server answers later has the person in it.
      _people = {..._people};
      _put(_people, person);
    }
  }

  /// Rebuilds the index by id from what arrived.
  void _index() {
    if (!persons.hasValue) {
      return;
    }
    var index = <String, Person>{};
    for (var person in persons.value!) {
      _put(index, person);
    }
    _people = index;
  }

  static void _put(Map<String, Person> index, Person person) {
    index[person.id] = person;
    // A face read before a merge still names the id that was merged away,
    // and the server answers the aliases so that a client can recognise it,
    // see `Person.aliases`.
    for (var alias in person.aliases) {
      index[alias.id] = person;
    }
  }
}
