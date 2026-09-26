import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/catalog/catalog_index.dart';
import 'package:pos_client/stock/stock_models.dart';

/// The counter's in-memory index at shelf scale: relevance, sizes and packs,
/// barcodes, browse filters and facets, and speed at 5,000 products.
void main() {
  CatalogDoc doc(
    String id,
    String name, {
    String cat = 'beer',
    String? brand,
    String? sub,
    String? size,
    String? code,
    int pack = 1,
    int pop = 0,
  }) => CatalogDoc(
    id: id,
    name: name,
    category: cat,
    brand: brand,
    subcategory: sub,
    size: size,
    barcode: code,
    packUnits: pack,
    popularity: pop,
  );

  final fixtures = [
    doc(
      'hazy-4',
      'Hazy Hills IPA 4-pack 16 oz cans',
      brand: 'Hazy Hills',
      sub: 'Hazy IPA',
      size: '4-pack',
      pack: 4,
      pop: 50,
      code: '487230001050',
    ),
    doc(
      'ipa-6',
      'Fogline Crooked Swell IPA 6-pack 12 oz bottles',
      brand: 'Fogline Ales',
      sub: 'IPA',
      size: '6-pack',
      pack: 6,
      pop: 90,
      code: '487230100014',
    ),
    doc(
      'ipa-6b',
      'Otter Cove Dusty Campfire IPA 6-pack 12 oz bottles',
      brand: 'Otter Cove Brewery',
      sub: 'IPA',
      size: '6-pack',
      pack: 6,
      pop: 10,
      code: '487230100021',
    ),
    doc(
      'ipa-can',
      'Fogline Crooked Swell IPA 16 oz tallboy',
      brand: 'Fogline Ales',
      sub: 'IPA',
      size: '16 oz tallboy',
      pop: 80,
      code: '487230100038',
    ),
    doc(
      'lager-6',
      'Golden Hour Lager 6-pack 12 oz cans',
      brand: 'Golden Hour',
      sub: 'Lager',
      size: '6-pack',
      pack: 6,
      pop: 99,
      code: '487230001029',
    ),
    doc(
      'vodka-175',
      'Silver Coast Vodka 1.75 L',
      cat: 'spirits',
      brand: 'Silver Coast',
      sub: 'Vodka',
      size: '1.75 L',
      pop: 70,
      code: '487230003023',
    ),
    doc(
      'vodka-750',
      'Silver Coast Vodka 750 ml',
      cat: 'spirits',
      brand: 'Silver Coast',
      sub: 'Vodka',
      size: '750 ml',
      pop: 75,
      code: '487230003016',
    ),
    doc(
      'rose',
      'Sunlit Rosé 750 ml',
      cat: 'wine',
      brand: 'Sunlit',
      sub: 'Rosé',
      size: '750 ml',
      pop: 40,
      code: '487230002033',
    ),
    doc(
      'bag',
      'Paper Bag',
      cat: 'sundries',
      brand: 'House',
      sub: 'Bags',
      size: 'Single',
      pop: 200,
    ),
  ];
  final index = CatalogIndex<CatalogDoc>(fixtures, (d) => d);
  List<String> ids(String q, {bool Function(CatalogDoc)? where}) => [
    for (final d in index.search(q, where: where)) d.id,
  ];

  test(
    'words and a pack size: "ipa 6" is IPA six-packs, best seller first',
    () {
      expect(ids('ipa 6'), ['ipa-6', 'ipa-6b']);
      expect(ids('ipa 6pk'), ['ipa-6', 'ipa-6b']);
      expect(ids('ipa 6 pack'), ['ipa-6', 'ipa-6b']);
      expect(ids('6-pack'), ['lager-6', 'ipa-6', 'ipa-6b']);
    },
  );

  test('prefixes as you type, brand words, accents folded', () {
    expect(ids('hazy hi'), ['hazy-4']);
    expect(ids('fog'), ['ipa-6', 'ipa-can']);
    expect(ids('rose'), ['rose']);
    expect(ids('ROSÉ'), ['rose']);
    expect(ids('silver vod 1.75'), ['vodka-175']);
    expect(ids('vodka 750'), ['vodka-750']);
    expect(ids('ipa 16'), ['ipa-can', 'hazy-4']); // 16 oz tallboy, 16 oz cans
  });

  test('barcode digits from four up, and both code forms', () {
    expect(ids('100021'), ['ipa-6b']);
    expect(index.byBarcode('487230001029')?.id, 'lager-6');
    expect(
      index.byBarcode('0487230001029')?.id,
      'lager-6',
      reason: 'EAN-13 form',
    );
    expect(index.byBarcode('999'), isNull);
  });

  test('no match, empty query, and a filter', () {
    expect(ids('zzz'), isEmpty);
    expect(ids('   '), isEmpty);
    expect(ids('silver', where: (d) => d.size == '750 ml'), ['vodka-750']);
  });

  test('browse filters combine and facets count the next level', () {
    expect(
      [
        for (final d in index.filter(
          category: 'beer',
          subcategory: 'IPA',
          size: '6-pack',
        ))
          d.id,
      ],
      ['ipa-6', 'ipa-6b'],
    );
    expect(index.filter(category: 'beer').length, 5);
    expect(index.filter(size: '750 ml').length, 2);
    expect(index.filter(category: 'wine', subcategory: 'IPA'), isEmpty);
    expect(index.facet('category'), {
      'beer': 5,
      'spirits': 2,
      'wine': 1,
      'sundries': 1,
    });
    expect(index.facet('subcategory', category: 'beer'), {
      'Hazy IPA': 1,
      'IPA': 3,
      'Lager': 1,
    });
    // sizes within Beer › IPA; subcategories within Beer at a chosen size
    expect(index.facet('size', category: 'beer', subcategory: 'IPA'), {
      '6-pack': 2,
      '16 oz tallboy': 1,
    });
    expect(index.facet('subcategory', category: 'beer', size: '6-pack'), {
      'IPA': 2,
      'Lager': 1,
    });
  });

  test('sizes sort the way a shelf reads', () {
    final sizes = [
      '750 ml',
      '6-pack',
      '12 oz can',
      '1.75 L',
      '12-pack',
      '50 ml',
      '16 oz tallboy',
      '4-pack',
    ]..sort(compareSizes);
    expect(sizes, [
      '12 oz can',
      '16 oz tallboy',
      '4-pack',
      '6-pack',
      '12-pack',
      '50 ml',
      '750 ml',
      '1.75 L',
    ]);
  });

  test('5,000 products: built and searched well under 100 ms', () {
    final rng = Random(7);
    const words = [
      'golden',
      'hazy',
      'coastal',
      'silver',
      'harbor',
      'canyon',
      'foggy',
      'wild',
      'sunny',
      'night',
    ];
    const subs = [
      'IPA',
      'Lager',
      'Pilsner',
      'Stout',
      'Cabernet Sauvignon',
      'Chardonnay',
      'Vodka',
      'Tequila Blanco',
    ];
    const sizes = [
      ('12 oz can', 1),
      ('6-pack', 6),
      ('12-pack', 12),
      ('750 ml', 1),
      ('1.75 L', 1),
      ('4-pack', 4),
    ];
    final docs = [
      for (var i = 0; i < 5000; i++)
        () {
          final sub = subs[rng.nextInt(subs.length)];
          final (size, pack) = sizes[rng.nextInt(sizes.length)];
          final brand =
              '${words[rng.nextInt(10)]} ${words[rng.nextInt(10)]} Co.';
          return doc(
            'p$i',
            '$brand ${words[rng.nextInt(10)]} $sub $size',
            brand: brand,
            sub: sub,
            size: size,
            pack: pack,
            pop: rng.nextInt(1000),
            code: '48723${(10000 + i).toString().padLeft(6, '0')}0',
          );
        }(),
    ];
    final sw = Stopwatch()..start();
    final big = CatalogIndex<CatalogDoc>(docs, (d) => d);
    final buildMs = sw.elapsedMilliseconds;
    sw.reset();
    const queries = [
      'ipa 6',
      'hazy',
      'silver vodka 1.75',
      'g',
      'golden lager 12',
      '1004',
      'cab sauv 750',
      'stout',
    ];
    for (var round = 0; round < 10; round++) {
      for (final q in queries) {
        big.search(q);
      }
    }
    final perQuery = sw.elapsedMicroseconds / (10 * queries.length) / 1000;
    // ignore: avoid_print
    print(
      'index: build ${buildMs}ms for 5,000; search ${perQuery.toStringAsFixed(2)} ms/query',
    );
    expect(perQuery, lessThan(100));
    expect(
      big
          .search('ipa 6')
          .every((d) => d.subcategory == 'IPA' && d.packUnits == 6),
      isTrue,
    );
    expect(big.search('ipa 6'), isNotEmpty);
  });

  test('the stock app looks products up on the same index', () {
    final products = ProductIndex([
      const StockProduct(
        id: 'ipa-6',
        name: 'Fogline Crooked Swell IPA 6-pack 12 oz bottles',
        barcode: '487230100014',
        subcategory: 'IPA',
        size: '6-pack',
        packUnits: 6,
      ),
      const StockProduct(
        id: 'lager',
        name: 'Golden Hour Lager 12 oz can',
        barcode: '487230001012',
      ),
    ]);
    expect(products.byCode('487230100014')?.id, 'ipa-6');
    expect(products.search('ipa 6').map((p) => p.id), ['ipa-6']);
    expect(products.search('gold').map((p) => p.id), ['lager']);
    expect(products.search('1012').map((p) => p.id), ['lager']);
  });
}
