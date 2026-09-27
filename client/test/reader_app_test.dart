import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pos_client/reader/reader_api.dart';
import 'package:pos_client/reader/reader_controller.dart';
import 'package:pos_client/reader/tap_reader.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// The card reader phone (POS_APP=reader) against a fake store and a fake
/// Tap to Pay reader: pair, connect, take a payment, report back.
class _FakeStore {
  final reports = <Map<String, dynamic>>[];
  Map<String, dynamic>? job;
  String jobState = 'queued';
  bool simulated = true;

  http.Client get client => MockClient((req) async {
    final path = req.url.path;
    final authed = req.headers['Authorization'] == 'Bearer tok-1';
    if (path == '/reader/pair') {
      final body = jsonDecode(req.body) as Map<String, dynamic>;
      if (body['code'] != '123456') {
        return http.Response(
          jsonEncode({'error': 'wrong', 'code': 'terminal_pairing_code_wrong'}),
          401,
        );
      }
      return http.Response(
        jsonEncode({
          'token': 'tok-1',
          'storeName': 'Sage & Poppy',
          'currency': 'USD',
        }),
        200,
      );
    }
    if (!authed) return http.Response('{"code":"terminal_not_paired"}', 401);
    switch (path) {
      case '/reader/config':
        return http.Response(
          jsonEncode({
            'storeName': 'Sage & Poppy',
            'currency': 'USD',
            'locationId': 'tml_1',
            'stripeAvailable': true,
            'simulated': simulated,
          }),
          200,
        );
      case '/reader/heartbeat':
        return http.Response('{"ok":true}', 200);
      case '/reader/connection-token':
        return http.Response('{"secret":"pst_test_x"}', 200);
      case '/reader/payment':
        return job == null
            ? http.Response('', 204)
            : http.Response(jsonEncode(job), 200);
    }
    if (path.endsWith('/result')) {
      final r = jsonDecode(req.body) as Map<String, dynamic>;
      reports.add(r);
      final state = r['status'] as String;
      if (state != 'collecting' && state != 'processing') job = null;
      return http.Response(jsonEncode({..._job(), 'state': state}), 200);
    }
    if (path.startsWith('/reader/payments/')) {
      return http.Response(jsonEncode({..._job(), 'state': jobState}), 200);
    }
    return http.Response('{}', 404);
  });
}

Map<String, dynamic> _job() => {
  'paymentIntentId': 'pi_1',
  'clientSecret': 'pi_1_secret',
  'amountCents': 1299,
  'currency': 'usd',
  'state': 'queued',
};

class _FakeTap implements TapReader {
  String? connectedTo;
  bool? simulated;
  ReaderTestCard? lastCard;
  bool collected = false;
  TapReaderException? fail;
  String? tokenSeen;

  @override
  Future<String> connect({
    required String locationId,
    required bool simulated,
    required Future<String> Function() fetchToken,
    String? merchantName,
  }) async {
    tokenSeen = await fetchToken();
    connectedTo = locationId;
    this.simulated = simulated;
    return 'Stripe simulated Tap to Pay';
  }

  @override
  Future<void> collect(
    String clientSecret, {
    ReaderTestCard? testCard,
    void Function(String phase)? onPhase,
  }) async {
    collected = true;
    lastCard = testCard;
    onPhase?.call('processing');
    if (fail != null) throw fail!;
  }

  @override
  Future<void> cancel() async {}
}

void main() {
  late _FakeStore store;
  late _FakeTap tap;
  ReaderController controller() => ReaderController(
    reader: tap,
    discover: () async => 'http://10.0.0.5:8082',
    apiFactory: (u, t) => ReaderApi(u, token: t, client: store.client),
    pollEvery: const Duration(hours: 1), // the test ticks by hand
    resultFor: const Duration(milliseconds: 1),
  );

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    store = _FakeStore();
    tap = _FakeTap();
  });

  test(
    'pairs with the code, connects the simulated reader, takes a test card',
    () async {
      final c = controller();
      await c.start();
      expect(c.stage, ReaderStage.pairing);
      await c.pair('000000');
      expect(c.stage, ReaderStage.pairing);
      expect(c.message, contains('code'));
      await c.pair('123456');
      expect(c.stage, ReaderStage.ready);
      expect(tap.connectedTo, 'tml_1');
      expect(tap.simulated, isTrue);
      expect(tap.tokenSeen, 'pst_test_x');
      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getString('reader.token'), 'tok-1');

      store.job = _job();
      await c.tick();
      expect(
        c.stage,
        ReaderStage.payment,
        reason: 'simulated: waits for the test card',
      );
      expect(c.job!.amountLabel, r'$12.99');
      c.testCard = ReaderTestCard.mastercard;
      await c.collect();
      expect(tap.lastCard, ReaderTestCard.mastercard);
      expect(store.reports.map((r) => r['status']), [
        'collecting',
        'processing',
        'collected',
      ]);
      expect(c.stage, ReaderStage.result);
      expect(c.resultOk, isTrue);
      c.dispose();
    },
  );

  test(
    'a decline and Developer options on are reported with their codes',
    () async {
      final c = controller();
      await c.start();
      await c.pair('123456');
      store.job = _job();
      await c.tick();
      tap.fail = const TapReaderException(
        'declinedByStripeApi',
        'declined',
        declined: true,
      );
      await c.collect();
      expect(store.reports.last['status'], 'failed');
      expect(store.reports.last['declined'], true);
      expect(c.resultTitle, 'Declined');

      await Future<void>.delayed(const Duration(milliseconds: 10));
      expect(c.stage, ReaderStage.ready);
      store.job = _job();
      await c.tick();
      tap.fail = const TapReaderException(
        'tapToPayInsecureEnvironment',
        'insecure',
      );
      await c.collect();
      expect(store.reports.last['code'], 'tapToPayInsecureEnvironment');
      expect(c.resultTitle, contains('Developer options'));
      c.dispose();
    },
  );

  test('a real reader goes straight to the tap, with no test card', () async {
    store.simulated = false;
    final c = controller();
    await c.start();
    await c.pair('123456');
    expect(tap.simulated, isFalse);
    store.job = _job();
    await c.tick();
    expect(tap.collected, isTrue);
    expect(tap.lastCard, isNull);
    expect(store.reports.last['status'], 'collected');
    c.dispose();
  });

  test('the POS cancels while the phone waits for the tap', () async {
    final c = controller();
    await c.start();
    await c.pair('123456');
    store.job = _job();
    await c.tick();
    expect(c.stage, ReaderStage.payment);
    store.jobState = 'canceled';
    await c.tick();
    expect(c.stage, ReaderStage.result);
    expect(c.resultTitle, 'Cancelled on the POS');
    expect(tap.collected, isFalse);
    c.dispose();
  });

  test(
    'a saved pairing reconnects without the code; a stale one goes back to pairing',
    () async {
      SharedPreferences.setMockInitialValues({
        'reader.storeUrl': 'http://10.0.0.5:8082',
        'reader.token': 'tok-1',
      });
      final c = controller();
      await c.start();
      expect(c.stage, ReaderStage.ready);
      SharedPreferences.setMockInitialValues({
        'reader.storeUrl': 'http://10.0.0.5:8082',
        'reader.token': 'stale',
      });
      final c2 = controller();
      await c2.start();
      expect(c2.stage, ReaderStage.pairing);
      c.dispose();
      c2.dispose();
    },
  );
}
