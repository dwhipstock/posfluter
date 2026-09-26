// The stock app against a fake store, at phone size (and the counter tablet
// for the ⋮ menu): count with a HID scanner, see the expected qty, review the
// variance, submit with a manager PIN; count offline; receive a delivery.
//
// To also write screenshots of the flow:
//   flutter test test/stock_app_test.dart \
//     --dart-define=SHOTS_DIR=$PWD/../docs/screenshots/stock
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
import 'package:pos_client/stock/barcode_scanner.dart';
import 'package:pos_client/stock/count_screens.dart';
import 'package:pos_client/stock/stock_home_screen.dart';
import 'package:pos_client/stock/stock_queue.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _shotsDir = String.fromEnvironment('SHOTS_DIR');
final _shotKey = GlobalKey();

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
  const lagerCode = '487230001029';
  const iceCode = '487230007014';

  Map<String, dynamic> item(String id, String name, String code) => {
    'id': id,
    'nameFr': name,
    'nameEn': name,
    'descriptionFr': '',
    'descriptionEn': '',
    'category': 'beer',
    'abbrev': 'GL',
    'isAlcohol': true,
    'active': true,
    'variants': [
      {
        'id': '$id:each',
        'labelFr': 'Each',
        'labelEn': 'Each',
        'priceCents': 1299,
      },
    ],
    'barcode': code,
  };

  late List<String> calls;
  late Map<String, dynamic>? submitBody;
  late Map<String, dynamic>? receiptBody;
  late Map<String, int> lines; // the store's view of the count
  late bool reachable;
  late String countName;
  const expected = {'golden-lager-6': 12, 'ice-7': 30};

  Map<String, dynamic> session(String id, {String status = 'OPEN'}) {
    final ls = [
      for (final e in lines.entries)
        {
          'itemId': e.key,
          'name': e.key == 'golden-lager-6'
              ? 'Golden Hour Lager 6-pack'
              : 'Party Ice 7 lb',
          'counted': e.value,
          'mine': e.value,
          'expected': expected[e.key],
          'variance': expected[e.key] == null
              ? null
              : e.value - expected[e.key]!,
          'countedAt': '2026-07-20T14:00:00.000-07:00',
        },
    ];
    final variance = ls.where((l) => (l['variance'] ?? 0) != 0).length;
    return {
      'id': id,
      'name': countName,
      'status': status,
      'startedBy': 'cashier',
      'startedByName': 'Demo Cashier',
      'startedAt': '2026-07-20T13:40:00.000-07:00',
      'lines': ls,
      'varianceLines': variance,
      'needsApproval': status == 'OPEN' && variance > 0,
    };
  }

  MockClient store() => MockClient((req) async {
    if (!reachable) throw const SocketException('no route to host');
    final path = req.url.path;
    calls.add('${req.method} $path');
    http.Response json(Object body, [int status = 200]) => http.Response.bytes(
      utf8.encode(jsonEncode(body)),
      status,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );
    if (path == '/items') {
      return json([
        item('golden-lager-6', 'Golden Hour Lager 6-pack', lagerCode),
        item('ice-7', 'Party Ice 7 lb', iceCode),
      ]);
    }
    if (path == '/categories') return json([]);
    if (path == '/shifts/current') return json({'error': 'none'}, 404);
    if (path == '/retail/sales/current') return http.Response('', 204);
    if (path == '/stock/expected') {
      return json({
        'available': true,
        'asOf': '2026-07-20T13:00:00.000-07:00',
        'items': expected,
      });
    }
    if (path == '/stock/counts' && req.method == 'GET') return json([]);
    if (path == '/stock/counts' && req.method == 'POST') {
      final body = jsonDecode(req.body) as Map<String, dynamic>;
      countName = (body['name'] as String?) ?? 'Count 2026-07-20';
      return json(session(body['id'] as String), 201);
    }
    final m = RegExp(
      r'^/stock/counts/([^/]+)(/(lines|submit))?$',
    ).firstMatch(path);
    if (m != null) {
      final id = m.group(1)!;
      if (m.group(3) == 'lines') {
        for (final l
            in ((jsonDecode(req.body) as Map)['lines'] as List).cast<Map>()) {
          if (l['remove'] == true) {
            lines.remove(l['itemId']);
          } else {
            lines[l['itemId'] as String] = l['qty'] as int;
          }
        }
        return json(session(id));
      }
      if (m.group(3) == 'submit') {
        submitBody = jsonDecode(req.body) as Map<String, dynamic>;
        if (submitBody!['managerPin'] != '1234') {
          return json({
            'error': 'manager',
            'code': 'manager_approval_required',
          }, 403);
        }
        return json(session(id, status: 'SUBMITTED'));
      }
      return json(session(id));
    }
    if (path == '/stock/receipts' && req.method == 'POST') {
      receiptBody = jsonDecode(req.body) as Map<String, dynamic>;
      return json({
        'id': receiptBody!['id'],
        'supplier': receiptBody!['supplier'],
        'units': 2,
      }, 201);
    }
    return json({'error': 'not found'}, 404);
  });

  setUpAll(() async {
    final manifest =
        jsonDecode(await rootBundle.loadString('FontManifest.json')) as List;
    for (final entry in manifest) {
      final loader = FontLoader(entry['family'] as String);
      for (final font in entry['fonts'] as List) {
        loader.addFont(rootBundle.load(font['asset'] as String));
      }
      await loader.load();
    }
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
          (_) async => null,
        );
  });

  setUp(() {
    calls = [];
    submitBody = null;
    receiptBody = null;
    lines = {};
    reachable = true;
    countName = '';
    AppMode.isStock = true;
    // no camera under test; screenshots show the button as a phone has it
    CameraScanner.supportedOverride = _shotsDir.isNotEmpty;
    SharedPreferences.setMockInitialValues({});
    StoreProfile.current = us;
    Prefs.instance.lang = 'en';
    Api.currentUser = AuthUser.fromJson({
      'userId': 'cashier',
      'name': 'Demo Cashier',
      'role': 'SERVER',
      'languageCode': 'en',
      'grants': ['price_override'],
    }, 'test-token');
  });

  tearDown(() {
    AppMode.isStock = false;
    CameraScanner.supportedOverride = null;
    StoreProfile.current = StoreProfile.pub;
  });

  Future<void> settle(WidgetTester tester, [int frames = 6]) async {
    for (var i = 0; i < frames; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
  }

  Future<void> snap(WidgetTester tester, String name) async {
    if (_shotsDir.isEmpty) return;
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 200)),
    );
    await tester.pump(const Duration(milliseconds: 100));
    final boundary =
        _shotKey.currentContext!.findRenderObject()! as RenderRepaintBoundary;
    await tester.runAsync(() async {
      final image = await boundary.toImage(
        pixelRatio: tester.view.devicePixelRatio,
      );
      final png = await image.toByteData(format: ui.ImageByteFormat.png);
      Directory(_shotsDir).createSync(recursive: true);
      File('$_shotsDir/$name.png').writeAsBytesSync(png!.buffer.asUint8List());
    });
  }

  /// A phone (iPhone 14-ish: 390×844 logical at 3×) or the counter tablet.
  Future<void> pump(
    WidgetTester tester,
    Widget home, {
    bool tablet = false,
  }) async {
    tester.view.physicalSize = tablet
        ? const Size(1920, 1200)
        : const Size(1170, 2532);
    tester.view.devicePixelRatio = tablet ? 1.5 : 3;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      RepaintBoundary(
        key: _shotKey,
        child: prefsScope(
          child: MaterialApp(
            debugShowCheckedModeBanner: false,
            theme: buildSagePoppyTheme(Brightness.light),
            home: home,
          ),
        ),
      ),
    );
    await settle(tester);
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
    await settle(tester);
  }

  testWidgets(
    'count on the phone: scan, expected qty, variance, manager approves',
    (tester) async {
      final queue = StockQueue(MemoryKeyValueStore());
      await http.runWithClient(() async {
        await pump(tester, StockHomeScreen(queue: queue));
        expect(find.text('Count'), findsOneWidget);
        expect(find.text('Receive'), findsOneWidget);
        await snap(tester, '01-phone-home');

        await tester.tap(find.byKey(const Key('home-count')));
        await settle(tester);
        expect(find.text('No open counts. Start one.'), findsOneWidget);
        await tester.tap(find.byKey(const Key('start-count')));
        await settle(tester);
        await tester.enterText(
          find.byKey(const Key('count-name')),
          'Beer cooler',
        );
        await tester.tap(find.byKey(const Key('count-start')));
        await settle(tester, 10);
        expect(find.text('Beer cooler'), findsOneWidget);
        expect(
          find.text('Nothing counted yet. Scan a product to start.'),
          findsOneWidget,
        );

        // a Bluetooth scanner: three six-packs of lager scanned, one ice typed in
        await scan(tester, lagerCode);
        await scan(tester, lagerCode);
        await scan(tester, lagerCode);
        await scan(tester, iceCode);
        expect(find.text('Golden Hour Lager 6-pack'), findsOneWidget);
        expect(
          find.byKey(const Key('expected-golden-lager-6')),
          findsOneWidget,
        );
        expect(find.text('expected 12'), findsOneWidget);
        expect(find.text('expected 30'), findsOneWidget);
        // exact number for the lager: 11 on the shelf
        await tester.tap(
          find.descendant(
            of: find.byKey(const Key('line-golden-lager-6')),
            matching: find.text('3'),
          ),
        );
        await settle(tester);
        await tester.enterText(find.byKey(const Key('qty-exact')), '11');
        await tester.tap(find.text('OK'));
        await settle(tester, 10);
        expect(
          find.descendant(
            of: find.byKey(const Key('line-golden-lager-6')),
            matching: find.text('11'),
          ),
          findsOneWidget,
        );
        await snap(tester, '02-phone-counting');
        expect(calls, contains('POST /stock/counts'));
        expect(
          calls,
          contains('PUT /stock/counts/${queue.drafts.single.id}/lines'),
        );

        // review: both lines differ from the expected qty
        await tester.tap(find.byKey(const Key('count-review')));
        await settle(tester, 10);
        expect(find.text('2 products differ'), findsOneWidget);
        expect(find.text('-1'), findsOneWidget); // 11 vs 12
        expect(find.text('-29'), findsOneWidget); // 1 vs 30
        await snap(tester, '03-phone-review');

        // a cashier: a manager's PIN approves the variance
        await tester.tap(find.byKey(const Key('count-submit')));
        await settle(tester);
        expect(find.text('A manager approves variances'), findsOneWidget);
        await tester.enterText(find.byKey(const Key('manager-pin')), '1234');
        await snap(tester, '04-phone-manager-pin');
        await tester.tap(find.text('OK'));
        await settle(tester, 10);
        expect(submitBody?['managerPin'], '1234');
        expect(lines, {'golden-lager-6': 11, 'ice-7': 1});
        expect(queue.pending, 0);
        expect(find.text('Count submitted'), findsOneWidget);
        expect(find.text('Count'), findsOneWidget); // back home
      }, store);
    },
  );

  testWidgets('no Wi-Fi: counting goes on and the counts wait on the phone', (
    tester,
  ) async {
    final queue = StockQueue(MemoryKeyValueStore());
    await queue.load();
    await queue.cacheReference(
      catalog: const [
        StockProduct(
          id: 'golden-lager-6',
          name: 'Golden Hour Lager 6-pack',
          barcode: lagerCode,
        ),
      ],
    );
    reachable = false;
    await http.runWithClient(() async {
      await pump(tester, CountListScreen(queue: queue));
      await settle(tester, 20); // the list request retries before giving up
      expect(
        find.text('Can’t reach the store — you can still count here.'),
        findsOneWidget,
      );
      await tester.tap(find.byKey(const Key('start-count')));
      await settle(tester);
      await tester.tap(find.byKey(const Key('count-start')));
      await settle(tester, 10);
      await scan(tester, lagerCode);
      await scan(tester, lagerCode);
      expect(find.text('Golden Hour Lager 6-pack'), findsOneWidget);
      expect(find.text('no expected qty'), findsOneWidget);
      expect(
        find.text('No connection to the store — saved on this phone'),
        findsOneWidget,
      );
      await snap(tester, '05-phone-offline');
      expect(queue.pending, 2); // start + one lines op
      expect(queue.drafts.single.lines['golden-lager-6']!.qty, 2);

      // the Wi-Fi is back: "Send now" delivers it
      reachable = true;
      await tester.tap(find.text('Send now'));
      await settle(tester, 10);
      expect(queue.pending, 0);
      expect(lines, {'golden-lager-6': 2});
      expect(find.text('Everything is sent'), findsOneWidget);
    }, store);
  });

  testWidgets('receive a delivery on the phone', (tester) async {
    final queue = StockQueue(MemoryKeyValueStore());
    await http.runWithClient(() async {
      await pump(tester, StockHomeScreen(queue: queue));
      await tester.tap(find.byKey(const Key('home-receive')));
      await settle(tester, 10);
      await tester.enterText(
        find.byKey(const Key('receive-supplier')),
        'Valley Beverage',
      );
      await tester.enterText(
        find.byKey(const Key('receive-reference')),
        'INV-1042',
      );
      await scan(tester, lagerCode);
      await scan(tester, lagerCode);
      await scan(tester, '000000000000'); // not in the catalog
      expect(find.text('No product with barcode 000000000000'), findsOneWidget);
      expect(find.text('Save delivery · 2 units'), findsOneWidget);
      await snap(tester, '06-phone-receive');
      await tester.tap(find.byKey(const Key('receive-save')));
      await settle(tester, 10);
      expect(receiptBody?['supplier'], 'Valley Beverage');
      expect(receiptBody?['reference'], 'INV-1042');
      expect(receiptBody?['lines'], [
        {'itemId': 'golden-lager-6', 'qty': 2},
      ]);
      expect(receiptBody?['id'], isA<String>());
      expect(find.text('Delivery saved'), findsOneWidget);
    }, store);
  });

  testWidgets('the counter tablet opens the same Count screens from its rail', (
    tester,
  ) async {
    AppMode.isStock = false;
    await http.runWithClient(() async {
      await pump(tester, const RetailScreen(), tablet: true);
      expect(find.byKey(const Key('menu-count')), findsOneWidget);
      expect(find.byKey(const Key('menu-receive')), findsOneWidget);
      await tester.tap(find.byKey(const Key('menu-count')));
      await settle(tester, 10);
      expect(find.byType(CountListScreen), findsOneWidget);
      await tester.tap(find.byKey(const Key('start-count')));
      await settle(tester);
      await tester.enterText(find.byKey(const Key('count-name')), 'Back room');
      await tester.tap(find.byKey(const Key('count-start')));
      await settle(tester, 10);
      await scan(tester, lagerCode);
      await scan(tester, iceCode);
      expect(find.text('expected 12'), findsOneWidget);
      await snap(tester, '07-tablet-count');
    }, store);
  });
}
