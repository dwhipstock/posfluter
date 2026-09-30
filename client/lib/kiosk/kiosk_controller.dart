import 'dart:async';

import 'package:flutter/foundation.dart' show ChangeNotifier;
import 'package:shared_preferences/shared_preferences.dart';

import '../api.dart' show Item, Category, Variant;
import 'kiosk_api.dart';

enum KioskStage {
  /// Staff: find the store and pair this kiosk.
  setup,

  /// "Touch to order" and the language flags.
  welcome,

  /// Dine in or take out.
  mode,

  /// Categories and photo tiles.
  menu,

  /// "Add a drink?": the store's suggestions, once per order, on the way to
  /// the cart.
  upsell,

  /// What is in the order, and Place order.
  cart,

  /// "Your order number is #101. Take your ticket to the counter."
  done,
}

class KioskLine {
  final Item item;
  final Variant variant;
  int qty;
  KioskLine(this.item, this.variant, this.qty);
  int get totalCents => variant.priceCents * qty;
}

/// One row of the "Add a drink?" step: the store's reason ("drink", "side",
/// "dessert"), its category, and the items the kiosk has for it.
class KioskOffer {
  final String reason, categoryId;
  final List<Item> items;
  const KioskOffer(this.reason, this.categoryId, this.items);
}

/// The kiosk's brain, UI-free (tests drive it with a fake API): pair with the
/// store once, then loop welcome -> dine in / take out -> menu -> cart ->
/// Place order -> the number -> welcome. An idle customer's cart is cleared.
class KioskController extends ChangeNotifier {
  final KioskApi Function(String baseUrl, String? token) apiFactory;
  final Future<String?> Function() discover;

  /// No touch for this long mid-order: the cart is cleared, back to welcome.
  final Duration idleTimeout;

  /// How long the order number stays up before the next customer.
  final Duration doneFor;

  KioskController({
    required this.discover,
    KioskApi Function(String baseUrl, String? token)? apiFactory,
    this.idleTimeout = const Duration(seconds: 90),
    this.doneFor = const Duration(seconds: 10),
  }) : apiFactory = apiFactory ?? ((u, t) => KioskApi(u, token: t));

  static const _kUrl = 'kiosk.storeUrl';
  static const _kToken = 'kiosk.token';

  KioskStage stage = KioskStage.setup;
  String? storeUrl, message;
  String storeName = '';
  String currency = 'USD';

  /// The store's legal drinking age, named on the ID note (21 in the US).
  int legalAge = 21;
  List<String> locales = const ['en', 'fr', 'es', 'de', 'af'];
  String lang = 'en';
  bool busy = false, offline = false;

  List<Item> items = const [];
  List<Category> categories = const [];
  String? category;

  /// DINE_IN | TAKE_OUT
  String? mode;
  final List<KioskLine> cart = [];
  KioskOrderResult? result;

  /// The "Add a drink?" rows being shown; asked for once per order.
  List<KioskOffer> offers = const [];
  bool _offered = false;

  KioskApi? _api;
  KioskApi? get api => _api;
  Timer? _idle, _done;
  bool _disposed = false;

  void _changed() {
    if (!_disposed) notifyListeners();
  }

  int get itemCount => cart.fold(0, (n, l) => n + l.qty);
  int get subtotalCents => cart.fold(0, (n, l) => n + l.totalCents);
  bool get hasAlcohol => cart.any((l) => l.item.isAlcohol);

  /// Categories that have something on sale, in the store's order.
  List<Category> get shownCategories => [
    for (final c in categories)
      if (items.any((i) => i.category == c.id && i.active)) c,
  ];

  List<Item> get shownItems => [
    for (final i in items)
      if (i.active && i.variants.isNotEmpty && i.category == category) i,
  ];

  // ------------------------------------------------------------ setup

  /// App start: reuse the saved pairing, else look for the store.
  Future<void> start() async {
    final p = await SharedPreferences.getInstance();
    storeUrl = p.getString(_kUrl);
    final token = p.getString(_kToken);
    if (storeUrl != null && token != null) {
      _api = apiFactory(storeUrl!, token);
      if (await _connect()) return;
      if (stage == KioskStage.welcome) return; // offline, but paired
    }
    await find();
  }

  Future<void> find() async {
    stage = KioskStage.setup;
    message = null;
    _changed();
    final url = storeUrl ?? await discover();
    storeUrl = url;
    message = url == null ? 'not_found' : null;
    _changed();
  }

  /// [code] is the 6 digits the POS shows (Orders → Pair a kiosk).
  Future<bool> pair(String code, {String? address}) async {
    final url = _normalize(address) ?? storeUrl;
    if (url == null) {
      message = 'not_found';
      _changed();
      return false;
    }
    storeUrl = url;
    busy = true;
    message = null;
    _changed();
    try {
      final api = apiFactory(url, null);
      storeName = await api.pair(code);
      _api = api;
      final p = await SharedPreferences.getInstance();
      await p.setString(_kUrl, url);
      await p.setString(_kToken, api.token!);
      busy = false;
      return await _connect();
    } catch (_) {
      busy = false;
      message = 'pair_failed';
      _changed();
      return false;
    }
  }

  static String? _normalize(String? raw) {
    var s = raw?.trim() ?? '';
    if (s.isEmpty) return null;
    if (!s.startsWith('http')) s = 'http://$s';
    if (s.endsWith('/')) s = s.substring(0, s.length - 1);
    final u = Uri.tryParse(s);
    if (u == null || u.host.isEmpty) return null;
    return u.hasPort ? s : '$s:8080';
  }

  /// The pairing still holds and the menu loads: the welcome screen.
  Future<bool> _connect() async {
    final api = _api;
    if (api == null) return false;
    try {
      final c = await api.config();
      storeName = c.storeName;
      currency = c.currency;
      legalAge = c.legalAge;
      if (c.locales.isNotEmpty) locales = c.locales;
      lang = locales.first;
      await _loadMenu();
      offline = false;
      _toWelcome();
      return true;
    } on KioskApiException catch (e) {
      if (e.notPaired) {
        final p = await SharedPreferences.getInstance();
        await p.remove(_kToken);
        _api = null;
        stage = KioskStage.setup;
        message = 'pair_failed';
        _changed();
      }
      return false;
    } catch (_) {
      // the store is off the Wi-Fi: say so on the welcome screen, retry on touch
      offline = true;
      _toWelcome();
      return false;
    }
  }

  Future<void> _loadMenu() async {
    final api = _api!;
    final loaded = await Future.wait([api.items(), api.categories()]);
    items = loaded[0] as List<Item>;
    categories = (loaded[1] as List<Category>)
      ..sort((a, b) => a.sortOrder.compareTo(b.sortOrder));
  }

  // ------------------------------------------------------------ the order

  void _toWelcome() {
    _idle?.cancel();
    _done?.cancel();
    cart.clear();
    mode = null;
    result = null;
    offers = const [];
    _offered = false;
    message = null;
    busy = false;
    lang = locales.isEmpty ? 'en' : locales.first;
    stage = KioskStage.welcome;
    _changed();
  }

  /// Any touch while ordering: the idle clock starts again.
  void touch() {
    if (stage == KioskStage.mode ||
        stage == KioskStage.menu ||
        stage == KioskStage.upsell ||
        stage == KioskStage.cart) {
      _idle?.cancel();
      _idle = Timer(idleTimeout, _toWelcome);
    }
  }

  void setLang(String l) {
    lang = l;
    _changed();
  }

  Future<void> startOrder() async {
    if (offline) {
      // try the store again; the welcome screen keeps saying offline if not
      if (!await _connect()) return;
    } else {
      // pick up anything the counter took off sale since the last order
      try {
        await _loadMenu();
      } catch (_) {}
    }
    stage = KioskStage.mode;
    touch();
    _changed();
  }

  void chooseMode(String m) {
    mode = m;
    final shown = shownCategories;
    category = shown.isEmpty ? null : shown.first.id;
    stage = KioskStage.menu;
    touch();
    _changed();
  }

  void chooseCategory(String id) {
    category = id;
    _changed();
  }

  void add(Item item, Variant variant) {
    final existing = cart.where(
      (l) => l.item.id == item.id && l.variant.id == variant.id,
    );
    if (existing.isNotEmpty) {
      existing.first.qty++;
    } else {
      cart.add(KioskLine(item, variant, 1));
    }
    _changed();
  }

  void setQty(KioskLine line, int qty) {
    if (qty <= 0) {
      cart.remove(line);
    } else {
      line.qty = qty.clamp(1, 20);
    }
    if (cart.isEmpty && stage == KioskStage.cart) stage = KioskStage.menu;
    _changed();
  }

  List<Map<String, dynamic>> get _lines => [
    for (final l in cart)
      {'itemId': l.item.id, 'variantId': l.variant.id, 'qty': l.qty},
  ];

  /// To the cart. The first time in an order, the store is asked what to
  /// suggest ("Add a drink?"); with nothing to suggest, or the store slow or
  /// away, straight to the cart. Never twice in one order.
  Future<void> openCart() async {
    if (cart.isEmpty || busy) return;
    final api = _api;
    if (!_offered && api != null) {
      _offered = true;
      busy = true;
      _changed();
      var found = <KioskOffer>[];
      try {
        final byId = {for (final i in items) i.id: i};
        for (final r in await api.upsell(_lines)) {
          final shown = [
            for (final id in r.itemIds)
              if (byId[id] case final i? when i.active && i.variants.isNotEmpty)
                i,
          ];
          if (shown.isNotEmpty) {
            found.add(KioskOffer(r.reason, r.categoryId, shown));
          }
        }
      } catch (_) {
        found = [];
      }
      busy = false;
      // the guest went idle (or started over) while the store answered
      if (stage != KioskStage.menu || cart.isEmpty) {
        _changed();
        return;
      }
      if (found.isNotEmpty) {
        offers = found;
        stage = KioskStage.upsell;
        touch();
        _changed();
        return;
      }
    }
    stage = KioskStage.cart;
    _changed();
  }

  /// A suggestion tapped (its size picked): in the cart, and on to the cart.
  void addOffer(Item item, Variant variant) {
    add(item, variant);
    closeOffers();
  }

  /// No thanks: on to the cart; the step does not come back this order.
  void closeOffers() {
    offers = const [];
    stage = cart.isEmpty ? KioskStage.menu : KioskStage.cart;
    _changed();
  }

  void back() {
    stage = switch (stage) {
      KioskStage.cart => KioskStage.menu,
      KioskStage.upsell => KioskStage.menu,
      KioskStage.menu => KioskStage.mode,
      _ => KioskStage.welcome,
    };
    if (stage == KioskStage.welcome) {
      _toWelcome();
      return;
    }
    _changed();
  }

  void cancelOrder() => _toWelcome();

  /// Sends at once — no review, no confirmation. The number, then welcome.
  Future<void> placeOrder() async {
    final api = _api;
    if (api == null || cart.isEmpty || busy) return;
    busy = true;
    message = null;
    _changed();
    try {
      result = await api.placeOrder(mode ?? 'TAKE_OUT', _lines, lang: lang);
      busy = false;
      _idle?.cancel();
      cart.clear();
      stage = KioskStage.done;
      _done = Timer(doneFor, _toWelcome);
      _changed();
    } catch (_) {
      busy = false;
      message = 'send_failed';
      _changed();
    }
  }

  void finish() => _toWelcome();

  @override
  void dispose() {
    _disposed = true;
    _idle?.cancel();
    _done?.cancel();
    super.dispose();
  }
}
