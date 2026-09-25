// Tablet screenshot harness: renders the sign-in, floor and check screens at
// the Galaxy Tab's landscape resolution (1920x1200 px at 1.5x = 1280x800
// logical) against captured store fixtures, with no network.
//
// Always runs as a smoke test (a layout overflow at tablet size fails it).
// To also write PNGs:
//   flutter test test/screenshots --dart-define=SHOTS_DIR=$PWD/../docs/screenshots
import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/screens/check_screen.dart';
import 'package:pos_client/screens/login_screen.dart';
import 'package:pos_client/screens/menu_management_screen.dart';
import 'package:pos_client/screens/tender_screen.dart';
import 'package:pos_client/screens/zones_screen.dart';

const _shotsDir = String.fromEnvironment('SHOTS_DIR');
const _shotPrefix = String.fromEnvironment('SHOT_PREFIX');
const _physical = Size(1920, 1200);
const _dpr = 1.5;

final _fixtures = '${Directory.current.path}/test/screenshots/fixtures';
dynamic _fx(String name) =>
    jsonDecode(File('$_fixtures/$name.json').readAsStringSync());

/// Every font the app bundles (icons included) — the test binding otherwise
/// renders all text as boxes.
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

/// Zones fixture with open times pinned relative to now, so "time open"
/// reads the same on every run.
List<dynamic> _zones() {
  final zones = _fx('zones') as List;
  final now = DateTime.now().toUtc();
  var i = 0;
  for (final z in zones) {
    for (final t in z['tables'] as List) {
      if (t['openCheckOpenedAt'] != null) {
        t['openCheckOpenedAt'] = now
            .subtract(Duration(minutes: 18 + 23 * i++))
            .toIso8601String();
      }
      if (t['oldestPendingAt'] != null) {
        t['oldestPendingAt'] = now
            .subtract(const Duration(seconds: 40))
            .toIso8601String();
      }
    }
  }
  return zones;
}

Map<String, dynamic> _check({bool pending = false, bool empty = false}) {
  final c = _fx('check') as Map<String, dynamic>;
  if (!pending) c['pendingLines'] = [];
  if (empty) {
    c['lines'] = [];
    c['fees'] = [];
    c['grandTotalCents'] = 0;
  }
  return c;
}

MockClient _store({Map<String, dynamic>? check}) => MockClient((req) async {
  final path = req.url.path;
  final body = switch (path) {
    '/health' => _fx('health'),
    '/staff' => _fx('staff'),
    '/zones' => _zones(),
    '/alert-config' => {
      'pendingAlertsEnabled': false,
      'pendingAlertEscalateSeconds': 90,
      'pendingAlertVolume': 0,
    },
    '/items' => _fx('items'),
    '/categories' => _fx('categories'),
    '/checks/1' => check ?? _check(),
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
  Future<void> Function(WidgetTester tester)? act,
}) async {
  tester.view.physicalSize = _physical;
  tester.view.devicePixelRatio = _dpr;
  addTearDown(tester.view.reset);
  // the floor's alert chime opens audio event streams, which have no host
  // implementation under test; everything else still fails the test
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
    // let fixtures resolve and fetch-on-mount spinners pass
    for (var i = 0; i < 6; i++) {
      await tester.pump(const Duration(milliseconds: 150));
    }
    if (act != null) {
      await act(tester);
      await tester.pump(const Duration(milliseconds: 300));
    }
    // real time for any font file still loading, then repaint with it
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
          '$_shotsDir/$_shotPrefix$name.png',
        ).writeAsBytesSync(png!.buffer.asUint8List());
      });
    }
    // unmount: stops the screens' poll timers and animations
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 1));
  }, () => store ?? _store());
  FlutterError.onError = reportError;
}

/// Seat counts pluralise, small one-seat bar tables drop the seat line, and
/// no corner marker (sub-table link) covers any text.
Future<void> Function(WidgetTester) _checkTableLabels({
  required String many,
  required String one,
}) => (tester) async {
  expect(find.text(many), findsWidgets);
  expect(find.textContaining('1 seats'), findsNothing);
  expect(find.text(one), findsNothing); // U-2…U-7 are too small for it
  final links = find.byIcon(LucideIcons.link);
  expect(links, findsWidgets);
  final texts = find
      .byType(Text)
      .evaluate()
      .map((e) => tester.getRect(find.byWidget(e.widget)));
  for (final link in links.evaluate()) {
    final r = tester.getRect(find.byWidget(link.widget));
    for (final t in texts) {
      expect(r.overlaps(t), isFalse, reason: 'link icon $r overlaps text $t');
    }
  }
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() async {
    _mockPlugins();
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

  testWidgets('sign-in', (tester) async {
    Api.currentUser = null;
    await _shoot(
      tester,
      'login',
      const LoginScreen(),
      expectText: 'Demo Manager',
    );
  });

  testWidgets('sign-in (French)', (tester) async {
    Api.currentUser = null;
    Prefs.instance.lang = 'fr';
    await _shoot(tester, 'login-fr', const LoginScreen());
  });

  testWidgets('floor', (tester) async {
    await _shoot(
      tester,
      'floor',
      const ZonesScreen(),
      expectText: 'U-1',
      act: _checkTableLabels(many: '4 seats', one: '1 seat'),
    );
  });

  testWidgets('floor (French)', (tester) async {
    Prefs.instance.lang = 'fr';
    await _shoot(
      tester,
      'floor-fr',
      const ZonesScreen(),
      act: _checkTableLabels(many: '4 places', one: '1 place'),
    );
  });

  testWidgets('check', (tester) async {
    await _shoot(
      tester,
      'check',
      const CheckScreen(checkId: 1, tableLabel: 'U-1'),
      expectText: 'Lantern House Lager',
    );
  });

  testWidgets('check, long item names', (tester) async {
    await _shoot(
      tester,
      'check-burgers',
      const CheckScreen(checkId: 1, tableLabel: 'U-1'),
      act: (t) => t.tap(find.text('Burgers & Sandwiches')),
      expectText: 'Mushroom Swiss Burger',
    );
  });

  testWidgets('check (French)', (tester) async {
    Prefs.instance.lang = 'fr';
    await _shoot(
      tester,
      'check-fr',
      const CheckScreen(checkId: 1, tableLabel: 'U-1'),
    );
  });

  testWidgets('check with a guest order waiting', (tester) async {
    await _shoot(
      tester,
      'check-pending',
      const CheckScreen(checkId: 1, tableLabel: 'U-1'),
      store: _store(check: _check(pending: true)),
    );
  });

  testWidgets('check, empty cart', (tester) async {
    await _shoot(
      tester,
      'check-empty',
      const CheckScreen(checkId: 1, tableLabel: 'U-1'),
      store: _store(check: _check(empty: true)),
    );
  });

  testWidgets('tender', (tester) async {
    await _shoot(
      tester,
      'tender',
      TenderScreen(check: Check.fromJson(_check())),
    );
  });

  testWidgets('menu management', (tester) async {
    await _shoot(tester, 'menu-management', const MenuManagementScreen());
  });
}
