// Probe of issue #240: a target belongs to one server and lasts through its
// last day, and its end is said exactly once.
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/upload_target.dart';

void main() {
  test("a target of one server never steers another, and ends after its day",
      () async {
    var now = DateTime(2026, 10, 12, 23, 59);
    var targets = UploadTargets(store: InMemorySettingsStore(), clock: () => now);
    await targets.load();
    const a = "https://home.example/valbum/data/";
    const b = "https://friends.example/valbum/data/";
    await targets.choose(
        a,
        UploadTarget(
            path: const ["2026", "Mallorca"], until: DateTime(2026, 10, 12)));

    var other = await targets.resolve(b);
    expect(other.target, isNull, reason: "server B still fills its inbox");
    expect(other.ended, isNull);

    var lastDay = await targets.resolve(a);
    expect(lastDay.target?.path, ["2026", "Mallorca"],
        reason: "the last day still counts");

    now = DateTime(2026, 10, 13, 0, 0);
    var over = await targets.resolve(a);
    expect(over.target, isNull);
    expect(over.ended, isNotNull, reason: "the end is said");
    var again = await targets.resolve(a);
    expect(again.ended, isNull, reason: "and said only once");
    expect((await targets.resolve(b)).target, isNull);
  });
}
