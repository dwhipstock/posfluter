import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/widgets/ai_photos.dart';

/// AI menu photos in the item editor: hidden when the add-on is off, disabled
/// with a clear note when offline, and a pick-one dialog that saves the choice.
const _png =
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==';

Widget _app(Widget child) => prefsScope(
  child: MaterialApp(
    theme: buildPosTheme(),
    home: Scaffold(body: child),
  ),
);

AiPhotoCandidates _candidates(int n) => AiPhotoCandidates.fromJson({
  'itemId': 'poutine',
  'provider': 'fake',
  'source': 'ai_generated',
  'elapsedMs': 1200,
  'estimatedCostUsd': 0.1,
  'candidates': [
    for (var i = 0; i < n; i++)
      {'id': 'c$i', 'contentType': 'image/png', 'dataBase64': _png},
  ],
});

void main() {
  setUp(() => Prefs.instance.lang = 'en');

  testWidgets('add-on off: no AI buttons at all', (tester) async {
    await tester.pumpWidget(
      _app(
        AiPhotoActions(
          status: AiPhotoStatus.hidden,
          onGenerate: () {},
          onEnhance: () {},
        ),
      ),
    );
    expect(find.byKey(const Key('ai-generate')), findsNothing);
    expect(find.byKey(const Key('ai-enhance')), findsNothing);
  });

  testWidgets('offline: both buttons disabled with a clear note', (
    tester,
  ) async {
    var taps = 0;
    await tester.pumpWidget(
      _app(
        AiPhotoActions(
          status: AiPhotoStatus.unavailable('image_offline'),
          onGenerate: () => taps++,
          onEnhance: () => taps++,
        ),
      ),
    );
    final generate = tester.widget<ButtonStyleButton>(
      find.byKey(const Key('ai-generate')),
    );
    expect(generate.onPressed, isNull);
    await tester.tap(find.byKey(const Key('ai-enhance')));
    expect(taps, 0);
    expect(find.textContaining('need an internet connection'), findsOneWidget);
    expect(find.textContaining('Everything else works'), findsOneWidget);
  });

  testWidgets('online: buttons work, no note', (tester) async {
    var generated = 0;
    await tester.pumpWidget(
      _app(
        AiPhotoActions(
          status: const AiPhotoStatus(configured: true, available: true),
          onGenerate: () => generated++,
          onEnhance: () {},
        ),
      ),
    );
    await tester.tap(find.byKey(const Key('ai-generate')));
    expect(generated, 1);
    expect(find.byKey(const Key('ai-unavailable-note')), findsNothing);
  });

  testWidgets('dialog shows candidates, regenerates, and saves the pick', (
    tester,
  ) async {
    var runs = 0;
    String? chosen;
    bool? result;
    await tester.pumpWidget(
      _app(
        Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              result = await showDialog<bool>(
                context: context,
                builder: (_) => AiPhotoDialog(
                  title: 'Generate',
                  run: () async {
                    runs++;
                    return _candidates(3);
                  },
                  choose: (id) async => chosen = id,
                ),
              );
            },
            child: const Text('open'),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(runs, 1);
    expect(find.byKey(const Key('ai-candidate-c0')), findsOneWidget);
    expect(find.byKey(const Key('ai-candidate-c2')), findsOneWidget);

    await tester.tap(find.byKey(const Key('ai-regenerate')));
    await tester.pumpAndSettle();
    expect(runs, 2);

    await tester.tap(find.byKey(const Key('ai-candidate-c2')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('ai-use')));
    await tester.pumpAndSettle();
    expect(chosen, 'c2');
    expect(result, isTrue);
  });

  testWidgets('a provider error is shown in words, and nothing is saved', (
    tester,
  ) async {
    var saved = false;
    await tester.pumpWidget(
      _app(
        AiPhotoDialog(
          title: 'Generate',
          run: () async =>
              throw ApiException('nope', 'image_refused', null, 422),
          choose: (_) async => saved = true,
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.textContaining('declined this request'), findsOneWidget);
    final use = tester.widget<ButtonStyleButton>(
      find.byKey(const Key('ai-use')),
    );
    expect(use.onPressed, isNull);
    expect(saved, isFalse);
  });

  test('status: an older store without the route hides the feature', () async {
    final store = MockClient((req) async {
      expect(req.url.path, '/ai-photos/status');
      return http.Response(
        jsonEncode({'error': 'not found', 'code': 'not_found'}),
        404,
      );
    });
    final st = await http.runWithClient(Api.aiPhotoStatus, () => store);
    expect(st.configured, isFalse);
  });

  test('status: an unreachable store reads as offline, not hidden', () async {
    final store = MockClient((req) async => throw http.ClientException('down'));
    final st = await http.runWithClient(Api.aiPhotoStatus, () => store);
    expect(st.configured, isTrue);
    expect(st.available, isFalse);
    expect(st.reason, 'image_offline');
  });

  test('status and candidates parse; the item carries its provenance', () {
    final st = AiPhotoStatus.fromJson({
      'configured': true,
      'available': false,
      'provider': 'flux',
      'online': false,
      'reason': 'image_offline',
      'defaultCount': 3,
    });
    expect(st.reason, 'image_offline');
    expect(_candidates(2).candidates.length, 2);
    final item = Item.fromJson({
      'id': 'poutine',
      'nameFr': 'Poutine',
      'nameEn': 'Poutine',
      'category': 'starters',
      'abbrev': 'PO',
      'isAlcohol': false,
      'variants': [],
      'photoVersion': 5,
      'photoSource': 'ai_enhanced',
    });
    expect(item.photoIsAi, isTrue);
  });

  testWidgets('AI badge shows only on AI photos', (tester) async {
    Item item(String? source, {int? version = 1}) => Item.fromJson({
      'id': 'x',
      'nameFr': 'X',
      'nameEn': 'X',
      'category': 'c',
      'abbrev': 'X',
      'isAlcohol': false,
      'variants': [],
      'photoVersion': version,
      'photoSource': source,
    });
    for (final (source, expected) in [
      ('ai_generated', findsOneWidget),
      ('ai_enhanced', findsOneWidget),
      ('original', findsNothing),
      (null, findsNothing),
    ]) {
      await tester.pumpWidget(
        _app(
          AiBadged(
            item: item(source),
            child: const SizedBox(width: 44, height: 44),
          ),
        ),
      );
      expect(find.byType(AiPhotoBadge), expected, reason: '$source');
    }
  });
}
