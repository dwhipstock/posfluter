import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import 'tokens.dart';

/// Flat bordered surface — the replacement for every Material Card.
class PosPanel extends StatelessWidget {
  final Widget child;
  final Color color;
  final Color borderColor;
  final EdgeInsetsGeometry? padding;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;

  /// Colored strip on the left edge (occupied tables, status accents).
  final Color? edgeStrip;

  /// Soft shadow for tiles meant to be tapped (menu items, staff cards).
  final bool raised;

  const PosPanel({
    super.key,
    required this.child,
    this.color = T.surface,
    this.borderColor = T.border,
    this.padding,
    this.onTap,
    this.onLongPress,
    this.edgeStrip,
    this.raised = false,
  });

  @override
  Widget build(BuildContext context) {
    Widget content = padding != null
        ? Padding(padding: padding!, child: child)
        : child;
    if (edgeStrip != null) {
      content = Row(
        children: [
          Container(width: 4, color: edgeStrip),
          Expanded(child: content),
        ],
      );
    }
    final box = Container(
      decoration: BoxDecoration(
        borderRadius: T.radiusMedium,
        boxShadow: raised ? T.raised : null,
      ),
      child: ClipRRect(
        borderRadius: T.radiusMedium,
        child: Container(
          decoration: BoxDecoration(
            color: color,
            borderRadius: T.radiusMedium,
            border: Border.all(color: borderColor),
          ),
          child: onTap == null && onLongPress == null
              ? content
              : Material(
                  color: Colors.transparent,
                  child: InkWell(
                    onTap: onTap,
                    onLongPress: onLongPress,
                    child: content,
                  ),
                ),
        ),
      ),
    );
    return box;
  }
}

/// Small status pill ("5/8 open", "OPEN", "86").
class Pill extends StatelessWidget {
  final String label;
  final Color color;
  const Pill(this.label, {super.key, this.color = T.textMuted});

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
    decoration: BoxDecoration(
      color: color.withValues(alpha: .14),
      borderRadius: T.radiusSmall,
      border: Border.all(color: color.withValues(alpha: .45)),
    ),
    child: Text(
      label,
      style: T.small(color: color, weight: FontWeight.w600),
    ),
  );
}

/// Gold dot for QR-pending markers.
class AttentionDot extends StatelessWidget {
  const AttentionDot({super.key});
  @override
  Widget build(BuildContext context) => Container(
    width: 10,
    height: 10,
    decoration: const BoxDecoration(color: T.pending, shape: BoxShape.circle),
  );
}

/// Pulsing gold count badge for orders waiting to be verified — a calm
/// ~1.6s breathe (glow + slight scale), not a strobe. Shared by the
/// floor-plan table corner marker and the room-switcher chip.
class PendingBadge extends StatefulWidget {
  final int count;
  const PendingBadge(this.count, {super.key});

  @override
  State<PendingBadge> createState() => _PendingBadgeState();
}

class _PendingBadgeState extends State<PendingBadge>
    with SingleTickerProviderStateMixin {
  late final _ctrl = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1600),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _ctrl,
      builder: (context, _) {
        final t = Curves.easeInOut.transform(_ctrl.value);
        return Transform.scale(
          scale: 1.0 + t * 0.08,
          child: Container(
            constraints: const BoxConstraints(minWidth: 18, minHeight: 18),
            padding: const EdgeInsets.symmetric(horizontal: 5),
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: T.pending,
              borderRadius: BorderRadius.circular(999),
              border: Border.all(color: T.onPending, width: 1.5),
              boxShadow: [
                BoxShadow(
                  color: T.pending.withValues(alpha: 0.35 + t * 0.4),
                  blurRadius: 6 + t * 8,
                  spreadRadius: 1 + t * 2,
                ),
              ],
            ),
            child: Text(
              '${widget.count}',
              style: T
                  .small(color: T.onPending, weight: FontWeight.w700)
                  .copyWith(fontSize: 11, height: 1),
            ),
          ),
        );
      },
    );
  }
}

/// Gentle gold breathing glow around a pending table or room chip (~1.6s,
/// calm heartbeat not a strobe) — purely a decorative shadow ring, doesn't
/// affect layout. Same pulse as [PendingBadge], just wrapping a whole shape.
class PulsingGlow extends StatefulWidget {
  final BorderRadius radius;
  final Widget child;
  const PulsingGlow({super.key, required this.radius, required this.child});

  @override
  State<PulsingGlow> createState() => _PulsingGlowState();
}

class _PulsingGlowState extends State<PulsingGlow>
    with SingleTickerProviderStateMixin {
  late final _ctrl = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1600),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _ctrl,
      builder: (context, child) {
        final t = Curves.easeInOut.transform(_ctrl.value);
        return Container(
          decoration: BoxDecoration(
            borderRadius: widget.radius,
            boxShadow: [
              BoxShadow(
                color: T.pending.withValues(alpha: 0.35 + t * 0.4),
                blurRadius: 8 + t * 10,
                spreadRadius: 1 + t * 2,
              ),
            ],
          ),
          child: child,
        );
      },
      child: widget.child,
    );
  }
}

/// 2-char abbreviation badge on a colored square — the no-photo fallback tile.
class AbbrevSquare extends StatelessWidget {
  final String abbrev;
  final double size;
  const AbbrevSquare(this.abbrev, {super.key, this.size = 48});

  // stable per-abbrev hue so tiles are tellable-apart at a glance
  Color get _color =>
      T.tilePalette[abbrev.hashCode.abs() % T.tilePalette.length];

  @override
  Widget build(BuildContext context) => Container(
    width: size,
    height: size,
    decoration: BoxDecoration(
      color: _color.withValues(alpha: .14),
      borderRadius: T.radiusSmall,
      border: Border.all(color: _color.withValues(alpha: .35)),
    ),
    alignment: Alignment.center,
    child: Text(
      abbrev,
      style: T.text(size: size * .38, weight: FontWeight.w700, color: _color),
    ),
  );
}

/// Numeric entry pad for cash amounts (CAD). Digits + 00 + backspace.
class AmountPad extends StatelessWidget {
  final void Function(String key) onKey;
  const AmountPad({super.key, required this.onKey});

  @override
  Widget build(BuildContext context) {
    Widget key(String label, {IconData? icon}) => Expanded(
      child: Padding(
        padding: const EdgeInsets.all(3),
        child: SizedBox(
          height: T.minTouch,
          child: OutlinedButton(
            onPressed: () => onKey(label),
            style: OutlinedButton.styleFrom(
              backgroundColor: T.surface,
              padding: EdgeInsets.zero,
            ),
            child: icon != null
                ? Icon(icon, size: 22, color: T.textPrimary)
                : Text(label, style: T.price(size: 22)),
          ),
        ),
      ),
    );

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (final row in const [
          ['1', '2', '3'],
          ['4', '5', '6'],
          ['7', '8', '9'],
        ])
          Row(children: [for (final d in row) key(d)]),
        Row(
          children: [
            key('00'),
            key('0'),
            key('⌫', icon: LucideIcons.delete),
          ],
        ),
      ],
    );
  }
}

/// Fetch-on-mount loading state: blank for the first 200ms, spinner after.
/// LAN fetches usually beat the delay — content appears with no flash.
class DelayedSpinner extends StatefulWidget {
  const DelayedSpinner({super.key});

  @override
  State<DelayedSpinner> createState() => _DelayedSpinnerState();
}

class _DelayedSpinnerState extends State<DelayedSpinner> {
  bool _visible = false;

  @override
  void initState() {
    super.initState();
    Future.delayed(const Duration(milliseconds: 200), () {
      if (mounted) setState(() => _visible = true);
    });
  }

  @override
  Widget build(BuildContext context) => _visible
      ? const Center(child: CircularProgressIndicator())
      : const SizedBox.expand();
}

/// Section header used by settings/reports ("PAYMENTS", "FEES"…).
class SectionLabel extends StatelessWidget {
  final String label;
  const SectionLabel(this.label, {super.key});
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 20, bottom: 8),
    child: Text(
      label.toUpperCase(),
      style: T
          .small(color: T.textMuted, weight: FontWeight.w700)
          .copyWith(letterSpacing: 1.2),
    ),
  );
}
