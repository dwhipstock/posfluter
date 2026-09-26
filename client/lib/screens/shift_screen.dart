import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import '../widgets/pin_pad.dart';
import '../widgets/resume_refresh.dart';
import 'receipt_screen.dart';

/// Shift = the business day. One scannable page: big revenue number on top,
/// horizontal segmented bar for the tender breakdown, item mix below. No tabs.
/// A small range dropdown swaps the backing query (today/yesterday/week/
/// custom) with the same layout; Z-close stays shift-only.
class ShiftScreen extends StatefulWidget {
  const ShiftScreen({super.key});

  @override
  State<ShiftScreen> createState() => _ShiftScreenState();
}

enum _ReportRange { shift, today, yesterday, week, custom }

class _ShiftScreenState extends State<ShiftScreen> with ResumeRefresh {
  @override
  void onAppResume() => _load();

  ShiftInfo? _shift;
  ShiftReport? _report;
  bool _loaded = false, _busy = false;
  String? _loadError; // a failed load must NOT render as "no shift open"
  _ReportRange _range = _ReportRange.shift;
  DateTime? _customFrom, _customTo;
  DateTime? _venueToday;
  final _float = TextEditingController(text: '1000');
  final _counted = TextEditingController();

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// From/to dates for the selected range (null for the live shift).
  (DateTime, DateTime)? get _rangeDates {
    if (_range == _ReportRange.shift) return null;
    final now = _venueToday;
    if (now == null) return null;
    final today = DateTime(now.year, now.month, now.day);
    return switch (_range) {
      _ReportRange.shift => null,
      _ReportRange.today => (today, today),
      _ReportRange.yesterday => (
        today.subtract(const Duration(days: 1)),
        today.subtract(const Duration(days: 1)),
      ),
      _ReportRange.week => (
        today.subtract(Duration(days: today.weekday - 1)), // Monday
        today,
      ),
      _ReportRange.custom => (_customFrom ?? today, _customTo ?? today),
    };
  }

  static String _iso(DateTime d) =>
      '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  Future<void> _load() async {
    setState(() {
      _loaded = false;
      _loadError = null;
    });
    try {
      if (_range != _ReportRange.shift) {
        _venueToday = await Api.venueToday();
      }
      final dates = _rangeDates;
      if (dates == null) {
        final shift = await Api.currentShift();
        final report = shift != null ? await Api.xReport() : null;
        if (!mounted) return;
        setState(() {
          _shift = shift;
          _report = report;
          _loaded = true;
        });
      } else {
        final report = await Api.rangeReport(_iso(dates.$1), _iso(dates.$2));
        if (!mounted) return;
        setState(() {
          _report = report;
          _loaded = true;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _loaded = true;
          _loadError = e is SessionExpiredException ? '' : '$e';
        });
      }
    }
  }

  Future<void> _pickCustomRange() async {
    late final DateTime today;
    try {
      today = await Api.venueToday();
    } catch (e) {
      if (mounted) showApiError(context, e);
      return;
    }
    if (!mounted) return;
    _venueToday = today;
    final from = await showDatePicker(
      context: context,
      initialDate: _customFrom ?? today,
      firstDate: DateTime(2026),
      lastDate: today,
    );
    if (from == null || !mounted) return;
    final to = await showDatePicker(
      context: context,
      initialDate: _customTo == null || _customTo!.isBefore(from)
          ? from
          : _customTo!,
      firstDate: from,
      lastDate: today,
    );
    if (to == null || !mounted) return;
    setState(() {
      _customFrom = from;
      _customTo = to;
      _range = _ReportRange.custom;
    });
    _load();
  }

  void _setRange(_ReportRange range) {
    if (range == _ReportRange.custom) {
      _pickCustomRange();
      return;
    }
    setState(() => _range = range);
    _load();
  }

  Future<void> _guard(Future<void> Function() op) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await op();
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _openShift() => _guard(() async {
    final floatCAD = int.tryParse(_float.text) ?? 0;
    final approval = await requireGrant(
      context,
      Perm.openShift,
      title: L.of(context).openShiftApproval,
    );
    if (approval == null) return;
    await Api.openShift(floatCAD * 100, approval.managerPin);
    await _load();
  });

  Future<void> _closeShift() => _guard(() async {
    final counted = int.tryParse(_counted.text);
    if (counted == null) return;
    final approval = await requireGrant(
      context,
      Perm.closeShift,
      title: L.of(context).closeShiftApproval,
    );
    if (approval == null) return;
    final z = await Api.closeShift(counted * 100, approval.managerPin);
    if (!mounted) return;
    await showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) {
        final l = L.of(context);
        return AlertDialog(
          title: Text(l.zReportDone),
          content: SizedBox(
            width: 440,
            child: SingleChildScrollView(child: _reportBody(z)),
          ),
          actions: [
            FilledButton(
              onPressed: () => Navigator.pop(context),
              child: Text(l.done),
            ),
          ],
        );
      },
    );
    if (mounted) Navigator.pop(context);
  });

  /// Record a non-sale cash movement (IN/OUT): amount + reason → manager PIN →
  /// server records it against the open shift and returns a till slip.
  Future<void> _cashMovement(String dir) => _guard(() async {
    final l = L.of(context);
    final input = await _askCashMovement(l, dir);
    if (input == null || !mounted) return;
    final (amountCents, reason) = input;
    final approval = await requireGrant(
      context,
      Perm.cashMovement,
      title: dir == 'IN' ? l.cashInApproval : l.cashOutApproval,
    );
    if (approval == null || !mounted) return;
    final result = await Api.recordCashMovement(
      dir,
      amountCents,
      reason,
      approval.managerPin,
    );
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          dir == 'IN'
              ? l.cashInDone(money(amountCents))
              : l.cashOutDone(money(amountCents)),
        ),
      ),
    );
    await _load();
    if (!mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => ReceiptScreen(
          checkId: result.movement.id,
          title: dir == 'IN' ? l.cashIn : l.cashOut,
          text: result.slipText,
        ),
      ),
    );
  });

  Future<(int, String)?> _askCashMovement(L l, String dir) {
    final amount = TextEditingController();
    final reason = TextEditingController();
    return showDialog<(int, String)>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(dir == 'IN' ? l.cashIn : l.cashOut),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: amount,
              autofocus: true,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
              ),
              style: T.price(),
              decoration: InputDecoration(labelText: l.cashAmountLabel),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: reason,
              decoration: InputDecoration(labelText: l.cashReasonLabel),
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
              final v = double.tryParse(amount.text.trim());
              final r = reason.text.trim();
              if (v == null || v <= 0 || r.isEmpty) return;
              Navigator.pop(context, ((v * 100).round(), r));
            },
            child: Text(l.ok),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(l.shiftReports),
        actions: [
          const LangActions(),
          if (_shift != null)
            IconButton(
              icon: const Icon(LucideIcons.refreshCw),
              onPressed: _load,
              tooltip: l.xReport,
            ),
        ],
      ),
      body: Column(
        children: [
          // range selector: same report layout, different backing query
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
            child: Row(
              children: [
                DropdownButton<_ReportRange>(
                  value: _range,
                  underline: const SizedBox(),
                  onChanged: (v) => v == null ? null : _setRange(v),
                  items: [
                    DropdownMenuItem(
                      value: _ReportRange.shift,
                      child: Text(l.rangeThisShift),
                    ),
                    DropdownMenuItem(
                      value: _ReportRange.today,
                      child: Text(l.rangeToday),
                    ),
                    DropdownMenuItem(
                      value: _ReportRange.yesterday,
                      child: Text(l.rangeYesterday),
                    ),
                    DropdownMenuItem(
                      value: _ReportRange.week,
                      child: Text(l.rangeThisWeek),
                    ),
                    DropdownMenuItem(
                      value: _ReportRange.custom,
                      child: Text(l.rangeCustom),
                    ),
                  ],
                ),
                if (_range == _ReportRange.custom) ...[
                  const SizedBox(width: 12),
                  TextButton.icon(
                    icon: const Icon(LucideIcons.calendar, size: 16),
                    label: Text(
                      _customFrom != null && _customTo != null
                          ? '${Prefs.instance.fmtDate(_customFrom!)} – ${Prefs.instance.fmtDate(_customTo!)}'
                          : l.rangeCustom,
                    ),
                    onPressed: _pickCustomRange,
                  ),
                ],
              ],
            ),
          ),
          Expanded(
            child: !_loaded
                ? const DelayedSpinner()
                : _loadError != null
                ? Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Text(
                          _loadError!.isEmpty
                              ? l.cannotReachServer
                              : _loadError!,
                        ),
                        const SizedBox(height: 12),
                        FilledButton(onPressed: _load, child: Text(l.retry)),
                      ],
                    ),
                  )
                : _range != _ReportRange.shift
                ? _rangeView(l)
                : _shift == null
                ? _openShiftForm(l)
                : _openShiftView(l),
          ),
        ],
      ),
    );
  }

  Widget _openShiftForm(L l) {
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 400),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              l.noShiftOpen,
              style: T.headline(),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _float,
              keyboardType: TextInputType.number,
              style: T.price(),
              decoration: InputDecoration(labelText: l.openingFloat),
            ),
            const SizedBox(height: 16),
            SizedBox(
              height: T.minTouch,
              child: FilledButton.icon(
                icon: const Icon(LucideIcons.play),
                label: Text(l.openShift),
                onPressed: _busy ? null : _openShift,
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// Historical range: same one-page report, no drawer reconciliation,
  /// no Z-close (that stays shift-only).
  Widget _rangeView(L l) {
    final report = _report;
    final dates = _rangeDates!;
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 620),
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            Text(
              l.rangeTitle(
                Prefs.instance.fmtDate(dates.$1),
                Prefs.instance.fmtDate(dates.$2),
              ),
              style: T.headline(),
            ),
            const SizedBox(height: 16),
            if (report != null) _reportBody(report),
            const SizedBox(height: 24),
          ],
        ),
      ),
    );
  }

  Widget _openShiftView(L l) {
    final report = _report;
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 620),
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    l.shiftOpenTitle(_shift!.id),
                    style: T.headline(),
                  ),
                ),
                const Pill('OPEN', color: T.primary),
              ],
            ),
            Text(
              l.shiftOpenedLine(
                Prefs.instance.fmtDateTime(_shift!.openedAt),
                _shift!.openedBy,
                money(_shift!.openingFloatCents),
              ),
              style: T.small(),
            ),
            const SizedBox(height: 16),
            if (report != null) _reportBody(report),
            const SizedBox(height: 24),
            SectionLabel(l.till),
            Row(
              children: [
                Expanded(
                  child: SizedBox(
                    height: T.minTouch,
                    child: OutlinedButton.icon(
                      icon: const Icon(LucideIcons.arrowDownToLine),
                      label: Text(l.cashIn),
                      onPressed: _busy ? null : () => _cashMovement('IN'),
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: SizedBox(
                    height: T.minTouch,
                    child: OutlinedButton.icon(
                      icon: const Icon(LucideIcons.arrowUpFromLine),
                      label: Text(l.cashOut),
                      onPressed: _busy ? null : () => _cashMovement('OUT'),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 24),
            SectionLabel(l.closeShiftZ),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _counted,
                    keyboardType: TextInputType.number,
                    style: T.price(),
                    decoration: InputDecoration(labelText: l.countedCash),
                  ),
                ),
                const SizedBox(width: 10),
                SizedBox(
                  height: T.minTouch,
                  child: FilledButton.icon(
                    icon: const Icon(LucideIcons.square),
                    style: FilledButton.styleFrom(
                      backgroundColor: T.destructive,
                      foregroundColor: T.onDestructive,
                    ),
                    label: Text(l.closeShift),
                    onPressed: _busy ? null : _closeShift,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 24),
          ],
        ),
      ),
    );
  }

  Widget _reportBody(ShiftReport r) {
    final l = L.of(context);
    final tenderLabels = {
      'CASH': l.cash,
      'CARD': l.card,
      'BANK_TRANSFER': l.bankTransfer,
      'STRIPE': l.cardStripe,
      'TERMINAL': l.cardTerminalTender,
    };
    const tenderColors = {
      'CASH': T.primary,
      'CARD': T.textPrimary,
      'BANK_TRANSFER': T.textMuted,
      'STRIPE': T.accent,
      'TERMINAL': T.accent,
    };

    Widget kv(String label, String value, {bool bold = false, Color? color}) =>
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                label,
                style: T.text(size: 15, color: color ?? T.textPrimary),
              ),
              Text(
                value,
                style: T.price(
                  size: 16,
                  weight: bold ? FontWeight.w700 : FontWeight.w500,
                  color: color ?? T.textPrimary,
                ),
              ),
            ],
          ),
        );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // big revenue number
        PosPanel(
          padding: const EdgeInsets.all(16),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(l.revenue, style: T.small()),
                    Text(
                      money(r.revenueCents),
                      style: T.price(size: 40, weight: FontWeight.w700),
                    ),
                  ],
                ),
              ),
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(
                    '${l.billCount}  ${r.transactionCount}',
                    style: T.small(),
                  ),
                  Text(
                    '${l.avgPerBill}  ${money(r.avgCheckCents)}',
                    style: T.small(),
                  ),
                  if (r.corkageCents > 0)
                    Text(
                      '${l.corkage}  ${money(r.corkageCents)}',
                      style: T.small(),
                    ),
                ],
              ),
            ],
          ),
        ),
        SectionLabel(l.byTender),
        // horizontal segmented bar
        if (r.tenderBreakdown.isNotEmpty) ...[
          ClipRRect(
            borderRadius: T.radiusSmall,
            child: SizedBox(
              height: 14,
              child: Row(
                children: [
                  for (final t in r.tenderBreakdown)
                    Expanded(
                      flex: (t.amountCents / 100).round().clamp(1, 1 << 30),
                      child: ColoredBox(
                        color: tenderColors[t.type] ?? T.textMuted,
                      ),
                    ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 8),
          for (final t in r.tenderBreakdown)
            kv(
              '●  ${tenderLabels[t.type] ?? t.type} (${t.count})',
              money(t.amountCents),
              color: tenderColors[t.type],
            ),
        ] else
          Text('—', style: T.small()),
        SectionLabel(l.topItems),
        for (final i in r.itemMix)
          kv('${l.name(i.nameFr, i.nameEn)} ×${i.qty}', money(i.revenueCents)),
        if (r.voids.isNotEmpty) ...[
          SectionLabel(l.voidedBills),
          for (final v in r.voids)
            kv(
              '${l.billNo(v.checkId)} — ${v.reason}',
              v.voidedBy,
              color: T.destructive,
            ),
        ],
        // non-sale cash movements + refunds that feed the expected-cash math
        if (r.cashPaidInCents > 0 ||
            r.cashPaidOutCents > 0 ||
            r.refundTotalCents > 0) ...[
          SectionLabel(l.paidInOut),
          if (r.cashPaidInCents > 0)
            kv('▲ ${l.cashIn}', money(r.cashPaidInCents), color: T.primary),
          if (r.cashPaidOutCents > 0)
            kv(
              '▼ ${l.cashOut}',
              money(r.cashPaidOutCents),
              color: T.destructive,
            ),
          if (r.refundTotalCents > 0)
            kv(l.refunds, money(r.refundTotalCents), color: T.destructive),
          if (r.cashRefundCents > 0)
            kv(l.cashRefunds, money(r.cashRefundCents), color: T.destructive),
        ],
        if (r.expectedCashCents == null && r.cashRoundingCents != 0) ...[
          const SizedBox(height: 12),
          kv(l.cashRounding, signedMoney(r.cashRoundingCents)),
        ],
        if (r.expectedCashCents != null) ...[
          const SizedBox(height: 12),
          PosPanel(
            padding: const EdgeInsets.all(16),
            child: Column(
              children: [
                if (r.cashRoundingCents != 0)
                  kv(l.cashRounding, signedMoney(r.cashRoundingCents)),
                kv(
                  l.expectedCash,
                  money(r.expectedCashCents!),
                  bold: r.closingCountCents == null,
                ),
                // Z only: the X-report has expected cash but no count yet
                if (r.closingCountCents != null)
                  kv(l.countedActual, money(r.closingCountCents!)),
                if (r.overShortCents != null)
                  kv(
                    l.overShort,
                    money(r.overShortCents!),
                    bold: true,
                    color: r.overShortCents! < 0 ? T.destructive : T.primary,
                  ),
              ],
            ),
          ),
        ],
      ],
    );
  }
}
