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
import 'package:pos_client/quickserve/counter_screen.dart';

/// The quick-serve counter, one flow for every order: it opens on a new
/// order (never stored until its first item), dine in / take out is a toggle,
/// Pay, and the next order opens by itself. Kiosk orders wait in a strip
/// until they are paid. Built for speed: every category on the rail at once,
/// a dense grid, one tap = one more on the order.
final _fixtures = '${Directory.current.path}/test/screenshots/fixtures';
dynamic _fx(String name) =>
    jsonDecode(File('$_fixtures/$name.json').readAsStringSync());

http.Response _json(Object b, [int status = 200]) => http.Response.bytes(
  utf8.encode(jsonEncode(b)),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

/// A fake store: the pub menu plus two Express categories, checks that grow
/// as lines are added, counter orders numbered when they are paid, and kiosk
/// orders waiting to pay.
class _Store {
  final lines = <int, List<Map<String, dynamic>>>{};

  /// POST /counter/orders: the mode and the first item of each new order.
  final created = <Map<String, dynamic>>[];

  /// POST /checks/{id}/lines: every item after an order's first.
  final added = <Map<String, dynamic>>[];
  final modes = <int, String>{};
  final sources = <int, String>{};
  final numbers = <int, int>{};
  final statuses = <int, String>{};
  final modeChanges = <String>[];
  final discarded = <int>[];
  final statusCalls = <String>[];
  final closed = <int>{};
  String defaultMode = 'TAKE_OUT';
  int _nextCheck = 1;
  int _nextNumber = 101;

  /// A kiosk order waiting to pay: K12 with a brownie and a cheesecake.
  void kioskOrder({int kiosk = 12}) {
    final id = _nextCheck++;
    modes[id] = 'DINE_IN';
    sources[id] = 'KIOSK';
    statuses[id] = 'WAITING';
    kioskNumbers[id] = kiosk;
    _addLine(id, 'brownie', 'brownie:regular', 1);
    _addLine(id, 'cheesecake', 'cheesecake:regular', 1);
  }

  final kioskNumbers = <int, int>{};

  List<dynamic> get items => [
    ...(_fx('items') as List),
    for (final (id, cat, en, fr) in [
      ('fries', 'sides', 'Fries', 'Frites'),
      ('onion-rings', 'sides', 'Onion rings', 'Rondelles d’oignon'),
      ('caesar', 'salads', 'Caesar salad', 'Salade César'),
    ])
      {
        'id': id,
        'nameFr': fr,
        'nameEn': en,
        'descriptionFr': '',
        'descriptionEn': '',
        'category': cat,
        'abbrev': 'XX',
        'isAlcohol': false,
        'active': true,
        'variants': [
          {'id': '$id:1', 'labelFr': '', 'labelEn': '', 'priceCents': 499},
        ],
      },
  ];

  List<dynamic> get categories => [
    ...(_fx('categories') as List),
    {
      'id': 'sides',
      'nameFr': 'Frites et accompagnements',
      'nameEn': 'Fries and sides',
      'sortOrder': 20,
    },
    {'id': 'salads', 'nameFr': 'Salades', 'nameEn': 'Salads', 'sortOrder': 21},
  ];

  int _total(int id) => (lines[id] ?? []).fold<int>(
    0,
    (n, l) => n + (l['lineTotalCents'] as int),
  );

  Map<String, dynamic> check(int id, {String? status}) {
    final ls = lines[id] ?? [];
    final total = _total(id);
    final st = status ?? (closed.contains(id) ? 'CLOSED' : 'OPEN');
    final open = st == 'OPEN';
    return {
      ...(_fx('check') as Map<String, dynamic>),
      'id': id,
      'status': st,
      'lines': ls,
      'pendingLines': [],
      'fees': [],
      'taxes': [],
      'itemsSubtotalCents': total,
      'subtotalCents': total,
      'grandTotalCents': total,
      'paidCents': open ? 0 : total,
      'outstandingCents': open ? total : 0,
      // the store rounds cash to the nickel: one cent up here
      'cashDueCents': open && total > 0 ? total + 1 : 0,
      'cashRoundingCents': open && total > 0 ? 1 : 0,
    };
  }

  Map<String, dynamic> order(int id) => {
    'checkId': id,
    'orderNumber': numbers[id],
    'kioskNumber': kioskNumbers[id],
    'serviceMode': modes[id],
    'source': sources[id] ?? 'POS',
    'status': statuses[id],
    'checkStatus': closed.contains(id) ? 'CLOSED' : 'OPEN',
    'totalCents': _total(id),
    'outstandingCents': closed.contains(id) ? 0 : _total(id),
    'itemCount': (lines[id] ?? []).fold<int>(
      0,
      (n, l) => n + (l['qty'] as int),
    ),
  };

  void _addLine(int id, String itemId, String variantId, int qty) {
    final item = items.firstWhere((i) => i['id'] == itemId);
    final v = (item['variants'] as List).firstWhere(
      (v) => v['id'] == variantId,
    );
    final ls = lines.putIfAbsent(id, () => []);
    final existing = ls.where((l) => l['variantId'] == v['id']).firstOrNull;
    if (existing != null) {
      existing['qty'] += qty;
      existing['lineTotalCents'] = existing['qty'] * (v['priceCents'] as int);
    } else {
      ls.add({
        'id': ls.length + 1,
        'itemId': item['id'],
        'variantId': v['id'],
        'nameFr': item['nameFr'],
        'nameEn': item['nameEn'],
        'variantLabelFr': null,
        'variantLabelEn': null,
        'qty': qty,
        'unitPriceCents': v['priceCents'],
        'lineTotalCents': qty * (v['priceCents'] as int),
        'note': null,
      });
    }
  }

  late final client = MockClient((req) async {
    final p = req.url.path;
    final body = req.body.isEmpty ? null : jsonDecode(req.body);
    if (p == '/items') return _json(items);
    if (p == '/categories') return _json(categories);
    if (p == '/counter/settings') {
      return _json({'defaultServiceMode': defaultMode});
    }
    if (p == '/counter/waiting') {
      return _json([
        for (final id in statuses.keys)
          if (statuses[id] == 'WAITING') order(id),
      ]);
    }
    if (p == '/counter/orders' && req.method == 'GET') {
      return _json([
        for (final id in numbers.keys.toList().reversed) order(id),
      ]);
    }
    if (p == '/counter/orders' && req.method == 'POST') {
      created.add(body as Map<String, dynamic>);
      final id = _nextCheck++;
      modes[id] = body['serviceMode'] as String;
      statuses[id] = 'DRAFT';
      _addLine(id, body['itemId'], body['variantId'], body['qty'] as int);
      return _json(order(id));
    }
    final o = RegExp(r'^/counter/orders/(\d+)(/\w+)?$').firstMatch(p);
    if (o != null) {
      final id = int.parse(o[1]!);
      switch (o[2]) {
        case null:
          return _json(order(id));
        case '/mode':
          modeChanges.add(body['serviceMode'] as String);
          modes[id] = body['serviceMode'] as String;
          return _json(order(id));
        case '/discard':
          discarded.add(id);
          statuses.remove(id);
          return _json({'discarded': true});
        case '/status':
          statusCalls.add('${numbers[id]}:${body['status']}');
          statuses[id] = body['status'] as String;
          return _json(order(id));
      }
    }
    final m = RegExp(r'^/checks/(\d+)(/.*)?$').firstMatch(p);
    if (m != null) {
      final id = int.parse(m[1]!);
      switch (m[2]) {
        case null:
          return _json(check(id));
        case '/lines':
          added.add(body as Map<String, dynamic>);
          _addLine(id, body['itemId'], body['variantId'], body['qty'] as int);
          return _json(check(id));
        case '/tenders':
          final c = check(id);
          return _json({
            'tender': {
              'id': 1,
              'type': 'CASH',
              'amountTenderedCents': body['amountTenderedCents'],
              'amountAppliedCents': c['outstandingCents'],
              'roundingAdjustmentCents': 1,
              'changeCents': 0,
            },
            'check': check(id, status: 'TOTAL_LOCKED'),
          }, 201);
        case '/finalize':
          // the store commits the order as it closes: its number, preparing
          closed.add(id);
          numbers[id] = _nextNumber++;
          statuses[id] = 'PREPARING';
          return _json(check(id));
        case '/receipt':
        case '/receipt/print':
          return _json({'checkId': id, 'text': 'RECEIPT #${numbers[id]}'});
      }
    }
    if (p == '/printer/status') return _json({'configured': false});
    return http.Response('{"error":"not found"}', 404);
  });
}

/// The real fonts, so text measures as on the tablet (the test binding
/// otherwise draws every glyph as a wide box).
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

/// `QS_SHOTS=dir`: also save each layout as a PNG, to look at.
const _shotsDir = String.fromEnvironment('QS_SHOTS');
final _shotKey = GlobalKey();

void main() {
  setUpAll(() async {
    await _loadFonts();
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

  setUp(() {
    Prefs.instance.lang = 'en';
    StoreProfile.current = const StoreProfile(
      kind: 'quick-serve',
      locales: ['en', 'fr', 'es', 'de'],
    );
  });
  tearDown(() => StoreProfile.current = StoreProfile.pub);

  Future<void> settle(WidgetTester tester) async {
    for (var i = 0; i < 8; i++) {
      await tester.pump(const Duration(milliseconds: 150));
    }
  }

  Future<void> pumpApp(
    WidgetTester tester,
    Widget home,
    Size size,
    double dpr,
  ) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = dpr;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      prefsScope(
        child: RepaintBoundary(
          key: _shotKey,
          child: MaterialApp(theme: buildPosTheme(), home: home),
        ),
      ),
    );
    await settle(tester);
  }

  const counter = CounterScreen();

  Future<void> tapTile(WidgetTester tester, String cat, String item) async {
    await tester.tap(find.byKey(Key('cat-$cat')));
    await settle(tester);
    await tester.tap(find.byKey(Key('tile-$item')));
    await settle(tester);
  }

  /// Pay cash: the exact (rounded) amount, OK on the change.
  Future<void> payCash(WidgetTester tester, String amount) async {
    await tester.tap(find.text('Pay'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(OutlinedButton, amount));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'OK'));
    await tester.pumpAndSettle();
  }

  bool modeOn(WidgetTester tester, String mode) =>
      tester
          .widget<OutlinedButton>(find.byKey(Key('mode-$mode')))
          .style!
          .backgroundColor!
          .resolve({}) ==
      T.navy;

  // the Express tablet (1920x1200) and a Surface (2736x1824, at 150% and 200%)
  for (final (size, dpr, lang) in [
    (const Size(1920, 1200), 1.5, 'en'),
    (const Size(1920, 1200), 1.5, 'fr'),
    (const Size(1920, 1200), 1.5, 'es'),
    (const Size(2736, 1824), 2.0, 'fr'),
    (const Size(2736, 1824), 1.5, 'en'),
    (const Size(2736, 1824), 2.0, 'de'),
  ]) {
    testWidgets('the counter opens on a new order: rail, grid, toggle and '
        'kiosk strip fit (${size.width.toInt()}x${size.height.toInt()} '
        '@$dpr, $lang)', (tester) async {
      Prefs.instance.lang = lang;
      final store = _Store()
        ..kioskOrder(kiosk: 12)
        ..kioskOrder(kiosk: 13);
      await http.runWithClient(() async {
        await pumpApp(tester, counter, size, dpr);
        expect(tester.takeException(), isNull, reason: 'no overflow');
        if (_shotsDir.isNotEmpty) {
          await tester.runAsync(() async {
            final b =
                _shotKey.currentContext!.findRenderObject()!
                    as RenderRepaintBoundary;
            final image = await b.toImage(pixelRatio: dpr);
            final png = await image.toByteData(format: ui.ImageByteFormat.png);
            File(
              '$_shotsDir/counter-${size.width.toInt()}-$dpr-$lang.png',
            ).writeAsBytesSync(png!.buffer.asUint8List());
          });
        }
        final screen = Offset.zero & (size / dpr);
        bool onScreen(Finder f) {
          final r = tester.getRect(f);
          return screen.contains(r.topLeft) && screen.contains(r.bottomRight);
        }

        // no order list and no "new dine in / take out" buttons: an order
        for (final k in [
          'mode-TAKE_OUT',
          'mode-DINE_IN',
          'kiosk-1',
          'kiosk-2',
        ]) {
          expect(find.byKey(Key(k)), findsOneWidget, reason: k);
          expect(onScreen(find.byKey(Key(k))), isTrue, reason: '$k on screen');
        }
        expect(find.byKey(const Key('new-dine-in')), findsNothing);
        expect(store.created, isEmpty, reason: 'an empty order is not stored');
        for (final c in ['*', ...store.categories.map((c) => c['id'])]) {
          final f = find.byKey(Key('cat-$c'));
          expect(f, findsOneWidget, reason: c);
          expect(onScreen(f), isTrue, reason: '$c on screen, no scrolling');
          expect(tester.getRect(f).height, greaterThanOrEqualTo(48));
        }
        // the first category starts selected; a dense grid: 5-6 across
        expect(find.byKey(const Key('tile-lantern-lager')), findsOneWidget);
        await tester.tap(find.byKey(const Key('cat-*')));
        await settle(tester);
        final tops = <double, int>{};
        for (final e in find.byType(Opacity).evaluate()) {
          final w = e.widget as Opacity;
          if (w.child is! Stack) continue;
          final top = tester.getTopLeft(find.byWidget(w)).dy;
          tops[top] = (tops[top] ?? 0) + 1;
        }
        final perRow = tops.values.reduce((a, b) => a > b ? a : b);
        expect(perRow, inInclusiveRange(5, 6));
        // full names: no tile name or category label runs out of lines
        for (final e in find.byType(RichText).evaluate()) {
          final p = e.renderObject! as RenderParagraph;
          expect(p.didExceedMaxLines, isFalse, reason: p.text.toPlainText());
        }
        await tester.tap(find.byKey(const Key('cat-desserts')));
        await settle(tester);
        expect(find.byKey(const Key('tile-brownie')), findsOneWidget);
        expect(find.byKey(const Key('tile-lantern-lager')), findsNothing);
        // a kiosk order on the panel still fits
        await tester.tap(find.byKey(const Key('kiosk-1')));
        await settle(tester);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
      }, () => store.client);
    });
  }

  testWidgets('one tap adds one right away (the first one stores the order); '
      'sizes and long-press open the chooser', (tester) async {
    final store = _Store();
    await http.runWithClient(() async {
      await pumpApp(tester, counter, const Size(1920, 1200), 1.5);
      await tapTile(tester, 'desserts', 'brownie');
      expect(find.byType(BottomSheet), findsNothing, reason: 'no chooser');
      expect(store.created.single, containsPair('itemId', 'brownie'));
      expect(store.created.single, containsPair('qty', 1));
      expect(store.added, isEmpty);
      expect(
        find.descendant(
          of: find.byKey(const Key('badge-brownie')),
          matching: find.text('1'),
        ),
        findsOneWidget,
      );
      await tester.tap(find.byKey(const Key('tile-brownie')));
      await settle(tester);
      expect(store.created, hasLength(1), reason: 'one order');
      expect(store.added, hasLength(1));
      expect(
        find.descendant(
          of: find.byKey(const Key('badge-brownie')),
          matching: find.text('2'),
        ),
        findsOneWidget,
      );
      // Canada has no pennies: the store's rounded cash figure, under the total
      expect(find.text(r'Cash: $16.01'), findsOneWidget);

      // long-press: options for a one-size item too
      await tester.longPress(find.byKey(const Key('tile-cheesecake')));
      await tester.pumpAndSettle();
      expect(find.byType(BottomSheet), findsOneWidget);
      Navigator.of(tester.element(find.byType(BottomSheet))).pop();
      await tester.pumpAndSettle();

      // an item with sizes must be chosen: tap opens the chooser
      await tester.tap(find.byKey(const Key('cat-draft-beer')));
      await settle(tester);
      await tester.tap(find.byKey(const Key('tile-lantern-lager')));
      await tester.pumpAndSettle();
      expect(find.byType(BottomSheet), findsOneWidget);
      expect(store.added, hasLength(1), reason: 'nothing added yet');
      await tester.pumpWidget(const SizedBox());
    }, () => store.client);
  });

  testWidgets('dine in / take out: the store default, a toggle until paid; '
      'paid → numbered, and the next new order opens by itself', (
    tester,
  ) async {
    final store = _Store()..defaultMode = 'DINE_IN';
    await http.runWithClient(() async {
      await pumpApp(tester, counter, const Size(1920, 1200), 1.5);
      expect(find.text('New order'), findsOneWidget);
      expect(modeOn(tester, 'DINE_IN'), isTrue, reason: 'the store default');
      // before the first item: nothing to tell the store yet
      await tester.tap(find.byKey(const Key('mode-TAKE_OUT')));
      await settle(tester);
      expect(modeOn(tester, 'TAKE_OUT'), isTrue);
      expect(store.modeChanges, isEmpty);

      await tapTile(tester, 'desserts', 'brownie');
      expect(store.created.single['serviceMode'], 'TAKE_OUT');
      // after: the order changes on the store
      await tester.tap(find.byKey(const Key('mode-DINE_IN')));
      await settle(tester);
      expect(store.modeChanges, ['DINE_IN']);
      await tester.tap(find.byKey(const Key('mode-TAKE_OUT')));
      await settle(tester);
      expect(store.modeChanges, ['DINE_IN', 'TAKE_OUT']);

      await payCash(tester, r'$8.01');
      // the receipt: the number the customer is called by, given on payment
      expect(find.text('Order #101 · Take out'), findsOneWidget);
      await tester.tap(find.text('Done'));
      await settle(tester);

      // straight on to a new, empty order, back in the default mode
      expect(find.text('New order'), findsOneWidget);
      expect(find.byKey(const Key('badge-brownie')), findsNothing);
      expect(modeOn(tester, 'DINE_IN'), isTrue);
      expect(store.created, hasLength(1), reason: 'the new one is not stored');

      // the next paid order is #102; the receipt closes on its own
      await tapTile(tester, 'desserts', 'cheesecake');
      expect(store.created.last['serviceMode'], 'DINE_IN');
      await payCash(tester, r'$8.51');
      expect(find.text('Order #102 · Dine in'), findsOneWidget);
      await tester.pump(const Duration(seconds: 9));
      await tester.pumpAndSettle();
      expect(find.text('New order'), findsOneWidget);
      expect(find.byKey(const Key('badge-cheesecake')), findsNothing);
      await tester.pumpWidget(const SizedBox());
    }, () => store.client);
  });

  testWidgets('a kiosk order: tap it in the strip, pay, then it is gone and '
      'a new order opens', (tester) async {
    final store = _Store()..kioskOrder(kiosk: 12);
    await http.runWithClient(() async {
      await pumpApp(tester, counter, const Size(1920, 1200), 1.5);
      expect(find.text('K12'), findsOneWidget);
      expect(find.text(r'$16.50'), findsOneWidget);
      await tester.tap(find.byKey(const Key('kiosk-1')));
      await settle(tester);
      expect(find.text('Kiosk K12 · to pay'), findsOneWidget);
      expect(modeOn(tester, 'DINE_IN'), isTrue, reason: 'as the guest chose');
      expect(find.byKey(const Key('badge-brownie')), findsNothing);
      await tester.tap(find.byKey(const Key('cat-desserts')));
      await settle(tester);
      expect(find.byKey(const Key('badge-brownie')), findsOneWidget);

      await payCash(tester, r'$16.51');
      expect(find.text('Order #101 · Dine in'), findsOneWidget);
      await tester.tap(find.text('Done'));
      await settle(tester);
      expect(find.text('New order'), findsOneWidget);
      expect(find.text('K12'), findsNothing, reason: 'paid: off the queue');
      expect(find.text('No kiosk orders waiting'), findsOneWidget);
      expect(store.created, isEmpty);
      await tester.pumpWidget(const SizedBox());
    }, () => store.client);
  });

  testWidgets('taking a kiosk order while ringing one: asked, then cleared', (
    tester,
  ) async {
    final store = _Store()..kioskOrder(kiosk: 12);
    await http.runWithClient(() async {
      await pumpApp(tester, counter, const Size(1920, 1200), 1.5);
      await tapTile(tester, 'desserts', 'brownie');
      final posOrder = 2;
      await tester.tap(find.byKey(const Key('kiosk-1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('switch-confirm')));
      await settle(tester);
      expect(store.discarded, [posOrder]);
      expect(find.text('Kiosk K12 · to pay'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    }, () => store.client);
  });

  testWidgets('clear order: the unpaid order is dropped, a new one opens', (
    tester,
  ) async {
    final store = _Store();
    await http.runWithClient(() async {
      await pumpApp(tester, counter, const Size(1920, 1200), 1.5);
      final bin = find.byKey(const Key('discard-order'));
      expect(
        tester.widget<IconButton>(bin).onPressed,
        isNull,
        reason: 'nothing to clear yet',
      );
      await tapTile(tester, 'desserts', 'brownie');
      await tester.tap(bin);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('discard-confirm')));
      await settle(tester);
      expect(store.discarded, [1]);
      expect(find.byKey(const Key('badge-brownie')), findsNothing);
      expect(find.text('New order'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    }, () => store.client);
  });

  testWidgets('the Orders panel: paid orders only, ready, picked up, recall', (
    tester,
  ) async {
    final store = _Store()..kioskOrder(kiosk: 12);
    await http.runWithClient(() async {
      await pumpApp(tester, counter, const Size(1920, 1200), 1.5);
      await tapTile(tester, 'desserts', 'brownie');
      await payCash(tester, r'$8.01');
      await tester.tap(find.text('Done'));
      await settle(tester);

      await tester.tap(find.byKey(const Key('orders-button')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('order-101')), findsOneWidget);
      expect(find.text('K12'), findsOneWidget, reason: 'only in the strip');
      await tester.tap(find.byKey(const Key('ready-101')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('pickedup-101')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('recall-101')));
      await tester.pumpAndSettle();
      expect(store.statusCalls, ['101:READY', '101:PICKED_UP', '101:READY']);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    }, () => store.client);
  });
}
