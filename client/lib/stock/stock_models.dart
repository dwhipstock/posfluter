import 'dart:math';

import '../catalog/catalog_index.dart';

/// Wire models for counting and receiving stock (store `/stock/*`, retail).
/// Quantities only — stock carries no money.

/// A random RFC 4122 v4 id. Counts and deliveries are named by the phone
/// itself, so one started offline keeps its id when it reaches the store and a
/// resend is the same row, never a duplicate.
String newStockId([Random? random]) {
  final r = random ?? Random.secure();
  final b = List<int>.generate(16, (_) => r.nextInt(256));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  String hex(int from, int to) => b
      .sublist(from, to)
      .map((x) => x.toRadixString(16).padLeft(2, '0'))
      .join();
  return '${hex(0, 4)}-${hex(4, 6)}-${hex(6, 8)}-${hex(8, 10)}-${hex(10, 16)}';
}

/// UPC-A and EAN-13: a valid EAN-13 starting with 0 is the UPC-A without it
/// (the store's rule), so either form finds the product.
String normalizeBarcode(String code) {
  final c = code.trim();
  if (c.length == 13 && c.startsWith('0') && _validGtin(c)) {
    return c.substring(1);
  }
  return c;
}

bool _validGtin(String c) {
  if (!RegExp(r'^\d{12,13}$').hasMatch(c)) return false;
  final body = c.substring(0, c.length - 1);
  var sum = 0;
  for (var i = 0; i < body.length; i++) {
    final digit = body.codeUnitAt(body.length - 1 - i) - 48;
    sum += digit * (i.isEven ? 3 : 1);
  }
  return (10 - sum % 10) % 10 == c.codeUnitAt(c.length - 1) - 48;
}

/// A product as the stock screens need it (cached on the phone so scanning
/// works while the store is out of reach).
class StockProduct {
  final String id, name, category;
  final String? barcode;
  final bool active;

  /// Catalog facets, for search ("ipa 6"); absent in an older cached copy.
  final String? brand, subcategory, size;
  final int packUnits, popularity;
  const StockProduct({
    required this.id,
    required this.name,
    this.category = '',
    this.barcode,
    this.active = true,
    this.brand,
    this.subcategory,
    this.size,
    this.packUnits = 1,
    this.popularity = 0,
  });
  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'category': category,
    'barcode': ?barcode,
    'active': active,
    'brand': ?brand,
    'subcategory': ?subcategory,
    'size': ?size,
    if (packUnits != 1) 'packUnits': packUnits,
    if (popularity != 0) 'popularity': popularity,
  };
  factory StockProduct.fromJson(Map<String, dynamic> j) => StockProduct(
    id: j['id'],
    name: j['name'] ?? j['id'],
    category: j['category'] ?? '',
    barcode: j['barcode'],
    active: j['active'] ?? true,
    brand: j['brand'],
    subcategory: j['subcategory'],
    size: j['size'],
    packUnits: j['packUnits'] ?? 1,
    popularity: j['popularity'] ?? 0,
  );

  CatalogDoc get doc => CatalogDoc(
    id: id,
    name: name,
    category: category,
    brand: brand,
    subcategory: subcategory,
    size: size,
    barcode: barcode,
    packUnits: packUnits,
    popularity: popularity,
  );
}

/// Barcode → product (both code forms, UPC-A and its EAN-13) and typed
/// search, on the counter's own index ([CatalogIndex]): the same matching as
/// the counter, fast at 5,000 products.
class ProductIndex {
  final CatalogIndex<StockProduct> _index;
  ProductIndex(Iterable<StockProduct> products)
    : _index = CatalogIndex(products, (p) => p.doc);
  StockProduct? byCode(String code) => _index.byBarcode(code);
  StockProduct? byId(String id) => _index.byId(id);
  bool get isEmpty => _index.isEmpty;

  /// Words, sizes or barcode digits ("ipa 6", "vodka 1.75", "0421"), best first.
  List<StockProduct> search(String q) => _index.search(q, limit: 30);
}

/// The best-effort expected on hand per product (the cloud's figure plus the
/// store's own moves since). Absent product = "no expected qty".
class StockExpected {
  final bool available;
  final String? asOf;
  final Map<String, int> items;
  const StockExpected({
    this.available = false,
    this.asOf,
    this.items = const {},
  });

  static const none = StockExpected();

  factory StockExpected.fromJson(Map<String, dynamic> j) => StockExpected(
    available: j['available'] == true,
    asOf: j['asOf'] as String?,
    items: {
      for (final e in ((j['items'] as Map?) ?? const {}).entries)
        if (e.value is num) e.key as String: (e.value as num).toInt(),
    },
  );

  Map<String, dynamic> toJson() => {
    'available': available,
    'asOf': ?asOf,
    'items': items,
  };
}

class CountLine {
  final String itemId, name;
  final String? barcode;
  final int counted;
  final int? mine, expected, variance;
  final String countedAt;
  const CountLine({
    required this.itemId,
    required this.name,
    this.barcode,
    required this.counted,
    this.mine,
    this.expected,
    this.variance,
    this.countedAt = '',
  });
  factory CountLine.fromJson(Map<String, dynamic> j) => CountLine(
    itemId: j['itemId'],
    name: j['name'] ?? j['itemId'],
    barcode: j['barcode'],
    counted: (j['counted'] as num).toInt(),
    mine: (j['mine'] as num?)?.toInt(),
    expected: (j['expected'] as num?)?.toInt(),
    variance: (j['variance'] as num?)?.toInt(),
    countedAt: j['countedAt'] ?? '',
  );
}

/// A count session as the store has it (every counter's lines summed).
class CountSession {
  final String id, name, status, startedByName, startedAt;
  final String? submittedAt;
  final List<CountLine> lines;
  final int varianceLines;
  final bool needsApproval;
  const CountSession({
    required this.id,
    required this.name,
    required this.status,
    this.startedByName = '',
    this.startedAt = '',
    this.submittedAt,
    this.lines = const [],
    this.varianceLines = 0,
    this.needsApproval = false,
  });
  bool get open => status == 'OPEN';
  factory CountSession.fromJson(Map<String, dynamic> j) => CountSession(
    id: j['id'],
    name: j['name'] ?? '',
    status: j['status'] ?? 'OPEN',
    startedByName: j['startedByName'] ?? '',
    startedAt: j['startedAt'] ?? '',
    submittedAt: j['submittedAt'],
    lines: ((j['lines'] as List?) ?? const [])
        .map((l) => CountLine.fromJson(l as Map<String, dynamic>))
        .toList(),
    varianceLines: (j['varianceLines'] as num?)?.toInt() ?? 0,
    needsApproval: j['needsApproval'] == true,
  );
}

/// One row of the store's count list.
class CountSummary {
  final String id, name, status, startedByName, startedAt;
  final String? submittedAt;
  final int products, units;
  const CountSummary({
    required this.id,
    required this.name,
    required this.status,
    this.startedByName = '',
    this.startedAt = '',
    this.submittedAt,
    this.products = 0,
    this.units = 0,
  });
  bool get open => status == 'OPEN';
  factory CountSummary.fromJson(Map<String, dynamic> j) => CountSummary(
    id: j['id'],
    name: j['name'] ?? '',
    status: j['status'] ?? 'OPEN',
    startedByName: j['startedByName'] ?? '',
    startedAt: j['startedAt'] ?? '',
    submittedAt: j['submittedAt'],
    products: (j['products'] as num?)?.toInt() ?? 0,
    units: (j['units'] as num?)?.toInt() ?? 0,
  );
}

class StockReceipt {
  final String id, supplier, reference, receivedByName, receivedAt;
  final int units;
  const StockReceipt({
    required this.id,
    this.supplier = '',
    this.reference = '',
    this.receivedByName = '',
    this.receivedAt = '',
    this.units = 0,
  });
  factory StockReceipt.fromJson(Map<String, dynamic> j) => StockReceipt(
    id: j['id'],
    supplier: j['supplier'] ?? '',
    reference: j['reference'] ?? '',
    receivedByName: j['receivedByName'] ?? '',
    receivedAt: j['receivedAt'] ?? '',
    units: (j['units'] as num?)?.toInt() ?? 0,
  );
}
