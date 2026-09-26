import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../retail/sp_theme.dart';
import 'barcode_scanner.dart';
import 'stock_i18n.dart';
import 'stock_queue.dart';
import 'stock_widgets.dart';

/// Check a delivery in: scan each product (each scan adds one; − / + or a
/// typed number sets it), optionally note the supplier and the invoice, save.
/// Saved on the phone first and sent when the store is reachable.
class ReceiveScreen extends StatefulWidget {
  final StockQueue? queue;
  const ReceiveScreen({super.key, this.queue});

  @override
  State<ReceiveScreen> createState() => _ReceiveScreenState();
}

class _ReceiveLine {
  final StockProduct product;
  int qty;
  _ReceiveLine(this.product, this.qty);
}

class _ReceiveScreenState extends State<ReceiveScreen> {
  StockQueue get _queue => widget.queue ?? StockQueue.instance;
  late ProductIndex _index = ProductIndex(_queue.catalog);
  final _supplier = TextEditingController();
  final _reference = TextEditingController();
  final _lines = <String, _ReceiveLine>{};
  bool _dialog = false;
  bool _busy = false;
  String? _flash;

  @override
  void initState() {
    super.initState();
    _prepare();
  }

  Future<void> _prepare() async {
    await _queue.load();
    await refreshStockReference(_queue);
    if (mounted) setState(() => _index = ProductIndex(_queue.catalog));
  }

  @override
  void dispose() {
    _supplier.dispose();
    _reference.dispose();
    super.dispose();
  }

  String? _add(StockProduct p) {
    setState(() {
      final line = _lines.remove(p.id) ?? _ReceiveLine(p, 0);
      line.qty++;
      _lines[p.id] = line; // most recent last
      _flash = p.id;
    });
    return '${p.name} · ${_lines[p.id]!.qty}';
  }

  String? _scanned(String code) {
    final s = S.of(context);
    final p = _index.byCode(code);
    if (p == null) {
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text(s.unknownCode(code))));
      return s.unknownCode(code);
    }
    return _add(p);
  }

  Future<void> _camera() async {
    _dialog = true;
    try {
      await CameraScanner.scanMany(context, _scanned);
    } finally {
      _dialog = false;
    }
  }

  Future<void> _type() async {
    _dialog = true;
    StockProduct? p;
    try {
      p = await pickProduct(context, _index);
    } finally {
      _dialog = false;
    }
    if (p != null) _add(p);
  }

  Future<void> _save() async {
    final s = S.of(context);
    final lines = [
      for (final l in _lines.values)
        if (l.qty > 0) {'itemId': l.product.id, 'qty': l.qty},
    ];
    if (lines.isEmpty) return;
    setState(() => _busy = true);
    try {
      await _queue.receive(
        supplier: _supplier.text.trim(),
        reference: _reference.text.trim(),
        lines: lines,
      );
      final result = await _queue.flush();
      if (!mounted) return;
      final refused = _queue.ops
          .where((o) => o.kind == 'receive' && o.failed)
          .firstOrNull;
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(
            content: Text(
              refused != null
                  ? (s.error(refused.errorCode) ?? refused.error ?? '')
                  : (result.offline ? s.deliverySavedOffline : s.deliverySaved),
            ),
          ),
        );
      Navigator.of(context).pop();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final c = SpColors.of(context);
    final lines = _lines.values.toList().reversed.toList();
    final units = lines.fold<int>(0, (n, l) => n + l.qty);
    return HidScanListener(
      enabled: !_dialog,
      onScan: _scanned,
      child: StockScaffold(
        title: s.receiveDelivery,
        bottom: FilledButton.icon(
          key: const Key('receive-save'),
          style: FilledButton.styleFrom(
            backgroundColor: c.poppy,
            foregroundColor: c.onPoppy,
            minimumSize: const Size.fromHeight(56),
          ),
          onPressed: _busy || units == 0 ? null : _save,
          icon: const Icon(LucideIcons.packageCheck),
          label: Text(
            '${s.saveDelivery} · ${s.units(units)}',
            style: T.text(size: 17, weight: FontWeight.w700, color: c.onPoppy),
          ),
        ),
        body: ListView(
          padding: const EdgeInsets.only(bottom: 16),
          children: [
            StockSyncBar(queue: _queue),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
              child: Row(
                children: [
                  Expanded(
                    child: TextField(
                      key: const Key('receive-supplier'),
                      controller: _supplier,
                      decoration: InputDecoration(
                        labelText: s.supplier,
                        helperText: s.optional,
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: TextField(
                      key: const Key('receive-reference'),
                      controller: _reference,
                      decoration: InputDecoration(
                        labelText: s.reference,
                        helperText: s.optional,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
              child: Row(
                children: [
                  if (CameraScanner.supported) ...[
                    Expanded(
                      child: FilledButton.icon(
                        key: const Key('scan-camera'),
                        style: FilledButton.styleFrom(
                          backgroundColor: c.sage,
                          minimumSize: const Size.fromHeight(52),
                        ),
                        onPressed: _camera,
                        icon: const Icon(LucideIcons.scanBarcode),
                        label: Text(s.camera),
                      ),
                    ),
                    const SizedBox(width: 10),
                  ],
                  Expanded(
                    child: OutlinedButton.icon(
                      key: const Key('scan-type'),
                      style: OutlinedButton.styleFrom(
                        minimumSize: const Size.fromHeight(52),
                      ),
                      onPressed: _type,
                      icon: const Icon(LucideIcons.keyboard),
                      label: Text(
                        s.scanOrType,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            if (lines.isEmpty)
              Padding(
                padding: const EdgeInsets.all(32),
                child: Text(
                  s.nothingReceived,
                  textAlign: TextAlign.center,
                  style: T.text(size: 16, color: c.textMuted),
                ),
              ),
            for (final l in lines)
              Card(
                key: Key('receive-${l.product.id}'),
                margin: const EdgeInsets.fromLTRB(16, 6, 16, 0),
                color: _flash == l.product.id ? c.poppySoft : c.surface,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(14, 10, 8, 10),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          l.product.name,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: T.text(
                            size: 16,
                            weight: FontWeight.w600,
                            color: c.text,
                          ),
                        ),
                      ),
                      QtyStepper(
                        qty: l.qty,
                        onChanged: (v) => setState(() {
                          if (v <= 0) {
                            _lines.remove(l.product.id);
                          } else {
                            l.qty = v;
                          }
                        }),
                      ),
                    ],
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
