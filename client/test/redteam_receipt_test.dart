// Red team: the on-screen receipt / bill preview must show the server's
// rendered text exactly as it prints — one printed row per screen row.
//
// The server (server/.../sdk/Printing.kt) renders at WIDTH = 42 but lets a
// row run to PAPER = 48 columns ("a row past it wraps at WIDTH; one that only
// passes WIDTH prints as it always did"). The preview card is a fixed 420 px
// box with 20 px padding (380 px for text) at 14 px monospace. Android's
// `monospace` (Droid Sans Mono) is 0.6 em per column → 8.4 px → 48 cols =
// 403 px: those rows wrap, and the price drops onto its own line.
//
// The test loads Droid Sans Mono (or Courier New: same 0.6 em advance) under
// the `monospace` family so widths match the tablet.
//
//   flutter test test/redteam_receipt_test.dart
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/screens/bill_preview_screen.dart';
import 'package:pos_client/screens/receipt_screen.dart';

const _monoCandidates = [
  '/Applications/Android Studio.app/Contents/jbr/Contents/Home/lib/fonts/DroidSansMono.ttf',
  '/System/Library/Fonts/Supplemental/Courier New.ttf',
  '/usr/share/fonts/truetype/droid/DroidSansMono.ttf',
];

String? _monoPath() {
  for (final p in _monoCandidates) {
    if (File(p).existsSync()) return p;
  }
  return null;
}

/// A server-shaped bill: KeyValue rows padded to WIDTH (42) and one row that
/// the server lets run to 47 cols (left + 1 + right <= PAPER).
String _bill() {
  String kv(String left, String right, [int width = 42]) {
    final pad = (width - left.length - right.length).clamp(1, 99);
    return left + ' ' * pad + right;
  }

  final long = kv('1 x Mushroom Swiss Burger + bacon', r'$1,234.56', 0);
  return [
    '              COPPER LANTERN',
    '-' * 42,
    kv('2 x Lantern House Lager', r'$15.00'),
    kv('1 x Chicken Wings', r'$16.75'),
    long, // 33 + 1 + 9 = 43..48 cols: allowed by the server
    kv('Brisket & Smoked Cheddar Sandwich 2x', r'$99,999.99', 0), // 47
    '-' * 42,
    kv('TOTAL', r'$101,265.30'),
  ].join('\n');
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final mono = _monoPath();

  setUpAll(() async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
          (_) async => null,
        );
    if (mono != null) {
      final bytes = File(mono).readAsBytesSync();
      final loader = FontLoader('monospace')
        ..addFont(Future.value(ByteData.sublistView(bytes)));
      await loader.load();
    }
  });

  setUp(() => Prefs.instance.lang = 'en');

  Future<void> expectNoWrappedRows(WidgetTester tester, String text) async {
    final finder = find.text(text);
    expect(finder, findsOneWidget);
    final box = tester.getSize(finder);
    final wrapped = <String>[];
    for (final row in text.split('\n')) {
      final tp = TextPainter(
        text: TextSpan(text: row, style: T.receipt()),
        textDirection: TextDirection.ltr,
      )..layout();
      if (tp.width > box.width + .5) {
        wrapped.add(
          '${row.length} cols = ${tp.width.toStringAsFixed(0)} px > '
          '${box.width.toStringAsFixed(0)} px: "$row"',
        );
      }
      tp.dispose();
    }
    expect(wrapped, isEmpty, reason: wrapped.join('\n'));
  }

  for (final (name, screen) in [
    ('bill preview', (String t) => BillPreviewScreen(checkId: 1, text: t)),
    ('receipt', (String t) => ReceiptScreen(checkId: 1, text: t)),
  ]) {
    for (final (size, dpr) in [
      (const Size(1920, 1200), 1.5),
      (const Size(1280, 800), 1.0),
    ]) {
      testWidgets('$name: a 47-col server row stays on one line '
          '[${size.width.toInt()}x${size.height.toInt()}]', (tester) async {
        expect(mono, isNotNull, reason: 'needs a 0.6em monospace TTF');
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = dpr;
        addTearDown(tester.view.reset);
        final text = _bill();
        await tester.pumpWidget(
          prefsScope(
            child: MaterialApp(theme: buildPosTheme(), home: screen(text)),
          ),
        );
        await tester.pump(const Duration(milliseconds: 100));
        await expectNoWrappedRows(tester, text);
      });
    }
  }
}
