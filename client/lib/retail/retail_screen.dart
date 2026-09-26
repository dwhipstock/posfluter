import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';
import '../screens/login_screen.dart';
import '../screens/receipt_screen.dart';
import '../screens/shift_screen.dart';
import '../widgets/brand.dart';
import 'add_product_dialog.dart';
import 'age_check_dialog.dart';
import 'pay_sheet.dart';
import 'retail_i18n.dart';
import 'scan_detector.dart';
import 'sp_theme.dart';

/// The retail counter (a store whose profile kind is retail): scan or search
/// → a basket with qty −/+ → ID check when it holds age-restricted items →
/// pay (cash, or card on the counter's own terminal) → receipt → next sale.
/// No tables, no floor plan, no kitchen. Landscape tablet first.
///
/// Scanning: a USB / Bluetooth HID scanner types the code and Enter. Every key
/// on this screen goes through a [HidScanner]: a fast burst ending in Enter
/// is a scan (even while the search box has focus — the burst is taken back
/// out of it); anything slower is a person typing. The tablet camera can plug
/// in later as another [BarcodeSource].
class RetailScreen extends StatefulWidget {
  /// Extra barcode sources (the camera, later). The HID scanner is built in.
  final List<BarcodeSource> sources;
  const RetailScreen({super.key, this.sources = const []});

  @override
  State<RetailScreen> createState() => _RetailScreenState();
}

class _RetailScreenState extends State<RetailScreen> {
  final _scanner = HidScanner();
  final _search = TextEditingController();
  final _searchFocus = FocusNode();
  final _subs = <dynamic>[];

  Check? _sale;
  List<Item> _items = [];
  List<Category> _categories = [];
  String _category = 'all';
  ShiftInfo? _shift;
  bool _shiftLoaded = false;
  bool _busy = false;
  bool _dialogOpen = false;
  String? _lastScan;
  DateTime? _lastScanAt;

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
        Api.items(),
        Api.categories(),
        Api.currentShift(),
      ]);
      if (!mounted) return;
      setState(() {
        _items = results[0] as List<Item>;
        _categories = results[1] as List<Category>;
        _shift = results[2] as ShiftInfo?;
        _shiftLoaded = true;
      });
      // a restarted terminal picks up the sale in progress; otherwise the
      // first scan starts one
      final current = await Api.currentSale();
      if (mounted) setState(() => _sale = current);
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
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
      _scan(q);
      return;
    }
    final hits = _visible;
    if (hits.length == 1) {
      _search.clear();
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
      final items = await Api.items();
      if (mounted) setState(() => _items = items);
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
  }

  Future<void> _openRegister() async {
    final r = R.of(context);
    final c = SpColors.of(context);
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
              style: FilledButton.styleFrom(backgroundColor: c.sage),
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

  Future<void> _signOut() async {
    await Api.logout();
    if (!mounted) return;
    Navigator.of(context).pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => const LoginScreen()),
      (_) => false,
    );
  }

  // ---- view ----

  List<Item> get _visible {
    final q = _search.text.trim().toLowerCase();
    return _items.where((i) {
      if (_category != 'all' && i.category != _category) return false;
      if (q.isEmpty) return true;
      return i.nameEn.toLowerCase().contains(q) ||
          (i.barcode ?? '').contains(q);
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    final c = SpColors.of(context);
    return Scaffold(
      backgroundColor: c.background,
      body: SafeArea(
        child: Column(
          children: [
            _header(context),
            Expanded(
              child: LayoutBuilder(
                builder: (context, box) {
                  final basketWidth = (box.maxWidth * .36).clamp(360.0, 460.0);
                  return Row(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Expanded(child: _catalog(context)),
                      Container(width: 1, color: c.border),
                      SizedBox(width: basketWidth, child: _basket(context)),
                    ],
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _header(BuildContext context) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final user = Api.currentUser;
    return Container(
      height: 72,
      padding: const EdgeInsets.symmetric(horizontal: 18),
      decoration: BoxDecoration(color: c.sageDeep),
      child: Row(
        children: [
          const BrandLogo(size: 52, ring: true),
          const SizedBox(width: 12),
          Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Sage & Poppy',
                style: T.text(
                  size: 22,
                  weight: FontWeight.w700,
                  color: Colors.white,
                ),
              ),
              Text(
                '${r.bottleShop} · ${r.register1}',
                style: T.text(
                  size: 13,
                  weight: FontWeight.w500,
                  color: const Color(0xFFD7E4D3),
                ),
              ),
            ],
          ),
          const Spacer(),
          _pill(
            icon: LucideIcons.scanBarcode,
            label: r.scannerReady,
            fg: Colors.white,
            bg: Colors.white.withValues(alpha: .12),
          ),
          const SizedBox(width: 12),
          if (user != null) ...[
            Icon(LucideIcons.user, size: 18, color: const Color(0xFFD7E4D3)),
            const SizedBox(width: 6),
            Text(
              user.name,
              style: T.text(
                size: 15,
                weight: FontWeight.w600,
                color: Colors.white,
              ),
            ),
            const SizedBox(width: 8),
          ],
          const LangActions(color: Colors.white),
          PopupMenuButton<String>(
            tooltip: r.more,
            icon: const Icon(LucideIcons.ellipsisVertical, color: Colors.white),
            onSelected: (v) async {
              if (v == 'open') await _openRegister();
              if (v == 'close' && context.mounted) {
                await Navigator.of(
                  context,
                ).push(MaterialPageRoute(builder: (_) => const ShiftScreen()));
                final shift = await Api.currentShift();
                if (mounted) setState(() => _shift = shift);
              }
              if (v == 'out') await _signOut();
            },
            itemBuilder: (_) => [
              if (_shift == null)
                PopupMenuItem(value: 'open', child: Text(r.openRegister)),
              if (_shift != null)
                PopupMenuItem(value: 'close', child: Text(r.closeRegister)),
              PopupMenuItem(value: 'out', child: Text(r.signOut)),
            ],
          ),
        ],
      ),
    );
  }

  Widget _pill({
    required IconData icon,
    required String label,
    required Color fg,
    required Color bg,
  }) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
    decoration: BoxDecoration(
      color: bg,
      borderRadius: BorderRadius.circular(40),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 16, color: fg),
        const SizedBox(width: 6),
        Text(
          label,
          style: T.text(size: 14, weight: FontWeight.w600, color: fg),
        ),
      ],
    ),
  );

  Widget _catalog(BuildContext context) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final visible = _visible;
    return Padding(
      padding: const EdgeInsets.fromLTRB(18, 16, 18, 0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (_shiftLoaded && _shift == null) ...[
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              decoration: BoxDecoration(
                color: c.warnSoft,
                borderRadius: T.radiusMedium,
              ),
              child: Row(
                children: [
                  Icon(LucideIcons.lockKeyhole, color: c.warn, size: 20),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      r.registerClosed,
                      style: T.text(
                        size: 15,
                        color: c.warn,
                        weight: FontWeight.w600,
                      ),
                    ),
                  ),
                  TextButton(
                    onPressed: _openRegister,
                    child: Text(r.openRegister),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),
          ],
          TextField(
            controller: _search,
            focusNode: _searchFocus,
            onChanged: (_) => setState(() {}),
            onSubmitted: _onSearchSubmitted,
            textInputAction: TextInputAction.search,
            decoration: InputDecoration(
              hintText: r.searchHint,
              prefixIcon: Icon(LucideIcons.search, color: c.textMuted),
              suffixIcon: _search.text.isEmpty
                  ? Icon(LucideIcons.scanBarcode, color: c.sage)
                  : IconButton(
                      icon: const Icon(LucideIcons.x),
                      onPressed: () => setState(_search.clear),
                    ),
            ),
          ),
          const SizedBox(height: 12),
          SizedBox(
            height: 44,
            child: ListView(
              scrollDirection: Axis.horizontal,
              children: [
                _chip('all', r.all),
                for (final cat in _categories)
                  _chip(cat.id, r.category(cat.id, cat.nameEn)),
              ],
            ),
          ),
          const SizedBox(height: 12),
          Expanded(
            child: visible.isEmpty
                ? Center(
                    child: Text(r.noMatch, style: T.text(color: c.textMuted)),
                  )
                : GridView.builder(
                    padding: const EdgeInsets.only(bottom: 18),
                    gridDelegate:
                        const SliverGridDelegateWithMaxCrossAxisExtent(
                          maxCrossAxisExtent: 230,
                          mainAxisExtent: 148,
                          crossAxisSpacing: 12,
                          mainAxisSpacing: 12,
                        ),
                    itemCount: visible.length,
                    itemBuilder: (_, i) => _tile(context, visible[i]),
                  ),
          ),
        ],
      ),
    );
  }

  Widget _chip(String id, String label) {
    final selected = _category == id;
    final c = SpColors.of(context);
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: ChoiceChip(
        selected: selected,
        onSelected: (_) => setState(() => _category = id),
        label: Text(
          label,
          style: T.text(
            size: 15,
            weight: FontWeight.w600,
            color: selected ? c.onSage : c.text,
          ),
        ),
      ),
    );
  }

  static const _catHues = {
    'beer': Color(0xFFB7791F),
    'wine': Color(0xFF8C2F4B),
    'spirits': Color(0xFF6B4C2A),
    'seltzers': Color(0xFF2F7A78),
    'mixers': Color(0xFF3D6B8C),
    'snacks': Color(0xFFBF5317),
    'ice': Color(0xFF4E7FA0),
  };

  Widget _tile(BuildContext context, Item item) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final hue = _catHues[item.category] ?? c.sage;
    final price = item.variants.isEmpty ? 0 : item.variants.first.priceCents;
    return Material(
      color: c.surface,
      shape: RoundedRectangleBorder(
        borderRadius: T.radiusLarge,
        side: BorderSide(color: c.border),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => _addItem(item),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    width: 36,
                    height: 36,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: hue.withValues(alpha: .14),
                      borderRadius: T.radiusSmall,
                    ),
                    child: Text(
                      item.abbrev,
                      style: T.text(
                        size: 14,
                        weight: FontWeight.w700,
                        color: hue,
                      ),
                    ),
                  ),
                  const Spacer(),
                  if (item.ageRestricted) _tag(r.agePill, c.poppy, c.onPoppy),
                ],
              ),
              const SizedBox(height: 8),
              Expanded(
                child: Text(
                  item.nameEn,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: T.text(
                    size: 15,
                    weight: FontWeight.w600,
                    color: c.text,
                  ),
                ),
              ),
              Row(
                children: [
                  Text(
                    money(price),
                    style: T.price(
                      size: 19,
                      weight: FontWeight.w700,
                      color: c.text,
                    ),
                  ),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Align(
                      alignment: Alignment.centerRight,
                      child: item.depositCents > 0
                          ? _tag(
                              r.crvPill(money(item.depositCents)),
                              c.surfaceAlt,
                              c.textMuted,
                            )
                          : !item.taxable
                          ? _tag(r.noTax, c.surfaceAlt, c.textMuted)
                          : const SizedBox.shrink(),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _tag(String text, Color bg, Color fg) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
    decoration: BoxDecoration(
      color: bg,
      borderRadius: BorderRadius.circular(6),
    ),
    child: Text(
      text,
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: T.text(size: 12, weight: FontWeight.w700, color: fg),
    ),
  );

  Widget _basket(BuildContext context) {
    final r = R.of(context);
    final c = SpColors.of(context);
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
            padding: const EdgeInsets.fromLTRB(18, 16, 18, 10),
            child: Row(
              children: [
                Text(
                  sale == null
                      ? r.sale(0).replaceAll('#0', '')
                      : r.sale(sale.id),
                  style: T.text(
                    size: 20,
                    weight: FontWeight.w700,
                    color: c.text,
                  ),
                ),
                const Spacer(),
                Text(
                  r.items(count),
                  style: T.text(size: 15, color: c.textMuted),
                ),
              ],
            ),
          ),
          Divider(height: 1, color: c.border),
          Expanded(
            child: lines.isEmpty
                ? _emptyBasket(r, c)
                : ListView.separated(
                    padding: const EdgeInsets.symmetric(vertical: 6),
                    itemCount: lines.length,
                    separatorBuilder: (_, _) => Divider(
                      height: 1,
                      indent: 18,
                      endIndent: 18,
                      color: c.border,
                    ),
                    itemBuilder: (_, i) => _line(context, lines[i]),
                  ),
          ),
          if (sale != null && sale.ageCheckRequired) _ageBanner(context, sale),
          Container(
            padding: const EdgeInsets.fromLTRB(18, 12, 18, 16),
            decoration: BoxDecoration(
              color: c.surfaceAlt,
              border: Border(top: BorderSide(color: c.border)),
            ),
            child: Column(
              children: [
                _sum(r.itemsSubtotal, money(sale?.itemsSubtotalCents ?? 0), c),
                if (crv > 0) _sum(r.crvLine, money(crv), c),
                for (final t in sale?.taxes ?? const <TaxLine>[])
                  _sum(
                    r.taxLine(t.labelEn, t.ratePercent),
                    money(t.amountCents),
                    c,
                  ),
                const SizedBox(height: 6),
                Row(
                  children: [
                    Text(
                      r.total,
                      style: T.text(
                        size: 22,
                        weight: FontWeight.w700,
                        color: c.text,
                      ),
                    ),
                    const Spacer(),
                    Text(
                      money(sale?.grandTotalCents ?? 0),
                      style: T.price(
                        size: 32,
                        weight: FontWeight.w800,
                        color: c.text,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                FilledButton.icon(
                  style: FilledButton.styleFrom(
                    backgroundColor: c.poppy,
                    foregroundColor: c.onPoppy,
                    minimumSize: const Size.fromHeight(64),
                  ),
                  onPressed: lines.isEmpty || _busy ? null : _pay,
                  icon: const Icon(LucideIcons.wallet, size: 24),
                  label: Text(
                    '${r.pay}  ${money(sale?.outstandingCents ?? 0)}',
                    style: T.text(
                      size: 22,
                      weight: FontWeight.w700,
                      color: c.onPoppy,
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

  Widget _emptyBasket(R r, SpColors c) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            LucideIcons.scanBarcode,
            size: 56,
            color: c.sage.withValues(alpha: .7),
          ),
          const SizedBox(height: 12),
          Text(
            r.emptyBasketTitle,
            style: T.text(size: 20, weight: FontWeight.w700, color: c.text),
          ),
          const SizedBox(height: 4),
          Text(
            r.emptyBasketHint,
            textAlign: TextAlign.center,
            style: T.text(size: 15, color: c.textMuted),
          ),
        ],
      ),
    ),
  );

  Widget _sum(String label, String value, SpColors c) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 2),
    child: Row(
      children: [
        Text(label, style: T.text(size: 16, color: c.textMuted)),
        const Spacer(),
        Text(value, style: T.price(size: 17, color: c.text)),
      ],
    ),
  );

  Widget _line(BuildContext context, CheckLine l) {
    final r = R.of(context);
    final c = SpColors.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
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
                  style: T.text(
                    size: 16,
                    weight: FontWeight.w600,
                    color: c.text,
                  ),
                ),
                const SizedBox(height: 3),
                Wrap(
                  spacing: 6,
                  runSpacing: 4,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    Text(
                      r.each(money(l.unitPriceCents)),
                      style: T.text(size: 13, color: c.textMuted),
                    ),
                    if (l.ageRestricted) _tag(r.agePill, c.poppySoft, c.poppy),
                    if (l.depositCents > 0)
                      _tag(
                        r.crvPill(money(l.depositCents * l.qty)),
                        c.surfaceAlt,
                        c.textMuted,
                      ),
                    if (!l.taxable) _tag(r.noTax, c.surfaceAlt, c.textMuted),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          _qtyButton(LucideIcons.minus, () => _setQty(l, l.qty - 1), c),
          SizedBox(
            width: 34,
            child: Text(
              '${l.qty}',
              textAlign: TextAlign.center,
              style: T.price(size: 18, weight: FontWeight.w700, color: c.text),
            ),
          ),
          _qtyButton(LucideIcons.plus, () => _setQty(l, l.qty + 1), c),
          SizedBox(
            width: 82,
            child: Text(
              money(l.lineTotalCents),
              textAlign: TextAlign.right,
              style: T.price(size: 17, weight: FontWeight.w600, color: c.text),
            ),
          ),
        ],
      ),
    );
  }

  Widget _qtyButton(IconData icon, VoidCallback onTap, SpColors c) => SizedBox(
    width: 40,
    height: 40,
    child: IconButton.outlined(
      padding: EdgeInsets.zero,
      style: IconButton.styleFrom(side: BorderSide(color: c.border)),
      onPressed: _busy ? null : onTap,
      icon: Icon(icon, size: 18, color: c.text),
    ),
  );

  Widget _ageBanner(BuildContext context, Check sale) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final (bg, fg, icon, text, action) = sale.ageCleared
        ? (c.okSoft, c.ok, LucideIcons.shieldCheck, r.idCheckedShort, null)
        : sale.ageCheckFailed
        ? (
            c.badSoft,
            c.bad,
            LucideIcons.shieldX,
            r.idFailedBanner,
            r.removeRestricted,
          )
        : (
            c.poppySoft,
            c.poppy,
            LucideIcons.idCard,
            r.idCheckNeeded,
            r.checkId,
          );
    return Container(
      margin: const EdgeInsets.fromLTRB(12, 0, 12, 10),
      padding: const EdgeInsets.fromLTRB(14, 10, 8, 10),
      decoration: BoxDecoration(color: bg, borderRadius: T.radiusMedium),
      child: Row(
        children: [
          Icon(icon, color: fg),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              text,
              style: T.text(size: 15, weight: FontWeight.w700, color: fg),
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
