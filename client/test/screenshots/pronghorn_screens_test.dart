// Pronghorn Fuel & Market screenshot harness: the gas station's sign-in and
// counter — the pump grid, a pump's actions, the prepay flow, a mixed
// receipt, Spanish and an offline forecourt — at the Galaxy Tab's landscape
// size, with no network. The fixture (fixtures/pronghorn.json) is what a real
// store answered with the forecourt simulator running a busy scene: its
// /forecourt, the open sale, the shelf slice and a closed sale's receipt.
//
// Always runs as a smoke test (a layout overflow fails it). To write PNGs:
//   flutter test test/screenshots/pronghorn_screens_test.dart \
//     --dart-define=SHOTS_DIR=$PWD/../docs/screenshots/gas-station
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
const _tablet = Size(1920, 1200);
const _dpr = 1.5;

final _fx =
    jsonDecode(
          File(
            '${Directory.current.path}/test/screenshots/fixtures/pronghorn.json',
          ).readAsStringSync(),
        )
        as Map<String, dynamic>;

const _profile = StoreProfile(
  venueId: 'pronghorn',
  brand: 'pronghorn',
  kind: 'retail',
  country: 'US',
  currency: 'USD',
  locales: ['en', 'es'],
  legalAge: 21,
  forecourt: true,
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

MockClient _store({bool offline = false, bool basket = true}) => MockClient((
  req,
) async {
  final path = req.url.path;
  if (path == '/retail/sales/current' && !basket) {
    return http.Response('', 204);
  }
  final Object? body = switch (path) {
    '/health' => {
      'status': 'ok',
      'venue': 'Pronghorn Fuel & Market',
      'venueId': 'pronghorn',
      'brand': 'pronghorn',
      'kind': 'retail',
      'country': 'US',
      'currency': 'USD',
      'locales': ['en', 'es'],
      'legalAge': 21,
      'forecourt': true,
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
    '/retail/sales/current' => _fx['sale'],
    '/forecourt' =>
      offline
          ? {
              'online': false,
              'message': 'ConnectException: Connection refused',
              'grades': [],
              'pumps': [
                for (var i = 1; i <= 8; i++) {'pump': i, 'state': 'OFFLINE'},
              ],
            }
          : _fx['forecourt'],
    '/shifts/current' => {
      'id': 3,
      'status': 'OPEN',
      'openedAt': '2026-09-26T13:00:00Z',
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
  bool offline = false,
  bool basket = true,
  String? expectText,
  Future<void> Function(WidgetTester tester)? act,
}) async {
  tester.view.physicalSize = _tablet;
  tester.view.devicePixelRatio = _dpr;
  addTearDown(tester.view.reset);
  await http.runWithClient(() async {
    await tester.pumpWidget(
      RepaintBoundary(
        key: _shotKey,
        child: prefsScope(
          child: MaterialApp(
            debugShowCheckedModeBanner: false,
            theme: buildPronghornTheme(),
            home: screen,
          ),
        ),
      ),
    );
    await _settle(tester);
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
        final image = await boundary.toImage(pixelRatio: _dpr);
        final png = await image.toByteData(format: ui.ImageByteFormat.png);
        Directory(_shotsDir).createSync(recursive: true);
        File(
          '$_shotsDir/pos-$name.png',
        ).writeAsBytesSync(png!.buffer.asUint8List());
      });
    }
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 1));
  }, () => _store(offline: offline, basket: basket));
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
  });

  testWidgets('sign-in', (tester) async {
    Api.currentUser = null;
    await _shoot(tester, 'login', const LoginScreen(), expectText: 'Pronghorn');
  });

  testWidgets('counter: the pump grid and a mixed basket', (tester) async {
    await _shoot(
      tester,
      'pump-grid',
      const RetailScreen(),
      expectText: 'FUELLING',
    );
  });

  testWidgets('a payable pump: add its fuel to the sale', (tester) async {
    await _shoot(
      tester,
      'pump-sheet-payable',
      const RetailScreen(),
      act: (t) => _tapKey(t, 'pump-5'),
      expectText: 'to the sale',
    );
  });

  testWidgets('prepay: pick an amount for a calling pump', (tester) async {
    await _shoot(
      tester,
      'prepay-dialog',
      const RetailScreen(),
      basket: false,
      act: (t) async {
        await _tapKey(t, 'pump-1');
        await _tapKey(t, 'prepay');
        await _tapKey(t, 'preset-4000');
      },
      expectText: 'Add \$40.00 prepay',
    );
  });

  testWidgets('change due on a finished prepay', (tester) async {
    await _shoot(
      tester,
      'prepay-change',
      const RetailScreen(),
      act: (t) => _tapKey(t, 'pump-7'),
      expectText: 'Change given',
    );
  });

  testWidgets('counter in Spanish', (tester) async {
    Prefs.instance.lang = 'es';
    await _shoot(
      tester,
      'pump-grid-es',
      const RetailScreen(),
      expectText: 'DESPACHANDO',
    );
  });

  testWidgets('forecourt offline: the shop keeps selling', (tester) async {
    await _shoot(
      tester,
      'pumps-offline',
      const RetailScreen(),
      offline: true,
      expectText: 'Pumps offline',
    );
  });

  testWidgets('a mixed fuel + shop receipt', (tester) async {
    await _shoot(
      tester,
      'receipt-mixed',
      ReceiptScreen(
        checkId: 1,
        title: 'Receipt · Sale #1',
        text: _fx['receipt'] as String,
      ),
      expectText: 'gal @',
    );
  });
}
