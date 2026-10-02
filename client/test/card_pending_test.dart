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
import 'package:pos_client/screens/tender_screen.dart';

/// A card payment left on the reader by a restart: the pay screen says so,
/// follows it, keeps every other tender waiting, and once it is settled says
/// how (recorded: what was paid and what is still due; not taken: pay
/// another way). A manager can cancel it on the reader.
Map<String, dynamic> _checkJson() => jsonDecode(
  File(
    '${Directory.current.path}/test/screenshots/fixtures/check.json',
  ).readAsStringSync(),
);

Map<String, dynamic> _check({
  bool pending = false,
  int paid = 0,
  String status = 'OPEN',
}) {
  final c = _checkJson();
  final total = c['grandTotalCents'] as int;
  return {
    ...c,
    'status': status,
    'cardPaymentPending': pending,
    'paidCents': paid,
    'outstandingCents': total - paid,
    'cashDueCents': total - paid,
    'cashRoundingCents': 0,
  };
}

Map<String, dynamic> _card(String status, {String prompt = 'present_card'}) => {
  'paymentId': 'p1',
  'source': 'terminal',
  'provider': 'simulator',
  'checkId': 1,
  'amountCents': 3000,
  'status': status,
  'prompt': prompt,
};

http.Response _json(Object body, [int status = 200]) => http.Response.bytes(
  utf8.encode(jsonEncode(body)),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

const _noTerminal = TerminalStatus(
  kind: 'simulator',
  integrated: true,
  available: true,
  readerState: 'idle',
  embedded: true,
);

void main() {
  setUpAll(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
          (_) async => null,
        );
  });

  Future<void> pumpTender(WidgetTester tester, Check check) async {
    tester.view.physicalSize = const Size(1920, 1200);
    tester.view.devicePixelRatio = 1.5;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      prefsScope(
        child: MaterialApp(
          theme: buildPosTheme(),
          home: TenderScreen(
            check: check,
            stripeStatus: () async => StripeStatus.off,
            terminalStatus: () async => _noTerminal,
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
  }

  testWidgets('pending card on open: banner, tenders wait, then "recorded — '
      'still due" and the tenders come back', (tester) async {
    Prefs.instance.lang = 'en';
    var polls = 0;
    final posted = <String>[];
    final store = MockClient((req) async {
      final path = req.url.path;
      if (req.method == 'POST') posted.add(path);
      if (path == '/checks/1/card-pending') {
        polls++;
        return polls == 1
            ? _json({
                'checkId': 1,
                'pending': [_card('PENDING')],
                'resolved': [],
                'check': _check(pending: true, status: 'TOTAL_LOCKED'),
              })
            : _json({
                'checkId': 1,
                'pending': [],
                'resolved': [_card('RECORDED')],
                'check': _check(paid: 3000, status: 'TOTAL_LOCKED'),
              });
      }
      return _json({'code': 'not_found'}, 404);
    });
    await http.runWithClient(() async {
      await pumpTender(
        tester,
        Check.fromJson(_check(pending: true, status: 'TOTAL_LOCKED')),
      );
      final l = L.forLang('en');
      expect(find.byKey(const ValueKey('card-pending-banner')), findsOneWidget);
      expect(find.text(l.cardPendingTitle), findsOneWidget);
      expect(find.textContaining(l.terminalPresentCard), findsOneWidget);
      // cash waits: the pad and the quick amounts don't take a tap
      expect(
        find.byKey(const ValueKey('tenders-wait-for-card')),
        findsOneWidget,
      );
      await tester.tap(
        find.byKey(const ValueKey('tender-tile-CARD')),
        warnIfMissed: false,
      );
      await tester.tap(
        find
            .descendant(
              of: find.byKey(const ValueKey('tenders-wait-for-card')),
              matching: find.byType(OutlinedButton),
            )
            .first,
        warnIfMissed: false,
      );
      await tester.pump();
      expect(
        posted,
        isEmpty,
        reason: 'no tender while the card is on the reader',
      );

      // the next poll: the reader approved it while the store was down
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.byKey(const ValueKey('card-pending-banner')), findsNothing);
      final total = _checkJson()['grandTotalCents'] as int;
      expect(
        find.text(l.cardPendingRecorded(money(3000), money(total - 3000))),
        findsOneWidget,
      );
      expect(find.byKey(const ValueKey('tenders-wait-for-card')), findsNothing);
      expect(find.text(money(total - 3000)), findsWidgets);
    }, () => store);
  });

  testWidgets('cash refused with card_payment_pending: the banner takes over, '
      'and a manager cancels the card on the reader', (tester) async {
    Prefs.instance.lang = 'en';
    Api.currentUser = AuthUser.fromJson({
      'userId': 'manager',
      'name': 'Demo Manager',
      'role': 'MANAGER',
      'languageCode': 'en',
      'grants': ['void'],
    }, 'test-token');
    addTearDown(() => Api.currentUser = null);
    final posted = <String>[];
    final store = MockClient((req) async {
      final path = req.url.path;
      if (req.method == 'POST') posted.add(path);
      if (path == '/checks/1/tenders') {
        return _json({
          'code': 'card_payment_pending',
          'error': 'a card payment is still in progress on the reader',
          'pending': [_card('PENDING')],
        }, 409);
      }
      if (path == '/checks/1/card-pending') {
        return _json({
          'checkId': 1,
          'pending': [_card('PENDING', prompt: 'enter_pin')],
          'resolved': [],
          'check': _check(pending: true, status: 'TOTAL_LOCKED'),
        });
      }
      if (path == '/checks/1/card-pending/p1/cancel') {
        return _json({
          'checkId': 1,
          'pending': [],
          'resolved': [_card('CANCELED')],
          'check': _check(),
        });
      }
      return _json({'code': 'not_found'}, 404);
    });
    await http.runWithClient(() async {
      await pumpTender(tester, Check.fromJson(_check()));
      final l = L.forLang('en');
      expect(find.byKey(const ValueKey('card-pending-banner')), findsNothing);
      // the exact cash amount: the store says a card is still on the reader
      final total = _checkJson()['grandTotalCents'] as int;
      await tester.tap(find.widgetWithText(OutlinedButton, money(total)).first);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      expect(posted, ['/checks/1/tenders']);
      expect(find.byKey(const ValueKey('card-pending-banner')), findsOneWidget);
      expect(find.textContaining(l.terminalEnterPin), findsOneWidget);
      expect(find.text(l.cashReceivedTitle), findsNothing);
      expect(
        find.byKey(const ValueKey('tenders-wait-for-card')),
        findsOneWidget,
      );

      await tester.tap(find.byKey(const ValueKey('card-pending-cancel-p1')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      expect(posted.last, '/checks/1/card-pending/p1/cancel');
      expect(find.text(l.cardPendingNotTaken), findsOneWidget);
      expect(find.byKey(const ValueKey('tenders-wait-for-card')), findsNothing);
    }, () => store);
  });
}
