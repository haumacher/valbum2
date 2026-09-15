/// Probe for the permission words of #85 slice 3 against what an older
/// server still answers: member, guest, an unknown role, and silence.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/caller.dart';
import 'package:valbum_ui/invitation.dart';
import 'package:valbum_ui/resource.dart';

CallerPermission perm(String role, {String clearance = "", bool mayShare = false}) =>
    CallerPermission.of(AuthInfo(role: role, clearance: clearance, mayShare: mayShare));

void main() {
  test("today's roles read as the new words, never as gibberish", () {
    expect(perm("member").phrase, startsWith("may edit the albums"));
    expect(perm("member").phrase, contains("may share links"));
    expect(perm("guest").phrase, startsWith("may look"));
    expect(perm("guest").phrase, contains("sees all but the private images"));
    expect(perm("admin").phrase, contains("sees all images"));
  });

  test('a headline for an unknown role promises nothing and ends cleanly', () {
    var known = invitationHeadline("alice", "edit");
    expect(known, contains("alice"));
    expect(known, endsWith("."));
    var unknown = invitationHeadline("alice", "wizard");
    expect(unknown, contains("alice"));
    expect(unknown, isNot(contains("wizard")));
    expect(unknown.trim(), endsWith("."));
    expect(unknown, isNot(contains("  ")));
    var silent = invitationHeadline("alice", "");
    expect(silent.trim(), endsWith("."));
  });

  test('the invitation defaults are the least that still shows the pictures', () {
    expect(defaultInviteRole, "view");
    expect(defaultInviteClearance, "nonPrivate");
  });
}
