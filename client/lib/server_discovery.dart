import 'dart:async';
import 'dart:io';

import 'api.dart';

/// LAN auto-discovery for the store server — so the tablet never needs a
/// hardcoded venue IP. It reads the device's own IPv4 (Wi-Fi), derives that
/// /24, TCP-scans it for an open :8080, then confirms each candidate with
/// GET /health. Returns the first match as 'http://host:8080'.
///
/// Two-phase (TCP connect, then HTTP /health) keeps it fast: a socket connect
/// is far cheaper than a full HTTP round-trip, so we only spend HTTP on the few
/// hosts that actually have the port open.
class ServerDiscovery {
  static const int port = 8080;

  /// The /24 prefixes the device is on (e.g. "10.0.0"). Usually one Wi-Fi NIC.
  static Future<List<String>> localSubnets() async {
    final prefixes = <String>{};
    try {
      final ifaces = await NetworkInterface.list(
        type: InternetAddressType.IPv4,
        includeLoopback: false,
      );
      for (final ni in ifaces) {
        for (final addr in ni.addresses) {
          final ip = addr.address; // e.g. 10.0.0.55
          // Skip link-local (169.254.x) — a real DHCP lease means a real LAN.
          if (ip.startsWith('169.254')) continue;
          final dot = ip.lastIndexOf('.');
          if (dot > 0) prefixes.add(ip.substring(0, dot));
        }
      }
    } catch (_) {}
    return prefixes.toList();
  }

  /// Scan the local subnet(s) for the store server. Returns its base URL, or
  /// null if nothing answered. [onStatus] surfaces coarse progress for the UI.
  static Future<String?> discover({
    Duration connectTimeout = const Duration(milliseconds: 300),
    int concurrency = 48,
    void Function(String subnet)? onStatus,
  }) async {
    final subnets = await localSubnets();
    for (final prefix in subnets) {
      onStatus?.call('$prefix.0/24');
      final hosts = [for (var i = 1; i <= 254; i++) '$prefix.$i'];
      final open = <String>[];
      // Phase 1: batched TCP-connect scan for an open :8080.
      for (var start = 0; start < hosts.length; start += concurrency) {
        final batch = hosts.skip(start).take(concurrency);
        final results = await Future.wait(
          batch.map((h) => _portOpen(h, port, connectTimeout)),
        );
        var i = 0;
        for (final h in batch) {
          if (results[i++]) open.add(h);
        }
      }
      // Phase 2: confirm each open-port host is actually the POS store.
      for (final h in open) {
        final base = 'http://$h:$port';
        if (await Api.probeHealth(base)) return base;
      }
    }
    return null;
  }

  /// Scan the local subnet(s) for every host with [port] open — e.g. a thermal
  /// printer on raw-TCP :9100. Unlike [discover] there is no HTTP confirm step:
  /// printers speak ESC/POS, not HTTP, so an open port is the signal. Returns
  /// all matches so the caller can disambiguate when a venue has several.
  static Future<List<String>> discoverOpenPort(
    int port, {
    Duration connectTimeout = const Duration(milliseconds: 300),
    int concurrency = 48,
  }) async {
    final subnets = await localSubnets();
    final found = <String>[];
    for (final prefix in subnets) {
      final hosts = [for (var i = 1; i <= 254; i++) '$prefix.$i'];
      for (var start = 0; start < hosts.length; start += concurrency) {
        final batch = hosts.skip(start).take(concurrency);
        final results = await Future.wait(
          batch.map((h) => _portOpen(h, port, connectTimeout)),
        );
        var i = 0;
        for (final h in batch) {
          if (results[i++]) found.add(h);
        }
      }
    }
    return found;
  }

  /// Find network thermal printers (raw ESC/POS over TCP :9100) on the LAN.
  static Future<List<String>> discoverPrinters() => discoverOpenPort(9100);

  static Future<bool> _portOpen(String host, int port, Duration timeout) async {
    try {
      final s = await Socket.connect(host, port, timeout: timeout);
      s.destroy();
      return true;
    } catch (_) {
      return false;
    }
  }
}
