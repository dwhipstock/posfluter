import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/screens/floor_plan_edit_screen.dart';
import 'package:pos_client/widgets/mic_button.dart';

/// The floor plan editor's "Ask AI" assistant (text and voice): the working
/// card (spinner/step/elapsed/progress) clears once the result arrives, and
/// Apply / Cancel stay reachable — not hidden under it or pushed off by the
/// change list. Cancelling the working card drops a result that shows up late.
class _FakeRecorder implements VoiceRecorder {
  @override
  Future<bool> start() async => true;
  @override
  Future<VoiceClip?> stop() async => (
    bytes: Uint8List.fromList(List.filled(8000, 1)),
    contentType: 'audio/wav',
  );
  @override
  void dispose() {}
}

http.Response _json(Object b, [int status = 200]) => http.Response.bytes(
  utf8.encode(jsonEncode(b)),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

Map<String, dynamic> _editProposal(String transcript) => {
  'proposalId': 'e1',
  'zoneId': 'z1',
  'edit': true,
  'summary': 'Added a 2-top.',
  if (transcript.isNotEmpty) 'transcript': transcript,
  'tables': [
    {
      'id': 'new-t1',
      'label': 'L-9',
      'x': 400,
      'y': 400,
      'width': 100,
      'height': 100,
      'shape': 'SQUARE',
      'seats': 2,
    },
  ],
  'objects': [],
  'changes': [
    {
      'id': 'c1',
      'kind': 'add_table',
      'title': 'L-9',
      'details': [
        {'field': 'seats', 'after': '2'},
      ],
    },
  ],
};

Widget _app(Widget child) =>
    prefsScope(child: MaterialApp(theme: buildPosTheme(), home: child));

void main() {
  setUp(() => Prefs.instance.lang = 'en');

  final zone = Zone('z1', 'Bas', 'Lower', 'OPEN', const [], const [], 'L');

  Future<void> openAskAi(WidgetTester t) async {
    // the app bar's AI popup menu needs real room to lay out its notes
    t.view.physicalSize = const Size(1600, 1000);
    t.view.devicePixelRatio = 1;
    addTearDown(t.view.reset);
    await t.pumpWidget(
      _app(
        FloorPlanEditScreen(
          zone: zone,
          managerPin: '1234',
          recorder: _FakeRecorder(),
        ),
      ),
    );
    await t.pumpAndSettle();
    await t.tap(find.byKey(const Key('room-ai-menu')));
    await t.pumpAndSettle();
    await t.tap(find.byKey(const Key('object-menu-ASK')));
    await t.pumpAndSettle();
  }

  // The AI working card ticks a real 1s Timer while shown, which would make
  // a bare pumpAndSettle() spin forever. These zero-duration pumps drain the
  // mocked HTTP round trip (a handful of microtasks) without advancing the
  // clock far enough to fire that timer, landing on the settled result.
  Future<void> pumpUntilAnswered(WidgetTester t) async {
    for (var i = 0; i < 10; i++) {
      await t.pump();
    }
  }

  testWidgets(
    'text: the working card clears and Apply/Cancel stay visible',
    (tester) async {
      final client = MockClient((req) async {
        if (req.url.path == '/menu-ai/status') {
          return _json({'configured': true, 'available': true});
        }
        if (req.url.path == '/zones/z1/ai-edit' && req.method == 'POST') {
          return _json(_editProposal(''));
        }
        return http.Response('{"error":"not found"}', 404);
      });
      await http.runWithClient(() async {
        await openAskAi(tester);
        // no autofocus: it must not pop the on-screen keyboard by itself
        expect(
          tester
              .widget<TextField>(find.byKey(const Key('floor-ai-text')))
              .autofocus,
          isFalse,
        );
        await tester.enterText(
          find.byKey(const Key('floor-ai-text')),
          'add a 2-top by the window',
        );
        await tester.tap(find.byKey(const Key('floor-ai-ask')));
        await tester.pump();
        await pumpUntilAnswered(tester);
        await tester.pumpAndSettle();
        expect(find.byKey(const Key('ai-working')), findsNothing);
        expect(find.byKey(const Key('room-apply')), findsOneWidget);
        expect(find.text('Cancel'), findsOneWidget);
      }, () => client);
    },
  );

  testWidgets(
    'voice: the working card clears and Apply/Cancel stay visible',
    (tester) async {
      final client = MockClient((req) async {
        if (req.url.path == '/menu-ai/status') {
          return _json({'configured': true, 'available': true});
        }
        if (req.url.path == '/zones/z1/ai-edit/voice' &&
            req.method == 'POST') {
          return _json(_editProposal('add a 2-top by the window'));
        }
        return http.Response('{"error":"not found"}', 404);
      });
      await http.runWithClient(() async {
        await openAskAi(tester);
        await tester.tap(find.byKey(const Key('floor-ai-mic')));
        await tester.pump();
        await tester.tap(find.byKey(const Key('floor-ai-mic')));
        await tester.pump();
        await pumpUntilAnswered(tester);
        await tester.pumpAndSettle();
        expect(find.byKey(const Key('ai-working')), findsNothing);
        expect(find.byKey(const Key('room-apply')), findsOneWidget);
        expect(find.text('Cancel'), findsOneWidget);
        expect(
          find.text('Heard: “add a 2-top by the window”'),
          findsOneWidget,
        );
      }, () => client);
    },
  );

  testWidgets(
    'cancelling the working card drops a result that arrives late',
    (tester) async {
      final answer = Completer<http.Response>();
      final client = MockClient((req) async {
        if (req.url.path == '/menu-ai/status') {
          return _json({'configured': true, 'available': true});
        }
        if (req.url.path == '/zones/z1/ai-edit' && req.method == 'POST') {
          return answer.future;
        }
        return http.Response('{"error":"not found"}', 404);
      });
      await http.runWithClient(() async {
        await openAskAi(tester);
        await tester.enterText(
          find.byKey(const Key('floor-ai-text')),
          'add a 2-top by the window',
        );
        await tester.tap(find.byKey(const Key('floor-ai-ask')));
        await tester.pump();
        expect(find.byKey(const Key('ai-working')), findsOneWidget);

        await tester.tap(find.byKey(const Key('ai-working-cancel')));
        await tester.pump();
        expect(find.byKey(const Key('ai-working')), findsNothing);
        // no preview and no error dialog while the answer is still pending
        expect(find.byKey(const Key('room-apply')), findsNothing);

        // the answer finally shows up: it's ignored, no preview, no crash
        answer.complete(_json(_editProposal('')));
        await pumpUntilAnswered(tester);
        expect(find.byKey(const Key('room-apply')), findsNothing);
        expect(tester.takeException(), isNull);
      }, () => client);
    },
  );

  testWidgets(
    'the back button clears the working card instead of leaving it stuck',
    (tester) async {
      final answer = Completer<http.Response>();
      final client = MockClient((req) async {
        if (req.url.path == '/menu-ai/status') {
          return _json({'configured': true, 'available': true});
        }
        if (req.url.path == '/zones/z1/ai-edit' && req.method == 'POST') {
          return answer.future;
        }
        return http.Response('{"error":"not found"}', 404);
      });
      await http.runWithClient(() async {
        await openAskAi(tester);
        await tester.enterText(
          find.byKey(const Key('floor-ai-text')),
          'add a 2-top by the window',
        );
        await tester.tap(find.byKey(const Key('floor-ai-ask')));
        await tester.pump();
        expect(find.byKey(const Key('ai-working')), findsOneWidget);

        // the system back button, mid-request
        await tester.binding.handlePopRoute();
        await tester.pump();
        expect(find.byKey(const Key('ai-working')), findsNothing);
        // still on the editor — one back press only cancelled the request
        expect(find.byKey(const Key('room-ai-menu')), findsOneWidget);

        answer.complete(_json(_editProposal('')));
        await pumpUntilAnswered(tester);
        expect(find.byKey(const Key('room-apply')), findsNothing);
        expect(tester.takeException(), isNull);
      }, () => client);
    },
  );

  testWidgets(
    'leaving the editor drops focus, closes the keyboard and clears any '
    'snackbar',
    (tester) async {
      final client = MockClient((req) async {
        if (req.url.path == '/menu-ai/status') {
          return _json({'configured': true, 'available': true});
        }
        if (req.url.path == '/zones/z1/ai-edit' && req.method == 'POST') {
          return _json(_editProposal(''));
        }
        if (req.url.path == '/zones/z1/ai-edit/apply' && req.method == 'POST') {
          return _json({
            'changeSetId': 'set1',
            'applied': 1,
            'tables': <Object>[],
            'objects': <Object>[],
          });
        }
        return http.Response('{"error":"not found"}', 404);
      });
      await http.runWithClient(() async {
        // a real route to pop, not just FloorPlanEditScreen as `home`: a
        // dialog closing on its own must not be mistaken for leaving the
        // editor screen itself
        final navKey = GlobalKey<NavigatorState>();
        tester.view.physicalSize = const Size(1600, 1000);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        await tester.pumpWidget(
          prefsScope(
            child: MaterialApp(
              theme: buildPosTheme(),
              navigatorKey: navKey,
              home: const Scaffold(body: Center(child: Text('home'))),
            ),
          ),
        );
        navKey.currentState!.push(
          MaterialPageRoute(
            builder: (_) => FloorPlanEditScreen(
              zone: zone,
              managerPin: '1234',
              recorder: _FakeRecorder(),
            ),
          ),
        );
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('room-ai-menu')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('object-menu-ASK')));
        await tester.pumpAndSettle();

        // focus the field: the (simulated) on-screen keyboard is now up
        await tester.tap(find.byKey(const Key('floor-ai-text')));
        await tester.pump();
        expect(tester.testTextInput.isVisible, isTrue);
        await tester.enterText(
          find.byKey(const Key('floor-ai-text')),
          'add a 2-top by the window',
        );
        await tester.tap(find.byKey(const Key('floor-ai-ask')));
        await pumpUntilAnswered(tester);
        await tester.pumpAndSettle();

        // apply: the "Floor plan updated … Revert" snackbar shows
        await tester.tap(find.byKey(const Key('room-apply')));
        await pumpUntilAnswered(tester);
        await tester.pump(); // let the snackbar animate in
        expect(find.byType(SnackBar), findsOneWidget);

        // leave the editor entirely
        navKey.currentState!.pop();
        await tester.pumpAndSettle();

        // the editor (and its text field) is gone, so nothing there can hold focus
        expect(find.byType(TextField), findsNothing);
        expect(find.byType(EditableText), findsNothing);
        expect(tester.testTextInput.isVisible, isFalse);
        expect(find.byType(SnackBar), findsNothing);
      }, () => client);
    },
  );
}
