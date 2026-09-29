import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/widgets/ai_menu.dart';

/// AI menu setup: offline disables with a note; a proposal is only a preview
/// (new / changed / removed) and only the ticked changes are applied; the
/// history reverts, asking first when a later edit touched the same things.
Widget _app(Widget child) => prefsScope(
  child: MaterialApp(
    theme: buildPosTheme(),
    home: Scaffold(body: child),
  ),
);

const _online = AiPhotoStatus(configured: true, available: true);

MenuProposal _proposal() => MenuProposal.fromJson({
  'proposalId': 'p1',
  'provider': 'fake',
  'summary': 'Added a Caesar salad, 86 the salmon.',
  'rejected': ['#3 (remove_item): unknown item'],
  'changes': [
    {
      'id': 'c1',
      'kind': 'add_category',
      'title': 'Salads',
      'details': [
        {'field': 'nameEn', 'after': 'Salads'},
      ],
    },
    {
      'id': 'c2',
      'kind': 'add_item',
      'title': 'Caesar Salad',
      'category': 'Salads',
      'needs': 'c1',
      'details': [
        {'field': 'price', 'label': 'Regular', 'after': r'$14.00'},
      ],
    },
    {
      'id': 'c3',
      'kind': 'update_item',
      'title': 'Maple-Glazed Salmon',
      'details': [
        {'field': 'available', 'before': 'true', 'after': 'false'},
      ],
    },
  ],
});

class _Fake {
  final applied = <List<String>>[];
  final reverts = <(String, bool)>[];
  var pinAsks = 0;
  var conflictOnce = true;

  AiMenuBackend get backend => AiMenuBackend(
    chat: (text, pin) async => _proposal(),
    fromPhotos: (photos, pin) async => _proposal(),
    apply: (id, ids, pin) async {
      applied.add(ids);
      return MenuApplyResult(ids.length, const ['caesar-salad'], 'set1');
    },
    history: () async => [
      MenuChangeSet(
        id: 'set1',
        source: 'chat',
        summary: 'Added a Caesar salad',
        appliedBy: 'Manager',
        createdAt: DateTime(2026, 9, 29, 10, 5),
        changeCount: 2,
        titles: const ['Caesar Salad'],
        reverted: reverts.any((r) => r.$1 == 'set1'),
      ),
    ],
    revert: (id, pin, force) async {
      if (!force && conflictOnce) {
        conflictOnce = false;
        throw const MenuRevertConflict(['Caesar Salad']);
      }
      reverts.add((id, force));
    },
  );

  Widget dialog({AiPhotoStatus status = _online}) => AiMenuDialog(
    status: status,
    askPin: () async {
      pinAsks++;
      return '1234';
    },
    pickPhotos: () async => [
      (bytes: const [1, 2, 3], contentType: 'image/jpeg'),
    ],
    backend: backend,
  );
}

void main() {
  setUp(() => Prefs.instance.lang = 'en');

  testWidgets('offline: buttons disabled with a clear note', (tester) async {
    await tester.pumpWidget(
      _app(
        _Fake().dialog(status: AiPhotoStatus.unavailable('menu_ai_offline')),
      ),
    );
    expect(find.byKey(const Key('ai-menu-unavailable')), findsOneWidget);
    expect(
      tester
          .widget<FilledButton>(find.byKey(const Key('ai-menu-ask')))
          .onPressed,
      isNull,
    );
    expect(
      tester
          .widget<OutlinedButton>(find.byKey(const Key('ai-menu-photos')))
          .onPressed,
      isNull,
    );
  });

  testWidgets(
    'chat → preview; only ticked changes apply, new category follows its item',
    (tester) async {
      final fake = _Fake();
      await tester.pumpWidget(_app(fake.dialog()));
      await tester.enterText(
        find.byKey(const Key('ai-menu-text')),
        '86 the salmon',
      );
      await tester.tap(find.byKey(const Key('ai-menu-ask')));
      await tester.pumpAndSettle();

      expect(find.text('Added a Caesar salad, 86 the salmon.'), findsOneWidget);
      expect(find.text('New (2)'), findsOneWidget);
      expect(find.text('Changed (1)'), findsOneWidget);
      expect(find.textContaining('1 suggestion(s) skipped'), findsOneWidget);
      expect(find.textContaining(r'$14.00'), findsOneWidget);
      expect(fake.applied, isEmpty); // a preview changes nothing

      // untick the category: the item that needs it goes too
      await tester.ensureVisible(find.byKey(const Key('ai-menu-change-c1')));
      await tester.tap(find.byKey(const Key('ai-menu-change-c1')));
      await tester.pump();
      expect(find.text('Apply (1)'), findsOneWidget);
      // tick the salad back: its category comes with it
      await tester.ensureVisible(find.byKey(const Key('ai-menu-change-c2')));
      await tester.tap(find.byKey(const Key('ai-menu-change-c2')));
      await tester.pump();
      expect(find.text('Apply (3)'), findsOneWidget);
      await tester.ensureVisible(find.byKey(const Key('ai-menu-change-c3')));
      await tester.tap(find.byKey(const Key('ai-menu-change-c3')));
      await tester.pump();

      await tester.tap(find.byKey(const Key('ai-menu-apply')));
      await tester.pumpAndSettle();
      expect(fake.applied.single, ['c1', 'c2']);
      expect(fake.pinAsks, 1);
    },
  );

  testWidgets('history: revert asks before overwriting a later edit', (
    tester,
  ) async {
    final fake = _Fake();
    await tester.pumpWidget(_app(fake.dialog()));
    await tester.tap(find.byKey(const Key('ai-menu-history')));
    await tester.pumpAndSettle();
    expect(find.text('Added a Caesar salad'), findsOneWidget);

    await tester.tap(find.byKey(const Key('ai-menu-revert-set1')));
    await tester.pumpAndSettle();
    expect(
      find.textContaining('Changed since this AI update: Caesar Salad'),
      findsOneWidget,
    );
    expect(fake.reverts, isEmpty);

    await tester.tap(find.byKey(const Key('ai-menu-revert-anyway')));
    await tester.pumpAndSettle();
    expect(fake.reverts.single, ('set1', true));
    expect(find.text('Reverted'), findsOneWidget);
  });
}
