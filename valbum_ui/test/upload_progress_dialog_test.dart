/// Tests of the upload dialog of issue #70: one measurement, images, and the
/// wheel shows the percentage.
///
/// What was reported from the phone was a dialog counting two different things
/// at once — bytes and batches — with a wheel that only spun and a line too
/// long to read. Each of those three is asserted against here: the line says
/// images and nothing else, the wheel carries the value, and nothing in this
/// dialog is ever cut off.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/upload_progress.dart';
import 'util/l10n.dart';

/// Pumps the dialog with the given progress on a surface of [width].
///
/// A phone is narrow, and 320 logical pixels is the narrowest one anybody
/// still uses: the line has to fit there, wrapping if it must.
Future<ValueNotifier<UploadProgress>> pumpDialog(
  WidgetTester tester,
  UploadProgress progress, {
  double width = 400,
  VoidCallback? onCancel,
}) async {
  var notifier = ValueNotifier<UploadProgress>(progress);
  addTearDown(notifier.dispose);
  await tester.binding.setSurfaceSize(Size(width, 640));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(
    MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: UploadProgressDialog(
        progress: notifier,
        onCancel: onCancel ?? () {},
      ),
    ),
  );
  // One frame, never `pumpAndSettle`: an indeterminate wheel spins forever and
  // there is nothing to settle for.
  await tester.pump();
  return notifier;
}

/// The wheel of the dialog.
CircularProgressIndicator wheelOf(WidgetTester tester) =>
    tester.widget<CircularProgressIndicator>(find.byKey(uploadProgressWheelKey));

void main() {
  testWidgets('counts images and lets the wheel show the percentage',
      (tester) async {
    await pumpDialog(
      tester,
      const UploadProgress(
        phase: UploadPhase.transferring,
        imagesDone: 12,
        imagesTotal: 48,
        fraction: 0.25,
      ),
    );

    expect(
      tester.widget<Text>(find.byKey(uploadProgressCountKey)).data,
      "12 of 48 images",
    );
    expect(wheelOf(tester).value, 0.25);
    expect(
      tester.widget<Text>(find.byKey(uploadProgressPercentKey)).data,
      "25 %",
    );
    // Not a word about batches, bytes or requests — the complaint of #70.
    expect(find.textContaining("Paket"), findsNothing);
    expect(find.textContaining("Byte"), findsNothing);
  });

  testWidgets('follows what the upload reports', (tester) async {
    var notifier = await pumpDialog(
      tester,
      const UploadProgress(
        phase: UploadPhase.transferring,
        imagesDone: 0,
        imagesTotal: 12,
      ),
    );

    notifier.value = const UploadProgress(
      phase: UploadPhase.transferring,
      imagesDone: 8,
      imagesTotal: 12,
      fraction: 0.75,
    );
    await tester.pump();

    expect(
      tester.widget<Text>(find.byKey(uploadProgressCountKey)).data,
      "8 of 12 images",
    );
    expect(wheelOf(tester).value, 0.75);
    expect(
      tester.widget<Text>(find.byKey(uploadProgressPercentKey)).data,
      "75 %",
    );
  });

  testWidgets('wraps rather than truncates on a narrow phone', (tester) async {
    await pumpDialog(
      tester,
      const UploadProgress(
        phase: UploadPhase.transferring,
        imagesDone: 128,
        imagesTotal: 256,
        fraction: 0.5,
      ),
      width: 320,
    );

    // Nothing in this dialog may end in an ellipsis: that is what made the
    // line unreadable on the phone.
    for (var text in tester.widgetList<Text>(find.descendant(
      of: find.byKey(uploadProgressDialogKey),
      matching: find.byType(Text),
    ))) {
      expect(text.overflow, isNot(TextOverflow.ellipsis));
    }
    // And the count is really on the screen, whole.
    var count = find.byKey(uploadProgressCountKey);
    expect(count, findsOneWidget);
    expect(
      tester.widget<Text>(count).data,
      "128 of 256 images",
    );
    var box = tester.getRect(count);
    expect(box.left, greaterThanOrEqualTo(0));
    expect(box.right, lessThanOrEqualTo(320));
    expect(tester.takeException(), isNull, reason: "nothing overflowed");
  });

  testWidgets('spins without a value in the phases that have no measure',
      (tester) async {
    var notifier = await pumpDialog(
      tester,
      const UploadProgress(
        phase: UploadPhase.preparing,
        imagesDone: 3,
        imagesTotal: 48,
      ),
    );

    expect(wheelOf(tester).value, isNull);
    expect(find.byKey(uploadProgressPercentKey), findsNothing);
    expect(
      tester.widget<Text>(find.byKey(uploadProgressCountKey)).data,
      "Preparing: 3 of 48...",
    );

    notifier.value = const UploadProgress(
      phase: UploadPhase.asking,
      imagesDone: 0,
      imagesTotal: 48,
    );
    await tester.pump();
    expect(wheelOf(tester).value, isNull);
    expect(
      tester.widget<Text>(find.byKey(uploadProgressCountKey)).data,
      uploadAskingMessage(testL10n),
    );

    // The last phase: the body is out and the server has not answered yet,
    // which is the lesson of issue #59 — the dialog is still up.
    notifier.value = const UploadProgress(
      phase: UploadPhase.waiting,
      imagesDone: 44,
      imagesTotal: 48,
      fraction: 0.99,
    );
    await tester.pump();
    expect(wheelOf(tester).value, isNull);
    expect(
      tester.widget<Text>(find.byKey(uploadProgressCountKey)).data,
      testL10n.uploadWaiting,
    );
  });

  testWidgets('cancelling asks the upload to stop', (tester) async {
    var handle = UploadHandle();
    await pumpDialog(
      tester,
      const UploadProgress(
        phase: UploadPhase.transferring,
        imagesDone: 1,
        imagesTotal: 12,
        fraction: 0.08,
      ),
      onCancel: handle.cancel,
    );

    expect(handle.cancelled, isFalse);
    await tester.tap(find.byKey(uploadProgressCancelKey));
    await tester.pump();

    expect(handle.cancelled, isTrue);
    // The dialog stays: what became of the upload is the upload's word, and
    // until it says so the transfer is still running.
    expect(find.byKey(uploadProgressDialogKey), findsOneWidget);
  });
}
