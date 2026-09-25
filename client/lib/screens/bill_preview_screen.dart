import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';

/// Provisional "check please" bill preview. Same paper card as the receipt
/// preview, but the server render carries the CUSTOMER BILL header, no tender
/// section, and a NOT A RECEIPT footer. Nothing is locked — "Print again"
/// re-hits the endpoint so staff can reprint after adding/removing items, then
/// Close returns to the check screen unchanged.
class BillPreviewScreen extends StatefulWidget {
  final int checkId;
  final String text;

  /// Bill group of a split check — scopes the render (and reprints) to that group.
  final int? groupId;
  const BillPreviewScreen({
    super.key,
    required this.checkId,
    required this.text,
    this.groupId,
  });

  @override
  State<BillPreviewScreen> createState() => _BillPreviewScreenState();
}

class _BillPreviewScreenState extends State<BillPreviewScreen> {
  late String _text = widget.text;
  bool _printing = false;

  Future<void> _printAgain() async {
    setState(() => _printing = true);
    try {
      final text = await Api.printBill(widget.checkId, groupId: widget.groupId);
      if (mounted) setState(() => _text = text);
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _printing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text('${l.customerBill} — ${l.bill} #${widget.checkId}'),
      ),
      body: Center(
        child: Container(
          width: 420,
          margin: const EdgeInsets.all(16),
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: T.receiptPaper, // receipt paper — matches ReceiptScreen
            borderRadius: T.radiusSmall,
            border: Border.all(color: T.border),
          ),
          child: SingleChildScrollView(child: Text(_text, style: T.receipt())),
        ),
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  icon: const Icon(LucideIcons.printer),
                  label: Text(l.printAgain),
                  onPressed: _printing ? null : _printAgain,
                  style: OutlinedButton.styleFrom(
                    minimumSize: const Size.fromHeight(T.minTouch),
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: FilledButton.icon(
                  icon: const Icon(LucideIcons.checkCheck),
                  label: Text(l.close),
                  onPressed: () => Navigator.pop(context),
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(T.minTouch),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
