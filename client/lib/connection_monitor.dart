import 'dart:async';

import 'package:flutter/material.dart';

import 'api.dart';
import 'design/tokens.dart';
import 'i18n.dart';

/// Global "is the store server reachable?" state, fed by [Api]'s HTTP layer.
///
/// Transport failures (socket / timeout / client) count; HTTP error statuses
/// do NOT — a 4xx/5xx proves the server is alive. After two consecutive
/// failures the monitor goes offline and probes GET /health every 3s (one
/// timer, ticks skipped while a probe is in flight — no pile-up). The first
/// successful probe (or any successful API call) flips it back online and
/// notifies listeners, so screens with their own polls refresh right away.
class ConnectionMonitor extends ChangeNotifier {
  ConnectionMonitor._();
  static final ConnectionMonitor instance = ConnectionMonitor._();

  static const _failureThreshold = 2;
  static const _probeInterval = Duration(seconds: 3);
  static const _probeTimeout = Duration(seconds: 2);

  /// Installed by main.dart to find the restaurant on local Wi-Fi. Kept as a
  /// callback so this low-level monitor does not own discovery or navigation.
  static Future<bool> Function()? findRestaurant;

  int _consecutiveFailures = 0;
  bool _offline = false;
  Timer? _probeTimer;
  bool _probing = false;
  int _suppressCount = 0;
  int _reconnectCount = 0;

  /// True while the server is unreachable (the overlay's trigger).
  bool get offline => _offline;

  /// True while a screen with its own connectivity affordances (pairing,
  /// startup gate) is up — the overlay stays away no matter what.
  bool get suppressed => _suppressCount > 0;

  /// Bumps once per recovery — listeners can key an immediate refresh off it.
  int get reconnectCount => _reconnectCount;

  /// Back to a clean online state (tests only): cancels the probe timer so no
  /// pending-timer assertions trip at teardown.
  @visibleForTesting
  void debugReset() {
    _probeTimer?.cancel();
    _probeTimer = null;
    _offline = false;
    _probing = false;
    _consecutiveFailures = 0;
    _suppressCount = 0;
  }

  /// Called by [Api] after any completed HTTP exchange.
  void reportSuccess() {
    _consecutiveFailures = 0;
    if (_offline) _goOnline();
  }

  /// Called by [Api] on a transport-level failure (never on HTTP statuses).
  void reportFailure() {
    if (_offline) return;
    _consecutiveFailures++;
    if (_consecutiveFailures >= _failureThreshold) _goOffline();
  }

  /// Counted (not boolean) so overlapping screen lifecycles — new route's
  /// initState before the old route's dispose — can't unsuppress too early.
  void pushSuppress() {
    _suppressCount++;
    notifyListeners();
  }

  void popSuppress() {
    if (_suppressCount > 0) _suppressCount--;
    notifyListeners();
  }

  void _goOffline() {
    _offline = true;
    _probeTimer?.cancel();
    _probeTimer = Timer.periodic(_probeInterval, (_) => _probe());
    notifyListeners();
  }

  void _goOnline() {
    _offline = false;
    _consecutiveFailures = 0;
    _probeTimer?.cancel();
    _probeTimer = null;
    _reconnectCount++;
    notifyListeners();
  }

  Future<void> _probe() async {
    if (_probing) return; // a slow probe must not stack behind the timer
    _probing = true;
    try {
      if (await Api.probeHealth(Api.baseUrl, timeout: _probeTimeout)) {
        if (_offline) _goOnline();
        return;
      }
      final find = findRestaurant;
      if (find != null && await find()) {
        if (_offline) _goOnline();
      }
    } finally {
      _probing = false;
    }
  }

  /// Manual "Try again": probe right now instead of waiting out the timer.
  /// Skipped (not queued) if an automatic probe is already in flight.
  Future<void> probeNow() => _probe();
}

/// Inserts/removes the full-screen "Reconnecting…" barrier on the root
/// navigator's overlay as [ConnectionMonitor] flips. Attach once from main().
/// Removal is automatic on recovery — no user action involved.
///
/// Teardown is deliberately redundant (a kiosk must never stay input-locked):
/// every state change is applied via a post-frame callback AND a short
/// fallback timer, and a 2s watchdog reconciles desired-vs-actual forever.
/// Timers run off the frame pipeline, so even a wedged/idle frame scheduler
/// can't strand the barrier on screen after the monitor recovers.
class ReconnectingOverlay {
  ReconnectingOverlay._();

  static OverlayEntry? _entry;
  static GlobalKey<NavigatorState>? _navKey;
  static Timer? _watchdog;

  /// A state change was notified but not yet applied. Cleared by [_apply].
  static bool _dirty = false;

  /// Test seam: drop the post-frame path so tests can prove the timer/watchdog
  /// path alone recovers (regression guard for the frame-starvation freeze).
  @visibleForTesting
  static bool debugDropPostFrameSync = false;

  /// Wired by main.dart: route to the pairing/server-URL screen. The overlay
  /// offers this as an escape hatch after a SUSTAINED outage so a wrong venue
  /// URL or a permanently-gone store doesn't input-lock a kiosk terminal until
  /// someone force-restarts the app. Null → no button (nowhere to send them).
  static void Function()? onEscape;

  static void attach(GlobalKey<NavigatorState> navKey) {
    _navKey = navKey;
    ConnectionMonitor.instance.addListener(_sync);
    _watchdog?.cancel();
    _watchdog = Timer.periodic(const Duration(seconds: 2), (_) => _reconcile());
  }

  /// Detach the listener/watchdog and drop any barrier (tests only — the real
  /// app attaches once and keeps it for the process lifetime).
  @visibleForTesting
  static void detach() {
    ConnectionMonitor.instance.removeListener(_sync);
    _watchdog?.cancel();
    _watchdog = null;
    _entry?.remove();
    _entry = null;
    _dirty = false;
  }

  static void _sync() {
    // Never touch the overlay synchronously from a notification — it can fire
    // from initState/dispose mid-build. Apply after the frame; a 600ms timer
    // backstops it in case no frame is ever produced (timers run off the frame
    // pipeline, so a stalled scheduler can't strand the input-blocking barrier).
    _dirty = true;
    if (!debugDropPostFrameSync) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _apply());
      WidgetsBinding.instance.ensureVisualUpdate(); // run even when idle
    }
    Timer(const Duration(milliseconds: 600), _apply);
  }

  /// Watchdog: if a notification went unapplied, or desired/actual drifted for
  /// any reason at all (dropped callback, throwing listener, overlay not built
  /// yet at insert time), heal it and force a frame so it becomes visible.
  static void _reconcile() {
    final m = ConnectionMonitor.instance;
    final want = m.offline && !m.suppressed;
    if (!_dirty && want == (_entry != null)) return;
    debugPrint(
      '[reconnect-overlay] watchdog reconcile '
      '(dirty=$_dirty want=$want have=${_entry != null})',
    );
    _apply();
    WidgetsBinding.instance.scheduleForcedFrame();
  }

  static void _apply() {
    _dirty = false;
    final m = ConnectionMonitor.instance;
    final want = m.offline && !m.suppressed;
    if (want && _entry == null) {
      final overlay = _navKey?.currentState?.overlay;
      if (overlay == null) return; // navigator not built yet — watchdog retries
      _entry = OverlayEntry(builder: (_) => const _ReconnectingBarrier());
      overlay.insert(_entry!);
      debugPrint('[reconnect-overlay] barrier INSERTED');
    } else if (!want && _entry != null) {
      _entry!.remove();
      _entry = null;
      debugPrint('[reconnect-overlay] barrier REMOVED');
      WidgetsBinding.instance.ensureVisualUpdate();
    }
  }
}

/// Dark scrim + a plain-language local restaurant connection status.
/// Blocks all input while the server is away (a POS must not queue taps into a
/// dead connection); disappears on its own the moment /health answers. After a
/// sustained outage it reveals a "change server" escape so a wrong URL / gone
/// store can't lock a kiosk terminal until an app restart.
class _ReconnectingBarrier extends StatefulWidget {
  const _ReconnectingBarrier();

  @override
  State<_ReconnectingBarrier> createState() => _ReconnectingBarrierState();
}

class _ReconnectingBarrierState extends State<_ReconnectingBarrier> {
  // long enough that a normal blip (router reboot, brief Wi-Fi drop) recovers
  // silently and staff never see the button — only a real stuck state does
  static const _escapeAfter = Duration(seconds: 20);
  Timer? _timer;
  bool _showEscape = false;

  @override
  void initState() {
    super.initState();
    if (ReconnectingOverlay.onEscape != null) {
      _timer = Timer(_escapeAfter, () {
        if (mounted) setState(() => _showEscape = true);
      });
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return Stack(
      children: [
        const ModalBarrier(dismissible: false, color: Color(0xCC000000)),
        Center(
          child: Material(
            type: MaterialType.transparency,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const CircularProgressIndicator(),
                const SizedBox(height: 20),
                Text(
                  l.reconnectingToRestaurant,
                  style: T.text(weight: FontWeight.w600),
                ),
                const SizedBox(height: 20),
                // auto-probes run every 3s anyway, but staff need a button to
                // press — a barrier with no affordance reads as a hang
                FilledButton(
                  onPressed: () => ConnectionMonitor.instance.probeNow(),
                  child: Text(l.retry),
                ),
                if (_showEscape) ...[
                  const SizedBox(height: 28),
                  TextButton(
                    onPressed: ReconnectingOverlay.onEscape,
                    child: Text(l.connectionHelp),
                  ),
                ],
              ],
            ),
          ),
        ),
      ],
    );
  }
}
