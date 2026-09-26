import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/i18n.dart';

void main() {
  const us = StoreProfile(
    venueId: 'sage-poppy',
    brand: 'sage-poppy',
    kind: 'retail',
    country: 'US',
    currency: 'USD',
    locales: ['en', 'es'],
    legalAge: 21,
  );

  tearDown(() {
    StoreProfile.current = StoreProfile.pub;
    Prefs.instance.lang = 'en';
  });

  test('USD reads the US way in English and Spanish', () {
    expect(formatMoney(1299, 'USD'), '\$12.99');
    expect(formatMoney(500, 'USD'), '\$5.00');
    expect(formatMoney(123450, 'USD'), '\$1,234.50');
    expect(formatMoney(-5, 'USD'), '-\$0.05');
    StoreProfile.current = us;
    expect(money(1299), '\$12.99');
  });

  test('CAD keeps the pubs\' house style; fr-CA puts the symbol after', () {
    expect(money(101000), '\$1,010'); // default profile: the pubs, unchanged
    expect(money(21550), '\$215.50');
    expect(cad(21550), '\$215.50');
    expect(formatMoney(1299, 'CAD', lang: 'fr-CA'), '12,99\u00A0\$');
    expect(formatMoney(123450, 'CAD', lang: 'fr-CA'), '1\u00A0234,50\u00A0\$');
    expect(formatMoney(101000, 'CAD', lang: 'fr'), '1\u00A0010\u00A0\$');
    expect(formatMoney(-50, 'CAD', lang: 'fr'), '-0,50\u00A0\$');
    // money() follows the UI language; USD stays the US way in every language
    Prefs.instance.lang = 'fr';
    expect(money(4000), '40\u00A0\$');
    expect(money(1050), '10,50\u00A0\$');
    expect(money(1050, lang: 'en'), '\$10.50');
    expect(money(1299, currency: 'USD'), '\$12.99');
  });

  test('the store describes itself from /health', () {
    final p = StoreProfile.fromHealth({
      'status': 'ok',
      'venue': 'Sage & Poppy Bottle Shop',
      'venueId': 'sage-poppy',
      'brand': 'sage-poppy',
      'kind': 'retail',
      'country': 'US',
      'currency': 'USD',
      'locales': ['en', 'es'],
      'legalAge': 21,
    });
    expect(p.isRetail, isTrue);
    expect(p.isSagePoppy, isTrue);
    expect(p.currency, 'USD');
    expect(p.defaultLocale, 'en');
    expect(p.legalAge, 21);
    // an older store says nothing: the pub defaults
    final old = StoreProfile.fromHealth({'status': 'ok', 'venue': 'x'});
    expect(old.currency, 'CAD');
    expect(old.locales, ['fr', 'en']);
    expect(old.isRetail, isFalse);
  });

  test('Spanish strings where translated, English otherwise', () {
    const es = L.forLang('es');
    expect(es.es, isTrue);
    expect(es.retry, 'Reintentar');
    expect(es.name('Bière', 'Beer'), 'Beer'); // catalog data: English side
    expect(es.nameAlt('Bière', 'Beer'), '');
    expect(const L(false).name('Bière', 'Beer'), 'Bière');
    expect(const L(true).nameAlt('Bière', 'Beer'), 'Bière');
  });

  test('the language toggle cycles through the store\'s own languages', () {
    Prefs.instance.useStore(us);
    Prefs.instance.lang = 'en';
    expect(Prefs.instance.nextLang, 'es');
    Prefs.instance.lang = 'es';
    expect(Prefs.instance.nextLang, 'en');
    // a pub terminal: French and English, as always
    Prefs.instance.useStore(StoreProfile.pub);
    Prefs.instance.lang = 'en';
    expect(Prefs.instance.nextLang, 'fr');
    Prefs.instance.lang = 'fr';
    expect(Prefs.instance.nextLang, 'en');
  });

  test('a language the store does not offer falls back', () {
    Prefs.instance.lang = 'fr';
    Prefs.instance.useStore(us);
    expect(Prefs.instance.lang, 'en');
    Prefs.instance.lang = 'es';
    Prefs.instance.useStore(StoreProfile.pub);
    expect(Prefs.instance.lang, 'en');
  });
}
