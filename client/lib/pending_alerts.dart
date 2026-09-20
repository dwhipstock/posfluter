import 'dart:async';

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/foundation.dart';

import 'api.dart';

/// Drives the pending-order alert lifecycle off the existing zones poll:
///   - a chime the moment a new customer QR order arrives, and
///   - escalation (a recurring, increasingly insistent chime + a global
///     "orders waiting" banner) once the oldest un-actioned order has sat past
///     the venue's escalate window.
///
/// It never polls the server itself — [ingest] is fed by the zones-screen's 5s
/// poll, and order age is derived from the server's `created_at`, so it is
/// correct across an app restart (no client-only first-seen memory).
class PendingAlerts extends ChangeNotifier {
  // Venue config (from settings; refreshed on load/resume).
  bool enabled = true;
  int escalateAfterSeconds = 90;
  int volumePercent = 80;

  // Live state, recomputed each ingest.
  int _pendingCount = 0;
  DateTime? _oldestPendingAt;

  // Arrival baseline: null until the first ingest, so pre-existing orders on
  // launch establish the baseline instead of firing a chime storm.
  int? _lastSeenCount;
  DateTime? _lastEscalationChime;

  final AudioPlayer _player = AudioPlayer()..setReleaseMode(ReleaseMode.stop);

  int get pendingCount => _pendingCount;

  /// Age of the oldest un-actioned order, or null when nothing is pending.
  Duration? age(DateTime now) =>
      _oldestPendingAt == null ? null : now.difference(_oldestPendingAt!);

  /// Seconds the oldest order is *past* the escalate window (>=0 while escalating).
  int overdueSeconds(DateTime now) {
    final a = age(now);
    if (a == null) return 0;
    return a.inSeconds - escalateAfterSeconds;
  }

  /// True once the oldest order has sat past the escalate window — the banner
  /// (and recurring chime) are live. Computed live so a widget ticker can flip
  /// it on between polls; the chime still only fires on an [ingest] tick.
  bool bannerActive(DateTime now) =>
      enabled && _pendingCount > 0 && overdueSeconds(now) >= 0;

  /// Apply venue alert config. Disabling silences everything immediately.
  void configure(AlertConfig c) {
    enabled = c.pendingAlertsEnabled;
    escalateAfterSeconds = c.pendingAlertEscalateSeconds;
    volumePercent = c.pendingAlertVolume;
    if (!enabled) _lastEscalationChime = null;
    notifyListeners();
  }

  /// Fed by each successful zones poll. Detects arrivals and drives escalation.
  void ingest(List<Zone> zones, DateTime now) {
    var count = 0;
    DateTime? oldest;
    for (final z in zones) {
      for (final t in z.tables) {
        count += t.pendingCount;
        final raw = t.oldestPendingAt;
        if (raw != null) {
          final ts = DateTime.tryParse(raw);
          if (ts != null && (oldest == null || ts.isBefore(oldest))) {
            oldest = ts;
          }
        }
      }
    }
    _pendingCount = count;
    _oldestPendingAt = oldest;

    if (enabled) {
      // Arrival: total pending grew since the last poll. (Net-zero churn — one
      // accepted as one arrives — can miss, but escalation is the safety net.)
      if (_lastSeenCount != null && count > _lastSeenCount!) {
        _playChime(level: 0);
      }
      // Escalation: past the window, re-chime on a ~30s cadence, louder + more
      // repeats the longer it sits.
      if (bannerActive(now)) {
        if (_lastEscalationChime == null ||
            now.difference(_lastEscalationChime!).inSeconds >= 30) {
          _lastEscalationChime = now;
          _playChime(level: 1 + (overdueSeconds(now) ~/ 60));
        }
      } else {
        _lastEscalationChime = null;
      }
    }
    _lastSeenCount = count;
    notifyListeners();
  }

  /// level 0 = gentle arrival; higher = more insistent (louder, repeated).
  Future<void> _playChime({required int level}) async {
    if (volumePercent <= 0) return;
    final l = level.clamp(0, 3);
    final vol = (volumePercent / 100.0 * (0.6 + 0.4 * (l / 3))).clamp(0.0, 1.0);
    final repeats = 1 + l; // 1 on arrival → up to 4 when badly overdue
    try {
      await _player.setVolume(vol);
      for (var i = 0; i < repeats; i++) {
        await _player.play(AssetSource('chime.wav'));
        if (i < repeats - 1) {
          await Future.delayed(const Duration(milliseconds: 650));
        }
      }
    } catch (_) {
      // Audio must never break the poll loop (no device audio, focus loss, etc.).
    }
  }

  @override
  void dispose() {
    _player.dispose();
    super.dispose();
  }
}
