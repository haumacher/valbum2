/// What a person is called, see issue #146.
///
/// A [Person] carries two names: the canonical [Person.name] they are
/// *identified* by — unique in the space, what `?type=users` answers — and the
/// optional [Person.nickname] they are *called* on a photograph. Both are
/// typed into one field with the convention `Berta Müller (Tante Berta)` and
/// split by the server, which is the only place that rule lives; the app only
/// has to decide which of the two to put on the screen.
///
/// That decision is these three functions and nothing else:
///
///  * [displayName] where a person is simply named — a heading of the face
///    editor, the tooltip of the viewer, the suggestion "Is this …?";
///  * [fullLabel] where a person is identified or edited — the second line of
///    a chooser, the prefill of the rename dialog, which is why the round trip
///    keeps both halves and deleting the bracket clears the nickname;
///  * [disambiguate] where several people are shown side by side, because a
///    nickname need not be unique: two grandmothers are both "Oma", and a list
///    that says "Oma" twice says nothing.
///
/// None of these is a sentence, so none of them needs a translation: a name is
/// a name in every language.
library;

import 'resource.dart';

/// What to write under a face: the nickname where there is one, else the name.
String displayName(Person person) {
  var nickname = person.nickname.trim();
  return nickname.isEmpty ? person.name.trim() : nickname;
}

/// The whole statement about a person: `<name> (<nickname>)`, or just the name.
///
/// Exactly what the name field takes, so the rename dialog is prefilled with
/// it and whatever comes back is parsed by the server into the two halves it
/// was composed from.
String fullLabel(Person person) {
  var name = person.name.trim();
  var nickname = person.nickname.trim();
  return nickname.isEmpty ? name : "$name ($nickname)";
}

/// What each of the given people is called where they are shown together.
///
/// The [displayName] normally, and the [fullLabel] for *both* of two people
/// whose display names are the same ignoring case — the canonical name is
/// unique, so saying it is always enough to tell them apart. Keyed by
/// [Person.id]; a person that is not in the list is not in the answer.
Map<String, String> disambiguate(Iterable<Person> people) {
  var seen = <String, int>{};
  for (var person in people) {
    var key = displayName(person).toLowerCase();
    seen[key] = (seen[key] ?? 0) + 1;
  }
  return {
    for (var person in people)
      person.id: (seen[displayName(person).toLowerCase()] ?? 0) > 1
          ? fullLabel(person)
          : displayName(person),
  };
}
