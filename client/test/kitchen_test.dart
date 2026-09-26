import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/kitchen/kitchen_i18n.dart';

void main() {
  tearDown(() => StoreProfile.current = StoreProfile.pub);

  test('kitchen tickets are off unless the store says so', () {
    expect(StoreProfile.pub.kitchenPrinting, isFalse);
    expect(StoreProfile.fromHealth({'status': 'ok'}).kitchenPrinting, isFalse);
    expect(
      StoreProfile.fromHealth({'kitchenPrinting': 'yes'}).kitchenPrinting,
      isFalse,
    );
    final on = StoreProfile.fromHealth({'kitchenPrinting': true});
    expect(on.kitchenPrinting, isTrue);
    expect(KitchenApi.enabled, isFalse);
    StoreProfile.current = on;
    expect(KitchenApi.enabled, isTrue);
  });

  test('timer colours follow the store thresholds', () {
    const board = KdsBoard(warnMinutes: 10, lateMinutes: 20);
    expect(board.levelFor(0), 'ok');
    expect(board.levelFor(599), 'ok');
    expect(board.levelFor(600), 'warn');
    expect(board.levelFor(1199), 'warn');
    expect(board.levelFor(1200), 'late');
  });

  test('board items: ADD, voided and partly voided', () {
    final board = KdsBoard.fromJson({
      'cards': [
        {
          'key': '7:kitchen',
          'checkId': 7,
          'stationId': 'kitchen',
          'stationNameFr': 'Cuisine',
          'stationNameEn': 'Kitchen',
          'tableLabel': 'U-3',
          'serverName': 'Demo Server',
          'guests': 4,
          'elapsedSeconds': 125,
          'level': 'ok',
          'items': [
            {
              'lineId': 1,
              'qty': 2,
              'nameFr': 'Burger',
              'nameEn': 'Burger',
              'voidedQty': 1,
            },
            {
              'lineId': 2,
              'qty': 1,
              'nameFr': 'Ailes',
              'nameEn': 'Wings',
              'voided': true,
              'voidedQty': 1,
            },
            {
              'lineId': 3,
              'qty': 1,
              'nameFr': 'Bretzel',
              'nameEn': 'Pretzel',
              'add': true,
            },
          ],
        },
      ],
      'recent': [],
      'stations': [],
      'warnMinutes': 10,
      'lateMinutes': 20,
      'sound': true,
      'language': 'fr',
      'latestTicket': 3,
    });
    final items = board.cards.single.items;
    expect(items[0].remaining, 1);
    expect(items[1].voided, isTrue);
    expect(items[1].remaining, 1);
    expect(items[2].add, isTrue);
    expect(board.cards.single.guests, 4);
  });

  test('send state: nothing to send once everything is sent', () {
    const clean = KitchenCheckState(1, null, 0, 0, 3);
    expect(clean.hasChanges, isFalse);
    expect(const KitchenCheckState(1, null, 2, 0, 3).hasChanges, isTrue);
    expect(const KitchenCheckState(1, null, 0, 1, 3).hasChanges, isTrue);
  });

  test('kitchen strings exist in French, English and Spanish', () {
    for (final lang in ['fr', 'en', 'es']) {
      final k = K(lang);
      expect(k.printerOffline(2), contains('2'));
      expect(k.sendCount(3), contains('3'));
      expect(k.kitchen, isNotEmpty);
    }
    expect(const K('fr').voidTag, 'ANNULÉ');
    expect(const K('en').voidTag, 'VOID');
    expect(
      const K('en').printerOffline(2),
      'Kitchen printer offline — 2 tickets waiting',
    );
  });
}
