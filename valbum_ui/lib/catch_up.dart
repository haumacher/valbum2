/// The administrator's view of the background work that prepares the albums
/// of a space before their first visit (issue #236): previews, covers, faces,
/// places and videos, made newest album first.
///
/// One line with the progress on the status page (issue #305), expandable to
/// what the server is doing right now, how many videos still wait, how far
/// the content search has got and why it waits (issue #257), and what failed
/// last. The page asks the server again every few seconds while it is open.
library;

import 'package:flutter/material.dart';

import 'l10n/app_localizations.dart';
import 'resource.dart';

/// The key of the section's line, see [CatchUpSection].
const Key catchUpLineKey = Key("settings.catchUp");

/// The key of the details below the line.
const Key catchUpDetailsKey = Key("settings.catchUp.details");

/// How far the server has got preparing the albums, see the library comment.
///
/// Shown to the administrator of the space only, on the status page of issue
/// #305, from what `?type=status` answered ([StatusInfo.catchUp]); the server
/// answers nobody else. [problem] says why the answer is not fresh, where
/// the last question failed.
class CatchUpSection extends StatelessWidget {
  /// What the server answered last, `null` before its first answer.
  final CatchUpStatus? status;

  /// Why the server could not be asked, `null` while it answers.
  final String? problem;

  const CatchUpSection({super.key, this.status, this.problem});

  CatchUpStatus? get _status => status;

  String? get _problem => problem;

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var status = _status;
    String line;
    var problem = _problem;
    if (status == null) {
      line = problem == null
          ? l10n.catchUpAsking
          : l10n.catchUpUnavailable(problem);
    } else if (_finished(status)) {
      line = l10n.catchUpComplete(status.albumsTotal);
    } else if (status.contentEnabled &&
        status.albumsDone >= status.albumsTotal &&
        status.contentRemaining > 0) {
      // Every album is prepared, but its photos still wait to be described.
      line = l10n.catchUpContentProgress(
          status.contentDescribed, status.contentTotal);
    } else {
      line = l10n.catchUpProgress(status.albumsDone, status.albumsTotal);
    }
    return Padding(
      padding: const EdgeInsets.only(top: 16),
      child: ExpansionTile(
        key: catchUpLineKey,
        tilePadding: EdgeInsets.zero,
        leading: Icon(
          status != null && _finished(status)
              ? Icons.check_circle_outline
              : Icons.hourglass_bottom,
        ),
        title: Text(l10n.catchUpHeading),
        subtitle: Text(line),
        expandedCrossAxisAlignment: CrossAxisAlignment.start,
        childrenPadding: const EdgeInsets.only(left: 40, bottom: 8),
        children: [
          Column(
            key: catchUpDetailsKey,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (var detail in _details(l10n, status)) Text(detail),
            ],
          ),
        ],
      ),
    );
  }

  /// Whether nothing is left to do.
  static bool _finished(CatchUpStatus status) =>
      status.step == CatchUpStep.idle && !_waits(status);

  /// Whether work waits, running or not: photos waiting for the content
  /// search count even where the embedder cannot answer and nothing of them
  /// is queued.
  static bool _waits(CatchUpStatus status) =>
      status.albumsDone < status.albumsTotal ||
      status.videosRemaining > 0 ||
      status.contentRemaining > 0 ||
      (status.contentEnabled && status.contentDescribed < status.contentTotal);

  List<String> _details(AppLocalizations l10n, CatchUpStatus? status) {
    var problem = _problem;
    if (status == null) {
      return [
        problem == null ? l10n.catchUpAsking : l10n.catchUpUnavailable(problem)
      ];
    }
    return [
      if (status.step == CatchUpStep.idle)
        _waits(status) ? l10n.catchUpWaiting : l10n.catchUpIdle
      else
        l10n.catchUpNow(catchUpStepName(l10n, status.step),
            _folder(l10n, status.folder)),
      if (status.yielding) l10n.catchUpYielding,
      l10n.catchUpVideos(status.videosRemaining),
      // Only where the space has the content search on (issue #257).
      if (status.contentEnabled)
        l10n.catchUpContentProgress(
            status.contentDescribed, status.contentTotal),
      if (status.contentRemaining > 0)
        l10n.catchUpContent(status.contentRemaining),
      if (status.contentWaiting.isNotEmpty)
        l10n.catchUpContentWaiting(status.contentWaiting),
      if (status.contentWaiting.isNotEmpty && status.contentRetryAt > 0)
        l10n.catchUpContentRetry(_minutesUntil(status.contentRetryAt)),
      if (status.failure.isEmpty)
        l10n.catchUpNoFailure
      else
        l10n.catchUpFailure(
            _folder(l10n, status.failureFolder), status.failure),
      // A refresh that failed after an answer leaves the answer standing, and
      // says why it is not fresh.
      if (problem != null) l10n.catchUpUnavailable(problem),
    ];
  }

  /// The whole minutes from now to the given moment (milliseconds since the
  /// epoch), rounded up; `0` for one that has passed.
  static int _minutesUntil(int millis) {
    var left = millis - DateTime.now().millisecondsSinceEpoch;
    return left <= 0 ? 0 : (left + 59999) ~/ 60000;
  }

  static String _folder(AppLocalizations l10n, String folder) =>
      folder.isEmpty ? l10n.catchUpRoot : folder;
}

/// The name of the given step of the background in words; [raw] (the
/// server's own name) where the step is one the app does not know.
String catchUpStepName(AppLocalizations l10n, CatchUpStep step,
    [String raw = ""]) {
  switch (step) {
    case CatchUpStep.analysis:
      return l10n.catchUpStepAnalysis;
    case CatchUpStep.hash:
      return l10n.catchUpStepHash;
    case CatchUpStep.previews:
      return l10n.catchUpStepPreviews;
    case CatchUpStep.cover:
      return l10n.catchUpStepCover;
    case CatchUpStep.faces:
      return l10n.catchUpStepFaces;
    case CatchUpStep.places:
      return l10n.catchUpStepPlaces;
    case CatchUpStep.videos:
      return l10n.catchUpStepVideos;
    case CatchUpStep.content:
      return l10n.catchUpStepContent;
    case CatchUpStep.idle:
    case CatchUpStep.other:
      return raw.isEmpty ? l10n.catchUpStepOther : raw;
  }
}
