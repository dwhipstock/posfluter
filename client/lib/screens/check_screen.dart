import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import '../widgets/item_photo.dart';
import '../widgets/pin_pad.dart';
import '../widgets/resume_refresh.dart';
import 'bill_preview_screen.dart';
import 'split_screen.dart';
import 'table_picker_screen.dart';
import 'tender_screen.dart';

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
    super.dispose();
  }

  Future<void> _refreshCheck() async {
    try {
      final check = await Api.getCheck(widget.checkId);
      if (mounted) setState(() => _check = check);
    } catch (_) {} // transient poll failures are fine
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
        backgroundColor: T.surfaceAlt,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: T.rLarge),
        ),
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
            : Row(
                children: [
                  _navRail(check, l),
                  const VerticalDivider(),
                  Expanded(flex: 5, child: _menuColumn(l)),
                  const VerticalDivider(),
                  SizedBox(width: 380, child: _billPanel(check, l)),
                ],
              ),
      ),
    );
  }

  // ~64pt icon column: back, void; language pinned at the bottom
  Widget _navRail(Check check, L l) {
    return Container(
      width: 64,
      color: T.background,
      child: Column(
        children: [
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
          const RotatedBox(quarterTurns: 0, child: LangActionsCompact()),
          const SizedBox(height: 8),
        ],
      ),
    );
  }

  Widget _menuColumn(L l) {
    final visible = _items.where((i) => i.category == _category).toList();
    final portrait = MediaQuery.of(context).size.width < 1100;
    return Column(
      children: [
        SizedBox(
          height: 60,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            children: [
              for (final c in _categories)
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: _CategoryChip(
                    label: l.name(c.nameFr, c.nameEn),
                    selected: c.id == _category,
                    onTap: () => setState(() => _category = c.id),
                  ),
                ),
              // off-menu line by name + price — lives with the add-item chips
              OutlinedButton.icon(
                icon: const Icon(LucideIcons.pencilLine, size: 16),
                label: Text(
                  l.openItem,
                  style: T.small(weight: FontWeight.w600),
                ),
                onPressed: _check?.status == 'OPEN' ? _addOpenItem : null,
                style: OutlinedButton.styleFrom(
                  minimumSize: const Size(0, 40),
                  padding: const EdgeInsets.symmetric(horizontal: 14),
                ),
              ),
            ],
          ),
        ),
        Expanded(
          child: GridView.count(
            crossAxisCount: portrait ? 3 : 4,
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            mainAxisSpacing: 10,
            crossAxisSpacing: 10,
            childAspectRatio: 1.05,
            children: [for (final item in visible) _menuTile(item, l)],
          ),
        ),
      ],
    );
  }

  Widget _menuTile(Item item, L l) {
    final inactive = !item.active; // 86'd: greyed + strike-through, NOT hidden
    return Opacity(
      opacity: inactive ? 0.45 : 1,
      child: PosPanel(
        onTap: inactive ? null : () => _addItem(item),
        onLongPress: inactive ? null : () => _addItem(item, forceSheet: true),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: ItemPhoto(
                item,
                width: 512,
                fallback: AbbrevFallback(item.abbrev),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(10, 6, 10, 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    l.name(item.nameFr, item.nameEn),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: T
                        .small(color: T.textPrimary, weight: FontWeight.w600)
                        .copyWith(
                          decoration: inactive
                              ? TextDecoration.lineThrough
                              : null,
                        ),
                  ),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        item.variants.length == 1
                            ? cad(item.variants.first.priceCents)
                            : '${cad(item.variants.first.priceCents)}+',
                        style: T.price(size: 16, color: T.accent),
                      ),
                      if (inactive) const Pill('86', color: T.destructive),
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

  Widget _billPanel(Check check, L l) {
    return Container(
      color: T.surface,
      child: Column(
        children: [
          // panel header: table + bill number, corkage "+"
          Container(
            padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
            decoration: const BoxDecoration(
              border: Border(bottom: BorderSide(color: T.border)),
            ),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '${l.table} ${widget.tableLabel}',
                        style: T.text(weight: FontWeight.w600),
                      ),
                      Text('${l.bill} #${widget.checkId}', style: T.small()),
                    ],
                  ),
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
                ? Center(child: Text(l.noItemsYet, style: T.small()))
                : ListView(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    children: [
                      if (check.pendingLines.isNotEmpty) ...[
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 16,
                            vertical: 8,
                          ),
                          color: T.attention.withValues(alpha: .12),
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
                      for (final line in check.lines) _billLine(check, line, l),
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
                                cad(fee.amountCents),
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
            padding: const EdgeInsets.all(16),
            decoration: const BoxDecoration(
              border: Border(top: BorderSide(color: T.border)),
            ),
            child: Column(
              children: [
                // This demo has no configured sales-tax row.
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Text(l.total, style: T.text(weight: FontWeight.w600)),
                    Text(
                      cad(check.grandTotalCents),
                      style: T.price(
                        size: T.priceBigSize,
                        weight: FontWeight.w600,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                // secondary "check please" bill next to — not competing with — Pay.
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
                          color: check.split != null ? T.accent : T.border,
                        ),
                      ),
                      child: Icon(
                        LucideIcons.split,
                        size: 20,
                        color: check.split != null ? T.accent : T.textPrimary,
                        semanticLabel: l.splitBill,
                      ),
                    ),
                    const SizedBox(width: 8),
                    OutlinedButton.icon(
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
                    const SizedBox(width: 8),
                    Expanded(
                      child: SizedBox(
                        height: T.minTouch,
                        child: FilledButton.icon(
                          icon: const Icon(LucideIcons.banknote),
                          label: Text(
                            check.pendingLines.isNotEmpty
                                ? l.ordersAwaiting
                                : l.pay,
                          ),
                          // a split check settles per group — Pay routes there
                          onPressed:
                              check.lines.isEmpty ||
                                  check.pendingLines.isNotEmpty
                              ? null
                              : check.split != null
                              ? () => _openSplit(check)
                              : () async {
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

  /// "éléphant · lanceur" — the variant matters when the item has sizes.
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
          Text(cad(line.lineTotalCents), style: T.price(size: 15)),
          IconButton(
            icon: const Icon(
              LucideIcons.checkCircle2,
              color: T.accent,
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
    final row = Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  _lineTitle(line, l),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: T.text(size: 16),
                ),
                if (line.note != null) Text(line.note!, style: T.small()),
              ],
            ),
          ),
          if (editable) ...[
            // − at qty 1 deletes the line entirely (no zero-qty lines), same as trash
            _stepBtn(
              LucideIcons.minus,
              () => _guarded(
                () => line.qty > 1
                    ? Api.setLineQty(widget.checkId, line.id, line.qty - 1)
                    : Api.removeLine(widget.checkId, line.id),
              ),
            ),
            SizedBox(
              width: 28,
              child: Center(
                child: Text('${line.qty}', style: T.price(size: 16)),
              ),
            ),
            _stepBtn(
              LucideIcons.plus,
              () => _guarded(
                () => Api.setLineQty(widget.checkId, line.id, line.qty + 1),
              ),
            ),
          ] else
            Text('×${line.qty}  ', style: T.small()),
          SizedBox(
            width: 66,
            child: Text(
              cad(line.lineTotalCents),
              textAlign: TextAlign.right,
              style: T.price(size: 16),
            ),
          ),
          if (editable)
            // primary, discoverable delete — swipe stays as a secondary gesture below.
            // Muted so it doesn't shout; tap = immediate delete (mistakes get re-added).
            _stepBtn(
              LucideIcons.trash2,
              () => _guarded(() => Api.removeLine(widget.checkId, line.id)),
              width: 44,
              color: T.textMuted,
              tooltip: l.deleteLine,
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
    Color? color,
    String? tooltip,
  }) => SizedBox(
    width: width,
    height: T.minTouch,
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
    return AnimatedContainer(
      duration: T.dNormal,
      curve: T.ease,
      decoration: BoxDecoration(
        color: selected ? T.accent.withValues(alpha: .16) : T.surface,
        borderRadius: T.radiusMedium,
        border: Border.all(color: selected ? T.accent : T.border),
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: T.radiusMedium,
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: Text(
              label,
              style: T.small(
                color: selected ? T.accent : T.textPrimary,
                weight: FontWeight.w600,
              ),
            ),
          ),
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
                        '${l.name(v.labelFr, v.labelEn)} ${cad(v.priceCents)}',
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
              child: Text(l.addToBill(cad(_variant.priceCents * _qty))),
            ),
          ),
        ],
      ),
    );
  }
}
