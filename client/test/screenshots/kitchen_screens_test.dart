// Kitchen tickets at tablet size (1920x1200 px at 1.5x), with the store
// faked: the POS Kitchen view (timer colours, ADD / VOID), the check screen's
// Send button, the floor with the printer-offline banner, and station setup.
// Always a smoke test (an overflow fails it); to also write PNGs:
//   flutter test test/screenshots/kitchen_screens_test.dart --dart-define=SHOTS_DIR=$PWD/../docs/screenshots/kitchen
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
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/kitchen/kitchen_screen.dart';
import 'package:pos_client/kitchen/kitchen_setup_screen.dart';
import 'package:pos_client/screens/check_screen.dart';
import 'package:pos_client/screens/zones_screen.dart';

const _shotsDir = String.fromEnvironment('SHOTS_DIR');
const _physical = Size(1920, 1200);
const _dpr = 1.5;

final _fixtures = '${Directory.current.path}/test/screenshots/fixtures';
dynamic _fx(String name) =>
    jsonDecode(File('$_fixtures/$name.json').readAsStringSync());

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

Map<String, dynamic> _item(
  int id,
  int qty,
  String fr,
  String en, {
  String? variantFr,
  String? variantEn,
  String? note,
  bool add = false,
  bool voided = false,
  int voidedQty = 0,
}) => {
  'lineId': id,
  'qty': qty,
  'nameFr': fr,
  'nameEn': en,
  'variantFr': variantFr,
  'variantEn': variantEn,
  'note': note,
  'add': add,
  'voided': voided,
  'voidedQty': voidedQty,
};

Map<String, dynamic> _card(
  int check,
  String station,
  String fr,
  String en,
  String table,
  int seconds,
  List<Map<String, dynamic>> items, {
  int? guests,
}) => {
  'key': '$check:$station',
  'checkId': check,
  'stationId': station,
  'stationNameFr': fr,
  'stationNameEn': en,
  'tableLabel': table,
  'serverName': 'Demo Server',
  'guests': guests,
  'firstSentAt': '2026-09-26T19:00:00-04:00',
  'elapsedSeconds': seconds,
  'level': 'ok',
  'items': items,
};

Map<String, dynamic> _station(
  String id,
  String fr,
  String en,
  String output, {
  String host = '',
  int paper = 80,
}) => {
  'id': id,
  'nameFr': fr,
  'nameEn': en,
  'output': output,
  'printerHost': host,
  'printerPort': 9100,
  'paperMm': paper,
  'sortOrder': 0,
};

final _stations = [
  _station('kitchen', 'Cuisine', 'Kitchen', 'both'),
  _station('bar', 'Bar', 'Bar', 'printer', host: '192.168.8.21', paper: 58),
  _station('sushi', 'Bar à sushis', 'Sushi Bar', 'both'),
];

Map<String, dynamic> _board() => {
  'cards': [
    _card(41, 'kitchen', 'Cuisine', 'Kitchen', 'U-3', 23 * 60 + 12, [
      _item(
        1,
        2,
        'Burger de la Lanterne',
        'Copper Lantern Burger',
        note: 'sans oignons / no onions',
      ),
      _item(
        2,
        1,
        'Ailes de poulet',
        'Chicken Wings',
        note: 'sauce piquante à part',
        voided: true,
        voidedQty: 1,
      ),
      _item(3, 1, 'Poutine classique', 'Classic Poutine', add: true),
    ], guests: 4),
    _card(44, 'kitchen', 'Cuisine', 'Kitchen', 'P-2', 11 * 60 + 40, [
      _item(4, 3, 'Fish and chips', 'Fish and Chips'),
      _item(5, 2, 'Bretzel géant', 'Giant Pub Pretzel', voidedQty: 1),
    ]),
    _card(45, 'sushi', 'Bar à sushis', 'Sushi Bar', 'S-4', 2 * 60 + 5, [
      _item(6, 2, 'Maki au saumon', 'Salmon Maki'),
      _item(
        7,
        1,
        'Saké junmai',
        'Junmai Sake',
        variantFr: 'Carafe',
        variantEn: 'Carafe',
        note: 'tiède / warm',
      ),
    ], guests: 2),
    _card(46, 'kitchen', 'Cuisine', 'Kitchen', 'U-5', 48, [
      _item(
        8,
        1,
        'Steak de chou-fleur rôti',
        'Roasted Cauliflower Steak',
        note: 'allergie aux noix',
      ),
    ]),
  ],
  'recent': [
    {
      'bumpId': 'kb-1',
      'checkId': 39,
      'stationId': 'kitchen',
      'tableLabel': 'B-2',
      'bumpedAt': '2026-09-26T18:58:00-04:00',
    },
  ],
  'stations': [_stations[0], _stations[2]],
  'warnMinutes': 10,
  'lateMinutes': 20,
  'sound': true,
  'language': 'fr',
  'latestTicket': 12,
  'serverTime': '2026-09-26T19:23:12-04:00',
};

MockClient _store({bool offline = false}) => MockClient((req) async {
  final path = req.url.path;
  final body = switch (path) {
    '/health' => {
      ..._fx('health') as Map<String, dynamic>,
      'kitchenPrinting': true,
    },
    '/staff' => _fx('staff'),
    '/zones' => _fx('zones'),
    '/alert-config' => {
      'pendingAlertsEnabled': false,
      'pendingAlertEscalateSeconds': 90,
      'pendingAlertVolume': 0,
    },
    '/items' => _fx('items'),
    '/categories' => _fx('categories'),
    '/checks/1' => {
      ..._fx('check') as Map<String, dynamic>,
      'pendingLines': [],
    },
    '/checks/1/kitchen' => {
      'checkId': 1,
      'guests': 4,
      'unsent': 3,
      'pendingVoids': 0,
      'sent': 2,
    },
    '/kitchen/board' => _board(),
    '/kitchen/status' => {
      'enabled': true,
      'waiting': offline ? 2 : 0,
      'failing': offline,
      'lastError': offline ? 'ConnectException: Connection refused' : null,
      'stations': [],
      'jobs': [],
    },
    '/kitchen/config' => {
      'settings': {
        'language': '',
        'defaultStationId': 'kitchen',
        'warnMinutes': 10,
        'lateMinutes': 20,
        'sound': true,
      },
      'language': 'fr',
      'stations': _stations,
      'routes': [
        for (final c in [
          'appetizers',
          'burgers-sandwiches',
          'mains',
          'desserts',
        ])
          {'kind': 'category', 'refId': c, 'stationId': 'kitchen'},
        for (final c in ['draft-beer', 'cocktails'])
          {'kind': 'category', 'refId': c, 'stationId': 'bar'},
        {'kind': 'item', 'refId': 'lantern-lager', 'stationId': 'bar'},
      ],
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

void _mockPlugins() {
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  for (final name in [
    'plugins.it_nomads.com/flutter_secure_storage',
    'xyz.luan/audioplayers',
    'xyz.luan/audioplayers.global',
  ]) {
    messenger.setMockMethodCallHandler(MethodChannel(name), (_) async => null);
  }
}

final _shotKey = GlobalKey();

Future<void> _shoot(
  WidgetTester tester,
  String name,
  Widget screen, {
  MockClient? store,
  String? expectText,
}) async {
  tester.view.physicalSize = _physical;
  tester.view.devicePixelRatio = _dpr;
  addTearDown(tester.view.reset);
  final reportError = FlutterError.onError;
  FlutterError.onError = (details) {
    if (details.exception is MissingPluginException) return;
    reportError?.call(details);
  };
  await http.runWithClient(() async {
    await tester.pumpWidget(
      RepaintBoundary(
        key: _shotKey,
        child: prefsScope(
          child: MaterialApp(
            debugShowCheckedModeBanner: false,
            theme: buildPosTheme(),
            home: screen,
          ),
        ),
      ),
    );
    for (var i = 0; i < 6; i++) {
      await tester.pump(const Duration(milliseconds: 150));
    }
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 300)),
    );
    await tester.pump(const Duration(milliseconds: 150));
    if (expectText != null) expect(find.text(expectText), findsWidgets);
    if (_shotsDir.isNotEmpty) {
      final boundary =
          _shotKey.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      await tester.runAsync(() async {
        final image = await boundary.toImage(pixelRatio: _dpr);
        final png = await image.toByteData(format: ui.ImageByteFormat.png);
        Directory(_shotsDir).createSync(recursive: true);
        File(
          '$_shotsDir/$name.png',
        ).writeAsBytesSync(png!.buffer.asUint8List());
      });
    }
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 1));
  }, () => store ?? _store());
  FlutterError.onError = reportError;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() async {
    _mockPlugins();
    await _loadFonts();
  });

  setUp(() {
    Prefs.instance.lang = 'en';
    StoreProfile.current = const StoreProfile(kitchenPrinting: true);
    Api.currentUser = AuthUser.fromJson({
      'userId': 'manager',
      'name': 'Demo Manager',
      'role': 'MANAGER',
      'languageCode': 'en',
      'grants': ['void', 'refund', 'edit_menu', 'manage_staff'],
    }, 'test-token');
  });

  tearDown(() => StoreProfile.current = StoreProfile.pub);

  testWidgets('kitchen view', (tester) async {
    await _shoot(
      tester,
      'kitchen-view',
      const KitchenScreen(),
      expectText: 'U-3',
    );
    // the late card's timer, the ADD and VOID tags
  });

  testWidgets('kitchen view (French)', (tester) async {
    Prefs.instance.lang = 'fr';
    await _shoot(
      tester,
      'kitchen-view-fr',
      const KitchenScreen(),
      expectText: 'ANNULÉ',
    );
  });

  testWidgets('check with Send to kitchen', (tester) async {
    await _shoot(
      tester,
      'kitchen-check-send',
      const CheckScreen(checkId: 1, tableLabel: 'U-1'),
      expectText: 'Send (3)',
    );
  });

  testWidgets('floor with the kitchen printer offline', (tester) async {
    await _shoot(
      tester,
      'kitchen-printer-offline',
      const ZonesScreen(),
      store: _store(offline: true),
      expectText: 'Kitchen printer offline — 2 tickets waiting',
    );
  });

  testWidgets('station setup', (tester) async {
    await _shoot(
      tester,
      'kitchen-setup',
      const KitchenSetupScreen(),
      expectText: 'Test print',
    );
  });
}
