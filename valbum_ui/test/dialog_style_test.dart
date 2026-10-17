/// Every dialog takes its spacing from `dialog_style.dart`: 12 pixels to the
/// screen's sides, 16 inside the frame.
library;

import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/app.dart';
import 'package:valbum_ui/dialog_style.dart';
import 'package:valbum_ui/form_dialog.dart';

/// Opens the dialog [dialog] on a phone, 360 by 640 pixels.
Future<void> _openOnPhone(WidgetTester tester, Widget dialog) async {
  tester.view.physicalSize = const Size(360, 640);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(MaterialApp(
    theme: appTheme(),
    home: Builder(
      builder: (context) => TextButton(
        child: const Text("open"),
        onPressed: () => showDialog<void>(
          context: context,
          builder: (context) => dialog,
        ),
      ),
    ),
  ));
  await tester.tap(find.text("open"));
  await tester.pumpAndSettle();
}

/// The frame of the dialog of [key], the [Material] its [Dialog] draws.
Rect _frame(WidgetTester tester, Key key) => tester.getRect(find
    .descendant(of: find.byKey(key), matching: find.byType(Material))
    .first);

void main() {
  testWidgets('an alert dialog on a phone is compact', (tester) async {
    await _openOnPhone(
      tester,
      AppAlertDialog(
        key: const Key("dialog"),
        title: const Text("Title", key: Key("title")),
        content: const SizedBox(
            width: 400, child: Text("wide content", key: Key("content"))),
        actions: [
          TextButton(
            key: const Key("button"),
            onPressed: () {},
            child: const Text("OK"),
          ),
        ],
      ),
    );

    var frame = _frame(tester, const Key("dialog"));
    expect(frame.left, 12);
    expect(tester.getRect(find.byKey(const Key("title"))).left, 12 + 16);
    expect(tester.getRect(find.byKey(const Key("title"))).top, frame.top + 16);
    expect(tester.getRect(find.byKey(const Key("content"))).left, 12 + 16);
    var button = tester.getRect(find.byKey(const Key("button")));
    expect(button.bottom, frame.bottom - 16);
    expect(button.right, frame.right - 16);
  });

  testWidgets('a form on a phone is spaced as an alert dialog', (tester) async {
    await _openOnPhone(
      tester,
      FormDialogFrame(
        key: const Key("dialog"),
        title: const Text("Title", key: Key("title")),
        fields: const [
          SizedBox(width: 400, child: Text("a field", key: Key("content"))),
        ],
        actions: [
          TextButton(
            key: const Key("button"),
            onPressed: () {},
            child: const Text("OK"),
          ),
        ],
      ),
    );

    var frame = _frame(tester, const Key("dialog"));
    expect(frame.left, 12);
    expect(tester.getRect(find.byKey(const Key("content"))).left, 12 + 16);
    var button = tester.getRect(find.byKey(const Key("button")));
    expect(button.bottom, frame.bottom - 16);
    expect(button.right, frame.right - 16);
  });

  test('every dialog is built by dialog_style.dart', () {
    // Flutter's own constructors, which would take Flutter's spacing.
    var raw = RegExp(
        r"\b(AlertDialog|SimpleDialog|SimpleDialogOption|Dialog)(\.\w+)?\(");
    var offenders = [
      for (var file in Directory("lib").listSync(recursive: true))
        if (file is File &&
            file.path.endsWith(".dart") &&
            !file.path.endsWith("dialog_style.dart") &&
            file
                .readAsLinesSync()
                .where((line) => !line.trimLeft().startsWith("//"))
                .any(raw.hasMatch))
          file.path,
    ];
    expect(offenders, isEmpty,
        reason: "build dialogs with AppAlertDialog, AppSimpleDialog, "
            "AppDialogOption, AppDialog or FormDialogFrame, see "
            "dialog_style.dart");
  });

  test('the scan sees a raw dialog', () {
    var raw = RegExp(
        r"\b(AlertDialog|SimpleDialog|SimpleDialogOption|Dialog)(\.\w+)?\(");
    expect(raw.hasMatch("builder: (context) => AlertDialog("), isTrue);
    expect(raw.hasMatch("return const Dialog(child: x)"), isTrue);
    expect(raw.hasMatch("Dialog.fullscreen("), isTrue);
    expect(raw.hasMatch("AppAlertDialog("), isFalse);
    expect(raw.hasMatch("AppDialog("), isFalse);
    expect(raw.hasMatch("UploadProgressDialog("), isFalse);
    expect(raw.hasMatch("showAppDialog<void>("), isFalse);
  });
}
