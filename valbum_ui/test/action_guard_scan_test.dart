/// The two rules of `action_guard.dart` (issue #293), kept by reading the
/// source, as `dialog_style_test.dart` keeps its own:
///
///  * a user's write to the server goes through `writeAction` — refused
///    offline, its refusal said in the server's words — never straight to the
///    client from a view;
///  * what a view offers is asked of `rightsOf`, never pieced together from
///    the raw `Rights.of` and `offeredRights`.
library;

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// The client's methods that change something on the server at a user's
/// asking. The sign-in handshakes (`pair`, `identify`, `proveEmail`,
/// `verifyEmail`, `oidcStart`, `oidcExchange`, `totpVerify`, `passkeyStart`,
/// `passkeyVerify`, `totpSetup`, `passkeyRegisterStart`) are not among them:
/// they are how an app that cannot reach the server finds its way back to
/// it, change nothing of the space, and show their failure in their own form.
const List<String> writeMethods = [
  "putResource",
  "saveAlbum",
  "saveListing",
  "createAlbum",
  "uploadFiles",
  "uploadNew",
  "move",
  "collect",
  "delete",
  "place",
  "reanalyze",
  "refreshCache",
  "purge",
  "share",
  "unshare",
  "resend",
  "shutOut",
  "editShare",
  "removeRecipient",
  "blockContact",
  "renameContact",
  "endContactSession",
  "deleteContact",
  "endOtherSessions",
  "endOwnSession",
  "overviewLink",
  "totpConfirm",
  "passkeyRegister",
  "removeSignIn",
  "removeUserSignIn",
  "removeContactSignIn",
  "saveSpaceSettings",
  "markNewsRead",
  "createPerson",
  "renamePerson",
  "mergePersons",
  "linkPerson",
  "tagFaces",
  "cropImage",
  "posterVideo",
  "adjustFaces",
  "relabel",
  "unpair",
  "deviceCode",
  "backupCode",
  "revokeBackupCode",
  "setPermission",
  "removeUser",
  "invite",
  "uninvite",
];

/// The writes that are no user's write, or whose flow cannot be the one of
/// `writeAction`, by file: each with its reason.
const Map<String, Map<String, String>> writeExceptions = {
  "camera_roll.dart": {
    "uploadNew": "the background sync of the camera roll: nobody asked, and "
        "an offline app simply does not send",
  },
  "news.dart": {
    "markNewsRead": "the bell's \"seen\" mark, written as the entries are "
        "looked at, not asked for",
  },
  "app.dart": {
    "uploadNew": "the upload flow (#59, #63): refused offline before the "
        "progress dialog opens, and a failure is told by what arrived",
    "endOwnSession": "\"Switch person\" (#202): the credential is forgotten "
        "even where the server cannot be asked — the visitor is never stuck",
  },
  "shared_overview.dart": {
    "endOwnSession": "\"Switch person\" (#202), as in app.dart",
  },
};

/// The functions of a file that wrap `writeAction` themselves, so that a
/// write handed to them is guarded: each must call `writeAction`, see the
/// test below.
const Map<String, List<String>> writeWrappers = {
  "shares_view.dart": ["_run"],
  "sign_in_options.dart": ["_run"],
  "manage_view.dart": ["_act"],
};

/// The source of a file with comments and string contents blanked, so that
/// neither a word in a comment nor a parenthesis in a string counts; the
/// offsets are those of the original.
String blankComments(String source) {
  var out = StringBuffer();
  var i = 0;
  while (i < source.length) {
    var c = source[i];
    if (source.startsWith("//", i)) {
      while (i < source.length && source[i] != "\n") {
        out.write(" ");
        i++;
      }
    } else if (source.startsWith("/*", i)) {
      var end = source.indexOf("*/", i + 2);
      end = end < 0 ? source.length : end + 2;
      for (; i < end; i++) {
        out.write(source[i] == "\n" ? "\n" : " ");
      }
    } else if (c == '"' || c == "'") {
      var triple = source.startsWith(c * 3, i);
      var quote = triple ? c * 3 : c;
      out.write(quote);
      i += quote.length;
      while (i < source.length && !source.startsWith(quote, i)) {
        if (source[i] == "\\" && i + 1 < source.length) {
          out.write("  ");
          i += 2;
          continue;
        }
        out.write(source[i] == "\n" ? "\n" : " ");
        i++;
      }
      out.write(quote);
      i += quote.length;
    } else {
      out.write(c);
      i++;
    }
  }
  return out.toString();
}

/// The offset of the bracket closing the one at [open] in [code].
int closing(String code, int open) {
  var opening = code[open];
  var close = opening == "(" ? ")" : "}";
  var depth = 0;
  for (var i = open; i < code.length; i++) {
    if (code[i] == opening) {
      depth++;
    } else if (code[i] == close) {
      depth--;
      if (depth == 0) {
        return i;
      }
    }
  }
  return code.length;
}

/// A call of the client's write [method] in view code.
final RegExp clientWrite = RegExp(
  r"\b(?:\w*[cC]lient!?|speaker|_anonymous)\s*\.\s*("
  "${writeMethods.join("|")}"
  r")\s*\(",
);

/// The writes of [source] that no guard of [guards] encloses, as
/// `line: method`, minus those [allowed].
List<String> unguardedWrites(
  String source, {
  List<String> guards = const ["writeAction"],
  Set<String> allowed = const {},
}) {
  var code = blankComments(source);
  var ranges = <(int, int)>[];
  var call = RegExp(r"\b(" + guards.join("|") + r")\s*(<[^>(]*>)?\s*\(");
  for (var match in call.allMatches(code)) {
    var open = match.end - 1;
    ranges.add((open, closing(code, open)));
  }
  return [
    for (var match in clientWrite.allMatches(code))
      if (!allowed.contains(match.group(1)) &&
          !ranges.any((r) => r.$1 < match.start && match.start < r.$2))
        "${"\n".allMatches(code.substring(0, match.start)).length + 1}: "
            "${match.group(1)}",
  ];
}

/// The places of [source] reading the rights raw, as line numbers.
List<int> rawRights(String source) {
  var code = blankComments(source);
  var raw = RegExp(r"\b(Rights\.of|offeredRights)\s*\(");
  return [
    for (var match in raw.allMatches(code))
      "\n".allMatches(code.substring(0, match.start)).length + 1,
  ];
}

/// The files of `lib/` that are view code: everything but the client (and its
/// extension `cache_refresh.dart`), the generated model and the translations.
Iterable<File> viewFiles() sync* {
  const notViews = {"client.dart", "cache_refresh.dart", "resource.dart"};
  for (var entry in Directory("lib").listSync(recursive: true)) {
    if (entry is File &&
        entry.path.endsWith(".dart") &&
        !entry.uri.pathSegments.contains("l10n") &&
        !notViews.contains(nameOf(entry))) {
      yield entry;
    }
  }
}

String nameOf(File file) => file.uri.pathSegments.last;

void main() {
  test('every write of a view goes through writeAction', () {
    var offenders = <String>[];
    for (var file in viewFiles()) {
      var name = nameOf(file);
      for (var place in unguardedWrites(
        file.readAsStringSync(),
        guards: ["writeAction", ...?writeWrappers[name]],
        allowed: writeExceptions[name]?.keys.toSet() ?? const {},
      )) {
        offenders.add("${file.path}:$place");
      }
    }
    expect(offenders, isEmpty,
        reason: "send a user's write with writeAction (action_guard.dart), "
            "or name it in writeExceptions with the reason it is none");
  });

  test('each wrapper of writeAction calls it', () {
    writeWrappers.forEach((name, wrappers) {
      var code = blankComments(File("lib/$name").readAsStringSync());
      for (var wrapper in wrappers) {
        // The declaration: the name, its parameters (bracket-matched, they
        // may hold a function type), and the body that follows them.
        int? open;
        for (var match in RegExp(r"\b" + RegExp.escape(wrapper) +
                r"\s*(<[^>(]*>)?\s*\(")
            .allMatches(code)) {
          var parameters = closing(code, match.end - 1);
          var body = RegExp(r"\s*(async\s*)?\{")
              .matchAsPrefix(code, parameters + 1);
          if (body != null) {
            open = body.end - 1;
            break;
          }
        }
        expect(open, isNotNull, reason: "$name declares $wrapper");
        var body = code.substring(open!, closing(code, open));
        expect(body.contains("writeAction"), isTrue,
            reason: "$wrapper of $name calls writeAction");
      }
    });
  });

  test('every exception names a write the file really sends', () {
    writeExceptions.forEach((name, methods) {
      var code = blankComments(File("lib/$name").readAsStringSync());
      for (var method in methods.keys) {
        expect(writeMethods, contains(method));
        expect(
            clientWrite
                .allMatches(code)
                .any((match) => match.group(1) == method),
            isTrue,
            reason: "$name still sends $method raw");
      }
    });
  });

  test('the rights are asked of rightsOf alone', () {
    var offenders = [
      for (var file in viewFiles())
        if (nameOf(file) != "rights.dart" &&
            nameOf(file) != "action_guard.dart")
          for (var line in rawRights(file.readAsStringSync()))
            "${file.path}:$line",
    ];
    expect(offenders, isEmpty,
        reason: "ask rightsOf (action_guard.dart) what a view offers");
  });

  test('the write scan sees an unguarded write', () {
    expect(
      unguardedWrites("""
Future<void> f() async {
  await widget.client.unpair(device.id);
}
"""),
      ["2: unpair"],
    );
    expect(
      unguardedWrites("""
Future<void> f() async {
  var answer = await client
      .removeSignIn(method, id: id);
}
"""),
      ["2: removeSignIn"],
    );
    expect(
      unguardedWrites("""
Future<void> f() async {
  var done = await writeAction(context, () => client.createPerson(name));
  var other = await writeAction<Person>(context, () async {
    return await widget.client!.renamePerson(id, "a (b");
  });
}
"""),
      isEmpty,
    );
    // A write in a comment is none, and a file's own `move` is no client's.
    expect(
      unguardedWrites("""
// client.unpair(id) is what this would do.
void f() => highlight.move(1);
"""),
      isEmpty,
    );
    expect(
      unguardedWrites(
        "Future<void> f() => _run(() => widget.client.unshare(p, id));",
        guards: ["writeAction", "_run"],
      ),
      isEmpty,
    );
    expect(
      unguardedWrites("void f() => client.markNewsRead(all: true);",
          allowed: {"markNewsRead"}),
      isEmpty,
    );
  });

  test('the rights scan sees a raw reading', () {
    expect(rawRights("Rights get rights => Rights.of(album);"), [1]);
    expect(rawRights("var r =\n  offeredRights(Rights.of(a), p);"), [2, 2]);
    expect(rawRights("var r = rightsOf(context, album);"), isEmpty);
    expect(rawRights("/// See [Rights.of] and offeredRights(x)."), isEmpty);
  });
}
