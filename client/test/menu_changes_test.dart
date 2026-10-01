import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/kiosk/kiosk_api.dart';
import 'package:pos_client/kiosk/kiosk_controller.dart';
import 'package:pos_client/kiosk/kiosk_i18n.dart';
import 'package:pos_client/menu_changes.dart';
import 'package:shared_preferences/shared_preferences.dart';

Item _item(String id, List<Variant> v, {bool active = true}) =>
    Item(id, id, id, '', '', 'food', 'IT', false, active, v, null);

/// A store whose menu the test changes under the kiosk.
class _Store extends KioskApi {
  _Store(super.baseUrl, {super.token});

  static List<Item> menu = [];
  static int version = 1;
  static Object? orderAnswer;
  static final placed = <List<Map<String, dynamic>>>[];
  static final orderIds = <String?>[];

  @override
  Future<KioskConfig> config() async =>
      const KioskConfig('Express', 'USD', ['en', 'fr']);
  @override
  Future<List<Item>> items() async => menu;
  @override
  Future<List<Category>> categories() async => [
    Category('food', 'Plats', 'Food', 0),
  ];
  @override
  Future<int?> menuVersion() async => version;
  @override
  Future<List<KioskUpsellRow>> upsell(List<Map<String, dynamic>> l) async =>
      const [];
  @override
  Future<KioskOrderResult> placeOrder(
    String mode,
    List<Map<String, dynamic>> lines, {
    String? lang,
    String? clientOrderId,
  }) async {
    placed.add(lines);
    orderIds.add(clientOrderId);
    final a = orderAnswer;
    if (a is KioskApiException) throw a;
    return a as KioskOrderResult;
  }
}

void main() {
  group('RejectedLine', () {
    test('reads the rejected array of a body, with names and price', () {
      final r = RejectedLine.listFrom({
        'rejected': [
          {
            'index': 1,
            'itemId': 'nachos',
            'variantId': 'nachos:regular',
            'code': 'price_changed',
            'priceCents': 1350,
            'nameEn': 'Nachos',
            'nameFr': 'Nachos maison',
            'names': {'es': 'Nachos caseros'},
          },
          {'index': 0, 'itemId': 'wings', 'code': 'item_unavailable'},
          'junk',
        ],
      });
      expect(r, hasLength(2));
      expect(r[0].isPriceChange, isTrue);
      expect(r[0].priceCents, 1350);
      expect(r[0].name('es'), 'Nachos caseros');
      expect(r[0].name('fr'), 'Nachos maison');
      expect(r[0].name('de'), 'Nachos');
      expect(r[1].code, RejectedLine.unavailable);
      expect(r[1].isPriceChange, isFalse);
      expect(RejectedLine.listFrom({'code': 'x'}), isEmpty);
      expect(RejectedLine.listFrom(null), isEmpty);
    });
  });

  group('checkAgainstMenu', () {
    test('gone, 86ed, size deleted, repriced, unchanged', () {
      final menu = [
        _item('burger', [Variant('burger:r', 'R', 'R', 1300)]),
        _item('fries', [Variant('fries:s', 'P', 'S', 400)]),
        _item('soda', [Variant('soda:s', 'P', 'S', 250)], active: false),
      ];
      final checks = checkAgainstMenu([
        (itemId: 'burger', variantId: 'burger:r', priceCents: 1195),
        (itemId: 'fries', variantId: 'fries:s', priceCents: 400),
        (itemId: 'fries', variantId: 'fries:l', priceCents: 600),
        (itemId: 'soda', variantId: 'soda:s', priceCents: 250),
        (itemId: 'wings', variantId: 'wings:r', priceCents: 900),
      ], menu);
      expect(checks.map((c) => c.fate), [
        LineFate.repriced,
        LineFate.ok,
        LineFate.unavailable,
        LineFate.unavailable,
        LineFate.unavailable,
      ]);
      expect(checks[0].variant!.priceCents, 1300);
    });
  });

  group('MenuVersionPoller', () {
    test(
      'the first answer is the baseline; a change fires; failures are quiet',
      () async {
        final answers = <int?>[3, 3, null, 4, 4];
        var calls = 0;
        var changes = 0;
        final p = MenuVersionPoller(
          fetch: () async {
            final a = answers[calls++];
            if (calls == 3) throw Exception('offline');
            return a;
          },
          onChange: () => changes++,
        );
        for (var i = 0; i < answers.length; i++) {
          await p.poll();
        }
        expect(changes, 1);
      },
    );
  });

  group('kiosk under a changing menu', () {
    late KioskController c;
    setUp(() async {
      SharedPreferences.setMockInitialValues({
        'kiosk.storeUrl': 'http://store:8080',
        'kiosk.token': 't',
      });
      _Store.menu = [
        _item('burger', [Variant('burger:r', 'R', 'Regular', 1195)]),
        _item('fries', [Variant('fries:s', 'P', 'Small', 395)]),
      ];
      _Store.version = 1;
      _Store.placed.clear();
      _Store.orderIds.clear();
      c = KioskController(
        discover: () async => null,
        apiFactory: (u, t) => _Store(u, token: t),
      );
      await c.start();
      await c.startOrder();
      c.chooseMode('TAKE_OUT');
      c.add(c.items[0], c.items[0].variants.first);
      c.add(c.items[1], c.items[1].variants.first);
    });
    tearDown(() => c.dispose());

    test(
      'a refresh drops the gone item and holds the new price for a confirm',
      () async {
        _Store.menu = [
          _item('burger', [Variant('burger:r', 'R', 'Regular', 1295)]),
        ];
        await c.refreshMenu();
        expect(c.cart.map((l) => l.item.id), ['burger']);
        expect(c.cart.single.variant.priceCents, 1295);
        expect(c.needsPriceConfirm, isTrue);
        expect(c.notices.map((n) => n.kind), ['repriced', 'removed']);
        // the order can't go until the guest accepts the new price
        _Store.orderAnswer = const KioskOrderResult(101, 1295, false);
        await c.placeOrder();
        expect(_Store.placed, isEmpty);
        c.acceptNewPrices();
        await c.placeOrder();
        expect(_Store.placed.single.single['expectedPriceCents'], 1295);
        expect(c.stage, KioskStage.done);
      },
    );

    test(
      'every line refused: gone ones leave, repriced ones wait, nothing placed',
      () async {
        _Store.orderAnswer = const KioskApiException(
          409,
          'lines_rejected',
          'all refused',
          rejected: [
            RejectedLine(
              index: 0,
              itemId: 'burger',
              variantId: 'burger:r',
              code: 'price_changed',
              priceCents: 1395,
              nameEn: 'burger',
            ),
            RejectedLine(
              index: 1,
              itemId: 'fries',
              variantId: 'fries:s',
              code: 'item_unavailable',
              nameEn: 'fries',
            ),
          ],
        );
        await c.placeOrder();
        expect(c.stage, isNot(KioskStage.done));
        expect(c.cart.single.item.id, 'burger');
        expect(c.cart.single.variant.priceCents, 1395);
        expect(c.cart.single.repriced, isTrue);
        expect(c.message, isNull);
        // the lines sent carried the price the guest saw
        expect(_Store.placed.single.map((l) => l['expectedPriceCents']), [
          1195,
          395,
        ]);
      },
    );

    test(
      'a retry of the same cart sends the same order id; a changed cart a new one',
      () async {
        _Store.orderAnswer = const KioskApiException(503, null, 'no answer');
        await c.placeOrder();
        await c.placeOrder();
        expect(c.message, 'send_failed');
        expect(_Store.orderIds, hasLength(2));
        expect(_Store.orderIds[0], isNotNull);
        expect(_Store.orderIds[1], _Store.orderIds[0]);
        // the guest adds a burger: a different order
        c.add(c.items[0], c.items[0].variants.first);
        _Store.orderAnswer = const KioskOrderResult(103, 2390, false);
        await c.placeOrder();
        expect(_Store.orderIds[2], isNot(_Store.orderIds[0]));
        expect(c.stage, KioskStage.done);
      },
    );

    test(
      'a partial order lists what was left out on the number screen',
      () async {
        _Store.orderAnswer = KioskOrderResult.fromJson({
          'orderNumber': 102,
          'totalCents': 1195,
          'rejected': [
            {
              'index': 1,
              'itemId': 'fries',
              'code': 'item_unavailable',
              'nameEn': 'Fries',
            },
          ],
        });
        await c.placeOrder();
        expect(c.stage, KioskStage.done);
        expect(c.result!.rejected.single.name('en'), 'Fries');
        expect(KioskText('fr').noLongerAvailable('Frites'), contains('Frites'));
      },
    );
  });
}
