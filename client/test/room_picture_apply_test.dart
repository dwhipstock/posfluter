import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/screens/floor_plan_edit_screen.dart';
import 'package:pos_client/widgets/ai_working.dart';

/// "Set up from picture" in the floor plan editor: once Apply (or Cancel, or
/// an error) is done, the ghost preview, its panel and its × markers are gone
/// and the canvas shows the room as saved; leaving mid-request is clean.
http.Response _json(Object b, [int status = 200]) => http.Response.bytes(
  utf8.encode(jsonEncode(b)),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

Map<String, dynamic> _table(String id, String label, int x) => {
  'id': id,
  'label': label,
  'x': x,
  'y': 300,
  'width': 100,
  'height': 100,
  'shape': 'SQUARE',
  'seats': 4,
};

final _old = _table('old1', 'L-1', 100);
final _saved = [_table('s1', 'L-2', 300), _table('s2', 'L-3', 500)];

Map<String, dynamic> _pictureProposal() => {
  'proposalId': 'p1',
  'zoneId': 'z1',
  'tables': [_table('g1', 'L-2', 300), _table('g2', 'L-3', 500)],
  'objects': [],
};

Widget _screen() => FloorPlanEditScreen(
  zone: Zone(
    'z1',
    'Bas',
    'Lower',
    'OPEN',
    [TableInfo.fromJson(_old)],
    const [],
    'L',
  ),
  managerPin: '1234',
  pickRoomPhotos: () async => [
    (bytes: List.filled(64, 1), contentType: 'image/jpeg'),
  ],
);

void main() {
  setUp(() => Prefs.instance.lang = 'en');

  Future<void> pumpUntilAnswered(WidgetTester t) async {
    for (var i = 0; i < 10; i++) {
      await t.pump();
    }
  }

  Future<void> open(WidgetTester t, {GlobalKey<NavigatorState>? navKey}) async {
    t.view.physicalSize = const Size(1600, 1000);
    t.view.devicePixelRatio = 1;
    addTearDown(t.view.reset);
    await t.pumpWidget(
      prefsScope(
        child: MaterialApp(
          theme: buildPosTheme(),
          navigatorKey: navKey,
          home: navKey == null
              ? _screen()
              : const Scaffold(body: Center(child: Text('home'))),
        ),
      ),
    );
    if (navKey != null) {
      navKey.currentState!.push(MaterialPageRoute(builder: (_) => _screen()));
    }
    await t.pumpAndSettle();
  }

  Future<void> startPicture(WidgetTester t) async {
    await t.tap(find.byKey(const Key('room-ai-menu')));
    await t.pumpAndSettle();
    await t.tap(find.byKey(const Key('object-menu-ROOM_PHOTO')));
    await t.pump();
  }

  void expectNoPreview() {
    expect(find.byKey(const Key('room-summary')), findsNothing);
    expect(find.byKey(const Key('room-apply')), findsNothing);
    expect(find.byKey(const Key('ghost-table-g1')), findsNothing);
    expect(find.byKey(const Key('ai-working')), findsNothing);
  }

  MockClient server({
    required bool applied,
    Completer<void>? gate,
    int applyStatus = 200,
  }) => MockClient((req) async {
    final path = req.url.path;
    if (path == '/menu-ai/status') {
      return _json({'configured': true, 'available': true});
    }
    if (path == '/zones') {
      return _json([
        {
          'id': 'z1',
          'nameFr': 'Bas',
          'nameEn': 'Lower',
          'tables': applied ? _saved : [_old],
          'objects': [],
        },
      ]);
    }
    if (path == '/zones/z1/ai-layout' && req.method == 'POST') {
      if (gate != null) await gate.future;
      return _json(_pictureProposal());
    }
    if (path == '/zones/z1/ai-layout/apply' && req.method == 'POST') {
      if (applyStatus != 200) {
        return _json({'error': 'already applied'}, applyStatus);
      }
      expect(jsonDecode(req.body)['mode'], 'replace');
      return _json({
        'changeSetId': 'set1',
        'added': 2,
        'removed': 1,
        'tables': _saved,
        'objects': [],
      });
    }
    return http.Response('{"error":"not found"}', 404);
  });

  testWidgets(
    'Apply (replace): the preview and its × markers go, the saved room shows',
    (tester) async {
      await http.runWithClient(() async {
        await open(tester);
        await startPicture(tester);
        await pumpUntilAnswered(tester);
        await tester.pumpAndSettle();
        expect(find.byKey(const Key('room-summary')), findsOneWidget);
        expect(find.byKey(const Key('ghost-table-g1')), findsOneWidget);

        await tester.tap(find.byKey(const Key('room-apply')));
        await pumpUntilAnswered(tester);
        await tester.pump(const Duration(milliseconds: 300));

        expectNoPreview();
        expect(find.byType(SnackBar), findsOneWidget); // the Revert one
        expect(find.text('L-2'), findsOneWidget);
        expect(find.text('L-3'), findsOneWidget);
        expect(find.text('L-1'), findsNothing); // replaced
        await tester.pumpAndSettle(const Duration(seconds: 9));
      }, () => server(applied: true));
    },
  );

  testWidgets(
    'a leftover Revert snackbar never sits over the next preview\'s buttons',
    (tester) async {
      await http.runWithClient(() async {
        await open(tester);
        await startPicture(tester);
        await pumpUntilAnswered(tester);
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('room-apply')));
        await pumpUntilAnswered(tester);
        await tester.pump(const Duration(milliseconds: 300));
        expect(find.byType(SnackBar), findsOneWidget);

        // again, while that snackbar is still up
        await startPicture(tester);
        await pumpUntilAnswered(tester);
        await tester.pumpAndSettle();
        expect(find.byType(SnackBar), findsNothing);
        expect(find.byKey(const Key('room-summary')), findsOneWidget);
        await tester.tap(find.byKey(const Key('room-apply'))); // hittable
        await pumpUntilAnswered(tester);
        await tester.pump(const Duration(milliseconds: 300));
        expectNoPreview();
        await tester.pumpAndSettle(const Duration(seconds: 9));
      }, () => server(applied: true));
    },
  );

  testWidgets('Cancel: the preview goes, the room is as it was', (
    tester,
  ) async {
    await http.runWithClient(() async {
      await open(tester);
      await startPicture(tester);
      await pumpUntilAnswered(tester);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Cancel'));
      await pumpUntilAnswered(tester);
      await tester.pumpAndSettle();

      expectNoPreview();
      expect(find.text('L-1'), findsOneWidget);
      expect(find.text('L-2'), findsNothing);
    }, () => server(applied: false));
  });

  testWidgets('an Apply error leaves preview mode and shows the saved room', (
    tester,
  ) async {
    await http.runWithClient(() async {
      await open(tester);
      await startPicture(tester);
      await pumpUntilAnswered(tester);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('room-apply')));
      await pumpUntilAnswered(tester);
      await tester.pump(const Duration(milliseconds: 300));

      expectNoPreview();
      expect(find.text('L-1'), findsOneWidget);
      await tester.pumpAndSettle(const Duration(seconds: 9));
    }, () => server(applied: false, applyStatus: 409));
  });

  testWidgets(
    'the working card shows (~40 s) and leaving mid-request is clean',
    (tester) async {
      final gate = Completer<void>();
      final navKey = GlobalKey<NavigatorState>();
      await http.runWithClient(() async {
        await open(tester, navKey: navKey);
        await startPicture(tester);
        await pumpUntilAnswered(tester);
        expect(find.byKey(const Key('ai-working')), findsOneWidget);
        // the same working card as Ask AI, paced for the slower picture read
        expect(
          tester
              .widget<AiWorkingIndicator>(find.byType(AiWorkingIndicator))
              .expected,
          const Duration(seconds: 40),
        );
        expect(find.text('Cancel'), findsOneWidget);

        navKey.currentState!.pop(); // leave while the AI is still working
        await tester.pump();
        await tester.pump(const Duration(seconds: 1));
        gate.complete(); // the late result arrives on a gone screen
        await pumpUntilAnswered(tester);
        await tester.pumpAndSettle();

        expect(find.text('home'), findsOneWidget);
        expect(find.byType(FloorPlanEditScreen), findsNothing);
        expect(find.byKey(const Key('room-summary')), findsNothing);
        expect(tester.takeException(), isNull);
      }, () => server(applied: false, gate: gate));
    },
  );
}
