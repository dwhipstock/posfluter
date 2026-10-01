part of '../api.dart';

/// One quick-serve order (Copper Lantern Express) as the counter sees it.
class CounterOrder {
  final int checkId, totalCents, outstandingCents, itemCount;

  /// The customer's number (101...): a kiosk order's from when it is placed
  /// (on the guest's ticket), a counter order's from when it is paid.
  final int? orderNumber;

  /// An older kiosk order's waiting number (K12); new ones have [orderNumber].
  final int? kioskNumber;

  /// DINE_IN | TAKE_OUT
  final String serviceMode;

  /// POS | KIOSK
  final String source;

  /// DRAFT | WAITING (unpaid) | PREPARING | READY | PICKED_UP (paid)
  final String status;

  /// The check's own status: OPEN (unpaid) | TOTAL_LOCKED | CLOSED (paid).
  final String checkStatus;
  final bool hasAlcohol;

  const CounterOrder({
    required this.checkId,
    this.orderNumber,
    this.kioskNumber,
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
    orderNumber: (j['orderNumber'] as num?)?.toInt(),
    kioskNumber: (j['kioskNumber'] as num?)?.toInt(),
    serviceMode: j['serviceMode'] as String? ?? 'TAKE_OUT',
    source: j['source'] as String? ?? 'POS',
    status: j['status'] as String? ?? 'DRAFT',
    checkStatus: j['checkStatus'] as String? ?? 'OPEN',
    totalCents: (j['totalCents'] as num? ?? 0).toInt(),
    outstandingCents: (j['outstandingCents'] as num? ?? 0).toInt(),
    itemCount: (j['itemCount'] as num? ?? 0).toInt(),
    hasAlcohol: j['hasAlcohol'] == true,
  );

  bool get paid => checkStatus == 'CLOSED';
  bool get takeOut => serviceMode == 'TAKE_OUT';
  bool get fromKiosk => source == 'KIOSK';

  /// "#101" (a kiosk order from the start, a counter order once paid).
  String get label => orderNumber != null
      ? '#$orderNumber'
      : kioskNumber != null
      ? 'K$kioskNumber'
      : '';
}

/// The quick-serve counter's routes (only on a quick-serve store).
class QuickServeApi {
  QuickServeApi._();

  static bool get enabled => StoreProfile.current.isQuickServe;

  static List<CounterOrder> _list(dynamic j) => [
    for (final o in j as List) CounterOrder.fromJson(o as Map<String, dynamic>),
  ];

  /// The Orders panel: today's paid orders, newest first.
  static Future<List<CounterOrder>> orders() async =>
      _list(await Api._get('/counter/orders'));

  /// Kiosk orders waiting to be paid at the counter, oldest first.
  static Future<List<CounterOrder>> waiting() async =>
      _list(await Api._get('/counter/waiting'));

  static Future<CounterOrder> order(int checkId) async =>
      CounterOrder.fromJson(await Api._get('/counter/orders/$checkId'));

  /// A new order, stored with its first item ([mode] DINE_IN | TAKE_OUT).
  static Future<CounterOrder> create(
    String mode,
    String itemId,
    String variantId,
    int qty, {
    String? note,
    int? expectedPriceCents,
  }) async => CounterOrder.fromJson(
    await Api._post('/counter/orders', {
      'serviceMode': mode,
      'itemId': itemId,
      'variantId': variantId,
      'qty': qty,
      'note': ?note,
      'expectedPriceCents': ?expectedPriceCents,
    }),
  );

  /// Dine in / take out, until the order is paid.
  static Future<CounterOrder> setMode(int checkId, String mode) async =>
      CounterOrder.fromJson(
        await Api._post('/counter/orders/$checkId/mode', {'serviceMode': mode}),
      );

  /// Drop an unpaid order (nothing paid, nothing sent to the kitchen).
  static Future<void> discard(int checkId) =>
      Api._post('/counter/orders/$checkId/discard');

  /// A paid order: PREPARING | READY | PICKED_UP.
  static Future<CounterOrder> setStatus(int checkId, String status) async =>
      CounterOrder.fromJson(
        await Api._post('/counter/orders/$checkId/status', {'status': status}),
      );

  /// The counter's default dine in / take out (DINE_IN | TAKE_OUT).
  static Future<String> defaultMode() async {
    final j = await Api._get('/counter/settings') as Map<String, dynamic>;
    return j['defaultServiceMode'] as String? ?? 'TAKE_OUT';
  }

  /// Whether a kiosk order prints the guest's ticket (on unless turned off).
  static Future<bool> kioskTicket() async {
    final j = await Api._get('/counter/settings') as Map<String, dynamic>;
    return j['kioskTicket'] as bool? ?? true;
  }

  static Future<bool> setKioskTicket(bool on) async {
    final j =
        await Api._put('/counter/settings', {'kioskTicket': on})
            as Map<String, dynamic>;
    return j['kioskTicket'] as bool? ?? on;
  }

  static Future<String> setDefaultMode(String mode) async {
    final j =
        await Api._put('/counter/settings', {'defaultServiceMode': mode})
            as Map<String, dynamic>;
    return j['defaultServiceMode'] as String? ?? mode;
  }

  /// A one-time code for pairing a self-order kiosk (manager).
  static Future<({String code, int expiresInSeconds})> kioskCode() async {
    final j = await Api._post('/counter/kiosk-code') as Map<String, dynamic>;
    return (
      code: j['code'] as String,
      expiresInSeconds: (j['expiresInSeconds'] as num? ?? 600).toInt(),
    );
  }
}
