import 'dart:async';
import 'dart:convert';
import 'dart:io' show SocketException;

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import '../api.dart';

/// Where the queue keeps its state between launches. The app uses
/// [PrefsKeyValueStore]; tests (and a platform without storage) use
/// [MemoryKeyValueStore].
abstract class KeyValueStore {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
}

class MemoryKeyValueStore implements KeyValueStore {
  final Map<String, String> data = {};
  @override
  Future<String?> read(String key) async => data[key];
  @override
  Future<void> write(String key, String value) async => data[key] = value;
}

class PrefsKeyValueStore implements KeyValueStore {
  @override
  Future<String?> read(String key) async =>
      (await SharedPreferences.getInstance()).getString(key);
  @override
  Future<void> write(String key, String value) async =>
      (await SharedPreferences.getInstance()).setString(key, value);
}

/// One piece of work for the store, in the order it happened.
class StockOp {
  final String id;

  /// start | lines | submit | cancel | receive
  final String kind;
  final String? countId;
  final Map<String, dynamic> body;

  /// A manager's PIN for a submit — kept in memory only, never written to
  /// the phone's storage. Lost on restart: the store then asks again.
  String? managerPin;

  /// Set when the store refused it (not a connection problem): the op stays,
  /// visible, until someone retries or discards it. Never dropped silently.
  String? errorCode;
  String? error;

  StockOp({
    required this.id,
    required this.kind,
    this.countId,
    Map<String, dynamic>? body,
    this.managerPin,
    this.errorCode,
    this.error,
  }) : body = body ?? {};

  bool get failed => errorCode != null || error != null;

  Map<String, dynamic> toJson() => {
    'id': id,
    'kind': kind,
    'countId': ?countId,
    'body': body,
    'errorCode': ?errorCode,
    'error': ?error,
  };

  factory StockOp.fromJson(Map<String, dynamic> j) => StockOp(
    id: j['id'],
    kind: j['kind'],
    countId: j['countId'],
    body: Map<String, dynamic>.from(j['body'] ?? const {}),
    errorCode: j['errorCode'],
    error: j['error'],
  );
}

/// A counted product on this phone: its own qty (other phones add theirs at
/// the store), and when it was last counted.
class DraftLine {
  final String itemId, name;
  final String? barcode;
  final int qty;
  final String countedAt;
  const DraftLine({
    required this.itemId,
    required this.name,
    this.barcode,
    required this.qty,
    required this.countedAt,
  });
  Map<String, dynamic> toJson() => {
    'itemId': itemId,
    'name': name,
    'barcode': ?barcode,
    'qty': qty,
    'countedAt': countedAt,
  };
  factory DraftLine.fromJson(Map<String, dynamic> j) => DraftLine(
    itemId: j['itemId'],
    name: j['name'] ?? j['itemId'],
    barcode: j['barcode'],
    qty: (j['qty'] as num).toInt(),
    countedAt: j['countedAt'] ?? '',
  );
}

/// This phone's view of a count: everything it counted, kept on the phone
/// until the store has acknowledged the submit.
class CountDraft {
  final String id;
  String name;

  /// Insertion order = most recently counted last.
  final Map<String, DraftLine> lines;

  /// Submit queued (or sent, awaiting the store).
  bool submitting;
  CountDraft({
    required this.id,
    required this.name,
    Map<String, DraftLine>? lines,
    this.submitting = false,
  }) : lines = lines ?? {};

  int get units => lines.values.fold(0, (n, l) => n + l.qty);

  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'lines': [for (final l in lines.values) l.toJson()],
    'submitting': submitting,
  };

  factory CountDraft.fromJson(Map<String, dynamic> j) => CountDraft(
    id: j['id'],
    name: j['name'] ?? '',
    lines: {
      for (final l in (j['lines'] as List? ?? const []))
        (l as Map)['itemId'] as String: DraftLine.fromJson(
          Map<String, dynamic>.from(l),
        ),
    },
    submitting: j['submitting'] == true,
  );
}

typedef StockSender = Future<Object?> Function(StockOp op, String counterId);

/// What one [StockQueue.flush] did.
class FlushResult {
  final int sent;

  /// Stopped on a connection problem; what is left goes next time.
  final bool offline;
  const FlushResult(this.sent, {this.offline = false});
}

/// The stock app's offline queue — "never lose a count".
///
/// Every change is written to the phone first (the count drafts and the
/// queue of operations), then sent to the store in order. Each operation is
/// idempotent at the store (client-minted ids, SET quantities), so resending
/// after a lost reply is harmless. A connection problem stops the flush and
/// keeps everything; a refusal from the store (say, a variance without a
/// manager) marks that operation and holds the rest of the same count behind
/// it — later operations for other counts and deliveries still go.
class StockQueue extends ChangeNotifier {
  StockQueue(this._store, {StockSender? sender, this.now})
    : _sender = sender ?? sendStockOp;

  static const _opsKey = 'stock.queue.ops';
  static const _draftsKey = 'stock.queue.drafts';
  static const _counterKey = 'stock.counter_id';
  static const _expectedKey = 'stock.expected';
  static const _catalogKey = 'stock.catalog';

  /// The app's queue (persisted in shared preferences).
  static StockQueue instance = StockQueue(PrefsKeyValueStore());

  final KeyValueStore _store;
  final StockSender _sender;

  /// Clock seam for tests.
  final DateTime Function()? now;

  final List<StockOp> _ops = [];
  final Map<String, CountDraft> _drafts = {};
  String _counterId = '';
  StockExpected _expected = StockExpected.none;
  List<Map<String, dynamic>> _catalog = [];
  bool _loaded = false;
  bool _flushing = false;
  bool _offline = false;

  List<StockOp> get ops => List.unmodifiable(_ops);
  int get pending => _ops.length;
  List<StockOp> get failed => _ops.where((o) => o.failed).toList();
  bool get flushing => _flushing;

  /// The last flush stopped on a connection problem.
  bool get offline => _offline;

  /// This phone's id as a counter (its lines add up with other phones').
  String get counterId => _counterId;
  StockExpected get expected => _expected;

  /// The last catalog seen (so scanning works while the store is out of reach).
  List<StockProduct> get catalog => [
    for (final j in _catalog) StockProduct.fromJson(j),
  ];

  List<CountDraft> get drafts => _drafts.values.toList();
  CountDraft? draft(String countId) => _drafts[countId];

  String _nowIso() => (now?.call() ?? DateTime.now()).toUtc().toIso8601String();

  Future<void> load() async {
    if (_loaded) return;
    _loaded = true;
    try {
      final ops = await _store.read(_opsKey);
      if (ops != null) {
        _ops
          ..clear()
          ..addAll(
            (jsonDecode(ops) as List).map(
              (o) => StockOp.fromJson(Map<String, dynamic>.from(o)),
            ),
          );
      }
      final drafts = await _store.read(_draftsKey);
      if (drafts != null) {
        for (final d in (jsonDecode(drafts) as List)) {
          final draft = CountDraft.fromJson(Map<String, dynamic>.from(d));
          _drafts[draft.id] = draft;
        }
      }
      final expected = await _store.read(_expectedKey);
      if (expected != null) {
        _expected = StockExpected.fromJson(jsonDecode(expected));
      }
      final catalog = await _store.read(_catalogKey);
      if (catalog != null) {
        _catalog = (jsonDecode(catalog) as List)
            .map((e) => Map<String, dynamic>.from(e))
            .toList();
      }
      _counterId = await _store.read(_counterKey) ?? '';
    } catch (e) {
      debugPrint('[stock] queue load failed: $e');
    }
    if (_counterId.isEmpty) {
      _counterId = newStockId();
      await _store.write(_counterKey, _counterId);
    }
    notifyListeners();
  }

  Future<void> _save() async {
    await _store.write(_opsKey, jsonEncode([for (final o in _ops) o.toJson()]));
    await _store.write(
      _draftsKey,
      jsonEncode([for (final d in _drafts.values) d.toJson()]),
    );
  }

  /// Keep the latest expected figures and catalog for offline use.
  Future<void> cacheReference({
    StockExpected? expected,
    List<StockProduct>? catalog,
  }) async {
    if (expected != null) {
      _expected = expected;
      await _store.write(_expectedKey, jsonEncode(expected.toJson()));
    }
    if (catalog != null) {
      _catalog = [for (final p in catalog) p.toJson()];
      await _store.write(_catalogKey, jsonEncode(_catalog));
    }
    notifyListeners();
  }

  // ---- counting ----

  /// Start a count on this phone (queued for the store).
  Future<CountDraft> startCount({String? name, String? id}) async {
    final draft = CountDraft(id: id ?? newStockId(), name: name ?? '');
    _drafts[draft.id] = draft;
    _ops.add(
      StockOp(
        id: newStockId(),
        kind: 'start',
        countId: draft.id,
        body: {'name': ?name},
      ),
    );
    await _save();
    notifyListeners();
    return draft;
  }

  /// Join a count that exists at the store (another phone started it).
  /// [mine] is what this phone already counted there.
  Future<CountDraft> joinCount(
    String id,
    String name, {
    List<CountLine> mine = const [],
  }) async {
    final draft = _drafts.putIfAbsent(id, () => CountDraft(id: id, name: name));
    for (final l in mine) {
      final qty = l.mine;
      if (qty == null || draft.lines.containsKey(l.itemId)) continue;
      draft.lines[l.itemId] = DraftLine(
        itemId: l.itemId,
        name: l.name,
        barcode: l.barcode,
        qty: qty,
        countedAt: l.countedAt,
      );
    }
    await _save();
    notifyListeners();
    return draft;
  }

  /// This phone's qty of [item] in the count (a SET; 0 is a real count).
  Future<void> setQty(
    String countId, {
    required String itemId,
    required String name,
    String? barcode,
    required int qty,
  }) async {
    final draft = _drafts[countId];
    if (draft == null || draft.submitting) return;
    final at = _nowIso();
    final clamped = qty.clamp(0, 100000);
    // most recent last: re-insert
    draft.lines.remove(itemId);
    draft.lines[itemId] = DraftLine(
      itemId: itemId,
      name: name,
      barcode: barcode,
      qty: clamped,
      countedAt: at,
    );
    _queueLine(countId, {'itemId': itemId, 'qty': clamped, 'countedAt': at});
    await _save();
    notifyListeners();
  }

  /// Forget this phone's line for [itemId].
  Future<void> removeLine(String countId, String itemId) async {
    final draft = _drafts[countId];
    if (draft == null || draft.submitting) return;
    if (draft.lines.remove(itemId) == null) return;
    _queueLine(countId, {'itemId': itemId, 'remove': true});
    await _save();
    notifyListeners();
  }

  /// Fold a line change into the count's last pending `lines` op when nothing
  /// for that count was queued after it; otherwise a new op. One product
  /// appears once per op (the latest value wins — the store SETs it).
  void _queueLine(String countId, Map<String, dynamic> line) {
    StockOp? tail;
    for (final op in _ops.reversed) {
      if (op.countId != countId) continue;
      if (op.kind == 'lines' && !op.failed) tail = op;
      break;
    }
    if (tail == null) {
      tail = StockOp(
        id: newStockId(),
        kind: 'lines',
        countId: countId,
        body: {'lines': <Map<String, dynamic>>[]},
      );
      _ops.add(tail);
    }
    final lines = List<Map<String, dynamic>>.from(
      (tail.body['lines'] as List).map((e) => Map<String, dynamic>.from(e)),
    )..removeWhere((l) => l['itemId'] == line['itemId']);
    lines.add(line);
    tail.body['lines'] = lines;
  }

  /// Queue the submit. [managerPin] approves a variance (memory only).
  Future<void> submitCount(String countId, {String? managerPin}) async {
    final draft = _drafts[countId];
    if (draft == null) return;
    draft.submitting = true;
    // a new PIN for a submit that was refused replaces it
    _ops.removeWhere((o) => o.countId == countId && o.kind == 'submit');
    for (final o in _ops.where((o) => o.countId == countId)) {
      o.errorCode = null;
      o.error = null;
    }
    _ops.add(
      StockOp(
        id: newStockId(),
        kind: 'submit',
        countId: countId,
        managerPin: managerPin,
      ),
    );
    await _save();
    notifyListeners();
  }

  /// Discard a count: nothing more is sent for it; the store drops it.
  Future<void> discardCount(String countId) async {
    final hadStart = _ops.any((o) => o.countId == countId && o.kind == 'start');
    _ops.removeWhere((o) => o.countId == countId);
    _drafts.remove(countId);
    // never reached the store → nothing to tell it
    if (!hadStart) {
      _ops.add(StockOp(id: newStockId(), kind: 'cancel', countId: countId));
    }
    await _save();
    notifyListeners();
  }

  // ---- receiving ----

  /// Queue a delivery: [lines] are {itemId, qty}. Returns its id.
  Future<String> receive({
    String supplier = '',
    String reference = '',
    required List<Map<String, dynamic>> lines,
  }) async {
    final id = newStockId();
    _ops.add(
      StockOp(
        id: newStockId(),
        kind: 'receive',
        body: {
          'id': id,
          'supplier': supplier,
          'reference': reference,
          'lines': lines,
        },
      ),
    );
    await _save();
    notifyListeners();
    return id;
  }

  // ---- sending ----

  /// Whether [e] means "the store is out of reach, try later".
  static bool isTransient(Object e) =>
      e is SocketException ||
      e is TimeoutException ||
      e is http.ClientException ||
      e is AuthException || // signed out meanwhile: resend after sign-in
      (e is ApiException && (e.status == null || e.status! >= 500));

  /// Send everything that can go, in order. Safe to call any time (a second
  /// call while one runs returns at once).
  Future<FlushResult> flush() async {
    if (_flushing) return const FlushResult(0);
    _flushing = true;
    // callers may start a flush while a frame builds (a screen's initState)
    await Future<void>.value();
    notifyListeners();
    var sent = 0;
    var offline = false;
    try {
      final blocked = <String>{};
      var i = 0;
      while (i < _ops.length) {
        final op = _ops[i];
        final count = op.countId;
        if (op.failed || (count != null && blocked.contains(count))) {
          if (count != null) blocked.add(count);
          i++;
          continue;
        }
        try {
          final result = await _sender(op, _counterId);
          _ops.removeAt(i);
          sent++;
          if (op.kind == 'submit' && count != null) {
            _drafts.remove(count); // the store has the count
          }
          if (op.kind == 'cancel' && count != null) _drafts.remove(count);
          if (op.kind == 'start' &&
              result is CountSession &&
              count != null &&
              (_drafts[count]?.name.isEmpty ?? false)) {
            _drafts[count]!.name = result.name;
          }
          await _save();
        } catch (e) {
          if (isTransient(e)) {
            offline = true;
            break;
          }
          // the store answered no: keep it, say why, hold this count
          op.errorCode = e is ApiException ? (e.code ?? 'error') : 'error';
          op.error = '$e';
          if (count != null) {
            blocked.add(count);
            if (op.kind == 'submit') _drafts[count]?.submitting = false;
          }
          await _save();
          i++;
        }
      }
    } finally {
      _flushing = false;
      _offline = offline;
      notifyListeners();
    }
    return FlushResult(sent, offline: offline);
  }

  /// Try a refused operation again (e.g. after a manager arrived).
  Future<FlushResult> retry(String opId, {String? managerPin}) async {
    // a submit goes after anything counted since it was refused
    final i = _ops.indexWhere((o) => o.id == opId && o.kind == 'submit');
    if (i >= 0) _ops.add(_ops.removeAt(i));
    for (final o in _ops) {
      if (o.id == opId) {
        o.errorCode = null;
        o.error = null;
        if (managerPin != null) o.managerPin = managerPin;
        if (o.kind == 'submit' && o.countId != null) {
          _drafts[o.countId!]?.submitting = true;
        }
      }
    }
    await _save();
    return flush();
  }

  /// Give up on a refused operation (the user chose to).
  Future<void> discardOp(String opId) async {
    _ops.removeWhere((o) => o.id == opId);
    await _save();
    notifyListeners();
  }
}

/// The real store calls behind each queued operation.
Future<Object?> sendStockOp(StockOp op, String counterId) async {
  switch (op.kind) {
    case 'start':
      return Api.startCount(op.countId!, name: op.body['name'] as String?);
    case 'lines':
      return Api.setCountLines(
        op.countId!,
        counterId,
        List<Map<String, dynamic>>.from(op.body['lines'] as List),
      );
    case 'submit':
      return Api.submitCount(op.countId!, managerPin: op.managerPin);
    case 'cancel':
      return Api.cancelCount(op.countId!);
    case 'receive':
      return Api.receiveStock(op.body);
  }
  return null;
}
