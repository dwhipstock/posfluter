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
      final log = File('${dir.path}\\store.log').openWrite();
      final p = await Process.start(
        _java.path,
        ['-Xmx768m', '-jar', _jar.path],
        workingDirectory: dir.path,
        environment: env,
      );
      _process = p;
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
          if (identical(_process, p)) {
            _process = null;
            _failure =
                'store exited ($code): '
                '${_tail.isEmpty ? 'see store.log' : _tail.last.trim()}';
          }
          await log.close();
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
    p?.kill();
    await p?.exitCode.timeout(const Duration(seconds: 5), onTimeout: () => -1);
  }

  static Future<void> restart() async {
    await stop();
    await start();
  }
}
