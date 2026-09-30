import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/widgets/tax_rows.dart';

/// North Carolina's sales tax and Wake County's prepared food tax, added on
/// top of pre-tax prices: parsed from the server's check, labelled per
/// language, and shown as subtotal + one row per tax.
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
    expect(c.taxes.map((t) => t.code), ['NC_SALES', 'WAKE_FOOD']);
    expect(c.taxes.map((t) => t.amountCents), [432, 64]);
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
    final nc = _check().taxes[0];
    expect(const L(true).taxLine(nc), 'NC sales tax 6.75%');
    expect(
      const L(true).taxLine(_check().taxes[1]),
      'Wake prepared food tax 1%',
    );
    expect(const L(false).taxLine(nc), 'NC sales tax 6,75\u00A0%');
  });

  testWidgets('tax rows show subtotal and the two NC taxes', (tester) async {
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
    expect(find.text('\$64.00'), findsOneWidget);
    expect(find.text('NC sales tax 6.75%'), findsOneWidget);
    expect(find.text('\$4.32'), findsOneWidget);
    expect(find.text('Wake prepared food tax 1%'), findsOneWidget);
    expect(find.text('\$0.64'), findsOneWidget);
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
