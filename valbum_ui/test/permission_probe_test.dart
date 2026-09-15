/// Probe for the permission-aware offering (issue #85, slice 2): partial
/// server answers, today's server, and callers the server never named.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/caller.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/rights.dart';

CallerPermission perm(String role, {String clearance = "", bool mayShare = false}) =>
    CallerPermission.of(AuthInfo(role: role, clearance: clearance, mayShare: mayShare));

void main() {
  test('an answered partial right wins over any role, in both directions', () {
    var viewOnly = Rights.ofNames(["view"]);
    expect(offeredRights(viewOnly, perm("admin")).mayEdit, isFalse);
    expect(offeredRights(viewOnly, perm("admin")).mayContribute, isFalse);
    var full = Rights.ofNames(["view", "download", "contribute", "edit"]);
    expect(offeredRights(full, perm("view")).mayEdit, isTrue);
    // A folder that answered nothing at all under a view caller offers viewing only.
    expect(offeredRights(Rights.unanswered, perm("view")).mayContribute, isFalse);
    expect(offeredRights(Rights.unanswered, perm("contribute")).mayContribute, isTrue);
    expect(offeredRights(Rights.unanswered, perm("contribute")).mayEdit, isFalse);
  });

  test("today's server: a member keeps sharing, a guest is a viewer", () {
    var member = perm("member");
    expect(member.role, "edit");
    expect(member.mayShare, isTrue, reason: "silence about the flag is not a refusal");
    var guest = perm("guest");
    expect(guest.role, "view");
    expect(guest.clearance, "nonPrivate");
    var viewer = perm("view", mayShare: false);
    expect(viewer.mayShare, isFalse, reason: "the new vocabulary is believed");
  });

  test('nobody named: everything stays offered, refusals speak later', () {
    var nobody = perm("");
    expect(nobody.named, isFalse);
    expect(nobody.clearance, "public");
    expect(nobody.mayShare, isFalse);
    expect(offeredRights(Rights.unanswered, nobody).mayEdit, isTrue);
    // Unknown words are nobody, not admin.
    expect(perm("ADMIN").named, isFalse);
    expect(perm("owner").named, isFalse);
  });

  test('an admin who the server says may not share is still offered sharing (flagged for #83)', () {
    expect(perm("admin", mayShare: false).mayShare, isTrue);
  });
}
