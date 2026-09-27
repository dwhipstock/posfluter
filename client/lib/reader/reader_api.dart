import 'dart:convert';

import 'package:http/http.dart' as http;

/// A payment the store queued for this phone. [clientSecret] is only ever
/// handed to the Stripe Terminal SDK, never shown or logged.
class ReaderJob {
  final String paymentIntentId, clientSecret, currency, state;
  final int amountCents;
  final String? description;
  const ReaderJob({
    required this.paymentIntentId,
    required this.clientSecret,
    required this.amountCents,
    required this.currency,
    required this.state,
    this.description,
  });

  factory ReaderJob.fromJson(Map<String, dynamic> j) => ReaderJob(
    paymentIntentId: j['paymentIntentId'] as String,
    clientSecret: j['clientSecret'] as String,
    amountCents: (j['amountCents'] as num).toInt(),
    currency: (j['currency'] as String? ?? 'usd').toUpperCase(),
    state: j['state'] as String? ?? 'queued',
    description: j['description'] as String?,
  );

  /// Still for the phone to take (the POS hasn't cancelled it, it hasn't timed out).
  bool get active =>
      state == 'queued' || state == 'collecting' || state == 'processing';

  String get amountLabel {
    final c = amountCents.abs();
    final symbol = currency == 'USD' || currency == 'CAD' ? r'$' : '';
    return '$symbol${c ~/ 100}.${(c % 100).toString().padLeft(2, '0')}'
        '${symbol.isEmpty ? ' $currency' : ''}';
  }
}

class ReaderConfig {
  final String storeName, currency;
  final String? locationId, reason;
  final bool stripeAvailable, simulated;
  const ReaderConfig({
    required this.storeName,
    required this.currency,
    this.locationId,
    this.reason,
    this.stripeAvailable = false,
    this.simulated = true,
  });
  factory ReaderConfig.fromJson(Map<String, dynamic> j) => ReaderConfig(
    storeName: j['storeName'] as String? ?? 'Store',
    currency: j['currency'] as String? ?? 'USD',
    locationId: j['locationId'] as String?,
    reason: j['reason'] as String?,
    stripeAvailable: j['stripeAvailable'] == true,
    simulated: j['simulated'] != false,
  );
}

class ReaderApiException implements Exception {
  final int status;
  final String? code;
  final String message;
  const ReaderApiException(this.status, this.code, this.message);

  /// The store forgot this phone (unpaired, or another phone paired).
  bool get notPaired => status == 401;

  @override
  String toString() => 'ReaderApiException($status, $code, $message)';
}

/// The store's phone-reader API (/reader/...), with this phone's bearer token.
class ReaderApi {
  final String baseUrl;
  String? token;
  final http.Client _http;
  static const _timeout = Duration(seconds: 8);

  ReaderApi(this.baseUrl, {this.token, http.Client? client})
    : _http = client ?? http.Client();

  Map<String, String> get _headers => {
    'Content-Type': 'application/json',
    if (token != null) 'Authorization': 'Bearer $token',
  };

  Future<Map<String, dynamic>?> _send(
    String method,
    String path, [
    Object? body,
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
            .timeout(_timeout);
    if (res.statusCode == 204) return null;
    Map<String, dynamic>? j;
    try {
      final d = jsonDecode(res.body);
      if (d is Map<String, dynamic>) j = d;
    } catch (_) {}
    if (res.statusCode >= 200 && res.statusCode < 300) return j ?? {};
    throw ReaderApiException(
      res.statusCode,
      j?['code'] as String?,
      j?['error'] as String? ?? 'HTTP ${res.statusCode}',
    );
  }

  /// Pair with the 6-digit code the POS shows. Returns the store's name.
  Future<String> pair(String code, String deviceName) async {
    final j = await _send('POST', '/reader/pair', {
      'code': code.trim(),
      'deviceName': deviceName,
    });
    token = j!['token'] as String;
    return j['storeName'] as String? ?? 'Store';
  }

  Future<ReaderConfig> config() async =>
      ReaderConfig.fromJson((await _send('GET', '/reader/config'))!);

  Future<void> heartbeat(String state, {String? readerName, String? message}) =>
      _send('POST', '/reader/heartbeat', {
        'state': state,
        'readerName': ?readerName,
        'message': ?message,
      });

  Future<String> connectionToken() async =>
      (await _send('POST', '/reader/connection-token'))!['secret'] as String;

  /// The payment to take now, or null.
  Future<ReaderJob?> nextPayment() async {
    final j = await _send('GET', '/reader/payment');
    return j == null ? null : ReaderJob.fromJson(j);
  }

  Future<ReaderJob> payment(String paymentIntentId) async => ReaderJob.fromJson(
    (await _send('GET', '/reader/payments/$paymentIntentId'))!,
  );

  Future<void> report(
    String paymentIntentId,
    String status, {
    String? code,
    String? message,
    bool declined = false,
  }) => _send('POST', '/reader/payments/$paymentIntentId/result', {
    'status': status,
    'code': ?code,
    'message': ?message,
    'declined': declined,
  });
}
