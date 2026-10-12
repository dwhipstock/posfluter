// The Thai locale: Thai UI strings, and a Thai + English store whose
// catalog keeps its Thai names in the `*_fr` slot (its data language),
// while a French store reads exactly as before. Also Thai dates (Buddhist
// Era year) and the language button's label.
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart' show TaxLine;
import 'package:pos_client/i18n.dart';
import 'package:pos_client/kiosk/kiosk_api.dart';
import 'package:pos_client/store_profile.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));
  tearDown(() {
    StoreProfile.current = StoreProfile.pub;
    Prefs.instance.lang = 'en';
  });

  group('data language', () {
    test('French unless the store is a Thai + English one', () {
      expect(dataLocaleFor(const ['en', 'fr']), 'fr');
      expect(dataLocaleFor(const ['fr', 'en']), 'fr');
      expect(dataLocaleFor(const ['en', 'fr', 'es', 'de', 'af']), 'fr');
      expect(dataLocaleFor(const ['en', 'es']), 'fr'); // the US stores
      expect(dataLocaleFor(const ['en']), 'fr');
      expect(dataLocaleFor(const ['th', 'en']), 'th');
      expect(dataLocaleFor(const ['en', 'th']), 'th');
      // the server's word wins; nonsense is ignored
      expect(dataLocaleFor(const ['en', 'fr'], 'th'), 'th');
      expect(dataLocaleFor(const ['th', 'en'], 'xx'), 'th');
      expect(dataLocaleFor(const ['th', 'en'], ' FR '), 'fr');
    });

    test('from /health: dataLocale, else from the locales', () {
      expect(StoreProfile.fromHealth({}).dataLocale, 'fr');
      expect(
        StoreProfile.fromHealth({
          'locales': ['th', 'en'],
        }).dataLocale,
        'th',
      );
      expect(
        StoreProfile.fromHealth({
          'locales': ['th', 'en'],
          'dataLocale': 'th',
        }).defaultLocale,
        'th',
      );
      expect(
        KioskConfig.fromJson({
          'locales': ['th', 'en'],
        }).dataLocale,
        'th',
      );
      expect(KioskConfig.fromJson({}).dataLocale, 'fr');
    });

    test('a French store: names exactly as before', () {
      StoreProfile.current = const StoreProfile(locales: ['fr', 'en']);
      expect(const L.forLang('fr').name('Frites', 'Fries'), 'Frites');
      expect(const L.forLang('en').name('Frites', 'Fries'), 'Fries');
      expect(
        const L.forLang('es').name('Frites', 'Fries', {'es': 'Papas'}),
        'Papas',
      );
      expect(const L.forLang('fr').nameAlt('Frites', 'Fries'), 'Fries');
      expect(const L.forLang('en').nameAlt('Frites', 'Fries'), 'Frites');
      expect(const L.forLang('es').nameAlt('Frites', 'Fries'), '');
      expect(pickName('fr', 'Frites', 'Fries'), 'Frites');
      expect(const L.forLang('en').nameFrLabel, 'Name (French)');
    });

    test('a Thai store: the fr slot is Thai', () {
      StoreProfile.current = const StoreProfile(locales: ['th', 'en']);
      const th = L.forLang('th'), en = L.forLang('en');
      expect(th.dataLang, 'th');
      expect(th.name('ผัดไทย', 'Pad Thai'), 'ผัดไทย');
      expect(en.name('ผัดไทย', 'Pad Thai'), 'Pad Thai');
      // a third language reads its translation, else English
      expect(
        const L.forLang('fr').name('ผัดไทย', 'Pad Thai', {'fr': 'Pad thaï'}),
        'Pad thaï',
      );
      expect(const L.forLang('fr').name('ผัดไทย', 'Pad Thai'), 'Pad Thai');
      // the second line on a tile: the other slot
      expect(th.nameAlt('ผัดไทย', 'Pad Thai'), 'Pad Thai');
      expect(en.nameAlt('ผัดไทย', 'Pad Thai'), 'ผัดไทย');
      expect(pickName('th', 'ผัดไทย', 'Pad Thai', null, 'th'), 'ผัดไทย');
      // the fields that edit that slot say Thai
      expect(en.nameFrLabel, 'Name (Thai)');
      expect(th.nameFrLabel, 'ชื่อ (ไทย)');
      expect(en.sizeLabelFr, 'Size (Thai)');
      expect(th.nameEnLabel, isNot(en.nameEnLabel));
    });

    test('tax labels read the data-language slot', () {
      final tax = TaxLine('VAT', 'ภาษีมูลค่าเพิ่ม', 'VAT', '7', 70);
      StoreProfile.current = const StoreProfile(locales: ['th', 'en']);
      expect(const L.forLang('th').taxLine(tax), 'ภาษีมูลค่าเพิ่ม 7%');
      expect(const L.forLang('en').taxLine(tax), 'VAT 7%');
    });
  });

  group('Thai UI', () {
    test('a Thai store offers Thai', () async {
      expect(Prefs.known, contains('th'));
      StoreProfile.current = const StoreProfile(locales: ['th', 'en']);
      Prefs.instance.hydrate(languageCode: 'th');
      expect(Prefs.instance.lang, 'th');
      expect(Prefs.instance.nextLang, 'en');
      // a store without Thai falls back
      StoreProfile.current = const StoreProfile(locales: ['en', 'fr']);
      Prefs.instance.hydrate(languageCode: 'th');
      expect(Prefs.instance.lang, 'en');
    });

    test('strings are Thai', () {
      const th = L.forLang('th');
      expect(th.cancel, 'ยกเลิก');
      expect(th.langName('th'), 'ไทย');
      expect(const L.forLang('en').langName('th'), 'Thai');
      expect(th.langName('en'), isNot('English'));
    });

    test('the language button says ไทย', () {
      expect(langButtonLabel('th'), 'ไทย');
      expect(langButtonLabel('en'), 'EN');
      expect(langButtonLabel('fr'), 'FR');
    });

    test('dates: Buddhist Era year in Thai, unchanged elsewhere', () {
      final d = DateTime(2026, 10, 8);
      Prefs.instance.lang = 'th';
      expect(Prefs.instance.fmtDate(d), '08/10/2569');
      expect(
        Prefs.instance.fmtDateTime('2026-10-08T19:05:00.000+07:00'),
        '08/10/2569 19:05',
      );
      Prefs.instance.lang = 'en';
      expect(Prefs.instance.fmtDate(d), '08/10/2026');
      Prefs.instance.lang = 'de';
      expect(Prefs.instance.fmtDate(d), '08.10.2026');
      expect(buddhistYear(2026), 2569);
      expect(thaiLongDate(d), '8 ตุลาคม 2569');
      expect(thaiMonths, hasLength(12));
      expect(thaiMonthsShort.first, 'ม.ค.');
    });
  });
}
