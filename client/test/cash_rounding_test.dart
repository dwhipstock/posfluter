import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/retail/pay_sheet.dart';
import 'package:pos_client/retail/sp_theme.dart';
import 'package:pos_client/screens/tender_screen.dart';

/// Cash nickel rounding: the server computes it, the client only shows it —
/// a "Rounding" line and the rounded "Cash total" when paying cash; card
/// stays exact; older servers (no fields) behave as before.
Map<String, dynamic> _pubCheck() => jsonDecode(
  File(
    '${Directory.current.path}/test/screenshots/fixtures/check.json',
  ).readAsStringSync(),
);

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

  group('parsing', () {
    test('old JSON without the rounding fields still works', () {
      final c = Check.fromJson(_pubCheck());
      expect(c.outstandingCents, 7358);
      expect(c.cashDueCents, 7358, reason: 'cash due falls back to exact');
      expect(c.cashRoundingCents, 0);

      final t = Tender.fromJson({
        'id': 1,
        'type': 'CASH',
        'amountTenderedCents': 2000,
        'amountAppliedCents': 1081,
        'changeCents': 919,
      });
      expect(t.roundingAdjustmentCents, 0);
      expect(t.cashPaidCents, 1081);

      final r = RefundView.fromJson({
        'id': 1,
        'checkId': 1,
        'grossCents': 1003,
        'netCents': 900,
        'taxCents': 103,
        'tenderType': 'CASH',
        'reason': 'x',
        'refundedBy': 'a',
        'createdAt': 'now',
      });
      expect(r.roundingAdjustmentCents, 0);
      expect(r.paidOutCents, 1003);

      final g = BillGroup.fromJson({
        'id': 1,
        'number': 1,
        'itemsSubtotalCents': 500,
        'grandTotalCents': 574,
        'paidCents': 0,
        'outstandingCents': 574,
      });
      expect(g.cashDueCents, 574);
      expect(g.cashRoundingCents, 0);

      final rep = ShiftReport.fromJson(_shiftJson());
      expect(rep.cashRoundingCents, 0);
      expect(rep.expectedCashCents, isNull);
    });

    test('new fields are read from the server', () {
      final c = Check.fromJson({
        ..._pubCheck(),
        'cashDueCents': 7360,
        'cashRoundingCents': 2,
      });
      expect(c.cashDueCents, 7360);
      expect(c.cashRoundingCents, 2);

      final r = RefundView.fromJson({
        'id': 1,
        'checkId': 1,
        'grossCents': 1003,
        'netCents': 900,
        'taxCents': 103,
        'tenderType': 'CASH',
        'reason': 'x',
        'refundedBy': 'a',
        'createdAt': 'now',
        'roundingAdjustmentCents': 2,
        'paidOutCents': 1005,
      });
      expect(r.paidOutCents, 1005);
      expect(r.roundingAdjustmentCents, 2);

      final rep = ShiftReport.fromJson({
        ..._shiftJson(),
        'cashRoundingCents': -3,
        'expectedCashCents': 20000,
      });
      expect(rep.cashRoundingCents, -3);
      expect(rep.expectedCashCents, 20000);
    });

    test('signed money shows the sign', () {
      expect(signedMoney(-2, currency: 'CAD'), '−\$0.02');
      expect(signedMoney(1, currency: 'USD'), '+\$0.01');
    });
  });

  group('pub tender screen (CAD)', () {
    Future<void> pump(WidgetTester tester, Map<String, dynamic> check) async {
      tester.view.physicalSize = const Size(1920, 1200);
      tester.view.devicePixelRatio = 1.5;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
        prefsScope(
          child: MaterialApp(
            theme: buildPosTheme(),
            home: TenderScreen(
              check: Check.fromJson(check),
              stripeStatus: () async => StripeStatus.off,
            ),
          ),
        ),
      );
      await tester.pump();
    }

    final rounded = {
      ..._pubCheck(),
      'cashDueCents': 7360,
      'cashRoundingCents': 2,
    };

    testWidgets('cash shows the rounding and the cash total from the server', (
      tester,
    ) async {
      await pump(tester, rounded);
      expect(find.byKey(const ValueKey('cash-rounding')), findsOneWidget);
      expect(find.text('Rounding'), findsOneWidget);
      expect(find.text('+\$0.02'), findsOneWidget);
      expect(find.text('Cash total'), findsOneWidget);
      // cash total row + the "exact" quick button
      expect(find.text('\$73.60'), findsNWidgets(2));
      expect(find.text('\$73.58'), findsOneWidget, reason: 'outstanding');
    });

    testWidgets('card stays exact: no rounding line', (tester) async {
      await pump(tester, rounded);
      await tester.tap(find.byKey(const ValueKey('tender-tile-CARD')));
      await tester.pump();
      expect(find.byKey(const ValueKey('cash-rounding')), findsNothing);
      expect(find.text('Rounding'), findsNothing);
      expect(find.text('\$73.60'), findsNothing);
      expect(find.textContaining('\$73.58'), findsWidgets);
    });

    testWidgets('no rounding → no rounding line', (tester) async {
      await pump(tester, {
        ..._pubCheck(),
        'cashDueCents': 7358,
        'cashRoundingCents': 0,
      });
      expect(find.byKey(const ValueKey('cash-rounding')), findsNothing);
      expect(find.text('Rounding'), findsNothing);
    });

    testWidgets('French label', (tester) async {
      Prefs.instance.lang = 'fr';
      addTearDown(() => Prefs.instance.lang = 'en');
      await pump(tester, rounded);
      expect(find.text('Arrondi'), findsOneWidget);
      expect(find.text('Total comptant'), findsOneWidget);
    });

    testWidgets('after the cash tender: rounding and cash total', (
      tester,
    ) async {
      final store = MockClient((req) async {
        if (req.url.path == '/checks/1/tenders') {
          expect(jsonDecode(req.body)['amountTenderedCents'], 7360);
          return _json({
            'tender': {
              'id': 1,
              'type': 'CASH',
              'amountTenderedCents': 7360,
              'amountAppliedCents': 7358,
              'roundingAdjustmentCents': 2,
              'changeCents': 0,
            },
            'check': {
              ...rounded,
              'status': 'TOTAL_LOCKED',
              'paidCents': 7358,
              'outstandingCents': 0,
              'cashDueCents': 0,
              'cashRoundingCents': 0,
            },
          }, 201);
        }
        return _json({'error': 'nope'}, 404);
      });
      await http.runWithClient(() async {
        await pump(tester, rounded);
        // the "exact" quick button is the rounded cash due
        await tester.tap(find.widgetWithText(OutlinedButton, '\$73.60'));
        await tester.pumpAndSettle();
        final dialog = find.byType(AlertDialog);
        expect(dialog, findsOneWidget);
        expect(
          find.descendant(of: dialog, matching: find.text('Rounding')),
          findsOneWidget,
        );
        expect(
          find.descendant(of: dialog, matching: find.text('+\$0.02')),
          findsOneWidget,
        );
        expect(
          find.descendant(of: dialog, matching: find.text('Cash total')),
          findsOneWidget,
        );
      }, () => store);
    });
  });

  group('retail pay sheet (USD)', () {
    Map<String, dynamic> sale(
      int outstanding,
      String status, {
      int? cashDue,
      int rounding = 0,
    }) => {
      'id': 3,
      'tableId': 'register-1',
      'status': status,
      'corkageBottles': 0,
      'lines': [],
      'pendingLines': [],
      'fees': [],
      'itemsSubtotalCents': 1081,
      'grandTotalCents': 1081,
      'paidCents': 1081 - outstanding,
      'outstandingCents': outstanding,
      'cashDueCents': cashDue ?? outstanding,
      'cashRoundingCents': rounding,
      'tenders': [],
    };

    setUp(() {
      StoreProfile.current = const StoreProfile(
        brand: 'sage-poppy',
        kind: 'retail',
        country: 'US',
        currency: 'USD',
        locales: ['en', 'es'],
      );
      Prefs.instance.lang = 'en';
    });
    tearDown(() => StoreProfile.current = StoreProfile.pub);

    Future<void> open(WidgetTester tester, Map<String, dynamic> s) async {
      tester.view.physicalSize = const Size(1920, 1200);
      tester.view.devicePixelRatio = 1.5;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
        prefsScope(
          child: MaterialApp(
            theme: buildSagePoppyTheme(Brightness.light),
            home: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: TextButton(
                    onPressed: () => PaySheet.show(context, Check.fromJson(s)),
                    child: const Text('open'),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
    }

    testWidgets('cash shows the rounded total; card is exact', (tester) async {
      await open(tester, sale(1081, 'OPEN', cashDue: 1080, rounding: -1));
      expect(find.text('CASH TOTAL'), findsOneWidget);
      expect(
        find.text('Amount due \$10.81 · Rounding −\$0.01'),
        findsOneWidget,
      );
      expect(find.text('Exact · \$10.80'), findsOneWidget);

      await tester.tap(find.text('Card (external terminal)'));
      await tester.pumpAndSettle();
      expect(find.text('AMOUNT DUE'), findsOneWidget);
      expect(find.text('\$10.81'), findsOneWidget);
      expect(find.byKey(const ValueKey('cash-rounding')), findsNothing);
    });

    testWidgets('no rounding → plain amount due', (tester) async {
      await open(tester, sale(1080, 'OPEN'));
      expect(find.text('AMOUNT DUE'), findsOneWidget);
      expect(find.byKey(const ValueKey('cash-rounding')), findsNothing);
      expect(find.text('Exact · \$10.80'), findsOneWidget);
    });

    testWidgets('Spanish labels', (tester) async {
      Prefs.instance.lang = 'es';
      await open(tester, sale(1081, 'OPEN', cashDue: 1080, rounding: -1));
      expect(find.text('TOTAL EN EFECTIVO'), findsOneWidget);
      expect(find.textContaining('Redondeo −\$0.01'), findsOneWidget);
    });

    testWidgets('paid: rounding, cash total and change from the tender', (
      tester,
    ) async {
      final store = MockClient((req) async {
        if (req.url.path == '/checks/3/tenders') {
          return _json({
            'tender': {
              'id': 1,
              'type': 'CASH',
              'amountTenderedCents': 2000,
              'amountAppliedCents': 1081,
              'roundingAdjustmentCents': -1,
              'changeCents': 920,
            },
            'check': sale(0, 'TOTAL_LOCKED'),
          }, 201);
        }
        if (req.url.path == '/checks/3/finalize') {
          return _json(sale(0, 'CLOSED'));
        }
        return _json({'error': 'nope'}, 404);
      });
      await http.runWithClient(() async {
        await open(tester, sale(1081, 'OPEN', cashDue: 1080, rounding: -1));
        await tester.tap(find.text('\$20.00'));
        await tester.pumpAndSettle();
        expect(find.text('Paid'), findsOneWidget);
        expect(
          find.text('Cash total \$10.80 · Rounding −\$0.01'),
          findsOneWidget,
        );
        expect(find.text('Change \$9.20'), findsOneWidget);
      }, () => store);
    });
  });
}

Map<String, dynamic> _shiftJson() => {
  'shiftId': 1,
  'shiftStatus': 'OPEN',
  'openedAt': '2026-09-25T09:00:00-04:00',
  'openedBy': 'Sam',
  'openingFloatCents': 20000,
  'revenueCents': 0,
  'transactionCount': 0,
  'avgCheckCents': 0,
  'corkageCents': 0,
  'tenderBreakdown': [],
  'itemMix': [],
  'voids': [],
};
