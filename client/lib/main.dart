import 'dart:async';

import 'package:flutter/foundation.dart';
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
const _storeChannel = MethodChannel('dev.dwhipstock.pos_client/store');

class _EmbeddedStoreStartupException implements Exception {
  const _EmbeddedStoreStartupException(this.message);
  final String message;
}

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // bundled fonts ship under the SIL Open Font License; list it in-app
  LicenseRegistry.addLicense(() async* {
    for (final (family, file) in const [
      ('Inter', 'OFL-Inter.txt'),
      ('Noto Sans', 'OFL-NotoSans.txt'),
    ]) {
      yield LicenseEntryWithLineBreaks([
        family,
      ], await rootBundle.loadString('assets/fonts/$file'));
    }
  });
  // Kiosk mode: hide status + navigation bars everywhere; a swipe from the
  // edge peeks them and immersiveSticky re-hides them on its own — no
  // per-screen or on-resume re-assertion needed.
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  // The counter tablet is used in landscape (either way up); the layouts are
  // designed for it. The manifest locks the activity the same way.
  await SystemChrome.setPreferredOrientations(const [
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);
  // A POS terminal must never sleep mid-shift. Re-asserted on every resume by
  // [_WakelockObserver] — Android can drop the lock while backgrounded.
  await WakelockPlus.enable();
  WidgetsBinding.instance.addObserver(_WakelockObserver());
  await Prefs.instance.load(); // device-level fallback until login hydrates
  await Api.loadServerConfig(); // manual override + last-discovered store URL
  // A saved address is a hint, not a lock. If it disappears while the app is
  // open, look for this restaurant on local Wi-Fi and move over automatically.
  ConnectionMonitor.findRestaurant = () async {
    if (Api.usesEmbeddedStore) return false;
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
    // The on-site service explicitly runs without device pairing. A stale
    // cloud response must never strand a LAN terminal in the pairing form.
    final screen = Api.isLocalVenueUrl(Api.baseUrl)
        ? const LoginScreen()
        : const PairingScreen();
    nav.pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => screen),
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
    final screen = Api.isLocalVenueUrl(Api.baseUrl)
        ? const StartupGate()
        : const PairingScreen();
    nav.pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => screen),
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
    // prefsScope above MaterialApp: the language toggle rebuilds
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

/// Finds the on-site restaurant and restores a persisted session. Only the
/// local restaurant gates startup; cloud sync/reporting is server-side and
/// never participates in this path.
class StartupGate extends StatefulWidget {
  const StartupGate({super.key});

  @override
  State<StartupGate> createState() => _StartupGateState();
}

class _StartupGateState extends State<StartupGate> {
  bool _connecting = false;
  String? _startupError;

  // Automatic discovery never gives up. Troubleshooting appears after a few
  // seconds, but recovery never depends on somebody pressing a retry button.
  bool _showConnectActions = false;
  Timer? _actionsTimer;

  // Bumped by every (re)start of _check; a superseded attempt sees a newer
  // generation and abandons itself silently, so "Retry now" mid-poll can't
  // leave two connect loops racing each other.
  int _generation = 0;

  // A cold colima/Docker/Ktor boot takes far longer than a single probe: poll
  // health while the server comes up, and proceed the instant it answers.
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
    if (mounted) setState(() => _startupError = null);
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
    } on _EmbeddedStoreStartupException catch (e) {
      if (mounted && gen == _generation) {
        setState(() {
          _connecting = false;
          _startupError = e.message;
        });
      }
    } on PairingRequiredException {
      // stored device token no longer accepted (revoked → already wiped)
      _replaceWith(const PairingScreen());
    } catch (e) {
      // Local health already succeeded. A stale/corrupt saved session or a
      // one-off secure-storage failure must not strand the terminal at startup;
      // the login screen can establish a fresh session against the local store.
      debugPrint('[startup] session restore skipped: $e');
      if (mounted && gen == _generation) _replaceWith(const LoginScreen());
    }
  }

  void _replaceWith(Widget screen) {
    if (!mounted) return;
    Navigator.of(
      context,
    ).pushReplacement(MaterialPageRoute(builder: (_) => screen));
  }

  /// Locate the on-site restaurant before use. A cached LAN address is a fast
  /// path; remote/cloud addresses are deliberately ignored. Discovery retries
  /// forever so a booting Mac, Wi-Fi handoff, or brief keystore failure heals
  /// without leaving a dead screen that needs a person to press Retry.
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
      while (true) {
        if (gen != _generation) return; // a manual retry took over
        try {
          if (Api.usesEmbeddedStore) {
            if (await Api.probeHealth(
              Api.embeddedStoreUrl,
              timeout: _probeTimeout,
            )) {
              return;
            }
            final failure = await _storeChannel.invokeMethod<String>(
              'startupFailure',
            );
            if (failure != null) throw _EmbeddedStoreStartupException(failure);
            await Future<void>.delayed(_probeGap);
            continue;
          }
          if (Api.isLocalVenueUrl(Api.baseUrl) &&
              await Api.probeHealth(Api.baseUrl, timeout: _probeTimeout)) {
            return;
          }
          final found = await ServerDiscovery.discover();
          if (found != null) {
            await Api.useDiscovered(found);
            if (await Api.probeHealth(Api.baseUrl, timeout: _probeTimeout)) {
              return;
            }
          }
        } on _EmbeddedStoreStartupException {
          rethrow;
        } catch (e) {
          // Discovery/persistence is best-effort and retried below. Never turn
          // a transient platform failure into a terminal startup state.
          debugPrint('[startup] local discovery retry: $e');
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
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            if (_startupError == null) const CircularProgressIndicator(),
            if (_connecting) ...[
              const SizedBox(height: 16),
              Text(
                Api.usesEmbeddedStore
                    ? l.startingThisTablet
                    : l.findingRestaurant,
                style: T.small(),
              ),
            ],
            if (_startupError != null) ...[
              const SizedBox(height: 16),
              Text(l.tabletStoreFailed, style: T.small()),
              Text(_startupError!, style: T.small()),
              TextButton(
                onPressed: () async {
                  await _storeChannel.invokeMethod<void>('restart');
                  _check();
                },
                child: Text(l.retry),
              ),
            ],
            if (_connecting &&
                _showConnectActions &&
                !Api.usesEmbeddedStore) ...[
              const SizedBox(height: 16),
              TextButton.icon(
                icon: const Icon(LucideIcons.wifi),
                label: Text(l.connectionHelp),
                onPressed: _enterServerUrl,
              ),
            ],
          ],
        ),
      ),
    );
  }
}
