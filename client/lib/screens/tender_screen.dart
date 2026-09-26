import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';
import '../payments/card_reader.dart';
import 'receipt_screen.dart';
import 'stripe_payment_screen.dart';
import '../widgets/tax_rows.dart';

/// Split-tender payment. Three big method tiles across the top, outstanding
/// prominent, quick-amount strip + numpad for cash. The check closes (and the
/// receipt prints) when the balance hits zero.
///
/// [groupId] scopes everything to one bill group of a split check: the due
/// amount is the group's outstanding, tenders pay into the group, and settling
/// the group pops back to the split view (the check itself closes only when
/// the LAST group settles).
class TenderScreen extends StatefulWidget {
  final Check check;
  final int? groupId;

  /// Test seams for the optional "Card (Stripe)" tender. Defaults: the store's
  /// /stripe/status, the Stripe Terminal SDK, and "Android only".
  final Future<StripeStatus> Function()? stripeStatus;
  final CardReader? cardReader;
  final bool? cardReaderSupported;
  const TenderScreen({
    super.key,
    required this.check,
    this.groupId,
    this.stripeStatus,
    this.cardReader,
    this.cardReaderSupported,
  });

  @override
  State<TenderScreen> createState() => _TenderScreenState();
}

class _TenderScreenState extends State<TenderScreen> {
  late Check _check = widget.check;
  String _method = 'CASH';
  String _entry = ''; // numpad-entered CAD amount (digits only)
  TenderInstructions? _instructions;
  bool _busy = false;

  /// null while loading (and when the store has no Stripe key: not shown).
  StripeStatus? _stripe;
  SimulatedTestCard _simCard = SimulatedTestCard.approved;

  bool get _readerSupported =>
      widget.cardReaderSupported ?? StripeTerminalReader.supported;

  /// "Card (Stripe)" is shown only on a store with a Stripe key configured.
  bool get _stripeShown => _stripe?.configured == true;

  /// Why it is greyed out, or null when it can be used.
  String? get _stripeBlocked {
    final st = _stripe;
    if (st == null || !st.configured) return 'stripe_not_configured';
    if (!_readerSupported) return 'stripe_unsupported';
    if (StripeTerminalReader.permissionDenied) {
      return 'stripe_permission_denied';
    }
    if (!st.available || st.locationId == null) {
      return st.reason ?? 'stripe_unavailable';
    }
    return null;
  }

  BillGroup? get _group => widget.groupId == null
      ? null
      : _check.split?.groups.where((g) => g.id == widget.groupId).firstOrNull;
  int get _due => _group?.outstandingCents ?? _check.outstandingCents;

  /// Paying in cash: the server's nickel-rounded amount and the signed
  /// rounding. Card / bank transfer / Stripe always pay the exact [_due].
  int get _cashDue => _group?.cashDueCents ?? _check.cashDueCents;
  int get _cashRounding =>
      _group?.cashRoundingCents ?? _check.cashRoundingCents;
  int? get _entryCAD => _entry.isEmpty ? null : int.parse(_entry);

  /// Never awaited by anything else on this screen: cash is usable at once,
  /// and a slow or offline Stripe just leaves the option greyed out.
  Future<void> _loadStripe() async {
    StripeStatus st;
    try {
      st = await (widget.stripeStatus ?? Api.stripeStatus)();
    } catch (_) {
      st = StripeStatus.off;
    }
    if (!mounted) return;
    setState(() {
      _stripe = st;
      if (_method == 'STRIPE' && _stripeBlocked != null) _method = 'CASH';
    });
  }

  @override
  void initState() {
    super.initState();
    _loadStripe();
    // Recovery: a check already fully paid but still TOTAL_LOCKED (e.g. finalize
    // was interrupted after the last tender, or the app died between the two) has
    // no balance left to tender. Close it directly instead of stranding it — the
    // table would otherwise show it as "due" forever and refuse further tenders.
    if (_check.status == 'TOTAL_LOCKED' && _check.outstandingCents == 0) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _guard(_finishCheck);
      });
    }
  }

  /// Quick strip: exact cash due, then round-ups to the next 100 / 500 / 1000.
  List<int> get _quickAmounts {
    int ceilTo(int unitCents) =>
        ((_cashDue + unitCents - 1) ~/ unitCents) * unitCents;
    return {_cashDue, ceilTo(10000), ceilTo(50000), ceilTo(100000)}.toList()
      ..sort();
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

  Future<void> _tenderCash(int amountCents) => _guard(() async {
    final result = await Api.tenderCash(
      _check.id,
      amountCents,
      groupId: widget.groupId,
    );
    setState(() {
      _check = result.check;
      _entry = '';
    });
    if (result.check.outstandingCents == 0) {
      await _showCashDialog(result.tender);
      await _finishCheck();
    } else if (_due == 0) {
      // this group is settled, other groups still owe → back to the split view
      await _showCashDialog(result.tender);
      if (mounted) Navigator.pop(context, false);
    } else {
      if (mounted) {
        _toast(
          L
              .of(context)
              .receivedToast(money(result.tender.cashPaidCents), money(_due)),
        );
      }
    }
  });

  Future<void> _showInstructions(String type) => _guard(() async {
    final amount = _entryCAD == null ? null : _entryCAD! * 100;
    final instructions = await Api.initiateTender(
      _check.id,
      type,
      amountCents: amount,
      groupId: widget.groupId,
    );
    setState(() => _instructions = instructions);
  });

  Future<void> _confirmElectronic() => _guard(() async {
    final instructions = _instructions!;
    final result = await Api.confirmTender(
      _check.id,
      instructions.type,
      instructions.amountCents,
      groupId: widget.groupId,
    );
    setState(() {
      _check = result.check;
      _instructions = null;
      _entry = '';
    });
    if (result.check.outstandingCents == 0) {
      await _finishCheck();
    } else if (_due == 0) {
      if (mounted) Navigator.pop(context, false); // group settled, others owe
    } else {
      if (mounted) {
        _toast(
          L
              .of(context)
              .receivedToast(
                money(result.tender.amountAppliedCents),
                money(_due),
              ),
        );
      }
    }
  });

  /// Card (Stripe): run the reader flow on its own screen. It pops with the
  /// recorded tender, or null when cancelled/failed (nothing recorded).
  Future<void> _payStripe() async {
    final st = _stripe;
    if (_busy || st == null || _stripeBlocked != null) return;
    final amount = _entryCAD == null ? null : _entryCAD! * 100;
    final result = await Navigator.of(context).push<TenderResult>(
      MaterialPageRoute(
        builder: (_) => StripePaymentScreen(
          checkId: _check.id,
          groupId: widget.groupId,
          amountCents: amount,
          locationId: st.locationId!,
          simulatedCard: _simCard,
          reader: widget.cardReader ?? StripeTerminalReader.instance,
        ),
      ),
    );
    if (!mounted) return;
    if (result == null) {
      // cancelled or failed: nothing recorded — refresh (totals may have locked)
      // and re-check Stripe (a denied permission greys it out)
      try {
        final fresh = await Api.getCheck(_check.id);
        if (mounted) setState(() => _check = fresh);
      } catch (_) {}
      if (mounted) setState(() {});
      return;
    }
    await _guard(() async {
      setState(() {
        _check = result.check;
        _entry = '';
      });
      if (result.check.outstandingCents == 0) {
        await _finishCheck();
      } else if (_due == 0) {
        if (mounted) Navigator.pop(context, false); // group settled, others owe
      } else if (mounted) {
        _toast(
          L
              .of(context)
              .receivedToast(
                money(result.tender.amountAppliedCents),
                money(_due),
              ),
        );
      }
    });
  }

  Future<void> _finishCheck() async {
    await Api.finalizeCheck(_check.id);
    final receipt = await Api.receiptText(_check.id);
    if (!mounted) return;
    // Best-effort heads-up: the receipt printed server-side (async, never blocks
    // the sale), so surface a toast if the printer looks offline.
    _warnIfPrinterOffline();
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => ReceiptScreen(checkId: _check.id, text: receipt),
      ),
    );
    if (mounted) Navigator.pop(context, true); // true → check closed
  }

  Future<void> _warnIfPrinterOffline() async {
    try {
      final s = await Api.printerStatus();
      if (!mounted || !s.configured || s.online) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(L.of(context).printerOffline)));
    } catch (_) {
      /* printer status is advisory — never disrupt the close */
    }
  }

  Future<void> _showCashDialog(Tender tender) => showDialog(
    context: context,
    barrierDismissible: false,
    builder: (context) {
      final l = L.of(context);
      return AlertDialog(
        title: Text(l.cashReceivedTitle),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _row(l.cashReceived, money(tender.amountTenderedCents)),
            if (tender.roundingAdjustmentCents != 0) ...[
              _row(l.rounding, signedMoney(tender.roundingAdjustmentCents)),
              _row(l.cashTotal, money(tender.cashPaidCents)),
            ],
            _row(l.change, money(tender.changeCents), big: true),
          ],
        ),
        actions: [
          FilledButton(
            onPressed: () => Navigator.pop(context),
            child: Text(l.ok),
          ),
        ],
      );
    },
  );

  void _toast(String message) => ScaffoldMessenger.of(
    context,
  ).showSnackBar(SnackBar(content: Text(message)));

  Widget _row(
    String label,
    String value, {
    bool big = false,
    bool bigValue = false,
  }) {
    final style = big
        ? T.price(size: 26, weight: FontWeight.w600)
        : T.text(size: 16);
    final valueStyle = bigValue
        ? T.price(size: 26, weight: FontWeight.w700)
        : style;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Flexible(
            child: Text(
              label,
              style: bigValue ? T.text(weight: FontWeight.w600) : style,
            ),
          ),
          const SizedBox(width: 12),
          Text(value, style: valueStyle),
        ],
      ),
    );
  }

  void _numpadKey(String key) {
    setState(() {
      if (key == '⌫') {
        _entry = _entry.isEmpty ? '' : _entry.substring(0, _entry.length - 1);
      } else if (_entry.length < 7) {
        final next = _entry + key;
        _entry = int.parse(next) == 0 ? '' : next;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(
          _group == null
              ? '${l.pay} — ${l.bill} #${_check.id}'
              : '${l.pay} — ${l.bill} #${_check.id} · ${l.groupTitle(_group!.number)}',
        ),
        actions: const [LangActions()],
      ),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 560),
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // outstanding, prominent
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Text(l.outstanding, style: T.text(weight: FontWeight.w600)),
                    Text(
                      money(_due),
                      style: T.price(size: 44, weight: FontWeight.w700),
                    ),
                  ],
                ),
                // what the total is made of: pre-tax subtotal + GST / QST
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: TaxRows(
                    subtotalCents:
                        _group?.subtotalCents ?? _check.subtotalCents,
                    taxes: _group?.taxes ?? _check.taxes,
                    priceSize: 15,
                  ),
                ),
                if ((_group?.paidCents ?? _check.paidCents) > 0)
                  Align(
                    alignment: Alignment.centerRight,
                    child: Text(
                      l.paidOf(
                        money(_group?.paidCents ?? _check.paidCents),
                        money(
                          _group?.grandTotalCents ?? _check.grandTotalCents,
                        ),
                      ),
                      style: T.small(),
                    ),
                  ),
                // cash only: the server's nickel rounding and what to collect
                if (_method == 'CASH' && _cashRounding != 0)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: PosPanel(
                      key: const ValueKey('cash-rounding'),
                      color: T.surfaceAlt,
                      padding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 8,
                      ),
                      child: Column(
                        children: [
                          _row(l.rounding, signedMoney(_cashRounding)),
                          _row(l.cashTotal, money(_cashDue), bigValue: true),
                        ],
                      ),
                    ),
                  ),
                const SizedBox(height: 16),
                // three big method tiles
                Row(
                  children: [
                    _methodTile('CASH', LucideIcons.banknote, l.cash),
                    const SizedBox(width: 8),
                    _methodTile('CARD', LucideIcons.creditCard, l.card),
                    const SizedBox(width: 8),
                    _methodTile(
                      'BANK_TRANSFER',
                      LucideIcons.landmark,
                      l.bankTransfer,
                    ),
                    if (_stripeShown) ...[
                      const SizedBox(width: 8),
                      _methodTile(
                        'STRIPE',
                        LucideIcons.nfc,
                        l.cardStripe,
                        enabled: _stripeBlocked == null,
                      ),
                    ],
                  ],
                ),
                if (_stripeShown && _stripeBlocked != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Row(
                      children: [
                        const Icon(
                          LucideIcons.wifiOff,
                          size: 16,
                          color: T.textMuted,
                        ),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text(
                            l.stripeUnavailableHint(_stripeBlocked),
                            key: const ValueKey('stripe-hint'),
                            style: T.small(),
                          ),
                        ),
                      ],
                    ),
                  ),
                const SizedBox(height: 20),
                if (_method == 'CASH')
                  _cashSection(l)
                else if (_method == 'STRIPE')
                  _stripeSection(l)
                else
                  _electronicSection(_method, l),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _methodTile(
    String value,
    IconData icon,
    String label, {
    bool enabled = true,
  }) {
    final selected = enabled && _method == value;
    final fg = !enabled
        ? T.textMuted.withValues(alpha: .55)
        : selected
        ? T.primary
        : T.textPrimary;
    return Expanded(
      child: SizedBox(
        height: 72,
        child: Opacity(
          opacity: enabled ? 1 : .7,
          child: PosPanel(
            key: ValueKey('tender-tile-$value'),
            color: !enabled
                ? T.background
                : selected
                ? T.surfaceAlt
                : T.surface,
            borderColor: selected ? T.primary : T.border,
            onTap: enabled
                ? () => setState(() {
                    _method = value;
                    _instructions = null;
                  })
                : null,
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(
                  icon,
                  size: 24,
                  color: selected ? T.primary : (enabled ? T.textMuted : fg),
                ),
                const SizedBox(height: 4),
                Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: T.small(color: fg, weight: FontWeight.w600),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _stripeSection(L l) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _entryDisplay(l, hint: l.amountHint(money(_due))),
        const SizedBox(height: 8),
        AmountPad(onKey: _numpadKey),
        const SizedBox(height: 12),
        Text(l.simulatedCardLabel, style: T.small()),
        const SizedBox(height: 6),
        Wrap(
          spacing: 8,
          children: [
            for (final c in SimulatedTestCard.values)
              ChoiceChip(
                label: Text(switch (c) {
                  SimulatedTestCard.approved => l.simApproved,
                  SimulatedTestCard.declined => l.simDeclined,
                  SimulatedTestCard.insufficientFunds => l.simInsufficient,
                }),
                selected: _simCard == c,
                onSelected: (_) => setState(() => _simCard = c),
              ),
          ],
        ),
        const SizedBox(height: 12),
        SizedBox(
          height: T.minTouch,
          child: FilledButton.icon(
            key: const ValueKey('stripe-charge'),
            style: FilledButton.styleFrom(
              backgroundColor: T.accent,
              foregroundColor: T.onAccent,
            ),
            icon: const Icon(LucideIcons.nfc),
            label: Text(l.chargeCardStripe),
            onPressed: _busy || _stripeBlocked != null ? null : _payStripe,
          ),
        ),
      ],
    );
  }

  Widget _entryDisplay(L l, {required String hint}) {
    return PosPanel(
      color: T.surfaceAlt,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          // long French hints wrap instead of overflowing the amount
          Flexible(child: Text(hint, style: T.small())),
          const SizedBox(width: 12),
          Text(
            _entryCAD == null ? '—' : money(_entryCAD! * 100),
            style: T.price(size: 26, weight: FontWeight.w600),
          ),
        ],
      ),
    );
  }

  Widget _cashSection(L l) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // quick-amount strip: exact cash due + next-100/500/1000 round-ups
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final amount in _quickAmounts)
              SizedBox(
                height: T.minTouch,
                child: OutlinedButton(
                  onPressed: _busy ? null : () => _tenderCash(amount),
                  style: OutlinedButton.styleFrom(
                    backgroundColor: T.surface,
                    side: BorderSide(
                      color: amount == _cashDue ? T.primary : T.border,
                    ),
                  ),
                  child: Text(
                    money(amount),
                    style: T.price(
                      size: 18,
                      color: amount == _cashDue ? T.primary : T.textPrimary,
                    ),
                  ),
                ),
              ),
          ],
        ),
        const SizedBox(height: 14),
        _entryDisplay(l, hint: l.cashInHint),
        const SizedBox(height: 8),
        AmountPad(onKey: _numpadKey),
        const SizedBox(height: 10),
        SizedBox(
          height: T.minTouch,
          child: FilledButton.icon(
            icon: const Icon(LucideIcons.check),
            label: Text(l.receive),
            onPressed: _busy || _entryCAD == null
                ? null
                : () => _tenderCash(_entryCAD! * 100),
          ),
        ),
      ],
    );
  }

  Widget _electronicSection(String type, L l) {
    final instructions = _instructions;
    if (instructions == null) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _entryDisplay(l, hint: l.amountHint(money(_due))),
          const SizedBox(height: 8),
          AmountPad(onKey: _numpadKey),
          const SizedBox(height: 10),
          SizedBox(
            height: T.minTouch,
            child: FilledButton.icon(
              icon: Icon(
                type == 'CARD' ? LucideIcons.creditCard : LucideIcons.landmark,
              ),
              label: Text(
                type == 'CARD' ? l.useCardTerminal : l.showBankAccount,
              ),
              onPressed: _busy ? null : () => _showInstructions(type),
            ),
          ),
        ],
      );
    }
    return Column(
      children: [
        Text(
          l.amountToPay(money(instructions.amountCents)),
          style: T.price(size: 26, weight: FontWeight.w600),
        ),
        const SizedBox(height: 12),
        if (instructions.qrPayload != null)
          Container(
            color: Colors.white,
            padding: const EdgeInsets.all(12),
            child: QrImageView(data: instructions.qrPayload!, size: 240),
          ),
        if (instructions.displayFields.isNotEmpty)
          PosPanel(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: Column(
              children: [
                for (final field in instructions.displayFields)
                  _row(l.name(field.labelFr, field.labelEn), field.value),
              ],
            ),
          ),
        const SizedBox(height: 16),
        SizedBox(
          width: double.infinity,
          height: T.minTouch,
          child: FilledButton.icon(
            icon: const Icon(LucideIcons.checkCircle2),
            label: Text(l.confirmMoneyIn),
            onPressed: _busy ? null : _confirmElectronic,
          ),
        ),
        TextButton(
          onPressed: _busy ? null : () => setState(() => _instructions = null),
          child: Text(l.cancel),
        ),
      ],
    );
  }
}
