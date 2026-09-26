import 'package:flutter/foundation.dart';

/// Which app this build is. One code base, two apps:
///
/// - the **terminal** (default): the counter tablet / desktop POS. On Android
///   it hosts the store itself (the embedded Ktor + SQLite service).
/// - the **stock app** (`--dart-define=POS_APP=stock`): a phone used in the
///   aisles to count and receive stock. It never runs a store; it finds the
///   store on the Wi-Fi like any LAN client, signs in with a staff PIN, and
///   lands on Count / Receive. Portrait, phone-sized, keeps its work on the
///   phone until the store has it.
///
/// The counter-tablet build also picks its **brand**
/// (`--dart-define=POS_BRAND=copperlantern|sagepoppy`, default copperlantern).
/// Each brand is its own Android app, so both can run on one tablet at once;
/// the only thing the Dart side needs from it is which loopback port that
/// app's embedded store listens on (8080 Copper Lantern, 8082 Sage & Poppy).
/// Everything else (name, theme, catalog) comes from the store itself.
///
/// The same defines also drive the Android build (a separate application id
/// and label, portrait, no store service), see android/app/build.gradle.kts.
/// An iPhone never hosts a store, so an iOS build IS the stock app unless it
/// asks for the terminal (`POS_APP=terminal`).
class AppMode {
  AppMode._();

  static const _define = String.fromEnvironment('POS_APP');

  /// True in the stock app. Settable for tests.
  static bool isStock =
      _define == 'stock' ||
      (_define.isEmpty &&
          !kIsWeb &&
          defaultTargetPlatform == TargetPlatform.iOS);

  static const _brandDefine = String.fromEnvironment('POS_BRAND');

  /// This build's brand: `copperlantern` (default) or `sagepoppy`.
  static const brand = _brandDefine == '' ? 'copperlantern' : _brandDefine;

  /// The port a counter-tablet build's embedded store listens on. Must match
  /// the brand table in android/app/build.gradle.kts.
  static int storePortFor(String brand) => brand == 'sagepoppy' ? 8082 : 8080;

  static final int embeddedStorePort = storePortFor(brand);

  /// The ports a store can listen on, for LAN discovery: Copper Lantern 8080,
  /// Sage & Poppy 8082. Both can run on one tablet; the stock app (count and
  /// receive is mostly a retail job) looks for Sage & Poppy first.
  static List<int> discoveryPorts({bool? stock}) =>
      (stock ?? isStock) ? const [8082, 8080] : const [8080, 8082];
}
