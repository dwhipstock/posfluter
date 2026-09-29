import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:flutter/material.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/screens/login_screen.dart';
import 'package:pos_client/widgets/open_shift_prompt.dart';

/// PAY-1: a manager signing in to a store with no open shift is offered one,
/// once (widgets/open_shift_prompt.dart: offerShiftAtSignIn). This was only
/// wired into the manual PIN-login screen (login_screen.dart's _tryLogin) —
/// a manager whose app simply reopens on a still-valid session lands
/// straight on the floor plan through main.dart's session-restore path and
/// never saw the offer at all ("only on some screens"). Fixed by wiring the
/// same call into that path too.
http.Response _json(Object b, [int s = 200]) => http.Response.bytes(
  utf8.encode(jsonEncode(b)),
  s,
  headers: {'content-type': 'application/json'},
);

void main() {
  setUpAll(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
          (_) async => null,
        );
  });

  tearDown(() {
    Api.currentUser = null;
  });

  Future<void> typePin(WidgetTester tester, String pin) async {
    for (final d in pin.split('')) {
      await tester.tap(find.widgetWithText(OutlinedButton, d).last);
      await tester.pump();
    }
  }

  testWidgets(
    'signing in as a manager with no open shift offers to open one',
    (tester) async {
      var shiftChecks = 0;
      final store = MockClient((req) async {
        if (req.url.path == '/login' && req.method == 'POST') {
          return _json({
            'token': 't',
            'userId': 'manager',
            'name': 'Demo Manager',
            'role': 'MANAGER',
            'languageCode': 'en',
            'grants': <String>[],
          });
        }
        if (req.url.path == '/shifts/current') {
          shiftChecks++;
          return _json({'error': 'no open shift'}, 404);
        }
        // /staff, /health, etc.: unused by this flow, safely 404
        return _json({'error': 'not found'}, 404);
      });
      await http.runWithClient(() async {
        await tester.pumpWidget(
          prefsScope(
            child: MaterialApp(
              theme: buildPosTheme(),
              home: const LoginScreen(),
            ),
          ),
        );
        await tester.pump();
        await typePin(tester, '1234');
        await tester.pumpAndSettle();
        expect(
          shiftChecks,
          1,
          reason: 'the sign-in flow must ask the store for the open shift',
        );
        expect(find.byKey(const Key('open-shift-prompt')), findsOneWidget);
        expect(find.text('No cash drawer shift open'), findsOneWidget);
      }, () => store);
    },
  );

  testWidgets(
    'a manager whose session was restored (not just freshly typed in) is '
    'still offered a shift — main.dart wires offerShiftAtSignIn the same way '
    'the login screen does',
    (tester) async {
      Api.currentUser = AuthUser.fromJson({
        'userId': 'manager',
        'name': 'Demo Manager',
        'role': 'MANAGER',
        'grants': <String>[],
      }, 'restored-token');
      var shiftChecks = 0;
      final store = MockClient((req) async {
        if (req.url.path == '/shifts/current') {
          shiftChecks++;
          return _json({'error': 'no open shift'}, 404);
        }
        return _json({'error': 'not found'}, 404);
      });
      await http.runWithClient(() async {
        await tester.pumpWidget(
          prefsScope(
            child: MaterialApp(
              theme: buildPosTheme(),
              // Stand-in for main.dart's StartupGate: it calls
              // offerShiftAtSignIn right after Api.restoreSession() succeeds
              // and before it navigates to homeScreen() — exercised directly
              // here since the gate's own network/pairing setup is out of
              // scope for this test.
              home: Builder(
                builder: (context) => FilledButton(
                  onPressed: () => offerShiftAtSignIn(context),
                  child: const Text('restore'),
                ),
              ),
            ),
          ),
        );
        await tester.tap(find.text('restore'));
        await tester.pumpAndSettle();
        expect(shiftChecks, 1);
        expect(find.byKey(const Key('open-shift-prompt')), findsOneWidget);
      }, () => store);
    },
  );
}
