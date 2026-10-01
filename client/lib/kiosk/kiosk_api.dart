import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../api.dart' show Item, Category;
import '../menu_changes.dart' show RejectedLine;

/// What a kiosk order came back as: the guest's order number (#101), the
/// same one they pay with at the counter and are called by at pickup.
class KioskOrderResult {
  final int orderNumber, totalCents;
  final bool idCheckAtCounter;

  /// What the kiosk shows ("#101"); older stores send none.
  final String? displayNumber;

  /// The store printed the guest a ticket: "Take your ticket to the counter".
  final bool ticket;

  /// Lines the store left out because the menu changed under the order
  /// (taken off, or a new price); the totals cover only what was placed.
  final List<RejectedLine> rejected;
  const KioskOrderResult(
    this.orderNumber,
    this.totalCents,
    this.idCheckAtCounter, {
    this.displayNumber,
    this.ticket = false,
    this.rejected = const [],
  });
  factory KioskOrderResult.fromJson(Map<String, dynamic> j) => KioskOrderResult(
    (j['orderNumber'] as num).toInt(),
    (j['totalCents'] as num? ?? 0).toInt(),
    j['idCheckAtCounter'] == true,
    displayNumber: j['displayNumber'] as String?,
    ticket: j['ticket'] == true,
    rejected: RejectedLine.listFrom(j),
  );

  String get label => displayNumber ?? '$orderNumber';
}

class KioskConfig {
  final String storeName, currency;
  final List<String> locales;

  /// The legal drinking age the ID note names; older stores send none (21).
  final int legalAge;
  const KioskConfig(
    this.storeName,
    this.currency,
    this.locales, {
    this.legalAge = 21,
  });
  factory KioskConfig.fromJson(Map<String, dynamic> j) => KioskConfig(
    j['storeName'] as String? ?? '',
    j['currency'] as String? ?? 'USD',
    [
      for (final l in (j['locales'] as List? ?? const ['en', 'fr']))
        if (l is String) l,
    ],
    legalAge: (j['legalAge'] as num?)?.toInt() ?? 21,
  );
}

/// One row of the "Add a drink?" step, as the store picked it: why
/// ("drink", "side", "dessert"), the category, and its items, best first.
class KioskUpsellRow {
  final String reason, categoryId;
  final List<String> itemIds;
  const KioskUpsellRow(this.reason, this.categoryId, this.itemIds);
  factory KioskUpsellRow.fromJson(Map<String, dynamic> j) => KioskUpsellRow(
    j['reason'] as String? ?? '',
    j['categoryId'] as String? ?? '',
    [
      for (final i in (j['itemIds'] as List? ?? const []))
        if (i is String) i,
    ],
  );
}

class KioskApiException implements Exception {
  final int status;
  final String? code;
  final String message;

  /// lines_rejected: every line the store refused (nothing was placed).
  final List<RejectedLine> rejected;
  const KioskApiException(
    this.status,
    this.code,
    this.message, {
    this.rejected = const [],
  });

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

  Future<dynamic> _send(
    String method,
    String path, [
    Object? body,
    Duration timeout = _timeout,
  ]) async {
    final uri = Uri.parse('$baseUrl$path');
    final res =
        await (method == 'GET'
                ? _http.get(uri, headers: _headers)
                : _http.post(
                    uri,
                    headers: _headers,
                    body: jsonEncode(body ?? {}),
                  ))
            .timeout(timeout);
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
      rejected: RejectedLine.listFrom(m),
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

  /// The store's menu version (it moves on any menu change); null when the
  /// store is too old to say or can't be reached.
  Future<int?> menuVersion() async {
    try {
      final j = await _send(
        'GET',
        '/menu/version',
        null,
        const Duration(seconds: 4),
      );
      return j is Map ? (j['version'] as num?)?.toInt() : null;
    } catch (_) {
      return null;
    }
  }

  /// [mode] DINE_IN | TAKE_OUT. Lines: itemId, variantId, qty. [lang]: the
  /// guest's language, for the ticket the store prints. [clientOrderId]: this
  /// order's own id; sent again, the store returns the order it already
  /// placed instead of a second one (a double tap, a retry).
  Future<KioskOrderResult> placeOrder(
    String mode,
    List<Map<String, dynamic>> lines, {
    String? lang,
    String? clientOrderId,
  }) async => KioskOrderResult.fromJson(
    await _send('POST', '/kiosk/orders', {
          'serviceMode': mode,
          'lines': lines,
          'lang': ?lang,
          'clientOrderId': ?clientOrderId,
        })
        as Map<String, dynamic>,
  );

  /// The store's "Add a drink?" rows for this cart (none: straight to the
  /// cart). Quick: the guest is waiting on it.
  Future<List<KioskUpsellRow>> upsell(List<Map<String, dynamic>> lines) async {
    final j = await _send('POST', '/kiosk/upsell', {
      'lines': lines,
    }, const Duration(seconds: 3));
    return [
      for (final r in ((j as Map?)?['rows'] as List? ?? const []))
        if (r is Map<String, dynamic>) KioskUpsellRow.fromJson(r),
    ];
  }

  /// A tile-sized photo (the store downsizes), or null when the item has none.
  String? photoUrl(Item item, {int width = 480}) => item.photoVersion == null
      ? null
      : '$baseUrl/photos/${item.id}?v=${item.photoVersion}&w=$width';
}
