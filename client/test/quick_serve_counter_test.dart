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
import 'package:pos_client/screens/check_screen.dart';

/// The quick-serve counter's order screen is built for speed: every category
/// on the rail at once, a dense grid, one tap = one more on the order, and
/// the next order starts on its own once this one is paid.
final _fixtures = '${Directory.current.path}/test/screenshots/fixtures';
dynamic _fx(String name) =>
    jsonDecode(File('$_fixtures/$name.json').readAsStringSync());

http.Response _json(Object b, [int status = 200]) => http.Response.bytes(
  utf8.encode(jsonEncode(b)),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

/// A fake store: the pub menu plus two Express categories, checks that grow
/// as lines are added, numbered counter orders.
class _Store {
  final lines = <int, List<Map<String, dynamic>>>{};
  final created = <String>[];
  final placed = <int>[];
  final added = <Map<String, dynamic>>[];
  int _nextOrder = 101;

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

  Map<String, dynamic> check(int id, {String status = 'OPEN'}) {
    final ls = lines[id] ?? [];
    final total = ls.fold<int>(0, (n, l) => n + (l['lineTotalCents'] as int));
    final open = status == 'OPEN';
    return {
      ...(_fx('check') as Map<String, dynamic>),
      'id': id,
      'status': status,
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

  Map<String, dynamic> order(int checkId, int number, String mode) => {
    'checkId': checkId,
    'orderNumber': number,
    'serviceMode': mode,
    'source': 'POS',
    'status': 'NEW',
    'checkStatus': 'OPEN',
  };

  late final client = MockClient((req) async {
    final p = req.url.path;
    final body = req.body.isEmpty ? null : jsonDecode(req.body);
    if (p == '/items') return _json(items);
    if (p == '/categories') return _json(categories);
    if (p == '/counter/orders' && req.method == 'GET') return _json([]);
    if (p == '/counter/orders' && req.method == 'POST') {
      final mode = body['serviceMode'] as String;
      created.add(mode);
      final n = _nextOrder++;
      return _json(order(n - 100, n, mode));
    }
    final place = RegExp(r'^/counter/orders/(\d+)/place$').firstMatch(p);
    if (place != null) {
      final id = int.parse(place[1]!);
      placed.add(id);
      return _json({...order(id, 100 + id, 'TAKE_OUT'), 'status': 'PREPARING'});
    }
    final m = RegExp(r'^/checks/(\d+)(/.*)?$').firstMatch(p);
    if (m != null) {
      final id = int.parse(m[1]!);
      switch (m[2]) {
        case null:
          return _json(check(id));
        case '/lines':
          added.add(body as Map<String, dynamic>);
          final item = items.firstWhere((i) => i['id'] == body['itemId']);
          final v = (item['variants'] as List).firstWhere(
            (v) => v['id'] == body['variantId'],
          );
          final ls = lines.putIfAbsent(id, () => []);
          final qty = body['qty'] as int;
          final existing = ls
              .where((l) => l['variantId'] == v['id'])
              .firstOrNull;
          if (existing != null) {
            existing['qty'] += qty;
            existing['lineTotalCents'] =
                existing['qty'] * (v['priceCents'] as int);
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
          return _json(check(id, status: 'CLOSED'));
        case '/receipt':
          return _json({'checkId': id, 'text': 'RECEIPT'});
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

  const order = CheckScreen(
    checkId: 1,
    tableLabel: '#101 · Take out',
    counterOrder: true,
  );

  // the Express tablet (1920x1200) and a Surface (2736x1824, at 150% and 200%)
  for (final (size, dpr, lang) in [
    (const Size(1920, 1200), 1.5, 'en'),
    (const Size(1920, 1200), 1.5, 'fr'),
    (const Size(2736, 1824), 2.0, 'fr'),
    (const Size(2736, 1824), 1.5, 'en'),
  ]) {
    testWidgets('rail: every category visible at once, tap switches '
        '(${size.width.toInt()}x${size.height.toInt()} @$dpr, $lang)', (
      tester,
    ) async {
      Prefs.instance.lang = lang;
      final store = _Store();
      await http.runWithClient(() async {
        await pumpApp(tester, order, size, dpr);
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
        for (final c in ['*', ...store.categories.map((c) => c['id'])]) {
          final f = find.byKey(Key('cat-$c'));
          expect(f, findsOneWidget, reason: c);
          final r = tester.getRect(f);
          expect(
            screen.contains(r.topLeft) && screen.contains(r.bottomRight),
            isTrue,
            reason: '$c on screen, no scrolling',
          );
          expect(r.height, greaterThanOrEqualTo(48), reason: 'a big button');
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
        await tester.tap(find.byKey(const Key('cat-sides')));
        await settle(tester);
        expect(find.byKey(const Key('tile-fries')), findsOneWidget);
        expect(find.byKey(const Key('tile-brownie')), findsNothing);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
      }, () => store.client);
    });
  }

  testWidgets('one tap adds one right away; sizes and long-press open the '
      'chooser', (tester) async {
    final store = _Store();
    await http.runWithClient(() async {
      await pumpApp(tester, order, const Size(1920, 1200), 1.5);
      await tester.tap(find.byKey(const Key('cat-desserts')));
      await settle(tester);

      await tester.tap(find.byKey(const Key('tile-brownie')));
      await settle(tester);
      expect(find.byType(BottomSheet), findsNothing, reason: 'no chooser');
      expect(store.added.single, containsPair('qty', 1));
      expect(
        find.descendant(
          of: find.byKey(const Key('badge-brownie')),
          matching: find.text('1'),
        ),
        findsOneWidget,
      );
      await tester.tap(find.byKey(const Key('tile-brownie')));
      await settle(tester);
      expect(store.added, hasLength(2));
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
      expect(store.added, hasLength(2), reason: 'nothing added yet');
      await tester.pumpWidget(const SizedBox());
    }, () => store.client);
  });

  testWidgets('paid → the next order starts on its own, same mode; '
      '"New order" does the same', (tester) async {
    final store = _Store();
    await http.runWithClient(() async {
      await pumpApp(tester, const CounterScreen(), const Size(1920, 1200), 1.5);
      await tester.tap(find.byKey(const Key('new-take-out')));
      await settle(tester);
      expect(find.text('#101 · Take out'), findsOneWidget);
      // an empty order is already the new one
      final newOrder = find.byKey(const Key('new-order'));
      expect(newOrder, findsOneWidget);
      expect(tester.widget<ButtonStyleButton>(newOrder).onPressed, isNull);

      await tester.tap(find.byKey(const Key('cat-desserts')));
      await settle(tester);
      await tester.tap(find.byKey(const Key('tile-brownie')));
      await settle(tester);

      // pay cash: the exact (rounded) amount, OK, Done on the receipt
      await tester.tap(find.text('Pay'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(OutlinedButton, r'$8.01'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'OK'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Done'));
      await settle(tester);

      expect(store.placed, [1], reason: 'paid order went to the kitchen');
      expect(store.created, ['TAKE_OUT', 'TAKE_OUT']);
      expect(find.text('#102 · Take out'), findsOneWidget);

      // the always-there "New order" button goes on to #103
      await tester.tap(find.byKey(const Key('cat-desserts')));
      await settle(tester);
      await tester.tap(find.byKey(const Key('tile-cheesecake')));
      await settle(tester);
      await tester.tap(find.byKey(const Key('new-order')));
      await settle(tester);
      expect(store.placed, [1, 2]);
      expect(store.created, ['TAKE_OUT', 'TAKE_OUT', 'TAKE_OUT']);
      expect(find.text('#103 · Take out'), findsOneWidget);

      // back arrow: to the order board, no new order
      await tester.tap(find.byTooltip('Back'));
      await settle(tester);
      expect(store.created, hasLength(3));
      expect(find.byKey(const Key('new-take-out')), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    }, () => store.client);
  });
}
