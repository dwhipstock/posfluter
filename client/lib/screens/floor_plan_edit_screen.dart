import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';
import '../widgets/floor_plan.dart';

/// Manager floor-plan editor. Geometry edits (drag / resize / rotate / shape /
/// seats) are LOCAL until "Save layout" does one batch write; add / delete /
/// rename hit the server immediately (they're identity, not layout). The
/// manager PIN collected at the edit-mode unlock travels with every request,
/// which re-verifies it server-side — same trust model as void/86.
class FloorPlanEditScreen extends StatefulWidget {
  final Zone zone;
  final String managerPin;
  const FloorPlanEditScreen({
    super.key,
    required this.zone,
    required this.managerPin,
  });

  @override
  State<FloorPlanEditScreen> createState() => _FloorPlanEditScreenState();
}

class _FloorPlanEditScreenState extends State<FloorPlanEditScreen> {
  static const _grid = 20; // snap step, logical units
  static const _shapes = ['ROUND', 'SQUARE', 'RECT', 'BAR'];

  late List<TableInfo> _tables = List.of(widget.zone.tables);
  // structural props (pool/bar/pillar): geometry-editable like tables, but inert
  late List<FloorObject> _objects = List.of(widget.zone.objects);
  // selection is mutually exclusive across the two layers
  String? _selectedId; // selected table
  String? _selectedObjectId; // selected object
  bool _dirty = false;
  bool _busy = false;
  // geometry-only undo: snapshots of BOTH layers before each drag/resize/toolbar
  // tweak. Cleared on add/delete/rename — those already live on the server.
  final List<_Snapshot> _undo = [];

  // Editor toggles, hydrated from device-local prefs (Prefs.load ran at startup)
  // so they stick between edit sessions. Grid = show the dot grid (+ its dot
  // spacing); snap = snap move/resize to the 20u step. All independent.
  bool _showGrid = Prefs.instance.editorShowGrid;
  bool _snapEnabled = Prefs.instance.editorSnap;
  int _gridStep =
      Prefs.instance.editorGridStep; // visual dot spacing (logical units)

  // Grid dropdown sizes, big → small; 100 is the original (largest) spacing.
  static const _gridSizes = [100, 50, 25];

  void _selectGrid(String value) {
    setState(() {
      if (value == 'off') {
        _showGrid = false;
      } else {
        _showGrid = true;
        _gridStep = int.parse(value);
      }
    });
    Prefs.instance.setEditorShowGrid(_showGrid);
    Prefs.instance.setEditorGridStep(_gridStep);
  }

  void _toggleSnap() {
    setState(() => _snapEnabled = !_snapEnabled);
    Prefs.instance.setEditorSnap(_snapEnabled);
  }

  TableInfo? get _selected {
    for (final t in _tables) {
      if (t.id == _selectedId) return t;
    }
    return null;
  }

  FloorObject? get _selectedObject {
    for (final o in _objects) {
      if (o.id == _selectedObjectId) return o;
    }
    return null;
  }

  void _pushUndo() {
    _undo.add(_Snapshot(List.of(_tables), List.of(_objects)));
    if (_undo.length > 50) _undo.removeAt(0);
  }

  void _mutateSelected(
    TableInfo Function(TableInfo) fn, {
    bool snapshot = true,
  }) {
    final t = _selected;
    if (t == null) return;
    if (snapshot) _pushUndo();
    setState(() {
      _tables = [for (final e in _tables) e.id == t.id ? fn(e) : e];
      _dirty = true;
    });
  }

  void _mutateObject(
    FloorObject Function(FloorObject) fn, {
    bool snapshot = true,
  }) {
    final o = _selectedObject;
    if (o == null) return;
    if (snapshot) _pushUndo();
    setState(() {
      _objects = [for (final e in _objects) e.id == o.id ? fn(e) : e];
      _dirty = true;
    });
  }

  // Snap to the grid step when enabled; otherwise just settle to a whole unit
  // (positions/sizes are ints) so free placement survives the drag/resize end.
  int _snap(num v) => _snapEnabled ? (v / _grid).round() * _grid : v.round();

  Future<void> _save() async {
    if (_busy) return;
    setState(() => _busy = true);
    final l = L.of(context);
    try {
      await Api.saveZoneLayout(widget.zone.id, [
        for (final t in _tables) t.geometryJson,
      ], widget.managerPin);
      await Api.saveZoneObjects(widget.zone.id, [
        for (final o in _objects) o.geometryJson,
      ], widget.managerPin);
      if (!mounted) return;
      setState(() {
        _dirty = false;
        _undo.clear();
      });
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.layoutSaved)));
    } catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _addTable() async {
    final l = L.of(context);
    final labelCtl = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l.addTable),
        content: SizedBox(
          width: 360,
          child: TextField(
            controller: labelCtl,
            autofocus: true,
            decoration: InputDecoration(
              labelText: l.tableLabelField,
              // labels are server-enforced to the zone prefix; blank = next free
              helperText: l.autoLabelHint(widget.zone.labelPrefix),
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(l.addTable),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      // drop it near the canvas center, nudged so repeated adds don't stack
      final nudge = (_tables.length % 5) * 30;
      // blank label → server auto-assigns the next "{prefix}-{n}"; a typed one is
      // coerced to the zone prefix server-side either way.
      final typed = labelCtl.text.trim();
      final created = await Api.addTable(widget.zone.id, {
        if (typed.isNotEmpty) 'label': typed,
        'x': 420 + nudge,
        'y': 420 + nudge,
      }, widget.managerPin);
      if (!mounted) return;
      setState(() {
        _tables = [..._tables, created];
        _selectedId = created.id;
        _undo
            .clear(); // snapshots from before the add would resurrect stale state
      });
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _deleteSelected() async {
    final t = _selected;
    if (t == null) return;
    final l = L.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l.deleteTable),
        content: Text(l.deleteTableConfirm(t.displayLabel)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: T.destructive,
              foregroundColor: Colors.white,
            ),
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(l.deleteTable),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      await Api.deleteTable(t.id, widget.managerPin);
      if (!mounted) return;
      setState(() {
        _tables = [
          for (final e in _tables)
            if (e.id != t.id) e,
        ];
        _selectedId = null;
        _undo.clear();
      });
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.tableDeleted)));
    } catch (e) {
      // table_in_use / has_sub_tables come back as clear localized errors
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _renameSelected() async {
    final t = _selected;
    if (t == null) return;
    final l = L.of(context);
    final labelCtl = TextEditingController(text: t.label);
    final vipCtl = TextEditingController(text: t.nameOverride ?? '');
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l.renameTable),
        content: SizedBox(
          width: 360,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: labelCtl,
                autofocus: true,
                decoration: InputDecoration(labelText: l.tableLabelField),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: vipCtl,
                decoration: InputDecoration(labelText: l.vipNameField),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(l.save),
          ),
        ],
      ),
    );
    if (ok != true || labelCtl.text.trim().isEmpty || !mounted) return;
    try {
      // empty VIP field = clear the override (server treats "" as clear)
      final updated = await Api.renameTable(
        t.id,
        label: labelCtl.text.trim(),
        nameOverride: vipCtl.text.trim(),
        managerPin: widget.managerPin,
      );
      if (!mounted) return;
      setState(() {
        _tables = [
          for (final e in _tables)
            e.id == t.id
                ? e.copyWith(
                    label: updated.label,
                    nameOverride: updated.nameOverride,
                    clearNameOverride: updated.nameOverride == null,
                  )
                : e,
        ];
        _undo.clear();
      });
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  /// Type-appropriate default footprint (logical units) for a fresh object.
  (int, int) _defaultObjectSize(String type) => switch (type) {
    'BAR_FRONT' => (320, 60), // long, shallow counter
    'POOL' => (200, 120), // a plain table slab
    _ => (80, 80), // PILLAR — a small block
  };

  /// Drop a structural prop near the canvas centre. Like _addTable, add hits the
  /// server immediately (identity); geometry then edits locally until save.
  Future<void> _addObject(String type) async {
    try {
      final nudge = ((_tables.length + _objects.length) % 5) * 30;
      final (w, h) = _defaultObjectSize(type);
      final created = await Api.addObject(widget.zone.id, {
        'type': type,
        'x': 400 + nudge,
        'y': 400 + nudge,
        'width': w,
        'height': h,
      }, widget.managerPin);
      if (!mounted) return;
      setState(() {
        _objects = [..._objects, created];
        _selectedObjectId = created.id;
        _selectedId = null;
        _undo
            .clear(); // snapshots from before the add would resurrect stale state
      });
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  Future<void> _deleteSelectedObject() async {
    final o = _selectedObject;
    if (o == null) return;
    final l = L.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l.deleteObject),
        content: Text(l.deleteObjectConfirm(_objectName(o, l))),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: T.destructive,
              foregroundColor: Colors.white,
            ),
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(l.deleteObject),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      await Api.deleteObject(o.id, widget.managerPin);
      if (!mounted) return;
      setState(() {
        _objects = [
          for (final e in _objects)
            if (e.id != o.id) e,
        ];
        _selectedObjectId = null;
        _undo.clear();
      });
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.objectDeleted)));
    } catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  String _objectName(FloorObject o, L l) => switch (o.type) {
    'POOL' => l.objectPool,
    'BAR_FRONT' => l.objectBarFront,
    _ => l.objectPillar,
  };

  void _undoLast() {
    if (_undo.isEmpty) return;
    final s = _undo.removeLast();
    setState(() {
      _tables = s.tables;
      _objects = s.objects;
      _dirty = true;
    });
  }

  Future<void> _confirmLeave(bool didPop, Object? result) async {
    if (didPop) return;
    final l = L.of(context);
    final leave = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l.unsavedLayoutTitle),
        content: Text(l.unsavedLayoutBody),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(
              l.discard,
              style: const TextStyle(color: T.destructive),
            ),
          ),
          FilledButton(
            onPressed: () async {
              await _save();
              if (ctx.mounted) Navigator.pop(ctx, !_dirty);
            },
            child: Text(l.saveLayout),
          ),
        ],
      ),
    );
    if (leave == true && mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final selected = _selected;
    return PopScope(
      canPop: !_dirty,
      onPopInvokedWithResult: _confirmLeave,
      child: Scaffold(
        appBar: AppBar(
          title: Text(
            l.editLayoutTitle(l.name(widget.zone.nameFr, widget.zone.nameEn)),
          ),
          actions: [
            // independent editor controls: dot-grid dropdown (off + 3 sizes) +
            // snap-to-grid toggle. accent when on, muted when off.
            _gridMenu(l),
            _toggleAction(
              LucideIcons.magnet,
              l.editorSnap,
              _snapEnabled,
              _toggleSnap,
            ),
            IconButton(
              icon: const Icon(LucideIcons.undo2),
              tooltip: l.undo,
              onPressed: _undo.isEmpty ? null : _undoLast,
            ),
            IconButton(
              icon: const Icon(LucideIcons.plus),
              tooltip: l.addTable,
              onPressed: _addTable,
            ),
            // palette: drop a structural prop (pool / bar front / pillar)
            PopupMenuButton<String>(
              icon: const Icon(LucideIcons.shapes),
              tooltip: l.addObject,
              onSelected: _addObject,
              itemBuilder: (ctx) => [
                _objectMenuItem('POOL', LucideIcons.circleDot, l.objectPool),
                _objectMenuItem(
                  'BAR_FRONT',
                  LucideIcons.wine,
                  l.objectBarFront,
                ),
                _objectMenuItem('PILLAR', LucideIcons.columns2, l.objectPillar),
              ],
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              child: FilledButton.icon(
                icon: const Icon(LucideIcons.check, size: 18),
                label: Text(l.saveLayout),
                onPressed: _dirty && !_busy ? _save : null,
              ),
            ),
          ],
        ),
        body: Column(
          children: [
            Expanded(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(20, 12, 20, 8),
                child: _canvas(l),
              ),
            ),
            _toolbar(selected, _selectedObject, l),
          ],
        ),
      ),
    );
  }

  Widget _canvas(L l) {
    return FloorPlanViewport(
      interactive: false, // drags own the gesture arena in edit mode
      builder: (scale) => [
        // faint dot grid — the visual for snap-to-grid
        Positioned.fill(
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: () => setState(() {
              _selectedId = null;
              _selectedObjectId = null;
            }),
            // keep the full-bleed tap target for deselect; drop only the dots
            child: CustomPaint(
              painter: _showGrid
                  ? _GridPainter(scale: scale, step: _gridStep)
                  : null,
            ),
          ),
        ),
        if (_tables.isEmpty && _objects.isEmpty)
          Center(
            child: Text(
              l.emptyZoneOnboarding,
              style: T.small(),
              textAlign: TextAlign.center,
            ),
          ),
        // structural props sit beneath the tables, same as service mode
        for (final o in _objects)
          placedObject(o, scale, child: _editableObject(o, scale)),
        for (final t in _tables)
          placedTable(t, scale, child: _editableTable(t, scale)),
      ],
    );
  }

  Widget _editableObject(FloorObject o, double scale) {
    final isSelected = o.id == _selectedObjectId;
    void select() => setState(() {
      _selectedObjectId = o.id;
      _selectedId = null;
    });
    return GestureDetector(
      onTap: select,
      onPanStart: (_) {
        _pushUndo();
        select();
      },
      onPanUpdate: (d) => _mutateObject(
        snapshot: false,
        (e) => e.copyWith(
          x: (e.x + d.delta.dx / scale)
              .round()
              .clamp(0, 1000 - e.width)
              .toInt(),
          y: (e.y + d.delta.dy / scale)
              .round()
              .clamp(0, 1000 - e.height)
              .toInt(),
        ),
      ),
      onPanEnd: (_) => _mutateObject(
        snapshot: false,
        (e) => e.copyWith(
          x: _snap(e.x).clamp(0, 1000 - e.width).toInt(),
          y: _snap(e.y).clamp(0, 1000 - e.height).toInt(),
        ),
      ),
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Positioned.fill(
            child: FloorObjectShape(
              object: o,
              scale: scale,
              selected: isSelected,
            ),
          ),
          if (isSelected)
            Positioned(
              right: -8,
              bottom: -8,
              child: GestureDetector(
                onPanStart: (_) => _pushUndo(),
                onPanUpdate: (d) => _mutateObject(
                  snapshot: false,
                  (e) => e.copyWith(
                    width: (e.width + d.delta.dx / scale)
                        .round()
                        .clamp(40, 1000 - e.x)
                        .toInt(),
                    height: (e.height + d.delta.dy / scale)
                        .round()
                        .clamp(40, 1000 - e.y)
                        .toInt(),
                  ),
                ),
                onPanEnd: (_) => _mutateObject(
                  snapshot: false,
                  (e) => e.copyWith(
                    width: _snap(e.width).clamp(40, 1000 - e.x).toInt(),
                    height: _snap(e.height).clamp(40, 1000 - e.y).toInt(),
                  ),
                ),
                child: Container(
                  width: 26,
                  height: 26,
                  decoration: BoxDecoration(
                    color: T.surfaceAlt,
                    borderRadius: T.radiusSmall,
                    border: Border.all(color: T.textPrimary, width: 2),
                  ),
                  child: const Icon(
                    LucideIcons.moveDiagonal2,
                    size: 14,
                    color: T.textPrimary,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _editableTable(TableInfo t, double scale) {
    final isSelected = t.id == _selectedId;
    void select() => setState(() {
      _selectedId = t.id;
      _selectedObjectId = null;
    });
    return GestureDetector(
      onTap: select,
      onPanStart: (_) {
        _pushUndo();
        select();
      },
      onPanUpdate: (d) => _mutateSelected(
        snapshot: false,
        (e) => e.copyWith(
          x: (e.x + d.delta.dx / scale)
              .round()
              .clamp(0, 1000 - e.width)
              .toInt(),
          y: (e.y + d.delta.dy / scale)
              .round()
              .clamp(0, 1000 - e.height)
              .toInt(),
        ),
      ),
      onPanEnd: (_) => _mutateSelected(
        snapshot: false,
        (e) => e.copyWith(
          x: _snap(e.x).clamp(0, 1000 - e.width).toInt(),
          y: _snap(e.y).clamp(0, 1000 - e.height).toInt(),
        ),
      ),
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Positioned.fill(
            child: TableShape(table: t, scale: scale, selected: isSelected),
          ),
          // resize handle: bottom-right corner of the selected table.
          // TODO: deltas are screen-axis, so resizing a rotated table drifts.
          if (isSelected)
            Positioned(
              right: -8,
              bottom: -8,
              child: GestureDetector(
                onPanStart: (_) => _pushUndo(),
                onPanUpdate: (d) => _mutateSelected(
                  snapshot: false,
                  (e) => e.copyWith(
                    width: (e.width + d.delta.dx / scale)
                        .round()
                        .clamp(40, 1000 - e.x)
                        .toInt(),
                    height: (e.height + d.delta.dy / scale)
                        .round()
                        .clamp(40, 1000 - e.y)
                        .toInt(),
                  ),
                ),
                onPanEnd: (_) => _mutateSelected(
                  snapshot: false,
                  (e) => e.copyWith(
                    width: _snap(e.width).clamp(40, 1000 - e.x).toInt(),
                    height: _snap(e.height).clamp(40, 1000 - e.y).toInt(),
                  ),
                ),
                child: Container(
                  width: 26,
                  height: 26,
                  decoration: BoxDecoration(
                    color: T.surfaceAlt,
                    borderRadius: T.radiusSmall,
                    border: Border.all(color: T.textPrimary, width: 2),
                  ),
                  child: const Icon(
                    LucideIcons.moveDiagonal2,
                    size: 14,
                    color: T.textPrimary,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  /// Bottom toolbar for the selected table: shape / rotate / seats / rename /
  /// delete. Hidden (hint shown) while nothing is selected.
  Widget _toolbar(TableInfo? t, FloorObject? o, L l) {
    final Widget body;
    if (o != null) {
      body = _objectToolbar(o, l);
    } else if (t != null) {
      body = _tableToolbar(t, l);
    } else {
      body = SizedBox(
        height: T.minTouch,
        child: Center(child: Text(l.tapTableToEditHint, style: T.small())),
      );
    }
    return Container(
      width: double.infinity,
      decoration: const BoxDecoration(
        color: T.surface,
        border: Border(top: BorderSide(color: T.border)),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
      child: body,
    );
  }

  /// Object controls: rotate + delete. No shape/seats — objects are inert.
  Widget _objectToolbar(FloorObject o, L l) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          Text(_objectName(o, l), style: T.headline()),
          const SizedBox(width: 16),
          OutlinedButton.icon(
            icon: const Icon(LucideIcons.rotateCw, size: 18),
            label: Text('${o.rotation}°'),
            onPressed: () => _mutateObject(
              (e) => e.copyWith(rotation: (e.rotation + 45) % 360),
            ),
          ),
          const SizedBox(width: 12),
          OutlinedButton.icon(
            icon: const Icon(
              LucideIcons.trash2,
              size: 18,
              color: T.destructive,
            ),
            label: Text(
              l.deleteObject,
              style: const TextStyle(color: T.destructive),
            ),
            onPressed: _deleteSelectedObject,
          ),
        ],
      ),
    );
  }

  /// Bottom toolbar for the selected table: shape / rotate / seats / rename /
  /// delete.
  Widget _tableToolbar(TableInfo t, L l) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          Text(t.displayLabel, style: T.headline()),
          const SizedBox(width: 16),
          // shape picker
          for (final s in _shapes) ...[
            _shapeButton(s, active: t.shape == s),
            const SizedBox(width: 6),
          ],
          const SizedBox(width: 12),
          OutlinedButton.icon(
            icon: const Icon(LucideIcons.rotateCw, size: 18),
            label: Text('${t.rotation}°'),
            onPressed: () => _mutateSelected(
              (e) => e.copyWith(rotation: (e.rotation + 45) % 360),
            ),
          ),
          const SizedBox(width: 12),
          Text(l.seatsLabel, style: T.small()),
          IconButton(
            icon: const Icon(LucideIcons.minus),
            onPressed: t.seats > 0
                ? () => _mutateSelected((e) => e.copyWith(seats: e.seats - 1))
                : null,
          ),
          Text('${t.seats}', style: T.price()),
          IconButton(
            icon: const Icon(LucideIcons.plus),
            onPressed: t.seats < 50
                ? () => _mutateSelected((e) => e.copyWith(seats: e.seats + 1))
                : null,
          ),
          const SizedBox(width: 12),
          OutlinedButton.icon(
            icon: const Icon(LucideIcons.pencil, size: 18),
            label: Text(l.renameTable),
            onPressed: _renameSelected,
          ),
          const SizedBox(width: 8),
          OutlinedButton.icon(
            icon: const Icon(
              LucideIcons.trash2,
              size: 18,
              color: T.destructive,
            ),
            label: Text(
              l.deleteTable,
              style: const TextStyle(color: T.destructive),
            ),
            onPressed: _deleteSelected,
          ),
        ],
      ),
    );
  }

  /// App-bar toggle: tooltip names the setting, colour shows on/off state.
  Widget _toggleAction(
    IconData icon,
    String label,
    bool on,
    VoidCallback onTap,
  ) => IconButton(
    icon: Icon(icon, color: on ? T.accent : T.textMuted),
    tooltip: on ? '$label ✓' : label,
    onPressed: onTap,
  );

  /// Grid control: a dropdown of Off + three dot spacings (Large/Medium/Small).
  /// Icon reads accent when the grid is on, muted when off — same colour
  /// language as the Snap toggle.
  Widget _gridMenu(L l) => PopupMenuButton<String>(
    icon: Icon(LucideIcons.grid3x3, color: _showGrid ? T.accent : T.textMuted),
    tooltip: l.editorGrid,
    onSelected: _selectGrid,
    itemBuilder: (ctx) => [
      _gridMenuItem('off', l.gridOff, selected: !_showGrid),
      for (final s in _gridSizes)
        _gridMenuItem(
          '$s',
          _gridSizeLabel(s, l),
          selected: _showGrid && _gridStep == s,
        ),
    ],
  );

  String _gridSizeLabel(int step, L l) => switch (step) {
    100 => l.gridLarge,
    50 => l.gridMedium,
    _ => l.gridSmall,
  };

  /// One grid-menu row: a leading check on the active choice, then the label.
  PopupMenuItem<String> _gridMenuItem(
    String value,
    String label, {
    required bool selected,
  }) => PopupMenuItem<String>(
    value: value,
    child: Row(
      children: [
        SizedBox(
          width: 26,
          child: selected
              ? const Icon(LucideIcons.check, size: 16, color: T.accent)
              : null,
        ),
        Text(label),
      ],
    ),
  );

  PopupMenuItem<String> _objectMenuItem(
    String type,
    IconData icon,
    String label,
  ) => PopupMenuItem<String>(
    value: type,
    child: Row(
      children: [
        Icon(icon, size: 18, color: T.textMuted),
        const SizedBox(width: 12),
        Text(label),
      ],
    ),
  );

  Widget _shapeButton(String shape, {required bool active}) {
    final icon = switch (shape) {
      'ROUND' => LucideIcons.circle,
      'SQUARE' => LucideIcons.square,
      'RECT' => LucideIcons.rectangleHorizontal,
      _ => LucideIcons.minus, // BAR
    };
    return SizedBox(
      width: T.minTouch,
      height: T.minTouch,
      child: OutlinedButton(
        style: OutlinedButton.styleFrom(
          padding: EdgeInsets.zero,
          backgroundColor: active ? T.surfaceAlt : null,
          side: BorderSide(color: active ? T.accent : T.border),
        ),
        // RECT/BAR presets widen the footprint so the shape reads immediately
        onPressed: () => _mutateSelected(
          (e) => e.copyWith(
            shape: shape,
            width: switch (shape) {
              'RECT' when e.width == e.height => math.min(
                e.width * 2,
                1000 - e.x,
              ),
              'BAR' => math.min(math.max(e.width, 240), 1000 - e.x),
              _ => e.width,
            },
            height: shape == 'BAR' ? 60 : e.height,
          ),
        ),
        child: Icon(icon, size: 20, color: active ? T.accent : T.textMuted),
      ),
    );
  }
}

/// One undo frame: both editable layers captured together so undo restores the
/// canvas exactly, whichever layer the last tweak touched.
class _Snapshot {
  final List<TableInfo> tables;
  final List<FloorObject> objects;
  _Snapshot(this.tables, this.objects);
}

/// Dot grid so snap-to-grid has a visual anchor. T.border is nearly the canvas
/// colour, so key off T.textMuted — the dots have to actually read on the dark
/// theme, else the Grid toggle looks like it does nothing.
class _GridPainter extends CustomPainter {
  final double scale;
  final int step; // logical units between dots
  _GridPainter({required this.scale, required this.step});

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()..color = T.textMuted.withValues(alpha: .7);
    for (var x = step; x < kCanvasUnits; x += step) {
      for (var y = step; y < kCanvasUnits; y += step) {
        canvas.drawCircle(Offset(x * scale, y * scale), 2.0, paint);
      }
    }
  }

  @override
  bool shouldRepaint(_GridPainter old) =>
      old.scale != scale || old.step != step;
}
