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

/// Print bill: tap prints in the owner's language (no lang); long-press
/// offers the store's languages, each in its own name, and prints that copy.
final _fixtures = '${Directory.current.path}/test/screenshots/fixtures';
dynamic _fx(String name) =>
    jsonDecode(File('$_fixtures/$name.json').readAsStringSync());

http.Response _json(Object b) => http.Response.bytes(
  utf8.encode(jsonEncode(b)),
  200,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

void main() {
  final bills = <Uri>[];

  MockClient store() => MockClient((req) async {
    final path = req.url.path;
    if (path == '/checks/1/bill') {
      bills.add(req.url);
      return _json({'checkId': 1, 'text': 'BILL ${req.url.query}'});
    }
    final check = _fx('check') as Map<String, dynamic>;
    check['pendingLines'] = [];
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
    bills.clear();
    Prefs.instance.lang = 'en';
    StoreProfile.current = const StoreProfile(
      locales: ['fr', 'en', 'es', 'de'],
    );
  });
  tearDown(() => StoreProfile.current = StoreProfile.pub);

  Future<void> pump(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1920, 1200);
    tester.view.devicePixelRatio = 1.5;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      prefsScope(
        child: MaterialApp(
          theme: buildPosTheme(),
          home: const CheckScreen(checkId: 1, tableLabel: 'U-1'),
        ),
      ),
    );
    for (var i = 0; i < 6; i++) {
      await tester.pump(const Duration(milliseconds: 150));
    }
  }

  // stop the check screen's poll timer
  Future<void> unmount(WidgetTester tester) =>
      tester.pumpWidget(const SizedBox());

  testWidgets('long-press shows the languages; Deutsch prints with lang=de', (
    tester,
  ) async {
    await http.runWithClient(() async {
      await pump(tester);
      await tester.longPress(find.text('Print bill'));
      await tester.pumpAndSettle();
      expect(find.text('Print in…'), findsOneWidget);
      for (final n in ['Français', 'English', 'Español', 'Deutsch']) {
        expect(find.text(n), findsOneWidget);
      }
      await tester.tap(find.text('Deutsch'));
      await tester.pumpAndSettle();
      expect(bills, hasLength(1));
      expect(bills.single.queryParameters, {'lang': 'de'});
      expect(Prefs.instance.lang, 'en', reason: 'owner keeps their language');
      expect(find.text('BILL lang=de'), findsOneWidget);
      await unmount(tester);
    }, store);
  });

  testWidgets('tap prints without a lang', (tester) async {
    await http.runWithClient(() async {
      await pump(tester);
      await tester.tap(find.text('Print bill'));
      await tester.pumpAndSettle();
      expect(find.text('Print in…'), findsNothing);
      expect(bills, hasLength(1));
      expect(bills.single.query, isEmpty);
      await unmount(tester);
    }, store);
  });

  testWidgets('one store language: long-press just prints', (tester) async {
    StoreProfile.current = const StoreProfile(locales: ['en']);
    await http.runWithClient(() async {
      await pump(tester);
      await tester.longPress(find.text('Print bill'));
      await tester.pumpAndSettle();
      expect(find.text('Print in…'), findsNothing);
      expect(bills, hasLength(1));
      expect(bills.single.query, isEmpty);
      await unmount(tester);
    }, store);
  });
}
