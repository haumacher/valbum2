/// Something a chooser offers that has to be asked of the server first, and
/// the one way the app waits for it (issue #291).
///
/// A chooser opened before what it offers arrived must not stand empty as if
/// there were nothing — the persons chooser said "Nobody yet" of a register
/// that was still on its way, or had failed, and offered "New person" for
/// somebody the space already knew. A [Loadable] knows the difference between
/// *not there yet*, *failed* and *there*, keeps a failure as a failure (never
/// as an empty answer), and asks again where it is told to. [LoadableBuilder]
/// draws the three: a progress indicator that turns into the content once the
/// answer lands — a click before it did is not lost — or the reason of the
/// failure, in the server's own words where it spoke, with a Retry.
library;

import 'package:flutter/material.dart';

import 'client.dart';
import 'l10n/app_localizations.dart';

/// The reason a failed request gives, the server's own words where it spoke.
String reasonOf(Object error) =>
    error is VAlbumException ? error.message : "$error";

/// What is asked of the server once and then held, see the library doc.
///
/// Asked lazily: nothing happens before the first [load]. While an answer is
/// on its way, a second [load] waits for the same one. A failure is kept in
/// [error] and the next [load] asks again — unless [keepsFailure] says the
/// failure *is* the answer (a refusal that asking again would only repeat).
/// [retry] always asks again.
class Loadable<T> extends ChangeNotifier {
  /// Asks the server.
  final Future<T> Function()? _ask;

  /// Whether a failure is final for [load], see the class doc.
  final bool Function(Object error) _keepsFailure;

  T? _value;

  bool _hasValue = false;

  Object? _error;

  /// The request on its way, `null` while none is.
  Future<T>? _running;

  /// The last request, answered or not, `null` before the first.
  Future<T>? _last;

  /// Something that is asked by [ask].
  ///
  /// [keepsFailure] tells a failure that [load] does not ask again about, see
  /// the class doc; none by default.
  Loadable(Future<T> Function() ask,
      {bool Function(Object error)? keepsFailure})
      : _ask = ask,
        _keepsFailure = keepsFailure ?? _never;

  /// Something that is there already, and is never asked for.
  Loadable.value(T value)
      : _ask = null,
        _keepsFailure = _never,
        _value = value,
        _hasValue = true,
        _last = Future<T>.value(value);

  static bool _never(Object error) => false;

  /// Whether an answer arrived (or one was [replace]d in).
  bool get hasValue => _hasValue;

  /// What arrived, `null` before it did — see [hasValue].
  T? get value => _value;

  /// Replaces what arrived: what the caller changed on the server itself
  /// (a person created) is written in without asking again.
  ///
  /// A request on its way is let go: its answer was asked before the change
  /// and would undo it.
  void replace(T value) {
    _running = null;
    _value = value;
    _hasValue = true;
    _error = null;
    _last = Future<T>.value(value);
    notifyListeners();
  }

  /// Why the last request failed, `null` while it did not.
  Object? get error => _error;

  /// Whether a request is on its way.
  bool get loading => _running != null;

  /// Answers what arrived, asking where nothing did or the last request
  /// failed (see [keepsFailure]); a request on its way is waited for.
  ///
  /// Fails where the request fails.
  Future<T> load() {
    var running = _running;
    if (running != null) {
      return running;
    }
    var last = _last;
    if (last != null) {
      var failure = _error;
      if (failure == null || _keepsFailure(failure)) {
        return last;
      }
    }
    return _start();
  }

  /// Asks again, whatever there is; a request on its way is waited for.
  Future<T> retry() => _running ?? _start();

  Future<T> _start() {
    var ask = _ask;
    if (ask == null) {
      return _last!;
    }
    var hadError = _error != null;
    _error = null;
    var asked = ask();
    _running = asked;
    _last = asked;
    asked.then(
      (answer) {
        if (identical(_running, asked)) {
          _running = null;
          _value = answer;
          _hasValue = true;
          notifyListeners();
        }
      },
      onError: (Object failure) {
        if (identical(_running, asked)) {
          _running = null;
          _error = failure;
          notifyListeners();
        }
      },
    );
    if (hadError || _hasValue) {
      // The failure (or the old answer) a chooser shows gives way to the
      // progress of the new request.
      notifyListeners();
    }
    return asked;
  }
}

/// Draws a [Loadable]: [builder] once it is there, a progress indicator
/// while it is on its way, the reason and a Retry where it failed.
///
/// Where something arrived, a later failed retry keeps showing it. [frame]
/// puts the progress or the failure where the content would stand — the
/// dialog a chooser would be — and stands without one where it is absent.
///
/// The keys are those of [keyPrefix]: `-loading`, `-failed` and `-retry`.
class LoadableBuilder<T> extends StatelessWidget {
  /// What is drawn.
  final Loadable<T> loadable;

  /// The content, from what arrived.
  final Widget Function(BuildContext context, T value) builder;

  /// What stands around the progress or the failure, see the class doc.
  final Widget Function(BuildContext context, Widget status)? frame;

  /// What the keys of the progress, the failure and the Retry start with.
  final String keyPrefix;

  const LoadableBuilder({
    super.key,
    required this.loadable,
    required this.builder,
    this.frame,
    this.keyPrefix = "loadable",
  });

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: loadable,
        builder: (context, _) {
          if (loadable.hasValue) {
            return builder(context, loadable.value as T);
          }
          var status = LoadableStatus(loadable: loadable, keyPrefix: keyPrefix);
          var around = frame;
          return around == null ? status : around(context, status);
        },
      );
}

/// The progress or the failure of a [Loadable] that has nothing to show yet,
/// see [LoadableBuilder] — on its own where a chooser keeps its frame (the
/// search field of the persons chooser) and waits only in its list.
class LoadableStatus extends StatelessWidget {
  /// Whose state is shown.
  final Loadable<Object?> loadable;

  /// See [LoadableBuilder.keyPrefix].
  final String keyPrefix;

  const LoadableStatus({
    super.key,
    required this.loadable,
    this.keyPrefix = "loadable",
  });

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var error = loadable.error;
    if (error != null) {
      return Column(
        key: Key("$keyPrefix-failed"),
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(l10n.loadingFailed(reasonOf(error))),
          const SizedBox(height: 8),
          Align(
            alignment: AlignmentDirectional.centerEnd,
            child: ElevatedButton.icon(
              key: Key("$keyPrefix-retry"),
              onPressed: () => loadable.retry().ignore(),
              icon: const Icon(Icons.refresh),
              label: Text(l10n.retry),
            ),
          ),
        ],
      );
    }
    return Row(
      key: Key("$keyPrefix-loading"),
      mainAxisSize: MainAxisSize.min,
      children: [
        const SizedBox(
          width: 24,
          height: 24,
          child: CircularProgressIndicator(strokeWidth: 3),
        ),
        const SizedBox(width: 16),
        Flexible(child: Text(l10n.loading)),
      ],
    );
  }
}
