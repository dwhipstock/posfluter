import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/skin.dart';
import '../design/tokens.dart';
import '../i18n.dart';

/// Numeric PIN pad; calls [onComplete] when [length] digits are entered.
class PinPad extends StatefulWidget {
  final int length;
  final void Function(String pin) onComplete;

  /// Key size; the sign-in screen passes a larger one for the tablet.
  final Size keySize;
  const PinPad({
    super.key,
    this.length = 4,
    required this.onComplete,
    this.keySize = const Size(84, T.minTouch + 8),
  });

  @override
  State<PinPad> createState() => PinPadState();
}

class PinPadState extends State<PinPad> {
  String _pin = '';

  void clear() => setState(() => _pin = '');

  void _tap(String digit) {
    if (_pin.length >= widget.length) return;
    setState(() => _pin += digit);
    if (_pin.length == widget.length) {
      final pin = _pin;
      // let the last dot render before submitting
      WidgetsBinding.instance.addPostFrameCallback(
        (_) => widget.onComplete(pin),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final big = widget.keySize.height >= 72;
    // the pubs: navy on cream, as always; a store with its own brand wears its theme
    final branded = StoreProfile.current.isSagePoppy;
    final scheme = Theme.of(context).colorScheme;
    final ink = branded ? scheme.primary : T.navy;
    final keyBg = branded ? scheme.surface : T.surface;
    final gap = big ? 12.0 : 8.0;
    // a skin with pill controls (Sage & Poppy) gets round, flat keys
    final skin = BrandSkin.of(context);
    if (skin.pillControls) return _round(context, skin, ink, big);
    Widget key(String label, {Widget? child, VoidCallback? onTap}) => SizedBox(
      width: widget.keySize.width,
      height: widget.keySize.height,
      child: label.isEmpty && child == null
          ? const SizedBox()
          : DecoratedBox(
              decoration: BoxDecoration(
                borderRadius: T.radiusMedium,
                boxShadow: big ? T.raised : null,
              ),
              child: OutlinedButton(
                onPressed: onTap ?? () => _tap(label),
                style: OutlinedButton.styleFrom(
                  backgroundColor: keyBg,
                  foregroundColor: ink,
                  padding: EdgeInsets.zero,
                ),
                child:
                    child ??
                    Text(
                      label,
                      style: T.price(
                        size: big ? 30 : 24,
                        weight: FontWeight.w600,
                        color: ink,
                      ),
                    ),
              ),
            ),
    );

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            for (var i = 0; i < widget.length; i++)
              AnimatedContainer(
                duration: T.dFast,
                curve: T.ease,
                width: big ? 18 : 14,
                height: big ? 18 : 14,
                margin: EdgeInsets.all(big ? 8 : 6),
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: i < _pin.length ? ink : keyBg,
                  border: Border.all(
                    color: i < _pin.length ? ink : T.textMuted,
                    width: 1.5,
                  ),
                ),
              ),
          ],
        ),
        SizedBox(height: big ? 20 : 16),
        for (final row in const [
          ['1', '2', '3'],
          ['4', '5', '6'],
          ['7', '8', '9'],
        ])
          Padding(
            padding: EdgeInsets.only(bottom: gap),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              spacing: gap,
              children: [for (final digit in row) key(digit)],
            ),
          ),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          spacing: gap,
          children: [
            key(''),
            key('0'),
            key(
              '',
              child: Icon(
                LucideIcons.delete,
                size: big ? 28 : 22,
                color: T.textMuted,
              ),
              onTap: () => setState(
                () => _pin = _pin.isEmpty
                    ? ''
                    : _pin.substring(0, _pin.length - 1),
              ),
            ),
          ],
        ),
      ],
    );
  }

  void _back() => setState(
    () => _pin = _pin.isEmpty ? '' : _pin.substring(0, _pin.length - 1),
  );

  /// Round keys on a soft tint, a segmented PIN indicator: the pill-shaped
  /// skins' pad (the pubs keep their raised square keys).
  Widget _round(BuildContext context, BrandSkin skin, Color ink, bool big) {
    final scheme = Theme.of(context).colorScheme;
    final d = big ? widget.keySize.height + 6 : widget.keySize.height;
    final gap = big ? 16.0 : 10.0;
    Widget key(String label, {Widget? child, VoidCallback? onTap}) => SizedBox(
      width: d,
      height: d,
      child: label.isEmpty && child == null
          ? const SizedBox()
          : Material(
              color: child == null
                  ? scheme.surfaceContainerHigh
                  : Colors.transparent,
              shape: const CircleBorder(),
              clipBehavior: Clip.antiAlias,
              child: InkWell(
                onTap: onTap ?? () => _tap(label),
                child: Center(
                  child:
                      child ??
                      Text(
                        label,
                        style: skin.figures(
                          size: big ? 32 : 24,
                          weight: FontWeight.w700,
                          color: scheme.onSurface,
                        ),
                      ),
                ),
              ),
            ),
    );
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            for (var i = 0; i < widget.length; i++)
              AnimatedContainer(
                duration: skin.fast,
                curve: skin.ease,
                width: i < _pin.length ? (big ? 34 : 26) : (big ? 22 : 18),
                height: big ? 10 : 8,
                margin: EdgeInsets.symmetric(horizontal: big ? 5 : 4),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(10),
                  color: i < _pin.length ? ink : scheme.outline,
                ),
              ),
          ],
        ),
        SizedBox(height: big ? 28 : 18),
        for (final row in const [
          ['1', '2', '3'],
          ['4', '5', '6'],
          ['7', '8', '9'],
        ])
          Padding(
            padding: EdgeInsets.only(bottom: gap),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              spacing: gap * 1.5,
              children: [for (final digit in row) key(digit)],
            ),
          ),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          spacing: gap * 1.5,
          children: [
            key(''),
            key('0'),
            key(
              '',
              child: Icon(
                skin.glyphs.backspace,
                size: big ? 28 : 22,
                color: scheme.onSurfaceVariant,
              ),
              onTap: _back,
            ),
          ],
        ),
      ],
    );
  }
}

/// Manager-approval modal: floating card, plainly states the action being
/// approved, PIN pad below. The PIN is verified inline — a typo shows in the
/// modal and lets the manager retry without losing the action context. The
/// returned PIN travels with the action, which re-verifies it server-side.
Future<String?> askManagerPin(
  BuildContext context, {
  String? title,
  String? permission,
}) {
  return showDialog<String>(
    context: context,
    builder: (context) =>
        _ManagerPinDialog(title: title, permission: permission),
  );
}

/// The outcome of a grant gate: proceed, carrying an optional approving PIN.
class Approval {
  const Approval(this.managerPin);

  /// null = the acting user already holds the grant (no manager PIN was needed).
  final String? managerPin;
}

/// Grant gate (CONTRACT §7): if the logged-in user already holds [permission],
/// proceed straight away (no PIN); otherwise fall back to the manager-PIN modal.
/// Returns null when the user cancels or no approver is available. Pass the
/// returned [Approval.managerPin] to the action — the server re-enforces either way.
Future<Approval?> requireGrant(
  BuildContext context,
  String permission, {
  String? title,
}) async {
  if (Api.currentUser?.can(permission) ?? false) return const Approval(null);
  final pin = await askManagerPin(
    context,
    title: title,
    permission: permission,
  );
  return pin == null ? null : Approval(pin);
}

class _ManagerPinDialog extends StatefulWidget {
  final String? title;
  final String? permission;
  const _ManagerPinDialog({this.title, this.permission});

  @override
  State<_ManagerPinDialog> createState() => _ManagerPinDialogState();
}

class _ManagerPinDialogState extends State<_ManagerPinDialog> {
  final _padKey = GlobalKey<PinPadState>();
  String? _error;
  bool _busy = false;

  Future<void> _verify(String pin) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await Api.verifyManagerPin(pin, permission: widget.permission);
      if (mounted) Navigator.pop(context, pin);
    } catch (e) {
      if (!mounted) return;
      if (e is SessionExpiredException) return; // redirect is in flight
      setState(() {
        _busy = false;
        _error = '$e';
      });
      _padKey.currentState?.clear();
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return Dialog(
      child: SizedBox(
        width: 380, // floating card, not a full-width sheet
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 20, 24, 12),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(
                    LucideIcons.shieldCheck,
                    size: 20,
                    color: T.attention,
                  ),
                  const SizedBox(width: 8),
                  Text(
                    l.managerApproval,
                    style: T.small(color: T.attention, weight: FontWeight.w700),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              // the action being approved, stated plainly
              Text(
                widget.title ?? l.managerPinTitle,
                textAlign: TextAlign.center,
                style: T.text(weight: FontWeight.w600),
              ),
              SizedBox(
                height: 28,
                child: Center(
                  child: _busy
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : _error != null
                      ? Text(
                          _error!,
                          style: T.small(color: T.destructive),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        )
                      : const SizedBox(),
                ),
              ),
              const SizedBox(height: 2),
              AbsorbPointer(
                absorbing: _busy,
                child: PinPad(key: _padKey, onComplete: _verify),
              ),
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: Text(l.cancel),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
