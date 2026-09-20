import 'package:flutter/material.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../i18n.dart';

/// Where the check lands after a move/merge — the caller navigates there.
class TableOpResult {
  final int checkId;
  final String tableLabel;
  final bool merged; // true = folded into another bill, false = moved
  TableOpResult(this.checkId, this.tableLabel, {required this.merged});
}

/// Move / merge destination picker: every zone's tables in the tables-screen
/// visual language, one gesture with two outcomes. A free table moves the
/// check there; an occupied one merges into its open bill (after a confirm).
/// Disabled: this bill's own table, closed zones, and bills already being paid.
class TablePickerScreen extends StatefulWidget {
  final Check check;
  const TablePickerScreen({super.key, required this.check});

  @override
  State<TablePickerScreen> createState() => _TablePickerScreenState();
}

class _TablePickerScreenState extends State<TablePickerScreen> {
  List<Zone>? _zones;
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final zones = await Api.zones();
      if (mounted) {
        setState(() {
          _zones = zones;
          _error = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    }
  }

  Future<void> _pick(TableInfo table) async {
    if (_busy) return;
    final l = L.of(context);
    setState(() => _busy = true);
    try {
      if (table.openCheckId == null) {
        // free table → move
        final check = await Api.moveCheck(widget.check.id, table.id);
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(l.movedToast(table.displayLabel))),
        );
        Navigator.pop(
          context,
          TableOpResult(check.id, table.displayLabel, merged: false),
        );
      } else {
        // occupied → merge, but only after an explicit confirm
        final sure = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: Text(l.mergeConfirmTitle),
            content: Text(
              l.mergeConfirmBody(
                widget.check.id,
                table.openCheckId!,
                table.displayLabel,
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: Text(l.cancel),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: Text(l.merge),
              ),
            ],
          ),
        );
        if (sure != true || !mounted) return;
        final dest = await Api.mergeCheck(widget.check.id, table.openCheckId!);
        if (!mounted) return;
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.mergedToast(dest.id))));
        Navigator.pop(
          context,
          TableOpResult(dest.id, table.displayLabel, merged: true),
        );
      }
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final zones = _zones;
    return Scaffold(
      appBar: AppBar(
        title: Text(l.moveMergeTitle(widget.check.id)),
        actions: const [LangActions()],
      ),
      body: _error != null
          ? Center(child: Text(_error!))
          : zones == null
          ? const DelayedSpinner()
          : ListView(
              padding: const EdgeInsets.all(20),
              children: [
                for (final zone in zones) ...[
                  Padding(
                    padding: const EdgeInsets.only(bottom: 10, top: 6),
                    child: Row(
                      children: [
                        Text(
                          l.name(zone.nameFr, zone.nameEn),
                          style: T.text(
                            weight: FontWeight.w600,
                            color: zone.isClosed ? T.textMuted : T.textPrimary,
                          ),
                        ),
                        if (zone.isClosed) ...[
                          const SizedBox(width: 8),
                          Pill(l.zoneClosed, color: T.destructive),
                        ],
                      ],
                    ),
                  ),
                  GridView.count(
                    crossAxisCount: 4,
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    mainAxisSpacing: 12,
                    crossAxisSpacing: 12,
                    childAspectRatio: 1.6,
                    children: [
                      for (final table in zone.tables)
                        _tableCard(zone, table, l),
                    ],
                  ),
                  const SizedBox(height: 14),
                ],
              ],
            ),
    );
  }

  Widget _tableCard(Zone zone, TableInfo table, L l) {
    final self = table.openCheckId == widget.check.id;
    final open = table.openCheckId != null;
    // TOTAL_LOCKED = money already applied on the destination → merge would be
    // refused server-side; show why instead of a dead tap
    final beingPaid = table.openCheckStatus == 'TOTAL_LOCKED';
    final enabled = !self && !zone.isClosed && !beingPaid && !_busy;
    return Opacity(
      opacity: enabled ? 1 : 0.45,
      child: PosPanel(
        edgeStrip: self ? T.attention : (open ? T.accent : null),
        onTap: enabled ? () => _pick(table) : null,
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              table.displayLabel,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: T.text(size: 20, weight: FontWeight.w600),
            ),
            const Spacer(),
            if (self)
              Text(l.thisBill, style: T.small(color: T.attention))
            else if (open) ...[
              Text(
                '${l.bill} #${table.openCheckId} · ${cad(table.openCheckTotalCents ?? 0)}',
                style: T.small(),
              ),
              Text(
                beingPaid ? l.beingPaid : l.merge,
                style: T.small(
                  color: beingPaid ? T.destructive : T.accent,
                  weight: FontWeight.w600,
                ),
              ),
            ] else
              Text(l.free, style: T.small(color: T.accent)),
          ],
        ),
      ),
    );
  }
}
