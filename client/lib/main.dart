import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import 'api.dart';
import 'connection_monitor.dart';
import 'design/tokens.dart';
import 'i18n.dart';
import 'screens/login_screen.dart';
import 'screens/pairing_screen.dart';
import 'screens/zones_screen.dart';
import 'server_discovery.dart';

final GlobalKey<NavigatorState> rootNavigatorKey = GlobalKey<NavigatorState>();

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Kiosk mode: hide status + navigation bars everywhere; a swipe from the
  // edge peeks them and immersiveSticky re-hides them on its own — no
  // per-screen or on-resume re-assertion needed.
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  // A POS terminal must never sleep mid-shift. Re-asserted on every resume by
  // [_WakelockObserver] — Android can drop the lock while backgrounded.
  await WakelockPlus.enable();
  WidgetsBinding.instance.addObserver(_WakelockObserver());
  await Prefs.instance.load(); // device-level fallback until login hydrates
  await Api.loadServerConfig(); // manual override + last-discovered store URL
  // A saved address is a hint, not a lock. If it disappears while the app is
  // open, look for this restaurant on local Wi-Fi and move over automatically.
  ConnectionMonitor.findRestaurant = () async {
    final found = await ServerDiscovery.discover();
    if (found == null) return false;
    await Api.useDiscovered(found);
    return Api.probeHealth(Api.baseUrl);
  };
  // session expiry is not an error the user acknowledges: no toast, just
  // land on the login screen with the dead stack (and its dialogs) gone.
  Api.onSessionExpired = () {
    final nav = rootNavigatorKey.currentState;
    if (nav == null) return;
    nav.pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => const LoginScreen()),
      (_) => false,
    );
  };
  // same silent redirect when the store answers 401 device_required /
  // device_revoked: this terminal must (re)pair before anything else works.
  Api.onPairingRequired = () {
    final nav = rootNavigatorKey.currentState;
    if (nav == null) return;
    nav.pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => const PairingScreen()),
      (_) => false,
    );
  };
  // global "Reconnecting…" barrier, inserted on the root navigator's overlay
  // whenever the ConnectionMonitor trips (2 consecutive transport failures).
  ReconnectingOverlay.attach(rootNavigatorKey);
  // escape hatch after a sustained outage: route to the pairing/server-URL
  // screen so a wrong URL or a gone store can't input-lock the kiosk. The
  // pairing screen suppresses the overlay while it's up.
  ReconnectingOverlay.onEscape = () {
    final nav = rootNavigatorKey.currentState;
    if (nav == null) return;
    nav.pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => const PairingScreen()),
      (_) => false,
    );
  };
  runApp(const PosApp());
}

/// Re-enables the wakelock whenever the app returns to the foreground.
/// Registered once from [main]; never removed — it lives as long as the app.
class _WakelockObserver with WidgetsBindingObserver {
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) WakelockPlus.enable();
  }
}

/// Logs every modal route's push/pop with its barrier colour + dismissibility.
/// Kept as a permanent, low-noise diagnostic: forensic analysis of the
/// 2026-07-30 "frozen" capture showed the input-blocking dim was a
/// Colors.black54 modal-route barrier (alpha 138 → luminance ×0.458 — a
/// showDialog/showModalBottomSheet scrim), NOT the reconnect overlay. If a
/// terminal ever looks locked behind a dim again, this names the exact route
/// left up. Routes turn over rarely on a POS, so the log stays quiet. See
/// docs/known-issues/client-reconnect-freeze.md.
class _RouteBarrierLogger extends NavigatorObserver {
  void _log(String verb, Route<dynamic>? route) {
    if (route is ModalRoute && route.barrierColor != null) {
      debugPrint(
        '[route] $verb ${route.runtimeType} '
        'barrier=${route.barrierColor} dismissible=${route.barrierDismissible} '
        'name=${route.settings.name}',
      );
    }
  }

  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) =>
      _log('push', route);
  @override
  void didPop(Route<dynamic> route, Route<dynamic>? previousRoute) =>
      _log('pop', route);
  @override
  void didRemove(Route<dynamic> route, Route<dynamic>? previousRoute) =>
      _log('remove', route);
}

class PosApp extends StatelessWidget {
  const PosApp({super.key});

  @override
  Widget build(BuildContext context) {
    // prefsScope above MaterialApp: language/calendar toggles rebuild
    // every live route instantly, dialogs included.
    return prefsScope(
      child: MaterialApp(
        navigatorObservers: [_RouteBarrierLogger()],
        title: 'Copper Lantern POS',
        navigatorKey: rootNavigatorKey,
        theme: buildPosTheme(),
        home: const StartupGate(),
      ),
    );
  }
}

/// Restores a persisted session: → zones if valid, → login if not,
/// → full-screen retry if the server is unreachable.
class StartupGate extends StatefulWidget {
  const StartupGate({super.key});

  @override
  State<StartupGate> createState() => _StartupGateState();
}

class _StartupGateState extends State<StartupGate> {
  bool _checking = true;
  bool _connecting = false;

  // The connect poll is patient (60s window), but the person standing at the
  // terminal must never face a bare spinner with nothing to press: after a few
  // seconds the spinner grows "Retry now" / "Set server URL" so a wrong URL or
  // dead store is escapable immediately, not after the window expires.
  bool _showConnectActions = false;
  Timer? _actionsTimer;

  // Bumped by every (re)start of _check; a superseded attempt sees a newer
  // generation and abandons itself silently, so "Retry now" mid-poll can't
  // leave two connect loops racing each other.
  int _generation = 0;

  // A cold colima/Docker/Ktor boot takes far longer than a single probe: poll
  // health while the server comes up, and proceed the instant it answers.
  static const _connectWindow = Duration(seconds: 60);
  static const _probeTimeout = Duration(seconds: 2);
  static const _probeGap = Duration(milliseconds: 1500);
  static const _actionsAfter = Duration(seconds: 5);

  @override
  void initState() {
    super.initState();
    // The gate has its own retry/unreachable affordances — no global overlay.
    ConnectionMonitor.instance.pushSuppress();
    _check();
  }

  @override
  void dispose() {
    _actionsTimer?.cancel();
    ConnectionMonitor.instance.popSuppress();
    super.dispose();
  }

  Future<void> _check() async {
    final gen = ++_generation;
    setState(() {
      _checking = true;
    });
    try {
      await _ensureServer(gen);
      if (gen != _generation) return; // superseded by a manual retry
      // Pairing-required store + no stored device token → pair before login.
      // pairingRequired=false (or absent: pre-pairing LAN server) keeps the
      // existing restore-session flow untouched.
      if (await Api.checkPairingRequired(Api.baseUrl) &&
          !Api.hasDevicePairing) {
        _replaceWith(const PairingScreen());
        return;
      }
      final user = await Api.restoreSession();
      if (!mounted) return;
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(
          builder: (_) =>
              user != null ? const ZonesScreen() : const LoginScreen(),
        ),
      );
    } on PairingRequiredException {
      // stored device token no longer accepted (revoked → already wiped)
      _replaceWith(const PairingScreen());
    } catch (_) {
      if (mounted && gen == _generation) {
        setState(() => _checking = false);
      }
    }
  }

  void _replaceWith(Widget screen) {
    if (!mounted) return;
    Navigator.of(
      context,
    ).pushReplacement(MaterialPageRoute(builder: (_) => screen));
  }

  /// Locate the store server before we try to use it, polling until it answers
  /// so a still-booting server doesn't fail the launch. A manual override is
  /// polled directly; otherwise we probe the best-guess URL and, failing that,
  /// scan the LAN — all inside a bounded retry window. Throws only once the
  /// window elapses without a live server, surfacing the "unreachable" screen.
  Future<void> _ensureServer(int gen) async {
    if (mounted) {
      setState(() {
        _connecting = true;
        _showConnectActions = false;
      });
    }
    _actionsTimer?.cancel();
    _actionsTimer = Timer(_actionsAfter, () {
      if (mounted && gen == _generation) {
        setState(() => _showConnectActions = true);
      }
    });
    try {
      final deadline = DateTime.now().add(_connectWindow);
      while (true) {
        if (gen != _generation) return; // a manual retry took over
        if (await Api.probeHealth(Api.baseUrl, timeout: _probeTimeout)) return;
        // The saved address is only a hint. Always search local Wi-Fi when it
        // fails; this lets an old cloud/demo address heal itself with no form.
        final found = await ServerDiscovery.discover();
        if (found != null) {
          await Api.useDiscovered(found);
          if (await Api.probeHealth(Api.baseUrl, timeout: _probeTimeout)) {
            return;
          }
        }
        if (DateTime.now().isAfter(deadline)) {
          throw TimeoutException('server did not respond', _connectWindow);
        }
        await Future<void>.delayed(_probeGap);
      }
    } finally {
      _actionsTimer?.cancel();
      if (mounted && gen == _generation) setState(() => _connecting = false);
    }
  }

  /// Manual "Set server URL" fallback for the offline screen — when discovery
  /// can't find it (odd subnet, mDNS blocked), let the user type it in.
  Future<void> _enterServerUrl() async {
    final l = L.of(context);
    final controller = TextEditingController(
      text: Api.serverUrlOverride ?? Api.baseUrl,
    );
    final url = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l.setServerUrl),
        content: TextField(
          controller: controller,
          autofocus: true,
          keyboardType: TextInputType.url,
          decoration: const InputDecoration(
            hintText: 'http://192.168.1.x:8080',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: Text(l.cancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, controller.text),
            child: Text(l.ok),
          ),
        ],
      ),
    );
    if (url == null) return;
    await Api.setServerUrlOverride(url);
    _check();
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return Scaffold(
      body: Center(
        child: _checking
            ? Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const CircularProgressIndicator(),
                  if (_connecting) ...[
                    const SizedBox(height: 16),
                    Text(l.findingRestaurant, style: T.small()),
                    const SizedBox(height: 12),
                    // Never trap the kiosk on the spinner: an immediate escape to
                    // reconfigure the server URL (e.g. after switching networks).
                    // _enterServerUrl restarts _check(), bumping _generation and
                    // abandoning this connect-loop.
                    TextButton.icon(
                      icon: const Icon(LucideIcons.pencil),
                      label: Text(l.connectionHelp),
                      onPressed: _enterServerUrl,
                    ),
                  ],
                  // A slow connect must never be a dead spinner: after a few
                  // seconds also surface an explicit retry and show which URL
                  // we're stuck on (the Set-server-URL escape above is already
                  // available from the first frame).
                  if (_connecting && _showConnectActions) ...[
                    const SizedBox(height: 8),
                    const SizedBox(height: 8),
                    FilledButton.icon(
                      icon: const Icon(LucideIcons.refreshCw),
                      label: Text(l.retry),
                      onPressed: _check,
                    ),
                  ],
                ],
              )
            : Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Icon(
                    LucideIcons.cloudOff,
                    size: 56,
                    color: T.textMuted,
                  ),
                  const SizedBox(height: 12),
                  Text(l.restaurantUnavailable),
                  const SizedBox(height: 16),
                  FilledButton.icon(
                    icon: const Icon(LucideIcons.refreshCw),
                    label: Text(l.retry),
                    onPressed: _check,
                  ),
                  const SizedBox(height: 8),
                  TextButton.icon(
                    icon: const Icon(LucideIcons.pencil),
                    label: Text(l.connectionHelp),
                    onPressed: _enterServerUrl,
                  ),
                ],
              ),
      ),
    );
  }
}
