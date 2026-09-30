import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/kiosk/kiosk_api.dart';
import 'package:pos_client/kiosk/kiosk_app.dart';
import 'package:pos_client/kiosk/kiosk_controller.dart';
import 'package:pos_client/kiosk/kiosk_i18n.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// A quick-serve store in memory: pairs with 123456, serves a tiny menu and
/// numbers orders #101, #102... (the guest keeps that number through payment).
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
    return const KioskConfig('Copper Lantern — Express', 'USD', [
      'fr',
      'en',
      'es',
      'de',
      'af',
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
    _item('fountain-soda', 'soft-drinks', 'Boisson gazeuse', 'Fountain Soda', [
      Variant('fountain-soda:small', 'Petit', 'Small', 245),
      Variant('fountain-soda:medium', 'Moyen', 'Medium', 295),
    ]),
    _item('lemonade', 'soft-drinks', 'Limonade maison', 'House Lemonade', [
      Variant('lemonade:regular', 'Standard', 'Regular', 395),
    ]),
  ];

  @override
  Future<List<Category>> categories() async => [
    Category('burgers', 'Burgers', 'Burgers', 0),
    Category('fries-sides', 'Frites', 'Fries & Sides', 1),
    Category('beer-wine', 'Bières et vins', 'Beer & Wine', 2),
    Category('soft-drinks', 'Boissons', 'Soft Drinks', 3),
  ];

  /// What the store suggests on the way to the cart (none unless a test says).
  static List<KioskUpsellRow> offers = const [];
  static final asked = <List<Map<String, dynamic>>>[];
  static bool upsellFails = false;

  @override
  Future<List<KioskUpsellRow>> upsell(List<Map<String, dynamic>> lines) async {
    asked.add(lines);
    if (upsellFails) throw const KioskApiException(500, null, 'down');
    return offers;
  }

  @override
  Future<KioskOrderResult> placeOrder(
    String mode,
    List<Map<String, dynamic>> lines, {
    String? lang,
  }) async {
    placed.add({'mode': mode, 'lines': lines, 'lang': lang});
    final alcohol = lines.any((l) => l['itemId'] == 'north-ipa');
    final n = next++;
    // the store prints the guest's ticket, numbered #101...
    return KioskOrderResult(n, 0, alcohol, displayNumber: '#$n', ticket: true);
  }
}

void main() {
  setUp(() {
    _FakeStore.placed.clear();
    _FakeStore.next = 101;
    _FakeStore.offers = const [];
    _FakeStore.asked.clear();
    _FakeStore.upsellFails = false;
  });

  KioskController controller({Duration idle = const Duration(seconds: 90)}) =>
      KioskController(
        discover: () async => 'http://10.0.0.5:8080',
        apiFactory: (u, t) => _FakeStore(u, token: t),
        idleTimeout: idle,
        doneFor: const Duration(seconds: 5),
      );

  Future<void> pump(
    WidgetTester tester,
    KioskController c, {
    Size size = const Size(1080, 1920),
  }) async {
    tester.view.physicalSize = size;
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
      expect(find.text('#101'), findsOneWidget);
      expect(
        _FakeStore.placed.single['lang'],
        'es',
        reason: 'the ticket prints in Spanish',
      );
      expect(
        find.text('Lleve su ticket al mostrador para pagar.'),
        findsOneWidget,
      );

      // back to welcome on its own, in the store's language, the cart empty
      await tester.pump(const Duration(seconds: 6));
      await tester.pumpAndSettle();
      expect(c.stage, KioskStage.welcome);
      expect(c.cart, isEmpty);
      expect(c.lang, 'fr');
    },
  );

  testWidgets('Afrikaans: its own button, its words, English menu names', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues({
      'kiosk.storeUrl': 'http://10.0.0.5:8080',
      'kiosk.token': 'kiosk-token',
    });
    final c = controller();
    await pump(tester, c);
    expect(find.text('Afrikaans'), findsOneWidget);
    await tester.tap(find.byKey(const Key('kiosk-lang-af')));
    await tester.pumpAndSettle();
    expect(find.text('Raak om te bestel'), findsOneWidget);
    await tester.tap(find.byKey(const Key('kiosk-welcome')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('kiosk-take-out')));
    await tester.pumpAndSettle();
    // no Afrikaans name in the menu: the English one
    expect(find.text('Copper Lantern Burger'), findsOneWidget);
    // money stays North American
    expect(find.textContaining('\$11.95'), findsWidgets);
    await tester.tap(find.byKey(const Key('kiosk-item-lantern-burger')));
    await tester.pumpAndSettle();
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('kiosk-view-order')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('kiosk-place-order')));
    await tester.pumpAndSettle();
    expect(_FakeStore.placed.single['lang'], 'af');
    await tester.pump(const Duration(seconds: 6));
    await tester.pumpAndSettle();
  });

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

  group('"Add a drink?"', () {
    // the "Burger added" note sits over My order for a moment
    Future<void> waitOutAdded(WidgetTester tester) async {
      await tester.pump(const Duration(seconds: 2));
      await tester.pumpAndSettle();
    }

    Future<KioskController> toMenuWithBurger(
      WidgetTester tester, {
      String lang = 'en',
      Size size = const Size(800, 1280),
    }) async {
      SharedPreferences.setMockInitialValues({
        'kiosk.storeUrl': 'http://10.0.0.5:8080',
        'kiosk.token': 'kiosk-token',
      });
      _FakeStore.offers = const [
        KioskUpsellRow('drink', 'soft-drinks', [
          'fountain-soda',
          'lemonade',
          'gone-drink', // not on this kiosk's menu: left out
        ]),
        KioskUpsellRow('side', 'fries-sides', ['late-fries']),
      ];
      final c = controller();
      await pump(tester, c, size: size);
      await tester.tap(find.byKey(Key('kiosk-lang-$lang')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('kiosk-welcome')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('kiosk-take-out')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('kiosk-item-lantern-burger')));
      await tester.pumpAndSettle();
      await waitOutAdded(tester);
      await tester.tap(find.byKey(const Key('kiosk-view-order')));
      await tester.pumpAndSettle();
      return c;
    }

    testWidgets('No thanks goes on to the cart, and it never shows again', (
      tester,
    ) async {
      final c = await toMenuWithBurger(tester);
      expect(c.stage, KioskStage.upsell);
      // the store got the cart and chose the rows
      expect(_FakeStore.asked.single.single['itemId'], 'lantern-burger');
      expect(find.text('Add a drink?'), findsOneWidget);
      expect(find.text('Add fries?'), findsOneWidget);
      expect(find.byKey(const Key('kiosk-item-fountain-soda')), findsOneWidget);
      expect(find.byKey(const Key('kiosk-item-lemonade')), findsOneWidget);
      expect(find.text('\$2.45'), findsOneWidget);

      await tester.tap(find.byKey(const Key('kiosk-upsell-skip')));
      await tester.pumpAndSettle();
      expect(c.stage, KioskStage.cart);
      expect(c.itemCount, 1);

      // back to the menu and to the cart again: straight to the cart
      await tester.tap(find.byTooltip('Back'));
      await tester.pumpAndSettle();
      expect(c.stage, KioskStage.menu);
      await tester.tap(find.byKey(const Key('kiosk-view-order')));
      await tester.pumpAndSettle();
      expect(c.stage, KioskStage.cart);
      expect(_FakeStore.asked.length, 1);

      // a new guest gets it again
      await tester.tap(find.byKey(const Key('kiosk-place-order')));
      await tester.pumpAndSettle();
      c.finish();
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('kiosk-welcome')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('kiosk-dine-in')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('kiosk-item-lantern-burger')));
      await tester.pumpAndSettle();
      await waitOutAdded(tester);
      await tester.tap(find.byKey(const Key('kiosk-view-order')));
      await tester.pumpAndSettle();
      expect(c.stage, KioskStage.upsell);
      // the idle clock stops with the order
      c.cancelOrder();
      await tester.pumpAndSettle();
    });

    testWidgets('a tap adds the drink (its size picked) and goes to the cart', (
      tester,
    ) async {
      // the landscape kiosk too
      final c = await toMenuWithBurger(
        tester,
        lang: 'fr',
        size: const Size(1280, 800),
      );
      expect(find.text('Ajouter une boisson\u00a0?'), findsOneWidget);
      expect(find.text('Non merci, continuer'), findsOneWidget);
      await tester.tap(find.byKey(const Key('kiosk-item-fountain-soda')));
      await tester.pumpAndSettle();
      await tester.tap(
        find.byKey(const Key('kiosk-variant-fountain-soda:medium')),
      );
      await tester.pumpAndSettle();
      expect(c.stage, KioskStage.cart);
      expect(c.cart.map((l) => l.variant.id), [
        'lantern-burger:regular',
        'fountain-soda:medium',
      ]);
      // North American money in French too
      expect(find.text('\$14.90'), findsOneWidget);
      // no second offer on the way back
      await tester.tap(find.byTooltip('Retour'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('kiosk-view-order')));
      await tester.pumpAndSettle();
      expect(c.stage, KioskStage.cart);
      // the idle clock stops with the order
      c.cancelOrder();
      await tester.pumpAndSettle();
    });

    testWidgets('the store away: straight to the cart', (tester) async {
      SharedPreferences.setMockInitialValues({
        'kiosk.storeUrl': 'http://10.0.0.5:8080',
        'kiosk.token': 'kiosk-token',
      });
      _FakeStore.upsellFails = true;
      final c = controller();
      await pump(tester, c);
      await tester.tap(find.byKey(const Key('kiosk-welcome')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('kiosk-take-out')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('kiosk-item-lantern-burger')));
      await tester.pumpAndSettle();
      await waitOutAdded(tester);
      await tester.tap(find.byKey(const Key('kiosk-view-order')));
      await tester.pumpAndSettle();
      expect(c.stage, KioskStage.cart);
      // the idle clock stops with the order
      c.cancelOrder();
      await tester.pumpAndSettle();
    });
  });

  group('legal drinking age (Copper Lantern, Raleigh: 21)', () {
    test('the kiosk config carries the store\'s age; older stores: 21', () {
      final c = KioskConfig.fromJson({
        'storeName': 'Copper Lantern — Express',
        'currency': 'USD',
        'locales': ['en', 'fr'],
        'legalAge': 21,
      });
      expect(c.legalAge, 21);
      expect(c.currency, 'USD');
      expect(KioskConfig.fromJson({'storeName': 'x'}).legalAge, 21);
      expect(KioskConfig.fromJson({'storeName': 'x'}).currency, 'USD');
    });

    test('the ID note names the age in every language', () {
      expect(
        const KioskText('en').idNote(21),
        'Alcohol — 21+ only. Staff will check ID at the counter.',
      );
      for (final lang in ['fr', 'es', 'de', 'af']) {
        expect(KioskText(lang).idNote(21), contains('21'), reason: lang);
      }
    });
  });
}
