// Barcode Pub (Bangkok) screenshot harness: the pubs' restaurant screens on
// Barcode's black + yellow palette (PubPalette.barcode) at the Galaxy Tab's
// landscape size, with no network. The store starts BLANK (no rooms, no
// menu), so the blank floor and menu are shot too; the busy floor and check
// reuse the pubs' fixtures to show every state of a table and a check.
//
// Always runs as a smoke test (a layout overflow fails it). To write PNGs:
//   flutter test test/screenshots/barcode_screens_test.dart \
//     --dart-define=SHOTS_DIR=$PWD/../docs/screenshots/barcode
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
import 'package:pos_client/design/skin.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/screens/check_screen.dart';
import 'package:pos_client/screens/login_screen.dart';
import 'package:pos_client/screens/menu_management_screen.dart';
import 'package:pos_client/screens/tender_screen.dart';
import 'package:pos_client/screens/zones_screen.dart';

const _shotsDir = String.fromEnvironment('SHOTS_DIR');
const _physical = Size(1920, 1200);
const _dpr = 1.5;

final _fixtures = '${Directory.current.path}/test/screenshots/fixtures';
dynamic _fx(String name) =>
    jsonDecode(File('$_fixtures/$name.json').readAsStringSync());

const _profile = StoreProfile(
  venueId: 'barcode-pub',
  brand: 'barcode',
  kind: 'restaurant',
  country: 'TH',
  currency: 'THB',
  locales: ['th', 'en'],
  legalAge: 20,
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

Map<String, dynamic> _check({bool pending = false}) {
  final c = _fx('check') as Map<String, dynamic>;
  if (!pending) c['pendingLines'] = [];
  return c;
}

MockClient _store({bool blank = false, bool pending = false}) =>
    MockClient((req) async {
      final body = switch (req.url.path) {
        '/health' => {
          'status': 'ok',
          'venue': 'Barcode Pub',
          'venueId': 'barcode-pub',
          'brand': 'barcode',
          'kind': 'restaurant',
          'country': 'TH',
          'currency': 'THB',
          'locales': ['th', 'en'],
          'legalAge': 20,
          'cashRounding': 'off',
        },
        '/staff' => _fx('staff'),
        '/zones' => blank ? [] : _fx('zones'),
        '/alert-config' => {
          'pendingAlertsEnabled': false,
          'pendingAlertEscalateSeconds': 90,
          'pendingAlertVolume': 0,
        },
        '/items' => blank ? [] : _fx('items'),
        '/categories' => blank ? [] : _fx('categories'),
        '/checks/1' => _check(pending: pending),
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
  Key? expectKey,
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
            theme: buildPosTheme(
              palette: PubPalette.barcode,
              skin: BrandSkin.barcode,
            ),
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
    if (expectKey != null) expect(find.byKey(expectKey), findsOneWidget);
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
    StoreProfile.current = _profile;
    Prefs.instance.lang = 'en';
    Api.currentUser = AuthUser.fromJson({
      'userId': 'manager',
      'name': 'Manager',
      'role': 'MANAGER',
      'languageCode': 'en',
      'grants': ['void', 'refund', 'edit_menu', 'manage_staff'],
    }, 'test-token');
  });

  tearDown(() {
    StoreProfile.current = StoreProfile.pub;
    // the next file's pub screens get the pubs' colours back
    buildPosTheme();
  });

  test('the dark palette is in force and readable', () {
    buildPosTheme(palette: PubPalette.barcode, skin: BrandSkin.barcode);
    expect(T.palette.isDark, isTrue);
    double ratio(Color a, Color b) {
      final la = a.computeLuminance(), lb = b.computeLuminance();
      return (la > lb ? la + .05 : lb + .05) / (la > lb ? lb + .05 : la + .05);
    }

    // every ink on every surface it is drawn on, at WCAG AA
    for (final bg in [T.background, T.surface, T.surfaceAlt, T.accentSoft]) {
      for (final ink in [T.textPrimary, T.textMuted, T.navy, T.success]) {
        expect(
          ratio(ink, bg),
          greaterThanOrEqualTo(4.5),
          reason: '$ink on $bg',
        );
      }
    }
    expect(ratio(T.onPrimary, T.primary), greaterThanOrEqualTo(4.5));
    expect(ratio(T.onNavyMuted, T.navy), greaterThanOrEqualTo(4.5));
    expect(ratio(T.onAccent, T.accent), greaterThanOrEqualTo(4.5));
    expect(ratio(T.onDestructive, T.destructive), greaterThanOrEqualTo(4.5));
    expect(ratio(T.onPending, T.pending), greaterThanOrEqualTo(4.5));
    expect(ratio(T.destructive, T.surface), greaterThanOrEqualTo(4.5));
    expect(ratio(T.attention, T.surface), greaterThanOrEqualTo(4.5));
    // the pubs' palette is untouched
    buildPosTheme();
    expect(T.navy, const Color(0xFF17456E));
    expect(T.palette.isDark, isFalse);
  });

  testWidgets('sign-in', (tester) async {
    Api.currentUser = null;
    await _shoot(tester, 'login', const LoginScreen());
  });

  testWidgets('floor, blank store', (tester) async {
    await _shoot(
      tester,
      'floor-blank',
      const ZonesScreen(),
      store: _store(blank: true),
      // a blank store shows the way to its first room, not an empty screen
      expectKey: const Key('floor-empty-add-room'),
    );
  });

  testWidgets('floor', (tester) async {
    await _shoot(tester, 'floor', const ZonesScreen());
  });

  testWidgets('check', (tester) async {
    await _shoot(
      tester,
      'check',
      const CheckScreen(checkId: 1, tableLabel: 'U-1'),
    );
  });

  testWidgets('check with a guest order waiting', (tester) async {
    await _shoot(
      tester,
      'check-pending',
      const CheckScreen(checkId: 1, tableLabel: 'U-1'),
      store: _store(pending: true),
    );
  });

  testWidgets('tender', (tester) async {
    await _shoot(
      tester,
      'tender',
      TenderScreen(check: Check.fromJson(_check())),
    );
  });

  testWidgets('menu management, blank store', (tester) async {
    await _shoot(
      tester,
      'menu-blank',
      const MenuManagementScreen(),
      store: _store(blank: true),
    );
  });

  testWidgets('menu management', (tester) async {
    await _shoot(tester, 'menu', const MenuManagementScreen());
  });
}
