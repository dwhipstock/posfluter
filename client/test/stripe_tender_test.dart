import 'dart:async';
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
import 'package:mek_stripe_terminal/mek_stripe_terminal.dart'
    show TerminalExceptionCode;
import 'package:pos_client/payments/card_reader.dart';
import 'package:pos_client/payments/stripe_log.dart';
import 'package:pos_client/screens/stripe_payment_screen.dart';
import 'package:pos_client/screens/tender_screen.dart';

/// The optional "Card (Stripe)" tender: shown only with a key, greyed out with
/// a hint when it can't be used, and a cancelled payment records nothing.
Map<String, dynamic> _checkJson() => jsonDecode(
  File(
    '${Directory.current.path}/test/screenshots/fixtures/check.json',
  ).readAsStringSync(),
);

class _FakeReader implements CardReader {
  _FakeReader({this.holdCollect = false, this.prepareError});
  final bool holdCollect;
  final CardReaderException? prepareError;
  final _hold = Completer<void>();
  bool canceled = false;
  int collects = 0;

  @override
  Future<void> prepare(
    String locationId,
    void Function(ReaderPhase) onPhase,
  ) async {
    onPhase(ReaderPhase.connecting);
    if (prepareError != null) throw prepareError!;
  }

  @override
  Future<void> collect(
    String clientSecret,
    SimulatedTestCard card,
    void Function(ReaderPhase) onPhase,
  ) async {
    collects++;
    onPhase(ReaderPhase.waitingForCard);
    if (holdCollect) await _hold.future;
  }

  @override
  Future<void> cancel() async {
    canceled = true;
    if (!_hold.isCompleted) {
      _hold.completeError(const CardReaderException(CardReaderError.canceled));
    }
  }
}

const _available = StripeStatus(
  configured: true,
  available: true,
  currency: 'CAD',
  locationId: 'tml_test',
);

void main() {
  test('only network codes read as "can\'t reach Stripe"', () {
    CardReaderError m(TerminalExceptionCode c) =>
        StripeTerminalReader.mapTerminalCode(c);
    expect(
      m(TerminalExceptionCode.locationServicesDisabled),
      CardReaderError.locationOff,
    );
    expect(
      m(TerminalExceptionCode.bluetoothDisabled),
      CardReaderError.bluetoothOff,
    );
    expect(
      m(TerminalExceptionCode.bluetoothPermissionDenied),
      CardReaderError.permissionDenied,
    );
    expect(
      m(TerminalExceptionCode.stripeApiConnectionError),
      CardReaderError.offline,
    );
    expect(
      m(TerminalExceptionCode.notConnectedToInternet),
      CardReaderError.offline,
    );
    expect(
      m(TerminalExceptionCode.connectionTokenProviderError),
      CardReaderError.tokenFailed,
    );
    expect(m(TerminalExceptionCode.stripeApiError), CardReaderError.stripeApi);
    expect(m(TerminalExceptionCode.unknown), CardReaderError.readerFailed);
    expect(
      m(TerminalExceptionCode.declinedByStripeApi),
      CardReaderError.declined,
    );
  });

  test('log lines never carry secrets', () {
    final line = scrubStripe(
      'token pst_test_abcDEF123 secret pi_3Ab_secret_XyZ key sk_test_51Hxyz',
    );
    expect(line, isNot(contains('abcDEF123')));
    expect(line, isNot(contains('XyZ')));
    expect(line, isNot(contains('51Hxyz')));
    expect(line, contains('pi_3Ab_secret_***'));
  });

  setUpAll(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
          (_) async => null,
        );
  });

  Future<void> pumpTender(
    WidgetTester tester,
    StripeStatus status, {
    bool supported = true,
    CardReader? reader,
  }) async {
    tester.view.physicalSize = const Size(1920, 1200);
    tester.view.devicePixelRatio = 1.5;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      prefsScope(
        child: MaterialApp(
          theme: buildPosTheme(),
          home: TenderScreen(
            check: Check.fromJson(_checkJson()),
            stripeStatus: () async => status,
            cardReaderSupported: supported,
            cardReader: reader ?? _FakeReader(),
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
  }

  final tile = find.byKey(const ValueKey('tender-tile-STRIPE'));
  final charge = find.byKey(const ValueKey('stripe-charge'));
  final hint = find.byKey(const ValueKey('stripe-hint'));

  testWidgets('enabled when Stripe is available', (tester) async {
    await pumpTender(tester, _available);
    expect(tile, findsOneWidget);
    expect(find.text('Card (Stripe)'), findsOneWidget);
    expect(hint, findsNothing);
    await tester.tap(tile);
    await tester.pump();
    expect(charge, findsOneWidget);
    expect(tester.widget<FilledButton>(charge).onPressed, isNotNull);
    expect(find.text('Simulated card (test mode)'), findsOneWidget);
  });

  testWidgets('greyed out offline, with a hint; cash stays selected', (
    tester,
  ) async {
    await pumpTender(tester, StripeStatus.unavailable('stripe_unavailable'));
    expect(tile, findsOneWidget);
    expect(hint, findsOneWidget);
    expect(
      find.text('No internet — Card (Stripe) is unavailable'),
      findsOneWidget,
    );
    await tester.tap(tile, warnIfMissed: false);
    await tester.pump();
    expect(charge, findsNothing, reason: 'a disabled tile cannot be selected');
    expect(find.text('Receive'), findsOneWidget, reason: 'cash still usable');
  });

  testWidgets('live key refused → greyed out with the reason', (tester) async {
    await pumpTender(
      tester,
      const StripeStatus(
        configured: true,
        available: false,
        reason: 'stripe_live_key_refused',
      ),
    );
    expect(tile, findsOneWidget);
    expect(find.textContaining('only test keys'), findsOneWidget);
  });

  testWidgets('not on the Android tablet → greyed out', (tester) async {
    await pumpTender(tester, _available, supported: false);
    expect(tile, findsOneWidget);
    expect(find.textContaining('Android tablet only'), findsOneWidget);
  });

  testWidgets('no key → not shown at all', (tester) async {
    await pumpTender(tester, StripeStatus.off);
    expect(tile, findsNothing);
    expect(hint, findsNothing);
    expect(find.text('Cash'), findsOneWidget);
  });

  testWidgets('French labels', (tester) async {
    Prefs.instance.lang = 'fr';
    addTearDown(() => Prefs.instance.lang = 'en');
    await pumpTender(tester, StripeStatus.unavailable('stripe_unavailable'));
    expect(find.text('Carte (Stripe)'), findsOneWidget);
    expect(find.textContaining('Pas d’Internet'), findsOneWidget);
  });

  group('payment screen', () {
    Future<List<String>> run(
      WidgetTester tester,
      _FakeReader reader, {
      Future<void> Function(WidgetTester)? act,
      Object? Function(Object?)? onPop,
    }) async {
      final posts = <String>[];
      Object? popped = 'not popped';
      final store = MockClient((req) async {
        posts.add('${req.method} ${req.url.path}');
        final body = switch (req.url.path) {
          '/checks/1/stripe/intents' => {
            'paymentId': 'pay_1',
            'paymentIntentId': 'pi_1',
            'clientSecret': 'pi_1_secret',
            'amountCents': 2250,
            'currency': 'CAD',
            'locationId': 'tml_test',
          },
          '/stripe/payments/pay_1/confirm' => {
            'tender': {
              'id': 7,
              'type': 'STRIPE',
              'amountTenderedCents': 2250,
              'amountAppliedCents': 2250,
              'roundingAdjustmentCents': 0,
              'changeCents': 0,
              'groupId': null,
            },
            'check': _checkJson(),
            'paymentId': 'pay_1',
          },
          '/stripe/payments/pay_1/cancel' => {
            'paymentId': 'pay_1',
            'status': 'CANCELED',
            'amountCents': 2250,
            'currency': 'CAD',
          },
          _ => null,
        };
        return body == null
            ? http.Response('{"code":"not_found"}', 404)
            : http.Response.bytes(utf8.encode(jsonEncode(body)), 201);
      });
      tester.view.physicalSize = const Size(1920, 1200);
      tester.view.devicePixelRatio = 1.5;
      addTearDown(tester.view.reset);
      await http.runWithClient(() async {
        await tester.pumpWidget(
          prefsScope(
            child: MaterialApp(
              theme: buildPosTheme(),
              home: Builder(
                builder: (context) => Scaffold(
                  body: TextButton(
                    onPressed: () async {
                      popped = await Navigator.of(context).push(
                        MaterialPageRoute(
                          builder: (_) => StripePaymentScreen(
                            checkId: 1,
                            locationId: 'tml_test',
                            reader: reader,
                          ),
                        ),
                      );
                    },
                    child: const Text('go'),
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.tap(find.text('go'));
        for (var i = 0; i < 10; i++) {
          await tester.pump(const Duration(milliseconds: 50));
        }
        if (act != null) await act(tester);
        for (var i = 0; i < 30; i++) {
          await tester.pump(const Duration(milliseconds: 50));
        }
      }, () => store);
      onPop?.call(popped);
      return posts;
    }

    testWidgets(
      'device Location off → precise message, code, settings button',
      (tester) async {
        final posts = await run(
          tester,
          _FakeReader(
            prepareError: const CardReaderException(
              CardReaderError.locationOff,
              'locationServicesDisabled',
            ),
          ),
          act: (tester) async {
            expect(
              find.text('Device Location is off. Turn it on, then try again.'),
              findsOneWidget,
            );
            expect(find.text('Code: locationServicesDisabled'), findsOneWidget);
            expect(find.text('Open Location settings'), findsOneWidget);
            expect(find.textContaining("can't reach Stripe"), findsNothing);
          },
        );
        expect(posts, isEmpty, reason: 'no PaymentIntent without a reader');
      },
    );

    testWidgets('Bluetooth off → Bluetooth settings button', (tester) async {
      await run(
        tester,
        _FakeReader(
          prepareError: const CardReaderException(
            CardReaderError.bluetoothOff,
            'bluetoothDisabled',
          ),
        ),
        act: (tester) async {
          expect(
            find.text('Bluetooth is off. Turn it on, then try again.'),
            findsOneWidget,
          );
          expect(find.text('Open Bluetooth settings'), findsOneWidget);
        },
      );
    });

    testWidgets('approved → tender returned', (tester) async {
      Object? result;
      final posts = await run(tester, _FakeReader(), onPop: (p) => result = p);
      expect(posts, contains('POST /stripe/payments/pay_1/confirm'));
      expect(result, isA<TenderResult>());
      expect((result as TenderResult).tender.type, 'STRIPE');
    });

    testWidgets('cancel while waiting for the card records nothing', (
      tester,
    ) async {
      final reader = _FakeReader(holdCollect: true);
      Object? result = 'x';
      final posts = await run(
        tester,
        reader,
        act: (tester) async {
          expect(
            find.text('Tap, insert or swipe the card (simulated)'),
            findsOneWidget,
          );
          await tester.tap(find.byKey(const ValueKey('stripe-cancel')));
        },
        onPop: (p) => result = p,
      );
      expect(reader.canceled, isTrue);
      expect(posts, contains('POST /stripe/payments/pay_1/cancel'));
      expect(posts, isNot(contains('POST /stripe/payments/pay_1/confirm')));
      expect(result, isNull);
    });
  });
}
