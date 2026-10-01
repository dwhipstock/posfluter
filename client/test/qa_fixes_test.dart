// Hands-on QA fixes on the Copper Lantern tablet: the floor plan shows what
// a part-paid bill still owes, the item editor puts the store's own language
// first and asks before Esc throws edits away, and the X / Z report keeps the
// two NC taxes apart for remittance.
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
import 'package:pos_client/screens/menu_management_screen.dart';
import 'package:pos_client/screens/zones_screen.dart';
import 'package:pos_client/widgets/floor_object_icons.dart';

final _fixtures = '${Directory.current.path}/test/screenshots/fixtures';
dynamic _fx(String name) =>
    jsonDecode(File('$_fixtures/$name.json').readAsStringSync());

MockClient _store({Object? zones}) => MockClient((req) async {
  final body = switch (req.url.path) {
    '/health' => _fx('health'),
    '/zones' => zones ?? _fx('zones'),
    '/alert-config' => {
      'pendingAlertsEnabled': false,
      'pendingAlertEscalateSeconds': 90,
      'pendingAlertVolume': 0,
    },
    '/items' => _fx('items'),
    '/categories' => _fx('categories'),
    _ => null,
  };
  if (body == null) return http.Response('{"error":"not found"}', 404);
  return http.Response.bytes(
    utf8.encode(jsonEncode(body)),
    200,
    headers: {'content-type': 'application/json; charset=utf-8'},
  );
});

Future<void> _pump(WidgetTester tester, Widget screen, MockClient store) async {
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
  }, () => store);
}

void main() {
  setUpAll(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
          (_) async => null,
        );
  });
  setUp(() => Prefs.instance.lang = 'en');

  group('floor plan: a part-paid bill', () {
    test('the tile amount is the balance once part paid', () {
      TableInfo t(Map<String, dynamic> extra) => TableInfo.fromJson({
        'id': 't1',
        'label': '1',
        'openCheckId': 7,
        'openCheckStatus': 'OPEN',
        'openCheckTotalCents': 3114,
        ...extra,
      });
      expect(t({}).partPaid, isFalse);
      expect(t({}).openCheckShownCents, 3114);
      final part = t({
        'openCheckPaidCents': 1557,
        'openCheckOutstandingCents': 1557,
      });
      expect(part.partPaid, isTrue);
      expect(part.openCheckShownCents, 1557);
      // carried through an editor copy
      expect(part.copyWith(x: 10).openCheckShownCents, 1557);
    });

    testWidgets('shows what is still owed, not the full bill', (tester) async {
      final zones = (_fx('zones') as List).cast<Map<String, dynamic>>();
      final table =
          (zones.first['tables'] as List).first as Map<String, dynamic>;
      table['openCheckTotalCents'] = 3114;
      table['openCheckPaidCents'] = 1557;
      table['openCheckOutstandingCents'] = 1557;
      await _pump(tester, const ZonesScreen(), _store(zones: zones));
      expect(find.text('\$15.57 left'), findsOneWidget);
      expect(find.textContaining('\$31.14'), findsNothing);
      await tester.pumpWidget(const SizedBox());
    });
  });

  group('item editor', () {
    setUp(() {
      Api.currentUser = AuthUser('t', 'u1', 'Manager', 'MANAGER', 'en');
    });
    tearDown(() {
      Api.currentUser = null;
      StoreProfile.current = StoreProfile.pub;
    });

    Future<void> openFirstItem(WidgetTester tester) async {
      await _pump(tester, const MenuManagementScreen(), _store());
      final first = (_fx('items') as List).first as Map<String, dynamic>;
      await tester.tap(find.text(first['nameEn'] as String).first);
      await tester.pumpAndSettle();
    }

    double leftOf(WidgetTester tester, String label) =>
        tester.getTopLeft(find.widgetWithText(TextField, label).first).dx;

    testWidgets('an English-first store lists English before French', (
      tester,
    ) async {
      StoreProfile.current = StoreProfile.pub; // Copper Lantern: en first
      await openFirstItem(tester);
      final l = const L(true);
      expect(
        leftOf(tester, l.nameEnLabel),
        lessThan(leftOf(tester, l.nameFrLabel)),
      );
      expect(
        leftOf(tester, l.sizeLabelEn),
        lessThan(leftOf(tester, l.sizeLabelFr)),
      );
    });

    testWidgets('a French-first store keeps French first', (tester) async {
      StoreProfile.current = const StoreProfile(locales: ['fr', 'en']);
      await openFirstItem(tester);
      final l = const L(true);
      expect(
        leftOf(tester, l.nameFrLabel),
        lessThan(leftOf(tester, l.nameEnLabel)),
      );
    });

    testWidgets('Esc with no changes closes; with changes it asks first', (
      tester,
    ) async {
      await openFirstItem(tester);
      final l = const L(true);
      expect(find.text(l.editItem), findsOneWidget);
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pumpAndSettle();
      expect(find.text(l.editItem), findsNothing, reason: 'nothing to lose');

      await openFirstItem(tester);
      await tester.enterText(
        find.widgetWithText(TextField, l.nameEnLabel).first,
        'Renamed',
      );
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pumpAndSettle();
      expect(find.text(l.unsavedItemTitle), findsOneWidget);
      await tester.tap(find.text(l.keepEditing));
      await tester.pumpAndSettle();
      expect(find.text(l.editItem), findsOneWidget, reason: 'still editing');
      expect(find.text('Renamed'), findsOneWidget);

      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pumpAndSettle();
      await tester.tap(find.text(l.discard));
      await tester.pumpAndSettle();
      expect(find.text(l.editItem), findsNothing);
      await tester.pumpWidget(const SizedBox());
    });
  });

  test('X / Z report: the taxes stay apart, with who they are paid to', () {
    final r = ShiftReport.fromJson({
      'shiftId': 1,
      'shiftStatus': 'OPEN',
      'openedAt': '2026-10-01T09:00:00-04:00',
      'openedBy': 'u1',
      'openingFloatCents': 0,
      'revenueCents': 6928,
      'transactionCount': 1,
      'avgCheckCents': 6928,
      'corkageCents': 0,
      'tenderBreakdown': const [],
      'itemMix': const [],
      'voids': const [],
      'taxes': [
        {
          'code': 'NC_SALES',
          'labelFr': 'NC sales tax',
          'labelEn': 'NC sales tax',
          'ratePercent': '7.25',
          'remitTo': 'NCDOR',
          'amountCents': 464,
        },
        {
          'code': 'WAKE_FOOD',
          'labelFr': 'Wake prepared food tax',
          'labelEn': 'Wake prepared food tax',
          'ratePercent': '1',
          'remitTo': 'Wake County',
          'amountCents': 64,
        },
      ],
    });
    expect(r.taxes.map((t) => t.remitTo), ['NCDOR', 'Wake County']);
    const l = L(true);
    expect(l.taxLine(r.taxes.first), 'NC sales tax 7.25%');
    expect(l.remitTo(r.taxes.first.remitTo), 'remit to NCDOR');
    // the report never collapses them, whatever the guests see
    expect(r.taxes.fold(0, (s, t) => s + t.amountCents), 528);
  });

  test('a Stage room object: named in every language, with its own icon', () {
    expect(floorObjectTypeIcon('STAGE', null), Icons.mic_external_on);
    expect(
      [
        for (final lang in ['fr', 'en', 'es', 'de', 'af'])
          L.forLang(lang).objectTypeName('STAGE'),
      ],
      ['Scène', 'Stage', 'Escenario', 'Bühne', 'Verhoog'],
    );
  });
}
