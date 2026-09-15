/// Tests of the permission model of Phase 6 in the app (issue #85, second
/// slice): what the server says about the caller, what the settings say about
/// it in plain words, and what is offered where.
///
/// Two axes and a flag: the role says what may be *done*, the clearance what
/// may be *seen*, and `mayShare` whether links may be handed out. What a
/// request may do is still the per-folder rights the server answers with every
/// folder — where those are there they decide alone, and the role only steps
/// in where the server answered none.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/caller.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/rights.dart';

/// The answer of `?type=auth`, as the server writes it.
AuthInfo auth({
  String role = "",
  String clearance = "",
  bool mayShare = false,
  String userName = "carol",
}) =>
    AuthInfo(
      mode: "writes",
      deviceName: "Phone",
      writeAllowed: true,
      userName: userName,
      role: role,
      space: userName,
      clearance: clearance,
      mayShare: mayShare,
    );

/// A folder carrying the given rights, as the server answers them.
AlbumInfo albumWith(List<String> rights) => AlbumInfo(
      rights: [for (var name in rights) RightName(name: name)],
    );

void main() {
  group('the role the server answers', () {
    test('is taken as it comes in the vocabulary of Phase 6', () {
      expect(CallerPermission.of(auth(role: "admin")).role, roleAdmin);
      expect(CallerPermission.of(auth(role: "edit")).role, roleEdit);
      expect(
        CallerPermission.of(auth(role: "contribute")).role,
        roleContribute,
      );
      expect(CallerPermission.of(auth(role: "view")).role, roleView);
    });

    test('reads the old names as the new ones, for one release', () {
      // A server that has not been updated yet still answers these.
      expect(CallerPermission.of(auth(role: "member")).role, roleEdit);
      expect(CallerPermission.of(auth(role: "guest")).role, roleView);
    });

    test('is empty where nobody was named, and for anything unknown', () {
      expect(CallerPermission.of(auth()).role, "");
      expect(CallerPermission.of(auth(role: "wizard")).role, "");
      expect(CallerPermission.of(auth()).named, isFalse);
      expect(CallerPermission.unknown.named, isFalse);
    });

    test('says what may be done', () {
      expect(CallerPermission.of(auth(role: "admin")).mayEdit, isTrue);
      expect(CallerPermission.of(auth(role: "edit")).mayEdit, isTrue);
      expect(CallerPermission.of(auth(role: "member")).mayEdit, isTrue);
      expect(CallerPermission.of(auth(role: "contribute")).mayEdit, isFalse);
      expect(
        CallerPermission.of(auth(role: "contribute")).mayContribute,
        isTrue,
      );
      expect(CallerPermission.of(auth(role: "view")).mayContribute, isFalse);
      expect(CallerPermission.of(auth(role: "guest")).mayContribute, isFalse);
    });
  });

  group('the clearance', () {
    test('is taken as it comes', () {
      expect(
        CallerPermission.of(auth(role: "view", clearance: "all")).clearance,
        clearanceAll,
      );
      expect(
        CallerPermission.of(auth(role: "edit", clearance: "public")).clearance,
        clearancePublic,
      );
      expect(
        CallerPermission.of(auth(role: "edit", clearance: "nonPrivate"))
            .clearance,
        clearanceNonPrivate,
      );
    });

    test('is what the role implies where the server said nothing', () {
      // The admin sees everything ...
      expect(CallerPermission.of(auth(role: "admin")).clearance, clearanceAll);
      // ... anybody the server named sees all but the private images ...
      for (var role in const ["edit", "contribute", "view", "member", "guest"]) {
        expect(
          CallerPermission.of(auth(role: role)).clearance,
          clearanceNonPrivate,
          reason: role,
        );
      }
      // ... and a caller nobody named sees what is public.
      expect(CallerPermission.of(auth()).clearance, clearancePublic);
    });

    test('says what is seen', () {
      var all = CallerPermission.of(auth(role: "view", clearance: "all"));
      expect(all.seesPrivate, isTrue);
      expect(all.seesMembers, isTrue);
      var members = CallerPermission.of(auth(role: "view"));
      expect(members.seesPrivate, isFalse);
      expect(members.seesMembers, isTrue);
      var public =
          CallerPermission.of(auth(role: "view", clearance: "public"));
      expect(public.seesPrivate, isFalse);
      expect(public.seesMembers, isFalse);
    });
  });

  group('sharing', () {
    test('is what a Phase 6 server says, admin always', () {
      expect(
        CallerPermission.of(auth(role: "edit", mayShare: true)).mayShare,
        isTrue,
      );
      expect(
        CallerPermission.of(auth(role: "edit", mayShare: false)).mayShare,
        isFalse,
      );
      expect(
        CallerPermission.of(auth(role: "view", mayShare: false)).mayShare,
        isFalse,
      );
      // The admin hands out what everybody else may only be given.
      expect(
        CallerPermission.of(auth(role: "admin", mayShare: false)).mayShare,
        isTrue,
      );
    });

    test('is not refused by a server that does not know the field', () {
      // `member` and `guest` are the old vocabulary: such a server never sets
      // `mayShare`, and its silence must not be read as "no" — the per-folder
      // rights decide there, as they always did.
      expect(CallerPermission.of(auth(role: "member")).mayShare, isTrue);
      expect(CallerPermission.of(auth(role: "guest")).mayShare, isTrue);
      expect(CallerPermission.statesPermissions("member"), isFalse);
      expect(CallerPermission.statesPermissions("edit"), isTrue);
    });

    test('is nothing at all where nobody was named', () {
      expect(CallerPermission.of(auth()).mayShare, isFalse);
      expect(CallerPermission.unknown.mayShare, isFalse);
    });
  });

  group('the sentence the settings show', () {
    test('says what may be done, what is seen, and whether links may go out',
        () {
      expect(
        CallerPermission.of(auth(role: "edit", clearance: "all", mayShare: true))
            .sentence,
        "You may edit every album of this space; you see all images; "
        "you may share links.",
      );
      expect(
        CallerPermission.of(auth(role: "contribute", clearance: "nonPrivate"))
            .sentence,
        "You may add photos to this space; you see all but the private "
        "images; you may not share links.",
      );
      expect(
        CallerPermission.of(auth(role: "view", clearance: "public")).sentence,
        "You may look at this space; you see the public images; you may not "
        "share links.",
      );
      expect(
        CallerPermission.of(auth(role: "admin")).sentence,
        "You manage this server; you see all images; you may share links.",
      );
    });
  });

  group('what the app offers', () {
    test('is what the folder\'s rights say, wherever the server said them', () {
      // The rights win, both ways: nothing more is offered than they allow ...
      expect(
        offeredRights(
          Rights.of(albumWith(const [rightView])),
          CallerPermission.of(auth(role: "admin")),
        ).mayEdit,
        isFalse,
      );
      // ... and nothing they allow is hidden.
      expect(
        offeredRights(
          Rights.of(albumWith(const [rightEdit])),
          CallerPermission.of(auth(role: "view")),
        ).mayEdit,
        isTrue,
      );
    });

    test('follows the role where the server answered no rights at all', () {
      var none = Rights.of(AlbumInfo());
      expect(none.answered, isFalse, reason: "nobody said anything");

      var viewer = offeredRights(none, CallerPermission.of(auth(role: "view")));
      expect(viewer.mayView, isTrue);
      expect(viewer.mayContribute, isFalse);
      expect(viewer.mayEdit, isFalse);

      var contributor =
          offeredRights(none, CallerPermission.of(auth(role: "contribute")));
      expect(contributor.mayContribute, isTrue);
      expect(contributor.mayEdit, isFalse);

      var editor = offeredRights(none, CallerPermission.of(auth(role: "edit")));
      expect(editor.mayEdit, isTrue);
      expect(
        offeredRights(none, CallerPermission.of(auth(role: "admin"))).mayEdit,
        isTrue,
      );
    });

    test('offers everything where nobody said anything at all', () {
      // No rights, no role: exactly as the app behaved before any of this
      // existed. Nothing is hidden on a guess; a refusal speaks when it comes.
      var offered = offeredRights(
        Rights.of(AlbumInfo()),
        CallerPermission.unknown,
      );
      expect(offered.mayEdit, isTrue);
      expect(offered.complete, isTrue);
    });

    test('keeps the rights of a folder the server described', () {
      var answered = Rights.of(albumWith(const [rightContribute]));
      expect(answered.answered, isTrue);
      expect(answered.mayContribute, isTrue);
      expect(answered.mayEdit, isFalse);
      // The role changes nothing about it.
      expect(
        offeredRights(answered, CallerPermission.of(auth(role: "edit")))
            .mayEdit,
        isFalse,
      );
    });
  });

  group('the caller published to the views', () {
    test('carries the permission the server answered', () {
      var caller = CallerInfo.of(
        auth(role: "contribute", clearance: "all", mayShare: true),
      );

      expect(caller.role, "contribute");
      expect(caller.clearance, "all");
      expect(caller.mayShare, isTrue);
      expect(caller.permission.mayContribute, isTrue);
      expect(caller.permission.mayEdit, isFalse);
      expect(caller.permission.seesPrivate, isTrue);
    });

    test('tells two permissions apart', () {
      expect(
        CallerInfo.of(auth(role: "edit")),
        CallerInfo.of(auth(role: "edit")),
      );
      expect(
        CallerInfo.of(auth(role: "edit")) ==
            CallerInfo.of(auth(role: "edit", mayShare: true)),
        isFalse,
      );
      expect(
        CallerInfo.of(auth(role: "edit")) ==
            CallerInfo.of(auth(role: "edit", clearance: "all")),
        isFalse,
      );
    });
  });
}
