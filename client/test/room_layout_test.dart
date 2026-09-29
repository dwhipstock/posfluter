import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/widgets/room_layout_preview.dart';

/// "Set up from picture": the ghost preview, its list, replace / merge, Apply.
void main() {
  TableInfo table(String id, String label, int x, {int seats = 4}) =>
      TableInfo.fromJson({
        'id': id,
        'label': label,
        'x': x,
        'y': 100,
        'width': 100,
        'height': 100,
        'shape': 'ROUND',
        'seats': seats,
      });

  final proposal = RoomLayoutProposal(
    proposalId: 'p1',
    zoneId: 'lower',
    tables: [
      table('ai-t1', 'L-1', 100),
      table('ai-t2', 'L-2', 400, seats: 6),
      table('ai-t3', 'L-50', 700, seats: 2),
    ],
    objects: [
      FloorObject.fromJson({
        'id': 'ai-o1',
        'type': 'BAR_FRONT',
        'x': 50,
        'y': 850,
        'width': 600,
        'height': 80,
      }),
    ],
    notes: 'Could not tell if the corner table is a booth.',
    rejected: const ['object 2: unknown type \'DRAGON\''],
    existingTables: 1,
    protectedTables: const ['lower-l-9'],
  );

  Future<void> pump(
    WidgetTester t, {
    required void Function(String, List<TableInfo>, List<FloorObject>) onApply,
    List<TableInfo> existing = const [],
  }) async {
    t.view.physicalSize = const Size(1600, 1000);
    t.view.devicePixelRatio = 1;
    addTearDown(t.view.reset);
    await t.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: RoomLayoutPreview(
            existingTables: existing,
            existingObjects: const [],
            proposal: proposal,
            onApply: (m, ts, os) async => onApply(m, ts, os),
            onCancel: () {},
          ),
        ),
      ),
    );
  }

  testWidgets(
    'shows the ghost and its count, and a removed table is left out',
    (t) async {
      String? mode;
      List<TableInfo>? applied;
      List<FloorObject>? objects;
      await pump(
        t,
        onApply: (m, ts, os) {
          mode = m;
          applied = ts;
          objects = os;
        },
        existing: [table('lower-l-9', 'L-9', 400)],
      );
      expect(find.text('3 table(s), 12 seat(s), 1 object(s)'), findsOneWidget);
      expect(find.byKey(const Key('ghost-table-ai-t1')), findsOneWidget);
      expect(find.textContaining('corner table is a booth'), findsOneWidget);
      expect(
        find.textContaining('1 table(s) with an open bill'),
        findsOneWidget,
      );

      await t.tap(find.byKey(const Key('room-remove-ai-t2')));
      await t.pump();
      expect(find.text('2 table(s), 6 seat(s), 1 object(s)'), findsOneWidget);
      expect(find.byKey(const Key('ghost-table-ai-t2')), findsNothing);

      // drag a ghost table to the right
      await t.drag(
        find.byKey(const Key('ghost-table-ai-t1')),
        const Offset(60, 0),
      );
      await t.pump();

      // the room has a table: replace is the default, merge is one tap away
      await t.tap(find.text('Add to room'));
      await t.pump();
      await t.tap(find.byKey(const Key('room-apply')));
      await t.pump();
      expect(mode, 'merge');
      expect(applied!.map((e) => e.id), ['ai-t1', 'ai-t3']);
      expect(applied!.first.x, greaterThan(100));
      expect(objects!.single.type, 'BAR_FRONT');
    },
  );

  testWidgets('an empty room has no replace / merge choice', (t) async {
    String? mode;
    await pump(t, onApply: (m, _, _) => mode = m);
    expect(find.text('Replace'), findsNothing);
    await t.tap(find.byKey(const Key('room-apply')));
    await t.pump();
    expect(mode, 'merge');
  });

  test('a previewed table goes back with its number from the label', () {
    expect(roomTableJson(table('ai-t3', 'L-50', 700))['number'], 50);
  });
}
