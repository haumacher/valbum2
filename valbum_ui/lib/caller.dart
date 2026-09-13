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

import 'resource.dart';

/// The role of the owner of a library, who invites and manages.
const String roleAdmin = "admin";

/// The role of somebody with a library of their own on this server.
const String roleMember = "member";

/// The role of somebody whose library is what others share with them.
const String roleGuest = "guest";

/// The sentence a guest is told what their library is with.
const String guestLibraryNotice =
    "Guest: your library is what others share with you.";

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

  const CallerInfo({
    this.userName = "",
    this.role = "",
    this.space = "",
  });

  /// What the server answered about this caller.
  factory CallerInfo.of(AuthInfo info) => CallerInfo(
        userName: info.userName,
        role: info.role,
        space: info.space,
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
      other.space == space;

  @override
  int get hashCode => Object.hash(userName, role, space);

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
