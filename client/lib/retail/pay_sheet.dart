import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';
import 'retail_i18n.dart';
import 'sp_theme.dart';

/// Retail payment: cash (quick amounts + keypad, exact change to the cent) or
/// a card on the counter's own external terminal (the cashier confirms once it
/// approves). No Stripe here: that integration is Canada-only for now.
/// Resolves to the closed sale, or null if dismissed before payment.
class PaySheet extends StatefulWidget {
  final Check sale;
  const PaySheet({super.key, required this.sale});

  static Future<PayResult?> show(BuildContext context, Check sale) =>
      showDialog<PayResult>(
        context: context,
        barrierDismissible: false,
        builder: (_) => PaySheet(sale: sale),
      );

  @override
  State<PaySheet> createState() => _PaySheetState();
}

/// A paid sale and the change handed back.
class PayResult {
  final Check check;
  final int changeCents;
  const PayResult(this.check, this.changeCents);
}

class _PaySheetState extends State<PaySheet> {
  late Check _sale = widget.sale;
  String _method = 'CASH';
  String _digits = ''; // keypad entry in cents
  bool _busy = false;
  PayResult? _done;

  int get _due => _sale.outstandingCents;
  int? get _entered => _digits.isEmpty ? null : int.parse(_digits);

  List<int> get _quick {
    int up(int unit) => ((_due + unit - 1) ~/ unit) * unit;
    return {
      _due,
      up(500),
      up(1000),
      up(2000),
      up(5000),
      up(10000),
    }.where((v) => v >= _due).toList()..sort();
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

  Future<void> _cash(int amount) => _guard(() async {
    final res = await Api.tenderCash(_sale.id, amount);
    _sale = res.check;
    if (_sale.outstandingCents == 0) {
      final closed = await Api.finalizeCheck(_sale.id);
      setState(() => _done = PayResult(closed, res.tender.changeCents));
    } else {
      setState(() => _digits = '');
    }
  });

  Future<void> _card() => _guard(() async {
    await Api.initiateTender(_sale.id, 'CARD', amountCents: _due);
    final res = await Api.confirmTender(_sale.id, 'CARD', _due);
    _sale = res.check;
    final closed = await Api.finalizeCheck(_sale.id);
    setState(() => _done = PayResult(closed, 0));
  });

  void _key(String k) => setState(() {
    if (k == '⌫') {
      if (_digits.isNotEmpty) {
        _digits = _digits.substring(0, _digits.length - 1);
      }
    } else if (_digits.length < 7) {
      _digits = (_digits + k).replaceFirst(RegExp(r'^0+'), '');
    }
  });

  @override
  Widget build(BuildContext context) {
    final r = R.of(context);
    final c = SpColors.of(context);
    final done = _done;
    return Dialog(
      insetPadding: const EdgeInsets.all(24),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 760, maxHeight: 640),
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: done != null ? _paid(r, c, done) : _paying(r, c),
        ),
      ),
    );
  }

  Widget _paying(R r, SpColors c) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    r.amountDue.toUpperCase(),
                    style: T
                        .text(
                          size: 13,
                          weight: FontWeight.w600,
                          color: c.textMuted,
                        )
                        .copyWith(letterSpacing: 1.2),
                  ),
                  Text(
                    money(_due),
                    style: T.price(
                      size: 44,
                      weight: FontWeight.w700,
                      color: c.text,
                    ),
                  ),
                ],
              ),
            ),
            IconButton(
              tooltip: r.cancel,
              onPressed: _busy ? null : () => Navigator.pop(context),
              icon: const Icon(LucideIcons.x),
            ),
          ],
        ),
        const SizedBox(height: 16),
        SegmentedButton<String>(
          segments: [
            ButtonSegment(
              value: 'CASH',
              icon: const Icon(LucideIcons.banknote),
              label: Text(r.cash),
            ),
            ButtonSegment(
              value: 'CARD',
              icon: const Icon(LucideIcons.creditCard),
              label: Text(r.cardTerminal),
            ),
          ],
          selected: {_method},
          onSelectionChanged: (s) => setState(() => _method = s.first),
        ),
        const SizedBox(height: 18),
        _method == 'CASH' ? _cashPane(r, c) : _cardPane(r, c),
      ],
    );
  }

  Widget _cashPane(R r, SpColors c) {
    final entered = _entered;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (final q in _quick)
                Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: OutlinedButton(
                    onPressed: _busy ? null : () => _cash(q),
                    child: Text(
                      q == _due ? '${r.exact} · ${money(q)}' : money(q),
                      style: T.price(
                        size: 20,
                        weight: FontWeight.w600,
                        color: c.text,
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
        const SizedBox(width: 18),
        SizedBox(
          width: 300,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              InputDecorator(
                decoration: InputDecoration(labelText: r.cashReceived),
                child: Text(
                  entered == null ? '—' : money(entered),
                  textAlign: TextAlign.right,
                  style: T.price(
                    size: 26,
                    weight: FontWeight.w600,
                    color: c.text,
                  ),
                ),
              ),
              const SizedBox(height: 10),
              for (final row in const [
                ['1', '2', '3'],
                ['4', '5', '6'],
                ['7', '8', '9'],
                ['00', '0', '⌫'],
              ])
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Row(
                    children: [
                      for (final k in row)
                        Expanded(
                          child: Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 4),
                            child: OutlinedButton(
                              style: OutlinedButton.styleFrom(
                                minimumSize: const Size(0, 52),
                              ),
                              onPressed: () => _key(k),
                              child: k == '⌫'
                                  ? const Icon(LucideIcons.delete)
                                  : Text(
                                      k,
                                      style: T.price(size: 22, color: c.text),
                                    ),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
              const SizedBox(height: 4),
              FilledButton.icon(
                style: FilledButton.styleFrom(
                  backgroundColor: c.poppy,
                  foregroundColor: c.onPoppy,
                  minimumSize: const Size.fromHeight(60),
                ),
                onPressed: _busy || entered == null || entered <= 0
                    ? null
                    : () => _cash(entered),
                icon: const Icon(LucideIcons.check),
                label: Text(
                  r.takeCash,
                  style: T.text(
                    size: 20,
                    weight: FontWeight.w700,
                    color: c.onPoppy,
                  ),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _cardPane(R r, SpColors c) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Container(
          padding: const EdgeInsets.all(18),
          decoration: BoxDecoration(
            color: c.surfaceAlt,
            borderRadius: T.radiusLarge,
          ),
          child: Row(
            children: [
              Icon(LucideIcons.creditCard, size: 40, color: c.sage),
              const SizedBox(width: 14),
              Expanded(
                child: Text(r.cardHint, style: T.text(size: 17, color: c.text)),
              ),
            ],
          ),
        ),
        const SizedBox(height: 8),
        Text(r.noStripeNote, style: T.text(size: 13, color: c.textMuted)),
        const SizedBox(height: 18),
        FilledButton.icon(
          style: FilledButton.styleFrom(
            backgroundColor: c.poppy,
            foregroundColor: c.onPoppy,
            minimumSize: const Size.fromHeight(64),
          ),
          onPressed: _busy ? null : _card,
          icon: const Icon(LucideIcons.badgeCheck),
          label: Text(
            r.cardApproved,
            style: T.text(size: 20, weight: FontWeight.w700, color: c.onPoppy),
          ),
        ),
      ],
    );
  }

  Widget _paid(R r, SpColors c, PayResult done) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(LucideIcons.circleCheck, size: 64, color: c.ok),
        const SizedBox(height: 10),
        Text(
          r.paid,
          style: T.text(size: 28, weight: FontWeight.w700, color: c.text),
        ),
        if (done.changeCents > 0) ...[
          const SizedBox(height: 8),
          Text(
            r.change(money(done.changeCents)),
            style: T.price(size: 36, weight: FontWeight.w700, color: c.poppy),
          ),
        ],
        const SizedBox(height: 24),
        FilledButton.icon(
          style: FilledButton.styleFrom(minimumSize: const Size(280, 60)),
          onPressed: () => Navigator.pop(context, done),
          icon: const Icon(LucideIcons.receipt),
          label: Text(r.receipt),
        ),
      ],
    );
  }
}
