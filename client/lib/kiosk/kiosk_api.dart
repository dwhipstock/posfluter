import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../api.dart' show Item, Category;

/// What a kiosk order came back as: the number the customer is called by.
class KioskOrderResult {
  final int orderNumber, totalCents;
  final bool idCheckAtCounter;
  const KioskOrderResult(
    this.orderNumber,
    this.totalCents,
    this.idCheckAtCounter,
  );
  factory KioskOrderResult.fromJson(Map<String, dynamic> j) => KioskOrderResult(
    (j['orderNumber'] as num).toInt(),
    (j['totalCents'] as num? ?? 0).toInt(),
    j['idCheckAtCounter'] == true,
  );
}

class KioskConfig {
  final String storeName, currency;
  final List<String> locales;
  const KioskConfig(this.storeName, this.currency, this.locales);
  factory KioskConfig.fromJson(Map<String, dynamic> j) => KioskConfig(
    j['storeName'] as String? ?? '',
    j['currency'] as String? ?? 'CAD',
    [
      for (final l in (j['locales'] as List? ?? const ['fr', 'en']))
        if (l is String) l,
    ],
  );
}

class KioskApiException implements Exception {
  final int status;
  final String? code;
  final String message;
  const KioskApiException(this.status, this.code, this.message);

  /// The store no longer knows this kiosk (never paired, or revoked).
  bool get notPaired => status == 401;

  @override
  String toString() => 'KioskApiException($status, $code, $message)';
}

/// The store's kiosk API over the LAN, with this kiosk's own device token
/// (X-Device-Token, from pairing). The menu is the store's open catalog.
class KioskApi {
  final String baseUrl;
  String? token;
  final http.Client _http;
  static const _timeout = Duration(seconds: 8);

  KioskApi(this.baseUrl, {this.token, http.Client? client})
    : _http = client ?? http.Client();

  Map<String, String> get _headers => {
    'Content-Type': 'application/json',
    'X-Device-Token': ?token,
  };

  Future<dynamic> _send(String method, String path, [Object? body]) async {
    final uri = Uri.parse('$baseUrl$path');
    final res =
        await (method == 'GET'
                ? _http.get(uri, headers: _headers)
                : _http.post(
                    uri,
                    headers: _headers,
                    body: jsonEncode(body ?? {}),
                  ))
            .timeout(_timeout);
    dynamic j;
    try {
      j = jsonDecode(utf8.decode(res.bodyBytes));
    } catch (_) {}
    if (res.statusCode >= 200 && res.statusCode < 300) return j;
    final m = j is Map ? j : const {};
    throw KioskApiException(
      res.statusCode,
      m['code'] as String?,
      m['error'] as String? ?? 'HTTP ${res.statusCode}',
    );
  }

  /// Is [base] a quick-serve store? (LAN discovery: skip the pubs and shops.)
  static Future<bool> isQuickServe(String base, {http.Client? client}) async {
    try {
      final res = await (client ?? http.Client())
          .get(Uri.parse('$base/health'))
          .timeout(const Duration(milliseconds: 800));
      if (res.statusCode != 200) return false;
      final j = jsonDecode(utf8.decode(res.bodyBytes));
      return j is Map && j['kind'] == 'quick-serve';
    } catch (_) {
      return false;
    }
  }

  /// Pair with the 6-digit code the POS shows. Returns the store's name.
  Future<String> pair(String code, {String deviceName = ''}) async {
    final j =
        await _send('POST', '/kiosk/pair', {
              'code': code.trim(),
              'deviceName': deviceName,
            })
            as Map<String, dynamic>;
    token = j['deviceToken'] as String;
    return j['storeName'] as String? ?? '';
  }

  Future<KioskConfig> config() async => KioskConfig.fromJson(
    await _send('GET', '/kiosk/config') as Map<String, dynamic>,
  );

  Future<List<Item>> items() async => [
    for (final i in (await _send('GET', '/items')) as List)
      Item.fromJson(i as Map<String, dynamic>),
  ];

  Future<List<Category>> categories() async => [
    for (final c in (await _send('GET', '/categories')) as List)
      Category.fromJson(c as Map<String, dynamic>),
  ];

  /// [mode] DINE_IN | TAKE_OUT. Lines: itemId, variantId, qty.
  Future<KioskOrderResult> placeOrder(
    String mode,
    List<Map<String, dynamic>> lines,
  ) async => KioskOrderResult.fromJson(
    await _send('POST', '/kiosk/orders', {'serviceMode': mode, 'lines': lines})
        as Map<String, dynamic>,
  );

  /// A tile-sized photo (the store downsizes), or null when the item has none.
  String? photoUrl(Item item, {int width = 480}) => item.photoVersion == null
      ? null
      : '$baseUrl/photos/${item.id}?v=${item.photoVersion}&w=$width';
}
