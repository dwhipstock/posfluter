import 'package:flutter/material.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';

/// Subtotal plus one row per tax added on top, shown just above a total —
/// or, in a store that shows its taxes as one line (Copper Lantern), a
/// single "Tax (8.25%)" row ([TaxLine.forGuests]). All figures are the
/// server's; renders nothing when no tax is added.
class TaxRows extends StatelessWidget {
  final int subtotalCents;
  final List<TaxLine> taxes;
  final double priceSize;
  final EdgeInsetsGeometry rowPadding;
  const TaxRows({
    super.key,
    required this.subtotalCents,
    required this.taxes,
    this.priceSize = 16,
    this.rowPadding = const EdgeInsets.symmetric(vertical: 2),
  });

  @override
  Widget build(BuildContext context) {
    if (taxes.isEmpty) return const SizedBox.shrink();
    final l = L.of(context);
    Widget row(String label, int cents) => Padding(
      padding: rowPadding,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          // a long tax name ("Wake prepared food tax 1%") wraps, never overflows
          Flexible(child: Text(label, style: T.small())),
          const SizedBox(width: 8),
          Text(
            money(cents),
            style: T.price(size: priceSize, color: T.textMuted),
          ),
        ],
      ),
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        row(l.subtotal, subtotalCents),
        for (final tax in TaxLine.forGuests(taxes))
          row(l.taxLine(tax), tax.amountCents),
      ],
    );
  }
}
