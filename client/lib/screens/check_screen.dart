import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import '../kitchen/kitchen_banner.dart';
import '../kitchen/kitchen_i18n.dart';
import '../quickserve/quick_serve_i18n.dart';
import '../widgets/item_photo.dart';
import '../widgets/pin_pad.dart';
import '../widgets/print_language_picker.dart';
import '../widgets/resume_refresh.dart';
import 'bill_preview_screen.dart';
import 'split_screen.dart';
import 'table_picker_screen.dart';
import 'tender_screen.dart';
import '../widgets/tax_rows.dart';

/// Three-column workspace: nav rail | menu grid | sticky bill panel.
/// The bill is always visible while ordering — no separate basket screen.
class CheckScreen extends StatefulWidget {
  final int checkId;
  final String tableLabel;

  /// A quick-serve counter order: no table to move it to, and the header
  /// shows the order, not a table. The counter gets a fast layout: every
  /// category on a rail, a dense grid, one tap = one more.
  final bool counterOrder;

  /// Counter, embedded in the counter screen (the one flow): [checkId] 0 is
  /// a new order that exists only here until its first item, which
  /// [createOrder] stores (returns the new check id). [onFinished] replaces
  /// leaving the screen: paid (true) or gone (emptied / discarded: false).
  /// [onDiscard] drops the unpaid order (the bin on the rail), and
  /// [panelTop] sits at the top of the order panel (dine in / take out).
  final Future<int> Function(Item item, Variant variant, int qty, String? note)?
  createOrder;
  final void Function(bool paid)? onFinished;
  final Future<void> Function()? onDiscard;
  final Widget? panelTop;
  const CheckScreen({
    super.key,
    required this.checkId,
    required this.tableLabel,
    this.counterOrder = false,
    this.createOrder,
    this.onFinished,
    this.onDiscard,
    this.panelTop,
  });

  /// The empty order the counter shows before its first item (never stored).
  static Check emptyOrder() =>
      Check(0, '', 'OPEN', 0, [], [], [], 0, 0, 0, 0, [], null);

  @override
  State<CheckScreen> createState() => _CheckScreenState();
}

class _CheckScreenState extends State<CheckScreen> with ResumeRefresh {
  @override
  void onAppResume() => _refreshCheck();

  Check? _check;

  /// The check on screen: [CheckScreen.checkId], or (counter) the order its
  /// first item just created; 0 = a new order not stored yet.
  late int _checkId = widget.checkId;

  /// Counter: the first item's "create the order" call, while it runs (the
  /// taps after it wait for it, so one order is created, not two).
  Future<int>? _creating;

  /// Embedded on the counter screen: never pops, tells the counter instead.
  bool get _embedded => widget.onFinished != null;
  List<Item> _items = [];
  List<Category> _categories = [];
  String? _category;
  String? _error;
  Timer? _poll;

  /// Kitchen tickets (store has kitchen.printing=on): what a Send would print.
  KitchenCheckState? _kitchen;
  bool _sending = false;

  /// Counter: the tile just tapped flashes; "All" shows every category.
  String? _flashItem;
  Timer? _flashTimer;
  static const _allCategories = '*';

  @override
  void initState() {
    super.initState();
    _load();
    // pick up QR-submitted pending lines while the screen is open. TODO: push/SSE
    _poll = Timer.periodic(const Duration(seconds: 5), (_) => _refreshCheck());
  }

  @override
  void didUpdateWidget(CheckScreen old) {
    super.didUpdateWidget(old);
    // the counter moved on: the next new order, or a kiosk order to pay
    if (widget.checkId != old.checkId && widget.checkId != _checkId) {
      _checkId = widget.checkId;
      _creating = null;
      _check = _checkId == 0 ? CheckScreen.emptyOrder() : null;
      if (_checkId != 0) _refreshCheck();
    }
  }

  @override
  void dispose() {
    _poll?.cancel();
    _flashTimer?.cancel();
    // leaving the bill sends whatever the kitchen doesn't have yet (best
    // effort, never blocks; the store already handles voids on its own).
    // A counter order goes to the kitchen when it is paid, by the store.
    if (KitchenApi.enabled && !widget.counterOrder) {
      KitchenApi.sendQuietly(_checkId);
    }
    super.dispose();
  }

  /// Paid, emptied or voided: back to where we came from, or (counter) on
  /// to the next order.
  void _leave({bool paid = false}) {
    if (_embedded) {
      widget.onFinished!(paid);
    } else {
      Navigator.of(context).pop();
    }
  }

  Future<void> _refreshCheck() async {
    if (_checkId == 0) return; // a new counter order: nothing stored yet
    final id = _checkId;
    try {
      final check = await Api.getCheck(id);
      // the counter may have moved on to another order meanwhile
      if (mounted && id == _checkId) setState(() => _check = check);
    } catch (_) {} // transient poll failures are fine
    _refreshKitchen();
  }

  Future<void> _refreshKitchen() async {
    if (!KitchenApi.enabled || widget.counterOrder) return;
    try {
      final k = await KitchenApi.checkState(_checkId);
      if (mounted) setState(() => _kitchen = k);
    } catch (_) {} // the Send count just stays as it was
  }

  /// Send new / changed items to their stations now.
  Future<void> _sendToKitchen() async {
    if (_sending) return;
    final k = K.of(context);
    setState(() => _sending = true);
    try {
      final r = await KitchenApi.send(_checkId);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(r.tickets == 0 ? k.nothingToSend : k.sentTo(r.tickets)),
        ),
      );
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _sending = false);
      _refreshKitchen();
    }
  }

  Future<void> _reprintKitchen() async {
    final k = K.of(context);
    try {
      final r = await KitchenApi.reprint(_checkId);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            r.tickets == 0 ? k.nothingToReprint : k.reprinted(r.tickets),
          ),
        ),
      );
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _setGuests() async {
    final k = K.of(context);
    final picked = await showDialog<int>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(k.guestsTitle),
        content: SizedBox(
          width: 360,
          child: Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (var n = 1; n <= 12; n++)
                SizedBox(
                  width: 64,
                  height: 56,
                  child: OutlinedButton(
                    onPressed: () => Navigator.pop(context, n),
                    child: Text('$n', style: T.price(size: 20)),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
    if (picked == null) return;
    try {
      final s = await KitchenApi.setGuests(_checkId, picked);
      if (mounted) setState(() => _kitchen = s);
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _load() async {
    try {
      final results = await Future.wait([
        _checkId == 0
            ? Future<Check>.value(CheckScreen.emptyOrder())
            : Api.getCheck(_checkId),
        Api.items(includeInactive: true), // 86'd items grey out, not vanish
        Api.categories(),
      ]);
      if (!mounted) return;
      setState(() {
        _check = results[0] as Check;
        _items = results[1] as List<Item>;
        _categories = (results[2] as List<Category>)
            .where((c) => _items.any((i) => i.category == c.id))
            .toList();
        _category ??= _categories.isEmpty ? null : _categories.first.id;
        _error = null;
      });
      _refreshKitchen();
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    }
  }

  Future<void> _guarded(Future<Check> Function() op) async {
    try {
      final check = await op();
      if (!mounted) return;
      // Deleting the last line (or rejecting the last pending order) empties the
      // check; the server auto-cancels it (nothing was rung — no void, no reason).
      // Leave the workspace so the table shows free again; tables screen reloads on pop.
      if (check.status == 'CANCELLED') {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(L.of(context).emptyBillClosed)));
        _leave();
        return;
      }
      setState(() => _check = check);
      _refreshKitchen();
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _addItem(Item item, {bool forceSheet = false}) async {
    if (!item.active) return; // 86'd: visible but not orderable
    if (widget.counterOrder && !forceSheet && item.variants.length == 1) {
      _flashTimer?.cancel();
      setState(() => _flashItem = item.id);
      _flashTimer = Timer(const Duration(milliseconds: 350), () {
        if (mounted) setState(() => _flashItem = null);
      });
    }
    Variant variant = item.variants.first;
    int qty = 1;
    String? note;

    if (item.variants.length > 1 || forceSheet) {
      final result = await showModalBottomSheet<(Variant, int, String?)>(
        context: context,
        isScrollControlled: true,
        builder: (_) => _VariantSheet(item: item),
      );
      if (result == null) return;
      (variant, qty, note) = result;
    }
    await _guarded(() => _addLine(item, variant, qty, note));
  }

  /// Counter: the first item stores the new order (with that item); the
  /// taps made meanwhile wait for it, then add to it.
  Future<Check> _addLine(Item item, Variant variant, int qty, String? note) async {
    final create = widget.createOrder;
    if (_checkId == 0 && create != null) {
      final pending = _creating;
      if (pending == null) {
        final f = _creating = create(item, variant, qty, note);
        try {
          _checkId = await f;
        } catch (_) {
          _creating = null;
          rethrow;
        }
        return Api.getCheck(_checkId);
      }
      await pending;
    }
    return Api.addLine(_checkId, item.id, variant.id, qty, note: note);
  }

  /// Counter: drop this unpaid order (nothing was paid, nothing went to
  /// the kitchen), then the next one starts.
  Future<void> _discard() async {
    final q = Q.of(context);
    final l = L.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(q.discardTitle),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(l.cancel),
          ),
          FilledButton(
            key: const Key('discard-confirm'),
            style: FilledButton.styleFrom(backgroundColor: T.destructive),
            onPressed: () => Navigator.pop(context, true),
            child: Text(q.discard),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      await widget.onDiscard?.call();
      if (mounted) _leave();
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _voidCheck() async {
    final l = L.of(context);
    final approval = await requireGrant(
      context,
      Perm.voidCheck,
      title: l.voidApprovalTitle,
    );
    if (approval == null || !mounted) return;
    final pin = approval.managerPin;

    final custom = TextEditingController();
    final reason = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l.voidReasonTitle),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (final r in l.voidReasons)
              ListTile(
                dense: true,
                title: Text(r),
                onTap: () => Navigator.pop(context, r),
              ),
            TextField(
              controller: custom,
              decoration: InputDecoration(labelText: l.otherReason),
              onSubmitted: (v) => Navigator.pop(context, v.trim()),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(l.cancel),
          ),
          FilledButton(
            onPressed: () {
              final v = custom.text.trim();
              if (v.isNotEmpty) Navigator.pop(context, v);
            },
            child: Text(l.ok),
          ),
        ],
      ),
    );
    if (reason == null || reason.isEmpty || !mounted) return;

    try {
      await Api.voidCheck(_checkId, reason, pin);
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.voidedBill(_checkId))));
      _leave();
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _setCorkage() async {
    final l = L.of(context);
    final controller = TextEditingController(
      text: '${_check?.corkageBottles ?? 0}',
    );
    final bottles = await showDialog<int>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l.corkageTitle),
        content: TextField(
          controller: controller,
          keyboardType: TextInputType.number,
          autofocus: true,
          decoration: InputDecoration(labelText: l.bottlesBrought),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(l.cancel),
          ),
          FilledButton(
            onPressed: () =>
                Navigator.pop(context, int.tryParse(controller.text) ?? 0),
            child: Text(l.ok),
          ),
        ],
      ),
    );
    if (bottles == null) return;
    await _guarded(() => Api.setCorkage(_checkId, bottles));
  }

  /// "Check please": print a provisional bill and open its preview. Non-mutating —
  /// the check stays open, so this just shows the current state and returns here.
  Future<void> _printBill({String? lang}) async {
    try {
      final text = await Api.printBill(_checkId, lang: lang);
      if (!mounted) return;
      await Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) =>
              BillPreviewScreen(checkId: _checkId, text: text),
        ),
      );
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  /// Move / merge: pick a destination table — empty moves the bill there,
  /// occupied folds it into that table's bill. Either way this screen is
  /// stale afterwards (new table label or new check id), so replace it with
  /// the destination's check screen.
  Future<void> _moveOrMerge(Check check) async {
    final result = await Navigator.of(context).push<TableOpResult>(
      MaterialPageRoute(builder: (_) => TablePickerScreen(check: check)),
    );
    if (result == null) {
      if (mounted) _load();
      return;
    }
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(
        builder: (_) =>
            CheckScreen(checkId: result.checkId, tableLabel: result.tableLabel),
      ),
    );
  }

  /// Open / misc item: ring something off-menu as name + price (+ qty).
  Future<void> _addOpenItem() async {
    final l = L.of(context);
    final name = TextEditingController();
    final price = TextEditingController();
    int qty = 1;
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: Text(l.openItem),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: name,
                autofocus: true,
                textCapitalization: TextCapitalization.sentences,
                decoration: InputDecoration(labelText: l.openItemName),
              ),
              const SizedBox(height: 10),
              TextField(
                controller: price,
                keyboardType: TextInputType.number,
                decoration: InputDecoration(labelText: l.openItemPrice),
              ),
              const SizedBox(height: 10),
              Row(
                children: [
                  Text(l.qty, style: T.small()),
                  const SizedBox(width: 10),
                  IconButton(
                    onPressed: qty > 1
                        ? () => setDialogState(() => qty--)
                        : null,
                    constraints: const BoxConstraints(
                      minWidth: T.minTouch,
                      minHeight: T.minTouch,
                    ),
                    icon: const Icon(LucideIcons.minusCircle),
                  ),
                  Text('$qty', style: T.price(size: 22)),
                  IconButton(
                    onPressed: () => setDialogState(() => qty++),
                    constraints: const BoxConstraints(
                      minWidth: T.minTouch,
                      minHeight: T.minTouch,
                    ),
                    icon: const Icon(LucideIcons.plusCircle),
                  ),
                ],
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: Text(l.cancel),
            ),
            FilledButton(
              onPressed: () {
                if (name.text.trim().isEmpty) return;
                if ((int.tryParse(price.text) ?? 0) <= 0) return;
                Navigator.pop(context, true);
              },
              child: Text(l.ok),
            ),
          ],
        ),
      ),
    );
    if (ok != true || !mounted) return;
    await _guarded(
      () => Api.addOpenLine(
        _checkId,
        name.text.trim(),
        int.parse(price.text) * 100,
        qty,
      ),
    );
  }

  /// Settlement-time split: partition this check's lines into bill groups,
  /// each printed/paid on its own. When the last group settles the check
  /// closes and we pop back to the tables screen like a normal payment.
  Future<void> _openSplit(Check check) async {
    if (KitchenApi.enabled && !widget.counterOrder) {
      unawaited(KitchenApi.sendQuietly(_checkId));
    }
    final closed = await Navigator.of(context).push<bool>(
      MaterialPageRoute(
        builder: (_) =>
            SplitScreen(check: check, tableLabel: widget.tableLabel),
      ),
    );
    if (closed == true && mounted) {
      _paidAndDone();
    } else {
      _load();
    }
  }

  /// Paid in full: back to the tables; the counter goes on to the next order.
  void _paidAndDone() => _leave(paid: true);

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final check = _check;
    return Scaffold(
      body: SafeArea(
        child: _error != null
            ? Center(child: Text(_error!))
            : check == null
            ? const DelayedSpinner()
            : Column(
                children: [
                  // kitchen printer trouble (kitchen tickets only; else empty)
                  const KitchenQueueBanner(),
                  Expanded(
                    child: LayoutBuilder(
                      builder: (context, c) => Row(
                        children: [
                          _navRail(check, l),
                          const VerticalDivider(),
                          Expanded(child: _menuColumn(l)),
                          const VerticalDivider(),
                          // fixed cart: roomy on the landscape tablet, narrower
                          // when the screen is (portrait / small windows)
                          SizedBox(
                            width: !widget.counterOrder && c.maxWidth >= 1100
                                ? 420
                                : 340,
                            child: _billPanel(check, l),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
      ),
    );
  }

  // ~64pt icon column: back, void; language pinned at the bottom
  Widget _navRail(Check check, L l) {
    return Container(
      width: 72,
      color: T.surface,
      child: Column(
        children: [
          const SizedBox(height: 8),
          // the counter screen is home: nothing to go back to
          if (!_embedded) ...[
            IconButton(
              icon: const Icon(LucideIcons.arrowLeft),
              tooltip: MaterialLocalizations.of(context).backButtonTooltip,
              onPressed: () => Navigator.of(context).pop(),
            ),
            const SizedBox(height: 4),
          ],
          if (_embedded)
            // unpaid, nothing sent anywhere: the cashier just drops it
            IconButton(
              key: const Key('discard-order'),
              icon: const Icon(LucideIcons.trash2, color: T.destructive),
              tooltip: Q.of(context).discard,
              onPressed: _checkId != 0 && check.status == 'OPEN'
                  ? _discard
                  : null,
            )
          else
            IconButton(
              icon: const Icon(LucideIcons.trash2, color: T.destructive),
              tooltip: l.voidBillManager,
              onPressed:
                  (check.status == 'OPEN' || check.status == 'TOTAL_LOCKED')
                  ? _voidCheck
                  : null,
            ),
          const SizedBox(height: 4),
          // no tables at the counter: nothing to move to
          if (!_embedded)
            IconButton(
              icon: const Icon(LucideIcons.arrowRightLeft),
              tooltip: l.moveMerge,
              // only while money is fluid: no tender, no split (server re-guards)
              onPressed:
                  !widget.counterOrder &&
                      check.status == 'OPEN' &&
                      check.split == null
                  ? () => _moveOrMerge(check)
                  : null,
            ),
          const Spacer(),
          const LangActionsCompact(),
          const SizedBox(height: 8),
        ],
      ),
    );
  }

  Widget _menuColumn(L l) {
    if (widget.counterOrder) return _counterMenu(l);
    final visible = _items.where((i) => i.category == _category).toList();
    final entries = <(String, String)>[
      for (final c in _categories) (c.id, l.name(c.nameFr, c.nameEn, c.names)),
    ];
    return Row(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _categoryRail(l, entries),
        const VerticalDivider(),
        Expanded(
          child: LayoutBuilder(
            builder: (context, c) {
              // responsive grid: ~175pt columns that always fill the pane
              // (4 across on the landscape tablet);
              // photo 16:10 + a fixed two-line name + price
              const pad = 16.0, gap = 14.0;
              final avail = c.maxWidth - pad * 2;
              final cols = ((avail + gap) / (172 + gap)).floor().clamp(2, 6);
              final tileW = (avail - gap * (cols - 1)) / cols;
              return GridView.builder(
                padding: const EdgeInsets.all(pad),
                gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: cols,
                  mainAxisSpacing: gap,
                  crossAxisSpacing: gap,
                  mainAxisExtent: tileW * 10 / 16 + _tileTextHeight,
                ),
                itemCount: visible.length,
                itemBuilder: (_, i) => _menuTile(visible[i], l),
              );
            },
          ),
        ),
      ],
    );
  }

  /// Name (two lines) + price block under each tile's photo.
  static const _tileTextHeight = 90.0;

  /// Every category at once on a left rail (no scrolling, nothing hidden),
  /// with "Open item" pinned at the bottom. Counter and full service share it.
  Widget _categoryRail(L l, List<(String, String)> entries) {
    return Container(
      width: 176,
      color: T.surface,
      padding: const EdgeInsets.all(6),
      child: LayoutBuilder(
        builder: (context, c) {
          // all of them fit: the buttons share the height (max 76 each)
          const gap = 8.0, openItemH = 52.0;
          final n = entries.length;
          final h = ((c.maxHeight - openItemH - gap * n) / n).clamp(36.0, 76.0);
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (final (id, label) in entries) ...[
                SizedBox(
                  height: h,
                  child: _RailButton(
                    key: Key('cat-$id'),
                    label: label,
                    selected: id == _category,
                    onTap: () => setState(() => _category = id),
                  ),
                ),
                const SizedBox(height: gap),
              ],
              const Spacer(),
              SizedBox(
                height: openItemH,
                child: OutlinedButton.icon(
                  icon: const Icon(LucideIcons.pencilLine, size: 18),
                  label: FittedBox(
                    fit: BoxFit.scaleDown,
                    child: Text(l.openItem, maxLines: 1),
                  ),
                  onPressed: _check?.status == 'OPEN' && _checkId != 0
                      ? _addOpenItem
                      : null,
                  style: OutlinedButton.styleFrom(
                    foregroundColor: T.navy,
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }

  /// Counter: every category at once on a rail (no scrolling), then a dense
  /// grid of small tiles — built for speed, like a fast-food register.
  Widget _counterMenu(L l) {
    final q = Q.of(context);
    final all = _category == _allCategories;
    final visible = all
        ? _items
        : _items.where((i) => i.category == _category).toList();
    final entries = <(String, String)>[
      (_allCategories, q.all),
      for (final c in _categories) (c.id, l.name(c.nameFr, c.nameEn, c.names)),
    ];
    final counts = <String, int>{};
    for (final line in _check?.lines ?? const <CheckLine>[]) {
      final id = line.itemId;
      if (id != null) counts[id] = (counts[id] ?? 0) + line.qty;
    }
    return Row(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _categoryRail(l, entries),
        const VerticalDivider(),
        Expanded(
          child: LayoutBuilder(
            builder: (context, c) {
              // ~112pt columns: 6 across on the landscape tablet and the Surface
              const pad = 8.0, gap = 6.0;
              final avail = c.maxWidth - pad * 2;
              final cols = ((avail + gap) / (112 + gap)).floor().clamp(3, 6);
              final tileW = (avail - gap * (cols - 1)) / cols;
              return GridView.builder(
                padding: const EdgeInsets.all(pad),
                gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: cols,
                  mainAxisSpacing: gap,
                  crossAxisSpacing: gap,
                  mainAxisExtent: tileW / 2.2 + _counterTextHeight,
                ),
                itemCount: visible.length,
                itemBuilder: (_, i) =>
                    _counterTile(visible[i], l, counts[visible[i].id] ?? 0),
              );
            },
          ),
        ),
      ],
    );
  }

  /// Counter tile: name (two lines, a third for the long ones) + price under a 2:1 photo.
  static const _counterTextHeight = 76.0;

  Widget _counterTile(Item item, L l, int inOrder) {
    final inactive = !item.active;
    final flash = _flashItem == item.id;
    return Opacity(
      opacity: inactive ? 0.45 : 1,
      child: Stack(
        fit: StackFit.expand,
        children: [
          PosPanel(
            key: Key('tile-${item.id}'),
            raised: !inactive,
            color: flash ? T.accent.withValues(alpha: .18) : T.surface,
            borderColor: flash || inOrder > 0 ? T.accent : T.border,
            onTap: inactive ? null : () => _addItem(item),
            onLongPress: inactive
                ? null
                : () => _addItem(item, forceSheet: true),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                AspectRatio(
                  aspectRatio: 2.2,
                  child: ItemPhoto(
                    item,
                    width: 320,
                    fallback: AbbrevFallback(item.abbrev, size: 36),
                  ),
                ),
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(8, 6, 8, 6),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          child: Text(
                            l.name(item.nameFr, item.nameEn, item.names),
                            maxLines: 3,
                            overflow: TextOverflow.ellipsis,
                            style: T
                                .text(size: 13, weight: FontWeight.w600)
                                .copyWith(
                                  height: 1.15,
                                  decoration: inactive
                                      ? TextDecoration.lineThrough
                                      : null,
                                ),
                          ),
                        ),
                        Row(
                          children: [
                            Expanded(
                              child: Text(
                                item.variants.length == 1
                                    ? money(item.variants.first.priceCents)
                                    : '${money(item.variants.first.priceCents)}+',
                                maxLines: 1,
                                style: T.price(
                                  size: 15,
                                  weight: FontWeight.w700,
                                ),
                              ),
                            ),
                            if (inactive)
                              const Pill('86', color: T.destructive),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
          // how many are on the order: the cashier sees each tap land
          if (inOrder > 0)
            Positioned(
              top: 6,
              right: 6,
              child: IgnorePointer(
                child: Container(
                  key: Key('badge-${item.id}'),
                  constraints: const BoxConstraints(minWidth: 30),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 8,
                    vertical: 3,
                  ),
                  decoration: BoxDecoration(
                    color: T.accent,
                    borderRadius: BorderRadius.circular(999),
                    boxShadow: T.raised,
                  ),
                  child: Text(
                    '$inOrder',
                    textAlign: TextAlign.center,
                    style: T.text(
                      size: 16,
                      weight: FontWeight.w800,
                      color: T.onAccent,
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _menuTile(Item item, L l) {
    final inactive = !item.active; // 86'd: greyed + strike-through, NOT hidden
    return Opacity(
      opacity: inactive ? 0.45 : 1,
      child: PosPanel(
        raised: !inactive,
        onTap: inactive ? null : () => _addItem(item),
        onLongPress: inactive ? null : () => _addItem(item, forceSheet: true),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            AspectRatio(
              aspectRatio: 16 / 10,
              child: ItemPhoto(
                item,
                width: 512,
                fallback: AbbrevFallback(item.abbrev, size: 56),
              ),
            ),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Text(
                        l.name(item.nameFr, item.nameEn, item.names),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: T
                            .text(size: 16, weight: FontWeight.w600)
                            .copyWith(
                              height: 1.25,
                              decoration: inactive
                                  ? TextDecoration.lineThrough
                                  : null,
                            ),
                      ),
                    ),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          item.variants.length == 1
                              ? money(item.variants.first.priceCents)
                              : '${money(item.variants.first.priceCents)}+',
                          style: T.price(size: 18, weight: FontWeight.w700),
                        ),
                        if (inactive) const Pill('86', color: T.destructive),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _billPanel(Check check, L l) {
    return Container(
      color: T.surface,
      child: Column(
        children: [
          // panel header: table + bill number, corkage "+"
          Container(
            padding: const EdgeInsets.fromLTRB(20, 14, 8, 14),
            decoration: const BoxDecoration(color: T.navy),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        widget.counterOrder
                            ? widget.tableLabel
                            : '${l.table} ${widget.tableLabel}',
                        style: T.text(
                          size: 22,
                          weight: FontWeight.w700,
                          color: T.onPrimary,
                        ),
                      ),
                      Text(
                        [
                          if (_checkId != 0) l.billNo(_checkId),
                          if (check.lines.isNotEmpty)
                            l.itemCount(
                              check.lines.fold(0, (n, x) => n + x.qty),
                            ),
                        ].join('  ·  '),
                        style: T.small(color: T.onNavyMuted),
                      ),
                    ],
                  ),
                ),
                if (KitchenApi.enabled && !widget.counterOrder)
                  TextButton.icon(
                    style: TextButton.styleFrom(
                      foregroundColor: T.onPrimary,
                      minimumSize: const Size(0, T.minTouch),
                    ),
                    icon: const Icon(LucideIcons.users, size: 18),
                    label: Text(
                      _kitchen?.guests == null
                          ? K.of(context).guests
                          : K.of(context).guestsCount(_kitchen!.guests!),
                    ),
                    onPressed: _setGuests,
                  ),
                if (check.corkageBottles > 0)
                  Padding(
                    padding: const EdgeInsets.only(right: 4),
                    child: Pill(
                      '${l.corkage} ×${check.corkageBottles}',
                      color: T.attention,
                    ),
                  ),
                if (!widget.counterOrder)
                  IconButton(
                    icon: const Icon(LucideIcons.plusCircle),
                    color: T.onPrimary,
                    disabledColor: T.onNavyMuted.withValues(alpha: .5),
                    tooltip: l.corkageTitle,
                    constraints: const BoxConstraints(
                      minWidth: T.minTouch,
                      minHeight: T.minTouch,
                    ),
                    onPressed: check.status == 'OPEN' ? _setCorkage : null,
                  ),
              ],
            ),
          ),
          ?widget.panelTop,
          Expanded(
            child: check.lines.isEmpty && check.pendingLines.isEmpty
                ? const _EmptyBill()
                : ListView(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    children: [
                      if (check.pendingLines.isNotEmpty) ...[
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 16,
                            vertical: 8,
                          ),
                          color: T.pending.withValues(alpha: .22),
                          child: Row(
                            children: [
                              const AttentionDot(),
                              const SizedBox(width: 8),
                              Expanded(
                                child: Text(
                                  l.pendingFromPhone(check.pendingLines.length),
                                  style: T.small(
                                    color: T.attention,
                                    weight: FontWeight.w600,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                        for (final line in check.pendingLines)
                          _pendingLine(line, l),
                        const Divider(),
                      ],
                      for (final (i, line) in check.lines.indexed) ...[
                        if (i > 0) const Divider(indent: 20, endIndent: 20),
                        _billLine(check, line, l),
                      ],
                      if (check.fees.isNotEmpty) const Divider(),
                      for (final fee in check.fees)
                        Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 16,
                            vertical: 6,
                          ),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              Text(
                                l.name(fee.labelFr, fee.labelEn),
                                style: T.small(),
                              ),
                              Text(
                                money(fee.amountCents),
                                style: T.price(size: 16, color: T.textMuted),
                              ),
                            ],
                          ),
                        ),
                    ],
                  ),
          ),
          // total + big green pay button
          Container(
            padding: const EdgeInsets.fromLTRB(20, 14, 20, 18),
            decoration: const BoxDecoration(
              color: T.background,
              border: Border(top: BorderSide(color: T.border)),
            ),
            child: Column(
              children: [
                // pre-tax subtotal + the taxes added on top, then the total
                TaxRows(subtotalCents: check.subtotalCents, taxes: check.taxes),
                if (check.taxes.isNotEmpty) const SizedBox(height: 6),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Text(
                      l.total,
                      style: T.text(size: 20, weight: FontWeight.w600),
                    ),
                    Text(
                      money(check.grandTotalCents),
                      style: T.price(
                        size: T.priceBigSize,
                        weight: FontWeight.w700,
                        color: T.navy,
                      ),
                    ),
                  ],
                ),
                // Canada has no pennies: what cash comes to, rounded by the
                // store (the same figure Pay → Cash and the receipt use)
                if (check.cashRoundingCents != 0)
                  Align(
                    alignment: Alignment.centerRight,
                    child: Text(
                      Q.of(context).cashLine(money(check.cashDueCents)),
                      key: const Key('cash-due'),
                      style: T.price(
                        size: 18,
                        weight: FontWeight.w700,
                        color: T.textMuted,
                      ),
                    ),
                  ),
                const SizedBox(height: 12),
                // secondary "check please" bill above — not competing with — Pay.
                // Same enable rule as Pay: needs real, resolved (non-pending) content.
                Row(
                  children: [
                    // split into per-person bills; accent icon = a split is active
                    OutlinedButton(
                      onPressed:
                          check.lines.isEmpty || check.pendingLines.isNotEmpty
                          ? null
                          : () => _openSplit(check),
                      style: OutlinedButton.styleFrom(
                        minimumSize: const Size(0, T.minTouch),
                        padding: const EdgeInsets.symmetric(horizontal: 12),
                        side: BorderSide(
                          color: check.split != null ? T.primary : T.border,
                        ),
                      ),
                      child: Icon(
                        LucideIcons.split,
                        size: 20,
                        color: check.split != null ? T.primary : T.textPrimary,
                        semanticLabel: l.splitBill,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: OutlinedButton.icon(
                        icon: const Icon(LucideIcons.receiptText, size: 18),
                        label: Text(l.printBill),
                        onPressed:
                            check.lines.isEmpty || check.pendingLines.isNotEmpty
                            ? null
                            : _printBill,
                        onLongPress:
                            check.lines.isEmpty || check.pendingLines.isNotEmpty
                            ? null
                            : () => printInPickedLanguage(
                                context,
                                (lang) => _printBill(lang: lang),
                              ),
                        style: OutlinedButton.styleFrom(
                          minimumSize: const Size(0, T.minTouch),
                          padding: const EdgeInsets.symmetric(horizontal: 14),
                        ),
                      ),
                    ),
                  ],
                ),
                // a counter order goes to the kitchen when it is paid
                if (KitchenApi.enabled && !widget.counterOrder) ...[
                  const SizedBox(height: 10),
                  _kitchenRow(check),
                ],
                const SizedBox(height: 10),
                // Pay: full width under the secondary actions, so its label
                // (long in French) never has to squeeze
                Row(
                  children: [
                    Expanded(
                      child: SizedBox(
                        height: 64,
                        child: FilledButton.icon(
                          // the one copper call to action on the screen
                          style: FilledButton.styleFrom(
                            backgroundColor: T.accent,
                            foregroundColor: T.onAccent,
                            textStyle: T.text(
                              size: 20,
                              weight: FontWeight.w700,
                            ),
                          ),
                          icon: const Icon(LucideIcons.banknote),
                          // one line, scaled down for the long French /
                          // "orders awaiting" labels rather than wrapping
                          label: FittedBox(
                            fit: BoxFit.scaleDown,
                            child: Text(
                              check.pendingLines.isNotEmpty
                                  ? l.ordersAwaiting
                                  : l.pay,
                              maxLines: 1,
                            ),
                          ),
                          // a split check settles per group — Pay routes there
                          onPressed:
                              check.lines.isEmpty ||
                                  check.pendingLines.isNotEmpty
                              ? null
                              : check.split != null
                              ? () => _openSplit(check)
                              : () async {
                                  if (KitchenApi.enabled &&
                                      !widget.counterOrder) {
                                    unawaited(
                                      KitchenApi.sendQuietly(_checkId),
                                    );
                                  }
                                  final closed = await Navigator.of(context)
                                      .push<bool>(
                                        MaterialPageRoute(
                                          builder: (_) => TenderScreen(
                                            check: check,
                                            counterOrder: widget.counterOrder,
                                          ),
                                        ),
                                      );
                                  if (closed == true && mounted) {
                                    _paidAndDone();
                                  } else {
                                    _load();
                                  }
                                },
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// Send to kitchen (with what's waiting) + reprint. Kitchen tickets only.
  Widget _kitchenRow(Check check) {
    final k = K.of(context);
    final state = _kitchen;
    final waiting = state == null ? 0 : state.unsent;
    final voids = state?.pendingVoids ?? 0;
    final canSend = state == null || state.hasChanges;
    return Row(
      children: [
        Expanded(
          child: SizedBox(
            height: T.minTouch,
            child: FilledButton.tonalIcon(
              icon: _sending
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(LucideIcons.chefHat, size: 20),
              label: FittedBox(
                fit: BoxFit.scaleDown,
                child: Text(
                  !canSend
                      ? k.allSent
                      : waiting > 0
                      ? k.sendCount(waiting)
                      : voids > 0
                      ? k.voidsWaiting(voids)
                      : k.send,
                  maxLines: 1,
                ),
              ),
              onPressed: _sending || !canSend ? null : _sendToKitchen,
            ),
          ),
        ),
        const SizedBox(width: 8),
        SizedBox(
          height: T.minTouch,
          child: OutlinedButton(
            onPressed: (state?.sent ?? 0) > 0 ? _reprintKitchen : null,
            style: OutlinedButton.styleFrom(
              padding: const EdgeInsets.symmetric(horizontal: 12),
            ),
            child: Icon(
              LucideIcons.printer,
              size: 20,
              semanticLabel: k.reprint,
            ),
          ),
        ),
      ],
    );
  }

  /// A line's extra-language names (es, de), from its menu item.
  Map<String, String>? _namesOf(CheckLine line) =>
      _items.where((i) => i.id == line.itemId).firstOrNull?.names;

  /// "Lantern House Lager · 20 oz pint" — the variant matters when the item has sizes.
  String _lineTitle(CheckLine line, L l) {
    final name = l.name(line.nameFr, line.nameEn, _namesOf(line));
    if (line.variantLabelFr == null) return name;
    return '$name · ${l.name(line.variantLabelFr!, line.variantLabelEn ?? line.variantLabelFr!)}';
  }

  Widget _pendingLine(CheckLine line, L l) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '${_lineTitle(line, l)} ×${line.qty}',
                  style: T.small(color: T.textPrimary),
                ),
                if (line.note != null) Text(line.note!, style: T.small()),
              ],
            ),
          ),
          Text(money(line.lineTotalCents), style: T.price(size: 15)),
          IconButton(
            icon: const Icon(
              LucideIcons.checkCircle2,
              color: T.primary,
              size: 22,
            ),
            tooltip: l.acceptOrder,
            constraints: const BoxConstraints(
              minWidth: 52,
              minHeight: T.minTouch,
            ),
            onPressed: () =>
                _guarded(() => Api.acceptPendingLine(_checkId, line.id)),
          ),
          IconButton(
            icon: const Icon(
              LucideIcons.xCircle,
              color: T.destructive,
              size: 22,
            ),
            tooltip: l.rejectOrder,
            constraints: const BoxConstraints(
              minWidth: 52,
              minHeight: T.minTouch,
            ),
            onPressed: () =>
                _guarded(() => Api.rejectPendingLine(_checkId, line.id)),
          ),
        ],
      ),
    );
  }

  Widget _billLine(Check check, CheckLine line, L l) {
    final editable = check.status == 'OPEN';
    final variant = line.variantLabelFr == null
        ? null
        : l.name(
            line.variantLabelFr!,
            line.variantLabelEn ?? line.variantLabelFr!,
          );
    final detail = [?variant, ?line.note].join(' · ');
    final row = Padding(
      padding: const EdgeInsets.fromLTRB(20, 10, 12, 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Text(
                  l.name(line.nameFr, line.nameEn, _namesOf(line)),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: T.text(size: 17, weight: FontWeight.w600),
                ),
              ),
              const SizedBox(width: 12),
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: Text(
                  money(line.lineTotalCents),
                  textAlign: TextAlign.right,
                  style: T.price(size: 17, weight: FontWeight.w700),
                ),
              ),
            ],
          ),
          Row(
            children: [
              Expanded(
                child: Text(
                  detail.isEmpty
                      ? '${money(line.unitPriceCents)} ${l.each}'
                      : detail,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: T.small(),
                ),
              ),
              if (editable) ...[
                // − at qty 1 deletes the line entirely (no zero-qty lines), same as trash
                Container(
                  decoration: BoxDecoration(
                    color: T.surface,
                    borderRadius: T.radiusMedium,
                    border: Border.all(color: T.border),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      _stepBtn(
                        LucideIcons.minus,
                        () => _guarded(
                          () => line.qty > 1
                              ? Api.setLineQty(
                                  _checkId,
                                  line.id,
                                  line.qty - 1,
                                )
                              : Api.removeLine(_checkId, line.id),
                        ),
                        width: 48,
                        height: 48,
                      ),
                      SizedBox(
                        width: 32,
                        child: Center(
                          child: Text(
                            '${line.qty}',
                            style: T.price(size: 18, weight: FontWeight.w700),
                          ),
                        ),
                      ),
                      _stepBtn(
                        LucideIcons.plus,
                        () => _guarded(
                          () => Api.setLineQty(
                            _checkId,
                            line.id,
                            line.qty + 1,
                          ),
                        ),
                        width: 48,
                        height: 48,
                      ),
                    ],
                  ),
                ),
                // primary, discoverable delete — swipe stays as a secondary gesture below.
                // Muted so it doesn't shout; tap = immediate delete (mistakes get re-added).
                _stepBtn(
                  LucideIcons.trash2,
                  () => _guarded(() => Api.removeLine(_checkId, line.id)),
                  width: 48,
                  color: T.textMuted,
                  tooltip: l.deleteLine,
                ),
              ] else
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: Text('×${line.qty}', style: T.price(size: 16)),
                ),
            ],
          ),
        ],
      ),
    );
    if (!editable) return row;
    // swipe-to-delete kept as a secondary gesture for real-tablet users
    return Dismissible(
      key: ValueKey('line-${line.id}'),
      direction: DismissDirection.endToStart,
      background: Container(
        color: T.destructive.withValues(alpha: .25),
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 20),
        child: const Icon(LucideIcons.trash2, color: T.destructive),
      ),
      onDismissed: (_) =>
          _guarded(() => Api.removeLine(_checkId, line.id)),
      child: row,
    );
  }

  // 52×56 — qty steppers get real touch targets (fat fingers, busy bar).
  // The trash tap uses a slimmer width + muted color so it reads as secondary.
  Widget _stepBtn(
    IconData icon,
    VoidCallback? onTap, {
    double width = 52,
    double height = T.minTouch,
    Color? color,
    String? tooltip,
  }) => SizedBox(
    width: width,
    height: height,
    child: IconButton(
      padding: EdgeInsets.zero,
      iconSize: 20,
      color: color,
      tooltip: tooltip,
      icon: Icon(icon),
      onPressed: onTap,
    ),
  );
}

class _CategoryChip extends StatelessWidget {
  final String label;
  final bool selected;
  final VoidCallback onTap;
  const _CategoryChip({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    // selected = the copper accent; the rest sit quietly on cream
    return AnimatedContainer(
      duration: T.dNormal,
      curve: T.ease,
      decoration: BoxDecoration(
        color: selected ? T.accent : T.surface,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: selected ? T.accent : T.border),
        boxShadow: selected ? T.raised : null,
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(999),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
            child: Text(
              label,
              style: T.text(
                size: 16,
                color: selected ? T.onAccent : T.textPrimary,
                weight: FontWeight.w600,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Counter category rail: a big full-width button, copper when selected.
class _RailButton extends StatelessWidget {
  final String label;
  final bool selected;
  final VoidCallback onTap;
  const _RailButton({
    super.key,
    required this.label,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: selected ? T.accent : T.background,
      shape: RoundedRectangleBorder(
        borderRadius: T.radiusMedium,
        side: BorderSide(color: selected ? T.accent : T.border),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 6),
          child: Align(
            alignment: Alignment.centerLeft,
            child: Text(
              label,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: T
                  .text(
                    size: 15,
                    weight: FontWeight.w700,
                    color: selected ? T.onAccent : T.textPrimary,
                  )
                  .copyWith(height: 1.15),
            ),
          ),
        ),
      ),
    );
  }
}

/// Cart empty state: what to do next, not a blank pane.
class _EmptyBill extends StatelessWidget {
  const _EmptyBill();

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 76,
              height: 76,
              decoration: const BoxDecoration(
                color: T.surfaceAlt,
                shape: BoxShape.circle,
              ),
              child: const Icon(
                LucideIcons.receiptText,
                size: 34,
                color: T.navy,
              ),
            ),
            const SizedBox(height: 16),
            Text(
              l.emptyBill,
              textAlign: TextAlign.center,
              style: T.text(size: 18, weight: FontWeight.w600),
            ),
            const SizedBox(height: 6),
            Text(l.tapToAdd, textAlign: TextAlign.center, style: T.small()),
          ],
        ),
      ),
    );
  }
}

class _VariantSheet extends StatefulWidget {
  final Item item;
  const _VariantSheet({required this.item});

  @override
  State<_VariantSheet> createState() => _VariantSheetState();
}

class _VariantSheetState extends State<_VariantSheet> {
  late Variant _variant = widget.item.variants.first;
  int _qty = 1;
  final _note = TextEditingController();

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return Padding(
      padding: EdgeInsets.only(
        left: 20,
        right: 20,
        top: 20,
        bottom: MediaQuery.of(context).viewInsets.bottom + 20,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            l.name(widget.item.nameFr, widget.item.nameEn, widget.item.names),
            style: T.headline(),
          ),
          Text(
            l.nameAlt(widget.item.nameFr, widget.item.nameEn),
            style: T.small(),
          ),
          const SizedBox(height: 14),
          if (widget.item.variants.length > 1)
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final v in widget.item.variants)
                  _CategoryChip(
                    label:
                        '${l.name(v.labelFr, v.labelEn)} ${money(v.priceCents)}',
                    selected: v.id == _variant.id,
                    onTap: () => setState(() => _variant = v),
                  ),
              ],
            ),
          const SizedBox(height: 14),
          Row(
            children: [
              Text(l.qty, style: T.small()),
              const SizedBox(width: 10),
              IconButton(
                onPressed: _qty > 1 ? () => setState(() => _qty--) : null,
                constraints: const BoxConstraints(
                  minWidth: T.minTouch,
                  minHeight: T.minTouch,
                ),
                iconSize: 26,
                icon: const Icon(LucideIcons.minusCircle),
              ),
              Text('$_qty', style: T.price(size: 22)),
              IconButton(
                onPressed: () => setState(() => _qty++),
                constraints: const BoxConstraints(
                  minWidth: T.minTouch,
                  minHeight: T.minTouch,
                ),
                iconSize: 26,
                icon: const Icon(LucideIcons.plusCircle),
              ),
            ],
          ),
          TextField(
            controller: _note,
            decoration: InputDecoration(labelText: l.noteHint),
          ),
          const SizedBox(height: 14),
          SizedBox(
            width: double.infinity,
            height: T.minTouch,
            child: FilledButton(
              onPressed: () => Navigator.pop(context, (
                _variant,
                _qty,
                _note.text.isEmpty ? null : _note.text,
              )),
              child: Text(l.addToBill(money(_variant.priceCents * _qty))),
            ),
          ),
        ],
      ),
    );
  }
}
