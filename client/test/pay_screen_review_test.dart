import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/screens/tender_screen.dart';

/// The Copper Lantern review (Pay screen): no dead end without a shift, no
/// cash amount carried over to a card, realistic quick-cash bills, a live
/// "Change due", and the Receive button on screen at tablet sizes.
Map<String, dynamic> _check() => {
  ...jsonDecode(
        File(
          '${Directory.current.path}/test/screenshots/fixtures/check.json',
        ).readAsStringSync(),
      )
      as Map<String, dynamic>,
  // $73.58 due; cash rounds to $73.60
  'cashDueCents': 7360,
  'cashRoundingCents': 2,
};

http.Response _json(Object b, [int s = 200]) => http.Response.bytes(
  utf8.encode(jsonEncode(b)),
  s,
  headers: {'content-type': 'application/json'},
);

void main() {
  setUpAll(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
          (_) async => null,
        );
  });

  group('quick cash', () {
    test('the exact amount, then the next bills up (max five)', () {
      expect(quickCashAmounts(3275), [3275, 3500, 4000, 5000, 10000]);
      expect(quickCashAmounts(7360), [7360, 7500, 8000, 10000]);
      // an exact $20: no second $20 button, the next bills are above it
      expect(quickCashAmounts(2000), [2000, 2500, 3000, 4000, 5000]);
      expect(quickCashAmounts(415), [415, 500, 1000, 2000, 5000]);
      expect(quickCashAmounts(0), isEmpty);
    });
  });

  Future<void> pump(
    WidgetTester tester, {
    Size size = const Size(2560, 1600),
    double dpr = 2,
    TerminalStatus terminal = TerminalStatus.none,
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = dpr;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      prefsScope(
        child: MaterialApp(
          theme: buildPosTheme(),
          home: TenderScreen(
            check: Check.fromJson(_check()),
            stripeStatus: () async => StripeStatus.off,
            terminalStatus: () async => terminal,
          ),
        ),
      ),
    );
    await tester.pump();
  }

  Future<void> type(WidgetTester tester, String digits) async {
    for (final d in digits.split('')) {
      await tester.tap(
        d == '⌫'
            ? find.byIcon(LucideIcons.delete)
            : find.widgetWithText(OutlinedButton, d).last,
      );
      await tester.pump();
    }
  }

  testWidgets('the cash amount does not carry over to Card', (tester) async {
    await pump(tester);
    await type(tester, '50');
    expect(find.text('\$50.00'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('tender-tile-CARD')));
    await tester.pump();
    expect(find.text('\$50.00'), findsNothing);
  });

  testWidgets('a card amount over the bill needs a confirm', (tester) async {
    var initiated = 0;
    final store = MockClient((req) async {
      if (req.url.path == '/checks/1/tenders/initiate') initiated++;
      return _json({'error': 'nope'}, 404);
    });
    await http.runWithClient(() async {
      await pump(tester);
      await tester.tap(find.byKey(const ValueKey('tender-tile-CARD')));
      await tester.pump();
      await type(tester, '90');
      await tester.tap(find.byKey(const ValueKey('electronic-start-CARD')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('over-due-confirm')), findsOneWidget);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(initiated, 0, reason: 'nothing charged without the yes');
    }, () => store);
  });

  testWidgets('change due shows live once the cash covers the total', (
    tester,
  ) async {
    await pump(tester);
    await type(tester, '70');
    expect(find.byKey(const Key('change-due')), findsNothing);
    await type(tester, '⌫');
    await type(tester, '⌫');
    await type(tester, '80');
    expect(find.text('Change due \$6.40'), findsOneWidget);
  });

  testWidgets('card terminal not set up: the button says why and is off', (
    tester,
  ) async {
    await pump(
      tester,
      terminal: const TerminalStatus(
        kind: 'tap_to_pay',
        reason: 'stripe_not_configured',
      ),
    );
    await tester.tap(find.byKey(const ValueKey('tender-tile-CARD')));
    await tester.pump();
    expect(find.byKey(const ValueKey('card-blocked-hint')), findsOneWidget);
    expect(find.textContaining('Settings → Payments'), findsOneWidget);
    final button = tester.widget<ButtonStyleButton>(
      find.byKey(const ValueKey('electronic-start-CARD')),
    );
    expect(button.onPressed, isNull);
  });

  for (final (name, size, dpr) in [
    ('2560x1600 tablet', const Size(2560, 1600), 2.0),
    ('1920x1200', const Size(1920, 1200), 1.5),
    ('1920x1200 at 100%', const Size(1920, 1200), 1.0),
    ('2736x1824 Surface', const Size(2736, 1824), 2.0),
  ]) {
    testWidgets('Receive is on screen without scrolling at $name', (
      tester,
    ) async {
      await pump(tester, size: size, dpr: dpr);
      final receive = find.widgetWithText(FilledButton, 'Receive');
      expect(receive, findsOneWidget);
      final screenH = size.height / dpr;
      expect(tester.getRect(receive).bottom, lessThanOrEqualTo(screenH));
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('no shift open: offer to open one, then the cash goes through', (
    tester,
  ) async {
    Api.currentUser = AuthUser.fromJson({
      'userId': 'mgr',
      'name': 'Manager',
      'role': 'MANAGER',
      'grants': ['open_shift'],
    }, 't');
    addTearDown(() => Api.currentUser = null);
    var shiftOpen = false;
    int? floatCents;
    var tenders = 0;
    final store = MockClient((req) async {
      if (req.url.path == '/shifts' && req.method == 'POST') {
        shiftOpen = true;
        floatCents = jsonDecode(req.body)['openingFloatCents'];
        return _json({
          'id': 1,
          'status': 'OPEN',
          'openedBy': 'mgr',
          'openedAt': '2026-09-29T12:00:00Z',
          'openingFloatCents': floatCents,
        }, 201);
      }
      if (req.url.path == '/checks/1/tenders') {
        tenders++;
        if (!shiftOpen) {
          return _json({
            'error': 'no open shift',
            'code': 'no_open_shift',
          }, 409);
        }
        return _json({
          'tender': {
            'id': 1,
            'type': 'CASH',
            'amountTenderedCents': 8000,
            'amountAppliedCents': 3000,
            'changeCents': 0,
          },
          'check': {..._check(), 'outstandingCents': 4358, 'paidCents': 3000},
        }, 201);
      }
      return _json({'error': 'nope'}, 404);
    });
    await http.runWithClient(() async {
      await pump(tester);
      await type(tester, '30');
      await tester.tap(find.widgetWithText(FilledButton, 'Receive'));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('open-shift-prompt')), findsOneWidget);
      expect(
        find.text('No cash drawer shift is open. Open one now?'),
        findsOne,
      );
      // the float defaults to $200 and can be changed
      expect(find.text('200'), findsOneWidget);
      await tester.enterText(find.byKey(const Key('open-shift-float')), '150');
      await tester.tap(find.byKey(const Key('open-shift-confirm')));
      await tester.pumpAndSettle();
      expect(floatCents, 15000);
      expect(tenders, 2, reason: 'the payment carried on after the shift');
      expect(find.byKey(const Key('open-shift-prompt')), findsNothing);
    }, () => store);
  });
}
