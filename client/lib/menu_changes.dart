import 'dart:async';

import 'api.dart' show Item, Variant;

/// The menu can change under an order (a tablet edit, the AI menu, or the
/// manager portal synced down): an item or a size deleted, 86'd, repriced.
/// The store re-checks every line it is sent; this file is the client's half:
/// read what it refused, notice menu changes, and line a cart up with a
/// freshly loaded menu.

/// One line the store refused. [index] is its place in the request's lines
/// (0 for a single-line call).
class RejectedLine {
  final int index;
  final String itemId, variantId;

  /// item_unavailable (deleted, 86'd, or that size deleted) | price_changed
  final String code;

  /// The current unit price (price_changed only).
  final int? priceCents;
  final String nameEn, nameFr;
  final Map<String, String> names;

  const RejectedLine({
    required this.index,
    required this.itemId,
    required this.variantId,
    required this.code,
    this.priceCents,
    this.nameEn = '',
    this.nameFr = '',
    this.names = const {},
  });

  static const unavailable = 'item_unavailable';
  static const priceChanged = 'price_changed';

  bool get isPriceChange => code == priceChanged && priceCents != null;

  factory RejectedLine.fromJson(Map<String, dynamic> j) => RejectedLine(
    index: (j['index'] as num?)?.toInt() ?? 0,
    itemId: j['itemId'] as String? ?? '',
    variantId: j['variantId'] as String? ?? '',
    code: j['code'] as String? ?? unavailable,
    priceCents: (j['priceCents'] as num?)?.toInt(),
    nameEn: j['nameEn'] as String? ?? '',
    nameFr: j['nameFr'] as String? ?? '',
    names: {
      for (final e in ((j['names'] as Map?) ?? const {}).entries)
        if (e.key is String && e.value is String)
          e.key as String: e.value as String,
    },
  );

  /// The `rejected` array of a response or error body (none: empty).
  static List<RejectedLine> listFrom(dynamic body) {
    final raw = body is Map ? body['rejected'] : null;
    if (raw is! List) return const [];
    return [
      for (final r in raw)
        if (r is Map<String, dynamic>) RejectedLine.fromJson(r),
    ];
  }

  /// The item's name in [lang]: its translation, else English, else French.
  String name(String lang) {
    final extra = names[lang];
    if (extra != null && extra.isNotEmpty) return extra;
    if (lang == 'fr' && nameFr.isNotEmpty) return nameFr;
    return nameEn.isNotEmpty ? nameEn : nameFr;
  }
}

/// What a freshly loaded menu says about one cart line.
enum LineFate { ok, unavailable, repriced }

class LineCheck {
  final LineFate fate;

  /// The line's item and size in the new menu (null when unavailable).
  final Item? item;
  final Variant? variant;
  const LineCheck(this.fate, [this.item, this.variant]);
}

/// Each cart line (item id, size id, the unit price the guest saw) against
/// [menu]: gone (deleted / 86'd / size deleted), repriced, or as it was.
List<LineCheck> checkAgainstMenu(
  List<({String itemId, String variantId, int priceCents})> lines,
  List<Item> menu,
) {
  final byId = {for (final i in menu) i.id: i};
  return [
    for (final l in lines)
      () {
        final item = byId[l.itemId];
        if (item == null || !item.active) {
          return const LineCheck(LineFate.unavailable);
        }
        final v = item.variants.where((v) => v.id == l.variantId).firstOrNull;
        if (v == null) return const LineCheck(LineFate.unavailable);
        return LineCheck(
          v.priceCents == l.priceCents ? LineFate.ok : LineFate.repriced,
          item,
          v,
        );
      }(),
  ];
}

/// Polls the store's menu version (GET /menu/version) and calls [onChange]
/// when it moves. The first answer is the baseline; a failed poll (offline,
/// an older store) is ignored. [pause] / [resume] while hidden.
class MenuVersionPoller {
  final Future<int?> Function() fetch;
  final FutureOr<void> Function() onChange;
  final Duration every;
  Timer? _timer;
  int? _last;
  bool _busy = false;

  MenuVersionPoller({
    required this.fetch,
    required this.onChange,
    this.every = const Duration(seconds: 15),
  });

  bool get running => _timer != null;

  void start() {
    _timer ??= Timer.periodic(every, (_) => poll());
    poll();
  }

  void pause() {
    _timer?.cancel();
    _timer = null;
  }

  void resume() => start();

  void dispose() => pause();

  /// One check now (tests call it directly).
  Future<void> poll() async {
    if (_busy) return;
    _busy = true;
    try {
      final v = await fetch();
      if (v == null) return;
      final before = _last;
      _last = v;
      if (before != null && before != v) await onChange();
    } catch (_) {
      // offline or an older store: try again next time
    } finally {
      _busy = false;
    }
  }
}
