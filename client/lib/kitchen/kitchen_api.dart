part of '../api.dart';

/// Kitchen tickets and the kitchen screen (store config `kitchen.printing=on`).
/// Every call here is only made when [StoreProfile.kitchenPrinting] is true;
/// with it off the terminal never shows a kitchen control.
class KitchenApi {
  KitchenApi._();

  static bool get enabled => StoreProfile.current.kitchenPrinting;

  // --- the check screen
  static Future<KitchenCheckState> checkState(int checkId) async =>
      KitchenCheckState.fromJson(await Api._get('/checks/$checkId/kitchen'));

  static Future<KitchenSendResult> send(int checkId) async =>
      KitchenSendResult.fromJson(
        await Api._post('/checks/$checkId/kitchen/send'),
      );

  static Future<KitchenSendResult> reprint(int checkId) async =>
      KitchenSendResult.fromJson(
        await Api._post('/checks/$checkId/kitchen/reprint'),
      );

  static Future<KitchenCheckState> setGuests(int checkId, int? guests) async =>
      KitchenCheckState.fromJson(
        await Api._post('/checks/$checkId/kitchen/guests', {'guests': guests}),
      );

  /// Best effort, never throws: the order is already on the bill, and a
  /// printer problem shows in the banner instead of blocking staff.
  static Future<void> sendQuietly(int checkId) async {
    if (!enabled) return;
    try {
      await send(checkId).timeout(const Duration(seconds: 8));
    } catch (_) {}
  }

  // --- the print queue
  static Future<KitchenQueueStatus> status() async =>
      KitchenQueueStatus.fromJson(await Api._get('/kitchen/status'));

  static Future<void> retry() async => Api._post('/kitchen/queue/retry');

  static Future<void> cancel({String? jobId}) async =>
      Api._post('/kitchen/queue/cancel', {'jobId': jobId});

  // --- station setup (manager)
  static Future<KitchenConfig> config() async =>
      KitchenConfig.fromJson(await Api._get('/kitchen/config'));

  static Future<KitchenSettings> saveSettings(KitchenSettings s) async =>
      KitchenSettings.fromJson(await Api._put('/kitchen/settings', s.toJson()));

  static Future<KitchenStation> saveStation(KitchenStation s) async =>
      KitchenStation.fromJson(await Api._post('/kitchen/stations', s.toJson()));

  static Future<KitchenConfig> deleteStation(String id) async =>
      KitchenConfig.fromJson(await Api._delete('/kitchen/stations/$id'));

  /// [stationId] null removes the mapping; '' = no ticket.
  static Future<KitchenConfig> setRoute(
    String kind,
    String refId,
    String? stationId,
  ) async => KitchenConfig.fromJson(
    await Api._put('/kitchen/routes', {
      'kind': kind,
      'refId': refId,
      'stationId': stationId,
    }),
  );

  static Future<KitchenTestResult> testStation(String id) async =>
      KitchenTestResult.fromJson(await Api._post('/kitchen/stations/$id/test'));

  // --- the kitchen screen
  static Future<KdsBoard> board({String? station}) async => KdsBoard.fromJson(
    await Api._get(
      station == null || station.isEmpty
          ? '/kitchen/board'
          : '/kitchen/board?station=${Uri.encodeQueryComponent(station)}',
    ),
  );

  static Future<void> bump(int checkId, String stationId) async => Api._post(
    '/kitchen/board/bump',
    {'checkId': checkId, 'stationId': stationId},
  );

  static Future<void> recall({String? bumpId, String? stationId}) async =>
      Api._post('/kitchen/board/recall', {
        'bumpId': bumpId,
        'stationId': stationId,
      });
}

int _int(dynamic v, [int fallback = 0]) => v is int ? v : fallback;
String _str(dynamic v, [String fallback = '']) => v is String ? v : fallback;
String? _strOrNull(dynamic v) => v is String ? v : null;

class KitchenCheckState {
  final int checkId;
  final int? guests;
  final int unsent, pendingVoids, sent;
  const KitchenCheckState(
    this.checkId,
    this.guests,
    this.unsent,
    this.pendingVoids,
    this.sent,
  );

  /// Something a Send would print (new items or voids).
  bool get hasChanges => unsent > 0 || pendingVoids > 0;

  factory KitchenCheckState.fromJson(Map<String, dynamic> j) =>
      KitchenCheckState(
        _int(j['checkId']),
        j['guests'] is int ? j['guests'] as int : null,
        _int(j['unsent']),
        _int(j['pendingVoids']),
        _int(j['sent']),
      );
}

class KitchenSendResult {
  final int tickets, printJobs, voided;
  final List<String> stations;
  const KitchenSendResult(
    this.tickets,
    this.printJobs,
    this.voided,
    this.stations,
  );
  factory KitchenSendResult.fromJson(Map<String, dynamic> j) =>
      KitchenSendResult(
        _int(j['tickets']),
        _int(j['printJobs']),
        _int(j['voided']),
        ((j['stations'] as List?) ?? const []).whereType<String>().toList(),
      );
}

class KitchenStationHealth {
  final String stationId, nameFr, nameEn;
  final int waiting;
  final String? lastError;
  final bool online;
  const KitchenStationHealth(
    this.stationId,
    this.nameFr,
    this.nameEn,
    this.waiting,
    this.lastError,
    this.online,
  );
  factory KitchenStationHealth.fromJson(Map<String, dynamic> j) =>
      KitchenStationHealth(
        _str(j['stationId']),
        _str(j['nameFr']),
        _str(j['nameEn']),
        _int(j['waiting']),
        _strOrNull(j['lastError']),
        j['online'] != false,
      );
}

class KitchenQueueStatus {
  final bool enabled, failing;
  final int waiting;
  final String? lastError;
  final List<KitchenStationHealth> stations;
  const KitchenQueueStatus(
    this.enabled,
    this.waiting,
    this.failing,
    this.lastError,
    this.stations,
  );
  factory KitchenQueueStatus.fromJson(Map<String, dynamic> j) =>
      KitchenQueueStatus(
        j['enabled'] == true,
        _int(j['waiting']),
        j['failing'] == true,
        _strOrNull(j['lastError']),
        ((j['stations'] as List?) ?? const [])
            .map((s) => KitchenStationHealth.fromJson(s))
            .toList(),
      );
}

class KitchenStation {
  final String id, nameFr, nameEn, output, printerHost;
  final int printerPort, paperMm, sortOrder;
  const KitchenStation({
    this.id = '',
    required this.nameFr,
    required this.nameEn,
    this.output = 'printer',
    this.printerHost = '',
    this.printerPort = 9100,
    this.paperMm = 80,
    this.sortOrder = 0,
  });

  bool get prints => output != 'screen';
  bool get onScreen => output != 'printer';

  factory KitchenStation.fromJson(Map<String, dynamic> j) => KitchenStation(
    id: _str(j['id']),
    nameFr: _str(j['nameFr']),
    nameEn: _str(j['nameEn']),
    output: _str(j['output'], 'printer'),
    printerHost: _str(j['printerHost']),
    printerPort: _int(j['printerPort'], 9100),
    paperMm: _int(j['paperMm'], 80),
    sortOrder: _int(j['sortOrder']),
  );

  Map<String, dynamic> toJson() => {
    'id': id,
    'nameFr': nameFr,
    'nameEn': nameEn,
    'output': output,
    'printerHost': printerHost,
    'printerPort': printerPort,
    'paperMm': paperMm,
    'sortOrder': sortOrder,
  };
}

class KitchenRoute {
  final String kind, refId, stationId;
  const KitchenRoute(this.kind, this.refId, this.stationId);
  factory KitchenRoute.fromJson(Map<String, dynamic> j) =>
      KitchenRoute(_str(j['kind']), _str(j['refId']), _str(j['stationId']));
}

class KitchenSettings {
  final String language, defaultStationId;
  final int warnMinutes, lateMinutes;
  final bool sound;
  const KitchenSettings({
    this.language = '',
    this.defaultStationId = '',
    this.warnMinutes = 10,
    this.lateMinutes = 20,
    this.sound = true,
  });

  KitchenSettings copyWith({
    String? language,
    String? defaultStationId,
    int? warnMinutes,
    int? lateMinutes,
    bool? sound,
  }) => KitchenSettings(
    language: language ?? this.language,
    defaultStationId: defaultStationId ?? this.defaultStationId,
    warnMinutes: warnMinutes ?? this.warnMinutes,
    lateMinutes: lateMinutes ?? this.lateMinutes,
    sound: sound ?? this.sound,
  );

  factory KitchenSettings.fromJson(Map<String, dynamic> j) => KitchenSettings(
    language: _str(j['language']),
    defaultStationId: _str(j['defaultStationId']),
    warnMinutes: _int(j['warnMinutes'], 10),
    lateMinutes: _int(j['lateMinutes'], 20),
    sound: j['sound'] != false,
  );

  Map<String, dynamic> toJson() => {
    'language': language,
    'defaultStationId': defaultStationId,
    'warnMinutes': warnMinutes,
    'lateMinutes': lateMinutes,
    'sound': sound,
  };
}

class KitchenConfig {
  final KitchenSettings settings;
  final String language;
  final List<KitchenStation> stations;
  final List<KitchenRoute> routes;
  const KitchenConfig(this.settings, this.language, this.stations, this.routes);

  /// The station a category / item is mapped to: null = not mapped, '' = no ticket.
  String? routeFor(String kind, String refId) {
    for (final r in routes) {
      if (r.kind == kind && r.refId == refId) return r.stationId;
    }
    return null;
  }

  factory KitchenConfig.fromJson(Map<String, dynamic> j) => KitchenConfig(
    KitchenSettings.fromJson(j['settings'] as Map<String, dynamic>),
    _str(j['language'], 'fr'),
    ((j['stations'] as List?) ?? const [])
        .map((s) => KitchenStation.fromJson(s))
        .toList(),
    ((j['routes'] as List?) ?? const [])
        .map((r) => KitchenRoute.fromJson(r))
        .toList(),
  );
}

class KitchenTestResult {
  final bool ok, configured;
  final String target;
  final String? error;
  const KitchenTestResult(this.ok, this.configured, this.target, this.error);
  factory KitchenTestResult.fromJson(Map<String, dynamic> j) =>
      KitchenTestResult(
        j['ok'] == true,
        j['configured'] == true,
        _str(j['target']),
        _strOrNull(j['error']),
      );
}

class KdsItem {
  final int lineId, qty, voidedQty;
  final String nameFr, nameEn;
  final String? variantFr, variantEn, note;
  final bool add, voided;
  const KdsItem({
    required this.lineId,
    required this.qty,
    required this.nameFr,
    required this.nameEn,
    this.variantFr,
    this.variantEn,
    this.note,
    this.add = false,
    this.voided = false,
    this.voidedQty = 0,
  });

  /// Still to make: a partly voided line shows what's left.
  int get remaining => voided ? qty : qty - voidedQty;

  factory KdsItem.fromJson(Map<String, dynamic> j) => KdsItem(
    lineId: _int(j['lineId']),
    qty: _int(j['qty']),
    nameFr: _str(j['nameFr']),
    nameEn: _str(j['nameEn']),
    variantFr: _strOrNull(j['variantFr']),
    variantEn: _strOrNull(j['variantEn']),
    note: _strOrNull(j['note']),
    add: j['add'] == true,
    voided: j['voided'] == true,
    voidedQty: _int(j['voidedQty']),
  );
}

class KdsCard {
  final String key, stationId, stationNameFr, stationNameEn;
  final String tableLabel, serverName, level;
  final int checkId;
  final int? guests;
  final int elapsedSeconds;
  final List<KdsItem> items;
  const KdsCard({
    required this.key,
    required this.checkId,
    required this.stationId,
    required this.stationNameFr,
    required this.stationNameEn,
    required this.tableLabel,
    required this.serverName,
    this.guests,
    required this.elapsedSeconds,
    required this.level,
    required this.items,
  });

  factory KdsCard.fromJson(Map<String, dynamic> j) => KdsCard(
    key: _str(j['key']),
    checkId: _int(j['checkId']),
    stationId: _str(j['stationId']),
    stationNameFr: _str(j['stationNameFr']),
    stationNameEn: _str(j['stationNameEn']),
    tableLabel: _str(j['tableLabel']),
    serverName: _str(j['serverName']),
    guests: j['guests'] is int ? j['guests'] as int : null,
    elapsedSeconds: _int(j['elapsedSeconds']),
    level: _str(j['level'], 'ok'),
    items: ((j['items'] as List?) ?? const [])
        .map((i) => KdsItem.fromJson(i))
        .toList(),
  );
}

class KdsBump {
  final String bumpId, stationId, tableLabel;
  final int checkId;
  const KdsBump(this.bumpId, this.checkId, this.stationId, this.tableLabel);
  factory KdsBump.fromJson(Map<String, dynamic> j) => KdsBump(
    _str(j['bumpId']),
    _int(j['checkId']),
    _str(j['stationId']),
    _str(j['tableLabel']),
  );
}

class KdsBoard {
  final List<KdsCard> cards;
  final List<KdsBump> recent;
  final List<KitchenStation> stations;
  final int warnMinutes, lateMinutes, latestTicket;
  final bool sound;
  final String language;
  const KdsBoard({
    this.cards = const [],
    this.recent = const [],
    this.stations = const [],
    this.warnMinutes = 10,
    this.lateMinutes = 20,
    this.latestTicket = 0,
    this.sound = true,
    this.language = 'fr',
  });

  /// The timer colour for [seconds] since the first send.
  String levelFor(int seconds) => seconds >= lateMinutes * 60
      ? 'late'
      : seconds >= warnMinutes * 60
      ? 'warn'
      : 'ok';

  factory KdsBoard.fromJson(Map<String, dynamic> j) => KdsBoard(
    cards: ((j['cards'] as List?) ?? const [])
        .map((c) => KdsCard.fromJson(c))
        .toList(),
    recent: ((j['recent'] as List?) ?? const [])
        .map((b) => KdsBump.fromJson(b))
        .toList(),
    stations: ((j['stations'] as List?) ?? const [])
        .map((s) => KitchenStation.fromJson(s))
        .toList(),
    warnMinutes: _int(j['warnMinutes'], 10),
    lateMinutes: _int(j['lateMinutes'], 20),
    latestTicket: _int(j['latestTicket']),
    sound: j['sound'] != false,
    language: _str(j['language'], 'fr'),
  );
}
