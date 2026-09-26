// Every string the terminal can show exists in every language a store uses:
// French and English at the pubs, English and Spanish at Sage & Poppy. The
// string tables are plain Dart (`_t(...)` calls with one literal per
// language), so this test reads their source and checks each call:
//
//  - it has a French, an English and a Spanish text, none empty;
//  - the French and Spanish texts are not just the English left in place,
//    unless every word is on the small allowlist below (proper nouns and words
//    both languages really use);
//  - French typography: a no-break space (U+00A0) before : ; ! ? and », and
//    after «.
//
// It also checks the Spanish names for the Sage & Poppy shelf's departments
// and styles (lib/retail/shelf_names.dart).
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/retail/shelf_names.dart';

/// A string table: its file, and which argument of `_t` is which language.
class _Table {
  final String path;
  final List<String> order;
  const _Table(this.path, this.order);
}

const _tables = [
  _Table('lib/i18n.dart', ['fr', 'en', 'es']),
  _Table('lib/retail/retail_i18n.dart', ['en', 'es', 'fr']),
  _Table('lib/stock/stock_i18n.dart', ['en', 'es', 'fr']),
];

/// Words a French or Spanish text may share with the English one: proper
/// nouns and abbreviations, and the few words the languages really spell the
/// same way on a POS screen. Keep it short — every entry is a place where a
/// missed translation would go unnoticed.
const _sameWords = {
  // proper nouns and abbreviations
  'Copper', 'Lantern', 'Sage', '&', 'Poppy', 'Stripe', 'Wi-Fi', 'OK', 'PIN',
  'CRV', 'QR', 'IPA',
  // the same word in French (Menu, Table, Zones, Total, Bar) or Spanish (Total,
  // Subtotal)
  'Total', 'Subtotal', 'Menu', 'Table', 'Zones', 'Bar',
};

/// A string literal's text, decoded: interpolations become '§'.
class _Lit {
  final String text;
  const _Lit(this.text);
}

/// A tiny Dart lexer: just enough to pull string literals (with escapes,
/// adjacent concatenation, `$x` / `${...}` interpolation, raw and triple
/// quotes) out of `_t(...)` calls, skipping comments.
class _Lexer {
  final String src;
  int i = 0;
  _Lexer(this.src);

  /// Literals met inside `${...}` interpolations (a ternary's two texts).
  final nested = <String>[];

  bool _isQuote(int at) =>
      at < src.length && (src[at] == "'" || src[at] == '"');

  bool get atString =>
      _isQuote(i) ||
      (src[i] == 'r' &&
          _isQuote(i + 1) &&
          (i == 0 || !RegExp(r'[\w$]').hasMatch(src[i - 1])));

  /// Reads one string literal at [i] and returns its decoded text.
  String readString() {
    var raw = false;
    if (src[i] == 'r') {
      raw = true;
      i++;
    }
    final q = src[i];
    final triple = src.startsWith(q * 3, i);
    final close = triple ? q * 3 : q;
    i += close.length;
    final out = StringBuffer();
    while (true) {
      if (i >= src.length) throw StateError('unterminated string');
      if (src.startsWith(close, i)) {
        i += close.length;
        return out.toString();
      }
      final c = src[i];
      if (!raw && c == r'\') {
        final n = src[i + 1];
        switch (n) {
          case 'n':
            out.write('\n');
            i += 2;
          case 't':
            out.write('\t');
            i += 2;
          case 'u':
            if (src[i + 2] == '{') {
              final end = src.indexOf('}', i);
              out.writeCharCode(
                int.parse(src.substring(i + 3, end), radix: 16),
              );
              i = end + 1;
            } else {
              out.writeCharCode(
                int.parse(src.substring(i + 2, i + 6), radix: 16),
              );
              i += 6;
            }
          default:
            out.write(n);
            i += 2;
        }
        continue;
      }
      if (!raw && c == r'$') {
        if (src[i + 1] == '{') {
          i += 2;
          _skipBalanced('{', '}');
        } else {
          i++;
          while (i < src.length && RegExp(r'[\w]').hasMatch(src[i])) {
            i++;
          }
        }
        out.write('§');
        continue;
      }
      out.write(c);
      i++;
    }
  }

  /// Skips to just past the bracket that closes an already-open one.
  void _skipBalanced(String open, String close) {
    var depth = 1;
    while (i < src.length) {
      if (atString) {
        nested.add(readString());
        continue;
      }
      final c = src[i];
      if (c == open) depth++;
      if (c == close) {
        depth--;
        if (depth == 0) {
          i++;
          return;
        }
      }
      i++;
    }
  }

  /// At [i] just past a call's "(": each argument's string literals.
  List<List<_Lit>> readArgs() {
    final args = <List<_Lit>>[[]];
    var depth = 0;
    while (i < src.length) {
      if (src.startsWith('//', i)) {
        i = src.indexOf('\n', i);
        continue;
      }
      if (atString) {
        nested.clear();
        final text = readString();
        args.last.add(_Lit(text));
        args.last.addAll(nested.map(_Lit.new));
        continue;
      }
      final c = src[i];
      i++;
      if ('([{'.contains(c)) depth++;
      if (')]}'.contains(c)) {
        if (depth == 0) break;
        depth--;
      }
      if (c == ',' && depth == 0) args.add([]);
    }
    if (args.length > 1 && args.last.isEmpty) args.removeLast(); // trailing ,
    return args;
  }
}

class _Call {
  final String file;
  final int line;
  final int arity;
  final Map<String, String> texts; // lang → the argument's literals, joined
  const _Call(this.file, this.line, this.arity, this.texts);
  String get where => '$file:$line';
}

List<_Call> _calls(_Table table) {
  final src = File(table.path).readAsStringSync();
  final calls = <_Call>[];
  for (final m in RegExp(r'(?<![\w$.])_t\(').allMatches(src)) {
    // the helper's own declaration: "String _t(String fr, ...)"
    if (src.substring(m.end).startsWith('String ')) continue;
    final lexer = _Lexer(src)..i = m.end;
    final args = lexer.readArgs();
    final line = '\n'.allMatches(src.substring(0, m.start)).length + 1;
    calls.add(
      _Call(table.path, line, args.length, {
        for (var k = 0; k < 3; k++)
          table.order[k]: k < args.length
              ? args[k].map((l) => l.text).join(' | ')
              : '',
      }),
    );
  }
  return calls;
}

final _word = RegExp(r"[A-Za-zÀ-ÿ][A-Za-zÀ-ÿ'’-]*|&");

bool _allowedSame(String text) =>
    _word.allMatches(text).every((w) => _sameWords.contains(w[0]));

void main() {
  for (final table in _tables) {
    group(table.path, () {
      final calls = _calls(table);

      test('has string calls', () => expect(calls, isNotEmpty));

      test('every string has French, English and Spanish', () {
        final arity = [
          for (final c in calls)
            if (c.arity != 3) '${c.where} has ${c.arity} language(s)',
        ];
        expect(arity, isEmpty, reason: arity.join('\n'));
        final missing = [
          for (final c in calls)
            for (final e in c.texts.entries)
              if (e.value.replaceAll('§', '').trim().isEmpty)
                '${c.where} (${e.key})',
        ];
        expect(missing, isEmpty, reason: 'empty or missing: $missing');
      });

      test('no English left in the French or Spanish texts', () {
        final leftover = [
          for (final c in calls)
            for (final lang in ['fr', 'es'])
              if (c.texts[lang] == c.texts['en'] &&
                  !_allowedSame(c.texts['en']!))
                '${c.where} ($lang): ${c.texts['en']}',
        ];
        expect(leftover, isEmpty, reason: leftover.join('\n'));
      });

      test('French typography: no-break spaces around : ; ! ? « »', () {
        final bad = <String>[];
        for (final c in calls) {
          // a pipe joins alternatives (ternaries); interpolations read as §
          for (final fr in c.texts['fr']!.split(' | ')) {
            final wrong =
                // a plain space (or none) before a high punctuation mark
                RegExp(r'([^ \s]|[ ])[;!?»]').hasMatch(fr) ||
                RegExp(r'([A-Za-zÀ-ÿ)§]|[ ]):(\s|$)').hasMatch(fr) ||
                RegExp(r'«[^ ]').hasMatch(fr) ||
                fr.contains('"');
            if (wrong) bad.add('${c.where}: $fr');
          }
        }
        expect(bad, isEmpty, reason: bad.join('\n'));
      });
    });
  }

  group('Sage & Poppy shelf names in Spanish', () {
    // Every department (SagePoppySeed.Cat) and every subcategory the catalog
    // generates (SagePoppyCatalog.kt: styles, varietals, spiritTypes,
    // rtdKinds, curatedFacets and otherProducts).
    const shelf = [
      'Beer & Cider',
      'Wine',
      'Spirits',
      'Seltzers & Coolers',
      'Mixers & Soda',
      'Snacks',
      'Ice',
      'Sundries',
      'Lager',
      'Light Lager',
      'Mexican-Style Lager',
      'Pilsner',
      'IPA',
      'Hazy IPA',
      'Double IPA',
      'West Coast IPA',
      'Pale Ale',
      'Amber Ale',
      'Brown Ale',
      'Wheat Beer',
      'Blonde Ale',
      'Stout',
      'Porter',
      'Sour',
      'Saison',
      'Cider',
      'Non-Alcoholic',
      'Cabernet Sauvignon',
      'Pinot Noir',
      'Chardonnay',
      'Sauvignon Blanc',
      'Zinfandel',
      'Merlot',
      'Syrah',
      'Rosé',
      'Red Blend',
      'White Blend',
      'Pinot Grigio',
      'Riesling',
      'Sparkling',
      'Malbec',
      'Petite Sirah',
      'Grenache',
      'Viognier',
      'Moscato',
      'Vodka',
      'Gin',
      'Bourbon',
      'Rye Whiskey',
      'American Whiskey',
      'Irish Whiskey',
      'Blended Scotch',
      'Single Malt Scotch',
      'Tequila Blanco',
      'Tequila Reposado',
      'Tequila Anejo',
      'Mezcal',
      'White Rum',
      'Spiced Rum',
      'Dark Rum',
      'Brandy',
      'Liqueur',
      'Cinnamon Whisky',
      'Hard Seltzer',
      'Canned Cocktail',
      'Hard Tea',
      'Hard Lemonade',
      'Cooler',
      'Club Soda',
      'Tonic',
      'Ginger Beer',
      'Soda',
      'Sparkling Water',
      'Juice',
      'Cocktail Mix',
      'Fresh Garnish',
      'Chips',
      'Nuts',
      'Jerky',
      'Candy',
      'Pretzels',
      'Popcorn',
      'Crackers',
      'Snack Meals',
      'Party Supplies',
      'Bagged Ice',
      'Block Ice',
      'Bags',
      'Barware',
      'Gifts',
      'Coolers',
      'Household',
    ];
    // names US Spanish speakers use as they are
    const asIs = {
      'Lager',
      'Pilsner',
      'IPA',
      'Pale Ale',
      'Stout',
      'Porter',
      'Saison',
      'Cabernet Sauvignon',
      'Pinot Noir',
      'Chardonnay',
      'Sauvignon Blanc',
      'Zinfandel',
      'Merlot',
      'Syrah',
      'Pinot Grigio',
      'Riesling',
      'Malbec',
      'Petite Sirah',
      'Viognier',
      'Vodka',
      'Bourbon',
      'Mezcal',
      'Brandy',
      'Pretzels',
    };

    test('every department and style has a Spanish name', () {
      final missing = shelf.where((n) => !shelfNamesEs.containsKey(n));
      expect(missing, isEmpty);
    });

    test('only the names kept on purpose read the same in Spanish', () {
      final same = [
        for (final e in shelfNamesEs.entries)
          if (e.key == e.value && !asIs.contains(e.key)) e.key,
      ];
      expect(same, isEmpty);
      expect(asIs.every((n) => shelfNamesEs[n] == n), isTrue);
    });

    test('sizes and packs', () {
      expect(shelfSizeEs('12 oz can'), 'lata de 12 oz');
      expect(shelfSizeEs('16 oz tallboy'), 'lata alta de 16 oz');
      expect(shelfSizeEs('22 oz bomber'), 'botella de 22 oz');
      expect(shelfSizeEs('7.5 oz cans'), 'latas de 7.5 oz');
      expect(shelfSizeEs('12 oz bottles'), 'botellas de 12 oz');
      expect(shelfSizeEs('3 L box'), 'caja de 3 L');
      expect(shelfSizeEs('6-pack'), 'paquete de 6');
      expect(shelfSizeEs('50-count'), '50 piezas');
      expect(shelfSizeEs('2-roll'), '2 rollos');
      expect(shelfSizeEs('Single'), 'Individual');
      expect(shelfSizeEs('750 ml'), '750 ml');
    });
  });
}
