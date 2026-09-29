import 'dart:async';
import 'dart:math' as math;

import 'package:clock/clock.dart';
import 'package:flutter/material.dart';

import '../design/tokens.dart';
import '../i18n.dart';

/// A live "the AI is working" card for a request that can take anywhere from
/// a few seconds to about a minute: a spinner, a step label that advances
/// with elapsed time, a live elapsed-seconds counter, an estimated progress
/// bar (no real percentage exists — it eases toward ~90% of [expected], then
/// creeps slowly past that until the caller stops showing it), and a Cancel
/// button. Meant to sit centred over a dimmed canvas, not as a toast that can
/// look stuck on a 5–60s round trip.
class AiWorkingIndicator extends StatefulWidget {
  /// Roughly how long this kind of request usually takes — paces the
  /// progress bar and picks the "usually…" hint (~15s for a typed/spoken
  /// floor edit, ~40s for "set up from picture").
  final Duration expected;

  /// True while a voice clip is still being recorded (before anything was
  /// sent) — shows "Listening…" instead of the sending/thinking steps.
  final bool listening;
  final VoidCallback onCancel;
  const AiWorkingIndicator({
    super.key,
    required this.expected,
    this.listening = false,
    required this.onCancel,
  });

  @override
  State<AiWorkingIndicator> createState() => _AiWorkingIndicatorState();
}

class _AiWorkingIndicatorState extends State<AiWorkingIndicator> {
  // clock.now(), not DateTime.now() or a Stopwatch: both read the system
  // clock directly and ignore flutter_test's virtual clock, so the elapsed
  // timer would never move in a widget test; clock.now() is what it overrides.
  final _start = clock.now();
  Timer? _timer;
  int _seconds = 0;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) {
        setState(() => _seconds = clock.now().difference(_start).inSeconds);
      }
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  /// Eases 0 → 0.9 over [expected], then creeps slowly toward — but never
  /// reaches — 1: there's no real percentage, this only has to look alive.
  double get _progress {
    final total = widget.expected.inMilliseconds;
    final elapsed = clock.now().difference(_start).inMilliseconds;
    if (total <= 0) return 0.5;
    if (elapsed < total) {
      final t = elapsed / total;
      return 0.9 * (1 - math.pow(1 - t, 2));
    }
    final over = (elapsed - total) / total;
    return 0.9 + 0.09 * (1 - 1 / (1 + over));
  }

  String _step(L l) {
    if (widget.listening) return l.aiStepListening;
    if (_seconds < 2) return l.aiStepSending;
    if (_seconds >= 20) return l.aiStepAlmostDone;
    return l.aiStepThinking(widget.expected > const Duration(seconds: 20));
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return Container(
      key: const Key('ai-working'),
      width: 280,
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: T.surface,
        borderRadius: T.radiusLarge,
        border: Border.all(color: T.border),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: .18),
            blurRadius: 24,
            offset: const Offset(0, 10),
          ),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const SizedBox(
            width: 32,
            height: 32,
            child: CircularProgressIndicator(strokeWidth: 3),
          ),
          const SizedBox(height: 14),
          Text(
            _step(l),
            key: const Key('ai-working-step'),
            style: T.headline(),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 4),
          Text(
            '${_seconds}s',
            key: const Key('ai-working-elapsed'),
            style: T.small(color: T.textMuted),
          ),
          const SizedBox(height: 12),
          ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: LinearProgressIndicator(value: _progress, minHeight: 6),
          ),
          const SizedBox(height: 16),
          OutlinedButton(
            key: const Key('ai-working-cancel'),
            onPressed: widget.onCancel,
            child: Text(l.cancel),
          ),
        ],
      ),
    );
  }
}

/// [child], dimmed and unreachable with [AiWorkingIndicator] centred over it
/// while [active]; otherwise just [child]. [child] keeps its size either way
/// (a [Stack], not a replacement), so nothing reflows when the request ends.
class AiWorkingOverlay extends StatelessWidget {
  final bool active;
  final Duration expected;
  final bool listening;
  final VoidCallback onCancel;
  final Widget child;
  const AiWorkingOverlay({
    super.key,
    required this.active,
    required this.expected,
    this.listening = false,
    required this.onCancel,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        child,
        if (active) ...[
          Positioned.fill(
            child: IgnorePointer(
              ignoring: false,
              child: Container(color: Colors.black.withValues(alpha: .25)),
            ),
          ),
          Center(
            child: AiWorkingIndicator(
              expected: expected,
              listening: listening,
              onCancel: onCancel,
            ),
          ),
        ],
      ],
    );
  }
}
