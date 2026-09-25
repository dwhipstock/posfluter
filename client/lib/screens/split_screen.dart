import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import '../widgets/resume_refresh.dart';
import 'bill_preview_screen.dart';
import 'tender_screen.dart';

/// Settlement-time split view: left = the check's not-yet-assigned lines,
/// right = bill-group cards. Tap a group card to select it, tap an unassigned
/// line to move it (whole remaining qty) into the selected group; a small
/// "1 only" chip peels a single unit off a qty>1 line. Tapping an assigned
/// line moves it back. Every total is server-computed — the client never sums.
///
/// The split locks at the first group payment (server-enforced); paid groups
/// show Paid ✓. When the last group settles, the check finalizes and this
/// screen pops `true` all the way back to the tables screen.
class SplitScreen extends StatefulWidget {
  final Check check;
  final String tableLabel;
  const SplitScreen({super.key, required this.check, required this.tableLabel});

  @override
  State<SplitScreen> createState() => _SplitScreenState();
}

class _SplitScreenState extends State<SplitScreen> with ResumeRefresh {
  late Check _check = widget.check;
  int? _selectedGroupId;
  bool _busy = false;

  SplitInfo? get _split => _check.split;
  bool get _locked => _split?.locked ?? false;

  @override
  void initState() {
    super.initState();
    // arriving without a split yet → start a 2-group by-item split right away
    if (_check.split == null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _guarded(() => Api.createSplit(_check.id, groups: 2));
      });
    } else {
      _selectedGroupId = _firstOpenGroupId(_check);
    }
  }

  @override
  void onAppResume() => _refresh();

  int? _firstOpenGroupId(Check check) =>
      check.split?.groups.where((g) => !g.isPaid).firstOrNull?.id;

  Future<void> _refresh() async {
    try {
      final check = await Api.getCheck(_check.id);
      if (mounted) setState(() => _check = check);
    } catch (_) {}
  }

  Future<void> _guarded(Future<Check> Function() op) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final check = await op();
      if (!mounted) return;
      setState(() {
        _check = check;
        // keep a valid selection: default to the first unpaid group
        final groups = check.split?.groups ?? const <BillGroup>[];
        if (groups.every((g) => g.id != _selectedGroupId)) {
          _selectedGroupId = _firstOpenGroupId(check);
        }
      });
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  // --- actions ---

  Future<void> _assign(Allocation slice, {bool single = false}) async {
    final target = _selectedGroupId;
    if (target == null || _locked) return;
    await _guarded(
      () => Api.assignLine(
        _check.id,
        target,
        slice.lineId,
        single ? 1 : slice.qty,
      ),
    );
  }

  Future<void> _unassign(
    BillGroup group,
    Allocation slice, {
    bool single = false,
  }) async {
    if (_locked || group.isPaid) return;
    await _guarded(
      () => Api.unassignLine(
        _check.id,
        group.id,
        slice.lineId,
        single ? 1 : slice.qty,
      ),
    );
  }

  Future<void> _clearSplit() async {
    final l = L.of(context);
    final sure = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l.clearSplit),
        content: Text(l.clearSplitConfirm),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(l.cancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(l.ok),
          ),
        ],
      ),
    );
    if (sure != true || !mounted) return;
    try {
      await Api.clearSplit(_check.id);
      if (mounted) {
        Navigator.pop(context, false); // back to the single-bill check
      }
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  /// Switch by-item ↔ even ÷N. Rebuilds the split from scratch — the two
  /// modes are mutually exclusive by design.
  Future<void> _switchMode({required bool even}) async {
    if ((_split?.even ?? false) == even) return;
    int groups = _split?.groups.length ?? 2;
    if (even) {
      final n = await _askHowManyWays();
      if (n == null) return;
      groups = n;
    }
    await _guarded(() async {
      await Api.clearSplit(_check.id);
      return Api.createSplit(_check.id, groups: even ? groups : 2, even: even);
    });
  }

  Future<int?> _askHowManyWays() => showDialog<int>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(L.of(context).splitHowManyWays),
      content: Wrap(
        spacing: 10,
        runSpacing: 10,
        children: [
          for (var n = 2; n <= 9; n++)
            SizedBox(
              width: 72,
              height: T.minTouch,
              child: OutlinedButton(
                onPressed: () => Navigator.pop(context, n),
                child: Text('$n', style: T.price(size: 22)),
              ),
            ),
        ],
      ),
    ),
  );

  Future<void> _moveCorkage() async {
    final l = L.of(context);
    final groups = _split?.groups ?? const <BillGroup>[];
    final target = await showDialog<int>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l.moveCorkageTitle),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final g in groups.where((g) => !g.includesCorkage))
              ListTile(
                title: Text(l.groupTitle(g.number)),
                onTap: () => Navigator.pop(context, g.id),
              ),
          ],
        ),
      ),
    );
    if (target == null || !mounted) return;
    await _guarded(() => Api.moveCorkage(_check.id, target));
  }

  Future<void> _printGroupBill(BillGroup group) async {
    try {
      final text = await Api.printBill(_check.id, groupId: group.id);
      if (!mounted) return;
      await Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) => BillPreviewScreen(
            checkId: _check.id,
            text: text,
            groupId: group.id,
          ),
        ),
      );
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _payGroup(BillGroup group) async {
    final closed = await Navigator.of(context).push<bool>(
      MaterialPageRoute(
        builder: (_) => TenderScreen(check: _check, groupId: group.id),
      ),
    );
    if (!mounted) return;
    if (closed == true) {
      Navigator.pop(context, true); // check finalized → all the way back
    } else {
      await _refresh();
      if (!mounted) return;
      final paid =
          _check.split?.groups
              .where((g) => g.id == group.id)
              .firstOrNull
              ?.isPaid ??
          false;
      if (paid) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(L.of(context).groupPaidToast(group.number))),
        );
      }
    }
  }

  // --- build ---

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final split = _split;
    return Scaffold(
      appBar: AppBar(
        title: Text(
          '${l.splitBill} — ${l.table} ${widget.tableLabel} · ${l.bill} #${_check.id}',
        ),
        actions: [
          if (split != null && !_locked) ...[
            _modeToggle(l, split),
            const SizedBox(width: 8),
            IconButton(
              icon: const Icon(LucideIcons.x, color: T.destructive),
              tooltip: l.clearSplit,
              onPressed: _clearSplit,
            ),
          ],
          const LangActions(),
        ],
      ),
      body: split == null
          ? const DelayedSpinner()
          : Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (!split.even) ...[
                  SizedBox(width: 360, child: _unassignedPanel(split, l)),
                  const VerticalDivider(),
                ],
                Expanded(child: _groupsPanel(split, l)),
              ],
            ),
    );
  }

  /// by-item ↔ evenly segmented toggle (rebuilds the split on switch)
  Widget _modeToggle(L l, SplitInfo split) {
    Widget seg(String label, bool active, VoidCallback onTap) => InkWell(
      borderRadius: T.radiusMedium,
      onTap: active ? null : onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          color: active ? T.surfaceAlt : Colors.transparent,
          borderRadius: T.radiusMedium,
          border: Border.all(color: active ? T.primary : T.border),
        ),
        child: Text(
          label,
          style: T.small(
            color: active ? T.primary : T.textMuted,
            weight: FontWeight.w600,
          ),
        ),
      ),
    );
    return Row(
      children: [
        seg(l.splitByItems, !split.even, () => _switchMode(even: false)),
        const SizedBox(width: 6),
        seg(l.splitEvenly, split.even, () => _switchMode(even: true)),
      ],
    );
  }

  // left column: what still has to be dealt onto a bill
  Widget _unassignedPanel(SplitInfo split, L l) {
    final linesById = {for (final line in _check.lines) line.id: line};
    return Container(
      color: T.surface,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 6),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(l.unassignedItems, style: T.text(weight: FontWeight.w600)),
                Text(l.tapToAssignHint, style: T.small()),
              ],
            ),
          ),
          const Divider(),
          Expanded(
            child: split.unassigned.isEmpty
                ? Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(
                          LucideIcons.checkCheck,
                          color: T.primary,
                          size: 32,
                        ),
                        const SizedBox(height: 8),
                        Text(
                          l.allItemsAssigned,
                          style: T.small(color: T.primary),
                        ),
                      ],
                    ),
                  )
                : ListView(
                    padding: const EdgeInsets.symmetric(vertical: 6),
                    children: [
                      for (final slice in split.unassigned)
                        _sliceRow(
                          line: linesById[slice.lineId],
                          slice: slice,
                          enabled: !_locked && _selectedGroupId != null,
                          onTap: () => _assign(slice),
                          onPeel: () => _assign(slice, single: true),
                          l: l,
                        ),
                    ],
                  ),
          ),
        ],
      ),
    );
  }

  // right side: one card per bill group + the add tile
  Widget _groupsPanel(SplitInfo split, L l) {
    final portrait = MediaQuery.of(context).size.width < 1100;
    return GridView.count(
      crossAxisCount: split.even ? (portrait ? 2 : 3) : (portrait ? 1 : 2),
      padding: const EdgeInsets.all(12),
      mainAxisSpacing: 10,
      crossAxisSpacing: 10,
      childAspectRatio: split.even ? 1.4 : (portrait ? 1.9 : 1.15),
      children: [
        for (final group in split.groups) _groupCard(split, group, l),
        if (!split.even && !_locked)
          PosPanel(
            onTap: _busy
                ? null
                : () => _guarded(() => Api.addSplitGroup(_check.id)),
            child: Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(LucideIcons.plus, size: 28, color: T.textMuted),
                  const SizedBox(height: 6),
                  Text(l.addGroup, style: T.small(weight: FontWeight.w600)),
                ],
              ),
            ),
          ),
      ],
    );
  }

  Widget _groupCard(SplitInfo split, BillGroup group, L l) {
    final linesById = {for (final line in _check.lines) line.id: line};
    final selected = group.id == _selectedGroupId && !split.even;
    final selectable = !split.even && !_locked && !group.isPaid;
    final showCorkage = group.includesCorkage && _check.corkageBottles > 0;
    return PosPanel(
      color: group.isPaid
          ? T.surface
          : (selected ? T.primary.withValues(alpha: .07) : T.surfaceAlt),
      borderColor: group.isPaid ? T.border : (selected ? T.primary : T.border),
      onTap: selectable
          ? () => setState(() => _selectedGroupId = group.id)
          : null,
      padding: EdgeInsets.zero,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // header: bill number + status pills + delete
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 10, 6, 4),
            child: Row(
              children: [
                Text(
                  l.groupTitle(group.number),
                  style: T.text(
                    weight: FontWeight.w600,
                    color: selected ? T.primary : T.textPrimary,
                  ),
                ),
                const SizedBox(width: 8),
                if (group.isPaid) Pill(l.paid, color: T.primary),
                const Spacer(),
                if (showCorkage)
                  InkWell(
                    borderRadius: T.radiusMedium,
                    onTap: _locked ? null : _moveCorkage,
                    child: Pill(
                      '${l.corkage} ×${_check.corkageBottles}',
                      color: T.attention,
                    ),
                  ),
                if (!_locked &&
                    !split.even &&
                    split.groups.length > 1 &&
                    !group.isPaid)
                  IconButton(
                    icon: const Icon(LucideIcons.x, size: 18),
                    tooltip: l.deleteGroup,
                    constraints: const BoxConstraints(
                      minWidth: 44,
                      minHeight: 44,
                    ),
                    onPressed: _busy
                        ? null
                        : () => _guarded(
                            () => Api.deleteSplitGroup(_check.id, group.id),
                          ),
                  ),
              ],
            ),
          ),
          const Divider(),
          // allocated lines (tap to send back) — even mode has none by design
          Expanded(
            child: split.even
                ? Center(
                    child: Text(
                      '÷${split.groups.length}',
                      style: T.price(size: 28, color: T.textMuted),
                    ),
                  )
                : group.allocations.isEmpty && !showCorkage
                ? Center(child: Text(l.noItemsYet, style: T.small()))
                : ListView(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    children: [
                      for (final slice in group.allocations)
                        _sliceRow(
                          line: linesById[slice.lineId],
                          slice: slice,
                          enabled: !_locked && !group.isPaid,
                          onTap: () => _unassign(group, slice),
                          onPeel: () => _unassign(group, slice, single: true),
                          l: l,
                          dense: true,
                        ),
                      for (final fee in group.fees)
                        Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 14,
                            vertical: 4,
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
                                style: T.price(size: 15, color: T.textMuted),
                              ),
                            ],
                          ),
                        ),
                    ],
                  ),
          ),
          const Divider(),
          // footer: server-computed total + print/pay
          Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(l.total, style: T.small(weight: FontWeight.w600)),
                    Text(
                      cad(group.grandTotalCents),
                      style: T.price(size: 24, weight: FontWeight.w600),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                if (group.isPaid)
                  SizedBox(
                    height: T.minTouch,
                    child: Center(
                      child: Text(
                        l.paid,
                        style: T.text(
                          color: T.primary,
                          weight: FontWeight.w600,
                        ),
                      ),
                    ),
                  )
                else
                  Row(
                    children: [
                      OutlinedButton.icon(
                        icon: const Icon(LucideIcons.receiptText, size: 18),
                        label: Text(l.printBill),
                        onPressed: _groupHasContent(split, group) && !_busy
                            ? () => _printGroupBill(group)
                            : null,
                        style: OutlinedButton.styleFrom(
                          minimumSize: const Size(0, T.minTouch),
                          padding: const EdgeInsets.symmetric(horizontal: 12),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: SizedBox(
                          height: T.minTouch,
                          child: FilledButton.icon(
                            icon: const Icon(LucideIcons.banknote),
                            label: Text(l.pay),
                            // paying needs the whole split dealt out (server
                            // locks totals at first tender), not just this card
                            onPressed:
                                _groupHasContent(split, group) &&
                                    split.unassigned.isEmpty &&
                                    !_busy
                                ? () => _payGroup(group)
                                : null,
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

  bool _groupHasContent(SplitInfo split, BillGroup group) =>
      split.even ||
      group.allocations.isNotEmpty ||
      (group.includesCorkage && _check.corkageBottles > 0);

  /// One movable (line, qty) slice — used on both sides. Tap moves the whole
  /// slice; the "1 only" chip peels a single unit off a qty>1 slice.
  Widget _sliceRow({
    required CheckLine? line,
    required Allocation slice,
    required bool enabled,
    required VoidCallback onTap,
    required VoidCallback onPeel,
    required L l,
    bool dense = false,
  }) {
    if (line == null) return const SizedBox.shrink(); // stale poll frame
    final title = line.variantLabelFr == null
        ? l.name(line.nameFr, line.nameEn)
        : '${l.name(line.nameFr, line.nameEn)} · '
              '${l.name(line.variantLabelFr!, line.variantLabelEn ?? line.variantLabelFr!)}';
    return InkWell(
      onTap: enabled ? onTap : null,
      child: Padding(
        padding: EdgeInsets.symmetric(horizontal: 14, vertical: dense ? 2 : 4),
        child: Row(
          children: [
            Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 10),
                child: Text(
                  '$title ×${slice.qty}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: T.text(
                    size: dense ? 15 : 16,
                    color: enabled ? T.textPrimary : T.textMuted,
                  ),
                ),
              ),
            ),
            Text(
              cad(line.unitPriceCents * slice.qty),
              style: T.price(size: dense ? 15 : 16),
            ),
            if (slice.qty > 1 && enabled) ...[
              const SizedBox(width: 8),
              SizedBox(
                height: 40,
                child: OutlinedButton(
                  onPressed: onPeel,
                  style: OutlinedButton.styleFrom(
                    minimumSize: const Size(52, 40),
                    padding: const EdgeInsets.symmetric(horizontal: 10),
                  ),
                  child: Text(
                    l.peelOne,
                    style: T.small(weight: FontWeight.w600),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
