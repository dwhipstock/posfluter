import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/widgets/ai_working.dart';

/// The shared "AI is working" card: shown/hidden by [AiWorkingOverlay]
/// without losing the content underneath, ticks a live elapsed counter, and
/// its Cancel button is always reachable.
void main() {
  setUp(() => Prefs.instance.lang = 'en');

  Widget app(Widget child) =>
      prefsScope(child: MaterialApp(theme: buildPosTheme(), home: child));

  testWidgets('inactive: just the child, no card', (tester) async {
    await tester.pumpWidget(
      app(
        AiWorkingOverlay(
          active: false,
          expected: const Duration(seconds: 15),
          onCancel: () {},
          child: const Text('canvas'),
        ),
      ),
    );
    expect(find.text('canvas'), findsOneWidget);
    expect(find.byKey(const Key('ai-working')), findsNothing);
  });

  testWidgets('active: the card sits over the child, which stays mounted', (
    tester,
  ) async {
    var cancelled = false;
    await tester.pumpWidget(
      app(
        AiWorkingOverlay(
          active: true,
          expected: const Duration(seconds: 15),
          onCancel: () => cancelled = true,
          child: const Text('canvas'),
        ),
      ),
    );
    await tester.pump();
    expect(find.text('canvas'), findsOneWidget);
    expect(find.byKey(const Key('ai-working')), findsOneWidget);
    expect(find.byKey(const Key('ai-working-step')), findsOneWidget);
    expect(find.text('0s'), findsOneWidget);

    await tester.tap(find.byKey(const Key('ai-working-cancel')));
    expect(cancelled, isTrue);
  });

  testWidgets('the elapsed counter ticks with real time', (tester) async {
    await tester.pumpWidget(
      app(
        AiWorkingIndicator(
          expected: const Duration(seconds: 15),
          onCancel: () {},
        ),
      ),
    );
    await tester.pump();
    expect(find.text('0s'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
    expect(find.text('3s'), findsOneWidget);
  });
}
