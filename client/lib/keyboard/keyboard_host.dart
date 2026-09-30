import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../design/tokens.dart';
import 'keyboard_view.dart';
import 'pos_keyboard.dart';

/// Docks the on-screen keyboard under the whole app (the MaterialApp
/// builder, so it sits above every route, dialog and sheet).
///
/// While it is up, its height is the bottom view inset — what a system
/// keyboard reports — so Scaffolds shrink, dialogs lift and bottom sheets
/// that pad by the inset stay usable; then the focused field is scrolled
/// into view.
class KeyboardHost extends StatefulWidget {
  const KeyboardHost({super.key, required this.control, required this.child});

  final PosKeyboardControl control;
  final Widget child;

  @override
  State<KeyboardHost> createState() => _KeyboardHostState();
}

class _KeyboardHostState extends State<KeyboardHost>
    with SingleTickerProviderStateMixin {
  late final AnimationController _slide = AnimationController(
    vsync: this,
    duration: T.dNormal,
    value: widget.control.visible.value ? 1 : 0,
  );

  @override
  void initState() {
    super.initState();
    widget.control.visible.addListener(_onVisible);
  }

  @override
  void didUpdateWidget(KeyboardHost old) {
    super.didUpdateWidget(old);
    if (old.control != widget.control) {
      old.control.visible.removeListener(_onVisible);
      widget.control.visible.addListener(_onVisible);
    }
  }

  @override
  void dispose() {
    widget.control.visible.removeListener(_onVisible);
    _slide.dispose();
    super.dispose();
  }

  void _onVisible() {
    final up = widget.control.visible.value;
    setState(() {});
    if (up) {
      _slide.forward();
      // the inset lands on the next frame; then bring the field above it
      WidgetsBinding.instance.addPostFrameCallback((_) => _revealFocus());
    } else {
      _slide.reverse();
    }
  }

  void _revealFocus() {
    if (!mounted || !widget.control.visible.value) return;
    final box = FocusManager.instance.primaryFocus?.context?.findRenderObject();
    if (box == null || !box.attached) return;
    box.showOnScreen(duration: T.dNormal, curve: T.ease);
  }

  @override
  Widget build(BuildContext context) {
    final mq = MediaQuery.of(context);
    final metrics = KeyboardMetrics.of(mq.size);
    final up = widget.control.visible.value;
    final inset = up ? metrics.height : 0.0;
    return Stack(
      fit: StackFit.expand,
      children: [
        MediaQuery(
          data: mq.copyWith(
            viewInsets: mq.viewInsets.copyWith(
              bottom: math.max(mq.viewInsets.bottom, inset),
            ),
            padding: mq.padding.copyWith(
              bottom: math.max(0.0, mq.padding.bottom - inset),
            ),
          ),
          child: widget.child,
        ),
        Positioned(
          left: 0,
          right: 0,
          bottom: 0,
          height: metrics.height,
          child: AnimatedBuilder(
            animation: _slide,
            builder: (context, child) => _slide.isDismissed
                ? const SizedBox.shrink()
                : FractionalTranslation(
                    translation: Offset(0, 1 - T.ease.transform(_slide.value)),
                    child: child,
                  ),
            // taps on the keyboard count as taps on the field: a desktop
            // TextField drops focus on any tap outside itself
            child: TextFieldTapRegion(
              child: KeyboardView(control: widget.control, metrics: metrics),
            ),
          ),
        ),
      ],
    );
  }
}
