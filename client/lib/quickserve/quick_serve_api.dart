part of '../api.dart';

/// One quick-serve order (Copper Lantern Express) as the counter sees it.
class CounterOrder {
  final int checkId, orderNumber, totalCents, outstandingCents, itemCount;

  /// DINE_IN | TAKE_OUT
  final String serviceMode;

  /// POS | KIOSK
  final String source;

  /// NEW | PREPARING | READY | PICKED_UP
  final String status;

  /// The check's own status: OPEN (unpaid) | TOTAL_LOCKED | CLOSED (paid).
  final String checkStatus;
  final bool hasAlcohol;

  const CounterOrder({
    required this.checkId,
    required this.orderNumber,
    required this.serviceMode,
    required this.source,
    required this.status,
    required this.checkStatus,
    this.totalCents = 0,
    this.outstandingCents = 0,
    this.itemCount = 0,
    this.hasAlcohol = false,
  });

  factory CounterOrder.fromJson(Map<String, dynamic> j) => CounterOrder(
    checkId: j['checkId'] as int,
    orderNumber: j['orderNumber'] as int,
    serviceMode: j['serviceMode'] as String? ?? 'TAKE_OUT',
    source: j['source'] as String? ?? 'POS',
    status: j['status'] as String? ?? 'NEW',
    checkStatus: j['checkStatus'] as String? ?? 'OPEN',
    totalCents: (j['totalCents'] as num? ?? 0).toInt(),
    outstandingCents: (j['outstandingCents'] as num? ?? 0).toInt(),
    itemCount: (j['itemCount'] as num? ?? 0).toInt(),
    hasAlcohol: j['hasAlcohol'] == true,
  );

  bool get paid => checkStatus == 'CLOSED';
  bool get takeOut => serviceMode == 'TAKE_OUT';
  bool get fromKiosk => source == 'KIOSK';
}

/// The quick-serve counter's routes (only on a quick-serve store).
class QuickServeApi {
  QuickServeApi._();

  static bool get enabled => StoreProfile.current.isQuickServe;

  static Future<List<CounterOrder>> orders() async => [
    for (final o in (await Api._get('/counter/orders')) as List)
      CounterOrder.fromJson(o as Map<String, dynamic>),
  ];

  /// [mode] DINE_IN | TAKE_OUT
  static Future<CounterOrder> create(String mode) async =>
      CounterOrder.fromJson(
        await Api._post('/counter/orders', {'serviceMode': mode}),
      );

  /// Done ringing: to the kitchen, on the pickup board as preparing.
  static Future<CounterOrder> place(int checkId) async =>
      CounterOrder.fromJson(await Api._post('/counter/orders/$checkId/place'));

  static Future<CounterOrder> setStatus(int checkId, String status) async =>
      CounterOrder.fromJson(
        await Api._post('/counter/orders/$checkId/status', {'status': status}),
      );

  /// A one-time code for pairing a self-order kiosk (manager).
  static Future<({String code, int expiresInSeconds})> kioskCode() async {
    final j = await Api._post('/counter/kiosk-code') as Map<String, dynamic>;
    return (
      code: j['code'] as String,
      expiresInSeconds: (j['expiresInSeconds'] as num? ?? 600).toInt(),
    );
  }
}
