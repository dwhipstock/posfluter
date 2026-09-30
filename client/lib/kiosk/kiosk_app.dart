import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart' show Item, Variant;
import '../i18n.dart' show pickName;
import '../server_discovery.dart';
import '../store_profile.dart' show formatMoney;
import 'kiosk_api.dart';
import 'kiosk_controller.dart';
import 'kiosk_i18n.dart';

const _navy = Color(0xFF17456E);
const _navyDeep = Color(0xFF0F3150);
const _copper = Color(0xFFA65A23);
const _cream = Color(0xFFF6F0E5);
const _ink = Color(0xFF1C2733);
const _muted = Color(0xFF62574B);

/// The self-order kiosk (`--dart-define=POS_APP=kiosk`): a portrait,
/// full-screen customer app at a quick-serve store. Payment is never taken
/// here; the order goes straight to the counter and the kitchen.
class KioskApp extends StatefulWidget {
  /// Test seam: a controller with a fake store.
  final KioskController? controller;
  const KioskApp({super.key, this.controller});

  @override
  State<KioskApp> createState() => _KioskAppState();
}

class _KioskAppState extends State<KioskApp> {
  late final KioskController c =
      widget.controller ??
      KioskController(
        discover: () => ServerDiscovery.discover(
          ports: const [8080, 8098, 8082, 8084],
          accept: KioskApi.isQuickServe,
        ),
      );

  @override
  void initState() {
    super.initState();
    c.start();
  }

  @override
  void dispose() {
    if (widget.controller == null) c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Kiosk',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: _navy,
          primary: _navy,
          secondary: _copper,
          surface: _cream,
        ),
        scaffoldBackgroundColor: _cream,
        fontFamily: 'Inter',
        useMaterial3: true,
      ),
      home: Listener(
        behavior: HitTestBehavior.translucent,
        onPointerDown: (_) => c.touch(),
        child: ListenableBuilder(
          listenable: c,
          builder: (context, _) => _screen(),
        ),
      ),
    );
  }

  Widget _screen() => switch (c.stage) {
    KioskStage.setup => _SetupScreen(c),
    KioskStage.welcome => _WelcomeScreen(c),
    KioskStage.mode => _ModeScreen(c),
    KioskStage.menu => _MenuScreen(c),
    KioskStage.cart => _CartScreen(c),
    KioskStage.done => _DoneScreen(c),
  };
}

String _money(KioskController c, int cents) =>
    formatMoney(cents, c.currency, lang: c.lang);

String _name(KioskController c, Item i) =>
    pickName(c.lang, i.nameFr, i.nameEn, i.names);

// ------------------------------------------------------------------ setup

class _SetupScreen extends StatefulWidget {
  final KioskController c;
  const _SetupScreen(this.c);
  @override
  State<_SetupScreen> createState() => _SetupScreenState();
}

class _SetupScreenState extends State<_SetupScreen> {
  final _address = TextEditingController();
  final _code = TextEditingController();

  @override
  void dispose() {
    _address.dispose();
    _code.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.c;
    final t = KioskText(c.lang == 'fr' ? 'fr' : 'en');
    final msg = switch (c.message) {
      'not_found' => t.notFound,
      'pair_failed' => t.pairFailed,
      _ => null,
    };
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 520),
            child: ListView(
              padding: const EdgeInsets.all(32),
              shrinkWrap: true,
              children: [
                const Icon(
                  LucideIcons.tabletSmartphone,
                  size: 64,
                  color: _navy,
                ),
                const SizedBox(height: 16),
                Text(
                  t.pairTitle,
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    fontSize: 28,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 12),
                Text(t.pairHelp, textAlign: TextAlign.center),
                const SizedBox(height: 16),
                if (c.storeUrl != null)
                  Text(
                    c.storeUrl!,
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: _muted),
                  ),
                if (msg != null)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    child: Text(
                      msg,
                      textAlign: TextAlign.center,
                      style: const TextStyle(color: Colors.red),
                    ),
                  ),
                const SizedBox(height: 8),
                TextField(
                  key: const Key('kiosk-address'),
                  controller: _address,
                  decoration: InputDecoration(
                    labelText: t.storeAddress,
                    hintText: '192.168.1.20:8080',
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  key: const Key('kiosk-code'),
                  controller: _code,
                  keyboardType: TextInputType.number,
                  maxLength: 6,
                  style: const TextStyle(fontSize: 28, letterSpacing: 6),
                  decoration: InputDecoration(labelText: t.code),
                ),
                const SizedBox(height: 12),
                FilledButton(
                  key: const Key('kiosk-pair'),
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(56),
                  ),
                  onPressed: c.busy
                      ? null
                      : () => c.pair(_code.text, address: _address.text),
                  child: Text(t.pair),
                ),
                TextButton(
                  onPressed: c.busy ? null : c.find,
                  child: Text(t.searchAgain),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// ------------------------------------------------------------------ welcome

class _WelcomeScreen extends StatelessWidget {
  final KioskController c;
  const _WelcomeScreen(this.c);

  @override
  Widget build(BuildContext context) {
    final t = KioskText(c.lang);
    return Scaffold(
      backgroundColor: _navy,
      body: GestureDetector(
        key: const Key('kiosk-welcome'),
        behavior: HitTestBehavior.opaque,
        onTap: c.startOrder,
        child: SafeArea(
          child: Column(
            children: [
              const Spacer(),
              ClipRRect(
                borderRadius: BorderRadius.circular(24),
                child: Image.asset(
                  'assets/copper_lantern_logo.png',
                  width: 220,
                  errorBuilder: (_, _, _) => const SizedBox(height: 220),
                ),
              ),
              const SizedBox(height: 24),
              Text(
                c.storeName,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 34,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const Spacer(),
              Container(
                margin: const EdgeInsets.symmetric(horizontal: 40),
                padding: const EdgeInsets.symmetric(vertical: 36),
                decoration: BoxDecoration(
                  color: _copper,
                  borderRadius: BorderRadius.circular(28),
                ),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    const Icon(
                      LucideIcons.pointer,
                      color: Colors.white,
                      size: 40,
                    ),
                    const SizedBox(width: 16),
                    Flexible(
                      child: Text(
                        c.offline ? t.offline : t.touchToOrder,
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 36,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 40),
              Wrap(
                spacing: 12,
                runSpacing: 12,
                alignment: WrapAlignment.center,
                children: [
                  for (final l in c.locales)
                    ChoiceChip(
                      key: Key('kiosk-lang-$l'),
                      label: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 6,
                        ),
                        child: Text(
                          KioskText.languageNames[l] ?? l.toUpperCase(),
                          style: const TextStyle(fontSize: 20),
                        ),
                      ),
                      selected: c.lang == l,
                      onSelected: (_) => c.setLang(l),
                    ),
                ],
              ),
              const SizedBox(height: 48),
            ],
          ),
        ),
      ),
    );
  }
}

// ------------------------------------------------------------------ dine in / take out

class _ModeScreen extends StatelessWidget {
  final KioskController c;
  const _ModeScreen(this.c);

  Widget _big(String key, IconData icon, String label, String mode) => Expanded(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Material(
        color: Colors.white,
        borderRadius: BorderRadius.circular(28),
        elevation: 2,
        child: InkWell(
          key: Key(key),
          borderRadius: BorderRadius.circular(28),
          onTap: () => c.chooseMode(mode),
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(icon, size: 120, color: _copper),
                const SizedBox(height: 20),
                Text(
                  label,
                  style: const TextStyle(
                    fontSize: 36,
                    fontWeight: FontWeight.w700,
                    color: _ink,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  );

  @override
  Widget build(BuildContext context) {
    final t = KioskText(c.lang);
    return Scaffold(
      appBar: AppBar(
        backgroundColor: _cream,
        leading: IconButton(
          icon: const Icon(LucideIcons.arrowLeft),
          tooltip: t.back,
          onPressed: c.back,
        ),
      ),
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(24),
              child: Text(
                t.chooseMode,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontSize: 34,
                  fontWeight: FontWeight.w800,
                  color: _ink,
                ),
              ),
            ),
            _big('kiosk-dine-in', LucideIcons.utensils, t.dineIn, 'DINE_IN'),
            _big(
              'kiosk-take-out',
              LucideIcons.shoppingBag,
              t.takeOut,
              'TAKE_OUT',
            ),
            const SizedBox(height: 24),
          ],
        ),
      ),
    );
  }
}

// ------------------------------------------------------------------ the menu

class _MenuScreen extends StatelessWidget {
  final KioskController c;
  const _MenuScreen(this.c);

  Future<void> _tap(BuildContext context, Item item) async {
    final t = KioskText(c.lang);
    if (item.variants.length == 1 && !item.isAlcohol) {
      c.add(item, item.variants.first);
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(
            duration: const Duration(milliseconds: 1400),
            content: Text(
              t.added(_name(c, item)),
              style: const TextStyle(fontSize: 20),
            ),
          ),
        );
      return;
    }
    final picked = await showModalBottomSheet<Variant>(
      context: context,
      showDragHandle: true,
      builder: (context) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                _name(c, item),
                style: const TextStyle(
                  fontSize: 28,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                pickName(c.lang, item.descriptionFr, item.descriptionEn),
                style: const TextStyle(color: _muted, fontSize: 18),
              ),
              if (item.isAlcohol)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Row(
                    children: [
                      const Icon(LucideIcons.idCard, color: _copper),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          t.idNote,
                          style: const TextStyle(fontSize: 16),
                        ),
                      ),
                    ],
                  ),
                ),
              if (item.variants.length > 1)
                Padding(
                  padding: const EdgeInsets.only(top: 16, bottom: 4),
                  child: Text(
                    t.chooseSize,
                    style: const TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              const SizedBox(height: 8),
              for (final v in item.variants)
                Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: FilledButton(
                    key: Key('kiosk-variant-${v.id}'),
                    style: FilledButton.styleFrom(
                      minimumSize: const Size.fromHeight(64),
                    ),
                    onPressed: () => Navigator.pop(context, v),
                    child: Text(
                      item.variants.length > 1
                          ? '${pickName(c.lang, v.labelFr, v.labelEn)} — ${_money(c, v.priceCents)}'
                          : t.addFor(_money(c, v.priceCents)),
                      style: const TextStyle(fontSize: 22),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
    if (picked != null) c.add(item, picked);
  }

  @override
  Widget build(BuildContext context) {
    final t = KioskText(c.lang);
    final cats = c.shownCategories;
    final shown = c.shownItems;
    return Scaffold(
      appBar: AppBar(
        backgroundColor: _navy,
        foregroundColor: Colors.white,
        leading: IconButton(
          icon: const Icon(LucideIcons.arrowLeft),
          tooltip: t.back,
          onPressed: c.back,
        ),
        title: Text(c.mode == 'DINE_IN' ? t.dineIn : t.takeOut),
        actions: [
          TextButton(
            onPressed: c.cancelOrder,
            style: TextButton.styleFrom(foregroundColor: Colors.white),
            child: Text(t.startOver),
          ),
        ],
      ),
      body: Column(
        children: [
          SizedBox(
            height: 76,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
              children: [
                for (final cat in cats)
                  Padding(
                    padding: const EdgeInsets.only(right: 10),
                    child: ChoiceChip(
                      key: Key('kiosk-cat-${cat.id}'),
                      label: Text(
                        pickName(c.lang, cat.nameFr, cat.nameEn, cat.names),
                        style: const TextStyle(fontSize: 20),
                      ),
                      selected: c.category == cat.id,
                      onSelected: (_) => c.chooseCategory(cat.id),
                    ),
                  ),
              ],
            ),
          ),
          Expanded(
            child: GridView.builder(
              padding: const EdgeInsets.all(12),
              gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                maxCrossAxisExtent: 360,
                mainAxisSpacing: 12,
                crossAxisSpacing: 12,
                childAspectRatio: 0.82,
              ),
              itemCount: shown.length,
              itemBuilder: (context, i) =>
                  _Tile(c, shown[i], () => _tap(context, shown[i])),
            ),
          ),
          if (c.cart.isNotEmpty)
            SafeArea(
              top: false,
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: FilledButton(
                  key: const Key('kiosk-view-order'),
                  style: FilledButton.styleFrom(
                    backgroundColor: _copper,
                    minimumSize: const Size.fromHeight(76),
                  ),
                  onPressed: c.openCart,
                  child: Row(
                    children: [
                      const Icon(LucideIcons.shoppingCart, size: 30),
                      const SizedBox(width: 12),
                      Text(
                        t.viewOrder(c.itemCount),
                        style: const TextStyle(fontSize: 26),
                      ),
                      const Spacer(),
                      Text(
                        _money(c, c.subtotalCents),
                        style: const TextStyle(
                          fontSize: 26,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _Tile extends StatelessWidget {
  final KioskController c;
  final Item item;
  final VoidCallback onTap;
  const _Tile(this.c, this.item, this.onTap);

  @override
  Widget build(BuildContext context) {
    final url = c.api?.photoUrl(item);
    final from = item.variants
        .map((v) => v.priceCents)
        .reduce((a, b) => a < b ? a : b);
    final badge = Container(
      color: _navyDeep,
      alignment: Alignment.center,
      child: Text(
        item.abbrev,
        style: const TextStyle(
          color: Colors.white,
          fontSize: 48,
          fontWeight: FontWeight.w800,
        ),
      ),
    );
    return Material(
      color: Colors.white,
      borderRadius: BorderRadius.circular(20),
      clipBehavior: Clip.antiAlias,
      elevation: 1,
      child: InkWell(
        key: Key('kiosk-item-${item.id}'),
        onTap: onTap,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: url == null
                  ? badge
                  : Image.network(
                      url,
                      fit: BoxFit.cover,
                      errorBuilder: (_, _, _) => badge,
                    ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 10, 14, 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _name(c, item),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.w700,
                      color: _ink,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      Text(
                        _money(c, from),
                        style: const TextStyle(
                          fontSize: 20,
                          color: _copper,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const Spacer(),
                      if (item.isAlcohol)
                        const Icon(LucideIcons.idCard, size: 20, color: _muted),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ------------------------------------------------------------------ the cart

class _CartScreen extends StatelessWidget {
  final KioskController c;
  const _CartScreen(this.c);

  @override
  Widget build(BuildContext context) {
    final t = KioskText(c.lang);
    return Scaffold(
      appBar: AppBar(
        backgroundColor: _navy,
        foregroundColor: Colors.white,
        leading: IconButton(
          icon: const Icon(LucideIcons.arrowLeft),
          tooltip: t.back,
          onPressed: c.back,
        ),
        title: Text(t.yourOrder),
      ),
      body: Column(
        children: [
          Expanded(
            child: c.cart.isEmpty
                ? Center(
                    child: Text(
                      t.emptyOrder,
                      style: const TextStyle(fontSize: 22),
                    ),
                  )
                : ListView(
                    padding: const EdgeInsets.all(16),
                    children: [
                      for (final l in c.cart)
                        Card(
                          child: Padding(
                            padding: const EdgeInsets.all(12),
                            child: Row(
                              children: [
                                Expanded(
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      Text(
                                        _name(c, l.item),
                                        style: const TextStyle(
                                          fontSize: 22,
                                          fontWeight: FontWeight.w700,
                                        ),
                                      ),
                                      if (l.item.variants.length > 1)
                                        Text(
                                          pickName(
                                            c.lang,
                                            l.variant.labelFr,
                                            l.variant.labelEn,
                                          ),
                                          style: const TextStyle(
                                            fontSize: 18,
                                            color: _muted,
                                          ),
                                        ),
                                    ],
                                  ),
                                ),
                                IconButton.filledTonal(
                                  iconSize: 28,
                                  onPressed: () => c.setQty(l, l.qty - 1),
                                  icon: const Icon(LucideIcons.minus),
                                ),
                                SizedBox(
                                  width: 48,
                                  child: Text(
                                    '${l.qty}',
                                    textAlign: TextAlign.center,
                                    style: const TextStyle(
                                      fontSize: 24,
                                      fontWeight: FontWeight.w700,
                                    ),
                                  ),
                                ),
                                IconButton.filledTonal(
                                  iconSize: 28,
                                  onPressed: () => c.setQty(l, l.qty + 1),
                                  icon: const Icon(LucideIcons.plus),
                                ),
                                SizedBox(
                                  width: 110,
                                  child: Text(
                                    _money(c, l.totalCents),
                                    textAlign: TextAlign.end,
                                    style: const TextStyle(
                                      fontSize: 22,
                                      fontWeight: FontWeight.w700,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      if (c.hasAlcohol)
                        Padding(
                          padding: const EdgeInsets.all(12),
                          child: Row(
                            children: [
                              const Icon(LucideIcons.idCard, color: _copper),
                              const SizedBox(width: 8),
                              Expanded(
                                child: Text(
                                  t.idNote,
                                  style: const TextStyle(fontSize: 18),
                                ),
                              ),
                            ],
                          ),
                        ),
                    ],
                  ),
          ),
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                children: [
                  Row(
                    children: [
                      Text(t.subtotal, style: const TextStyle(fontSize: 24)),
                      const Spacer(),
                      Text(
                        _money(c, c.subtotalCents),
                        style: const TextStyle(
                          fontSize: 28,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                    ],
                  ),
                  Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      t.taxesAtCounter,
                      style: const TextStyle(color: _muted, fontSize: 16),
                    ),
                  ),
                  if (c.message == 'send_failed')
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Text(
                        t.sendFailed,
                        style: const TextStyle(color: Colors.red, fontSize: 18),
                      ),
                    ),
                  const SizedBox(height: 12),
                  FilledButton(
                    key: const Key('kiosk-place-order'),
                    style: FilledButton.styleFrom(
                      backgroundColor: _copper,
                      minimumSize: const Size.fromHeight(80),
                    ),
                    onPressed: c.busy || c.cart.isEmpty ? null : c.placeOrder,
                    child: Text(
                      c.busy ? t.sending : t.placeOrder,
                      style: const TextStyle(fontSize: 30),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ------------------------------------------------------------------ the number

class _DoneScreen extends StatelessWidget {
  final KioskController c;
  const _DoneScreen(this.c);

  @override
  Widget build(BuildContext context) {
    final t = KioskText(c.lang);
    final r = c.result;
    return Scaffold(
      backgroundColor: _navy,
      body: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: c.finish,
        child: SafeArea(
          child: Center(
            child: Padding(
              padding: const EdgeInsets.all(32),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    t.thanks,
                    style: const TextStyle(color: Colors.white70, fontSize: 32),
                  ),
                  const SizedBox(height: 16),
                  Text(
                    t.yourNumber,
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: Colors.white, fontSize: 34),
                  ),
                  Text(
                    r?.label ?? '',
                    key: const Key('kiosk-order-number'),
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 160,
                      fontWeight: FontWeight.w900,
                    ),
                  ),
                  Text(
                    t.payAtCounter,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 32,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  if (r?.idCheckAtCounter ?? false)
                    Padding(
                      padding: const EdgeInsets.only(top: 24),
                      child: Text(
                        t.idNote,
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          color: Colors.white70,
                          fontSize: 22,
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
