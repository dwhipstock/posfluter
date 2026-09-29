import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/kiosk/kiosk_api.dart';
import 'package:pos_client/kiosk/kiosk_app.dart';
import 'package:pos_client/kiosk/kiosk_controller.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// A quick-serve store in memory: pairs with 123456, serves a tiny menu and
/// numbers orders from 101.
class _FakeStore extends KioskApi {
  _FakeStore(super.baseUrl, {super.token});

  static final placed = <Map<String, dynamic>>[];
  static int next = 101;

  @override
  Future<String> pair(String code, {String deviceName = ''}) async {
    if (code.trim() != '123456') {
      throw const KioskApiException(404, 'bad_pairing_code', 'no');
    }
    token = 'kiosk-token';
    return 'Copper Lantern — Express';
  }

  @override
  Future<KioskConfig> config() async {
    if (token != 'kiosk-token') {
      throw const KioskApiException(401, 'kiosk_not_paired', 'no');
    }
    return const KioskConfig('Copper Lantern — Express', 'CAD', [
      'fr',
      'en',
      'es',
      'de',
    ]);
  }

  static Item _item(
    String id,
    String cat,
    String fr,
    String en,
    List<Variant> v, {
    bool alcohol = false,
    Map<String, String> names = const {},
  }) => Item(
    id,
    fr,
    en,
    '',
    '',
    cat,
    id.substring(0, 2).toUpperCase(),
    alcohol,
    true,
    v,
    null,
    names: names,
  );

  @override
  Future<List<Item>> items() async => [
    _item(
      'lantern-burger',
      'burgers',
      'Burger de la Lanterne',
      'Copper Lantern Burger',
      [Variant('lantern-burger:regular', 'Standard', 'Regular', 1195)],
      names: {'es': 'Hamburguesa Copper Lantern'},
    ),
    _item('late-fries', 'fries-sides', 'Frites', 'Fries', [
      Variant('late-fries:small', 'Petit', 'Small', 395),
      Variant('late-fries:large', 'Grand', 'Large', 595),
    ]),
    _item('north-ipa', 'beer-wine', 'IPA du Nord', 'North Trail IPA', [
      Variant('north-ipa:16oz', 'Verre 16 oz', '16 oz glass', 775),
    ], alcohol: true),
  ];

  @override
  Future<List<Category>> categories() async => [
    Category('burgers', 'Burgers', 'Burgers', 0),
    Category('fries-sides', 'Frites', 'Fries & Sides', 1),
    Category('beer-wine', 'Bières et vins', 'Beer & Wine', 2),
  ];

  @override
  Future<KioskOrderResult> placeOrder(
    String mode,
    List<Map<String, dynamic>> lines,
  ) async {
    placed.add({'mode': mode, 'lines': lines});
    final alcohol = lines.any((l) => l['itemId'] == 'north-ipa');
    return KioskOrderResult(next++, 0, alcohol);
  }
}

void main() {
  setUp(() {
    _FakeStore.placed.clear();
    _FakeStore.next = 101;
  });

  KioskController controller({Duration idle = const Duration(seconds: 90)}) =>
      KioskController(
        discover: () async => 'http://10.0.0.5:8080',
        apiFactory: (u, t) => _FakeStore(u, token: t),
        idleTimeout: idle,
        doneFor: const Duration(seconds: 5),
      );

  Future<void> pump(WidgetTester tester, KioskController c) async {
    tester.view.physicalSize = const Size(1080, 1920);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(KioskApp(controller: c));
    await tester.pumpAndSettle();
  }

  testWidgets(
    'pair, then welcome → take out → menu → cart → the number → welcome',
    (tester) async {
      SharedPreferences.setMockInitialValues({});
      final c = controller();
      await pump(tester, c);
      expect(c.stage, KioskStage.setup);

      await tester.enterText(find.byKey(const Key('kiosk-code')), '123456');
      await tester.tap(find.byKey(const Key('kiosk-pair')));
      await tester.pumpAndSettle();
      expect(c.stage, KioskStage.welcome);
      expect(find.text('Touchez pour commander'), findsOneWidget);
      // the pairing is kept for the next start
      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getString('kiosk.token'), 'kiosk-token');

      // Spanish from the flags
      await tester.tap(find.byKey(const Key('kiosk-lang-es')));
      await tester.pumpAndSettle();
      expect(find.text('Toque para ordenar'), findsOneWidget);

      await tester.tap(find.byKey(const Key('kiosk-welcome')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('kiosk-take-out')));
      await tester.pumpAndSettle();
      expect(c.stage, KioskStage.menu);
      expect(find.text('Hamburguesa Copper Lantern'), findsOneWidget);

      // one tap adds a one-size item
      await tester.tap(find.byKey(const Key('kiosk-item-lantern-burger')));
      await tester.pumpAndSettle();
      // a size to pick
      await tester.tap(find.byKey(const Key('kiosk-cat-fries-sides')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('kiosk-item-late-fries')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('kiosk-variant-late-fries:large')));
      await tester.pumpAndSettle();
      // alcohol: the ID note, never a block
      await tester.tap(find.byKey(const Key('kiosk-cat-beer-wine')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('kiosk-item-north-ipa')));
      await tester.pumpAndSettle();
      expect(
        find.textContaining('verificará su identificación'),
        findsOneWidget,
      );
      await tester.tap(find.byKey(const Key('kiosk-variant-north-ipa:16oz')));
      await tester.pumpAndSettle();
      expect(c.itemCount, 3);

      await tester.tap(find.byKey(const Key('kiosk-view-order')));
      await tester.pumpAndSettle();
      expect(c.stage, KioskStage.cart);
      await tester.tap(find.byKey(const Key('kiosk-place-order')));
      await tester.pumpAndSettle();

      // sent at once: no review, no confirmation
      expect(_FakeStore.placed.single['mode'], 'TAKE_OUT');
      expect((_FakeStore.placed.single['lines'] as List).length, 3);
      expect(c.stage, KioskStage.done);
      expect(find.text('101'), findsOneWidget);
      expect(find.text('Por favor pague en el mostrador.'), findsOneWidget);

      // back to welcome on its own, in the store's language, the cart empty
      await tester.pump(const Duration(seconds: 6));
      await tester.pumpAndSettle();
      expect(c.stage, KioskStage.welcome);
      expect(c.cart, isEmpty);
      expect(c.lang, 'fr');
    },
  );

  testWidgets('an idle customer\'s cart is cleared', (tester) async {
    SharedPreferences.setMockInitialValues({
      'kiosk.storeUrl': 'http://10.0.0.5:8080',
      'kiosk.token': 'kiosk-token',
    });
    final c = controller(idle: const Duration(seconds: 30));
    await pump(tester, c);
    expect(c.stage, KioskStage.welcome, reason: 'the saved pairing is reused');
    await tester.tap(find.byKey(const Key('kiosk-welcome')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('kiosk-dine-in')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('kiosk-item-lantern-burger')));
    await tester.pumpAndSettle();
    expect(c.itemCount, 1);
    await tester.pump(const Duration(seconds: 31));
    await tester.pumpAndSettle();
    expect(c.stage, KioskStage.welcome);
    expect(c.cart, isEmpty);
    expect(_FakeStore.placed, isEmpty);
  });
}
