import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/retail/retail_screen.dart';
import 'package:pos_client/retail/sp_theme.dart';

/// The retail counter against a fake store: a HID-scanner burst rings a
/// product up, and an unknown barcode walks the manager through adding it,
/// then sells it. Rendered at the tablet's landscape size (overflow fails).
void main() {
  const us = StoreProfile(
    venueId: 'sage-poppy',
    brand: 'sage-poppy',
    kind: 'retail',
    country: 'US',
    currency: 'USD',
    locales: ['en', 'es'],
    legalAge: 21,
  );

  Map<String, dynamic> item(
    String id,
    String name,
    String code,
    int cents, {
    bool age = false,
    int crv = 0,
  }) => {
    'id': id,
    'nameFr': name,
    'nameEn': name,
    'descriptionFr': '',
    'descriptionEn': '',
    'category': 'mixers',
    'abbrev': name.substring(0, 2).toUpperCase(),
    'isAlcohol': age,
    'active': true,
    'variants': [
      {
        'id': '$id:each',
        'labelFr': 'Each',
        'labelEn': 'Each',
        'priceCents': cents,
      },
    ],
    'barcode': code,
    'ageRestricted': age,
    'taxable': true,
    'depositCents': crv,
  };

  Map<String, dynamic> sale(List<Map<String, dynamic>> lines) {
    final items = lines.fold<int>(
      0,
      (n, l) => n + (l['lineTotalCents'] as int),
    );
    return {
      'id': 7,
      'tableId': 'register-1',
      'status': 'OPEN',
      'corkageBottles': 0,
      'lines': lines,
      'pendingLines': [],
      'fees': [],
      'itemsSubtotalCents': items,
      'grandTotalCents': items,
      'paidCents': 0,
      'outstandingCents': items,
      'tenders': [],
      'subtotalCents': items,
      'taxes': [],
      'ageCheckRequired': false,
      'ageCleared': true,
      'ageCheckFailed': false,
    };
  }

  Map<String, dynamic> line(String id, String name, int cents) => {
    'id': 1,
    'itemId': id,
    'variantId': '$id:each',
    'nameFr': name,
    'nameEn': name,
    'qty': 1,
    'unitPriceCents': cents,
    'lineTotalCents': cents,
    'note': null,
  };

  late List<String> calls;
  late Set<String> known;
  late Map<String, dynamic>? addedProduct;

  MockClient store() => MockClient((req) async {
    final path = req.url.path;
    calls.add('${req.method} $path');
    http.Response json(Object body, [int status = 200]) => http.Response.bytes(
      utf8.encode(jsonEncode(body)),
      status,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );
    if (path == '/items') {
      return json([
        item('club-soda', 'Club Soda 1 L', '487230005010', 229, crv: 10),
        if (known.contains('012345678905'))
          item('p-lemonade', 'Sparkling Lemonade', '012345678905', 189),
      ]);
    }
    if (path == '/categories') {
      return json([
        {
          'id': 'mixers',
          'nameFr': 'Mixers & Soda',
          'nameEn': 'Mixers & Soda',
          'sortOrder': 0,
        },
      ]);
    }
    if (path == '/shifts/current') return json({'error': 'none'}, 404);
    if (path == '/retail/sales/current') return http.Response('', 204);
    if (path == '/retail/sales' && req.method == 'POST') {
      return json(sale([]), 201);
    }
    if (path == '/retail/sales/7/scan') {
      final code = (jsonDecode(req.body) as Map)['barcode'] as String;
      if (code == '487230005010') {
        return json(sale([line('club-soda', 'Club Soda 1 L', 229)]));
      }
      if (known.contains(code)) {
        return json(sale([line('p-lemonade', 'Sparkling Lemonade', 189)]));
      }
      return json({'error': 'no product', 'code': 'unknown_barcode'}, 404);
    }
    if (path == '/retail/lookup/012345678905') {
      return json({
        'barcode': '012345678905',
        'name': 'Sparkling Lemonade, 12 fl oz',
      });
    }
    if (path == '/retail/products' && req.method == 'POST') {
      addedProduct = jsonDecode(req.body) as Map<String, dynamic>;
      known.add(addedProduct!['barcode'] as String);
      return json({
        'itemId': 'p-lemonade',
        'variantId': 'p-lemonade:each',
        'barcode': '012345678905',
      }, 201);
    }
    return json({'error': 'not found'}, 404);
  });

  setUpAll(() async {
    // the bundled fonts, so text measures as on the tablet
    final manifest =
        jsonDecode(await rootBundle.loadString('FontManifest.json')) as List;
    for (final entry in manifest) {
      final loader = FontLoader(entry['family'] as String);
      for (final font in entry['fonts'] as List) {
        loader.addFont(rootBundle.load(font['asset'] as String));
      }
      await loader.load();
    }
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(
      const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
      (_) async => null,
    );
  });

  setUp(() {
    calls = [];
    known = {};
    addedProduct = null;
    StoreProfile.current = us;
    Prefs.instance.lang = 'en';
    Api.currentUser = AuthUser.fromJson({
      'userId': 'manager',
      'name': 'Demo Manager',
      'role': 'MANAGER',
      'languageCode': 'en',
      'grants': ['edit_menu', 'open_shift'],
    }, 'test-token');
  });

  tearDown(() => StoreProfile.current = StoreProfile.pub);

  Future<void> pumpCounter(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1920, 1200);
    tester.view.devicePixelRatio = 1.5;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      prefsScope(
        child: MaterialApp(
          theme: buildSagePoppyTheme(Brightness.light),
          home: const RetailScreen(),
        ),
      ),
    );
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
  }

  /// A HID scanner: every key of the code back to back, then Enter.
  Future<void> scan(WidgetTester tester, String code) async {
    for (final d in code.split('')) {
      await tester.sendKeyEvent(
        LogicalKeyboardKey(0x30 + int.parse(d)),
        character: d,
      );
    }
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
  }

  testWidgets('a scanner burst rings the product up', (tester) async {
    await http.runWithClient(() async {
      await pumpCounter(tester);
      expect(find.text('Scan to start a sale'), findsOneWidget);
      await scan(tester, '487230005010');
      expect(calls, contains('POST /retail/sales/7/scan'));
      expect(find.text('Club Soda 1 L'), findsWidgets); // tile + basket line
      expect(find.text('Sale #7'), findsOneWidget);
    }, store);
  });

  testWidgets('an unknown barcode: a manager adds it, then it sells', (
    tester,
  ) async {
    await http.runWithClient(() async {
      await pumpCounter(tester);
      await scan(tester, '012345678905');
      expect(find.text('Unknown barcode 012345678905'), findsOneWidget);
      await tester.tap(find.text('Add product (manager)'));
      for (var i = 0; i < 5; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      // the online suggestion prefilled the name
      expect(find.text('Sparkling Lemonade, 12 fl oz'), findsOneWidget);
      await tester.enterText(
        find.widgetWithText(TextField, 'Price (USD)'),
        '1.89',
      );
      await tester.tap(find.text('Add and ring up'));
      for (var i = 0; i < 8; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      expect(addedProduct?['priceCents'], 189);
      expect(addedProduct?['barcode'], '012345678905');
      expect(addedProduct?['categoryId'], 'mixers');
      // …and it was rung up straight away
      expect(calls.where((c) => c == 'POST /retail/sales/7/scan').length, 2);
      expect(find.text('Sparkling Lemonade'), findsWidgets);
    }, store);
  });
}
