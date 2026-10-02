// Menu specials on the client: a day price ("Happy hour", "Tuesday special")
// and day availability ("Fri & Sat only"). The store decides them (GET /items
// gives each size its price now and, while a special is on, the menu price
// it replaces); the screens show them, and the menu editor edits the rules —
// always against the MENU price, never a special's.
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/kiosk/kiosk_api.dart';
import 'package:pos_client/kiosk/kiosk_app.dart';
import 'package:pos_client/kiosk/kiosk_controller.dart';
import 'package:pos_client/screens/check_screen.dart';
import 'package:pos_client/screens/menu_management_screen.dart';
import 'package:pos_client/specials_i18n.dart';
import 'package:pos_client/widgets/specials_widgets.dart';
import 'package:shared_preferences/shared_preferences.dart';

final _fixtures = '${Directory.current.path}/test/screenshots/fixtures';
dynamic _fx(String name) =>
    jsonDecode(File('$_fixtures/$name.json').readAsStringSync());

const _happyHour = {
  'days': ['mon', 'tue', 'wed', 'thu', 'fri'],
  'from': '16:00',
  'to': '18:00',
};

/// The pub menu with: the lager's pint on happy hour now ($5.00, menu
/// $7.50), the amber ale sold only on Fridays and Saturdays (not today).
List<dynamic> _specialItems() {
  final items = _fx('items') as List;
  for (final i in items) {
    if (i['id'] == 'lantern-lager') {
      final pint = (i['variants'] as List).first as Map<String, dynamic>;
      i['specials'] = [
        {
          ..._happyHour,
          'prices': {pint['id']: 500},
        },
      ];
      pint['regularPriceCents'] = pint['priceCents'];
      pint['priceCents'] = 500;
      pint['special'] = _happyHour;
      // one size only, so the tile shows the struck-through menu price
      i['variants'] = [pint];
    }
    if (i['id'] == 'amber-ale') {
      i['availableDays'] = ['fri', 'sat'];
      i['availableNow'] = false;
    }
  }
  return items;
}

http.Response _json(Object b, [int status = 200]) => http.Response.bytes(
  utf8.encode(jsonEncode(b)),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

/// [_specialItems], plus the IPA's pint on happy hour ($6.00, menu $8.25)
/// with both its sizes kept: a sized item on special.
List<dynamic> _sizedSpecialItems() {
  final items = _specialItems();
  for (final i in items) {
    if (i['id'] == 'north-ipa') {
      final pint = (i['variants'] as List).first as Map<String, dynamic>;
      i['specials'] = [
        {
          ..._happyHour,
          'prices': {pint['id']: 600},
        },
      ];
      pint['regularPriceCents'] = pint['priceCents'];
      pint['priceCents'] = 600;
      pint['special'] = _happyHour;
    }
  }
  return items;
}

/// A fake store; [requests] records every call that changes something.
MockClient _store(
  List<(String, String, Object?)> requests, {
  Object? check,
  List<dynamic> Function() items = _specialItems,
}) => MockClient((req) async {
  if (req.method != 'GET') {
    requests.add((
      req.method,
      req.url.path,
      req.body.isEmpty ? null : jsonDecode(req.body),
    ));
  }
  final body = switch (req.url.path) {
    '/health' => _fx('health'),
    '/staff' => _fx('staff'),
    '/zones' => _fx('zones'),
    '/alert-config' => {
      'pendingAlertsEnabled': false,
      'pendingAlertEscalateSeconds': 90,
      'pendingAlertVolume': 0,
    },
    '/items' => items(),
    '/categories' => _fx('categories'),
    '/checks/1' => check ?? _fx('check'),
    _ when req.method == 'PATCH' => _specialItems().first,
    _ => null,
  };
  if (body == null) return http.Response('{"error":"not found"}', 404);
  return _json(body);
});

Future<void> _pumpPos(
  WidgetTester tester,
  Widget screen,
  MockClient store,
  Future<void> Function() body,
) async {
  tester.view.physicalSize = const Size(1920, 1200);
  tester.view.devicePixelRatio = 1.5;
  addTearDown(tester.view.reset);
  await http.runWithClient(() async {
    await tester.pumpWidget(
      prefsScope(
        child: MaterialApp(theme: buildPosTheme(), home: screen),
      ),
    );
    for (var i = 0; i < 6; i++) {
      await tester.pump(const Duration(milliseconds: 150));
    }
    await body();
    await tester.pumpWidget(const SizedBox());
  }, () => store);
}

class _Kiosk extends KioskApi {
  _Kiosk(super.baseUrl, {super.token});

  @override
  Future<KioskConfig> config() async =>
      const KioskConfig('Copper Lantern — Express', 'USD', ['en', 'fr']);

  @override
  Future<List<Item>> items() async => [
    for (final j in _specialItems()) Item.fromJson(j as Map<String, dynamic>),
  ];

  @override
  Future<List<Category>> categories() async => [
    for (final j in _fx('categories') as List)
      Category.fromJson(j as Map<String, dynamic>),
  ];

  @override
  Future<List<KioskUpsellRow>> upsell(List<Map<String, dynamic>> lines) async =>
      const [];
}

/// The span in [root] that reads exactly [text].
TextSpan? _spanOf(InlineSpan root, String text) {
  TextSpan? hit;
  root.visitChildren((s) {
    if (s is TextSpan && s.text == text) {
      hit = s;
      return false;
    }
    return true;
  });
  return hit;
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

  setUp(() {
    Prefs.instance.lang = 'en';
    Api.currentUser = AuthUser.fromJson({
      'userId': 'manager',
      'name': 'Demo Manager',
      'role': 'MANAGER',
      'languageCode': 'en',
      'grants': ['void', 'refund', 'edit_menu', 'manage_staff'],
    }, 'test-token');
  });

  group('the model', () {
    test('a size on special: price now, menu price, its special', () {
      final lager = Item.fromJson(_specialItems().first);
      final pint = lager.variants.first;
      expect(pint.priceCents, 500);
      expect(pint.regularPriceCents, 750);
      expect(pint.menuPriceCents, 750);
      expect(pint.onSpecial, isTrue);
      expect(lager.specialNow?.name('en'), 'Happy hour');
      expect(lager.specials.single.prices, {pint.id: 500});
      final ale = Item.fromJson(_specialItems()[1]);
      expect(ale.availableNow, isFalse);
      expect(ale.availableDays, ['fri', 'sat']);
      // an item without any keeps the defaults
      final plain = Item.fromJson(_specialItems()[2]);
      expect(plain.availableNow, isTrue);
      expect(plain.dayOnly, isFalse);
      expect(plain.variants.first.menuPriceCents, plain.priceCents);
    });

    test('a special\'s name, the store\'s rule, in 5 languages', () {
      const tue = SpecialTag(['tue']);
      expect(tue.name('en'), 'Tuesday special');
      expect(tue.name('fr'), 'Spécial du mardi');
      expect(tue.name('es'), 'Especial del martes');
      expect(tue.name('de'), 'Dienstagsangebot');
      expect(tue.name('af'), 'Dinsdag-spesiaal');
      const hh = SpecialTag(['mon', 'fri'], from: '16:00', to: '18:00');
      expect(hh.name('es'), 'Hora feliz');
      expect(const SpecialTag(['mon', 'fri']).name('en'), 'Special');
      expect(
        const SpecialTag(['sun'], label: 'Taco Tuesday').name('fr'),
        'Taco Tuesday',
      );
      expect(SpecialsText('en').onlyOn(['fri', 'sat']), 'Fri & Sat only');
      expect(SpecialsText('fr').onlyOn(['fri', 'sat']), 'ven et sam seulement');
      expect(SpecialsText('de').onlyOn(['sun']), 'nur So');
      expect(normalizeHhmm('9:05'), '09:05');
      expect(normalizeHhmm('25:00'), isNull);
    });
  });

  testWidgets('editor: day chips and a special price → the PATCH body', (
    tester,
  ) async {
    final item = Item.fromJson(_specialItems()[2]); // a plain one
    final c = SpecialsEditController.of(item);
    final vid = item.variants.first.id;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: StatefulBuilder(
              builder: (context, setState) => SpecialsEditor(
                c: c,
                lang: 'en',
                sizeLabels: {vid: 'Pint'},
                onChanged: () => setState(() {}),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.byKey(const ValueKey('available-fri')));
    await tester.tap(find.byKey(const ValueKey('available-sat')));
    await tester.tap(find.byKey(const ValueKey('special-add')));
    await tester.pump();
    // incomplete: no day, no price
    expect(c.validate('en'), SpecialsText('en').pickADay);
    await tester.tap(find.byKey(const ValueKey('special-0-day-tue')));
    await tester.pump();
    expect(c.validate('en'), SpecialsText('en').setAPrice);
    await tester.enterText(
      find.byKey(ValueKey('special-0-price-$vid')),
      '9.95',
    );
    await tester.enterText(
      find.byKey(const ValueKey('special-0-from')),
      '15:00',
    );
    await tester.pump();
    expect(c.validate('en'), SpecialsText('en').bothTimes);
    await tester.enterText(find.byKey(const ValueKey('special-0-to')), '18:00');
    await tester.pump();
    expect(c.validate('en'), isNull);
    expect(c.toPatch(), {
      'availableDays': ['fri', 'sat'],
      'specials': [
        {
          'days': ['tue'],
          'from': '15:00',
          'to': '18:00',
          'prices': {vid: 995},
        },
      ],
    });
    // the day chips toggle off again; the special can go
    await tester.tap(find.byKey(const ValueKey('available-fri')));
    await tester.tap(find.byKey(const ValueKey('special-0-remove')));
    await tester.pump();
    expect(c.toPatch(), {
      'availableDays': ['sat'],
      'specials': <Object>[],
    });
  });

  testWidgets('menu editor shows the MENU price while a special is on, '
      'and sends specials only when changed', (tester) async {
    final requests = <(String, String, Object?)>[];
    await _pumpPos(
      tester,
      const MenuManagementScreen(),
      _store(requests),
      () async {
        await tester.tap(find.text('Lantern House Lager').first);
        await tester.pumpAndSettle();
        // the pint box holds $7.50 (the menu price), never the $5.00 special
        expect(
          find.byWidgetPredicate(
            (w) => w is TextField && w.controller?.text == '7.50',
          ),
          findsOneWidget,
        );
        expect(
          find.byWidgetPredicate(
            (w) => w is TextField && w.controller?.text == '5.00',
          ),
          findsOneWidget, // the special's own price box
        );
        await tester.ensureVisible(find.text('Save'));
        await tester.tap(find.text('Save'));
        await tester.pumpAndSettle();
        final patches = requests.where((r) => r.$1 == 'PATCH').toList();
        // the item only (no size repriced), and no specials (unchanged)
        expect(patches.map((r) => r.$2), ['/items/lantern-lager']);
        expect((patches.single.$3 as Map).containsKey('specials'), isFalse);
      },
    );
  });

  testWidgets('POS: a day-only item is greyed with its days and can\'t be '
      'rung; a special shows its name and price; today\'s specials row', (
    tester,
  ) async {
    final requests = <(String, String, Object?)>[];
    final check = _fx('check') as Map<String, dynamic>;
    check['pendingLines'] = [];
    final line = (check['lines'] as List).first as Map<String, dynamic>;
    line['regularUnitPriceCents'] = 750;
    line['unitPriceCents'] = 500;
    line['special'] = _happyHour;
    await _pumpPos(
      tester,
      const CheckScreen(checkId: 1, tableLabel: 'U-1'),
      _store(requests, check: check),
      () async {
        expect(find.byKey(const Key('special-amber-ale')), findsOneWidget);
        expect(find.text('Fri & Sat only'), findsOneWidget);
        expect(find.byKey(const Key('special-lantern-lager')), findsOneWidget);
        // tile + the bill line both name it
        expect(find.text('Happy hour'), findsNWidgets(2));
        expect(find.byKey(const Key('line-special-1')), findsOneWidget);
        expect(find.byKey(const Key('todays-specials')), findsOneWidget);
        expect(find.byKey(const Key('today-lantern-lager')), findsOneWidget);
        expect(find.byKey(const Key('today-amber-ale')), findsNothing);

        await tester.tap(find.byKey(const Key('menu-tile-amber-ale')));
        await tester.pump(const Duration(milliseconds: 300));
        expect(find.text('Not sold today — Fri & Sat only'), findsOneWidget);
        expect(requests.where((r) => r.$2.contains('/lines')), isEmpty);
      },
    );
  });

  testWidgets('kiosk: an item not sold today isn\'t there; a special shows '
      'its price and name', (tester) async {
    SharedPreferences.setMockInitialValues({
      'kiosk.storeUrl': 'http://10.0.0.5:8080',
      'kiosk.token': 'kiosk-token',
    });
    final c = KioskController(
      discover: () async => 'http://10.0.0.5:8080',
      apiFactory: (u, t) => _Kiosk(u, token: t),
    );
    tester.view.physicalSize = const Size(1080, 1920);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(KioskApp(controller: c));
    await tester.pumpAndSettle();
    c.setLang('en');
    await tester.tap(find.byKey(const Key('kiosk-welcome')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('kiosk-take-out')));
    await tester.pumpAndSettle();
    expect(c.category, 'draft-beer');
    expect(c.shownItems.map((i) => i.id), isNot(contains('amber-ale')));
    expect(find.byKey(const Key('kiosk-item-amber-ale')), findsNothing);
    expect(
      find.byKey(const Key('kiosk-special-lantern-lager')),
      findsOneWidget,
    );
    expect(find.text('Happy hour'), findsOneWidget);
    expect(find.text(r'$5.00'), findsOneWidget);
    expect(find.text(r'$7.50'), findsOneWidget); // struck through
    c.dispose();
  });
  testWidgets('editor: a selected day chip is white on navy, readable', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: buildPosTheme(),
        home: Scaffold(
          body: DayChips(selected: const {'mon'}, lang: 'en', onToggle: (_) {}),
        ),
      ),
    );
    Color? colorOf(String text) =>
        tester.renderObject<RenderParagraph>(find.text(text)).text.style?.color;
    final mon = tester.widget<FilterChip>(
      find.byKey(const ValueKey('day-mon')),
    );
    expect(mon.selected, isTrue);
    expect(colorOf(SpecialsText('en').dayShort('mon')), T.onPrimary);
    expect(mon.checkmarkColor, T.onPrimary);
    // not selected: dark text on the white chip
    expect(colorOf(SpecialsText('en').dayShort('tue')), T.textPrimary);
  });

  testWidgets('a "from" price: the menu price struck through after it too', (
    tester,
  ) async {
    final item = Item.fromJson(
      _sizedSpecialItems().firstWhere((i) => i['id'] == 'north-ipa'),
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SpecialPriceText(
            item.variants.first,
            money: (c) => '\$${(c / 100).toStringAsFixed(2)}',
            style: const TextStyle(fontSize: 18),
            suffix: '+',
          ),
        ),
      ),
    );
    final span =
        tester.renderObject<RenderParagraph>(find.byType(RichText)).text
            as TextSpan;
    expect(span.toPlainText(), r'$6.00+ $8.25+');
    expect(
      _spanOf(span, r'$8.25+')?.style?.decoration,
      TextDecoration.lineThrough,
    );
    expect(_spanOf(span, r'$6.00+')?.style?.decoration, isNull);
  });

  for (final scale in [1.0, 1.15]) {
    testWidgets('POS: a sized item on special shows its regular "from" price '
        'struck through, no overflow at ${scale}x text', (tester) async {
      tester.platformDispatcher.textScaleFactorTestValue = scale;
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      final requests = <(String, String, Object?)>[];
      await _pumpPos(
        tester,
        const CheckScreen(checkId: 1, tableLabel: 'U-1'),
        _store(requests, items: _sizedSpecialItems),
        () async {
          final tile = find.byKey(const Key('menu-tile-north-ipa'));
          expect(tile, findsOneWidget);
          final price = find.descendant(
            of: tile,
            matching: find.textContaining(r'$8.25+', findRichText: true),
          );
          expect(price, findsOneWidget);
          final span =
              tester.renderObject<RenderParagraph>(price).text as TextSpan;
          expect(span.toPlainText(), r'$6.00+ $8.25+');
          expect(
            _spanOf(span, r'$8.25+')?.style?.decoration,
            TextDecoration.lineThrough,
          );
          // a plain sized item: just its "from" price
          expect(
            find.descendant(
              of: find.byKey(const Key('menu-tile-maple-stout')),
              matching: find.text(r'$8.50+'),
            ),
            findsOneWidget,
          );
          expect(tester.takeException(), isNull);
        },
      );
    });
  }
}
