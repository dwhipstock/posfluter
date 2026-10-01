// Red team: hostile names on the kitchen board (Copper Lantern) and on the
// Sage & Poppy register + sign-in. A layout error (RenderFlex overflow etc.)
// fails the test.
//
//   flutter test test/redteam_kitchen_retail_test.dart
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/app_mode.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/kitchen/kitchen_screen.dart';
import 'package:pos_client/retail/retail_screen.dart';
import 'package:pos_client/retail/sp_theme.dart';
import 'package:pos_client/screens/login_screen.dart';

final _fixtures = '${Directory.current.path}/test/screenshots/fixtures';
dynamic _fx(String name) =>
    jsonDecode(File('$_fixtures/$name.json').readAsStringSync());

// realistic worst cases, within the server's column limits
// (item names 100-200, notes 500, staff name 100, VIP table name 100)
final long100 = 'Wagyu' * 20; // 100 chars, no spaces
final note500 = List.filled(100, 'xtra').join(' ').padRight(500, '!');
const zalgo =
    'Z̶̢̛̤͓͖͙̩͉̺͇̦'
    'à̸́̂̃̄̅̆̇̈̉̊';
const rtl = 'برجر الفانوس النحاسي مع البطاطس המבורגר של הפנס';
const cjk = '铜灯笼特制汉堡包配手切薯条和秘制酱料双层芝士';
const emoji = '🍔🔥👨‍👩‍👧‍👦🇨🇦🍟🥤✨';
const newlines = 'Line one\nLine two\nLine three\nLine four\nLine five';
final hostile = [long100, zalgo * 6, rtl, cjk, emoji * 4, newlines];

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

http.Response _json(Object body) => http.Response.bytes(
  utf8.encode(jsonEncode(body)),
  200,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

Future<List<String>> _render(
  WidgetTester tester,
  Widget screen,
  MockClient store, {
  ThemeData? theme,
  (Size, double) size = (const Size(1920, 1200), 1.5),
}) async {
  tester.view.physicalSize = size.$1;
  tester.view.devicePixelRatio = size.$2;
  addTearDown(tester.view.reset);
  final errors = <String>[];
  final prev = FlutterError.onError;
  FlutterError.onError = (d) {
    if (d.exception is MissingPluginException) return;
    final at = RegExp(r'lib/[\w/]+\.dart:\d+').firstMatch(d.toString());
    errors.add(
      '${d.exceptionAsString().split('\n').first} @ ${at?.group(0) ?? '?'}',
    );
  };
  try {
    await http.runWithClient(() async {
      await tester.pumpWidget(
        prefsScope(
          child: MaterialApp(
            debugShowCheckedModeBanner: false,
            theme: theme ?? buildPosTheme(),
            home: screen,
          ),
        ),
      );
      for (var i = 0; i < 6; i++) {
        await tester.pump(const Duration(milliseconds: 150));
      }
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 400)),
      );
      for (var i = 0; i < 4; i++) {
        await tester.pump(const Duration(milliseconds: 150));
      }
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(seconds: 1));
    }, () => store);
  } finally {
    FlutterError.onError = prev;
  }
  return errors;
}

// ---------------------------------------------------------------- kitchen
Map<String, dynamic> _kItem(int id, String name, String? note) => {
  'lineId': id,
  'qty': 999,
  'nameFr': name,
  'nameEn': name,
  'variantFr': name,
  'variantEn': name,
  'note': note,
  'add': id.isEven,
  'voided': id % 3 == 0,
  'voidedQty': 0,
};

Map<String, dynamic> _kCard(int check, String table, String server) => {
  'key': '$check:kitchen',
  'checkId': check,
  'stationId': 'kitchen',
  'stationNameFr': 'Cuisine',
  'stationNameEn': 'Kitchen',
  'tableLabel': table,
  'serverName': server,
  'guests': 99,
  'firstSentAt': '2026-09-26T19:00:00-04:00',
  'elapsedSeconds': 99 * 60 + 59,
  'level': 'late',
  'items': [
    for (final (i, n) in hostile.indexed)
      _kItem(check * 10 + i, n, i.isEven ? note500 : hostile[(i + 1) % 6]),
  ],
};

MockClient _kitchenStore() => MockClient((req) async {
  final vip = List.filled(25, 'VIP\n').join().substring(0, 99);
  final body = switch (req.url.path) {
    '/health' => {
      ..._fx('health') as Map<String, dynamic>,
      'kitchenPrinting': true,
    },
    '/kitchen/board' => {
      'cards': [
        _kCard(41, long100, long100),
        _kCard(42, vip, emoji * 4),
        _kCard(43, rtl, rtl),
      ],
      'recent': [],
      'stations': [
        {
          'id': 'kitchen',
          'nameFr': long100,
          'nameEn': long100,
          'output': 'both',
          'printerHost': '',
          'printerPort': 9100,
          'paperMm': 80,
          'sortOrder': 0,
        },
      ],
      'warnMinutes': 10,
      'lateMinutes': 20,
      'sound': false,
      'language': 'en',
      'latestTicket': 12,
      'serverTime': '2026-09-26T19:23:12-04:00',
    },
    _ => null,
  };
  return body == null
      ? http.Response('{"error":"not found"}', 404)
      : _json(body);
});

// ------------------------------------------------------------ Sage & Poppy
const _sp = StoreProfile(
  venueId: 'sage-poppy',
  brand: 'sage-poppy',
  kind: 'retail',
  country: 'US',
  currency: 'USD',
  locales: ['en', 'es'],
  legalAge: 21,
);

Map<String, dynamic> _spSale() {
  final lines = [
    for (final (i, n) in hostile.indexed)
      {
        'id': i + 1,
        'itemId': 'x$i',
        'variantId': 'x$i:each',
        'nameFr': n,
        'nameEn': n,
        'qty': 999,
        'unitPriceCents': 9999999,
        'lineTotalCents': 123456789,
        'note': null,
        'ageRestricted': i.isEven,
        'taxable': true,
        'depositCents': 500,
      },
  ];
  return {
    'id': 1042,
    'tableId': 'register-1',
    'status': 'OPEN',
    'corkageBottles': 0,
    'lines': lines,
    'pendingLines': [],
    'fees': [
      {
        'code': 'crv',
        'labelFr': 'CRV',
        'labelEn': 'CRV',
        'amountCents': 123456789,
        'taxable': false,
      },
    ],
    'itemsSubtotalCents': 123456789,
    'grandTotalCents': 123456789,
    'paidCents': 0,
    'outstandingCents': 123456789,
    'tenders': [],
    'subtotalCents': 123456789,
    'taxes': [
      {
        'code': 'SALES',
        'labelFr': 'Sales Tax',
        'labelEn': 'Sales Tax',
        'ratePercent': '9.5',
        'amountCents': 12345678,
      },
    ],
    'ageCheckRequired': true,
    'ageCleared': false,
    'ageCheckFailed': false,
  };
}

MockClient _spStore({List<Map<String, dynamic>>? staff}) {
  final fx =
      jsonDecode(File('$_fixtures/sage_poppy.json').readAsStringSync())
          as Map<String, dynamic>;
  return MockClient((req) async {
    final body = switch (req.url.path) {
      '/health' => {
        'status': 'ok',
        'venue': 'Sage & Poppy Bottle Shop',
        'venueId': 'sage-poppy',
        'brand': 'sage-poppy',
        'kind': 'retail',
        'country': 'US',
        'currency': 'USD',
        'locales': ['en', 'es'],
        'legalAge': 21,
      },
      '/staff' =>
        staff ??
            [
              {'id': 'manager', 'name': 'Demo Manager', 'role': 'MANAGER'},
            ],
      '/items' => fx['items'],
      '/categories' => fx['categories'],
      '/retail/quick-keys' => fx['quickKeys'],
      '/retail/top-sellers' => fx['topSellers'],
      '/retail/sales/current' => _spSale(),
      '/shifts/current' => {
        'id': 12,
        'status': 'OPEN',
        'openedAt': '2026-09-25T16:00:00Z',
        'openedBy': 'manager',
        'openingFloatCents': 20000,
      },
      _ => null,
    };
    return body == null
        ? http.Response('{"error":"not found"}', 404)
        : _json(body);
  });
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() async {
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
    await _loadFonts();
  });

  setUp(() {
    Prefs.instance.lang = 'en';
    AppMode.isStock = false;
    Api.currentUser = AuthUser.fromJson({
      'userId': 'manager',
      'name': 'Demo Manager',
      'role': 'MANAGER',
      'languageCode': 'en',
      'grants': ['void', 'refund', 'edit_menu', 'open_shift'],
    }, 'test-token');
  });

  tearDown(() {
    StoreProfile.current = StoreProfile.pub;
    AppMode.isStock = false;
  });

  for (final lang in ['en', 'fr', 'es', 'de', 'af']) {
    testWidgets('kitchen board: hostile tickets [$lang]', (tester) async {
      Prefs.instance.lang = lang;
      StoreProfile.current = const StoreProfile(kitchenPrinting: true);
      final errors = await _render(
        tester,
        const KitchenScreen(),
        _kitchenStore(),
      );
      expect(errors, isEmpty, reason: errors.join('\n'));
    });
  }

  for (final lang in ['en', 'es']) {
    // initials are built with `w[0]` (one UTF-16 unit): an emoji first
    // letter is half a surrogate pair → "string is not well-formed UTF-16"
    // (lib/screens/login_screen.dart:380)
    testWidgets('Sage & Poppy sign-in: staff named "🍔 Burger Bob" [$lang]', (
      tester,
    ) async {
      StoreProfile.current = _sp;
      Prefs.instance.lang = lang;
      Api.currentUser = null;
      final errors = await _render(
        tester,
        const LoginScreen(),
        _spStore(
          staff: [
            {'id': 'm', 'name': 'Demo Manager', 'role': 'MANAGER'},
            {'id': 'b', 'name': '🍔 Burger Bob', 'role': 'SERVER'},
          ],
        ),
        theme: buildSagePoppyTheme(Brightness.light),
      );
      expect(errors, isEmpty, reason: errors.join('\n'));
    });

    testWidgets('Sage & Poppy sign-in: long/RTL/CJK/zalgo/newline staff names '
        '(no emoji) [$lang]', (tester) async {
      StoreProfile.current = _sp;
      Prefs.instance.lang = lang;
      Api.currentUser = null;
      final errors = await _render(
        tester,
        const LoginScreen(),
        _spStore(
          staff: [
            for (final (i, n) in hostile.where((n) => n != emoji * 4).indexed)
              {'id': 's$i', 'name': n, 'role': i.isEven ? 'MANAGER' : 'SERVER'},
          ],
        ),
        theme: buildSagePoppyTheme(Brightness.light),
      );
      expect(errors, isEmpty, reason: errors.join('\n'));
    });

    // same `w[0]` initials in the register's header and side rail
    // (lib/retail/retail_screen.dart:914 and :1081)
    testWidgets('Sage & Poppy register: signed in as "🍔 Burger Bob" [$lang]', (
      tester,
    ) async {
      StoreProfile.current = _sp;
      Prefs.instance.lang = lang;
      Api.currentUser = AuthUser.fromJson({
        'userId': 'b',
        'name': '🍔 Burger Bob',
        'role': 'MANAGER',
        'languageCode': lang,
        'grants': ['void', 'refund', 'edit_menu', 'open_shift'],
      }, 'test-token');
      final errors = await _render(
        tester,
        const RetailScreen(),
        _spStore(),
        theme: buildSagePoppyTheme(Brightness.light),
      );
      expect(errors, isEmpty, reason: errors.join('\n'));
    });

    testWidgets('Sage & Poppy register: hostile basket, \$1,234,567.89 '
        '[$lang]', (tester) async {
      StoreProfile.current = _sp;
      Prefs.instance.lang = lang;
      final errors = await _render(
        tester,
        const RetailScreen(),
        _spStore(),
        theme: buildSagePoppyTheme(Brightness.light),
      );
      expect(errors, isEmpty, reason: errors.join('\n'));
    });
  }
}
