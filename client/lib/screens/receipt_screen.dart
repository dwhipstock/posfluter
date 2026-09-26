import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../design/skin.dart';
import '../design/tokens.dart';
import '../retail/sp_theme.dart';
import '../widgets/brand.dart';
import '../i18n.dart';

/// Receipt preview after close. Renders the server's 42-col text form on a
/// paper-white card — the one intentionally light surface in the app.
/// TODO: no monospace font is bundled — Noto Sans with
/// tabular figures keeps the number column straight; label widths drift
/// slightly. Revisit with the thermal printer work (M2).
class ReceiptScreen extends StatelessWidget {
  final int checkId;
  final String text;

  /// Overrides the default "Receipt — Bill #id" bar (refund / till slips reuse
  /// this same paper-white preview with their own heading).
  final String? title;
  const ReceiptScreen({
    super.key,
    required this.checkId,
    required this.text,
    this.title,
  });

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(title ?? '${l.receipt} — ${l.bill} #$checkId'),
      ),
      body: Center(
        child: Container(
          width: 420,
          margin: const EdgeInsets.all(16),
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: T.receiptPaper, // receipt paper
            borderRadius: T.radiusSmall,
            border: Border.all(color: T.border),
          ),
          child: SingleChildScrollView(
            child:
                BrandSkin.of(context).receiptHeader ==
                    ReceiptHeaderStyle.wordmark
                ? Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const _WordmarkHeader(),
                      const SizedBox(height: 14),
                      Text(_withoutNameLine(text), style: T.receipt()),
                    ],
                  )
                : Text(text, style: T.receipt()),
          ),
        ),
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: FilledButton.icon(
            icon: const Icon(LucideIcons.checkCheck),
            label: Text(l.done),
            onPressed: () => Navigator.pop(context),
            style: FilledButton.styleFrom(
              minimumSize: const Size.fromHeight(T.minTouch),
            ),
          ),
        ),
      ),
    );
  }
}

/// The band already names the store: drop the text's own name line.
String _withoutNameLine(String text) {
  final lines = text.split('\n');
  if (lines.isNotEmpty &&
      lines.first.trim().toLowerCase().startsWith('sage & poppy')) {
    return lines.skip(1).join('\n');
  }
  return text;
}

/// The receipt header of a skin with a wordmark (Sage & Poppy): the mark and
/// the name on a sage band across the top of the paper, left-aligned, instead
/// of centred store lines.
class _WordmarkHeader extends StatelessWidget {
  const _WordmarkHeader();

  @override
  Widget build(BuildContext context) {
    final s = BrandSkin.of(context);
    final c = SpColors.light; // paper is always light
    return Container(
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
      decoration: BoxDecoration(
        color: c.sageMist,
        borderRadius: s.radiusMedium,
      ),
      child: Row(
        children: [
          const BrandLogo(size: 40),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'SAGE & POPPY',
                  style: s
                      .text(
                        size: 18,
                        weight: FontWeight.w800,
                        color: c.sageDeep,
                      )
                      .copyWith(letterSpacing: 2.4),
                ),
                Text(
                  'BOTTLE SHOP',
                  style: s
                      .text(size: 11, weight: FontWeight.w700, color: c.poppy)
                      .copyWith(letterSpacing: 3),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
