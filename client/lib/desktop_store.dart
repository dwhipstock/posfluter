import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'app_mode.dart';

/// The Windows counter app's own store: the same store the Android tablet
/// runs in-process, here as a child process (`store\pos-server-all.jar` on
/// the bundled Java runtime `store\runtime`, both beside the .exe).
///
/// Data lives under `%LOCALAPPDATA%\<brand>\` (pos.db, receipts, bills,
/// photos, store.log) with a `store.properties` beside it, same keys as the
/// tablet. The runner puts the app in a kill-on-close job object
/// (windows/runner/main.cpp), so the store stops with the app, even on a crash.
///
/// Only active when the bundled store is present: a plain `flutter run` on a
/// Windows dev machine still finds a store on the LAN like the Mac build.
class DesktopStore {
  DesktopStore._();

  static Process? _process;
  static String? _failure;
  static final List<String> _tail = [];

  static Directory get _bundleDir => File(Platform.resolvedExecutable).parent;

  static File get _java =>
      File('${_bundleDir.path}\\store\\runtime\\bin\\javaw.exe');
  static File get _jar => File('${_bundleDir.path}\\store\\pos-server-all.jar');

  static final bool enabled =
      !kIsWeb &&
      Platform.isWindows &&
      !AppMode.isStock &&
      !AppMode.isReader &&
      _java.existsSync() &&
      _jar.existsSync();

  /// The store each brand runs when store.properties names none (the Android
  /// brand table in android/app/build.gradle.kts).
  static String? get _defaultVenue => switch (AppMode.brand) {
    'sagepoppy' => 'sage-poppy',
    'pronghorn' => 'pronghorn',
    _ => null,
  };

  static Directory get dataDir {
    final base =
        Platform.environment['LOCALAPPDATA'] ??
        '${Platform.environment['USERPROFILE']}\\AppData\\Local';
    return Directory('$base\\${AppMode.brand}');
  }

  static const _template = '''
# Store settings for this Windows POS (same keys as the Android tablet).
# Edit, then close and reopen the app. Lines starting with # are ignored.

# Receipts: digital (no printer attached) or paper (a LAN receipt printer).
print.receipts=digital

# Which store this is (leave unset for this app's own store).
#store.venue=

# Fuel pumps (the pump simulator on another computer):
#forecourt.url=http://192.168.1.20:8086

# Card terminal: simulator | stripe | jpmorgan | external | off
#payment.terminal=simulator
#payment.terminal.host=192.168.1.20

#cash.rounding=nickel
#legal.age=21
#staff.app.mfa=on
#kitchen.printing=off
''';

  static Map<String, String> _readProps(File f) {
    final out = <String, String>{};
    if (!f.existsSync()) return out;
    for (final raw in f.readAsLinesSync()) {
      final line = raw.trim();
      if (line.isEmpty || line.startsWith('#') || line.startsWith('!')) {
        continue;
      }
      final i = line.indexOf(RegExp('[=:]'));
      if (i <= 0) continue;
      out[line.substring(0, i).trim()] = line.substring(i + 1).trim();
    }
    return out;
  }

  /// Start the store (no-op if it is already running or already answering).
  static Future<void> start() async {
    if (!enabled || _process != null) return;
    _failure = null;
    _tail.clear();
    try {
      final dir = dataDir..createSync(recursive: true);
      final propsFile = File('${dir.path}\\store.properties');
      if (!propsFile.existsSync()) propsFile.writeAsStringSync(_template);
      final props = _readProps(propsFile);
      final port = AppMode.embeddedStorePort;
      final venue = (props['store.venue']?.isNotEmpty ?? false)
          ? props['store.venue']
          : _defaultVenue;
      final env = <String, String>{
        'POS_PORT': '$port',
        'POS_DB': '${dir.path}\\pos.db',
        'POS_RECEIPTS_DIR': '${dir.path}\\receipts',
        'POS_BILLS_DIR': '${dir.path}\\bills',
        'POS_PHOTOS_DIR': '${dir.path}\\photos',
        // every other switch (print.receipts, payment.terminal*, cash.rounding,
        // staff.app.mfa, kitchen.printing, image.*) is read from this file
        'POS_CONFIG_FILE': propsFile.path,
        'POS_VENUE': ?venue,
        if (props['forecourt.url']?.isNotEmpty ?? false)
          'FORECOURT_URL': props['forecourt.url']!,
        if (props['legal.age']?.isNotEmpty ?? false)
          'POS_LEGAL_AGE': props['legal.age']!,
      };
      // a fresh log per app run; a restart appends, so the crash stays readable
      final log = File(
        '${dir.path}\\store.log',
      ).openWrite(mode: _logStarted ? FileMode.append : FileMode.write);
      _logStarted = true;
      final note = _pendingLogNote;
      _pendingLogNote = null;
      if (note != null) {
        log.writeln('[pos-app ${_now().toIso8601String()}] $note');
      }
      final p = await Process.start(
        _java.path,
        ['-Xmx768m', '-jar', _jar.path],
        workingDirectory: dir.path,
        environment: env,
      );
      _process = p;
      _answered = false;
      _watch();
      void capture(List<int> bytes) {
        log.add(bytes);
        final text = String.fromCharCodes(bytes);
        _tail.addAll(text.split('\n').where((l) => l.trim().isNotEmpty));
        if (_tail.length > 20) _tail.removeRange(0, _tail.length - 20);
      }

      p.stdout.listen(capture);
      p.stderr.listen(capture);
      unawaited(
        p.exitCode.then((code) async {
          await log.close();
          if (!identical(_process, p)) return; // stopped on purpose
          _process = null;
          final why =
              'store exited ($code): '
              '${_tail.isEmpty ? 'see store.log' : _tail.last.trim()}';
          if (!_scheduleRestart(why)) _failure = why;
        }),
      );
    } catch (e) {
      _process = null;
      _failure = 'could not start the store: $e';
    }
  }

  /// Why the store is not running, or null while it is (or is starting).
  static String? get startupFailure => _failure;

  static Future<void> stop() async {
    final p = _process;
    _process = null;
    _restartTimer?.cancel();
    _restartTimer = null;
    p?.kill();
    await p?.exitCode.timeout(const Duration(seconds: 5), onTimeout: () => -1);
  }

  static Future<void> restart() async {
    await stop();
    await start();
  }

  /// The reconnect screen's Retry: bring the store back if it is not running
  /// (or has stopped answering). A person asked, so no rate limit.
  static Future<void> ensureRunning() async {
    if (!enabled) return;
    if (_process == null) {
      _restartTimer?.cancel();
      _restartTimer = null;
      _pendingLogNote =
          'restarting the store (Retry pressed; it was not running)';
      await start();
    } else if (_policy.consecutiveHealthFailures > 0) {
      _pendingLogNote = 'restarting the store (Retry pressed; not answering)';
      await restart();
    }
  }

  // --- self-restart: the app keeps its own store alive -----------------------

  static final StoreRestartPolicy _policy = StoreRestartPolicy();
  static Timer? _restartTimer;
  static Timer? _healthTimer;
  static bool _checking = false;
  static bool _answered = false; // /health answered since this start
  static bool _logStarted = false;
  static String? _pendingLogNote;
  static DateTime _now() => DateTime.now();

  /// Queue a restart with backoff; false when the policy gives up (too many
  /// restarts in the last minute) — then the failure shows as before.
  static bool _scheduleRestart(String why) {
    final delay = _policy.nextRestartDelay(_now());
    if (delay == null) {
      _appendLog(
        'store keeps failing ($why); not restarting again this minute',
      );
      return false;
    }
    _pendingLogNote =
        'restarting the store in ${delay.inSeconds}s '
        '(attempt ${_policy.recentRestarts}): $why';
    debugPrint('[desktop-store] $_pendingLogNote');
    _restartTimer?.cancel();
    _restartTimer = Timer(delay, () {
      _restartTimer = null;
      start();
    });
    return true;
  }

  /// Every 5 s: GET /health. Three misses in a row (after it has answered
  /// once — the JVM start is slow) → kill it and let the exit handler restart.
  static void _watch() {
    _healthTimer ??= Timer.periodic(const Duration(seconds: 5), (_) async {
      final p = _process;
      if (p == null || _checking) return;
      _checking = true;
      try {
        final ok = await _healthy();
        if (!identical(p, _process)) return;
        if (ok) _answered = true;
        if (!_answered) return;
        if (_policy.recordHealth(ok)) {
          _appendLog('store stopped answering /health; killing it to restart');
          p.kill(ProcessSignal.sigkill);
        }
      } finally {
        _checking = false;
      }
    });
  }

  static Future<bool> _healthy() async {
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 3);
    try {
      final req = await client
          .getUrl(
            Uri.parse('http://127.0.0.1:${AppMode.embeddedStorePort}/health'),
          )
          .timeout(const Duration(seconds: 3));
      final res = await req.close().timeout(const Duration(seconds: 3));
      await res.drain<void>();
      return res.statusCode == 200;
    } catch (_) {
      return false;
    } finally {
      client.close(force: true);
    }
  }

  static void _appendLog(String line) {
    debugPrint('[desktop-store] $line');
    try {
      File('${dataDir.path}\\store.log').writeAsStringSync(
        '[pos-app ${_now().toIso8601String()}] $line\n',
        mode: FileMode.append,
      );
    } catch (_) {}
  }
}

/// When the Windows app restarts its own store: after it exits, or after
/// [healthFailureLimit] missed health checks in a row; with a growing delay
/// (1 s, 2 s, 4 s…), and at most [maxPerMinute] restarts in any minute.
class StoreRestartPolicy {
  StoreRestartPolicy({this.maxPerMinute = 5, this.healthFailureLimit = 3});

  final int maxPerMinute;
  final int healthFailureLimit;
  final List<DateTime> _restarts = [];
  int consecutiveHealthFailures = 0;

  int get recentRestarts => _restarts.length;

  /// A health check result; true when it is time to restart the store.
  bool recordHealth(bool ok) {
    if (ok) {
      consecutiveHealthFailures = 0;
      return false;
    }
    consecutiveHealthFailures++;
    if (consecutiveHealthFailures < healthFailureLimit) return false;
    consecutiveHealthFailures = 0;
    return true;
  }

  /// The wait before the next restart, or null when the store has already
  /// been restarted [maxPerMinute] times in the last minute.
  Duration? nextRestartDelay(DateTime now) {
    _restarts.removeWhere(
      (t) => now.difference(t) >= const Duration(minutes: 1),
    );
    if (_restarts.length >= maxPerMinute) return null;
    final delay = Duration(seconds: 1 << _restarts.length); // 1, 2, 4, 8, 16
    _restarts.add(now);
    consecutiveHealthFailures = 0;
    return delay;
  }
}
