import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/widgets/ai_menu.dart';
import 'package:pos_client/widgets/mic_button.dart';
import 'package:pos_client/widgets/room_layout_preview.dart';

/// Voice input: the mic button's states (tap to start / tap to stop, hold to
/// talk, no microphone), "Heard: …" in the menu AI chat, and the floor
/// assistant's change list in the ghost preview.
class _FakeRecorder implements VoiceRecorder {
  bool allow = true;
  var starts = 0, stops = 0;
  @override
  Future<bool> start() async {
    starts++;
    return allow;
  }

  @override
  Future<VoiceClip?> stop() async {
    stops++;
    return (
      bytes: Uint8List.fromList(List.filled(8000, 1)),
      contentType: 'audio/wav',
    );
  }

  @override
  void dispose() {}
}

Widget _app(Widget child) => prefsScope(
  child: MaterialApp(
    theme: buildPosTheme(),
    home: Scaffold(body: Center(child: child)),
  ),
);

void main() {
  setUp(() => Prefs.instance.lang = 'en');

  testWidgets('tap to start, tap again to send; a spinner while it sends', (
    tester,
  ) async {
    final rec = _FakeRecorder();
    final clips = <VoiceClip>[];
    final sending = Completer<void>();
    await tester.pumpWidget(
      _app(
        MicButton(
          recorder: rec,
          onClip: (c) {
            clips.add(c);
            return sending.future;
          },
        ),
      ),
    );
    expect(find.byKey(const Key('mic-idle')), findsOneWidget);

    await tester.tap(find.byType(MicButton));
    await tester.pump();
    expect(find.byKey(const Key('mic-recording')), findsOneWidget);
    expect(rec.starts, 1);

    await tester.tap(find.byType(MicButton));
    await tester.pump();
    expect(find.byKey(const Key('mic-sending')), findsOneWidget);
    expect(clips.single.contentType, 'audio/wav');

    sending.complete();
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('mic-idle')), findsOneWidget);
    expect(rec.stops, 1);
  });

  testWidgets('hold to talk: release sends', (tester) async {
    final rec = _FakeRecorder();
    var sent = 0;
    await tester.pumpWidget(
      _app(MicButton(recorder: rec, onClip: (_) async => sent++)),
    );
    final press = await tester.startGesture(
      tester.getCenter(find.byType(MicButton)),
    );
    await tester.pump(const Duration(milliseconds: 600));
    expect(find.byKey(const Key('mic-recording')), findsOneWidget);
    await press.up();
    await tester.pumpAndSettle();
    expect(sent, 1);
    expect(find.byKey(const Key('mic-idle')), findsOneWidget);
  });

  testWidgets('no microphone: stays idle and says so', (tester) async {
    final rec = _FakeRecorder()..allow = false;
    var sent = 0;
    await tester.pumpWidget(
      _app(MicButton(recorder: rec, onClip: (_) async => sent++)),
    );
    await tester.tap(find.byType(MicButton));
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const Key('mic-idle')), findsOneWidget);
    expect(find.textContaining('microphone isn’t available'), findsOneWidget);
    expect(sent, 0);
  });

  testWidgets('disabled: a tap does nothing', (tester) async {
    final rec = _FakeRecorder();
    await tester.pumpWidget(
      _app(MicButton(recorder: rec, enabled: false, onClip: (_) async {})),
    );
    await tester.tap(find.byType(MicButton));
    await tester.pump();
    expect(find.byKey(const Key('mic-idle')), findsOneWidget);
    expect(rec.starts, 0);
  });

  testWidgets('menu AI chat by voice shows what was heard', (tester) async {
    final rec = _FakeRecorder();
    final clips = <VoiceClip>[];
    Future<MenuProposal> never(_, _) => throw UnimplementedError();
    await tester.pumpWidget(
      _app(
        AiMenuDialog(
          status: const AiPhotoStatus(configured: true, available: true),
          askPin: () async => '1234',
          pickPhotos: () async => null,
          backend: AiMenuBackend(
            chat: never,
            fromPhotos: never,
            apply: (_, _, _, _) => throw UnimplementedError(),
            history: () async => const [],
            revert: (_, _, _) async {},
            recorder: rec,
            chatVoice: (clip, pin) async {
              clips.add(clip);
              return MenuProposal.fromJson({
                'proposalId': 'p1',
                'summary': 'Burgers up a dollar.',
                'transcript': 'raise all burgers by one dollar',
                'changes': [
                  {
                    'id': 'c1',
                    'kind': 'update_item',
                    'title': 'Copper Lantern Burger',
                    'details': [
                      {
                        'field': 'price',
                        'label': 'Regular',
                        'before': r'$19.25',
                        'after': r'$20.25',
                      },
                    ],
                  },
                ],
              });
            },
          ),
        ),
      ),
    );
    await tester.tap(find.byKey(const Key('ai-menu-mic')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('ai-menu-mic')));
    await tester.pumpAndSettle();
    expect(clips, hasLength(1));
    expect(
      find.text('Heard: “raise all burgers by one dollar”'),
      findsOneWidget,
    );
    expect(find.text('Burgers up a dollar.'), findsOneWidget);
  });

  testWidgets('floor assistant: the change list, what was heard, Apply', (
    tester,
  ) async {
    TableInfo table(String id, String label, int x) => TableInfo.fromJson({
      'id': id,
      'label': label,
      'x': x,
      'y': 100,
      'width': 100,
      'height': 100,
      'shape': 'ROUND',
      'seats': 4,
    });
    final p = RoomLayoutProposal.fromJson({
      'proposalId': 'e1',
      'zoneId': 'lower',
      'edit': true,
      'summary': 'Table 5 round, the pool table out.',
      'transcript': 'make table 5 round and remove the pool table',
      'tables': [
        {
          'id': 'l10',
          'label': 'L-5',
          'x': 60,
          'y': 690,
          'width': 110,
          'height': 110,
          'shape': 'ROUND',
          'seats': 6,
        },
      ],
      'objects': [],
      'removedObjects': ['lower-pool'],
      'changes': [
        {
          'id': 'c1',
          'kind': 'update_table',
          'title': 'L-5',
          'details': [
            {'field': 'shape', 'before': 'SQUARE', 'after': 'ROUND'},
            {'field': 'seats', 'before': '4', 'after': '6'},
          ],
        },
        {'id': 'c2', 'kind': 'remove_object', 'title': 'Pool'},
      ],
    });
    String? applied;
    await tester.binding.setSurfaceSize(const Size(1280, 800));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(
      _app(
        RoomLayoutPreview(
          existingTables: [table('l10', 'L-5', 60), table('t7', 'L-1', 300)],
          existingObjects: const [],
          proposal: p,
          onApply: (mode, _, _) async => applied = mode,
          onCancel: () {},
        ),
      ),
    );
    expect(
      find.text('Heard: “make table 5 round and remove the pool table”'),
      findsOneWidget,
    );
    expect(find.text('Table changed · L-5'), findsOneWidget);
    expect(find.text('Shape: square → round'), findsOneWidget);
    expect(find.text('Object removed · Pool'), findsOneWidget);
    // no replace / merge choice for an edit, and the ghost has no ×
    expect(find.text('Replace'), findsNothing);
    await tester.tap(find.byKey(const Key('room-apply')));
    await tester.pumpAndSettle();
    expect(applied, 'merge');
  });
}
