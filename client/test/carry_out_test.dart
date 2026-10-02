import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/carryout/carry_out_screen.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/screens/check_screen.dart';

/// Carry-out at the full-service pub: a Carry-out spot on the floor shows
/// the open orders and opens their list; a new order (optional name /
/// phone) opens the normal check screen as "Order #105 · Carry-out"; Mark
/// ready / Picked up (only once paid); an order left empty is dropped.
final _fixtures = '${Directory.current.path}/test/screenshots/fixtures';
dynamic _fx(String name) =>
    jsonDecode(File('$_fixtures/$name.json').readAsStringSync());

http.Response _json(Object b, [int status = 200]) => http.Response.bytes(
  utf8.encode(jsonEncode(b)),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

class _Store {
  final orders = <Map<String, dynamic>>[];
  final created = <Map<String, dynamic>>[];
  final statusCalls = <String>[];
  final discarded = <int>[];
  int _next = 105;

  Map<String, dynamic> order(
    int number, {
    String status = 'PREPARING',
    bool paid = false,
    String? name,
    int total = 2350,
  }) => {
    'checkId': number - 100,
    'orderNumber': number,
    'serviceMode': 'TAKE_OUT',
    'source': 'CARRY_OUT',
    'status': status,
    'checkStatus': paid ? 'CLOSED' : 'OPEN',
    'totalCents': total,
    'outstandingCents': paid ? 0 : total,
    'itemCount': 2,
    'hasAlcohol': false,
    'createdAt': DateTime.now()
        .subtract(const Duration(minutes: 12))
        .toIso8601String(),
    'customerName': name,
  };

  late final client = MockClient((req) async {
    final p = req.url.path;
    final body = req.body.isEmpty ? null : jsonDecode(req.body);
    if (p == '/items') return _json(_fx('items'));
    if (p == '/categories') return _json(_fx('categories'));
    if (p == '/carryout/orders' && req.method == 'GET') return _json(orders);
    if (p == '/carryout/orders' && req.method == 'POST') {
      created.add(body as Map<String, dynamic>);
      final o = order(
        _next++,
        status: 'OPEN',
        name: body['customerName'] as String?,
        total: 0,
      );
      orders.add(o);
      return _json(o, 201);
    }
    final o = RegExp(r'^/carryout/orders/(\d+)/(\w+)$').firstMatch(p);
    if (o != null) {
      final id = int.parse(o[1]!);
      if (o[2] == 'status') statusCalls.add('$id:${body['status']}');
      if (o[2] == 'discard') discarded.add(id);
      return _json(orders.firstWhere((x) => x['checkId'] == id));
    }
    final c = RegExp(r'^/checks/(\d+)$').firstMatch(p);
    if (c != null) {
      return _json({
        ...(_fx('check') as Map<String, dynamic>),
        'id': int.parse(c[1]!),
        'status': 'OPEN',
        'lines': [],
        'pendingLines': [],
        'fees': [],
        'taxes': [],
        'itemsSubtotalCents': 0,
        'subtotalCents': 0,
        'grandTotalCents': 0,
        'paidCents': 0,
        'outstandingCents': 0,
      });
    }
    return http.Response('{"error":"not found"}', 404);
  });
}

void main() {
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
  setUp(() => Prefs.instance.lang = 'en');

  Future<void> pump(WidgetTester tester, Widget home) async {
    tester.view.physicalSize = const Size(2736, 1824); // a Surface, at 150%
    tester.view.devicePixelRatio = 1.5;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      prefsScope(
        child: MaterialApp(theme: buildPosTheme(), home: home),
      ),
    );
    for (var i = 0; i < 6; i++) {
      await tester.pump(const Duration(milliseconds: 150));
    }
  }

  testWidgets('the Carry-out spot shows the open orders and is tappable', (
    tester,
  ) async {
    var taps = 0;
    final spot = FloorObject(
      'o1',
      'CARRY_OUT',
      40,
      900,
      160,
      90,
      0,
      null,
      null,
    );
    for (final lang in ['en', 'fr', 'es', 'de', 'af']) {
      Prefs.instance.lang = lang;
      await pump(
        tester,
        Scaffold(
          body: SizedBox(
            width: 240,
            height: 135,
            child: CarryOutSpot(
              object: spot,
              scale: 1.5,
              openCount: 3,
              onTap: () => taps++,
            ),
          ),
        ),
      );
      expect(tester.takeException(), isNull, reason: 'no overflow in $lang');
    }
    Prefs.instance.lang = 'en';
    await pump(
      tester,
      Scaffold(
        body: SizedBox(
          width: 240,
          height: 135,
          child: CarryOutSpot(
            object: spot,
            scale: 1.5,
            openCount: 3,
            onTap: () => taps++,
          ),
        ),
      ),
    );
    expect(find.text('Carry-out'), findsOneWidget);
    expect(find.text('3 open'), findsOneWidget);
    await tester.tap(find.byKey(const Key('carryout-spot-o1')));
    expect(taps, 1);
  });

  testWidgets('the list: number, customer, paid or pay at pickup; '
      'ready, and picked up only once paid', (tester) async {
    final store = _Store();
    store.orders
      ..add(store.order(101, name: 'Sam'))
      ..add(store.order(102, status: 'READY'))
      ..add(store.order(103, status: 'READY', paid: true));
    await http.runWithClient(() async {
      await pump(tester, const CarryOutScreen());
      expect(find.text('#101'), findsOneWidget);
      expect(find.textContaining('Sam'), findsOneWidget);
      expect(find.text('Pay at pickup'), findsNWidgets(2));
      expect(find.text('Paid'), findsOneWidget);
      expect(find.text('New carry-out order'), findsOneWidget);

      await tester.tap(find.byKey(const Key('ready-101')));
      await tester.pump(const Duration(milliseconds: 300));
      expect(store.statusCalls, ['1:READY']);

      // unpaid: told to take the payment first, nothing sent
      await tester.tap(find.byKey(const Key('pickedup-102')));
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.text('Take the payment before pickup'), findsOneWidget);
      expect(store.statusCalls, ['1:READY']);

      await tester.tap(find.byKey(const Key('pickedup-103')));
      await tester.pump(const Duration(milliseconds: 300));
      expect(store.statusCalls, ['1:READY', '3:PICKED_UP']);
    }, () => store.client);
  });

  testWidgets('a new order: name and phone, then the normal check screen as '
      'Order #105 Carry-out; left empty, it is dropped', (tester) async {
    final store = _Store();
    await http.runWithClient(() async {
      await pump(tester, const CarryOutScreen());
      expect(find.text('No open carry-out orders'), findsOneWidget);
      await tester.tap(find.byKey(const Key('carryout-new')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('carryout-name')), 'Lee');
      await tester.enterText(
        find.byKey(const Key('carryout-phone')),
        '919-555-0100',
      );
      await tester.tap(find.byKey(const Key('carryout-start')));
      for (var i = 0; i < 8; i++) {
        await tester.pump(const Duration(milliseconds: 150));
      }
      expect(store.created, [
        {'customerName': 'Lee', 'customerPhone': '919-555-0100'},
      ]);
      final screen = tester.widget<CheckScreen>(find.byType(CheckScreen));
      expect(screen.carryOut, isTrue);
      expect(screen.tableLabel, 'Order #105 · Carry-out');
      // the pay screen says "Order #105", not "Bill #5"
      expect(screen.orderNumber, 105);
      expect(find.text('Order #105 · Carry-out'), findsOneWidget);
      expect(find.textContaining('Lee'), findsOneWidget);
      expect(tester.takeException(), isNull);

      // back with nothing rung: the empty order is dropped
      await tester.tap(find.byTooltip('Back'));
      for (var i = 0; i < 8; i++) {
        await tester.pump(const Duration(milliseconds: 150));
      }
      expect(store.discarded, [5]);
    }, () => store.client);
  });

  testWidgets('the list has its title, white on the navy bar, in all five '
      'languages', (tester) async {
    const titles = {
      'en': 'Carry-out',
      'fr': 'À emporter',
      'es': 'Para llevar',
      'de': 'Zum Mitnehmen',
      'af': 'Wegneem',
    };
    final store = _Store();
    for (final MapEntry(key: lang, value: title) in titles.entries) {
      Prefs.instance.lang = lang;
      await http.runWithClient(() async {
        await pump(tester, const CarryOutScreen());
        final t = find.byKey(const Key('carryout-title'));
        expect(tester.widget<Text>(t).data, title);
        final p = tester.renderObject<RenderParagraph>(
          find.descendant(of: t, matching: find.byType(RichText)),
        );
        expect(p.text.style?.color, T.onPrimary, reason: lang);
        expect(tester.takeException(), isNull);
      }, () => store.client);
      await tester.pumpWidget(const SizedBox());
    }
  });
}
