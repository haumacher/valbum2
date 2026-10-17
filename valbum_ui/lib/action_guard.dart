/// The two questions every action of a view asks first (issue #293): **may it
/// be offered here** ([rightsOf]) and **how is it written** ([writeAction]).
///
/// Before this library each view answered both by hand: one read the
/// server's rights raw where the others fell back on the caller's role, one
/// combined `mayEdit` with "not in a share link" seven times over, and a
/// handful of writes went out while the app was offline, to fail with a
/// transport error instead of the one sentence every other write says. Here
/// the rule is said once, and `test/action_guard_scan_test.dart` keeps view
/// code from going round it: no raw `Rights.of` outside `rights.dart` and this
/// library, no write of the client outside [writeAction].
library;

import 'package:flutter/material.dart';

import 'caller.dart';
import 'loadable.dart';
import 'offline.dart';
import 'resource.dart';
import 'rights.dart';
import 'share_session.dart';

/// What the app offers for [folder], the one answer every view takes.
///
///  * The server's per-folder rights where it answered them (issue #49), the
///    caller's role where it did not (issue #85), see [offeredRights];
///  * narrowed by the session, see [sessionRights]: nothing is edited through
///    a share link, and a "view as" preview ([previewing]) acts on nothing.
///
/// A `null` folder is one the view does not hold — a space-level page such as
/// the duplicates of issue #220: the role decides there, which is also what
/// the server decides for a paired caller (its rights are its role's, the same
/// in every album of the space, see `AuthService.rights`).
///
/// [listen] `false` asks without becoming dependent on the caller and the
/// session, for the places that ask from `initState` (see [CallerInfo.peek]);
/// such a view must take the caller's answer when it arrives by itself.
Rights rightsOf(
  BuildContext context,
  FolderResource? folder, {
  bool previewing = false,
  bool listen = true,
}) {
  var caller = listen ? CallerInfo.maybeOf(context) : CallerInfo.peek(context);
  var share = listen ? ShareSession.of(context) : ShareSession.peek(context);
  return sessionRights(
    offeredRights(
      Rights.of(folder),
      caller?.permission ?? CallerPermission.unknown,
    ),
    shareLink: share != null,
    previewing: previewing,
  );
}

/// What a write answered, see [writeAction]: a box, so that a write answering
/// nothing (`void`, a `null` album) is told from one that was not done.
@immutable
class Written<T> {
  /// The server's answer.
  final T value;

  const Written(this.value);
}

/// The bar a refused write is said in: the server's own reason (issue #49),
/// or the sentence [message] a view makes of it.
/// [key] is the key of its text, by which a surface's refusal is found.
SnackBar refusalBar(Object error, {String? message, Key? key}) => SnackBar(
      content: Text(message ?? reasonOf(error), key: key),
      backgroundColor: Colors.red.shade700,
      duration: const Duration(seconds: 8),
    );

/// Shows the server's own reason for a refused write, as every refused write
/// is shown.
void showRefusal(ScaffoldMessengerState messenger, Object error) =>
    messenger.showSnackBar(refusalBar(error));

/// Does the user-initiated write [write], the one way every view writes
/// (issue #293):
///
///  1. **offline** it is refused before anything is sent, with the sentence
///     every refusal of the kind says ([refuseWhileOffline]), and `null` is
///     answered;
///  2. otherwise [write] is sent, and its answer comes back in a [Written];
///  3. a **refusal** (whatever [write] threw) answers `null` as well, after
///     [onRefused] was told — to take an edit back, to end a progress
///     indicator; it is told whether or not the view is still there, so it
///     asks `mounted` before it touches the view's state — and is said in
///     the server's own words ([showRefusal]), or in the sentence [refusal]
///     makes of them, unless [speaks] is `false` because the view says it in
///     a place of its own (a line of a dialog, say); [refusalKey] is the key
///     of the bar's text where a surface's refusal has always had one.
///
/// The messenger is taken before the request goes out, so a refusal that
/// arrives after the view was left still speaks.
///
/// Writes nobody asked for — the background sync of the camera roll, the
/// "seen" mark of the bell, a prefetch — are not user writes and do not come
/// here: an offline app simply does not do them, and nobody is to be told.
Future<Written<T>?> writeAction<T>(
  BuildContext context,
  Future<T> Function() write, {
  void Function(Object error)? onRefused,
  String Function(Object error)? refusal,
  Key? refusalKey,
  bool speaks = true,
}) async {
  if (refuseWhileOffline(context)) {
    return null;
  }
  var messenger = ScaffoldMessenger.maybeOf(context);
  try {
    return Written(await write());
  } catch (error) {
    onRefused?.call(error);
    if (speaks && messenger != null && messenger.mounted) {
      messenger.showSnackBar(refusalBar(
        error,
        message: refusal?.call(error),
        key: refusalKey,
      ));
    }
    return null;
  }
}
