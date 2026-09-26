import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import '../kitchen/kitchen_banner.dart';
import '../kitchen/kitchen_i18n.dart';
import '../widgets/item_photo.dart';
import '../widgets/pin_pad.dart';
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
  const CheckScreen({
    super.key,
    required this.checkId,
    required this.tableLabel,
  });

  @override
  State<CheckScreen> createState() => _CheckScreenState();
}

class _CheckScreenState extends State<CheckScreen> with ResumeRefresh {
  @override
  void onAppResume() => _refreshCheck();

  Check? _check;
  List<Item> _items = [];
  List<Category> _categories = [];
  String? _category;
  String? _error;
  Timer? _poll;

  /// Kitchen tickets (store has kitchen.printing=on): what a Send would print.
  KitchenCheckState? _kitchen;
  bool _sending = false;

  @override
  void initState() {
    super.initState();
    _load();
    // pick up QR-submitted pending lines while the screen is open. TODO: push/SSE
    _poll = Timer.periodic(const Duration(seconds: 5), (_) => _refreshCheck());
  }

  @override
  void dispose() {
    _poll?.cancel();
    // leaving the bill sends whatever the kitchen doesn't have yet (best
    // effort, never blocks; the store already handles voids on its own)
    if (KitchenApi.enabled) KitchenApi.sendQuietly(widget.checkId);
    super.dispose();
  }

  Future<void> _refreshCheck() async {
    try {
      final check = await Api.getCheck(widget.checkId);
      if (mounted) setState(() => _check = check);
    } catch (_) {} // transient poll failures are fine
    _refreshKitchen();
  }

  Future<void> _refreshKitchen() async {
    if (!KitchenApi.enabled) return;
    try {
      final k = await KitchenApi.checkState(widget.checkId);
      if (mounted) setState(() => _kitchen = k);
    } catch (_) {} // the Send count just stays as it was
  }

  /// Send new / changed items to their stations now.
  Future<void> _sendToKitchen() async {
    if (_sending) return;
    final k = K.of(context);
    setState(() => _sending = true);
    try {
      final r = await KitchenApi.send(widget.checkId);
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
      final r = await KitchenApi.reprint(widget.checkId);
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
      final s = await KitchenApi.setGuests(widget.checkId, picked);
      if (mounted) setState(() => _kitchen = s);
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _load() async {
    try {
      final results = await Future.wait([
        Api.getCheck(widget.checkId),
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
        Navigator.of(context).pop();
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
    await _guarded(
      () => Api.addLine(widget.checkId, item.id, variant.id, qty, note: note),
    );
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
      await Api.voidCheck(widget.checkId, reason, pin);
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.voidedBill(widget.checkId))));
      Navigator.of(context).pop();
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
    await _guarded(() => Api.setCorkage(widget.checkId, bottles));
  }

  /// "Check please": print a provisional bill and open its preview. Non-mutating —
  /// the check stays open, so this just shows the current state and returns here.
  Future<void> _printBill() async {
    try {
      final text = await Api.printBill(widget.checkId);
      if (!mounted) return;
      await Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) =>
              BillPreviewScreen(checkId: widget.checkId, text: text),
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
        widget.checkId,
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
    if (KitchenApi.enabled) unawaited(KitchenApi.sendQuietly(widget.checkId));
    final closed = await Navigator.of(context).push<bool>(
      MaterialPageRoute(
        builder: (_) =>
            SplitScreen(check: check, tableLabel: widget.tableLabel),
      ),
    );
    if (closed == true && mounted) {
      Navigator.of(context).pop();
    } else {
      _load();
    }
  }

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
                            width: c.maxWidth >= 1100 ? 420 : 340,
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
          IconButton(
            icon: const Icon(LucideIcons.arrowLeft),
            tooltip: MaterialLocalizations.of(context).backButtonTooltip,
            onPressed: () => Navigator.of(context).pop(),
          ),
          const SizedBox(height: 4),
          IconButton(
            icon: const Icon(LucideIcons.trash2, color: T.destructive),
            tooltip: l.voidBillManager,
            onPressed:
                (check.status == 'OPEN' || check.status == 'TOTAL_LOCKED')
                ? _voidCheck
                : null,
          ),
          const SizedBox(height: 4),
          IconButton(
            icon: const Icon(LucideIcons.arrowRightLeft),
            tooltip: l.moveMerge,
            // only while money is fluid: no tender, no split (server re-guards)
            onPressed: check.status == 'OPEN' && check.split == null
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
    final visible = _items.where((i) => i.category == _category).toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(
          height: 72,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 10),
            children: [
              for (final c in _categories)
                Padding(
                  padding: const EdgeInsets.only(right: 10),
                  child: _CategoryChip(
                    label: l.name(c.nameFr, c.nameEn),
                    selected: c.id == _category,
                    onTap: () => setState(() => _category = c.id),
                  ),
                ),
              // off-menu line by name + price — lives with the add-item chips
              OutlinedButton.icon(
                icon: const Icon(LucideIcons.pencilLine, size: 18),
                label: Text(
                  l.openItem,
                  style: T.text(size: 16, weight: FontWeight.w600),
                ),
                onPressed: _check?.status == 'OPEN' ? _addOpenItem : null,
                style: OutlinedButton.styleFrom(
                  foregroundColor: T.navy,
                  minimumSize: const Size(0, 48),
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                ),
              ),
            ],
          ),
        ),
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
                padding: const EdgeInsets.fromLTRB(pad, 4, pad, pad),
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
                        l.name(item.nameFr, item.nameEn),
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
                        '${l.table} ${widget.tableLabel}',
                        style: T.text(
                          size: 22,
                          weight: FontWeight.w700,
                          color: T.onPrimary,
                        ),
                      ),
                      Text(
                        [
                          l.billNo(widget.checkId),
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
                if (KitchenApi.enabled)
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
                // pre-tax subtotal + GST / QST added on top, then the total
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
                        style: OutlinedButton.styleFrom(
                          minimumSize: const Size(0, T.minTouch),
                          padding: const EdgeInsets.symmetric(horizontal: 14),
                        ),
                      ),
                    ),
                  ],
                ),
                if (KitchenApi.enabled) ...[
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
                                  if (KitchenApi.enabled) {
                                    unawaited(
                                      KitchenApi.sendQuietly(widget.checkId),
                                    );
                                  }
                                  final closed = await Navigator.of(context)
                                      .push<bool>(
                                        MaterialPageRoute(
                                          builder: (_) =>
                                              TenderScreen(check: check),
                                        ),
                                      );
                                  if (closed == true && mounted) {
                                    Navigator.of(context).pop();
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

  /// "Lantern House Lager · 20 oz pint" — the variant matters when the item has sizes.
  String _lineTitle(CheckLine line, L l) {
    final name = l.name(line.nameFr, line.nameEn);
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
                _guarded(() => Api.acceptPendingLine(widget.checkId, line.id)),
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
                _guarded(() => Api.rejectPendingLine(widget.checkId, line.id)),
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
                  l.name(line.nameFr, line.nameEn),
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
                                  widget.checkId,
                                  line.id,
                                  line.qty - 1,
                                )
                              : Api.removeLine(widget.checkId, line.id),
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
                            widget.checkId,
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
                  () => _guarded(() => Api.removeLine(widget.checkId, line.id)),
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
          _guarded(() => Api.removeLine(widget.checkId, line.id)),
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
            l.name(widget.item.nameFr, widget.item.nameEn),
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
