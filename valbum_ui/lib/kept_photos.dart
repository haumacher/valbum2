/// Photos a delete keeps where other albums show them, see issue #307.
///
/// A photo that another album still shows as a reference is never thrown
/// away: instead of going into the trash it moves into the album of its
/// oldest reference and takes that reference's place there; the server says
/// so per photo ([MoveOutcome.kept], [CreateResult.kept]). Before a delete the
/// app asks the server what it would keep ([keptOnDelete], one round trip, the
/// very code that deletes answering) and names those albums in the question
/// ([confirmKeeping]); afterwards it says where the photos went
/// ([keptReport]). Nothing of this is guessed here: where the server cannot
/// be asked — offline, an older server, a saved search — the question is the
/// one asked before, and the answer after the delete still names the albums.
library;

import 'package:flutter/material.dart';

import 'client.dart';
import 'dialog_style.dart';
import 'l10n/app_localizations.dart';
import 'offline.dart';
import 'resource.dart';
import 'selectable.dart' show showAppDialog;

/// Every kept photo of [result], in the order the server named them.
List<KeptPhoto> keptOf(MoveResult result) => [
      for (var outcome in result.outcomes) ...outcome.kept,
    ];

/// Whether [outcome] is that of a photo kept in an album that shows it,
/// rather than of an album some of whose photos were kept.
bool isKeptPhoto(MoveOutcome outcome) =>
    outcome.kept.length == 1 && outcome.kept.first.photo == outcome.name;

/// The albums [kept] names, each once, in the order first named, quoted as
/// the sentences about kept photos quote them.
String keptAlbums(AppLocalizations l10n, List<KeptPhoto> kept) {
  var titles = <String>{
    for (var photo in kept) photo.title.isEmpty ? photo.album : photo.title,
  };
  return titles.map((title) => l10n.keptAlbumName(title)).join(", ");
}

/// What a delete of [count] photos keeps, said before it is done:
/// "3 photos are still shown in ‘Best of 2025’ – they stay there, all others
/// go to the trash." `null` where it keeps nothing — the question is then the
/// one asked before issue #307.
///
/// [count] is how many photos are deleted, `null` where that is not known (an
/// album is deleted with whatever lies in it): the others are said to go to
/// the trash unless every one of them is kept.
String? keptQuestion(AppLocalizations l10n, List<KeptPhoto> kept, int? count) {
  if (kept.isEmpty) {
    return null;
  }
  var albums = keptAlbums(l10n, kept);
  return count != null && kept.length >= count
      ? l10n.keptQuestionAll(albums, kept.length)
      : l10n.keptQuestion(albums, kept.length);
}

/// What a delete kept, said after it was done; `null` where it kept nothing.
String? keptReport(AppLocalizations l10n, List<KeptPhoto> kept) =>
    kept.isEmpty ? null : l10n.keptReport(keptAlbums(l10n, kept), kept.length);

/// What a purge will keep, said before it is done; `null` for nothing.
String? keptPurgeQuestion(AppLocalizations l10n, List<KeptPhoto> kept) =>
    kept.isEmpty
        ? null
        : l10n.keptPurgeQuestion(keptAlbums(l10n, kept), kept.length);

/// What a purge kept, said after it was done; `null` for nothing.
String? keptPurgeReport(AppLocalizations l10n, List<KeptPhoto> kept) =>
    kept.isEmpty
        ? null
        : l10n.keptPurgeReport(keptAlbums(l10n, kept), kept.length);

/// Asks the server what deleting [names] of the folder at [path] would keep,
/// see [VAlbumClient.deleteCheck]: nothing where it cannot be asked — offline,
/// an older server that knows no such question, a refusal (the delete itself
/// will say why).
Future<List<KeptPhoto>> keptOnDelete(
  BuildContext context,
  VAlbumClient client,
  List<String> path,
  List<String> names,
) async {
  if (names.isEmpty || OfflineScope.isOffline(context)) {
    return const [];
  }
  try {
    return keptOf(await client.deleteCheck(path, names));
  } catch (_) {
    return const [];
  }
}

/// Asks the server what purging the trash of the album at [path] would keep,
/// see [keptOnDelete].
Future<List<KeptPhoto>> keptOnPurge(
  BuildContext context,
  VAlbumClient client,
  List<String> path,
) async {
  if (OfflineScope.isOffline(context)) {
    return const [];
  }
  try {
    return keptOf(await client.purgeCheck(path));
  } catch (_) {
    return const [];
  }
}

/// The question asked before a delete that keeps photos where other albums
/// show them: [question] (see [keptQuestion]) under the title "Shown in other
/// albums", Cancel / [confirm]. Answers whether to go on.
Future<bool> confirmKeeping(
  BuildContext context, {
  required String question,
  required String confirm,
}) async {
  var l10n = AppLocalizations.of(context)!;
  var confirmed = await showAppDialog<bool>(
    context: context,
    builder: (context) => AppAlertDialog(
      key: const Key("kept-confirm-dialog"),
      title: Text(l10n.keptTitle),
      content: Text(question, key: const Key("kept-question")),
      actions: [
        TextButton(
          key: const Key("kept-confirm-cancel"),
          onPressed: () => Navigator.of(context).pop(false),
          child: Text(l10n.cancel),
        ),
        ElevatedButton(
          key: const Key("kept-confirm"),
          onPressed: () => Navigator.of(context).pop(true),
          child: Text(confirm),
        ),
      ],
    ),
  );
  return confirmed == true;
}

/// Says [report] (see [keptReport]) in a snack bar of [messenger].
void showKeptReport(ScaffoldMessengerState messenger, String? report) {
  if (report == null) {
    return;
  }
  messenger.showSnackBar(
    SnackBar(
      content: Text(report, key: const Key("kept-report")),
      duration: const Duration(seconds: 8),
    ),
  );
}
