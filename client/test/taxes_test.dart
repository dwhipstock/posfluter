import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/widgets/tax_rows.dart';

/// GST / QST added on top of pre-tax prices: parsed from the server's check,
/// labelled per language, and shown as subtotal + one row per tax.
Check _check() => Check.fromJson(
  jsonDecode(
    File(
      '${Directory.current.path}/test/screenshots/fixtures/check.json',
    ).readAsStringSync(),
  ),
);

void main() {
  test('check carries the pre-tax subtotal and each tax; they add up', () {
    final c = _check();
    expect(c.subtotalCents, 6400);
    expect(c.taxes.map((t) => t.code), ['GST', 'QST']);
    expect(c.taxes.map((t) => t.amountCents), [320, 638]);
    expect(
      c.subtotalCents + c.taxes.fold(0, (s, t) => s + t.amountCents),
      c.grandTotalCents,
    );
  });

  test(
    'a check without tax fields still parses (no taxes, subtotal = total)',
    () {
      final j =
          jsonDecode(
                File(
                  '${Directory.current.path}/test/screenshots/fixtures/check.json',
                ).readAsStringSync(),
              )
              as Map<String, dynamic>;
      j.remove('taxes');
      j.remove('subtotalCents');
      final c = Check.fromJson(j);
      expect(c.taxes, isEmpty);
      expect(c.subtotalCents, c.grandTotalCents);
    },
  );

  test('tax labels follow the language and its decimal mark', () {
    final qst = _check().taxes[1];
    expect(const L(true).taxLine(_check().taxes[0]), 'GST 5%');
    expect(const L(true).taxLine(qst), 'QST 9.975%');
    expect(const L(false).taxLine(qst), 'TVQ 9,975 %');
  });

  testWidgets('tax rows show subtotal, GST and QST', (tester) async {
    final c = _check();
    Prefs.instance.lang = 'en';
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: TaxRows(subtotalCents: c.subtotalCents, taxes: c.taxes),
        ),
      ),
    );
    expect(find.text('Subtotal'), findsOneWidget);
    expect(find.text('\$64'), findsOneWidget);
    expect(find.text('GST 5%'), findsOneWidget);
    expect(find.text('\$3.20'), findsOneWidget);
    expect(find.text('QST 9.975%'), findsOneWidget);
    expect(find.text('\$6.38'), findsOneWidget);
  });

  testWidgets('no taxes → nothing shown', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(body: TaxRows(subtotalCents: 100, taxes: [])),
      ),
    );
    expect(find.text('Subtotal'), findsNothing);
  });
}
