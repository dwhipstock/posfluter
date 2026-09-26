import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../api.dart';
import '../catalog/catalog_index.dart';
import '../design/skin.dart';
import '../i18n.dart';
import '../screens/login_screen.dart';
import '../screens/receipt_screen.dart';
import '../screens/shift_screen.dart';
import '../stock/barcode_scanner.dart';
import '../stock/count_screens.dart';
import '../stock/receive_screen.dart';
import '../widgets/brand.dart';
import '../widgets/pin_pad.dart';
import 'add_product_dialog.dart';
import 'age_check_dialog.dart';
import 'counter_widgets.dart';
import 'pay_sheet.dart';
import 'retail_i18n.dart';
import 'scan_detector.dart';
import 'sp_theme.dart';

/// The retail counter (a store whose profile kind is retail), built for a
/// 5,000-product shelf the way real bottle-shop registers are: scan first,
/// with fast lanes for everything else.
///
/// - **Scan** from anywhere on the screen: a USB / Bluetooth HID scanner types
///   the code and Enter, and every key goes through a [HidScanner] (a fast
///   burst ending in Enter is a scan, even while the search box has focus —
///   the burst is taken back out of it). The camera scans too.
/// - **Quick keys**: ~36 one-tap tiles — a manager's pins, the products with
///   no barcode (a bag, a lime), then the store's fastest sellers of the last
///   28 days, computed on the store (`GET /retail/quick-keys`).
/// - **Top sellers**: the ranked top 20% of the shelf, with department filters.
/// - **Search**: type-ahead over name, brand, size and barcode digits on the
///   terminal's own in-memory index ([CatalogIndex]) — "ipa 6" finds IPA
///   six-packs in about a millisecond.
/// - **Browse**: department › style › size, combined, with a reset.
///
/// Every list is lazy (only the rows on screen are built). The basket sits
/// docked on the right with the Pay button; ID check, payment, receipts and
/// the stock screens work as before. The brand skin decides the frame: a
/// left rail (Sage & Poppy) or a top band.
class RetailScreen extends StatefulWidget {
  /// Extra barcode sources. The HID scanner and the camera are built in.
  final List<BarcodeSource> sources;
  const RetailScreen({super.key, this.sources = const []});

  @override
  State<RetailScreen> createState() => _RetailScreenState();
}

enum _View { keys, top, browse }

class _RetailScreenState extends State<RetailScreen> {
  final _scanner = HidScanner();
  final _search = TextEditingController();
  final _searchFocus = FocusNode();
  final _subs = <dynamic>[];

  Check? _sale;
  List<Item> _items = [];
  late CatalogIndex<Item> _index = CatalogIndex(const <Item>[], _doc);
  List<Category> _categories = [];
  QuickKeys _keys = QuickKeys.empty;
  List<TopSeller> _top = const [];
  Map<String, int> _unitsById = const {};
  ShiftInfo? _shift;
  bool _shiftLoaded = false;
  bool _catalogLoaded = false;
  bool _busy = false;
  bool _dialogOpen = false;
  bool _editKeys = false;
  String? _lastScan;
  DateTime? _lastScanAt;

  _View _view = _View.keys;
  String _topCategory = 'all';
  String? _cat, _sub, _size;

  /// Ties rank the store's recent sellers first, then the catalog's weight.
  CatalogDoc _doc(Item i) => CatalogDoc(
    id: i.id,
    name: i.nameEn,
    category: i.category,
    brand: i.brand,
    subcategory: i.subcategory,
    size: i.size,
    barcode: i.barcode,
    packUnits: i.packUnits,
    popularity: (_unitsById[i.id] ?? 0) * 1000000 + i.salesWeight,
  );

  @override
  void initState() {
    super.initState();
    HardwareKeyboard.instance.addHandler(_onKey);
    for (final s in widget.sources) {
      _subs.add(s.scans.listen(_scan));
    }
    _load();
  }

  @override
  void dispose() {
    HardwareKeyboard.instance.removeHandler(_onKey);
    for (final s in _subs) {
      s.cancel();
    }
    _scanner.dispose();
    _search.dispose();
    _searchFocus.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final results = await Future.wait([
        Api.catalog(),
        Api.categories(),
        Api.currentShift(),
      ]);
      if (!mounted) return;
      setState(() {
        _setItems(results[0] as List<Item>);
        _categories = results[1] as List<Category>;
        _shift = results[2] as ShiftInfo?;
        _shiftLoaded = true;
        _catalogLoaded = true;
      });
      // a restarted terminal picks up the sale in progress; otherwise the
      // first scan starts one
      final current = await Api.currentSale();
      if (mounted) setState(() => _sale = current);
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
    _loadFastLanes();
  }

  /// Quick keys and top sellers: the store's own ranking. An older store
  /// without them (or no connection) falls back to the catalog's weights.
  Future<void> _loadFastLanes() async {
    try {
      final keys = await Api.quickKeys();
      if (mounted) setState(() => _keys = keys);
    } catch (_) {}
    try {
      final top = await Api.topSellers();
      if (!mounted) return;
      setState(() {
        _top = top;
        _unitsById = {for (final t in top) t.itemId: t.units};
        _index = CatalogIndex(_items, _doc);
      });
    } catch (_) {}
  }

  void _setItems(List<Item> items) {
    _items = items;
    _index = CatalogIndex(items, _doc);
  }

  // ---- scanning ----

  bool _onKey(KeyEvent e) {
    if (_dialogOpen || e is KeyUpEvent) return false;
    final key = e.logicalKey;
    if (key == LogicalKeyboardKey.enter ||
        key == LogicalKeyboardKey.numpadEnter) {
      final code = _scanner.enter(e.timeStamp);
      if (code == null) return false;
      _lastScan = code;
      _lastScanAt = DateTime.now();
      // the burst may have landed in the search box: take it back out
      WidgetsBinding.instance.addPostFrameCallback(
        (_) => _stripFromSearch(code),
      );
      _scan(code);
      return true;
    }
    final ch = e.character;
    if (ch != null && ch.length == 1 && ch.codeUnitAt(0) >= 0x20) {
      _scanner.char(ch, e.timeStamp);
    }
    return false;
  }

  void _stripFromSearch(String code) {
    final t = _search.text;
    if (t.endsWith(code)) {
      _search.text = t.substring(0, t.length - code.length);
      setState(() {});
    }
  }

  /// The search box's own Enter: a scan that just happened wins; otherwise
  /// a typed barcode is scanned, and a single match is added.
  void _onSearchSubmitted(String text) {
    if (_lastScanAt != null &&
        DateTime.now().difference(_lastScanAt!) < const Duration(seconds: 1)) {
      _stripFromSearch(_lastScan ?? '');
      return;
    }
    final q = text.trim();
    if (q.isEmpty) return;
    if (RegExp(r'^\d{6,14}$').hasMatch(q)) {
      _search.clear();
      setState(() {});
      _scan(q);
      return;
    }
    final hits = _index.search(q, limit: 2);
    if (hits.length == 1) {
      _search.clear();
      setState(() {});
      _addItem(hits.single);
    }
  }

  Future<Check> _ensureSale() async {
    final s = _sale;
    if (s != null && s.status == 'OPEN') return s;
    final opened = await Api.openSale();
    if (mounted) setState(() => _sale = opened);
    return opened;
  }

  Future<void> _scan(String code) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final sale = await _ensureSale();
      final updated = await Api.scanBarcode(sale.id, code);
      if (mounted) setState(() => _sale = updated);
    } on ApiException catch (e) {
      if (!mounted) return;
      if (e.code == 'unknown_barcode') {
        _busy = false;
        await _unknown(code);
      } else {
        showApiError(context, e);
      }
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _unknown(String code) async {
    _dialogOpen = true;
    try {
      if (!await showUnknownBarcode(context, code)) return;
      if (!mounted) return;
      final added = await AddProductDialog.show(context, code, _categories);
      if (!added || !mounted) return;
      final items = await Api.catalog();
      if (mounted) setState(() => _setItems(items));
    } finally {
      _dialogOpen = false;
    }
    // added: ring it up straight away
    await _scan(code);
  }

  // ---- basket ----

  Future<void> _addItem(Item item) async {
    final code = item.barcode;
    if (code != null && code.isNotEmpty) return _scan(code);
    if (_busy || item.variants.isEmpty) return;
    setState(() => _busy = true);
    try {
      final sale = await _ensureSale();
      final v = item.variants.first;
      final existing = sale.lines
          .where(
            (l) => l.itemId == item.id && l.variantId == v.id && l.note == null,
          )
          .firstOrNull;
      final updated = existing != null
          ? await Api.setLineQty(sale.id, existing.id, existing.qty + 1)
          : await Api.addLine(sale.id, item.id, v.id, 1);
      if (mounted) setState(() => _sale = updated);
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _setQty(CheckLine line, int qty) async {
    final sale = _sale;
    if (sale == null || _busy) return;
    setState(() => _busy = true);
    try {
      final updated = qty <= 0
          ? await Api.removeLine(sale.id, line.id)
          : await Api.setLineQty(sale.id, line.id, qty);
      // removing the last line cancels an empty sale; the next scan starts a new one
      if (mounted) {
        setState(() => _sale = updated.status == 'OPEN' ? updated : null);
      }
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _removeRestricted() async {
    var sale = _sale;
    if (sale == null) return;
    for (final l in sale.lines.where((l) => l.ageRestricted).toList()) {
      sale = await Api.removeLine(sale!.id, l.id);
    }
    if (mounted) setState(() => _sale = sale?.status == 'OPEN' ? sale : null);
  }

  // ---- quick keys: pins ----

  bool _isPinned(String id) =>
      _keys.keys.any((k) => k.itemId == id && k.pinned);

  /// Pin or unpin [item]: a menu edit (the edit-menu grant, or a manager's PIN).
  Future<void> _togglePin(Item item) async {
    final r = R.of(context);
    final pinned = _isPinned(item.id);
    _dialogOpen = true;
    Approval? ok;
    try {
      ok = await requireGrant(
        context,
        Perm.editMenu,
        title: pinned ? r.unpinKey : r.pinKey,
      );
    } finally {
      _dialogOpen = false;
    }
    if (ok == null) return;
    try {
      final keys = pinned
          ? await Api.unpinQuickKey(item.id, managerPin: ok.managerPin)
          : await Api.pinQuickKey(item.id, managerPin: ok.managerPin);
      if (mounted) setState(() => _keys = keys);
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _pinMenu(Item item) async {
    final r = R.of(context);
    final s = BrandSkin.of(context);
    final pinned = _isPinned(item.id);
    _dialogOpen = true;
    try {
      final go = await showModalBottomSheet<bool>(
        context: context,
        showDragHandle: true,
        builder: (ctx) => SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  item.nameEn,
                  style: s.text(size: 18, weight: FontWeight.w700),
                ),
                const SizedBox(height: 16),
                FilledButton.icon(
                  onPressed: () => Navigator.pop(ctx, true),
                  icon: Icon(pinned ? s.glyphs.pinOff : s.glyphs.pin),
                  label: Text(pinned ? r.unpinKey : r.pinKey),
                ),
              ],
            ),
          ),
        ),
      );
      if (go == true) await _togglePin(item);
    } finally {
      _dialogOpen = false;
    }
  }

  // ---- ID check + payment ----

  Future<void> _checkId() async {
    final sale = _sale;
    if (sale == null) return;
    _dialogOpen = true;
    AgeCheckResult? result;
    try {
      result = await AgeCheckDialog.show(
        context,
        saleId: sale.id,
        legalAge: StoreProfile.current.legalAge,
      );
    } finally {
      _dialogOpen = false;
    }
    if (result == null || !mounted) return;
    setState(() => _sale = result!.check);
    if (!result.passed) await _removeRestricted();
  }

  Future<void> _pay() async {
    var sale = _sale;
    if (sale == null || sale.lines.isEmpty) return;
    if (_shift == null) {
      await _openRegister();
      if (_shift == null) return;
    }
    if (sale.ageCheckRequired && !sale.ageCleared) {
      await _checkId();
      sale = _sale;
      if (sale == null ||
          (sale.ageCheckRequired && !sale.ageCleared) ||
          sale.lines.isEmpty) {
        return;
      }
    }
    if (!mounted) return;
    _dialogOpen = true;
    PayResult? paid;
    try {
      paid = await PaySheet.show(context, sale);
    } finally {
      _dialogOpen = false;
    }
    if (paid == null) {
      // dismissed before payment: pick up any partial tender
      final fresh = await Api.getCheck(sale.id);
      if (mounted) setState(() => _sale = fresh);
      return;
    }
    if (!mounted) return;
    setState(() => _sale = null);
    try {
      final text = await Api.receiptText(paid.check.id);
      if (!mounted) return;
      await Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) => ReceiptScreen(
            checkId: paid!.check.id,
            text: text,
            title:
                '${R.of(context).receipt} · ${R.of(context).sale(paid.check.id)}',
          ),
        ),
      );
    } catch (_) {} // the sale is paid and recorded either way
    // the sale moved the fast lanes: refresh them quietly
    _loadFastLanes();
  }

  Future<void> _openRegister() async {
    final r = R.of(context);
    final floatCtl = TextEditingController(text: '200');
    final pinCtl = TextEditingController();
    final needsPin = Api.currentUser?.can(Perm.openShift) != true;
    _dialogOpen = true;
    try {
      final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: Text(r.openRegister),
          content: SizedBox(
            width: 420,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: floatCtl,
                  keyboardType: TextInputType.number,
                  decoration: InputDecoration(
                    labelText: r.openingFloat,
                    prefixText: '\$ ',
                  ),
                ),
                if (needsPin) ...[
                  const SizedBox(height: 12),
                  TextField(
                    controller: pinCtl,
                    obscureText: true,
                    keyboardType: TextInputType.number,
                    maxLength: 4,
                    decoration: InputDecoration(
                      labelText: r.managerPin,
                      counterText: '',
                    ),
                  ),
                ],
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: Text(r.cancel),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: Text(r.open),
            ),
          ],
        ),
      );
      if (ok != true) return;
      final cents = ((double.tryParse(floatCtl.text.trim()) ?? 0) * 100)
          .round();
      final shift = await Api.openShift(
        cents,
        needsPin ? pinCtl.text.trim() : null,
      );
      if (mounted) setState(() => _shift = shift);
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      _dialogOpen = false;
    }
  }

  Future<void> _closeRegister() async {
    await Navigator.of(
      context,
    ).push(MaterialPageRoute(builder: (_) => const ShiftScreen()));
    final shift = await Api.currentShift();
    if (mounted) setState(() => _shift = shift);
  }

  Future<void> _stock(bool count) async {
    _dialogOpen = true; // the stock screens own the scanner
    try {
      await Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) =>
              count ? const CountListScreen() : const ReceiveScreen(),
        ),
      );
    } finally {
      _dialogOpen = false;
    }
  }

  /// The tablet camera as a scanner: one code, rung up like a HID scan.
  Future<void> _cameraScan() async {
    _dialogOpen = true;
    String? code;
    try {
      code = await CameraScanner.scanOnce(context);
    } finally {
      _dialogOpen = false;
    }
    if (code != null && mounted) await _scan(code);
  }

  Future<void> _signOut() async {
    await Api.logout();
    if (!mounted) return;
    Navigator.of(context).pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => const LoginScreen()),
      (_) => false,
    );
  }

  // ---- what the fast lanes show ----

  /// The quick keys as products; before the store answers (or on an older
  /// store), the products with no barcode and the most popular ones.
  List<(Item, QuickKey?)> get _quickKeyItems {
    final out = <(Item, QuickKey?)>[];
    if (_keys.keys.isNotEmpty) {
      for (final k in _keys.keys) {
        final item = _index.byId(k.itemId);
        if (item != null && item.active) out.add((item, k));
      }
      return out;
    }
    final sorted = [..._items.where((i) => i.active)]
      ..sort((a, b) {
        final u = (a.barcode == null ? 0 : 1).compareTo(
          b.barcode == null ? 0 : 1,
        );
        return u != 0 ? u : b.salesWeight.compareTo(a.salesWeight);
      });
    return [for (final i in sorted.take(36)) (i, null)];
  }

  List<(Item, int)> get _topSellers {
    final ranked = <(Item, int)>[];
    if (_top.isNotEmpty) {
      for (final t in _top) {
        final item = _index.byId(t.itemId);
        if (item != null) ranked.add((item, t.units));
      }
    } else {
      final sorted = [..._items]
        ..sort((a, b) => b.salesWeight.compareTo(a.salesWeight));
      ranked.addAll([
        for (final i in sorted.take((_items.length / 5).ceil())) (i, 0),
      ]);
    }
    if (_topCategory == 'all') return ranked;
    return [
      for (final r in ranked)
        if (r.$1.category == _topCategory) r,
    ];
  }

  String _categoryName(String id) {
    final r = R.of(context);
    final c = _categories.where((c) => c.id == id).firstOrNull;
    return r.category(id, c?.nameEn ?? id);
  }

  List<String> get _categoryIds {
    final present = _index.facet('category');
    final ordered = [
      for (final c in _categories)
        if (present.containsKey(c.id)) c.id,
    ];
    for (final id in present.keys) {
      if (!ordered.contains(id)) ordered.add(id);
    }
    return ordered;
  }

  // ---- view ----

  @override
  Widget build(BuildContext context) {
    final c = SpColors.of(context);
    final skin = BrandSkin.of(context);
    final body = LayoutBuilder(
      builder: (context, box) {
        final basketWidth = (box.maxWidth * .3).clamp(340.0, 420.0);
        return Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (skin.shell == ShellLayout.leftRail) _rail(context),
            Expanded(child: _main(context)),
            SizedBox(width: basketWidth, child: _basket(context)),
          ],
        );
      },
    );
    return Scaffold(
      backgroundColor: c.background,
      body: SafeArea(
        child: skin.shell == ShellLayout.topBar
            ? Column(
                children: [
                  _topBand(context),
                  Expanded(child: body),
                ],
              )
            : body,
      ),
    );
  }

  // ---- frame: the rail (S&P) or a top band ----

  Widget _rail(BuildContext context) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final g = s.glyphs;
    final user = Api.currentUser;
    final initials = (user?.name ?? '?')
        .split(RegExp(r'\s+'))
        .where((w) => w.isNotEmpty)
        .take(2)
        .map((w) => w[0].toUpperCase())
        .join();
    return Container(
      width: 92,
      color: c.surface,
      padding: const EdgeInsets.symmetric(vertical: 14),
      child: Column(
        children: [
          const BrandLogo(size: 50),
          const SizedBox(height: 18),
          RailButton(icon: g.sell, label: r.sell, selected: true),
          RailButton(
            key: const Key('menu-count'),
            icon: g.count,
            label: r.count,
            onTap: () => _stock(true),
          ),
          RailButton(
            key: const Key('menu-receive'),
            icon: g.receive,
            label: r.receive,
            onTap: () => _stock(false),
          ),
          RailButton(
            key: const Key('rail-register'),
            icon: g.register,
            label: r.register,
            badge: !_shiftLoaded ? null : (_shift == null ? c.poppy : c.ok),
            onTap: _shift == null ? _openRegister : _closeRegister,
          ),
          const Spacer(),
          TextButton(
            onPressed: () => Prefs.instance.setLang(Prefs.instance.nextLang),
            style: TextButton.styleFrom(
              minimumSize: const Size(56, 44),
              foregroundColor: c.text,
            ),
            child: Text(
              L.of(context).lang.toUpperCase(),
              style: s.text(size: 15, weight: FontWeight.w800, color: c.text),
            ),
          ),
          const SizedBox(height: 6),
          PopupMenuButton<String>(
            tooltip: user?.name ?? r.more,
            onSelected: (v) {
              if (v == 'out') _signOut();
            },
            itemBuilder: (_) => [
              PopupMenuItem(
                enabled: false,
                child: Text(
                  user?.name ?? '',
                  style: s.text(
                    size: 15,
                    weight: FontWeight.w700,
                    color: c.text,
                  ),
                ),
              ),
              PopupMenuItem(value: 'out', child: Text(r.signOut)),
            ],
            child: CircleAvatar(
              radius: 24,
              backgroundColor: c.sage,
              child: Text(
                initials,
                style: s.text(
                  size: 16,
                  weight: FontWeight.w800,
                  color: c.onSage,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _topBand(BuildContext context) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final user = Api.currentUser;
    return Container(
      height: 72,
      padding: const EdgeInsets.symmetric(horizontal: 18),
      color: c.sageDeep,
      child: Row(
        children: [
          const BrandLogo(size: 52, ring: true),
          const SizedBox(width: 12),
          Text(
            StoreProfile.current.isSagePoppy ? 'Sage & Poppy' : Api.venueBrand,
            style: s.text(
              size: 22,
              weight: FontWeight.w700,
              color: Colors.white,
            ),
          ),
          const Spacer(),
          if (user != null)
            Text(
              user.name,
              style: s.text(
                size: 15,
                weight: FontWeight.w600,
                color: Colors.white,
              ),
            ),
          const LangActions(color: Colors.white),
          PopupMenuButton<String>(
            tooltip: r.more,
            icon: Icon(s.glyphs.signOut, color: Colors.white),
            onSelected: (v) async {
              if (v == 'open') await _openRegister();
              if (v == 'close') await _closeRegister();
              if (v == 'count') await _stock(true);
              if (v == 'receive') await _stock(false);
              if (v == 'out') await _signOut();
            },
            itemBuilder: (_) => [
              if (_shift == null)
                PopupMenuItem(value: 'open', child: Text(r.openRegister)),
              if (_shift != null)
                PopupMenuItem(value: 'close', child: Text(r.closeRegister)),
              PopupMenuItem(value: 'count', child: Text(r.countStock)),
              PopupMenuItem(value: 'receive', child: Text(r.receiveDelivery)),
              PopupMenuItem(value: 'out', child: Text(r.signOut)),
            ],
          ),
        ],
      ),
    );
  }

  // ---- main column: search/scan bar, lanes ----

  Widget _main(BuildContext context) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final searching = _search.text.trim().isNotEmpty;
    return Padding(
      padding: EdgeInsets.fromLTRB(s.gutter, 16, s.gutter, 0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _searchBar(context),
          const SizedBox(height: 14),
          if (_shiftLoaded && _shift == null) ...[
            _closedBanner(context),
            const SizedBox(height: 12),
          ],
          if (!searching) ...[_laneTabs(context), const SizedBox(height: 12)],
          Expanded(
            child: !_catalogLoaded
                ? Center(
                    child: Text(
                      r.loadingShelf,
                      style: s.text(color: c.textMuted),
                    ),
                  )
                : searching
                ? _results(context)
                : switch (_view) {
                    _View.keys => _quickKeysGrid(context),
                    _View.top => _topList(context),
                    _View.browse => _browse(context),
                  },
          ),
        ],
      ),
    );
  }

  Widget _searchBar(BuildContext context) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final g = s.glyphs;
    final border = s.pillControls ? BorderRadius.circular(40) : s.radiusMedium;
    OutlineInputBorder outline(Color color, double w) => OutlineInputBorder(
      borderRadius: border,
      borderSide: BorderSide(color: color, width: w),
    );
    return SizedBox(
      height: 64,
      child: TextField(
        key: const Key('counter-search'),
        controller: _search,
        focusNode: _searchFocus,
        onChanged: (_) => setState(() {}),
        onSubmitted: _onSearchSubmitted,
        textInputAction: TextInputAction.search,
        style: s.text(size: 19, weight: FontWeight.w600, color: c.text),
        decoration: InputDecoration(
          hintText: r.searchCatalog(_items.length),
          hintStyle: s.text(size: 18, color: c.textMuted),
          contentPadding: const EdgeInsets.symmetric(vertical: 20),
          prefixIcon: Padding(
            padding: const EdgeInsets.only(left: 18, right: 10),
            child: Icon(g.search, size: 26, color: c.strong),
          ),
          prefixIconConstraints: const BoxConstraints(minWidth: 54),
          suffixIcon: Padding(
            padding: const EdgeInsets.only(right: 8),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (_search.text.isNotEmpty)
                  IconButton(
                    tooltip: r.cancel,
                    icon: Icon(g.close, color: c.textMuted),
                    onPressed: () => setState(_search.clear),
                  )
                else
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 7,
                    ),
                    decoration: BoxDecoration(
                      color: c.okSoft,
                      borderRadius: BorderRadius.circular(40),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(g.scan, size: 17, color: c.ok),
                        const SizedBox(width: 6),
                        Text(
                          r.scannerReady,
                          style: s.text(
                            size: 13,
                            weight: FontWeight.w700,
                            color: c.ok,
                          ),
                        ),
                      ],
                    ),
                  ),
                if (CameraScanner.supported)
                  IconButton(
                    key: const Key('retail-camera'),
                    tooltip: r.scanWithCamera,
                    icon: Icon(g.camera, color: c.strong),
                    onPressed: _cameraScan,
                  ),
              ],
            ),
          ),
          filled: true,
          fillColor: c.surface,
          border: outline(c.border, 1),
          enabledBorder: outline(c.surface, 1),
          focusedBorder: outline(c.sage, 2),
        ),
      ),
    );
  }

  Widget _closedBanner(BuildContext context) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 6, 6, 6),
      decoration: BoxDecoration(
        color: c.warnSoft,
        borderRadius: s.radiusMedium,
      ),
      child: Row(
        children: [
          Icon(s.glyphs.lock, color: c.warn, size: 20),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              r.registerClosed,
              style: s.text(size: 15, color: c.warn, weight: FontWeight.w600),
            ),
          ),
          TextButton(onPressed: _openRegister, child: Text(r.openRegister)),
        ],
      ),
    );
  }

  Widget _laneTabs(BuildContext context) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final g = s.glyphs;
    Widget tab(_View v, String key, IconData icon, String label) {
      final on = _view == v;
      return Padding(
        padding: const EdgeInsets.only(right: 6),
        child: Material(
          color: on ? c.selectedFill : Colors.transparent,
          shape: s.controlShape(),
          child: InkWell(
            key: Key(key),
            customBorder: s.controlShape(),
            onTap: () => setState(() {
              _view = v;
              if (v != _View.keys) _editKeys = false;
            }),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(icon, size: 20, color: on ? c.onSelected : c.textMuted),
                  const SizedBox(width: 8),
                  Text(
                    label,
                    style: s.text(
                      size: 15.5,
                      weight: FontWeight.w700,
                      color: on ? c.onSelected : c.text,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
    }

    return SizedBox(
      height: 44,
      child: Row(
        children: [
          tab(_View.keys, 'tab-keys', g.star, r.quickKeys),
          tab(_View.top, 'tab-top', g.list, r.topSellers),
          tab(_View.browse, 'tab-browse', g.filter, r.browse),
          const Spacer(),
          if (_view == _View.keys)
            TextButton.icon(
              key: const Key('edit-keys'),
              onPressed: () => setState(() => _editKeys = !_editKeys),
              icon: Icon(_editKeys ? g.check : g.pin, size: 18),
              label: Text(_editKeys ? r.doneEditing : r.editKeys),
            ),
        ],
      ),
    );
  }

  Widget _quickKeysGrid(BuildContext context) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final keys = _quickKeyItems;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (_editKeys)
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: Text(
              r.keysHint,
              style: s.text(size: 14, color: c.textMuted),
            ),
          ),
        Expanded(
          child: LayoutBuilder(
            builder: (context, box) {
              const cols = 6;
              const gap = 8.0;
              final rows = (keys.length / cols).ceil().clamp(1, 8);
              final tileH = ((box.maxHeight - 16 - gap * (rows - 1)) / rows)
                  .clamp(84.0, 132.0);
              return GridView.builder(
                padding: const EdgeInsets.only(bottom: 16),
                gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: cols,
                  mainAxisExtent: tileH,
                  crossAxisSpacing: gap,
                  mainAxisSpacing: gap,
                ),
                itemCount: keys.length,
                itemBuilder: (_, i) {
                  final (item, key) = keys[i];
                  return ProductTile(
                    key: Key('key-${item.id}'),
                    item: item,
                    pinned: key?.pinned == true,
                    editing: _editKeys,
                    onTap: () => _editKeys ? _togglePin(item) : _addItem(item),
                    onLongPress: () => _pinMenu(item),
                  );
                },
              );
            },
          ),
        ),
      ],
    );
  }

  Widget _topList(BuildContext context) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final rows = _topSellers;
    final all = _top.isNotEmpty ? _top.length : (_items.length / 5).ceil();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(
          height: 42,
          child: ListView(
            scrollDirection: Axis.horizontal,
            children: [
              CounterChip(
                label: r.all,
                selected: _topCategory == 'all',
                onTap: () => setState(() => _topCategory = 'all'),
              ),
              for (final id in _categoryIds)
                CounterChip(
                  key: Key('top-cat-$id'),
                  label: _categoryName(id),
                  dot: departmentHue(context, id),
                  selected: _topCategory == id,
                  onTap: () => setState(() => _topCategory = id),
                ),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(4, 10, 4, 8),
          child: Text(
            r.topSellersNote(all, _items.length),
            style: s.text(size: 13.5, color: c.textMuted),
          ),
        ),
        Expanded(
          child: ClipRRect(
            borderRadius: s.radiusMedium,
            child: ColoredBox(
              color: c.surface,
              child: ListView.separated(
                padding: EdgeInsets.zero,
                itemCount: rows.length,
                separatorBuilder: (_, _) => Divider(
                  height: 1,
                  indent: 16,
                  endIndent: 16,
                  color: c.border,
                ),
                itemBuilder: (_, i) {
                  final (item, units) = rows[i];
                  return SizedBox(
                    height: ProductRow.extent,
                    child: ProductRow(
                      item: item,
                      leading: '${i + 1}',
                      note: units > 0 ? r.sold(units, 28) : r.notSoldYet,
                      noteStrong: units > 0,
                      onTap: () => _addItem(item),
                      onLongPress: () => _pinMenu(item),
                    ),
                  );
                },
              ),
            ),
          ),
        ),
        const SizedBox(height: 16),
      ],
    );
  }

  Widget _results(BuildContext context) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final q = _search.text.trim();
    final hits = _index.search(q, limit: 201);
    if (hits.isEmpty) {
      return Center(
        child: Text(r.noMatch, style: s.text(size: 17, color: c.textMuted)),
      );
    }
    final capped = hits.length > 200;
    final shown = capped ? hits.sublist(0, 200) : hits;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(4, 0, 4, 10),
          child: Text(
            capped ? r.resultsCapped : r.results(shown.length, q),
            style: s.text(
              size: 14,
              weight: FontWeight.w600,
              color: c.textMuted,
            ),
          ),
        ),
        Expanded(
          child: ClipRRect(
            borderRadius: s.radiusMedium,
            child: ColoredBox(
              color: c.surface,
              child: ListView.separated(
                padding: EdgeInsets.zero,
                itemCount: shown.length,
                separatorBuilder: (_, _) => Divider(
                  height: 1,
                  indent: 16,
                  endIndent: 16,
                  color: c.border,
                ),
                itemBuilder: (_, i) {
                  final item = shown[i];
                  final units = _unitsById[item.id] ?? 0;
                  return SizedBox(
                    height: ProductRow.extent,
                    child: ProductRow(
                      item: item,
                      note: units > 0 ? r.sold(units, 28) : null,
                      noteStrong: true,
                      onTap: () {
                        _search.clear();
                        setState(() {});
                        _addItem(item);
                      },
                      onLongPress: () => _pinMenu(item),
                    ),
                  );
                },
              ),
            ),
          ),
        ),
        const SizedBox(height: 16),
      ],
    );
  }

  Widget _browse(BuildContext context) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final cats = _index.facet('category');
    final cat = _cat;
    if (cat == null) {
      // step 1: pick a department
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(4, 0, 4, 10),
            child: Text(
              r.pickDepartment,
              style: s.text(
                size: 14,
                weight: FontWeight.w600,
                color: c.textMuted,
              ),
            ),
          ),
          Expanded(
            child: GridView.builder(
              padding: const EdgeInsets.only(bottom: 16),
              gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                maxCrossAxisExtent: 250,
                mainAxisExtent: 112,
                crossAxisSpacing: 10,
                mainAxisSpacing: 10,
              ),
              itemCount: _categoryIds.length,
              itemBuilder: (_, i) {
                final id = _categoryIds[i];
                final hue = departmentHue(context, id);
                return Material(
                  color: c.surface,
                  shape: RoundedRectangleBorder(borderRadius: s.tileRadius),
                  clipBehavior: Clip.antiAlias,
                  child: InkWell(
                    key: Key('cat-$id'),
                    onTap: () => setState(() {
                      _cat = id;
                      _sub = null;
                      _size = null;
                    }),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Container(width: 6, color: hue),
                        Expanded(
                          child: Padding(
                            padding: const EdgeInsets.all(16),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                Text(
                                  _categoryName(id),
                                  maxLines: 2,
                                  style: s.text(
                                    size: 18,
                                    weight: FontWeight.w800,
                                    color: c.text,
                                  ),
                                ),
                                const SizedBox(height: 4),
                                Text(
                                  r.products(cats[id] ?? 0),
                                  style: s.text(size: 13.5, color: c.textMuted),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      );
    }
    final subs = _index.facet('subcategory', category: cat, size: _size);
    final sizes = _index.facet('size', category: cat, subcategory: _sub);
    final subKeys = subs.keys.toList()
      ..sort((a, b) => subs[b]!.compareTo(subs[a]!));
    final sizeKeys = sizes.keys.toList()..sort(compareSizes);
    final shown = _index.filter(category: cat, subcategory: _sub, size: _size);
    final crumbs = [
      _categoryName(cat),
      if (_sub != null) r.shelfName(_sub!),
      if (_size != null) r.sizeName(_size!),
    ].join(' › ');
    Widget chipRow(List<Widget> chips) => SizedBox(
      height: 42,
      child: ListView(scrollDirection: Axis.horizontal, children: chips),
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        chipRow([
          for (final id in _categoryIds)
            CounterChip(
              key: Key('cat-$id'),
              label: _categoryName(id),
              dot: departmentHue(context, id),
              count: cats[id],
              selected: id == cat,
              onTap: () => setState(() {
                _cat = id;
                _sub = null;
                _size = null;
              }),
            ),
        ]),
        const SizedBox(height: 8),
        chipRow([
          CounterChip(
            label: r.allStyles,
            selected: _sub == null,
            onTap: () => setState(() => _sub = null),
          ),
          for (final v in subKeys)
            CounterChip(
              key: Key('sub-$v'),
              label: r.shelfName(v),
              count: subs[v],
              selected: _sub == v,
              onTap: () => setState(() => _sub = _sub == v ? null : v),
            ),
        ]),
        const SizedBox(height: 8),
        chipRow([
          CounterChip(
            label: r.allSizes,
            selected: _size == null,
            onTap: () => setState(() => _size = null),
          ),
          for (final v in sizeKeys)
            CounterChip(
              key: Key('size-$v'),
              label: r.sizeName(v),
              count: sizes[v],
              selected: _size == v,
              onTap: () => setState(() => _size = _size == v ? null : v),
            ),
        ]),
        Padding(
          padding: const EdgeInsets.fromLTRB(4, 10, 0, 6),
          child: Row(
            children: [
              Expanded(
                child: Text.rich(
                  TextSpan(
                    children: [
                      TextSpan(
                        text: crumbs,
                        style: s.text(
                          size: 16,
                          weight: FontWeight.w800,
                          color: c.text,
                        ),
                      ),
                      TextSpan(
                        text: '  ·  ${r.products(shown.length)}',
                        style: s.text(size: 14, color: c.textMuted),
                      ),
                    ],
                  ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              TextButton.icon(
                key: const Key('browse-reset'),
                onPressed: () => setState(() {
                  _cat = null;
                  _sub = null;
                  _size = null;
                }),
                icon: Icon(s.glyphs.reset, size: 18),
                label: Text(r.reset),
              ),
            ],
          ),
        ),
        Expanded(
          child: GridView.builder(
            padding: const EdgeInsets.only(bottom: 16),
            gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
              maxCrossAxisExtent: 170,
              mainAxisExtent: 104,
              crossAxisSpacing: 8,
              mainAxisSpacing: 8,
            ),
            itemCount: shown.length,
            itemBuilder: (_, i) => ProductTile(
              item: shown[i],
              pinned: _isPinned(shown[i].id),
              onTap: () => _addItem(shown[i]),
              onLongPress: () => _pinMenu(shown[i]),
            ),
          ),
        ),
      ],
    );
  }

  // ---- the docked basket ----

  Widget _basket(BuildContext context) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final sale = _sale;
    final lines = sale?.lines ?? const <CheckLine>[];
    final count = lines.fold<int>(0, (n, l) => n + l.qty);
    final crv =
        sale?.fees
            .where((f) => f.code == 'crv')
            .fold<int>(0, (n, f) => n + f.amountCents) ??
        0;
    return Container(
      color: c.surface,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(22, 20, 22, 12),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text(
                  sale == null
                      ? r.sale(0).replaceAll('#0', '').trim()
                      : r.sale(sale.id),
                  style: s.text(
                    size: 22,
                    weight: FontWeight.w800,
                    color: c.text,
                  ),
                ),
                const Spacer(),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 4,
                  ),
                  decoration: BoxDecoration(
                    color: c.sageMist,
                    borderRadius: BorderRadius.circular(40),
                  ),
                  child: Text(
                    r.items(count),
                    style: s.text(
                      size: 13.5,
                      weight: FontWeight.w700,
                      color: c.strong,
                    ),
                  ),
                ),
              ],
            ),
          ),
          Expanded(
            child: lines.isEmpty
                ? _emptyBasket(context)
                : ListView.separated(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    itemCount: lines.length,
                    separatorBuilder: (_, _) => Divider(
                      height: 1,
                      indent: 22,
                      endIndent: 22,
                      color: c.border,
                    ),
                    itemBuilder: (_, i) => _line(context, lines[i]),
                  ),
          ),
          if (sale != null && sale.ageCheckRequired) _ageBanner(context, sale),
          Padding(
            padding: const EdgeInsets.fromLTRB(22, 12, 22, 18),
            child: Column(
              children: [
                _sum(
                  context,
                  r.itemsSubtotal,
                  money(sale?.itemsSubtotalCents ?? 0),
                ),
                if (crv > 0) _sum(context, r.crvLine, money(crv)),
                for (final t in sale?.taxes ?? const <TaxLine>[])
                  _sum(
                    context,
                    r.taxLine(t.labelEn, t.ratePercent),
                    money(t.amountCents),
                  ),
                const SizedBox(height: 8),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Text(
                      r.total,
                      style: s.text(
                        size: 20,
                        weight: FontWeight.w700,
                        color: c.text,
                      ),
                    ),
                    const Spacer(),
                    Text(
                      money(sale?.grandTotalCents ?? 0),
                      style: s.figures(
                        size: 34,
                        weight: FontWeight.w800,
                        color: c.text,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 14),
                SizedBox(
                  height: 74,
                  width: double.infinity,
                  child: FilledButton.icon(
                    style: FilledButton.styleFrom(
                      backgroundColor: c.poppy,
                      foregroundColor: c.onPoppy,
                      disabledBackgroundColor: c.surfaceAlt,
                      shape: s.controlShape(),
                    ),
                    onPressed: lines.isEmpty || _busy ? null : _pay,
                    icon: Icon(s.glyphs.pay, size: 28),
                    label: Text(
                      '${r.pay}  ${money(sale?.outstandingCents ?? 0)}',
                      style: s.text(
                        size: 23,
                        weight: FontWeight.w800,
                        color: lines.isEmpty ? c.textMuted : c.onPoppy,
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _emptyBasket(BuildContext context) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 84,
              height: 84,
              decoration: BoxDecoration(
                color: c.sageMist,
                shape: BoxShape.circle,
              ),
              child: Icon(s.glyphs.scan, size: 42, color: c.strong),
            ),
            const SizedBox(height: 16),
            Text(
              r.emptyBasketTitle,
              style: s.text(size: 20, weight: FontWeight.w800, color: c.text),
            ),
            const SizedBox(height: 4),
            Text(
              r.emptyBasketHint,
              textAlign: TextAlign.center,
              style: s.text(size: 15, color: c.textMuted),
            ),
          ],
        ),
      ),
    );
  }

  Widget _sum(BuildContext context, String label, String value) {
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2.5),
      child: Row(
        children: [
          Text(label, style: s.text(size: 15.5, color: c.textMuted)),
          const Spacer(),
          Text(
            value,
            style: s.figures(size: 16, weight: FontWeight.w600, color: c.text),
          ),
        ],
      ),
    );
  }

  Widget _tag(BuildContext context, String text, Color bg, Color fg) {
    final s = BrandSkin.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(40),
      ),
      child: Text(
        text,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: s.text(size: 11.5, weight: FontWeight.w800, color: fg),
      ),
    );
  }

  Widget _line(BuildContext context, CheckLine l) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 10),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  l.nameEn,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: s.text(
                    size: 15.5,
                    weight: FontWeight.w700,
                    color: c.text,
                    height: 1.2,
                  ),
                ),
                const SizedBox(height: 4),
                Wrap(
                  spacing: 6,
                  runSpacing: 4,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    Text(
                      r.each(money(l.unitPriceCents)),
                      style: s.text(size: 13, color: c.textMuted),
                    ),
                    if (l.ageRestricted)
                      _tag(context, r.agePill, c.poppySoft, c.poppy),
                    if (l.depositCents > 0)
                      _tag(
                        context,
                        r.crvPill(money(l.depositCents * l.qty)),
                        c.surfaceAlt,
                        c.textMuted,
                      ),
                    if (!l.taxable)
                      _tag(context, r.noTax, c.surfaceAlt, c.textMuted),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Container(
            height: 42,
            decoration: BoxDecoration(
              color: c.sageMist,
              borderRadius: BorderRadius.circular(40),
            ),
            child: Row(
              children: [
                _qtyButton(
                  context,
                  s.glyphs.minus,
                  () => _setQty(l, l.qty - 1),
                ),
                SizedBox(
                  width: 26,
                  child: Text(
                    '${l.qty}',
                    textAlign: TextAlign.center,
                    style: s.figures(
                      size: 17,
                      weight: FontWeight.w800,
                      color: c.text,
                    ),
                  ),
                ),
                _qtyButton(context, s.glyphs.plus, () => _setQty(l, l.qty + 1)),
              ],
            ),
          ),
          SizedBox(
            width: 84,
            child: Text(
              money(l.lineTotalCents),
              textAlign: TextAlign.right,
              style: s.figures(
                size: 16.5,
                weight: FontWeight.w700,
                color: c.text,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _qtyButton(BuildContext context, IconData icon, VoidCallback onTap) {
    final c = SpColors.of(context);
    return SizedBox(
      width: 42,
      height: 42,
      child: IconButton(
        padding: EdgeInsets.zero,
        onPressed: _busy ? null : onTap,
        icon: Icon(icon, size: 20, color: c.strong),
      ),
    );
  }

  Widget _ageBanner(BuildContext context, Check sale) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final g = s.glyphs;
    final (bg, fg, icon, text, action) = sale.ageCleared
        ? (c.okSoft, c.ok, g.idOk, r.idCheckedShort, null)
        : sale.ageCheckFailed
        ? (c.badSoft, c.bad, g.idBad, r.idFailedBanner, r.removeRestricted)
        : (c.poppySoft, c.poppy, g.idCard, r.idCheckNeeded, r.checkId);
    return Container(
      margin: const EdgeInsets.fromLTRB(14, 4, 14, 4),
      padding: const EdgeInsets.fromLTRB(16, 8, 8, 8),
      decoration: BoxDecoration(color: bg, borderRadius: s.radiusMedium),
      child: Row(
        children: [
          Icon(icon, color: fg),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              text,
              style: s.text(size: 15, weight: FontWeight.w700, color: fg),
            ),
          ),
          if (action != null)
            TextButton(
              onPressed: sale.ageCheckFailed ? _removeRestricted : _checkId,
              style: TextButton.styleFrom(foregroundColor: fg),
              child: Text(action),
            ),
        ],
      ),
    );
  }
}
