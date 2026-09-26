import 'dart:async';
import 'dart:convert';
import 'dart:io' show Platform, SocketException;

import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart' as http_parser;

import 'app_mode.dart';
import 'connection_monitor.dart';
import 'i18n.dart';
import 'stock/stock_models.dart';
import 'store_profile.dart';

export 'stock/stock_models.dart';
export 'store_profile.dart';

part 'kitchen/kitchen_api.dart';

/// Thin API client for the store server. The client owns NO money logic —
/// pricing, tax, rounding all live server-side (architecture principle #2).
class Api {
  Api._();

  /// Android hosts its own store. Desktop/web builds retain LAN discovery for
  /// development; an Android tablet never silently falls back to a Mac. The
  /// stock app (a phone) never runs a store: it finds the store on the Wi-Fi.
  static bool get usesEmbeddedStore =>
      !kIsWeb && Platform.isAndroid && !AppMode.isStock;

  /// This app's own embedded store (the port differs per brand app, so two
  /// brands can run side by side on one tablet; see [AppMode.brand]).
  static String get embeddedStoreUrl =>
      'http://127.0.0.1:${AppMode.embeddedStorePort}';

  /// The POS itself uses loopback, but a QR scanned by another phone must not.
  /// The embedded store reports its current LAN origin through /cloud/info.
  static String? phoneQrBaseUrl(String? value) {
    final uri = Uri.tryParse(value?.trim() ?? '');
    if (uri == null ||
        (uri.scheme != 'http' && uri.scheme != 'https') ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.host == 'localhost' ||
        uri.host.startsWith('127.') ||
        uri.host == '0.0.0.0' ||
        uri.host == '10.0.2.2' ||
        uri.host == '::1') {
      return null;
    }
    return uri.origin;
  }

  static const _storage = FlutterSecureStorage();
  static const _storageTimeout = Duration(seconds: 2);

  static Future<String?> _readStorage(String key) async {
    try {
      return await _storage.read(key: key).timeout(_storageTimeout);
    } catch (_) {
      return null;
    }
  }

  static Future<void> _writeStorage(String key, String? value) async {
    try {
      await _storage.write(key: key, value: value).timeout(_storageTimeout);
    } catch (_) {}
  }

  static Future<void> _deleteStorage(String key) async {
    try {
      await _storage.delete(key: key).timeout(_storageTimeout);
    } catch (_) {}
  }

  // --- store server URL: no hardcoded venue IP -------------------------------
  // The base URL resolves, in priority order:
  //   1. a manual override the user typed          (device-local, persisted)
  //   2. a URL auto-discovered by scanning the LAN (cached from last success)
  //   3. a build-time --dart-define=SERVER_URL     (demo/CI convenience)
  //   4. the platform default                      (emulator/desktop only)
  // 1 and 2 are loaded ONCE at startup by [loadServerConfig] so [baseUrl] stays
  // synchronous for every caller; changes apply on the next launch.
  static String? _override; // user-typed; wins over everything
  static String? _discovered; // last successful LAN scan
  static const _envServerUrl = String.fromEnvironment('SERVER_URL');

  /// Only reachable when the store runs on the same host (Android emulator →
  /// 10.0.2.2, desktop → localhost). On a real tablet this never answers, so an
  /// override or LAN discovery is expected to supply the real URL.
  static String get _platformDefault {
    if (!kIsWeb && Platform.isAndroid) return 'http://10.0.2.2:8080';
    return 'http://localhost:8080';
  }

  static String get baseUrl {
    if (usesEmbeddedStore) return embeddedStoreUrl;
    if (_override != null && _override!.isNotEmpty) return _override!;
    if (_discovered != null && _discovered!.isNotEmpty) return _discovered!;
    if (_envServerUrl.isNotEmpty) return _envServerUrl;
    return _platformDefault;
  }

  /// The manual override (null = auto-detect). For the settings/startup UI.
  static String? get serverUrlOverride => _override;

  /// The store address this device last used (typed or discovered), if any.
  /// LAN discovery looks on its port first, so a phone stays with the same
  /// store when two run on one tablet (8080 and 8082).
  static String? get savedServerUrl => hasServerOverride
      ? _override
      : ((_discovered?.isNotEmpty ?? false) ? _discovered : null);
  static bool get hasServerOverride =>
      _override != null && _override!.isNotEmpty;

  /// Load persisted server config once at startup, before any request.
  static Future<void> loadServerConfig() async {
    // A damaged/temporarily unavailable Android keystore must not brick a POS
    // before its first frame. Connection addresses are only hints and device
    // pairing is optional on the LAN, so load each value independently.
    _override = _normalizeUrl(await _readStorage('server_url_override'));
    _discovered = _normalizeUrl(await _readStorage('server_url_cache'));
    _deviceToken = await _readStorage('device_token');
  }

  /// Persist (or clear) the manual override. Empty/null clears it → auto-detect.
  static Future<void> setServerUrlOverride(String? url) async {
    final v = _normalizeUrl(url);
    _override = v;
    if (v == null) {
      await _deleteStorage('server_url_override');
    } else {
      await _writeStorage('server_url_override', v);
    }
  }

  /// Record a discovered URL as the working default (cached for next launch).
  static Future<void> setDiscovered(String url) async {
    final v = _normalizeUrl(url);
    if (v == null) return;
    _discovered = v;
    await _writeStorage('server_url_cache', v);
  }

  /// Switch to a restaurant found on the current Wi-Fi. A previously entered
  /// address is only a hint, never a permanent lock: when it stops answering,
  /// discovery must be able to move the terminal back to the local venue.
  static Future<void> useDiscovered(String url) async {
    final v = _normalizeUrl(url);
    if (v == null) return;
    _override = null;
    _discovered = v;
    // The in-memory switch is authoritative for this run. Persistence is
    // best-effort: an Android keystore hiccup must never turn a healthy local
    // restaurant into a blocking startup error.
    await _deleteStorage('server_url_override');
    await _writeStorage('server_url_cache', v);
  }

  /// True only for an on-site/LAN origin. The tablet must never prefer an old
  /// Internet-hosted demo merely because it answers; cloud is sync/reporting,
  /// while all live POS traffic belongs on local Wi-Fi.
  static bool isLocalVenueUrl(String value) {
    final host = Uri.tryParse(value)?.host.toLowerCase() ?? '';
    if (host.isEmpty) return false;
    if (host == 'localhost' || host.endsWith('.local')) return true;
    final parts = host.split('.').map(int.tryParse).toList();
    if (parts.length != 4 || parts.any((part) => part == null)) return false;
    final a = parts[0]!;
    final b = parts[1]!;
    return a == 10 ||
        a == 127 ||
        (a == 172 && b >= 16 && b <= 31) ||
        (a == 192 && b == 168) ||
        (a == 169 && b == 254);
  }

  /// Normalize user input into a server origin, or null when blank/invalid.
  /// Bare/LAN HTTP addresses default to :8080; HTTPS uses its standard :443.
  static String? _normalizeUrl(String? raw) {
    var s = raw?.trim() ?? '';
    if (s.isEmpty) return null;
    if (!s.contains('://')) s = 'http://$s';
    s = s.replaceAll(RegExp(r'/+$'), '');
    final uri = Uri.tryParse(s);
    if (uri == null || uri.host.isEmpty) return null;
    if (!uri.hasPort && uri.scheme.toLowerCase() != 'https') s = '$s:8080';
    return s;
  }

  /// GET /health → parsed body, or null on any non-200/error. The single health
  /// request shape both probes below share, so /health moving or changing shape
  /// is a one-line fix, not two divergent copies.
  static Future<Map<String, dynamic>?> _getHealth(
    String base,
    Duration timeout,
  ) async {
    try {
      final res = await http.get(Uri.parse('$base/health')).timeout(timeout);
      if (res.statusCode != 200) return null;
      final body = jsonDecode(utf8.decode(res.bodyBytes));
      if (body is! Map<String, dynamic>) return <String, dynamic>{};
      // only the store this terminal uses names it — a LAN scan also probes
      // other stores' /health on the way
      final venue = body['venue'];
      if (base == baseUrl && venue is String && venue.trim().isNotEmpty) {
        venueName = venue.trim();
      }
      // ...and describes itself: screens, brand, languages, currency
      if (base == baseUrl) {
        Prefs.instance.useStore(StoreProfile.fromHealth(body));
      }
      return body;
    } catch (_) {
      return null;
    }
  }

  /// The store's display name from GET /health ("Copper Lantern — Vieux-Port");
  /// null until a health probe answered (or on a store too old to send it).
  static String? venueName;

  /// "Copper Lantern" — the brand half of [venueName].
  static String get venueBrand => splitVenueName(venueName).$1;

  /// "Vieux-Port" — the location half of [venueName]; null when unknown.
  static String? get venueLocation => splitVenueName(venueName).$2;

  /// "Brand — Location" → (brand, location). Falls back to the house brand.
  static (String, String?) splitVenueName(String? name) {
    const brand = 'Copper Lantern';
    final n = name?.trim() ?? '';
    if (n.isEmpty) return (brand, null);
    final parts = n.split(RegExp(r'\s+[—–-]\s+'));
    if (parts.length < 2) return (n, null);
    return (parts.first.trim(), parts.sublist(1).join(' — ').trim());
  }

  /// Fetch the venue name if no probe has yet (sign-in screen header).
  static Future<void> loadVenueName() async {
    if (venueName != null) return;
    await _getHealth(baseUrl, const Duration(seconds: 2));
  }

  /// Quick liveness probe of a base URL's /health — used by startup + discovery
  /// (short timeout: the LAN scan probes many hosts).
  static Future<bool> probeHealth(
    String base, {
    Duration timeout = const Duration(milliseconds: 800),
  }) async => await _getHealth(base, timeout) != null;

  /// Does this store demand a paired device? From GET /health's `pairingRequired`
  /// field; false when absent (pre-pairing servers) or the probe fails — the
  /// caller has already established reachability.
  static Future<bool> checkPairingRequired(
    String base, {
    Duration timeout = const Duration(seconds: 2),
  }) async => (await pairingRequirement(base, timeout: timeout)) == true;

  /// Nullable form used by the pairing screen: null means the restaurant did
  /// not answer, which is different from an explicit `pairingRequired:false`.
  static Future<bool?> pairingRequirement(
    String base, {
    Duration timeout = const Duration(seconds: 2),
  }) async {
    final health = await _getHealth(base, timeout);
    if (health == null) return null;
    return health['pairingRequired'] == true;
  }

  // --- device pairing: cloud venues hand each terminal a per-device token ---
  // Minted once via POST /pair (single-use code from the owner portal) and
  // sent as X-Device-Token on every request from then on. Harmless on LAN
  // servers that don't require it.
  static String? _deviceToken;

  static bool get hasDevicePairing =>
      _deviceToken != null && _deviceToken!.isNotEmpty;

  /// Normalize a typed venue address for pairing. Unlike [_normalizeUrl] (LAN:
  /// http + :8080), a bare host is assumed to be a cloud venue → https. An
  /// explicit port picks the scheme by the port: :443 is TLS (https), any other
  /// port is a LAN box (http, e.g. :8080). Explicit schemes are kept as typed.
  static String? normalizeVenueAddress(String? raw) {
    var s = raw?.trim() ?? '';
    if (s.isEmpty) return null;
    final hasScheme = s.contains(
      '://',
    ); // checked before slash-trimming can eat "http://"
    s = s.replaceAll(RegExp(r'/+$'), '');
    if (!hasScheme) {
      if (s.isEmpty) return null;
      // scheme by port: :443 → https (TLS), any other explicit port → http (LAN
      // box like :8080), no port → https (cloud venue behind Caddy)
      final port = RegExp(r':(\d+)$').firstMatch(s)?.group(1);
      s = (port == null || port == '443') ? 'https://$s' : 'http://$s';
    }
    final uri = Uri.tryParse(s);
    if (uri == null || uri.host.isEmpty) return null;
    return s;
  }

  /// Canonicalize a typed pairing code: uppercase, drop spaces/dashes/etc.,
  /// then re-group as XXXX-XXXX (the format the store expects). Null when the
  /// input holds no code characters at all.
  static String? normalizePairingCode(String? raw) {
    final cleaned = (raw ?? '').toUpperCase().replaceAll(
      RegExp(r'[^A-Z0-9]'),
      '',
    );
    if (cleaned.isEmpty) return null;
    final groups = <String>[];
    for (var i = 0; i < cleaned.length; i += 4) {
      groups.add(
        cleaned.substring(i, i + 4 > cleaned.length ? cleaned.length : i + 4),
      );
    }
    return groups.join('-');
  }

  /// Pair this terminal with a venue. Hits POST /pair on the GIVEN url — not
  /// [baseUrl] — because pairing is what establishes the URL. On success the
  /// device token is persisted and the venue URL becomes the server override.
  static Future<void> pair(
    String serverUrl,
    String code,
    String deviceName,
  ) async {
    // canonicalize once; the result is a full scheme://host[:port] origin
    final base = normalizeVenueAddress(serverUrl) ?? serverUrl;
    final res = await http
        .post(
          Uri.parse('$base/pair'),
          headers: {'Content-Type': 'application/json'},
          body: jsonEncode({'code': code, 'deviceName': deviceName}),
        )
        .timeout(const Duration(seconds: 10));
    _throwOnError(res);
    final json = jsonDecode(utf8.decode(res.bodyBytes));
    _deviceToken = json['deviceToken'];
    await _writeStorage('device_token', _deviceToken);
    // Persist the paired origin VERBATIM — NOT via setServerUrlOverride, whose
    // _normalizeUrl appends :8080 to a portless URL and would point an https
    // cloud venue at a port Caddy never serves, bricking the terminal.
    _override = base;
    await _writeStorage('server_url_override', base);
    _redirectingToPairing = false;
  }

  /// Forget this terminal's pairing (revoked server-side, or manual reset).
  static Future<void> clearPairing() async {
    _deviceToken = null;
    await _deleteStorage('device_token');
  }

  static String? _token;
  static AuthUser? currentUser;

  /// Wired by main.dart: navigate to the login screen, clearing the stack.
  /// Session expiry is not an error the user acknowledges — no toast, just go.
  static void Function()? onSessionExpired;
  static bool _redirectingToLogin = false;

  /// Wired by main.dart: navigate to the pairing screen, clearing the stack.
  /// Fires on 401 device_required / device_revoked (mirrors [onSessionExpired]).
  static void Function()? onPairingRequired;
  static bool _redirectingToPairing = false;

  static Map<String, String> get _headers => {
    'Content-Type': 'application/json',
    if (_token != null) 'Authorization': 'Bearer $_token',
    if (hasDevicePairing) 'X-Device-Token': _deviceToken!,
  };

  /// Headers for non-Api fetches of store media (Image.network on /photos):
  /// just the device token, so photos keep loading on device-gated stores.
  static Map<String, String> get mediaHeaders => {
    if (hasDevicePairing) 'X-Device-Token': _deviceToken!,
  };

  /// Cap on any single gated request. Without it a black-holed network (router
  /// power-cycled mid-shift, packets silently dropped) would hang each call for
  /// the OS TCP timeout (~1-2 min), so the reconnecting overlay would take
  /// minutes to appear — the exact state it exists to prevent. 15s is generous
  /// for a working LAN/cloud round-trip incl. a small photo upload.
  static const Duration _requestTimeout = Duration(seconds: 15);

  /// Run one HTTP call, feeding the global [ConnectionMonitor]: transport-level
  /// failures (socket/timeout/client) count toward the reconnecting overlay;
  /// any completed response — success OR http error status — counts as alive.
  static Future<http.Response> _send(
    Future<http.Response> Function() run, {
    Duration timeout = _requestTimeout,
    String operation = 'request',
  }) async {
    try {
      final res = await run().timeout(timeout);
      ConnectionMonitor.instance.reportSuccess(operation: operation);
      return res;
    } catch (e) {
      if (e is SocketException ||
          e is TimeoutException ||
          e is http.ClientException) {
        ConnectionMonitor.instance.reportFailure(
          operation: operation,
          error: e,
        );
      }
      rethrow;
    }
  }

  /// Restore a persisted session; returns the user or null (→ login screen).
  /// The startup gate owns navigation here — the expiry and pairing redirects
  /// stay off; a pairing demand is rethrown for the gate to route itself.
  static Future<AuthUser?> restoreSession() async {
    _token = await _readStorage('session_token');
    if (_token == null) return null;
    final expiredHandler = onSessionExpired;
    final pairingHandler = onPairingRequired;
    onSessionExpired = null;
    onPairingRequired = null;
    try {
      currentUser = AuthUser.fromJson(await _get('/me'), _token!);
      Prefs.instance.hydrate(languageCode: currentUser!.languageCode);
      return currentUser;
    } on PairingRequiredException {
      _token = null;
      rethrow; // the startup gate shows the pairing screen
    } on AuthException {
      _token = null;
      return null;
    } catch (_) {
      // server unreachable: keep the token, let the caller show retry UI
      rethrow;
    } finally {
      onSessionExpired = expiredHandler;
      onPairingRequired = pairingHandler;
      _redirectingToLogin = false;
      _redirectingToPairing = false;
    }
  }

  static Future<AuthUser> login(String pin) async {
    dynamic json;
    // Login is the one POST that is safe to repeat. If the local store accepts
    // it but Wi-Fi drops the response, retrying only replaces an unused session
    // token; it cannot duplicate a sale or another business operation.
    for (var attempt = 0; ; attempt++) {
      try {
        final res = await _send(
          () => http.post(
            Uri.parse('$baseUrl/login'),
            headers: _headers,
            body: jsonEncode({'pin': pin}),
          ),
          timeout: const Duration(seconds: 4),
          operation: 'POST /login',
        );
        _throwOnError(res);
        json = jsonDecode(utf8.decode(res.bodyBytes));
        break;
      } on SocketException {
        if (attempt >= 1) rethrow;
      } on TimeoutException {
        if (attempt >= 1) rethrow;
      } on http.ClientException {
        if (attempt >= 1) rethrow;
      }
      await Future<void>.delayed(const Duration(milliseconds: 250));
    }
    _redirectingToLogin = false;
    _redirectingToPairing = false;
    _token = json['token'];
    currentUser = AuthUser.fromJson(json, _token!);
    await _writeStorage('session_token', _token);
    Prefs.instance.hydrate(languageCode: currentUser!.languageCode);
    return currentUser!;
  }

  static Future<void> logout() async {
    try {
      await _post('/logout');
    } catch (_) {}
    _token = null;
    currentUser = null;
    await _deleteStorage('session_token');
  }

  /// Backoff for GETs. GETs are idempotent, so a bounded auto-retry is safe — it
  /// absorbs transient failures (server restart, Wi-Fi blip, a lost-lock 5xx)
  /// that otherwise make a first load a coin flip. Writes (_post/_patch/_delete)
  /// NEVER retry: a duplicated write is worse than an error the user can re-issue.
  static const _getRetryDelays = [
    Duration(milliseconds: 250),
    Duration(milliseconds: 750),
  ];

  static Future<dynamic> _get(String path) async =>
      jsonDecode(utf8.decode((await _getResponse(path)).bodyBytes));

  static Future<http.Response> _getResponse(String path) async {
    for (var attempt = 0; ; attempt++) {
      final canRetry = attempt < _getRetryDelays.length;
      try {
        final res = await _send(
          () => http.get(Uri.parse('$baseUrl$path'), headers: _headers),
          operation: 'GET $path',
        );
        // 5xx = server alive but hiccuped; give it another chance before surfacing
        // an error. 4xx (auth, not-found) throws immediately.
        if (res.statusCode < 500 || !canRetry) {
          _throwOnError(res);
          return res;
        }
      } on SocketException {
        if (!canRetry) rethrow;
      } on TimeoutException {
        if (!canRetry) rethrow;
      } on http.ClientException {
        if (!canRetry) rethrow;
      }
      await Future<void>.delayed(_getRetryDelays[attempt]);
    }
  }

  static Future<dynamic> _post(
    String path, [
    Map<String, dynamic>? body,
  ]) async {
    final res = await _send(
      () => http.post(
        Uri.parse('$baseUrl$path'),
        headers: _headers,
        body: jsonEncode(body ?? {}),
      ),
      operation: 'POST $path',
    );
    _throwOnError(res);
    return jsonDecode(utf8.decode(res.bodyBytes));
  }

  static Future<dynamic> _patch(String path, Map<String, dynamic> body) async {
    final res = await _send(
      () => http.patch(
        Uri.parse('$baseUrl$path'),
        headers: _headers,
        body: jsonEncode(body),
      ),
      operation: 'PATCH $path',
    );
    _throwOnError(res);
    return jsonDecode(utf8.decode(res.bodyBytes));
  }

  static Future<dynamic> _put(String path, Map<String, dynamic> body) async {
    final res = await _send(
      () => http.put(
        Uri.parse('$baseUrl$path'),
        headers: _headers,
        body: jsonEncode(body),
      ),
      operation: 'PUT $path',
    );
    _throwOnError(res);
    return jsonDecode(utf8.decode(res.bodyBytes));
  }

  static Future<dynamic> _delete(String path) async {
    final res = await _send(
      () => http.delete(Uri.parse('$baseUrl$path'), headers: _headers),
      operation: 'DELETE $path',
    );
    _throwOnError(res);
    return jsonDecode(utf8.decode(res.bodyBytes));
  }

  static void _throwOnError(http.Response res) {
    if (res.statusCode == 401) {
      String? code;
      try {
        code = jsonDecode(utf8.decode(res.bodyBytes))['code'];
      } catch (_) {}
      // Device pairing problems — with or without a session (/login 401s too).
      // Revocation wipes the stored pairing; both routes go to the pairing
      // screen via the same silent-redirect pattern as session expiry.
      if (code == 'device_required' || code == 'device_revoked') {
        if (code == 'device_revoked') clearPairing(); // fire-and-forget wipe
        _token = null;
        currentUser = null;
        _deleteStorage('session_token');
        if (!_redirectingToPairing) {
          _redirectingToPairing = true;
          onPairingRequired?.call();
        }
        throw PairingRequiredException(code!);
      }
      // mid-session expiry (we HAD a token): silently return to login.
      // pre-login 401s (wrong PIN) fall through as normal coded errors.
      if (_token != null) {
        _token = null;
        currentUser = null;
        _deleteStorage('session_token');
        if (!_redirectingToLogin) {
          _redirectingToLogin = true;
          onSessionExpired?.call();
        }
        throw SessionExpiredException();
      }
    }
    if (res.statusCode >= 400) {
      String message = 'HTTP ${res.statusCode}';
      String? code, declineCode;
      try {
        final body = jsonDecode(utf8.decode(res.bodyBytes));
        message = body['error'] ?? message;
        code = body['code'];
        declineCode = body['declineCode'];
      } catch (_) {}
      throw ApiException(message, code, declineCode, res.statusCode);
    }
  }

  static Future<AuthUser> updatePreferences(String languageCode) async {
    final json = await _patch('/me/preferences', {
      'languageCode': languageCode,
    });
    currentUser = AuthUser.fromJson(json, _token!);
    return currentUser!;
  }

  static Future<List<Zone>> zones() async =>
      ((await _get('/zones')) as List).map((z) => Zone.fromJson(z)).toList();

  /// Close/reopen a zone. Manager-gated inline like void/86 — [managerPin] is
  /// re-verified server-side. status is 'OPEN' or 'CLOSED'.
  static Future<void> setZoneStatus(
    String zoneId,
    String status,
    String? managerPin,
  ) async => _patch('/zones/$zoneId/status', {
    'status': status,
    'managerPin': ?managerPin,
  });

  // --- zone (room) management: add / rename / delete / reorder, same inline
  // manager-PIN gate as the floor-plan editor ---

  /// Create an empty room; the returned zone opens to a blank floor plan.
  static Future<Zone> createZone(
    String nameFr,
    String nameEn,
    String managerPin,
  ) async => Zone.fromJson(
    await _post('/zones', {
      'nameFr': nameFr,
      'nameEn': nameEn,
      'managerPin': managerPin,
    }),
  );

  /// Rename a room. Ids never change; status has its own route.
  static Future<Zone> renameZone(
    String zoneId, {
    required String nameFr,
    required String nameEn,
    required String managerPin,
  }) async => Zone.fromJson(
    await _patch('/zones/$zoneId', {
      'nameFr': nameFr,
      'nameEn': nameEn,
      'managerPin': managerPin,
    }),
  );

  /// Delete a room. Server refuses with zone_not_empty while it holds tables or
  /// floor objects.
  static Future<void> deleteZone(String zoneId, String managerPin) async =>
      _post('/zones/$zoneId/delete', {'managerPin': managerPin});

  /// Reorder the room switcher: index in [orderedIds] becomes sort order.
  static Future<void> reorderZones(
    List<String> orderedIds,
    String managerPin,
  ) async => _patch('/zones/order', {
    'orderedIds': orderedIds,
    'managerPin': managerPin,
  });

  // --- floor plan (all manager-PIN-gated inline, like void/86/zone-status) ---

  /// Batch "save layout": one write for a whole zone after an edit session.
  static Future<void> saveZoneLayout(
    String zoneId,
    List<Map<String, dynamic>> tables,
    String managerPin,
  ) async => _put('/zones/$zoneId/layout', {
    'tables': tables,
    'managerPin': managerPin,
  });

  static Future<TableInfo> addTable(
    String zoneId,
    Map<String, dynamic> body,
    String managerPin,
  ) async => TableInfo.fromJson(
    await _post('/zones/$zoneId/tables', {...body, 'managerPin': managerPin}),
  );

  /// Soft delete. Server refuses with table_in_use / has_sub_tables.
  static Future<void> deleteTable(String tableId, String managerPin) async =>
      _post('/tables/$tableId/delete', {'managerPin': managerPin});

  /// Rename label and/or VIP name. Empty [nameOverride] clears it; null leaves it.
  static Future<TableInfo> renameTable(
    String tableId, {
    String? label,
    String? nameOverride,
    required String managerPin,
  }) async => TableInfo.fromJson(
    await _patch('/tables/$tableId', {
      'label': ?label,
      'nameOverride': ?nameOverride,
      'managerPin': managerPin,
    }),
  );

  // --- floor objects (pool / bar front / pillar), same manager-PIN gate ---

  /// Drop a structural prop on the plan. [body] carries type + geometry.
  static Future<FloorObject> addObject(
    String zoneId,
    Map<String, dynamic> body,
    String managerPin,
  ) async => FloorObject.fromJson(
    await _post('/zones/$zoneId/objects', {...body, 'managerPin': managerPin}),
  );

  /// Hard delete — nothing references a floor object.
  static Future<void> deleteObject(String objectId, String managerPin) async =>
      _post('/objects/$objectId/delete', {'managerPin': managerPin});

  /// Batch geometry write for a zone's objects, mirroring saveZoneLayout.
  static Future<void> saveZoneObjects(
    String zoneId,
    List<Map<String, dynamic>> objects,
    String managerPin,
  ) async => _put('/zones/$zoneId/objects-layout', {
    'objects': objects,
    'managerPin': managerPin,
  });

  static Future<List<Category>> categories() async =>
      ((await _get('/categories')) as List)
          .map((c) => Category.fromJson(c))
          .toList();

  /// Open route: login-screen staff tiles. Names + roles only.
  static Future<List<Staff>> staff() async =>
      ((await _get('/staff')) as List).map((s) => Staff.fromJson(s)).toList();

  // --- staff administration (manage_staff). The tablet owns its staff; every
  // change is pushed up to the portal for display (one-way sync). ---

  static Future<List<ManagedStaff>> managedStaff() async =>
      (((await _get('/staff/manage')) as Map)['staff'] as List)
          .map((s) => ManagedStaff.fromJson(s))
          .toList();

  static Future<ManagedStaff> createStaff(
    String name,
    String role,
    String pin,
  ) async => ManagedStaff.fromJson(
    await _post('/staff/manage', {'name': name, 'role': role, 'pin': pin}),
  );

  static Future<ManagedStaff> updateStaff(
    String id, {
    String? name,
    String? role,
    bool? active,
  }) async => ManagedStaff.fromJson(
    await _patch('/staff/manage/$id', {
      'name': ?name,
      'role': ?role,
      'active': ?active,
    }),
  );

  static Future<void> resetStaffPin(String id, String pin) =>
      _post('/staff/manage/$id/pin', {'pin': pin});

  static Future<void> deleteStaff(String id) => _delete('/staff/manage/$id');

  static Future<VenueSettings> settings() async =>
      VenueSettings.fromJson(await _get('/settings'));

  static Future<VenueSettings> updateSettings(
    Map<String, dynamic> patch,
  ) async => VenueSettings.fromJson(await _patch('/settings', patch));

  /// Fire a test page at the configured thermal printer (manager-only).
  static Future<PrinterStatus> testPrint() async =>
      PrinterStatus.fromJson(await _post('/printer/test'));

  /// Live printer health — used to surface a "printer offline" toast post-sale.
  static Future<PrinterStatus> printerStatus() async =>
      PrinterStatus.fromJson(await _get('/printer/status'));

  /// Print one table's scan-to-order QR slip on the thermal printer. The QR
  /// payload is built server-side (matches the on-screen QR). Reports printer
  /// state via [PrinterStatus] rather than throwing when it's offline/unset.
  static Future<PrinterStatus> printTableSlip(String tableId) async =>
      PrinterStatus.fromJson(await _post('/tables/$tableId/slip/print'));

  /// Print [copies] guest Wi-Fi join slips (manager). Throws [ApiException]
  /// with code `wifi_not_configured` until the network is set up.
  static Future<WifiSlipResult> printWifiSlip(int copies) async =>
      WifiSlipResult.fromJson(
        await _post('/printer/wifi/print', {'copies': copies}),
      );

  /// Rotate a table's customer link (manager): every printed slip for it stops
  /// working. Returns the new "/m/t/{token}" path.
  static Future<String> regenerateTableLink(String tableId) async =>
      (await _post('/tables/$tableId/link/regenerate'))['menuPath'] as String;

  /// A short-lived ticket so the browser can open the printable slip pages
  /// (they carry every table's link, so they aren't public).
  static Future<String> slipsTicket() async =>
      (await _post('/slips/ticket'))['ticket'] as String;

  /// Bulk-print every active table's QR slip (manager, venue setup).
  static Future<PrintAllResult> printAllTableSlips() async =>
      PrintAllResult.fromJson(await _post('/tables/slips/print-all'));

  /// Pending-order alert config — readable by any staff (not manager-gated like
  /// full settings), so every terminal can drive its chime/escalation.
  static Future<AlertConfig> alertConfig() async =>
      AlertConfig.fromJson(await _get('/alert-config'));

  /// Owner reporting-portal URL, derived server-side from the store's cloud
  /// config (never hardcoded here). Null when cloud sync isn't configured — the
  /// settings screen hides the QR in that case.
  static Future<Map<String, dynamic>> cloudInfo() async =>
      (await _get('/cloud/info')) as Map<String, dynamic>;

  static Future<String?> cloudPortalUrl() async =>
      (await cloudInfo())['portalUrl'] as String?;

  static Future<void> changePin(String currentPin, String newPin) async =>
      _patch('/me/pin', {'currentPin': currentPin, 'newPin': newPin});

  /// Modal pre-flight: can this PIN approve [permission]? Throws (403/429) if not.
  /// Grant-aware when [permission] is given — the approver must hold it too
  /// (CONTRACT §7). The action endpoint still re-verifies the PIN it is sent.
  static Future<void> verifyManagerPin(
    String pin, {
    String? permission,
  }) async => _post('/auth/verify-manager-pin', {
    'pin': pin,
    'permission': ?permission,
  });

  /// The whole shelf for the counter's own index (a 5,000-product shop is a
  /// few MB of JSON): fetched in one gzip'd response and decoded off the UI
  /// thread, so the counter never stutters while it loads.
  static Future<List<Item>> catalog() async {
    final res = await _getResponse('/items');
    // a pub menu (or a test fixture) is small: not worth an isolate
    if (res.bodyBytes.length < 512 * 1024) return _decodeItems(res.bodyBytes);
    return compute(_decodeItems, res.bodyBytes);
  }

  /// The counter's quick keys (pins, unscannables, fastest sellers).
  static Future<QuickKeys> quickKeys({int limit = 36}) async =>
      QuickKeys.fromJson(await _get('/retail/quick-keys?limit=$limit'));

  static Future<QuickKeys> pinQuickKey(
    String itemId, {
    String? managerPin,
  }) async => QuickKeys.fromJson(
    await _post('/retail/quick-keys/pins', {
      'itemId': itemId,
      'managerPin': ?managerPin,
    }),
  );

  static Future<QuickKeys> unpinQuickKey(
    String itemId, {
    String? managerPin,
  }) async => QuickKeys.fromJson(
    await _post('/retail/quick-keys/unpin', {
      'itemId': itemId,
      'managerPin': ?managerPin,
    }),
  );

  /// The ranked top 20% of the shelf by the last 28 days' sales.
  static Future<List<TopSeller>> topSellers() async {
    final j = await _get('/retail/top-sellers') as Map<String, dynamic>;
    return [
      for (final r in (j['items'] as List? ?? const []))
        TopSeller(
          r['itemId'],
          r['rank'] ?? 0,
          (r['units'] as num? ?? 0).toInt(),
        ),
    ];
  }

  static Future<List<Item>> items({bool includeInactive = false}) async =>
      ((await _get('/items${includeInactive ? "?all=true" : ""}')) as List)
          .map((i) => Item.fromJson(i))
          .toList();

  // --- owner-editable catalog (manager session) ---

  static Future<Item> createItem(Map<String, dynamic> body) async =>
      Item.fromJson(await _post('/items', body));

  static Future<Item> updateItem(
    String itemId,
    Map<String, dynamic> patch,
  ) async => Item.fromJson(await _patch('/items/$itemId', patch));

  static Future<void> deleteItem(String itemId) async =>
      _delete('/items/$itemId');

  static Future<Item> addVariant(
    String itemId,
    Map<String, dynamic> body,
  ) async => Item.fromJson(await _post('/items/$itemId/variants', body));

  static Future<Item> updateVariant(
    String itemId,
    String variantId,
    Map<String, dynamic> patch,
  ) async =>
      Item.fromJson(await _patch('/items/$itemId/variants/$variantId', patch));

  static Future<Item> deleteVariant(String itemId, String variantId) async =>
      Item.fromJson(await _delete('/items/$itemId/variants/$variantId'));

  static Future<Category> createCategory(Map<String, dynamic> body) async =>
      Category.fromJson(await _post('/categories', body));

  static Future<Category> updateCategory(
    String id,
    Map<String, dynamic> patch,
  ) async => Category.fromJson(await _patch('/categories/$id', patch));

  static Future<void> deleteCategory(String id) async =>
      _delete('/categories/$id');

  static Future<void> reorderCategories(List<String> orderedIds) async =>
      _patch('/categories/order', {'orderedIds': orderedIds});

  static Future<Check> openCheck(String tableId) async =>
      Check.fromJson(await _post('/tables/$tableId/checks'));

  static Future<Check> getCheck(int id) async =>
      Check.fromJson(await _get('/checks/$id'));

  static Future<Check> addLine(
    int checkId,
    String itemId,
    String variantId,
    int qty, {
    String? note,
  }) async => Check.fromJson(
    await _post('/checks/$checkId/lines', {
      'itemId': itemId,
      'variantId': variantId,
      'qty': qty,
      'note': ?note,
    }),
  );

  static Future<Check> removeLine(int checkId, int lineId) async {
    final res = await _send(
      () => http.delete(
        Uri.parse('$baseUrl/checks/$checkId/lines/$lineId'),
        headers: _headers,
      ),
    );
    _throwOnError(res);
    return Check.fromJson(jsonDecode(utf8.decode(res.bodyBytes)));
  }

  static Future<Check> setLineQty(int checkId, int lineId, int qty) async =>
      Check.fromJson(
        await _post('/checks/$checkId/lines/$lineId/qty', {'qty': qty}),
      );

  /// Table ops: one gesture, two outcomes. Empty destination → move;
  /// occupied → merge into its open check (client confirms first).
  static Future<Check> moveCheck(int checkId, String tableId) async =>
      Check.fromJson(
        await _post('/checks/$checkId/move', {'tableId': tableId}),
      );

  static Future<Check> mergeCheck(int checkId, int intoCheckId) async =>
      Check.fromJson(
        await _post('/checks/$checkId/merge', {'intoCheckId': intoCheckId}),
      );

  /// Off-menu "open item": name + price + qty, no catalog row.
  static Future<Check> addOpenLine(
    int checkId,
    String name,
    int unitPriceCents,
    int qty,
  ) async => Check.fromJson(
    await _post('/checks/$checkId/open-lines', {
      'name': name,
      'unitPriceCents': unitPriceCents,
      'qty': qty,
    }),
  );

  static Future<Check> voidCheck(
    int checkId,
    String reason,
    String? managerPin,
  ) async => Check.fromJson(
    await _post('/checks/$checkId/void', {
      'reason': reason,
      'managerPin': ?managerPin,
    }),
  );

  static Future<void> setAvailability(
    String itemId,
    bool active,
    String? managerPin,
  ) async => _post('/items/$itemId/availability', {
    'active': active,
    'managerPin': ?managerPin,
  });

  /// Multipart photo upload (manager-gated). JPEG/PNG, ≤2MB, replaces existing.
  static Future<void> uploadItemPhoto(
    String itemId,
    List<int> bytes,
    String contentType,
    String managerPin,
  ) async {
    final req =
        http.MultipartRequest('POST', Uri.parse('$baseUrl/items/$itemId/photo'))
          ..headers['Authorization'] = 'Bearer $_token'
          ..fields['managerPin'] = managerPin
          ..files.add(
            http.MultipartFile.fromBytes(
              'photo',
              bytes,
              filename: 'photo',
              contentType: http_parser.MediaType.parse(contentType),
            ),
          );
    if (hasDevicePairing) req.headers['X-Device-Token'] = _deviceToken!;
    final res = await _send(
      () async => http.Response.fromStream(await req.send()),
    );
    _throwOnError(res);
  }

  // --- AI menu photos (paid add-on, online only) ------------------------------
  // These wait on an image provider (through the store) for up to a couple of
  // minutes, so like the Stripe calls they never feed the ConnectionMonitor: a
  // slow or offline provider must not look like the local store dropping out.

  static const Duration _aiTimeout = Duration(seconds: 200);

  /// Is "Generate photo" / "Snap and enhance" usable now? Any failure → the
  /// buttons show as unavailable; an older store without the route → hidden.
  static Future<AiPhotoStatus> aiPhotoStatus() async {
    try {
      final res = await http
          .get(Uri.parse('$baseUrl/ai-photos/status'), headers: _headers)
          .timeout(const Duration(seconds: 8));
      _throwOnError(res);
      return AiPhotoStatus.fromJson(jsonDecode(utf8.decode(res.bodyBytes)));
    } on ApiException catch (e) {
      if (e.code == null || e.code == 'not_found') return AiPhotoStatus.hidden;
      return AiPhotoStatus.unavailable(e.code);
    } on SessionExpiredException {
      rethrow;
    } catch (_) {
      return AiPhotoStatus.unavailable('image_offline');
    }
  }

  static Future<AiPhotoCandidates> aiGeneratePhoto(
    String itemId,
    String managerPin, {
    int? count,
  }) async {
    final res = await http
        .post(
          Uri.parse('$baseUrl/items/$itemId/ai-photo/generate'),
          headers: _headers,
          body: jsonEncode({'managerPin': managerPin, 'count': ?count}),
        )
        .timeout(_aiTimeout);
    _throwOnError(res);
    return AiPhotoCandidates.fromJson(jsonDecode(utf8.decode(res.bodyBytes)));
  }

  static Future<AiPhotoCandidates> aiEnhancePhoto(
    String itemId,
    List<int> bytes,
    String contentType,
    String managerPin, {
    int? count,
  }) async {
    final req =
        http.MultipartRequest(
            'POST',
            Uri.parse('$baseUrl/items/$itemId/ai-photo/enhance'),
          )
          ..headers['Authorization'] = 'Bearer $_token'
          ..fields['managerPin'] = managerPin
          ..files.add(
            http.MultipartFile.fromBytes(
              'photo',
              bytes,
              filename: 'dish',
              contentType: http_parser.MediaType.parse(contentType),
            ),
          );
    if (count != null) req.fields['count'] = '$count';
    if (hasDevicePairing) req.headers['X-Device-Token'] = _deviceToken!;
    final res = await http.Response.fromStream(
      await req.send(),
    ).timeout(_aiTimeout);
    _throwOnError(res);
    return AiPhotoCandidates.fromJson(jsonDecode(utf8.decode(res.bodyBytes)));
  }

  /// Save the picked candidate as the item's photo (the ordinary photo pipeline).
  static Future<void> aiChoosePhoto(
    String itemId,
    String candidateId,
    String managerPin,
  ) async {
    final res = await _send(
      () => http.post(
        Uri.parse('$baseUrl/items/$itemId/ai-photo/choose'),
        headers: _headers,
        body: jsonEncode({
          'managerPin': managerPin,
          'candidateId': candidateId,
        }),
      ),
      operation: 'POST ai-photo/choose',
    );
    _throwOnError(res);
  }

  static Future<Check> setCorkage(int checkId, int bottles) async =>
      Check.fromJson(
        await _post('/checks/$checkId/corkage', {'bottles': bottles}),
      );

  // --- settlement-time split: bill groups. All money comes back server-computed
  // on the Check (check.split); the client never sums group totals itself.

  static Future<Check> createSplit(
    int checkId, {
    int groups = 2,
    bool even = false,
  }) async => Check.fromJson(
    await _post('/checks/$checkId/split', {'groups': groups, 'even': even}),
  );

  static Future<Check> clearSplit(int checkId) async =>
      Check.fromJson(await _delete('/checks/$checkId/split'));

  static Future<Check> addSplitGroup(int checkId) async =>
      Check.fromJson(await _post('/checks/$checkId/split/groups'));

  static Future<Check> deleteSplitGroup(int checkId, int groupId) async =>
      Check.fromJson(await _delete('/checks/$checkId/split/groups/$groupId'));

  static Future<Check> assignLine(
    int checkId,
    int groupId,
    int lineId,
    int qty,
  ) async => Check.fromJson(
    await _post('/checks/$checkId/split/groups/$groupId/lines', {
      'lineId': lineId,
      'qty': qty,
    }),
  );

  static Future<Check> unassignLine(
    int checkId,
    int groupId,
    int lineId,
    int qty,
  ) async => Check.fromJson(
    await _post(
      '/checks/$checkId/split/groups/$groupId/lines/$lineId/unassign',
      {'qty': qty},
    ),
  );

  static Future<Check> moveCorkage(int checkId, int groupId) async =>
      Check.fromJson(
        await _post('/checks/$checkId/split/corkage', {'groupId': groupId}),
      );

  static Future<TenderResult> tenderCash(
    int checkId,
    int amountTenderedCents, {
    int? groupId,
  }) async {
    final json = await _post('/checks/$checkId/tenders', {
      'type': 'CASH',
      'amountTenderedCents': amountTenderedCents,
      'groupId': ?groupId,
    });
    return TenderResult(
      Tender.fromJson(json['tender']),
      Check.fromJson(json['check']),
    );
  }

  // --- retail counter (a store whose profile kind is retail) ---------------

  /// The sale in progress on the register, or a new one (idempotent).
  static Future<Check> openSale() async =>
      Check.fromJson(await _post('/retail/sales'));

  /// The sale in progress, or null when the register is free.
  static Future<Check?> currentSale() async {
    final res = await _send(
      () => http.get(
        Uri.parse('$baseUrl/retail/sales/current'),
        headers: _headers,
      ),
    );
    if (res.statusCode == 204) return null;
    _throwOnError(res);
    return Check.fromJson(jsonDecode(utf8.decode(res.bodyBytes)));
  }

  /// A barcode → one more of that product. ApiException code
  /// `unknown_barcode` → offer to add it.
  static Future<Check> scanBarcode(int saleId, String barcode) async =>
      Check.fromJson(
        await _post('/retail/sales/$saleId/scan', {'barcode': barcode}),
      );

  /// ID check: [scan] (the text a 2D scanner typed from a licence) or a
  /// typed [dateOfBirth] (YYYY-MM-DD) with [cashierSawId]. Sent once; the
  /// store keeps only the outcome.
  static Future<AgeCheckResult> ageCheck(
    int saleId, {
    String? scan,
    String? dateOfBirth,
    bool cashierSawId = false,
  }) async => AgeCheckResult.fromJson(
    await _post('/retail/sales/$saleId/age-check', {
      'method': scan != null ? 'SCAN' : 'MANUAL',
      'scan': ?scan,
      'dateOfBirth': ?dateOfBirth,
      'cashierSawId': cashierSawId,
    }),
  );

  /// An online name suggestion for an unknown barcode (null offline / no match).
  static Future<String?> lookupBarcode(String barcode) async {
    try {
      final json = await _get('/retail/lookup/${Uri.encodeComponent(barcode)}');
      final name = json is Map ? json['name'] : null;
      return name is String && name.trim().isNotEmpty ? name.trim() : null;
    } catch (_) {
      return null; // never in the way: the manager types the name
    }
  }

  /// A manager adds the product behind an unknown barcode.
  static Future<void> addRetailProduct({
    required String barcode,
    required String name,
    required int priceCents,
    required String categoryId,
    required bool ageRestricted,
    required String crvSize,
    required int packUnits,
    required bool taxable,
    String? managerPin,
  }) async => _post('/retail/products', {
    'barcode': barcode,
    'name': name,
    'priceCents': priceCents,
    'categoryId': categoryId,
    'ageRestricted': ageRestricted,
    'crvSize': crvSize,
    'packUnits': packUnits,
    'taxable': taxable,
    'managerPin': ?managerPin,
  });

  static Future<Check> finalizeCheck(int checkId) async =>
      Check.fromJson(await _post('/checks/$checkId/finalize'));

  /// Confirm-then-record electronic tender, step 1: get payment instructions.
  static Future<TenderInstructions> initiateTender(
    int checkId,
    String type, {
    int? amountCents,
    int? groupId,
  }) async => TenderInstructions.fromJson(
    await _post('/checks/$checkId/tenders/initiate', {
      'type': type,
      'amountCents': ?amountCents,
      'groupId': ?groupId,
    }),
  );

  /// Step 2: staff saw the money arrive — record it.
  static Future<TenderResult> confirmTender(
    int checkId,
    String type,
    int amountCents, {
    int? groupId,
  }) async {
    final json = await _post('/checks/$checkId/tenders/confirm', {
      'type': type,
      'amountCents': amountCents,
      'groupId': ?groupId,
    });
    return TenderResult(
      Tender.fromJson(json['tender']),
      Check.fromJson(json['check']),
    );
  }

  // --- Stripe: optional "Card (Stripe)" tender (test mode, simulated reader) --
  // These calls wait on Stripe (through the store), so they never feed the
  // ConnectionMonitor: a slow or offline Stripe must not look like the local
  // store dropping out, and must never raise the reconnecting overlay.

  static Future<dynamic> _stripeCall(
    String method,
    String path, {
    Map<String, dynamic>? body,
    Duration timeout = const Duration(seconds: 30),
  }) async {
    final uri = Uri.parse('$baseUrl$path');
    final res =
        await (method == 'GET'
                ? http.get(uri, headers: _headers)
                : http.post(
                    uri,
                    headers: _headers,
                    body: jsonEncode(body ?? {}),
                  ))
            .timeout(timeout);
    _throwOnError(res);
    return jsonDecode(utf8.decode(res.bodyBytes));
  }

  /// Whether "Card (Stripe)" can be offered now. Any failure → unavailable.
  static Future<StripeStatus> stripeStatus() async {
    try {
      return StripeStatus.fromJson(
        await _stripeCall(
          'GET',
          '/stripe/status',
          timeout: const Duration(seconds: 12),
        ),
      );
    } on ApiException catch (e) {
      // an older store without Stripe routes answers 404: not configured
      if (e.code == null || e.code == 'not_found') return StripeStatus.off;
      return StripeStatus.unavailable(e.code);
    } on SessionExpiredException {
      rethrow;
    } catch (_) {
      return StripeStatus.unavailable('stripe_unavailable');
    }
  }

  /// Terminal SDK connection token (fetched by the SDK when it needs one).
  static Future<String> stripeConnectionToken() async =>
      (await _stripeCall('POST', '/stripe/connection-token'))['secret'];

  /// A card_present PaymentIntent for the amount due (or [amountCents]).
  static Future<StripeIntent> createStripeIntent(
    int checkId, {
    int? amountCents,
    int? groupId,
  }) async => StripeIntent.fromJson(
    await _stripeCall(
      'POST',
      '/checks/$checkId/stripe/intents',
      body: {'amountCents': ?amountCents, 'groupId': ?groupId},
    ),
  );

  /// Capture the authorized card payment and record the tender.
  static Future<TenderResult> confirmStripePayment(String paymentId) async {
    final json = await _stripeCall(
      'POST',
      '/stripe/payments/$paymentId/confirm',
      timeout: const Duration(seconds: 45),
    );
    return TenderResult(
      Tender.fromJson(json['tender']),
      Check.fromJson(json['check']),
    );
  }

  /// Release the PaymentIntent; nothing is recorded. Best-effort.
  static Future<void> cancelStripePayment(String paymentId) async {
    await _stripeCall('POST', '/stripe/payments/$paymentId/cancel');
  }

  static Future<String> receiptText(int checkId) async =>
      (await _get('/checks/$checkId/receipt'))['text'];

  /// Provisional "check please" bill: render + spool the current state, return the
  /// text for preview. Non-mutating server-side — callable repeatedly after edits.
  /// [groupId] prints one bill group of a split check.
  static Future<String> printBill(int checkId, {int? groupId}) async =>
      (await _post(
        '/checks/$checkId/bill${groupId == null ? "" : "?groupId=$groupId"}',
      ))['text'];

  static Future<Check> acceptPendingLine(int checkId, int lineId) async =>
      Check.fromJson(
        await _post('/checks/$checkId/pending-lines/$lineId/accept'),
      );

  static Future<Check> rejectPendingLine(int checkId, int lineId) async =>
      Check.fromJson(
        await _post('/checks/$checkId/pending-lines/$lineId/reject'),
      );

  /// null when no shift is open.
  static Future<ShiftInfo?> currentShift() async {
    final res = await _send(
      () => http.get(Uri.parse('$baseUrl/shifts/current'), headers: _headers),
    );
    if (res.statusCode == 404) return null;
    _throwOnError(res);
    return ShiftInfo.fromJson(jsonDecode(utf8.decode(res.bodyBytes)));
  }

  static Future<ShiftInfo> openShift(
    int openingFloatCents,
    String? managerPin,
  ) async => ShiftInfo.fromJson(
    await _post('/shifts', {
      'openingFloatCents': openingFloatCents,
      'managerPin': ?managerPin,
    }),
  );

  static Future<ShiftReport> xReport() async =>
      ShiftReport.fromJson(await _get('/shifts/current/report'));

  static Future<DateTime> venueToday() async {
    final response = await _get('/reports/today');
    return DateTime.parse(response['date'] as String);
  }

  /// X-report layout over closed-at dates, inclusive (YYYY-MM-DD).
  static Future<ShiftReport> rangeReport(String from, String to) async =>
      ShiftReport.fromJson(await _get('/reports/range?from=$from&to=$to'));

  static Future<ShiftReport> closeShift(
    int closingCountCents,
    String? managerPin,
  ) async => ShiftReport.fromJson(
    await _post('/shifts/current/close', {
      'closingCountCents': closingCountCents,
      'managerPin': ?managerPin,
    }),
  );

  // --- refunds: return money on a finalized (CLOSED) check ---

  /// Recent CLOSED checks with their refund state — the refund picker.
  static Future<List<ClosedCheckSummary>> recentClosedChecks() async =>
      ((await _get('/checks/recent')) as List)
          .map((c) => ClosedCheckSummary.fromJson(c))
          .toList();

  /// A check's grand total, what's been refunded, and its refund history.
  static Future<RefundInfo> refundInfo(int checkId) async =>
      RefundInfo.fromJson(await _get('/checks/$checkId/refunds'));

  /// Refund by amount ([amountCents]) or by line ([lines] = [{lineId,qty}]).
  /// [tenderType] is CASH | CARD | BANK_TRANSFER | STRIPE (STRIPE refunds the
  /// card at Stripe first; refused if Stripe can't be reached). Returns the
  /// refund + slip.
  static Future<RefundResult> refundCheck(
    int checkId, {
    int? amountCents,
    List<Map<String, int>>? lines,
    required String tenderType,
    required String reason,
    String? managerPin,
  }) async {
    final json = await _post('/checks/$checkId/refund', {
      'amountCents': ?amountCents,
      'lines': ?lines,
      'tenderType': tenderType,
      'reason': reason,
      'managerPin': ?managerPin,
    });
    return RefundResult.fromJson(json);
  }

  // --- cash movements: non-sale cash in/out of the till ---

  /// Cash movements on the open shift (empty if none). The till log.
  static Future<List<CashMovement>> cashMovements() async =>
      ((await _get('/cash-movements')) as List)
          .map((m) => CashMovement.fromJson(m))
          .toList();

  /// Record a cash-in (IN) or cash-out (OUT). Manager-gated. Returns the row + slip.
  static Future<CashMovementResult> recordCashMovement(
    String direction,
    int amountCents,
    String reason,
    String? managerPin,
  ) async {
    final json = await _post('/cash-movements', {
      'direction': direction,
      'amountCents': amountCents,
      'reason': reason,
      'managerPin': ?managerPin,
    });
    return CashMovementResult.fromJson(json);
  }

  // --- stock (retail): counting and receiving, from the stock app or the
  // counter. Every write is idempotent on the client's own id, so the offline
  // queue (stock/stock_queue.dart) can resend after a dropped connection. ---

  /// The best-effort expected on hand per product.
  static Future<StockExpected> stockExpected() async =>
      StockExpected.fromJson(await _get('/stock/expected'));

  /// Open counts first, then the latest submitted ones.
  static Future<List<CountSummary>> stockCounts() async =>
      ((await _get('/stock/counts')) as List)
          .map((c) => CountSummary.fromJson(c as Map<String, dynamic>))
          .toList();

  /// Start the count with this client id (or get it, if it exists).
  static Future<CountSession> startCount(String id, {String? name}) async =>
      CountSession.fromJson(
        await _post('/stock/counts', {'id': id, 'name': ?name}),
      );

  static Future<CountSession> getCount(String id, {String? counterId}) async =>
      CountSession.fromJson(
        await _get(
          '/stock/counts/$id${counterId == null ? '' : '?counter=$counterId'}',
        ),
      );

  /// SET this counter's quantities ([lines]: itemId, qty, countedAt, remove).
  static Future<CountSession> setCountLines(
    String id,
    String counterId,
    List<Map<String, dynamic>> lines,
  ) async => CountSession.fromJson(
    await _put('/stock/counts/$id/lines', {
      'counterId': counterId,
      'lines': lines,
    }),
  );

  /// Submit (idempotent). A variance needs a manager: their session or PIN.
  static Future<CountSession> submitCount(
    String id, {
    String? managerPin,
  }) async => CountSession.fromJson(
    await _post('/stock/counts/$id/submit', {'managerPin': ?managerPin}),
  );

  static Future<CountSession> cancelCount(String id) async =>
      CountSession.fromJson(await _post('/stock/counts/$id/cancel'));

  /// A delivery (idempotent on its id).
  static Future<StockReceipt> receiveStock(Map<String, dynamic> body) async =>
      StockReceipt.fromJson(await _post('/stock/receipts', body));
}

class ApiException implements Exception {
  final String message; // server's english debug message (logs/debug only)
  final String? code; // machine code, translated client-side
  final String?
  declineCode; // Stripe card decline reason (stripe_declined only)

  /// The HTTP status, when the store answered (null for client-made errors).
  final int? status;
  ApiException(this.message, [this.code, this.declineCode, this.status]);

  /// User-facing text: the localized copy for [code], or — for an unknown or
  /// missing code — a clean generic message. The raw server [message] (internal
  /// ids, "HTTP 409", etc.) is never shown to end users; use it only for logs.
  @override
  String toString() => code == 'stripe_declined'
      ? L.current.stripeDeclineMessage(declineCode)
      : L.current.apiError(code) ?? L.current.apiError('internal')!;
}

/// 401 — session missing/expired; UI should return to the login screen.
class AuthException implements Exception {
  @override
  String toString() => L.current.apiError('login_required')!;
}

/// Mid-session expiry: the redirect to login already happened (or is in
/// flight). Display sites suppress this — it is not a user-facing error.
class SessionExpiredException extends AuthException {}

/// 401 device_required / device_revoked: this terminal must (re)pair. The
/// redirect to the pairing screen already happened (or is in flight); display
/// sites suppress it like [SessionExpiredException].
class PairingRequiredException extends SessionExpiredException {
  final String code; // device_required | device_revoked
  PairingRequiredException([this.code = 'device_required']);
  @override
  String toString() =>
      L.current.apiError(code) ?? L.current.apiError('internal')!;
}

class AuthUser {
  final String token, userId, name, role, languageCode;

  /// Effective grants the store computed for this user (CONTRACT §7). Used to
  /// skip the manager-PIN prompt for actions the user is already allowed.
  final Set<String> grants;
  AuthUser(
    this.token,
    this.userId,
    this.name,
    this.role,
    this.languageCode, {
    this.grants = const {},
  });
  factory AuthUser.fromJson(Map<String, dynamic> j, String token) => AuthUser(
    token,
    j['userId'],
    j['name'],
    j['role'],
    j['languageCode'] ?? 'en',
    grants: ((j['grants'] as List?) ?? const [])
        .map((e) => e as String)
        .toSet(),
  );
  bool get isManager => role == 'MANAGER';

  /// Offline grant check: does the acting user hold [permission]?
  bool can(String permission) => grants.contains(permission);
}

/// The fixed grant vocabulary (mirrors the store / CONTRACT §7).
class Perm {
  static const voidCheck = 'void';
  static const refund = 'refund';
  static const cashMovement = 'cash_movement';
  static const openShift = 'open_shift';
  static const closeShift = 'close_shift';
  static const zoneOpenClose = 'zone_open_close';
  static const editMenu = 'edit_menu';
  // Defined for completeness; portal-configurable but not yet gated on the store.
  static const discountComp = 'discount_comp';
  static const priceOverride = 'price_override';
  static const manageStaff = 'manage_staff';
}

/// The pubs' CAD house style: $1,010 for whole dollars, $10.50 otherwise.
/// Screens call [money], which follows the store's own currency.
String cad(int cents) => formatMoney(cents, 'CAD');

class Category {
  final String id, nameFr, nameEn;
  final int sortOrder;
  Category(this.id, this.nameFr, this.nameEn, this.sortOrder);
  factory Category.fromJson(Map<String, dynamic> j) =>
      Category(j['id'], j['nameFr'], j['nameEn'], j['sortOrder'] ?? 0);
}

class Staff {
  final String id, name, role;
  Staff(this.id, this.name, this.role);
  factory Staff.fromJson(Map<String, dynamic> j) =>
      Staff(j['id'], j['name'], j['role']);
}

/// A staff row as the manager's staff screen sees it (inactive ones included).
class ManagedStaff {
  final String id, name, role;
  final bool active;
  ManagedStaff(this.id, this.name, this.role, this.active);
  factory ManagedStaff.fromJson(Map<String, dynamic> j) =>
      ManagedStaff(j['id'], j['name'], j['role'], j['active'] ?? true);
  bool get isManager => role == 'MANAGER';
}

class VenueSettings {
  final String cardProcessor, bankName, bankAccountNumber, bankAccountName;
  final int serviceChargePercent;
  final int corkagePerBottleCents;
  final String receiptFooter, venuePhone, venueAddress;
  final int sessionIdleMinutes;
  final bool pendingAlertsEnabled;
  final int pendingAlertEscalateSeconds;
  final int pendingAlertVolume;
  final String printerIp;
  final int printerPort;

  /// Guest Wi-Fi for the join slips. Empty [wifiSsid] = not configured.
  final String wifiSsid;

  /// Null when this session may not read it (redacted server-side).
  final String? wifiPassword;
  final String wifiSecurity; // WPA | WEP | nopass
  final bool wifiHidden;
  VenueSettings(
    this.cardProcessor,
    this.bankName,
    this.bankAccountNumber,
    this.bankAccountName,
    this.serviceChargePercent,
    this.corkagePerBottleCents,
    this.receiptFooter,
    this.venuePhone,
    this.venueAddress,
    this.sessionIdleMinutes,
    this.pendingAlertsEnabled,
    this.pendingAlertEscalateSeconds,
    this.pendingAlertVolume,
    this.printerIp,
    this.printerPort, {
    this.wifiSsid = '',
    this.wifiPassword,
    this.wifiSecurity = 'WPA',
    this.wifiHidden = false,
  });
  factory VenueSettings.fromJson(Map<String, dynamic> j) => VenueSettings(
    j['cardProcessor'],
    j['bankName'],
    j['bankAccountNumber'],
    j['bankAccountName'],
    j['serviceChargePercent'],
    j['corkagePerBottleCents'],
    j['receiptFooter'],
    j['venuePhone'],
    j['venueAddress'],
    j['sessionIdleMinutes'],
    j['pendingAlertsEnabled'] ?? true,
    j['pendingAlertEscalateSeconds'] ?? 90,
    j['pendingAlertVolume'] ?? 80,
    j['printerIp'] ?? '',
    j['printerPort'] ?? 9100,
    wifiSsid: j['wifiSsid'] ?? '',
    wifiPassword: j['wifiPassword'],
    wifiSecurity: j['wifiSecurity'] ?? 'WPA',
    wifiHidden: j['wifiHidden'] ?? false,
  );
}

/// Result of POST /printer/wifi/print: printer state plus copies printed.
class WifiSlipResult {
  final bool configured, online;
  final int printed, copies;
  WifiSlipResult(this.configured, this.online, this.printed, this.copies);
  factory WifiSlipResult.fromJson(Map<String, dynamic> j) => WifiSlipResult(
    j['configured'] ?? false,
    j['online'] ?? false,
    j['printed'] ?? 0,
    j['copies'] ?? 0,
  );
}

/// Network thermal-printer health, from GET /printer/status and POST /printer/test.
class PrinterStatus {
  final bool configured;
  final bool online;
  final String? lastError;
  final String? lastOkAt;
  PrinterStatus(this.configured, this.online, this.lastError, this.lastOkAt);
  factory PrinterStatus.fromJson(Map<String, dynamic> j) => PrinterStatus(
    j['configured'] ?? false,
    j['online'] ?? false,
    j['lastError'],
    j['lastOkAt'],
  );
}

/// Result of the bulk "print all table QR slips" action (POST /tables/slips/print-all).
class PrintAllResult {
  final int printed, attempted;
  final bool configured, online;
  PrintAllResult(this.printed, this.attempted, this.configured, this.online);
  factory PrintAllResult.fromJson(Map<String, dynamic> j) => PrintAllResult(
    j['printed'] ?? 0,
    j['attempted'] ?? 0,
    j['configured'] ?? false,
    j['online'] ?? false,
  );
}

/// The any-staff subset of settings that drives pending-order alerts.
class AlertConfig {
  final bool pendingAlertsEnabled;
  final int pendingAlertEscalateSeconds;
  final int pendingAlertVolume;
  AlertConfig(
    this.pendingAlertsEnabled,
    this.pendingAlertEscalateSeconds,
    this.pendingAlertVolume,
  );
  factory AlertConfig.fromJson(Map<String, dynamic> j) => AlertConfig(
    j['pendingAlertsEnabled'] ?? true,
    j['pendingAlertEscalateSeconds'] ?? 90,
    j['pendingAlertVolume'] ?? 80,
  );
}

class Zone {
  final String id, nameFr, nameEn;
  final String status; // OPEN | CLOSED
  final List<TableInfo> tables;

  /// Inert structural props (pool/bar/pillar) drawn beneath the tables.
  final List<FloorObject> objects;

  /// Table-label prefix (U/O/B/L): every table here is labelled "{prefix}-{n}".
  final String labelPrefix;
  Zone(
    this.id,
    this.nameFr,
    this.nameEn,
    this.status,
    this.tables,
    this.objects,
    this.labelPrefix,
  );
  bool get isClosed => status == 'CLOSED';
  factory Zone.fromJson(Map<String, dynamic> j) => Zone(
    j['id'],
    j['nameFr'],
    j['nameEn'],
    j['status'] ?? 'OPEN',
    (j['tables'] as List).map((t) => TableInfo.fromJson(t)).toList(),
    ((j['objects'] as List?) ?? const [])
        .map((o) => FloorObject.fromJson(o))
        .toList(),
    j['labelPrefix'] ?? '',
  );
}

/// A non-orderable structural prop on the floor plan — a pool table, the bar
/// front, a pillar. Pure context: never a check, no seats, no status color, not
/// tappable in service mode. Same logical 0–1000 canvas as [TableInfo].
class FloorObject {
  final String id;
  final String type; // POOL | BAR_FRONT | PILLAR
  final int x, y, width, height, rotation;

  /// Optional caption per catalog language ("Billard" / "Pool").
  final String? labelFr, labelEn;
  FloorObject(
    this.id,
    this.type,
    this.x,
    this.y,
    this.width,
    this.height,
    this.rotation,
    this.labelFr,
    this.labelEn,
  );
  factory FloorObject.fromJson(Map<String, dynamic> j) => FloorObject(
    j['id'],
    j['type'],
    j['x'] ?? 0,
    j['y'] ?? 0,
    j['width'] ?? 100,
    j['height'] ?? 100,
    j['rotation'] ?? 0,
    // an older store sends one `label` for both languages
    j['labelFr'] ?? j['label'],
    j['labelEn'] ?? j['label'],
  );

  /// Editor-local geometry mutation (drag/resize/rotate); identity carries over.
  FloorObject copyWith({
    int? x,
    int? y,
    int? width,
    int? height,
    int? rotation,
  }) => FloorObject(
    id,
    type,
    x ?? this.x,
    y ?? this.y,
    width ?? this.width,
    height ?? this.height,
    rotation ?? this.rotation,
    labelFr,
    labelEn,
  );

  /// The geometry slice the batch "objects layout" endpoint expects.
  Map<String, dynamic> get geometryJson => {
    'id': id,
    'x': x,
    'y': y,
    'width': width,
    'height': height,
    'rotation': rotation,
  };
}

class TableInfo {
  final String id, label;
  final String? parentTableId, nameOverride;
  final int? openCheckId;
  final String? openCheckStatus; // OPEN | TOTAL_LOCKED (null = free)
  final int pendingCount;

  /// ISO timestamp of the oldest un-actioned pending line; null when none.
  final String? oldestPendingAt;
  final int? openCheckTotalCents;
  final String? openCheckOpenedAt;

  /// Floor-plan geometry: logical units on the server's 0–1000 canvas.
  final int x, y, width, height, rotation, seats;
  final String shape; // ROUND | SQUARE | RECT | BAR

  /// Customer link path "/m/t/{token}" (random per table; null on an old server).
  final String? menuPath;
  TableInfo(
    this.id,
    this.label,
    this.parentTableId,
    this.nameOverride,
    this.openCheckId,
    this.openCheckStatus,
    this.pendingCount,
    this.oldestPendingAt,
    this.openCheckTotalCents,
    this.openCheckOpenedAt,
    this.x,
    this.y,
    this.width,
    this.height,
    this.rotation,
    this.shape,
    this.seats, {
    this.menuPath,
  });
  factory TableInfo.fromJson(Map<String, dynamic> j) => TableInfo(
    j['id'],
    j['label'],
    j['parentTableId'],
    j['nameOverride'],
    j['openCheckId'],
    j['openCheckStatus'],
    j['pendingCount'] ?? 0,
    j['oldestPendingAt'],
    j['openCheckTotalCents'],
    j['openCheckOpenedAt'],
    j['x'] ?? 0,
    j['y'] ?? 0,
    j['width'] ?? 100,
    j['height'] ?? 100,
    j['rotation'] ?? 0,
    j['shape'] ?? 'SQUARE',
    j['seats'] ?? 4,
    menuPath: j['menuPath'],
  );
  String get displayLabel => nameOverride ?? label;
  bool get isVip => nameOverride != null;

  /// Editor-local mutation (drag/resize/rename); occupancy fields carry over.
  TableInfo copyWith({
    int? x,
    int? y,
    int? width,
    int? height,
    int? rotation,
    String? shape,
    int? seats,
    String? label,
    String? nameOverride,
    bool clearNameOverride = false,
  }) => TableInfo(
    id,
    label ?? this.label,
    parentTableId,
    clearNameOverride ? null : (nameOverride ?? this.nameOverride),
    openCheckId,
    openCheckStatus,
    pendingCount,
    oldestPendingAt,
    openCheckTotalCents,
    openCheckOpenedAt,
    x ?? this.x,
    y ?? this.y,
    width ?? this.width,
    height ?? this.height,
    rotation ?? this.rotation,
    shape ?? this.shape,
    seats ?? this.seats,
    menuPath: menuPath,
  );

  /// The geometry slice the batch "save layout" endpoint expects.
  Map<String, dynamic> get geometryJson => {
    'id': id,
    'x': x,
    'y': y,
    'width': width,
    'height': height,
    'rotation': rotation,
    'shape': shape,
    'seats': seats,
  };
}

class Variant {
  final String id, labelFr, labelEn;
  final int priceCents;
  Variant(this.id, this.labelFr, this.labelEn, this.priceCents);
  factory Variant.fromJson(Map<String, dynamic> j) =>
      Variant(j['id'], j['labelFr'], j['labelEn'], j['priceCents']);
}

List<Item> _decodeItems(Uint8List bytes) => [
  for (final j in jsonDecode(utf8.decode(bytes)) as List)
    Item.fromJson(j as Map<String, dynamic>),
];

/// One counter quick key: a product, and why it is there.
class QuickKey {
  final String itemId;
  final bool pinned;
  final int units;

  /// pin | unscannable | velocity | popular
  final String source;
  const QuickKey(this.itemId, this.pinned, this.units, this.source);
}

class QuickKeys {
  final List<QuickKey> keys;
  final int windowDays;
  const QuickKeys(this.keys, {this.windowDays = 28});
  static const empty = QuickKeys([]);
  factory QuickKeys.fromJson(dynamic j) => QuickKeys([
    for (final k in (j['keys'] as List? ?? const []))
      QuickKey(
        k['itemId'],
        k['pinned'] == true,
        (k['units'] as num? ?? 0).toInt(),
        k['source'] ?? 'velocity',
      ),
  ], windowDays: j['windowDays'] ?? 28);
}

/// A product's place in the last 28 days' sales.
class TopSeller {
  final String itemId;
  final int rank, units;
  const TopSeller(this.itemId, this.rank, this.units);
}

class Item {
  final String id,
      nameFr,
      nameEn,
      descriptionFr,
      descriptionEn,
      category,
      abbrev;
  final bool isAlcohol, active;
  final List<Variant> variants;

  /// Cache-busting photo version; null = no photo (tile shows the badge).
  final int? photoVersion;

  /// Where the photo came from: original | ai_generated | ai_enhanced
  /// (null = no photo, or an older store). The AI values get an "AI" badge.
  final String? photoSource;
  bool get photoIsAi =>
      photoSource == 'ai_generated' || photoSource == 'ai_enhanced';

  /// Retail shelf facts: UPC barcode, ID check before payment, taxed or
  /// not, and the bottle deposit (CRV) per unit sold. Pub items: defaults.
  final String? barcode;
  final bool ageRestricted, taxable;
  final int depositCents;

  /// Catalog facets (a big shelf): producer, style / varietal / type, size or
  /// pack label; containers in the pack; the demo popularity weight.
  final String? brand, subcategory, size;
  final int packUnits, salesWeight;
  Item(
    this.id,
    this.nameFr,
    this.nameEn,
    this.descriptionFr,
    this.descriptionEn,
    this.category,
    this.abbrev,
    this.isAlcohol,
    this.active,
    this.variants,
    this.photoVersion, {
    this.photoSource,
    this.barcode,
    this.ageRestricted = false,
    this.taxable = true,
    this.depositCents = 0,
    this.brand,
    this.subcategory,
    this.size,
    this.packUnits = 1,
    this.salesWeight = 0,
  });
  factory Item.fromJson(Map<String, dynamic> j) => Item(
    j['id'],
    j['nameFr'],
    j['nameEn'],
    j['descriptionFr'] ?? '',
    j['descriptionEn'] ?? '',
    j['category'],
    j['abbrev'],
    j['isAlcohol'],
    j['active'] ?? true,
    (j['variants'] as List).map((v) => Variant.fromJson(v)).toList(),
    j['photoVersion'],
    photoSource: j['photoSource'],
    barcode: j['barcode'],
    ageRestricted: j['ageRestricted'] ?? false,
    taxable: j['taxable'] ?? true,
    depositCents: j['depositCents'] ?? 0,
    brand: j['brand'],
    subcategory: j['subcategory'],
    size: j['size'],
    packUnits: j['packUnits'] ?? 1,
    salesWeight: j['salesWeight'] ?? 0,
  );

  /// The first price (a shelf product has exactly one).
  int get priceCents => variants.isEmpty ? 0 : variants.first.priceCents;

  /// Photo URL, version-busted (?v=) so a replaced photo bypasses every cache
  /// layer. [width] asks the server for its downscaled variant (?w=, snapped
  /// to fixed buckets) — tiles should never pull the ~170KB 1000px original.
  String? photoUrl({int? width}) => photoVersion == null
      ? null
      : '${Api.baseUrl}/photos/$id?v=$photoVersion${width == null ? '' : '&w=$width'}';
}

class CheckLine {
  final int id, qty, unitPriceCents, lineTotalCents;
  final String nameFr, nameEn;

  /// Null on open (off-menu) lines — the name lives on the line itself.
  final String? itemId, variantId;

  /// Set only when the item has >1 variant (bottle/pitcher/tower).
  final String? variantLabelFr, variantLabelEn;
  final String? note;

  /// Retail: needs an ID check; bottle deposit (CRV) per unit; taxed or not.
  final bool ageRestricted, taxable;
  final int depositCents;
  CheckLine(
    this.id,
    this.itemId,
    this.variantId,
    this.nameFr,
    this.nameEn,
    this.variantLabelFr,
    this.variantLabelEn,
    this.qty,
    this.unitPriceCents,
    this.lineTotalCents,
    this.note, {
    this.ageRestricted = false,
    this.taxable = true,
    this.depositCents = 0,
  });
  factory CheckLine.fromJson(Map<String, dynamic> j) => CheckLine(
    j['id'],
    j['itemId'],
    j['variantId'],
    j['nameFr'],
    j['nameEn'],
    j['variantLabelFr'],
    j['variantLabelEn'],
    j['qty'],
    j['unitPriceCents'],
    j['lineTotalCents'],
    j['note'],
    ageRestricted: j['ageRestricted'] ?? false,
    taxable: j['taxable'] ?? true,
    depositCents: j['depositCents'] ?? 0,
  );
}

class FeeLine {
  final String code, labelFr, labelEn;
  final int amountCents;
  FeeLine(this.code, this.labelFr, this.labelEn, this.amountCents);
  factory FeeLine.fromJson(Map<String, dynamic> j) =>
      FeeLine(j['code'], j['labelFr'], j['labelEn'], j['amountCents']);
}

/// A tax added on top of the pre-tax subtotal (GST, QST). Server-computed;
/// [ratePercent] is a decimal string ("9.975").
class TaxLine {
  final String code, labelFr, labelEn, ratePercent;
  final int amountCents;
  TaxLine(
    this.code,
    this.labelFr,
    this.labelEn,
    this.ratePercent,
    this.amountCents,
  );
  factory TaxLine.fromJson(Map<String, dynamic> j) => TaxLine(
    j['code'] ?? '',
    j['labelFr'] ?? '',
    j['labelEn'] ?? '',
    j['ratePercent'] ?? '',
    j['amountCents'] ?? 0,
  );

  static List<TaxLine> listFrom(dynamic json) =>
      ((json ?? []) as List).map((t) => TaxLine.fromJson(t)).toList();
}

class Tender {
  final int id,
      amountTenderedCents,
      amountAppliedCents,
      roundingAdjustmentCents,
      changeCents;
  final String type;

  /// Bill group this tender paid into; null = whole-check tender.
  final int? groupId;
  Tender(
    this.id,
    this.type,
    this.amountTenderedCents,
    this.amountAppliedCents,
    this.roundingAdjustmentCents,
    this.changeCents,
    this.groupId,
  );

  /// Cash actually paid into the drawer for this tender (server-rounded):
  /// applied + rounding. Equals [amountAppliedCents] for non-cash tenders.
  int get cashPaidCents => amountAppliedCents + roundingAdjustmentCents;

  factory Tender.fromJson(Map<String, dynamic> j) => Tender(
    j['id'],
    j['type'],
    j['amountTenderedCents'],
    j['amountAppliedCents'],
    j['roundingAdjustmentCents'] ?? 0,
    j['changeCents'],
    j['groupId'],
  );
}

/// One (lineId, qty) slice of a check line inside a bill group / unassigned pool.
class Allocation {
  final int lineId, qty;
  Allocation(this.lineId, this.qty);
  factory Allocation.fromJson(Map<String, dynamic> j) =>
      Allocation(j['lineId'], j['qty']);
}

/// One bill group of a split check. All money server-computed.
class BillGroup {
  final int id, number;
  final bool includesCorkage;
  final int? fixedAmountCents; // set on even ÷N money-only groups
  final List<Allocation> allocations;
  final int itemsSubtotalCents, grandTotalCents, paidCents, outstandingCents;
  final List<FeeLine> fees;

  /// Pre-tax share; subtotal + taxes = grand total.
  final int subtotalCents;

  /// This bill's share of the check's taxes.
  final List<TaxLine> taxes;

  /// What this group owes if paid in CASH (server-rounded to the nickel) and
  /// the signed difference from [outstandingCents]. Older servers omit them:
  /// cash due = outstanding, no rounding.
  final int cashDueCents, cashRoundingCents;
  BillGroup(
    this.id,
    this.number,
    this.includesCorkage,
    this.fixedAmountCents,
    this.allocations,
    this.itemsSubtotalCents,
    this.fees,
    this.grandTotalCents,
    this.paidCents,
    this.outstandingCents, {
    int? subtotalCents,
    this.taxes = const [],
    int? cashDueCents,
    this.cashRoundingCents = 0,
  }) : subtotalCents = subtotalCents ?? grandTotalCents,
       cashDueCents = cashDueCents ?? outstandingCents;
  factory BillGroup.fromJson(Map<String, dynamic> j) => BillGroup(
    j['id'],
    j['number'],
    j['includesCorkage'] ?? false,
    j['fixedAmountCents'],
    ((j['allocations'] ?? []) as List)
        .map((a) => Allocation.fromJson(a))
        .toList(),
    j['itemsSubtotalCents'],
    ((j['fees'] ?? []) as List).map((f) => FeeLine.fromJson(f)).toList(),
    j['grandTotalCents'],
    j['paidCents'],
    j['outstandingCents'],
    subtotalCents: j['subtotalCents'],
    taxes: TaxLine.listFrom(j['taxes']),
    cashDueCents: j['cashDueCents'],
    cashRoundingCents: j['cashRoundingCents'] ?? 0,
  );

  /// Paid = money actually covered this group — an empty $0 group is NOT paid.
  bool get isPaid => outstandingCents == 0 && paidCents > 0;
}

/// Settlement-time split overlay: groups + the not-yet-assigned pool.
class SplitInfo {
  final List<BillGroup> groups;
  final List<Allocation> unassigned;
  final bool even; // ÷N money-only split (no line allocation)
  final bool locked; // any group tendered → no more edits
  SplitInfo(this.groups, this.unassigned, this.even, this.locked);
  factory SplitInfo.fromJson(Map<String, dynamic> j) => SplitInfo(
    (j['groups'] as List).map((g) => BillGroup.fromJson(g)).toList(),
    ((j['unassigned'] ?? []) as List)
        .map((a) => Allocation.fromJson(a))
        .toList(),
    j['even'] ?? false,
    j['locked'] ?? false,
  );
}

class Check {
  final int id;
  final String tableId, status;
  final int corkageBottles,
      itemsSubtotalCents,
      grandTotalCents,
      paidCents,
      outstandingCents;
  final List<CheckLine> lines;
  final List<CheckLine> pendingLines;
  final List<FeeLine> fees;
  final List<Tender> tenders;

  /// Settlement-time bill groups; null = not split (normal single-bill flow).
  final SplitInfo? split;

  /// Pre-tax: items + fees. subtotal + taxes = grand total.
  final int subtotalCents;

  /// Taxes added on top of the subtotal (GST, QST), server-computed.
  final List<TaxLine> taxes;

  /// Retail: an age-restricted item is on the sale → payment needs an ID
  /// check. [ageCleared]: none needed or one passed. [ageCheckFailed]: the
  /// last check failed and none passed (remove the restricted items).
  final bool ageCheckRequired, ageCleared, ageCheckFailed;

  /// What's left if paid in CASH (server-rounded to the nickel) and the signed
  /// difference from [outstandingCents] (e.g. -2, +1). Card is always exact.
  /// Older servers omit them: cash due = outstanding, no rounding.
  final int cashDueCents, cashRoundingCents;
  Check(
    this.id,
    this.tableId,
    this.status,
    this.corkageBottles,
    this.lines,
    this.pendingLines,
    this.fees,
    this.itemsSubtotalCents,
    this.grandTotalCents,
    this.paidCents,
    this.outstandingCents,
    this.tenders,
    this.split, {
    int? subtotalCents,
    this.taxes = const [],
    this.ageCheckRequired = false,
    this.ageCleared = true,
    this.ageCheckFailed = false,
    int? cashDueCents,
    this.cashRoundingCents = 0,
  }) : subtotalCents = subtotalCents ?? grandTotalCents,
       cashDueCents = cashDueCents ?? outstandingCents;
  factory Check.fromJson(Map<String, dynamic> j) => Check(
    j['id'],
    j['tableId'],
    j['status'],
    j['corkageBottles'],
    (j['lines'] as List).map((l) => CheckLine.fromJson(l)).toList(),
    ((j['pendingLines'] ?? []) as List)
        .map((l) => CheckLine.fromJson(l))
        .toList(),
    (j['fees'] as List).map((f) => FeeLine.fromJson(f)).toList(),
    j['itemsSubtotalCents'],
    j['grandTotalCents'],
    j['paidCents'],
    j['outstandingCents'],
    (j['tenders'] as List).map((t) => Tender.fromJson(t)).toList(),
    j['split'] == null ? null : SplitInfo.fromJson(j['split']),
    subtotalCents: j['subtotalCents'],
    taxes: TaxLine.listFrom(j['taxes']),
    ageCheckRequired: j['ageCheckRequired'] ?? false,
    ageCleared: j['ageCleared'] ?? true,
    ageCheckFailed: j['ageCheckFailed'] ?? false,
    cashDueCents: j['cashDueCents'],
    cashRoundingCents: j['cashRoundingCents'] ?? 0,
  );
}

/// The outcome of one ID check (the store keeps only this).
class AgeCheckResult {
  final bool passed;
  final int? ageYears;
  final int legalAge;

  /// under_age | expired | unreadable | not_confirmed; null when passed.
  final String? reason;
  final Check check;
  AgeCheckResult(
    this.passed,
    this.ageYears,
    this.legalAge,
    this.reason,
    this.check,
  );
  factory AgeCheckResult.fromJson(Map<String, dynamic> j) => AgeCheckResult(
    j['passed'] ?? false,
    j['ageYears'],
    j['legalAge'] ?? 21,
    j['reason'],
    Check.fromJson(j['check']),
  );
}

class ShiftInfo {
  final int id;
  final String status, openedAt, openedBy;
  final int openingFloatCents;
  ShiftInfo(
    this.id,
    this.status,
    this.openedAt,
    this.openedBy,
    this.openingFloatCents,
  );
  factory ShiftInfo.fromJson(Map<String, dynamic> j) => ShiftInfo(
    j['id'],
    j['status'],
    j['openedAt'],
    j['openedBy'],
    j['openingFloatCents'],
  );
}

class TenderSummary {
  final String type;
  final int amountCents, count;
  TenderSummary(this.type, this.amountCents, this.count);
  factory TenderSummary.fromJson(Map<String, dynamic> j) =>
      TenderSummary(j['type'], j['amountCents'], j['count']);
}

class ItemMixEntry {
  final String itemId, nameFr, nameEn;
  final int qty, revenueCents;
  ItemMixEntry(
    this.itemId,
    this.nameFr,
    this.nameEn,
    this.qty,
    this.revenueCents,
  );
  factory ItemMixEntry.fromJson(Map<String, dynamic> j) => ItemMixEntry(
    j['itemId'],
    j['nameFr'],
    j['nameEn'] ?? j['nameFr'],
    j['qty'],
    j['revenueCents'],
  );
}

class VoidEntry {
  final int checkId;
  final String reason, voidedBy;
  VoidEntry(this.checkId, this.reason, this.voidedBy);
  factory VoidEntry.fromJson(Map<String, dynamic> j) =>
      VoidEntry(j['checkId'], j['reason'], j['voidedBy']);
}

class ShiftReport {
  final int shiftId;
  final String shiftStatus, openedAt, openedBy;
  final int openingFloatCents,
      revenueCents,
      transactionCount,
      avgCheckCents,
      corkageCents;
  final List<TenderSummary> tenderBreakdown;
  final List<ItemMixEntry> itemMix;
  final List<VoidEntry> voids;

  /// Non-sale cash movements + refunds posted to the shift (feed expected cash).
  final int cashPaidInCents,
      cashPaidOutCents,
      cashRefundCents,
      refundTotalCents;
  final int? expectedCashCents, closingCountCents, overShortCents;

  /// Net cash nickel rounding over the shift (signed; 0 on older servers).
  final int cashRoundingCents;
  ShiftReport(
    this.shiftId,
    this.shiftStatus,
    this.openedAt,
    this.openedBy,
    this.openingFloatCents,
    this.revenueCents,
    this.transactionCount,
    this.avgCheckCents,
    this.corkageCents,
    this.tenderBreakdown,
    this.itemMix,
    this.voids,
    this.cashPaidInCents,
    this.cashPaidOutCents,
    this.cashRefundCents,
    this.refundTotalCents,
    this.expectedCashCents,
    this.closingCountCents,
    this.overShortCents, {
    this.cashRoundingCents = 0,
  });
  factory ShiftReport.fromJson(Map<String, dynamic> j) => ShiftReport(
    j['shiftId'],
    j['shiftStatus'],
    j['openedAt'],
    j['openedBy'],
    j['openingFloatCents'],
    j['revenueCents'],
    j['transactionCount'],
    j['avgCheckCents'],
    j['corkageCents'],
    (j['tenderBreakdown'] as List)
        .map((t) => TenderSummary.fromJson(t))
        .toList(),
    (j['itemMix'] as List).map((i) => ItemMixEntry.fromJson(i)).toList(),
    (j['voids'] as List).map((v) => VoidEntry.fromJson(v)).toList(),
    j['cashPaidInCents'] ?? 0,
    j['cashPaidOutCents'] ?? 0,
    j['cashRefundCents'] ?? 0,
    j['refundTotalCents'] ?? 0,
    j['expectedCashCents'],
    j['closingCountCents'],
    j['overShortCents'],
    cashRoundingCents: j['cashRoundingCents'] ?? 0,
  );
}

/// A CLOSED check in the refund picker: what it was, what's left to refund.
class ClosedCheckSummary {
  final int id;
  final String tableLabel, closedAt;
  final int grandTotalCents, refundedCents, refundableCents;
  ClosedCheckSummary(
    this.id,
    this.tableLabel,
    this.closedAt,
    this.grandTotalCents,
    this.refundedCents,
    this.refundableCents,
  );
  factory ClosedCheckSummary.fromJson(Map<String, dynamic> j) =>
      ClosedCheckSummary(
        j['id'],
        j['tableLabel'],
        j['closedAt'],
        j['grandTotalCents'],
        j['refundedCents'],
        j['refundableCents'],
      );
}

class RefundView {
  final int id, checkId, grossCents, netCents, taxCents;
  final String tenderType, reason, refundedBy, createdAt;

  /// Cash refunds only: signed nickel rounding, and the cash actually handed
  /// back (gross + rounding). Older servers omit them: no rounding, = gross.
  final int roundingAdjustmentCents, paidOutCents;
  RefundView(
    this.id,
    this.checkId,
    this.grossCents,
    this.netCents,
    this.taxCents,
    this.tenderType,
    this.reason,
    this.refundedBy,
    this.createdAt, {
    this.roundingAdjustmentCents = 0,
    int? paidOutCents,
  }) : paidOutCents = paidOutCents ?? grossCents + roundingAdjustmentCents;
  factory RefundView.fromJson(Map<String, dynamic> j) => RefundView(
    j['id'],
    j['checkId'],
    j['grossCents'],
    j['netCents'],
    j['taxCents'],
    j['tenderType'],
    j['reason'],
    j['refundedBy'],
    j['createdAt'],
    roundingAdjustmentCents: j['roundingAdjustmentCents'] ?? 0,
    paidOutCents: j['paidOutCents'],
  );
}

class RefundInfo {
  final int checkId, grandTotalCents, refundedCents, refundableCents;
  final List<RefundView> refunds;

  /// Still refundable to a Stripe card on this check (0 = no Stripe tender).
  final int stripeRefundableCents;
  RefundInfo(
    this.checkId,
    this.grandTotalCents,
    this.refundedCents,
    this.refundableCents,
    this.refunds, {
    this.stripeRefundableCents = 0,
  });
  factory RefundInfo.fromJson(Map<String, dynamic> j) => RefundInfo(
    j['checkId'],
    j['grandTotalCents'],
    j['refundedCents'],
    j['refundableCents'],
    (j['refunds'] as List).map((r) => RefundView.fromJson(r)).toList(),
    stripeRefundableCents: j['stripeRefundableCents'] ?? 0,
  );
}

class RefundResult {
  final RefundView refund;
  final Check check;
  final String slipText;
  RefundResult(this.refund, this.check, this.slipText);
  factory RefundResult.fromJson(Map<String, dynamic> j) => RefundResult(
    RefundView.fromJson(j['refund']),
    Check.fromJson(j['check']),
    j['slipText'],
  );
}

class CashMovement {
  final int id;
  final int? shiftId;
  final String direction, reason, createdBy, createdAt;
  final int amountCents;
  CashMovement(
    this.id,
    this.shiftId,
    this.direction,
    this.amountCents,
    this.reason,
    this.createdBy,
    this.createdAt,
  );
  factory CashMovement.fromJson(Map<String, dynamic> j) => CashMovement(
    j['id'],
    j['shiftId'],
    j['direction'],
    j['amountCents'],
    j['reason'],
    j['createdBy'],
    j['createdAt'],
  );
}

class CashMovementResult {
  final CashMovement movement;
  final String slipText;
  CashMovementResult(this.movement, this.slipText);
  factory CashMovementResult.fromJson(Map<String, dynamic> j) =>
      CashMovementResult(CashMovement.fromJson(j['movement']), j['slipText']);
}

class DisplayField {
  final String labelFr, labelEn, value;
  DisplayField(this.labelFr, this.labelEn, this.value);
  factory DisplayField.fromJson(Map<String, dynamic> j) =>
      DisplayField(j['labelFr'], j['labelEn'], j['value']);
}

class TenderInstructions {
  final String type;
  final int amountCents;
  final String? qrPayload;
  final List<DisplayField> displayFields;
  TenderInstructions(
    this.type,
    this.amountCents,
    this.qrPayload,
    this.displayFields,
  );
  factory TenderInstructions.fromJson(Map<String, dynamic> j) =>
      TenderInstructions(
        j['type'],
        j['amountCents'],
        j['qrPayload'],
        ((j['displayFields'] ?? []) as List)
            .map((f) => DisplayField.fromJson(f))
            .toList(),
      );
}

class TenderResult {
  final Tender tender;
  final Check check;
  TenderResult(this.tender, this.check);
}

/// GET /stripe/status. [configured] false → the option is not shown at all;
/// configured but not [available] → shown greyed out with a hint ([reason]).
class StripeStatus {
  final bool configured, available;
  final String? reason, currency, locationId;
  const StripeStatus({
    required this.configured,
    required this.available,
    this.reason,
    this.currency,
    this.locationId,
  });
  static const off = StripeStatus(
    configured: false,
    available: false,
    reason: 'stripe_not_configured',
  );
  factory StripeStatus.unavailable(String? reason) => StripeStatus(
    configured: true,
    available: false,
    reason: reason ?? 'stripe_unavailable',
  );
  factory StripeStatus.fromJson(Map<String, dynamic> j) => StripeStatus(
    configured: j['configured'] == true,
    available: j['available'] == true,
    reason: j['reason'],
    currency: j['currency'],
    locationId: j['locationId'],
  );
}

class StripeIntent {
  final String paymentId, paymentIntentId, clientSecret, currency;
  final String? locationId;
  final int amountCents;
  StripeIntent(
    this.paymentId,
    this.paymentIntentId,
    this.clientSecret,
    this.amountCents,
    this.currency,
    this.locationId,
  );
  factory StripeIntent.fromJson(Map<String, dynamic> j) => StripeIntent(
    j['paymentId'],
    j['paymentIntentId'],
    j['clientSecret'],
    j['amountCents'],
    j['currency'],
    j['locationId'],
  );
}

/// GET /ai-photos/status. [configured] false → no AI buttons at all (the
/// add-on is not on for this client); configured but not [available] → the
/// buttons show disabled with a note ([reason]: image_offline, image_key_missing…).
class AiPhotoStatus {
  final bool configured, available;
  final String? reason, provider;
  final int defaultCount;
  const AiPhotoStatus({
    required this.configured,
    required this.available,
    this.reason,
    this.provider,
    this.defaultCount = 3,
  });
  static const hidden = AiPhotoStatus(
    configured: false,
    available: false,
    reason: 'image_generation_off',
  );
  factory AiPhotoStatus.unavailable(String? reason) => AiPhotoStatus(
    configured: true,
    available: false,
    reason: reason ?? 'image_offline',
  );
  factory AiPhotoStatus.fromJson(Map<String, dynamic> j) => AiPhotoStatus(
    configured: j['configured'] == true,
    available: j['available'] == true,
    reason: j['reason'],
    provider: j['provider'],
    defaultCount: (j['defaultCount'] as num?)?.toInt() ?? 3,
  );
}

class AiPhotoCandidate {
  final String id, contentType;
  final Uint8List bytes;
  AiPhotoCandidate(this.id, this.contentType, this.bytes);
}

/// 2–4 pictures to pick from; [source] is what choosing one records.
class AiPhotoCandidates {
  final String itemId, provider, source;
  final int elapsedMs;
  final double estimatedCostUsd;
  final List<AiPhotoCandidate> candidates;
  AiPhotoCandidates(
    this.itemId,
    this.provider,
    this.source,
    this.elapsedMs,
    this.estimatedCostUsd,
    this.candidates,
  );
  factory AiPhotoCandidates.fromJson(Map<String, dynamic> j) =>
      AiPhotoCandidates(
        j['itemId'],
        j['provider'],
        j['source'],
        (j['elapsedMs'] as num?)?.toInt() ?? 0,
        (j['estimatedCostUsd'] as num?)?.toDouble() ?? 0,
        [
          for (final c in (j['candidates'] as List))
            AiPhotoCandidate(
              c['id'],
              c['contentType'],
              base64Decode(c['dataBase64']),
            ),
        ],
      );
}
