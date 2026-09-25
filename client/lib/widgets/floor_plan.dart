import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';

/// Geometry is stored server-side in LOGICAL units on a square canvas —
/// the client scales to its viewport, so one layout fits every screen.
const double kCanvasUnits = 1000;

/// Status color shared by service + edit views. Same priority as the old
/// cards: closed zone (red, a stopping state) > pending guest order (gold) >
/// open check (copper) > free (null → neutral border).
Color? tableStatusColor(TableInfo t, {required bool zoneClosed}) {
  if (zoneClosed) return T.destructive;
  if (t.pendingCount > 0) return T.pending;
  if (t.openCheckId != null) return T.accent;
  return null;
}

/// Text colour for a table filled with [statusColor] (null = not filled).
Color? _onStatus(Color? statusColor) => switch (statusColor) {
  T.accent => T.onAccent,
  T.pending => T.onPending,
  _ => null,
};

/// Logical-unit bounding box of a room's tables and props, padded, so the
/// service view can zoom to what is actually drawn instead of the whole
/// 1000x1000 canvas. Null when the room is empty.
Rect? floorContentBounds(Zone zone, {double pad = 30}) {
  final boxes = [
    for (final t in zone.tables)
      Rect.fromLTWH(
        t.x.toDouble(),
        t.y.toDouble(),
        t.width.toDouble(),
        t.height.toDouble(),
      ),
    for (final o in zone.objects)
      Rect.fromLTWH(
        o.x.toDouble(),
        o.y.toDouble(),
        o.width.toDouble(),
        o.height.toDouble(),
      ),
  ];
  if (boxes.isEmpty) return null;
  final r = boxes.reduce((a, b) => a.expandToInclude(b));
  return Rect.fromLTRB(
    math.max(0, r.left - pad),
    math.max(0, r.top - pad),
    math.min(kCanvasUnits, r.right + pad),
    math.min(kCanvasUnits, r.bottom + pad),
  );
}

/// Scaled, centered canvas. [builder] gets the logical->pixel scale and returns
/// the Stack children (callers own gestures: service taps, edit drags).
/// [interactive] enables pinch zoom + pan (service mode); edit mode turns it
/// off so table drags never fight the viewer for the gesture arena.
/// [contentBounds] (service mode) fits that logical rect to the viewport
/// instead of the full square canvas, so no band of empty floor is drawn.
class FloorPlanViewport extends StatelessWidget {
  final List<Widget> Function(double scale) builder;
  final bool interactive;
  final Rect? contentBounds;
  const FloorPlanViewport({
    super.key,
    required this.builder,
    this.interactive = true,
    this.contentBounds,
  });

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final bounds =
            contentBounds ??
            const Rect.fromLTWH(0, 0, kCanvasUnits, kCanvasUnits);
        final scale = math.min(
          constraints.maxWidth / bounds.width,
          constraints.maxHeight / bounds.height,
        );
        final canvas = Container(
          width: bounds.width * scale,
          height: bounds.height * scale,
          decoration: BoxDecoration(
            color: T.surface.withValues(alpha: .6),
            borderRadius: T.radiusLarge,
            border: Border.all(color: T.border),
          ),
          child: ClipRRect(
            borderRadius: T.radiusLarge,
            child: Stack(
              clipBehavior: Clip.none,
              children: [
                Positioned(
                  left: -bounds.left * scale,
                  top: -bounds.top * scale,
                  width: kCanvasUnits * scale,
                  height: kCanvasUnits * scale,
                  child: Stack(children: builder(scale)),
                ),
              ],
            ),
          ),
        );
        if (!interactive) return Center(child: canvas);
        // constrained:false pins the child top-left, so center it ourselves by
        // handing the viewer a viewport-sized box with the canvas centered in it
        return InteractiveViewer(
          constrained: false,
          minScale: 1,
          maxScale: 4,
          boundaryMargin: const EdgeInsets.all(120),
          child: SizedBox(
            width: constraints.maxWidth,
            height: constraints.maxHeight,
            child: Center(child: canvas),
          ),
        );
      },
    );
  }
}

/// Position + rotate one table on the canvas; [child] is a [TableShape] (or a
/// gesture wrapper around one). Rotation spins the shape around its center.
Widget placedTable(TableInfo t, double scale, {required Widget child}) =>
    Positioned(
      left: t.x * scale,
      top: t.y * scale,
      width: t.width * scale,
      height: t.height * scale,
      child: t.rotation == 0
          ? child
          : Transform.rotate(angle: t.rotation * math.pi / 180, child: child),
    );

/// Position + rotate a structural object on the canvas — same math as
/// [placedTable]; [child] is a [FloorObjectShape] (or an edit gesture wrapper).
Widget placedObject(FloorObject o, double scale, {required Widget child}) =>
    Positioned(
      left: o.x * scale,
      top: o.y * scale,
      width: o.width * scale,
      height: o.height * scale,
      child: o.rotation == 0
          ? child
          : Transform.rotate(angle: o.rotation * math.pi / 180, child: child),
    );

/// A non-orderable structural prop — pool table, bar front, pillar. Rendered
/// deliberately quiet (mono/muted, no accent, no status color) so it reads as
/// background context and the real tables stay the eye's focus. Drawn beneath
/// the tables. Inert in service mode — no gestures, no navigation.
class FloorObjectShape extends StatelessWidget {
  final FloorObject object;
  final double scale;
  final bool selected;
  const FloorObjectShape({
    super.key,
    required this.object,
    required this.scale,
    this.selected = false,
  });

  bool get _isPillar => object.type == 'PILLAR';

  // pillar reads as a round column; pool/bar are plain slabs
  BorderRadius get _radius =>
      _isPillar ? BorderRadius.circular(999) : T.radiusSmall;

  /// Caption drawn on the slab: the manager's label, else a type default.
  /// Pillars stay unlabeled — a small block needs no word.
  String? get _caption {
    final l = object.label;
    if (l != null && l.isNotEmpty) return l;
    return switch (object.type) {
      'POOL' => 'Pool',
      'BAR_FRONT' => 'Bar',
      _ => null, // PILLAR
    };
  }

  @override
  Widget build(BuildContext context) {
    final w = object.width * scale;
    final h = object.height * scale;
    // pillar: a solid muted block. pool/bar: a faint outlined slab.
    final fill = _isPillar ? T.border : T.surfaceAlt.withValues(alpha: .5);
    final caption = _caption;
    final labelSize = (math.min(w, h) * .24).clamp(9.0, 16.0);
    return Container(
      decoration: BoxDecoration(
        color: fill,
        borderRadius: _radius,
        border: Border.all(
          color: selected ? T.textPrimary : T.border,
          width: selected ? 2 : 1,
        ),
      ),
      child: caption == null
          ? null
          : Center(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                // counter-rotate so the caption stays upright on rotated slabs
                child: object.rotation == 0
                    ? _label(caption, labelSize)
                    : Transform.rotate(
                        angle: -object.rotation * math.pi / 180,
                        child: _label(caption, labelSize),
                      ),
              ),
            ),
    );
  }

  Widget _label(String text, double size) => Text(
    text,
    maxLines: 1,
    overflow: TextOverflow.ellipsis,
    textAlign: TextAlign.center,
    style: T.text(size: size, weight: FontWeight.w500, color: T.textMuted),
  );
}

/// The table object itself: shape + status fill + upright label. Pure
/// visual — no gestures, no navigation. Free tables are plain; an open check
/// fills copper and a waiting guest order fills gold, both with a contrasting
/// label so the room scans at a glance.
class TableShape extends StatelessWidget {
  final TableInfo table;
  final double scale;
  final Color? statusColor;
  final String? subtitle;

  /// Third line when the table is tall enough (time open).
  final String? detail;

  /// The subtitle is nice-to-have (seat count): dropped on small tables so
  /// the label never gets squeezed.
  final bool subtitleOptional;
  final bool selected;
  const TableShape({
    super.key,
    required this.table,
    required this.scale,
    this.statusColor,
    this.subtitle,
    this.detail,
    this.subtitleOptional = false,
    this.selected = false,
  });

  /// Below this (px) a table shows its label only, plus any money line.
  static const compactSize = 64.0;

  BorderRadius get _radius => switch (table.shape) {
    'ROUND' => BorderRadius.circular(999),
    'BAR' => T.radiusSmall,
    _ => T.radiusMedium, // SQUARE | RECT
  };

  @override
  Widget build(BuildContext context) {
    final w = table.width * scale;
    final h = table.height * scale;
    final labelSize = (math.min(w, h) * .26).clamp(11.0, 22.0);
    final subSize = math.max(labelSize * .64, 10.0);
    final onFill = _onStatus(statusColor);
    final filled = onFill != null;
    final fill = filled
        ? statusColor!
        : statusColor?.withValues(alpha: .12) ??
              (table.shape == 'BAR' ? T.surfaceAlt : T.surface);
    final ink = onFill ?? statusColor ?? T.textPrimary;
    final inkMuted = onFill ?? T.textMuted;
    final border = filled
        ? Color.lerp(statusColor, T.textPrimary, .25)!
        : statusColor ?? T.border;
    final content = Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          table.displayLabel,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          textAlign: TextAlign.center,
          style: T.text(size: labelSize, weight: FontWeight.w700, color: ink),
        ),
        if (subtitle != null &&
            h > 36 &&
            !(subtitleOptional && math.min(w, h) < compactSize))
          Text(
            subtitle!,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.center,
            style: T
                .price(
                  size: subSize,
                  weight: filled ? FontWeight.w600 : FontWeight.w400,
                  color: inkMuted,
                )
                .copyWith(height: 1.15),
          ),
        if (detail != null && h > 58)
          Text(
            detail!,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.center,
            style: T
                .text(size: math.max(subSize * .9, 9), color: inkMuted)
                .copyWith(height: 1.15),
          ),
      ],
    );
    final pending = table.pendingCount > 0;
    final box = Container(
      decoration: BoxDecoration(
        color: fill,
        borderRadius: _radius,
        border: Border.all(
          color: selected ? T.textPrimary : border,
          width: selected || statusColor != null ? 2 : 1,
        ),
        boxShadow: filled ? T.raised : null,
      ),
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Center(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 4),
              // scale down rather than clip when a small table gets 3 lines
              child: FittedBox(
                fit: BoxFit.scaleDown,
                // counter-rotate so the label stays upright on rotated tables
                child: table.rotation == 0
                    ? content
                    : Transform.rotate(
                        angle: -table.rotation * math.pi / 180,
                        child: content,
                      ),
              ),
            ),
          ),
          // small tables: the badge sits on the corner, clear of the label
          if (pending)
            Positioned(
              right: math.min(w, h) < 70 ? -7 : 3,
              top: math.min(w, h) < 70 ? -7 : 3,
              child: PendingBadge(table.pendingCount),
            ),
          // VIP: the name_override already replaces the label; the star makes
          // "why is this table called Alex Morgan" legible at a glance
          // corner markers sit on the shape's bounding-box corners, outside
          // the text area (a round table's corners are outside the circle),
          // so they never cover the label at any size
          if (table.isVip)
            const Positioned(
              left: -7,
              top: -7,
              child: _CornerMarker(LucideIcons.star, color: T.attention),
            ),
          // sub-table: same physical spot as its parent, independent bill
          if (table.parentTableId != null)
            const Positioned(
              left: -7,
              bottom: -7,
              child: _CornerMarker(LucideIcons.link, color: T.textMuted),
            ),
        ],
      ),
    );
    // orders waiting to be verified need to catch the eye across the room —
    // a slow gold breathing glow around the whole table, not just the badge
    return pending ? PulsingGlow(radius: _radius, child: box) : box;
  }
}

/// Small disc on a table corner (VIP star, sub-table link).
class _CornerMarker extends StatelessWidget {
  final IconData icon;
  final Color color;
  const _CornerMarker(this.icon, {required this.color});

  @override
  Widget build(BuildContext context) => Container(
    width: 20,
    height: 20,
    decoration: BoxDecoration(
      color: T.surface,
      shape: BoxShape.circle,
      border: Border.all(color: T.border),
    ),
    child: Icon(icon, size: 11, color: color),
  );
}
