/// The dialog an upload runs behind (issue #70).
///
/// The report this replaces: the old `sn_progress_dialog` counted two
/// different things at once — a percentage of bytes *and* a batch number —
/// while the wheel only spun, and on a phone the line was too long to read.
/// One measurement is left, images, and it is the wheel that shows the
/// percentage:
///
///  * a determinate [CircularProgressIndicator] with the percentage inside it
///    while the images are being transferred, spinning in the phases that have
///    no measure (preparing, asking, waiting for the answer),
///  * below it exactly one line, `12 of 48 images`, wrapping if it must and
///    never truncated — no `TextOverflow.ellipsis` anywhere in this dialog,
///  * a Cancel button, which asks the upload to stop, see [UploadHandle].
///
/// The dialog never closes itself. That was the bug of issue #59: a dialog
/// that ends at its maximum vanishes while the bytes are still in flight. It
/// is closed by the code that opened it, once the server has answered, see
/// `AlbumState.uploadPicked`.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import 'client.dart';
import 'l10n/app_localizations.dart';

/// The key of the dialog, so that a test can address it.
const Key uploadProgressDialogKey = Key("upload.progress.dialog");

/// The key of the wheel showing the transfer.
const Key uploadProgressWheelKey = Key("upload.progress.wheel");

/// The key of the percentage inside the wheel.
const Key uploadProgressPercentKey = Key("upload.progress.percent");

/// The key of the one line saying how many images have arrived.
const Key uploadProgressCountKey = Key("upload.progress.count");

/// The key of the button asking the upload to stop.
const Key uploadProgressCancelKey = Key("upload.progress.cancel");

/// How large the wheel is drawn.
const double uploadProgressWheelSize = 88;

/// The dialog showing how far an upload has got, see the library comment.
class UploadProgressDialog extends StatelessWidget {
  /// What the upload reports, see [UploadProgress].
  final ValueListenable<UploadProgress> progress;

  /// Asks the upload to stop — [UploadHandle.cancel], as a rule.
  ///
  /// The dialog stays up afterwards: what became of the upload is said by the
  /// upload itself, and until it says so the transfer is still running.
  final VoidCallback onCancel;

  const UploadProgressDialog({
    super.key,
    required this.progress,
    required this.onCancel,
  });

  @override
  Widget build(BuildContext context) => AlertDialog(
        key: uploadProgressDialogKey,
        title: Text(AppLocalizations.of(context)!.uploadProgressTitle),
        content: ValueListenableBuilder<UploadProgress>(
          valueListenable: progress,
          builder: (context, value, _) => Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SizedBox.square(
                dimension: uploadProgressWheelSize,
                child: Stack(
                  alignment: Alignment.center,
                  children: [
                    Positioned.fill(
                      child: CircularProgressIndicator(
                        key: uploadProgressWheelKey,
                        // `null` is the spinning wheel: the phases that are
                        // not the transfer have nothing to measure, and a bar
                        // standing still at some value would be a lie.
                        value: value.determinate ? value.fraction : null,
                        strokeWidth: 6,
                      ),
                    ),
                    if (value.determinate)
                      Text(
                        "${value.percent} %",
                        key: uploadProgressPercentKey,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              // One line, wrapping. Never an ellipsis: the whole complaint of
              // issue #70 was a sentence that could not be read to its end.
              Text(
                value.lineOf(AppLocalizations.of(context)!),
                key: uploadProgressCountKey,
                textAlign: TextAlign.center,
                softWrap: true,
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            key: uploadProgressCancelKey,
            onPressed: onCancel,
            child: Text(AppLocalizations.of(context)!.cancel),
          ),
        ],
      );
}
