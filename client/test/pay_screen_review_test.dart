import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
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
  // $69.28 due; cash rounds to $69.30
  'cashDueCents': 6930,
  'cashRoundingCents': 2,
};

http.Response _json(Object b, [int s = 200]) => http.Response.bytes(
  utf8.encode(jsonEncode(b)),
  s,
  headers: {'content-type': 'application/json'},
);

/// The real fonts, so labels measure as on the tablet (the test binding
/// otherwise draws every glyph as a wide box).
Future<void> _loadFonts() async {
  final manifest =
      jsonDecode(await rootBundle.loadString('FontManifest.json')) as List;
  for (final entry in manifest) {
    final loader = FontLoader(entry['family'] as String);
    for (final font in entry['fonts'] as List) {
      loader.addFont(rootBundle.load(font['asset'] as String));
    }
    await loader.load();
  }
}

void main() {
  setUpAll(() async {
    await _loadFonts();
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
    int? orderNumber,
    bool counterOrder = false,
    StripeStatus stripe = StripeStatus.off,
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
            stripeStatus: () async => stripe,
            terminalStatus: () async => terminal,
            orderNumber: orderNumber,
            counterOrder: counterOrder,
          ),
        ),
      ),
    );
    await tester.pump();
  }

  testWidgets('a table check says Bill #; a carry-out order says Order #', (
    tester,
  ) async {
    Prefs.instance.lang = 'en';
    final id = Check.fromJson(_check()).id;
    await pump(tester);
    expect(
      tester.widget<Text>(find.byKey(const Key('pay-title'))).data,
      'Pay — Bill #$id',
    );
    await tester.pumpWidget(const SizedBox());
    await pump(tester, orderNumber: 105);
    expect(
      tester.widget<Text>(find.byKey(const Key('pay-title'))).data,
      'Pay — Order #105',
    );
    await tester.pumpWidget(const SizedBox());
    // a new counter order has no number until it is paid: never "Bill #"
    await pump(tester, counterOrder: true);
    expect(
      tester.widget<Text>(find.byKey(const Key('pay-title'))).data,
      'Pay — New order',
    );
    await tester.pumpWidget(const SizedBox());
    await pump(tester, counterOrder: true, orderNumber: 112);
    expect(
      tester.widget<Text>(find.byKey(const Key('pay-title'))).data,
      'Pay — Order #112',
    );
    await tester.pumpWidget(const SizedBox());
    Prefs.instance.lang = 'fr';
    addTearDown(() => Prefs.instance.lang = 'en');
    await pump(tester, orderNumber: 105);
    expect(
      tester.widget<Text>(find.byKey(const Key('pay-title'))).data,
      allOf(startsWith('Payer — Commande n'), endsWith('105')),
    );
  });

  // five tenders on the Express tablet (1920x1200 at 100%: 1280 wide at
  // 150%): every label whole, wrapped between words, in every language
  for (final lang in ['en', 'fr', 'es', 'de', 'af']) {
    testWidgets('five tenders fit, labels whole, at 1280 wide ($lang)', (
      tester,
    ) async {
      Prefs.instance.lang = lang;
      addTearDown(() => Prefs.instance.lang = 'en');
      await pump(
        tester,
        size: const Size(1920, 1200),
        dpr: 1.5,
        stripe: const StripeStatus(configured: true, available: true),
        terminal: const TerminalStatus(kind: 'simulator', available: true),
      );
      await tester.pump();
      expect(tester.view.physicalSize.width / 1.5, 1280);
      for (final t in ['CASH', 'CARD', 'BANK_TRANSFER', 'STRIPE', 'TERMINAL']) {
        final tile = find.byKey(ValueKey('tender-tile-$t'));
        expect(tile, findsOneWidget, reason: t);
        final label = tester.renderObject<RenderParagraph>(
          find.descendant(
            of: find.byKey(ValueKey('tender-label-$t')),
            matching: find.byType(RichText),
          ),
        );
        expect(label.didExceedMaxLines, isFalse, reason: '$lang $t cut off');
        // every word on one line: never split inside a word
        final text = label.text.toPlainText();
        for (final word in text.split(' ')) {
          final start = text.indexOf(word);
          final boxes = label.getBoxesForSelection(
            TextSelection(baseOffset: start, extentOffset: start + word.length),
          );
          expect(
            boxes.map((b) => b.top).toSet(),
            hasLength(1),
            reason: '$lang "$word" split across lines',
          );
        }
        expect(
          tester.getRect(find.byKey(ValueKey('tender-label-$t'))).bottom,
          lessThanOrEqualTo(tester.getRect(tile).bottom),
        );
      }
      expect(tester.takeException(), isNull);
    });
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
    await type(tester, '60');
    expect(find.byKey(const Key('change-due')), findsNothing);
    await type(tester, '⌫');
    await type(tester, '⌫');
    await type(tester, '70');
    expect(find.text('Change due \$0.70'), findsOneWidget);
  });

  testWidgets('cash is capped at what is due + \$1,000, like the server', (
    tester,
  ) async {
    await pump(tester);
    // $69.30 due in cash: at most $1,069.30 — the 4th "9" is kept out
    await type(tester, '9999999');
    expect(find.text('\$999.00'), findsOneWidget);
    expect(find.text('Change due \$929.70'), findsOneWidget);
    expect(find.textContaining('9,999'), findsNothing);
    expect(
      find.text('At most \$1,069.30 in cash for this bill'),
      findsOneWidget,
    );
    // the next key clears the note
    await type(tester, '⌫');
    expect(find.byKey(const Key('cash-capped')), findsNothing);
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
      expect(find.text('Open one now to take cash?'), findsOne);
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
