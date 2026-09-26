part of '../api.dart';

/// The forecourt (a gas station, `StoreProfile.forecourt`): the pumps as the
/// store sees them through its forecourt controller, and the cashier's
/// commands. The store does the talking to the controller; the terminal only
/// ever talks to its store. Units as on the wire: gallons in thousandths,
/// prices per gallon in thousandths of a dollar, money in cents.
class ForecourtApi {
  ForecourtApi._();

  static bool get enabled => StoreProfile.current.forecourt;

  /// The pump grid's poll: one quick GET, no retries (the next poll is the retry).
  static Future<Forecourt> state() async {
    final res = await Api._send(
      () => http.get(
        Uri.parse('${Api.baseUrl}/forecourt'),
        headers: Api._headers,
      ),
      timeout: const Duration(seconds: 3),
      operation: 'GET /forecourt',
    );
    Api._throwOnError(res);
    return Forecourt.fromJson(jsonDecode(utf8.decode(res.bodyBytes)));
  }

  static Future<Forecourt> _cmd(String path) async =>
      Forecourt.fromJson(await Api._post(path));

  /// Postpay: release the pump; the customer pays inside afterwards.
  static Future<Forecourt> authorise(int pump) =>
      _cmd('/forecourt/pumps/$pump/authorise');
  static Future<Forecourt> stop(int pump) =>
      _cmd('/forecourt/pumps/$pump/stop');
  static Future<Forecourt> resume(int pump) =>
      _cmd('/forecourt/pumps/$pump/resume');
  static Future<Forecourt> reset(int pump) =>
      _cmd('/forecourt/pumps/$pump/reset');

  /// One pump, or every pump when [pump] is null.
  static Future<Forecourt> emergencyStop([int? pump]) => _cmd(
    pump == null
        ? '/forecourt/emergency-stop'
        : '/forecourt/pumps/$pump/emergency-stop',
  );

  static Future<Forecourt> cancelPrepay(int fuelSaleId) =>
      _cmd('/forecourt/prepays/$fuelSaleId/cancel');
  static Future<Forecourt> changeGiven(int fuelSaleId) =>
      _cmd('/forecourt/prepays/$fuelSaleId/change-given');

  /// A completed postpay fuelling onto the sale.
  static Future<Check> addFuel(int saleId, String trxId) async =>
      Check.fromJson(
        await Api._post('/retail/sales/$saleId/fuel', {'trxId': trxId}),
      );

  /// "$X on pump N": a prepay line; the pump starts once the sale is paid.
  static Future<Check> prepay(int saleId, int pump, int amountCents) async =>
      Check.fromJson(
        await Api._post('/retail/sales/$saleId/prepay', {
          'pump': pump,
          'amountCents': amountCents,
        }),
      );
}

class Forecourt {
  final bool online;
  final String? message;
  final List<FuelGradeInfo> grades;
  final List<PumpInfo> pumps;
  const Forecourt({
    required this.online,
    this.message,
    this.grades = const [],
    this.pumps = const [],
  });

  /// Before the first answer, or when the store itself can't be reached.
  static Forecourt offline(int pumps, {String? message}) => Forecourt(
    online: false,
    message: message,
    pumps: [for (var i = 1; i <= pumps; i++) PumpInfo.offline(i)],
  );

  factory Forecourt.fromJson(Map<String, dynamic> j) => Forecourt(
    online: j['online'] == true,
    message: j['message'] as String?,
    grades: [
      for (final g in (j['grades'] as List? ?? const []))
        FuelGradeInfo.fromJson(g as Map<String, dynamic>),
    ],
    pumps: [
      for (final p in (j['pumps'] as List? ?? const []))
        PumpInfo.fromJson(p as Map<String, dynamic>),
    ],
  );
}

class FuelGradeInfo {
  final String code, name, nameEs;
  final int priceMills;
  const FuelGradeInfo(this.code, this.name, this.nameEs, this.priceMills);
  factory FuelGradeInfo.fromJson(Map<String, dynamic> j) => FuelGradeInfo(
    j['code'] ?? '',
    j['name'] ?? '',
    j['nameEs'] ?? j['name'] ?? '',
    (j['priceMills'] as num?)?.toInt() ?? 0,
  );
}

/// One pump. [state]: IDLE | CALLING | AUTHORISED | FUELLING | SUSPENDED |
/// EMERGENCY_STOP | ERROR | OFFLINE.
class PumpInfo {
  final int pump;
  final String state;
  final int? nozzleUp;
  final bool flowing, live, limitReached;
  final String? grade, gradeName, mode, error;
  final int priceMills, volumeMilli, amountCents;
  final int? limitCents;
  final List<PayableFuel> payable;
  final PrepayInfo? prepay;
  final ChangeInfo? change;

  const PumpInfo({
    required this.pump,
    required this.state,
    this.nozzleUp,
    this.flowing = false,
    this.live = false,
    this.limitReached = false,
    this.grade,
    this.gradeName,
    this.mode,
    this.error,
    this.priceMills = 0,
    this.volumeMilli = 0,
    this.amountCents = 0,
    this.limitCents,
    this.payable = const [],
    this.prepay,
    this.change,
  });

  factory PumpInfo.offline(int n) => PumpInfo(pump: n, state: 'OFFLINE');

  factory PumpInfo.fromJson(Map<String, dynamic> j) {
    int i(String k) => (j[k] as num?)?.toInt() ?? 0;
    return PumpInfo(
      pump: i('pump'),
      state: j['state'] ?? 'OFFLINE',
      nozzleUp: (j['nozzleUp'] as num?)?.toInt(),
      flowing: j['flowing'] == true,
      live: j['live'] == true,
      limitReached: j['limitReached'] == true,
      grade: j['grade'],
      gradeName: j['gradeName'],
      mode: j['mode'],
      error: j['error'],
      priceMills: i('priceMills'),
      volumeMilli: i('volumeMilli'),
      amountCents: i('amountCents'),
      limitCents: (j['limitCents'] as num?)?.toInt(),
      payable: [
        for (final t in (j['payable'] as List? ?? const []))
          PayableFuel.fromJson(t as Map<String, dynamic>),
      ],
      prepay: j['prepay'] is Map<String, dynamic>
          ? PrepayInfo.fromJson(j['prepay'])
          : null,
      change: j['change'] is Map<String, dynamic>
          ? ChangeInfo.fromJson(j['change'])
          : null,
    );
  }

  bool get offline => state == 'OFFLINE';
  bool get stopped => state == 'EMERGENCY_STOP';
  bool get outOfService => offline || stopped || state == 'ERROR';

  /// Completed postpay sales not yet on any sale.
  List<PayableFuel> get unpaid => [
    for (final t in payable)
      if (t.saleId == null) t,
  ];
}

class PayableFuel {
  final String trxId, grade, gradeName, state;
  final int volumeMilli, priceMills, amountCents;
  final int? saleId;
  const PayableFuel({
    required this.trxId,
    required this.grade,
    required this.gradeName,
    required this.state,
    required this.volumeMilli,
    required this.priceMills,
    required this.amountCents,
    this.saleId,
  });
  factory PayableFuel.fromJson(Map<String, dynamic> j) => PayableFuel(
    trxId: j['trxId'] ?? '',
    grade: j['grade'] ?? '',
    gradeName: j['gradeName'] ?? j['grade'] ?? '',
    state: j['state'] ?? 'PAYABLE',
    volumeMilli: (j['volumeMilli'] as num?)?.toInt() ?? 0,
    priceMills: (j['priceMills'] as num?)?.toInt() ?? 0,
    amountCents: (j['amountCents'] as num?)?.toInt() ?? 0,
    saleId: (j['saleId'] as num?)?.toInt(),
  );
}

/// A prepay for a pump: IN_BASKET (on a sale, not paid yet), AUTHORISED (the
/// pump is released), AUTH_FAILED (paid, the pump didn't take it yet — retried).
class PrepayInfo {
  final int fuelSaleId, prepaidCents;
  final String status;
  final int? saleId;
  final String? error;
  const PrepayInfo(
    this.fuelSaleId,
    this.status,
    this.prepaidCents,
    this.saleId,
    this.error,
  );
  factory PrepayInfo.fromJson(Map<String, dynamic> j) => PrepayInfo(
    (j['fuelSaleId'] as num).toInt(),
    j['status'] ?? '',
    (j['prepaidCents'] as num?)?.toInt() ?? 0,
    (j['saleId'] as num?)?.toInt(),
    j['error'],
  );
}

/// A finished prepay's change to hand back (already refunded on the sale).
class ChangeInfo {
  final int fuelSaleId, refundCents, dispensedCents, prepaidCents;
  final int? saleId;
  const ChangeInfo(
    this.fuelSaleId,
    this.refundCents,
    this.saleId,
    this.dispensedCents,
    this.prepaidCents,
  );
  factory ChangeInfo.fromJson(Map<String, dynamic> j) => ChangeInfo(
    (j['fuelSaleId'] as num).toInt(),
    (j['refundCents'] as num?)?.toInt() ?? 0,
    (j['saleId'] as num?)?.toInt(),
    (j['dispensedCents'] as num?)?.toInt() ?? 0,
    (j['prepaidCents'] as num?)?.toInt() ?? 0,
  );
}

/// A fuel or prepay line's facts (on [CheckLine.fuel]).
class FuelLine {
  final int fuelSaleId, pump;
  final String mode, status;
  final String? grade, gradeName;
  final int? volumeMilli, priceMills, prepaidCents;
  const FuelLine({
    required this.fuelSaleId,
    required this.pump,
    required this.mode,
    required this.status,
    this.grade,
    this.gradeName,
    this.volumeMilli,
    this.priceMills,
    this.prepaidCents,
  });
  bool get prepay => mode == 'PREPAY';
  factory FuelLine.fromJson(Map<String, dynamic> j) => FuelLine(
    fuelSaleId: (j['fuelSaleId'] as num).toInt(),
    pump: (j['pump'] as num).toInt(),
    mode: j['mode'] ?? 'POSTPAY',
    status: j['status'] ?? '',
    grade: j['grade'],
    gradeName: j['gradeName'],
    volumeMilli: (j['volumeMilli'] as num?)?.toInt(),
    priceMills: (j['priceMills'] as num?)?.toInt(),
    prepaidCents: (j['prepaidCents'] as num?)?.toInt(),
  );
}

/// "10.052" gallons.
String gallons(int volumeMilli) =>
    '${volumeMilli ~/ 1000}.${(volumeMilli % 1000).toString().padLeft(3, '0')}';

/// "$3.299" a gallon (US pump prices carry the tenth of a cent).
String pricePerGallon(int priceMills) =>
    '\$${priceMills ~/ 1000}.${(priceMills % 1000).toString().padLeft(3, '0')}';
