import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/screens/check_screen.dart';

/// A table tapped and left with nothing on it: leaving the check screen asks
/// the store to drop the empty bill (so the floor shows the table free). A
/// bill with something on it is left alone.
final _fixtures = '${Directory.current.path}/test/screenshots/fixtures';
dynamic _fx(String name) =>
    jsonDecode(File('$_fixtures/$name.json').readAsStringSync());

http.Response _json(Object b) => http.Response.bytes(
  utf8.encode(jsonEncode(b)),
  200,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

void main() {
  final drops = <String>[];

  MockClient store({required bool empty}) => MockClient((req) async {
    final path = req.url.path;
    final check = _fx('check') as Map<String, dynamic>;
    if (empty) {
      check['lines'] = [];
      check['pendingLines'] = [];
    }
    if (path == '/checks/1/drop-if-empty') {
      drops.add(req.method);
      return _json({...check, 'status': empty ? 'CANCELLED' : 'OPEN'});
    }
    final body = switch (path) {
      '/items' => _fx('items'),
      '/categories' => _fx('categories'),
      '/checks/1' => check,
      _ => null,
    };
    if (body == null) return http.Response('{"error":"not found"}', 404);
    return _json(body);
  });

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
    drops.clear();
    Prefs.instance.lang = 'en';
  });

  /// The floor (a button) with the table's check screen pushed on top.
  Future<void> openTable(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1920, 1200);
    tester.view.devicePixelRatio = 1.5;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      prefsScope(
        child: MaterialApp(
          theme: buildPosTheme(),
          home: Builder(
            builder: (context) => TextButton(
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) =>
                      const CheckScreen(checkId: 1, tableLabel: 'U-1'),
                ),
              ),
              child: const Text('floor'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('floor'));
    for (var i = 0; i < 8; i++) {
      await tester.pump(const Duration(milliseconds: 150));
    }
    expect(find.byType(CheckScreen), findsOneWidget);
  }

  Future<void> settle(WidgetTester tester) async {
    for (var i = 0; i < 8; i++) {
      await tester.pump(const Duration(milliseconds: 150));
    }
    expect(find.byType(CheckScreen), findsNothing);
  }

  testWidgets('back arrow on an empty table bill drops it', (tester) async {
    await http.runWithClient(() async {
      await openTable(tester);
      await tester.tap(find.byIcon(LucideIcons.arrowLeft));
      await settle(tester);
      expect(drops, ['POST']);
    }, () => store(empty: true));
  });

  testWidgets('system back on an empty table bill drops it', (tester) async {
    await http.runWithClient(() async {
      await openTable(tester);
      await tester.binding.handlePopRoute();
      await settle(tester);
      expect(drops, ['POST']);
    }, () => store(empty: true));
  });

  testWidgets('back on a bill with items does not drop it', (tester) async {
    await http.runWithClient(() async {
      await openTable(tester);
      await tester.tap(find.byIcon(LucideIcons.arrowLeft));
      await settle(tester);
      expect(drops, isEmpty);
    }, () => store(empty: false));
  });
}
