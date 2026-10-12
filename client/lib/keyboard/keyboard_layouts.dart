/// The on-screen keyboard's key maps: one letter layout per UI language, the
/// two symbol pages, and the long-press accent rows. Pure data, no widgets
/// (plus the Thai typing rules: the shift layer, and where a vowel or tone
/// mark goes).
library;

/// The languages the keyboard has a layout for.
const keyboardLayouts = ['en', 'fr', 'es', 'de', 'af', 'th'];

/// The layout for the app language [lang] (English for anything else).
String keyboardLayoutFor(String lang) =>
    keyboardLayouts.contains(lang) ? lang : 'en';

/// Each layout's name in its own language, shown on the space bar.
const keyboardLayoutNames = {
  'en': 'English',
  'fr': 'Français',
  'es': 'Español',
  'de': 'Deutsch',
  'af': 'Afrikaans',
  'th': 'ไทย',
};

/// The other letters a layout's staff type every day, one tap away on the
/// keyboard's language key: Thai staff type English names too. Layouts not
/// listed have no language key (their accents cover the store's languages).
const keyboardPartnerLayouts = {'th': 'en'};

/// The three letter rows of a layout (lowercase).
///
/// - en: QWERTY
/// - fr: Canadian French QWERTY (not AZERTY), with è, à, ç and é on keys
/// - es: QWERTY with ñ
/// - de: QWERTZ with ü, ö, ä and ß
/// - af: QWERTY with an apostrophe key (for ’n); its accents (ê ë é è ô ö
///   î ï û ü á ŉ) are on long-press
/// - th: Kedmanee (the standard Thai layout, TIS 820-2538), four letter rows
///   as on a Thai keyboard's number, top, home and bottom rows; shift gives
///   the second layer ([thaiShifted]), never capitals
List<List<String>> letterRows(String layout) => switch (layout) {
  'th' => const [
    ['ๅ', '/', '-', 'ภ', 'ถ', 'ุ', 'ึ', 'ค', 'ต', 'จ', 'ข', 'ช'],
    ['ๆ', 'ไ', 'ำ', 'พ', 'ะ', 'ั', 'ี', 'ร', 'น', 'ย', 'บ', 'ล'],
    ['ฟ', 'ห', 'ก', 'ด', 'เ', '้', '่', 'า', 'ส', 'ว', 'ง'],
    ['ผ', 'ป', 'แ', 'อ', 'ิ', 'ื', 'ท', 'ม', 'ใ', 'ฝ'],
  ],
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
  'af' => const [
    ['q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'],
    ['a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'],
    ['z', 'x', 'c', 'v', 'b', 'n', 'm', "'"],
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
/// forms (French, Spanish, German and Afrikaans first), a few symbols their cousins.
const _alternates = {
  'a': ['à', 'â', 'á', 'ä', 'æ', 'ã', 'å'],
  'c': ['ç'],
  'e': ['é', 'è', 'ê', 'ë'],
  'i': ['î', 'ï', 'í', 'ì'],
  'n': ['ñ', 'ŉ'],
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
const _preferred = {
  'fr': 'éèàâêîôûç',
  'es': 'áéíóúñü',
  'de': 'äöüß',
  'af': 'êëéèôöîïûüáŉ',
};

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

/// A key's shifted form. ß has no everyday capital, and ŉ has no single
/// capital letter: they stay as they are.
String upperOf(String key) =>
    key == 'ß' || key == 'ŉ' ? key : key.toUpperCase();

/// Kedmanee's shift layer: each unshifted Thai key to what it types with
/// shift (Thai digits, the rarer consonants, ฿, and marks like ็ ๋ ์).
const thaiShifted = {
  // number row
  'ๅ': '+', '/': '๑', '-': '๒', 'ภ': '๓', 'ถ': '๔', 'ุ': 'ู', 'ึ': '฿',
  'ค': '๕', 'ต': '๖', 'จ': '๗', 'ข': '๘', 'ช': '๙',
  // top row
  'ๆ': '๐', 'ไ': '"', 'ำ': 'ฎ', 'พ': 'ฑ', 'ะ': 'ธ', 'ั': 'ํ', 'ี': '๊',
  'ร': 'ณ', 'น': 'ฯ', 'ย': 'ญ', 'บ': 'ฐ', 'ล': ',',
  // home row
  'ฟ': 'ฤ', 'ห': 'ฆ', 'ก': 'ฏ', 'ด': 'โ', 'เ': 'ฌ', '้': '็', '่': '๋',
  'า': 'ษ', 'ส': 'ศ', 'ว': 'ซ', 'ง': '.',
  // bottom row
  'ผ': '(', 'ป': ')', 'แ': 'ฉ', 'อ': 'ฮ', 'ิ': 'ฺ', 'ื': '์', 'ท': '?',
  'ม': 'ฒ', 'ใ': 'ฬ', 'ฝ': 'ฦ',
};

/// What a letter key types on [layout] with shift on: the Thai second layer,
/// else the capital.
String shiftedOf(String key, String layout) =>
    layout == 'th' ? (thaiShifted[key] ?? key) : upperOf(key);

/// A layout without capitals: shift is a second layer, so the keyboard never
/// shifts on its own at the start of a sentence or word.
bool caselessLayout(String layout) => layout == 'th';

/// A Thai vowel or tone mark that sits above or below the letter before it
/// (ั ิ ี ึ ื ุ ู ฺ ็ ่ ้ ๊ ๋ ์ ํ ๎): typed after its consonant, drawn on it.
bool isThaiMark(String c) {
  if (c.length != 1) return false;
  final u = c.codeUnitAt(0);
  return u == 0x0E31 ||
      (u >= 0x0E34 && u <= 0x0E3A) ||
      (u >= 0x0E47 && u <= 0x0E4E);
}

/// The four tone marks (่ ้ ๊ ๋) and the silencer ์, which go last on a
/// consonant, after any vowel above or below it.
bool _isThaiTone(int u) => u >= 0x0E48 && u <= 0x0E4C;

/// A vowel above or below (ั ิ ี ึ ื ุ ู ฺ ็): it comes before a tone mark.
bool _isThaiVowelMark(int u) =>
    u == 0x0E31 || (u >= 0x0E34 && u <= 0x0E3A) || u == 0x0E47;

/// What a key face shows: a vowel or tone mark on a dotted circle (◌ิ), the
/// way Thai keyboards print them, so the mark is never drawn on nothing.
String keyFace(String key) => isThaiMark(key) ? '\u25CC$key' : key;

/// [before] (the text up to the caret) after typing the Thai mark [mark],
/// in the order Thai is stored: consonant, vowel above or below, then tone.
///
/// - a vowel mark typed after a tone mark goes in before it (ก่ + ิ = กิ่,
///   the same text as typing ก ิ ่);
/// - a second tone mark replaces the first (ก่ + ้ = ก้), and a vowel mark
///   typed twice stays one;
/// - anything else is simply added.
String thaiCompose(String before, String mark) {
  if (before.isEmpty || mark.length != 1) return before + mark;
  final m = mark.codeUnitAt(0);
  final last = before.codeUnitAt(before.length - 1);
  if (_isThaiTone(m) && _isThaiTone(last)) {
    return before.substring(0, before.length - 1) + mark;
  }
  if (_isThaiVowelMark(m)) {
    if (last == m) return before;
    if (_isThaiTone(last)) {
      final head = before.substring(0, before.length - 1);
      final prev = head.isEmpty ? -1 : head.codeUnitAt(head.length - 1);
      // replace a vowel mark already there (ก ิ ่ + ี = กี่), else insert
      final base = _isThaiVowelMark(prev)
          ? head.substring(0, head.length - 1)
          : head;
      return base + mark + String.fromCharCode(last);
    }
  }
  return before + mark;
}

/// Backspace in Thai text: one Thai character (a tone or a vowel mark comes
/// off on its own, as on a Thai keyboard), else one whole character.
bool thaiDeletesOneCodeUnit(String before) {
  if (before.isEmpty) return false;
  final u = before.codeUnitAt(before.length - 1);
  return u >= 0x0E00 && u <= 0x0E7F;
}
