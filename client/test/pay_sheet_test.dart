import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/retail/pay_sheet.dart';
import 'package:pos_client/retail/sp_theme.dart';

/// Cash at the counter: pay, see the change, and the sheet hands back the
/// closed sale when the cashier asks for the receipt.
void main() {
  Map<String, dynamic> check(int outstanding, String status) => {
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

  testWidgets('cash, change, then the receipt closes the sheet', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1920, 1200);
    tester.view.devicePixelRatio = 1.5;
    addTearDown(tester.view.reset);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
          (_) async => null,
        );
    final store = MockClient((req) async {
      http.Response json(Object b, [int s = 200]) => http.Response.bytes(
        utf8.encode(jsonEncode(b)),
        s,
        headers: {'content-type': 'application/json'},
      );
      if (req.url.path == '/checks/3/tenders') {
        return json({
          'tender': {
            'id': 1,
            'type': 'CASH',
            'amountTenderedCents': 2000,
            'amountAppliedCents': 1081,
            'roundingAdjustmentCents': 0,
            'changeCents': 919,
          },
          'check': check(0, 'TOTAL_LOCKED'),
        }, 201);
      }
      if (req.url.path == '/checks/3/finalize') return json(check(0, 'CLOSED'));
      return json({'error': 'nope'}, 404);
    });
    PayResult? result;
    await http.runWithClient(() async {
      await tester.pumpWidget(
        prefsScope(
          child: MaterialApp(
            theme: buildSagePoppyTheme(Brightness.light),
            home: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: TextButton(
                    onPressed: () async => result = await PaySheet.show(
                      context,
                      Check.fromJson(check(1081, 'OPEN')),
                    ),
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
      expect(find.text('\$10.81'), findsWidgets);
      await tester.tap(find.text('\$20.00'));
      await tester.pumpAndSettle();
      expect(find.text('Change \$9.19'), findsOneWidget);
      await tester.tap(find.text('Receipt'));
      await tester.pumpAndSettle();
      expect(result?.check.status, 'CLOSED');
      expect(result?.changeCents, 919);
      expect(find.text('Paid'), findsNothing);
    }, () => store);
  });
}
