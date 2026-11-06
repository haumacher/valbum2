/// The app's way into the background and back, as the platform reports it:
/// state by state, the order an [AppLifecycleListener] insists on.
library;

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

/// The app in the foreground, as at its start.
void resume(WidgetTester tester) =>
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);

/// The app goes to the background: inactive, hidden, paused.
void toBackground(WidgetTester tester) {
  for (var state in const [
    AppLifecycleState.inactive,
    AppLifecycleState.hidden,
    AppLifecycleState.paused,
  ]) {
    tester.binding.handleAppLifecycleStateChanged(state);
  }
}

/// The app comes back to the foreground: hidden, inactive, resumed.
void toForeground(WidgetTester tester) {
  for (var state in const [
    AppLifecycleState.hidden,
    AppLifecycleState.inactive,
    AppLifecycleState.resumed,
  ]) {
    tester.binding.handleAppLifecycleStateChanged(state);
  }
}
