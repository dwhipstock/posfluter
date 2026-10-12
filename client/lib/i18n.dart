import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'api.dart';

/// Per-user display preferences: UI language (en/fr).
/// Device-persisted as the pre-login fallback; hydrated from the user profile
/// on login and PATCHed back to the server on change.
class Prefs extends ChangeNotifier {
  Prefs._();
  static final Prefs instance = Prefs._();

  static const _storage = FlutterSecureStorage();

  String lang =
      'en'; // one of the store's languages: en | fr (pubs, + es | de | af at Copper Lantern), en | es (US store), th | en (a Thai store)

  /// Every language the terminal has strings for; a store offers a subset.
  static const known = {'en', 'fr', 'es', 'de', 'af', 'th'};

  /// The store's languages (from its profile), default first.
  List<String> get storeLocales => StoreProfile.current.locales;

  String _supported(String? value) {
    final v = value?.trim().toLowerCase() ?? '';
    if (known.contains(v) && storeLocales.contains(v)) return v;
    // a language this store doesn't offer: English if it does, else its default
    return storeLocales.contains('en')
        ? 'en'
        : StoreProfile.current.defaultLocale;
  }

  /// The store described itself (GET /health). A language it doesn't offer
  /// (a pub's French on a US terminal) falls back; rebuilds on any change.
  void useStore(StoreProfile profile) {
    final before = StoreProfile.current;
    StoreProfile.current = profile;
    final next = _supported(lang);
    final changed =
        next != lang ||
        before.currency != profile.currency ||
        before.kind != profile.kind ||
        before.brand != profile.brand ||
        before.dataLocale != profile.dataLocale ||
        before.kitchenPrinting != profile.kitchenPrinting;
    lang = next;
    if (changed) notifyListeners();
  }

  // Floor-plan editor toggles — device-local only (per-terminal habit, never
  // synced to the user row). Grid + snap default on (the old hardwired grid);
  // grid step defaults to 100 logical units (the original, largest spacing).
  bool editorShowGrid = true;
  bool editorSnap = true;
  int editorGridStep = 100; // visual dot spacing: 100 (large) | 50 | 25 (small)

  bool get isEn => lang == 'en';

  Future<void> load() async {
    final storedLang = await _storage.read(key: 'pref_lang');
    lang = known.contains(storedLang) ? storedLang! : 'en';
    editorShowGrid = (await _storage.read(key: 'pref_editor_grid')) != 'false';
    editorSnap = (await _storage.read(key: 'pref_editor_snap')) != 'false';
    editorGridStep =
        int.tryParse(await _storage.read(key: 'pref_editor_grid_step') ?? '') ??
        100;
    notifyListeners();
  }

  Future<void> setEditorShowGrid(bool value) async {
    editorShowGrid = value;
    await _storage.write(key: 'pref_editor_grid', value: value.toString());
  }

  Future<void> setEditorSnap(bool value) async {
    editorSnap = value;
    await _storage.write(key: 'pref_editor_snap', value: value.toString());
  }

  Future<void> setEditorGridStep(int value) async {
    editorGridStep = value;
    await _storage.write(key: 'pref_editor_grid_step', value: value.toString());
  }

  /// Login/restore: the user's stored preference wins over the device default.
  void hydrate({required String languageCode}) {
    lang = _supported(languageCode);
    _persistLocal();
    notifyListeners();
  }

  Future<void> setLang(String value) async {
    lang = _supported(value);
    notifyListeners();
    await _persist();
  }

  /// The toggle: the store's next language (fr ⇄ en at the pubs, en ⇄ es in the US).
  String get nextLang {
    final all = storeLocales;
    final i = all.indexOf(lang);
    return all[(i < 0 ? 0 : i + 1) % all.length];
  }

  Future<void> _persist() async {
    await _persistLocal();
    // logged in → also store on the user row (survives device changes)
    if (Api.currentUser != null) {
      try {
        await Api.updatePreferences(lang);
      } catch (_) {} // offline toggle still works locally
    }
  }

  Future<void> _persistLocal() async {
    await _storage.write(key: 'pref_lang', value: lang);
  }

  /// "2026-07-08T00:12:34.000-04:00" → "08/07/2026 00:12".
  ///
  /// The store sends instants with the VENUE's offset, so the leading wall
  /// clock is already venue-local: show it as-is. Parsing into a DateTime would
  /// convert to the device's timezone, which is not the venue's.
  String fmtDateTime(String iso) {
    final m = RegExp(
      r'^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})',
    ).firstMatch(iso.trim());
    if (m == null) return iso;
    final wall = DateTime(int.parse(m[1]!), int.parse(m[2]!), int.parse(m[3]!));
    return '${fmtDate(wall)} ${m[4]}:${m[5]}';
  }

  /// Date-only, dd/MM/yyyy (German: dd.MM.yyyy; Thai: the Buddhist Era
  /// year, CE + 543, as Thai dates are written).
  String fmtDate(DateTime d) {
    String p2(int n) => n.toString().padLeft(2, '0');
    final sep = lang == 'de' ? '.' : '/';
    final year = lang == 'th' ? buddhistYear(d.year) : d.year;
    return '${p2(d.day)}$sep${p2(d.month)}$sep$year';
  }
}

/// Rebuild scope above MaterialApp: widgets that call [L.of] re-render
/// instantly on toggle, dialogs and pushed routes included.
class PrefsScope extends InheritedNotifier<Prefs> {
  PrefsScope({super.key, required super.child})
    : super(notifier: Prefs.instance);
}

Widget prefsScope({required Widget child}) => PrefsScope(child: child);

/// All UI strings, every locale inline — compile-checked, no key typos.
/// `L.of(context)` subscribes the caller to language changes.
///
/// Every string is written five times: Québec French (the pubs; vous, and
/// French typography — a no-break space before : ; ! ? and inside « »),
/// English, US Spanish (the Sage & Poppy counter; tú), German (Copper
/// Lantern's extra language; staff screens neutral, Sie where addressed) and
/// Afrikaans (Copper Lantern too; staff screens neutral, jy where addressed,
/// u for guests; ’n with a typographic apostrophe).
/// test/locale_coverage_test.dart checks that no `_t` call misses a language.
class L {
  /// en | fr | es | de | af | th
  final String lang;

  /// English (true) or French (false) — the pubs' pair.
  const L(bool en) : lang = en ? 'en' : 'fr';
  const L.forLang(this.lang);

  static L of(BuildContext context) {
    context.dependOnInheritedWidgetOfExactType<PrefsScope>();
    return L.forLang(Prefs.instance.lang);
  }

  /// The current language, without subscribing (API errors, background work).
  static L get current => L.forLang(Prefs.instance.lang);

  bool get en => lang != 'fr';
  bool get es => lang == 'es';
  bool get de => lang == 'de';
  bool get af => lang == 'af';

  /// French, English, US Spanish, German and Afrikaans, in that order, then
  /// Thai by name ([th]).
  String _t(
    String fr,
    String enS,
    String esS,
    String deS,
    String afS, {
    required String th,
  }) => switch (lang) {
    'fr' => fr,
    'es' => esS,
    'de' => deS,
    'af' => afS,
    'th' => th,
    _ => enS,
  };

  /// The store's data language: what its catalog's second name slot holds
  /// (French at the pubs, Thai at a Thai + English store).
  String get dataLang => StoreProfile.current.dataLocale;

  /// The second name slot holds Thai (a Thai + English store), not French.
  bool get _thaiData => dataLang == 'th';

  /// Data-driven names (items, zones, variants): user's language first.
  /// The catalog carries the store's data language ([fr] — French, or Thai
  /// at a Thai store) and English; any other language reads its row in
  /// [extra] (the store's translations table), else English, else [fr].
  String name(String fr, String enS, [Map<String, String>? extra]) =>
      pickName(lang, fr, enS, extra, dataLang);

  /// The other catalog language, shown as the small secondary line on menu
  /// tiles (only between the store's data language and English).
  String nameAlt(String fr, String enS) {
    if (lang != dataLang && lang != 'en') return '';
    return lang == dataLang ? enS : fr;
  }

  /// The store's currency code, for "($cur)" / "(USD)" field labels.
  String get cur => StoreProfile.current.currency;

  // common
  String get retry => _t(
    'Réessayer',
    'Retry',
    'Reintentar',
    'Erneut versuchen',
    'Probeer weer',
    th: 'ลองอีกครั้ง',
  );
  String get cancel => _t(
    'Annuler',
    'Cancel',
    'Cancelar',
    'Abbrechen',
    'Kanselleer',
    th: 'ยกเลิก',
  );
  String get ok => _t('OK', 'OK', 'OK', 'OK', 'OK', th: 'OK');
  String get done =>
      _t('Terminé', 'Done', 'Listo', 'Fertig', 'Klaar', th: 'เสร็จสิ้น');
  String get close =>
      _t('Fermer', 'Close', 'Cerrar', 'Schließen', 'Maak toe', th: 'ปิด');
  String get cannotReachServer => _t(
    'Restaurant temporairement injoignable',
    'Restaurant temporarily unavailable',
    'La tienda no está disponible por el momento',
    'Restaurant vorübergehend nicht erreichbar',
    'Restaurant tydelik onbeskikbaar',
    th: 'ร้านไม่สามารถเชื่อมต่อได้ชั่วคราว',
  );

  // on-screen keyboard (Windows tablet): key names and the enter key's labels
  String get kbDone =>
      _t('Terminé', 'Done', 'Listo', 'Fertig', 'Klaar', th: 'เสร็จสิ้น');
  String get kbNext =>
      _t('Suivant', 'Next', 'Siguiente', 'Weiter', 'Volgende', th: 'ถัดไป');
  String get kbPrevious => _t(
    'Précédent',
    'Previous',
    'Anterior',
    'Zurück',
    'Vorige',
    th: 'ก่อนหน้า',
  );
  String get kbGo => _t('Aller', 'Go', 'Ir', 'Los', 'Gaan', th: 'ไป');
  String get kbSearch =>
      _t('Rechercher', 'Search', 'Buscar', 'Suchen', 'Soek', th: 'ค้นหา');
  String get kbSend =>
      _t('Envoyer', 'Send', 'Enviar', 'Senden', 'Stuur', th: 'ส่ง');
  String get kbNewLine => _t(
    'Nouvelle ligne',
    'New line',
    'Nueva línea',
    'Neue Zeile',
    'Nuwe reël',
    th: 'ขึ้นบรรทัดใหม่',
  );
  String get kbSpace =>
      _t('Espace', 'Space', 'Espacio', 'Leertaste', 'Spasie', th: 'เว้นวรรค');
  String get kbBackspace =>
      _t('Effacer', 'Delete', 'Borrar', 'Löschen', 'Vee uit', th: 'ลบ');
  String get kbShift => _t(
    'Majuscule',
    'Shift',
    'Mayúscula',
    'Umschalt',
    'Hoofletter',
    th: 'Shift',
  );
  String get kbSymbols => _t(
    'Symboles',
    'Symbols',
    'Símbolos',
    'Symbole',
    'Simbole',
    th: 'สัญลักษณ์',
  );
  String get kbLetters => _t(
    'Lettres',
    'Letters',
    'Letras',
    'Buchstaben',
    'Alfabet',
    th: 'ตัวอักษร',
  );
  String get kbHide => _t(
    'Masquer le clavier',
    'Hide keyboard',
    'Ocultar teclado',
    'Tastatur ausblenden',
    'Versteek sleutelbord',
    th: 'ซ่อนแป้นพิมพ์',
  );

  // login
  String get enterPin => _t(
    'Entrez votre NIP pour vous connecter',
    'Enter your PIN to sign in',
    'Ingresa tu PIN para iniciar sesión',
    'PIN eingeben zum Anmelden',
    'Voer jou PIN in om aan te meld',
    th: 'ใส่ PIN เพื่อเข้าสู่ระบบ',
  );
  String get loginInterrupted => _t(
    'Connexion interrompue — entrez votre NIP de nouveau',
    'Connection interrupted — enter your PIN again',
    'Se interrumpió la conexión: ingresa tu PIN otra vez',
    'Verbindung unterbrochen – bitte PIN erneut eingeben',
    'Verbinding onderbreek — voer jou PIN weer in',
    th: 'การเชื่อมต่อขัดข้อง — กรุณาใส่ PIN อีกครั้ง',
  );
  String get whoClockingIn => _t(
    'Qui commence son quart ?',
    "Who's clocking in?",
    '¿Quién empieza su turno?',
    'Wer stempelt sich ein?',
    'Wie teken in?',
    th: 'ใครลงเวลาเข้างาน?',
  );
  String get switchLanguage => _t(
    'Changer de langue',
    'Switch language',
    'Cambiar idioma',
    'Sprache wechseln',
    'Verander taal',
    th: 'เปลี่ยนภาษา',
  );
  String get staffTerminal => _t(
    'Terminal du personnel',
    'Staff terminal',
    'Terminal del personal',
    'Personal-Terminal',
    'Personeelterminaal',
    th: 'เครื่องพนักงาน',
  );

  // floor header: short labels under the action icons
  String get navMenu =>
      _t('Menu', 'Menu', 'Menú', 'Speisekarte', 'Spyskaart', th: 'เมนู');
  String get navReports => _t(
    'Rapports',
    'Reports',
    'Reportes',
    'Berichte',
    'Verslae',
    th: 'รายงาน',
  );
  String get navRefunds => _t(
    'Remboursements',
    'Refunds',
    'Reembolsos',
    'Erstattungen',
    'Terugbetalings',
    th: 'คืนเงิน',
  );
  String get navLayout => _t(
    'Plan de salle',
    'Layout',
    'Plano',
    'Tischplan',
    'Vloerplan',
    th: 'ผังร้าน',
  );
  String get navRefresh => _t(
    'Actualiser',
    'Refresh',
    'Actualizar',
    'Aktualisieren',
    'Verfris',
    th: 'รีเฟรช',
  );
  String get navMore =>
      _t('Plus', 'More', 'Más', 'Mehr', 'Meer', th: 'เพิ่มเติม');

  // floor legend + room list
  String get rooms =>
      _t('Salles', 'Rooms', 'Salones', 'Räume', 'Vertrekke', th: 'ห้อง');
  String get legendFree =>
      _t('Libre', 'Free', 'Libre', 'Frei', 'Vry', th: 'ว่าง');
  String get legendOccupied =>
      _t('Occupée', 'Occupied', 'Ocupada', 'Besetzt', 'Beset', th: 'มีลูกค้า');
  String get legendPending => _t(
    'Commande en attente',
    'Order waiting',
    'Pedido en espera',
    'Bestellung wartet',
    'Bestelling wag',
    th: 'ออร์เดอร์รออยู่',
  );
  String tablesOccupied(int open, int total) => _t(
    '$open sur $total occupées',
    '$open of $total occupied',
    '$open de $total ocupadas',
    '$open von $total besetzt',
    '$open van $total beset',
    th: 'มีลูกค้า $open จาก $total โต๊ะ',
  );

  /// How long a check has been open, e.g. "25 min", "1 h 05".
  String openFor(Duration d) {
    if (d.inMinutes < 1) {
      return _t(
        'à l’instant',
        'just now',
        'ahora mismo',
        'gerade eben',
        'so pas',
        th: 'เมื่อสักครู่',
      );
    }
    if (d.inHours < 1) return '${d.inMinutes} min';
    final m = (d.inMinutes % 60).toString().padLeft(2, '0');
    return '${d.inHours} h $m';
  }

  // check screen cart
  String itemCount(int n) {
    if (n == 1) {
      return _t(
        '1 article',
        '1 item',
        '1 artículo',
        '1 Artikel',
        '1 item',
        th: '1 รายการ',
      );
    }
    if (n == 0) {
      return _t(
        '0 article',
        '0 items',
        '0 artículos',
        '0 Artikel',
        '0 items',
        th: '0 รายการ',
      );
    }
    return _t(
      '$n articles',
      '$n items',
      '$n artículos',
      '$n Artikel',
      '$n items',
      th: '$n รายการ',
    );
  }

  String get emptyBill => _t(
    'Rien sur cette addition pour l’instant',
    'Nothing on this bill yet',
    'Todavía no hay nada en esta cuenta',
    'Noch nichts auf dieser Rechnung',
    'Nog niks op hierdie rekening nie',
    th: 'ยังไม่มีรายการในบิลนี้',
  );
  String get each => _t('l’unité', 'each', 'c/u', 'je', 'elk', th: 'ต่อชิ้น');
  String get tapToAdd => _t(
    'Touchez un article du menu pour l’ajouter.',
    'Tap a menu item to add it.',
    'Toca un artículo del menú para agregarlo.',
    'Artikel in der Karte antippen, um ihn hinzuzufügen.',
    'Tik op ’n spyskaartitem om dit by te voeg.',
    th: 'แตะเมนูเพื่อเพิ่มรายการ',
  );

  // zones
  String get zones =>
      _t('Zones', 'Zones', 'Zonas', 'Bereiche', 'Areas', th: 'โซน');
  String get manageMenu => _t(
    'Gérer le menu (86)',
    'Manage menu (86)',
    'Administrar menú (86)',
    'Karte verwalten (86)',
    'Bestuur spyskaart (86)',
    th: 'จัดการเมนู (86)',
  );
  String get shiftReports => _t(
    'Quart / Rapports',
    'Shift / Reports',
    'Turno / Reportes',
    'Schicht / Berichte',
    'Skof / verslae',
    th: 'กะ / รายงาน',
  );
  String get logout => _t(
    'Se déconnecter',
    'Log out',
    'Cerrar sesión',
    'Abmelden',
    'Meld af',
    th: 'ออกจากระบบ',
  );

  // tables
  String get subTable => _t(
    'Sous-table',
    'Sub-table',
    'Submesa',
    'Untertisch',
    'Subtafel',
    th: 'โต๊ะย่อย',
  );
  String get free => _t('Libre', 'Free', 'Libre', 'Frei', 'Vry', th: 'ว่าง');

  // floor plan
  String seatsShort(int n) {
    if (n == 1) {
      return _t(
        '1 place',
        '1 seat',
        '1 lugar',
        '1 Platz',
        '1 sitplek',
        th: '1 ที่นั่ง',
      );
    }
    if (n == 0) {
      return _t(
        '0 place',
        '0 seats',
        '0 lugares',
        '0 Plätze',
        '0 sitplekke',
        th: '0 ที่นั่ง',
      );
    }
    return _t(
      '$n places',
      '$n seats',
      '$n lugares',
      '$n Plätze',
      '$n sitplekke',
      th: '$n ที่นั่ง',
    );
  }

  String get emptyZoneOnboarding => _t(
    'Aucune table dans cette zone pour l’instant.\nTouchez le crayon pour dessiner le plan de salle.',
    'No tables in this zone yet.\nTap the pencil to build the layout.',
    'Todavía no hay mesas en esta zona.\nToca el lápiz para armar el plano.',
    'In diesem Bereich gibt es noch keine Tische.\nTippen Sie auf den Stift, um den Tischplan anzulegen.',
    'Nog geen tafels in hierdie area nie.\nTik op die potlood om die vloerplan te bou.',
    th: 'ยังไม่มีโต๊ะในโซนนี้\nแตะรูปดินสอเพื่อจัดผังร้าน',
  );

  // floor plan editor (manager)
  String get editLayout => _t(
    'Modifier le plan de salle',
    'Edit layout',
    'Editar plano',
    'Tischplan bearbeiten',
    'Wysig vloerplan',
    th: 'แก้ไขผังร้าน',
  );
  String editLayoutTitle(String zone) => _t(
    'Modifier le plan de salle · $zone',
    'Edit layout · $zone',
    'Editar plano · $zone',
    'Tischplan bearbeiten · $zone',
    'Wysig vloerplan · $zone',
    th: 'แก้ไขผังร้าน · $zone',
  );
  String get saveLayout => _t(
    'Enregistrer le plan',
    'Save layout',
    'Guardar plano',
    'Tischplan speichern',
    'Stoor vloerplan',
    th: 'บันทึกผังร้าน',
  );
  String get layoutSaved => _t(
    'Plan de salle enregistré',
    'Layout saved',
    'Plano guardado',
    'Tischplan gespeichert',
    'Vloerplan gestoor',
    th: 'บันทึกผังร้านแล้ว',
  );
  String get addTable => _t(
    'Ajouter une table',
    'Add table',
    'Agregar mesa',
    'Tisch hinzufügen',
    'Voeg tafel by',
    th: 'เพิ่มโต๊ะ',
  );
  String get deleteTable => _t(
    'Retirer la table',
    'Remove table',
    'Quitar mesa',
    'Tisch entfernen',
    'Verwyder tafel',
    th: 'ลบโต๊ะ',
  );
  String deleteTableConfirm(String label) => _t(
    'Retirer la table « $label » du plan ? Les anciennes additions la conservent, mais elle disparaît de tous les écrans.',
    'Remove table "$label" from the plan? Old bills keep it; it disappears from every screen.',
    '¿Quitar la mesa "$label" del plano? Las cuentas anteriores la conservan, pero desaparece de todas las pantallas.',
    'Tisch „$label“ aus dem Plan entfernen? Alte Rechnungen behalten ihn, auf allen Bildschirmen verschwindet er.',
    'Verwyder tafel “$label” van die plan? Ou rekeninge behou dit; dit verdwyn van elke skerm.',
    th: 'ลบโต๊ะ "$label" ออกจากผังร้าน? บิลเก่ายังเก็บไว้ แต่โต๊ะจะหายไปจากทุกหน้าจอ',
  );
  String get tableDeleted => _t(
    'Table retirée',
    'Table removed',
    'Mesa quitada',
    'Tisch entfernt',
    'Tafel verwyder',
    th: 'ลบโต๊ะแล้ว',
  );

  /// The room editor, once a table is removed: the one that went (a
  /// sub-table names itself, never its parent).
  String tableDeletedNamed(String label) => _t(
    'Table « $label » retirée',
    'Table "$label" removed',
    'Mesa "$label" quitada',
    'Tisch „$label“ entfernt',
    'Tafel “$label” verwyder',
    th: 'ลบโต๊ะ "$label" แล้ว',
  );

  /// The room editor: a table with sub-tables can't go; names them.
  String tableHasSubTables(String label, String subs) => _t(
    'La table « $label » a des sous-tables ($subs) : retirez-les d’abord',
    'Table "$label" has sub-tables ($subs): remove them first',
    'La mesa "$label" tiene submesas ($subs): quítalas primero',
    'Tisch „$label“ hat Untertische ($subs): diese zuerst entfernen',
    'Tafel “$label” het subtafels ($subs): verwyder hulle eers',
    th: 'โต๊ะ "$label" มีโต๊ะย่อย ($subs): กรุณาลบโต๊ะย่อยก่อน',
  );
  String get renameTable => _t(
    'Renommer la table',
    'Rename table',
    'Cambiar nombre de la mesa',
    'Tisch umbenennen',
    'Hernoem tafel',
    th: 'เปลี่ยนชื่อโต๊ะ',
  );
  String get tableLabelField => _t(
    'Nom de la table (ex. : L-5)',
    'Table label (e.g. L-5)',
    'Nombre de la mesa (p. ej., L-5)',
    'Tischname (z. B. L-5)',
    'Tafelnaam (bv. L-5)',
    th: 'ชื่อโต๊ะ (เช่น L-5)',
  );

  /// Add-table dialog helper: labels are auto-assigned to the zone prefix.
  String autoLabelHint(String prefix) => _t(
    'Laissez vide pour le numéro suivant ($prefix-…)',
    'Leave blank for the next number ($prefix-…)',
    'Déjalo vacío para el siguiente número ($prefix-…)',
    'Leer lassen für die nächste Nummer ($prefix-…)',
    'Laat leeg vir die volgende nommer ($prefix-…)',
    th: 'เว้นว่างเพื่อใช้เลขถัดไป ($prefix-…)',
  );
  String get vipNameField => _t(
    'Nom VIP (vide = aucun)',
    'VIP name (blank = none)',
    'Nombre VIP (vacío = ninguno)',
    'VIP-Name (leer = keiner)',
    'VIP-naam (leeg = geen)',
    th: 'ชื่อ VIP (เว้นว่าง = ไม่มี)',
  );
  String get rotate =>
      _t('Pivoter', 'Rotate', 'Girar', 'Drehen', 'Draai', th: 'หมุน');
  String get undo =>
      _t('Annuler', 'Undo', 'Deshacer', 'Rückgängig', 'Ontdoen', th: 'เลิกทำ');
  String get editorGrid =>
      _t('Grille', 'Grid', 'Cuadrícula', 'Raster', 'Rooster', th: 'ตาราง');
  String get editorSnap =>
      _t('Magnétisme', 'Snap', 'Ajustar', 'Einrasten', 'Belyn', th: 'จัดแนว');
  String get gridOff =>
      _t('Désactivée', 'Off', 'Desactivada', 'Aus', 'Af', th: 'ปิด');
  String get gridLarge =>
      _t('Grande', 'Large', 'Grande', 'Groß', 'Groot', th: 'ใหญ่');
  String get gridMedium =>
      _t('Moyenne', 'Medium', 'Mediana', 'Mittel', 'Middel', th: 'กลาง');
  String get gridSmall =>
      _t('Petite', 'Small', 'Pequeña', 'Klein', 'Klein', th: 'เล็ก');
  String get seatsLabel =>
      _t('Places', 'Seats', 'Lugares', 'Plätze', 'Sitplekke', th: 'ที่นั่ง');
  String get unsavedLayoutTitle => _t(
    'Plan non enregistré',
    'Layout not saved',
    'Plano sin guardar',
    'Tischplan nicht gespeichert',
    'Vloerplan nie gestoor nie',
    th: 'ยังไม่ได้บันทึกผังร้าน',
  );
  String get unsavedLayoutBody => _t(
    'Les tables déplacées reprendront leur place si vous quittez maintenant.',
    'Table positions you moved will be lost if you leave now.',
    'Si sales ahora, se perderán las posiciones de las mesas que moviste.',
    'Verschobene Tische gehen verloren, wenn Sie jetzt verlassen.',
    'Tafelposisies wat jy geskuif het, sal verlore gaan as jy nou uitgaan.',
    th: 'ตำแหน่งโต๊ะที่ย้ายไว้จะหายไปถ้าออกตอนนี้',
  );
  String get unsavedItemTitle => _t(
    'Abandonner les modifications\u00A0?',
    'Discard changes?',
    '¿Descartar los cambios?',
    'Änderungen verwerfen?',
    'Gooi veranderinge weg?',
    th: 'ทิ้งการเปลี่ยนแปลง?',
  );
  String get unsavedItemBody => _t(
    'Les modifications de cet article ne sont pas enregistrées.',
    'Your changes to this item are not saved.',
    'Los cambios de este artículo no están guardados.',
    'Die Änderungen an diesem Artikel sind nicht gespeichert.',
    'Jou veranderinge aan hierdie item is nie gestoor nie.',
    th: 'การเปลี่ยนแปลงของรายการนี้ยังไม่ได้บันทึก',
  );
  String get keepEditing => _t(
    'Continuer',
    'Keep editing',
    'Seguir editando',
    'Weiter bearbeiten',
    'Hou aan wysig',
    th: 'แก้ไขต่อ',
  );
  String get discard => _t(
    'Quitter sans enregistrer',
    'Discard',
    'Descartar',
    'Verwerfen',
    'Gooi weg',
    th: 'ทิ้ง',
  );
  String get tapTableToEditHint => _t(
    'Touchez une table pour la sélectionner · glissez pour la déplacer',
    'Tap a table to select · drag to move',
    'Toca una mesa para seleccionarla · arrastra para moverla',
    'Tisch antippen zum Auswählen · ziehen zum Verschieben',
    'Tik op ’n tafel om te kies · sleep om te skuif',
    th: 'แตะโต๊ะเพื่อเลือก · ลากเพื่อย้าย',
  );

  // floor plan editor — structural objects (pool / bar front / pillar)
  String get addObject => _t(
    'Ajouter un élément',
    'Add object',
    'Agregar elemento',
    'Element hinzufügen',
    'Voeg voorwerp by',
    th: 'เพิ่มวัตถุ',
  );
  String get objectPool => _t(
    'Table de billard',
    'Pool table',
    'Mesa de billar',
    'Billardtisch',
    'Biljarttafel',
    th: 'โต๊ะพูล',
  );
  String get objectBarFront => _t(
    'Comptoir du bar',
    'Bar front',
    'Barra',
    'Tresen',
    'Kroegtoonbank',
    th: 'หน้าบาร์',
  );
  String get objectPillar =>
      _t('Colonne', 'Pillar', 'Columna', 'Säule', 'Pilaar', th: 'เสา');
  // the short caption on an unlabelled pool table / bar front on the plan
  String get objectPoolCaption =>
      _t('Billard', 'Pool', 'Billar', 'Billard', 'Biljart', th: 'พูล');
  String get objectBarCaption =>
      _t('Bar', 'Bar', 'Barra', 'Bar', 'Kroeg', th: 'บาร์');
  // the room's built-in landmarks: palette name and default caption
  String? objectTypeName(String type) => switch (type) {
    'ENTRANCE' => _t(
      'Entrée',
      'Entrance',
      'Entrada',
      'Eingang',
      'Ingang',
      th: 'ทางเข้า',
    ),
    'HOST_STAND' => _t(
      'Accueil',
      'Host stand',
      'Recepción',
      'Empfang',
      'Ontvangs',
      th: 'จุดต้อนรับ',
    ),
    'KITCHEN' => _t(
      'Cuisine',
      'Kitchen',
      'Cocina',
      'Küche',
      'Kombuis',
      th: 'ครัว',
    ),
    'RESTROOMS' => _t(
      'Toilettes',
      'Restrooms',
      'Baños',
      'Toiletten',
      'Toilette',
      th: 'ห้องน้ำ',
    ),
    'STAGE' => _t(
      'Scène',
      'Stage',
      'Escenario',
      'Bühne',
      'Verhoog',
      th: 'เวที',
    ),
    'CARRY_OUT' => _t(
      'À emporter',
      'Carry-out',
      'Para llevar',
      'Zum Mitnehmen',
      'Wegneem',
      th: 'กลับบ้าน',
    ),
    _ => null,
  };
  // a manager-made object: by hand, or suggested by the AI from a photo
  String get customObject => _t(
    'Élément personnalisé…',
    'Custom object…',
    'Elemento personalizado…',
    'Eigenes Element…',
    'Eie voorwerp…',
    th: 'วัตถุกำหนดเอง…',
  );
  String get objectFromPhoto => _t(
    'Ajouter depuis une photo…',
    'Add from photo…',
    'Agregar desde foto…',
    'Aus Foto hinzufügen…',
    'Voeg by uit foto…',
    th: 'เพิ่มจากรูปภาพ…',
  );
  String get objectFromPhotoOffNote => _t(
    'Photo IA : Internet et le menu IA requis. L’élément personnalisé fonctionne toujours.',
    'Add from photo needs AI menu setup and internet. Custom object still works.',
    'Foto con IA: requiere el menú con IA e internet. El elemento personalizado sigue funcionando.',
    '„Aus Foto“ braucht die KI-Kartenerstellung und Internet. Eigene Elemente gehen weiterhin.',
    '“Voeg by uit foto” het KI-spyskaartopstelling en internet nodig. Eie voorwerpe werk steeds.',
    th: 'การเพิ่มจากรูปภาพต้องตั้งค่าเมนู AI และใช้อินเทอร์เน็ต วัตถุกำหนดเองยังใช้ได้',
  );
  String get objectFromPhotoWorking => _t(
    'L’IA regarde la photo…',
    'AI is looking at the photo…',
    'La IA mira la foto…',
    'Die KI sieht sich das Foto an…',
    'KI kyk na die foto…',
    th: 'AI กำลังดูรูปภาพ…',
  );
  // "Set up from picture": the AI drafts the whole room from 1–4 pictures
  String get roomFromPicture => _t(
    'Créer depuis une image…',
    'Set up from picture…',
    'Crear desde una imagen…',
    'Aus Bild einrichten…',
    'Stel op uit prent…',
    th: 'ตั้งค่าจากรูปภาพ…',
  );
  // the photo tray before "Set up room": 1–4 views of the same room
  String roomPhotosHint(int max) => _t(
    'Jusqu’à $max photos de la même salle, prises de coins différents. L’IA les combine en un seul plan.',
    'Up to $max photos of the same room, taken from different corners. The AI combines them into one plan.',
    'Hasta $max fotos de la misma sala, tomadas desde distintas esquinas. La IA las combina en un solo plano.',
    'Bis zu $max Fotos desselben Raums aus verschiedenen Ecken. Die KI fügt sie zu einem Plan zusammen.',
    'Tot $max foto’s van dieselfde vertrek, uit verskillende hoeke geneem. Die KI voeg hulle saam tot een plan.',
    th: 'ถ่ายรูปห้องเดียวกันได้สูงสุด $max รูป จากมุมต่าง ๆ AI จะรวมเป็นผังเดียว',
  );
  String roomPhotosCount(int n, int max) => _t(
    '$n photo(s) sur $max',
    '$n of $max photos',
    '$n de $max fotos',
    '$n von $max Fotos',
    '$n van $max foto’s',
    th: '$n จาก $max รูป',
  );
  String get roomPhotoTakeAnother => _t(
    'Prendre une autre photo',
    'Take another photo',
    'Tomar otra foto',
    'Noch ein Foto aufnehmen',
    'Neem nog ’n foto',
    th: 'ถ่ายรูปอีกรูป',
  );
  String get roomPhotoChooseFiles => _t(
    'Choisir des fichiers',
    'Choose files',
    'Elegir archivos',
    'Dateien auswählen',
    'Kies lêers',
    th: 'เลือกไฟล์',
  );
  String get roomPhotoRemove => _t(
    'Retirer la photo',
    'Remove photo',
    'Quitar la foto',
    'Foto entfernen',
    'Verwyder foto',
    th: 'ลบรูปภาพ',
  );
  String get roomPhotosSetUp => _t(
    'Créer la salle',
    'Set up room',
    'Crear la sala',
    'Raum einrichten',
    'Stel vertrek op',
    th: 'ตั้งค่าห้อง',
  );
  String get roomFromPictureOffNote => _t(
    'Nécessite le menu IA et Internet.',
    'Needs AI menu setup and internet.',
    'Requiere el menú con IA e internet.',
    'Braucht die KI-Kartenerstellung und Internet.',
    'Het KI-spyskaartopstelling en internet nodig.',
    th: 'ต้องตั้งค่าเมนู AI และใช้อินเทอร์เน็ต',
  );
  String get roomLayoutHistory => _t(
    'Salles créées par l’IA',
    'Rooms set up by AI',
    'Salas creadas con IA',
    'Von der KI eingerichtete Räume',
    'Vertrekke deur KI opgestel',
    th: 'ห้องที่ AI ตั้งค่าไว้',
  );
  String roomPreviewSummary(int tables, int seats, int objects) => _t(
    '$tables table(s), $seats place(s), $objects élément(s)',
    '$tables table(s), $seats seat(s), $objects object(s)',
    '$tables mesa(s), $seats lugar(es), $objects elemento(s)',
    '$tables Tisch(e), $seats Platz/Plätze, $objects Element(e)',
    '$tables tafel(s), $seats sitplek(ke), $objects voorwerp(e)',
    th: '$tables โต๊ะ, $seats ที่นั่ง, $objects วัตถุ',
  );
  String get roomPreviewHint => _t(
    'Aperçu : rien ne change avant « Appliquer ». Glissez pour déplacer, × pour retirer.',
    'Preview: nothing changes until Apply. Drag to move, × to remove.',
    'Vista previa: nada cambia hasta Aplicar. Arrastra para mover, × para quitar.',
    'Vorschau: nichts ändert sich vor „Übernehmen“. Ziehen zum Verschieben, × zum Entfernen.',
    'Voorskou: niks verander voor “Pas toe” nie. Sleep om te skuif, × om te verwyder.',
    th: 'ตัวอย่าง: ยังไม่มีอะไรเปลี่ยนจนกว่าจะกดใช้ ลากเพื่อย้าย กด × เพื่อลบ',
  );
  String get roomModeReplace => _t(
    'Remplacer',
    'Replace',
    'Reemplazar',
    'Ersetzen',
    'Vervang',
    th: 'แทนที่',
  );
  String get roomModeMerge => _t(
    'Ajouter',
    'Add to room',
    'Añadir',
    'Hinzufügen',
    'Voeg by vertrek',
    th: 'เพิ่มในห้อง',
  );
  String roomExisting(int n) => _t(
    'La salle a déjà $n table(s).',
    'This room already has $n table(s).',
    'La sala ya tiene $n mesa(s).',
    'Der Raum hat schon $n Tisch(e).',
    'Hierdie vertrek het reeds $n tafel(s).',
    th: 'ห้องนี้มีโต๊ะอยู่แล้ว $n โต๊ะ',
  );
  String roomProtected(int n) => _t(
    '$n table(s) avec une addition ouverte restent en place.',
    '$n table(s) with an open bill stay where they are.',
    '$n mesa(s) con cuenta abierta se quedan donde están.',
    '$n Tisch(e) mit offener Rechnung bleiben, wo sie sind.',
    '$n tafel(s) met ’n oop rekening bly waar hulle is.',
    th: 'โต๊ะที่มีบิลค้างอยู่ $n โต๊ะจะอยู่ที่เดิม',
  );
  String get roomNotSure => _t(
    'Pas certain :',
    'Not sure:',
    'No está seguro:',
    'Unsicher:',
    'Onseker:',
    th: 'ไม่แน่ใจ:',
  );
  String roomSkipped(int n) => _t(
    '$n élément(s) ignoré(s) ou corrigé(s) :',
    '$n thing(s) skipped or fixed:',
    '$n elemento(s) omitido(s) o corregido(s):',
    '$n Element(e) übersprungen oder korrigiert:',
    '$n ding(e) oorgeslaan of reggemaak:',
    th: 'ข้ามหรือแก้ไข $n รายการ:',
  );
  String get roomApply =>
      _t('Appliquer', 'Apply', 'Aplicar', 'Übernehmen', 'Pas toe', th: 'ใช้');
  String roomApplied(int n) => _t(
    '$n élément(s) ajouté(s) à la salle',
    '$n thing(s) added to the room',
    '$n elemento(s) añadido(s) a la sala',
    '$n Element(e) zum Raum hinzugefügt',
    '$n ding(e) by die vertrek gevoeg',
    th: 'เพิ่ม $n รายการในห้องแล้ว',
  );
  // voice input (menu AI chat, floor assistant): hold to talk, or tap to start / stop
  String get micHint => _t(
    'Maintenez pour parler, ou touchez pour commencer et encore pour arrêter',
    'Hold to talk, or tap to start and tap again to stop',
    'Mantén pulsado para hablar, o toca para empezar y otra vez para parar',
    'Zum Sprechen gedrückt halten, oder antippen zum Starten und erneut zum Stoppen',
    'Hou in om te praat, of tik om te begin en tik weer om te stop',
    th: 'กดค้างเพื่อพูด หรือแตะเพื่อเริ่มและแตะอีกครั้งเพื่อหยุด',
  );
  String get micListening => _t(
    'J’écoute… relâchez ou touchez pour arrêter',
    'Listening… release or tap to stop',
    'Escuchando… suelta o toca para parar',
    'Ich höre zu… loslassen oder antippen zum Stoppen',
    'Luister… los of tik om te stop',
    th: 'กำลังฟัง… ปล่อยหรือแตะเพื่อหยุด',
  );
  String get micUnavailable => _t(
    'Le micro n’est pas disponible. Vérifiez l’autorisation, ou tapez la demande.',
    'The microphone isn’t available. Check the permission, or type the request.',
    'El micrófono no está disponible. Revisa el permiso, o escribe la petición.',
    'Das Mikrofon ist nicht verfügbar. Prüfen Sie die Berechtigung oder tippen Sie die Anfrage.',
    'Die mikrofoon is nie beskikbaar nie. Kontroleer die toestemming, of tik die versoek.',
    th: 'ใช้ไมโครโฟนไม่ได้ ตรวจสอบสิทธิ์การเข้าถึง หรือพิมพ์คำขอแทน',
  );
  String aiHeard(String text) => _t(
    'Entendu : « $text »',
    'Heard: “$text”',
    'Oído: «$text»',
    'Verstanden: „$text“',
    'Gehoor: “$text”',
    th: 'ได้ยินว่า: “$text”',
  );
  // floor plan "Ask AI": edit the current room by text or voice
  String get floorAskAi => _t(
    'Demander à l’IA…',
    'Ask AI…',
    'Preguntar a la IA…',
    'KI fragen…',
    'Vra KI…',
    th: 'ถาม AI…',
  );
  String get floorAskAiHint => _t(
    'Ex. « ajoute quatre tables de 2 le long de la fenêtre », « table 5 ronde avec 6 places », « enlève le billard »',
    'e.g. “add four 2-tops along the window”, “make table 5 round with 6 seats”, “remove the pool table”',
    'p. ej. «añade cuatro mesas de 2 junto a la ventana», «mesa 5 redonda con 6 lugares», «quita el billar»',
    'z. B. „vier Zweiertische am Fenster“, „Tisch 5 rund mit 6 Plätzen“, „Billardtisch entfernen“',
    'bv. “voeg vier 2-tafels langs die venster by”, “maak tafel 5 rond met 6 sitplekke”, “verwyder die biljarttafel”',
    th: 'เช่น “เพิ่มโต๊ะ 2 ที่นั่ง 4 ตัวริมหน้าต่าง”, “เปลี่ยนโต๊ะ 5 เป็นโต๊ะกลม 6 ที่นั่ง”, “ลบโต๊ะพูล”',
  );
  // AI working card: a request can take 5-60s, so it gets a step label, a
  // live elapsed count and an estimated progress bar instead of a banner
  // that would otherwise look stuck.
  String get aiStepListening => _t(
    'Écoute…',
    'Listening…',
    'Escuchando…',
    'Ich höre zu…',
    'Luister…',
    th: 'กำลังฟัง…',
  );
  String get aiStepSending => _t(
    'Envoi…',
    'Sending…',
    'Enviando…',
    'Wird gesendet…',
    'Stuur tans…',
    th: 'กำลังส่ง…',
  );
  String aiStepThinking(bool slow) => slow
      ? _t(
          'Réflexion… habituellement 20 à 60 secondes',
          'Thinking… usually 20–60 seconds',
          'Pensando… habitualmente 20 a 60 segundos',
          'Überlegt… meist 20 bis 60 Sekunden',
          'Dink… gewoonlik 20–60 sekondes',
          th: 'กำลังคิด… ปกติใช้เวลา 20–60 วินาที',
        )
      : _t(
          'Réflexion… habituellement 5 à 15 secondes',
          'Thinking… usually 5–15 seconds',
          'Pensando… habitualmente 5 a 15 segundos',
          'Überlegt… meist 5 bis 15 Sekunden',
          'Dink… gewoonlik 5–15 sekondes',
          th: 'กำลังคิด… ปกติใช้เวลา 5–15 วินาที',
        );
  String get aiStepAlmostDone => _t(
    'Presque fini…',
    'Almost done…',
    'Casi listo…',
    'Fast fertig…',
    'Amper klaar…',
    th: 'ใกล้เสร็จแล้ว…',
  );
  String get floorEditHint => _t(
    'Aperçu : rien ne change avant « Appliquer ». Les contours montrent le nouveau et le déplacé, le pâle est retiré.',
    'Preview: nothing changes until Apply. Outlined = new or moved; faded = removed.',
    'Vista previa: nada cambia hasta Aplicar. Con borde = nuevo o movido; pálido = quitado.',
    'Vorschau: nichts ändert sich vor „Übernehmen“. Umrandet = neu oder verschoben; blass = entfernt.',
    'Voorskou: niks verander voor “Pas toe” nie. Omlyn = nuut of geskuif; verbleik = verwyder.',
    th: 'ตัวอย่าง: ยังไม่มีอะไรเปลี่ยนจนกว่าจะกดใช้ เส้นขอบ = ใหม่หรือย้าย จาง = ลบ',
  );
  String floorEditApplied(int n) => _t(
    'Plan de salle mis à jour : $n changement(s)',
    'Floor plan updated: $n change(s)',
    'Plano actualizado: $n cambio(s)',
    'Raumplan aktualisiert: $n Änderung(en)',
    'Vloerplan bygewerk: $n verandering(e)',
    th: 'อัปเดตผังร้านแล้ว: เปลี่ยน $n รายการ',
  );
  String floorChangeKind(String kind) => switch (kind) {
    'add_table' => _t(
      'Nouvelle table',
      'New table',
      'Mesa nueva',
      'Neuer Tisch',
      'Nuwe tafel',
      th: 'โต๊ะใหม่',
    ),
    'update_table' => _t(
      'Table modifiée',
      'Table changed',
      'Mesa cambiada',
      'Tisch geändert',
      'Tafel verander',
      th: 'แก้ไขโต๊ะ',
    ),
    'remove_table' => _t(
      'Table retirée',
      'Table removed',
      'Mesa quitada',
      'Tisch entfernt',
      'Tafel verwyder',
      th: 'ลบโต๊ะ',
    ),
    'add_object' => _t(
      'Nouvel élément',
      'New object',
      'Elemento nuevo',
      'Neues Element',
      'Nuwe voorwerp',
      th: 'วัตถุใหม่',
    ),
    'update_object' => _t(
      'Élément déplacé',
      'Object moved',
      'Elemento movido',
      'Element verschoben',
      'Voorwerp geskuif',
      th: 'ย้ายวัตถุ',
    ),
    _ => _t(
      'Élément retiré',
      'Object removed',
      'Elemento quitado',
      'Element entfernt',
      'Voorwerp verwyder',
      th: 'ลบวัตถุ',
    ),
  };
  String floorField(String field) => switch (field) {
    'number' => _t(
      'Numéro',
      'Number',
      'Número',
      'Nummer',
      'Nommer',
      th: 'หมายเลข',
    ),
    'shape' => _t('Forme', 'Shape', 'Forma', 'Form', 'Vorm', th: 'รูปทรง'),
    'seats' => seatsLabel,
    'position' => _t(
      'Emplacement',
      'Position',
      'Ubicación',
      'Lage',
      'Posisie',
      th: 'ตำแหน่ง',
    ),
    'size' => _t('Taille', 'Size', 'Tamaño', 'Größe', 'Grootte', th: 'ขนาด'),
    _ => _t('Angle', 'Rotation', 'Giro', 'Drehung', 'Draaiing', th: 'การหมุน'),
  };
  String floorShapeName(String shape) => switch (shape) {
    'ROUND' => _t('ronde', 'round', 'redonda', 'rund', 'rond', th: 'กลม'),
    'SQUARE' => _t(
      'carrée',
      'square',
      'cuadrada',
      'quadratisch',
      'vierkantig',
      th: 'สี่เหลี่ยมจัตุรัส',
    ),
    'RECT' => _t(
      'rectangulaire',
      'rectangle',
      'rectangular',
      'rechteckig',
      'reghoekig',
      th: 'สี่เหลี่ยมผืนผ้า',
    ),
    'BAR' => _t(
      'haute',
      'high-top',
      'alta',
      'Stehtisch',
      'hoë tafel',
      th: 'โต๊ะสูง',
    ),
    _ => shape,
  };
  String get objectNameFr => _thaiData
      ? _t(
          'Nom (thaï)',
          'Name (Thai)',
          'Nombre (tailandés)',
          'Name (Thailändisch)',
          'Naam (Thai)',
          th: 'ชื่อ (ไทย)',
        )
      : _t(
          'Nom (français)',
          'Name (French)',
          'Nombre (francés)',
          'Name (Französisch)',
          'Naam (Frans)',
          th: 'ชื่อ (ฝรั่งเศส)',
        );
  String get objectNameEn => _t(
    'Nom (anglais)',
    'Name (English)',
    'Nombre (inglés)',
    'Name (Englisch)',
    'Naam (Engels)',
    th: 'ชื่อ (อังกฤษ)',
  );
  String get objectIcon =>
      _t('Icône', 'Icon', 'Ícono', 'Symbol', 'Ikoon', th: 'ไอคอน');
  String get objectShapeRect => _t(
    'Rectangulaire',
    'Rectangle',
    'Rectangular',
    'Eckig',
    'Reghoekig',
    th: 'สี่เหลี่ยม',
  );
  String get objectShapeRound =>
      _t('Rond', 'Round', 'Redondo', 'Rund', 'Rond', th: 'กลม');
  String get objectSize =>
      _t('Taille', 'Size', 'Tamaño', 'Größe', 'Grootte', th: 'ขนาด');
  String get placeObject =>
      _t('Placer', 'Place', 'Colocar', 'Platzieren', 'Plaas', th: 'วาง');
  String get deleteObject => _t(
    'Retirer l’élément',
    'Remove object',
    'Quitar elemento',
    'Element entfernen',
    'Verwyder voorwerp',
    th: 'ลบวัตถุ',
  );
  String deleteObjectConfirm(String name) => _t(
    'Retirer « $name » du plan ?',
    'Remove "$name" from the plan?',
    '¿Quitar "$name" del plano?',
    '„$name“ aus dem Plan entfernen?',
    'Verwyder “$name” van die plan?',
    th: 'ลบ "$name" ออกจากผังร้าน?',
  );
  String get objectDeleted => _t(
    'Élément retiré',
    'Object removed',
    'Elemento quitado',
    'Element entfernt',
    'Voorwerp verwyder',
    th: 'ลบวัตถุแล้ว',
  );

  // check screen
  String get table =>
      _t('Table', 'Table', 'Mesa', 'Tisch', 'Tafel', th: 'โต๊ะ');
  String get bill =>
      _t('Addition', 'Bill', 'Cuenta', 'Rechnung', 'Rekening', th: 'บิล');

  /// A document number: "n° 12" in French, "#12" in English and US Spanish.
  String numbered(int n) =>
      _t('n°\u00A0$n', '#$n', '#$n', '#$n', '#$n', th: '#$n');

  /// "Addition n° 12" / "Bill #12" / "Cuenta #12".
  String billNo(int id) => '$bill ${numbered(id)}';
  String get corkage => _t(
    'Droit de bouchon',
    'Corkage',
    'Descorche',
    'Korkgeld',
    'Kurkgeld',
    th: 'ค่าเปิดขวด',
  );
  String get voidBillManager => _t(
    'Annuler l’addition (gérant)',
    'Void bill (manager)',
    'Anular cuenta (gerente)',
    'Rechnung stornieren (Manager)',
    'Kanselleer rekening (bestuurder)',
    th: 'ยกเลิกบิล (ผู้จัดการ)',
  );
  String get noItemsYet => _t(
    'Aucun article pour l’instant — touchez le menu pour en ajouter',
    'No items yet — tap the menu to add',
    'Todavía no hay artículos: toca el menú para agregar',
    'Noch keine Artikel – zum Hinzufügen Karte antippen',
    'Nog geen items nie — tik op die spyskaart om by te voeg',
    th: 'ยังไม่มีรายการ — แตะเมนูเพื่อเพิ่ม',
  );
  String pendingFromPhone(int n) => _t(
    'Commandé par téléphone — à confirmer ($n)',
    'Ordered from phone — awaiting confirm ($n)',
    'Pedido desde el celular: por confirmar ($n)',
    'Per Handy bestellt – Bestätigung ausstehend ($n)',
    'Per foon bestel — wag vir bevestiging ($n)',
    th: 'สั่งจากโทรศัพท์ — รอยืนยัน ($n)',
  );
  String get acceptOrder =>
      _t('Accepter', 'Accept', 'Aceptar', 'Annehmen', 'Aanvaar', th: 'รับ');
  String get rejectOrder =>
      _t('Refuser', 'Reject', 'Rechazar', 'Ablehnen', 'Weier', th: 'ปฏิเสธ');
  String get deleteLine =>
      _t('Retirer', 'Remove', 'Quitar', 'Entfernen', 'Verwyder', th: 'ลบ');

  /// "[name] is no longer available" (deleted, 86ed, or that size deleted).
  String itemNoLongerAvailable(String name) => _t(
    '$name n’est plus offert',
    '$name is no longer available',
    '$name ya no está disponible',
    '$name ist nicht mehr verfügbar',
    '$name is nie meer beskikbaar nie',
    th: '$name ไม่มีจำหน่ายแล้ว',
  );

  /// The menu changed under the screen: the new price, confirm to add.
  String priceChangedTo(String name, String price) => _t(
    'Le prix de $name est maintenant $price',
    'The price of $name changed to $price',
    'El precio de $name cambió a $price',
    'Der Preis von $name ist jetzt $price',
    'Die prys van $name is nou $price',
    th: 'ราคาของ $name เปลี่ยนเป็น $price',
  );
  String get priceChangedTitle => _t(
    'Prix modifié',
    'Price changed',
    'Precio modificado',
    'Preis geändert',
    'Prys verander',
    th: 'ราคาเปลี่ยน',
  );
  String addAtPrice(String price) => _t(
    'Ajouter à $price',
    'Add at $price',
    'Agregar a $price',
    'Für $price hinzufügen',
    'Voeg by teen $price',
    th: 'เพิ่มในราคา $price',
  );
  String get menuUpdated => _t(
    'Le menu a été mis à jour',
    'The menu was updated',
    'El menú se actualizó',
    'Die Karte wurde aktualisiert',
    'Die spyskaart is bygewerk',
    th: 'เมนูมีการอัปเดต',
  );

  String get emptyBillClosed => _t(
    'Addition vide fermée',
    'Empty bill closed',
    'Se cerró la cuenta vacía',
    'Leere Rechnung geschlossen',
    'Leë rekening toegemaak',
    th: 'ปิดบิลว่างแล้ว',
  );

  /// The check went away under the screen ([status] as the store has it
  /// now): a counter / kiosk [order] or a table's bill.
  String checkGone(String status, {required bool order}) => switch (status) {
    'CLOSED' =>
      order
          ? _t(
              'Cette commande a déjà été payée sur un autre appareil',
              'This order was already paid on another device',
              'Este pedido ya se pagó en otro dispositivo',
              'Diese Bestellung wurde bereits auf einem anderen Gerät bezahlt',
              'Hierdie bestelling is reeds op ’n ander toestel betaal',
              th: 'ออร์เดอร์นี้ชำระเงินแล้วบนอุปกรณ์อื่น',
            )
          : _t(
              'Cette addition a déjà été payée sur un autre appareil',
              'This bill was already paid on another device',
              'Esta cuenta ya se pagó en otro dispositivo',
              'Diese Rechnung wurde bereits auf einem anderen Gerät bezahlt',
              'Hierdie rekening is reeds op ’n ander toestel betaal',
              th: 'บิลนี้ชำระเงินแล้วบนอุปกรณ์อื่น',
            ),
    'CANCELLED' || 'VOID' =>
      order
          ? _t(
              'Cette commande a été annulée — non payée à temps ou effacée sur un autre appareil',
              'This order was cancelled — it expired unpaid or was cleared on another device',
              'Este pedido se canceló: venció sin pagar o se borró en otro dispositivo',
              'Diese Bestellung wurde storniert – unbezahlt abgelaufen oder auf einem anderen Gerät gelöscht',
              'Hierdie bestelling is gekanselleer — dit het onbetaal verval of is op ’n ander toestel uitgevee',
              th: 'ออร์เดอร์นี้ถูกยกเลิก — หมดเวลาโดยไม่ได้ชำระเงิน หรือถูกล้างบนอุปกรณ์อื่น',
            )
          : _t(
              'Cette addition a été annulée sur un autre appareil',
              'This bill was cancelled on another device',
              'Esta cuenta se canceló en otro dispositivo',
              'Diese Rechnung wurde auf einem anderen Gerät storniert',
              'Hierdie rekening is op ’n ander toestel gekanselleer',
              th: 'บิลนี้ถูกยกเลิกบนอุปกรณ์อื่น',
            ),
    _ =>
      order
          ? _t(
              'Cette commande a été fermée sur un autre appareil',
              'This order was closed on another device',
              'Este pedido se cerró en otro dispositivo',
              'Diese Bestellung wurde auf einem anderen Gerät geschlossen',
              'Hierdie bestelling is op ’n ander toestel gesluit',
              th: 'ออร์เดอร์นี้ถูกปิดบนอุปกรณ์อื่น',
            )
          : _t(
              'Cette addition a été fermée sur un autre appareil',
              'This bill was closed on another device',
              'Esta cuenta se cerró en otro dispositivo',
              'Diese Rechnung wurde auf einem anderen Gerät geschlossen',
              'Hierdie rekening is op ’n ander toestel gesluit',
              th: 'บิลนี้ถูกปิดบนอุปกรณ์อื่น',
            ),
  };
  String get total =>
      _t('Total', 'Total', 'Total', 'Gesamt', 'Totaal', th: 'ยอดรวม');
  String get subtotal => _t(
    'Sous-total',
    'Subtotal',
    'Subtotal',
    'Zwischensumme',
    'Subtotaal',
    th: 'ยอดรวมย่อย',
  );

  /// A tax added on top, e.g. "NC sales tax 7.25%" / "NC sales tax 7,25 %" (rate is a decimal string).
  /// Data, not a string table: the tax names come from the store.
  /// The report's tax section: each tax collected, for remittance.
  String get taxCollected => _t(
    'Taxes perçues',
    'Tax collected',
    'Impuestos cobrados',
    'Eingenommene Steuern',
    'Belasting ingevorder',
    th: 'ภาษีที่เก็บ',
  );
  String get taxTotal => _t(
    'Total des taxes',
    'Total tax',
    'Total de impuestos',
    'Steuern gesamt',
    'Totale belasting',
    th: 'ภาษีรวม',
  );

  /// Who a tax is paid to: "remit to NCDOR".
  String remitTo(String who) => _t(
    'à verser à $who',
    'remit to $who',
    'a pagar a $who',
    'abzuführen an $who',
    'betaalbaar aan $who',
    th: 'นำส่ง $who',
  );

  /// The one combined line ([TaxLine.forGuests]): "Tax (8.25%)", "Taxes (8,25 %)"
  /// — the store's own word when it set one.
  String taxLine(TaxLine tax) =>
      tax.isCombined ? _taxCombined(tax) : _taxItem(tax);

  String _taxCombined(TaxLine tax) {
    final own = lang == dataLang ? tax.labelFr : tax.labelEn;
    final word = own.trim().isNotEmpty
        ? own
        : _t('Taxes', 'Tax', 'Impuesto', 'Steuer', 'Belasting', th: 'ภาษี');
    // the rate always reads "8.25%", like money, in every language
    return '$word (${tax.ratePercent}%)';
  }

  String _taxItem(TaxLine tax) => lang == dataLang
      ? '${tax.labelFr} ${tax.ratePercent}%'
      : '${tax.labelEn} ${tax.ratePercent}%';
  String get pay =>
      _t('Payer', 'Pay', 'Cobrar', 'Bezahlen', 'Betaal', th: 'ชำระเงิน');
  String get ordersAwaiting => _t(
    'Commandes à confirmer',
    'Orders awaiting confirm',
    'Pedidos por confirmar',
    'Bestellungen zur Bestätigung',
    'Bestellings wat wag vir bevestiging',
    th: 'ออร์เดอร์รอยืนยัน',
  );
  String sizesFrom(int n, String price) => _t(
    '$n formats, dès $price',
    '$n sizes $price+',
    '$n tamaños desde $price',
    '$n Größen ab $price',
    '$n groottes $price+',
    th: '$n ขนาด $price+',
  );
  String get qty => _t('Qté', 'Qty', 'Cant.', 'Menge', 'Aantal', th: 'จำนวน');
  String get noteHint => _t(
    'Note (ex. : sans glace)',
    'Note (e.g. no ice)',
    'Nota (p. ej., sin hielo)',
    'Notiz (z. B. ohne Eis)',
    'Nota (bv. geen ys nie)',
    th: 'หมายเหตุ (เช่น ไม่ใส่น้ำแข็ง)',
  );
  String addToBill(String price) => _t(
    'Ajouter à l’addition · $price',
    'Add to bill $price',
    'Agregar a la cuenta · $price',
    'Auf Rechnung $price',
    'Voeg by rekening $price',
    th: 'เพิ่มในบิล $price',
  );

  // void flow
  String get voidApprovalTitle => _t(
    'Annuler l’addition — approbation du gérant',
    'Void bill — manager approval',
    'Anular cuenta: aprobación del gerente',
    'Rechnung stornieren – Freigabe durch Manager',
    'Kanselleer rekening — bestuurder se goedkeuring',
    th: 'ยกเลิกบิล — ต้องให้ผู้จัดการอนุมัติ',
  );
  String get voidReasonTitle => _t(
    'Motif de l’annulation',
    'Void reason',
    'Motivo de la anulación',
    'Stornogrund',
    'Rede vir kansellasie',
    th: 'เหตุผลที่ยกเลิก',
  );
  List<String> get voidReasons => [
    _t(
      'Mauvaise table',
      'Wrong table',
      'Mesa equivocada',
      'Falscher Tisch',
      'Verkeerde tafel',
      th: 'ผิดโต๊ะ',
    ),
    _t(
      'Le client a changé d’idée',
      'Customer changed mind',
      'El cliente cambió de opinión',
      'Gast hat es sich anders überlegt',
      'Klant het van plan verander',
      th: 'ลูกค้าเปลี่ยนใจ',
    ),
    _t(
      'Mauvais prix',
      'Wrong price',
      'Precio equivocado',
      'Falscher Preis',
      'Verkeerde prys',
      th: 'ราคาผิด',
    ),
    _t(
      'Test du système',
      'System test',
      'Prueba del sistema',
      'Systemtest',
      'Stelseltoets',
      th: 'ทดสอบระบบ',
    ),
  ];
  String get otherReason => _t(
    'Autre motif',
    'Other reason',
    'Otro motivo',
    'Anderer Grund',
    'Ander rede',
    th: 'เหตุผลอื่น',
  );
  // void a bill that already has payments: hand them back first
  String get voidPaidTitle => _t(
    'Cette addition a déjà des paiements',
    'This bill already has payments',
    'Esta cuenta ya tiene pagos',
    'Diese Rechnung hat bereits Zahlungen',
    'Hierdie rekening het reeds betalings',
    th: 'บิลนี้มีการชำระเงินแล้ว',
  );
  String voidPaidBody(String amount) => _t(
    '$amount a été payé. Rendez-le au client (comptant de la caisse, carte remboursée sur son terminal), puis l’addition est annulée. Un gérant doit approuver.',
    '$amount was paid. Hand it back to the guest (cash from the drawer, a card refunded on its terminal), then the bill is voided. A manager must approve.',
    'Se pagó $amount. Devuélvelo al cliente (efectivo de la caja, tarjeta reembolsada en su terminal) y luego se anula la cuenta. Debe aprobarlo un gerente.',
    '$amount wurde bezahlt. Dem Gast zurückgeben (Bargeld aus der Kasse, Karte am Terminal erstatten), dann wird die Rechnung storniert. Ein Manager muss freigeben.',
    '$amount is betaal. Gee dit terug aan die gas (kontant uit die laai, ’n kaart terugbetaal op sy terminaal), dan word die rekening gekanselleer. ’n Bestuurder moet goedkeur.',
    th: 'ชำระแล้ว $amount คืนเงินให้ลูกค้าก่อน (เงินสดจากลิ้นชัก หรือคืนเงินบัตรที่เครื่องรูดบัตร) แล้วบิลจะถูกยกเลิก ต้องให้ผู้จัดการอนุมัติ',
  );
  String get handBackAndVoid => _t(
    'Rendre et annuler',
    'Hand back and void',
    'Devolver y anular',
    'Zurückgeben und stornieren',
    'Gee terug en kanselleer',
    th: 'คืนเงินและยกเลิก',
  );
  String get rejectAllOrders => _t(
    'Tout refuser',
    'Reject all',
    'Rechazar todo',
    'Alle ablehnen',
    'Weier alles',
    th: 'ปฏิเสธทั้งหมด',
  );
  String voidedBill(int id) => _t(
    'Addition n° $id annulée',
    'Bill #$id voided',
    'Cuenta #$id anulada',
    'Rechnung #$id storniert',
    'Rekening #$id gekanselleer',
    th: 'ยกเลิกบิล #$id แล้ว',
  );

  // corkage dialog
  String get corkageTitle => _t(
    'Droit de bouchon',
    'Corkage',
    'Descorche',
    'Korkgeld',
    'Kurkgeld',
    th: 'ค่าเปิดขวด',
  );
  String get bottlesBrought => _t(
    'Bouteilles apportées par le client',
    'Bottles brought by customer',
    'Botellas que trajo el cliente',
    'Vom Gast mitgebrachte Flaschen',
    'Bottels deur klant saamgebring',
    th: 'ขวดที่ลูกค้านำมาเอง',
  );

  // tender screen
  String get outstanding =>
      _t('À payer', 'Due', 'Por pagar', 'Offen', 'Verskuldig', th: 'ค้างชำระ');
  String paidOf(String paid, String total) => _t(
    'Payé $paid sur $total',
    'Paid $paid / $total',
    'Pagado $paid de $total',
    'Bezahlt $paid / $total',
    'Betaal $paid / $total',
    th: 'ชำระแล้ว $paid / $total',
  );
  String get cash =>
      _t('Comptant', 'Cash', 'Efectivo', 'Bar', 'Kontant', th: 'เงินสด');
  String get card =>
      _t('Carte', 'Card', 'Tarjeta', 'Karte', 'Kaart', th: 'บัตร');
  String get bankTransfer => _t(
    'Virement',
    'Transfer',
    'Transferencia',
    'Überweisung',
    'Oorplasing',
    th: 'โอนเงิน',
  );
  String get cashInHint => _t(
    'Comptant reçu ($cur) — paiement partiel accepté',
    'Cash received ($cur) — partial OK',
    'Efectivo recibido ($cur): se acepta pago parcial',
    'Bar erhalten ($cur) – Teilbetrag möglich',
    'Kontant ontvang ($cur) — gedeeltelik OK',
    th: 'รับเงินสด ($cur) — จ่ายบางส่วนได้',
  );
  String get receive => _t(
    'Encaisser',
    'Receive',
    'Cobrar',
    'Kassieren',
    'Ontvang',
    th: 'รับเงิน',
  );
  String amountHint(String due) => _t(
    'Montant ($cur) — vide = la totalité, $due',
    'Amount ($cur) — blank = full $due',
    'Monto ($cur): vacío = el total, $due',
    'Betrag ($cur) – leer = voller Betrag $due',
    'Bedrag ($cur) — leeg = volle $due',
    th: 'จำนวนเงิน ($cur) — เว้นว่าง = เต็มจำนวน $due',
  );
  String get useCardTerminal => _t(
    'Utiliser le terminal de paiement',
    'Use card terminal',
    'Usar la terminal de tarjetas',
    'Kartenterminal verwenden',
    'Gebruik kaartterminaal',
    th: 'ใช้เครื่องรูดบัตร',
  );
  String get showBankAccount => _t(
    'Afficher les coordonnées bancaires',
    'Show account details',
    'Mostrar datos de la cuenta',
    'Kontodaten anzeigen',
    'Wys rekeningbesonderhede',
    th: 'แสดงรายละเอียดบัญชี',
  );
  String amountToPay(String amount) => _t(
    'Montant à payer : $amount',
    'Amount due $amount',
    'Monto a pagar: $amount',
    'Offener Betrag $amount',
    'Bedrag verskuldig $amount',
    th: 'ยอดที่ต้องชำระ $amount',
  );
  String get confirmMoneyIn => _t(
    'Confirmer — paiement reçu',
    'Confirm — money received',
    'Confirmar: dinero recibido',
    'Bestätigen – Geld erhalten',
    'Bevestig — geld ontvang',
    th: 'ยืนยัน — ได้รับเงินแล้ว',
  );
  String get cashReceivedTitle => _t(
    'Comptant reçu ✓',
    'Cash received ✓',
    'Efectivo recibido ✓',
    'Bar erhalten ✓',
    'Kontant ontvang ✓',
    th: 'รับเงินสดแล้ว ✓',
  );
  String get cashReceived => _t(
    'Comptant reçu',
    'Cash received',
    'Efectivo recibido',
    'Bar erhalten',
    'Kontant ontvang',
    th: 'รับเงินสด',
  );
  String get rounding => _t(
    'Arrondi',
    'Rounding',
    'Redondeo',
    'Rundung',
    'Afronding',
    th: 'ปัดเศษ',
  );
  String get cashTotal => _t(
    'Total comptant',
    'Cash total',
    'Total en efectivo',
    'Barbetrag',
    'Kontanttotaal',
    th: 'ยอดเงินสด',
  );
  String get change =>
      _t('Monnaie', 'Change', 'Cambio', 'Rückgeld', 'Kleingeld', th: 'เงินทอน');
  String receivedToast(String amount, String due) => _t(
    '$amount reçu — reste $due à payer',
    'Received $amount — $due outstanding',
    'Recibido $amount: faltan $due',
    '$amount erhalten – $due offen',
    '$amount ontvang — $due uitstaande',
    th: 'รับแล้ว $amount — ค้างชำระ $due',
  );

  // PromptPay — Thai QR payment, THB stores with promptpay.id set
  String get promptPay => _t(
    'PromptPay',
    'PromptPay',
    'PromptPay',
    'PromptPay',
    'PromptPay',
    th: 'พร้อมเพย์',
  );
  String get showPromptPayQr => _t(
    'Afficher le code QR',
    'Show QR code',
    'Mostrar código QR',
    'QR-Code anzeigen',
    'Wys QR-kode',
    th: 'แสดง QR',
  );
  String get promptPayScanHint => _t(
    'Le client scanne avec son application bancaire. Vérifiez la vôtre, puis touchez Payé.',
    'The customer scans with their banking app. Check yours, then tap Paid.',
    'El cliente escanea con su app bancaria. Revise la suya y toque Pagado.',
    'Der Kunde scannt mit seiner Banking-App. Prüfen Sie Ihre, dann auf Bezahlt tippen.',
    'Die kliënt skandeer met sy bankprogram. Kyk na joune en tik dan Betaal.',
    th: 'ลูกค้าสแกนด้วยแอปธนาคาร ตรวจสอบยอดในแอปธนาคารของร้าน แล้วกด ได้รับเงินแล้ว',
  );
  String get promptPayPaid =>
      _t('Payé', 'Paid', 'Pagado', 'Bezahlt', 'Betaal', th: 'ได้รับเงินแล้ว');

  // Card (Stripe) — optional tender, test mode, simulated reader
  String get cardStripe => _t(
    'Carte (Stripe)',
    'Card (Stripe)',
    'Tarjeta (Stripe)',
    'Karte (Stripe)',
    'Kaart (Stripe)',
    th: 'บัตร (Stripe)',
  );
  String get chargeCardStripe => _t(
    'Payer par carte (Stripe)',
    'Charge card (Stripe)',
    'Cobrar con tarjeta (Stripe)',
    'Karte belasten (Stripe)',
    'Belas kaart (Stripe)',
    th: 'รูดบัตร (Stripe)',
  );
  String get simulatedCardLabel => _t(
    'Carte simulée (mode test)',
    'Simulated card (test mode)',
    'Tarjeta simulada (modo de prueba)',
    'Simulierte Karte (Testmodus)',
    'Gesimuleerde kaart (toetsmodus)',
    th: 'บัตรจำลอง (โหมดทดสอบ)',
  );
  String get simApproved => _t(
    'Approuvée',
    'Approved',
    'Aprobada',
    'Genehmigt',
    'Goedgekeur',
    th: 'อนุมัติ',
  );
  String get simDeclined => _t(
    'Refusée',
    'Declined',
    'Rechazada',
    'Abgelehnt',
    'Geweier',
    th: 'ถูกปฏิเสธ',
  );
  String get simInsufficient => _t(
    'Fonds insuffisants',
    'Insufficient funds',
    'Fondos insuficientes',
    'Deckung nicht ausreichend',
    'Onvoldoende fondse',
    th: 'ยอดเงินไม่พอ',
  );
  String get stripeTitle => _t(
    'Paiement par carte (Stripe)',
    'Card payment (Stripe)',
    'Pago con tarjeta (Stripe)',
    'Kartenzahlung (Stripe)',
    'Kaartbetaling (Stripe)',
    th: 'ชำระด้วยบัตร (Stripe)',
  );
  String get stripeTestMode => _t(
    'MODE TEST — lecteur simulé, aucun argent réel',
    'TEST MODE — simulated reader, no real money',
    'MODO DE PRUEBA: lector simulado, sin dinero real',
    'TESTMODUS – simuliertes Lesegerät, kein echtes Geld',
    'TOETSMODUS — gesimuleerde leser, geen regte geld nie',
    th: 'โหมดทดสอบ — เครื่องอ่านจำลอง ไม่ใช้เงินจริง',
  );
  String get stripePreparing => _t(
    'Préparation du paiement…',
    'Preparing the payment…',
    'Preparando el pago…',
    'Zahlung wird vorbereitet…',
    'Berei die betaling voor…',
    th: 'กำลังเตรียมการชำระเงิน…',
  );
  String get stripePermissions => _t(
    'Vérification des autorisations Bluetooth et de localisation…',
    'Checking Bluetooth and location permission…',
    'Revisando los permisos de Bluetooth y ubicación…',
    'Bluetooth- und Standortberechtigung wird geprüft…',
    'Kontroleer Bluetooth- en liggingtoestemming…',
    th: 'กำลังตรวจสอบสิทธิ์ Bluetooth และตำแหน่ง…',
  );
  String get stripeConnecting => _t(
    'Connexion au lecteur de carte (simulé)…',
    'Connecting to the card reader (simulated)…',
    'Conectando con el lector de tarjetas (simulado)…',
    'Verbindung zum Kartenleser (simuliert)…',
    'Koppel aan die kaartleser (gesimuleer)…',
    th: 'กำลังเชื่อมต่อเครื่องอ่านบัตร (จำลอง)…',
  );
  String get stripeTapCard => _t(
    'Présentez, insérez ou glissez la carte (simulé)',
    'Tap, insert or swipe the card (simulated)',
    'Acerca, inserta o desliza la tarjeta (simulado)',
    'Karte auflegen, einstecken oder durchziehen (simuliert)',
    'Tik, steek in of swiep die kaart (gesimuleer)',
    th: 'แตะ เสียบ หรือรูดบัตร (จำลอง)',
  );
  String get stripeProcessing => _t(
    'Traitement du paiement…',
    'Processing…',
    'Procesando…',
    'Wird verarbeitet…',
    'Verwerk…',
    th: 'กำลังดำเนินการ…',
  );
  String get stripeApproved => _t(
    'Paiement approuvé',
    'Payment approved',
    'Pago aprobado',
    'Zahlung genehmigt',
    'Betaling goedgekeur',
    th: 'อนุมัติการชำระเงินแล้ว',
  );
  String get stripeDeclinedTitle => _t(
    'Carte refusée',
    'Card declined',
    'Tarjeta rechazada',
    'Karte abgelehnt',
    'Kaart geweier',
    th: 'บัตรถูกปฏิเสธ',
  );
  String get stripeErrorTitle => _t(
    'Échec du paiement par carte',
    'Card payment failed',
    'Falló el pago con tarjeta',
    'Kartenzahlung fehlgeschlagen',
    'Kaartbetaling het misluk',
    th: 'ชำระด้วยบัตรไม่สำเร็จ',
  );
  String get nothingCharged => _t(
    'Rien n’a été débité. L’addition peut être réglée autrement.',
    'Nothing was charged. The bill can be paid another way.',
    'No se cobró nada. La cuenta se puede pagar de otra forma.',
    'Es wurde nichts belastet. Die Rechnung kann anders bezahlt werden.',
    'Niks is gehef nie. Die rekening kan op ’n ander manier betaal word.',
    th: 'ยังไม่มีการตัดเงิน สามารถชำระบิลด้วยวิธีอื่นได้',
  );

  /// Why "Card (Stripe)" is greyed out (hint under the tender tiles).
  String stripeUnavailableHint(String? reason) => switch (reason) {
    'stripe_unavailable' => _t(
      'Pas d’Internet — la carte (Stripe) est indisponible',
      'No internet — Card (Stripe) is unavailable',
      'Sin internet: la tarjeta (Stripe) no está disponible',
      'Kein Internet – Karte (Stripe) nicht verfügbar',
      'Geen internet nie — Kaart (Stripe) is nie beskikbaar nie',
      th: 'ไม่มีอินเทอร์เน็ต — ใช้บัตร (Stripe) ไม่ได้',
    ),
    'stripe_live_key_refused' => _t(
      'Stripe désactivé : seules les clés de test (sk_test_) sont acceptées',
      'Stripe is off: only test keys (sk_test_) are accepted',
      'Stripe está desactivado: solo se aceptan claves de prueba (sk_test_)',
      'Stripe ist aus: nur Testschlüssel (sk_test_) werden akzeptiert',
      'Stripe is af: slegs toetssleutels (sk_test_) word aanvaar',
      th: 'ปิด Stripe อยู่: รับเฉพาะคีย์ทดสอบ (sk_test_) เท่านั้น',
    ),
    'stripe_permission_denied' => _t(
      'Autorisation Bluetooth ou localisation refusée — carte (Stripe) désactivée',
      'Bluetooth/location permission denied — Card (Stripe) disabled',
      'Se negó el permiso de Bluetooth o ubicación: tarjeta (Stripe) desactivada',
      'Bluetooth-/Standortberechtigung verweigert – Karte (Stripe) deaktiviert',
      'Bluetooth-/liggingtoestemming geweier — Kaart (Stripe) gedeaktiveer',
      th: 'ไม่ได้รับสิทธิ์ Bluetooth/ตำแหน่ง — ปิดการใช้บัตร (Stripe)',
    ),
    'stripe_unsupported' => _t(
      'La carte (Stripe) fonctionne seulement sur la tablette Android',
      'Card (Stripe) works on the Android tablet only',
      'La tarjeta (Stripe) solo funciona en la tableta Android',
      'Karte (Stripe) funktioniert nur auf dem Android-Tablet',
      'Kaart (Stripe) werk slegs op die Android-tablet',
      th: 'บัตร (Stripe) ใช้ได้เฉพาะบนแท็บเล็ต Android',
    ),
    'stripe_location_required' => _t(
      'Stripe : aucun emplacement Terminal (définissez STRIPE_LOCATION_ID)',
      'Stripe: no Terminal location (set STRIPE_LOCATION_ID)',
      'Stripe: no hay ubicación de Terminal (configura STRIPE_LOCATION_ID)',
      'Stripe: kein Terminal-Standort (STRIPE_LOCATION_ID setzen)',
      'Stripe: geen Terminal-ligging nie (stel STRIPE_LOCATION_ID)',
      th: 'Stripe: ไม่มีตำแหน่ง Terminal (ตั้งค่า STRIPE_LOCATION_ID)',
    ),
    'stripe_currency_mismatch' => _t(
      'Stripe désactivé : le compte Stripe n’est pas en CAD',
      'Stripe is off: the Stripe account is not in the store’s currency',
      'Stripe está desactivado: la cuenta de Stripe no está en la moneda de la tienda',
      'Stripe ist aus: das Stripe-Konto ist nicht in der Währung des Geschäfts',
      'Stripe is af: die Stripe-rekening is nie in die winkel se geldeenheid nie',
      th: 'ปิด Stripe อยู่: บัญชี Stripe ไม่ได้ใช้สกุลเงินเดียวกับร้าน',
    ),
    _ => _t(
      'La carte (Stripe) est indisponible pour le moment',
      'Card (Stripe) is unavailable right now',
      'La tarjeta (Stripe) no está disponible por ahora',
      'Karte (Stripe) ist gerade nicht verfügbar',
      'Kaart (Stripe) is nou nie beskikbaar nie',
      th: 'ขณะนี้ใช้บัตร (Stripe) ไม่ได้',
    ),
  };

  /// Friendly text for a Stripe decline code.
  String stripeDeclineMessage(String? code) => switch (code) {
    'insufficient_funds' => _t(
      'Fonds insuffisants. Essayez une autre carte ou un autre mode de paiement.',
      'Insufficient funds. Try another card or another way to pay.',
      'Fondos insuficientes. Prueba con otra tarjeta u otra forma de pago.',
      'Deckung nicht ausreichend. Andere Karte oder Zahlungsart versuchen.',
      'Onvoldoende fondse. Probeer ’n ander kaart of betaalmetode.',
      th: 'ยอดเงินไม่พอ ลองใช้บัตรอื่นหรือชำระด้วยวิธีอื่น',
    ),
    'expired_card' => _t(
      'Carte expirée. Essayez une autre carte.',
      'The card has expired. Try another card.',
      'La tarjeta está vencida. Prueba con otra tarjeta.',
      'Die Karte ist abgelaufen. Andere Karte versuchen.',
      'Die kaart het verval. Probeer ’n ander kaart.',
      th: 'บัตรหมดอายุแล้ว ลองใช้บัตรอื่น',
    ),
    'incorrect_pin' || 'incorrect_cvc' || 'invalid_pin' => _t(
      'NIP incorrect. Réessayez ou utilisez une autre carte.',
      'Incorrect PIN. Try again or use another card.',
      'PIN incorrecto. Inténtalo de nuevo o usa otra tarjeta.',
      'Falsche PIN. Erneut versuchen oder andere Karte verwenden.',
      'Verkeerde PIN. Probeer weer of gebruik ’n ander kaart.',
      th: 'PIN ไม่ถูกต้อง ลองอีกครั้งหรือใช้บัตรอื่น',
    ),
    'pin_try_exceeded' => _t(
      'Trop d’essais de NIP. Utilisez une autre carte.',
      'Too many PIN attempts. Use another card.',
      'Demasiados intentos de PIN. Usa otra tarjeta.',
      'Zu viele PIN-Versuche. Andere Karte verwenden.',
      'Te veel PIN-pogings. Gebruik ’n ander kaart.',
      th: 'ใส่ PIN ผิดหลายครั้งเกินไป กรุณาใช้บัตรอื่น',
    ),
    _ => _t(
      'La carte a été refusée. Essayez une autre carte ou un autre mode de paiement.',
      'The card was declined. Try another card or another way to pay.',
      'La tarjeta fue rechazada. Prueba con otra tarjeta u otra forma de pago.',
      'Die Karte wurde abgelehnt. Andere Karte oder Zahlungsart versuchen.',
      'Die kaart is geweier. Probeer ’n ander kaart of betaalmetode.',
      th: 'บัตรถูกปฏิเสธ ลองใช้บัตรอื่นหรือชำระด้วยวิธีอื่น',
    ),
  };

  /// Friendly text for a reader-side failure kind (see CardReaderError).
  String stripeReaderError(String kind) => switch (kind) {
    'offline' => _t(
      'La tablette ne joint pas Stripe (réseau).',
      'The tablet can\'t reach Stripe (network).',
      'La tableta no puede conectarse con Stripe (red).',
      'Das Tablet erreicht Stripe nicht (Netzwerk).',
      'Die tablet kan Stripe nie bereik nie (netwerk).',
      th: 'แท็บเล็ตเชื่อมต่อ Stripe ไม่ได้ (เครือข่าย)',
    ),
    'tokenFailed' => _t(
      'Le magasin n’a pas pu obtenir de jeton de connexion Stripe.',
      'The store couldn\'t get a Stripe connection token.',
      'La tienda no pudo obtener un token de conexión de Stripe.',
      'Die Kasse hat kein Stripe-Verbindungstoken erhalten.',
      'Die winkel kon nie ’n Stripe-verbindingstoken kry nie.',
      th: 'ร้านขอโทเค็นเชื่อมต่อ Stripe ไม่สำเร็จ',
    ),
    'stripeApi' => _t(
      'Stripe a refusé la demande du lecteur.',
      'Stripe refused the reader request.',
      'Stripe rechazó la solicitud del lector.',
      'Stripe hat die Anfrage des Lesegeräts abgelehnt.',
      'Stripe het die leser se versoek geweier.',
      th: 'Stripe ปฏิเสธคำขอของเครื่องอ่านบัตร',
    ),
    'permissionDenied' => _t(
      'L’autorisation « Position » ou « Appareils à proximité » a été refusée à l’application.',
      'The app\'s Location or Nearby devices permission was denied.',
      'Se negó el permiso de Ubicación o Dispositivos cercanos de la app.',
      'Die Berechtigung für Standort oder Geräte in der Nähe wurde verweigert.',
      'Die toep se toestemming vir ligging of nabygeleë toestelle is geweier.',
      th: 'แอปไม่ได้รับสิทธิ์ตำแหน่งหรืออุปกรณ์ใกล้เคียง',
    ),
    'locationOff' => _t(
      'La localisation de l’appareil est désactivée. Activez-la, puis réessayez.',
      'Device Location is off. Turn it on, then try again.',
      'La ubicación del dispositivo está desactivada. Actívala e inténtalo de nuevo.',
      'Der Gerätestandort ist aus. Bitte einschalten und erneut versuchen.',
      'Toestelligging is af. Skakel dit aan en probeer dan weer.',
      th: 'ตำแหน่งของอุปกรณ์ปิดอยู่ เปิดแล้วลองอีกครั้ง',
    ),
    'bluetoothOff' => _t(
      'Le Bluetooth est désactivé. Activez-le, puis réessayez.',
      'Bluetooth is off. Turn it on, then try again.',
      'El Bluetooth está desactivado. Actívalo e inténtalo de nuevo.',
      'Bluetooth ist aus. Bitte einschalten und erneut versuchen.',
      'Bluetooth is af. Skakel dit aan en probeer dan weer.',
      th: 'Bluetooth ปิดอยู่ เปิดแล้วลองอีกครั้ง',
    ),
    'unsupported' => _t(
      'La carte (Stripe) fonctionne seulement sur la tablette Android.',
      'Card (Stripe) works on the Android tablet only.',
      'La tarjeta (Stripe) solo funciona en la tableta Android.',
      'Karte (Stripe) funktioniert nur auf dem Android-Tablet.',
      'Kaart (Stripe) werk slegs op die Android-tablet.',
      th: 'บัตร (Stripe) ใช้ได้เฉพาะบนแท็บเล็ต Android',
    ),
    _ => _t(
      'Le lecteur de carte (simulé) a échoué.',
      'The card reader (simulated) failed.',
      'Falló el lector de tarjetas (simulado).',
      'Der Kartenleser (simuliert) ist fehlgeschlagen.',
      'Die kaartleser (gesimuleer) het misluk.',
      th: 'เครื่องอ่านบัตร (จำลอง) ทำงานผิดพลาด',
    ),
  };
  String get openLocationSettings => _t(
    'Ouvrir les paramètres de localisation',
    'Open Location settings',
    'Abrir la configuración de ubicación',
    'Standorteinstellungen öffnen',
    'Maak ligginginstellings oop',
    th: 'เปิดการตั้งค่าตำแหน่ง',
  );
  String get openBluetoothSettings => _t(
    'Ouvrir les paramètres Bluetooth',
    'Open Bluetooth settings',
    'Abrir la configuración de Bluetooth',
    'Bluetooth-Einstellungen öffnen',
    'Maak Bluetooth-instellings oop',
    th: 'เปิดการตั้งค่า Bluetooth',
  );
  String get openAppSettings => _t(
    'Ouvrir les autorisations de l’application',
    'Open app permissions',
    'Abrir los permisos de la app',
    'App-Berechtigungen öffnen',
    'Maak toeptoestemmings oop',
    th: 'เปิดสิทธิ์ของแอป',
  );
  String errorCode(String code) => _t(
    'Code : $code',
    'Code: $code',
    'Código: $code',
    'Code: $code',
    'Kode: $code',
    th: 'รหัส: $code',
  );

  // table ops: move / merge
  String get moveMerge => _t(
    'Déplacer / fusionner',
    'Move / merge',
    'Mover / combinar',
    'Umsetzen / zusammenlegen',
    'Skuif / voeg saam',
    th: 'ย้าย / รวม',
  );
  String moveMergeTitle(int billId) => _t(
    'Déplacer l’addition n° $billId vers…',
    'Move Bill #$billId to…',
    'Mover la cuenta #$billId a…',
    'Rechnung #$billId umsetzen nach…',
    'Skuif rekening #$billId na…',
    th: 'ย้ายบิล #$billId ไปที่…',
  );
  String get thisBill => _t(
    'Cette addition',
    'This bill',
    'Esta cuenta',
    'Diese Rechnung',
    'Hierdie rekening',
    th: 'บิลนี้',
  );
  String get beingPaid => _t(
    'Paiement en cours',
    'Being paid',
    'Pago en curso',
    'Wird bezahlt',
    'Word betaal',
    th: 'กำลังชำระ',
  );
  String get mergeConfirmTitle => _t(
    'Fusionner les additions',
    'Merge bills',
    'Combinar cuentas',
    'Rechnungen zusammenlegen',
    'Voeg rekenings saam',
    th: 'รวมบิล',
  );
  String mergeConfirmBody(int src, int dest, String destLabel) => _t(
    'Fusionner l’addition n° $src avec l’addition n° $dest (table $destLabel) ? Tous les articles passent sur l’addition de destination.',
    'Merge Bill #$src into Bill #$dest (table $destLabel)? All items move to the destination bill.',
    '¿Combinar la cuenta #$src con la cuenta #$dest (mesa $destLabel)? Todos los artículos pasan a la cuenta de destino.',
    'Rechnung #$src mit Rechnung #$dest (Tisch $destLabel) zusammenlegen? Alle Artikel wandern auf die Zielrechnung.',
    'Voeg rekening #$src by rekening #$dest (tafel $destLabel) saam? Alle items skuif na die bestemmingsrekening.',
    th: 'รวมบิล #$src เข้ากับบิล #$dest (โต๊ะ $destLabel)? ทุกรายการจะย้ายไปบิลปลายทาง',
  );
  String get merge => _t(
    'Fusionner',
    'Merge',
    'Combinar',
    'Zusammenlegen',
    'Voeg saam',
    th: 'รวม',
  );
  String movedToast(String label) => _t(
    'Déplacée à la table $label',
    'Moved to table $label',
    'Se movió a la mesa $label',
    'Auf Tisch $label umgesetzt',
    'Geskuif na tafel $label',
    th: 'ย้ายไปโต๊ะ $label แล้ว',
  );
  String mergedToast(int dest) => _t(
    'Fusionnée avec l’addition n° $dest',
    'Merged into Bill #$dest',
    'Se combinó con la cuenta #$dest',
    'Mit Rechnung #$dest zusammengelegt',
    'Saamgevoeg by rekening #$dest',
    th: 'รวมเข้ากับบิล #$dest แล้ว',
  );

  // open / misc item
  String get openItem => _t(
    'Article libre',
    'Open item',
    'Artículo libre',
    'Freier Artikel',
    'Oop item',
    th: 'รายการเปิด',
  );
  String get openItemName => _t(
    'Nom de l’article',
    'Item name',
    'Nombre del artículo',
    'Artikelname',
    'Itemnaam',
    th: 'ชื่อรายการ',
  );
  String get openItemPrice => _t(
    'Prix ($cur)',
    'Price ($cur)',
    'Precio ($cur)',
    'Preis ($cur)',
    'Prys ($cur)',
    th: 'ราคา ($cur)',
  );

  // split checks (settlement-time bill groups)
  String get splitBill => _t(
    'Séparer l’addition',
    'Split bill',
    'Dividir la cuenta',
    'Rechnung teilen',
    'Verdeel rekening',
    th: 'แยกบิล',
  );
  String get splitByItems => _t(
    'Par article',
    'By item',
    'Por artículo',
    'Nach Artikel',
    'Per item',
    th: 'ตามรายการ',
  );
  String get splitEvenly => _t(
    'Parts égales',
    'Split evenly',
    'Partes iguales',
    'Gleichmäßig teilen',
    'Verdeel gelykop',
    th: 'แบ่งเท่ากัน',
  );
  String get splitHowManyWays => _t(
    'En combien de parts ?',
    'Split how many ways?',
    '¿En cuántas partes?',
    'In wie viele Teile?',
    'In hoeveel dele verdeel?',
    th: 'แบ่งกี่ส่วน?',
  );
  String groupTitle(int n) => _t(
    'Addition $n',
    'Bill $n',
    'Cuenta $n',
    'Rechnung $n',
    'Rekening $n',
    th: 'บิล $n',
  );
  String get addGroup => _t(
    'Ajouter une addition',
    'Add bill',
    'Agregar cuenta',
    'Rechnung hinzufügen',
    'Voeg rekening by',
    th: 'เพิ่มบิล',
  );
  String get unassignedItems => _t(
    'Non attribués',
    'Unassigned',
    'Sin asignar',
    'Nicht zugeordnet',
    'Nie toegewys nie',
    th: 'ยังไม่ได้จัด',
  );
  String get allItemsAssigned => _t(
    'Tous les articles sont attribués',
    'All items assigned',
    'Todos los artículos están asignados',
    'Alle Artikel zugeordnet',
    'Alle items toegewys',
    th: 'จัดครบทุกรายการแล้ว',
  );
  String get tapToAssignHint => _t(
    'Touchez un article pour le déplacer vers l’addition sélectionnée',
    'Tap an item to move it to the selected bill',
    'Toca un artículo para pasarlo a la cuenta seleccionada',
    'Artikel antippen, um ihn auf die gewählte Rechnung zu verschieben',
    'Tik op ’n item om dit na die gekose rekening te skuif',
    th: 'แตะรายการเพื่อย้ายไปยังบิลที่เลือก',
  );
  String get paid => _t(
    'Payée ✓',
    'Paid ✓',
    'Pagada ✓',
    'Bezahlt ✓',
    'Betaal ✓',
    th: 'ชำระแล้ว ✓',
  );
  String get clearSplit => _t(
    'Annuler la séparation',
    'Clear split',
    'Deshacer la división',
    'Teilung aufheben',
    'Hef verdeling op',
    th: 'ล้างการแยกบิล',
  );
  String get clearSplitConfirm => _t(
    'Tout regrouper sur une seule addition ?',
    'Merge everything back into one bill?',
    '¿Juntar todo otra vez en una sola cuenta?',
    'Alles wieder zu einer Rechnung zusammenlegen?',
    'Voeg alles weer saam in een rekening?',
    th: 'รวมทุกอย่างกลับเป็นบิลเดียว?',
  );
  String groupPaidToast(int n) => _t(
    'Addition $n payée',
    'Bill $n paid',
    'Cuenta $n pagada',
    'Rechnung $n bezahlt',
    'Rekening $n betaal',
    th: 'บิล $n ชำระแล้ว',
  );
  String get moveCorkageTitle => _t(
    'Déplacer le droit de bouchon vers…',
    'Move corkage to…',
    'Mover el descorche a…',
    'Korkgeld verschieben nach…',
    'Skuif kurkgeld na…',
    th: 'ย้ายค่าเปิดขวดไปที่…',
  );
  String get deleteGroup => _t(
    'Retirer cette addition',
    'Remove this bill',
    'Quitar esta cuenta',
    'Diese Rechnung entfernen',
    'Verwyder hierdie rekening',
    th: 'ลบบิลนี้',
  );
  String get peelOne =>
      _t('Un seul', '1 only', 'Solo 1', 'Nur 1', 'Slegs 1', th: 'เพียง 1');

  // receipt
  String get receipt =>
      _t('Reçu', 'Receipt', 'Recibo', 'Beleg', 'Kwitansie', th: 'ใบเสร็จ');

  // provisional bill ("check please")
  String get printBill => _t(
    'Imprimer l’addition',
    'Print bill',
    'Imprimir la cuenta',
    'Rechnung drucken',
    'Druk rekening',
    th: 'พิมพ์บิล',
  );
  String get customerBill => _t(
    'Addition du client',
    'Customer bill',
    'Cuenta del cliente',
    'Gästerechnung',
    'Klantrekening',
    th: 'บิลลูกค้า',
  );
  String get notAReceipt => _t(
    'Ceci n’est pas un reçu',
    'Not a receipt',
    'No es un recibo',
    'Kein Beleg',
    'Nie ’n kwitansie nie',
    th: 'ไม่ใช่ใบเสร็จ',
  );
  String get printedAt => _t(
    'Imprimée à',
    'Printed at',
    'Impresa a las',
    'Gedruckt am',
    'Gedruk om',
    th: 'พิมพ์เมื่อ',
  );
  String get printAgain => _t(
    'Imprimer de nouveau',
    'Print again',
    'Volver a imprimir',
    'Erneut drucken',
    'Druk weer',
    th: 'พิมพ์อีกครั้ง',
  );
  String get printIn => _t(
    'Imprimer en…',
    'Print in…',
    'Imprimir en…',
    'Drucken auf…',
    'Druk in…',
    th: 'พิมพ์เป็นภาษา…',
  );

  // shift screen
  String get noShiftOpen => _t(
    'Aucun quart ouvert',
    'No shift open',
    'No hay turno abierto',
    'Keine Schicht offen',
    'Geen skof oop nie',
    th: 'ยังไม่ได้เปิดกะ',
  );
  String get openingFloat => _t(
    'Fonds de caisse ($cur)',
    'Opening float ($cur)',
    'Fondo inicial ($cur)',
    'Wechselgeld ($cur)',
    'Beginkontant ($cur)',
    th: 'เงินทอนตั้งต้น ($cur)',
  );
  String get openShift => _t(
    'Ouvrir le quart',
    'Open shift',
    'Abrir turno',
    'Schicht öffnen',
    'Open skof',
    th: 'เปิดกะ',
  );
  String get openShiftApproval => _t(
    'Ouvrir le quart — approbation du gérant',
    'Open shift — manager approval',
    'Abrir turno: aprobación del gerente',
    'Schicht öffnen – Freigabe durch Manager',
    'Open skof — bestuurder se goedkeuring',
    th: 'เปิดกะ — ต้องให้ผู้จัดการอนุมัติ',
  );
  String get openShiftPromptTitle => _t(
    'Aucun quart de caisse ouvert',
    'No cash drawer shift open',
    'No hay turno de caja abierto',
    'Keine Kassenschicht offen',
    'Geen kontantlaaiskof oop nie',
    th: 'ยังไม่ได้เปิดกะลิ้นชักเงินสด',
  );
  String get openShiftPromptBody => _t(
    'Ouvrir un maintenant pour prendre des espèces ?',
    'Open one now to take cash?',
    '¿Abrir uno ahora para cobrar en efectivo?',
    'Jetzt eine öffnen, um Bargeld anzunehmen?',
    'Open nou een om kontant te neem?',
    th: 'เปิดกะตอนนี้เพื่อรับเงินสดไหม?',
  );
  String get notNow => _t(
    'Plus tard',
    'Not now',
    'Ahora no',
    'Nicht jetzt',
    'Nie nou nie',
    th: 'ไว้ทีหลัง',
  );
  String changeDue(String amount) => _t(
    'Monnaie à rendre : $amount',
    'Change due $amount',
    'Cambio a devolver: $amount',
    'Rückgeld $amount',
    'Kleingeld verskuldig $amount',
    th: 'เงินทอน $amount',
  );

  /// A floor-plan table whose bill is partly paid (a split bill settled).
  String get partPaid => _t(
    'Payé en partie',
    'Part paid',
    'Pagado en parte',
    'Teilweise bezahlt',
    'Deels betaal',
    th: 'ชำระบางส่วน',
  );

  /// A part-paid table's tile: what is still owed ("$15.57 left").
  String balanceLeft(String amount) => _t(
    'Reste $amount',
    '$amount left',
    'Faltan $amount',
    'Noch $amount',
    '$amount oor',
    th: 'เหลือ $amount',
  );

  /// The pay screen kept a digit out: the cash would be more than due + $1,000.
  String cashEntryCapped(String max) => _t(
    'Au plus $max en comptant pour cette addition',
    'At most $max in cash for this bill',
    'Como máximo $max en efectivo para esta cuenta',
    'Höchstens $max in bar für diese Rechnung',
    'Hoogstens $max kontant vir hierdie rekening',
    th: 'รับเงินสดได้ไม่เกิน $max สำหรับบิลนี้',
  );
  String get overDueTitle => _t(
    'Montant plus élevé que le solde',
    'Amount is more than the bill',
    'El monto supera la cuenta',
    'Betrag höher als die Rechnung',
    'Bedrag is meer as die rekening',
    th: 'ยอดเงินมากกว่ายอดบิล',
  );
  String overDueBody(String amount, String due) => _t(
    'Débiter $amount alors qu’il reste $due ?',
    'Charge $amount when only $due is due?',
    '¿Cobrar $amount cuando solo se deben $due?',
    '$amount belasten, obwohl nur $due offen sind?',
    'Hef $amount wanneer slegs $due verskuldig is?',
    th: 'เรียกเก็บ $amount ทั้งที่ยอดค้างเพียง $due?',
  );
  String get chargeAnyway => _t(
    'Débiter quand même',
    'Charge anyway',
    'Cobrar de todos modos',
    'Trotzdem belasten',
    'Hef in elk geval',
    th: 'เรียกเก็บต่อ',
  );
  String get closeShiftApproval => _t(
    'Fermer le quart (Z) — approbation du gérant',
    'Close shift (Z) — manager approval',
    'Cerrar turno (Z): aprobación del gerente',
    'Schicht schließen (Z) – Freigabe durch Manager',
    'Sluit skof (Z) — bestuurder se goedkeuring',
    th: 'ปิดกะ (Z) — ต้องให้ผู้จัดการอนุมัติ',
  );
  String shiftOpenTitle(int id) => _t(
    'Quart n° $id — ouvert',
    'Shift #$id — open',
    'Turno #$id: abierto',
    'Schicht #$id – offen',
    'Skof #$id — oop',
    th: 'กะ #$id — เปิดอยู่',
  );
  String shiftOpenedLine(String at, String by, String float) => _t(
    'Ouvert le $at par $by • fonds de caisse $float',
    'Opened $at by $by • float $float',
    'Abierto el $at por $by • fondo $float',
    'Geöffnet $at von $by • Wechselgeld $float',
    'Oopgemaak $at deur $by • beginkontant $float',
    th: 'เปิดเมื่อ $at โดย $by • เงินตั้งต้น $float',
  );
  String get closeShiftZ => _t(
    'Fermer le quart (rapport Z)',
    'Close shift (Z-Report)',
    'Cerrar turno (reporte Z)',
    'Schicht schließen (Z-Bericht)',
    'Sluit skof (Z-verslag)',
    th: 'ปิดกะ (รายงาน Z)',
  );
  String get countedCash => _t(
    'Comptant compté ($cur)',
    'Counted cash ($cur)',
    'Efectivo contado ($cur)',
    'Gezähltes Bargeld ($cur)',
    'Getelde kontant ($cur)',
    th: 'เงินสดที่นับได้ ($cur)',
  );
  String get closeShift => _t(
    'Fermer le quart',
    'Close shift',
    'Cerrar turno',
    'Schicht schließen',
    'Sluit skof',
    th: 'ปิดกะ',
  );
  String get zReportDone => _t(
    'Quart fermé — rapport Z',
    'Shift closed — Z-Report',
    'Turno cerrado: reporte Z',
    'Schicht geschlossen – Z-Bericht',
    'Skof gesluit — Z-verslag',
    th: 'ปิดกะแล้ว — รายงาน Z',
  );
  String get revenue =>
      _t('Ventes', 'Revenue', 'Ventas', 'Umsatz', 'Inkomste', th: 'ยอดขาย');
  String get billCount =>
      _t('Additions', 'Bills', 'Cuentas', 'Rechnungen', 'Rekenings', th: 'บิล');
  String get avgPerBill => _t(
    'Moyenne par addition',
    'Avg per bill',
    'Promedio por cuenta',
    'Ø pro Rechnung',
    'Gem. per rekening',
    th: 'เฉลี่ยต่อบิล',
  );
  String get byTender => _t(
    'Par mode de paiement',
    'By tender',
    'Por forma de pago',
    'Nach Zahlungsart',
    'Per betaalmetode',
    th: 'ตามวิธีชำระ',
  );
  String get topItems => _t(
    'Meilleurs vendeurs',
    'Top items',
    'Más vendidos',
    'Topseller',
    'Topitems',
    th: 'รายการขายดี',
  );
  String get voidedBills => _t(
    'Additions annulées',
    'Voided bills',
    'Cuentas anuladas',
    'Stornierte Rechnungen',
    'Gekanselleerde rekenings',
    th: 'บิลที่ยกเลิก',
  );
  String get expectedCash => _t(
    'Comptant attendu',
    'Expected cash',
    'Efectivo esperado',
    'Bar-Soll',
    'Verwagte kontant',
    th: 'เงินสดที่ควรมี',
  );
  String get countedActual =>
      _t('Compté', 'Counted', 'Contado', 'Gezählt', 'Getel', th: 'นับได้');
  String get overShort => _t(
    'Surplus / manque',
    'Over/short',
    'Sobrante / faltante',
    'Differenz',
    'Oor/kort',
    th: 'ขาด/เกิน',
  );
  String get xReport => _t(
    'Rapport X',
    'X-Report',
    'Reporte X',
    'X-Bericht',
    'X-verslag',
    th: 'รายงาน X',
  );
  // shift reconciliation: cash movements + refunds
  String get paidInOut => _t(
    'Entrées / sorties de caisse',
    'Paid in / out',
    'Entradas / salidas de caja',
    'Einlagen / Entnahmen',
    'Inbetaal / uitbetaal',
    th: 'นำเงินเข้า / ออก',
  );
  // card tips on the X / Z / range reports
  String get cardTips => _t(
    'Pourboires par carte',
    'Card tips',
    'Propinas con tarjeta',
    'Kartentrinkgeld',
    'Kaartfooitjies',
    th: 'ทิปผ่านบัตร',
  );
  String get tipsByServer => _t(
    'Pourboires par serveur',
    'Tips by server',
    'Propinas por mesero',
    'Trinkgeld pro Bedienung',
    'Fooitjies per kelner',
    th: 'ทิปตามพนักงานเสิร์ฟ',
  );
  String tipCount(int n) => _t(
    '$n pourboire${n == 1 ? '' : 's'}',
    '$n tip${n == 1 ? '' : 's'}',
    '$n propina${n == 1 ? '' : 's'}',
    '$n Trinkgeld${n == 1 ? '' : 'er'}',
    '$n fooitjie${n == 1 ? '' : 's'}',
    th: 'ทิป $n รายการ',
  );
  String voidReversed(String amount) => _t(
    'paiements rendus $amount',
    'payments handed back $amount',
    'pagos devueltos $amount',
    'Zahlungen zurückgegeben $amount',
    'betalings teruggegee $amount',
    th: 'คืนเงินที่ชำระแล้ว $amount',
  );
  String get cashRefunds => _t(
    'Remboursements en comptant',
    'Cash refunds',
    'Reembolsos en efectivo',
    'Bar-Erstattungen',
    'Kontantterugbetalings',
    th: 'คืนเงินสด',
  );
  String get cashRounding => _t(
    'Arrondi du comptant',
    'Cash rounding',
    'Redondeo del efectivo',
    'Barrundung',
    'Kontantafronding',
    th: 'ปัดเศษเงินสด',
  );

  // refunds — return money on a finalized (CLOSED) bill
  String get refunds => _t(
    'Remboursements',
    'Refunds',
    'Reembolsos',
    'Erstattungen',
    'Terugbetalings',
    th: 'คืนเงิน',
  );
  String get salesTitle => _t(
    'Additions fermées',
    'Closed bills',
    'Cuentas cerradas',
    'Abgeschlossene Rechnungen',
    'Geslote rekenings',
    th: 'บิลที่ปิดแล้ว',
  );
  String get refundTitle => _t(
    'Rembourser l’addition',
    'Refund bill',
    'Reembolsar la cuenta',
    'Rechnung erstatten',
    'Betaal rekening terug',
    th: 'คืนเงินบิล',
  );
  String get refundOverrideTitle => _t(
    'Rembourser autrement que le paiement ?',
    'Refund another way than the guest paid?',
    '¿Reembolsar de otra forma que el pago?',
    'Anders erstatten als bezahlt?',
    'Terugbetaal anders as wat die gas betaal het?',
    th: 'คืนเงินต่างจากวิธีที่ลูกค้าชำระ?',
  );
  String get refundOverrideBody => _t(
    'Le client n’a pas payé ce montant de cette façon. Seul un gérant peut l’autoriser ; ce sera noté sur le remboursement.',
    'The guest didn’t pay this much that way. Only a manager can allow it; it is noted on the refund.',
    'El cliente no pagó tanto de esa forma. Solo un gerente puede permitirlo; quedará anotado en el reembolso.',
    'Der Gast hat so viel nicht auf diese Art bezahlt. Nur ein Manager kann das erlauben; es wird bei der Erstattung vermerkt.',
    'Die gas het nie soveel op dié manier betaal nie. Net ’n bestuurder kan dit toelaat; dit word op die terugbetaling aangeteken.',
    th: 'ลูกค้าไม่ได้ชำระด้วยวิธีนี้เป็นจำนวนเท่านี้ ต้องให้ผู้จัดการอนุญาตเท่านั้น และจะบันทึกไว้ในการคืนเงิน',
  );
  String get managerOverride => _t(
    'Autorisation du gérant',
    'Manager override',
    'Autorización del gerente',
    'Manager-Freigabe',
    'Bestuurder se magtiging',
    th: 'ผู้จัดการอนุมัติแทน',
  );
  String get refundApprovalTitle => _t(
    'Remboursement — approbation du gérant',
    'Refund — manager approval',
    'Reembolso: aprobación del gerente',
    'Erstattung – Freigabe durch Manager',
    'Terugbetaling — bestuurder se goedkeuring',
    th: 'คืนเงิน — ต้องให้ผู้จัดการอนุมัติ',
  );
  String get refundReasonTitle => _t(
    'Motif du remboursement',
    'Refund reason',
    'Motivo del reembolso',
    'Erstattungsgrund',
    'Rede vir terugbetaling',
    th: 'เหตุผลการคืนเงิน',
  );
  List<String> get refundReasons => [
    _t(
      'Mauvais article',
      'Wrong item',
      'Artículo equivocado',
      'Falscher Artikel',
      'Verkeerde item',
      th: 'ได้รายการผิด',
    ),
    _t(
      'Le client a changé d’idée',
      'Customer changed mind',
      'El cliente cambió de opinión',
      'Gast hat es sich anders überlegt',
      'Klant het van plan verander',
      th: 'ลูกค้าเปลี่ยนใจ',
    ),
    _t(
      'Problème de qualité',
      'Quality issue',
      'Problema de calidad',
      'Qualitätsmangel',
      'Gehalteprobleem',
      th: 'ปัญหาด้านคุณภาพ',
    ),
    _t(
      'Surfacturation',
      'Overcharged',
      'Cobro de más',
      'Zu viel berechnet',
      'Te veel gehef',
      th: 'เรียกเก็บเกิน',
    ),
  ];
  String get refundFull => _t(
    'Remboursement complet',
    'Full refund',
    'Reembolso total',
    'Vollständige Erstattung',
    'Volle terugbetaling',
    th: 'คืนเงินเต็มจำนวน',
  );
  String get refundByLine => _t(
    'Par article',
    'By item',
    'Por artículo',
    'Nach Artikel',
    'Per item',
    th: 'ตามรายการ',
  );
  String get refundByAmount => _t(
    'Par montant',
    'By amount',
    'Por monto',
    'Nach Betrag',
    'Per bedrag',
    th: 'ตามจำนวนเงิน',
  );
  String get refundAmountLabel => _t(
    'Montant à rembourser ($cur)',
    'Refund amount ($cur)',
    'Monto del reembolso ($cur)',
    'Erstattungsbetrag ($cur)',
    'Terugbetalingsbedrag ($cur)',
    th: 'จำนวนเงินคืน ($cur)',
  );
  String get refundTender => _t(
    'Rembourser par',
    'Refund via',
    'Reembolsar con',
    'Erstatten über',
    'Betaal terug via',
    th: 'คืนเงินผ่าน',
  );
  String get refundableLabel => _t(
    'Remboursable',
    'Refundable',
    'Reembolsable',
    'Erstattbar',
    'Terugbetaalbaar',
    th: 'คืนได้',
  );
  String get refundedLabel => _t(
    'Remboursé',
    'Refunded',
    'Reembolsado',
    'Erstattet',
    'Terugbetaal',
    th: 'คืนแล้ว',
  );
  String get fullyRefunded => _t(
    'Entièrement remboursée',
    'Fully refunded',
    'Reembolsada por completo',
    'Vollständig erstattet',
    'Volledig terugbetaal',
    th: 'คืนเงินครบแล้ว',
  );
  String get confirmRefund => _t(
    'Confirmer le remboursement',
    'Confirm refund',
    'Confirmar reembolso',
    'Erstattung bestätigen',
    'Bevestig terugbetaling',
    th: 'ยืนยันการคืนเงิน',
  );
  String refundDone(String amount) => _t(
    '$amount remboursé',
    'Refunded $amount',
    'Se reembolsaron $amount',
    '$amount erstattet',
    '$amount terugbetaal',
    th: 'คืนเงินแล้ว $amount',
  );
  String cashHandedBack(String amount) => _t(
    'Comptant remis : $amount',
    'Cash handed back $amount',
    'Efectivo devuelto: $amount',
    '$amount bar zurückgegeben',
    '$amount kontant teruggegee',
    th: 'คืนเงินสดแล้ว $amount',
  );
  String get noClosedBills => _t(
    'Aucune addition fermée pour l’instant',
    'No closed bills yet',
    'Todavía no hay cuentas cerradas',
    'Noch keine abgeschlossenen Rechnungen',
    'Nog geen geslote rekenings nie',
    th: 'ยังไม่มีบิลที่ปิดแล้ว',
  );
  String get refundHistory => _t(
    'Historique des remboursements',
    'Refund history',
    'Historial de reembolsos',
    'Erstattungsverlauf',
    'Terugbetalingsgeskiedenis',
    th: 'ประวัติการคืนเงิน',
  );
  String get pickLinesHint => _t(
    'Touchez les articles à rembourser',
    'Tap items to refund',
    'Toca los artículos que vas a reembolsar',
    'Artikel zum Erstatten antippen',
    'Tik items om terug te betaal',
    th: 'แตะรายการที่จะคืนเงิน',
  );
  String get refundSlipTitle => _t(
    'Bon de remboursement',
    'Refund slip',
    'Comprobante de reembolso',
    'Erstattungsbeleg',
    'Terugbetalingstrokie',
    th: 'ใบคืนเงิน',
  );
  String get amountExceedsRefundable => _t(
    'Dépasse le montant remboursable',
    'Exceeds the refundable amount',
    'Supera el monto reembolsable',
    'Übersteigt den erstattbaren Betrag',
    'Oorskry die terugbetaalbare bedrag',
    th: 'เกินยอดที่คืนได้',
  );
  String get pickAmountOrLines => _t(
    'Choisissez des articles ou entrez un montant',
    'Pick items or enter an amount',
    'Elige artículos o ingresa un monto',
    'Artikel wählen oder Betrag eingeben',
    'Kies items of voer ’n bedrag in',
    th: 'เลือกรายการหรือใส่จำนวนเงิน',
  );

  // till — non-sale cash in / out
  String get till =>
      _t('Caisse', 'Till', 'Caja', 'Kasse', 'Kasregister', th: 'ลิ้นชักเงินสด');
  String get cashIn => _t(
    'Entrée de caisse',
    'Cash in',
    'Entrada de efectivo',
    'Einlage',
    'Kontant in',
    th: 'นำเงินเข้า',
  );
  String get cashOut => _t(
    'Sortie de caisse',
    'Cash out',
    'Salida de efectivo',
    'Entnahme',
    'Kontant uit',
    th: 'นำเงินออก',
  );
  String get cashInApproval => _t(
    'Entrée de caisse — approbation du gérant',
    'Cash in — manager approval',
    'Entrada de efectivo: aprobación del gerente',
    'Einlage – Freigabe durch Manager',
    'Kontant in — bestuurder se goedkeuring',
    th: 'นำเงินเข้า — ต้องให้ผู้จัดการอนุมัติ',
  );
  String get cashOutApproval => _t(
    'Sortie de caisse — approbation du gérant',
    'Cash out — manager approval',
    'Salida de efectivo: aprobación del gerente',
    'Entnahme – Freigabe durch Manager',
    'Kontant uit — bestuurder se goedkeuring',
    th: 'นำเงินออก — ต้องให้ผู้จัดการอนุมัติ',
  );
  String get cashAmountLabel => _t(
    'Montant ($cur)',
    'Amount ($cur)',
    'Monto ($cur)',
    'Betrag ($cur)',
    'Bedrag ($cur)',
    th: 'จำนวนเงิน ($cur)',
  );
  String get cashReasonLabel =>
      _t('Motif', 'Reason', 'Motivo', 'Grund', 'Rede', th: 'เหตุผล');
  String get cashReasonRequired => _t(
    'Un motif est requis',
    'A reason is required',
    'Se requiere un motivo',
    'Bitte einen Grund angeben',
    '’n Rede is nodig',
    th: 'ต้องระบุเหตุผล',
  );
  String cashInDone(String amount) => _t(
    'Entrée de caisse de $amount enregistrée',
    'Cash in $amount recorded',
    'Entrada de efectivo de $amount registrada',
    'Einlage über $amount erfasst',
    'Kontant in $amount aangeteken',
    th: 'บันทึกนำเงินเข้า $amount แล้ว',
  );
  String cashOutDone(String amount) => _t(
    'Sortie de caisse de $amount enregistrée',
    'Cash out $amount recorded',
    'Salida de efectivo de $amount registrada',
    'Entnahme über $amount erfasst',
    'Kontant uit $amount aangeteken',
    th: 'บันทึกนำเงินออก $amount แล้ว',
  );
  String get cashMovementsLabel => _t(
    'Mouvements de caisse',
    'Cash movements',
    'Movimientos de efectivo',
    'Kassenbewegungen',
    'Kontantbewegings',
    th: 'การเคลื่อนไหวเงินสด',
  );
  String get noCashMovements => _t(
    'Aucun mouvement de caisse pour l’instant',
    'No cash movements yet',
    'Todavía no hay movimientos de efectivo',
    'Noch keine Kassenbewegungen',
    'Nog geen kontantbewegings nie',
    th: 'ยังไม่มีการเคลื่อนไหวเงินสด',
  );
  String get needOpenShiftForTill => _t(
    'Ouvrez un quart avant d’enregistrer des mouvements de caisse',
    'Open a shift before recording cash movements',
    'Abre un turno antes de registrar movimientos de efectivo',
    'Bitte zuerst eine Schicht öffnen, um Kassenbewegungen zu erfassen',
    'Begin ’n skof voordat jy kontantbewegings aanteken',
    th: 'เปิดกะก่อนบันทึกการเคลื่อนไหวเงินสด',
  );

  // report ranges
  String get rangeThisShift => _t(
    'Ce quart',
    'This shift',
    'Este turno',
    'Diese Schicht',
    'Hierdie skof',
    th: 'กะนี้',
  );
  String get rangeToday =>
      _t('Aujourd’hui', 'Today', 'Hoy', 'Heute', 'Vandag', th: 'วันนี้');
  String get rangeYesterday =>
      _t('Hier', 'Yesterday', 'Ayer', 'Gestern', 'Gister', th: 'เมื่อวาน');
  String get rangeThisWeek => _t(
    'Cette semaine',
    'This week',
    'Esta semana',
    'Diese Woche',
    'Hierdie week',
    th: 'สัปดาห์นี้',
  );
  String get rangeCustom => _t(
    'Personnalisé…',
    'Custom…',
    'Personalizado…',
    'Zeitraum…',
    'Pasgemaak…',
    th: 'กำหนดเอง…',
  );
  String rangeTitle(String from, String to) => from == to
      ? _t(
          'Ventes du $from',
          'Sales for $from',
          'Ventas del $from',
          'Umsatz am $from',
          'Verkope vir $from',
          th: 'ยอดขาย $from',
        )
      : _t(
          'Ventes du $from au $to',
          'Sales $from – $to',
          'Ventas del $from al $to',
          'Umsatz $from – $to',
          'Verkope $from – $to',
          th: 'ยอดขาย $from – $to',
        );

  // menu management
  String get manageMenuTitle => _t(
    'Gérer le menu — en vente / épuisé',
    'Manage menu — on/off sale',
    'Administrar menú: a la venta / agotado',
    'Speisekarte verwalten – verfügbar/ausverkauft',
    'Bestuur spyskaart — beskikbaar/uitverkoop',
    th: 'จัดการเมนู — เปิด/ปิดขาย',
  );
  String get onSale => _t(
    'En vente',
    'On sale',
    'A la venta',
    'Verfügbar',
    'Beskikbaar',
    th: 'เปิดขาย',
  );
  String get offSale => _t(
    'Épuisé (86)',
    'Off sale (86)',
    'Agotado (86)',
    'Ausverkauft (86)',
    'Uitverkoop (86)',
    th: 'ปิดขาย (86)',
  );
  String enableSale(String name) => _t(
    'Remettre $name en vente',
    'Put $name on sale',
    'Poner $name a la venta',
    '$name wieder verfügbar machen',
    'Maak $name beskikbaar',
    th: 'เปิดขาย $name',
  );
  String disableSale(String name) => _t(
    'Marquer $name épuisé (86)',
    'Take $name off sale (86)',
    'Marcar $name como agotado (86)',
    '$name als ausverkauft markieren (86)',
    'Merk $name as uitverkoop (86)',
    th: 'ปิดขาย $name (86)',
  );

  // menu editing (owner catalog)
  String get addItem => _t(
    'Ajouter un article',
    'Add item',
    'Agregar artículo',
    'Artikel hinzufügen',
    'Voeg item by',
    th: 'เพิ่มรายการ',
  );
  String get editItem => _t(
    'Modifier l’article',
    'Edit item',
    'Editar artículo',
    'Artikel bearbeiten',
    'Wysig item',
    th: 'แก้ไขรายการ',
  );
  String get deleteItem => _t(
    'Supprimer l’article',
    'Delete item',
    'Eliminar artículo',
    'Artikel löschen',
    'Verwyder item',
    th: 'ลบรายการ',
  );

  /// The item editor's delete button: one short word, so it fits beside
  /// Cancel and Save in every language (the confirm says "Delete item").
  String get deleteItemShort =>
      _t('Supprimer', 'Delete', 'Eliminar', 'Löschen', 'Verwyder', th: 'ลบ');
  String deleteItemConfirm(String name) => _t(
    'Retirer « $name » du menu ? Les anciennes additions le conservent, mais il disparaît de tous les écrans.',
    'Remove "$name" from the menu? Old bills keep it; it disappears from every screen.',
    '¿Quitar "$name" del menú? Las cuentas anteriores lo conservan, pero desaparece de todas las pantallas.',
    '„$name“ von der Speisekarte entfernen? Alte Rechnungen behalten den Artikel; auf allen Bildschirmen verschwindet er.',
    'Verwyder “$name” van die spyskaart? Ou rekenings behou dit; dit verdwyn van elke skerm.',
    th: 'ลบ “$name” ออกจากเมนู? บิลเก่ายังคงเก็บไว้ แต่จะหายไปจากทุกหน้าจอ',
  );
  String get itemDeleted => _t(
    'Article retiré',
    'Item removed',
    'Artículo quitado',
    'Artikel entfernt',
    'Item verwyder',
    th: 'ลบรายการแล้ว',
  );
  String get nameFrLabel => _thaiData
      ? _t(
          'Nom (thaï)',
          'Name (Thai)',
          'Nombre (tailandés)',
          'Name (Thailändisch)',
          'Naam (Thai)',
          th: 'ชื่อ (ไทย)',
        )
      : _t(
          'Nom (français)',
          'Name (French)',
          'Nombre (francés)',
          'Name (Französisch)',
          'Naam (Frans)',
          th: 'ชื่อ (ฝรั่งเศส)',
        );
  String get nameEnLabel => _t(
    'Nom (anglais)',
    'Name (English)',
    'Nombre (inglés)',
    'Name (Englisch)',
    'Naam (Engels)',
    th: 'ชื่อ (อังกฤษ)',
  );
  String get categoryLabel => _t(
    'Catégorie',
    'Category',
    'Categoría',
    'Kategorie',
    'Kategorie',
    th: 'หมวดหมู่',
  );
  String get abbrevLabel => _t(
    'Pastille (1 à 4 caractères)',
    'Tile badge (1–4 chars)',
    'Insignia (1 a 4 caracteres)',
    'Kachel-Kürzel (1–4 Zeichen)',
    'Teëlkenteken (1–4 karakters)',
    th: 'ป้ายบนปุ่ม (1–4 ตัวอักษร)',
  );
  String get isAlcoholLabel => _t(
    'Alcool',
    'Alcohol',
    'Con alcohol',
    'Alkohol',
    'Alkohol',
    th: 'แอลกอฮอล์',
  );
  String get activeLabel => _t(
    'En vente',
    'On sale',
    'A la venta',
    'Verfügbar',
    'Beskikbaar',
    th: 'เปิดขาย',
  );
  String get sizesLabel => _t(
    'Formats / prix',
    'Sizes / prices',
    'Tamaños / precios',
    'Größen / Preise',
    'Groottes / pryse',
    th: 'ขนาด / ราคา',
  );
  String get addSize => _t(
    'Ajouter un format',
    'Add size',
    'Agregar tamaño',
    'Größe hinzufügen',
    'Voeg grootte by',
    th: 'เพิ่มขนาด',
  );
  String get sizeLabelFr => _thaiData
      ? _t(
          'Format (thaï)',
          'Size (Thai)',
          'Tamaño (tailandés)',
          'Größe (Thailändisch)',
          'Grootte (Thai)',
          th: 'ขนาด (ไทย)',
        )
      : _t(
          'Format (français)',
          'Size (French)',
          'Tamaño (francés)',
          'Größe (Französisch)',
          'Grootte (Frans)',
          th: 'ขนาด (ฝรั่งเศส)',
        );
  String get sizeLabelEn => _t(
    'Format (anglais)',
    'Size (English)',
    'Tamaño (inglés)',
    'Größe (Englisch)',
    'Grootte (Engels)',
    th: 'ขนาด (อังกฤษ)',
  );
  String get priceCAD => _t(
    'Prix ($cur)',
    'Price ($cur)',
    'Precio ($cur)',
    'Preis ($cur)',
    'Prys ($cur)',
    th: 'ราคา ($cur)',
  );
  String get fillAllFields => _t(
    'Remplissez tous les champs',
    'Fill in all fields',
    'Completa todos los campos',
    'Bitte alle Felder ausfüllen',
    'Vul alle velde in',
    th: 'กรุณากรอกให้ครบทุกช่อง',
  );

  /// A money box holds something that isn't dollars and cents. The example
  /// stays North American ($12.99) in every language — that's how it's typed.
  String get invalidMoneyAmount => _t(
    'Montant invalide : entrez par ex. 12.99 (point pour les cents)',
    'Invalid amount: enter e.g. 12.99',
    'Importe no válido: escribe p. ej. 12.99 (punto para los centavos)',
    'Ungültiger Betrag: z. B. 12.99 eingeben (Punkt vor den Cents)',
    'Ongeldige bedrag: tik bv. 12.99 (punt voor die sente)',
    th: 'จำนวนเงินไม่ถูกต้อง: ใส่ เช่น 12.99',
  );
  String get editCategories => _t(
    'Gérer les catégories',
    'Edit categories',
    'Editar categorías',
    'Kategorien bearbeiten',
    'Wysig kategorieë',
    th: 'แก้ไขหมวดหมู่',
  );
  String get addCategory => _t(
    'Ajouter une catégorie',
    'Add category',
    'Agregar categoría',
    'Kategorie hinzufügen',
    'Voeg kategorie by',
    th: 'เพิ่มหมวดหมู่',
  );
  String get deleteCategory => _t(
    'Supprimer la catégorie',
    'Delete category',
    'Eliminar categoría',
    'Kategorie löschen',
    'Verwyder kategorie',
    th: 'ลบหมวดหมู่',
  );
  String get dragToReorder => _t(
    'Glissez pour réordonner',
    'Drag to reorder',
    'Arrastra para reordenar',
    'Zum Sortieren ziehen',
    'Sleep om te herrangskik',
    th: 'ลากเพื่อจัดลำดับ',
  );
  String get saved => _t(
    'Enregistré',
    'Saved',
    'Guardado',
    'Gespeichert',
    'Gestoor',
    th: 'บันทึกแล้ว',
  );

  // slips
  String get printSlips => _t(
    'Imprimer les fiches de toutes les tables',
    'Print all table slips',
    'Imprimir las hojas de todas las mesas',
    'Alle Tischzettel drucken',
    'Druk alle tafelstrokies',
    th: 'พิมพ์ใบโต๊ะทั้งหมด',
  );

  // photos
  String get uploadPhoto => _t(
    'Téléverser une photo',
    'Upload photo',
    'Subir foto',
    'Foto hochladen',
    'Laai foto op',
    th: 'อัปโหลดรูปภาพ',
  );
  String uploadPhotoFor(String name) => _t(
    'Téléverser une photo pour $name',
    'Upload photo for $name',
    'Subir foto de $name',
    'Foto für $name hochladen',
    'Laai foto op vir $name',
    th: 'อัปโหลดรูปภาพสำหรับ $name',
  );
  String get photoUploaded => _t(
    'Photo téléversée',
    'Photo uploaded',
    'Foto subida',
    'Foto hochgeladen',
    'Foto opgelaai',
    th: 'อัปโหลดรูปภาพแล้ว',
  );

  // AI menu photos (paid add-on)
  String get aiGeneratePhoto => _t(
    'Générer une photo',
    'Generate photo',
    'Generar foto',
    'Foto erzeugen',
    'Genereer foto',
    th: 'สร้างรูปภาพ',
  );
  String get aiSnapEnhance => _t(
    'Photographier et améliorer',
    'Snap and enhance',
    'Tomar foto y mejorar',
    'Foto aufwerten',
    'Neem en verbeter',
    th: 'ถ่ายและปรับแต่ง',
  );
  String aiGenerateFor(String name) => _t(
    'Générer une photo pour $name',
    'Generate a photo for $name',
    'Generar una foto de $name',
    'Foto für $name erzeugen',
    'Genereer ’n foto vir $name',
    th: 'สร้างรูปภาพสำหรับ $name',
  );
  String aiEnhanceFor(String name) => _t(
    'Améliorer une photo de $name',
    'Enhance a photo of $name',
    'Mejorar una foto de $name',
    'Foto von $name aufwerten',
    'Verbeter ’n foto van $name',
    th: 'ปรับแต่งรูปภาพของ $name',
  );
  String get aiTakePhoto => _t(
    'Prendre une photo',
    'Take a photo',
    'Tomar una foto',
    'Foto aufnehmen',
    'Neem ’n foto',
    th: 'ถ่ายรูป',
  );
  String get aiChooseFromGallery => _t(
    'Choisir dans la galerie',
    'Choose from gallery',
    'Elegir de la galería',
    'Aus Galerie wählen',
    'Kies uit galery',
    th: 'เลือกจากแกลเลอรี',
  );
  // The Windows tablet's own camera page (lib/widgets/camera_capture.dart).
  String get cameraUsePhoto => _t(
    'Utiliser la photo',
    'Use photo',
    'Usar la foto',
    'Foto verwenden',
    'Gebruik foto',
    th: 'ใช้รูปนี้',
  );
  String get cameraRetake => _t(
    'Reprendre',
    'Retake',
    'Repetir',
    'Neu aufnehmen',
    'Neem weer',
    th: 'ถ่ายใหม่',
  );
  String get cameraSwitch => _t(
    'Changer de caméra',
    'Switch camera',
    'Cambiar de cámara',
    'Kamera wechseln',
    'Wissel kamera',
    th: 'สลับกล้อง',
  );
  String get cameraNone => _t(
    'Aucune caméra trouvée sur cet appareil.',
    'No camera found on this device.',
    'No se encontró ninguna cámara en este dispositivo.',
    'Keine Kamera auf diesem Gerät gefunden.',
    'Geen kamera op hierdie toestel gevind nie.',
    th: 'ไม่พบกล้องในอุปกรณ์นี้',
  );
  String get cameraDenied => _t(
    'L’accès à la caméra est désactivé. Autorisez-le dans Paramètres Windows > Confidentialité et sécurité > Caméra.',
    'Camera access is turned off. Allow it in Windows Settings > Privacy & security > Camera.',
    'El acceso a la cámara está desactivado. Actívelo en Configuración de Windows > Privacidad y seguridad > Cámara.',
    'Der Kamerazugriff ist ausgeschaltet. Erlauben Sie ihn unter Windows-Einstellungen > Datenschutz und Sicherheit > Kamera.',
    'Kameratoegang is afgeskakel. Laat dit toe in Windows-instellings > Privaatheid en sekuriteit > Kamera.',
    th: 'การเข้าถึงกล้องปิดอยู่ เปิดได้ที่ Windows Settings > Privacy & security > Camera',
  );
  String get cameraFailed => _t(
    'La caméra n’a pas pu démarrer. Elle est peut-être utilisée par une autre application.',
    'The camera could not start. Another app may be using it.',
    'No se pudo iniciar la cámara. Puede que otra aplicación la esté usando.',
    'Die Kamera konnte nicht starten. Vielleicht wird sie von einer anderen App verwendet.',
    'Die kamera kon nie begin nie. ’n Ander toep gebruik dit dalk.',
    th: 'เปิดกล้องไม่ได้ อาจมีแอปอื่นใช้กล้องอยู่',
  );
  String get aiWorking => _t(
    'Création des photos… (jusqu’à une minute)',
    'Making photos… (up to a minute)',
    'Creando fotos… (hasta un minuto)',
    'Fotos werden erstellt… (bis zu einer Minute)',
    'Maak foto’s… (tot ’n minuut)',
    th: 'กำลังสร้างรูป… (อาจใช้เวลาถึง 1 นาที)',
  );
  // the AI menu dialog's spinner: what it is doing right now
  String get aiMenuThinking => _t(
    'Lecture de votre demande…',
    'Working on your request…',
    'Procesando tu solicitud…',
    'Ihre Anfrage wird bearbeitet…',
    'Besig met jou versoek…',
    th: 'กำลังดำเนินการตามคำขอ…',
  );
  String get aiMenuReadingPhotos => _t(
    'Lecture des photos du menu… (jusqu’à une minute)',
    'Reading the menu photos… (up to a minute)',
    'Leyendo las fotos del menú… (hasta un minuto)',
    'Menüfotos werden gelesen… (bis zu einer Minute)',
    'Lees die spyskaartfoto’s… (tot ’n minuut)',
    th: 'กำลังอ่านรูปเมนู… (อาจใช้เวลาถึง 1 นาที)',
  );
  String get aiMenuTranslating => _t(
    'Traduction du menu…',
    'Translating the menu…',
    'Traduciendo el menú…',
    'Menü wird übersetzt…',
    'Vertaal die spyskaart…',
    th: 'กำลังแปลเมนู…',
  );
  String get aiMenuApplying => _t(
    'Mise à jour du menu…',
    'Updating the menu…',
    'Actualizando el menú…',
    'Menü wird aktualisiert…',
    'Werk die spyskaart by…',
    th: 'กำลังอัปเดตเมนู…',
  );
  String get aiPickOne => _t(
    'Touchez la photo à garder',
    'Tap the photo to keep',
    'Toca la foto que quieres conservar',
    'Gewünschtes Foto antippen',
    'Tik die foto om te hou',
    th: 'แตะรูปที่ต้องการเก็บไว้',
  );
  String get aiUseThis => _t(
    'Utiliser cette photo',
    'Use this photo',
    'Usar esta foto',
    'Dieses Foto verwenden',
    'Gebruik hierdie foto',
    th: 'ใช้รูปนี้',
  );
  String get aiRegenerate => _t(
    'Recommencer',
    'Regenerate',
    'Generar de nuevo',
    'Neu erzeugen',
    'Genereer weer',
    th: 'สร้างใหม่',
  );
  String get aiPhotoSaved => _t(
    'Photo IA enregistrée',
    'AI photo saved',
    'Foto con IA guardada',
    'KI-Foto gespeichert',
    'KI-foto gestoor',
    th: 'บันทึกรูป AI แล้ว',
  );
  String get aiBadge => _t('IA', 'AI', 'IA', 'KI', 'KI', th: 'AI');
  String get aiGeneratedLabel => _t(
    'Photo générée par IA',
    'AI-generated photo',
    'Foto generada con IA',
    'KI-generiertes Foto',
    'KI-gegenereerde foto',
    th: 'รูปที่สร้างด้วย AI',
  );
  String get aiEnhancedLabel => _t(
    'Photo réelle retouchée par IA',
    'Real photo, AI-enhanced',
    'Foto real mejorada con IA',
    'Echtes Foto, mit KI aufgewertet',
    'Regte foto, met KI verbeter',
    th: 'รูปจริง ปรับแต่งด้วย AI',
  );
  String get aiEnhanceHint => _t(
    'Les aliments restent tels quels : seuls l’éclairage, le fond et la présentation changent.',
    'The food stays as it is: only the lighting, background and presentation change.',
    'La comida queda igual: solo cambian la luz, el fondo y la presentación.',
    'Das Gericht bleibt, wie es ist: Nur Licht, Hintergrund und Präsentation ändern sich.',
    'Die kos bly soos dit is: net die beligting, agtergrond en aanbieding verander.',
    th: 'อาหารยังคงเหมือนเดิม เปลี่ยนเฉพาะแสง พื้นหลัง และการจัดวาง',
  );

  /// Why the AI photo buttons are greyed out (a note under them).
  String aiUnavailableNote(String? reason) => switch (reason) {
    'image_offline' || 'image_unavailable' => _t(
      'Photos IA : connexion Internet requise. Tout le reste fonctionne normalement.',
      'AI photos need an internet connection. Everything else works as usual.',
      'Las fotos con IA necesitan internet. Todo lo demás funciona con normalidad.',
      'KI-Fotos benötigen eine Internetverbindung. Alles andere funktioniert wie gewohnt.',
      'KI-foto’s het ’n internetverbinding nodig. Alles anders werk soos gewoonlik.',
      th: 'รูป AI ต้องใช้อินเทอร์เน็ต ส่วนอื่นยังใช้งานได้ตามปกติ',
    ),
    _ => _t(
      'Photos IA pas encore configurées sur ce magasin.',
      'AI photos aren’t set up on this store yet.',
      'Las fotos con IA aún no están configuradas en esta tienda.',
      'KI-Fotos sind für diesen Betrieb noch nicht eingerichtet.',
      'KI-foto’s is nog nie vir hierdie winkel opgestel nie.',
      th: 'ร้านนี้ยังไม่ได้ตั้งค่ารูป AI',
    ),
  };

  // AI menu setup
  String get aiMenuTitle => _t(
    'Configurer le menu avec l’IA',
    'AI menu setup',
    'Menú con IA',
    'KI-Speisekarte',
    'KI-spyskaartopstelling',
    th: 'ตั้งค่าเมนูด้วย AI',
  );
  String get aiMenuFromPhotos => _t(
    'Menu à partir de photos',
    'Menu from photos',
    'Menú desde fotos',
    'Karte aus Fotos',
    'Spyskaart uit foto’s',
    th: 'เมนูจากรูปภาพ',
  );
  String get aiMenuChatHint => currencySymbol() == r'$'
      ? _t(
          'Ex. : « ajoute salade César 14 \$ dans Entrées », « monte les burgers d’un dollar », « 86 le saumon »',
          'e.g. “add Caesar salad \$14 under starters”, “raise all burgers by a dollar”, “86 the salmon”',
          'p. ej. «agrega ensalada César \$14 en entradas», «sube las hamburguesas un dólar», «86 el salmón»',
          'z. B. „Caesar Salad für 14 \$ zu den Vorspeisen“, „alle Burger um einen Dollar teurer“, „Lachs ist aus“',
          'bv. “voeg Caesar-slaai \$14 by onder voorgeregte”, “maak alle burgers ’n dollar duurder”, “86 die salm”',
          th: 'เช่น “เพิ่มซีซาร์สลัด \$14 ในหมวดอาหารเรียกน้ำย่อย”, “ขึ้นราคาเบอร์เกอร์ทั้งหมด 1 ดอลลาร์”, “ปิดขายแซลมอน”',
        )
      // another currency (baht): example amounts that read right in it
      : _t(
          'Ex. : « ajoute salade César ${money(_hintPrice)} dans Entrées », « monte les burgers de ${money(_hintStep)} », « 86 le saumon »',
          'e.g. “add Caesar salad ${money(_hintPrice)} under starters”, “raise all burgers by ${money(_hintStep)}”, “86 the salmon”',
          'p. ej. «agrega ensalada César ${money(_hintPrice)} en entradas», «sube las hamburguesas ${money(_hintStep)}», «86 el salmón»',
          'z. B. „Caesar Salad für ${money(_hintPrice)} zu den Vorspeisen“, „alle Burger um ${money(_hintStep)} teurer“, „Lachs ist aus“',
          'bv. “voeg Caesar-slaai ${money(_hintPrice)} by onder voorgeregte”, “maak alle burgers ${money(_hintStep)} duurder”, “86 die salm”',
          th: 'เช่น “เพิ่มซีซาร์สลัด ${money(_hintPrice)} ในหมวดอาหารเรียกน้ำย่อย”, “ขึ้นราคาเบอร์เกอร์ทั้งหมด ${money(_hintStep)}”, “ปิดขายแซลมอน”',
        );

  /// The AI hint's example price and price step, in minor units: baht
  /// prices run about ten times dollar ones.
  int get _hintPrice =>
      StoreProfile.current.currency.toUpperCase() == 'THB' ? 15000 : 1400;
  int get _hintStep =>
      StoreProfile.current.currency.toUpperCase() == 'THB' ? 1000 : 100;
  String get aiMenuAsk => _t(
    'Proposer',
    'Propose',
    'Proponer',
    'Vorschlagen',
    'Stel voor',
    th: 'เสนอ',
  );
  String get aiMenuPreviewNote => _t(
    'Rien ne change avant « Appliquer ». Cochez ce que vous voulez garder.',
    'Nothing changes until you tap Apply. Tick what you want to keep.',
    'Nada cambia hasta que toques Aplicar. Marca lo que quieras conservar.',
    'Nichts ändert sich, bevor Sie auf Übernehmen tippen. Markieren Sie, was Sie behalten möchten.',
    'Niks verander voordat jy Pas toe tik nie. Merk wat jy wil hou.',
    th: 'จะยังไม่มีอะไรเปลี่ยนจนกว่าจะแตะ ใช้ เลือกรายการที่ต้องการเก็บไว้',
  );
  String get aiMenuNew =>
      _t('Nouveau', 'New', 'Nuevo', 'Neu', 'Nuut', th: 'ใหม่');
  String get aiMenuChanged => _t(
    'Modifié',
    'Changed',
    'Cambiado',
    'Geändert',
    'Verander',
    th: 'แก้ไขแล้ว',
  );
  String get aiMenuRemoved => _t(
    'Retiré',
    'Removed',
    'Eliminado',
    'Entfernt',
    'Verwyder',
    th: 'ลบแล้ว',
  );
  String get aiMenuNothing => _t(
    'Aucun changement proposé.',
    'No changes proposed.',
    'No se propusieron cambios.',
    'Keine Änderungen vorgeschlagen.',
    'Geen veranderinge voorgestel nie.',
    th: 'ไม่มีการเสนอเปลี่ยนแปลง',
  );
  String aiMenuRejected(int n) => _t(
    '$n suggestion(s) ignorée(s) (produit inconnu ou prix invalide).',
    '$n suggestion(s) skipped (unknown item or invalid price).',
    '$n sugerencia(s) omitida(s) (producto desconocido o precio no válido).',
    '$n Vorschlag/Vorschläge übersprungen (unbekannter Artikel oder ungültiger Preis).',
    '$n voorstel(le) oorgeslaan (onbekende item of ongeldige prys).',
    th: 'ข้าม $n คำแนะนำ (ไม่รู้จักรายการหรือราคาไม่ถูกต้อง)',
  );
  String aiMenuApply(int n) => _t(
    'Appliquer ($n)',
    'Apply ($n)',
    'Aplicar ($n)',
    'Übernehmen ($n)',
    'Pas toe ($n)',
    th: 'ใช้ ($n)',
  );
  String aiMenuApplied(int n) => _t(
    '$n changement(s) appliqué(s)',
    '$n change(s) applied',
    '$n cambio(s) aplicado(s)',
    '$n Änderung(en) übernommen',
    '$n verandering(e) toegepas',
    th: 'ใช้การเปลี่ยนแปลงแล้ว $n รายการ',
  );
  String get aiMenuHistory => _t(
    'Historique IA',
    'AI history',
    'Historial IA',
    'KI-Verlauf',
    'KI-geskiedenis',
    th: 'ประวัติ AI',
  );
  String get aiMenuRevert => _t(
    'Défaire',
    'Revert',
    'Revertir',
    'Rückgängig',
    'Herstel',
    th: 'ย้อนกลับ',
  );
  String get aiMenuReverted => _t(
    'Défait',
    'Reverted',
    'Revertido',
    'Rückgängig gemacht',
    'Herstel',
    th: 'ย้อนกลับแล้ว',
  );
  String get aiMenuRevertDone => _t(
    'Mise à jour IA défaite',
    'AI update reverted',
    'Actualización IA revertida',
    'KI-Änderung rückgängig gemacht',
    'KI-opdatering herstel',
    th: 'ย้อนกลับการอัปเดตจาก AI แล้ว',
  );
  String get aiMenuNoHistory => _t(
    'Aucune mise à jour IA pour l’instant.',
    'No AI updates yet.',
    'Aún no hay actualizaciones con IA.',
    'Noch keine KI-Änderungen.',
    'Nog geen KI-opdaterings nie.',
    th: 'ยังไม่มีการอัปเดตจาก AI',
  );
  String aiMenuRevertConflict(String titles) => _t(
    'Modifié depuis cette mise à jour : $titles. Défaire quand même et écraser ces changements ?',
    'Changed since this AI update: $titles. Revert anyway and overwrite those changes?',
    'Cambiado desde esta actualización: $titles. ¿Revertir de todos modos y sobrescribir esos cambios?',
    'Seit dieser KI-Änderung geändert: $titles. Trotzdem rückgängig machen und diese Änderungen überschreiben?',
    'Verander sedert hierdie KI-opdatering: $titles. Herstel in elk geval en oorskryf daardie veranderinge?',
    th: 'มีการแก้ไขหลังการอัปเดต AI ครั้งนี้: $titles ต้องการย้อนกลับและเขียนทับการแก้ไขเหล่านั้นหรือไม่?',
  );
  String get aiMenuRevertAnyway => _t(
    'Défaire quand même',
    'Revert anyway',
    'Revertir de todos modos',
    'Trotzdem rückgängig',
    'Herstel in elk geval',
    th: 'ย้อนกลับเลย',
  );
  String aiMenuSourceLabel(String source) => switch (source) {
    'photos' => _t(
      'Photos du menu',
      'Menu photos',
      'Fotos del menú',
      'Kartenfotos',
      'Spyskaartfoto’s',
      th: 'รูปเมนู',
    ),
    'translate' => _t(
      'Traduction',
      'Translation',
      'Traducción',
      'Übersetzung',
      'Vertaling',
      th: 'การแปล',
    ),
    _ => _t('Clavardage', 'Chat', 'Conversación', 'Chat', 'Klets', th: 'แชท'),
  };
  String aiMenuPhotosFor(int n) => _t(
    'Générer des photos pour les $n nouveaux produits ?',
    'Generate photos for the $n new items?',
    '¿Generar fotos para los $n productos nuevos?',
    'Fotos für die $n neuen Artikel erzeugen?',
    'Genereer foto’s vir die $n nuwe items?',
    th: 'สร้างรูปให้รายการใหม่ $n รายการไหม?',
  );
  String get aiMenuAddPhoto => _t(
    'Ajouter une photo',
    'Add photo',
    'Agregar foto',
    'Foto hinzufügen',
    'Voeg foto by',
    th: 'เพิ่มรูป',
  );
  String get aiMenuReadPhotos => _t(
    'Lire le menu',
    'Read menu',
    'Leer menú',
    'Karte einlesen',
    'Lees spyskaart',
    th: 'อ่านเมนู',
  );
  String aiMenuField(String field) => switch (field) {
    'nameEn' => _t(
      'Nom (anglais)',
      'Name (English)',
      'Nombre (inglés)',
      'Name (Englisch)',
      'Naam (Engels)',
      th: 'ชื่อ (อังกฤษ)',
    ),
    'nameFr' =>
      _thaiData
          ? _t(
              'Nom (thaï)',
              'Name (Thai)',
              'Nombre (tailandés)',
              'Name (Thailändisch)',
              'Naam (Thai)',
              th: 'ชื่อ (ไทย)',
            )
          : _t(
              'Nom (français)',
              'Name (French)',
              'Nombre (francés)',
              'Name (Französisch)',
              'Naam (Frans)',
              th: 'ชื่อ (ฝรั่งเศส)',
            ),
    'descriptionEn' => _t(
      'Description (anglais)',
      'Description (English)',
      'Descripción (inglés)',
      'Beschreibung (Englisch)',
      'Beskrywing (Engels)',
      th: 'คำอธิบาย (อังกฤษ)',
    ),
    'descriptionFr' =>
      _thaiData
          ? _t(
              'Description (thaï)',
              'Description (Thai)',
              'Descripción (tailandés)',
              'Beschreibung (Thailändisch)',
              'Beskrywing (Thai)',
              th: 'คำอธิบาย (ไทย)',
            )
          : _t(
              'Description (français)',
              'Description (French)',
              'Descripción (francés)',
              'Beschreibung (Französisch)',
              'Beskrywing (Frans)',
              th: 'คำอธิบาย (ฝรั่งเศส)',
            ),
    'price' => _t('Prix', 'Price', 'Precio', 'Preis', 'Prys', th: 'ราคา'),
    'category' => _t(
      'Catégorie',
      'Category',
      'Categoría',
      'Kategorie',
      'Kategorie',
      th: 'หมวดหมู่',
    ),
    'available' => _t(
      'En vente',
      'On sale',
      'A la venta',
      'Verfügbar',
      'Beskikbaar',
      th: 'เปิดขาย',
    ),
    'order' => _t(
      'Ordre',
      'Order',
      'Orden',
      'Reihenfolge',
      'Volgorde',
      th: 'ลำดับ',
    ),
    'name' => _t('Nom', 'Name', 'Nombre', 'Name', 'Naam', th: 'ชื่อ'),
    _ => field,
  };

  /// A language by its code, in the current language ("de" → "German",
  /// "af" → "Afrikaans" in every language but French and Spanish).
  String langName(String code) => switch (code) {
    'fr' => _t(
      'français',
      'French',
      'francés',
      'Französisch',
      'Frans',
      th: 'ฝรั่งเศส',
    ),
    'en' => _t(
      'anglais',
      'English',
      'inglés',
      'Englisch',
      'Engels',
      th: 'อังกฤษ',
    ),
    'es' => _t(
      'espagnol',
      'Spanish',
      'español',
      'Spanisch',
      'Spaans',
      th: 'สเปน',
    ),
    'de' => _t(
      'allemand',
      'German',
      'alemán',
      'Deutsch',
      'Duits',
      th: 'เยอรมัน',
    ),
    'af' => _t(
      'afrikaans',
      'Afrikaans',
      'afrikáans',
      'Afrikaans',
      'Afrikaans',
      th: 'แอฟริกานส์',
    ),
    'th' => _t('thaï', 'Thai', 'tailandés', 'Thailändisch', 'Thai', th: 'ไทย'),
    _ => code,
  };

  // "Translate menu": the AI fills in the names missing in the store's extra languages
  String get aiMenuTranslate => _t(
    'Traduire le menu',
    'Translate menu',
    'Traducir menú',
    'Karte übersetzen',
    'Vertaal spyskaart',
    th: 'แปลเมนู',
  );
  String aiMenuTranslateNote(String langs) => _t(
    'Propose les noms manquants en $langs. Rien ne change avant d’appliquer.',
    'Suggests the missing $langs names. Nothing changes until you apply.',
    'Propone los nombres que faltan en $langs. Nada cambia hasta que apliques.',
    'Schlägt fehlende Namen auf $langs vor. Erst „Übernehmen“ ändert etwas.',
    'Stel ontbrekende name in $langs voor. Niks verander voordat jy toepas nie.',
    th: 'เสนอชื่อภาษา $langs ที่ยังขาดอยู่ จะยังไม่มีอะไรเปลี่ยนจนกว่าจะกดใช้',
  );

  /// The assistant's fixed reply when there is no change set (the server's
  /// `refusal` code): never the model's own words.
  String aiMenuRefusal(String code) => code == 'no_change'
      ? _t(
          'Je n’ai trouvé aucun changement de menu à faire. Nommez le produit et ce qu’il faut changer, par exemple « salade César 14 ».',
          'I couldn’t find a menu change to make from that. Name the item and what to change, for example “Caesar salad 14”.',
          'No encontré ningún cambio de menú que hacer. Indica el producto y qué cambiar, por ejemplo «ensalada César 14».',
          'Ich habe keine Änderung an der Speisekarte gefunden. Nennen Sie den Artikel und was sich ändern soll, zum Beispiel „Caesar Salad 14“.',
          'Ek kon nie ’n spyskaartverandering daaruit aflei nie. Noem die item en wat moet verander, byvoorbeeld “Caesar salad 14”.',
          th: 'ไม่พบการแก้ไขเมนูจากข้อความนี้ กรุณาระบุชื่อรายการและสิ่งที่ต้องการเปลี่ยน เช่น “ซีซาร์สลัด 14”',
        )
      : _t(
          'Je peux seulement vous aider à configurer et modifier votre menu. Essayez par exemple « ajoute une salade César à 14 dans Salades ».',
          'I can only help set up and edit your menu. Try something like “add a Caesar salad for 14 under Salads”.',
          'Solo puedo ayudarte a configurar y editar tu menú. Prueba algo como «añade una ensalada César a 14 en Ensaladas».',
          'Ich kann nur beim Einrichten und Bearbeiten Ihrer Speisekarte helfen. Versuchen Sie zum Beispiel „Caesar Salad für 14 unter Salate hinzufügen“.',
          'Ek kan net help om jou spyskaart op te stel en te wysig. Probeer iets soos “voeg ’n Caesar-slaai vir 14 by onder Slaaie”.',
          th: 'ช่วยได้เฉพาะการตั้งค่าและแก้ไขเมนูเท่านั้น ลองพิมพ์เช่น “เพิ่มซีซาร์สลัด ราคา 14 ในหมวดสลัด”',
        );
  String aiMenuBulkConfirm(int removes, int prices) => _t(
    'C’est un gros changement : $removes produit(s) retiré(s) et $prices prix modifié(s). Appliquer quand même ?',
    'This is a big change: $removes item(s) removed and $prices price(s) changed. Apply anyway?',
    'Es un cambio grande: $removes producto(s) eliminado(s) y $prices precio(s) cambiado(s). ¿Aplicar de todos modos?',
    'Das ist eine große Änderung: $removes Artikel entfernt und $prices Preis(e) geändert. Trotzdem übernehmen?',
    'Dit is ’n groot verandering: $removes item(s) verwyder en $prices prys(e) verander. Pas in elk geval toe?',
    th: 'การเปลี่ยนแปลงนี้ค่อนข้างมาก: ลบ $removes รายการ และเปลี่ยนราคา $prices รายการ ต้องการใช้ต่อหรือไม่?',
  );
  String get aiMenuBigChangeConfirm => _t(
    'C’est un gros changement : des produits retirés de la caisse ou des prix réduits de moitié ou plus. Appliquer quand même ?',
    'This is a big change: items taken off the till, or prices cut by half or more. Apply anyway?',
    'Es un cambio grande: productos retirados de la caja o precios rebajados a la mitad o más. ¿Aplicar de todos modos?',
    'Das ist eine große Änderung: Artikel von der Kasse genommen oder Preise um die Hälfte oder mehr gesenkt. Trotzdem übernehmen?',
    'Dit is ’n groot verandering: items van die kasregister afgehaal, of pryse met die helfte of meer verlaag. Pas in elk geval toe?',
    th: 'การเปลี่ยนแปลงนี้ค่อนข้างมาก: มีรายการถูกนำออกจากหน้าขาย หรือราคาลดลงครึ่งหนึ่งขึ้นไป ต้องการใช้ต่อหรือไม่?',
  );
  String floorEditBulkConfirm(int n) => _t(
    'L’IA retirerait $n table(s) ou élément(s) de cette salle. Appliquer quand même ?',
    'The AI would remove $n table(s) or object(s) from this room. Apply anyway?',
    'La IA quitaría $n mesa(s) o elemento(s) de esta sala. ¿Aplicar de todos modos?',
    'Die KI würde $n Tisch(e) oder Element(e) aus diesem Raum entfernen. Trotzdem übernehmen?',
    'Die KI sou $n tafel(s) of voorwerp(e) uit hierdie vertrek verwyder. Pas in elk geval toe?',
    th: 'AI จะลบโต๊ะหรือวัตถุ $n ชิ้นออกจากห้องนี้ ต้องการใช้ต่อหรือไม่?',
  );
  String get floorEditApplyAnyway => _t(
    'Appliquer quand même',
    'Apply anyway',
    'Aplicar de todos modos',
    'Trotzdem übernehmen',
    'Pas in elk geval toe',
    th: 'ใช้ต่อ',
  );
  String get aiMenuTranslateDone => _t(
    'Tous les noms sont déjà traduits.',
    'Every name is already translated.',
    'Todos los nombres ya están traducidos.',
    'Alle Namen sind bereits übersetzt.',
    'Elke naam is reeds vertaal.',
    th: 'ทุกชื่อแปลครบแล้ว',
  );
  String get aiMenuCategoryOrder => _t(
    'Ordre des catégories',
    'Category order',
    'Orden de categorías',
    'Kategorie-Reihenfolge',
    'Kategorievolgorde',
    th: 'ลำดับหมวดหมู่',
  );
  String aiMenuNewCategory(String name) => _t(
    'Catégorie : $name',
    'Category: $name',
    'Categoría: $name',
    'Kategorie: $name',
    'Kategorie: $name',
    th: 'หมวดหมู่: $name',
  );

  /// Why the AI menu buttons are greyed out.
  String aiMenuUnavailableNote(String? reason) => switch (reason) {
    'menu_ai_offline' || 'menu_ai_unavailable' => _t(
      'Menu IA : connexion Internet requise. Tout le reste fonctionne normalement.',
      'AI menu setup needs an internet connection. Everything else works as usual.',
      'El menú con IA necesita internet. Todo lo demás funciona con normalidad.',
      'Die KI-Speisekarte benötigt eine Internetverbindung. Alles andere funktioniert wie gewohnt.',
      'KI-spyskaartopstelling het ’n internetverbinding nodig. Alles anders werk soos gewoonlik.',
      th: 'การตั้งค่าเมนูด้วย AI ต้องใช้อินเทอร์เน็ต ส่วนอื่นยังใช้งานได้ตามปกติ',
    ),
    _ => _t(
      'Menu IA pas encore configuré sur ce magasin.',
      'AI menu setup isn’t set up on this store yet.',
      'El menú con IA aún no está configurado en esta tienda.',
      'Die KI-Speisekarte ist für diesen Betrieb noch nicht eingerichtet.',
      'KI-spyskaartopstelling is nog nie vir hierdie winkel opgestel nie.',
      th: 'ร้านนี้ยังไม่ได้เปิดใช้การตั้งค่าเมนูด้วย AI',
    ),
  };

  // pin pad / manager approval
  String get managerPinTitle => _t(
    'Gérant : entrez votre NIP',
    'Manager: enter PIN',
    'Gerente: ingresa tu PIN',
    'Manager: PIN eingeben',
    'Bestuurder: voer PIN in',
    th: 'ผู้จัดการ: ใส่ PIN',
  );
  String get managerApproval => _t(
    'Approbation du gérant',
    'Manager approval',
    'Aprobación del gerente',
    'Freigabe durch Manager',
    'Bestuurder se goedkeuring',
    th: 'การอนุมัติจากผู้จัดการ',
  );

  // change PIN
  String get changePin => _t(
    'Changer de NIP',
    'Change PIN',
    'Cambiar PIN',
    'PIN ändern',
    'Verander PIN',
    th: 'เปลี่ยน PIN',
  );
  String get currentPin => _t(
    'NIP actuel',
    'Current PIN',
    'PIN actual',
    'Aktuelle PIN',
    'Huidige PIN',
    th: 'PIN ปัจจุบัน',
  );
  String get newPin => _t(
    'Nouveau NIP',
    'New PIN',
    'PIN nuevo',
    'Neue PIN',
    'Nuwe PIN',
    th: 'PIN ใหม่',
  );
  String get confirmNewPin => _t(
    'Confirmer le nouveau NIP',
    'Confirm new PIN',
    'Confirmar el PIN nuevo',
    'Neue PIN bestätigen',
    'Bevestig nuwe PIN',
    th: 'ยืนยัน PIN ใหม่',
  );
  String get pinChanged => _t(
    'NIP modifié',
    'PIN changed',
    'PIN cambiado',
    'PIN geändert',
    'PIN verander',
    th: 'เปลี่ยน PIN แล้ว',
  );
  String get pinMismatch => _t(
    'Les nouveaux NIP ne correspondent pas',
    'New PINs do not match',
    'Los PIN nuevos no coinciden',
    'Die neuen PINs stimmen nicht überein',
    'Nuwe PIN’s stem nie ooreen nie',
    th: 'PIN ใหม่ไม่ตรงกัน',
  );

  // staff administration (the tablet owns its staff; the portal only shows them)
  String get staffTitle => _t(
    'Personnel',
    'Staff',
    'Personal',
    'Personal',
    'Personeel',
    th: 'พนักงาน',
  );
  String get staffAdd => _t(
    'Ajouter un employé',
    'Add staff',
    'Agregar empleado',
    'Mitarbeiter hinzufügen',
    'Voeg personeellid by',
    th: 'เพิ่มพนักงาน',
  );
  String get staffEdit => _t(
    'Modifier l’employé',
    'Edit staff',
    'Editar empleado',
    'Mitarbeiter bearbeiten',
    'Wysig personeellid',
    th: 'แก้ไขพนักงาน',
  );
  String get staffName =>
      _t('Nom', 'Name', 'Nombre', 'Name', 'Naam', th: 'ชื่อ');
  String get staffRole =>
      _t('Rôle', 'Role', 'Puesto', 'Rolle', 'Rol', th: 'ตำแหน่ง');
  String get roleManager => _t(
    'Gérant',
    'Manager',
    'Gerente',
    'Manager',
    'Bestuurder',
    th: 'ผู้จัดการ',
  );
  String get roleServer => _t(
    'Serveur',
    'Server',
    'Mesero',
    'Service',
    'Kelner',
    th: 'พนักงานเสิร์ฟ',
  );
  String get staffActive =>
      _t('Actif', 'Active', 'Activo', 'Aktiv', 'Aktief', th: 'ใช้งาน');
  String get staffInactive => _t(
    'Inactif',
    'Inactive',
    'Inactivo',
    'Inaktiv',
    'Onaktief',
    th: 'ไม่ใช้งาน',
  );
  String get staffPin4 => _t(
    'NIP (4 chiffres)',
    'PIN (4 digits)',
    'PIN (4 dígitos)',
    'PIN (4 Ziffern)',
    'PIN (4 syfers)',
    th: 'PIN (4 หลัก)',
  );
  String get staffResetPin => _t(
    'Nouveau NIP',
    'Reset PIN',
    'Restablecer PIN',
    'PIN zurücksetzen',
    'Stel PIN terug',
    th: 'รีเซ็ต PIN',
  );
  String get staffPinInvalid => _t(
    'Le NIP doit compter 4 chiffres',
    'PIN must be 4 digits',
    'El PIN debe tener 4 dígitos',
    'Die PIN muss 4 Ziffern haben',
    'PIN moet 4 syfers wees',
    th: 'PIN ต้องมี 4 หลัก',
  );
  String get staffNameRequired => _t(
    'Un nom est requis',
    'A name is required',
    'Se requiere un nombre',
    'Bitte einen Namen angeben',
    '’n Naam is nodig',
    th: 'กรุณาใส่ชื่อ',
  );
  String get staffDelete =>
      _t('Supprimer', 'Delete', 'Eliminar', 'Löschen', 'Verwyder', th: 'ลบ');
  String staffDeleteConfirm(String name) => _t(
    'Supprimer $name ? L’historique des ventes est conservé.',
    'Delete $name? Sales history is kept.',
    '¿Eliminar a $name? El historial de ventas se conserva.',
    '$name löschen? Der Umsatzverlauf bleibt erhalten.',
    'Verwyder $name? Verkoopsgeskiedenis word behou.',
    th: 'ลบ $name ใช่ไหม? ประวัติการขายจะยังเก็บไว้',
  );
  String get staffSaved => _t(
    'Enregistré',
    'Saved',
    'Guardado',
    'Gespeichert',
    'Gestoor',
    th: 'บันทึกแล้ว',
  );
  String get staffSyncHint => _t(
    'Les changements s’appliquent tout de suite sur cette tablette et sont envoyés au portail à la prochaine connexion.',
    'Changes apply on this tablet at once and are sent to the owner portal when it next connects.',
    'Los cambios se aplican de inmediato en esta tableta y se envían al portal del dueño la próxima vez que se conecte.',
    'Änderungen gelten sofort auf diesem Tablet und werden beim nächsten Verbindungsaufbau an das Inhaberportal gesendet.',
    'Veranderinge geld dadelik op hierdie tablet en word na die eienaarportaal gestuur wanneer dit weer koppel.',
    th: 'การเปลี่ยนแปลงมีผลบนแท็บเล็ตนี้ทันที และจะส่งไปยังพอร์ทัลเจ้าของร้านเมื่อเชื่อมต่อครั้งถัดไป',
  );

  // settings
  String get settings => _t(
    'Paramètres de l’établissement',
    'Venue settings',
    'Configuración de la tienda',
    'Betriebseinstellungen',
    'Plekinstellings',
    th: 'ตั้งค่าร้าน',
  );
  String get sectionPayments => _t(
    'Paiements',
    'Payments',
    'Pagos',
    'Zahlungen',
    'Betalings',
    th: 'การชำระเงิน',
  );
  String get sectionFees =>
      _t('Frais', 'Fees', 'Cargos', 'Gebühren', 'Fooie', th: 'ค่าธรรมเนียม');
  String get sectionReceipt =>
      _t('Reçu', 'Receipt', 'Recibo', 'Beleg', 'Kwitansie', th: 'ใบเสร็จ');
  String get sectionSecurity => _t(
    'Sécurité',
    'Security',
    'Seguridad',
    'Sicherheit',
    'Sekuriteit',
    th: 'ความปลอดภัย',
  );
  String get sessionIdleLabel => _t(
    'Déconnexion automatique après inactivité (minutes)',
    'Auto-logout when idle (minutes)',
    'Cerrar sesión por inactividad (minutos)',
    'Automatisch abmelden bei Inaktivität (Minuten)',
    'Meld outomaties af wanneer onaktief (minute)',
    th: 'ออกจากระบบอัตโนมัติเมื่อไม่ได้ใช้งาน (นาที)',
  );
  String get cardProcessorLabel => _t(
    'Nom du terminal de paiement',
    'Card terminal label',
    'Nombre de la terminal de tarjetas',
    'Bezeichnung Kartenterminal',
    'Kaartterminaal-etiket',
    th: 'ชื่อเครื่องรับบัตร',
  );
  String get bankNameLabel =>
      _t('Banque', 'Bank', 'Banco', 'Bank', 'Bank', th: 'ธนาคาร');
  String get bankAccountNumberLabel => _t(
    'Numéro de compte',
    'Account number',
    'Número de cuenta',
    'Kontonummer',
    'Rekeningnommer',
    th: 'เลขที่บัญชี',
  );
  String get bankAccountNameLabel => _t(
    'Titulaire du compte',
    'Account holder name',
    'Titular de la cuenta',
    'Kontoinhaber',
    'Naam van rekeninghouer',
    th: 'ชื่อบัญชี',
  );
  String get serviceChargeLabel => _t(
    'Frais de service (%) — 0 = désactivés',
    'Service charge (%) — 0 = off',
    'Cargo por servicio (%): 0 = desactivado',
    'Servicegebühr (%) – 0 = aus',
    'Diensheffing (%) — 0 = af',
    th: 'ค่าบริการ (%) — 0 = ปิด',
  );
  String get corkageRateLabel => _t(
    'Droit de bouchon ($cur/bouteille) — 0 = désactivé',
    'Corkage ($cur/bottle) — 0 = off',
    'Descorche ($cur/botella): 0 = desactivado',
    'Korkgeld ($cur/Flasche) – 0 = aus',
    'Kurkgeld ($cur/bottel) — 0 = af',
    th: 'ค่าเปิดขวด ($cur/ขวด) — 0 = ปิด',
  );
  String get receiptFooterLabel => _t(
    'Message au bas du reçu',
    'Receipt footer text',
    'Mensaje al pie del recibo',
    'Fußzeile auf dem Beleg',
    'Voetskrif op kwitansie',
    th: 'ข้อความท้ายใบเสร็จ',
  );
  String get venuePhoneLabel => _t(
    'Téléphone de l’établissement',
    'Venue phone',
    'Teléfono de la tienda',
    'Telefon des Betriebs',
    'Plek se telefoon',
    th: 'เบอร์โทรร้าน',
  );
  String get venueAddressLabel => _t(
    'Adresse de l’établissement',
    'Venue address',
    'Dirección de la tienda',
    'Adresse des Betriebs',
    'Plek se adres',
    th: 'ที่อยู่ร้าน',
  );
  String get save =>
      _t('Enregistrer', 'Save', 'Guardar', 'Speichern', 'Stoor', th: 'บันทึก');
  String get savedTakesEffectNext => _t(
    'Enregistré — s’applique à partir de la prochaine addition',
    'Saved — applies from the next bill',
    'Guardado: se aplica desde la próxima cuenta',
    'Gespeichert – gilt ab der nächsten Rechnung',
    'Gestoor — geld vanaf die volgende rekening',
    th: 'บันทึกแล้ว — มีผลตั้งแต่บิลถัดไป',
  );

  // store server URL (device-local; auto-discovered on the LAN)
  String get sectionServer => _t(
    'Connexion au restaurant',
    'Restaurant connection',
    'Conexión con la tienda',
    'Verbindung zum Restaurant',
    'Restaurantverbinding',
    th: 'การเชื่อมต่อร้าน',
  );
  String get serverUrlLabel => _t(
    'Adresse de connexion avancée',
    'Advanced connection address',
    'Dirección de conexión avanzada',
    'Erweiterte Verbindungsadresse',
    'Gevorderde verbindingsadres',
    th: 'ที่อยู่การเชื่อมต่อขั้นสูง',
  );
  String get scanForServer => _t(
    'Trouver le restaurant sur le Wi-Fi',
    'Find restaurant on Wi-Fi',
    'Buscar la tienda en el Wi-Fi',
    'Restaurant im WLAN suchen',
    'Soek restaurant op Wi-Fi',
    th: 'ค้นหาร้านบน Wi-Fi',
  );
  String get scanningForServer => _t(
    'Recherche du restaurant…',
    'Finding restaurant…',
    'Buscando la tienda…',
    'Restaurant wird gesucht…',
    'Soek restaurant…',
    th: 'กำลังค้นหาร้าน…',
  );
  String get connectingToServer => _t(
    'Recherche du restaurant…',
    'Finding your restaurant…',
    'Buscando tu tienda…',
    'Ihr Restaurant wird gesucht…',
    'Soek jou restaurant…',
    th: 'กำลังค้นหาร้านของคุณ…',
  );
  String get findingRestaurant => _t(
    'Recherche du restaurant…',
    'Finding your restaurant…',
    'Buscando tu tienda…',
    'Ihr Restaurant wird gesucht…',
    'Soek jou restaurant…',
    th: 'กำลังค้นหาร้านของคุณ…',
  );
  String get startingThisTablet => _t(
    'Démarrage de cette tablette…',
    'Starting this tablet…',
    'Iniciando esta tableta…',
    'Tablet wird gestartet…',
    'Begin hierdie tablet…',
    th: 'กำลังเริ่มแท็บเล็ตนี้…',
  );
  String get tabletStoreFailed => _t(
    'Le service local de cette tablette n’a pas démarré.',
    'This tablet’s local store did not start.',
    'El servicio local de esta tableta no arrancó.',
    'Der lokale Server dieses Tablets ist nicht gestartet.',
    'Hierdie tablet se plaaslike bediener het nie begin nie.',
    th: 'ฐานข้อมูลในเครื่องของแท็บเล็ตนี้เริ่มทำงานไม่ได้',
  );
  String get staffAppNeedsWifi => _t(
    'Connectez la tablette au Wi-Fi pour afficher le code de l’application du personnel.',
    'Connect this tablet to Wi-Fi to show the staff app code.',
    'Conecta esta tableta al Wi-Fi para mostrar el código de la app del personal.',
    'Verbinden Sie dieses Tablet mit dem WLAN, um den Code der Personal-App anzuzeigen.',
    'Koppel hierdie tablet aan Wi-Fi om die personeelapp-kode te wys.',
    th: 'เชื่อมต่อแท็บเล็ตนี้กับ Wi-Fi เพื่อแสดงรหัสแอปพนักงาน',
  );
  String get restaurantUnavailable => _t(
    'Restaurant introuvable sur ce réseau Wi-Fi',
    'Restaurant is not available on this Wi-Fi',
    'La tienda no está disponible en este Wi-Fi',
    'Restaurant ist in diesem WLAN nicht erreichbar',
    'Restaurant is nie op hierdie Wi-Fi beskikbaar nie',
    th: 'ไม่พบร้านบน Wi-Fi นี้',
  );
  String get connectionHelp => _t(
    'Aide à la connexion',
    'Connection help',
    'Ayuda con la conexión',
    'Hilfe zur Verbindung',
    'Verbindingshulp',
    th: 'ช่วยเหลือการเชื่อมต่อ',
  );
  String serverFoundAt(String url) => _t(
    'Restaurant trouvé',
    'Restaurant found',
    'Tienda encontrada',
    'Restaurant gefunden',
    'Restaurant gevind',
    th: 'พบร้านแล้ว',
  );
  String get serverNotFound => _t(
    'Restaurant introuvable sur ce Wi-Fi',
    'Restaurant not found on this Wi-Fi',
    'No se encontró la tienda en este Wi-Fi',
    'Restaurant in diesem WLAN nicht gefunden',
    'Restaurant nie op hierdie Wi-Fi gevind nie',
    th: 'ไม่พบร้านบน Wi-Fi นี้',
  );
  String currentlyUsing(String url) => _t(
    'Adresse actuelle : $url',
    'Currently using: $url',
    'Dirección actual: $url',
    'Aktuell verwendet: $url',
    'Gebruik tans: $url',
    th: 'กำลังใช้: $url',
  );
  String get serverSavedRestart => _t(
    'Enregistré — redémarrez l’application pour l’appliquer',
    'Saved — restart the app to apply',
    'Guardado: reinicia la app para aplicarlo',
    'Gespeichert – App neu starten, um es anzuwenden',
    'Gestoor — herbegin die app om toe te pas',
    th: 'บันทึกแล้ว — รีสตาร์ทแอปเพื่อให้มีผล',
  );
  String get setServerUrl => _t(
    'Connexion avancée',
    'Advanced connection',
    'Conexión avanzada',
    'Erweiterte Verbindung',
    'Gevorderde verbinding',
    th: 'การเชื่อมต่อขั้นสูง',
  );
  String get restaurantConnectionReady => _t(
    'Restaurant disponible',
    'Restaurant available',
    'Tienda disponible',
    'Restaurant erreichbar',
    'Restaurant beskikbaar',
    th: 'ร้านพร้อมใช้งาน',
  );
  String get advancedConnection => _t(
    'Dépannage avancé',
    'Advanced troubleshooting',
    'Solución de problemas avanzada',
    'Erweiterte Fehlerbehebung',
    'Gevorderde probleemoplossing',
    th: 'การแก้ปัญหาขั้นสูง',
  );

  // terminal pairing (cloud venues)
  String get pairTerminalTitle => _t(
    'Jumeler ce terminal',
    'Pair this terminal',
    'Vincular esta terminal',
    'Dieses Terminal koppeln',
    'Koppel hierdie terminaal',
    th: 'จับคู่เครื่องนี้',
  );
  String get pairTerminalIntro => _t(
    'Créez un code de jumelage dans le portail du propriétaire, puis entrez-le ici.',
    'Mint a pairing code in the owner portal, then enter it here.',
    'Genera un código de vinculación en el portal del dueño y luego ingrésalo aquí.',
    'Erzeugen Sie im Inhaberportal einen Kopplungscode und geben Sie ihn hier ein.',
    'Skep ’n koppelkode in die eienaarportaal en voer dit dan hier in.',
    th: 'สร้างรหัสจับคู่ในพอร์ทัลเจ้าของร้าน แล้วใส่รหัสที่นี่',
  );
  String get pairVenueAddressLabel => _t(
    'Adresse de l’établissement',
    'Venue address',
    'Dirección de la tienda',
    'Adresse des Betriebs',
    'Plek se adres',
    th: 'ที่อยู่ร้าน',
  );
  String get pairingCodeLabel => _t(
    'Code de jumelage',
    'Pairing code',
    'Código de vinculación',
    'Kopplungscode',
    'Koppelkode',
    th: 'รหัสจับคู่',
  );
  String get deviceNameLabel => _t(
    'Nom de l’appareil (ex. : Bar)',
    'Device name (e.g. Bar)',
    'Nombre del dispositivo (p. ej., Caja)',
    'Gerätename (z. B. Bar)',
    'Toestelnaam (bv. Kroeg)',
    th: 'ชื่ออุปกรณ์ (เช่น บาร์)',
  );
  String get pairAction =>
      _t('Jumeler', 'Pair', 'Vincular', 'Koppeln', 'Koppel', th: 'จับคู่');
  String get enterVenueAddress => _t(
    'Entrez l’adresse de l’établissement',
    'Enter the venue address',
    'Ingresa la dirección de la tienda',
    'Bitte die Adresse des Betriebs eingeben',
    'Voer die plek se adres in',
    th: 'ใส่ที่อยู่ร้าน',
  );
  String get enterPairingCode => _t(
    'Entrez le code de jumelage',
    'Enter the pairing code',
    'Ingresa el código de vinculación',
    'Bitte den Kopplungscode eingeben',
    'Voer die koppelkode in',
    th: 'ใส่รหัสจับคู่',
  );
  String get pairNetworkError => _t(
    'Établissement injoignable — vérifiez l’adresse et le réseau',
    'Cannot reach the venue — check the address and your network',
    'No se puede conectar con la tienda: revisa la dirección y la red',
    'Betrieb nicht erreichbar – Adresse und Netzwerk prüfen',
    'Kan nie die plek bereik nie — gaan die adres en jou netwerk na',
    th: 'เชื่อมต่อร้านไม่ได้ — ตรวจสอบที่อยู่และเครือข่ายของคุณ',
  );

  // global reconnecting overlay
  String get reconnectingToServer => _t(
    'Reconnexion au restaurant…',
    'Reconnecting to your restaurant…',
    'Reconectando con tu tienda…',
    'Verbindung zum Restaurant wird wiederhergestellt…',
    'Herkoppel met jou restaurant…',
    th: 'กำลังเชื่อมต่อร้านของคุณใหม่…',
  );
  String get reconnectingToRestaurant => _t(
    'Reconnexion au restaurant…',
    'Reconnecting to your restaurant…',
    'Reconectando con tu tienda…',
    'Verbindung zum Restaurant wird wiederhergestellt…',
    'Herkoppel met jou restaurant…',
    th: 'กำลังเชื่อมต่อร้านของคุณใหม่…',
  );
  String get reconnectChangeServer => _t(
    'Aide à la connexion',
    'Connection help',
    'Ayuda con la conexión',
    'Hilfe zur Verbindung',
    'Verbindingshulp',
    th: 'ช่วยเหลือการเชื่อมต่อ',
  );

  // on-screen QR codes: table scan-to-order (long-press a table) + staff app
  String tableQrTitle(String label) => _t(
    'Balayez pour commander · $label',
    'Scan to order · $label',
    'Escanea para pedir · $label',
    'Scannen und bestellen · $label',
    'Skandeer om te bestel · $label',
    th: 'สแกนเพื่อสั่งอาหาร · $label',
  );
  String get tableQrHint => _t(
    'Les clients balaient le code avec un téléphone branché sur le Wi-Fi de l’établissement',
    'Guests scan with a phone on the venue Wi-Fi',
    'Los clientes escanean con un celular conectado al Wi-Fi de la tienda',
    'Gäste scannen mit dem Handy im WLAN des Restaurants',
    'Gaste skandeer met ’n foon op die plek se Wi-Fi',
    th: 'ลูกค้าสแกนด้วยโทรศัพท์ที่เชื่อมต่อ Wi-Fi ของร้าน',
  );
  String get tableQrNeedsWifi => _t(
    'Connectez la tablette au Wi-Fi pour afficher un code QR utilisable.',
    'Connect the tablet to Wi-Fi to show a scannable QR code.',
    'Conecta la tableta al Wi-Fi para mostrar un código QR que funcione.',
    'Verbinden Sie das Tablet mit dem WLAN, um einen scanbaren QR-Code anzuzeigen.',
    'Koppel die tablet aan Wi-Fi om ’n skandeerbare QR-kode te wys.',
    th: 'เชื่อมต่อแท็บเล็ตกับ Wi-Fi เพื่อแสดง QR code ที่สแกนได้',
  );
  String get showQrCode => _t(
    'Afficher le code QR',
    'Show QR code',
    'Mostrar código QR',
    'QR-Code anzeigen',
    'Wys QR-kode',
    th: 'แสดง QR code',
  );
  String get printQrCode => _t(
    'Imprimer le code QR',
    'Print QR code',
    'Imprimir código QR',
    'QR-Code drucken',
    'Druk QR-kode',
    th: 'พิมพ์ QR code',
  );
  String get regenerateTableLink => _t(
    'Régénérer le lien de la table',
    'Regenerate table link',
    'Regenerar el enlace de la mesa',
    'Tisch-Link neu erzeugen',
    'Hergenereer tafelskakel',
    th: 'สร้างลิงก์โต๊ะใหม่',
  );
  String regenerateTableLinkConfirm(String label) => _t(
    'Créer un nouveau lien pour $label ? Les fiches QR déjà imprimées pour cette table cesseront de fonctionner ; réimprimez sa fiche.',
    'Create a new link for $label? QR slips already printed for this table stop working; reprint its slip.',
    '¿Crear un enlace nuevo para $label? Las hojas QR ya impresas para esta mesa dejarán de funcionar; vuelve a imprimir su hoja.',
    'Neuen Link für $label erstellen? Bereits gedruckte QR-Zettel für diesen Tisch funktionieren dann nicht mehr; drucken Sie den Zettel neu.',
    'Skep ’n nuwe skakel vir $label? QR-strokies wat reeds vir hierdie tafel gedruk is, sal nie meer werk nie; druk sy strokie weer.',
    th: 'สร้างลิงก์ใหม่ให้ $label ไหม? ใบ QR ที่พิมพ์ไว้แล้วของโต๊ะนี้จะใช้ไม่ได้ ต้องพิมพ์ใบใหม่',
  );
  String get regenerate => _t(
    'Régénérer',
    'Regenerate',
    'Regenerar',
    'Neu erzeugen',
    'Hergenereer',
    th: 'สร้างใหม่',
  );
  String get tableLinkRegenerated => _t(
    'Nouveau lien créé. Réimprimez la fiche QR de cette table.',
    'New link created. Reprint this table\'s QR slip.',
    'Se creó un enlace nuevo. Vuelve a imprimir la hoja QR de esta mesa.',
    'Neuer Link erstellt. Bitte den QR-Zettel dieses Tisches neu drucken.',
    'Nuwe skakel geskep. Druk hierdie tafel se QR-strokie weer.',
    th: 'สร้างลิงก์ใหม่แล้ว กรุณาพิมพ์ใบ QR ของโต๊ะนี้ใหม่',
  );
  String get slipSentToPrinter => _t(
    'Fiche QR envoyée à l’imprimante',
    'QR slip sent to the printer',
    'Hoja QR enviada a la impresora',
    'QR-Zettel an den Drucker gesendet',
    'QR-strokie na die drukker gestuur',
    th: 'ส่งใบ QR ไปที่เครื่องพิมพ์แล้ว',
  );
  String get sectionStaffApp => _t(
    'Application du personnel',
    'Staff app',
    'App del personal',
    'Personal-App',
    'Personeelapp',
    th: 'แอปพนักงาน',
  );
  String get staffAppQrLabel => _t(
    'Application de commande du personnel — balayez pour l’ouvrir',
    'Staff ordering app — scan to open',
    'App de pedidos del personal: escanea para abrir',
    'Bestell-App fürs Personal – zum Öffnen scannen',
    'Personeel-bestelapp — skandeer om oop te maak',
    th: 'แอปสั่งอาหารสำหรับพนักงาน — สแกนเพื่อเปิด',
  );
  String get sectionReportsPortal => _t(
    'Rapports',
    'Reports',
    'Reportes',
    'Berichte',
    'Verslae',
    th: 'รายงาน',
  );
  String get reportsPortalQrLabel => _t(
    'Portail de rapports du propriétaire — balayez pour l’ouvrir',
    'Owner reporting portal — scan to open',
    'Portal de reportes del dueño: escanea para abrir',
    'Auswertungsportal für Inhaber – zum Öffnen scannen',
    'Eienaar-verslagportaal — skandeer om oop te maak',
    th: 'พอร์ทัลรายงานสำหรับเจ้าของร้าน — สแกนเพื่อเปิด',
  );
  String get cloudNotConfigured => _t(
    'Nuage non configuré',
    'Cloud not configured',
    'La nube no está configurada',
    'Cloud nicht eingerichtet',
    'Wolk nie opgestel nie',
    th: 'ยังไม่ได้ตั้งค่าคลาวด์',
  );

  // pending-order alerts (settings + banner)
  String get sectionAlerts => _t(
    'Alertes de commandes clients',
    'Customer order alerts',
    'Alertas de pedidos de clientes',
    'Hinweise bei Gästebestellungen',
    'Waarskuwings vir klantbestellings',
    th: 'แจ้งเตือนออร์เดอร์จากลูกค้า',
  );
  String get alertsEnabledLabel => _t(
    'Carillon à chaque nouvelle commande d’un client',
    'Chime on new customer orders',
    'Sonido con cada pedido nuevo de un cliente',
    'Signalton bei neuen Gästebestellungen',
    'Klokkie by nuwe klantbestellings',
    th: 'ส่งเสียงเมื่อมีออร์เดอร์ใหม่จากลูกค้า',
  );
  String get alertEscalateLabel => _t(
    'Alerte insistante si rien n’est fait après (secondes)',
    'Escalate if un-actioned after (seconds)',
    'Alerta insistente si nadie responde después de (segundos)',
    'Erneut melden, wenn unbearbeitet nach (Sekunden)',
    'Eskaleer indien onafgehandel na (sekondes)',
    th: 'แจ้งเตือนซ้ำถ้ายังไม่มีคนรับภายใน (วินาที)',
  );
  String get alertVolumeLabel => _t(
    'Volume de l’alerte',
    'Alert volume',
    'Volumen de la alerta',
    'Lautstärke der Hinweise',
    'Waarskuwingvolume',
    th: 'ระดับเสียงแจ้งเตือน',
  );

  // receipt printer (settings)
  String get sectionPrinter => _t(
    'Imprimante à reçus',
    'Receipt printer',
    'Impresora de recibos',
    'Belegdrucker',
    'Kwitansiedrukker',
    th: 'เครื่องพิมพ์ใบเสร็จ',
  );
  String get printerIpLabel => _t(
    'IP de l’imprimante (vide = désactivée)',
    'Printer IP (blank = off)',
    'IP de la impresora (vacío = desactivada)',
    'Drucker-IP (leer = aus)',
    'Drukker-IP (leeg = af)',
    th: 'IP เครื่องพิมพ์ (เว้นว่าง = ปิด)',
  );
  String get printerPortLabel =>
      _t('Port réseau', 'Port', 'Puerto', 'Port', 'Poort', th: 'พอร์ต');
  String get scanForPrinter => _t(
    'Chercher l’imprimante sur le réseau',
    'Scan network for printer',
    'Buscar la impresora en la red',
    'Netzwerk nach Drucker durchsuchen',
    'Soek netwerk vir drukker',
    th: 'ค้นหาเครื่องพิมพ์ในเครือข่าย',
  );
  String get scanningForPrinter => _t(
    'Recherche de l’imprimante…',
    'Scanning for printer…',
    'Buscando la impresora…',
    'Drucker wird gesucht …',
    'Soek drukker…',
    th: 'กำลังค้นหาเครื่องพิมพ์…',
  );
  String printerFoundAt(String ip) => _t(
    'Imprimante trouvée : $ip',
    'Found printer: $ip',
    'Impresora encontrada: $ip',
    'Drucker gefunden: $ip',
    'Drukker gevind: $ip',
    th: 'พบเครื่องพิมพ์: $ip',
  );
  String printersFound(int n, String ip) => _t(
    '$n imprimantes trouvées — utilisation de $ip',
    'Found $n printers — using $ip',
    'Se encontraron $n impresoras: usando $ip',
    '$n Drucker gefunden – verwendet wird $ip',
    '$n drukkers gevind — gebruik $ip',
    th: 'พบเครื่องพิมพ์ $n เครื่อง — ใช้ $ip',
  );
  String get printerNotFound => _t(
    'Aucune imprimante trouvée sur ce réseau',
    'No printer found on this network',
    'No se encontró ninguna impresora en esta red',
    'Kein Drucker in diesem Netzwerk gefunden',
    'Geen drukker op hierdie netwerk gevind nie',
    th: 'ไม่พบเครื่องพิมพ์ในเครือข่ายนี้',
  );
  String get testPrint => _t(
    'Impression d’essai',
    'Test print',
    'Impresión de prueba',
    'Testdruck',
    'Toetsdruk',
    th: 'ทดสอบพิมพ์',
  );
  String get printerTestSent => _t(
    'Page d’essai envoyée à l’imprimante',
    'Test page sent to the printer',
    'Página de prueba enviada a la impresora',
    'Testseite an den Drucker gesendet',
    'Toetsbladsy na die drukker gestuur',
    th: 'ส่งหน้าทดสอบไปที่เครื่องพิมพ์แล้ว',
  );
  String get printerNotConfigured => _t(
    'IP de l’imprimante non définie',
    'Printer IP not set',
    'No se configuró la IP de la impresora',
    'Keine Drucker-IP eingestellt',
    'Drukker-IP nie gestel nie',
    th: 'ยังไม่ได้ตั้งค่า IP เครื่องพิมพ์',
  );
  String get printerOffline => _t(
    'Échec de l’impression — imprimante hors ligne',
    'Print failed — printer offline',
    'Falló la impresión: impresora desconectada',
    'Druck fehlgeschlagen – Drucker offline',
    'Druk het misluk — drukker vanlyn',
    th: 'พิมพ์ไม่สำเร็จ — เครื่องพิมพ์ออฟไลน์',
  );
  String get sectionDemoMode => _t(
    'Mode démo',
    'Demo mode',
    'Modo demo',
    'Demo-Modus',
    'Demo-modus',
    th: 'โหมดเดโม',
  );
  String get demoModeOn => _t(
    'Activé (demo.mode dans store.properties). L’application du personnel se connecte avec le NIP seulement.',
    'On (demo.mode in store.properties). The staff app signs in with a PIN only.',
    'Activado (demo.mode en store.properties). La app del personal inicia sesión solo con el PIN.',
    'Ein (demo.mode in store.properties). Die Personal-App meldet sich nur mit der PIN an.',
    'Aan (demo.mode in store.properties). Die personeel-app meld net met ’n PIN aan.',
    th: 'เปิด (demo.mode ใน store.properties) แอปพนักงานเข้าสู่ระบบด้วย PIN อย่างเดียว',
  );
  String get demoModeOff => _t(
    'Désactivé (demo.mode dans store.properties).',
    'Off (demo.mode in store.properties).',
    'Desactivado (demo.mode en store.properties).',
    'Aus (demo.mode in store.properties).',
    'Af (demo.mode in store.properties).',
    th: 'ปิด (demo.mode ใน store.properties)',
  );
  String get printDemoSheet => _t(
    'Imprimer la fiche QR de démo',
    'Print demo QR sheet',
    'Imprimir la hoja QR de demo',
    'Demo-QR-Blatt drucken',
    'Druk demo-QR-blad',
    th: 'พิมพ์แผ่น QR เดโม',
  );
  String get demoSheetPrinted => _t(
    'Fiche QR de démo envoyée à l’imprimante',
    'Demo QR sheet sent to the printer',
    'Hoja QR de demo enviada a la impresora',
    'Demo-QR-Blatt an den Drucker gesendet',
    'Demo-QR-blad na die drukker gestuur',
    th: 'ส่งแผ่น QR เดโมไปที่เครื่องพิมพ์แล้ว',
  );
  String get printAllTableQr => _t(
    'Imprimer les codes QR de toutes les tables',
    'Print all table QR codes',
    'Imprimir los códigos QR de todas las mesas',
    'Alle Tisch-QR-Codes drucken',
    'Druk alle tafel-QR-kodes',
    th: 'พิมพ์ QR code ทุกโต๊ะ',
  );
  String printAllTableQrConfirm(int n) => _t(
    'Imprimer les fiches QR des $n tables ? Cela prend beaucoup de papier.',
    'Print QR slips for all $n tables? This uses a lot of paper.',
    '¿Imprimir las hojas QR de las $n mesas? Esto usa mucho papel.',
    'QR-Zettel für alle $n Tische drucken? Das braucht viel Papier.',
    'Druk QR-strokies vir al $n tafels? Dit gebruik baie papier.',
    th: 'พิมพ์ใบ QR ของทั้ง $n โต๊ะไหม? จะใช้กระดาษเยอะ',
  );
  String slipsPrinted(int n) => _t(
    '$n fiches imprimées',
    'Printed $n slips',
    'Se imprimieron $n hojas',
    '$n Zettel gedruckt',
    '$n strokies gedruk',
    th: 'พิมพ์แล้ว $n ใบ',
  );

  // guest Wi-Fi (settings): the join slip and step 1 of the table slips
  String get sectionGuestWifi => _t(
    'Wi-Fi invités',
    'Guest Wi-Fi',
    'Wi-Fi para clientes',
    'Gäste-WLAN',
    'Gaste-Wi-Fi',
    th: 'Wi-Fi สำหรับลูกค้า',
  );
  String get guestWifiHint => _t(
    'Imprimé sur une fiche Wi-Fi et comme première étape des fiches QR des tables.',
    'Printed on a Wi-Fi slip and as step 1 on the table QR slips.',
    'Se imprime en una hoja de Wi-Fi y como paso 1 en las hojas QR de las mesas.',
    'Wird auf einem WLAN-Zettel und als Schritt 1 auf den Tisch-QR-Zetteln gedruckt.',
    'Gedruk op ’n Wi-Fi-strokie en as stap 1 op die tafel-QR-strokies.',
    th: 'พิมพ์ลงบนใบ Wi-Fi และเป็นขั้นตอนที่ 1 บนใบ QR ของโต๊ะ',
  );
  String get wifiSsidLabel => _t(
    'Nom du réseau (vide = aucun)',
    'Network name (blank = none)',
    'Nombre de la red (vacío = ninguna)',
    'Netzwerkname (leer = keins)',
    'Netwerknaam (leeg = geen)',
    th: 'ชื่อเครือข่าย (เว้นว่าง = ไม่มี)',
  );
  String get wifiPasswordLabel => _t(
    'Mot de passe',
    'Password',
    'Contraseña',
    'Passwort',
    'Wagwoord',
    th: 'รหัสผ่าน',
  );
  String get wifiShowPassword => _t(
    'Afficher le mot de passe',
    'Show password',
    'Mostrar contraseña',
    'Passwort anzeigen',
    'Wys wagwoord',
    th: 'แสดงรหัสผ่าน',
  );
  String get wifiHidePassword => _t(
    'Masquer le mot de passe',
    'Hide password',
    'Ocultar contraseña',
    'Passwort verbergen',
    'Versteek wagwoord',
    th: 'ซ่อนรหัสผ่าน',
  );
  String get wifiSecurityLabel => _t(
    'Sécurité',
    'Security',
    'Seguridad',
    'Sicherheit',
    'Sekuriteit',
    th: 'ความปลอดภัย',
  );
  String get wifiSecurityNone => _t(
    'Aucune (réseau ouvert)',
    'None (open)',
    'Ninguna (abierta)',
    'Keine (offen)',
    'Geen (oop)',
    th: 'ไม่มี (เปิด)',
  );
  String get wifiHiddenLabel => _t(
    'Réseau masqué',
    'Hidden network',
    'Red oculta',
    'Verborgenes Netzwerk',
    'Versteekte netwerk',
    th: 'เครือข่ายที่ซ่อนอยู่',
  );
  String get printWifiSlip => _t(
    'Imprimer la fiche Wi-Fi',
    'Print Wi-Fi slip',
    'Imprimir hoja de Wi-Fi',
    'WLAN-Zettel drucken',
    'Druk Wi-Fi-strokie',
    th: 'พิมพ์ใบ Wi-Fi',
  );
  String get wifiCopiesLabel =>
      _t('Exemplaires', 'Copies', 'Copias', 'Anzahl', 'Kopieë', th: 'จำนวนชุด');
  String wifiSlipsPrinted(int n) => n == 1
      ? _t(
          'Fiche Wi-Fi imprimée',
          'Wi-Fi slip printed',
          'Hoja de Wi-Fi impresa',
          'WLAN-Zettel gedruckt',
          'Wi-Fi-strokie gedruk',
          th: 'พิมพ์ใบ Wi-Fi แล้ว',
        )
      : _t(
          '$n fiches Wi-Fi imprimées',
          'Printed $n Wi-Fi slips',
          'Se imprimieron $n hojas de Wi-Fi',
          '$n WLAN-Zettel gedruckt',
          '$n Wi-Fi-strokies gedruk',
          th: 'พิมพ์ใบ Wi-Fi แล้ว $n ใบ',
        );
  String get wifiNotConfigured => _t(
    'Entrez d’abord le nom et le mot de passe du réseau Wi-Fi',
    'Set the Wi-Fi network name and password first',
    'Primero configura el nombre y la contraseña de la red Wi-Fi',
    'Bitte zuerst WLAN-Name und Passwort eingeben',
    'Stel eers die Wi-Fi-netwerknaam en wagwoord',
    th: 'ตั้งชื่อเครือข่าย Wi-Fi และรหัสผ่านก่อน',
  );
  String get saveFirstToTest => _t(
    'Enregistrez d’abord, puis lancez l’impression d’essai',
    'Save first, then test print',
    'Guarda primero y luego haz la impresión de prueba',
    'Erst speichern, dann Testdruck',
    'Stoor eers, dan toetsdruk',
    th: 'บันทึกก่อน แล้วจึงทดสอบพิมพ์',
  );
  String ordersWaiting(int n) => n == 1
      ? _t(
          '1 commande en attente',
          '1 order waiting',
          '1 pedido en espera',
          '1 Bestellung wartet',
          '1 bestelling wag',
          th: 'มี 1 ออร์เดอร์รออยู่',
        )
      : _t(
          '$n commandes en attente',
          '$n orders waiting',
          '$n pedidos en espera',
          '$n Bestellungen warten',
          '$n bestellings wag',
          th: 'มี $n ออร์เดอร์รออยู่',
        );

  /// Compact age for the alert banner: seconds under a minute, else minutes.
  String alertAge(Duration d) =>
      d.inMinutes < 1 ? '${d.inSeconds}s' : elapsedShort(d);

  // zone open/closed
  String get zoneClosed =>
      _t('Fermée', 'Closed', 'Cerrada', 'Geschlossen', 'Gesluit', th: 'ปิด');
  String get zoneClosedBanner => _t(
    'Cette section est temporairement fermée. Adressez-vous au personnel.',
    'This section is temporarily closed. Please ask staff.',
    'Esta sección está cerrada por el momento. Pregunta al personal.',
    'Dieser Bereich ist vorübergehend geschlossen. Bitte wenden Sie sich an das Personal.',
    'Hierdie afdeling is tydelik gesluit. Vra asseblief die personeel.',
    th: 'โซนนี้ปิดชั่วคราว กรุณาติดต่อพนักงาน',
  );
  String get zoneCloseAction => _t(
    'Fermer la zone',
    'Close zone',
    'Cerrar zona',
    'Bereich schließen',
    'Sluit area',
    th: 'ปิดโซน',
  );
  String get zoneReopenAction => _t(
    'Rouvrir la zone',
    'Reopen zone',
    'Reabrir zona',
    'Bereich öffnen',
    'Heropen area',
    th: 'เปิดโซนอีกครั้ง',
  );
  String get newCheckBlockedZoneClosed => _t(
    'Zone fermée',
    'Zone is closed',
    'La zona está cerrada',
    'Bereich ist geschlossen',
    'Area is gesluit',
    th: 'โซนปิดอยู่',
  );

  // zone (room) management — add / rename / delete / reorder
  String get addRoom => _t(
    'Ajouter une salle',
    'Add room',
    'Agregar salón',
    'Raum hinzufügen',
    'Voeg vertrek by',
    th: 'เพิ่มห้อง',
  );
  String get manageRoom => _t(
    'Gérer la salle',
    'Manage room',
    'Administrar salón',
    'Raum verwalten',
    'Bestuur vertrek',
    th: 'จัดการห้อง',
  );
  String get renameRoom => _t(
    'Renommer la salle',
    'Rename room',
    'Cambiar nombre del salón',
    'Raum umbenennen',
    'Hernoem vertrek',
    th: 'เปลี่ยนชื่อห้อง',
  );
  String get deleteRoom => _t(
    'Supprimer la salle',
    'Delete room',
    'Eliminar salón',
    'Raum löschen',
    'Verwyder vertrek',
    th: 'ลบห้อง',
  );
  String deleteRoomConfirm(String name) => _t(
    'Supprimer la salle « $name » ? Elle doit être vide (ni tables ni éléments) pour être supprimée.',
    'Delete room "$name"? A room must be empty (no tables or objects) to delete.',
    '¿Eliminar el salón "$name"? Debe estar vacío (sin mesas ni elementos) para eliminarlo.',
    'Raum „$name“ löschen? Nur ein leerer Raum (ohne Tische und Objekte) kann gelöscht werden.',
    'Verwyder vertrek “$name”? ’n Vertrek moet leeg wees (geen tafels of voorwerpe nie) om verwyder te word.',
    th: 'ลบห้อง "$name" ไหม? ต้องไม่มีโต๊ะหรือวัตถุในห้องจึงจะลบได้',
  );
  String get roomDeleted => _t(
    'Salle supprimée',
    'Room deleted',
    'Salón eliminado',
    'Raum gelöscht',
    'Vertrek verwyder',
    th: 'ลบห้องแล้ว',
  );
  String get roomCreated => _t(
    'Salle créée',
    'Room created',
    'Salón creado',
    'Raum angelegt',
    'Vertrek geskep',
    th: 'สร้างห้องแล้ว',
  );
  String get roomNameRequired => _t(
    'Entrez un nom pour la salle',
    'Enter a name for the room',
    'Escriba un nombre para el salón',
    'Bitte einen Namen für den Raum eingeben',
    'Voer ’n naam vir die vertrek in',
    th: 'กรุณาใส่ชื่อห้อง',
  );
  String get moveRoomLeft => _t(
    'Déplacer à gauche',
    'Move left',
    'Mover a la izquierda',
    'Nach links',
    'Skuif links',
    th: 'ย้ายไปทางซ้าย',
  );
  String get moveRoomRight => _t(
    'Déplacer à droite',
    'Move right',
    'Mover a la derecha',
    'Nach rechts',
    'Skuif regs',
    th: 'ย้ายไปทางขวา',
  );
  String get roomNameFrField => _thaiData
      ? _t(
          'Nom de la salle (thaï)',
          'Room name (Thai)',
          'Nombre del salón (tailandés)',
          'Raumname (Thailändisch)',
          'Vertreknaam (Thai)',
          th: 'ชื่อห้อง (ไทย)',
        )
      : _t(
          'Nom de la salle (français)',
          'Room name (French)',
          'Nombre del salón (francés)',
          'Raumname (Französisch)',
          'Vertreknaam (Frans)',
          th: 'ชื่อห้อง (ฝรั่งเศส)',
        );
  String get roomNameEnField => _t(
    'Nom de la salle (anglais)',
    'Room name (English)',
    'Nombre del salón (inglés)',
    'Raumname (Englisch)',
    'Vertreknaam (Engels)',
    th: 'ชื่อห้อง (อังกฤษ)',
  );

  // tables / time
  String elapsedShort(Duration d) => d.inHours > 0
      ? '${d.inHours}h ${(d.inMinutes % 60).toString().padLeft(2, '0')}m'
      : '${d.inMinutes}m';

  /// The server never answered at all within this device's own wait limit —
  /// a client-side [TimeoutException], not a coded server error.
  String get requestTimedOut => _t(
    'Ça prend trop de temps. Vérifiez la connexion et réessayez.',
    'This is taking too long. Check the connection and try again.',
    'Esto está tardando demasiado. Revisa la conexión e inténtalo de nuevo.',
    'Das dauert zu lange. Verbindung prüfen und erneut versuchen.',
    'Dit neem te lank. Gaan die verbinding na en probeer weer.',
    th: 'ใช้เวลานานเกินไป ตรวจสอบการเชื่อมต่อแล้วลองอีกครั้ง',
  );

  /// Server error codes → local language. Fallback: raw server message.
  String? apiError(String? code) => switch (code) {
    'invalid_pin' => _t(
      'NIP invalide',
      'Invalid PIN',
      'PIN no válido',
      'Ungültige PIN',
      'Ongeldige PIN',
      th: 'PIN ไม่ถูกต้อง',
    ),
    'image_unavailable' ||
    'image_offline' => aiUnavailableNote('image_offline'),
    'image_disabled' => aiUnavailableNote('image_generation_off'),
    'image_timeout' => _t(
      'Le service de photos IA a mis trop de temps. Réessayez.',
      'The AI photo service took too long. Please try again.',
      'El servicio de fotos con IA tardó demasiado. Inténtalo de nuevo.',
      'Der KI-Fotodienst hat zu lange gebraucht. Bitte erneut versuchen.',
      'Die KI-fotodiens het te lank geneem. Probeer asseblief weer.',
      th: 'บริการรูปภาพ AI ใช้เวลานานเกินไป กรุณาลองอีกครั้ง',
    ),
    'image_rate_limited' => _t(
      'Trop de demandes de photos IA. Réessayez dans une minute.',
      'Too many AI photo requests. Try again in a minute.',
      'Demasiadas solicitudes de fotos con IA. Inténtalo en un minuto.',
      'Zu viele KI-Fotoanfragen. In einer Minute erneut versuchen.',
      'Te veel KI-fotoversoeke. Probeer weer oor ’n minuut.',
      th: 'ขอรูปภาพ AI บ่อยเกินไป ลองอีกครั้งในอีกหนึ่งนาที',
    ),
    'image_quota' => _t(
      'Le compte de photos IA n’a plus de crédits.',
      'The AI photo account is out of credits.',
      'La cuenta de fotos con IA se quedó sin créditos.',
      'Das Guthaben des KI-Fotokontos ist aufgebraucht.',
      'Die KI-foto-rekening se krediete is op.',
      th: 'บัญชีรูปภาพ AI เครดิตหมดแล้ว',
    ),
    'image_daily_limit' => _t(
      'La limite quotidienne de photos IA de ce magasin est atteinte. Réessayez demain.',
      'This store’s daily AI photo limit is reached. Try again tomorrow.',
      'Se alcanzó el límite diario de fotos con IA de esta tienda. Inténtalo mañana.',
      'Das tägliche KI-Fotolimit dieses Geschäfts ist erreicht. Morgen erneut versuchen.',
      'Hierdie winkel se daaglikse KI-fotolimiet is bereik. Probeer môre weer.',
      th: 'ร้านนี้ใช้รูปภาพ AI ครบโควตารายวันแล้ว ลองอีกครั้งพรุ่งนี้',
    ),
    'image_refused' => _t(
      'Le service de photos IA a refusé cette demande. Modifiez la description et réessayez.',
      'The AI photo service declined this request. Adjust the description and try again.',
      'El servicio de fotos con IA rechazó la solicitud. Ajusta la descripción e inténtalo de nuevo.',
      'Der KI-Fotodienst hat die Anfrage abgelehnt. Beschreibung anpassen und erneut versuchen.',
      'Die KI-fotodiens het hierdie versoek geweier. Pas die beskrywing aan en probeer weer.',
      th: 'บริการรูปภาพ AI ปฏิเสธคำขอนี้ ปรับคำอธิบายแล้วลองอีกครั้ง',
    ),
    'image_auth' || 'image_error' => _t(
      'Le service de photos IA a renvoyé une erreur. Réessayez plus tard.',
      'The AI photo service returned an error. Try again later.',
      'El servicio de fotos con IA devolvió un error. Inténtalo más tarde.',
      'Der KI-Fotodienst meldet einen Fehler. Später erneut versuchen.',
      'Die KI-fotodiens het ’n fout teruggegee. Probeer later weer.',
      th: 'บริการรูปภาพ AI แจ้งข้อผิดพลาด ลองอีกครั้งภายหลัง',
    ),
    'menu_ai_unavailable' ||
    'menu_ai_offline' ||
    'menu_ai_disabled' => aiMenuUnavailableNote(code),
    'menu_ai_timeout' || 'menu_ai_rate_limited' => _t(
      'Le service IA est occupé ou lent. Réessayez dans une minute.',
      'The AI service is busy or slow. Try again in a minute.',
      'El servicio de IA está ocupado o lento. Inténtalo en un minuto.',
      'Der KI-Dienst ist ausgelastet oder langsam. In einer Minute erneut versuchen.',
      'Die KI-diens is besig of stadig. Probeer weer oor ’n minuut.',
      th: 'บริการ AI ไม่ว่างหรือช้า ลองอีกครั้งในอีกหนึ่งนาที',
    ),
    'menu_ai_too_many' => _t(
      'Trop de demandes IA (20 par 10 minutes). Réessayez dans quelques minutes.',
      'Too many AI requests (20 every 10 minutes). Try again in a few minutes.',
      'Demasiadas solicitudes de IA (20 cada 10 minutos). Inténtalo en unos minutos.',
      'Zu viele KI-Anfragen (20 pro 10 Minuten). In ein paar Minuten erneut versuchen.',
      'Te veel KI-versoeke (20 elke 10 minute). Probeer weer oor ’n paar minute.',
      th: 'ส่งคำขอ AI บ่อยเกินไป (20 ครั้งต่อ 10 นาที) ลองอีกครั้งในอีกไม่กี่นาที',
    ),
    'menu_ai_confirm_required' => _t(
      'Ce gros changement doit être confirmé. Touchez « Appliquer » de nouveau.',
      'This big change needs a confirm. Tap Apply again.',
      'Este cambio grande necesita confirmación. Toca Aplicar de nuevo.',
      'Diese große Änderung muss bestätigt werden. Erneut auf Übernehmen tippen.',
      'Hierdie groot verandering moet bevestig word. Tik weer op Pas toe.',
      th: 'การเปลี่ยนแปลงครั้งใหญ่นี้ต้องยืนยัน แตะ ใช้ อีกครั้ง',
    ),
    'menu_ai_quota' => _t(
      'Le compte IA n’a plus de crédits.',
      'The AI account is out of credits.',
      'La cuenta de IA se quedó sin créditos.',
      'Das Guthaben des KI-Kontos ist aufgebraucht.',
      'Die KI-rekening se krediete is op.',
      th: 'บัญชี AI เครดิตหมดแล้ว',
    ),
    'menu_ai_bad_reply' ||
    'menu_ai_refused' ||
    'menu_ai_auth' ||
    'menu_ai_error' => _t(
      'L’IA n’a pas pu proposer de changements. Reformulez et réessayez.',
      'The AI couldn’t propose changes. Rephrase and try again.',
      'La IA no pudo proponer cambios. Reformula e inténtalo de nuevo.',
      'Die KI konnte keine Änderungen vorschlagen. Anders formulieren und erneut versuchen.',
      'Die KI kon nie veranderinge voorstel nie. Herformuleer en probeer weer.',
      th: 'AI เสนอการเปลี่ยนแปลงไม่ได้ ลองพิมพ์ใหม่แล้วลองอีกครั้ง',
    ),
    'menu_ai_already_reverted' => aiMenuReverted,
    'wifi_not_configured' => wifiNotConfigured,
    'pin_in_use' => _t(
      'Ce NIP est déjà utilisé par un autre employé',
      'That PIN is already used by another staff member',
      'Ese PIN ya lo usa otro empleado',
      'Diese PIN wird bereits von einem anderen Mitarbeiter verwendet',
      'Daardie PIN word reeds deur ’n ander personeellid gebruik',
      th: 'PIN นี้มีพนักงานคนอื่นใช้อยู่แล้ว',
    ),
    'bad_pin' => _t(
      'Le NIP doit compter 4 chiffres',
      'PIN must be 4 digits',
      'El PIN debe tener 4 dígitos',
      'Die PIN muss 4 Ziffern haben',
      'PIN moet 4 syfers wees',
      th: 'PIN ต้องมี 4 หลัก',
    ),
    'last_manager' => _t(
      'Gardez au moins un gérant actif pour gérer le personnel',
      'Keep at least one active manager who can manage staff',
      'Debe quedar al menos un gerente activo que administre al personal',
      'Mindestens ein aktiver Manager mit Personalverwaltung muss bleiben',
      'Hou ten minste een aktiewe bestuurder wat personeel kan bestuur',
      th: 'ต้องมีผู้จัดการที่ใช้งานอยู่อย่างน้อยหนึ่งคนเพื่อจัดการพนักงาน',
    ),
    'login_required' => _t(
      'Veuillez vous reconnecter',
      'Please log in again',
      'Inicia sesión de nuevo',
      'Bitte erneut anmelden',
      'Meld asseblief weer aan',
      th: 'กรุณาเข้าสู่ระบบอีกครั้ง',
    ),
    'manager_approval_required' => _t(
      'Approbation du gérant requise',
      'Manager approval required',
      'Se requiere la aprobación del gerente',
      'Freigabe durch Manager erforderlich',
      'Bestuurder se goedkeuring nodig',
      th: 'ต้องได้รับอนุมัติจากผู้จัดการ',
    ),
    'manager_pos_only' => _t(
      'En mode démo, le gérant se connecte seulement sur la tablette du PDV',
      'In demo mode, managers sign in only on the POS tablet',
      'En modo demo, el gerente inicia sesión solo en la tableta del TPV',
      'Im Demo-Modus meldet sich die Leitung nur am Kassen-Tablet an',
      'In demo-modus meld die bestuurder net op die kassa-tablet aan',
      th: 'ในโหมดเดโม ผู้จัดการเข้าสู่ระบบได้เฉพาะบนแท็บเล็ต POS',
    ),
    'pos_terminal_required' => _t(
      'Seulement sur la caisse (PDV)',
      'Only on the POS terminal',
      'Solo en el terminal TPV',
      'Nur an der Kasse',
      'Net op die kassa-terminaal',
      th: 'ใช้ได้เฉพาะบนเครื่อง POS',
    ),
    'check_not_open' => _t(
      'Cette addition n’est plus ouverte',
      'This bill is no longer open',
      'Esta cuenta ya no está abierta',
      'Diese Rechnung ist nicht mehr offen',
      'Hierdie rekening is nie meer oop nie',
      th: 'บิลนี้ปิดไปแล้ว',
    ),
    'zone_closed' => _t(
      'Zone fermée',
      'Zone is closed',
      'La zona está cerrada',
      'Bereich ist geschlossen',
      'Area is gesluit',
      th: 'โซนปิดอยู่',
    ),
    'bill_locked' => _t(
      'Paiement de l’addition en cours — adressez-vous au personnel',
      'Bill is being paid — ask staff',
      'La cuenta se está pagando: pregunta al personal',
      'Rechnung wird gerade bezahlt – bitte Personal fragen',
      'Rekening word betaal — vra die personeel',
      th: 'กำลังชำระบิลนี้อยู่ — กรุณาติดต่อพนักงาน',
    ),
    'already_paid' => _t(
      'Addition déjà entièrement payée',
      'Bill already fully paid',
      'La cuenta ya está pagada por completo',
      'Rechnung ist bereits vollständig bezahlt',
      'Rekening is reeds ten volle betaal',
      th: 'บิลนี้ชำระครบแล้ว',
    ),
    'pending_lines_unresolved' => _t(
      'Des commandes clients attendent une confirmation — réglez-les avant le paiement',
      'Customer orders awaiting confirm — resolve before payment',
      'Hay pedidos de clientes por confirmar: resuélvelos antes de cobrar',
      'Gästebestellungen warten auf Bestätigung – vor dem Bezahlen erledigen',
      'Klantbestellings wag op bevestiging — handel dit af voor betaling',
      th: 'มีออร์เดอร์ของลูกค้ารอยืนยัน — จัดการก่อนชำระเงิน',
    ),
    'outstanding_balance' => _t(
      'Il reste un solde à payer',
      'Balance still outstanding',
      'Todavía queda saldo pendiente',
      'Es ist noch ein Betrag offen',
      'Saldo is nog uitstaande',
      th: 'ยังมียอดค้างชำระ',
    ),
    'void_has_tenders' => _t(
      'L’addition a des paiements : annulez-la pour les rendre d’abord',
      'Bill has payments; void it to hand them back first',
      'La cuenta tiene pagos; anúlala para devolver primero los pagos',
      'Rechnung hat bereits Zahlungen; stornieren, um sie zuerst zurückzugeben',
      'Rekening het betalings; kanselleer dit om hulle eers terug te gee',
      th: 'บิลมีการชำระเงินแล้ว ยกเลิกบิลเพื่อคืนเงินก่อน',
    ),
    'refund_not_closed' => _t(
      'Seule une addition fermée peut être remboursée',
      'Only a closed bill can be refunded',
      'Solo se puede reembolsar una cuenta cerrada',
      'Nur eine geschlossene Rechnung kann erstattet werden',
      'Slegs ’n geslote rekening kan terugbetaal word',
      th: 'คืนเงินได้เฉพาะบิลที่ปิดแล้ว',
    ),
    'refund_exceeds_total' => _t(
      'Le remboursement dépasse le montant encore remboursable',
      'Refund exceeds the remaining refundable amount',
      'El reembolso supera el monto que queda por reembolsar',
      'Erstattung übersteigt den noch erstattbaren Betrag',
      'Terugbetaling oorskry die oorblywende terugbetaalbare bedrag',
      th: 'ยอดคืนเงินเกินยอดที่คืนได้',
    ),
    'refund_non_positive' => _t(
      'Le montant du remboursement doit être supérieur à zéro',
      'Refund amount must be positive',
      'El monto del reembolso debe ser mayor que cero',
      'Erstattungsbetrag muss positiv sein',
      'Terugbetalingsbedrag moet positief wees',
      th: 'ยอดคืนเงินต้องมากกว่าศูนย์',
    ),
    'refund_no_amount' => _t(
      'Entrez un montant ou choisissez des articles',
      'Enter an amount or pick items',
      'Ingresa un monto o elige artículos',
      'Betrag eingeben oder Artikel auswählen',
      'Voer ’n bedrag in of kies items',
      th: 'ใส่จำนวนเงินหรือเลือกรายการ',
    ),
    'refund_bad_tender' => _t(
      'Mode de remboursement invalide',
      'Invalid refund tender',
      'Forma de reembolso no válida',
      'Ungültige Erstattungsart',
      'Ongeldige terugbetalingsmetode',
      th: 'วิธีคืนเงินไม่ถูกต้อง',
    ),
    'refund_bad_line' => _t(
      'L’article choisi n’est pas sur cette addition',
      'Selected item is not on this bill',
      'El artículo elegido no está en esta cuenta',
      'Der gewählte Artikel steht nicht auf dieser Rechnung',
      'Gekose item is nie op hierdie rekening nie',
      th: 'รายการที่เลือกไม่อยู่ในบิลนี้',
    ),
    'refund_qty_too_high' => _t(
      'La quantité dépasse celle de l’addition',
      'Refund quantity exceeds the bill',
      'La cantidad supera la de la cuenta',
      'Erstattungsmenge übersteigt die Rechnung',
      'Terugbetalingshoeveelheid oorskry die rekening',
      th: 'จำนวนที่คืนเกินจำนวนในบิล',
    ),
    'refund_line_already_refunded' => _t(
      'Cet article a déjà été remboursé',
      'This item was already refunded',
      'Este artículo ya se reembolsó',
      'Dieser Artikel wurde bereits erstattet',
      'Hierdie item is reeds terugbetaal',
      th: 'รายการนี้คืนเงินไปแล้ว',
    ),
    'refund_tender_mismatch' => _t(
      'Remboursez comme le client a payé (carte sur la carte, comptant en comptant) — sinon un gérant doit approuver',
      'Refund the way the guest paid (card to card, cash to cash) — otherwise a manager must approve',
      'Reembolsa como pagó el cliente (tarjeta a tarjeta, efectivo en efectivo); si no, debe aprobarlo un gerente',
      'So erstatten, wie der Gast bezahlt hat (Karte auf Karte, bar in bar) – sonst muss ein Manager freigeben',
      'Betaal terug soos die gas betaal het (kaart na kaart, kontant in kontant) — anders moet ’n bestuurder goedkeur',
      th: 'คืนเงินตามวิธีที่ลูกค้าชำระ (บัตรคืนเข้าบัตร เงินสดคืนเป็นเงินสด) — ไม่เช่นนั้นต้องให้ผู้จัดการอนุมัติ',
    ),
    'qty_out_of_range' => _t(
      'Quantité trop élevée',
      'Quantity is too high',
      'La cantidad es demasiado alta',
      'Menge ist zu hoch',
      'Hoeveelheid is te hoog',
      th: 'จำนวนมากเกินไป',
    ),
    'price_too_high' => _t(
      'Prix trop élevé (maximum ${money(maxUnitPriceCents)} par unité)',
      'Price is too high (at most ${money(maxUnitPriceCents)} each)',
      'El precio es demasiado alto (máximo ${money(maxUnitPriceCents)} por unidad)',
      'Preis ist zu hoch (höchstens ${money(maxUnitPriceCents)} pro Stück)',
      'Prys is te hoog (hoogstens ${money(maxUnitPriceCents)} elk)',
      th: 'ราคาสูงเกินไป (สูงสุด ${money(maxUnitPriceCents)} ต่อชิ้น)',
    ),
    'price_non_positive' => _t(
      'Le prix doit être supérieur à zéro',
      'Price must be more than zero',
      'El precio debe ser mayor que cero',
      'Preis muss größer als null sein',
      'Prys moet meer as nul wees',
      th: 'ราคาต้องมากกว่าศูนย์',
    ),
    'cash_amount_too_high' => _t(
      'Montant trop élevé — vérifiez le comptant reçu',
      'Amount is too high — check the cash received',
      'El monto es demasiado alto: revisa el efectivo recibido',
      'Betrag ist zu hoch – erhaltenes Bargeld prüfen',
      'Bedrag is te hoog — kyk na die kontant ontvang',
      th: 'จำนวนเงินสูงเกินไป — ตรวจสอบเงินสดที่รับมา',
    ),
    'too_many_lines' => _t(
      'Trop d’articles dans une seule commande',
      'Too many items in one order',
      'Demasiados artículos en un solo pedido',
      'Zu viele Artikel in einer Bestellung',
      'Te veel items in een bestelling',
      th: 'รายการในออร์เดอร์เดียวมากเกินไป',
    ),
    'too_many_pending' => _t(
      'Trop de commandes clients en attente — confirmez-les ou refusez-les',
      'Too many customer orders waiting — accept or reject them',
      'Demasiados pedidos de clientes en espera: acéptalos o recházalos',
      'Zu viele Gästebestellungen offen – annehmen oder ablehnen',
      'Te veel klantbestellings wag — aanvaar of weier hulle',
      th: 'มีออร์เดอร์ของลูกค้ารออยู่มากเกินไป — รับหรือปฏิเสธก่อน',
    ),
    'shift_has_paid_open_bills' => _t(
      'Des additions encore ouvertes ont déjà des paiements — terminez-les ou annulez-les avant de fermer le quart',
      'Some open bills already have payments — finish or cancel them before closing the shift',
      'Hay cuentas abiertas que ya tienen pagos: termínalas o anúlalas antes de cerrar el turno',
      'Offene Rechnungen haben bereits Zahlungen – vor dem Schichtabschluss abschließen oder stornieren',
      'Sommige oop rekenings het reeds betalings — voltooi of kanselleer hulle voor die skof gesluit word',
      th: 'บิลที่ยังเปิดอยู่บางใบมีการชำระเงินแล้ว — ปิดหรือยกเลิกก่อนปิดกะ',
    ),
    'void_card_on_reader' => _t(
      'Un paiement par carte au terminal ne peut pas être rendu ici — terminez l’addition puis remboursez la carte',
      'A card paid on the reader can’t be handed back here — finish the bill, then refund the card',
      'Un pago con tarjeta en el lector no se puede devolver aquí: termina la cuenta y luego reembolsa la tarjeta',
      'Eine Kartenzahlung am Terminal kann hier nicht zurückgegeben werden – Rechnung abschließen, dann Karte erstatten',
      '’n Kaartbetaling op die leser kan nie hier teruggegee word nie — voltooi die rekening en betaal dan die kaart terug',
      th: 'การชำระด้วยบัตรผ่านเครื่องรูดบัตรคืนที่นี่ไม่ได้ — ปิดบิลก่อน แล้วคืนเงินเข้าบัตร',
    ),
    'cash_bad_direction' => _t(
      'Sens invalide',
      'Invalid direction',
      'Dirección no válida',
      'Ungültige Richtung',
      'Ongeldige rigting',
      th: 'ทิศทางไม่ถูกต้อง',
    ),
    'cash_non_positive' => _t(
      'Le montant doit être supérieur à zéro',
      'Amount must be positive',
      'El monto debe ser mayor que cero',
      'Betrag muss positiv sein',
      'Bedrag moet positief wees',
      th: 'จำนวนเงินต้องมากกว่าศูนย์',
    ),
    'no_open_shift' => _t(
      'Aucun quart ouvert',
      'No shift open',
      'No hay turno abierto',
      'Keine Schicht geöffnet',
      'Geen skof oop nie',
      th: 'ยังไม่ได้เปิดกะ',
    ),
    'shift_already_open' => _t(
      'Un quart est déjà ouvert',
      'A shift is already open',
      'Ya hay un turno abierto',
      'Es ist bereits eine Schicht geöffnet',
      '’n Skof is reeds oop',
      th: 'เปิดกะอยู่แล้ว',
    ),
    'tender_type_not_accepted' => _t(
      'Mode de paiement non accepté',
      'Payment method not accepted',
      'No se acepta esta forma de pago',
      'Zahlungsart nicht akzeptiert',
      'Betaalmetode word nie aanvaar nie',
      th: 'ไม่รับวิธีชำระเงินนี้',
    ),
    'no_receipt_yet' => _t(
      'Pas encore de reçu',
      'No receipt yet',
      'Todavía no hay recibo',
      'Noch kein Beleg',
      'Nog geen kwitansie nie',
      th: 'ยังไม่มีใบเสร็จ',
    ),
    'check_not_billable' => _t(
      'Cette addition est fermée — impression impossible',
      'This bill is closed — cannot print',
      'Esta cuenta está cerrada: no se puede imprimir',
      'Diese Rechnung ist geschlossen – Druck nicht möglich',
      'Hierdie rekening is gesluit — kan nie druk nie',
      th: 'บิลนี้ปิดแล้ว — พิมพ์ไม่ได้',
    ),
    'bad_pairing_code' => _t(
      'Code de jumelage invalide ou expiré',
      'Invalid or expired pairing code',
      'Código de vinculación no válido o vencido',
      'Kopplungscode ungültig oder abgelaufen',
      'Ongeldige of verstreke koppelkode',
      th: 'รหัสจับคู่ไม่ถูกต้องหรือหมดอายุ',
    ),
    'pairing_unavailable' => _t(
      'Jumelage temporairement indisponible — réessayez',
      'Pairing is temporarily unavailable — try again',
      'La vinculación no está disponible por ahora: inténtalo de nuevo',
      'Kopplung vorübergehend nicht verfügbar – erneut versuchen',
      'Koppeling is tydelik onbeskikbaar — probeer weer',
      th: 'จับคู่อุปกรณ์ไม่ได้ชั่วคราว — ลองอีกครั้ง',
    ),
    'cloud_unreachable' => _t(
      'L’établissement ne joint pas le nuage — réessayez sous peu',
      'The store cannot reach the cloud — try again shortly',
      'La tienda no puede conectarse con la nube: inténtalo en un momento',
      'Die Filiale erreicht die Cloud nicht – gleich erneut versuchen',
      'Die winkel kan nie die wolk bereik nie — probeer binnekort weer',
      th: 'ร้านเชื่อมต่อคลาวด์ไม่ได้ — ลองอีกครั้งในไม่ช้า',
    ),
    'device_required' => _t(
      'Ce terminal n’est pas jumelé à l’établissement',
      'This terminal is not paired with the store',
      'Esta terminal no está vinculada con la tienda',
      'Dieses Gerät ist nicht mit der Filiale gekoppelt',
      'Hierdie terminaal is nie met die winkel gekoppel nie',
      th: 'เครื่องนี้ยังไม่ได้จับคู่กับร้าน',
    ),
    'device_revoked' => _t(
      'Le jumelage de ce terminal a été révoqué — jumelez-le de nouveau',
      'This terminal\'s pairing was revoked — pair again',
      'Se revocó la vinculación de esta terminal: vincúlala otra vez',
      'Die Kopplung dieses Geräts wurde widerrufen – bitte neu koppeln',
      'Hierdie terminaal se koppeling is herroep — koppel weer',
      th: 'การจับคู่ของเครื่องนี้ถูกยกเลิก — จับคู่ใหม่',
    ),
    'not_found' => _t(
      'Introuvable',
      'Not found',
      'No se encontró',
      'Nicht gefunden',
      'Nie gevind nie',
      th: 'ไม่พบข้อมูล',
    ),
    'bad_request' || 'bad_body' => _t(
      'Demande invalide',
      'Invalid request',
      'Solicitud no válida',
      'Ungültige Anfrage',
      'Ongeldige versoek',
      th: 'คำขอไม่ถูกต้อง',
    ),
    'kiosk_not_found' => _t(
      'Cette borne n’est plus jumelée',
      'This kiosk is no longer paired',
      'Este quiosco ya no está vinculado',
      'Dieses Bestellterminal ist nicht mehr gekoppelt',
      'Hierdie kiosk is nie meer gekoppel nie',
      th: 'ตู้สั่งอาหารนี้ไม่ได้จับคู่แล้ว',
    ),
    'rate_limited' => _t(
      'Trop de tentatives échouées — réessayez plus tard',
      'Too many failed attempts — try again later',
      'Demasiados intentos fallidos: inténtalo más tarde',
      'Zu viele Fehlversuche – später erneut versuchen',
      'Te veel mislukte pogings — probeer later weer',
      th: 'ลองผิดหลายครั้งเกินไป — ลองอีกครั้งภายหลัง',
    ),
    'variant_in_use' => _t(
      'Ce format est sur une addition ouverte — fermez-la ou annulez-la d’abord',
      'This size is on an open order — close or void that check first',
      'Este tamaño está en una cuenta abierta: ciérrala o anúlala primero',
      'Diese Größe steht auf einer offenen Bestellung – Rechnung erst schließen oder stornieren',
      'Hierdie grootte is op ’n oop bestelling — sluit of kanselleer daardie rekening eers',
      th: 'ขนาดนี้อยู่ในออร์เดอร์ที่เปิดอยู่ — ปิดหรือยกเลิกบิลนั้นก่อน',
    ),
    'item_in_use' => _t(
      'Cet article est sur une addition ouverte — fermez-la ou annulez-la d’abord',
      'This item is on an open order — close or void that check first',
      'Este artículo está en una cuenta abierta: ciérrala o anúlala primero',
      'Dieser Artikel steht auf einer offenen Bestellung – Rechnung erst schließen oder stornieren',
      'Hierdie item is op ’n oop bestelling — sluit of kanselleer daardie rekening eers',
      th: 'รายการนี้อยู่ในออร์เดอร์ที่เปิดอยู่ — ปิดหรือยกเลิกบิลนั้นก่อน',
    ),
    'last_variant' => _t(
      'C’est le seul format de l’article — supprimez plutôt l’article au complet',
      'This is the item\'s only size — delete the whole item instead',
      'Es el único tamaño del artículo: mejor elimina el artículo completo',
      'Das ist die einzige Größe des Artikels – stattdessen den ganzen Artikel löschen',
      'Dit is die item se enigste grootte — verwyder eerder die hele item',
      th: 'นี่เป็นขนาดเดียวของรายการนี้ — ให้ลบทั้งรายการแทน',
    ),
    'category_not_empty' => _t(
      'Cette catégorie contient encore des articles — déplacez-les ou retirez-les d’abord',
      'This category still has items — move or remove them first',
      'Esta categoría todavía tiene artículos: muévelos o quítalos primero',
      'Diese Kategorie enthält noch Artikel – erst verschieben oder entfernen',
      'Hierdie kategorie het nog items — skuif of verwyder hulle eers',
      th: 'หมวดหมู่นี้ยังมีรายการอยู่ — ย้ายหรือลบออกก่อน',
    ),
    'split_locked' => _t(
      'Un paiement a déjà été reçu — la séparation est verrouillée',
      'Money already taken — split is locked',
      'Ya se recibió un pago: la división está bloqueada',
      'Es wurde bereits kassiert – Teilung ist gesperrt',
      'Geld is reeds ontvang — verdeling is gesluit',
      th: 'รับเงินไปแล้ว — แยกบิลไม่ได้',
    ),
    'split_exists' => _t(
      'L’addition est déjà séparée',
      'Bill is already split',
      'La cuenta ya está dividida',
      'Rechnung ist bereits geteilt',
      'Rekening is reeds verdeel',
      th: 'บิลแยกไว้แล้ว',
    ),
    'no_split' => _t(
      'L’addition n’est pas séparée',
      'Bill is not split',
      'La cuenta no está dividida',
      'Rechnung ist nicht geteilt',
      'Rekening is nie verdeel nie',
      th: 'บิลยังไม่ได้แยก',
    ),
    'split_stale' => _t(
      'L’addition a changé depuis la séparation en parts égales — séparez-la de nouveau',
      'The bill changed since it was split evenly — split it again',
      'La cuenta cambió desde que se dividió en partes iguales: divídela otra vez',
      'Die Rechnung hat sich seit dem gleichmäßigen Teilen geändert – bitte neu teilen',
      'Die rekening het verander sedert dit gelyk verdeel is — verdeel dit weer',
      th: 'บิลมีการเปลี่ยนแปลงหลังแยกเท่ากัน — แยกใหม่อีกครั้ง',
    ),
    'split_unassigned_lines' => _t(
      'Des articles ne sont pas encore attribués — attribuez-les tous avant le paiement',
      'Some items are still unassigned — assign everything before payment',
      'Todavía hay artículos sin asignar: asígnalos todos antes de cobrar',
      'Einige Artikel sind noch nicht zugeordnet – vor dem Bezahlen alles zuordnen',
      'Sommige items is nog nie toegeken nie — ken alles toe voor betaling',
      th: 'ยังมีรายการที่ไม่ได้กำหนดบิล — กำหนดให้ครบก่อนชำระเงิน',
    ),
    'group_required' => _t(
      'L’addition est séparée — choisissez l’addition à payer',
      'Bill is split — pay a specific bill',
      'La cuenta está dividida: elige qué cuenta pagar',
      'Rechnung ist geteilt – eine bestimmte Teilrechnung bezahlen',
      'Rekening is verdeel — betaal ’n spesifieke rekening',
      th: 'บิลถูกแยกแล้ว — เลือกบิลที่จะชำระ',
    ),
    'group_already_paid' => _t(
      'Cette addition est déjà payée',
      'This bill is already paid',
      'Esta cuenta ya está pagada',
      'Diese Rechnung ist bereits bezahlt',
      'Hierdie rekening is reeds betaal',
      th: 'บิลนี้ชำระแล้ว',
    ),
    'group_not_found' => _t(
      'Cette addition séparée n’existe plus — actualisez la séparation',
      'That split bill no longer exists — refresh the split',
      'Esa cuenta dividida ya no existe: actualiza la división',
      'Diese Teilrechnung gibt es nicht mehr – Teilung aktualisieren',
      'Daardie verdeelde rekening bestaan nie meer nie — verfris die verdeling',
      th: 'บิลที่แยกไว้นี้ไม่มีแล้ว — รีเฟรชการแยกบิล',
    ),
    'group_outstanding' => _t(
      'Certaines additions ne sont pas encore payées',
      'Some bills still owe',
      'Algunas cuentas todavía tienen saldo',
      'Einige Rechnungen sind noch offen',
      'Sommige rekeninge is nog uitstaande',
      th: 'ยังมีบางบิลค้างชำระ',
    ),
    'qty_below_allocated' => _t(
      'L’article est attribué à une addition séparée — retirez-le de celle-ci d’abord',
      'Item is assigned to a split bill — unassign it first',
      'El artículo está asignado a una cuenta dividida: quítalo de ahí primero',
      'Artikel ist einer Teilrechnung zugeordnet – Zuordnung erst aufheben',
      'Item is aan ’n verdeelde rekening toegeken — hef die toekenning eers op',
      th: 'รายการนี้อยู่ในบิลที่แยกไว้ — นำออกจากบิลนั้นก่อน',
    ),
    'qty_exceeds_unassigned' => _t(
      'Plus que la quantité non attribuée',
      'More than the unassigned quantity',
      'Más que la cantidad sin asignar',
      'Mehr als die nicht zugeordnete Menge',
      'Meer as die nie-toegekende hoeveelheid',
      th: 'มากกว่าจำนวนที่ยังไม่ได้กำหนด',
    ),
    'qty_exceeds_allocated' => _t(
      'Plus que la quantité attribuée',
      'More than the assigned quantity',
      'Más que la cantidad asignada',
      'Mehr als die zugeordnete Menge',
      'Meer as die toegekende hoeveelheid',
      th: 'มากกว่าจำนวนที่กำหนดไว้',
    ),
    'even_split' => _t(
      'L’addition est séparée en parts égales (÷N) — les articles ne peuvent pas être déplacés',
      'Split is even ÷N — items cannot move',
      'La cuenta está dividida en partes iguales (÷N): no se pueden mover artículos',
      'Gleichmäßig geteilt ÷N – Artikel können nicht verschoben werden',
      'Gelyk verdeel ÷N — items kan nie skuif nie',
      th: 'แยกบิลเท่ากัน ÷N — ย้ายรายการไม่ได้',
    ),
    'empty_check' => _t(
      'Rien à séparer',
      'Nothing to split',
      'No hay nada que dividir',
      'Nichts zu teilen',
      'Niks om te verdeel nie',
      th: 'ไม่มีอะไรให้แยก',
    ),
    'last_group' => _t(
      'Il doit rester au moins une addition',
      'At least one bill must remain',
      'Debe quedar al menos una cuenta',
      'Mindestens eine Rechnung muss bleiben',
      'Minstens een rekening moet oorbly',
      th: 'ต้องเหลืออย่างน้อยหนึ่งบิล',
    ),
    'same_table' => _t(
      'L’addition est déjà à cette table',
      'Bill is already on this table',
      'La cuenta ya está en esta mesa',
      'Rechnung ist bereits an diesem Tisch',
      'Rekening is reeds by hierdie tafel',
      th: 'บิลอยู่ที่โต๊ะนี้แล้ว',
    ),
    'same_check' => _t(
      'Impossible de fusionner une addition avec elle-même',
      'Cannot merge a bill into itself',
      'No se puede combinar una cuenta consigo misma',
      'Eine Rechnung kann nicht mit sich selbst zusammengeführt werden',
      'Kan nie ’n rekening met homself saamvoeg nie',
      th: 'รวมบิลเข้ากับตัวเองไม่ได้',
    ),
    'table_occupied' => _t(
      'La table a une addition ouverte — fusionnez plutôt',
      'Table has an open bill — merge instead',
      'La mesa tiene una cuenta abierta: mejor combínalas',
      'Tisch hat eine offene Rechnung – stattdessen zusammenführen',
      'Tafel het ’n oop rekening — voeg eerder saam',
      th: 'โต๊ะนี้มีบิลเปิดอยู่ — ให้รวมบิลแทน',
    ),
    'table_in_use' => _t(
      'La table a une addition ouverte — fermez-la ou déplacez-la avant de retirer la table',
      'Table has an open bill — close or move it before removing',
      'La mesa tiene una cuenta abierta: ciérrala o muévela antes de quitar la mesa',
      'Tisch hat eine offene Rechnung – vor dem Entfernen schließen oder verschieben',
      'Tafel het ’n oop rekening — sluit of skuif dit voor verwydering',
      th: 'โต๊ะนี้มีบิลเปิดอยู่ — ปิดหรือย้ายบิลก่อนลบโต๊ะ',
    ),
    'has_sub_tables' => _t(
      'La table a des sous-tables — retirez-les d’abord',
      'Table has sub-tables — remove them first',
      'La mesa tiene submesas: quítalas primero',
      'Tisch hat Untertische – diese zuerst entfernen',
      'Tafel het subtafels — verwyder hulle eers',
      th: 'โต๊ะนี้มีโต๊ะย่อย — ลบโต๊ะย่อยก่อน',
    ),
    'label_taken' => _t(
      'Une table porte déjà ce nom dans cette zone',
      'A table with this label already exists in this zone',
      'Ya existe una mesa con ese nombre en esta zona',
      'In diesem Bereich gibt es bereits einen Tisch mit dieser Bezeichnung',
      '’n Tafel met hierdie etiket bestaan reeds in hierdie area',
      th: 'มีโต๊ะชื่อนี้ในโซนนี้แล้ว',
    ),
    'table_not_in_zone' => _t(
      'Le plan ne correspond pas à cette zone — actualisez et réessayez',
      'Layout does not match this zone — refresh and retry',
      'El plano no coincide con esta zona: actualiza e inténtalo de nuevo',
      'Tischplan passt nicht zu diesem Bereich – aktualisieren und erneut versuchen',
      'Vloerplan pas nie by hierdie area nie — verfris en probeer weer',
      th: 'ผังไม่ตรงกับโซนนี้ — รีเฟรชแล้วลองอีกครั้ง',
    ),
    'zone_not_empty' => _t(
      'Cette salle contient encore des tables ou des éléments — videz-la avant de la supprimer',
      'This room still has tables or objects — clear them before deleting it',
      'Este salón todavía tiene mesas o elementos: vacíalo antes de eliminarlo',
      'Dieser Raum hat noch Tische oder Objekte – vor dem Löschen entfernen',
      'Hierdie vertrek het nog tafels of voorwerpe — verwyder hulle voordat jy dit uitvee',
      th: 'ห้องนี้ยังมีโต๊ะหรือวัตถุอยู่ — นำออกให้หมดก่อนลบ',
    ),
    'clear_split_first' => _t(
      'L’addition est séparée — annulez d’abord la séparation',
      'Bill is split — clear the split first',
      'La cuenta está dividida: deshaz la división primero',
      'Rechnung ist geteilt – Teilung zuerst aufheben',
      'Rekening is verdeel — hef eers die verdeling op',
      th: 'บิลถูกแยกแล้ว — ยกเลิกการแยกบิลก่อน',
    ),
    'conflict' => _t(
      'Action impossible pour le moment',
      'Cannot do that right now',
      'No se puede hacer eso ahora',
      'Das ist gerade nicht möglich',
      'Kan dit nie nou doen nie',
      th: 'ทำรายการนี้ไม่ได้ในขณะนี้',
    ),
    'internal' => _t(
      'Une erreur est survenue — réessayez',
      'Something went wrong — try again',
      'Algo salió mal: inténtalo de nuevo',
      'Etwas ist schiefgelaufen – erneut versuchen',
      'Iets het fout gegaan — probeer weer',
      th: 'เกิดข้อผิดพลาด — ลองอีกครั้ง',
    ),
    'stripe_unavailable' => _t(
      'Stripe est injoignable (Internet ?). Rien n’a été fait chez Stripe.',
      'Can\'t reach Stripe (internet?). Nothing was done at Stripe.',
      'No se puede conectar con Stripe (¿internet?). No se hizo nada en Stripe.',
      'Stripe ist nicht erreichbar (Internet?). Bei Stripe wurde nichts ausgeführt.',
      'Kan Stripe nie bereik nie (internet?). Niks is by Stripe gedoen nie.',
      th: 'เชื่อมต่อ Stripe ไม่ได้ (อินเทอร์เน็ต?) ยังไม่มีการดำเนินการใดที่ Stripe',
    ),
    'stripe_declined' => stripeDeclineMessage(null),
    'stripe_not_configured' => _t(
      'La carte (Stripe) n’est pas configurée dans cet établissement',
      'Card (Stripe) is not set up on this store',
      'La tarjeta (Stripe) no está configurada en esta tienda',
      'Karte (Stripe) ist in dieser Filiale nicht eingerichtet',
      'Kaart (Stripe) is nie in hierdie winkel opgestel nie',
      th: 'ร้านนี้ยังไม่ได้ตั้งค่าบัตร (Stripe)',
    ),
    'stripe_live_key_refused' => stripeUnavailableHint(
      'stripe_live_key_refused',
    ),
    'stripe_location_required' => stripeUnavailableHint(
      'stripe_location_required',
    ),
    'stripe_currency_mismatch' => stripeUnavailableHint(
      'stripe_currency_mismatch',
    ),
    'stripe_error' => _t(
      'Stripe a refusé la demande. Rien n’a été débité.',
      'Stripe refused the request. Nothing was charged.',
      'Stripe rechazó la solicitud. No se cobró nada.',
      'Stripe hat die Anfrage abgelehnt. Es wurde nichts belastet.',
      'Stripe het die versoek geweier. Niks is gehef nie.',
      th: 'Stripe ปฏิเสธคำขอ ยังไม่มีการตัดเงิน',
    ),
    'stripe_amount_exceeds_due' => _t(
      'L’addition ne doit plus ce montant — la carte n’a pas été débitée',
      'The bill no longer owes that amount — the card was not charged',
      'La cuenta ya no debe ese monto: no se cobró a la tarjeta',
      'Die Rechnung weist diesen Betrag nicht mehr aus – die Karte wurde nicht belastet',
      'Die rekening skuld nie meer daardie bedrag nie — die kaart is nie gehef nie',
      th: 'บิลไม่ได้ค้างยอดนั้นแล้ว — ยังไม่ได้ตัดเงินจากบัตร',
    ),
    'stripe_payment_reversed' => _t(
      'Le paiement par carte n’a pas pu être appliqué à cette addition et a été remboursé',
      'The card payment could not be applied to this bill and was refunded',
      'El pago con tarjeta no se pudo aplicar a esta cuenta y se reembolsó',
      'Die Kartenzahlung konnte nicht auf diese Rechnung gebucht werden und wurde erstattet',
      'Die kaartbetaling kon nie op hierdie rekening toegepas word nie en is terugbetaal',
      th: 'ใช้การชำระด้วยบัตรกับบิลนี้ไม่ได้ และได้คืนเงินแล้ว',
    ),
    'stripe_payment_canceled' => _t(
      'Ce paiement par carte a été annulé',
      'That card payment was canceled',
      'Se canceló ese pago con tarjeta',
      'Diese Kartenzahlung wurde abgebrochen',
      'Daardie kaartbetaling is gekanselleer',
      th: 'การชำระด้วยบัตรนั้นถูกยกเลิกแล้ว',
    ),
    'stripe_not_ready' => _t(
      'Le paiement par carte n’est pas terminé — réessayez',
      'The card payment isn\'t finished yet — try again',
      'El pago con tarjeta todavía no termina: inténtalo de nuevo',
      'Die Kartenzahlung ist noch nicht abgeschlossen – erneut versuchen',
      'Die kaartbetaling is nog nie klaar nie — probeer weer',
      th: 'การชำระด้วยบัตรยังไม่เสร็จ — ลองอีกครั้ง',
    ),
    'stripe_no_card_tender' => _t(
      'Cette addition n’a pas été payée par carte (Stripe)',
      'This bill wasn\'t paid by Card (Stripe)',
      'Esta cuenta no se pagó con tarjeta (Stripe)',
      'Diese Rechnung wurde nicht mit Karte (Stripe) bezahlt',
      'Hierdie rekening is nie met kaart (Stripe) betaal nie',
      th: 'บิลนี้ไม่ได้ชำระด้วยบัตร (Stripe)',
    ),
    'stripe_refund_exceeds_card' => _t(
      'Le remboursement dépasse le montant payé par carte (Stripe)',
      'The refund is more than was paid by Card (Stripe)',
      'El reembolso supera lo que se pagó con tarjeta (Stripe)',
      'Die Erstattung übersteigt den mit Karte (Stripe) bezahlten Betrag',
      'Die terugbetaling is meer as wat met kaart (Stripe) betaal is',
      th: 'ยอดคืนเงินมากกว่ายอดที่ชำระด้วยบัตร (Stripe)',
    ),
    'stripe_refund_split_required' => _t(
      'Remboursez chaque paiement par carte séparément',
      'Refund each card payment separately',
      'Reembolsa cada pago con tarjeta por separado',
      'Jede Kartenzahlung einzeln erstatten',
      'Betaal elke kaartbetaling afsonderlik terug',
      th: 'คืนเงินการชำระด้วยบัตรทีละรายการ',
    ),
    'stripe_refund_failed' => _t(
      'Stripe n’a pas effectué le remboursement. Rien n’a été enregistré.',
      'Stripe did not make the refund. Nothing was recorded.',
      'Stripe no hizo el reembolso. No se registró nada.',
      'Stripe hat die Erstattung nicht ausgeführt. Es wurde nichts gebucht.',
      'Stripe het nie die terugbetaling gedoen nie. Niks is aangeteken nie.',
      th: 'Stripe ไม่ได้คืนเงิน ยังไม่มีการบันทึกใด ๆ',
    ),
    'stripe_refund_via_stripe' => _t(
      'Les remboursements par carte (Stripe) passent par Stripe',
      'Card (Stripe) refunds go through Stripe',
      'Los reembolsos con tarjeta (Stripe) se hacen por Stripe',
      'Erstattungen für Karte (Stripe) laufen über Stripe',
      'Terugbetalings vir kaart (Stripe) gaan deur Stripe',
      th: 'การคืนเงินบัตร (Stripe) ทำผ่าน Stripe',
    ),
    // retail counter
    'unknown_barcode' => _t(
      'Code-barres inconnu',
      'Unknown barcode',
      'Código de barras desconocido',
      'Unbekannter Barcode',
      'Onbekende strepieskode',
      th: 'ไม่รู้จักบาร์โค้ดนี้',
    ),
    'age_check_required' => _t(
      'Vérifiez la pièce d’identité du client avant le paiement',
      'Check the customer\'s ID before payment',
      'Verifica la identificación del cliente antes de cobrar',
      'Vor dem Bezahlen den Ausweis des Kunden prüfen',
      'Kontroleer die klant se ID voor betaling',
      th: 'ตรวจบัตรประชาชนของลูกค้าก่อนชำระเงิน',
    ),
    'age_check_failed' => _t(
      'Vérification de l’âge échouée — retirez les articles réservés aux adultes',
      'ID check failed — remove the age-restricted items',
      'No pasó la verificación de edad: quita los artículos con restricción de edad',
      'Ausweisprüfung nicht bestanden – altersbeschränkte Artikel entfernen',
      'ID-kontrole het misluk — verwyder die ouderdomsbeperkte items',
      th: 'ตรวจบัตรไม่ผ่าน — นำรายการที่จำกัดอายุออก',
    ),
    'barcode_taken' => _t(
      'Ce code-barres appartient déjà à un produit',
      'That barcode already belongs to a product',
      'Ese código de barras ya pertenece a un producto',
      'Dieser Barcode gehört bereits zu einem Produkt',
      'Daardie strepieskode behoort reeds aan ’n produk',
      th: 'บาร์โค้ดนี้เป็นของสินค้าอื่นแล้ว',
    ),
    'item_unavailable' => _t(
      'Cet article n’est plus offert (retiré du menu ou en rupture)',
      'That item is no longer available (taken off the menu or sold out)',
      'Ese artículo ya no está disponible (retirado del menú o agotado)',
      'Dieser Artikel ist nicht mehr verfügbar (von der Karte genommen oder ausverkauft)',
      'Daardie item is nie meer beskikbaar nie (van die spyskaart af of uitverkoop)',
      th: 'รายการนี้ไม่มีแล้ว (นำออกจากเมนูหรือหมด)',
    ),
    'price_changed' => _t(
      'Le prix de cet article vient de changer',
      'The price of that item just changed',
      'El precio de ese artículo acaba de cambiar',
      'Der Preis dieses Artikels hat sich gerade geändert',
      'Die prys van daardie item het pas verander',
      th: 'ราคาของรายการนี้เพิ่งเปลี่ยน',
    ),
    'lines_rejected' => _t(
      'Le menu a changé : aucun article n’a pu être ajouté',
      'The menu changed: none of the items could be added',
      'El menú cambió: no se pudo agregar ningún artículo',
      'Die Karte hat sich geändert: kein Artikel konnte hinzugefügt werden',
      'Die spyskaart het verander: geen items kon bygevoeg word nie',
      th: 'เมนูมีการเปลี่ยนแปลง: เพิ่มรายการไม่ได้เลย',
    ),
    'item_inactive' => _t(
      'Ce produit n’est pas en vente',
      'That product is off sale',
      'Ese producto no está a la venta',
      'Dieses Produkt ist nicht im Verkauf',
      'Daardie produk is nie beskikbaar nie',
      th: 'สินค้านี้งดจำหน่าย',
    ),
    'terminal_unavailable' ||
    'terminal_not_paired' ||
    'terminal_busy' ||
    'terminal_off' ||
    'jpm_not_configured' => terminalUnavailableHint(code),
    'terminal_pairing_code_wrong' => _t(
      'Ce n’est pas le code affiché sur le terminal',
      'That is not the code shown on the terminal',
      'Ese no es el código que muestra la terminal',
      'Das ist nicht der Code, der am Terminal angezeigt wird',
      'Dit is nie die kode wat op die terminaal gewys word nie',
      th: 'รหัสนี้ไม่ตรงกับที่แสดงบนเครื่องรูดบัตร',
    ),
    'terminal_pairing_code_required' => _t(
      'Entrez le code affiché sur le terminal',
      'Enter the code shown on the terminal',
      'Escribe el código que muestra la terminal',
      'Den am Terminal angezeigten Code eingeben',
      'Voer die kode in wat op die terminaal gewys word',
      th: 'ใส่รหัสที่แสดงบนเครื่องรูดบัตร',
    ),
    'terminal_bad_host' => _t(
      'Entrez l’adresse IP du terminal, par exemple 192.168.1.50',
      'Enter the terminal’s IP address, like 192.168.1.50',
      'Escribe la dirección IP de la terminal, por ejemplo 192.168.1.50',
      'IP-Adresse des Terminals eingeben, z. B. 192.168.1.50',
      'Voer die terminaal se IP-adres in, soos 192.168.1.50',
      th: 'ใส่ IP address ของเครื่องรูดบัตร เช่น 192.168.1.50',
    ),
    'terminal_pairing_unsupported' => _t(
      'Ce terminal ne s’associe pas depuis la caisse',
      'This terminal doesn’t pair from the POS',
      'Esta terminal no se vincula desde la caja',
      'Dieses Terminal wird nicht über die Kasse gekoppelt',
      'Hierdie terminaal koppel nie vanaf die kasregister nie',
      th: 'เครื่องรูดบัตรนี้จับคู่จาก POS ไม่ได้',
    ),
    'terminal_cancel_unavailable' => _t(
      'La carte est en cours de traitement — impossible d’annuler maintenant',
      'The card is being processed — it can’t be cancelled now',
      'La tarjeta se está procesando: no se puede cancelar ahora',
      'Die Karte wird verarbeitet – Abbruch jetzt nicht möglich',
      'Die kaart word verwerk — dit kan nie nou gekanselleer word nie',
      th: 'กำลังประมวลผลบัตร — ยกเลิกตอนนี้ไม่ได้',
    ),
    'card_payment_pending' => _t(
      'Un paiement par carte est encore en cours sur le terminal pour cette addition',
      'A card payment is still in progress on the reader for this bill',
      'Un pago con tarjeta sigue en curso en el lector para esta cuenta',
      'Für diese Rechnung läuft noch eine Kartenzahlung am Kartenleser',
      '’n Kaartbetaling is nog besig op die kaartleser vir hierdie rekening',
      th: 'บิลนี้ยังมีการชำระด้วยบัตรค้างอยู่ที่เครื่องรูดบัตร',
    ),
    'card_cancel_unconfirmed' => _t(
      'Le terminal n’a pas pu confirmer que la carte n’a pas été débitée — le paiement reste en attente',
      'The reader couldn’t confirm the card wasn’t charged — the payment stays pending',
      'El lector no pudo confirmar que no se cobró la tarjeta — el pago sigue pendiente',
      'Der Kartenleser konnte nicht bestätigen, dass die Karte nicht belastet wurde – die Zahlung bleibt offen',
      'Die kaartleser kon nie bevestig dat die kaart nie gedebiteer is nie — die betaling bly hangende',
      th: 'เครื่องรูดบัตรยืนยันไม่ได้ว่าบัตรไม่ถูกตัดเงิน — การชำระเงินยังรอดำเนินการ',
    ),
    'terminal_already_recorded' => _t(
      'Ce paiement est déjà enregistré — faites un remboursement',
      'That payment is already recorded — refund it instead',
      'Ese pago ya está registrado: haz un reembolso',
      'Diese Zahlung ist bereits gebucht – stattdessen erstatten',
      'Daardie betaling is reeds aangeteken — betaal dit eerder terug',
      th: 'การชำระเงินนี้บันทึกแล้ว — ให้คืนเงินแทน',
    ),
    'terminal_no_card_tender' => _t(
      'Cette addition n’a pas été payée par carte au terminal',
      'This bill wasn’t paid by card on the terminal',
      'Esta cuenta no se pagó con tarjeta en la terminal',
      'Diese Rechnung wurde nicht mit Karte am Terminal bezahlt',
      'Hierdie rekening is nie met kaart op die terminaal betaal nie',
      th: 'บิลนี้ไม่ได้ชำระด้วยบัตรที่เครื่องรูดบัตร',
    ),
    'terminal_refund_exceeds_card' => _t(
      'Le remboursement dépasse ce qui a été payé par carte au terminal',
      'The refund is more than was paid by card on the terminal',
      'El reembolso supera lo pagado con tarjeta en la terminal',
      'Die Erstattung übersteigt den mit Karte am Terminal bezahlten Betrag',
      'Die terugbetaling is meer as wat met kaart op die terminaal betaal is',
      th: 'ยอดคืนเงินมากกว่ายอดที่ชำระด้วยบัตรที่เครื่องรูดบัตร',
    ),
    'terminal_refund_split_required' => _t(
      'Remboursez chaque paiement par carte séparément',
      'Refund each card payment separately',
      'Reembolsa cada pago con tarjeta por separado',
      'Jede Kartenzahlung einzeln erstatten',
      'Betaal elke kaartbetaling afsonderlik terug',
      th: 'คืนเงินการชำระด้วยบัตรทีละรายการ',
    ),
    'terminal_refund_failed' => _t(
      'Le terminal n’a pas fait le remboursement. Rien n’a été enregistré.',
      'The terminal didn’t make the refund. Nothing was recorded.',
      'La terminal no hizo el reembolso. No se registró nada.',
      'Das Terminal hat die Erstattung nicht ausgeführt. Es wurde nichts gebucht.',
      'Die terminaal het nie die terugbetaling gedoen nie. Niks is aangeteken nie.',
      th: 'เครื่องรูดบัตรไม่ได้คืนเงิน ยังไม่มีการบันทึกใด ๆ',
    ),
    _ => null,
  };

  // --- Card terminal (payment.terminal): simulator / J.P. Morgan ------------

  String get cardTerminalTender => _t(
    'Carte (terminal)',
    'Card (terminal)',
    'Tarjeta (terminal)',
    'Karte (Terminal)',
    'Kaart (terminaal)',
    th: 'บัตร (เครื่องรูดบัตร)',
  );
  String get chargeCardTerminal => _t(
    'Encaisser au terminal',
    'Charge card on the terminal',
    'Cobrar en la terminal',
    'Karte am Terminal belasten',
    'Hef kaart op die terminaal',
    th: 'ชำระด้วยบัตรที่เครื่องรูดบัตร',
  );
  String get terminalTitle => _t(
    'Paiement par carte au terminal',
    'Card payment on the terminal',
    'Pago con tarjeta en la terminal',
    'Kartenzahlung am Terminal',
    'Kaartbetaling op die terminaal',
    th: 'ชำระด้วยบัตรที่เครื่องรูดบัตร',
  );
  String get terminalStarting => _t(
    'Envoi du montant au terminal…',
    'Sending the amount to the terminal…',
    'Enviando el monto a la terminal…',
    'Betrag wird an das Terminal gesendet …',
    'Stuur die bedrag na die terminaal…',
    th: 'กำลังส่งยอดเงินไปที่เครื่องรูดบัตร…',
  );
  String get terminalPresentCard => _t(
    'Le client présente, insère ou glisse sa carte au terminal',
    'Customer taps, inserts or swipes on the terminal',
    'El cliente acerca, inserta o desliza su tarjeta en la terminal',
    'Kunde hält, steckt oder zieht die Karte am Terminal',
    'Klant tik, steek in of swiep op die terminaal',
    th: 'ลูกค้าแตะ เสียบ หรือรูดบัตรที่เครื่อง',
  );
  String get terminalEnterPin => _t(
    'Le client entre son NIP au terminal',
    'Customer enters their PIN on the terminal',
    'El cliente escribe su PIN en la terminal',
    'Kunde gibt die PIN am Terminal ein',
    'Klant voer hul PIN op die terminaal in',
    th: 'ลูกค้าใส่ PIN ที่เครื่องรูดบัตร',
  );
  String get terminalChooseTip => _t(
    'Le client choisit le pourboire au terminal',
    'Customer is choosing a tip on the terminal',
    'El cliente elige la propina en la terminal',
    'Kunde wählt das Trinkgeld am Terminal',
    'Klant kies ’n fooitjie op die terminaal',
    th: 'ลูกค้ากำลังเลือกทิปที่เครื่องรูดบัตร',
  );
  String get terminalProcessing => _t(
    'Traitement de la carte…',
    'Processing the card…',
    'Procesando…',
    'Karte wird verarbeitet …',
    'Verwerk die kaart…',
    th: 'กำลังประมวลผลบัตร…',
  );
  String get terminalWaitingForPhone => _t(
    'En attente du téléphone… touchez la carte sur le téléphone',
    'Waiting for the phone… tap on the phone',
    'Esperando el teléfono… acerca la tarjeta al teléfono',
    'Warten auf das Telefon … am Telefon bezahlen',
    'Wag vir die foon… tik op die foon',
    th: 'รอโทรศัพท์… แตะบัตรที่โทรศัพท์',
  );

  // A card payment left on the reader by a restart (power cut, app crash)
  String get cardPendingTitle => _t(
    'Un paiement par carte est encore en cours sur le terminal — vérification…',
    'A card payment is still in progress on the reader — checking…',
    'Un pago con tarjeta sigue en curso en el lector — comprobando…',
    'Eine Kartenzahlung läuft noch am Kartenleser – wird geprüft …',
    '’n Kaartbetaling is nog besig op die kaartleser — word nagegaan…',
    th: 'มีการชำระด้วยบัตรค้างอยู่ที่เครื่องรูดบัตร — กำลังตรวจสอบ…',
  );
  String get cardPendingOffline => _t(
    'Le terminal ne répond pas — le paiement reste en attente jusqu’à sa réponse',
    'The reader isn’t answering — the payment stays pending until it does',
    'El lector no responde — el pago sigue pendiente hasta que responda',
    'Der Kartenleser antwortet nicht – die Zahlung bleibt offen, bis er antwortet',
    'Die kaartleser antwoord nie — die betaling bly hangende totdat dit antwoord',
    th: 'เครื่องรูดบัตรไม่ตอบสนอง — การชำระเงินจะรอจนกว่าเครื่องจะตอบ',
  );
  String get cardPendingWait => _t(
    'Les autres modes de paiement attendent la fin du paiement par carte',
    'Other payments wait until the card payment is settled',
    'Los demás pagos esperan a que termine el pago con tarjeta',
    'Andere Zahlungen warten, bis die Kartenzahlung abgeschlossen ist',
    'Ander betalings wag totdat die kaartbetaling afgehandel is',
    th: 'การชำระเงินวิธีอื่นต้องรอให้การชำระด้วยบัตรเสร็จก่อน',
  );
  String get cardPendingCancel => _t(
    'Annuler le paiement par carte',
    'Cancel card payment',
    'Cancelar el pago con tarjeta',
    'Kartenzahlung abbrechen',
    'Kanselleer kaartbetaling',
    th: 'ยกเลิกการชำระด้วยบัตร',
  );
  String cardPendingAmount(String amount) => _t(
    'Montant sur le terminal — $amount',
    'On the reader — $amount',
    'En el lector — $amount',
    'Am Kartenleser – $amount',
    'Op die kaartleser — $amount',
    th: 'ที่เครื่องรูดบัตร — $amount',
  );
  String cardPendingRecorded(String amount, String due) => _t(
    'Paiement par carte de $amount enregistré — reste $due à payer',
    'Card payment of $amount recorded — $due still due',
    'Pago con tarjeta de $amount registrado — faltan $due',
    'Kartenzahlung über $amount verbucht – noch $due offen',
    'Kaartbetaling van $amount aangeteken — $due nog uitstaande',
    th: 'บันทึกการชำระด้วยบัตร $amount แล้ว — ยังค้างชำระ $due',
  );
  String cardPendingRecordedPaid(String amount) => _t(
    'Paiement par carte de $amount enregistré — l’addition est réglée',
    'Card payment of $amount recorded — the bill is paid',
    'Pago con tarjeta de $amount registrado — la cuenta está pagada',
    'Kartenzahlung über $amount verbucht – die Rechnung ist bezahlt',
    'Kaartbetaling van $amount aangeteken — die rekening is betaal',
    th: 'บันทึกการชำระด้วยบัตร $amount แล้ว — ชำระบิลครบแล้ว',
  );
  String get cardPendingNotTaken => _t(
    'Le paiement par carte n’a pas été effectué — choisissez le mode de paiement',
    'The card payment was not taken — choose how to pay',
    'El pago con tarjeta no se realizó — elige cómo pagar',
    'Die Kartenzahlung kam nicht zustande – Zahlungsart wählen',
    'Die kaartbetaling het nie deurgegaan nie — kies hoe om te betaal',
    th: 'การชำระด้วยบัตรไม่สำเร็จ — เลือกวิธีชำระเงิน',
  );
  String get terminalTapOnPhone => _t(
    'Le client touche sa carte sur le téléphone',
    'Customer taps their card on the phone',
    'El cliente acerca su tarjeta al teléfono',
    'Kunde hält die Karte ans Telefon',
    'Klant tik hul kaart op die foon',
    th: 'ลูกค้าแตะบัตรที่โทรศัพท์',
  );
  String get followPhone => _t(
    'Suivez les étapes sur le téléphone',
    'Follow the steps on the phone',
    'Sigue los pasos en el teléfono',
    'Den Schritten auf dem Telefon folgen',
    'Volg die stappe op die foon',
    th: 'ทำตามขั้นตอนบนโทรศัพท์',
  );
  String phonePairingCode(String code) => _t(
    'Code d’association du téléphone : $code',
    'Phone pairing code: $code',
    'Código para vincular el teléfono: $code',
    'Kopplungscode fürs Telefon: $code',
    'Foon se koppelkode: $code',
    th: 'รหัสจับคู่โทรศัพท์: $code',
  );
  String get phonePairingHint => _t(
    'Ouvrez l’app « Card Reader » sur le téléphone et entrez ce code.',
    'Open the Card Reader app on the phone and enter this code.',
    'Abre la app Card Reader en el teléfono y escribe este código.',
    'Auf dem Telefon die App Card Reader öffnen und diesen Code eingeben.',
    'Maak die Card Reader-toep op die foon oop en voer hierdie kode in.',
    th: 'เปิดแอป Card Reader บนโทรศัพท์แล้วใส่รหัสนี้',
  );
  String get unpairPhone => _t(
    'Dissocier le téléphone',
    'Unpair the phone',
    'Desvincular el teléfono',
    'Telefon entkoppeln',
    'Ontkoppel die foon',
    th: 'ยกเลิกการจับคู่โทรศัพท์',
  );
  String get phoneSimulatedNote => _t(
    'Lecteur Tap to Pay simulé par Stripe (cartes de test choisies sur le téléphone)',
    'Stripe’s simulated Tap to Pay reader (test cards picked on the phone)',
    'Lector Tap to Pay simulado de Stripe (tarjetas de prueba en el teléfono)',
    'Simuliertes Tap-to-Pay-Lesegerät von Stripe (Testkarten am Telefon wählen)',
    'Stripe se gesimuleerde Tap to Pay-leser (toetskaarte word op die foon gekies)',
    th: 'เครื่องอ่านบัตร Tap to Pay จำลองของ Stripe (เลือกบัตรทดสอบบนโทรศัพท์)',
  );
  String get terminalOfflineNow => _t(
    'Le terminal ne répond pas — vérifiez qu’il est allumé et sur le Wi-Fi',
    'The terminal isn’t answering — check it’s on and on the Wi-Fi',
    'La terminal no responde: revisa que esté encendida y en el Wi-Fi',
    'Das Terminal antwortet nicht – prüfen, ob es eingeschaltet und im WLAN ist',
    'Die terminaal antwoord nie — kontroleer dat dit aan is en op die Wi-Fi',
    th: 'เครื่องรูดบัตรไม่ตอบสนอง — ตรวจสอบว่าเปิดเครื่องและเชื่อมต่อ Wi-Fi อยู่',
  );
  String get terminalApproved => _t(
    'Paiement approuvé',
    'Payment approved',
    'Pago aprobado',
    'Zahlung genehmigt',
    'Betaling goedgekeur',
    th: 'อนุมัติการชำระเงินแล้ว',
  );
  String get terminalDeclined => _t(
    'Carte refusée',
    'Card declined',
    'Tarjeta rechazada',
    'Karte abgelehnt',
    'Kaart geweier',
    th: 'บัตรถูกปฏิเสธ',
  );
  String get terminalTimedOut => _t(
    'Délai dépassé — aucune carte présentée',
    'Timed out — no card presented',
    'Se agotó el tiempo: no se presentó ninguna tarjeta',
    'Zeitüberschreitung – keine Karte vorgelegt',
    'Tyd verstreke — geen kaart aangebied nie',
    th: 'หมดเวลา — ไม่มีการแตะบัตร',
  );
  String get terminalCancelled => _t(
    'Paiement annulé',
    'Payment cancelled',
    'Pago cancelado',
    'Zahlung abgebrochen',
    'Betaling gekanselleer',
    th: 'ยกเลิกการชำระเงินแล้ว',
  );
  String get terminalFailed => _t(
    'Échec du paiement par carte',
    'Card payment failed',
    'Falló el pago con tarjeta',
    'Kartenzahlung fehlgeschlagen',
    'Kaartbetaling het misluk',
    th: 'ชำระด้วยบัตรไม่สำเร็จ',
  );
  String get payAnotherWay => _t(
    'Payer autrement',
    'Pay another way',
    'Pagar de otra forma',
    'Anders bezahlen',
    'Betaal op ’n ander manier',
    th: 'ชำระวิธีอื่น',
  );
  String get authCodeLabel => _t(
    'N° d’autorisation',
    'Auth code',
    'Cód. de autorización',
    'Autorisierungscode',
    'Magtigingskode',
    th: 'รหัสอนุมัติ',
  );
  String get processorRefLabel => _t(
    'Référence',
    'Reference',
    'Referencia',
    'Referenz',
    'Verwysing',
    th: 'เลขอ้างอิง',
  );
  String followTerminal(String? address) => address == null
      ? _t(
          'Suivez les étapes sur le terminal',
          'Follow the steps on the terminal',
          'Sigue los pasos en la terminal',
          'Den Schritten am Terminal folgen',
          'Volg die stappe op die terminaal',
          th: 'ทำตามขั้นตอนบนเครื่องรูดบัตร',
        )
      : _t(
          'Suivez les étapes sur le terminal ($address)',
          'Follow the steps on the terminal ($address)',
          'Sigue los pasos en la terminal ($address)',
          'Den Schritten am Terminal folgen ($address)',
          'Volg die stappe op die terminaal ($address)',
          th: 'ทำตามขั้นตอนบนเครื่องรูดบัตร ($address)',
        );
  String get playReaderHere => _t(
    'Jouer le lecteur de carte ici',
    'Play the card reader here',
    'Usar el lector de tarjetas aquí',
    'Kartenleser hier simulieren',
    'Speel die kaartleser hier',
    th: 'จำลองเครื่องอ่านบัตรที่นี่',
  );

  /// Why a terminal card payment was declined.
  String terminalDeclineMessage(String? code) => switch (code) {
    'insufficient_funds' => stripeDeclineMessage('insufficient_funds'),
    'do_not_honor' || 'do_not_honour' => _t(
      'La banque a refusé la carte (« ne pas honorer »). Essayez une autre carte.',
      'The bank declined the card (do not honour). Try another card.',
      'El banco rechazó la tarjeta (no aceptar). Prueba con otra tarjeta.',
      'Die Bank hat die Karte abgelehnt (nicht ausführen). Andere Karte versuchen.',
      'Die bank het die kaart geweier (moenie honoreer nie). Probeer ’n ander kaart.',
      th: 'ธนาคารปฏิเสธบัตร (do not honour) ลองใช้บัตรอื่น',
    ),
    'processor_unavailable' => _t(
      'Le processeur de paiement est injoignable. Prenez le comptant ou le terminal du comptoir.',
      'The card processor can’t be reached. Take cash or use the counter terminal.',
      'No se puede conectar con el procesador de pagos. Cobra en efectivo o con la terminal del mostrador.',
      'Der Kartenabwickler ist nicht erreichbar. Bar kassieren oder das Terminal an der Theke nutzen.',
      'Die kaartverwerker kan nie bereik word nie. Neem kontant of gebruik die toonbankterminaal.',
      th: 'เชื่อมต่อผู้ให้บริการบัตรไม่ได้ รับเงินสดหรือใช้เครื่องรูดบัตรที่เคาน์เตอร์',
    ),
    'jpm_not_configured' => terminalUnavailableHint('jpm_not_configured'),
    _ => stripeDeclineMessage(null),
  };

  /// Why "Card (terminal)" is greyed out.
  String terminalUnavailableHint(String? reason) => switch (reason) {
    'phone_reader_offline' => _t(
      'Le téléphone lecteur ne répond pas — ouvrez l’app Card Reader sur le Wi-Fi du magasin',
      'The phone reader isn’t answering — open the Card Reader app on the store Wi-Fi',
      'El teléfono lector no responde: abre la app Card Reader en el Wi-Fi de la tienda',
      'Das Telefon-Lesegerät antwortet nicht – App Card Reader im WLAN der Filiale öffnen',
      'Die foonleser antwoord nie — maak die Card Reader-toep op die winkel se Wi-Fi oop',
      th: 'โทรศัพท์อ่านบัตรไม่ตอบสนอง — เปิดแอป Card Reader บน Wi-Fi ของร้าน',
    ),
    'phone_reader_error' => _t(
      'Le téléphone lecteur signale une erreur — regardez l’écran du téléphone',
      'The phone reader reports a problem — check the phone’s screen',
      'El teléfono lector tiene un problema: revisa su pantalla',
      'Das Telefon-Lesegerät meldet ein Problem – Anzeige am Telefon prüfen',
      'Die foonleser meld ’n probleem — kontroleer die foon se skerm',
      th: 'โทรศัพท์อ่านบัตรแจ้งปัญหา — ดูที่หน้าจอโทรศัพท์',
    ),
    'stripe_not_configured' || 'terminal_not_configured' => _t(
      'Le terminal de carte n’est pas configuré — Réglages → Paiements',
      'Card terminal isn’t set up — Settings → Payments',
      'La terminal de tarjetas no está configurada: Ajustes → Pagos',
      'Kartenterminal ist nicht eingerichtet – Einstellungen → Zahlungen',
      'Kaartterminaal is nie opgestel nie — Instellings → Betalings',
      th: 'ยังไม่ได้ตั้งค่าเครื่องรูดบัตร — ตั้งค่า → การชำระเงิน',
    ),
    'terminal_not_paired' => _t(
      'Terminal non associé — associez-le dans Réglages',
      'Terminal not paired — pair it in Settings',
      'Terminal sin vincular: vincúlala en Ajustes',
      'Terminal nicht gekoppelt – in den Einstellungen koppeln',
      'Terminaal nie gekoppel nie — koppel dit in Instellings',
      th: 'ยังไม่ได้จับคู่เครื่องรูดบัตร — จับคู่ในตั้งค่า',
    ),
    'terminal_busy' => _t(
      'Le terminal est occupé par un autre paiement',
      'The terminal is busy with another payment',
      'La terminal está ocupada con otro pago',
      'Das Terminal ist mit einer anderen Zahlung beschäftigt',
      'Die terminaal is besig met ’n ander betaling',
      th: 'เครื่องรูดบัตรกำลังทำรายการอื่นอยู่',
    ),
    'terminal_off' => _t(
      'Aucun terminal de carte intégré dans cet établissement',
      'No integrated card terminal on this store',
      'Esta tienda no tiene terminal de tarjetas integrada',
      'Kein integriertes Kartenterminal in dieser Filiale',
      'Geen geïntegreerde kaartterminaal in hierdie winkel nie',
      th: 'ร้านนี้ไม่มีเครื่องรูดบัตรที่เชื่อมต่อกับระบบ',
    ),
    'jpm_not_configured' => _t(
      'J.P. Morgan n’est pas configuré dans cet établissement',
      'J.P. Morgan is not set up on this store',
      'J.P. Morgan no está configurado en esta tienda',
      'J.P. Morgan ist in dieser Filiale nicht eingerichtet',
      'J.P. Morgan is nie in hierdie winkel opgestel nie',
      th: 'ร้านนี้ยังไม่ได้ตั้งค่า J.P. Morgan',
    ),
    _ => _t(
      'Le terminal de carte est injoignable — payez comptant ou au terminal du comptoir',
      'The card terminal can’t be reached — take cash or the counter terminal',
      'No se puede conectar con la terminal: cobra en efectivo o con la terminal del mostrador',
      'Das Kartenterminal ist nicht erreichbar – bar kassieren oder das Terminal an der Theke nutzen',
      'Die kaartterminaal kan nie bereik word nie — neem kontant of gebruik die toonbankterminaal',
      th: 'เชื่อมต่อเครื่องรูดบัตรไม่ได้ — รับเงินสดหรือใช้เครื่องรูดบัตรที่เคาน์เตอร์',
    ),
  };

  // the reader sheet (the built-in simulator played on this tablet)
  String get readerTitle => _t(
    'Lecteur de carte (simulateur)',
    'Card reader (simulator)',
    'Lector de tarjetas (simulador)',
    'Kartenleser (Simulator)',
    'Kaartleser (simulator)',
    th: 'เครื่องอ่านบัตร (จำลอง)',
  );
  String get readerTestCard => _t(
    'Carte de test',
    'Test card',
    'Tarjeta de prueba',
    'Testkarte',
    'Toetskaart',
    th: 'บัตรทดสอบ',
  );
  String get readerOutcome => _t(
    'Réponse de la banque',
    'Bank answer',
    'Respuesta del banco',
    'Antwort der Bank',
    'Bank se antwoord',
    th: 'ผลจากธนาคาร',
  );
  String get readerApprove => _t(
    'Approuver',
    'Approve',
    'Aprobar',
    'Genehmigen',
    'Keur goed',
    th: 'อนุมัติ',
  );
  String get readerInsufficient => _t(
    'Refus — fonds insuffisants',
    'Decline — insufficient funds',
    'Rechazo: fondos insuficientes',
    'Ablehnen – Deckung unzureichend',
    'Weier — onvoldoende fondse',
    th: 'ปฏิเสธ — เงินไม่พอ',
  );
  String get readerDoNotHonour => _t(
    'Refus — ne pas honorer',
    'Decline — do not honour',
    'Rechazo: no aceptar',
    'Ablehnen – nicht ausführen',
    'Weier — moenie honoreer nie',
    th: 'ปฏิเสธ — do not honour',
  );
  String get readerTimeout => _t(
    'Délai dépassé',
    'Time out',
    'Tiempo agotado',
    'Zeitüberschreitung',
    'Tyd verstreke',
    th: 'หมดเวลา',
  );
  String get readerCustomerCancels => _t(
    'Le client annule',
    'Customer cancels',
    'El cliente cancela',
    'Kunde bricht ab',
    'Klant kanselleer',
    th: 'ลูกค้ายกเลิก',
  );
  String get readerTap =>
      _t('Sans contact', 'Tap', 'Acercar', 'Kontaktlos', 'Tik', th: 'แตะ');
  String get readerInsert => _t(
    'Insérer (NIP)',
    'Insert (PIN)',
    'Insertar (PIN)',
    'Stecken (PIN)',
    'Steek in (PIN)',
    th: 'เสียบ (PIN)',
  );
  String get readerSwipe =>
      _t('Glisser', 'Swipe', 'Deslizar', 'Durchziehen', 'Swiep', th: 'รูด');
  String get readerTapInsertSwipe => _t(
    'Présentez, insérez ou glissez',
    'Tap, insert or swipe',
    'Acerca, inserta o desliza',
    'Karte vorhalten, stecken oder durchziehen',
    'Tik, steek in of swiep',
    th: 'แตะ เสียบ หรือรูดบัตร',
  );
  String get readerEnterPin => _t(
    'Entrez votre NIP',
    'Enter your PIN',
    'Escribe tu PIN',
    'Bitte PIN eingeben',
    'Voer u PIN in',
    th: 'กรุณาใส่ PIN',
  );
  String get readerAddTip => _t(
    'Ajouter un pourboire',
    'Add a tip',
    'Agregar propina',
    'Trinkgeld hinzufügen',
    'Voeg ’n fooitjie by',
    th: 'เพิ่มทิป',
  );
  String get readerNoTip => _t(
    'Sans pourboire',
    'No tip',
    'Sin propina',
    'Kein Trinkgeld',
    'Geen fooitjie',
    th: 'ไม่ให้ทิป',
  );
  String get readerReady => _t(
    'Prêt pour le prochain paiement',
    'Ready for the next payment',
    'Listo para el siguiente pago',
    'Bereit für die nächste Zahlung',
    'Gereed vir die volgende betaling',
    th: 'พร้อมรับการชำระเงินถัดไป',
  );
  String get readerApprovedBig => _t(
    'APPROUVÉE',
    'APPROVED',
    'APROBADA',
    'GENEHMIGT',
    'GOEDGEKEUR',
    th: 'อนุมัติ',
  );
  String get readerDeclinedBig => _t(
    'REFUSÉE',
    'DECLINED',
    'RECHAZADA',
    'ABGELEHNT',
    'GEWEIER',
    th: 'ปฏิเสธ',
  );
  String get readerCancelledBig => _t(
    'ANNULÉE',
    'CANCELLED',
    'CANCELADA',
    'ABGEBROCHEN',
    'GEKANSELLEER',
    th: 'ยกเลิกแล้ว',
  );
  String get readerTimeoutBig => _t(
    'DÉLAI DÉPASSÉ',
    'TIMED OUT',
    'TIEMPO AGOTADO',
    'ZEIT ABGELAUFEN',
    'TYD VERSTREKE',
    th: 'หมดเวลา',
  );
  String get readerNoRealCards => _t(
    'Cartes de test seulement — aucune vraie carte',
    'Test cards only — no real card data',
    'Solo tarjetas de prueba: ningún dato real',
    'Nur Testkarten – keine echten Kartendaten',
    'Slegs toetskaarte — geen regte kaartdata nie',
    th: 'บัตรทดสอบเท่านั้น — ไม่มีข้อมูลบัตรจริง',
  );

  // settings: the card terminal
  String get sectionCardTerminal => _t(
    'Terminal de carte',
    'Card terminal',
    'Terminal de tarjetas',
    'Kartenterminal',
    'Kaartterminaal',
    th: 'เครื่องรูดบัตร',
  );
  String terminalKindName(String kind) => switch (kind) {
    'simulator' => _t(
      'Simulateur',
      'Simulator',
      'Simulador',
      'Simulator',
      'Simulator',
      th: 'เครื่องจำลอง',
    ),
    'jpmorgan' => _t(
      'J.P. Morgan (bac à sable)',
      'J.P. Morgan (sandbox)',
      'J.P. Morgan (entorno de pruebas)',
      'J.P. Morgan (Sandbox)',
      'J.P. Morgan (toetsomgewing)',
      th: 'J.P. Morgan (ทดสอบ)',
    ),
    'stripe' => cardStripe,
    'tap_to_pay' => _t(
      'Téléphone (Tap to Pay)',
      'Phone (Tap to Pay)',
      'Teléfono (Tap to Pay)',
      'Telefon (Tap to Pay)',
      'Foon (Tap to Pay)',
      th: 'โทรศัพท์ (Tap to Pay)',
    ),
    'off' => _t('Aucun', 'None', 'Ninguno', 'Keins', 'Geen', th: 'ไม่มี'),
    _ => _t(
      'Terminal externe',
      'External terminal',
      'Terminal externa',
      'Externes Terminal',
      'Eksterne terminaal',
      th: 'เครื่องรูดบัตรภายนอก',
    ),
  };
  String terminalStateName(String? state) => switch (state) {
    'idle' => _t('Prêt', 'Ready', 'Lista', 'Bereit', 'Gereed', th: 'พร้อม'),
    'busy' => _t('Occupé', 'Busy', 'Ocupada', 'Belegt', 'Besig', th: 'ไม่ว่าง'),
    'not_paired' => _t(
      'Non associé',
      'Not paired',
      'Sin vincular',
      'Nicht gekoppelt',
      'Nie gekoppel nie',
      th: 'ยังไม่ได้จับคู่',
    ),
    'offline' => _t(
      'Injoignable',
      'Unreachable',
      'Sin conexión',
      'Nicht erreichbar',
      'Onbereikbaar',
      th: 'เชื่อมต่อไม่ได้',
    ),
    _ => _t(
      'Sur la tablette',
      'On the tablet',
      'En la tableta',
      'Auf dem Tablet',
      'Op die tablet',
      th: 'บนแท็บเล็ต',
    ),
  };
  String get terminalBuiltIn => _t(
    'Intégré à la caisse (page /terminal ou lecteur sur la tablette)',
    'Built into the POS (the /terminal page, or the reader on the tablet)',
    'Integrada en la caja (la página /terminal o el lector en la tableta)',
    'In die Kasse integriert (die Seite /terminal oder das Lesegerät am Tablet)',
    'Ingebou in die kasregister (die /terminal-bladsy, of die leser op die tablet)',
    th: 'มีในระบบ POS (หน้า /terminal หรือเครื่องอ่านบัตรบนแท็บเล็ต)',
  );
  String get terminalPageQrLabel => _t(
    'Lecteur de carte de démonstration : ouvrez cette page sur un téléphone pour jouer le lecteur',
    'Practice card reader: open this page on a phone to play the card reader',
    'Lector de tarjetas de práctica: abre esta página en un teléfono para hacer de lector',
    'Übungs-Kartenleser: diese Seite auf einem Handy öffnen, um den Kartenleser zu spielen',
    'Oefen-kaartleser: maak hierdie bladsy op ’n foon oop om die kaartleser te speel',
    th: 'เครื่องอ่านบัตรสำหรับฝึก: เปิดหน้านี้บนโทรศัพท์เพื่อจำลองเครื่องอ่านบัตร',
  );
  String get pairTerminal => _t(
    'Associer un terminal',
    'Pair a terminal',
    'Vincular una terminal',
    'Terminal koppeln',
    'Koppel ’n terminaal',
    th: 'จับคู่เครื่องรูดบัตร',
  );
  String get terminalAddressLabel => _t(
    'Adresse IP du terminal (ex. 192.168.1.50:8090)',
    'Terminal IP address (e.g. 192.168.1.50:8090)',
    'Dirección IP de la terminal (p. ej. 192.168.1.50:8090)',
    'IP-Adresse des Terminals (z. B. 192.168.1.50:8090)',
    'Terminaal se IP-adres (bv. 192.168.1.50:8090)',
    th: 'IP address ของเครื่องรูดบัตร (เช่น 192.168.1.50:8090)',
  );
  String get terminalCodeLabel => _t(
    'Code affiché sur le terminal',
    'Code shown on the terminal',
    'Código que muestra la terminal',
    'Am Terminal angezeigter Code',
    'Kode wat op die terminaal gewys word',
    th: 'รหัสที่แสดงบนเครื่องรูดบัตร',
  );
  String get useBuiltInTerminal => _t(
    'Utiliser le terminal intégré',
    'Use the built-in terminal',
    'Usar la terminal integrada',
    'Integriertes Terminal verwenden',
    'Gebruik die ingeboude terminaal',
    th: 'ใช้เครื่องรูดบัตรในตัว',
  );
  String get terminalPaired => _t(
    'Terminal associé',
    'Terminal paired',
    'Terminal vinculada',
    'Terminal gekoppelt',
    'Terminaal gekoppel',
    th: 'จับคู่เครื่องรูดบัตรแล้ว',
  );
}

/// Show an API error as a snackbar — except session expiry, which already
/// navigated to login and needs no acknowledgement. A client-side
/// [TimeoutException] (the server never answered at all, so there's no error
/// code to translate) gets the same friendly wording an AI timeout does,
/// instead of its raw "TimeoutException after 0:03:20.000000: …" text.
void showApiError(BuildContext context, Object error) {
  if (error is SessionExpiredException) return;
  final text = error is TimeoutException
      ? L.of(context).requestTimedOut
      : '$error';
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
}

/// Vertical variant for the 64pt nav rail on the check screen.
class LangActionsCompact extends StatelessWidget {
  const LangActionsCompact({super.key});

  @override
  Widget build(BuildContext context) {
    L.of(context); // subscribe to rebuilds
    final prefs = Prefs.instance;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        TextButton(
          onPressed: () => prefs.setLang(prefs.nextLang),
          child: Text(
            langButtonLabel(prefs.lang),
            style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
          ),
        ),
      ],
    );
  }
}

/// The language pill's text: the code in capitals (EN, FR, ES…), Thai in
/// its own script.
String langButtonLabel(String lang) =>
    lang == 'th' ? 'ไทย' : lang.toUpperCase();

/// AppBar actions: language toggle, visible on every screen. A small
/// outlined pill (globe + EN/FR) in the surrounding icon colour, so it reads
/// on the cream app bars and on the navy floor header alike.
class LangActions extends StatelessWidget {
  final Color? color;
  const LangActions({super.key, this.color});

  @override
  Widget build(BuildContext context) {
    final l = L.of(context); // subscribe to rebuilds
    final prefs = Prefs.instance;
    final fg =
        color ?? IconTheme.of(context).color ?? Theme.of(context).primaryColor;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4),
      child: Tooltip(
        message: l.switchLanguage,
        child: OutlinedButton.icon(
          onPressed: () => prefs.setLang(prefs.nextLang),
          icon: Icon(Icons.language, size: 18, color: fg),
          label: Text(
            langButtonLabel(prefs.lang),
            style: TextStyle(
              fontWeight: FontWeight.w700,
              color: fg,
              letterSpacing: .5,
            ),
          ),
          style: OutlinedButton.styleFrom(
            backgroundColor: Colors.transparent,
            foregroundColor: fg,
            minimumSize: const Size(0, 44),
            padding: const EdgeInsets.symmetric(horizontal: 12),
            side: BorderSide(color: fg.withValues(alpha: .45)),
            shape: const StadiumBorder(),
          ),
        ),
      ),
    );
  }
}

/// The Buddhist Era year Thai dates are written in: CE + 543 (2026 → 2569).
int buddhistYear(int ce) => ce + 543;

/// Thai month names, January first (full, and the short forms with dots).
const thaiMonths = [
  'มกราคม',
  'กุมภาพันธ์',
  'มีนาคม',
  'เมษายน',
  'พฤษภาคม',
  'มิถุนายน',
  'กรกฎาคม',
  'สิงหาคม',
  'กันยายน',
  'ตุลาคม',
  'พฤศจิกายน',
  'ธันวาคม',
];
const thaiMonthsShort = [
  'ม.ค.',
  'ก.พ.',
  'มี.ค.',
  'เม.ย.',
  'พ.ค.',
  'มิ.ย.',
  'ก.ค.',
  'ส.ค.',
  'ก.ย.',
  'ต.ค.',
  'พ.ย.',
  'ธ.ค.',
];

/// A long Thai date: "12 ตุลาคม 2569" (day, month name, Buddhist Era year).
String thaiLongDate(DateTime d) =>
    '${d.day} ${thaiMonths[d.month - 1]} ${buddhistYear(d.year)}';

/// A data name in [lang]: the two catalog slots for the store's data
/// language ([dataLang]: fr by default, th at a Thai + English store) and en;
/// for any other language its translation in [extra], else English, else the
/// data-language slot.
String pickName(
  String lang,
  String fr,
  String en, [
  Map<String, String>? extra,
  String dataLang = 'fr',
]) {
  if (lang == dataLang) return fr;
  if (lang == 'en') return en;
  final t = extra?[lang];
  if (t != null && t.trim().isNotEmpty) return t;
  return en.isNotEmpty ? en : fr;
}
