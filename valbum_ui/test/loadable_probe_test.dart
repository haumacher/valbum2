/// Probe of [Loadable]: what the caller wrote in itself is never overwritten
/// by an answer that was asked before it.
library;

import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/loadable.dart';

void main() {
  test('a replace during a request is not undone by its older answer',
      () async {
    var answer = Completer<List<String>>();
    var people = Loadable<List<String>>(() => answer.future);
    var asked = people.load();

    // A person created meanwhile (the caller knows the server has it).
    people.replace(["Anne", "Bob"]);
    answer.complete(["Anne"]);
    await asked;
    await Future<void>.delayed(Duration.zero);

    expect(people.value, ["Anne", "Bob"],
        reason: "the older answer does not drop the person just created");
  });

  test('a failure of a request asked before a replace leaves the value',
      () async {
    var answer = Completer<List<String>>();
    var people = Loadable<List<String>>(() => answer.future);
    var asked = people.load();
    people.replace(["Anne"]);
    answer.completeError(StateError("gone"));
    await asked.catchError((_) => <String>[]);
    await Future<void>.delayed(Duration.zero);
    expect(people.value, ["Anne"]);
    expect(people.error, isNull);
  });
}
