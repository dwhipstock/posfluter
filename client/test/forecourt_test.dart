import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/forecourt/food_panel.dart';
import 'package:pos_client/forecourt/forecourt_i18n.dart';
import 'package:pos_client/forecourt/pump_grid.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/retail/sp_theme.dart';

/// The gas station's counter logic: reading the store's forecourt view, what
/// a pump tile says, the poll going offline, fuel lines and deals on a sale,
/// and the cup size / flavour / add-on picker.
void main() {
  final fx =
      jsonDecode(
            File('test/screenshots/fixtures/pronghorn.json').readAsStringSync(),
          )
          as Map<String, dynamic>;
  const f = F('en');

  test('the forecourt view reads every pump and what waits to be paid', () {
    final fc = Forecourt.fromJson(fx['forecourt']);
    expect(fc.online, isTrue);
    expect(fc.pumps.length, 8);
    expect(
      fc.grades.map((g) => g.code),
      containsAll(['REG', 'MID', 'PRE', 'DSL']),
    );
    final p5 = fc.pumps.firstWhere((p) => p.pump == 5);
    expect(p5.unpaid, isNotEmpty, reason: 'a postpay sale waits on pump 5');
    final p8 = fc.pumps.firstWhere((p) => p.pump == 8);
    expect(p8.prepay?.status, 'AUTHORISED');
    expect(
      fc.pumps.firstWhere((p) => p.pump == 7).change?.refundCents,
      greaterThan(0),
    );
  });

  test('a tile shows the most urgent state first', () {
    PumpLook look(Map<String, dynamic> j) =>
        PumpLook.of(PumpInfo.fromJson({'pump': 1, ...j}), f);
    expect(look({'state': 'OFFLINE'}).label, 'OFFLINE');
    expect(look({'state': 'EMERGENCY_STOP'}).color, PumpColors.stopped);
    expect(
      look({'state': 'FUELLING', 'live': true}).color,
      PumpColors.fuelling,
    );
    final payable = look({
      'state': 'IDLE',
      'payable': [
        {
          'trxId': 'T-1',
          'grade': 'REG',
          'amountCents': 100,
          'volumeMilli': 345,
          'priceMills': 2899,
        },
      ],
    });
    expect(payable.label, 'PAY');
    expect(payable.blink, isTrue);
    // on a sale already: not "PAY" any more
    final taken = look({
      'state': 'IDLE',
      'payable': [
        {'trxId': 'T-1', 'grade': 'REG', 'amountCents': 100, 'saleId': 4},
      ],
    });
    expect(taken.label, 'READY');
    expect(look({'state': 'CALLING'}).blink, isTrue);
  });

  test(
    'a failed poll shows every pump offline; the next one recovers',
    () async {
      var fail = true;
      final c = ForecourtController(
        fetch: () async {
          if (fail) throw const SocketException('refused');
          return Forecourt.fromJson(fx['forecourt']);
        },
      );
      await c.refresh();
      expect(c.state.online, isFalse);
      expect(c.state.pumps.length, 8);
      expect(c.state.pumps.every((p) => p.offline), isTrue);
      fail = false;
      await c.refresh();
      expect(c.state.online, isTrue);
      c.dispose();
    },
  );

  test('fuel lines and the deals on a sale', () {
    final sale = Check.fromJson(fx['sale']);
    final fuel = sale.lines.where((l) => l.fuel != null).toList();
    expect(fuel.map((l) => l.fuel!.mode), containsAll(['POSTPAY', 'PREPAY']));
    expect(fuel.every((l) => !l.taxable), isTrue);
    expect(
      sale.discounts.map((d) => d.code),
      containsAll(['energy-2for5', 'hotdog-fountain', 'coffee-fuel']),
    );
    expect(gallons(10052), '10.052');
    expect(pricePerGallon(3299), '\$3.299');
  });

  testWidgets('the picker rings a size, a flavour and paid add-ons', (
    tester,
  ) async {
    final items = (fx['items'] as List)
        .map((j) => Item.fromJson(j as Map<String, dynamic>))
        .toList();
    final slush = items.firstWhere((i) => i.id == 'ph-frozen-slush');
    final nachos = items.firstWhere(
      (i) => i.id == 'ph-nachos-with-pump-cheese',
    );
    final hotDog = items.firstWhere((i) => i.id == 'ph-hot-dog');
    List<FoodPick>? picked;
    late BuildContext ctx;
    await tester.pumpWidget(
      prefsScope(
        child: MaterialApp(
          theme: buildPronghornTheme(),
          home: Builder(
            builder: (c) {
              ctx = c;
              return const SizedBox();
            },
          ),
        ),
      ),
    );
    // a single-size dish comes straight back
    expect(
      (await FoodPicker.pick(ctx, hotDog, items))!.single.item.id,
      'ph-hot-dog',
    );

    final pending = FoodPicker.pick(ctx, slush, items).then((v) => picked = v);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('size-Large 44 oz')));
    await tester.tap(find.byKey(const Key('flavour-Mango')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('food-add')));
    await tester.pumpAndSettle();
    await pending;
    expect(picked!.single.variant.labelEn, 'Large 44 oz');
    expect(picked!.single.note, 'Mango');

    final pending2 = FoodPicker.pick(
      ctx,
      nachos,
      items,
    ).then((v) => picked = v);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('addon-ph-add-chili')));
    await tester.pump();
    expect(
      find.textContaining('\$4.48'),
      findsOneWidget,
      reason: 'nachos 3.49 + chili 0.99',
    );
    await tester.tap(find.byKey(const Key('food-add')));
    await tester.pumpAndSettle();
    await pending2;
    expect(picked!.map((p) => p.item.id), [
      'ph-nachos-with-pump-cheese',
      'ph-add-chili',
    ]);
  });
}
