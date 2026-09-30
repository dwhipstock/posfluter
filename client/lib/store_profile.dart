/// What this terminal's store is: which screens, brand, languages and money
/// format it uses. Read from the store's public `GET /health` (before
/// sign-in), so a retail counter, a gas station or a pub each come up
/// right without a rebuild. Until the store answers, the pub defaults apply —
/// exactly what the terminal always did.
class StoreProfile {
  final String venueId;

  /// 'copper-lantern' | 'sage-poppy' | 'pronghorn'
  final String brand;

  /// 'restaurant' | 'retail' | 'quick-serve'
  final String kind;
  final String country;
  final String currency;

  /// Languages staff can pick; the first is the store's default.
  final List<String> locales;
  final int legalAge;

  /// `kitchen.printing=on` at the store: Send to kitchen, the Kitchen view,
  /// station setup. Off (the default) and the terminal is exactly as before.
  final bool kitchenPrinting;

  /// A gas station: the counter shows the pump grid (GET /forecourt).
  final bool forecourt;

  /// `age.check=looks-under:N`: the cashier may pass a customer who clearly
  /// looks over N without an ID (never for tobacco and vape). Null = an ID
  /// every time (the default).
  final int? looksOverAge;

  const StoreProfile({
    this.venueId = '',
    this.brand = 'copper-lantern',
    this.kind = 'restaurant',
    this.country = 'US',
    this.currency = 'USD',
    this.locales = const ['en', 'fr'],
    this.legalAge = 21,
    this.kitchenPrinting = false,
    this.forecourt = false,
    this.looksOverAge,
  });

  /// The Copper Lantern pubs (Raleigh, NC: US, USD, 21) and any store too old
  /// to describe itself.
  static const pub = StoreProfile();

  static StoreProfile current = pub;

  bool get isRetail => kind == 'retail';

  /// A quick-serve counter (Copper Lantern Express): numbered orders, no floor plan.
  bool get isQuickServe => kind == 'quick-serve';
  bool get isSagePoppy => brand == 'sage-poppy';
  bool get isPronghorn => brand == 'pronghorn';

  /// A retail brand with its own skin and palette (not the pubs' look).
  bool get hasOwnBrand => isSagePoppy || isPronghorn;

  /// The short brand name the screens show, or null for the pubs (which
  /// show the venue's own name from settings).
  String? get brandName => isSagePoppy
      ? 'Sage & Poppy'
      : isPronghorn
      ? 'Pronghorn'
      : null;
  String get defaultLocale => locales.first;

  /// From /health; missing or odd fields keep the pub defaults.
  factory StoreProfile.fromHealth(Map<String, dynamic> j) {
    List<String> locales = const ['en', 'fr'];
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
      country: str('country', 'US').toUpperCase(),
      currency: str('currency', 'USD').toUpperCase(),
      locales: locales,
      legalAge: j['legalAge'] is int ? j['legalAge'] as int : 21,
      kitchenPrinting: j['kitchenPrinting'] == true,
      forecourt: j['forecourt'] == true,
      looksOverAge: j['looksOverAge'] is int ? j['looksOverAge'] as int : null,
    );
  }
}

/// Money for people, in this store's currency. Cents on the wire everywhere.
///
/// Always North American style, whatever the UI language (owner's rule):
/// symbol first, period decimal, comma thousands.
///
/// - CAD and USD: `$12.99`, `$8.00`, `$1,010.00` — always two decimals.
///
/// [lang] is kept for compatibility and ignored.
String money(int cents, {String? currency, String? lang}) =>
    formatMoney(cents, currency ?? StoreProfile.current.currency);

/// A signed adjustment (cash rounding): `+$0.01`, `−$0.02`. Always shows the
/// sign so the cashier reads it as a correction, not an amount.
String signedMoney(int cents, {String? currency}) {
  final abs = money(cents.abs(), currency: currency);
  return cents < 0 ? '−$abs' : '+$abs';
}

/// North American money text: `$1,234.56`, `-$5.00`, `$8.00` — always
/// with cents. [lang] is kept for compatibility and ignored — money never
/// changes shape with the UI language.
String formatMoney(int cents, String currency, {String lang = 'en'}) {
  final sign = cents < 0 ? '-' : '';
  final abs = cents.abs();
  final whole = abs ~/ 100;
  final frac = abs % 100;
  final grouped = whole.toString().replaceAllMapped(
    RegExp(r'(\d)(?=(\d{3})+$)'),
    (m) => '${m[1]},',
  );
  final cc = frac.toString().padLeft(2, '0');
  switch (currency.toUpperCase()) {
    case 'CAD':
    case 'USD':
      return '$sign\$$grouped.$cc';
    default:
      return '$sign${currency.toUpperCase()} $grouped.$cc';
  }
}
