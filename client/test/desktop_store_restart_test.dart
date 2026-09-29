import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/desktop_store.dart';

void main() {
  test('three missed health checks in a row ask for a restart', () {
    final p = StoreRestartPolicy();
    expect(p.recordHealth(false), isFalse);
    expect(p.recordHealth(false), isFalse);
    expect(p.recordHealth(true), isFalse); // an answer resets the count
    expect(p.recordHealth(false), isFalse);
    expect(p.recordHealth(false), isFalse);
    expect(p.recordHealth(false), isTrue);
  });

  test('restarts back off and stop at five a minute', () {
    final p = StoreRestartPolicy();
    final t0 = DateTime(2026, 9, 29, 12);
    final delays = [
      for (var i = 0; i < 5; i++) p.nextRestartDelay(t0)!.inSeconds,
    ];
    expect(delays, [1, 2, 4, 8, 16]);
    expect(p.nextRestartDelay(t0.add(const Duration(seconds: 30))), isNull);
    // a minute later it may try again, from the short delay
    expect(
      p.nextRestartDelay(t0.add(const Duration(minutes: 1))),
      const Duration(seconds: 1),
    );
  });
}
