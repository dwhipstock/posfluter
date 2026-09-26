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
/// The same define also drives the Android build (a separate application id
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
}
