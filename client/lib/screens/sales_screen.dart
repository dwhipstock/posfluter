import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import '../widgets/pin_pad.dart';
import 'receipt_screen.dart';

/// The refund picker: recent CLOSED bills, each with how much is still
/// refundable. Tap one to refund it (full / by item / by amount). Refunds are
/// distinct from voids — a void cancels a bill before money is taken; a refund
/// returns money on a bill that already closed.
class SalesScreen extends StatefulWidget {
  const SalesScreen({super.key});

  @override
  State<SalesScreen> createState() => _SalesScreenState();
}

class _SalesScreenState extends State<SalesScreen> {
  List<ClosedCheckSummary>? _checks;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final checks = await Api.recentClosedChecks();
      if (!mounted) return;
      setState(() {
        _checks = checks;
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = e.toString());
    }
  }

  Future<void> _openRefund(ClosedCheckSummary c) async {
    final refunded = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => RefundScreen(checkId: c.id)),
    );
    if (refunded == true) _load();
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final checks = _checks;
    return Scaffold(
      appBar: AppBar(
        title: Text(l.salesTitle),
        actions: [
          const LangActions(),
          IconButton(icon: const Icon(LucideIcons.refreshCw), onPressed: _load),
        ],
      ),
      body: _error != null
          ? Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(l.cannotReachServer),
                  const SizedBox(height: 12),
                  FilledButton(onPressed: _load, child: Text(l.retry)),
                ],
              ),
            )
          : checks == null
          ? const DelayedSpinner()
          : checks.isEmpty
          ? Center(child: Text(l.noClosedBills, style: T.small()))
          : ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: checks.length,
              separatorBuilder: (_, _) => const SizedBox(height: 8),
              itemBuilder: (_, i) => _checkTile(l, checks[i]),
            ),
    );
  }

  Widget _checkTile(L l, ClosedCheckSummary c) {
    final fullyRefunded = c.refundableCents <= 0;
    return PosPanel(
      padding: EdgeInsets.zero,
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
        title: Text(
          '${l.bill} #${c.id}  ·  ${c.tableLabel}',
          style: T.text(size: 16, weight: FontWeight.w600),
        ),
        subtitle: Text(
          '${Prefs.instance.fmtDateTime(c.closedAt)}  ·  ${money(c.grandTotalCents)}'
          '${c.refundedCents > 0 ? '  ·  ${l.refundedLabel} ${money(c.refundedCents)}' : ''}',
          style: T.small(),
        ),
        trailing: fullyRefunded
            ? Pill(l.fullyRefunded, color: T.textMuted)
            : const Icon(LucideIcons.chevronRight, color: T.textMuted),
        onTap: fullyRefunded ? null : () => _openRefund(c),
      ),
    );
  }
}

enum _RefundMode { full, byLine, byAmount }

/// Refund one finalized bill: full, by item, or by amount. Confirm collects a
/// manager PIN and a reason, then the server reverses the included tax and
/// returns a slip.
class RefundScreen extends StatefulWidget {
  final int checkId;
  const RefundScreen({super.key, required this.checkId});

  @override
  State<RefundScreen> createState() => _RefundScreenState();
}

class _RefundScreenState extends State<RefundScreen> {
  Check? _check;
  RefundInfo? _info;
  String? _error;
  bool _busy = false;

  _RefundMode _mode = _RefundMode.full;
  String _tender = 'CASH';
  final _amount = TextEditingController();
  // by-line: lineId -> qty selected to refund
  final Map<int, int> _lineQty = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final check = await Api.getCheck(widget.checkId);
      final info = await Api.refundInfo(widget.checkId);
      if (!mounted) return;
      setState(() {
        _check = check;
        _info = info;
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = e.toString());
    }
  }

  int get _refundable => _info?.refundableCents ?? 0;

  /// Gross cents the current selection will refund.
  int get _selectedGross {
    switch (_mode) {
      case _RefundMode.full:
        return _refundable;
      case _RefundMode.byAmount:
        final v = double.tryParse(_amount.text.trim());
        return v == null ? 0 : (v * 100).round();
      case _RefundMode.byLine:
        final lines = {
          for (final ln in _check!.lines) ln.id: ln.unitPriceCents,
        };
        final preTax = _lineQty.entries.fold(
          0,
          (sum, e) => sum + (lines[e.key] ?? 0) * e.value,
        );
        return _withTax(preTax);
    }
  }

  /// Line prices are pre-tax; a by-line refund also returns the lines' share
  /// of the taxes added on top — the same math the server applies (half-up,
  /// capped at what is still refundable). Preview only; the server decides.
  int _withTax(int preTax) {
    final c = _check!;
    final subtotal = c.subtotalCents;
    if (c.taxes.isEmpty || subtotal <= 0 || preTax <= 0) return preTax;
    final gross = (preTax * c.grandTotalCents * 2 + subtotal) ~/ (subtotal * 2);
    return _refundable > 0 && gross > _refundable ? _refundable : gross;
  }

  bool get _canRefund =>
      _selectedGross > 0 && _selectedGross <= _refundable && !_busy;

  Future<String?> _askReason(L l) {
    final custom = TextEditingController();
    return showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l.refundReasonTitle),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (final r in l.refundReasons)
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
  }

  Future<void> _confirm() async {
    final l = L.of(context);
    if (!_canRefund) return;
    // pick → grant check (manager PIN only if the server lacks it) → reason → refund
    final approval = await requireGrant(
      context,
      Perm.refund,
      title: l.refundApprovalTitle,
    );
    if (approval == null || !mounted) return;
    final pin = approval.managerPin;
    final reason = await _askReason(l);
    if (reason == null || reason.isEmpty || !mounted) return;

    setState(() => _busy = true);
    try {
      final lines = _mode == _RefundMode.byLine
          ? _lineQty.entries
                .where((e) => e.value > 0)
                .map((e) => {'lineId': e.key, 'qty': e.value})
                .toList()
          : null;
      final result = await Api.refundCheck(
        widget.checkId,
        amountCents: _mode == _RefundMode.byLine ? null : _selectedGross,
        lines: lines,
        tenderType: _tender,
        reason: reason,
        managerPin: pin,
      );
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(l.refundDone(money(result.refund.grossCents)))),
      );
      await Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) => ReceiptScreen(
            checkId: widget.checkId,
            title: l.refundSlipTitle,
            text: result.slipText,
          ),
        ),
      );
      if (mounted) Navigator.of(context).pop(true); // tell Sales to reload
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final check = _check;
    final info = _info;
    return Scaffold(
      appBar: AppBar(title: Text('${l.refundTitle} #${widget.checkId}')),
      body: _error != null
          ? Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(l.cannotReachServer),
                  const SizedBox(height: 12),
                  FilledButton(onPressed: _load, child: Text(l.retry)),
                ],
              ),
            )
          : (check == null || info == null)
          ? const DelayedSpinner()
          : _body(l, check, info),
      bottomNavigationBar: (check == null || info == null)
          ? null
          : SafeArea(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: FilledButton.icon(
                  icon: const Icon(LucideIcons.undo2),
                  label: Text(
                    '${l.confirmRefund}  ·  ${money(_selectedGross)}',
                  ),
                  onPressed: _canRefund ? _confirm : null,
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(T.minTouch),
                  ),
                ),
              ),
            ),
    );
  }

  Widget _body(L l, Check check, RefundInfo info) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        PosPanel(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _kv(l.bill, money(info.grandTotalCents)),
              if (info.refundedCents > 0)
                _kv(
                  l.refundedLabel,
                  money(info.refundedCents),
                  color: T.textMuted,
                ),
              _kv(
                l.refundableLabel,
                money(info.refundableCents),
                bold: true,
                color: T.primary,
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        SegmentedButton<_RefundMode>(
          segments: [
            ButtonSegment(value: _RefundMode.full, label: Text(l.refundFull)),
            ButtonSegment(
              value: _RefundMode.byLine,
              label: Text(l.refundByLine),
            ),
            ButtonSegment(
              value: _RefundMode.byAmount,
              label: Text(l.refundByAmount),
            ),
          ],
          selected: {_mode},
          onSelectionChanged: (s) => setState(() => _mode = s.first),
        ),
        const SizedBox(height: 16),
        if (_mode == _RefundMode.byAmount)
          TextField(
            controller: _amount,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            style: T.price(),
            onChanged: (_) => setState(() {}),
            decoration: InputDecoration(
              labelText: l.refundAmountLabel,
              helperText: _selectedGross > _refundable
                  ? l.amountExceedsRefundable
                  : null,
              helperStyle: const TextStyle(color: T.destructive),
            ),
          ),
        if (_mode == _RefundMode.byLine) ...[
          Text(l.pickLinesHint, style: T.small()),
          const SizedBox(height: 8),
          for (final ln in check.lines) _lineRow(l, ln),
        ],
        const SizedBox(height: 20),
        SectionLabel(l.refundTender),
        Wrap(
          spacing: 8,
          children: [
            for (final t in [
              'CASH',
              'CARD',
              'BANK_TRANSFER',
              // back to the Stripe card: only when this bill was paid that way
              if (info.stripeRefundableCents > 0) 'STRIPE',
            ])
              ChoiceChip(
                label: Text(_tenderLabel(l, t)),
                selected: _tender == t,
                onSelected: (_) => setState(() => _tender = t),
              ),
          ],
        ),
        if (info.refunds.isNotEmpty) ...[
          const SizedBox(height: 20),
          SectionLabel(l.refundHistory),
          for (final r in info.refunds)
            _kv(
              '${_tenderLabel(l, r.tenderType)} · ${r.reason}',
              money(r.grossCents),
              color: T.textMuted,
            ),
        ],
        const SizedBox(height: 80),
      ],
    );
  }

  Widget _lineRow(L l, CheckLine ln) {
    final qty = _lineQty[ln.id] ?? 0;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Expanded(
            child: Text(
              '${l.name(ln.nameFr, ln.nameEn)}  ×${ln.qty}',
              style: T.text(size: 15),
            ),
          ),
          Text(money(ln.unitPriceCents), style: T.small()),
          const SizedBox(width: 8),
          IconButton(
            icon: const Icon(LucideIcons.minus, size: 18),
            onPressed: qty > 0
                ? () => setState(() => _lineQty[ln.id] = qty - 1)
                : null,
          ),
          SizedBox(
            width: 22,
            child: Text('$qty', textAlign: TextAlign.center, style: T.price()),
          ),
          IconButton(
            icon: const Icon(LucideIcons.plus, size: 18),
            onPressed: qty < ln.qty
                ? () => setState(() => _lineQty[ln.id] = qty + 1)
                : null,
          ),
        ],
      ),
    );
  }

  String _tenderLabel(L l, String t) => switch (t) {
    'CASH' => l.cash,
    'CARD' => l.card,
    'BANK_TRANSFER' => l.bankTransfer,
    'STRIPE' => l.cardStripe,
    _ => t,
  };

  Widget _kv(String label, String value, {bool bold = false, Color? color}) =>
      Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Expanded(
              child: Text(
                label,
                style: T.text(size: 15, color: color ?? T.textPrimary),
              ),
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
}
