import 'i18n.dart';

/// What this terminal's store is: which screens, brand, languages and money
/// format it uses. Read from the store's public `GET /health` (before
/// sign-in), so a retail counter, a US store or a Québec pub each come up
/// right without a rebuild. Until the store answers, the pub defaults apply —
/// exactly what the terminal always did.
class StoreProfile {
  final String venueId;

  /// 'copper-lantern' | 'sage-poppy'
  final String brand;

  /// 'restaurant' | 'retail'
  final String kind;
  final String country;
  final String currency;

  /// Languages staff can pick; the first is the store's default.
  final List<String> locales;
  final int legalAge;

  const StoreProfile({
    this.venueId = '',
    this.brand = 'copper-lantern',
    this.kind = 'restaurant',
    this.country = 'CA',
    this.currency = 'CAD',
    this.locales = const ['fr', 'en'],
    this.legalAge = 18,
  });

  /// The Montréal pubs (and any store too old to describe itself).
  static const pub = StoreProfile();

  static StoreProfile current = pub;

  bool get isRetail => kind == 'retail';
  bool get isSagePoppy => brand == 'sage-poppy';
  String get defaultLocale => locales.first;

  /// From /health; missing or odd fields keep the pub defaults.
  factory StoreProfile.fromHealth(Map<String, dynamic> j) {
    List<String> locales = const ['fr', 'en'];
    final raw = j['locales'];
    if (raw is List) {
      final tags = raw
          .whereType<String>()
          .map((s) => s.trim().toLowerCase())
          .where((s) => s.isNotEmpty)
          .toList();
      if (tags.isNotEmpty) locales = tags;
    }
    String str(String key, String fallback) {
      final v = j[key];
      return v is String && v.trim().isNotEmpty ? v.trim() : fallback;
    }

    return StoreProfile(
      venueId: str('venueId', ''),
      brand: str('brand', 'copper-lantern'),
      kind: str('kind', 'restaurant'),
      country: str('country', 'CA').toUpperCase(),
      currency: str('currency', 'CAD').toUpperCase(),
      locales: locales,
      legalAge: j['legalAge'] is int ? j['legalAge'] as int : 18,
    );
  }
}

/// Money for people, in this store's currency. Cents on the wire everywhere.
///
/// - CAD (the pubs): the house style they always had — `$1,010` for whole
///   dollars, `$10.50` otherwise — in English; in French the Québec way,
///   `1 010 $` and `10,50 $` (no-break spaces, the symbol after).
/// - USD: `$12.99`, `$5.00` — US shelf style, always with cents, in English
///   and US Spanish alike.
///
/// [lang] defaults to the terminal's current UI language.
String money(int cents, {String? currency, String? lang}) => formatMoney(
  cents,
  currency ?? StoreProfile.current.currency,
  lang: lang ?? Prefs.instance.lang,
);

/// A signed adjustment (cash rounding): `+$0.01`, `−$0.02`. Always shows the
/// sign so the cashier reads it as a correction, not an amount.
String signedMoney(int cents, {String? currency}) {
  final abs = money(cents.abs(), currency: currency);
  return cents < 0 ? '−$abs' : '+$abs';
}

/// [lang] is a UI language (`en`, `fr`, `es`) or a tag (`fr-CA`).
String formatMoney(int cents, String currency, {String lang = 'en'}) {
  final sign = cents < 0 ? '-' : '';
  final abs = cents.abs();
  final whole = abs ~/ 100;
  final frac = abs % 100;
  String grouped(String sep) => whole.toString().replaceAllMapped(
    RegExp(r'(\d)(?=(\d{3})+$)'),
    (m) => '${m[1]}$sep',
  );
  final cc = frac.toString().padLeft(2, '0');
  switch (currency.toUpperCase()) {
    case 'CAD':
      if (lang.toLowerCase().startsWith('fr')) {
        // Québec French: 1 010 $ / 10,50 $ (no-break spaces keep it on one line)
        final figure = frac == 0 ? grouped(_nbsp) : '${grouped(_nbsp)},$cc';
        return '$sign$figure$_nbsp\$';
      }
      return frac == 0
          ? '$sign\$${grouped(',')}'
          : '$sign\$${grouped(',')}.$cc';
    case 'USD':
      return '$sign\$${grouped(',')}.$cc';
    default:
      return '$sign${currency.toUpperCase()} ${grouped(',')}.$cc';
  }
}

const _nbsp = '\u00A0';
