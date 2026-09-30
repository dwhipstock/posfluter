/// The on-screen keyboard's key maps: one letter layout per UI language, the
/// two symbol pages, and the long-press accent rows. Pure data, no widgets.
library;

/// The languages the keyboard has a layout for.
const keyboardLayouts = ['en', 'fr', 'es', 'de'];

/// The layout for the app language [lang] (English for anything else).
String keyboardLayoutFor(String lang) =>
    keyboardLayouts.contains(lang) ? lang : 'en';

/// Each layout's name in its own language, shown on the space bar.
const keyboardLayoutNames = {
  'en': 'English',
  'fr': 'Français',
  'es': 'Español',
  'de': 'Deutsch',
};

/// The three letter rows of a layout (lowercase).
///
/// - en: QWERTY
/// - fr: Canadian French QWERTY (not AZERTY), with è, à, ç and é on keys
/// - es: QWERTY with ñ
/// - de: QWERTZ with ü, ö, ä and ß
List<List<String>> letterRows(String layout) => switch (layout) {
  'fr' => const [
    ['q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p', 'è'],
    ['a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', 'à', 'ç'],
    ['z', 'x', 'c', 'v', 'b', 'n', 'm', 'é'],
  ],
  'es' => const [
    ['q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'],
    ['a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', 'ñ'],
    ['z', 'x', 'c', 'v', 'b', 'n', 'm'],
  ],
  'de' => const [
    ['q', 'w', 'e', 'r', 't', 'z', 'u', 'i', 'o', 'p', 'ü'],
    ['a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', 'ö', 'ä'],
    ['y', 'x', 'c', 'v', 'b', 'n', 'm', 'ß'],
  ],
  _ => const [
    ['q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'],
    ['a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'],
    ['z', 'x', 'c', 'v', 'b', 'n', 'm'],
  ],
};

/// The first symbol page (numbers and common punctuation), and the second
/// (brackets, maths, currency). Row 3 sits between the page toggles.
const symbolRows = [
  ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'],
  ['-', '/', ':', ';', '(', ')', r'$', '&', '@', '"'],
  ['.', ',', '?', '!', "'"],
];
const moreSymbolRows = [
  ['[', ']', '{', '}', '#', '%', '^', '*', '+', '='],
  ['_', r'\', '|', '~', '<', '>', '€', '£', '¥', '•'],
  ['.', ',', '?', '!', "'"],
];

/// Long-press alternates for a key, lowercase. Letters get their accented
/// forms (French, Spanish and German first), a few symbols their cousins.
const _alternates = {
  'a': ['à', 'â', 'á', 'ä', 'æ', 'ã', 'å'],
  'c': ['ç'],
  'e': ['é', 'è', 'ê', 'ë'],
  'i': ['î', 'ï', 'í', 'ì'],
  'n': ['ñ'],
  'o': ['ô', 'ó', 'ö', 'ò', 'œ', 'õ', 'ø'],
  's': ['ß'],
  'u': ['ù', 'û', 'ú', 'ü'],
  'y': ['ÿ', 'ý'],
  'è': ['é', 'ê', 'ë', 'e'],
  'é': ['è', 'ê', 'ë', 'e'],
  'à': ['â', 'á', 'ä', 'a'],
  'ü': ['ù', 'û', 'ú', 'u'],
  'ö': ['ô', 'ó', 'ò', 'o'],
  'ä': ['à', 'â', 'á', 'a'],
  '.': ['…'],
  '-': ['–', '—'],
  r'$': ['€', '£', '¥', '¢'],
  '?': ['¿'],
  '!': ['¡'],
  '"': ['«', '»', '“', '”'],
  "'": ['’', '‘'],
  '0': ['°'],
  '&': ['§'],
};

/// The letters each layout's language uses most, moved to the front of a
/// popup so the likeliest accent is the one under the finger.
const _preferred = {'fr': 'éèàâêîôûç', 'es': 'áéíóúñü', 'de': 'äöüß'};

/// The popup row for [key] on [layout], cased to match [upper] (empty when
/// the key has none).
List<String> alternatesFor(String key, String layout, {bool upper = false}) {
  final base = _alternates[key];
  if (base == null) return const [];
  final pref = _preferred[layout] ?? '';
  final sorted = [...base]
    ..sort((a, b) {
      final ia = pref.indexOf(a), ib = pref.indexOf(b);
      if (ia < 0 && ib < 0) return base.indexOf(a) - base.indexOf(b);
      if (ia < 0) return 1;
      if (ib < 0) return -1;
      return ia - ib;
    });
  return upper ? sorted.map(upperOf).toList() : sorted;
}

/// A key's shifted form. ß has no everyday capital: it stays ß.
String upperOf(String key) => key == 'ß' ? key : key.toUpperCase();
