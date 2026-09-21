import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:pos_client/connection_monitor.dart';

/// Regression guards for the reconnect barrier's self-heal guarantees
/// (docs/known-issues/client-reconnect-freeze.md): a kiosk POS must never
/// stay input-locked once the server is back, even if the post-frame
/// callback path never runs.
void main() {
  final navKey = GlobalKey<NavigatorState>();

  Widget app({Widget? home}) => MaterialApp(
    navigatorKey: navKey,
    home: home ?? const Scaffold(body: Center(child: Text('floor'))),
  );

  void goOffline() {
    ConnectionMonitor.instance.reportFailure();
    ConnectionMonitor.instance.reportFailure();
  }

  // flutter_test asserts no pending timers when the BODY ends (before
  // tearDown), so every test must flush the 600ms fallback one-shots and
  // cancel the periodic watchdog itself.
  Future<void> finish(WidgetTester tester) async {
    ConnectionMonitor.instance.reportSuccess(); // online → probe timer gone
    await tester.pump(const Duration(seconds: 1)); // fire fallback one-shots
    ReconnectingOverlay.detach(); // cancels the periodic watchdog
    await tester.pump();
  }

  setUp(() {
    ConnectionMonitor.instance.debugReset();
    ConnectionMonitor.findRestaurant = null;
  });

  tearDown(() {
    ReconnectingOverlay.detach();
    ReconnectingOverlay.debugDropPostFrameSync = false;
    ReconnectingOverlay.onEscape = null;
    ConnectionMonitor.findRestaurant = null;
    ConnectionMonitor.instance.debugReset();
  });

  testWidgets('barrier inserts on offline and removes on recovery', (
    tester,
  ) async {
    await tester.pumpWidget(app());
    ReconnectingOverlay.attach(navKey);

    goOffline();
    await tester.pump();
    await tester.pump();
    expect(find.byType(ModalBarrier), findsWidgets);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);

    ConnectionMonitor.instance.reportSuccess();
    await tester.pump();
    await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsNothing);
    await finish(tester);
  });

  testWidgets('barrier blocks taps while offline', (tester) async {
    var taps = 0;
    await tester.pumpWidget(
      app(
        home: Scaffold(
          body: Center(
            child: TextButton(
              onPressed: () => taps++,
              child: const Text('tap me'),
            ),
          ),
        ),
      ),
    );
    ReconnectingOverlay.attach(navKey);

    goOffline();
    await tester.pump();
    await tester.pump();
    await tester.tap(find.text('tap me'), warnIfMissed: false);
    expect(taps, 0);

    ConnectionMonitor.instance.reportSuccess();
    await tester.pump();
    await tester.pump();
    await tester.tap(find.text('tap me'));
    expect(taps, 1);
    await finish(tester);
  });

  testWidgets('teardown does NOT depend on the post-frame callback path '
      '(timer fallback heals it)', (tester) async {
    await tester.pumpWidget(app());
    ReconnectingOverlay.attach(navKey);

    // Insert normally, then cut the post-frame path entirely: only the
    // 600ms fallback timer / 2s watchdog may apply the recovery.
    goOffline();
    await tester.pump();
    await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsOneWidget);

    ReconnectingOverlay.debugDropPostFrameSync = true;
    ConnectionMonitor.instance.reportSuccess();
    // No notification-driven frame: advance the clock so timers fire.
    await tester.pump(const Duration(milliseconds: 700));
    await tester.pump();
    expect(
      find.byType(CircularProgressIndicator),
      findsNothing,
      reason:
          'fallback timer must remove the barrier without a '
          'notification-driven post-frame callback',
    );
    await finish(tester);
  });

  testWidgets('watchdog reconciles a desynced barrier', (tester) async {
    await tester.pumpWidget(app());
    ReconnectingOverlay.attach(navKey);

    // Pathological case: every per-change apply path is lost (post-frame
    // dropped). The 2s periodic watchdog alone must converge the overlay
    // to the monitor's state, in both directions.
    ReconnectingOverlay.debugDropPostFrameSync = true;
    goOffline();
    await tester.pump(const Duration(seconds: 3)); // watchdog inserts
    await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsOneWidget);

    ConnectionMonitor.instance.reportSuccess();
    await tester.pump(const Duration(seconds: 3)); // watchdog removes
    await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsNothing);
    await finish(tester);
  });

  testWidgets('escape hatch appears after a sustained outage', (tester) async {
    var escaped = false;
    ReconnectingOverlay.onEscape = () => escaped = true;
    await tester.pumpWidget(app());
    ReconnectingOverlay.attach(navKey);

    goOffline();
    await tester.pump();
    await tester.pump();
    expect(find.byType(TextButton), findsNothing);

    await tester.pump(const Duration(seconds: 21));
    expect(find.byType(TextButton), findsOneWidget);
    await tester.tap(find.byType(TextButton));
    expect(escaped, isTrue);
    await finish(tester);
  });

  testWidgets('suppressed screens keep the barrier away', (tester) async {
    await tester.pumpWidget(app());
    ReconnectingOverlay.attach(navKey);

    ConnectionMonitor.instance.pushSuppress();
    goOffline();
    await tester.pump(const Duration(seconds: 3));
    await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsNothing);

    // un-suppress while still offline → barrier appears
    ConnectionMonitor.instance.popSuppress();
    await tester.pump(const Duration(seconds: 3));
    await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    await finish(tester);
  });

  testWidgets(
    'offline monitor can recover by finding the restaurant on Wi-Fi',
    (tester) async {
      var discoveries = 0;
      ConnectionMonitor.findRestaurant = () async {
        discoveries++;
        return true;
      };
      await tester.pumpWidget(app());
      ReconnectingOverlay.attach(navKey);

      goOffline();
      await tester.pump();
      await tester.pump();
      expect(find.byType(CircularProgressIndicator), findsOneWidget);

      await tester.pump(const Duration(seconds: 4));
      await tester.pump();
      expect(discoveries, greaterThan(0));
      expect(find.byType(CircularProgressIndicator), findsNothing);
      await finish(tester);
    },
  );
}
