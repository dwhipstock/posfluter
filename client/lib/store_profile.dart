/// What this terminal's store is: which screens, brand, languages and money
/// format it uses. Read from the store's public `GET /health` (before
/// sign-in), so a retail counter, a gas station or a pub each come up
/// right without a rebuild. Until the store answers, the pub defaults apply —
/// exactly what the terminal always did.
class StoreProfile {
  final String venueId;

  /// 'copper-lantern' | 'sage-poppy' | 'pronghorn' | 'barcode'
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

  /// `promptpay.id` is set at a THB store: the pay screen offers PromptPay QR.
  final bool promptPay;

  /// `age.check=looks-under:N`: the cashier may pass a customer who clearly
  /// looks over N without an ID (never for tobacco and vape). Null = an ID
  /// every time (the default).
  final int? looksOverAge;

  /// `taxDisplay: combined` (Copper Lantern): guests see the added taxes as
  /// ONE line at the summed rate, "Tax (8.25%)", like the printed receipt;
  /// the reports still itemise them. False = one row per tax.
  final bool taxCombined;

  /// The combined line's own word instead of the language's "Tax"; '' = the language's.
  final String taxLabel;

  /// What the server said its data language is (`dataLocale` in /health);
  /// '' = derive it from [locales] ([dataLocale]).
  final String dataLocaleSetting;

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
    this.promptPay = false,
    this.looksOverAge,
    this.taxCombined = false,
    this.taxLabel = '',
    this.dataLocaleSetting = '',
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

  /// Barcode Pub (Bangkok): the pubs' restaurant screens in its own dark
  /// palette ([PubPalette.barcode]), not a retail brand's own screens.
  bool get isBarcode => brand == 'barcode';

  /// A retail brand with its own skin and palette (not the pubs' look).
  bool get hasOwnBrand => isSagePoppy || isPronghorn;

  /// The short brand name the screens show, or null for the pubs (which
  /// show the venue's own name from settings).
  String? get brandName => isSagePoppy
      ? 'Sage & Poppy'
      : isPronghorn
      ? 'Pronghorn'
      : isBarcode
      ? 'Barcode Pub'
      : null;
  String get defaultLocale => locales.first;

  /// The languages that can live in the catalog's second name slot (the
  /// `*_fr` columns: nameFr, labelFr…). English always has its own slot.
  static const slotLanguages = {'fr', 'th'};

  /// The store's DATA language: the one its `*_fr` columns hold. The server's
  /// `dataLocale` when it sent one, else the first of [locales] that can sit
  /// in that slot, else French — so a French store (and every store that
  /// predates this) reads exactly as before, and a Thai + English store keeps
  /// its Thai names in the same columns.
  String get dataLocale => dataLocaleFor(locales, dataLocaleSetting);

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
      promptPay: j['promptPay'] == true,
      looksOverAge: j['looksOverAge'] is int ? j['looksOverAge'] as int : null,
      taxCombined: j['taxDisplay'] == 'combined',
      taxLabel: j['taxLabel'] is String ? (j['taxLabel'] as String).trim() : '',
      dataLocaleSetting: j['dataLocale'] is String
          ? (j['dataLocale'] as String).trim().toLowerCase()
          : '',
    );
  }
}

/// A store's data language (see [StoreProfile.dataLocale]) from its
/// languages and, when the server sent one, its explicit `dataLocale`.
String dataLocaleFor(List<String> locales, [String? explicit]) {
  final set = explicit?.trim().toLowerCase() ?? '';
  if (StoreProfile.slotLanguages.contains(set)) return set;
  for (final l in locales) {
    final tag = l.trim().toLowerCase();
    if (StoreProfile.slotLanguages.contains(tag)) return tag;
  }
  return 'fr';
}

/// Money for people, in this store's currency. Cents on the wire everywhere.
///
/// Always North American style, whatever the UI language (owner's rule):
/// symbol first, period decimal, comma thousands.
///
/// - CAD and USD: `$12.99`, `$8.00`, `$1,010.00` — always two decimals.
/// - THB: `฿1,234`, `฿1,234.50`, `฿0` — satang only when non-zero.
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

/// The store's cap on one unit's price (its `price_too_high` error): 99,999.99
/// in the store's currency, in minor units.
const maxUnitPriceCents = 9999999;

/// The symbol a money input box shows in front of the amount for
/// [currency] (default: this store's): `$` for CAD/USD, `฿` for THB, else the
/// code itself (`EUR`).
String currencySymbol([String? currency]) {
  final c = (currency ?? StoreProfile.current.currency).toUpperCase();
  switch (c) {
    case 'CAD':
    case 'USD':
      return r'$';
    case 'THB':
      return '฿';
    default:
      return c;
  }
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
    case 'THB':
      // Baht: whole amounts print bare; satang only when there are some.
      return frac == 0 ? '$sign฿$grouped' : '$sign฿$grouped.$cc';
    default:
      return '$sign${currency.toUpperCase()} $grouped.$cc';
  }
}
