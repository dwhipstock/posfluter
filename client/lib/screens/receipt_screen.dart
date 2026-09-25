import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../design/tokens.dart';
import '../i18n.dart';

/// Receipt preview after close. Renders the server's 42-col text form on a
/// paper-white card — the one intentionally light surface in the app.
/// TODO: no true French monospace is bundled — Noto Sans with
/// tabular figures keeps the number column straight; French label widths drift
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
          child: SingleChildScrollView(child: Text(text, style: T.receipt())),
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
