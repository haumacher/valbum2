/// The administrator's view of the background work that prepares the albums
/// of a space before their first visit (issue #236): previews, covers, faces,
/// places and videos, made newest album first.
///
/// One line with the progress in the server settings, expandable to what the
/// server is doing right now, how many videos still wait and what failed last.
/// It asks the server again every few seconds while it is on screen, and never
/// otherwise.
library;

import 'dart:async';

import 'package:flutter/material.dart';

import 'client.dart';
import 'l10n/app_localizations.dart';
import 'manage_view.dart' show refusalMessage;
import 'resource.dart';

/// The key of the section's line, see [CatchUpSection].
const Key catchUpLineKey = Key("settings.catchUp");

/// The key of the details below the line.
const Key catchUpDetailsKey = Key("settings.catchUp.details");

/// How far the server has got preparing the albums, see the library comment.
///
/// Shown to the administrator of the space only: the settings screen builds it
/// for that role and no other, and the server refuses everybody else anyway.
class CatchUpSection extends StatefulWidget {
  /// Whom to ask.
  final VAlbumClient client;

  /// How often the server is asked while the section is on screen.
  final Duration interval;

  const CatchUpSection({
    super.key,
    required this.client,
    this.interval = const Duration(seconds: 5),
  });

  @override
  State<CatchUpSection> createState() => CatchUpSectionState();
}

class CatchUpSectionState extends State<CatchUpSection> {
  /// What the server answered last, `null` before its first answer.
  CatchUpStatus? _status;

  /// Why the server could not be asked, `null` while it answers.
  String? _problem;

  Timer? _timer;

  bool _asking = false;

  @override
  void initState() {
    super.initState();
    _ask();
    _timer = Timer.periodic(widget.interval, (_) => _ask());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _ask() async {
    if (_asking) {
      return;
    }
    _asking = true;
    try {
      var status = await widget.client.catchUp();
      if (mounted) {
        setState(() {
          _status = status;
          _problem = null;
        });
      }
    } catch (error) {
      if (mounted) {
        setState(() => _problem = refusalMessage(error));
      }
    } finally {
      _asking = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var status = _status;
    String line;
    if (status == null) {
      line = _problem == null
          ? l10n.catchUpAsking
          : l10n.catchUpUnavailable(_problem!);
    } else if (_finished(status)) {
      line = l10n.catchUpComplete(status.albumsTotal);
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
      status.step == CatchUpStep.idle &&
      status.albumsDone >= status.albumsTotal &&
      status.videosRemaining == 0;

  List<String> _details(AppLocalizations l10n, CatchUpStatus? status) {
    var problem = _problem;
    if (status == null) {
      return [
        problem == null ? l10n.catchUpAsking : l10n.catchUpUnavailable(problem)
      ];
    }
    return [
      if (status.step == CatchUpStep.idle)
        l10n.catchUpIdle
      else
        l10n.catchUpNow(
            _stepName(l10n, status.step), _folder(l10n, status.folder)),
      if (status.yielding) l10n.catchUpYielding,
      l10n.catchUpVideos(status.videosRemaining),
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

  static String _folder(AppLocalizations l10n, String folder) =>
      folder.isEmpty ? l10n.catchUpRoot : folder;

  static String _stepName(AppLocalizations l10n, CatchUpStep step) {
    switch (step) {
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
      case CatchUpStep.idle:
      case CatchUpStep.other:
        return l10n.catchUpStepOther;
    }
  }
}
