// Red team: money must ALWAYS read "$1,234.56" (North American), in every
// UI language, on every screen. Each test below that FAILS is a repro.
//
//   flutter test test/redteam_money_test.dart
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
import 'package:pos_client/payments/card_reader.dart';
import 'package:pos_client/reader/reader_api.dart';
import 'package:pos_client/screens/menu_management_screen.dart';
import 'package:pos_client/screens/settings_screen.dart';
import 'package:pos_client/screens/stripe_payment_screen.dart';
import 'package:pos_client/store_profile.dart';

final _fixtures = '${Directory.current.path}/test/screenshots/fixtures';
dynamic _fx(String name) =>
    jsonDecode(File('$_fixtures/$name.json').readAsStringSync());

/// A reader that connects and then waits for a card forever.
class _IdleReader implements CardReader {
  @override
  Future<void> prepare(String l, void Function(ReaderPhase) onPhase) async =>
      onPhase(ReaderPhase.connecting);
  @override
  Future<void> collect(
    String s,
    SimulatedTestCard c,
    void Function(ReaderPhase) onPhase,
  ) async => onPhase(ReaderPhase.waitingForCard);
  @override
  Future<void> cancel() async {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    for (final name in [
      'plugins.it_nomads.com/flutter_secure_storage',
      'xyz.luan/audioplayers',
      'xyz.luan/audioplayers.global',
    ]) {
      messenger.setMockMethodCallHandler(
        MethodChannel(name),
        (_) async => null,
      );
    }
  });

  // ---------------------------------------------------------- the formatter
  group('formatMoney / money (held up: these pass)', () {
    const cases = {
      0: r'$0.00',
      1: r'$0.01',
      5: r'$0.05',
      99: r'$0.99',
      100: r'$1.00',
      99999: r'$999.99',
      100000: r'$1,000.00',
      123456: r'$1,234.56',
      9999999: r'$99,999.99',
      123456789: r'$1,234,567.89',
      -500: r'-$5.00',
      -123456: r'-$1,234.56',
    };
    for (final lang in ['en', 'fr', 'es', 'de', 'af']) {
      for (final cur in ['USD', 'CAD']) {
        test('$cur in $lang', () {
          Prefs.instance.lang = lang;
          for (final MapEntry(:key, :value) in cases.entries) {
            expect(formatMoney(key, cur, lang: lang), value);
            expect(money(key, currency: cur, lang: lang), value);
          }
        });
      }
    }
  });

  // ------------------------------------------- phone card reader amount
  // lib/reader/reader_api.dart:33 hand-rolls money with no thousands comma
  group('phone card reader (ReaderJob.amountLabel)', () {
    ReaderJob job(int cents, String cur) => ReaderJob.fromJson({
      'paymentIntentId': 'pi_1',
      'clientSecret': 'sec',
      'amountCents': cents,
      'currency': cur,
      'state': 'queued',
    });

    test('a \$1,234.56 card payment reads "\$1,234.56"', () {
      expect(job(123456, 'usd').amountLabel, r'$1,234.56');
    });

    test('matches formatMoney for every amount (CAD)', () {
      for (final c in [0, 99, 123456, 9999999, 123456789]) {
        expect(job(c, 'cad').amountLabel, formatMoney(c, 'CAD'), reason: '$c');
      }
    });
  });

  // ------------------------------------------ Stripe card payment screen
  // lib/screens/stripe_payment_screen.dart:238 prints
  // '${currency} ${(cents / 100).toStringAsFixed(2)}' → "CAD 1234.56"
  for (final lang in ['en', 'fr', 'de']) {
    testWidgets('Stripe card screen shows the amount as \$1,234.56 [$lang]', (
      tester,
    ) async {
      Prefs.instance.lang = lang;
      final store = MockClient((req) async {
        if (req.url.path == '/checks/1/stripe/intents') {
          return http.Response.bytes(
            utf8.encode(
              jsonEncode({
                'paymentId': 'pay_1',
                'paymentIntentId': 'pi_1',
                'clientSecret': 'pi_1_secret',
                'amountCents': 123456,
                'currency': 'CAD',
                'locationId': 'tml_test',
              }),
            ),
            201,
          );
        }
        return http.Response('{"code":"not_found"}', 404);
      });
      tester.view.physicalSize = const Size(1920, 1200);
      tester.view.devicePixelRatio = 1.5;
      addTearDown(tester.view.reset);
      await http.runWithClient(() async {
        await tester.pumpWidget(
          prefsScope(
            child: MaterialApp(
              theme: buildPosTheme(),
              home: StripePaymentScreen(
                checkId: 1,
                locationId: 'tml_test',
                reader: _IdleReader(),
              ),
            ),
          ),
        );
        for (var i = 0; i < 10; i++) {
          await tester.pump(const Duration(milliseconds: 50));
        }
        final shown = find
            .byType(Text)
            .evaluate()
            .map((e) => (e.widget as Text).data ?? '')
            .where((s) => s.contains('1234') || s.contains('1,234'))
            .toList();
        expect(shown, contains(r'$1,234.56'), reason: 'saw: $shown');
        await tester.pumpWidget(const SizedBox());
        await tester.pump(const Duration(seconds: 1));
      }, () => store);
    });
  }

  // ------------------------------------- menu editor drops the cents
  // lib/screens/menu_management_screen.dart:468-469 pre-fills the price box
  // with `cents ~/ 100` (whole dollars) and :606 saves `int.parse(text)*100`:
  // opening a $7.50 item and pressing Save rewrites it to $7.00.
  testWidgets('menu editor: open "Lantern House Lager" (\$7.50 / \$20.25), '
      'press Save without touching anything → prices unchanged', (
    tester,
  ) async {
    Prefs.instance.lang = 'en';
    Api.currentUser = AuthUser.fromJson({
      'userId': 'manager',
      'name': 'Demo Manager',
      'role': 'MANAGER',
      'languageCode': 'en',
      'grants': ['edit_menu'],
    }, 'test-token');
    final items = _fx('items') as List;
    final lager = items.firstWhere((i) => i['id'] == 'lantern-lager');
    final variantPatches = <String>[];
    final store = MockClient((req) async {
      final path = req.url.path;
      if (req.method == 'PATCH' && path.contains('/variants/')) {
        variantPatches.add('$path ${req.body}');
      }
      final body = switch (path) {
        '/items' => items,
        '/categories' => _fx('categories'),
        '/health' => _fx('health'),
        _ when path.startsWith('/items/lantern-lager') => lager,
        _ => null,
      };
      if (body == null) return http.Response('{"error":"not found"}', 404);
      return http.Response.bytes(
        utf8.encode(jsonEncode(body)),
        200,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );
    });
    tester.view.physicalSize = const Size(1920, 1200);
    tester.view.devicePixelRatio = 1.5;
    addTearDown(tester.view.reset);
    await http.runWithClient(() async {
      await tester.pumpWidget(
        prefsScope(
          child: MaterialApp(
            theme: buildPosTheme(),
            home: const MenuManagementScreen(),
          ),
        ),
      );
      for (var i = 0; i < 6; i++) {
        await tester.pump(const Duration(milliseconds: 150));
      }
      await tester.tap(find.text('Lantern House Lager').first);
      for (var i = 0; i < 6; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      // what the price boxes show the manager
      final boxes = find
          .byType(TextField)
          .evaluate()
          .map((e) => (e.widget as TextField).controller?.text ?? '')
          .toList();
      await tester.tap(find.widgetWithText(FilledButton, 'Save').last);
      for (var i = 0; i < 10; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      expect(
        variantPatches,
        isEmpty,
        reason:
            'nothing was edited, yet Save re-priced the sizes:\n'
            '${variantPatches.join('\n')}\nfields shown: $boxes',
      );
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(seconds: 1));
    }, () => store);
  });

  // ------------------------------------- settings: corkage loses its cents
  // lib/screens/settings_screen.dart:148 shows `cents ~/ 100`, :176 saves
  // `int.tryParse(text) * 100`: any Save (even to change Wi-Fi) turns a
  // $12.50 corkage fee into $12.00.
  testWidgets('settings: Save with nothing changed keeps a \$12.50 corkage', (
    tester,
  ) async {
    Prefs.instance.lang = 'en';
    Api.currentUser = AuthUser.fromJson({
      'userId': 'manager',
      'name': 'Demo Manager',
      'role': 'MANAGER',
      'languageCode': 'en',
      'grants': ['edit_menu'],
    }, 'test-token');
    final settings = {
      'cardProcessor': '',
      'bankName': '',
      'bankAccountNumber': '',
      'bankAccountName': '',
      'serviceChargePercent': 0,
      'corkagePerBottleCents': 1250,
      'receiptFooter': '',
      'venuePhone': '',
      'venueAddress': '',
      'sessionIdleMinutes': 5,
    };
    final patches = <Map<String, dynamic>>[];
    final store = MockClient((req) async {
      if (req.url.path == '/settings') {
        if (req.method == 'PATCH' || req.method == 'PUT') {
          patches.add(jsonDecode(req.body) as Map<String, dynamic>);
        }
        return http.Response.bytes(
          utf8.encode(jsonEncode(settings)),
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      }
      return http.Response('{"error":"not found"}', 404);
    });
    tester.view.physicalSize = const Size(1920, 1200);
    tester.view.devicePixelRatio = 1.5;
    addTearDown(tester.view.reset);
    await http.runWithClient(() async {
      await tester.pumpWidget(
        prefsScope(
          child: MaterialApp(
            theme: buildPosTheme(),
            home: const SettingsScreen(),
          ),
        ),
      );
      for (var i = 0; i < 6; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      final save = find.widgetWithText(FilledButton, 'Save');
      await tester.scrollUntilVisible(
        save,
        300,
        scrollable: find
            .descendant(
              of: find.byType(ListView),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      await tester.tap(save);
      for (var i = 0; i < 6; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      expect(patches, hasLength(1));
      expect(
        patches.single['corkagePerBottleCents'],
        1250,
        reason: 'Save sent corkage ${patches.single['corkagePerBottleCents']}',
      );
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(seconds: 1));
    }, () => store);
  });
}
