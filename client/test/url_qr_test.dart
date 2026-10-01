import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/kitchen/kitchen_i18n.dart';
import 'package:pos_client/kitchen/kitchen_screen.dart';
import 'package:pos_client/payments/terminal.dart';
import 'package:pos_client/payments/terminal_settings.dart';
import 'package:pos_client/screens/settings_screen.dart';
import 'package:pos_client/widgets/url_qr.dart';

/// Every link the POS shows for a person to open comes with a QR of exactly
/// that link (UrlQr), and demo mode's "Print demo QR sheet".
http.Response _json(Object? b, [int status = 200]) => http.Response.bytes(
  utf8.encode(jsonEncode(b)),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

const _store = 'http://192.168.1.50:8080';

/// The settings screen's own list (text fields have scrollables too).
final _list = find
    .descendant(of: find.byType(ListView), matching: find.byType(Scrollable))
    .first;

Map<String, dynamic> _settings({bool demo = false}) => {
  'cardProcessor': '',
  'bankName': '',
  'bankAccountNumber': '',
  'bankAccountName': '',
  'serviceChargePercent': 0,
  'corkagePerBottleCents': 0,
  'receiptFooter': '',
  'venuePhone': '',
  'venueAddress': '',
  'sessionIdleMinutes': 5,
  'demoMode': demo,
};

/// The QR on screen carries [url] and the same URL is shown as text.
void expectUrlQr(WidgetTester tester, String url) {
  expect(find.byKey(UrlQr.qrKey(url)), findsOneWidget, reason: url);
  final qr = tester.widget<UrlQr>(
    find.ancestor(
      of: find.byKey(UrlQr.qrKey(url)),
      matching: find.byType(UrlQr),
    ),
  );
  expect(qr.url, url);
  expect(find.widgetWithText(SelectableText, url), findsOneWidget);
  expect(
    tester.getSize(find.byKey(UrlQr.qrKey(url))).width,
    greaterThanOrEqualTo(200),
  );
}

class _Terminal extends TerminalClient {
  const _Terminal();
  @override
  Future<TerminalStatus> status() async => const TerminalStatus(
    kind: 'simulator',
    integrated: true,
    available: true,
    embedded: true,
    readerState: 'ready',
  );
}

void main() {
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
  setUp(() => Prefs.instance.lang = 'en');

  Future<void> pump(WidgetTester tester, Widget home) async {
    tester.view.physicalSize = const Size(1920, 1200);
    tester.view.devicePixelRatio = 1.5;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      prefsScope(
        child: MaterialApp(theme: buildPosTheme(), home: home),
      ),
    );
    for (var i = 0; i < 6; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
  }

  testWidgets('UrlQr in a dialog: the QR payload is the shown URL', (
    tester,
  ) async {
    const url = '$_store/pickup';
    await pump(
      tester,
      Builder(
        builder: (context) => TextButton(
          onPressed: () => showDialog<void>(
            context: context,
            builder: (_) => const AlertDialog(content: UrlQr(url)),
          ),
          child: const Text('open'),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull, reason: 'intrinsic-safe');
    expectUrlQr(tester, url);
  });

  testWidgets('kitchen screen: "web screen" shows its link and its QR', (
    tester,
  ) async {
    final client = MockClient((req) async {
      if (req.url.path == '/kitchen/board') {
        return _json({
          'cards': [],
          'recent': [],
          'stations': [],
          'warnMinutes': 10,
          'lateMinutes': 20,
          'latestTicket': 0,
        });
      }
      if (req.url.path == '/cloud/info') return _json({'storeUrl': _store});
      return _json({'error': 'not found'}, 404);
    });
    await http.runWithClient(() async {
      await pump(tester, const KitchenScreen());
      await tester.tap(find.byTooltip(const K('en').webScreen));
      for (var i = 0; i < 6; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      // not the embedded store here: the kitchen link is this POS's store
      expectUrlQr(tester, '${Api.baseUrl}/kitchen');
      await tester.pumpWidget(const SizedBox());
    }, () => client);
  });

  group('Venue settings', () {
    final posts = <Uri>[];
    MockClient store({required bool demo}) => MockClient((req) async {
      final p = req.url.path;
      if (p == '/settings') return _json(_settings(demo: demo));
      if (p == '/cloud/info') {
        return _json({
          'portalUrl': 'https://portal.example.com',
          'storeUrl': _store,
        });
      }
      if (p == '/printer/demo-sheet/print') {
        posts.add(req.url);
        return _json({'configured': true, 'online': true});
      }
      if (p == '/payments/terminal') return _json({'kind': 'external'});
      return _json({'error': 'not found'}, 404);
    });
    setUp(posts.clear);

    testWidgets('staff app and owner portal: link text + QR of that link', (
      tester,
    ) async {
      await http.runWithClient(() async {
        await pump(tester, const SettingsScreen());
        expectUrlQr(tester, '${Api.baseUrl}/staff-app');
        expectUrlQr(tester, 'https://portal.example.com');
        await tester.pumpWidget(const SizedBox());
      }, () => store(demo: false));
    });

    testWidgets('demo mode off: shown as off, no demo sheet button', (
      tester,
    ) async {
      await http.runWithClient(() async {
        await pump(tester, const SettingsScreen());
        final status = find.byKey(const ValueKey('demo-mode-status'));
        await tester.scrollUntilVisible(status, 300, scrollable: _list);
        expect(tester.widget<Text>(status).data, L.forLang('en').demoModeOff);
        expect(find.byKey(const ValueKey('print-demo-sheet')), findsNothing);
        await tester.pumpWidget(const SizedBox());
      }, () => store(demo: false));
    });

    testWidgets('demo mode on: "Print demo QR sheet" prints in the app '
        'language', (tester) async {
      Prefs.instance.lang = 'de';
      await http.runWithClient(() async {
        await pump(tester, const SettingsScreen());
        final button = find.byKey(const ValueKey('print-demo-sheet'));
        await tester.scrollUntilVisible(button, 300, scrollable: _list);
        expect(find.text(L.forLang('de').printDemoSheet), findsOneWidget);
        await tester.tap(button);
        for (var i = 0; i < 6; i++) {
          await tester.pump(const Duration(milliseconds: 100));
        }
        expect(posts.single.queryParameters['lang'], 'de');
        expect(find.text(L.forLang('de').demoSheetPrinted), findsOneWidget);
        await tester.pumpWidget(const SizedBox());
      }, () => store(demo: true));
    });
  });

  testWidgets('card terminal (built-in practice reader): its /terminal page '
      'link + QR', (tester) async {
    final client = MockClient((req) async {
      if (req.url.path == '/cloud/info') return _json({'storeUrl': _store});
      return _json({'error': 'not found'}, 404);
    });
    await http.runWithClient(() async {
      await pump(
        tester,
        const Scaffold(
          body: SingleChildScrollView(
            child: CardTerminalSettings(client: _Terminal()),
          ),
        ),
      );
      expectUrlQr(tester, '$_store/terminal');
      await tester.pumpWidget(const SizedBox());
    }, () => client);
  });
}
