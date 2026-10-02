import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/screens/floor_plan_edit_screen.dart';

/// The room editor's remove message names the table that went: a sub-table
/// (U-17) names itself, never its parent (U-16); and a parent that still has
/// sub-tables names them.
http.Response _json(Object b, [int status = 200]) => http.Response.bytes(
  utf8.encode(jsonEncode(b)),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

TableInfo _table(String id, String label, int x, {String? parent}) =>
    TableInfo.fromJson({
      'id': id,
      'label': label,
      'parentTableId': parent,
      'x': x,
      'y': 400,
      'width': 120,
      'height': 120,
      'shape': 'SQUARE',
      'seats': 4,
    });

void main() {
  setUp(() => Prefs.instance.lang = 'en');

  testWidgets('removing a sub-table names it; its parent names the sub-table '
      'still on it', (tester) async {
    tester.view.physicalSize = const Size(1600, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final zone = Zone(
      'z1',
      'Haut',
      'Upper',
      'OPEN',
      [_table('u5', 'U-16', 300), _table('u5-5', 'U-17', 600, parent: 'u5')],
      const [],
      'U',
    );
    final deleted = <String>[];
    final client = MockClient((req) async {
      final m = RegExp(r'^/tables/([^/]+)/delete$').firstMatch(req.url.path);
      if (m != null) {
        if (m[1] == 'u5' && !deleted.contains('u5-5')) {
          return _json({
            'error': 'table u5 has 1 sub-table(s); remove them first',
            'code': 'has_sub_tables',
          }, 409);
        }
        deleted.add(m[1]!);
        return _json({'tableId': m[1], 'deleted': 'true'});
      }
      return http.Response('{"error":"not found"}', 404);
    });

    Future<void> remove(String label) async {
      await tester.tap(find.text(label).first);
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(OutlinedButton, 'Remove table'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Remove table'));
      await tester.pumpAndSettle();
    }

    String message() => tester
        .widget<Text>(find.byKey(const Key('table-removed-message')))
        .data!;

    await http.runWithClient(() async {
      await tester.pumpWidget(
        prefsScope(
          child: MaterialApp(
            theme: buildPosTheme(),
            home: FloorPlanEditScreen(zone: zone, managerPin: '1234'),
          ),
        ),
      );
      await tester.pumpAndSettle();

      // the parent first: refused, and the sub-table on it is named
      await remove('U-16');
      expect(deleted, isEmpty);
      expect(
        message(),
        'Table "U-16" has sub-tables (U-17): remove them first',
      );
      ScaffoldMessenger.of(
        tester.element(find.byType(FloorPlanEditScreen)),
      ).clearSnackBars();
      await tester.pumpAndSettle();

      // the sub-table: removed, and the message names U-17, not U-16
      await remove('U-17');
      expect(deleted, ['u5-5']);
      expect(message(), 'Table "U-17" removed');
      expect(find.text('U-17'), findsNothing);
      expect(tester.takeException(), isNull);
    }, () => client);
  });
}
