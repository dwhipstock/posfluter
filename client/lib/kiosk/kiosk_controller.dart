import 'dart:async';
import 'dart:convert' show jsonEncode;
import 'dart:math' show Random;

import 'package:flutter/foundation.dart' show ChangeNotifier;
import 'package:shared_preferences/shared_preferences.dart';

import '../api.dart' show Item, Category, Variant;
import '../menu_changes.dart';
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
  Item item;
  Variant variant;
  int qty;

  /// The price changed since the guest added it: shown, and confirmed before
  /// the order can go.
  bool repriced = false;
  KioskLine(this.item, this.variant, this.qty);
  int get totalCents => variant.priceCents * qty;
}

/// Something the menu change did to the cart, told to the guest.
class KioskNotice {
  /// removed | repriced
  final String kind;
  final Item? item;
  final String fallbackName;
  final int? priceCents;
  const KioskNotice(this.kind, this.item, this.fallbackName, [this.priceCents]);
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

  /// What a menu change did to this cart (removed items, new prices).
  final List<KioskNotice> notices = [];

  /// A line's price changed: the guest confirms before placing the order.
  bool get needsPriceConfirm => cart.any((l) => l.repriced);

  /// Polls the store's menu version while an order is open.
  MenuVersionPoller? _menuPoll;

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
    _menuPoll?.pause();
    notices.clear();
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
    // the menu can change mid-order (the counter 86es something, the
    // manager portal edits a price): keep the menu and the cart current
    final api = _api;
    if (api != null) {
      _menuPoll ??= MenuVersionPoller(
        fetch: api.menuVersion,
        onChange: refreshMenu,
      );
      _menuPoll!.start();
    }
    _changed();
  }

  /// Reload the menu and line the cart up with it: gone items leave the cart
  /// (with a notice), new prices are shown and need a confirm; the upsell
  /// only offers what is still on sale.
  Future<void> refreshMenu() async {
    if (_api == null) return;
    try {
      await _loadMenu();
    } catch (_) {
      return; // offline: the next poll tries again
    }
    applyMenuToCart();
    if (category != null && !shownCategories.any((c) => c.id == category)) {
      final shown = shownCategories;
      category = shown.isEmpty ? null : shown.first.id;
    }
    _changed();
  }

  /// The cart against [items] (just loaded). Public for tests.
  void applyMenuToCart() {
    final checks = checkAgainstMenu([
      for (final l in cart)
        (
          itemId: l.item.id,
          variantId: l.variant.id,
          priceCents: l.variant.priceCents,
        ),
    ], items);
    final gone = <KioskLine>[];
    for (var i = 0; i < cart.length; i++) {
      final l = cart[i];
      final c = checks[i];
      switch (c.fate) {
        case LineFate.unavailable:
          gone.add(l);
          notices.add(KioskNotice('removed', l.item, l.item.nameEn));
        case LineFate.repriced:
          l.item = c.item!;
          l.variant = c.variant!;
          l.repriced = true;
          notices.add(
            KioskNotice(
              'repriced',
              l.item,
              l.item.nameEn,
              c.variant!.priceCents,
            ),
          );
        case LineFate.ok:
          l.item = c.item!;
          l.variant = c.variant!;
      }
    }
    cart.removeWhere(gone.contains);
    // suggestions: only items still on sale
    final byId = {for (final i in items) i.id: i};
    offers = [
      for (final o in offers)
        if ([
              for (final i in o.items)
                if (byId[i.id] case final n?
                    when n.active && n.variants.isNotEmpty)
                  n,
            ]
            case final shown when shown.isNotEmpty)
          KioskOffer(o.reason, o.categoryId, shown),
    ];
    if (stage == KioskStage.upsell && offers.isEmpty) {
      stage = cart.isEmpty ? KioskStage.menu : KioskStage.cart;
    }
    if (cart.isEmpty && stage == KioskStage.cart) stage = KioskStage.menu;
  }

  /// The guest saw the new prices: the order can go.
  void acceptNewPrices() {
    for (final l in cart) {
      l.repriced = false;
    }
    notices.clear();
    _changed();
  }

  /// Notices read (the removed-items note): clear them.
  void dismissNotices() {
    notices.removeWhere((n) => n.kind == 'removed');
    _changed();
  }

  /// The store refused lines (nothing placed): drop the gone ones, reprice
  /// the others for a confirm.
  void _applyRejected(List<RejectedLine> rejected) {
    final lines = List.of(cart);
    for (final r in rejected) {
      final l = r.index >= 0 && r.index < lines.length ? lines[r.index] : null;
      if (l == null) continue;
      if (r.isPriceChange) {
        l.variant = Variant(
          l.variant.id,
          l.variant.labelFr,
          l.variant.labelEn,
          r.priceCents!,
        );
        l.repriced = true;
        notices.add(KioskNotice('repriced', l.item, r.nameEn, r.priceCents));
      } else {
        cart.remove(l);
        notices.add(KioskNotice('removed', l.item, r.nameEn));
      }
    }
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
      {
        'itemId': l.item.id,
        'variantId': l.variant.id,
        'qty': l.qty,
        // the price the guest saw: a change since is refused, never charged
        'expectedPriceCents': l.variant.priceCents,
      },
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

  /// The id of the order being sent, and the cart it was made for. The same
  /// cart sent again (a retry after "could not send", a second tap) carries
  /// the same id, so the store answers with the order it already placed
  /// rather than placing it twice; a changed cart is a new order, a new id.
  String? _orderId;
  String? _orderIdCart;

  String _orderIdFor(String mode) {
    final cartKey = jsonEncode([mode, _lines]);
    if (_orderId == null || _orderIdCart != cartKey) {
      final r = Random.secure();
      _orderId = List.generate(
        16,
        (_) => r.nextInt(16).toRadixString(16),
      ).join();
      _orderIdCart = cartKey;
    }
    return _orderId!;
  }

  /// Sends at once — no review, no confirmation. The number, then welcome.
  Future<void> placeOrder() async {
    final api = _api;
    if (api == null || cart.isEmpty || busy || needsPriceConfirm) return;
    busy = true;
    message = null;
    _changed();
    try {
      final m = mode ?? 'TAKE_OUT';
      result = await api.placeOrder(
        m,
        _lines,
        lang: lang,
        clientOrderId: _orderIdFor(m),
      );
      _orderId = null;
      busy = false;
      _idle?.cancel();
      _menuPoll?.pause();
      cart.clear();
      notices.clear();
      stage = KioskStage.done;
      // a few seconds more to read what was left out
      _done = Timer(
        result!.rejected.isEmpty ? doneFor : doneFor * 2,
        _toWelcome,
      );
      _changed();
    } on KioskApiException catch (e) {
      busy = false;
      if (e.code == 'lines_rejected' && e.rejected.isNotEmpty) {
        // nothing was placed: show what changed, keep the rest for a retry
        _applyRejected(e.rejected);
        if (cart.isEmpty) stage = KioskStage.menu;
        unawaited(refreshMenu());
      } else {
        message = 'send_failed';
      }
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
    _menuPoll?.dispose();
    _idle?.cancel();
    _done?.cancel();
    super.dispose();
  }
}
