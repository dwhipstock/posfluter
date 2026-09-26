import 'package:flutter/material.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';

/// Subtotal plus one row per tax added on top (GST, QST), shown just above a
/// total. All figures are the server's; renders nothing when no tax is added.
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
          Text(label, style: T.small()),
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
        for (final tax in taxes) row(l.taxLine(tax), tax.amountCents),
      ],
    );
  }
}
