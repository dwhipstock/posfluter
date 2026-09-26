import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';
import '../retail/sp_theme.dart';
import 'barcode_scanner.dart';
import 'stock_i18n.dart';
import 'stock_queue.dart';
import 'stock_widgets.dart';

/// Pick a count to work on — the store's open ones and the ones kept on this
/// phone — or start a new one. Works offline (only this phone's then).
class CountListScreen extends StatefulWidget {
  final StockQueue? queue;
  const CountListScreen({super.key, this.queue});

  @override
  State<CountListScreen> createState() => _CountListScreenState();
}

class _CountListScreenState extends State<CountListScreen> {
  StockQueue get _queue => widget.queue ?? StockQueue.instance;
  List<CountSummary>? _store;
  bool _unreachable = false;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    await _queue.load();
    try {
      final counts = await Api.stockCounts();
      if (mounted) {
        setState(() {
          _store = counts.where((c) => c.open).toList();
          _unreachable = false;
        });
      }
    } catch (e) {
      if (e is ApiException && e.code == 'not_retail') {
        if (mounted) showApiError(context, e);
      }
      if (mounted) setState(() => _unreachable = true);
    }
  }

  Future<void> _start() async {
    final s = S.of(context);
    final ctl = TextEditingController();
    final name = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(s.startCount),
        content: TextField(
          key: const Key('count-name'),
          controller: ctl,
          autofocus: true,
          decoration: InputDecoration(labelText: s.countName),
          onSubmitted: (v) => Navigator.pop(ctx, v),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: Text(s.cancel),
          ),
          FilledButton(
            key: const Key('count-start'),
            onPressed: () => Navigator.pop(ctx, ctl.text),
            child: Text(s.start),
          ),
        ],
      ),
    );
    if (name == null || !mounted) return;
    final draft = await _queue.startCount(
      name: name.trim().isEmpty ? null : name.trim(),
    );
    _queue.flush();
    if (!mounted) return;
    await _open(draft.id);
  }

  /// Join a store count: pick up what this phone already counted there.
  Future<void> _join(CountSummary c) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      if (_queue.draft(c.id) == null) {
        final session = await Api.getCount(c.id, counterId: _queue.counterId);
        await _queue.joinCount(c.id, c.name, mine: session.lines);
      }
      if (mounted) await _open(c.id);
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _open(String id) async {
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => CountScreen(countId: id, queue: _queue),
      ),
    );
    if (mounted) _load();
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final c = SpColors.of(context);
    return StockScaffold(
      title: s.counts,
      bottom: FilledButton.icon(
        key: const Key('start-count'),
        style: FilledButton.styleFrom(
          backgroundColor: c.sage,
          minimumSize: const Size.fromHeight(56),
        ),
        onPressed: _start,
        icon: const Icon(LucideIcons.plus),
        label: Text(
          s.startCount,
          style: T.text(size: 17, weight: FontWeight.w700, color: Colors.white),
        ),
      ),
      body: ListenableBuilder(
        listenable: _queue,
        builder: (context, _) {
          final local = _queue.drafts.where((d) => !d.submitting).toList();
          final localIds = local.map((d) => d.id).toSet();
          final remote = (_store ?? const <CountSummary>[])
              .where((c) => !localIds.contains(c.id))
              .toList();
          return RefreshIndicator(
            onRefresh: _load,
            child: ListView(
              padding: const EdgeInsets.only(bottom: 24),
              children: [
                StockSyncBar(queue: _queue),
                if (_unreachable) _note(context, s.storeListUnavailable),
                if (local.isNotEmpty) _heading(context, s.onThisPhone),
                for (final d in local)
                  _tile(
                    context,
                    key: Key('draft-${d.id}'),
                    title: d.name.isEmpty ? s.count : d.name,
                    subtitle:
                        '${s.products(d.lines.length)} · ${s.units(d.units)}',
                    icon: LucideIcons.smartphone,
                    onTap: () => _open(d.id),
                  ),
                if (remote.isNotEmpty) _heading(context, s.openCounts),
                for (final r in remote)
                  _tile(
                    context,
                    key: Key('count-${r.id}'),
                    title: r.name,
                    subtitle:
                        '${s.startedBy(r.startedByName)} · ${s.products(r.products)}',
                    icon: LucideIcons.clipboardList,
                    onTap: () => _join(r),
                  ),
                if (local.isEmpty &&
                    remote.isEmpty &&
                    !_unreachable &&
                    _store != null)
                  _note(context, s.noOpenCounts),
                if (_store == null && !_unreachable)
                  const Padding(
                    padding: EdgeInsets.all(32),
                    child: Center(child: CircularProgressIndicator()),
                  ),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _heading(BuildContext context, String text) => Padding(
    padding: const EdgeInsets.fromLTRB(20, 20, 20, 6),
    child: Text(
      text.toUpperCase(),
      style: T.small(weight: FontWeight.w700).copyWith(letterSpacing: 1.2),
    ),
  );

  Widget _note(BuildContext context, String text) => Padding(
    padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
    child: Text(
      text,
      style: T.text(size: 15, color: SpColors.of(context).textMuted),
    ),
  );

  Widget _tile(
    BuildContext context, {
    required Key key,
    required String title,
    required String subtitle,
    required IconData icon,
    required VoidCallback onTap,
  }) {
    final c = SpColors.of(context);
    return Card(
      key: key,
      margin: const EdgeInsets.fromLTRB(16, 6, 16, 0),
      color: c.surface,
      child: ListTile(
        leading: Icon(icon, color: c.sage),
        title: Text(
          title,
          style: T.text(size: 17, weight: FontWeight.w600, color: c.text),
        ),
        subtitle: Text(subtitle),
        trailing: const Icon(LucideIcons.chevronRight),
        onTap: onTap,
      ),
    );
  }
}

/// Counting: scan (camera or HID scanner) or pick a product; each scan adds
/// one, − / + or a typed number sets it. Every change is kept on the phone
/// and queued for the store. Shows the expected qty when the store has one.
class CountScreen extends StatefulWidget {
  final String countId;
  final StockQueue? queue;
  const CountScreen({super.key, required this.countId, this.queue});

  @override
  State<CountScreen> createState() => _CountScreenState();
}

class _CountScreenState extends State<CountScreen> {
  StockQueue get _queue => widget.queue ?? StockQueue.instance;
  late ProductIndex _index = ProductIndex(_queue.catalog);
  bool _dialog = false;
  String? _flash; // the product just scanned, highlighted

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    await refreshStockReference(_queue);
    if (mounted) setState(() => _index = ProductIndex(_queue.catalog));
  }

  CountDraft? get _draft => _queue.draft(widget.countId);

  /// A scan: one more of that product. Returns feedback for the camera page.
  Future<String?> _scanned(String code) async {
    final s = S.of(context);
    final p = _index.byCode(code);
    if (p == null) {
      if (mounted) {
        ScaffoldMessenger.of(context)
          ..hideCurrentSnackBar()
          ..showSnackBar(SnackBar(content: Text(s.unknownCode(code))));
      }
      return s.unknownCode(code);
    }
    final qty = (_draft?.lines[p.id]?.qty ?? 0) + 1;
    await _set(p, qty);
    return '${p.name} · $qty';
  }

  Future<void> _set(StockProduct p, int qty) async {
    await _queue.setQty(
      widget.countId,
      itemId: p.id,
      name: p.name,
      barcode: p.barcode,
      qty: qty,
    );
    if (mounted) setState(() => _flash = p.id);
  }

  Future<void> _camera() async {
    _dialog = true;
    try {
      await CameraScanner.scanMany(context, _scanned);
    } finally {
      _dialog = false;
    }
    _queue.flush();
  }

  Future<void> _type() async {
    _dialog = true;
    StockProduct? p;
    try {
      p = await pickProduct(context, _index);
    } finally {
      _dialog = false;
    }
    if (p == null) return;
    await _set(p, (_draft?.lines[p.id]?.qty ?? 0) + 1);
    _queue.flush();
  }

  Future<void> _discard() async {
    final s = S.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        content: Text(s.discardCountConfirm),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(s.cancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(s.discard),
          ),
        ],
      ),
    );
    if (ok != true) return;
    await _queue.discardCount(widget.countId);
    _queue.flush();
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final c = SpColors.of(context);
    return HidScanListener(
      enabled: !_dialog,
      onScan: (code) async {
        await _scanned(code);
        _queue.flush();
      },
      child: ListenableBuilder(
        listenable: _queue,
        builder: (context, _) {
          final draft = _draft;
          final lines = draft?.lines.values.toList().reversed.toList() ?? [];
          return StockScaffold(
            title: draft == null || draft.name.isEmpty ? s.count : draft.name,
            actions: [
              PopupMenuButton<String>(
                icon: const Icon(
                  LucideIcons.ellipsisVertical,
                  color: Colors.white,
                ),
                onSelected: (v) {
                  if (v == 'discard') _discard();
                },
                itemBuilder: (_) => [
                  PopupMenuItem(value: 'discard', child: Text(s.discardCount)),
                ],
              ),
            ],
            bottom: FilledButton.icon(
              key: const Key('count-review'),
              style: FilledButton.styleFrom(
                backgroundColor: c.poppy,
                foregroundColor: c.onPoppy,
                minimumSize: const Size.fromHeight(56),
              ),
              onPressed: lines.isEmpty
                  ? null
                  : () => Navigator.of(context).push(
                      MaterialPageRoute(
                        builder: (_) => CountReviewScreen(
                          countId: widget.countId,
                          queue: _queue,
                        ),
                      ),
                    ),
              icon: const Icon(LucideIcons.listChecks),
              label: Text(
                '${s.review} · ${s.products(lines.length)}',
                style: T.text(
                  size: 17,
                  weight: FontWeight.w700,
                  color: c.onPoppy,
                ),
              ),
            ),
            body: Column(
              children: [
                StockSyncBar(queue: _queue),
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
                Expanded(
                  child: lines.isEmpty
                      ? Center(
                          child: Padding(
                            padding: const EdgeInsets.all(32),
                            child: Text(
                              s.nothingCounted,
                              textAlign: TextAlign.center,
                              style: T.text(size: 16, color: c.textMuted),
                            ),
                          ),
                        )
                      : ListView.builder(
                          padding: const EdgeInsets.fromLTRB(0, 4, 0, 16),
                          itemCount: lines.length,
                          itemBuilder: (context, i) => _line(context, lines[i]),
                        ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _line(BuildContext context, DraftLine l) {
    final s = S.of(context);
    final c = SpColors.of(context);
    final expected = _queue.expected.items[l.itemId];
    final off = expected != null && expected != l.qty;
    return Card(
      key: Key('line-${l.itemId}'),
      margin: const EdgeInsets.fromLTRB(16, 6, 16, 0),
      color: _flash == l.itemId ? c.poppySoft : c.surface,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 10, 8, 10),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    l.name,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: T.text(
                      size: 16,
                      weight: FontWeight.w600,
                      color: c.text,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      if (off) ...[
                        Icon(
                          LucideIcons.triangleAlert,
                          size: 14,
                          color: c.warn,
                        ),
                        const SizedBox(width: 4),
                      ],
                      Flexible(
                        child: Text(
                          expected == null
                              ? s.noExpected
                              : s.expected(expected),
                          key: Key('expected-${l.itemId}'),
                          style: T.text(
                            size: 13,
                            weight: off ? FontWeight.w700 : FontWeight.w500,
                            color: off ? c.warn : c.textMuted,
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            QtyStepper(
              qty: l.qty,
              onChanged: (v) async {
                await _set(
                  StockProduct(id: l.itemId, name: l.name, barcode: l.barcode),
                  v,
                );
                _queue.flush();
              },
            ),
          ],
        ),
      ),
    );
  }
}

/// Review before submitting: counted vs expected per product, variances
/// first. The store's view (every phone on this count) when reachable, else
/// this phone's. A variance needs a manager's approval to submit.
class CountReviewScreen extends StatefulWidget {
  final String countId;
  final StockQueue? queue;
  const CountReviewScreen({super.key, required this.countId, this.queue});

  @override
  State<CountReviewScreen> createState() => _CountReviewScreenState();
}

class _ReviewLine {
  final String itemId, name;
  final int counted;
  final int? expected;
  const _ReviewLine(this.itemId, this.name, this.counted, this.expected);
  int? get variance => expected == null ? null : counted - expected!;
}

class _CountReviewScreenState extends State<CountReviewScreen> {
  StockQueue get _queue => widget.queue ?? StockQueue.instance;
  CountSession? _store;
  bool _loading = true;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    // send what this phone has first, so the store's view includes it
    final flushed = await _queue.flush();
    CountSession? session;
    final holding = _queue.ops.any((o) => o.countId == widget.countId);
    if (!flushed.offline && !holding) {
      try {
        session = await Api.getCount(
          widget.countId,
          counterId: _queue.counterId,
        );
      } catch (_) {}
    }
    if (mounted) {
      setState(() {
        _store = session;
        _loading = false;
      });
    }
  }

  List<_ReviewLine> get _lines {
    final store = _store;
    final List<_ReviewLine> lines;
    if (store != null) {
      lines = [
        for (final l in store.lines)
          _ReviewLine(l.itemId, l.name, l.counted, l.expected),
      ];
    } else {
      final draft = _queue.draft(widget.countId);
      lines = [
        for (final l in draft?.lines.values ?? const <DraftLine>[])
          _ReviewLine(l.itemId, l.name, l.qty, _queue.expected.items[l.itemId]),
      ];
    }
    lines.sort((a, b) {
      final va = (a.variance ?? 0).abs(), vb = (b.variance ?? 0).abs();
      return va != vb ? vb.compareTo(va) : a.name.compareTo(b.name);
    });
    return lines;
  }

  Future<void> _submit() async {
    final s = S.of(context);
    final variances = _lines.where((l) => (l.variance ?? 0) != 0).length;
    final needsManager =
        (_store?.needsApproval ?? variances > 0) &&
        Api.currentUser?.isManager != true;
    String? pin;
    if (needsManager) {
      pin = await askManagerPin(context);
      if (pin == null) return;
    }
    setState(() => _busy = true);
    try {
      await _queue.submitCount(widget.countId, managerPin: pin);
      final result = await _queue.flush();
      if (!mounted) return;
      final refused = _queue.ops
          .where((o) => o.countId == widget.countId && o.failed)
          .firstOrNull;
      if (refused != null) {
        ScaffoldMessenger.of(context)
          ..hideCurrentSnackBar()
          ..showSnackBar(
            SnackBar(
              content: Text(s.error(refused.errorCode) ?? refused.error ?? ''),
            ),
          );
        return;
      }
      final sent = _queue.draft(widget.countId) == null;
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(
            content: Text(
              sent && !result.offline ? s.submitted : s.submittedOffline,
            ),
          ),
        );
      Navigator.of(
        context,
      ).popUntil((r) => r.isFirst || r.settings.name == 'stock-home');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final c = SpColors.of(context);
    final lines = _lines;
    final variances = lines.where((l) => (l.variance ?? 0) != 0).length;
    return StockScaffold(
      title: s.varianceReview,
      bottom: FilledButton.icon(
        key: const Key('count-submit'),
        style: FilledButton.styleFrom(
          backgroundColor: c.poppy,
          foregroundColor: c.onPoppy,
          minimumSize: const Size.fromHeight(56),
        ),
        onPressed: _loading || _busy || lines.isEmpty ? null : _submit,
        icon: const Icon(LucideIcons.send),
        label: Text(
          s.submit,
          style: T.text(size: 17, weight: FontWeight.w700, color: c.onPoppy),
        ),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.only(bottom: 16),
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
                  child: Row(
                    children: [
                      Icon(
                        variances == 0
                            ? LucideIcons.circleCheck
                            : LucideIcons.triangleAlert,
                        color: variances == 0 ? c.ok : c.warn,
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          s.varianceSummary(variances),
                          key: const Key('variance-summary'),
                          style: T.text(
                            size: 18,
                            weight: FontWeight.w700,
                            color: c.text,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                  child: Text(
                    '${_store != null ? s.allPhones : s.thisPhoneOnly}'
                    '${variances > 0 ? ' · ${s.managerApproves}' : ''}',
                    style: T.text(size: 13, color: c.textMuted),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 4,
                  ),
                  child: Row(
                    children: [
                      const Expanded(child: SizedBox()),
                      _col(context, s.counted, header: true),
                      _col(context, s.expectedCol, header: true),
                      _col(context, s.variance, header: true),
                    ],
                  ),
                ),
                for (final l in lines)
                  Container(
                    key: Key('review-${l.itemId}'),
                    margin: const EdgeInsets.fromLTRB(16, 4, 16, 0),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 10,
                    ),
                    decoration: BoxDecoration(
                      color: (l.variance ?? 0) != 0 ? c.warnSoft : c.surface,
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Row(
                      children: [
                        Expanded(
                          child: Text(
                            l.name,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: T.text(
                              size: 15,
                              weight: FontWeight.w600,
                              color: c.text,
                            ),
                          ),
                        ),
                        _col(context, '${l.counted}'),
                        _col(context, l.expected?.toString() ?? '—'),
                        _col(
                          context,
                          l.variance == null
                              ? '—'
                              : (l.variance! > 0
                                    ? '+${l.variance}'
                                    : '${l.variance}'),
                          color: (l.variance ?? 0) == 0 ? null : c.bad,
                        ),
                      ],
                    ),
                  ),
              ],
            ),
    );
  }

  Widget _col(
    BuildContext context,
    String text, {
    bool header = false,
    Color? color,
  }) {
    final c = SpColors.of(context);
    return SizedBox(
      width: 72,
      child: Text(
        text,
        textAlign: TextAlign.right,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: header
            ? T.small(weight: FontWeight.w700).copyWith(fontSize: 11)
            : T.text(size: 16, weight: FontWeight.w700, color: color ?? c.text),
      ),
    );
  }
}
