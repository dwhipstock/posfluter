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
import 'package:pos_client/payments/terminal.dart';
import 'package:pos_client/payments/terminal_settings.dart';
import 'package:pos_client/retail/pay_sheet.dart';
import 'package:pos_client/screens/sales_screen.dart';
import 'package:pos_client/screens/tender_screen.dart';
import 'package:pos_client/screens/terminal_payment_screen.dart';

/// "Card (terminal)": a card payment on a terminal the store drives (the
/// built-in simulator, a LAN simulator, J.P. Morgan). The tablet starts it,
/// follows it, and pops with the tender the store recorded — or with nothing.
Map<String, dynamic> _checkJson() => jsonDecode(
  File(
    '${Directory.current.path}/test/screenshots/fixtures/check.json',
  ).readAsStringSync(),
);

TerminalPayment _pending([String prompt = 'present_card']) => TerminalPayment(
  paymentId: 'p1',
  status: 'PENDING',
  amountCents: 2328,
  prompt: prompt,
);

TerminalPayment _recorded() => TerminalPayment.fromJson({
  'paymentId': 'p1',
  'status': 'RECORDED',
  'amountCents': 2328,
  'currency': 'CAD',
  'card': {
    'brand': 'Visa',
    'last4': '4242',
    'entryMode': 'TAP',
    'authCode': '123456',
    'processorRef': 'jpm-txn-1',
    'processor': 'J.P. Morgan sandbox (mock)',
  },
  'tender': {
    'id': 9,
    'type': 'TERMINAL',
    'amountTenderedCents': 2328,
    'amountAppliedCents': 2328,
    'roundingAdjustmentCents': 0,
    'changeCents': 0,
  },
  'check': _checkJson(),
});

class _FakeTerminal extends TerminalClient {
  _FakeTerminal(this.polls);
  final List<TerminalPayment> polls;
  int started = 0, polled = 0, cancelled = 0;
  String? tipMode;

  @override
  Future<TerminalPayment> start(
    int checkId, {
    int? amountCents,
    int? groupId,
    String tipMode = 'none',
  }) async {
    started++;
    this.tipMode = tipMode;
    return _pending();
  }

  @override
  Future<TerminalPayment> poll(String paymentId) async {
    final p = polls[polled.clamp(0, polls.length - 1)];
    polled++;
    return p;
  }

  @override
  Future<TerminalPayment> cancel(String paymentId) async {
    cancelled++;
    return const TerminalPayment(
      paymentId: 'p1',
      status: 'CANCELED',
      amountCents: 2328,
    );
  }
}

class _FakeReader extends SimReaderClient {
  _FakeReader(this.screen);
  SimScreen screen;
  final presented = <List<String>>[];
  final pins = <String>[];

  @override
  Future<SimScreen> state() async => screen;
  @override
  Future<SimScreen> present(String entry, String card, String outcome) async {
    presented.add([entry, card, outcome]);
    screen = entry == 'insert'
        ? const SimScreen(screen: 'pin', totalCents: 2328)
        : const SimScreen(screen: 'processing', totalCents: 2328);
    return screen;
  }

  @override
  Future<SimScreen> pin(String pin) async {
    pins.add(pin);
    return screen = const SimScreen(screen: 'processing', totalCents: 2328);
  }
}

class _PairingClient extends TerminalClient {
  final paired = <List<String>>[];
  @override
  Future<TerminalStatus> status() async => _embedded;
  @override
  Future<TerminalStatus> pair(String host, String code) async {
    paired.add([host, code]);
    return _lan;
  }
}

const _lan = TerminalStatus(
  kind: 'simulator',
  integrated: true,
  available: true,
  readerState: 'idle',
  address: '192.168.1.50:8090',
);
const _embedded = TerminalStatus(
  kind: 'simulator',
  integrated: true,
  available: true,
  embedded: true,
  readerState: 'idle',
);

void main() {
  setUpAll(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
          (_) async => null,
        );
  });
  setUp(() => Prefs.instance.lang = 'en');

  void bigScreen(WidgetTester tester) {
    tester.view.physicalSize = const Size(1920, 1200);
    tester.view.devicePixelRatio = 1.5;
    addTearDown(tester.view.reset);
  }

  /// A host page that opens the payment screen and keeps what it popped with.
  Future<List<Object?>> pumpPayment(
    WidgetTester tester, {
    required TerminalClient client,
    TerminalStatus status = _lan,
    SimReaderClient reader = const SimReaderClient(),
  }) async {
    bigScreen(tester);
    final popped = <Object?>[];
    await tester.pumpWidget(
      prefsScope(
        child: MaterialApp(
          theme: buildPosTheme(),
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () async {
                  popped.add(
                    await Navigator.of(context).push<TenderResult>(
                      MaterialPageRoute(
                        builder: (_) => TerminalPaymentScreen(
                          checkId: 1,
                          status: status,
                          client: client,
                          reader: reader,
                          pollInterval: const Duration(milliseconds: 10),
                          resultHold: const Duration(milliseconds: 10),
                        ),
                      ),
                    ),
                  );
                },
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    return popped;
  }

  testWidgets('approved → shows the card and pops with the recorded tender', (
    tester,
  ) async {
    final client = _FakeTerminal([
      _pending(),
      _pending('processing'),
      _recorded(),
    ]);
    final popped = await pumpPayment(tester, client: client);
    // settled: approved, popped back to the host page
    expect(client.started, 1);
    expect(popped, hasLength(1));
    final result = popped.single as TenderResult;
    expect(result.tender.type, 'TERMINAL');
    expect(result.tender.amountAppliedCents, 2328);
  });

  testWidgets('approved screen shows brand, last 4, auth code, processor ref', (
    tester,
  ) async {
    bigScreen(tester);
    final client = _FakeTerminal([_recorded()]);
    await tester.pumpWidget(
      prefsScope(
        child: MaterialApp(
          theme: buildPosTheme(),
          home: TerminalPaymentScreen(
            checkId: 1,
            status: _lan,
            client: client,
            pollInterval: const Duration(milliseconds: 10),
            resultHold: const Duration(seconds: 5),
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 20));
    await tester.pump();
    expect(find.text('Payment approved'), findsOneWidget);
    expect(find.text('Visa •••• 4242'), findsOneWidget);
    expect(find.text('Auth code 123456'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('terminal-processor-ref')),
      findsOneWidget,
    );
    expect(find.textContaining('jpm-txn-1'), findsOneWidget);
    await tester.pump(const Duration(seconds: 6));
  });

  testWidgets('declined → friendly reason, nothing recorded, pay another way', (
    tester,
  ) async {
    final client = _FakeTerminal([
      const TerminalPayment(
        paymentId: 'p1',
        status: 'DECLINED',
        amountCents: 2328,
        declineCode: 'insufficient_funds',
      ),
    ]);
    final popped = await pumpPayment(tester, client: client);
    expect(find.text('Card declined'), findsOneWidget);
    expect(find.textContaining('Insufficient funds'), findsOneWidget);
    expect(find.textContaining('Nothing was charged'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('terminal-other-way')));
    await tester.pumpAndSettle();
    expect(popped, [null]);
  });

  testWidgets('processor unreachable reads as a clean failure', (tester) async {
    final client = _FakeTerminal([
      const TerminalPayment(
        paymentId: 'p1',
        status: 'DECLINED',
        amountCents: 2328,
        declineCode: 'processor_unavailable',
      ),
    ]);
    await pumpPayment(tester, client: client);
    expect(
      find.textContaining('card processor can’t be reached'),
      findsOneWidget,
    );
  });

  testWidgets('cancel stops it on the terminal and records nothing', (
    tester,
  ) async {
    bigScreen(tester);
    final client = _FakeTerminal([_pending()]);
    final popped = <Object?>[];
    await tester.pumpWidget(
      prefsScope(
        child: MaterialApp(
          theme: buildPosTheme(),
          home: Builder(
            builder: (context) => TextButton(
              onPressed: () async => popped.add(
                await Navigator.of(context).push<TenderResult>(
                  MaterialPageRoute(
                    builder: (_) => TerminalPaymentScreen(
                      checkId: 1,
                      status: _lan,
                      client: client,
                      pollInterval: const Duration(seconds: 30),
                    ),
                  ),
                ),
              ),
              child: const Text('open'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(
      find.text('Customer taps, inserts or swipes on the terminal'),
      findsOneWidget,
    );
    expect(find.textContaining('192.168.1.50:8090'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('terminal-cancel')));
    await tester.pumpAndSettle();
    expect(client.cancelled, 1);
    expect(popped, [null]);
  });

  testWidgets('built-in simulator: the tablet plays the reader', (
    tester,
  ) async {
    bigScreen(tester);
    final reader = _FakeReader(
      const SimScreen(screen: 'present', totalCents: 2328),
    );
    await tester.pumpWidget(
      prefsScope(
        child: MaterialApp(
          theme: buildPosTheme(),
          home: TerminalPaymentScreen(
            checkId: 1,
            status: _embedded,
            client: _FakeTerminal([_pending()]),
            reader: reader,
            pollInterval: const Duration(seconds: 30),
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 20));
    expect(find.byKey(const ValueKey('sim-reader')), findsOneWidget);
    expect(find.text('Tap, insert or swipe'), findsOneWidget);
    Future<void> tapKey(String key) async {
      final f = find.byKey(ValueKey(key));
      await tester.ensureVisible(f);
      await tester.pump();
      await tester.tap(f);
      await tester.pump();
    }

    await tapKey('reader-card-mastercard');
    await tapKey('reader-outcome-insufficient_funds');
    await tapKey('reader-insert');
    expect(reader.presented, [
      ['insert', 'mastercard', 'insufficient_funds'],
    ]);
    expect(find.text('Enter your PIN'), findsOneWidget);
    for (final k in ['1', '2', '3', '4', 'ok']) {
      await tapKey('reader-pin-$k');
    }
    expect(reader.pins, ['1234']);
    // leave: unmount so the pollers stop
    await tester.pumpWidget(const SizedBox());
  });

  group('tender screen', () {
    Future<void> pumpTender(WidgetTester tester, TerminalStatus status) async {
      bigScreen(tester);
      await tester.pumpWidget(
        prefsScope(
          child: MaterialApp(
            theme: buildPosTheme(),
            home: TenderScreen(
              check: Check.fromJson(_checkJson()),
              stripeStatus: () async => StripeStatus.off,
              terminalStatus: () async => status,
              terminalClient: _FakeTerminal([_pending()]),
            ),
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
    }

    final tile = find.byKey(const ValueKey('tender-tile-TERMINAL'));

    testWidgets('shows "Card (terminal)" for a simulator store', (
      tester,
    ) async {
      await pumpTender(tester, _lan);
      expect(tile, findsOneWidget);
      expect(find.text('Card (terminal)'), findsOneWidget);
      // the hand-keyed card stays
      expect(find.byKey(const ValueKey('tender-tile-CARD')), findsOneWidget);
      await tester.tap(tile);
      await tester.pump();
      final charge = find.byKey(const ValueKey('terminal-charge'));
      expect(tester.widget<FilledButton>(charge).onPressed, isNotNull);
    });

    testWidgets('greyed with a hint when not paired', (tester) async {
      await pumpTender(
        tester,
        const TerminalStatus(
          kind: 'simulator',
          reason: 'terminal_not_paired',
          readerState: 'not_paired',
        ),
      );
      expect(tile, findsOneWidget);
      expect(find.byKey(const ValueKey('terminal-hint')), findsOneWidget);
      expect(find.textContaining('pair it in Settings'), findsOneWidget);
    });

    testWidgets('hidden for Stripe and external-terminal stores', (
      tester,
    ) async {
      await pumpTender(tester, const TerminalStatus(kind: 'stripe'));
      expect(tile, findsNothing);
      await pumpTender(tester, TerminalStatus.none);
      expect(tile, findsNothing);
    });
  });

  testWidgets(
    'retail pay sheet offers "Card (terminal)" beside the counter card',
    (tester) async {
      bigScreen(tester);
      await tester.pumpWidget(
        prefsScope(
          child: MaterialApp(
            theme: buildPosTheme(),
            home: Scaffold(
              body: PaySheet(
                sale: Check.fromJson(_checkJson()),
                terminalStatus: () async => _embedded,
                terminalClient: _FakeTerminal([_pending()]),
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 20));
      expect(find.text('Card (terminal)'), findsOneWidget);
      expect(find.text('Card (external terminal)'), findsOneWidget);
      await tester.tap(find.text('Card (terminal)'));
      await tester.pump();
      final charge = find.byKey(const ValueKey('terminal-charge'));
      expect(tester.widget<FilledButton>(charge).onPressed, isNotNull);
    },
  );

  testWidgets('settings: pair a LAN terminal with the code on its screen', (
    tester,
  ) async {
    bigScreen(tester);
    final client = _PairingClient();
    await tester.pumpWidget(
      prefsScope(
        child: MaterialApp(
          theme: buildPosTheme(),
          home: Scaffold(body: CardTerminalSettings(client: client)),
        ),
      ),
    );
    await tester.pump();
    expect(
      find.byKey(const ValueKey('card-terminal-settings')),
      findsOneWidget,
    );
    await tester.tap(find.byKey(const ValueKey('card-terminal-pair')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const ValueKey('pair-host')),
      '192.168.1.50:8090',
    );
    await tester.enterText(find.byKey(const ValueKey('pair-code')), '482913');
    await tester.tap(find.byKey(const ValueKey('pair-submit')));
    await tester.pumpAndSettle();
    expect(client.paired, [
      ['192.168.1.50:8090', '482913'],
    ]);
    expect(find.textContaining('192.168.1.50:8090'), findsOneWidget);
    expect(find.byKey(const ValueKey('card-terminal-unpair')), findsOneWidget);
  });

  testWidgets('refund offers the terminal card when it was paid that way', (
    tester,
  ) async {
    bigScreen(tester);
    final closed = {..._checkJson(), 'status': 'CLOSED'};
    final store = MockClient((req) async {
      http.Response json(Object b) => http.Response.bytes(
        utf8.encode(jsonEncode(b)),
        200,
        headers: {'content-type': 'application/json'},
      );
      if (req.url.path == '/checks/1') return json(closed);
      if (req.url.path == '/checks/1/refunds') {
        return json({
          'checkId': 1,
          'grandTotalCents': 2328,
          'refundedCents': 0,
          'refundableCents': 2328,
          'refunds': [],
          'terminalRefundableCents': 2328,
        });
      }
      return http.Response('{}', 404);
    });
    await http.runWithClient(() async {
      await tester.pumpWidget(
        prefsScope(
          child: MaterialApp(
            theme: buildPosTheme(),
            home: const RefundScreen(checkId: 1),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }, () => store);
    expect(find.text('Card (terminal)'), findsOneWidget);
  });
}
