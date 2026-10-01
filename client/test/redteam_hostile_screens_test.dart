// Red team: hostile data typed by execs at a live demo, on the restaurant
// (Copper Lantern) tablet screens. Each test FAILS on a layout error
// (RenderFlex overflow etc.) or on money shown in the wrong format.
//
//   flutter test test/redteam_hostile_screens_test.dart
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/screens/check_screen.dart';
import 'package:pos_client/screens/login_screen.dart';
import 'package:pos_client/screens/menu_management_screen.dart';
import 'package:pos_client/screens/tender_screen.dart';
import 'package:pos_client/screens/zones_screen.dart';

const _tab15 = (Size(1920, 1200), 1.5); // Galaxy Tab, 1280x800 logical
const _tab10 = (Size(1280, 800), 1.0); // 10" mdpi tablet, 1280x800 logical
const _tab8 = (Size(1280, 800), 4 / 3); // 8" tvdpi tablet, 960x600 logical

final _fixtures = '${Directory.current.path}/test/screenshots/fixtures';
dynamic _fx(String name) =>
    jsonDecode(File('$_fixtures/$name.json').readAsStringSync());

// ---------------------------------------------------------------- hostile text
final long500 = 'Wagyu' * 100; // 500 chars, no spaces
final spaced500 = List.filled(100, 'beef').join(' ').padRight(500, 'x');
const zalgo =
    'Z̶̢̛̤͓͖͙̩͉̺͇̦'
    'à̸́̂̃̄̅̆̇̈̉̊'
    'l̵̐̑̒̓̔̽̾̿̀̕̚'
    'ǵ̶͂̓̈́͆͊͋͌͐͑͒'
    'o̷͗͛ͣͤͥͦͧͨͩͪͫ';
const rtl = 'برجر الفانوس النحاسي مع البطاطس המבורגר של הפנס';
const cjk = '铜灯笼特制汉堡包配手切薯条和秘制酱料双层芝士';
const emoji = '🍔🔥👨‍👩‍👧‍👦🇨🇦🍟🥤✨';
const newlines = 'Line one\nLine two\nLine three\nLine four\nLine five';

List<String> get hostile => [
  long500,
  spaced500,
  zalgo * 8,
  rtl,
  cjk,
  emoji * 5,
  newlines,
];

// -------------------------------------------------------------------- harness
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

MockClient _store({
  Object? check,
  Object? zones,
  Object? items,
  Object? staff,
}) => MockClient((req) async {
  final body = switch (req.url.path) {
    '/health' => _fx('health'),
    '/staff' => staff ?? _fx('staff'),
    '/zones' => zones ?? _fx('zones'),
    '/alert-config' => {
      'pendingAlertsEnabled': false,
      'pendingAlertEscalateSeconds': 90,
      'pendingAlertVolume': 0,
    },
    '/items' => items ?? _fx('items'),
    '/categories' => _fx('categories'),
    '/checks/1' => check ?? _fx('check'),
    _ => null,
  };
  if (body == null) return http.Response('{"error":"not found"}', 404);
  return http.Response.bytes(
    utf8.encode(jsonEncode(body)),
    200,
    headers: {'content-type': 'application/json; charset=utf-8'},
  );
});

/// Pumps [screen] at a tablet size; returns every layout/render error the
/// framework reported (overflows included), as one short line each.
Future<List<String>> _render(
  WidgetTester tester,
  Widget screen, {
  required MockClient store,
  (Size, double) size = _tab15,
  Future<void> Function(WidgetTester)? act,
}) async {
  tester.view.physicalSize = size.$1;
  tester.view.devicePixelRatio = size.$2;
  addTearDown(tester.view.reset);
  final errors = <String>[];
  final prev = FlutterError.onError;
  FlutterError.onError = (d) {
    if (d.exception is MissingPluginException) return;
    // first line + where in lib/ the offending widget was built
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
            theme: buildPosTheme(),
            home: screen,
          ),
        ),
      );
      for (var i = 0; i < 6; i++) {
        await tester.pump(const Duration(milliseconds: 150));
      }
      if (act != null) {
        await act(tester);
        await tester.pump(const Duration(milliseconds: 300));
      }
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 200)),
      );
      await tester.pump(const Duration(milliseconds: 150));
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(seconds: 1));
    }, () => store);
  } finally {
    FlutterError.onError = prev;
  }
  return errors;
}

// ------------------------------------------------------------- data builders
Map<String, dynamic> _hostileCheck({
  int lineCents = 9999999,
  int totalCents = 123456789,
}) {
  final c = _fx('check') as Map<String, dynamic>;
  final h = hostile;
  final lines = (c['lines'] as List).cast<Map<String, dynamic>>();
  for (final (i, l) in lines.indexed) {
    final name = h[i % h.length];
    l['nameEn'] = name;
    l['nameFr'] = name;
    l['note'] = h[(i + 1) % h.length];
    l['qty'] = 999;
    l['unitPriceCents'] = lineCents;
    l['lineTotalCents'] = lineCents;
  }
  c['pendingLines'] = [];
  c['subtotalCents'] = totalCents;
  c['itemsSubtotalCents'] = totalCents;
  c['grandTotalCents'] = totalCents;
  c['outstandingCents'] = totalCents;
  for (final t in (c['taxes'] as List).cast<Map<String, dynamic>>()) {
    t['amountCents'] = 9999999;
  }
  return c;
}

/// The real menu, names untouched, every price [cents].
List<dynamic> _pricedItems(int cents) {
  final items = (_fx('items') as List).cast<Map<String, dynamic>>();
  for (final it in items) {
    for (final v in (it['variants'] as List).cast<Map<String, dynamic>>()) {
      v['priceCents'] = cents;
    }
  }
  return items;
}

List<dynamic> _hostileItems() {
  final items = (_fx('items') as List).cast<Map<String, dynamic>>();
  final h = hostile;
  for (final (i, it) in items.indexed) {
    final name = h[i % h.length];
    it['nameEn'] = name;
    it['nameFr'] = name;
    it['names'] = {'es': name, 'de': name, 'af': name};
    it['abbrev'] = emoji;
    for (final v in (it['variants'] as List).cast<Map<String, dynamic>>()) {
      v['priceCents'] = 123456789; // $1,234,567.89
    }
  }
  return items;
}

List<dynamic> _hostileZones() {
  final zones = (_fx('zones') as List).cast<Map<String, dynamic>>();
  final h = hostile;
  for (final (zi, z) in zones.indexed) {
    z['nameEn'] = h[zi % h.length];
    z['nameFr'] = h[zi % h.length];
    for (final (i, t) in (z['tables'] as List)
        .cast<Map<String, dynamic>>()
        .indexed) {
      t['nameOverride'] = h[i % h.length];
      if (t['openCheckId'] != null) t['openCheckTotalCents'] = 123456789;
    }
  }
  return zones;
}

List<Map<String, dynamic>> _hostileStaff() => [
  for (final (i, n) in hostile.indexed)
    {'id': 's$i', 'name': n, 'role': i.isEven ? 'MANAGER' : 'SERVER'},
];

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
    Api.currentUser = AuthUser.fromJson({
      'userId': 'manager',
      'name': 'Demo Manager',
      'role': 'MANAGER',
      'languageCode': 'en',
      'grants': ['void', 'refund', 'edit_menu', 'manage_staff'],
    }, 'test-token');
  });

  for (final lang in ['en', 'fr', 'es', 'de', 'af']) {
    for (final size in [_tab15, _tab10, _tab8]) {
      final dprTag = size.$2 == 1.0 ? '' : '@${size.$2.toStringAsFixed(2)}';
      final tag =
          '$lang ${size.$1.width.toInt()}x${size.$1.height.toInt()}$dprTag';

      // a plain menu, only the price is big: \$99,999.99 (\$99,999.99+ on
      // items with sizes) — what an exec types to "see what happens"
      testWidgets('menu grid: \$99,999.99 price on real item names [$tag]', (
        tester,
      ) async {
        Prefs.instance.lang = lang;
        final errors = await _render(
          tester,
          const CheckScreen(checkId: 1, tableLabel: 'U-1'),
          store: _store(items: _pricedItems(9999999)),
          size: size,
          act: (t) async {
            expect(find.text(r'$99,999.99+'), findsWidgets);
          },
        );
        expect(errors, isEmpty, reason: errors.join('\n'));
      });

      testWidgets('check: hostile line names/notes + huge money [$tag]', (
        tester,
      ) async {
        Prefs.instance.lang = lang;
        final errors = await _render(
          tester,
          const CheckScreen(checkId: 1, tableLabel: 'U-1'),
          store: _store(check: _hostileCheck(), items: _hostileItems()),
          size: size,
          act: (t) async {
            expect(find.text(r'$1,234,567.89'), findsWidgets);
          },
        );
        expect(errors, isEmpty, reason: errors.join('\n'));
      });

      // VIP table name: the server allows 100 chars (name_override
      // VARCHAR(100)) and keeps newlines; the floor editor has no maxLength
      testWidgets('check: 100-char VIP table name w/ newlines in header [$tag]', (
        tester,
      ) async {
        Prefs.instance.lang = lang;
        final vip = List.filled(25, 'VIP\n').join().substring(0, 99);
        expect(vip.length, lessThanOrEqualTo(100));
        final errors = await _render(
          tester,
          CheckScreen(checkId: 1, tableLabel: vip),
          store: _store(),
          size: size,
        );
        expect(errors, isEmpty, reason: errors.join('\n'));
      });

      // the same 100-char cap, ordinary words, no newlines
      testWidgets('check: 100-char VIP table name, plain words [$tag]', (
        tester,
      ) async {
        Prefs.instance.lang = lang;
        const vip =
            'Board of Directors, Copper Lantern Hospitality Group, '
            'with their very important guests tonight';
        expect(vip.length, lessThanOrEqualTo(100));
        final errors = await _render(
          tester,
          const CheckScreen(checkId: 1, tableLabel: vip),
          store: _store(),
          size: size,
        );
        expect(errors, isEmpty, reason: errors.join('\n'));
      });

      testWidgets('floor: hostile room/table names + huge open totals [$tag]', (
        tester,
      ) async {
        Prefs.instance.lang = lang;
        final errors = await _render(
          tester,
          const ZonesScreen(),
          store: _store(zones: _hostileZones()),
          size: size,
        );
        expect(errors, isEmpty, reason: errors.join('\n'));
      });

      testWidgets('sign-in: hostile staff names [$tag]', (tester) async {
        Api.currentUser = null;
        Prefs.instance.lang = lang;
        final errors = await _render(
          tester,
          const LoginScreen(),
          store: _store(staff: _hostileStaff()),
          size: size,
        );
        expect(errors, isEmpty, reason: errors.join('\n'));
      });

      testWidgets('tender: \$1,234,567.89 due [$tag]', (tester) async {
        Prefs.instance.lang = lang;
        final errors = await _render(
          tester,
          TenderScreen(check: Check.fromJson(_hostileCheck())),
          store: _store(check: _hostileCheck()),
          size: size,
          act: (t) async {
            expect(find.textContaining(r'$1,234,567.89'), findsWidgets);
          },
        );
        expect(errors, isEmpty, reason: errors.join('\n'));
      });
    }
  }

  // VIP name (server cap 100 chars) on a table tile: lib/widgets/floor_plan.dart
  // :312-317 lays the label out on ONE line inside a FittedBox(scaleDown), so a
  // long name is shrunk to fit the tile rather than wrapped or ellipsized
  testWidgets('floor: a 100-char VIP name on a table stays readable '
      '(>= 9 px tall on screen)', (tester) async {
    const vip =
        'Board of Directors, Copper Lantern Hospitality Group, '
        'with their very important guests tonight';
    final zones = (_fx('zones') as List).cast<Map<String, dynamic>>();
    (zones.first['tables'] as List).first['nameOverride'] = vip;
    double? shownHeight;
    final errors = await _render(
      tester,
      const ZonesScreen(),
      store: _store(zones: zones),
      act: (t) async {
        final label = find.text(vip);
        expect(label, findsWidgets);
        shownHeight = t.getRect(label.first).height;
      },
    );
    expect(errors, isEmpty, reason: errors.join('\n'));
    expect(
      shownHeight,
      greaterThanOrEqualTo(9),
      reason: 'label drawn ${shownHeight?.toStringAsFixed(1)} px tall',
    );
  });

  // plain demo data, translations only: every label must fit the buttons
  group('5-language labels, plain data', () {
    for (final lang in ['en', 'fr', 'es', 'de', 'af']) {
      for (final size in [_tab15, _tab8]) {
        final tag = '$lang ${(size.$1 / size.$2).width.toInt()}x'
            '${(size.$1 / size.$2).height.toInt()} logical';
        for (final (name, screen) in [
          ('sign-in', const LoginScreen() as Widget),
          ('floor', const ZonesScreen()),
          ('check', const CheckScreen(checkId: 1, tableLabel: 'U-1')),
          ('menu management', const MenuManagementScreen()),
        ]) {
          testWidgets('$name [$tag]', (tester) async {
            if (name == 'sign-in') Api.currentUser = null;
            Prefs.instance.lang = lang;
            final errors = await _render(
              tester,
              screen,
              store: _store(),
              size: size,
            );
            expect(errors, isEmpty, reason: errors.join('\n'));
          });
        }
        testWidgets('tender [$tag]', (tester) async {
          Prefs.instance.lang = lang;
          final c = _fx('check') as Map<String, dynamic>;
          c['pendingLines'] = [];
          final errors = await _render(
            tester,
            TenderScreen(check: Check.fromJson(c)),
            store: _store(),
            size: size,
          );
          expect(errors, isEmpty, reason: errors.join('\n'));
        });
      }
    }
  });
}
