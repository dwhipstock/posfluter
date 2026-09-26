/// The counter's own index over the whole shelf (~5,000 products at Sage &
/// Poppy): type-ahead search, barcode lookup and the browse facets, all in
/// memory on the terminal.
///
/// Why in memory and not SQLite FTS: the terminal already holds the catalog
/// to draw it, the store may be across the Wi-Fi (a round trip per keystroke
/// is 20–200 ms before any search happens), and 5,000 products make a
/// vocabulary of a few thousand words — a sorted word list with posting lists
/// answers a query in about a millisecond, far under the 100 ms budget, with
/// no plugin, no schema and nothing to keep in sync. FTS earns its keep at
/// hundreds of thousands of rows, or for text that doesn't fit in memory.
///
/// Matching (the store's `GET /items?q=` does the same): every query word
/// must start a word of the product's name, brand, subcategory or size; a
/// number matches a pack size ("ipa 6" → IPA six-packs), a container size
/// ("vodka 1.75") or, from 4 digits, the barcode. Best match first, then the
/// better seller.
library;

/// What the index needs to know about a product.
class CatalogDoc {
  final String id, name;
  final String category;
  final String? brand, subcategory, size, barcode;
  final int packUnits;

  /// Popularity for ranking ties: the store's recent sales rank if known,
  /// else the catalog's demo weight. Higher = more popular.
  final int popularity;
  const CatalogDoc({
    required this.id,
    required this.name,
    this.category = '',
    this.brand,
    this.subcategory,
    this.size,
    this.barcode,
    this.packUnits = 1,
    this.popularity = 0,
  });
}

/// Shelf shorthand people type.
const _synonyms = {
  'pk': 'pack',
  'pks': 'pack',
  'packs': 'pack',
  'btl': 'bottle',
  'btls': 'bottles',
  'cn': 'can',
  'cans': 'can',
};

const _folds = {
  'á': 'a', 'à': 'a', 'â': 'a', 'ä': 'a', 'ã': 'a', 'å': 'a', //
  'é': 'e', 'è': 'e', 'ê': 'e', 'ë': 'e', //
  'í': 'i', 'ì': 'i', 'î': 'i', 'ï': 'i', //
  'ó': 'o', 'ò': 'o', 'ô': 'o', 'ö': 'o', 'õ': 'o', //
  'ú': 'u', 'ù': 'u', 'û': 'u', 'ü': 'u', //
  'ñ': 'n', 'ç': 'c',
};

final _word = RegExp(r'[0-9]+(?:\.[0-9]+)?|[a-z]+');

/// "IPA 6-pack 12oz" → [ipa, 6, pack, 12, oz]: words and numbers, lower
/// case, accents folded, split where letters meet digits.
List<String> catalogTokens(String text) {
  final lower = text.toLowerCase();
  final b = StringBuffer();
  for (final r in lower.runes) {
    final ch = String.fromCharCode(r);
    b.write(_folds[ch] ?? ch);
  }
  return [for (final m in _word.allMatches(b.toString())) m.group(0)!];
}

bool _isNumber(String t) => t.codeUnitAt(0) >= 48 && t.codeUnitAt(0) <= 57;

/// Browse facets for one level: value → how many products.
typedef FacetCounts = Map<String, int>;

class CatalogIndex<T> {
  final List<T> _items;
  final List<CatalogDoc> _docs;
  final Map<String, int> _byCode = {};
  final Map<String, int> _byId = {};

  /// Sorted unique words, and for each the docs containing it.
  late final List<String> _vocab;
  late final List<List<int>> _postings;
  final Map<int, List<int>> _byPack = {};
  final List<List<String>> _nameWords;

  CatalogIndex._(this._items, this._docs)
    : _nameWords = [for (final d in _docs) catalogTokens(d.name)] {
    final words = <String, List<int>>{};
    for (var i = 0; i < _docs.length; i++) {
      final d = _docs[i];
      _byId[d.id] = i;
      final code = d.barcode;
      if (code != null && code.isNotEmpty) _byCode[_normalize(code)] = i;
      final seen = <String>{};
      for (final w in catalogTokens(
        [d.name, d.brand, d.subcategory, d.size].whereType<String>().join(' '),
      )) {
        if (seen.add(w)) (words[w] ??= []).add(i);
      }
      if (d.packUnits > 1) (_byPack[d.packUnits] ??= []).add(i);
    }
    _vocab = words.keys.toList()..sort();
    _postings = [for (final w in _vocab) words[w]!];
  }

  /// Build the index over [items], described by [doc].
  factory CatalogIndex(Iterable<T> items, CatalogDoc Function(T) doc) {
    final list = items.toList(growable: false);
    return CatalogIndex._(list, [for (final i in list) doc(i)]);
  }

  int get length => _items.length;
  List<T> get items => _items;
  bool get isEmpty => _items.isEmpty;

  T? byId(String id) {
    final i = _byId[id];
    return i == null ? null : _items[i];
  }

  /// UPC-A and its EAN-13 (a leading 0) find the same product.
  T? byBarcode(String code) {
    final i = _byCode[_normalize(code)];
    return i == null ? null : _items[i];
  }

  CatalogDoc docOf(String id) => _docs[_byId[id]!];

  /// Docs whose words start with [prefix] (binary search on the sorted vocabulary).
  void _prefix(String prefix, Map<int, int> into, int points, int exactPoints) {
    var lo = 0, hi = _vocab.length;
    while (lo < hi) {
      final mid = (lo + hi) >> 1;
      if (_vocab[mid].compareTo(prefix) < 0) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    for (var w = lo; w < _vocab.length && _vocab[w].startsWith(prefix); w++) {
      final p = _vocab[w].length == prefix.length ? exactPoints : points;
      for (final d in _postings[w]) {
        final had = into[d];
        if (had == null || had < p) into[d] = p;
      }
    }
  }

  int _exactWord(String word) {
    var lo = 0, hi = _vocab.length - 1;
    while (lo <= hi) {
      final mid = (lo + hi) >> 1;
      final c = _vocab[mid].compareTo(word);
      if (c == 0) return mid;
      if (c < 0) {
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return -1;
  }

  /// Type-ahead search, best first. [where] narrows (e.g. a category);
  /// [limit] caps the result list (the counter shows the first screenfuls).
  List<T> search(String query, {bool Function(T)? where, int limit = 200}) {
    final terms = [for (final t in catalogTokens(query)) _synonyms[t] ?? t];
    if (terms.isEmpty) return const [];
    Map<int, int>? total;
    for (final t in terms) {
      final hits = <int, int>{};
      if (_isNumber(t)) {
        final w = _exactWord(t);
        if (w >= 0) {
          for (final d in _postings[w]) {
            hits[d] = 3;
          }
        }
        final n = int.tryParse(t);
        if (n != null) {
          for (final d in _byPack[n] ?? const <int>[]) {
            hits[d] = 3;
          }
        }
        if (t.length >= 4) {
          for (var d = 0; d < _docs.length; d++) {
            if ((_docs[d].barcode ?? '').contains(t)) hits[d] = 3;
          }
        }
      } else {
        _prefix(t, hits, 2, 3);
      }
      if (total == null) {
        total = hits;
      } else {
        final next = <int, int>{};
        total.forEach((d, s) {
          final h = hits[d];
          if (h != null) next[d] = s + h;
        });
        total = next;
      }
      if (total.isEmpty) return const [];
    }
    final first = terms.first;
    final scored = <(int, int)>[];
    total!.forEach((d, s) {
      if (where != null && !where(_items[d])) return;
      final nw = _nameWords[d];
      final bonus = nw.isNotEmpty && nw.first.startsWith(first) ? 1 : 0;
      scored.add((d, s + bonus));
    });
    scored.sort((a, b) {
      final c = b.$2.compareTo(a.$2);
      if (c != 0) return c;
      final p = _docs[b.$1].popularity.compareTo(_docs[a.$1].popularity);
      if (p != 0) return p;
      return _docs[a.$1].name.compareTo(_docs[b.$1].name);
    });
    return [for (final s in scored.take(limit)) _items[s.$1]];
  }

  /// Products matching the browse filters (null = any), most popular first.
  List<T> filter({String? category, String? subcategory, String? size}) {
    final out = <int>[];
    for (var i = 0; i < _docs.length; i++) {
      final d = _docs[i];
      if (category != null && d.category != category) continue;
      if (subcategory != null && d.subcategory != subcategory) continue;
      if (size != null && d.size != size) continue;
      out.add(i);
    }
    out.sort((a, b) {
      final p = _docs[b].popularity.compareTo(_docs[a].popularity);
      return p != 0 ? p : _docs[a].name.compareTo(_docs[b].name);
    });
    return [for (final i in out) _items[i]];
  }

  /// Counts for the next browse level, given the levels already chosen:
  /// subcategories within [category], sizes within [category] › [subcategory].
  FacetCounts facet(
    String field, {
    String? category,
    String? subcategory,
    String? size,
  }) {
    final counts = <String, int>{};
    for (final d in _docs) {
      if (category != null && d.category != category) continue;
      if (field != 'subcategory' &&
          subcategory != null &&
          d.subcategory != subcategory) {
        continue;
      }
      if (field != 'size' && size != null && d.size != size) continue;
      final v = switch (field) {
        'category' => d.category,
        'subcategory' => d.subcategory,
        'size' => d.size,
        _ => null,
      };
      if (v == null || v.isEmpty) continue;
      counts[v] = (counts[v] ?? 0) + 1;
    }
    return counts;
  }

  static String _normalize(String code) {
    final c = code.trim();
    if (c.length == 13 && c.startsWith('0') && _validGtin(c)) {
      return c.substring(1);
    }
    return c;
  }

  static bool _validGtin(String c) {
    if (!RegExp(r'^\d{12,13}$').hasMatch(c)) return false;
    final body = c.substring(0, c.length - 1);
    var sum = 0;
    for (var i = 0; i < body.length; i++) {
      final digit = body.codeUnitAt(body.length - 1 - i) - 48;
      sum += digit * (i.isEven ? 3 : 1);
    }
    return (10 - sum % 10) % 10 == c.codeUnitAt(c.length - 1) - 48;
  }
}

/// Sizes in a sensible shelf order (singles, then packs, then bottles by
/// volume) rather than alphabetically: "12 oz can" before "6-pack" before
/// "12-pack", "50 ml" before "750 ml" before "1.75 L".
int compareSizes(String a, String b) {
  double key(String s) {
    final t = s.toLowerCase();
    final n =
        double.tryParse(
          RegExp(r'[0-9]+(\.[0-9]+)?').firstMatch(t)?.group(0) ?? '',
        ) ??
        0;
    if (t.contains('pack')) return 1000 + n;
    if (t.contains('count')) return 3000 + n;
    if (t.contains(' ml')) return 2000 + n / 1000;
    if (t.endsWith(' l') || t.contains(' l ')) return 2000 + n;
    if (t.contains('oz')) return n;
    if (t.contains('lb')) return 4000 + n;
    return 5000;
  }

  final c = key(a).compareTo(key(b));
  return c != 0 ? c : a.compareTo(b);
}
