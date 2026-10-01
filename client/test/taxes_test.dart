import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/widgets/tax_rows.dart';

/// North Carolina's sales tax (7.25%) and Wake County's prepared food tax
/// (1%), added on top of pre-tax prices: parsed from the server's check,
/// labelled per language, and shown to guests as ONE "Tax (8.25%)" line when
/// the store says so (`taxDisplay: combined`), else one row per tax.
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
    expect(c.taxes.map((t) => t.amountCents), [464, 64]);
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
    expect(const L(true).taxLine(nc), 'NC sales tax 7.25%');
    expect(
      const L(true).taxLine(_check().taxes[1]),
      'Wake prepared food tax 1%',
    );
    expect(const L(false).taxLine(nc), 'NC sales tax 7.25%');
  });

  testWidgets('an itemising store shows subtotal and the two NC taxes', (
    tester,
  ) async {
    final c = _check();
    Prefs.instance.lang = 'en';
    StoreProfile.current = StoreProfile.pub;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: TaxRows(subtotalCents: c.subtotalCents, taxes: c.taxes),
        ),
      ),
    );
    expect(find.text('Subtotal'), findsOneWidget);
    expect(find.text('\$64.00'), findsOneWidget);
    expect(find.text('NC sales tax 7.25%'), findsOneWidget);
    expect(find.text('\$4.64'), findsOneWidget);
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

  group('one combined line for guests (Copper Lantern)', () {
    const combined = StoreProfile(taxCombined: true);
    tearDown(() => StoreProfile.current = StoreProfile.pub);

    test('/health taxDisplay=combined turns it on; absent = itemised', () {
      expect(
        StoreProfile.fromHealth({'taxDisplay': 'combined'}).taxCombined,
        isTrue,
      );
      expect(StoreProfile.fromHealth({}).taxCombined, isFalse);
      expect(
        StoreProfile.fromHealth({
          'taxDisplay': 'combined',
          'taxLabel': 'Sales tax',
        }).taxLabel,
        'Sales tax',
      );
    });

    test('rates add up exactly, amounts add up to the cent', () {
      expect(TaxLine.sumRates(['7.25', '1']), '8.25');
      expect(TaxLine.sumRates(['5', '9.975']), '14.975');
      expect(TaxLine.sumRates(['0.1', '0.2']), '0.3');
      expect(TaxLine.sumRates(['6']), '6');
      final c = _check();
      final g = TaxLine.forGuests(c.taxes, profile: combined);
      expect(g, hasLength(1));
      expect(g.single.isCombined, isTrue);
      expect(g.single.ratePercent, '8.25');
      expect(g.single.amountCents, 528);
      expect(c.subtotalCents + g.single.amountCents, c.grandTotalCents);
      // itemising stores and a single tax are left as they are
      expect(TaxLine.forGuests(c.taxes, profile: StoreProfile.pub), c.taxes);
      expect(TaxLine.forGuests([c.taxes.first], profile: combined), [
        c.taxes.first,
      ]);
    });

    test('labelled "Tax (8.25%)" in every language', () {
      final t = TaxLine.forGuests(_check().taxes, profile: combined).single;
      expect(const L(true).taxLine(t), 'Tax (8.25%)');
      expect(const L(false).taxLine(t), 'Taxes (8.25%)');
      expect(const L.forLang('es').taxLine(t), 'Impuesto (8.25%)');
      expect(const L.forLang('de').taxLine(t), 'Steuer (8.25%)');
      expect(const L.forLang('af').taxLine(t), 'Belasting (8.25%)');
    });

    testWidgets('the check / pay screens show subtotal + one tax line', (
      tester,
    ) async {
      final c = _check();
      Prefs.instance.lang = 'en';
      StoreProfile.current = combined;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: TaxRows(subtotalCents: c.subtotalCents, taxes: c.taxes),
          ),
        ),
      );
      expect(find.text('Subtotal'), findsOneWidget);
      expect(find.text('Tax (8.25%)'), findsOneWidget);
      expect(find.text('\$5.28'), findsOneWidget);
      expect(find.textContaining('NC sales tax'), findsNothing);
      expect(find.textContaining('Wake'), findsNothing);
    });
  });
}
