import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';
import '../retail/sp_theme.dart';
import 'stock_i18n.dart';
import 'stock_queue.dart';

/// Refresh what counting needs while the store is reachable: the catalog
/// (barcodes) and the expected quantities. Kept on the phone for later.
/// Never throws — offline, the cached copy stays.
Future<void> refreshStockReference([StockQueue? queue]) async {
  final q = queue ?? StockQueue.instance;
  try {
    final items = await Api.catalog();
    await q.cacheReference(
      catalog: [
        for (final i in items)
          StockProduct(
            id: i.id,
            name: i.nameEn.isNotEmpty ? i.nameEn : i.nameFr,
            category: i.category,
            barcode: i.barcode,
            active: i.active,
            brand: i.brand,
            subcategory: i.subcategory,
            size: i.size,
            packUnits: i.packUnits,
            popularity: i.salesWeight,
          ),
      ],
    );
  } catch (_) {}
  try {
    await q.cacheReference(expected: await Api.stockExpected());
  } catch (_) {}
}

/// The stock screens' frame: the store's band on top, the body centred and
/// never wider than a phone column (on the tablet too).
class StockScaffold extends StatelessWidget {
  final String title;
  final Widget body;
  final List<Widget> actions;
  final Widget? bottom;
  const StockScaffold({
    super.key,
    required this.title,
    required this.body,
    this.actions = const [],
    this.bottom,
  });

  @override
  Widget build(BuildContext context) {
    final c = SpColors.of(context);
    return Scaffold(
      backgroundColor: c.background,
      appBar: AppBar(
        backgroundColor: c.sageDeep,
        foregroundColor: Colors.white,
        title: Text(
          title,
          style: T.text(size: 20, weight: FontWeight.w700, color: Colors.white),
        ),
        actions: [
          ...actions,
          const LangActions(color: Colors.white),
        ],
      ),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 720),
            child: body,
          ),
        ),
      ),
      bottomNavigationBar: bottom == null
          ? null
          : SafeArea(
              child: Center(
                heightFactor: 1,
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 720),
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
                    child: bottom,
                  ),
                ),
              ),
            ),
    );
  }
}

/// What is waiting to go to the store, and anything the store refused.
class StockSyncBar extends StatelessWidget {
  final StockQueue queue;
  const StockSyncBar({super.key, required this.queue});

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: queue,
      builder: (context, _) {
        final s = S.of(context);
        final c = SpColors.of(context);
        final failed = queue.failed;
        final waiting = queue.pending - failed.length;
        final (IconData icon, String text, Color fg, Color bg) = queue.flushing
            ? (LucideIcons.refreshCw, s.sending, c.text, c.surfaceAlt)
            : waiting > 0 && queue.offline
            ? (LucideIcons.wifiOff, s.offlineSaved, c.warn, c.warnSoft)
            : waiting > 0
            ? (LucideIcons.clock, s.waiting(waiting), c.text, c.surfaceAlt)
            : (LucideIcons.circleCheck, s.allSent, c.ok, c.okSoft);
        return Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              key: const Key('stock-sync-bar'),
              margin: const EdgeInsets.fromLTRB(16, 12, 16, 0),
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
              decoration: BoxDecoration(
                color: bg,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Row(
                children: [
                  Icon(icon, size: 18, color: fg),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      text,
                      style: T.text(
                        size: 14,
                        weight: FontWeight.w600,
                        color: fg,
                      ),
                    ),
                  ),
                  if (waiting > 0 && !queue.flushing)
                    TextButton(onPressed: queue.flush, child: Text(s.sendNow)),
                ],
              ),
            ),
            for (final op in failed)
              Container(
                margin: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                padding: const EdgeInsets.fromLTRB(14, 8, 8, 8),
                decoration: BoxDecoration(
                  color: c.badSoft,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Row(
                  children: [
                    Icon(LucideIcons.triangleAlert, size: 18, color: c.bad),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        '${s.needsAttention}: ${s.error(op.errorCode) ?? op.error ?? ''}',
                        style: T.text(size: 14, color: c.text),
                      ),
                    ),
                    TextButton(
                      onPressed: () async {
                        String? pin;
                        if (op.errorCode == 'manager_approval_required') {
                          pin = await askManagerPin(context);
                          if (pin == null) return;
                        }
                        await queue.retry(op.id, managerPin: pin);
                      },
                      child: Text(s.retry),
                    ),
                    TextButton(
                      onPressed: () => queue.discardOp(op.id),
                      child: Text(s.discard),
                    ),
                  ],
                ),
              ),
          ],
        );
      },
    );
  }
}

/// A manager's 4-digit PIN (approves a variance); null = cancelled.
Future<String?> askManagerPin(BuildContext context) {
  final s = S.of(context);
  final ctl = TextEditingController();
  return showDialog<String>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(s.managerApproves),
      content: TextField(
        key: const Key('manager-pin'),
        controller: ctl,
        autofocus: true,
        obscureText: true,
        keyboardType: TextInputType.number,
        maxLength: 4,
        decoration: InputDecoration(labelText: s.managerPin, counterText: ''),
        onSubmitted: (v) => Navigator.pop(ctx, v.trim()),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: Text(s.cancel)),
        FilledButton(
          onPressed: () => Navigator.pop(ctx, ctl.text.trim()),
          child: Text(s.ok),
        ),
      ],
    ),
  ).then((v) => (v == null || v.isEmpty) ? null : v);
}

/// Type a code (or search by name) when there is no scanner at hand.
Future<StockProduct?> pickProduct(BuildContext context, ProductIndex index) {
  return showModalBottomSheet<StockProduct>(
    context: context,
    isScrollControlled: true,
    builder: (ctx) => _ProductPicker(index: index),
  );
}

class _ProductPicker extends StatefulWidget {
  final ProductIndex index;
  const _ProductPicker({required this.index});

  @override
  State<_ProductPicker> createState() => _ProductPickerState();
}

class _ProductPickerState extends State<_ProductPicker> {
  final _ctl = TextEditingController();

  @override
  void dispose() {
    _ctl.dispose();
    super.dispose();
  }

  void _submit(String v) {
    final hit = widget.index.byCode(v);
    if (hit != null) {
      Navigator.pop(context, hit);
      return;
    }
    final found = widget.index.search(v);
    if (found.length == 1) Navigator.pop(context, found.single);
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final c = SpColors.of(context);
    final results = widget.index.search(_ctl.text);
    return Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom,
      ),
      child: SafeArea(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.of(context).size.height * .7,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Padding(
                padding: const EdgeInsets.all(16),
                child: TextField(
                  key: const Key('product-search'),
                  controller: _ctl,
                  autofocus: true,
                  decoration: InputDecoration(
                    prefixIcon: const Icon(LucideIcons.search),
                    labelText: s.scanOrType,
                  ),
                  onChanged: (_) => setState(() {}),
                  onSubmitted: _submit,
                ),
              ),
              Flexible(
                child: ListView(
                  shrinkWrap: true,
                  children: [
                    for (final p in results)
                      ListTile(
                        title: Text(p.name, style: T.text(color: c.text)),
                        subtitle: Text(p.barcode ?? ''),
                        onTap: () => Navigator.pop(context, p),
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// − qty + with the qty tappable to type an exact number.
class QtyStepper extends StatelessWidget {
  final int qty;
  final ValueChanged<int> onChanged;
  final int min;
  const QtyStepper({
    super.key,
    required this.qty,
    required this.onChanged,
    this.min = 0,
  });

  @override
  Widget build(BuildContext context) {
    final c = SpColors.of(context);
    Widget btn(IconData icon, VoidCallback? onTap, String key) => SizedBox(
      width: 44,
      height: 44,
      child: IconButton.filledTonal(
        key: Key(key),
        onPressed: onTap,
        icon: Icon(icon, size: 20),
      ),
    );
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        btn(
          LucideIcons.minus,
          qty > min ? () => onChanged(qty - 1) : null,
          'qty-minus',
        ),
        InkWell(
          borderRadius: BorderRadius.circular(8),
          onTap: () async {
            final v = await _askQty(context, qty);
            if (v != null) onChanged(v < min ? min : v);
          },
          child: SizedBox(
            width: 56,
            height: 44,
            child: Center(
              child: Text(
                '$qty',
                style: T.text(size: 20, weight: FontWeight.w700, color: c.text),
              ),
            ),
          ),
        ),
        btn(LucideIcons.plus, () => onChanged(qty + 1), 'qty-plus'),
      ],
    );
  }

  static Future<int?> _askQty(BuildContext context, int current) {
    final s = S.of(context);
    final ctl = TextEditingController(text: '$current');
    return showDialog<int>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(s.setQty),
        content: TextField(
          key: const Key('qty-exact'),
          controller: ctl,
          autofocus: true,
          keyboardType: TextInputType.number,
          onSubmitted: (v) => Navigator.pop(ctx, int.tryParse(v.trim())),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: Text(s.cancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, int.tryParse(ctl.text.trim())),
            child: Text(s.ok),
          ),
        ],
      ),
    );
  }
}

/// Flush now and every [period] while [child] is on screen.
class StockAutoFlush extends StatefulWidget {
  final StockQueue queue;
  final Widget child;
  final Duration period;
  const StockAutoFlush({
    super.key,
    required this.queue,
    required this.child,
    this.period = const Duration(seconds: 20),
  });

  @override
  State<StockAutoFlush> createState() => _StockAutoFlushState();
}

class _StockAutoFlushState extends State<StockAutoFlush>
    with WidgetsBindingObserver {
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _timer = Timer.periodic(widget.period, (_) => _flush());
  }

  void _flush() {
    if (widget.queue.pending > 0) widget.queue.flush();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _flush();
  }

  @override
  void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
