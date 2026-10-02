part of '../api.dart';

/// What the floor needs to show carry-out: how many orders are open, the
/// Carry-out spots on the plan, and whether the store turned on the header
/// button. Null from [CarryOutApi.summary] = the store has no carry-out.
class CarryOutSummary {
  final int openCount, spots;
  final bool headerButton;
  const CarryOutSummary({
    this.openCount = 0,
    this.spots = 0,
    this.headerButton = false,
  });
  factory CarryOutSummary.fromJson(Map<String, dynamic> j) => CarryOutSummary(
    openCount: (j['openCount'] as num? ?? 0).toInt(),
    spots: (j['spots'] as num? ?? 0).toInt(),
    headerButton: j['headerButton'] == true,
  );
}

/// Carry-out (to-go) at a table-service restaurant: numbered orders, the
/// quick-serve counter's ([CounterOrder]), on a normal check. Payment, the
/// kitchen send and the bill are the usual check calls on [CounterOrder.checkId].
class CarryOutApi {
  CarryOutApi._();

  static List<CounterOrder> _list(dynamic j) => [
    for (final o in j as List) CounterOrder.fromJson(o as Map<String, dynamic>),
  ];

  /// Null when the store has no carry-out (an older store, retail, quick serve).
  static Future<CarryOutSummary?> summary() async {
    try {
      return CarryOutSummary.fromJson(
        await Api._get('/carryout/summary') as Map<String, dynamic>,
      );
    } on SessionExpiredException {
      rethrow;
    } catch (_) {
      return null;
    }
  }

  /// Orders not picked up yet, oldest first.
  static Future<List<CounterOrder>> orders() async =>
      _list(await Api._get('/carryout/orders'));

  static Future<CounterOrder> order(int checkId) async =>
      CounterOrder.fromJson(await Api._get('/carryout/orders/$checkId'));

  /// A new order, numbered at once; name and phone are optional.
  static Future<CounterOrder> create({String? name, String? phone}) async =>
      CounterOrder.fromJson(
        await Api._post('/carryout/orders', {
          'customerName': ?name,
          'customerPhone': ?phone,
        }),
      );

  static Future<CounterOrder> setCustomer(
    int checkId, {
    String? name,
    String? phone,
  }) async => CounterOrder.fromJson(
    await Api._put('/carryout/orders/$checkId/customer', {
      'customerName': name,
      'customerPhone': phone,
    }),
  );

  /// PREPARING | READY | PICKED_UP (picked up only once paid).
  static Future<CounterOrder> setStatus(int checkId, String status) async =>
      CounterOrder.fromJson(
        await Api._post('/carryout/orders/$checkId/status', {'status': status}),
      );

  /// Drop an order with nothing on it.
  static Future<void> discard(int checkId) =>
      Api._post('/carryout/orders/$checkId/discard');

  static Future<bool> headerButton() async {
    final j = await Api._get('/carryout/settings') as Map<String, dynamic>;
    return j['headerButton'] == true;
  }

  static Future<bool> setHeaderButton(bool on) async {
    final j =
        await Api._put('/carryout/settings', {'headerButton': on})
            as Map<String, dynamic>;
    return j['headerButton'] == true;
  }
}
