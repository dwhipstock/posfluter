import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Diagnostics for the Card (Stripe) flow, visible in release builds:
///   adb logcat -s StripeTerminal flutter | grep -i stripe
/// Every line goes to logcat twice: `flutter` tag via debugPrint ("[stripe] …")
/// and `StripeTerminal` via the native Log.w bridge in MainActivity.
/// Secrets (connection tokens, client secrets, API keys) are masked first.
const _channel = MethodChannel('dev.dwhipstock.pos_client/store');

final _secrets = [
  RegExp(r'\b(sk|rk|pk)_(test|live)_[A-Za-z0-9]+'),
  RegExp(r'\bpst_(test|live)_[A-Za-z0-9_]+'),
  RegExp(r'\b(pi|seti)_[A-Za-z0-9]+_secret_[A-Za-z0-9]+'),
];

String scrubStripe(String s) {
  var out = s;
  for (final r in _secrets) {
    out = out.replaceAllMapped(r, (m) {
      final v = m[0]!;
      final cut = v.indexOf('_secret_');
      if (cut > 0) return '${v.substring(0, cut)}_secret_***';
      final parts = v.split('_');
      return '${parts[0]}_${parts[1]}_***';
    });
  }
  return out;
}

void stripeLog(String message, {bool warn = false}) {
  final line = scrubStripe(message);
  debugPrint('[stripe] $line');
  _channel
      .invokeMethod('stripeLog', {'message': line, 'warn': warn})
      .catchError((_) => null); // tests / non-Android: debugPrint only
}

/// Device-wide switches the Terminal SDK depends on. null = unknown.
class DeviceServices {
  final bool? locationOn, bluetoothOn;
  const DeviceServices(this.locationOn, this.bluetoothOn);
}

Future<DeviceServices> deviceServices() async {
  try {
    final m = await _channel.invokeMapMethod<String, dynamic>('deviceServices');
    return DeviceServices(
      m?['locationOn'] as bool?,
      m?['bluetoothOn'] as bool?,
    );
  } catch (_) {
    return const DeviceServices(null, null);
  }
}

/// Open a system settings screen: `location` or `bluetooth`.
Future<void> openDeviceSettings(String which) async {
  try {
    await _channel.invokeMethod('openSettings', {'which': which});
  } catch (e) {
    stripeLog('openSettings($which) failed: $e', warn: true);
  }
}
