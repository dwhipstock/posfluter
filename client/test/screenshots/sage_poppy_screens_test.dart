// Sage & Poppy screenshot harness: the bottle shop's sign-in and counter at
// the Galaxy Tab's landscape size (1920x1200 px at 1.5x), against a slice of
// the real ~5,000-product shelf (fixtures/sage_poppy.json, exported from a
// seeded store), with no network. The stock app's sign-in at phone size.
//
// Always runs as a smoke test (a layout overflow fails it). To write PNGs:
//   flutter test test/screenshots/sage_poppy_screens_test.dart \
//     --dart-define=SHOTS_DIR=$PWD/../docs/screenshots
import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/app_mode.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/retail/retail_screen.dart';
import 'package:pos_client/retail/sp_theme.dart';
import 'package:pos_client/screens/login_screen.dart';
import 'package:pos_client/screens/receipt_screen.dart';

const _shotsDir = String.fromEnvironment('SHOTS_DIR');
const _shotPrefix = String.fromEnvironment('SHOT_PREFIX');
const _tablet = Size(1920, 1200);
const _tabletDpr = 1.5;
const _phone = Size(1080, 2340);
const _phoneDpr = 2.75;

final _fixtures = '${Directory.current.path}/test/screenshots/fixtures';
final Map<String, dynamic> _fx =
    jsonDecode(File('$_fixtures/sage_poppy.json').readAsStringSync())
        as Map<String, dynamic>;

const _profile = StoreProfile(
  venueId: 'sage-poppy',
  brand: 'sage-poppy',
  kind: 'retail',
  country: 'US',
  currency: 'USD',
  locales: ['en', 'es'],
  legalAge: 21,
);

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

Map<String, dynamic> _item(String id) =>
    (_fx['items'] as List).cast<Map<String, dynamic>>().firstWhere(
      (i) => i['id'] == id,
    );

/// A sale in progress: a six-pack, a bottle of wine, chips and ice.
Map<String, dynamic> _sale() {
  var n = 0;
  Map<String, dynamic> line(String id, int qty) {
    final i = _item(id);
    final cents = i['variants'][0]['priceCents'] as int;
    return {
      'id': ++n,
      'itemId': id,
      'variantId': '$id:each',
      'nameFr': i['nameEn'],
      'nameEn': i['nameEn'],
      'qty': qty,
      'unitPriceCents': cents,
      'lineTotalCents': cents * qty,
      'note': null,
      'ageRestricted': i['ageRestricted'] ?? false,
      'taxable': i['taxable'] ?? true,
      'depositCents': i['depositCents'] ?? 0,
    };
  }

  final lines = [
    line('golden-lager-6', 2),
    line('coastal-cab', 1),
    line('chips-sea-salt', 1),
    line('ice-7', 1),
  ];
  final items = lines.fold<int>(0, (s, l) => s + (l['lineTotalCents'] as int));
  final crv = lines.fold<int>(
    0,
    (s, l) => s + (l['depositCents'] as int) * (l['qty'] as int),
  );
  final taxable = lines
      .where((l) => l['taxable'] == true)
      .fold<int>(0, (s, l) => s + (l['lineTotalCents'] as int));
  final tax = (taxable * 95 + 500) ~/ 1000;
  final total = items + crv + tax;
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
        'amountCents': crv,
        'taxable': false,
      },
    ],
    'itemsSubtotalCents': items,
    'grandTotalCents': total,
    'paidCents': 0,
    'outstandingCents': total,
    'tenders': [],
    'subtotalCents': items + crv,
    'taxes': [
      {
        'code': 'SALES',
        'labelFr': 'Sales Tax',
        'labelEn': 'Sales Tax',
        'ratePercent': '9.5',
        'amountCents': tax,
      },
    ],
    'ageCheckRequired': true,
    'ageCleared': false,
    'ageCheckFailed': false,
  };
}

MockClient _store({bool basket = false}) => MockClient((req) async {
  final path = req.url.path;
  if (path == '/retail/sales/current' && !basket) {
    return http.Response('', 204);
  }
  final body = switch (path) {
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
    '/staff' => [
      {'id': 'manager', 'name': 'Demo Manager', 'role': 'MANAGER'},
      {'id': 'cashier', 'name': 'Demo Cashier', 'role': 'SERVER'},
      {'id': 'cajera', 'name': 'Cajera Demo', 'role': 'SERVER'},
    ],
    '/items' => _fx['items'],
    '/categories' => _fx['categories'],
    '/retail/quick-keys' => _fx['quickKeys'],
    '/retail/top-sellers' => _fx['topSellers'],
    '/retail/sales/current' => _sale(),
    '/shifts/current' => {
      'id': 12,
      'status': 'OPEN',
      'openedAt': '2026-09-25T16:00:00Z',
      'openedBy': 'manager',
      'openingFloatCents': 20000,
    },
    _ => null,
  };
  if (body == null) return http.Response('{"error":"not found"}', 404);
  return http.Response.bytes(
    utf8.encode(jsonEncode(body)),
    200,
    headers: {'content-type': 'application/json; charset=utf-8'},
  );
});

final _shotKey = GlobalKey();

Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 6; i++) {
    await tester.pump(const Duration(milliseconds: 150));
  }
}

Future<void> _shoot(
  WidgetTester tester,
  String name,
  Widget screen, {
  bool basket = false,
  bool dark = false,
  bool phone = false,
  String? expectText,
  Future<void> Function(WidgetTester tester)? act,
}) async {
  tester.view.physicalSize = phone ? _phone : _tablet;
  tester.view.devicePixelRatio = phone ? _phoneDpr : _tabletDpr;
  addTearDown(tester.view.reset);
  await http.runWithClient(() async {
    await tester.pumpWidget(
      RepaintBoundary(
        key: _shotKey,
        child: prefsScope(
          child: MaterialApp(
            debugShowCheckedModeBanner: false,
            theme: buildSagePoppyTheme(Brightness.light),
            darkTheme: buildSagePoppyTheme(Brightness.dark),
            themeMode: dark ? ThemeMode.dark : ThemeMode.light,
            home: screen,
          ),
        ),
      ),
    );
    await _settle(tester);
    // the catalog decodes off the UI thread: give it real time
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 400)),
    );
    await _settle(tester);
    if (act != null) {
      await act(tester);
      await _settle(tester);
    }
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 300)),
    );
    await tester.pump(const Duration(milliseconds: 150));
    if (expectText != null) {
      expect(find.textContaining(expectText, findRichText: true), findsWidgets);
    }
    if (_shotsDir.isNotEmpty) {
      final boundary =
          _shotKey.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      await tester.runAsync(() async {
        final image = await boundary.toImage(
          pixelRatio: phone ? _phoneDpr : _tabletDpr,
        );
        final png = await image.toByteData(format: ui.ImageByteFormat.png);
        Directory(_shotsDir).createSync(recursive: true);
        File(
          '$_shotsDir/${_shotPrefix}sp-$name.png',
        ).writeAsBytesSync(png!.buffer.asUint8List());
      });
    }
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 1));
  }, () => _store(basket: basket));
}

Future<void> _tapKey(WidgetTester tester, String key) async {
  await tester.tap(find.byKey(Key(key)));
  await _settle(tester);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() async {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(
      const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
      (_) async => null,
    );
    await _loadFonts();
  });

  setUp(() {
    StoreProfile.current = _profile;
    Prefs.instance.lang = 'en';
    AppMode.isStock = false;
    Api.currentUser = AuthUser.fromJson({
      'userId': 'manager',
      'name': 'Demo Manager',
      'role': 'MANAGER',
      'languageCode': 'en',
      'grants': ['edit_menu', 'open_shift'],
    }, 'test-token');
  });

  tearDown(() {
    StoreProfile.current = StoreProfile.pub;
    AppMode.isStock = false;
  });

  testWidgets('sign-in', (tester) async {
    Api.currentUser = null;
    await _shoot(tester, 'login', const LoginScreen(), expectText: 'Demo Cashier');
  });

  testWidgets('sign-in (Spanish, dark)', (tester) async {
    Api.currentUser = null;
    Prefs.instance.lang = 'es';
    await _shoot(tester, 'login-es-dark', const LoginScreen(), dark: true);
  });

  testWidgets('counter: quick keys', (tester) async {
    await _shoot(tester, 'counter', const RetailScreen());
  });

  testWidgets('counter with a basket', (tester) async {
    await _shoot(tester, 'counter-basket', const RetailScreen(), basket: true);
  });

  testWidgets('counter, dark', (tester) async {
    await _shoot(
      tester,
      'counter-dark',
      const RetailScreen(),
      basket: true,
      dark: true,
    );
  });

  testWidgets('top sellers', (tester) async {
    await _shoot(
      tester,
      'top-sellers',
      const RetailScreen(),
      basket: true,
      act: (t) => _tapKey(t, 'tab-top'),
    );
  });

  testWidgets('search "ipa 6"', (tester) async {
    await _shoot(
      tester,
      'search',
      const RetailScreen(),
      basket: true,
      act: (t) async {
        await t.enterText(find.byKey(const Key('counter-search')), 'ipa 6');
        await _settle(t);
      },
    );
  });

  testWidgets('browse: Beer › IPA › 6-pack', (tester) async {
    await _shoot(
      tester,
      'browse',
      const RetailScreen(),
      basket: true,
      act: (t) async {
        await _tapKey(t, 'tab-browse');
        await _tapKey(t, 'cat-beer');
        await _tapKey(t, 'sub-IPA');
        await _tapKey(t, 'size-6-pack');
      },
      expectText: 'Beer & Cider › IPA › 6-pack',
    );
  });

  testWidgets('receipt', (tester) async {
    await _shoot(
      tester,
      'receipt',
      const ReceiptScreen(
        checkId: 1042,
        title: 'Receipt · Sale #1042',
        text:
            'Sage & Poppy — Bottle Shop\n'
            '1427 Poppy Field Ave, Los Angeles, CA 90026\n'
            'Tel: +1 213 555 0148\n\n'
            'Register 1                      Sale #1042\n'
            'Opened               09/25/2026 5:41 PM\n'
            'Closed               09/25/2026 5:43 PM\n'
            '------------------------------------------\n'
            'Golden Hour Lager 6-pack ×2        19.98\n'
            '  @9.99\n'
            'Coastal Ridge Cabernet Sauvignon    18.99\n'
            'CRV                                 0.60\n'
            '------------------------------------------\n'
            'Subtotal                           39.57\n'
            'Sales Tax 9.5%                      3.70\n'
            'Total                              43.27\n',
      ),
    );
  });

  testWidgets('stock app sign-in (phone)', (tester) async {
    Api.currentUser = null;
    AppMode.isStock = true;
    await _shoot(tester, 'stock-login-phone', const LoginScreen(), phone: true);
  });
}
