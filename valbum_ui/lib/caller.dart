/// Who the app is signed in as, published to every view, see issue #52.
///
/// The rights of issue #49 say what the caller may do with *one folder*, and
/// the server answers them with every folder, so the app never has to ask. A
/// guest's root is the one thing the rights do not describe: a guest holds
/// every right in their own root — they rearrange and decline the links others
/// shared with them there — and yet a photo, an album and a folder of their
/// own are refused there by *role*, see `AuthService.GUEST_SPACE_REFUSED`.
///
/// So the role has to reach the views, and it is asked once per client rather
/// than once per folder: the answer of `?type=auth` is the same for every
/// request the app makes, and the app already has to ask it in a link session.
/// A caller the server did not name (an anonymous one, a server that could not
/// be reached) is `null` here, and everything then behaves exactly as it did
/// before this existed.
library;

import 'package:flutter/widgets.dart';

import 'l10n/app_localizations.dart';
import 'resource.dart';

/// The role of the owner of a library, who invites and manages.
const String roleAdmin = "admin";

/// The role of somebody with a library of their own on this server.
const String roleMember = "member";

/// The role of somebody whose library is what others share with them.
const String roleGuest = "guest";

/// The role of somebody who may change every album of their space (issue #85).
///
/// The Phase 6 model has four roles and no `member`/`guest` any more: `admin`,
/// `edit`, `contribute`, `view`. For one release a server may still answer the
/// old names, and they are read as the new ones, see [CallerPermission.of].
const String roleEdit = "edit";

/// The role of somebody who may add photos but change nothing (issue #85).
const String roleContribute = "contribute";

/// The role of somebody who may look and nothing else (issue #85).
const String roleView = "view";

/// Where a position is shown when the server names no map, see issue #112.
///
/// A URL template carrying `{lat}` and `{lon}`. It is the very default the
/// server applies for a space whose `space.json` names none
/// (`SpaceStore.DEFAULT_MAP_URL`), repeated here for the one case the server
/// cannot cover: a server built before the field existed, which answers
/// nothing at all.
const String defaultMapUrl = "https://www.google.com/maps?q={lat},{lon}";

/// The clearance of somebody who sees only what is public (issue #85).
const String clearancePublic = "public";

/// The clearance of somebody who sees everything but the private images.
const String clearanceNonPrivate = "nonPrivate";

/// The clearance of somebody who sees every image, private ones included.
const String clearanceAll = "all";

/// What the caller may do and see on this server, as the permission model of
/// Phase 6 states it (issues #82/#83/#85).
///
/// Two axes and a flag: the **role** says what may be *done* (look, add,
/// change, manage), the **clearance** what may be *seen* (the privacy levels
/// of issue #46), and [mayShare] whether links may be handed out. It is the
/// server's word, normalised here once so that no view has to know which
/// spelling arrived.
///
/// This is what the app *offers*. What a request may do is still the per-folder
/// rights the server answers with every folder, see [Rights] — the two agree on
/// a Phase 6 server, and where they do not, the rights win, see
/// `offeredRights`.
@immutable
class CallerPermission {
  /// The role, always one of [roleAdmin], [roleEdit], [roleContribute],
  /// [roleView] — or empty, where the server named no caller at all.
  final String role;

  /// The clearance, always one of [clearanceAll], [clearanceNonPrivate],
  /// [clearancePublic].
  final String clearance;

  /// Whether this caller may create share links.
  final bool mayShare;

  const CallerPermission({
    this.role = "",
    this.clearance = clearancePublic,
    this.mayShare = false,
  });

  /// What nobody said: an anonymous caller, or a server that never answered.
  static const CallerPermission unknown = CallerPermission();

  /// The permission the given answer of `?type=auth` describes.
  ///
  /// Three mappings, each of them the server's business made explicit here:
  ///
  ///  * the **role** is taken as it comes, except that the old `member` is the
  ///    new [roleEdit] and the old `guest` is [roleView] — for one release both
  ///    spellings are in the field, and a view must not have to know that;
  ///  * an empty **clearance** is what the role implies: the admin sees
  ///    everything, anybody else the server named sees everything but the
  ///    private images, and a caller nobody named sees what is public;
  ///  * **mayShare** is what the server said, and always true for the admin,
  ///    who hands out what everybody else may only be given — but a server
  ///    that does not know the field at all says nothing rather than "no", see
  ///    [statesPermissions]: silence is not a refusal, and on such a server
  ///    the per-folder rights decide as they always did.
  factory CallerPermission.of(AuthInfo info) => CallerPermission.ofFields(
        role: info.role,
        clearance: info.clearance,
        mayShare: info.mayShare,
      );

  /// The permission of the given raw fields, see [CallerPermission.of].
  factory CallerPermission.ofFields({
    String role = "",
    String clearance = "",
    bool mayShare = false,
  }) {
    var named = normalizeRole(role);
    return CallerPermission(
      role: named,
      clearance: normalizeClearance(clearance, named),
      mayShare: named.isEmpty
          ? false
          : mayShare || named == roleAdmin || !statesPermissions(role),
    );
  }

  /// Whether the role [name] is one only a Phase 6 server answers.
  ///
  /// `edit`, `contribute` and `view` are the new vocabulary, and a server that
  /// speaks it also states [mayShare] and the [clearance]. `member`, `guest`
  /// — and `admin`, which is in both vocabularies — say nothing about either,
  /// so their silence must not be read as a refusal, see
  /// [CallerPermission.of].
  static bool statesPermissions(String name) => switch (name.trim()) {
        roleEdit || roleContribute || roleView => true,
        _ => false,
      };

  /// The role of [name] in the spelling of Phase 6, empty for anything the
  /// app does not know.
  static String normalizeRole(String name) => switch (name.trim()) {
        roleAdmin => roleAdmin,
        roleEdit || roleMember => roleEdit,
        roleContribute => roleContribute,
        roleView || roleGuest => roleView,
        _ => "",
      };

  /// The clearance of [name], or the one [role] implies where it is empty.
  static String normalizeClearance(String name, String role) =>
      switch (name.trim()) {
        clearanceAll => clearanceAll,
        clearanceNonPrivate => clearanceNonPrivate,
        clearancePublic => clearancePublic,
        // Nothing said: the admin sees everything, anybody the server named
        // sees everything but the private images, a stranger sees the public.
        _ => role == roleAdmin
            ? clearanceAll
            : role.isEmpty
                ? clearancePublic
                : clearanceNonPrivate,
      };

  /// Whether the server named this caller at all.
  bool get named => role.isNotEmpty;

  /// Whether every album of this space may be changed.
  bool get mayEdit => role == roleAdmin || role == roleEdit;

  /// Whether photos may be added.
  bool get mayContribute => mayEdit || role == roleContribute;

  /// Whether the private images are seen as well.
  bool get seesPrivate => clearance == clearanceAll;

  /// Whether anything beyond the public images is seen.
  bool get seesMembers => seesPrivate || clearance == clearanceNonPrivate;

  /// What this permission allows, in plain words (issue #85).
  ///
  /// One sentence of three clauses — what may be done, what is seen, whether
  /// links may be handed out — because that is how somebody checks whether the
  /// server thinks of them what they think it does.
  String sentence(AppLocalizations l10n) => l10n.permissionSentence(
        _doing(l10n),
        _seeing(l10n),
        _sharing(l10n),
      );

  String _doing(AppLocalizations l10n) => switch (role) {
        roleAdmin => l10n.permissionDoingAdmin,
        roleEdit => l10n.permissionDoingEdit,
        roleContribute => l10n.permissionDoingContribute,
        roleView => l10n.permissionDoingView,
        _ => l10n.permissionDoingNone,
      };

  String _seeing(AppLocalizations l10n) => switch (clearance) {
        clearanceAll => l10n.permissionSeeingAll,
        clearanceNonPrivate => l10n.permissionSeeingNonPrivate,
        _ => l10n.permissionSeeingPublic,
      };

  String _sharing(AppLocalizations l10n) => mayShare
      ? l10n.permissionSharingMay
      : l10n.permissionSharingMayNot;

  /// What this permission allows, in the same three clauses but about
  /// somebody else — for a row of the users or invitations list (issue #85).
  String phrase(AppLocalizations l10n) => l10n.permissionPhrase(
        roleWord(l10n, role),
        clearanceWord(l10n, clearance),
        mayShare ? l10n.permissionPhraseMayShare : l10n.permissionPhraseNoLinks,
      );

  /// What the role [name] allows, in words.
  static String roleWord(AppLocalizations l10n, String name) =>
      switch (normalizeRole(name)) {
        roleAdmin => l10n.roleWordAdmin,
        roleEdit => l10n.roleWordEdit,
        roleContribute => l10n.roleWordContribute,
        roleView => l10n.roleWordView,
        _ => l10n.roleWordUnknown,
      };

  /// What the role [name] allows, said to the person it is about.
  ///
  /// Empty for a role the app does not know: a sentence that names nothing is
  /// better than one that promises the wrong thing, see [invitationRoleName].
  static String roleWordYou(AppLocalizations l10n, String name) =>
      switch (normalizeRole(name)) {
        roleAdmin => l10n.roleWordYouAdmin,
        roleEdit => l10n.roleWordYouEdit,
        roleContribute => l10n.roleWordYouContribute,
        roleView => l10n.roleWordYouView,
        _ => "",
      };

  /// What the clearance [name] shows, in words.
  ///
  /// [role] is what an empty clearance is read as, see [normalizeClearance].
  static String clearanceWord(
    AppLocalizations l10n,
    String name, {
    String role = "",
  }) =>
      switch (normalizeClearance(name, normalizeRole(role))) {
        clearanceAll => l10n.clearanceWordAll,
        clearanceNonPrivate => l10n.clearanceWordNonPrivate,
        _ => l10n.clearanceWordPublic,
      };

  @override
  bool operator ==(Object other) =>
      other is CallerPermission &&
      other.role == role &&
      other.clearance == clearance &&
      other.mayShare == mayShare;

  @override
  int get hashCode => Object.hash(role, clearance, mayShare);

  @override
  String toString() => "CallerPermission($role, $clearance, $mayShare)";
}

/// The sentence a guest is told what their library is with.
String guestLibraryNotice(AppLocalizations l10n) => l10n.guestLibraryNotice;

/// Who the app talks to the server as, as the server itself says it.
@immutable
class CallerInfo {
  /// The name of the signed-in user, empty for an anonymous caller and for an
  /// owner who has no name yet.
  final String userName;

  /// The caller's role: [roleAdmin], [roleMember] or [roleGuest]; empty for an
  /// anonymous caller.
  final String role;

  /// The folder the caller's requests are resolved against, empty for the
  /// server's base folder itself.
  final String space;

  /// Which privacy levels the caller may see, as the server spelled it
  /// (issue #85); empty where it said nothing, see [permission].
  final String clearance;

  /// Whether the caller may create share links, as the server said it.
  final bool mayShare;

  /// The URL template the space shows a position on a map with (issue #112).
  ///
  /// Never empty: [defaultMapUrl] where the server named none, which is what
  /// a server built before the field existed answers. It carries `{lat}` and
  /// `{lon}`, see `mapUrlFor`.
  final String mapUrl;

  const CallerInfo({
    this.userName = "",
    this.role = "",
    this.space = "",
    this.clearance = "",
    this.mayShare = false,
    String mapUrl = "",
  }) : mapUrl = mapUrl == "" ? defaultMapUrl : mapUrl;

  /// What the server answered about this caller.
  factory CallerInfo.of(AuthInfo info) => CallerInfo(
        userName: info.userName,
        role: info.role,
        space: info.space,
        clearance: info.clearance,
        mayShare: info.mayShare,
        mapUrl: info.mapUrl.trim(),
      );

  /// What this caller may do and see, normalised, see [CallerPermission].
  CallerPermission get permission => CallerPermission.ofFields(
        role: role,
        clearance: clearance,
        mayShare: mayShare,
      );

  /// Whether this caller is a guest, whose root is not a library of their own.
  bool get isGuest => role == roleGuest;

  /// Whether this caller may invite people (issue #52).
  ///
  /// A member and the admin may; a guest may not, and an anonymous caller has
  /// nothing to invite anybody with. A server started with `--invite admin`
  /// refuses a member nevertheless — that is the server's word, said in the
  /// dialog where it is asked, not guessed here.
  bool get mayInvite => role == roleAdmin || role == roleMember;

  @override
  bool operator ==(Object other) =>
      other is CallerInfo &&
      other.userName == userName &&
      other.role == role &&
      other.space == space &&
      other.clearance == clearance &&
      other.mayShare == mayShare &&
      other.mapUrl == mapUrl;

  @override
  int get hashCode =>
      Object.hash(userName, role, space, clearance, mayShare, mapUrl);

  @override
  String toString() => "CallerInfo($userName, $role, $space)";

  /// Who the enclosing app is signed in as, `null` where the server has not
  /// said (an anonymous caller, a server that could not be reached, a view
  /// pumped on its own in a test).
  static CallerInfo? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<CallerScope>()?.caller;

  /// Whether the enclosing app is signed in as a guest, see [isGuest].
  static bool isGuestCaller(BuildContext context) =>
      maybeOf(context)?.isGuest ?? false;

  /// What the caller of the enclosing app may do and see (issue #85).
  ///
  /// [CallerPermission.unknown] where the server has not said: nothing is
  /// hidden on a guess, see `offeredRights`.
  static CallerPermission permissionOf(BuildContext context) =>
      maybeOf(context)?.permission ?? CallerPermission.unknown;

  /// Who the app is signed in as, asked without becoming dependent on it.
  ///
  /// For the places that ask in `initState`, where a dependency may not be
  /// registered, exactly as [ShareSession.peek]. Unlike the link session, this
  /// one *does* change once — the server's answer arrives after the first
  /// build — so a view that asks here must also take the answer when it
  /// comes, see `album_view.dart`.
  static CallerInfo? peek(BuildContext context) =>
      context.getInheritedWidgetOfExactType<CallerScope>()?.caller;

  /// Whether the app is signed in as a guest, asked without a dependency.
  static bool isGuestPeek(BuildContext context) =>
      peek(context)?.isGuest ?? false;

  /// The map template of the space the enclosing app talks to (issue #112).
  ///
  /// [defaultMapUrl] where no server has said anything — a view pumped on its
  /// own in a test, a server that could not be reached — so that a position
  /// always has a map to open.
  static String mapUrlOf(BuildContext context) =>
      maybeOf(context)?.mapUrl ?? defaultMapUrl;
}

/// Publishes the [CallerInfo] to the widget tree, see [CallerInfo.maybeOf].
class CallerScope extends InheritedWidget {
  /// Who the app is signed in as, `null` while nobody said.
  final CallerInfo? caller;

  const CallerScope({
    super.key,
    required this.caller,
    required super.child,
  });

  @override
  bool updateShouldNotify(CallerScope oldWidget) => caller != oldWidget.caller;
}
