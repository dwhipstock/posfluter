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
import 'package:qr_flutter/qr_flutter.dart';

/// PromptPay QR: offered only at a THB store with an id, shows the QR with
/// the amount and account name under it, and Paid records the tender.
Map<String, dynamic> _checkJson() => jsonDecode(
  File(
    '${Directory.current.path}/test/screenshots/fixtures/check.json',
  ).readAsStringSync(),
);

const _thaiStore = StoreProfile(
  brand: 'barcode',
  country: 'TH',
  currency: 'THB',
  locales: ['th', 'en'],
  promptPay: true,
);

void main() {
  setUpAll(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
          (_) async => null,
        );
  });

  final tile = find.byKey(const ValueKey('tender-tile-PROMPTPAY'));

  Future<List<String>> pumpTender(
    WidgetTester tester,
    StoreProfile profile, {
    String lang = 'en',
    Future<void> Function()? act,
  }) async {
    final before = StoreProfile.current;
    StoreProfile.current = profile;
    Prefs.instance.lang = lang;
    addTearDown(() {
      StoreProfile.current = before;
      Prefs.instance.lang = 'en';
    });
    tester.view.physicalSize = const Size(1920, 1200);
    tester.view.devicePixelRatio = 1.5;
    addTearDown(tester.view.reset);
    final posts = <String>[];
    final store = MockClient((req) async {
      posts.add('${req.method} ${req.url.path} ${req.body}');
      final body = switch (req.url.path) {
        '/checks/1/tenders/initiate' => {
          'type': 'PROMPTPAY',
          'amountCents': 6928,
          'qrPayload': '00020101021229370016A000000677010111fake6304ABCD',
          'displayFields': [
            {
              'labelFr': 'ชื่อบัญชี',
              'labelEn': 'Account name',
              'value': 'TEST SHOP',
            },
          ],
        },
        '/checks/1/tenders/confirm' => {
          'tender': {
            'id': 9,
            'type': 'PROMPTPAY',
            'amountTenderedCents': 6928,
            'amountAppliedCents': 6928,
            'roundingAdjustmentCents': 0,
            'changeCents': 0,
            'groupId': null,
          },
          'check': _checkJson(),
        },
        _ => null,
      };
      return body == null
          ? http.Response('{"code":"not_found"}', 404)
          : http.Response.bytes(utf8.encode(jsonEncode(body)), 201);
    });
    await http.runWithClient(() async {
      await tester.pumpWidget(
        prefsScope(
          child: MaterialApp(
            theme: buildPosTheme(),
            home: TenderScreen(
              check: Check.fromJson(_checkJson()),
              stripeStatus: () async => StripeStatus.off,
              terminalStatus: () async => TerminalStatus.none,
            ),
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      if (act != null) await act();
    }, () => store);
    return posts;
  }

  testWidgets('a THB store with an id: QR, amount, name, then Paid', (
    tester,
  ) async {
    final posts = await pumpTender(
      tester,
      _thaiStore,
      act: () async {
        expect(tile, findsOneWidget);
        expect(find.text('PromptPay'), findsOneWidget);
        await tester.tap(tile);
        await tester.pump();
        await tester.tap(
          find.byKey(const ValueKey('electronic-start-PROMPTPAY')),
        );
        for (var i = 0; i < 5; i++) {
          await tester.pump(const Duration(milliseconds: 50));
        }
        expect(find.byKey(const ValueKey('promptpay-qr')), findsOneWidget);
        expect(find.byType(QrImageView), findsOneWidget);
        expect(find.text('TEST SHOP'), findsOneWidget);
        expect(find.byKey(const ValueKey('promptpay-amount')), findsOneWidget);
        expect(find.text(money(6928)), findsWidgets);
        expect(find.text('Paid'), findsOneWidget);
        expect(find.text('Cancel'), findsOneWidget);
        await tester.tap(find.byKey(const ValueKey('promptpay-paid')));
        for (var i = 0; i < 5; i++) {
          await tester.pump(const Duration(milliseconds: 50));
        }
      },
    );
    expect(posts.first, contains('/checks/1/tenders/initiate'));
    expect(posts.first, contains('"type":"PROMPTPAY"'));
    expect(posts.last, contains('/checks/1/tenders/confirm'));
    expect(posts.last, contains('"amountCents":6928'));
    // recorded: back to the tiles
    expect(find.byKey(const ValueKey('promptpay-qr')), findsNothing);
  });

  testWidgets('Cancel records nothing and goes back', (tester) async {
    final posts = await pumpTender(
      tester,
      _thaiStore,
      act: () async {
        await tester.tap(tile);
        await tester.pump();
        await tester.tap(
          find.byKey(const ValueKey('electronic-start-PROMPTPAY')),
        );
        for (var i = 0; i < 5; i++) {
          await tester.pump(const Duration(milliseconds: 50));
        }
        await tester.tap(find.byKey(const ValueKey('promptpay-cancel')));
        await tester.pump();
      },
    );
    expect(posts.where((p) => p.contains('/confirm')), isEmpty);
    expect(find.byKey(const ValueKey('promptpay-qr')), findsNothing);
  });

  testWidgets('Thai labels', (tester) async {
    await pumpTender(
      tester,
      _thaiStore,
      lang: 'th',
      act: () async {
        expect(find.text('พร้อมเพย์'), findsOneWidget);
        await tester.tap(tile);
        await tester.pump();
        await tester.tap(
          find.byKey(const ValueKey('electronic-start-PROMPTPAY')),
        );
        for (var i = 0; i < 5; i++) {
          await tester.pump(const Duration(milliseconds: 50));
        }
        expect(find.text('ได้รับเงินแล้ว'), findsOneWidget);
      },
    );
  });

  testWidgets('not offered without an id or outside THB', (tester) async {
    await pumpTender(
      tester,
      const StoreProfile(currency: 'THB', locales: ['th', 'en']),
    );
    expect(tile, findsNothing);
  });

  testWidgets('not offered at a USD store even if flagged', (tester) async {
    await pumpTender(tester, const StoreProfile(promptPay: true));
    expect(tile, findsNothing);
  });

  test('/health promptPay flag', () {
    expect(StoreProfile.fromHealth({'promptPay': true}).promptPay, isTrue);
    expect(StoreProfile.fromHealth({}).promptPay, isFalse);
  });
}
