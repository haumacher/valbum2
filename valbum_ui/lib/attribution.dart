/// Who brought a photo into an album, and what the app makes of it (#53).
///
/// The server records the uploader once, at the upload, and derives
/// [ImagePart.contributor] (the subject: `user:<name>`, `token:<id>`,
/// `anonymous`) and [ImagePart.contributorLabel] (what to show) on every read.
/// Both are empty for a photo that never came through an upload — an album
/// from before this build looks exactly as it always did, which is why every
/// question below answers "nothing to show" for an empty label.
///
/// Two things follow from the attribution, and this library is where they are
/// decided once, so that the viewer and the tile editor cannot disagree:
///
///  * the line "Added by …", shown wherever the image is looked at, but not
///    to the person who added it — they know;
///  * "Take back…", offered to a contributor who may add photos to an album
///    but not change it: taking a photo back is a *move* into a folder of
///    one's own, never a delete, see `MoveService.CONTRIBUTION_REFUSED`.
library;

import 'package:flutter/material.dart';

import 'caller.dart';
import 'resource.dart';
import 'rights.dart';
import 'share_session.dart';

/// The prefix the server spells a signed-in user's subject with.
const String subjectUserPrefix = "user:";

/// How an attribution is worded.
String attributionLine(String label) => "Added by $label";

/// The subject the server would name this caller by, `null` where the app
/// cannot know it.
///
/// A signed-in user is `user:<name>` — the one subject the app can build,
/// because the name is what `?type=auth` answered, see [CallerInfo.userName].
/// Everybody else is `null`, and an attribution is then always shown:
///
///  * a **share link** caller is a `token:<id>` subject whose id the app never
///    sees (the token is the secret, the id is the server's record of it), so
///    a link caller can never be recognised as the contributor — and never
///    has to be: a link has no album to take a photo back into anyway;
///  * an **anonymous** caller has contributed under `anonymous`, which is not
///    one person, so "Added by …" stays.
String? callerSubject(BuildContext context) {
  if (ShareSession.of(context) != null) {
    return null;
  }
  var name = CallerInfo.maybeOf(context)?.userName ?? "";
  return name.isEmpty ? null : "$subjectUserPrefix$name";
}

/// The line naming who added [image], `null` where none is shown.
///
/// Nothing without a label, and nothing for the caller's own contribution: the
/// viewer is a caption, not a receipt.
String? attributionOf(BuildContext context, ImagePart image) {
  var label = image.contributorLabel.trim();
  if (label.isEmpty) {
    return null;
  }
  var subject = image.contributor;
  if (subject.isNotEmpty && subject == callerSubject(context)) {
    return null;
  }
  return attributionLine(label);
}

/// The line naming who added [image] wherever it is shown to the editor as
/// well, `null` while nothing is known.
///
/// The tile properties of the album's edit mode show the attribution of every
/// image, the editor's own included: this is the screen that says what an
/// image *is*, and who added it is part of that.
String? attributionShown(ImagePart image) {
  var label = image.contributorLabel.trim();
  return label.isEmpty ? null : attributionLine(label);
}

/// Whether the caller may take [image] back out of the album it is in.
///
/// The rule the server applies (see `ImageServlet.moveEntries`), asked here so
/// that what would be refused is not offered:
///
///  * the caller holds `contribute` but not `edit` — somebody holding `edit`
///    moves photos in the album's own edit mode ("Move to…") and needs no
///    second way of doing the same thing;
///  * the image carries this caller's own subject, so the move is a
///    take-back and not an attempt at somebody else's photo;
///  * there is a space to move it into: a share-link caller has none
///    (`AuthService.SHARE_MOVE_REFUSED`) and a guest's root is not a library
///    (`AuthService.GUEST_SPACE_REFUSED`), so neither is offered the button.
///
/// An album carrying no rights at all reads as [Rights.everything] (an older
/// server, a view pumped on its own), which holds `edit` — so the button is
/// not offered there either.
bool mayTakeBack(BuildContext context, ImagePart image) {
  if (ShareSession.of(context) != null || CallerInfo.isGuestCaller(context)) {
    return false;
  }
  var subject = callerSubject(context);
  if (subject == null || image.contributor != subject) {
    return false;
  }
  var rights = Rights.of(image.owner);
  return rights.mayContribute && !rights.mayEdit;
}
