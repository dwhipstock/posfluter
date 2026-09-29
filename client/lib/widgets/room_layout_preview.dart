import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';
import 'floor_plan.dart';

/// "Set up from picture" preview: the AI's proposed tables and objects drawn
/// as a ghost over the room as it is now, plus a list (N tables, M seats,
/// objects). The manager drags or removes ghost items, picks replace or merge
/// when the room already has tables, then Apply. Nothing is saved before
/// [onApply]; the editor owns the store call.
///
/// The floor assistant ("Ask AI", [RoomLayoutProposal.isEdit]) uses the same
/// preview: the new and changed things outlined, the removed ones faded, the
/// list of changes, then Apply or Cancel (the ghost is not dragged: the store
/// applies the checked proposal).
class RoomLayoutPreview extends StatefulWidget {
  final List<TableInfo> existingTables;
  final List<FloorObject> existingObjects;
  final RoomLayoutProposal proposal;

  /// mode: replace | merge; the ghost as the manager left it.
  final Future<void> Function(
    String mode,
    List<TableInfo> tables,
    List<FloorObject> objects,
  )
  onApply;
  final VoidCallback onCancel;
  const RoomLayoutPreview({
    super.key,
    required this.existingTables,
    required this.existingObjects,
    required this.proposal,
    required this.onApply,
    required this.onCancel,
  });

  @override
  State<RoomLayoutPreview> createState() => _RoomLayoutPreviewState();
}

class _RoomLayoutPreviewState extends State<RoomLayoutPreview> {
  late List<TableInfo> _tables = List.of(widget.proposal.tables);
  late List<FloorObject> _objects = List.of(widget.proposal.objects);
  late String _mode = widget.existingTables.isEmpty || _edit
      ? 'merge'
      : 'replace';
  bool _busy = false;

  bool get _edit => widget.proposal.isEdit;
  bool get _hasExisting => widget.existingTables.isNotEmpty && !_edit;

  Future<void> _apply() async {
    setState(() => _busy = true);
    try {
      await widget.onApply(_hasExisting ? _mode : 'merge', _tables, _objects);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _removeTable(String id) => setState(
    () => _tables = [
      for (final t in _tables)
        if (t.id != id) t,
    ],
  );
  void _removeObject(String id) => setState(
    () => _objects = [
      for (final o in _objects)
        if (o.id != id) o,
    ],
  );

  String _objectName(FloorObject o, L l) => switch (o.type) {
    'POOL' => l.objectPool,
    'BAR_FRONT' => l.objectBarFront,
    'PILLAR' => l.objectPillar,
    _ => l.objectTypeName(o.type) ?? l.name(o.labelFr ?? '', o.labelEn ?? ''),
  };

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Expanded(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 12, 12),
            child: _canvas(),
          ),
        ),
        Container(
          width: 320,
          decoration: const BoxDecoration(
            color: T.surface,
            border: Border(left: BorderSide(color: T.border)),
          ),
          child: _panel(l),
        ),
      ],
    );
  }

  Widget _canvas() {
    final p = widget.proposal;
    final protected = p.protectedTables.toSet();
    // an edit: the changed ones show as ghosts at their new place
    final changed = {
      for (final t in _tables) t.id,
      for (final o in _objects) o.id,
    };
    final gone = {...p.removedTables, ...p.removedObjects};
    // what an apply clears (replace, or the assistant removes) fades further than what it keeps
    double fade(TableInfo t) =>
        gone.contains(t.id) ||
            (_mode == 'replace' && _hasExisting && !protected.contains(t.id))
        ? .12
        : .35;
    return FloorPlanViewport(
      interactive: false,
      builder: (scale) => [
        for (final o in widget.existingObjects)
          if (!_edit || !changed.contains(o.id))
            placedObject(
              o,
              scale,
              child: IgnorePointer(
                child: Opacity(
                  opacity:
                      gone.contains(o.id) ||
                          (_mode == 'replace' && _hasExisting)
                      ? .12
                      : .35,
                  child: FloorObjectShape(object: o, scale: scale),
                ),
              ),
            ),
        for (final t in widget.existingTables)
          if (!_edit || !changed.contains(t.id))
            placedTable(
              t,
              scale,
              child: IgnorePointer(
                child: Opacity(
                  opacity: fade(t),
                  child: TableShape(table: t, scale: scale),
                ),
              ),
            ),
        for (final o in _objects)
          placedObject(
            o,
            scale,
            child: _ghost(
              key: Key('ghost-object-${o.id}'),
              onMove: (d) => setState(() {
                _objects = [
                  for (final e in _objects)
                    e.id == o.id
                        ? e.copyWith(
                            x: (e.x + d.dx / scale).round().clamp(
                              0,
                              1000 - e.width,
                            ),
                            y: (e.y + d.dy / scale).round().clamp(
                              0,
                              1000 - e.height,
                            ),
                          )
                        : e,
                ];
              }),
              onRemove: () => _removeObject(o.id),
              child: FloorObjectShape(object: o, scale: scale),
            ),
          ),
        for (final t in _tables)
          placedTable(
            t,
            scale,
            child: _ghost(
              key: Key('ghost-table-${t.id}'),
              onMove: (d) => setState(() {
                _tables = [
                  for (final e in _tables)
                    e.id == t.id
                        ? e.copyWith(
                            x: (e.x + d.dx / scale).round().clamp(
                              0,
                              1000 - e.width,
                            ),
                            y: (e.y + d.dy / scale).round().clamp(
                              0,
                              1000 - e.height,
                            ),
                          )
                        : e,
                ];
              }),
              onRemove: () => _removeTable(t.id),
              child: TableShape(
                table: t,
                scale: scale,
                subtitle: '${t.seats}',
                subtitleOptional: true,
              ),
            ),
          ),
      ],
    );
  }

  /// A proposed item: outlined in the accent colour, draggable, with a ×.
  Widget _ghost({
    required Key key,
    required ValueChanged<Offset> onMove,
    required VoidCallback onRemove,
    required Widget child,
  }) => GestureDetector(
    key: key,
    onPanUpdate: _edit ? null : (d) => onMove(d.delta),
    child: Stack(
      clipBehavior: Clip.none,
      children: [
        Positioned.fill(child: Opacity(opacity: .85, child: child)),
        Positioned.fill(
          child: IgnorePointer(
            child: DecoratedBox(
              decoration: BoxDecoration(
                borderRadius: T.radiusMedium,
                border: Border.all(color: T.primary, width: 2),
              ),
            ),
          ),
        ),
        if (!_edit)
          Positioned(
            right: -10,
            top: -10,
            child: GestureDetector(
              onTap: onRemove,
              child: Container(
                width: 22,
                height: 22,
                decoration: const BoxDecoration(
                  color: T.destructive,
                  shape: BoxShape.circle,
                ),
                child: const Icon(
                  LucideIcons.x,
                  size: 14,
                  color: T.onDestructive,
                ),
              ),
            ),
          ),
      ],
    ),
  );

  Widget _panel(L l) {
    final p = widget.proposal;
    final seats = _tables.fold<int>(0, (s, t) => s + t.seats);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: _edit
                ? _editList(l, p)
                : [
                    Text(
                      l.roomPreviewSummary(
                        _tables.length,
                        seats,
                        _objects.length,
                      ),
                      key: const Key('room-summary'),
                      style: T.headline(),
                    ),
                    const SizedBox(height: 6),
                    Text(l.roomPreviewHint, style: T.small(color: T.textMuted)),
                    if (_hasExisting) ...[
                      const SizedBox(height: 14),
                      Text(l.roomExisting(widget.existingTables.length)),
                      const SizedBox(height: 8),
                      SegmentedButton<String>(
                        segments: [
                          ButtonSegment(
                            value: 'replace',
                            label: Text(l.roomModeReplace),
                          ),
                          ButtonSegment(
                            value: 'merge',
                            label: Text(l.roomModeMerge),
                          ),
                        ],
                        selected: {_mode},
                        onSelectionChanged: (s) =>
                            setState(() => _mode = s.first),
                      ),
                      if (p.protectedTables.isNotEmpty) ...[
                        const SizedBox(height: 6),
                        Text(
                          l.roomProtected(p.protectedTables.length),
                          style: T.small(color: T.textMuted),
                        ),
                      ],
                    ],
                    if (p.notes.isNotEmpty) ...[
                      const SizedBox(height: 14),
                      Text(l.roomNotSure, style: T.small()),
                      Text(p.notes, style: T.small(color: T.textMuted)),
                    ],
                    if (p.rejected.isNotEmpty) ...[
                      const SizedBox(height: 14),
                      Text(l.roomSkipped(p.rejected.length), style: T.small()),
                      for (final r in p.rejected)
                        Text('• $r', style: T.small(color: T.textMuted)),
                    ],
                    const SizedBox(height: 14),
                    for (final t in _tables)
                      _row(
                        '${t.label} · ${t.seats} ${l.seatsLabel.toLowerCase()}',
                        () => _removeTable(t.id),
                        Key('room-remove-${t.id}'),
                      ),
                    for (final o in _objects)
                      _row(
                        _objectName(o, l),
                        () => _removeObject(o.id),
                        Key('room-remove-${o.id}'),
                      ),
                  ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: _busy ? null : widget.onCancel,
                  child: Text(l.cancel),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: FilledButton(
                  key: const Key('room-apply'),
                  onPressed:
                      _busy ||
                          (_edit
                              ? p.changes.isEmpty
                              : _tables.isEmpty && _objects.isEmpty)
                      ? null
                      : _apply,
                  child: Text(l.roomApply),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  /// The floor assistant's side panel: what was heard, the summary, each change.
  List<Widget> _editList(L l, RoomLayoutProposal p) {
    String value(String field, String v) =>
        field == 'shape' ? l.floorShapeName(v) : v;
    return [
      if ((p.transcript ?? '').isNotEmpty) ...[
        Text(
          l.aiHeard(p.transcript!),
          key: const Key('floor-heard'),
          style: T.small(color: T.textMuted),
        ),
        const SizedBox(height: 8),
      ],
      if (p.summary.isNotEmpty) Text(p.summary, style: T.headline()),
      const SizedBox(height: 6),
      Text(l.floorEditHint, style: T.small(color: T.textMuted)),
      if (p.protectedTables.isNotEmpty) ...[
        const SizedBox(height: 6),
        Text(
          l.roomProtected(p.protectedTables.length),
          style: T.small(color: T.textMuted),
        ),
      ],
      const SizedBox(height: 14),
      for (final c in p.changes)
        Padding(
          key: Key('floor-change-${c.id}'),
          padding: const EdgeInsets.only(bottom: 10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '${l.floorChangeKind(c.kind)} · ${c.title}',
                style: T.text(
                  size: 15,
                  color: c.kind.startsWith('remove')
                      ? T.destructive
                      : T.textPrimary,
                ),
              ),
              for (final d in c.details)
                Text(
                  '${l.floorField(d.field)}: '
                  '${d.before == null ? '' : '${value(d.field, d.before!)} → '}'
                  '${value(d.field, d.after ?? '')}',
                  style: T.small(),
                ),
            ],
          ),
        ),
      if (p.rejected.isNotEmpty) ...[
        const SizedBox(height: 8),
        Text(l.roomSkipped(p.rejected.length), style: T.small()),
        for (final r in p.rejected)
          Text('• $r', style: T.small(color: T.textMuted)),
      ],
    ];
  }

  Widget _row(String text, VoidCallback onRemove, Key key) => Row(
    children: [
      Expanded(child: Text(text, overflow: TextOverflow.ellipsis)),
      IconButton(
        key: key,
        icon: const Icon(LucideIcons.x, size: 16),
        onPressed: onRemove,
        visualDensity: VisualDensity.compact,
      ),
    ],
  );
}
