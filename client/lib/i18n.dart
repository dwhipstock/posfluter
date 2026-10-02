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
      'en'; // one of the store's languages: en | fr (pubs, + es | de | af at Copper Lantern), en | es (US store)

  /// Every language the terminal has strings for; a store offers a subset.
  static const known = {'en', 'fr', 'es', 'de', 'af'};

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

  /// Date-only, dd/MM/yyyy (German: dd.MM.yyyy).
  String fmtDate(DateTime d) {
    String p2(int n) => n.toString().padLeft(2, '0');
    final sep = lang == 'de' ? '.' : '/';
    return '${p2(d.day)}$sep${p2(d.month)}$sep${d.year}';
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
  /// en | fr | es | de | af
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

  /// French, English, US Spanish, German and Afrikaans, in that order.
  String _t(String fr, String enS, String esS, String deS, String afS) =>
      switch (lang) {
        'fr' => fr,
        'es' => esS,
        'de' => deS,
        'af' => afS,
        _ => enS,
      };

  /// Data-driven names (items, zones, variants): user's language first.
  /// The catalog carries French and English; any other language reads its
  /// row in [extra] (the store's translations table), else English, else French.
  String name(String fr, String enS, [Map<String, String>? extra]) =>
      pickName(lang, fr, enS, extra);

  /// The other catalog language, shown as the small secondary line on menu
  /// tiles (only between French and English).
  String nameAlt(String fr, String enS) {
    if (lang != 'fr' && lang != 'en') return '';
    return lang == 'fr' ? enS : fr;
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
  );
  String get cancel =>
      _t('Annuler', 'Cancel', 'Cancelar', 'Abbrechen', 'Kanselleer');
  String get ok => _t('OK', 'OK', 'OK', 'OK', 'OK');
  String get done => _t('Terminé', 'Done', 'Listo', 'Fertig', 'Klaar');
  String get close => _t('Fermer', 'Close', 'Cerrar', 'Schließen', 'Maak toe');
  String get cannotReachServer => _t(
    'Restaurant temporairement injoignable',
    'Restaurant temporarily unavailable',
    'La tienda no está disponible por el momento',
    'Restaurant vorübergehend nicht erreichbar',
    'Restaurant tydelik onbeskikbaar',
  );

  // on-screen keyboard (Windows tablet): key names and the enter key's labels
  String get kbDone => _t('Terminé', 'Done', 'Listo', 'Fertig', 'Klaar');
  String get kbNext => _t('Suivant', 'Next', 'Siguiente', 'Weiter', 'Volgende');
  String get kbPrevious =>
      _t('Précédent', 'Previous', 'Anterior', 'Zurück', 'Vorige');
  String get kbGo => _t('Aller', 'Go', 'Ir', 'Los', 'Gaan');
  String get kbSearch => _t('Rechercher', 'Search', 'Buscar', 'Suchen', 'Soek');
  String get kbSend => _t('Envoyer', 'Send', 'Enviar', 'Senden', 'Stuur');
  String get kbNewLine => _t(
    'Nouvelle ligne',
    'New line',
    'Nueva línea',
    'Neue Zeile',
    'Nuwe reël',
  );
  String get kbSpace => _t('Espace', 'Space', 'Espacio', 'Leertaste', 'Spasie');
  String get kbBackspace =>
      _t('Effacer', 'Delete', 'Borrar', 'Löschen', 'Vee uit');
  String get kbShift =>
      _t('Majuscule', 'Shift', 'Mayúscula', 'Umschalt', 'Hoofletter');
  String get kbSymbols =>
      _t('Symboles', 'Symbols', 'Símbolos', 'Symbole', 'Simbole');
  String get kbLetters =>
      _t('Lettres', 'Letters', 'Letras', 'Buchstaben', 'Alfabet');
  String get kbHide => _t(
    'Masquer le clavier',
    'Hide keyboard',
    'Ocultar teclado',
    'Tastatur ausblenden',
    'Versteek sleutelbord',
  );

  // login
  String get enterPin => _t(
    'Entrez votre NIP pour vous connecter',
    'Enter your PIN to sign in',
    'Ingresa tu PIN para iniciar sesión',
    'PIN eingeben zum Anmelden',
    'Voer jou PIN in om aan te meld',
  );
  String get loginInterrupted => _t(
    'Connexion interrompue — entrez votre NIP de nouveau',
    'Connection interrupted — enter your PIN again',
    'Se interrumpió la conexión: ingresa tu PIN otra vez',
    'Verbindung unterbrochen – bitte PIN erneut eingeben',
    'Verbinding onderbreek — voer jou PIN weer in',
  );
  String get whoClockingIn => _t(
    'Qui commence son quart ?',
    "Who's clocking in?",
    '¿Quién empieza su turno?',
    'Wer stempelt sich ein?',
    'Wie teken in?',
  );
  String get switchLanguage => _t(
    'Changer de langue',
    'Switch language',
    'Cambiar idioma',
    'Sprache wechseln',
    'Verander taal',
  );
  String get staffTerminal => _t(
    'Terminal du personnel',
    'Staff terminal',
    'Terminal del personal',
    'Personal-Terminal',
    'Personeelterminaal',
  );

  // floor header: short labels under the action icons
  String get navMenu => _t('Menu', 'Menu', 'Menú', 'Speisekarte', 'Spyskaart');
  String get navReports =>
      _t('Rapports', 'Reports', 'Reportes', 'Berichte', 'Verslae');
  String get navRefunds => _t(
    'Remboursements',
    'Refunds',
    'Reembolsos',
    'Erstattungen',
    'Terugbetalings',
  );
  String get navLayout =>
      _t('Plan de salle', 'Layout', 'Plano', 'Tischplan', 'Vloerplan');
  String get navRefresh =>
      _t('Actualiser', 'Refresh', 'Actualizar', 'Aktualisieren', 'Verfris');
  String get navMore => _t('Plus', 'More', 'Más', 'Mehr', 'Meer');

  // floor legend + room list
  String get rooms => _t('Salles', 'Rooms', 'Salones', 'Räume', 'Vertrekke');
  String get legendFree => _t('Libre', 'Free', 'Libre', 'Frei', 'Vry');
  String get legendOccupied =>
      _t('Occupée', 'Occupied', 'Ocupada', 'Besetzt', 'Beset');
  String get legendPending => _t(
    'Commande en attente',
    'Order waiting',
    'Pedido en espera',
    'Bestellung wartet',
    'Bestelling wag',
  );
  String tablesOccupied(int open, int total) => _t(
    '$open sur $total occupées',
    '$open of $total occupied',
    '$open de $total ocupadas',
    '$open von $total besetzt',
    '$open van $total beset',
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
      );
    }
    if (d.inHours < 1) return '${d.inMinutes} min';
    final m = (d.inMinutes % 60).toString().padLeft(2, '0');
    return '${d.inHours} h $m';
  }

  // check screen cart
  String itemCount(int n) {
    if (n == 1) {
      return _t('1 article', '1 item', '1 artículo', '1 Artikel', '1 item');
    }
    if (n == 0) {
      return _t('0 article', '0 items', '0 artículos', '0 Artikel', '0 items');
    }
    return _t(
      '$n articles',
      '$n items',
      '$n artículos',
      '$n Artikel',
      '$n items',
    );
  }

  String get emptyBill => _t(
    'Rien sur cette addition pour l’instant',
    'Nothing on this bill yet',
    'Todavía no hay nada en esta cuenta',
    'Noch nichts auf dieser Rechnung',
    'Nog niks op hierdie rekening nie',
  );
  String get each => _t('l’unité', 'each', 'c/u', 'je', 'elk');
  String get tapToAdd => _t(
    'Touchez un article du menu pour l’ajouter.',
    'Tap a menu item to add it.',
    'Toca un artículo del menú para agregarlo.',
    'Artikel in der Karte antippen, um ihn hinzuzufügen.',
    'Tik op ’n spyskaartitem om dit by te voeg.',
  );

  // zones
  String get zones => _t('Zones', 'Zones', 'Zonas', 'Bereiche', 'Areas');
  String get manageMenu => _t(
    'Gérer le menu (86)',
    'Manage menu (86)',
    'Administrar menú (86)',
    'Karte verwalten (86)',
    'Bestuur spyskaart (86)',
  );
  String get shiftReports => _t(
    'Quart / Rapports',
    'Shift / Reports',
    'Turno / Reportes',
    'Schicht / Berichte',
    'Skof / verslae',
  );
  String get logout =>
      _t('Se déconnecter', 'Log out', 'Cerrar sesión', 'Abmelden', 'Meld af');

  // tables
  String get subTable =>
      _t('Sous-table', 'Sub-table', 'Submesa', 'Untertisch', 'Subtafel');
  String get free => _t('Libre', 'Free', 'Libre', 'Frei', 'Vry');

  // floor plan
  String seatsShort(int n) {
    if (n == 1) {
      return _t('1 place', '1 seat', '1 lugar', '1 Platz', '1 sitplek');
    }
    if (n == 0) {
      return _t('0 place', '0 seats', '0 lugares', '0 Plätze', '0 sitplekke');
    }
    return _t(
      '$n places',
      '$n seats',
      '$n lugares',
      '$n Plätze',
      '$n sitplekke',
    );
  }

  String get emptyZoneOnboarding => _t(
    'Aucune table dans cette zone pour l’instant.\nTouchez le crayon pour dessiner le plan de salle.',
    'No tables in this zone yet.\nTap the pencil to build the layout.',
    'Todavía no hay mesas en esta zona.\nToca el lápiz para armar el plano.',
    'In diesem Bereich gibt es noch keine Tische.\nTippen Sie auf den Stift, um den Tischplan anzulegen.',
    'Nog geen tafels in hierdie area nie.\nTik op die potlood om die vloerplan te bou.',
  );

  // floor plan editor (manager)
  String get editLayout => _t(
    'Modifier le plan de salle',
    'Edit layout',
    'Editar plano',
    'Tischplan bearbeiten',
    'Wysig vloerplan',
  );
  String editLayoutTitle(String zone) => _t(
    'Modifier le plan de salle · $zone',
    'Edit layout · $zone',
    'Editar plano · $zone',
    'Tischplan bearbeiten · $zone',
    'Wysig vloerplan · $zone',
  );
  String get saveLayout => _t(
    'Enregistrer le plan',
    'Save layout',
    'Guardar plano',
    'Tischplan speichern',
    'Stoor vloerplan',
  );
  String get layoutSaved => _t(
    'Plan de salle enregistré',
    'Layout saved',
    'Plano guardado',
    'Tischplan gespeichert',
    'Vloerplan gestoor',
  );
  String get addTable => _t(
    'Ajouter une table',
    'Add table',
    'Agregar mesa',
    'Tisch hinzufügen',
    'Voeg tafel by',
  );
  String get deleteTable => _t(
    'Retirer la table',
    'Remove table',
    'Quitar mesa',
    'Tisch entfernen',
    'Verwyder tafel',
  );
  String deleteTableConfirm(String label) => _t(
    'Retirer la table « $label » du plan ? Les anciennes additions la conservent, mais elle disparaît de tous les écrans.',
    'Remove table "$label" from the plan? Old bills keep it; it disappears from every screen.',
    '¿Quitar la mesa "$label" del plano? Las cuentas anteriores la conservan, pero desaparece de todas las pantallas.',
    'Tisch „$label“ aus dem Plan entfernen? Alte Rechnungen behalten ihn, auf allen Bildschirmen verschwindet er.',
    'Verwyder tafel “$label” van die plan? Ou rekeninge behou dit; dit verdwyn van elke skerm.',
  );
  String get tableDeleted => _t(
    'Table retirée',
    'Table removed',
    'Mesa quitada',
    'Tisch entfernt',
    'Tafel verwyder',
  );
  String get renameTable => _t(
    'Renommer la table',
    'Rename table',
    'Cambiar nombre de la mesa',
    'Tisch umbenennen',
    'Hernoem tafel',
  );
  String get tableLabelField => _t(
    'Nom de la table (ex. : L-5)',
    'Table label (e.g. L-5)',
    'Nombre de la mesa (p. ej., L-5)',
    'Tischname (z. B. L-5)',
    'Tafelnaam (bv. L-5)',
  );

  /// Add-table dialog helper: labels are auto-assigned to the zone prefix.
  String autoLabelHint(String prefix) => _t(
    'Laissez vide pour le numéro suivant ($prefix-…)',
    'Leave blank for the next number ($prefix-…)',
    'Déjalo vacío para el siguiente número ($prefix-…)',
    'Leer lassen für die nächste Nummer ($prefix-…)',
    'Laat leeg vir die volgende nommer ($prefix-…)',
  );
  String get vipNameField => _t(
    'Nom VIP (vide = aucun)',
    'VIP name (blank = none)',
    'Nombre VIP (vacío = ninguno)',
    'VIP-Name (leer = keiner)',
    'VIP-naam (leeg = geen)',
  );
  String get rotate => _t('Pivoter', 'Rotate', 'Girar', 'Drehen', 'Draai');
  String get undo => _t('Annuler', 'Undo', 'Deshacer', 'Rückgängig', 'Ontdoen');
  String get editorGrid =>
      _t('Grille', 'Grid', 'Cuadrícula', 'Raster', 'Rooster');
  String get editorSnap =>
      _t('Magnétisme', 'Snap', 'Ajustar', 'Einrasten', 'Belyn');
  String get gridOff => _t('Désactivée', 'Off', 'Desactivada', 'Aus', 'Af');
  String get gridLarge => _t('Grande', 'Large', 'Grande', 'Groß', 'Groot');
  String get gridMedium =>
      _t('Moyenne', 'Medium', 'Mediana', 'Mittel', 'Middel');
  String get gridSmall => _t('Petite', 'Small', 'Pequeña', 'Klein', 'Klein');
  String get seatsLabel =>
      _t('Places', 'Seats', 'Lugares', 'Plätze', 'Sitplekke');
  String get unsavedLayoutTitle => _t(
    'Plan non enregistré',
    'Layout not saved',
    'Plano sin guardar',
    'Tischplan nicht gespeichert',
    'Vloerplan nie gestoor nie',
  );
  String get unsavedLayoutBody => _t(
    'Les tables déplacées reprendront leur place si vous quittez maintenant.',
    'Table positions you moved will be lost if you leave now.',
    'Si sales ahora, se perderán las posiciones de las mesas que moviste.',
    'Verschobene Tische gehen verloren, wenn Sie jetzt verlassen.',
    'Tafelposisies wat jy geskuif het, sal verlore gaan as jy nou uitgaan.',
  );
  String get unsavedItemTitle => _t(
    'Abandonner les modifications\u00A0?',
    'Discard changes?',
    '¿Descartar los cambios?',
    'Änderungen verwerfen?',
    'Gooi veranderinge weg?',
  );
  String get unsavedItemBody => _t(
    'Les modifications de cet article ne sont pas enregistrées.',
    'Your changes to this item are not saved.',
    'Los cambios de este artículo no están guardados.',
    'Die Änderungen an diesem Artikel sind nicht gespeichert.',
    'Jou veranderinge aan hierdie item is nie gestoor nie.',
  );
  String get keepEditing => _t(
    'Continuer',
    'Keep editing',
    'Seguir editando',
    'Weiter bearbeiten',
    'Hou aan wysig',
  );
  String get discard => _t(
    'Quitter sans enregistrer',
    'Discard',
    'Descartar',
    'Verwerfen',
    'Gooi weg',
  );
  String get tapTableToEditHint => _t(
    'Touchez une table pour la sélectionner · glissez pour la déplacer',
    'Tap a table to select · drag to move',
    'Toca una mesa para seleccionarla · arrastra para moverla',
    'Tisch antippen zum Auswählen · ziehen zum Verschieben',
    'Tik op ’n tafel om te kies · sleep om te skuif',
  );

  // floor plan editor — structural objects (pool / bar front / pillar)
  String get addObject => _t(
    'Ajouter un élément',
    'Add object',
    'Agregar elemento',
    'Element hinzufügen',
    'Voeg voorwerp by',
  );
  String get objectPool => _t(
    'Table de billard',
    'Pool table',
    'Mesa de billar',
    'Billardtisch',
    'Biljarttafel',
  );
  String get objectBarFront =>
      _t('Comptoir du bar', 'Bar front', 'Barra', 'Tresen', 'Kroegtoonbank');
  String get objectPillar =>
      _t('Colonne', 'Pillar', 'Columna', 'Säule', 'Pilaar');
  // the short caption on an unlabelled pool table / bar front on the plan
  String get objectPoolCaption =>
      _t('Billard', 'Pool', 'Billar', 'Billard', 'Biljart');
  String get objectBarCaption => _t('Bar', 'Bar', 'Barra', 'Bar', 'Kroeg');
  // the room's built-in landmarks: palette name and default caption
  String? objectTypeName(String type) => switch (type) {
    'ENTRANCE' => _t('Entrée', 'Entrance', 'Entrada', 'Eingang', 'Ingang'),
    'HOST_STAND' => _t(
      'Accueil',
      'Host stand',
      'Recepción',
      'Empfang',
      'Ontvangs',
    ),
    'KITCHEN' => _t('Cuisine', 'Kitchen', 'Cocina', 'Küche', 'Kombuis'),
    'RESTROOMS' => _t(
      'Toilettes',
      'Restrooms',
      'Baños',
      'Toiletten',
      'Toilette',
    ),
    'STAGE' => _t('Scène', 'Stage', 'Escenario', 'Bühne', 'Verhoog'),
    'CARRY_OUT' => _t(
      'À emporter',
      'Carry-out',
      'Para llevar',
      'Zum Mitnehmen',
      'Wegneem',
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
  );
  String get objectFromPhoto => _t(
    'Ajouter depuis une photo…',
    'Add from photo…',
    'Agregar desde foto…',
    'Aus Foto hinzufügen…',
    'Voeg by uit foto…',
  );
  String get objectFromPhotoOffNote => _t(
    'Photo IA : Internet et le menu IA requis. L’élément personnalisé fonctionne toujours.',
    'Add from photo needs AI menu setup and internet. Custom object still works.',
    'Foto con IA: requiere el menú con IA e internet. El elemento personalizado sigue funcionando.',
    '„Aus Foto“ braucht die KI-Kartenerstellung und Internet. Eigene Elemente gehen weiterhin.',
    '“Voeg by uit foto” het KI-spyskaartopstelling en internet nodig. Eie voorwerpe werk steeds.',
  );
  String get objectFromPhotoWorking => _t(
    'L’IA regarde la photo…',
    'AI is looking at the photo…',
    'La IA mira la foto…',
    'Die KI sieht sich das Foto an…',
    'KI kyk na die foto…',
  );
  // "Set up from picture": the AI drafts the whole room from 1–4 pictures
  String get roomFromPicture => _t(
    'Créer depuis une image…',
    'Set up from picture…',
    'Crear desde una imagen…',
    'Aus Bild einrichten…',
    'Stel op uit prent…',
  );
  String get roomFromPictureOffNote => _t(
    'Nécessite le menu IA et Internet.',
    'Needs AI menu setup and internet.',
    'Requiere el menú con IA e internet.',
    'Braucht die KI-Kartenerstellung und Internet.',
    'Het KI-spyskaartopstelling en internet nodig.',
  );
  String get roomLayoutHistory => _t(
    'Salles créées par l’IA',
    'Rooms set up by AI',
    'Salas creadas con IA',
    'Von der KI eingerichtete Räume',
    'Vertrekke deur KI opgestel',
  );
  String roomPreviewSummary(int tables, int seats, int objects) => _t(
    '$tables table(s), $seats place(s), $objects élément(s)',
    '$tables table(s), $seats seat(s), $objects object(s)',
    '$tables mesa(s), $seats lugar(es), $objects elemento(s)',
    '$tables Tisch(e), $seats Platz/Plätze, $objects Element(e)',
    '$tables tafel(s), $seats sitplek(ke), $objects voorwerp(e)',
  );
  String get roomPreviewHint => _t(
    'Aperçu : rien ne change avant « Appliquer ». Glissez pour déplacer, × pour retirer.',
    'Preview: nothing changes until Apply. Drag to move, × to remove.',
    'Vista previa: nada cambia hasta Aplicar. Arrastra para mover, × para quitar.',
    'Vorschau: nichts ändert sich vor „Übernehmen“. Ziehen zum Verschieben, × zum Entfernen.',
    'Voorskou: niks verander voor “Pas toe” nie. Sleep om te skuif, × om te verwyder.',
  );
  String get roomModeReplace =>
      _t('Remplacer', 'Replace', 'Reemplazar', 'Ersetzen', 'Vervang');
  String get roomModeMerge =>
      _t('Ajouter', 'Add to room', 'Añadir', 'Hinzufügen', 'Voeg by vertrek');
  String roomExisting(int n) => _t(
    'La salle a déjà $n table(s).',
    'This room already has $n table(s).',
    'La sala ya tiene $n mesa(s).',
    'Der Raum hat schon $n Tisch(e).',
    'Hierdie vertrek het reeds $n tafel(s).',
  );
  String roomProtected(int n) => _t(
    '$n table(s) avec une addition ouverte restent en place.',
    '$n table(s) with an open bill stay where they are.',
    '$n mesa(s) con cuenta abierta se quedan donde están.',
    '$n Tisch(e) mit offener Rechnung bleiben, wo sie sind.',
    '$n tafel(s) met ’n oop rekening bly waar hulle is.',
  );
  String get roomNotSure => _t(
    'Pas certain :',
    'Not sure:',
    'No está seguro:',
    'Unsicher:',
    'Onseker:',
  );
  String roomSkipped(int n) => _t(
    '$n élément(s) ignoré(s) ou corrigé(s) :',
    '$n thing(s) skipped or fixed:',
    '$n elemento(s) omitido(s) o corregido(s):',
    '$n Element(e) übersprungen oder korrigiert:',
    '$n ding(e) oorgeslaan of reggemaak:',
  );
  String get roomApply =>
      _t('Appliquer', 'Apply', 'Aplicar', 'Übernehmen', 'Pas toe');
  String roomApplied(int n) => _t(
    '$n élément(s) ajouté(s) à la salle',
    '$n thing(s) added to the room',
    '$n elemento(s) añadido(s) a la sala',
    '$n Element(e) zum Raum hinzugefügt',
    '$n ding(e) by die vertrek gevoeg',
  );
  // voice input (menu AI chat, floor assistant): hold to talk, or tap to start / stop
  String get micHint => _t(
    'Maintenez pour parler, ou touchez pour commencer et encore pour arrêter',
    'Hold to talk, or tap to start and tap again to stop',
    'Mantén pulsado para hablar, o toca para empezar y otra vez para parar',
    'Zum Sprechen gedrückt halten, oder antippen zum Starten und erneut zum Stoppen',
    'Hou in om te praat, of tik om te begin en tik weer om te stop',
  );
  String get micListening => _t(
    'J’écoute… relâchez ou touchez pour arrêter',
    'Listening… release or tap to stop',
    'Escuchando… suelta o toca para parar',
    'Ich höre zu… loslassen oder antippen zum Stoppen',
    'Luister… los of tik om te stop',
  );
  String get micUnavailable => _t(
    'Le micro n’est pas disponible. Vérifiez l’autorisation, ou tapez la demande.',
    'The microphone isn’t available. Check the permission, or type the request.',
    'El micrófono no está disponible. Revisa el permiso, o escribe la petición.',
    'Das Mikrofon ist nicht verfügbar. Prüfen Sie die Berechtigung oder tippen Sie die Anfrage.',
    'Die mikrofoon is nie beskikbaar nie. Kontroleer die toestemming, of tik die versoek.',
  );
  String aiHeard(String text) => _t(
    'Entendu : « $text »',
    'Heard: “$text”',
    'Oído: «$text»',
    'Verstanden: „$text“',
    'Gehoor: “$text”',
  );
  // floor plan "Ask AI": edit the current room by text or voice
  String get floorAskAi => _t(
    'Demander à l’IA…',
    'Ask AI…',
    'Preguntar a la IA…',
    'KI fragen…',
    'Vra KI…',
  );
  String get floorAskAiHint => _t(
    'Ex. « ajoute quatre tables de 2 le long de la fenêtre », « table 5 ronde avec 6 places », « enlève le billard »',
    'e.g. “add four 2-tops along the window”, “make table 5 round with 6 seats”, “remove the pool table”',
    'p. ej. «añade cuatro mesas de 2 junto a la ventana», «mesa 5 redonda con 6 lugares», «quita el billar»',
    'z. B. „vier Zweiertische am Fenster“, „Tisch 5 rund mit 6 Plätzen“, „Billardtisch entfernen“',
    'bv. “voeg vier 2-tafels langs die venster by”, “maak tafel 5 rond met 6 sitplekke”, “verwyder die biljarttafel”',
  );
  // AI working card: a request can take 5-60s, so it gets a step label, a
  // live elapsed count and an estimated progress bar instead of a banner
  // that would otherwise look stuck.
  String get aiStepListening =>
      _t('Écoute…', 'Listening…', 'Escuchando…', 'Ich höre zu…', 'Luister…');
  String get aiStepSending =>
      _t('Envoi…', 'Sending…', 'Enviando…', 'Wird gesendet…', 'Stuur tans…');
  String aiStepThinking(bool slow) => slow
      ? _t(
          'Réflexion… habituellement 20 à 60 secondes',
          'Thinking… usually 20–60 seconds',
          'Pensando… habitualmente 20 a 60 segundos',
          'Überlegt… meist 20 bis 60 Sekunden',
          'Dink… gewoonlik 20–60 sekondes',
        )
      : _t(
          'Réflexion… habituellement 5 à 15 secondes',
          'Thinking… usually 5–15 seconds',
          'Pensando… habitualmente 5 a 15 segundos',
          'Überlegt… meist 5 bis 15 Sekunden',
          'Dink… gewoonlik 5–15 sekondes',
        );
  String get aiStepAlmostDone => _t(
    'Presque fini…',
    'Almost done…',
    'Casi listo…',
    'Fast fertig…',
    'Amper klaar…',
  );
  String get floorEditHint => _t(
    'Aperçu : rien ne change avant « Appliquer ». Les contours montrent le nouveau et le déplacé, le pâle est retiré.',
    'Preview: nothing changes until Apply. Outlined = new or moved; faded = removed.',
    'Vista previa: nada cambia hasta Aplicar. Con borde = nuevo o movido; pálido = quitado.',
    'Vorschau: nichts ändert sich vor „Übernehmen“. Umrandet = neu oder verschoben; blass = entfernt.',
    'Voorskou: niks verander voor “Pas toe” nie. Omlyn = nuut of geskuif; verbleik = verwyder.',
  );
  String floorEditApplied(int n) => _t(
    'Plan de salle mis à jour : $n changement(s)',
    'Floor plan updated: $n change(s)',
    'Plano actualizado: $n cambio(s)',
    'Raumplan aktualisiert: $n Änderung(en)',
    'Vloerplan bygewerk: $n verandering(e)',
  );
  String floorChangeKind(String kind) => switch (kind) {
    'add_table' => _t(
      'Nouvelle table',
      'New table',
      'Mesa nueva',
      'Neuer Tisch',
      'Nuwe tafel',
    ),
    'update_table' => _t(
      'Table modifiée',
      'Table changed',
      'Mesa cambiada',
      'Tisch geändert',
      'Tafel verander',
    ),
    'remove_table' => _t(
      'Table retirée',
      'Table removed',
      'Mesa quitada',
      'Tisch entfernt',
      'Tafel verwyder',
    ),
    'add_object' => _t(
      'Nouvel élément',
      'New object',
      'Elemento nuevo',
      'Neues Element',
      'Nuwe voorwerp',
    ),
    'update_object' => _t(
      'Élément déplacé',
      'Object moved',
      'Elemento movido',
      'Element verschoben',
      'Voorwerp geskuif',
    ),
    _ => _t(
      'Élément retiré',
      'Object removed',
      'Elemento quitado',
      'Element entfernt',
      'Voorwerp verwyder',
    ),
  };
  String floorField(String field) => switch (field) {
    'number' => _t('Numéro', 'Number', 'Número', 'Nummer', 'Nommer'),
    'shape' => _t('Forme', 'Shape', 'Forma', 'Form', 'Vorm'),
    'seats' => seatsLabel,
    'position' => _t('Emplacement', 'Position', 'Ubicación', 'Lage', 'Posisie'),
    'size' => _t('Taille', 'Size', 'Tamaño', 'Größe', 'Grootte'),
    _ => _t('Angle', 'Rotation', 'Giro', 'Drehung', 'Draaiing'),
  };
  String floorShapeName(String shape) => switch (shape) {
    'ROUND' => _t('ronde', 'round', 'redonda', 'rund', 'rond'),
    'SQUARE' => _t('carrée', 'square', 'cuadrada', 'quadratisch', 'vierkantig'),
    'RECT' => _t(
      'rectangulaire',
      'rectangle',
      'rectangular',
      'rechteckig',
      'reghoekig',
    ),
    'BAR' => _t('haute', 'high-top', 'alta', 'Stehtisch', 'hoë tafel'),
    _ => shape,
  };
  String get objectNameFr => _t(
    'Nom (français)',
    'Name (French)',
    'Nombre (francés)',
    'Name (Französisch)',
    'Naam (Frans)',
  );
  String get objectNameEn => _t(
    'Nom (anglais)',
    'Name (English)',
    'Nombre (inglés)',
    'Name (Englisch)',
    'Naam (Engels)',
  );
  String get objectIcon => _t('Icône', 'Icon', 'Ícono', 'Symbol', 'Ikoon');
  String get objectShapeRect =>
      _t('Rectangulaire', 'Rectangle', 'Rectangular', 'Eckig', 'Reghoekig');
  String get objectShapeRound => _t('Rond', 'Round', 'Redondo', 'Rund', 'Rond');
  String get objectSize => _t('Taille', 'Size', 'Tamaño', 'Größe', 'Grootte');
  String get placeObject =>
      _t('Placer', 'Place', 'Colocar', 'Platzieren', 'Plaas');
  String get deleteObject => _t(
    'Retirer l’élément',
    'Remove object',
    'Quitar elemento',
    'Element entfernen',
    'Verwyder voorwerp',
  );
  String deleteObjectConfirm(String name) => _t(
    'Retirer « $name » du plan ?',
    'Remove "$name" from the plan?',
    '¿Quitar "$name" del plano?',
    '„$name“ aus dem Plan entfernen?',
    'Verwyder “$name” van die plan?',
  );
  String get objectDeleted => _t(
    'Élément retiré',
    'Object removed',
    'Elemento quitado',
    'Element entfernt',
    'Voorwerp verwyder',
  );

  // check screen
  String get table => _t('Table', 'Table', 'Mesa', 'Tisch', 'Tafel');
  String get bill => _t('Addition', 'Bill', 'Cuenta', 'Rechnung', 'Rekening');

  /// A document number: "n° 12" in French, "#12" in English and US Spanish.
  String numbered(int n) => _t('n°\u00A0$n', '#$n', '#$n', '#$n', '#$n');

  /// "Addition n° 12" / "Bill #12" / "Cuenta #12".
  String billNo(int id) => '$bill ${numbered(id)}';
  String get corkage =>
      _t('Droit de bouchon', 'Corkage', 'Descorche', 'Korkgeld', 'Kurkgeld');
  String get voidBillManager => _t(
    'Annuler l’addition (gérant)',
    'Void bill (manager)',
    'Anular cuenta (gerente)',
    'Rechnung stornieren (Manager)',
    'Kanselleer rekening (bestuurder)',
  );
  String get noItemsYet => _t(
    'Aucun article pour l’instant — touchez le menu pour en ajouter',
    'No items yet — tap the menu to add',
    'Todavía no hay artículos: toca el menú para agregar',
    'Noch keine Artikel – zum Hinzufügen Karte antippen',
    'Nog geen items nie — tik op die spyskaart om by te voeg',
  );
  String pendingFromPhone(int n) => _t(
    'Commandé par téléphone — à confirmer ($n)',
    'Ordered from phone — awaiting confirm ($n)',
    'Pedido desde el celular: por confirmar ($n)',
    'Per Handy bestellt – Bestätigung ausstehend ($n)',
    'Per foon bestel — wag vir bevestiging ($n)',
  );
  String get acceptOrder =>
      _t('Accepter', 'Accept', 'Aceptar', 'Annehmen', 'Aanvaar');
  String get rejectOrder =>
      _t('Refuser', 'Reject', 'Rechazar', 'Ablehnen', 'Weier');
  String get deleteLine =>
      _t('Retirer', 'Remove', 'Quitar', 'Entfernen', 'Verwyder');

  /// "[name] is no longer available" (deleted, 86ed, or that size deleted).
  String itemNoLongerAvailable(String name) => _t(
    '$name n’est plus offert',
    '$name is no longer available',
    '$name ya no está disponible',
    '$name ist nicht mehr verfügbar',
    '$name is nie meer beskikbaar nie',
  );

  /// The menu changed under the screen: the new price, confirm to add.
  String priceChangedTo(String name, String price) => _t(
    'Le prix de $name est maintenant $price',
    'The price of $name changed to $price',
    'El precio de $name cambió a $price',
    'Der Preis von $name ist jetzt $price',
    'Die prys van $name is nou $price',
  );
  String get priceChangedTitle => _t(
    'Prix modifié',
    'Price changed',
    'Precio modificado',
    'Preis geändert',
    'Prys verander',
  );
  String addAtPrice(String price) => _t(
    'Ajouter à $price',
    'Add at $price',
    'Agregar a $price',
    'Für $price hinzufügen',
    'Voeg by teen $price',
  );
  String get menuUpdated => _t(
    'Le menu a été mis à jour',
    'The menu was updated',
    'El menú se actualizó',
    'Die Karte wurde aktualisiert',
    'Die spyskaart is bygewerk',
  );

  String get emptyBillClosed => _t(
    'Addition vide fermée',
    'Empty bill closed',
    'Se cerró la cuenta vacía',
    'Leere Rechnung geschlossen',
    'Leë rekening toegemaak',
  );
  String get total => _t('Total', 'Total', 'Total', 'Gesamt', 'Totaal');
  String get subtotal =>
      _t('Sous-total', 'Subtotal', 'Subtotal', 'Zwischensumme', 'Subtotaal');

  /// A tax added on top, e.g. "NC sales tax 7.25%" / "NC sales tax 7,25 %" (rate is a decimal string).
  /// Data, not a string table: the tax names come from the store.
  /// The report's tax section: each tax collected, for remittance.
  String get taxCollected => _t(
    'Taxes perçues',
    'Tax collected',
    'Impuestos cobrados',
    'Eingenommene Steuern',
    'Belasting ingevorder',
  );
  String get taxTotal => _t(
    'Total des taxes',
    'Total tax',
    'Total de impuestos',
    'Steuern gesamt',
    'Totale belasting',
  );

  /// Who a tax is paid to: "remit to NCDOR".
  String remitTo(String who) => _t(
    'à verser à $who',
    'remit to $who',
    'a pagar a $who',
    'abzuführen an $who',
    'betaalbaar aan $who',
  );

  /// The one combined line ([TaxLine.forGuests]): "Tax (8.25%)", "Taxes (8,25 %)"
  /// — the store's own word when it set one.
  String taxLine(TaxLine tax) =>
      tax.isCombined ? _taxCombined(tax) : _taxItem(tax);

  String _taxCombined(TaxLine tax) {
    final own = lang == 'fr' ? tax.labelFr : tax.labelEn;
    final word = own.trim().isNotEmpty
        ? own
        : _t('Taxes', 'Tax', 'Impuesto', 'Steuer', 'Belasting');
    // the rate always reads "8.25%", like money, in every language
    return '$word (${tax.ratePercent}%)';
  }

  String _taxItem(TaxLine tax) => switch (lang) {
    'fr' => '${tax.labelFr} ${tax.ratePercent}%',
    _ => '${tax.labelEn} ${tax.ratePercent}%',
  };
  String get pay => _t('Payer', 'Pay', 'Cobrar', 'Bezahlen', 'Betaal');
  String get ordersAwaiting => _t(
    'Commandes à confirmer',
    'Orders awaiting confirm',
    'Pedidos por confirmar',
    'Bestellungen zur Bestätigung',
    'Bestellings wat wag vir bevestiging',
  );
  String sizesFrom(int n, String price) => _t(
    '$n formats, dès $price',
    '$n sizes $price+',
    '$n tamaños desde $price',
    '$n Größen ab $price',
    '$n groottes $price+',
  );
  String get qty => _t('Qté', 'Qty', 'Cant.', 'Menge', 'Aantal');
  String get noteHint => _t(
    'Note (ex. : sans glace)',
    'Note (e.g. no ice)',
    'Nota (p. ej., sin hielo)',
    'Notiz (z. B. ohne Eis)',
    'Nota (bv. geen ys nie)',
  );
  String addToBill(String price) => _t(
    'Ajouter à l’addition · $price',
    'Add to bill $price',
    'Agregar a la cuenta · $price',
    'Auf Rechnung $price',
    'Voeg by rekening $price',
  );

  // void flow
  String get voidApprovalTitle => _t(
    'Annuler l’addition — approbation du gérant',
    'Void bill — manager approval',
    'Anular cuenta: aprobación del gerente',
    'Rechnung stornieren – Freigabe durch Manager',
    'Kanselleer rekening — bestuurder se goedkeuring',
  );
  String get voidReasonTitle => _t(
    'Motif de l’annulation',
    'Void reason',
    'Motivo de la anulación',
    'Stornogrund',
    'Rede vir kansellasie',
  );
  List<String> get voidReasons => [
    _t(
      'Mauvaise table',
      'Wrong table',
      'Mesa equivocada',
      'Falscher Tisch',
      'Verkeerde tafel',
    ),
    _t(
      'Le client a changé d’idée',
      'Customer changed mind',
      'El cliente cambió de opinión',
      'Gast hat es sich anders überlegt',
      'Klant het van plan verander',
    ),
    _t(
      'Mauvais prix',
      'Wrong price',
      'Precio equivocado',
      'Falscher Preis',
      'Verkeerde prys',
    ),
    _t(
      'Test du système',
      'System test',
      'Prueba del sistema',
      'Systemtest',
      'Stelseltoets',
    ),
  ];
  String get otherReason => _t(
    'Autre motif',
    'Other reason',
    'Otro motivo',
    'Anderer Grund',
    'Ander rede',
  );
  // void a bill that already has payments: hand them back first
  String get voidPaidTitle => _t(
    'Cette addition a déjà des paiements',
    'This bill already has payments',
    'Esta cuenta ya tiene pagos',
    'Diese Rechnung hat bereits Zahlungen',
    'Hierdie rekening het reeds betalings',
  );
  String voidPaidBody(String amount) => _t(
    '$amount a été payé. Rendez-le au client (comptant de la caisse, carte remboursée sur son terminal), puis l’addition est annulée. Un gérant doit approuver.',
    '$amount was paid. Hand it back to the guest (cash from the drawer, a card refunded on its terminal), then the bill is voided. A manager must approve.',
    'Se pagó $amount. Devuélvelo al cliente (efectivo de la caja, tarjeta reembolsada en su terminal) y luego se anula la cuenta. Debe aprobarlo un gerente.',
    '$amount wurde bezahlt. Dem Gast zurückgeben (Bargeld aus der Kasse, Karte am Terminal erstatten), dann wird die Rechnung storniert. Ein Manager muss freigeben.',
    '$amount is betaal. Gee dit terug aan die gas (kontant uit die laai, ’n kaart terugbetaal op sy terminaal), dan word die rekening gekanselleer. ’n Bestuurder moet goedkeur.',
  );
  String get handBackAndVoid => _t(
    'Rendre et annuler',
    'Hand back and void',
    'Devolver y anular',
    'Zurückgeben und stornieren',
    'Gee terug en kanselleer',
  );
  String get rejectAllOrders => _t(
    'Tout refuser',
    'Reject all',
    'Rechazar todo',
    'Alle ablehnen',
    'Weier alles',
  );
  String voidedBill(int id) => _t(
    'Addition n° $id annulée',
    'Bill #$id voided',
    'Cuenta #$id anulada',
    'Rechnung #$id storniert',
    'Rekening #$id gekanselleer',
  );

  // corkage dialog
  String get corkageTitle =>
      _t('Droit de bouchon', 'Corkage', 'Descorche', 'Korkgeld', 'Kurkgeld');
  String get bottlesBrought => _t(
    'Bouteilles apportées par le client',
    'Bottles brought by customer',
    'Botellas que trajo el cliente',
    'Vom Gast mitgebrachte Flaschen',
    'Bottels deur klant saamgebring',
  );

  // tender screen
  String get outstanding =>
      _t('À payer', 'Due', 'Por pagar', 'Offen', 'Verskuldig');
  String paidOf(String paid, String total) => _t(
    'Payé $paid sur $total',
    'Paid $paid / $total',
    'Pagado $paid de $total',
    'Bezahlt $paid / $total',
    'Betaal $paid / $total',
  );
  String get cash => _t('Comptant', 'Cash', 'Efectivo', 'Bar', 'Kontant');
  String get card => _t('Carte', 'Card', 'Tarjeta', 'Karte', 'Kaart');
  String get bankTransfer =>
      _t('Virement', 'Transfer', 'Transferencia', 'Überweisung', 'Oorplasing');
  String get cashInHint => _t(
    'Comptant reçu ($cur) — paiement partiel accepté',
    'Cash received ($cur) — partial OK',
    'Efectivo recibido ($cur): se acepta pago parcial',
    'Bar erhalten ($cur) – Teilbetrag möglich',
    'Kontant ontvang ($cur) — gedeeltelik OK',
  );
  String get receive =>
      _t('Encaisser', 'Receive', 'Cobrar', 'Kassieren', 'Ontvang');
  String amountHint(String due) => _t(
    'Montant ($cur) — vide = la totalité, $due',
    'Amount ($cur) — blank = full $due',
    'Monto ($cur): vacío = el total, $due',
    'Betrag ($cur) – leer = voller Betrag $due',
    'Bedrag ($cur) — leeg = volle $due',
  );
  String get useCardTerminal => _t(
    'Utiliser le terminal de paiement',
    'Use card terminal',
    'Usar la terminal de tarjetas',
    'Kartenterminal verwenden',
    'Gebruik kaartterminaal',
  );
  String get showBankAccount => _t(
    'Afficher les coordonnées bancaires',
    'Show account details',
    'Mostrar datos de la cuenta',
    'Kontodaten anzeigen',
    'Wys rekeningbesonderhede',
  );
  String amountToPay(String amount) => _t(
    'Montant à payer : $amount',
    'Amount due $amount',
    'Monto a pagar: $amount',
    'Offener Betrag $amount',
    'Bedrag verskuldig $amount',
  );
  String get confirmMoneyIn => _t(
    'Confirmer — paiement reçu',
    'Confirm — money received',
    'Confirmar: dinero recibido',
    'Bestätigen – Geld erhalten',
    'Bevestig — geld ontvang',
  );
  String get cashReceivedTitle => _t(
    'Comptant reçu ✓',
    'Cash received ✓',
    'Efectivo recibido ✓',
    'Bar erhalten ✓',
    'Kontant ontvang ✓',
  );
  String get cashReceived => _t(
    'Comptant reçu',
    'Cash received',
    'Efectivo recibido',
    'Bar erhalten',
    'Kontant ontvang',
  );
  String get rounding =>
      _t('Arrondi', 'Rounding', 'Redondeo', 'Rundung', 'Afronding');
  String get cashTotal => _t(
    'Total comptant',
    'Cash total',
    'Total en efectivo',
    'Barbetrag',
    'Kontanttotaal',
  );
  String get change =>
      _t('Monnaie', 'Change', 'Cambio', 'Rückgeld', 'Kleingeld');
  String receivedToast(String amount, String due) => _t(
    '$amount reçu — reste $due à payer',
    'Received $amount — $due outstanding',
    'Recibido $amount: faltan $due',
    '$amount erhalten – $due offen',
    '$amount ontvang — $due uitstaande',
  );

  // Card (Stripe) — optional tender, test mode, simulated reader
  String get cardStripe => _t(
    'Carte (Stripe)',
    'Card (Stripe)',
    'Tarjeta (Stripe)',
    'Karte (Stripe)',
    'Kaart (Stripe)',
  );
  String get chargeCardStripe => _t(
    'Payer par carte (Stripe)',
    'Charge card (Stripe)',
    'Cobrar con tarjeta (Stripe)',
    'Karte belasten (Stripe)',
    'Belas kaart (Stripe)',
  );
  String get simulatedCardLabel => _t(
    'Carte simulée (mode test)',
    'Simulated card (test mode)',
    'Tarjeta simulada (modo de prueba)',
    'Simulierte Karte (Testmodus)',
    'Gesimuleerde kaart (toetsmodus)',
  );
  String get simApproved =>
      _t('Approuvée', 'Approved', 'Aprobada', 'Genehmigt', 'Goedgekeur');
  String get simDeclined =>
      _t('Refusée', 'Declined', 'Rechazada', 'Abgelehnt', 'Geweier');
  String get simInsufficient => _t(
    'Fonds insuffisants',
    'Insufficient funds',
    'Fondos insuficientes',
    'Deckung nicht ausreichend',
    'Onvoldoende fondse',
  );
  String get stripeTitle => _t(
    'Paiement par carte (Stripe)',
    'Card payment (Stripe)',
    'Pago con tarjeta (Stripe)',
    'Kartenzahlung (Stripe)',
    'Kaartbetaling (Stripe)',
  );
  String get stripeTestMode => _t(
    'MODE TEST — lecteur simulé, aucun argent réel',
    'TEST MODE — simulated reader, no real money',
    'MODO DE PRUEBA: lector simulado, sin dinero real',
    'TESTMODUS – simuliertes Lesegerät, kein echtes Geld',
    'TOETSMODUS — gesimuleerde leser, geen regte geld nie',
  );
  String get stripePreparing => _t(
    'Préparation du paiement…',
    'Preparing the payment…',
    'Preparando el pago…',
    'Zahlung wird vorbereitet…',
    'Berei die betaling voor…',
  );
  String get stripePermissions => _t(
    'Vérification des autorisations Bluetooth et de localisation…',
    'Checking Bluetooth and location permission…',
    'Revisando los permisos de Bluetooth y ubicación…',
    'Bluetooth- und Standortberechtigung wird geprüft…',
    'Kontroleer Bluetooth- en liggingtoestemming…',
  );
  String get stripeConnecting => _t(
    'Connexion au lecteur de carte (simulé)…',
    'Connecting to the card reader (simulated)…',
    'Conectando con el lector de tarjetas (simulado)…',
    'Verbindung zum Kartenleser (simuliert)…',
    'Koppel aan die kaartleser (gesimuleer)…',
  );
  String get stripeTapCard => _t(
    'Présentez, insérez ou glissez la carte (simulé)',
    'Tap, insert or swipe the card (simulated)',
    'Acerca, inserta o desliza la tarjeta (simulado)',
    'Karte auflegen, einstecken oder durchziehen (simuliert)',
    'Tik, steek in of swiep die kaart (gesimuleer)',
  );
  String get stripeProcessing => _t(
    'Traitement du paiement…',
    'Processing…',
    'Procesando…',
    'Wird verarbeitet…',
    'Verwerk…',
  );
  String get stripeApproved => _t(
    'Paiement approuvé',
    'Payment approved',
    'Pago aprobado',
    'Zahlung genehmigt',
    'Betaling goedgekeur',
  );
  String get stripeDeclinedTitle => _t(
    'Carte refusée',
    'Card declined',
    'Tarjeta rechazada',
    'Karte abgelehnt',
    'Kaart geweier',
  );
  String get stripeErrorTitle => _t(
    'Échec du paiement par carte',
    'Card payment failed',
    'Falló el pago con tarjeta',
    'Kartenzahlung fehlgeschlagen',
    'Kaartbetaling het misluk',
  );
  String get nothingCharged => _t(
    'Rien n’a été débité. L’addition peut être réglée autrement.',
    'Nothing was charged. The bill can be paid another way.',
    'No se cobró nada. La cuenta se puede pagar de otra forma.',
    'Es wurde nichts belastet. Die Rechnung kann anders bezahlt werden.',
    'Niks is gehef nie. Die rekening kan op ’n ander manier betaal word.',
  );

  /// Why "Card (Stripe)" is greyed out (hint under the tender tiles).
  String stripeUnavailableHint(String? reason) => switch (reason) {
    'stripe_unavailable' => _t(
      'Pas d’Internet — la carte (Stripe) est indisponible',
      'No internet — Card (Stripe) is unavailable',
      'Sin internet: la tarjeta (Stripe) no está disponible',
      'Kein Internet – Karte (Stripe) nicht verfügbar',
      'Geen internet nie — Kaart (Stripe) is nie beskikbaar nie',
    ),
    'stripe_live_key_refused' => _t(
      'Stripe désactivé : seules les clés de test (sk_test_) sont acceptées',
      'Stripe is off: only test keys (sk_test_) are accepted',
      'Stripe está desactivado: solo se aceptan claves de prueba (sk_test_)',
      'Stripe ist aus: nur Testschlüssel (sk_test_) werden akzeptiert',
      'Stripe is af: slegs toetssleutels (sk_test_) word aanvaar',
    ),
    'stripe_permission_denied' => _t(
      'Autorisation Bluetooth ou localisation refusée — carte (Stripe) désactivée',
      'Bluetooth/location permission denied — Card (Stripe) disabled',
      'Se negó el permiso de Bluetooth o ubicación: tarjeta (Stripe) desactivada',
      'Bluetooth-/Standortberechtigung verweigert – Karte (Stripe) deaktiviert',
      'Bluetooth-/liggingtoestemming geweier — Kaart (Stripe) gedeaktiveer',
    ),
    'stripe_unsupported' => _t(
      'La carte (Stripe) fonctionne seulement sur la tablette Android',
      'Card (Stripe) works on the Android tablet only',
      'La tarjeta (Stripe) solo funciona en la tableta Android',
      'Karte (Stripe) funktioniert nur auf dem Android-Tablet',
      'Kaart (Stripe) werk slegs op die Android-tablet',
    ),
    'stripe_location_required' => _t(
      'Stripe : aucun emplacement Terminal (définissez STRIPE_LOCATION_ID)',
      'Stripe: no Terminal location (set STRIPE_LOCATION_ID)',
      'Stripe: no hay ubicación de Terminal (configura STRIPE_LOCATION_ID)',
      'Stripe: kein Terminal-Standort (STRIPE_LOCATION_ID setzen)',
      'Stripe: geen Terminal-ligging nie (stel STRIPE_LOCATION_ID)',
    ),
    'stripe_currency_mismatch' => _t(
      'Stripe désactivé : le compte Stripe n’est pas en CAD',
      'Stripe is off: the Stripe account is not in the store’s currency',
      'Stripe está desactivado: la cuenta de Stripe no está en la moneda de la tienda',
      'Stripe ist aus: das Stripe-Konto ist nicht in der Währung des Geschäfts',
      'Stripe is af: die Stripe-rekening is nie in die winkel se geldeenheid nie',
    ),
    _ => _t(
      'La carte (Stripe) est indisponible pour le moment',
      'Card (Stripe) is unavailable right now',
      'La tarjeta (Stripe) no está disponible por ahora',
      'Karte (Stripe) ist gerade nicht verfügbar',
      'Kaart (Stripe) is nou nie beskikbaar nie',
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
    ),
    'expired_card' => _t(
      'Carte expirée. Essayez une autre carte.',
      'The card has expired. Try another card.',
      'La tarjeta está vencida. Prueba con otra tarjeta.',
      'Die Karte ist abgelaufen. Andere Karte versuchen.',
      'Die kaart het verval. Probeer ’n ander kaart.',
    ),
    'incorrect_pin' || 'incorrect_cvc' || 'invalid_pin' => _t(
      'NIP incorrect. Réessayez ou utilisez une autre carte.',
      'Incorrect PIN. Try again or use another card.',
      'PIN incorrecto. Inténtalo de nuevo o usa otra tarjeta.',
      'Falsche PIN. Erneut versuchen oder andere Karte verwenden.',
      'Verkeerde PIN. Probeer weer of gebruik ’n ander kaart.',
    ),
    'pin_try_exceeded' => _t(
      'Trop d’essais de NIP. Utilisez une autre carte.',
      'Too many PIN attempts. Use another card.',
      'Demasiados intentos de PIN. Usa otra tarjeta.',
      'Zu viele PIN-Versuche. Andere Karte verwenden.',
      'Te veel PIN-pogings. Gebruik ’n ander kaart.',
    ),
    _ => _t(
      'La carte a été refusée. Essayez une autre carte ou un autre mode de paiement.',
      'The card was declined. Try another card or another way to pay.',
      'La tarjeta fue rechazada. Prueba con otra tarjeta u otra forma de pago.',
      'Die Karte wurde abgelehnt. Andere Karte oder Zahlungsart versuchen.',
      'Die kaart is geweier. Probeer ’n ander kaart of betaalmetode.',
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
    ),
    'tokenFailed' => _t(
      'Le magasin n’a pas pu obtenir de jeton de connexion Stripe.',
      'The store couldn\'t get a Stripe connection token.',
      'La tienda no pudo obtener un token de conexión de Stripe.',
      'Die Kasse hat kein Stripe-Verbindungstoken erhalten.',
      'Die winkel kon nie ’n Stripe-verbindingstoken kry nie.',
    ),
    'stripeApi' => _t(
      'Stripe a refusé la demande du lecteur.',
      'Stripe refused the reader request.',
      'Stripe rechazó la solicitud del lector.',
      'Stripe hat die Anfrage des Lesegeräts abgelehnt.',
      'Stripe het die leser se versoek geweier.',
    ),
    'permissionDenied' => _t(
      'L’autorisation « Position » ou « Appareils à proximité » a été refusée à l’application.',
      'The app\'s Location or Nearby devices permission was denied.',
      'Se negó el permiso de Ubicación o Dispositivos cercanos de la app.',
      'Die Berechtigung für Standort oder Geräte in der Nähe wurde verweigert.',
      'Die toep se toestemming vir ligging of nabygeleë toestelle is geweier.',
    ),
    'locationOff' => _t(
      'La localisation de l’appareil est désactivée. Activez-la, puis réessayez.',
      'Device Location is off. Turn it on, then try again.',
      'La ubicación del dispositivo está desactivada. Actívala e inténtalo de nuevo.',
      'Der Gerätestandort ist aus. Bitte einschalten und erneut versuchen.',
      'Toestelligging is af. Skakel dit aan en probeer dan weer.',
    ),
    'bluetoothOff' => _t(
      'Le Bluetooth est désactivé. Activez-le, puis réessayez.',
      'Bluetooth is off. Turn it on, then try again.',
      'El Bluetooth está desactivado. Actívalo e inténtalo de nuevo.',
      'Bluetooth ist aus. Bitte einschalten und erneut versuchen.',
      'Bluetooth is af. Skakel dit aan en probeer dan weer.',
    ),
    'unsupported' => _t(
      'La carte (Stripe) fonctionne seulement sur la tablette Android.',
      'Card (Stripe) works on the Android tablet only.',
      'La tarjeta (Stripe) solo funciona en la tableta Android.',
      'Karte (Stripe) funktioniert nur auf dem Android-Tablet.',
      'Kaart (Stripe) werk slegs op die Android-tablet.',
    ),
    _ => _t(
      'Le lecteur de carte (simulé) a échoué.',
      'The card reader (simulated) failed.',
      'Falló el lector de tarjetas (simulado).',
      'Der Kartenleser (simuliert) ist fehlgeschlagen.',
      'Die kaartleser (gesimuleer) het misluk.',
    ),
  };
  String get openLocationSettings => _t(
    'Ouvrir les paramètres de localisation',
    'Open Location settings',
    'Abrir la configuración de ubicación',
    'Standorteinstellungen öffnen',
    'Maak ligginginstellings oop',
  );
  String get openBluetoothSettings => _t(
    'Ouvrir les paramètres Bluetooth',
    'Open Bluetooth settings',
    'Abrir la configuración de Bluetooth',
    'Bluetooth-Einstellungen öffnen',
    'Maak Bluetooth-instellings oop',
  );
  String get openAppSettings => _t(
    'Ouvrir les autorisations de l’application',
    'Open app permissions',
    'Abrir los permisos de la app',
    'App-Berechtigungen öffnen',
    'Maak toeptoestemmings oop',
  );
  String errorCode(String code) => _t(
    'Code : $code',
    'Code: $code',
    'Código: $code',
    'Code: $code',
    'Kode: $code',
  );

  // table ops: move / merge
  String get moveMerge => _t(
    'Déplacer / fusionner',
    'Move / merge',
    'Mover / combinar',
    'Umsetzen / zusammenlegen',
    'Skuif / voeg saam',
  );
  String moveMergeTitle(int billId) => _t(
    'Déplacer l’addition n° $billId vers…',
    'Move Bill #$billId to…',
    'Mover la cuenta #$billId a…',
    'Rechnung #$billId umsetzen nach…',
    'Skuif rekening #$billId na…',
  );
  String get thisBill => _t(
    'Cette addition',
    'This bill',
    'Esta cuenta',
    'Diese Rechnung',
    'Hierdie rekening',
  );
  String get beingPaid => _t(
    'Paiement en cours',
    'Being paid',
    'Pago en curso',
    'Wird bezahlt',
    'Word betaal',
  );
  String get mergeConfirmTitle => _t(
    'Fusionner les additions',
    'Merge bills',
    'Combinar cuentas',
    'Rechnungen zusammenlegen',
    'Voeg rekenings saam',
  );
  String mergeConfirmBody(int src, int dest, String destLabel) => _t(
    'Fusionner l’addition n° $src avec l’addition n° $dest (table $destLabel) ? Tous les articles passent sur l’addition de destination.',
    'Merge Bill #$src into Bill #$dest (table $destLabel)? All items move to the destination bill.',
    '¿Combinar la cuenta #$src con la cuenta #$dest (mesa $destLabel)? Todos los artículos pasan a la cuenta de destino.',
    'Rechnung #$src mit Rechnung #$dest (Tisch $destLabel) zusammenlegen? Alle Artikel wandern auf die Zielrechnung.',
    'Voeg rekening #$src by rekening #$dest (tafel $destLabel) saam? Alle items skuif na die bestemmingsrekening.',
  );
  String get merge =>
      _t('Fusionner', 'Merge', 'Combinar', 'Zusammenlegen', 'Voeg saam');
  String movedToast(String label) => _t(
    'Déplacée à la table $label',
    'Moved to table $label',
    'Se movió a la mesa $label',
    'Auf Tisch $label umgesetzt',
    'Geskuif na tafel $label',
  );
  String mergedToast(int dest) => _t(
    'Fusionnée avec l’addition n° $dest',
    'Merged into Bill #$dest',
    'Se combinó con la cuenta #$dest',
    'Mit Rechnung #$dest zusammengelegt',
    'Saamgevoeg by rekening #$dest',
  );

  // open / misc item
  String get openItem => _t(
    'Article libre',
    'Open item',
    'Artículo libre',
    'Freier Artikel',
    'Oop item',
  );
  String get openItemName => _t(
    'Nom de l’article',
    'Item name',
    'Nombre del artículo',
    'Artikelname',
    'Itemnaam',
  );
  String get openItemPrice => _t(
    'Prix ($cur)',
    'Price ($cur)',
    'Precio ($cur)',
    'Preis ($cur)',
    'Prys ($cur)',
  );

  // split checks (settlement-time bill groups)
  String get splitBill => _t(
    'Séparer l’addition',
    'Split bill',
    'Dividir la cuenta',
    'Rechnung teilen',
    'Verdeel rekening',
  );
  String get splitByItems =>
      _t('Par article', 'By item', 'Por artículo', 'Nach Artikel', 'Per item');
  String get splitEvenly => _t(
    'Parts égales',
    'Split evenly',
    'Partes iguales',
    'Gleichmäßig teilen',
    'Verdeel gelykop',
  );
  String get splitHowManyWays => _t(
    'En combien de parts ?',
    'Split how many ways?',
    '¿En cuántas partes?',
    'In wie viele Teile?',
    'In hoeveel dele verdeel?',
  );
  String groupTitle(int n) =>
      _t('Addition $n', 'Bill $n', 'Cuenta $n', 'Rechnung $n', 'Rekening $n');
  String get addGroup => _t(
    'Ajouter une addition',
    'Add bill',
    'Agregar cuenta',
    'Rechnung hinzufügen',
    'Voeg rekening by',
  );
  String get unassignedItems => _t(
    'Non attribués',
    'Unassigned',
    'Sin asignar',
    'Nicht zugeordnet',
    'Nie toegewys nie',
  );
  String get allItemsAssigned => _t(
    'Tous les articles sont attribués',
    'All items assigned',
    'Todos los artículos están asignados',
    'Alle Artikel zugeordnet',
    'Alle items toegewys',
  );
  String get tapToAssignHint => _t(
    'Touchez un article pour le déplacer vers l’addition sélectionnée',
    'Tap an item to move it to the selected bill',
    'Toca un artículo para pasarlo a la cuenta seleccionada',
    'Artikel antippen, um ihn auf die gewählte Rechnung zu verschieben',
    'Tik op ’n item om dit na die gekose rekening te skuif',
  );
  String get paid =>
      _t('Payée ✓', 'Paid ✓', 'Pagada ✓', 'Bezahlt ✓', 'Betaal ✓');
  String get clearSplit => _t(
    'Annuler la séparation',
    'Clear split',
    'Deshacer la división',
    'Teilung aufheben',
    'Hef verdeling op',
  );
  String get clearSplitConfirm => _t(
    'Tout regrouper sur une seule addition ?',
    'Merge everything back into one bill?',
    '¿Juntar todo otra vez en una sola cuenta?',
    'Alles wieder zu einer Rechnung zusammenlegen?',
    'Voeg alles weer saam in een rekening?',
  );
  String groupPaidToast(int n) => _t(
    'Addition $n payée',
    'Bill $n paid',
    'Cuenta $n pagada',
    'Rechnung $n bezahlt',
    'Rekening $n betaal',
  );
  String get moveCorkageTitle => _t(
    'Déplacer le droit de bouchon vers…',
    'Move corkage to…',
    'Mover el descorche a…',
    'Korkgeld verschieben nach…',
    'Skuif kurkgeld na…',
  );
  String get deleteGroup => _t(
    'Retirer cette addition',
    'Remove this bill',
    'Quitar esta cuenta',
    'Diese Rechnung entfernen',
    'Verwyder hierdie rekening',
  );
  String get peelOne => _t('Un seul', '1 only', 'Solo 1', 'Nur 1', 'Slegs 1');

  // receipt
  String get receipt => _t('Reçu', 'Receipt', 'Recibo', 'Beleg', 'Kwitansie');

  // provisional bill ("check please")
  String get printBill => _t(
    'Imprimer l’addition',
    'Print bill',
    'Imprimir la cuenta',
    'Rechnung drucken',
    'Druk rekening',
  );
  String get customerBill => _t(
    'Addition du client',
    'Customer bill',
    'Cuenta del cliente',
    'Gästerechnung',
    'Klantrekening',
  );
  String get notAReceipt => _t(
    'Ceci n’est pas un reçu',
    'Not a receipt',
    'No es un recibo',
    'Kein Beleg',
    'Nie ’n kwitansie nie',
  );
  String get printedAt => _t(
    'Imprimée à',
    'Printed at',
    'Impresa a las',
    'Gedruckt am',
    'Gedruk om',
  );
  String get printAgain => _t(
    'Imprimer de nouveau',
    'Print again',
    'Volver a imprimir',
    'Erneut drucken',
    'Druk weer',
  );
  String get printIn => _t(
    'Imprimer en…',
    'Print in…',
    'Imprimir en…',
    'Drucken auf…',
    'Druk in…',
  );

  // shift screen
  String get noShiftOpen => _t(
    'Aucun quart ouvert',
    'No shift open',
    'No hay turno abierto',
    'Keine Schicht offen',
    'Geen skof oop nie',
  );
  String get openingFloat => _t(
    'Fonds de caisse ($cur)',
    'Opening float ($cur)',
    'Fondo inicial ($cur)',
    'Wechselgeld ($cur)',
    'Beginkontant ($cur)',
  );
  String get openShift => _t(
    'Ouvrir le quart',
    'Open shift',
    'Abrir turno',
    'Schicht öffnen',
    'Open skof',
  );
  String get openShiftApproval => _t(
    'Ouvrir le quart — approbation du gérant',
    'Open shift — manager approval',
    'Abrir turno: aprobación del gerente',
    'Schicht öffnen – Freigabe durch Manager',
    'Open skof — bestuurder se goedkeuring',
  );
  String get openShiftPromptTitle => _t(
    'Aucun quart de caisse ouvert',
    'No cash drawer shift open',
    'No hay turno de caja abierto',
    'Keine Kassenschicht offen',
    'Geen kontantlaaiskof oop nie',
  );
  String get openShiftPromptBody => _t(
    'Ouvrir un maintenant pour prendre des espèces ?',
    'Open one now to take cash?',
    '¿Abrir uno ahora para cobrar en efectivo?',
    'Jetzt eine öffnen, um Bargeld anzunehmen?',
    'Open nou een om kontant te neem?',
  );
  String get notNow =>
      _t('Plus tard', 'Not now', 'Ahora no', 'Nicht jetzt', 'Nie nou nie');
  String changeDue(String amount) => _t(
    'Monnaie à rendre : $amount',
    'Change due $amount',
    'Cambio a devolver: $amount',
    'Rückgeld $amount',
    'Kleingeld verskuldig $amount',
  );

  /// A floor-plan table whose bill is partly paid (a split bill settled).
  String get partPaid => _t(
    'Payé en partie',
    'Part paid',
    'Pagado en parte',
    'Teilweise bezahlt',
    'Deels betaal',
  );

  /// A part-paid table's tile: what is still owed ("$15.57 left").
  String balanceLeft(String amount) => _t(
    'Reste $amount',
    '$amount left',
    'Faltan $amount',
    'Noch $amount',
    '$amount oor',
  );

  /// The pay screen kept a digit out: the cash would be more than due + $1,000.
  String cashEntryCapped(String max) => _t(
    'Au plus $max en comptant pour cette addition',
    'At most $max in cash for this bill',
    'Como máximo $max en efectivo para esta cuenta',
    'Höchstens $max in bar für diese Rechnung',
    'Hoogstens $max kontant vir hierdie rekening',
  );
  String get overDueTitle => _t(
    'Montant plus élevé que le solde',
    'Amount is more than the bill',
    'El monto supera la cuenta',
    'Betrag höher als die Rechnung',
    'Bedrag is meer as die rekening',
  );
  String overDueBody(String amount, String due) => _t(
    'Débiter $amount alors qu’il reste $due ?',
    'Charge $amount when only $due is due?',
    '¿Cobrar $amount cuando solo se deben $due?',
    '$amount belasten, obwohl nur $due offen sind?',
    'Hef $amount wanneer slegs $due verskuldig is?',
  );
  String get chargeAnyway => _t(
    'Débiter quand même',
    'Charge anyway',
    'Cobrar de todos modos',
    'Trotzdem belasten',
    'Hef in elk geval',
  );
  String get closeShiftApproval => _t(
    'Fermer le quart (Z) — approbation du gérant',
    'Close shift (Z) — manager approval',
    'Cerrar turno (Z): aprobación del gerente',
    'Schicht schließen (Z) – Freigabe durch Manager',
    'Sluit skof (Z) — bestuurder se goedkeuring',
  );
  String shiftOpenTitle(int id) => _t(
    'Quart n° $id — ouvert',
    'Shift #$id — open',
    'Turno #$id: abierto',
    'Schicht #$id – offen',
    'Skof #$id — oop',
  );
  String shiftOpenedLine(String at, String by, String float) => _t(
    'Ouvert le $at par $by • fonds de caisse $float',
    'Opened $at by $by • float $float',
    'Abierto el $at por $by • fondo $float',
    'Geöffnet $at von $by • Wechselgeld $float',
    'Oopgemaak $at deur $by • beginkontant $float',
  );
  String get closeShiftZ => _t(
    'Fermer le quart (rapport Z)',
    'Close shift (Z-Report)',
    'Cerrar turno (reporte Z)',
    'Schicht schließen (Z-Bericht)',
    'Sluit skof (Z-verslag)',
  );
  String get countedCash => _t(
    'Comptant compté ($cur)',
    'Counted cash ($cur)',
    'Efectivo contado ($cur)',
    'Gezähltes Bargeld ($cur)',
    'Getelde kontant ($cur)',
  );
  String get closeShift => _t(
    'Fermer le quart',
    'Close shift',
    'Cerrar turno',
    'Schicht schließen',
    'Sluit skof',
  );
  String get zReportDone => _t(
    'Quart fermé — rapport Z',
    'Shift closed — Z-Report',
    'Turno cerrado: reporte Z',
    'Schicht geschlossen – Z-Bericht',
    'Skof gesluit — Z-verslag',
  );
  String get revenue => _t('Ventes', 'Revenue', 'Ventas', 'Umsatz', 'Inkomste');
  String get billCount =>
      _t('Additions', 'Bills', 'Cuentas', 'Rechnungen', 'Rekenings');
  String get avgPerBill => _t(
    'Moyenne par addition',
    'Avg per bill',
    'Promedio por cuenta',
    'Ø pro Rechnung',
    'Gem. per rekening',
  );
  String get byTender => _t(
    'Par mode de paiement',
    'By tender',
    'Por forma de pago',
    'Nach Zahlungsart',
    'Per betaalmetode',
  );
  String get topItems => _t(
    'Meilleurs vendeurs',
    'Top items',
    'Más vendidos',
    'Topseller',
    'Topitems',
  );
  String get voidedBills => _t(
    'Additions annulées',
    'Voided bills',
    'Cuentas anuladas',
    'Stornierte Rechnungen',
    'Gekanselleerde rekenings',
  );
  String get expectedCash => _t(
    'Comptant attendu',
    'Expected cash',
    'Efectivo esperado',
    'Bar-Soll',
    'Verwagte kontant',
  );
  String get countedActual =>
      _t('Compté', 'Counted', 'Contado', 'Gezählt', 'Getel');
  String get overShort => _t(
    'Surplus / manque',
    'Over/short',
    'Sobrante / faltante',
    'Differenz',
    'Oor/kort',
  );
  String get xReport =>
      _t('Rapport X', 'X-Report', 'Reporte X', 'X-Bericht', 'X-verslag');
  // shift reconciliation: cash movements + refunds
  String get paidInOut => _t(
    'Entrées / sorties de caisse',
    'Paid in / out',
    'Entradas / salidas de caja',
    'Einlagen / Entnahmen',
    'Inbetaal / uitbetaal',
  );
  // card tips on the X / Z / range reports
  String get cardTips => _t(
    'Pourboires par carte',
    'Card tips',
    'Propinas con tarjeta',
    'Kartentrinkgeld',
    'Kaartfooitjies',
  );
  String get tipsByServer => _t(
    'Pourboires par serveur',
    'Tips by server',
    'Propinas por mesero',
    'Trinkgeld pro Bedienung',
    'Fooitjies per kelner',
  );
  String tipCount(int n) => _t(
    '$n pourboire${n == 1 ? '' : 's'}',
    '$n tip${n == 1 ? '' : 's'}',
    '$n propina${n == 1 ? '' : 's'}',
    '$n Trinkgeld${n == 1 ? '' : 'er'}',
    '$n fooitjie${n == 1 ? '' : 's'}',
  );
  String voidReversed(String amount) => _t(
    'paiements rendus $amount',
    'payments handed back $amount',
    'pagos devueltos $amount',
    'Zahlungen zurückgegeben $amount',
    'betalings teruggegee $amount',
  );
  String get cashRefunds => _t(
    'Remboursements en comptant',
    'Cash refunds',
    'Reembolsos en efectivo',
    'Bar-Erstattungen',
    'Kontantterugbetalings',
  );
  String get cashRounding => _t(
    'Arrondi du comptant',
    'Cash rounding',
    'Redondeo del efectivo',
    'Barrundung',
    'Kontantafronding',
  );

  // refunds — return money on a finalized (CLOSED) bill
  String get refunds => _t(
    'Remboursements',
    'Refunds',
    'Reembolsos',
    'Erstattungen',
    'Terugbetalings',
  );
  String get salesTitle => _t(
    'Additions fermées',
    'Closed bills',
    'Cuentas cerradas',
    'Abgeschlossene Rechnungen',
    'Geslote rekenings',
  );
  String get refundTitle => _t(
    'Rembourser l’addition',
    'Refund bill',
    'Reembolsar la cuenta',
    'Rechnung erstatten',
    'Betaal rekening terug',
  );
  String get refundOverrideTitle => _t(
    'Rembourser autrement que le paiement ?',
    'Refund another way than the guest paid?',
    '¿Reembolsar de otra forma que el pago?',
    'Anders erstatten als bezahlt?',
    'Terugbetaal anders as wat die gas betaal het?',
  );
  String get refundOverrideBody => _t(
    'Le client n’a pas payé ce montant de cette façon. Seul un gérant peut l’autoriser ; ce sera noté sur le remboursement.',
    'The guest didn’t pay this much that way. Only a manager can allow it; it is noted on the refund.',
    'El cliente no pagó tanto de esa forma. Solo un gerente puede permitirlo; quedará anotado en el reembolso.',
    'Der Gast hat so viel nicht auf diese Art bezahlt. Nur ein Manager kann das erlauben; es wird bei der Erstattung vermerkt.',
    'Die gas het nie soveel op dié manier betaal nie. Net ’n bestuurder kan dit toelaat; dit word op die terugbetaling aangeteken.',
  );
  String get managerOverride => _t(
    'Autorisation du gérant',
    'Manager override',
    'Autorización del gerente',
    'Manager-Freigabe',
    'Bestuurder se magtiging',
  );
  String get refundApprovalTitle => _t(
    'Remboursement — approbation du gérant',
    'Refund — manager approval',
    'Reembolso: aprobación del gerente',
    'Erstattung – Freigabe durch Manager',
    'Terugbetaling — bestuurder se goedkeuring',
  );
  String get refundReasonTitle => _t(
    'Motif du remboursement',
    'Refund reason',
    'Motivo del reembolso',
    'Erstattungsgrund',
    'Rede vir terugbetaling',
  );
  List<String> get refundReasons => [
    _t(
      'Mauvais article',
      'Wrong item',
      'Artículo equivocado',
      'Falscher Artikel',
      'Verkeerde item',
    ),
    _t(
      'Le client a changé d’idée',
      'Customer changed mind',
      'El cliente cambió de opinión',
      'Gast hat es sich anders überlegt',
      'Klant het van plan verander',
    ),
    _t(
      'Problème de qualité',
      'Quality issue',
      'Problema de calidad',
      'Qualitätsmangel',
      'Gehalteprobleem',
    ),
    _t(
      'Surfacturation',
      'Overcharged',
      'Cobro de más',
      'Zu viel berechnet',
      'Te veel gehef',
    ),
  ];
  String get refundFull => _t(
    'Remboursement complet',
    'Full refund',
    'Reembolso total',
    'Vollständige Erstattung',
    'Volle terugbetaling',
  );
  String get refundByLine =>
      _t('Par article', 'By item', 'Por artículo', 'Nach Artikel', 'Per item');
  String get refundByAmount =>
      _t('Par montant', 'By amount', 'Por monto', 'Nach Betrag', 'Per bedrag');
  String get refundAmountLabel => _t(
    'Montant à rembourser ($cur)',
    'Refund amount ($cur)',
    'Monto del reembolso ($cur)',
    'Erstattungsbetrag ($cur)',
    'Terugbetalingsbedrag ($cur)',
  );
  String get refundTender => _t(
    'Rembourser par',
    'Refund via',
    'Reembolsar con',
    'Erstatten über',
    'Betaal terug via',
  );
  String get refundableLabel => _t(
    'Remboursable',
    'Refundable',
    'Reembolsable',
    'Erstattbar',
    'Terugbetaalbaar',
  );
  String get refundedLabel =>
      _t('Remboursé', 'Refunded', 'Reembolsado', 'Erstattet', 'Terugbetaal');
  String get fullyRefunded => _t(
    'Entièrement remboursée',
    'Fully refunded',
    'Reembolsada por completo',
    'Vollständig erstattet',
    'Volledig terugbetaal',
  );
  String get confirmRefund => _t(
    'Confirmer le remboursement',
    'Confirm refund',
    'Confirmar reembolso',
    'Erstattung bestätigen',
    'Bevestig terugbetaling',
  );
  String refundDone(String amount) => _t(
    '$amount remboursé',
    'Refunded $amount',
    'Se reembolsaron $amount',
    '$amount erstattet',
    '$amount terugbetaal',
  );
  String cashHandedBack(String amount) => _t(
    'Comptant remis : $amount',
    'Cash handed back $amount',
    'Efectivo devuelto: $amount',
    '$amount bar zurückgegeben',
    '$amount kontant teruggegee',
  );
  String get noClosedBills => _t(
    'Aucune addition fermée pour l’instant',
    'No closed bills yet',
    'Todavía no hay cuentas cerradas',
    'Noch keine abgeschlossenen Rechnungen',
    'Nog geen geslote rekenings nie',
  );
  String get refundHistory => _t(
    'Historique des remboursements',
    'Refund history',
    'Historial de reembolsos',
    'Erstattungsverlauf',
    'Terugbetalingsgeskiedenis',
  );
  String get pickLinesHint => _t(
    'Touchez les articles à rembourser',
    'Tap items to refund',
    'Toca los artículos que vas a reembolsar',
    'Artikel zum Erstatten antippen',
    'Tik items om terug te betaal',
  );
  String get refundSlipTitle => _t(
    'Bon de remboursement',
    'Refund slip',
    'Comprobante de reembolso',
    'Erstattungsbeleg',
    'Terugbetalingstrokie',
  );
  String get amountExceedsRefundable => _t(
    'Dépasse le montant remboursable',
    'Exceeds the refundable amount',
    'Supera el monto reembolsable',
    'Übersteigt den erstattbaren Betrag',
    'Oorskry die terugbetaalbare bedrag',
  );
  String get pickAmountOrLines => _t(
    'Choisissez des articles ou entrez un montant',
    'Pick items or enter an amount',
    'Elige artículos o ingresa un monto',
    'Artikel wählen oder Betrag eingeben',
    'Kies items of voer ’n bedrag in',
  );

  // till — non-sale cash in / out
  String get till => _t('Caisse', 'Till', 'Caja', 'Kasse', 'Kasregister');
  String get cashIn => _t(
    'Entrée de caisse',
    'Cash in',
    'Entrada de efectivo',
    'Einlage',
    'Kontant in',
  );
  String get cashOut => _t(
    'Sortie de caisse',
    'Cash out',
    'Salida de efectivo',
    'Entnahme',
    'Kontant uit',
  );
  String get cashInApproval => _t(
    'Entrée de caisse — approbation du gérant',
    'Cash in — manager approval',
    'Entrada de efectivo: aprobación del gerente',
    'Einlage – Freigabe durch Manager',
    'Kontant in — bestuurder se goedkeuring',
  );
  String get cashOutApproval => _t(
    'Sortie de caisse — approbation du gérant',
    'Cash out — manager approval',
    'Salida de efectivo: aprobación del gerente',
    'Entnahme – Freigabe durch Manager',
    'Kontant uit — bestuurder se goedkeuring',
  );
  String get cashAmountLabel => _t(
    'Montant ($cur)',
    'Amount ($cur)',
    'Monto ($cur)',
    'Betrag ($cur)',
    'Bedrag ($cur)',
  );
  String get cashReasonLabel =>
      _t('Motif', 'Reason', 'Motivo', 'Grund', 'Rede');
  String get cashReasonRequired => _t(
    'Un motif est requis',
    'A reason is required',
    'Se requiere un motivo',
    'Bitte einen Grund angeben',
    '’n Rede is nodig',
  );
  String cashInDone(String amount) => _t(
    'Entrée de caisse de $amount enregistrée',
    'Cash in $amount recorded',
    'Entrada de efectivo de $amount registrada',
    'Einlage über $amount erfasst',
    'Kontant in $amount aangeteken',
  );
  String cashOutDone(String amount) => _t(
    'Sortie de caisse de $amount enregistrée',
    'Cash out $amount recorded',
    'Salida de efectivo de $amount registrada',
    'Entnahme über $amount erfasst',
    'Kontant uit $amount aangeteken',
  );
  String get cashMovementsLabel => _t(
    'Mouvements de caisse',
    'Cash movements',
    'Movimientos de efectivo',
    'Kassenbewegungen',
    'Kontantbewegings',
  );
  String get noCashMovements => _t(
    'Aucun mouvement de caisse pour l’instant',
    'No cash movements yet',
    'Todavía no hay movimientos de efectivo',
    'Noch keine Kassenbewegungen',
    'Nog geen kontantbewegings nie',
  );
  String get needOpenShiftForTill => _t(
    'Ouvrez un quart avant d’enregistrer des mouvements de caisse',
    'Open a shift before recording cash movements',
    'Abre un turno antes de registrar movimientos de efectivo',
    'Bitte zuerst eine Schicht öffnen, um Kassenbewegungen zu erfassen',
    'Begin ’n skof voordat jy kontantbewegings aanteken',
  );

  // report ranges
  String get rangeThisShift => _t(
    'Ce quart',
    'This shift',
    'Este turno',
    'Diese Schicht',
    'Hierdie skof',
  );
  String get rangeToday => _t('Aujourd’hui', 'Today', 'Hoy', 'Heute', 'Vandag');
  String get rangeYesterday =>
      _t('Hier', 'Yesterday', 'Ayer', 'Gestern', 'Gister');
  String get rangeThisWeek => _t(
    'Cette semaine',
    'This week',
    'Esta semana',
    'Diese Woche',
    'Hierdie week',
  );
  String get rangeCustom => _t(
    'Personnalisé…',
    'Custom…',
    'Personalizado…',
    'Zeitraum…',
    'Pasgemaak…',
  );
  String rangeTitle(String from, String to) => from == to
      ? _t(
          'Ventes du $from',
          'Sales for $from',
          'Ventas del $from',
          'Umsatz am $from',
          'Verkope vir $from',
        )
      : _t(
          'Ventes du $from au $to',
          'Sales $from – $to',
          'Ventas del $from al $to',
          'Umsatz $from – $to',
          'Verkope $from – $to',
        );

  // menu management
  String get manageMenuTitle => _t(
    'Gérer le menu — en vente / épuisé',
    'Manage menu — on/off sale',
    'Administrar menú: a la venta / agotado',
    'Speisekarte verwalten – verfügbar/ausverkauft',
    'Bestuur spyskaart — beskikbaar/uitverkoop',
  );
  String get onSale =>
      _t('En vente', 'On sale', 'A la venta', 'Verfügbar', 'Beskikbaar');
  String get offSale => _t(
    'Épuisé (86)',
    'Off sale (86)',
    'Agotado (86)',
    'Ausverkauft (86)',
    'Uitverkoop (86)',
  );
  String enableSale(String name) => _t(
    'Remettre $name en vente',
    'Put $name on sale',
    'Poner $name a la venta',
    '$name wieder verfügbar machen',
    'Maak $name beskikbaar',
  );
  String disableSale(String name) => _t(
    'Marquer $name épuisé (86)',
    'Take $name off sale (86)',
    'Marcar $name como agotado (86)',
    '$name als ausverkauft markieren (86)',
    'Merk $name as uitverkoop (86)',
  );

  // menu editing (owner catalog)
  String get addItem => _t(
    'Ajouter un article',
    'Add item',
    'Agregar artículo',
    'Artikel hinzufügen',
    'Voeg item by',
  );
  String get editItem => _t(
    'Modifier l’article',
    'Edit item',
    'Editar artículo',
    'Artikel bearbeiten',
    'Wysig item',
  );
  String get deleteItem => _t(
    'Supprimer l’article',
    'Delete item',
    'Eliminar artículo',
    'Artikel löschen',
    'Verwyder item',
  );
  String deleteItemConfirm(String name) => _t(
    'Retirer « $name » du menu ? Les anciennes additions le conservent, mais il disparaît de tous les écrans.',
    'Remove "$name" from the menu? Old bills keep it; it disappears from every screen.',
    '¿Quitar "$name" del menú? Las cuentas anteriores lo conservan, pero desaparece de todas las pantallas.',
    '„$name“ von der Speisekarte entfernen? Alte Rechnungen behalten den Artikel; auf allen Bildschirmen verschwindet er.',
    'Verwyder “$name” van die spyskaart? Ou rekenings behou dit; dit verdwyn van elke skerm.',
  );
  String get itemDeleted => _t(
    'Article retiré',
    'Item removed',
    'Artículo quitado',
    'Artikel entfernt',
    'Item verwyder',
  );
  String get nameFrLabel => _t(
    'Nom (français)',
    'Name (French)',
    'Nombre (francés)',
    'Name (Französisch)',
    'Naam (Frans)',
  );
  String get nameEnLabel => _t(
    'Nom (anglais)',
    'Name (English)',
    'Nombre (inglés)',
    'Name (Englisch)',
    'Naam (Engels)',
  );
  String get categoryLabel =>
      _t('Catégorie', 'Category', 'Categoría', 'Kategorie', 'Kategorie');
  String get abbrevLabel => _t(
    'Pastille (1 à 4 caractères)',
    'Tile badge (1–4 chars)',
    'Insignia (1 a 4 caracteres)',
    'Kachel-Kürzel (1–4 Zeichen)',
    'Teëlkenteken (1–4 karakters)',
  );
  String get isAlcoholLabel =>
      _t('Alcool', 'Alcohol', 'Con alcohol', 'Alkohol', 'Alkohol');
  String get activeLabel =>
      _t('En vente', 'On sale', 'A la venta', 'Verfügbar', 'Beskikbaar');
  String get sizesLabel => _t(
    'Formats / prix',
    'Sizes / prices',
    'Tamaños / precios',
    'Größen / Preise',
    'Groottes / pryse',
  );
  String get addSize => _t(
    'Ajouter un format',
    'Add size',
    'Agregar tamaño',
    'Größe hinzufügen',
    'Voeg grootte by',
  );
  String get sizeLabelFr => _t(
    'Format (français)',
    'Size (French)',
    'Tamaño (francés)',
    'Größe (Französisch)',
    'Grootte (Frans)',
  );
  String get sizeLabelEn => _t(
    'Format (anglais)',
    'Size (English)',
    'Tamaño (inglés)',
    'Größe (Englisch)',
    'Grootte (Engels)',
  );
  String get priceCAD => _t(
    'Prix ($cur)',
    'Price ($cur)',
    'Precio ($cur)',
    'Preis ($cur)',
    'Prys ($cur)',
  );
  String get fillAllFields => _t(
    'Remplissez tous les champs',
    'Fill in all fields',
    'Completa todos los campos',
    'Bitte alle Felder ausfüllen',
    'Vul alle velde in',
  );

  /// A money box holds something that isn't dollars and cents. The example
  /// stays North American ($12.99) in every language — that's how it's typed.
  String get invalidMoneyAmount => _t(
    'Montant invalide : entrez par ex. 12.99 (point pour les cents)',
    'Invalid amount: enter e.g. 12.99',
    'Importe no válido: escribe p. ej. 12.99 (punto para los centavos)',
    'Ungültiger Betrag: z. B. 12.99 eingeben (Punkt vor den Cents)',
    'Ongeldige bedrag: tik bv. 12.99 (punt voor die sente)',
  );
  String get editCategories => _t(
    'Gérer les catégories',
    'Edit categories',
    'Editar categorías',
    'Kategorien bearbeiten',
    'Wysig kategorieë',
  );
  String get addCategory => _t(
    'Ajouter une catégorie',
    'Add category',
    'Agregar categoría',
    'Kategorie hinzufügen',
    'Voeg kategorie by',
  );
  String get deleteCategory => _t(
    'Supprimer la catégorie',
    'Delete category',
    'Eliminar categoría',
    'Kategorie löschen',
    'Verwyder kategorie',
  );
  String get dragToReorder => _t(
    'Glissez pour réordonner',
    'Drag to reorder',
    'Arrastra para reordenar',
    'Zum Sortieren ziehen',
    'Sleep om te herrangskik',
  );
  String get saved =>
      _t('Enregistré', 'Saved', 'Guardado', 'Gespeichert', 'Gestoor');

  // slips
  String get printSlips => _t(
    'Imprimer les fiches de toutes les tables',
    'Print all table slips',
    'Imprimir las hojas de todas las mesas',
    'Alle Tischzettel drucken',
    'Druk alle tafelstrokies',
  );

  // photos
  String get uploadPhoto => _t(
    'Téléverser une photo',
    'Upload photo',
    'Subir foto',
    'Foto hochladen',
    'Laai foto op',
  );
  String uploadPhotoFor(String name) => _t(
    'Téléverser une photo pour $name',
    'Upload photo for $name',
    'Subir foto de $name',
    'Foto für $name hochladen',
    'Laai foto op vir $name',
  );
  String get photoUploaded => _t(
    'Photo téléversée',
    'Photo uploaded',
    'Foto subida',
    'Foto hochgeladen',
    'Foto opgelaai',
  );

  // AI menu photos (paid add-on)
  String get aiGeneratePhoto => _t(
    'Générer une photo',
    'Generate photo',
    'Generar foto',
    'Foto erzeugen',
    'Genereer foto',
  );
  String get aiSnapEnhance => _t(
    'Photographier et améliorer',
    'Snap and enhance',
    'Tomar foto y mejorar',
    'Foto aufwerten',
    'Neem en verbeter',
  );
  String aiGenerateFor(String name) => _t(
    'Générer une photo pour $name',
    'Generate a photo for $name',
    'Generar una foto de $name',
    'Foto für $name erzeugen',
    'Genereer ’n foto vir $name',
  );
  String aiEnhanceFor(String name) => _t(
    'Améliorer une photo de $name',
    'Enhance a photo of $name',
    'Mejorar una foto de $name',
    'Foto von $name aufwerten',
    'Verbeter ’n foto van $name',
  );
  String get aiTakePhoto => _t(
    'Prendre une photo',
    'Take a photo',
    'Tomar una foto',
    'Foto aufnehmen',
    'Neem ’n foto',
  );
  String get aiChooseFromGallery => _t(
    'Choisir dans la galerie',
    'Choose from gallery',
    'Elegir de la galería',
    'Aus Galerie wählen',
    'Kies uit galery',
  );
  // The Windows tablet's own camera page (lib/widgets/camera_capture.dart).
  String get cameraUsePhoto => _t(
    'Utiliser la photo',
    'Use photo',
    'Usar la foto',
    'Foto verwenden',
    'Gebruik foto',
  );
  String get cameraRetake =>
      _t('Reprendre', 'Retake', 'Repetir', 'Neu aufnehmen', 'Neem weer');
  String get cameraSwitch => _t(
    'Changer de caméra',
    'Switch camera',
    'Cambiar de cámara',
    'Kamera wechseln',
    'Wissel kamera',
  );
  String get cameraNone => _t(
    'Aucune caméra trouvée sur cet appareil.',
    'No camera found on this device.',
    'No se encontró ninguna cámara en este dispositivo.',
    'Keine Kamera auf diesem Gerät gefunden.',
    'Geen kamera op hierdie toestel gevind nie.',
  );
  String get cameraDenied => _t(
    'L’accès à la caméra est désactivé. Autorisez-le dans Paramètres Windows > Confidentialité et sécurité > Caméra.',
    'Camera access is turned off. Allow it in Windows Settings > Privacy & security > Camera.',
    'El acceso a la cámara está desactivado. Actívelo en Configuración de Windows > Privacidad y seguridad > Cámara.',
    'Der Kamerazugriff ist ausgeschaltet. Erlauben Sie ihn unter Windows-Einstellungen > Datenschutz und Sicherheit > Kamera.',
    'Kameratoegang is afgeskakel. Laat dit toe in Windows-instellings > Privaatheid en sekuriteit > Kamera.',
  );
  String get cameraFailed => _t(
    'La caméra n’a pas pu démarrer. Elle est peut-être utilisée par une autre application.',
    'The camera could not start. Another app may be using it.',
    'No se pudo iniciar la cámara. Puede que otra aplicación la esté usando.',
    'Die Kamera konnte nicht starten. Vielleicht wird sie von einer anderen App verwendet.',
    'Die kamera kon nie begin nie. ’n Ander toep gebruik dit dalk.',
  );
  String get aiWorking => _t(
    'Création des photos… (jusqu’à une minute)',
    'Making photos… (up to a minute)',
    'Creando fotos… (hasta un minuto)',
    'Fotos werden erstellt… (bis zu einer Minute)',
    'Maak foto’s… (tot ’n minuut)',
  );
  // the AI menu dialog's spinner: what it is doing right now
  String get aiMenuThinking => _t(
    'Lecture de votre demande…',
    'Working on your request…',
    'Procesando tu solicitud…',
    'Ihre Anfrage wird bearbeitet…',
    'Besig met jou versoek…',
  );
  String get aiMenuReadingPhotos => _t(
    'Lecture des photos du menu… (jusqu’à une minute)',
    'Reading the menu photos… (up to a minute)',
    'Leyendo las fotos del menú… (hasta un minuto)',
    'Menüfotos werden gelesen… (bis zu einer Minute)',
    'Lees die spyskaartfoto’s… (tot ’n minuut)',
  );
  String get aiMenuTranslating => _t(
    'Traduction du menu…',
    'Translating the menu…',
    'Traduciendo el menú…',
    'Menü wird übersetzt…',
    'Vertaal die spyskaart…',
  );
  String get aiMenuApplying => _t(
    'Mise à jour du menu…',
    'Updating the menu…',
    'Actualizando el menú…',
    'Menü wird aktualisiert…',
    'Werk die spyskaart by…',
  );
  String get aiPickOne => _t(
    'Touchez la photo à garder',
    'Tap the photo to keep',
    'Toca la foto que quieres conservar',
    'Gewünschtes Foto antippen',
    'Tik die foto om te hou',
  );
  String get aiUseThis => _t(
    'Utiliser cette photo',
    'Use this photo',
    'Usar esta foto',
    'Dieses Foto verwenden',
    'Gebruik hierdie foto',
  );
  String get aiRegenerate => _t(
    'Recommencer',
    'Regenerate',
    'Generar de nuevo',
    'Neu erzeugen',
    'Genereer weer',
  );
  String get aiPhotoSaved => _t(
    'Photo IA enregistrée',
    'AI photo saved',
    'Foto con IA guardada',
    'KI-Foto gespeichert',
    'KI-foto gestoor',
  );
  String get aiBadge => _t('IA', 'AI', 'IA', 'KI', 'KI');
  String get aiGeneratedLabel => _t(
    'Photo générée par IA',
    'AI-generated photo',
    'Foto generada con IA',
    'KI-generiertes Foto',
    'KI-gegenereerde foto',
  );
  String get aiEnhancedLabel => _t(
    'Photo réelle retouchée par IA',
    'Real photo, AI-enhanced',
    'Foto real mejorada con IA',
    'Echtes Foto, mit KI aufgewertet',
    'Regte foto, met KI verbeter',
  );
  String get aiEnhanceHint => _t(
    'Les aliments restent tels quels : seuls l’éclairage, le fond et la présentation changent.',
    'The food stays as it is: only the lighting, background and presentation change.',
    'La comida queda igual: solo cambian la luz, el fondo y la presentación.',
    'Das Gericht bleibt, wie es ist: Nur Licht, Hintergrund und Präsentation ändern sich.',
    'Die kos bly soos dit is: net die beligting, agtergrond en aanbieding verander.',
  );

  /// Why the AI photo buttons are greyed out (a note under them).
  String aiUnavailableNote(String? reason) => switch (reason) {
    'image_offline' || 'image_unavailable' => _t(
      'Photos IA : connexion Internet requise. Tout le reste fonctionne normalement.',
      'AI photos need an internet connection. Everything else works as usual.',
      'Las fotos con IA necesitan internet. Todo lo demás funciona con normalidad.',
      'KI-Fotos benötigen eine Internetverbindung. Alles andere funktioniert wie gewohnt.',
      'KI-foto’s het ’n internetverbinding nodig. Alles anders werk soos gewoonlik.',
    ),
    _ => _t(
      'Photos IA pas encore configurées sur ce magasin.',
      'AI photos aren’t set up on this store yet.',
      'Las fotos con IA aún no están configuradas en esta tienda.',
      'KI-Fotos sind für diesen Betrieb noch nicht eingerichtet.',
      'KI-foto’s is nog nie vir hierdie winkel opgestel nie.',
    ),
  };

  // AI menu setup
  String get aiMenuTitle => _t(
    'Configurer le menu avec l’IA',
    'AI menu setup',
    'Menú con IA',
    'KI-Speisekarte',
    'KI-spyskaartopstelling',
  );
  String get aiMenuFromPhotos => _t(
    'Menu à partir de photos',
    'Menu from photos',
    'Menú desde fotos',
    'Karte aus Fotos',
    'Spyskaart uit foto’s',
  );
  String get aiMenuChatHint => _t(
    'Ex. : « ajoute salade César 14 \$ dans Entrées », « monte les burgers d’un dollar », « 86 le saumon »',
    'e.g. “add Caesar salad \$14 under starters”, “raise all burgers by a dollar”, “86 the salmon”',
    'p. ej. «agrega ensalada César \$14 en entradas», «sube las hamburguesas un dólar», «86 el salmón»',
    'z. B. „Caesar Salad für 14 \$ zu den Vorspeisen“, „alle Burger um einen Dollar teurer“, „Lachs ist aus“',
    'bv. “voeg Caesar-slaai \$14 by onder voorgeregte”, “maak alle burgers ’n dollar duurder”, “86 die salm”',
  );
  String get aiMenuAsk =>
      _t('Proposer', 'Propose', 'Proponer', 'Vorschlagen', 'Stel voor');
  String get aiMenuPreviewNote => _t(
    'Rien ne change avant « Appliquer ». Cochez ce que vous voulez garder.',
    'Nothing changes until you tap Apply. Tick what you want to keep.',
    'Nada cambia hasta que toques Aplicar. Marca lo que quieras conservar.',
    'Nichts ändert sich, bevor Sie auf Übernehmen tippen. Markieren Sie, was Sie behalten möchten.',
    'Niks verander voordat jy Pas toe tik nie. Merk wat jy wil hou.',
  );
  String get aiMenuNew => _t('Nouveau', 'New', 'Nuevo', 'Neu', 'Nuut');
  String get aiMenuChanged =>
      _t('Modifié', 'Changed', 'Cambiado', 'Geändert', 'Verander');
  String get aiMenuRemoved =>
      _t('Retiré', 'Removed', 'Eliminado', 'Entfernt', 'Verwyder');
  String get aiMenuNothing => _t(
    'Aucun changement proposé.',
    'No changes proposed.',
    'No se propusieron cambios.',
    'Keine Änderungen vorgeschlagen.',
    'Geen veranderinge voorgestel nie.',
  );
  String aiMenuRejected(int n) => _t(
    '$n suggestion(s) ignorée(s) (produit inconnu ou prix invalide).',
    '$n suggestion(s) skipped (unknown item or invalid price).',
    '$n sugerencia(s) omitida(s) (producto desconocido o precio no válido).',
    '$n Vorschlag/Vorschläge übersprungen (unbekannter Artikel oder ungültiger Preis).',
    '$n voorstel(le) oorgeslaan (onbekende item of ongeldige prys).',
  );
  String aiMenuApply(int n) => _t(
    'Appliquer ($n)',
    'Apply ($n)',
    'Aplicar ($n)',
    'Übernehmen ($n)',
    'Pas toe ($n)',
  );
  String aiMenuApplied(int n) => _t(
    '$n changement(s) appliqué(s)',
    '$n change(s) applied',
    '$n cambio(s) aplicado(s)',
    '$n Änderung(en) übernommen',
    '$n verandering(e) toegepas',
  );
  String get aiMenuHistory => _t(
    'Historique IA',
    'AI history',
    'Historial IA',
    'KI-Verlauf',
    'KI-geskiedenis',
  );
  String get aiMenuRevert =>
      _t('Défaire', 'Revert', 'Revertir', 'Rückgängig', 'Herstel');
  String get aiMenuReverted =>
      _t('Défait', 'Reverted', 'Revertido', 'Rückgängig gemacht', 'Herstel');
  String get aiMenuRevertDone => _t(
    'Mise à jour IA défaite',
    'AI update reverted',
    'Actualización IA revertida',
    'KI-Änderung rückgängig gemacht',
    'KI-opdatering herstel',
  );
  String get aiMenuNoHistory => _t(
    'Aucune mise à jour IA pour l’instant.',
    'No AI updates yet.',
    'Aún no hay actualizaciones con IA.',
    'Noch keine KI-Änderungen.',
    'Nog geen KI-opdaterings nie.',
  );
  String aiMenuRevertConflict(String titles) => _t(
    'Modifié depuis cette mise à jour : $titles. Défaire quand même et écraser ces changements ?',
    'Changed since this AI update: $titles. Revert anyway and overwrite those changes?',
    'Cambiado desde esta actualización: $titles. ¿Revertir de todos modos y sobrescribir esos cambios?',
    'Seit dieser KI-Änderung geändert: $titles. Trotzdem rückgängig machen und diese Änderungen überschreiben?',
    'Verander sedert hierdie KI-opdatering: $titles. Herstel in elk geval en oorskryf daardie veranderinge?',
  );
  String get aiMenuRevertAnyway => _t(
    'Défaire quand même',
    'Revert anyway',
    'Revertir de todos modos',
    'Trotzdem rückgängig',
    'Herstel in elk geval',
  );
  String aiMenuSourceLabel(String source) => switch (source) {
    'photos' => _t(
      'Photos du menu',
      'Menu photos',
      'Fotos del menú',
      'Kartenfotos',
      'Spyskaartfoto’s',
    ),
    'translate' => _t(
      'Traduction',
      'Translation',
      'Traducción',
      'Übersetzung',
      'Vertaling',
    ),
    _ => _t('Clavardage', 'Chat', 'Conversación', 'Chat', 'Klets'),
  };
  String aiMenuPhotosFor(int n) => _t(
    'Générer des photos pour les $n nouveaux produits ?',
    'Generate photos for the $n new items?',
    '¿Generar fotos para los $n productos nuevos?',
    'Fotos für die $n neuen Artikel erzeugen?',
    'Genereer foto’s vir die $n nuwe items?',
  );
  String get aiMenuAddPhoto => _t(
    'Ajouter une photo',
    'Add photo',
    'Agregar foto',
    'Foto hinzufügen',
    'Voeg foto by',
  );
  String get aiMenuReadPhotos => _t(
    'Lire le menu',
    'Read menu',
    'Leer menú',
    'Karte einlesen',
    'Lees spyskaart',
  );
  String aiMenuField(String field) => switch (field) {
    'nameEn' => _t(
      'Nom (anglais)',
      'Name (English)',
      'Nombre (inglés)',
      'Name (Englisch)',
      'Naam (Engels)',
    ),
    'nameFr' => _t(
      'Nom (français)',
      'Name (French)',
      'Nombre (francés)',
      'Name (Französisch)',
      'Naam (Frans)',
    ),
    'descriptionEn' => _t(
      'Description (anglais)',
      'Description (English)',
      'Descripción (inglés)',
      'Beschreibung (Englisch)',
      'Beskrywing (Engels)',
    ),
    'descriptionFr' => _t(
      'Description (français)',
      'Description (French)',
      'Descripción (francés)',
      'Beschreibung (Französisch)',
      'Beskrywing (Frans)',
    ),
    'price' => _t('Prix', 'Price', 'Precio', 'Preis', 'Prys'),
    'category' => _t(
      'Catégorie',
      'Category',
      'Categoría',
      'Kategorie',
      'Kategorie',
    ),
    'available' => _t(
      'En vente',
      'On sale',
      'A la venta',
      'Verfügbar',
      'Beskikbaar',
    ),
    'order' => _t('Ordre', 'Order', 'Orden', 'Reihenfolge', 'Volgorde'),
    'name' => _t('Nom', 'Name', 'Nombre', 'Name', 'Naam'),
    _ => field,
  };

  /// A language by its code, in the current language ("de" → "German",
  /// "af" → "Afrikaans" in every language but French and Spanish).
  String langName(String code) => switch (code) {
    'fr' => _t('français', 'French', 'francés', 'Französisch', 'Frans'),
    'en' => _t('anglais', 'English', 'inglés', 'Englisch', 'Engels'),
    'es' => _t('espagnol', 'Spanish', 'español', 'Spanisch', 'Spaans'),
    'de' => _t('allemand', 'German', 'alemán', 'Deutsch', 'Duits'),
    'af' => _t('afrikaans', 'Afrikaans', 'afrikáans', 'Afrikaans', 'Afrikaans'),
    _ => code,
  };

  // "Translate menu": the AI fills in the names missing in the store's extra languages
  String get aiMenuTranslate => _t(
    'Traduire le menu',
    'Translate menu',
    'Traducir menú',
    'Karte übersetzen',
    'Vertaal spyskaart',
  );
  String aiMenuTranslateNote(String langs) => _t(
    'Propose les noms manquants en $langs. Rien ne change avant d’appliquer.',
    'Suggests the missing $langs names. Nothing changes until you apply.',
    'Propone los nombres que faltan en $langs. Nada cambia hasta que apliques.',
    'Schlägt fehlende Namen auf $langs vor. Erst „Übernehmen“ ändert etwas.',
    'Stel ontbrekende name in $langs voor. Niks verander voordat jy toepas nie.',
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
        )
      : _t(
          'Je peux seulement vous aider à configurer et modifier votre menu. Essayez par exemple « ajoute une salade César à 14 dans Salades ».',
          'I can only help set up and edit your menu. Try something like “add a Caesar salad for 14 under Salads”.',
          'Solo puedo ayudarte a configurar y editar tu menú. Prueba algo como «añade una ensalada César a 14 en Ensaladas».',
          'Ich kann nur beim Einrichten und Bearbeiten Ihrer Speisekarte helfen. Versuchen Sie zum Beispiel „Caesar Salad für 14 unter Salate hinzufügen“.',
          'Ek kan net help om jou spyskaart op te stel en te wysig. Probeer iets soos “voeg ’n Caesar-slaai vir 14 by onder Slaaie”.',
        );
  String aiMenuBulkConfirm(int removes, int prices) => _t(
    'C’est un gros changement : $removes produit(s) retiré(s) et $prices prix modifié(s). Appliquer quand même ?',
    'This is a big change: $removes item(s) removed and $prices price(s) changed. Apply anyway?',
    'Es un cambio grande: $removes producto(s) eliminado(s) y $prices precio(s) cambiado(s). ¿Aplicar de todos modos?',
    'Das ist eine große Änderung: $removes Artikel entfernt und $prices Preis(e) geändert. Trotzdem übernehmen?',
    'Dit is ’n groot verandering: $removes item(s) verwyder en $prices prys(e) verander. Pas in elk geval toe?',
  );
  String get aiMenuBigChangeConfirm => _t(
    'C’est un gros changement : des produits retirés de la caisse ou des prix réduits de moitié ou plus. Appliquer quand même ?',
    'This is a big change: items taken off the till, or prices cut by half or more. Apply anyway?',
    'Es un cambio grande: productos retirados de la caja o precios rebajados a la mitad o más. ¿Aplicar de todos modos?',
    'Das ist eine große Änderung: Artikel von der Kasse genommen oder Preise um die Hälfte oder mehr gesenkt. Trotzdem übernehmen?',
    'Dit is ’n groot verandering: items van die kasregister afgehaal, of pryse met die helfte of meer verlaag. Pas in elk geval toe?',
  );
  String floorEditBulkConfirm(int n) => _t(
    'L’IA retirerait $n table(s) ou élément(s) de cette salle. Appliquer quand même ?',
    'The AI would remove $n table(s) or object(s) from this room. Apply anyway?',
    'La IA quitaría $n mesa(s) o elemento(s) de esta sala. ¿Aplicar de todos modos?',
    'Die KI würde $n Tisch(e) oder Element(e) aus diesem Raum entfernen. Trotzdem übernehmen?',
    'Die KI sou $n tafel(s) of voorwerp(e) uit hierdie vertrek verwyder. Pas in elk geval toe?',
  );
  String get floorEditApplyAnyway => _t(
    'Appliquer quand même',
    'Apply anyway',
    'Aplicar de todos modos',
    'Trotzdem übernehmen',
    'Pas in elk geval toe',
  );
  String get aiMenuTranslateDone => _t(
    'Tous les noms sont déjà traduits.',
    'Every name is already translated.',
    'Todos los nombres ya están traducidos.',
    'Alle Namen sind bereits übersetzt.',
    'Elke naam is reeds vertaal.',
  );
  String get aiMenuCategoryOrder => _t(
    'Ordre des catégories',
    'Category order',
    'Orden de categorías',
    'Kategorie-Reihenfolge',
    'Kategorievolgorde',
  );
  String aiMenuNewCategory(String name) => _t(
    'Catégorie : $name',
    'Category: $name',
    'Categoría: $name',
    'Kategorie: $name',
    'Kategorie: $name',
  );

  /// Why the AI menu buttons are greyed out.
  String aiMenuUnavailableNote(String? reason) => switch (reason) {
    'menu_ai_offline' || 'menu_ai_unavailable' => _t(
      'Menu IA : connexion Internet requise. Tout le reste fonctionne normalement.',
      'AI menu setup needs an internet connection. Everything else works as usual.',
      'El menú con IA necesita internet. Todo lo demás funciona con normalidad.',
      'Die KI-Speisekarte benötigt eine Internetverbindung. Alles andere funktioniert wie gewohnt.',
      'KI-spyskaartopstelling het ’n internetverbinding nodig. Alles anders werk soos gewoonlik.',
    ),
    _ => _t(
      'Menu IA pas encore configuré sur ce magasin.',
      'AI menu setup isn’t set up on this store yet.',
      'El menú con IA aún no está configurado en esta tienda.',
      'Die KI-Speisekarte ist für diesen Betrieb noch nicht eingerichtet.',
      'KI-spyskaartopstelling is nog nie vir hierdie winkel opgestel nie.',
    ),
  };

  // pin pad / manager approval
  String get managerPinTitle => _t(
    'Gérant : entrez votre NIP',
    'Manager: enter PIN',
    'Gerente: ingresa tu PIN',
    'Manager: PIN eingeben',
    'Bestuurder: voer PIN in',
  );
  String get managerApproval => _t(
    'Approbation du gérant',
    'Manager approval',
    'Aprobación del gerente',
    'Freigabe durch Manager',
    'Bestuurder se goedkeuring',
  );

  // change PIN
  String get changePin => _t(
    'Changer de NIP',
    'Change PIN',
    'Cambiar PIN',
    'PIN ändern',
    'Verander PIN',
  );
  String get currentPin => _t(
    'NIP actuel',
    'Current PIN',
    'PIN actual',
    'Aktuelle PIN',
    'Huidige PIN',
  );
  String get newPin =>
      _t('Nouveau NIP', 'New PIN', 'PIN nuevo', 'Neue PIN', 'Nuwe PIN');
  String get confirmNewPin => _t(
    'Confirmer le nouveau NIP',
    'Confirm new PIN',
    'Confirmar el PIN nuevo',
    'Neue PIN bestätigen',
    'Bevestig nuwe PIN',
  );
  String get pinChanged => _t(
    'NIP modifié',
    'PIN changed',
    'PIN cambiado',
    'PIN geändert',
    'PIN verander',
  );
  String get pinMismatch => _t(
    'Les nouveaux NIP ne correspondent pas',
    'New PINs do not match',
    'Los PIN nuevos no coinciden',
    'Die neuen PINs stimmen nicht überein',
    'Nuwe PIN’s stem nie ooreen nie',
  );

  // staff administration (the tablet owns its staff; the portal only shows them)
  String get staffTitle =>
      _t('Personnel', 'Staff', 'Personal', 'Personal', 'Personeel');
  String get staffAdd => _t(
    'Ajouter un employé',
    'Add staff',
    'Agregar empleado',
    'Mitarbeiter hinzufügen',
    'Voeg personeellid by',
  );
  String get staffEdit => _t(
    'Modifier l’employé',
    'Edit staff',
    'Editar empleado',
    'Mitarbeiter bearbeiten',
    'Wysig personeellid',
  );
  String get staffName => _t('Nom', 'Name', 'Nombre', 'Name', 'Naam');
  String get staffRole => _t('Rôle', 'Role', 'Puesto', 'Rolle', 'Rol');
  String get roleManager =>
      _t('Gérant', 'Manager', 'Gerente', 'Manager', 'Bestuurder');
  String get roleServer =>
      _t('Serveur', 'Server', 'Mesero', 'Service', 'Kelner');
  String get staffActive => _t('Actif', 'Active', 'Activo', 'Aktiv', 'Aktief');
  String get staffInactive =>
      _t('Inactif', 'Inactive', 'Inactivo', 'Inaktiv', 'Onaktief');
  String get staffPin4 => _t(
    'NIP (4 chiffres)',
    'PIN (4 digits)',
    'PIN (4 dígitos)',
    'PIN (4 Ziffern)',
    'PIN (4 syfers)',
  );
  String get staffResetPin => _t(
    'Nouveau NIP',
    'Reset PIN',
    'Restablecer PIN',
    'PIN zurücksetzen',
    'Stel PIN terug',
  );
  String get staffPinInvalid => _t(
    'Le NIP doit compter 4 chiffres',
    'PIN must be 4 digits',
    'El PIN debe tener 4 dígitos',
    'Die PIN muss 4 Ziffern haben',
    'PIN moet 4 syfers wees',
  );
  String get staffNameRequired => _t(
    'Un nom est requis',
    'A name is required',
    'Se requiere un nombre',
    'Bitte einen Namen angeben',
    '’n Naam is nodig',
  );
  String get staffDelete =>
      _t('Supprimer', 'Delete', 'Eliminar', 'Löschen', 'Verwyder');
  String staffDeleteConfirm(String name) => _t(
    'Supprimer $name ? L’historique des ventes est conservé.',
    'Delete $name? Sales history is kept.',
    '¿Eliminar a $name? El historial de ventas se conserva.',
    '$name löschen? Der Umsatzverlauf bleibt erhalten.',
    'Verwyder $name? Verkoopsgeskiedenis word behou.',
  );
  String get staffSaved =>
      _t('Enregistré', 'Saved', 'Guardado', 'Gespeichert', 'Gestoor');
  String get staffSyncHint => _t(
    'Les changements s’appliquent tout de suite sur cette tablette et sont envoyés au portail à la prochaine connexion.',
    'Changes apply on this tablet at once and are sent to the owner portal when it next connects.',
    'Los cambios se aplican de inmediato en esta tableta y se envían al portal del dueño la próxima vez que se conecte.',
    'Änderungen gelten sofort auf diesem Tablet und werden beim nächsten Verbindungsaufbau an das Inhaberportal gesendet.',
    'Veranderinge geld dadelik op hierdie tablet en word na die eienaarportaal gestuur wanneer dit weer koppel.',
  );

  // settings
  String get settings => _t(
    'Paramètres de l’établissement',
    'Venue settings',
    'Configuración de la tienda',
    'Betriebseinstellungen',
    'Plekinstellings',
  );
  String get sectionPayments =>
      _t('Paiements', 'Payments', 'Pagos', 'Zahlungen', 'Betalings');
  String get sectionFees => _t('Frais', 'Fees', 'Cargos', 'Gebühren', 'Fooie');
  String get sectionReceipt =>
      _t('Reçu', 'Receipt', 'Recibo', 'Beleg', 'Kwitansie');
  String get sectionSecurity =>
      _t('Sécurité', 'Security', 'Seguridad', 'Sicherheit', 'Sekuriteit');
  String get sessionIdleLabel => _t(
    'Déconnexion automatique après inactivité (minutes)',
    'Auto-logout when idle (minutes)',
    'Cerrar sesión por inactividad (minutos)',
    'Automatisch abmelden bei Inaktivität (Minuten)',
    'Meld outomaties af wanneer onaktief (minute)',
  );
  String get cardProcessorLabel => _t(
    'Nom du terminal de paiement',
    'Card terminal label',
    'Nombre de la terminal de tarjetas',
    'Bezeichnung Kartenterminal',
    'Kaartterminaal-etiket',
  );
  String get bankNameLabel => _t('Banque', 'Bank', 'Banco', 'Bank', 'Bank');
  String get bankAccountNumberLabel => _t(
    'Numéro de compte',
    'Account number',
    'Número de cuenta',
    'Kontonummer',
    'Rekeningnommer',
  );
  String get bankAccountNameLabel => _t(
    'Titulaire du compte',
    'Account holder name',
    'Titular de la cuenta',
    'Kontoinhaber',
    'Naam van rekeninghouer',
  );
  String get serviceChargeLabel => _t(
    'Frais de service (%) — 0 = désactivés',
    'Service charge (%) — 0 = off',
    'Cargo por servicio (%): 0 = desactivado',
    'Servicegebühr (%) – 0 = aus',
    'Diensheffing (%) — 0 = af',
  );
  String get corkageRateLabel => _t(
    'Droit de bouchon ($cur/bouteille) — 0 = désactivé',
    'Corkage ($cur/bottle) — 0 = off',
    'Descorche ($cur/botella): 0 = desactivado',
    'Korkgeld ($cur/Flasche) – 0 = aus',
    'Kurkgeld ($cur/bottel) — 0 = af',
  );
  String get receiptFooterLabel => _t(
    'Message au bas du reçu',
    'Receipt footer text',
    'Mensaje al pie del recibo',
    'Fußzeile auf dem Beleg',
    'Voetskrif op kwitansie',
  );
  String get venuePhoneLabel => _t(
    'Téléphone de l’établissement',
    'Venue phone',
    'Teléfono de la tienda',
    'Telefon des Betriebs',
    'Plek se telefoon',
  );
  String get venueAddressLabel => _t(
    'Adresse de l’établissement',
    'Venue address',
    'Dirección de la tienda',
    'Adresse des Betriebs',
    'Plek se adres',
  );
  String get save => _t('Enregistrer', 'Save', 'Guardar', 'Speichern', 'Stoor');
  String get savedTakesEffectNext => _t(
    'Enregistré — s’applique à partir de la prochaine addition',
    'Saved — applies from the next bill',
    'Guardado: se aplica desde la próxima cuenta',
    'Gespeichert – gilt ab der nächsten Rechnung',
    'Gestoor — geld vanaf die volgende rekening',
  );

  // store server URL (device-local; auto-discovered on the LAN)
  String get sectionServer => _t(
    'Connexion au restaurant',
    'Restaurant connection',
    'Conexión con la tienda',
    'Verbindung zum Restaurant',
    'Restaurantverbinding',
  );
  String get serverUrlLabel => _t(
    'Adresse de connexion avancée',
    'Advanced connection address',
    'Dirección de conexión avanzada',
    'Erweiterte Verbindungsadresse',
    'Gevorderde verbindingsadres',
  );
  String get scanForServer => _t(
    'Trouver le restaurant sur le Wi-Fi',
    'Find restaurant on Wi-Fi',
    'Buscar la tienda en el Wi-Fi',
    'Restaurant im WLAN suchen',
    'Soek restaurant op Wi-Fi',
  );
  String get scanningForServer => _t(
    'Recherche du restaurant…',
    'Finding restaurant…',
    'Buscando la tienda…',
    'Restaurant wird gesucht…',
    'Soek restaurant…',
  );
  String get connectingToServer => _t(
    'Recherche du restaurant…',
    'Finding your restaurant…',
    'Buscando tu tienda…',
    'Ihr Restaurant wird gesucht…',
    'Soek jou restaurant…',
  );
  String get findingRestaurant => _t(
    'Recherche du restaurant…',
    'Finding your restaurant…',
    'Buscando tu tienda…',
    'Ihr Restaurant wird gesucht…',
    'Soek jou restaurant…',
  );
  String get startingThisTablet => _t(
    'Démarrage de cette tablette…',
    'Starting this tablet…',
    'Iniciando esta tableta…',
    'Tablet wird gestartet…',
    'Begin hierdie tablet…',
  );
  String get tabletStoreFailed => _t(
    'Le service local de cette tablette n’a pas démarré.',
    'This tablet’s local store did not start.',
    'El servicio local de esta tableta no arrancó.',
    'Der lokale Server dieses Tablets ist nicht gestartet.',
    'Hierdie tablet se plaaslike bediener het nie begin nie.',
  );
  String get staffAppNeedsWifi => _t(
    'Connectez la tablette au Wi-Fi pour afficher le code de l’application du personnel.',
    'Connect this tablet to Wi-Fi to show the staff app code.',
    'Conecta esta tableta al Wi-Fi para mostrar el código de la app del personal.',
    'Verbinden Sie dieses Tablet mit dem WLAN, um den Code der Personal-App anzuzeigen.',
    'Koppel hierdie tablet aan Wi-Fi om die personeelapp-kode te wys.',
  );
  String get restaurantUnavailable => _t(
    'Restaurant introuvable sur ce réseau Wi-Fi',
    'Restaurant is not available on this Wi-Fi',
    'La tienda no está disponible en este Wi-Fi',
    'Restaurant ist in diesem WLAN nicht erreichbar',
    'Restaurant is nie op hierdie Wi-Fi beskikbaar nie',
  );
  String get connectionHelp => _t(
    'Aide à la connexion',
    'Connection help',
    'Ayuda con la conexión',
    'Hilfe zur Verbindung',
    'Verbindingshulp',
  );
  String serverFoundAt(String url) => _t(
    'Restaurant trouvé',
    'Restaurant found',
    'Tienda encontrada',
    'Restaurant gefunden',
    'Restaurant gevind',
  );
  String get serverNotFound => _t(
    'Restaurant introuvable sur ce Wi-Fi',
    'Restaurant not found on this Wi-Fi',
    'No se encontró la tienda en este Wi-Fi',
    'Restaurant in diesem WLAN nicht gefunden',
    'Restaurant nie op hierdie Wi-Fi gevind nie',
  );
  String currentlyUsing(String url) => _t(
    'Adresse actuelle : $url',
    'Currently using: $url',
    'Dirección actual: $url',
    'Aktuell verwendet: $url',
    'Gebruik tans: $url',
  );
  String get serverSavedRestart => _t(
    'Enregistré — redémarrez l’application pour l’appliquer',
    'Saved — restart the app to apply',
    'Guardado: reinicia la app para aplicarlo',
    'Gespeichert – App neu starten, um es anzuwenden',
    'Gestoor — herbegin die app om toe te pas',
  );
  String get setServerUrl => _t(
    'Connexion avancée',
    'Advanced connection',
    'Conexión avanzada',
    'Erweiterte Verbindung',
    'Gevorderde verbinding',
  );
  String get restaurantConnectionReady => _t(
    'Restaurant disponible',
    'Restaurant available',
    'Tienda disponible',
    'Restaurant erreichbar',
    'Restaurant beskikbaar',
  );
  String get advancedConnection => _t(
    'Dépannage avancé',
    'Advanced troubleshooting',
    'Solución de problemas avanzada',
    'Erweiterte Fehlerbehebung',
    'Gevorderde probleemoplossing',
  );

  // terminal pairing (cloud venues)
  String get pairTerminalTitle => _t(
    'Jumeler ce terminal',
    'Pair this terminal',
    'Vincular esta terminal',
    'Dieses Terminal koppeln',
    'Koppel hierdie terminaal',
  );
  String get pairTerminalIntro => _t(
    'Créez un code de jumelage dans le portail du propriétaire, puis entrez-le ici.',
    'Mint a pairing code in the owner portal, then enter it here.',
    'Genera un código de vinculación en el portal del dueño y luego ingrésalo aquí.',
    'Erzeugen Sie im Inhaberportal einen Kopplungscode und geben Sie ihn hier ein.',
    'Skep ’n koppelkode in die eienaarportaal en voer dit dan hier in.',
  );
  String get pairVenueAddressLabel => _t(
    'Adresse de l’établissement',
    'Venue address',
    'Dirección de la tienda',
    'Adresse des Betriebs',
    'Plek se adres',
  );
  String get pairingCodeLabel => _t(
    'Code de jumelage',
    'Pairing code',
    'Código de vinculación',
    'Kopplungscode',
    'Koppelkode',
  );
  String get deviceNameLabel => _t(
    'Nom de l’appareil (ex. : Bar)',
    'Device name (e.g. Bar)',
    'Nombre del dispositivo (p. ej., Caja)',
    'Gerätename (z. B. Bar)',
    'Toestelnaam (bv. Kroeg)',
  );
  String get pairAction =>
      _t('Jumeler', 'Pair', 'Vincular', 'Koppeln', 'Koppel');
  String get enterVenueAddress => _t(
    'Entrez l’adresse de l’établissement',
    'Enter the venue address',
    'Ingresa la dirección de la tienda',
    'Bitte die Adresse des Betriebs eingeben',
    'Voer die plek se adres in',
  );
  String get enterPairingCode => _t(
    'Entrez le code de jumelage',
    'Enter the pairing code',
    'Ingresa el código de vinculación',
    'Bitte den Kopplungscode eingeben',
    'Voer die koppelkode in',
  );
  String get pairNetworkError => _t(
    'Établissement injoignable — vérifiez l’adresse et le réseau',
    'Cannot reach the venue — check the address and your network',
    'No se puede conectar con la tienda: revisa la dirección y la red',
    'Betrieb nicht erreichbar – Adresse und Netzwerk prüfen',
    'Kan nie die plek bereik nie — gaan die adres en jou netwerk na',
  );

  // global reconnecting overlay
  String get reconnectingToServer => _t(
    'Reconnexion au restaurant…',
    'Reconnecting to your restaurant…',
    'Reconectando con tu tienda…',
    'Verbindung zum Restaurant wird wiederhergestellt…',
    'Herkoppel met jou restaurant…',
  );
  String get reconnectingToRestaurant => _t(
    'Reconnexion au restaurant…',
    'Reconnecting to your restaurant…',
    'Reconectando con tu tienda…',
    'Verbindung zum Restaurant wird wiederhergestellt…',
    'Herkoppel met jou restaurant…',
  );
  String get reconnectChangeServer => _t(
    'Aide à la connexion',
    'Connection help',
    'Ayuda con la conexión',
    'Hilfe zur Verbindung',
    'Verbindingshulp',
  );

  // on-screen QR codes: table scan-to-order (long-press a table) + staff app
  String tableQrTitle(String label) => _t(
    'Balayez pour commander · $label',
    'Scan to order · $label',
    'Escanea para pedir · $label',
    'Scannen und bestellen · $label',
    'Skandeer om te bestel · $label',
  );
  String get tableQrHint => _t(
    'Les clients balaient le code avec un téléphone branché sur le Wi-Fi de l’établissement',
    'Guests scan with a phone on the venue Wi-Fi',
    'Los clientes escanean con un celular conectado al Wi-Fi de la tienda',
    'Gäste scannen mit dem Handy im WLAN des Restaurants',
    'Gaste skandeer met ’n foon op die plek se Wi-Fi',
  );
  String get tableQrNeedsWifi => _t(
    'Connectez la tablette au Wi-Fi pour afficher un code QR utilisable.',
    'Connect the tablet to Wi-Fi to show a scannable QR code.',
    'Conecta la tableta al Wi-Fi para mostrar un código QR que funcione.',
    'Verbinden Sie das Tablet mit dem WLAN, um einen scanbaren QR-Code anzuzeigen.',
    'Koppel die tablet aan Wi-Fi om ’n skandeerbare QR-kode te wys.',
  );
  String get showQrCode => _t(
    'Afficher le code QR',
    'Show QR code',
    'Mostrar código QR',
    'QR-Code anzeigen',
    'Wys QR-kode',
  );
  String get printQrCode => _t(
    'Imprimer le code QR',
    'Print QR code',
    'Imprimir código QR',
    'QR-Code drucken',
    'Druk QR-kode',
  );
  String get regenerateTableLink => _t(
    'Régénérer le lien de la table',
    'Regenerate table link',
    'Regenerar el enlace de la mesa',
    'Tisch-Link neu erzeugen',
    'Hergenereer tafelskakel',
  );
  String regenerateTableLinkConfirm(String label) => _t(
    'Créer un nouveau lien pour $label ? Les fiches QR déjà imprimées pour cette table cesseront de fonctionner ; réimprimez sa fiche.',
    'Create a new link for $label? QR slips already printed for this table stop working; reprint its slip.',
    '¿Crear un enlace nuevo para $label? Las hojas QR ya impresas para esta mesa dejarán de funcionar; vuelve a imprimir su hoja.',
    'Neuen Link für $label erstellen? Bereits gedruckte QR-Zettel für diesen Tisch funktionieren dann nicht mehr; drucken Sie den Zettel neu.',
    'Skep ’n nuwe skakel vir $label? QR-strokies wat reeds vir hierdie tafel gedruk is, sal nie meer werk nie; druk sy strokie weer.',
  );
  String get regenerate =>
      _t('Régénérer', 'Regenerate', 'Regenerar', 'Neu erzeugen', 'Hergenereer');
  String get tableLinkRegenerated => _t(
    'Nouveau lien créé. Réimprimez la fiche QR de cette table.',
    'New link created. Reprint this table\'s QR slip.',
    'Se creó un enlace nuevo. Vuelve a imprimir la hoja QR de esta mesa.',
    'Neuer Link erstellt. Bitte den QR-Zettel dieses Tisches neu drucken.',
    'Nuwe skakel geskep. Druk hierdie tafel se QR-strokie weer.',
  );
  String get slipSentToPrinter => _t(
    'Fiche QR envoyée à l’imprimante',
    'QR slip sent to the printer',
    'Hoja QR enviada a la impresora',
    'QR-Zettel an den Drucker gesendet',
    'QR-strokie na die drukker gestuur',
  );
  String get sectionStaffApp => _t(
    'Application du personnel',
    'Staff app',
    'App del personal',
    'Personal-App',
    'Personeelapp',
  );
  String get staffAppQrLabel => _t(
    'Application de commande du personnel — balayez pour l’ouvrir',
    'Staff ordering app — scan to open',
    'App de pedidos del personal: escanea para abrir',
    'Bestell-App fürs Personal – zum Öffnen scannen',
    'Personeel-bestelapp — skandeer om oop te maak',
  );
  String get sectionReportsPortal =>
      _t('Rapports', 'Reports', 'Reportes', 'Berichte', 'Verslae');
  String get reportsPortalQrLabel => _t(
    'Portail de rapports du propriétaire — balayez pour l’ouvrir',
    'Owner reporting portal — scan to open',
    'Portal de reportes del dueño: escanea para abrir',
    'Auswertungsportal für Inhaber – zum Öffnen scannen',
    'Eienaar-verslagportaal — skandeer om oop te maak',
  );
  String get cloudNotConfigured => _t(
    'Nuage non configuré',
    'Cloud not configured',
    'La nube no está configurada',
    'Cloud nicht eingerichtet',
    'Wolk nie opgestel nie',
  );

  // pending-order alerts (settings + banner)
  String get sectionAlerts => _t(
    'Alertes de commandes clients',
    'Customer order alerts',
    'Alertas de pedidos de clientes',
    'Hinweise bei Gästebestellungen',
    'Waarskuwings vir klantbestellings',
  );
  String get alertsEnabledLabel => _t(
    'Carillon à chaque nouvelle commande d’un client',
    'Chime on new customer orders',
    'Sonido con cada pedido nuevo de un cliente',
    'Signalton bei neuen Gästebestellungen',
    'Klokkie by nuwe klantbestellings',
  );
  String get alertEscalateLabel => _t(
    'Alerte insistante si rien n’est fait après (secondes)',
    'Escalate if un-actioned after (seconds)',
    'Alerta insistente si nadie responde después de (segundos)',
    'Erneut melden, wenn unbearbeitet nach (Sekunden)',
    'Eskaleer indien onafgehandel na (sekondes)',
  );
  String get alertVolumeLabel => _t(
    'Volume de l’alerte',
    'Alert volume',
    'Volumen de la alerta',
    'Lautstärke der Hinweise',
    'Waarskuwingvolume',
  );

  // receipt printer (settings)
  String get sectionPrinter => _t(
    'Imprimante à reçus',
    'Receipt printer',
    'Impresora de recibos',
    'Belegdrucker',
    'Kwitansiedrukker',
  );
  String get printerIpLabel => _t(
    'IP de l’imprimante (vide = désactivée)',
    'Printer IP (blank = off)',
    'IP de la impresora (vacío = desactivada)',
    'Drucker-IP (leer = aus)',
    'Drukker-IP (leeg = af)',
  );
  String get printerPortLabel =>
      _t('Port réseau', 'Port', 'Puerto', 'Port', 'Poort');
  String get scanForPrinter => _t(
    'Chercher l’imprimante sur le réseau',
    'Scan network for printer',
    'Buscar la impresora en la red',
    'Netzwerk nach Drucker durchsuchen',
    'Soek netwerk vir drukker',
  );
  String get scanningForPrinter => _t(
    'Recherche de l’imprimante…',
    'Scanning for printer…',
    'Buscando la impresora…',
    'Drucker wird gesucht …',
    'Soek drukker…',
  );
  String printerFoundAt(String ip) => _t(
    'Imprimante trouvée : $ip',
    'Found printer: $ip',
    'Impresora encontrada: $ip',
    'Drucker gefunden: $ip',
    'Drukker gevind: $ip',
  );
  String printersFound(int n, String ip) => _t(
    '$n imprimantes trouvées — utilisation de $ip',
    'Found $n printers — using $ip',
    'Se encontraron $n impresoras: usando $ip',
    '$n Drucker gefunden – verwendet wird $ip',
    '$n drukkers gevind — gebruik $ip',
  );
  String get printerNotFound => _t(
    'Aucune imprimante trouvée sur ce réseau',
    'No printer found on this network',
    'No se encontró ninguna impresora en esta red',
    'Kein Drucker in diesem Netzwerk gefunden',
    'Geen drukker op hierdie netwerk gevind nie',
  );
  String get testPrint => _t(
    'Impression d’essai',
    'Test print',
    'Impresión de prueba',
    'Testdruck',
    'Toetsdruk',
  );
  String get printerTestSent => _t(
    'Page d’essai envoyée à l’imprimante',
    'Test page sent to the printer',
    'Página de prueba enviada a la impresora',
    'Testseite an den Drucker gesendet',
    'Toetsbladsy na die drukker gestuur',
  );
  String get printerNotConfigured => _t(
    'IP de l’imprimante non définie',
    'Printer IP not set',
    'No se configuró la IP de la impresora',
    'Keine Drucker-IP eingestellt',
    'Drukker-IP nie gestel nie',
  );
  String get printerOffline => _t(
    'Échec de l’impression — imprimante hors ligne',
    'Print failed — printer offline',
    'Falló la impresión: impresora desconectada',
    'Druck fehlgeschlagen – Drucker offline',
    'Druk het misluk — drukker vanlyn',
  );
  String get sectionDemoMode =>
      _t('Mode démo', 'Demo mode', 'Modo demo', 'Demo-Modus', 'Demo-modus');
  String get demoModeOn => _t(
    'Activé (demo.mode dans store.properties). L’application du personnel se connecte avec le NIP seulement.',
    'On (demo.mode in store.properties). The staff app signs in with a PIN only.',
    'Activado (demo.mode en store.properties). La app del personal inicia sesión solo con el PIN.',
    'Ein (demo.mode in store.properties). Die Personal-App meldet sich nur mit der PIN an.',
    'Aan (demo.mode in store.properties). Die personeel-app meld net met ’n PIN aan.',
  );
  String get demoModeOff => _t(
    'Désactivé (demo.mode dans store.properties).',
    'Off (demo.mode in store.properties).',
    'Desactivado (demo.mode en store.properties).',
    'Aus (demo.mode in store.properties).',
    'Af (demo.mode in store.properties).',
  );
  String get printDemoSheet => _t(
    'Imprimer la fiche QR de démo',
    'Print demo QR sheet',
    'Imprimir la hoja QR de demo',
    'Demo-QR-Blatt drucken',
    'Druk demo-QR-blad',
  );
  String get demoSheetPrinted => _t(
    'Fiche QR de démo envoyée à l’imprimante',
    'Demo QR sheet sent to the printer',
    'Hoja QR de demo enviada a la impresora',
    'Demo-QR-Blatt an den Drucker gesendet',
    'Demo-QR-blad na die drukker gestuur',
  );
  String get printAllTableQr => _t(
    'Imprimer les codes QR de toutes les tables',
    'Print all table QR codes',
    'Imprimir los códigos QR de todas las mesas',
    'Alle Tisch-QR-Codes drucken',
    'Druk alle tafel-QR-kodes',
  );
  String printAllTableQrConfirm(int n) => _t(
    'Imprimer les fiches QR des $n tables ? Cela prend beaucoup de papier.',
    'Print QR slips for all $n tables? This uses a lot of paper.',
    '¿Imprimir las hojas QR de las $n mesas? Esto usa mucho papel.',
    'QR-Zettel für alle $n Tische drucken? Das braucht viel Papier.',
    'Druk QR-strokies vir al $n tafels? Dit gebruik baie papier.',
  );
  String slipsPrinted(int n) => _t(
    '$n fiches imprimées',
    'Printed $n slips',
    'Se imprimieron $n hojas',
    '$n Zettel gedruckt',
    '$n strokies gedruk',
  );

  // guest Wi-Fi (settings): the join slip and step 1 of the table slips
  String get sectionGuestWifi => _t(
    'Wi-Fi invités',
    'Guest Wi-Fi',
    'Wi-Fi para clientes',
    'Gäste-WLAN',
    'Gaste-Wi-Fi',
  );
  String get guestWifiHint => _t(
    'Imprimé sur une fiche Wi-Fi et comme première étape des fiches QR des tables.',
    'Printed on a Wi-Fi slip and as step 1 on the table QR slips.',
    'Se imprime en una hoja de Wi-Fi y como paso 1 en las hojas QR de las mesas.',
    'Wird auf einem WLAN-Zettel und als Schritt 1 auf den Tisch-QR-Zetteln gedruckt.',
    'Gedruk op ’n Wi-Fi-strokie en as stap 1 op die tafel-QR-strokies.',
  );
  String get wifiSsidLabel => _t(
    'Nom du réseau (vide = aucun)',
    'Network name (blank = none)',
    'Nombre de la red (vacío = ninguna)',
    'Netzwerkname (leer = keins)',
    'Netwerknaam (leeg = geen)',
  );
  String get wifiPasswordLabel =>
      _t('Mot de passe', 'Password', 'Contraseña', 'Passwort', 'Wagwoord');
  String get wifiShowPassword => _t(
    'Afficher le mot de passe',
    'Show password',
    'Mostrar contraseña',
    'Passwort anzeigen',
    'Wys wagwoord',
  );
  String get wifiHidePassword => _t(
    'Masquer le mot de passe',
    'Hide password',
    'Ocultar contraseña',
    'Passwort verbergen',
    'Versteek wagwoord',
  );
  String get wifiSecurityLabel =>
      _t('Sécurité', 'Security', 'Seguridad', 'Sicherheit', 'Sekuriteit');
  String get wifiSecurityNone => _t(
    'Aucune (réseau ouvert)',
    'None (open)',
    'Ninguna (abierta)',
    'Keine (offen)',
    'Geen (oop)',
  );
  String get wifiHiddenLabel => _t(
    'Réseau masqué',
    'Hidden network',
    'Red oculta',
    'Verborgenes Netzwerk',
    'Versteekte netwerk',
  );
  String get printWifiSlip => _t(
    'Imprimer la fiche Wi-Fi',
    'Print Wi-Fi slip',
    'Imprimir hoja de Wi-Fi',
    'WLAN-Zettel drucken',
    'Druk Wi-Fi-strokie',
  );
  String get wifiCopiesLabel =>
      _t('Exemplaires', 'Copies', 'Copias', 'Anzahl', 'Kopieë');
  String wifiSlipsPrinted(int n) => n == 1
      ? _t(
          'Fiche Wi-Fi imprimée',
          'Wi-Fi slip printed',
          'Hoja de Wi-Fi impresa',
          'WLAN-Zettel gedruckt',
          'Wi-Fi-strokie gedruk',
        )
      : _t(
          '$n fiches Wi-Fi imprimées',
          'Printed $n Wi-Fi slips',
          'Se imprimieron $n hojas de Wi-Fi',
          '$n WLAN-Zettel gedruckt',
          '$n Wi-Fi-strokies gedruk',
        );
  String get wifiNotConfigured => _t(
    'Entrez d’abord le nom et le mot de passe du réseau Wi-Fi',
    'Set the Wi-Fi network name and password first',
    'Primero configura el nombre y la contraseña de la red Wi-Fi',
    'Bitte zuerst WLAN-Name und Passwort eingeben',
    'Stel eers die Wi-Fi-netwerknaam en wagwoord',
  );
  String get saveFirstToTest => _t(
    'Enregistrez d’abord, puis lancez l’impression d’essai',
    'Save first, then test print',
    'Guarda primero y luego haz la impresión de prueba',
    'Erst speichern, dann Testdruck',
    'Stoor eers, dan toetsdruk',
  );
  String ordersWaiting(int n) => n == 1
      ? _t(
          '1 commande en attente',
          '1 order waiting',
          '1 pedido en espera',
          '1 Bestellung wartet',
          '1 bestelling wag',
        )
      : _t(
          '$n commandes en attente',
          '$n orders waiting',
          '$n pedidos en espera',
          '$n Bestellungen warten',
          '$n bestellings wag',
        );

  /// Compact age for the alert banner: seconds under a minute, else minutes.
  String alertAge(Duration d) =>
      d.inMinutes < 1 ? '${d.inSeconds}s' : elapsedShort(d);

  // zone open/closed
  String get zoneClosed =>
      _t('Fermée', 'Closed', 'Cerrada', 'Geschlossen', 'Gesluit');
  String get zoneClosedBanner => _t(
    'Cette section est temporairement fermée. Adressez-vous au personnel.',
    'This section is temporarily closed. Please ask staff.',
    'Esta sección está cerrada por el momento. Pregunta al personal.',
    'Dieser Bereich ist vorübergehend geschlossen. Bitte wenden Sie sich an das Personal.',
    'Hierdie afdeling is tydelik gesluit. Vra asseblief die personeel.',
  );
  String get zoneCloseAction => _t(
    'Fermer la zone',
    'Close zone',
    'Cerrar zona',
    'Bereich schließen',
    'Sluit area',
  );
  String get zoneReopenAction => _t(
    'Rouvrir la zone',
    'Reopen zone',
    'Reabrir zona',
    'Bereich öffnen',
    'Heropen area',
  );
  String get newCheckBlockedZoneClosed => _t(
    'Zone fermée',
    'Zone is closed',
    'La zona está cerrada',
    'Bereich ist geschlossen',
    'Area is gesluit',
  );

  // zone (room) management — add / rename / delete / reorder
  String get addRoom => _t(
    'Ajouter une salle',
    'Add room',
    'Agregar salón',
    'Raum hinzufügen',
    'Voeg vertrek by',
  );
  String get manageRoom => _t(
    'Gérer la salle',
    'Manage room',
    'Administrar salón',
    'Raum verwalten',
    'Bestuur vertrek',
  );
  String get renameRoom => _t(
    'Renommer la salle',
    'Rename room',
    'Cambiar nombre del salón',
    'Raum umbenennen',
    'Hernoem vertrek',
  );
  String get deleteRoom => _t(
    'Supprimer la salle',
    'Delete room',
    'Eliminar salón',
    'Raum löschen',
    'Verwyder vertrek',
  );
  String deleteRoomConfirm(String name) => _t(
    'Supprimer la salle « $name » ? Elle doit être vide (ni tables ni éléments) pour être supprimée.',
    'Delete room "$name"? A room must be empty (no tables or objects) to delete.',
    '¿Eliminar el salón "$name"? Debe estar vacío (sin mesas ni elementos) para eliminarlo.',
    'Raum „$name“ löschen? Nur ein leerer Raum (ohne Tische und Objekte) kann gelöscht werden.',
    'Verwyder vertrek “$name”? ’n Vertrek moet leeg wees (geen tafels of voorwerpe nie) om verwyder te word.',
  );
  String get roomDeleted => _t(
    'Salle supprimée',
    'Room deleted',
    'Salón eliminado',
    'Raum gelöscht',
    'Vertrek verwyder',
  );
  String get roomCreated => _t(
    'Salle créée',
    'Room created',
    'Salón creado',
    'Raum angelegt',
    'Vertrek geskep',
  );
  String get roomNameRequired => _t(
    'Entrez un nom pour la salle',
    'Enter a name for the room',
    'Escriba un nombre para el salón',
    'Bitte einen Namen für den Raum eingeben',
    'Voer ’n naam vir die vertrek in',
  );
  String get moveRoomLeft => _t(
    'Déplacer à gauche',
    'Move left',
    'Mover a la izquierda',
    'Nach links',
    'Skuif links',
  );
  String get moveRoomRight => _t(
    'Déplacer à droite',
    'Move right',
    'Mover a la derecha',
    'Nach rechts',
    'Skuif regs',
  );
  String get roomNameFrField => _t(
    'Nom de la salle (français)',
    'Room name (French)',
    'Nombre del salón (francés)',
    'Raumname (Französisch)',
    'Vertreknaam (Frans)',
  );
  String get roomNameEnField => _t(
    'Nom de la salle (anglais)',
    'Room name (English)',
    'Nombre del salón (inglés)',
    'Raumname (Englisch)',
    'Vertreknaam (Engels)',
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
  );

  /// Server error codes → local language. Fallback: raw server message.
  String? apiError(String? code) => switch (code) {
    'invalid_pin' => _t(
      'NIP invalide',
      'Invalid PIN',
      'PIN no válido',
      'Ungültige PIN',
      'Ongeldige PIN',
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
    ),
    'image_rate_limited' => _t(
      'Trop de demandes de photos IA. Réessayez dans une minute.',
      'Too many AI photo requests. Try again in a minute.',
      'Demasiadas solicitudes de fotos con IA. Inténtalo en un minuto.',
      'Zu viele KI-Fotoanfragen. In einer Minute erneut versuchen.',
      'Te veel KI-fotoversoeke. Probeer weer oor ’n minuut.',
    ),
    'image_quota' => _t(
      'Le compte de photos IA n’a plus de crédits.',
      'The AI photo account is out of credits.',
      'La cuenta de fotos con IA se quedó sin créditos.',
      'Das Guthaben des KI-Fotokontos ist aufgebraucht.',
      'Die KI-foto-rekening se krediete is op.',
    ),
    'image_daily_limit' => _t(
      'La limite quotidienne de photos IA de ce magasin est atteinte. Réessayez demain.',
      'This store’s daily AI photo limit is reached. Try again tomorrow.',
      'Se alcanzó el límite diario de fotos con IA de esta tienda. Inténtalo mañana.',
      'Das tägliche KI-Fotolimit dieses Geschäfts ist erreicht. Morgen erneut versuchen.',
      'Hierdie winkel se daaglikse KI-fotolimiet is bereik. Probeer môre weer.',
    ),
    'image_refused' => _t(
      'Le service de photos IA a refusé cette demande. Modifiez la description et réessayez.',
      'The AI photo service declined this request. Adjust the description and try again.',
      'El servicio de fotos con IA rechazó la solicitud. Ajusta la descripción e inténtalo de nuevo.',
      'Der KI-Fotodienst hat die Anfrage abgelehnt. Beschreibung anpassen und erneut versuchen.',
      'Die KI-fotodiens het hierdie versoek geweier. Pas die beskrywing aan en probeer weer.',
    ),
    'image_auth' || 'image_error' => _t(
      'Le service de photos IA a renvoyé une erreur. Réessayez plus tard.',
      'The AI photo service returned an error. Try again later.',
      'El servicio de fotos con IA devolvió un error. Inténtalo más tarde.',
      'Der KI-Fotodienst meldet einen Fehler. Später erneut versuchen.',
      'Die KI-fotodiens het ’n fout teruggegee. Probeer later weer.',
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
    ),
    'menu_ai_too_many' => _t(
      'Trop de demandes IA (20 par 10 minutes). Réessayez dans quelques minutes.',
      'Too many AI requests (20 every 10 minutes). Try again in a few minutes.',
      'Demasiadas solicitudes de IA (20 cada 10 minutos). Inténtalo en unos minutos.',
      'Zu viele KI-Anfragen (20 pro 10 Minuten). In ein paar Minuten erneut versuchen.',
      'Te veel KI-versoeke (20 elke 10 minute). Probeer weer oor ’n paar minute.',
    ),
    'menu_ai_confirm_required' => _t(
      'Ce gros changement doit être confirmé. Touchez « Appliquer » de nouveau.',
      'This big change needs a confirm. Tap Apply again.',
      'Este cambio grande necesita confirmación. Toca Aplicar de nuevo.',
      'Diese große Änderung muss bestätigt werden. Erneut auf Übernehmen tippen.',
      'Hierdie groot verandering moet bevestig word. Tik weer op Pas toe.',
    ),
    'menu_ai_quota' => _t(
      'Le compte IA n’a plus de crédits.',
      'The AI account is out of credits.',
      'La cuenta de IA se quedó sin créditos.',
      'Das Guthaben des KI-Kontos ist aufgebraucht.',
      'Die KI-rekening se krediete is op.',
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
    ),
    'menu_ai_already_reverted' => aiMenuReverted,
    'wifi_not_configured' => wifiNotConfigured,
    'pin_in_use' => _t(
      'Ce NIP est déjà utilisé par un autre employé',
      'That PIN is already used by another staff member',
      'Ese PIN ya lo usa otro empleado',
      'Diese PIN wird bereits von einem anderen Mitarbeiter verwendet',
      'Daardie PIN word reeds deur ’n ander personeellid gebruik',
    ),
    'bad_pin' => _t(
      'Le NIP doit compter 4 chiffres',
      'PIN must be 4 digits',
      'El PIN debe tener 4 dígitos',
      'Die PIN muss 4 Ziffern haben',
      'PIN moet 4 syfers wees',
    ),
    'last_manager' => _t(
      'Gardez au moins un gérant actif pour gérer le personnel',
      'Keep at least one active manager who can manage staff',
      'Debe quedar al menos un gerente activo que administre al personal',
      'Mindestens ein aktiver Manager mit Personalverwaltung muss bleiben',
      'Hou ten minste een aktiewe bestuurder wat personeel kan bestuur',
    ),
    'login_required' => _t(
      'Veuillez vous reconnecter',
      'Please log in again',
      'Inicia sesión de nuevo',
      'Bitte erneut anmelden',
      'Meld asseblief weer aan',
    ),
    'manager_approval_required' => _t(
      'Approbation du gérant requise',
      'Manager approval required',
      'Se requiere la aprobación del gerente',
      'Freigabe durch Manager erforderlich',
      'Bestuurder se goedkeuring nodig',
    ),
    'manager_pos_only' => _t(
      'En mode démo, le gérant se connecte seulement sur la tablette du PDV',
      'In demo mode, managers sign in only on the POS tablet',
      'En modo demo, el gerente inicia sesión solo en la tableta del TPV',
      'Im Demo-Modus meldet sich die Leitung nur am Kassen-Tablet an',
      'In demo-modus meld die bestuurder net op die kassa-tablet aan',
    ),
    'pos_terminal_required' => _t(
      'Seulement sur la caisse (PDV)',
      'Only on the POS terminal',
      'Solo en el terminal TPV',
      'Nur an der Kasse',
      'Net op die kassa-terminaal',
    ),
    'check_not_open' => _t(
      'Cette addition n’est plus ouverte',
      'This bill is no longer open',
      'Esta cuenta ya no está abierta',
      'Diese Rechnung ist nicht mehr offen',
      'Hierdie rekening is nie meer oop nie',
    ),
    'zone_closed' => _t(
      'Zone fermée',
      'Zone is closed',
      'La zona está cerrada',
      'Bereich ist geschlossen',
      'Area is gesluit',
    ),
    'bill_locked' => _t(
      'Paiement de l’addition en cours — adressez-vous au personnel',
      'Bill is being paid — ask staff',
      'La cuenta se está pagando: pregunta al personal',
      'Rechnung wird gerade bezahlt – bitte Personal fragen',
      'Rekening word betaal — vra die personeel',
    ),
    'already_paid' => _t(
      'Addition déjà entièrement payée',
      'Bill already fully paid',
      'La cuenta ya está pagada por completo',
      'Rechnung ist bereits vollständig bezahlt',
      'Rekening is reeds ten volle betaal',
    ),
    'pending_lines_unresolved' => _t(
      'Des commandes clients attendent une confirmation — réglez-les avant le paiement',
      'Customer orders awaiting confirm — resolve before payment',
      'Hay pedidos de clientes por confirmar: resuélvelos antes de cobrar',
      'Gästebestellungen warten auf Bestätigung – vor dem Bezahlen erledigen',
      'Klantbestellings wag op bevestiging — handel dit af voor betaling',
    ),
    'outstanding_balance' => _t(
      'Il reste un solde à payer',
      'Balance still outstanding',
      'Todavía queda saldo pendiente',
      'Es ist noch ein Betrag offen',
      'Saldo is nog uitstaande',
    ),
    'void_has_tenders' => _t(
      'L’addition a des paiements : annulez-la pour les rendre d’abord',
      'Bill has payments; void it to hand them back first',
      'La cuenta tiene pagos; anúlala para devolver primero los pagos',
      'Rechnung hat bereits Zahlungen; stornieren, um sie zuerst zurückzugeben',
      'Rekening het betalings; kanselleer dit om hulle eers terug te gee',
    ),
    'refund_not_closed' => _t(
      'Seule une addition fermée peut être remboursée',
      'Only a closed bill can be refunded',
      'Solo se puede reembolsar una cuenta cerrada',
      'Nur eine geschlossene Rechnung kann erstattet werden',
      'Slegs ’n geslote rekening kan terugbetaal word',
    ),
    'refund_exceeds_total' => _t(
      'Le remboursement dépasse le montant encore remboursable',
      'Refund exceeds the remaining refundable amount',
      'El reembolso supera el monto que queda por reembolsar',
      'Erstattung übersteigt den noch erstattbaren Betrag',
      'Terugbetaling oorskry die oorblywende terugbetaalbare bedrag',
    ),
    'refund_non_positive' => _t(
      'Le montant du remboursement doit être supérieur à zéro',
      'Refund amount must be positive',
      'El monto del reembolso debe ser mayor que cero',
      'Erstattungsbetrag muss positiv sein',
      'Terugbetalingsbedrag moet positief wees',
    ),
    'refund_no_amount' => _t(
      'Entrez un montant ou choisissez des articles',
      'Enter an amount or pick items',
      'Ingresa un monto o elige artículos',
      'Betrag eingeben oder Artikel auswählen',
      'Voer ’n bedrag in of kies items',
    ),
    'refund_bad_tender' => _t(
      'Mode de remboursement invalide',
      'Invalid refund tender',
      'Forma de reembolso no válida',
      'Ungültige Erstattungsart',
      'Ongeldige terugbetalingsmetode',
    ),
    'refund_bad_line' => _t(
      'L’article choisi n’est pas sur cette addition',
      'Selected item is not on this bill',
      'El artículo elegido no está en esta cuenta',
      'Der gewählte Artikel steht nicht auf dieser Rechnung',
      'Gekose item is nie op hierdie rekening nie',
    ),
    'refund_qty_too_high' => _t(
      'La quantité dépasse celle de l’addition',
      'Refund quantity exceeds the bill',
      'La cantidad supera la de la cuenta',
      'Erstattungsmenge übersteigt die Rechnung',
      'Terugbetalingshoeveelheid oorskry die rekening',
    ),
    'refund_line_already_refunded' => _t(
      'Cet article a déjà été remboursé',
      'This item was already refunded',
      'Este artículo ya se reembolsó',
      'Dieser Artikel wurde bereits erstattet',
      'Hierdie item is reeds terugbetaal',
    ),
    'refund_tender_mismatch' => _t(
      'Remboursez comme le client a payé (carte sur la carte, comptant en comptant) — sinon un gérant doit approuver',
      'Refund the way the guest paid (card to card, cash to cash) — otherwise a manager must approve',
      'Reembolsa como pagó el cliente (tarjeta a tarjeta, efectivo en efectivo); si no, debe aprobarlo un gerente',
      'So erstatten, wie der Gast bezahlt hat (Karte auf Karte, bar in bar) – sonst muss ein Manager freigeben',
      'Betaal terug soos die gas betaal het (kaart na kaart, kontant in kontant) — anders moet ’n bestuurder goedkeur',
    ),
    'qty_out_of_range' => _t(
      'Quantité trop élevée',
      'Quantity is too high',
      'La cantidad es demasiado alta',
      'Menge ist zu hoch',
      'Hoeveelheid is te hoog',
    ),
    'price_too_high' => _t(
      'Prix trop élevé (maximum 99 999,99 \$ par unité)',
      'Price is too high (at most \$99,999.99 each)',
      'El precio es demasiado alto (máximo \$99,999.99 por unidad)',
      'Preis ist zu hoch (höchstens \$99,999.99 pro Stück)',
      'Prys is te hoog (hoogstens \$99,999.99 elk)',
    ),
    'price_non_positive' => _t(
      'Le prix doit être supérieur à zéro',
      'Price must be more than zero',
      'El precio debe ser mayor que cero',
      'Preis muss größer als null sein',
      'Prys moet meer as nul wees',
    ),
    'cash_amount_too_high' => _t(
      'Montant trop élevé — vérifiez le comptant reçu',
      'Amount is too high — check the cash received',
      'El monto es demasiado alto: revisa el efectivo recibido',
      'Betrag ist zu hoch – erhaltenes Bargeld prüfen',
      'Bedrag is te hoog — kyk na die kontant ontvang',
    ),
    'too_many_lines' => _t(
      'Trop d’articles dans une seule commande',
      'Too many items in one order',
      'Demasiados artículos en un solo pedido',
      'Zu viele Artikel in einer Bestellung',
      'Te veel items in een bestelling',
    ),
    'too_many_pending' => _t(
      'Trop de commandes clients en attente — confirmez-les ou refusez-les',
      'Too many customer orders waiting — accept or reject them',
      'Demasiados pedidos de clientes en espera: acéptalos o recházalos',
      'Zu viele Gästebestellungen offen – annehmen oder ablehnen',
      'Te veel klantbestellings wag — aanvaar of weier hulle',
    ),
    'shift_has_paid_open_bills' => _t(
      'Des additions encore ouvertes ont déjà des paiements — terminez-les ou annulez-les avant de fermer le quart',
      'Some open bills already have payments — finish or cancel them before closing the shift',
      'Hay cuentas abiertas que ya tienen pagos: termínalas o anúlalas antes de cerrar el turno',
      'Offene Rechnungen haben bereits Zahlungen – vor dem Schichtabschluss abschließen oder stornieren',
      'Sommige oop rekenings het reeds betalings — voltooi of kanselleer hulle voor die skof gesluit word',
    ),
    'void_card_on_reader' => _t(
      'Un paiement par carte au terminal ne peut pas être rendu ici — terminez l’addition puis remboursez la carte',
      'A card paid on the reader can’t be handed back here — finish the bill, then refund the card',
      'Un pago con tarjeta en el lector no se puede devolver aquí: termina la cuenta y luego reembolsa la tarjeta',
      'Eine Kartenzahlung am Terminal kann hier nicht zurückgegeben werden – Rechnung abschließen, dann Karte erstatten',
      '’n Kaartbetaling op die leser kan nie hier teruggegee word nie — voltooi die rekening en betaal dan die kaart terug',
    ),
    'cash_bad_direction' => _t(
      'Sens invalide',
      'Invalid direction',
      'Dirección no válida',
      'Ungültige Richtung',
      'Ongeldige rigting',
    ),
    'cash_non_positive' => _t(
      'Le montant doit être supérieur à zéro',
      'Amount must be positive',
      'El monto debe ser mayor que cero',
      'Betrag muss positiv sein',
      'Bedrag moet positief wees',
    ),
    'no_open_shift' => _t(
      'Aucun quart ouvert',
      'No shift open',
      'No hay turno abierto',
      'Keine Schicht geöffnet',
      'Geen skof oop nie',
    ),
    'shift_already_open' => _t(
      'Un quart est déjà ouvert',
      'A shift is already open',
      'Ya hay un turno abierto',
      'Es ist bereits eine Schicht geöffnet',
      '’n Skof is reeds oop',
    ),
    'tender_type_not_accepted' => _t(
      'Mode de paiement non accepté',
      'Payment method not accepted',
      'No se acepta esta forma de pago',
      'Zahlungsart nicht akzeptiert',
      'Betaalmetode word nie aanvaar nie',
    ),
    'no_receipt_yet' => _t(
      'Pas encore de reçu',
      'No receipt yet',
      'Todavía no hay recibo',
      'Noch kein Beleg',
      'Nog geen kwitansie nie',
    ),
    'check_not_billable' => _t(
      'Cette addition est fermée — impression impossible',
      'This bill is closed — cannot print',
      'Esta cuenta está cerrada: no se puede imprimir',
      'Diese Rechnung ist geschlossen – Druck nicht möglich',
      'Hierdie rekening is gesluit — kan nie druk nie',
    ),
    'bad_pairing_code' => _t(
      'Code de jumelage invalide ou expiré',
      'Invalid or expired pairing code',
      'Código de vinculación no válido o vencido',
      'Kopplungscode ungültig oder abgelaufen',
      'Ongeldige of verstreke koppelkode',
    ),
    'pairing_unavailable' => _t(
      'Jumelage temporairement indisponible — réessayez',
      'Pairing is temporarily unavailable — try again',
      'La vinculación no está disponible por ahora: inténtalo de nuevo',
      'Kopplung vorübergehend nicht verfügbar – erneut versuchen',
      'Koppeling is tydelik onbeskikbaar — probeer weer',
    ),
    'cloud_unreachable' => _t(
      'L’établissement ne joint pas le nuage — réessayez sous peu',
      'The store cannot reach the cloud — try again shortly',
      'La tienda no puede conectarse con la nube: inténtalo en un momento',
      'Die Filiale erreicht die Cloud nicht – gleich erneut versuchen',
      'Die winkel kan nie die wolk bereik nie — probeer binnekort weer',
    ),
    'device_required' => _t(
      'Ce terminal n’est pas jumelé à l’établissement',
      'This terminal is not paired with the store',
      'Esta terminal no está vinculada con la tienda',
      'Dieses Gerät ist nicht mit der Filiale gekoppelt',
      'Hierdie terminaal is nie met die winkel gekoppel nie',
    ),
    'device_revoked' => _t(
      'Le jumelage de ce terminal a été révoqué — jumelez-le de nouveau',
      'This terminal\'s pairing was revoked — pair again',
      'Se revocó la vinculación de esta terminal: vincúlala otra vez',
      'Die Kopplung dieses Geräts wurde widerrufen – bitte neu koppeln',
      'Hierdie terminaal se koppeling is herroep — koppel weer',
    ),
    'not_found' => _t(
      'Introuvable',
      'Not found',
      'No se encontró',
      'Nicht gefunden',
      'Nie gevind nie',
    ),
    'bad_request' || 'bad_body' => _t(
      'Demande invalide',
      'Invalid request',
      'Solicitud no válida',
      'Ungültige Anfrage',
      'Ongeldige versoek',
    ),
    'kiosk_not_found' => _t(
      'Cette borne n’est plus jumelée',
      'This kiosk is no longer paired',
      'Este quiosco ya no está vinculado',
      'Dieses Bestellterminal ist nicht mehr gekoppelt',
      'Hierdie kiosk is nie meer gekoppel nie',
    ),
    'rate_limited' => _t(
      'Trop de tentatives échouées — réessayez plus tard',
      'Too many failed attempts — try again later',
      'Demasiados intentos fallidos: inténtalo más tarde',
      'Zu viele Fehlversuche – später erneut versuchen',
      'Te veel mislukte pogings — probeer later weer',
    ),
    'variant_in_use' => _t(
      'Ce format est sur une addition ouverte — fermez-la ou annulez-la d’abord',
      'This size is on an open order — close or void that check first',
      'Este tamaño está en una cuenta abierta: ciérrala o anúlala primero',
      'Diese Größe steht auf einer offenen Bestellung – Rechnung erst schließen oder stornieren',
      'Hierdie grootte is op ’n oop bestelling — sluit of kanselleer daardie rekening eers',
    ),
    'item_in_use' => _t(
      'Cet article est sur une addition ouverte — fermez-la ou annulez-la d’abord',
      'This item is on an open order — close or void that check first',
      'Este artículo está en una cuenta abierta: ciérrala o anúlala primero',
      'Dieser Artikel steht auf einer offenen Bestellung – Rechnung erst schließen oder stornieren',
      'Hierdie item is op ’n oop bestelling — sluit of kanselleer daardie rekening eers',
    ),
    'last_variant' => _t(
      'C’est le seul format de l’article — supprimez plutôt l’article au complet',
      'This is the item\'s only size — delete the whole item instead',
      'Es el único tamaño del artículo: mejor elimina el artículo completo',
      'Das ist die einzige Größe des Artikels – stattdessen den ganzen Artikel löschen',
      'Dit is die item se enigste grootte — verwyder eerder die hele item',
    ),
    'category_not_empty' => _t(
      'Cette catégorie contient encore des articles — déplacez-les ou retirez-les d’abord',
      'This category still has items — move or remove them first',
      'Esta categoría todavía tiene artículos: muévelos o quítalos primero',
      'Diese Kategorie enthält noch Artikel – erst verschieben oder entfernen',
      'Hierdie kategorie het nog items — skuif of verwyder hulle eers',
    ),
    'split_locked' => _t(
      'Un paiement a déjà été reçu — la séparation est verrouillée',
      'Money already taken — split is locked',
      'Ya se recibió un pago: la división está bloqueada',
      'Es wurde bereits kassiert – Teilung ist gesperrt',
      'Geld is reeds ontvang — verdeling is gesluit',
    ),
    'split_exists' => _t(
      'L’addition est déjà séparée',
      'Bill is already split',
      'La cuenta ya está dividida',
      'Rechnung ist bereits geteilt',
      'Rekening is reeds verdeel',
    ),
    'no_split' => _t(
      'L’addition n’est pas séparée',
      'Bill is not split',
      'La cuenta no está dividida',
      'Rechnung ist nicht geteilt',
      'Rekening is nie verdeel nie',
    ),
    'split_stale' => _t(
      'L’addition a changé depuis la séparation en parts égales — séparez-la de nouveau',
      'The bill changed since it was split evenly — split it again',
      'La cuenta cambió desde que se dividió en partes iguales: divídela otra vez',
      'Die Rechnung hat sich seit dem gleichmäßigen Teilen geändert – bitte neu teilen',
      'Die rekening het verander sedert dit gelyk verdeel is — verdeel dit weer',
    ),
    'split_unassigned_lines' => _t(
      'Des articles ne sont pas encore attribués — attribuez-les tous avant le paiement',
      'Some items are still unassigned — assign everything before payment',
      'Todavía hay artículos sin asignar: asígnalos todos antes de cobrar',
      'Einige Artikel sind noch nicht zugeordnet – vor dem Bezahlen alles zuordnen',
      'Sommige items is nog nie toegeken nie — ken alles toe voor betaling',
    ),
    'group_required' => _t(
      'L’addition est séparée — choisissez l’addition à payer',
      'Bill is split — pay a specific bill',
      'La cuenta está dividida: elige qué cuenta pagar',
      'Rechnung ist geteilt – eine bestimmte Teilrechnung bezahlen',
      'Rekening is verdeel — betaal ’n spesifieke rekening',
    ),
    'group_already_paid' => _t(
      'Cette addition est déjà payée',
      'This bill is already paid',
      'Esta cuenta ya está pagada',
      'Diese Rechnung ist bereits bezahlt',
      'Hierdie rekening is reeds betaal',
    ),
    'group_not_found' => _t(
      'Cette addition séparée n’existe plus — actualisez la séparation',
      'That split bill no longer exists — refresh the split',
      'Esa cuenta dividida ya no existe: actualiza la división',
      'Diese Teilrechnung gibt es nicht mehr – Teilung aktualisieren',
      'Daardie verdeelde rekening bestaan nie meer nie — verfris die verdeling',
    ),
    'group_outstanding' => _t(
      'Certaines additions ne sont pas encore payées',
      'Some bills still owe',
      'Algunas cuentas todavía tienen saldo',
      'Einige Rechnungen sind noch offen',
      'Sommige rekeninge is nog uitstaande',
    ),
    'qty_below_allocated' => _t(
      'L’article est attribué à une addition séparée — retirez-le de celle-ci d’abord',
      'Item is assigned to a split bill — unassign it first',
      'El artículo está asignado a una cuenta dividida: quítalo de ahí primero',
      'Artikel ist einer Teilrechnung zugeordnet – Zuordnung erst aufheben',
      'Item is aan ’n verdeelde rekening toegeken — hef die toekenning eers op',
    ),
    'qty_exceeds_unassigned' => _t(
      'Plus que la quantité non attribuée',
      'More than the unassigned quantity',
      'Más que la cantidad sin asignar',
      'Mehr als die nicht zugeordnete Menge',
      'Meer as die nie-toegekende hoeveelheid',
    ),
    'qty_exceeds_allocated' => _t(
      'Plus que la quantité attribuée',
      'More than the assigned quantity',
      'Más que la cantidad asignada',
      'Mehr als die zugeordnete Menge',
      'Meer as die toegekende hoeveelheid',
    ),
    'even_split' => _t(
      'L’addition est séparée en parts égales (÷N) — les articles ne peuvent pas être déplacés',
      'Split is even ÷N — items cannot move',
      'La cuenta está dividida en partes iguales (÷N): no se pueden mover artículos',
      'Gleichmäßig geteilt ÷N – Artikel können nicht verschoben werden',
      'Gelyk verdeel ÷N — items kan nie skuif nie',
    ),
    'empty_check' => _t(
      'Rien à séparer',
      'Nothing to split',
      'No hay nada que dividir',
      'Nichts zu teilen',
      'Niks om te verdeel nie',
    ),
    'last_group' => _t(
      'Il doit rester au moins une addition',
      'At least one bill must remain',
      'Debe quedar al menos una cuenta',
      'Mindestens eine Rechnung muss bleiben',
      'Minstens een rekening moet oorbly',
    ),
    'same_table' => _t(
      'L’addition est déjà à cette table',
      'Bill is already on this table',
      'La cuenta ya está en esta mesa',
      'Rechnung ist bereits an diesem Tisch',
      'Rekening is reeds by hierdie tafel',
    ),
    'same_check' => _t(
      'Impossible de fusionner une addition avec elle-même',
      'Cannot merge a bill into itself',
      'No se puede combinar una cuenta consigo misma',
      'Eine Rechnung kann nicht mit sich selbst zusammengeführt werden',
      'Kan nie ’n rekening met homself saamvoeg nie',
    ),
    'table_occupied' => _t(
      'La table a une addition ouverte — fusionnez plutôt',
      'Table has an open bill — merge instead',
      'La mesa tiene una cuenta abierta: mejor combínalas',
      'Tisch hat eine offene Rechnung – stattdessen zusammenführen',
      'Tafel het ’n oop rekening — voeg eerder saam',
    ),
    'table_in_use' => _t(
      'La table a une addition ouverte — fermez-la ou déplacez-la avant de retirer la table',
      'Table has an open bill — close or move it before removing',
      'La mesa tiene una cuenta abierta: ciérrala o muévela antes de quitar la mesa',
      'Tisch hat eine offene Rechnung – vor dem Entfernen schließen oder verschieben',
      'Tafel het ’n oop rekening — sluit of skuif dit voor verwydering',
    ),
    'has_sub_tables' => _t(
      'La table a des sous-tables — retirez-les d’abord',
      'Table has sub-tables — remove them first',
      'La mesa tiene submesas: quítalas primero',
      'Tisch hat Untertische – diese zuerst entfernen',
      'Tafel het subtafels — verwyder hulle eers',
    ),
    'label_taken' => _t(
      'Une table porte déjà ce nom dans cette zone',
      'A table with this label already exists in this zone',
      'Ya existe una mesa con ese nombre en esta zona',
      'In diesem Bereich gibt es bereits einen Tisch mit dieser Bezeichnung',
      '’n Tafel met hierdie etiket bestaan reeds in hierdie area',
    ),
    'table_not_in_zone' => _t(
      'Le plan ne correspond pas à cette zone — actualisez et réessayez',
      'Layout does not match this zone — refresh and retry',
      'El plano no coincide con esta zona: actualiza e inténtalo de nuevo',
      'Tischplan passt nicht zu diesem Bereich – aktualisieren und erneut versuchen',
      'Vloerplan pas nie by hierdie area nie — verfris en probeer weer',
    ),
    'zone_not_empty' => _t(
      'Cette salle contient encore des tables ou des éléments — videz-la avant de la supprimer',
      'This room still has tables or objects — clear them before deleting it',
      'Este salón todavía tiene mesas o elementos: vacíalo antes de eliminarlo',
      'Dieser Raum hat noch Tische oder Objekte – vor dem Löschen entfernen',
      'Hierdie vertrek het nog tafels of voorwerpe — verwyder hulle voordat jy dit uitvee',
    ),
    'clear_split_first' => _t(
      'L’addition est séparée — annulez d’abord la séparation',
      'Bill is split — clear the split first',
      'La cuenta está dividida: deshaz la división primero',
      'Rechnung ist geteilt – Teilung zuerst aufheben',
      'Rekening is verdeel — hef eers die verdeling op',
    ),
    'conflict' => _t(
      'Action impossible pour le moment',
      'Cannot do that right now',
      'No se puede hacer eso ahora',
      'Das ist gerade nicht möglich',
      'Kan dit nie nou doen nie',
    ),
    'internal' => _t(
      'Une erreur est survenue — réessayez',
      'Something went wrong — try again',
      'Algo salió mal: inténtalo de nuevo',
      'Etwas ist schiefgelaufen – erneut versuchen',
      'Iets het fout gegaan — probeer weer',
    ),
    'stripe_unavailable' => _t(
      'Stripe est injoignable (Internet ?). Rien n’a été fait chez Stripe.',
      'Can\'t reach Stripe (internet?). Nothing was done at Stripe.',
      'No se puede conectar con Stripe (¿internet?). No se hizo nada en Stripe.',
      'Stripe ist nicht erreichbar (Internet?). Bei Stripe wurde nichts ausgeführt.',
      'Kan Stripe nie bereik nie (internet?). Niks is by Stripe gedoen nie.',
    ),
    'stripe_declined' => stripeDeclineMessage(null),
    'stripe_not_configured' => _t(
      'La carte (Stripe) n’est pas configurée dans cet établissement',
      'Card (Stripe) is not set up on this store',
      'La tarjeta (Stripe) no está configurada en esta tienda',
      'Karte (Stripe) ist in dieser Filiale nicht eingerichtet',
      'Kaart (Stripe) is nie in hierdie winkel opgestel nie',
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
    ),
    'stripe_amount_exceeds_due' => _t(
      'L’addition ne doit plus ce montant — la carte n’a pas été débitée',
      'The bill no longer owes that amount — the card was not charged',
      'La cuenta ya no debe ese monto: no se cobró a la tarjeta',
      'Die Rechnung weist diesen Betrag nicht mehr aus – die Karte wurde nicht belastet',
      'Die rekening skuld nie meer daardie bedrag nie — die kaart is nie gehef nie',
    ),
    'stripe_payment_reversed' => _t(
      'Le paiement par carte n’a pas pu être appliqué à cette addition et a été remboursé',
      'The card payment could not be applied to this bill and was refunded',
      'El pago con tarjeta no se pudo aplicar a esta cuenta y se reembolsó',
      'Die Kartenzahlung konnte nicht auf diese Rechnung gebucht werden und wurde erstattet',
      'Die kaartbetaling kon nie op hierdie rekening toegepas word nie en is terugbetaal',
    ),
    'stripe_payment_canceled' => _t(
      'Ce paiement par carte a été annulé',
      'That card payment was canceled',
      'Se canceló ese pago con tarjeta',
      'Diese Kartenzahlung wurde abgebrochen',
      'Daardie kaartbetaling is gekanselleer',
    ),
    'stripe_not_ready' => _t(
      'Le paiement par carte n’est pas terminé — réessayez',
      'The card payment isn\'t finished yet — try again',
      'El pago con tarjeta todavía no termina: inténtalo de nuevo',
      'Die Kartenzahlung ist noch nicht abgeschlossen – erneut versuchen',
      'Die kaartbetaling is nog nie klaar nie — probeer weer',
    ),
    'stripe_no_card_tender' => _t(
      'Cette addition n’a pas été payée par carte (Stripe)',
      'This bill wasn\'t paid by Card (Stripe)',
      'Esta cuenta no se pagó con tarjeta (Stripe)',
      'Diese Rechnung wurde nicht mit Karte (Stripe) bezahlt',
      'Hierdie rekening is nie met kaart (Stripe) betaal nie',
    ),
    'stripe_refund_exceeds_card' => _t(
      'Le remboursement dépasse le montant payé par carte (Stripe)',
      'The refund is more than was paid by Card (Stripe)',
      'El reembolso supera lo que se pagó con tarjeta (Stripe)',
      'Die Erstattung übersteigt den mit Karte (Stripe) bezahlten Betrag',
      'Die terugbetaling is meer as wat met kaart (Stripe) betaal is',
    ),
    'stripe_refund_split_required' => _t(
      'Remboursez chaque paiement par carte séparément',
      'Refund each card payment separately',
      'Reembolsa cada pago con tarjeta por separado',
      'Jede Kartenzahlung einzeln erstatten',
      'Betaal elke kaartbetaling afsonderlik terug',
    ),
    'stripe_refund_failed' => _t(
      'Stripe n’a pas effectué le remboursement. Rien n’a été enregistré.',
      'Stripe did not make the refund. Nothing was recorded.',
      'Stripe no hizo el reembolso. No se registró nada.',
      'Stripe hat die Erstattung nicht ausgeführt. Es wurde nichts gebucht.',
      'Stripe het nie die terugbetaling gedoen nie. Niks is aangeteken nie.',
    ),
    'stripe_refund_via_stripe' => _t(
      'Les remboursements par carte (Stripe) passent par Stripe',
      'Card (Stripe) refunds go through Stripe',
      'Los reembolsos con tarjeta (Stripe) se hacen por Stripe',
      'Erstattungen für Karte (Stripe) laufen über Stripe',
      'Terugbetalings vir kaart (Stripe) gaan deur Stripe',
    ),
    // retail counter
    'unknown_barcode' => _t(
      'Code-barres inconnu',
      'Unknown barcode',
      'Código de barras desconocido',
      'Unbekannter Barcode',
      'Onbekende strepieskode',
    ),
    'age_check_required' => _t(
      'Vérifiez la pièce d’identité du client avant le paiement',
      'Check the customer\'s ID before payment',
      'Verifica la identificación del cliente antes de cobrar',
      'Vor dem Bezahlen den Ausweis des Kunden prüfen',
      'Kontroleer die klant se ID voor betaling',
    ),
    'age_check_failed' => _t(
      'Vérification de l’âge échouée — retirez les articles réservés aux adultes',
      'ID check failed — remove the age-restricted items',
      'No pasó la verificación de edad: quita los artículos con restricción de edad',
      'Ausweisprüfung nicht bestanden – altersbeschränkte Artikel entfernen',
      'ID-kontrole het misluk — verwyder die ouderdomsbeperkte items',
    ),
    'barcode_taken' => _t(
      'Ce code-barres appartient déjà à un produit',
      'That barcode already belongs to a product',
      'Ese código de barras ya pertenece a un producto',
      'Dieser Barcode gehört bereits zu einem Produkt',
      'Daardie strepieskode behoort reeds aan ’n produk',
    ),
    'item_unavailable' => _t(
      'Cet article n’est plus offert (retiré du menu ou en rupture)',
      'That item is no longer available (taken off the menu or sold out)',
      'Ese artículo ya no está disponible (retirado del menú o agotado)',
      'Dieser Artikel ist nicht mehr verfügbar (von der Karte genommen oder ausverkauft)',
      'Daardie item is nie meer beskikbaar nie (van die spyskaart af of uitverkoop)',
    ),
    'price_changed' => _t(
      'Le prix de cet article vient de changer',
      'The price of that item just changed',
      'El precio de ese artículo acaba de cambiar',
      'Der Preis dieses Artikels hat sich gerade geändert',
      'Die prys van daardie item het pas verander',
    ),
    'lines_rejected' => _t(
      'Le menu a changé : aucun article n’a pu être ajouté',
      'The menu changed: none of the items could be added',
      'El menú cambió: no se pudo agregar ningún artículo',
      'Die Karte hat sich geändert: kein Artikel konnte hinzugefügt werden',
      'Die spyskaart het verander: geen items kon bygevoeg word nie',
    ),
    'item_inactive' => _t(
      'Ce produit n’est pas en vente',
      'That product is off sale',
      'Ese producto no está a la venta',
      'Dieses Produkt ist nicht im Verkauf',
      'Daardie produk is nie beskikbaar nie',
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
    ),
    'terminal_pairing_code_required' => _t(
      'Entrez le code affiché sur le terminal',
      'Enter the code shown on the terminal',
      'Escribe el código que muestra la terminal',
      'Den am Terminal angezeigten Code eingeben',
      'Voer die kode in wat op die terminaal gewys word',
    ),
    'terminal_bad_host' => _t(
      'Entrez l’adresse IP du terminal, par exemple 192.168.1.50',
      'Enter the terminal’s IP address, like 192.168.1.50',
      'Escribe la dirección IP de la terminal, por ejemplo 192.168.1.50',
      'IP-Adresse des Terminals eingeben, z. B. 192.168.1.50',
      'Voer die terminaal se IP-adres in, soos 192.168.1.50',
    ),
    'terminal_pairing_unsupported' => _t(
      'Ce terminal ne s’associe pas depuis la caisse',
      'This terminal doesn’t pair from the POS',
      'Esta terminal no se vincula desde la caja',
      'Dieses Terminal wird nicht über die Kasse gekoppelt',
      'Hierdie terminaal koppel nie vanaf die kasregister nie',
    ),
    'terminal_cancel_unavailable' => _t(
      'La carte est en cours de traitement — impossible d’annuler maintenant',
      'The card is being processed — it can’t be cancelled now',
      'La tarjeta se está procesando: no se puede cancelar ahora',
      'Die Karte wird verarbeitet – Abbruch jetzt nicht möglich',
      'Die kaart word verwerk — dit kan nie nou gekanselleer word nie',
    ),
    'terminal_already_recorded' => _t(
      'Ce paiement est déjà enregistré — faites un remboursement',
      'That payment is already recorded — refund it instead',
      'Ese pago ya está registrado: haz un reembolso',
      'Diese Zahlung ist bereits gebucht – stattdessen erstatten',
      'Daardie betaling is reeds aangeteken — betaal dit eerder terug',
    ),
    'terminal_no_card_tender' => _t(
      'Cette addition n’a pas été payée par carte au terminal',
      'This bill wasn’t paid by card on the terminal',
      'Esta cuenta no se pagó con tarjeta en la terminal',
      'Diese Rechnung wurde nicht mit Karte am Terminal bezahlt',
      'Hierdie rekening is nie met kaart op die terminaal betaal nie',
    ),
    'terminal_refund_exceeds_card' => _t(
      'Le remboursement dépasse ce qui a été payé par carte au terminal',
      'The refund is more than was paid by card on the terminal',
      'El reembolso supera lo pagado con tarjeta en la terminal',
      'Die Erstattung übersteigt den mit Karte am Terminal bezahlten Betrag',
      'Die terugbetaling is meer as wat met kaart op die terminaal betaal is',
    ),
    'terminal_refund_split_required' => _t(
      'Remboursez chaque paiement par carte séparément',
      'Refund each card payment separately',
      'Reembolsa cada pago con tarjeta por separado',
      'Jede Kartenzahlung einzeln erstatten',
      'Betaal elke kaartbetaling afsonderlik terug',
    ),
    'terminal_refund_failed' => _t(
      'Le terminal n’a pas fait le remboursement. Rien n’a été enregistré.',
      'The terminal didn’t make the refund. Nothing was recorded.',
      'La terminal no hizo el reembolso. No se registró nada.',
      'Das Terminal hat die Erstattung nicht ausgeführt. Es wurde nichts gebucht.',
      'Die terminaal het nie die terugbetaling gedoen nie. Niks is aangeteken nie.',
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
  );
  String get chargeCardTerminal => _t(
    'Encaisser au terminal',
    'Charge card on the terminal',
    'Cobrar en la terminal',
    'Karte am Terminal belasten',
    'Hef kaart op die terminaal',
  );
  String get terminalTitle => _t(
    'Paiement par carte au terminal',
    'Card payment on the terminal',
    'Pago con tarjeta en la terminal',
    'Kartenzahlung am Terminal',
    'Kaartbetaling op die terminaal',
  );
  String get terminalStarting => _t(
    'Envoi du montant au terminal…',
    'Sending the amount to the terminal…',
    'Enviando el monto a la terminal…',
    'Betrag wird an das Terminal gesendet …',
    'Stuur die bedrag na die terminaal…',
  );
  String get terminalPresentCard => _t(
    'Le client présente, insère ou glisse sa carte au terminal',
    'Customer taps, inserts or swipes on the terminal',
    'El cliente acerca, inserta o desliza su tarjeta en la terminal',
    'Kunde hält, steckt oder zieht die Karte am Terminal',
    'Klant tik, steek in of swiep op die terminaal',
  );
  String get terminalEnterPin => _t(
    'Le client entre son NIP au terminal',
    'Customer enters their PIN on the terminal',
    'El cliente escribe su PIN en la terminal',
    'Kunde gibt die PIN am Terminal ein',
    'Klant voer hul PIN op die terminaal in',
  );
  String get terminalChooseTip => _t(
    'Le client choisit le pourboire au terminal',
    'Customer is choosing a tip on the terminal',
    'El cliente elige la propina en la terminal',
    'Kunde wählt das Trinkgeld am Terminal',
    'Klant kies ’n fooitjie op die terminaal',
  );
  String get terminalProcessing => _t(
    'Traitement de la carte…',
    'Processing the card…',
    'Procesando…',
    'Karte wird verarbeitet …',
    'Verwerk die kaart…',
  );
  String get terminalWaitingForPhone => _t(
    'En attente du téléphone… touchez la carte sur le téléphone',
    'Waiting for the phone… tap on the phone',
    'Esperando el teléfono… acerca la tarjeta al teléfono',
    'Warten auf das Telefon … am Telefon bezahlen',
    'Wag vir die foon… tik op die foon',
  );
  String get terminalTapOnPhone => _t(
    'Le client touche sa carte sur le téléphone',
    'Customer taps their card on the phone',
    'El cliente acerca su tarjeta al teléfono',
    'Kunde hält die Karte ans Telefon',
    'Klant tik hul kaart op die foon',
  );
  String get followPhone => _t(
    'Suivez les étapes sur le téléphone',
    'Follow the steps on the phone',
    'Sigue los pasos en el teléfono',
    'Den Schritten auf dem Telefon folgen',
    'Volg die stappe op die foon',
  );
  String phonePairingCode(String code) => _t(
    'Code d’association du téléphone : $code',
    'Phone pairing code: $code',
    'Código para vincular el teléfono: $code',
    'Kopplungscode fürs Telefon: $code',
    'Foon se koppelkode: $code',
  );
  String get phonePairingHint => _t(
    'Ouvrez l’app « Card Reader » sur le téléphone et entrez ce code.',
    'Open the Card Reader app on the phone and enter this code.',
    'Abre la app Card Reader en el teléfono y escribe este código.',
    'Auf dem Telefon die App Card Reader öffnen und diesen Code eingeben.',
    'Maak die Card Reader-toep op die foon oop en voer hierdie kode in.',
  );
  String get unpairPhone => _t(
    'Dissocier le téléphone',
    'Unpair the phone',
    'Desvincular el teléfono',
    'Telefon entkoppeln',
    'Ontkoppel die foon',
  );
  String get phoneSimulatedNote => _t(
    'Lecteur Tap to Pay simulé par Stripe (cartes de test choisies sur le téléphone)',
    'Stripe’s simulated Tap to Pay reader (test cards picked on the phone)',
    'Lector Tap to Pay simulado de Stripe (tarjetas de prueba en el teléfono)',
    'Simuliertes Tap-to-Pay-Lesegerät von Stripe (Testkarten am Telefon wählen)',
    'Stripe se gesimuleerde Tap to Pay-leser (toetskaarte word op die foon gekies)',
  );
  String get terminalOfflineNow => _t(
    'Le terminal ne répond pas — vérifiez qu’il est allumé et sur le Wi-Fi',
    'The terminal isn’t answering — check it’s on and on the Wi-Fi',
    'La terminal no responde: revisa que esté encendida y en el Wi-Fi',
    'Das Terminal antwortet nicht – prüfen, ob es eingeschaltet und im WLAN ist',
    'Die terminaal antwoord nie — kontroleer dat dit aan is en op die Wi-Fi',
  );
  String get terminalApproved => _t(
    'Paiement approuvé',
    'Payment approved',
    'Pago aprobado',
    'Zahlung genehmigt',
    'Betaling goedgekeur',
  );
  String get terminalDeclined => _t(
    'Carte refusée',
    'Card declined',
    'Tarjeta rechazada',
    'Karte abgelehnt',
    'Kaart geweier',
  );
  String get terminalTimedOut => _t(
    'Délai dépassé — aucune carte présentée',
    'Timed out — no card presented',
    'Se agotó el tiempo: no se presentó ninguna tarjeta',
    'Zeitüberschreitung – keine Karte vorgelegt',
    'Tyd verstreke — geen kaart aangebied nie',
  );
  String get terminalCancelled => _t(
    'Paiement annulé',
    'Payment cancelled',
    'Pago cancelado',
    'Zahlung abgebrochen',
    'Betaling gekanselleer',
  );
  String get terminalFailed => _t(
    'Échec du paiement par carte',
    'Card payment failed',
    'Falló el pago con tarjeta',
    'Kartenzahlung fehlgeschlagen',
    'Kaartbetaling het misluk',
  );
  String get payAnotherWay => _t(
    'Payer autrement',
    'Pay another way',
    'Pagar de otra forma',
    'Anders bezahlen',
    'Betaal op ’n ander manier',
  );
  String get authCodeLabel => _t(
    'N° d’autorisation',
    'Auth code',
    'Cód. de autorización',
    'Autorisierungscode',
    'Magtigingskode',
  );
  String get processorRefLabel =>
      _t('Référence', 'Reference', 'Referencia', 'Referenz', 'Verwysing');
  String followTerminal(String? address) => address == null
      ? _t(
          'Suivez les étapes sur le terminal',
          'Follow the steps on the terminal',
          'Sigue los pasos en la terminal',
          'Den Schritten am Terminal folgen',
          'Volg die stappe op die terminaal',
        )
      : _t(
          'Suivez les étapes sur le terminal ($address)',
          'Follow the steps on the terminal ($address)',
          'Sigue los pasos en la terminal ($address)',
          'Den Schritten am Terminal folgen ($address)',
          'Volg die stappe op die terminaal ($address)',
        );
  String get playReaderHere => _t(
    'Jouer le lecteur de carte ici',
    'Play the card reader here',
    'Usar el lector de tarjetas aquí',
    'Kartenleser hier simulieren',
    'Speel die kaartleser hier',
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
    ),
    'processor_unavailable' => _t(
      'Le processeur de paiement est injoignable. Prenez le comptant ou le terminal du comptoir.',
      'The card processor can’t be reached. Take cash or use the counter terminal.',
      'No se puede conectar con el procesador de pagos. Cobra en efectivo o con la terminal del mostrador.',
      'Der Kartenabwickler ist nicht erreichbar. Bar kassieren oder das Terminal an der Theke nutzen.',
      'Die kaartverwerker kan nie bereik word nie. Neem kontant of gebruik die toonbankterminaal.',
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
    ),
    'phone_reader_error' => _t(
      'Le téléphone lecteur signale une erreur — regardez l’écran du téléphone',
      'The phone reader reports a problem — check the phone’s screen',
      'El teléfono lector tiene un problema: revisa su pantalla',
      'Das Telefon-Lesegerät meldet ein Problem – Anzeige am Telefon prüfen',
      'Die foonleser meld ’n probleem — kontroleer die foon se skerm',
    ),
    'stripe_not_configured' || 'terminal_not_configured' => _t(
      'Le terminal de carte n’est pas configuré — Réglages → Paiements',
      'Card terminal isn’t set up — Settings → Payments',
      'La terminal de tarjetas no está configurada: Ajustes → Pagos',
      'Kartenterminal ist nicht eingerichtet – Einstellungen → Zahlungen',
      'Kaartterminaal is nie opgestel nie — Instellings → Betalings',
    ),
    'terminal_not_paired' => _t(
      'Terminal non associé — associez-le dans Réglages',
      'Terminal not paired — pair it in Settings',
      'Terminal sin vincular: vincúlala en Ajustes',
      'Terminal nicht gekoppelt – in den Einstellungen koppeln',
      'Terminaal nie gekoppel nie — koppel dit in Instellings',
    ),
    'terminal_busy' => _t(
      'Le terminal est occupé par un autre paiement',
      'The terminal is busy with another payment',
      'La terminal está ocupada con otro pago',
      'Das Terminal ist mit einer anderen Zahlung beschäftigt',
      'Die terminaal is besig met ’n ander betaling',
    ),
    'terminal_off' => _t(
      'Aucun terminal de carte intégré dans cet établissement',
      'No integrated card terminal on this store',
      'Esta tienda no tiene terminal de tarjetas integrada',
      'Kein integriertes Kartenterminal in dieser Filiale',
      'Geen geïntegreerde kaartterminaal in hierdie winkel nie',
    ),
    'jpm_not_configured' => _t(
      'J.P. Morgan n’est pas configuré dans cet établissement',
      'J.P. Morgan is not set up on this store',
      'J.P. Morgan no está configurado en esta tienda',
      'J.P. Morgan ist in dieser Filiale nicht eingerichtet',
      'J.P. Morgan is nie in hierdie winkel opgestel nie',
    ),
    _ => _t(
      'Le terminal de carte est injoignable — payez comptant ou au terminal du comptoir',
      'The card terminal can’t be reached — take cash or the counter terminal',
      'No se puede conectar con la terminal: cobra en efectivo o con la terminal del mostrador',
      'Das Kartenterminal ist nicht erreichbar – bar kassieren oder das Terminal an der Theke nutzen',
      'Die kaartterminaal kan nie bereik word nie — neem kontant of gebruik die toonbankterminaal',
    ),
  };

  // the reader sheet (the built-in simulator played on this tablet)
  String get readerTitle => _t(
    'Lecteur de carte (simulateur)',
    'Card reader (simulator)',
    'Lector de tarjetas (simulador)',
    'Kartenleser (Simulator)',
    'Kaartleser (simulator)',
  );
  String get readerTestCard => _t(
    'Carte de test',
    'Test card',
    'Tarjeta de prueba',
    'Testkarte',
    'Toetskaart',
  );
  String get readerOutcome => _t(
    'Réponse de la banque',
    'Bank answer',
    'Respuesta del banco',
    'Antwort der Bank',
    'Bank se antwoord',
  );
  String get readerApprove =>
      _t('Approuver', 'Approve', 'Aprobar', 'Genehmigen', 'Keur goed');
  String get readerInsufficient => _t(
    'Refus — fonds insuffisants',
    'Decline — insufficient funds',
    'Rechazo: fondos insuficientes',
    'Ablehnen – Deckung unzureichend',
    'Weier — onvoldoende fondse',
  );
  String get readerDoNotHonour => _t(
    'Refus — ne pas honorer',
    'Decline — do not honour',
    'Rechazo: no aceptar',
    'Ablehnen – nicht ausführen',
    'Weier — moenie honoreer nie',
  );
  String get readerTimeout => _t(
    'Délai dépassé',
    'Time out',
    'Tiempo agotado',
    'Zeitüberschreitung',
    'Tyd verstreke',
  );
  String get readerCustomerCancels => _t(
    'Le client annule',
    'Customer cancels',
    'El cliente cancela',
    'Kunde bricht ab',
    'Klant kanselleer',
  );
  String get readerTap =>
      _t('Sans contact', 'Tap', 'Acercar', 'Kontaktlos', 'Tik');
  String get readerInsert => _t(
    'Insérer (NIP)',
    'Insert (PIN)',
    'Insertar (PIN)',
    'Stecken (PIN)',
    'Steek in (PIN)',
  );
  String get readerSwipe =>
      _t('Glisser', 'Swipe', 'Deslizar', 'Durchziehen', 'Swiep');
  String get readerTapInsertSwipe => _t(
    'Présentez, insérez ou glissez',
    'Tap, insert or swipe',
    'Acerca, inserta o desliza',
    'Karte vorhalten, stecken oder durchziehen',
    'Tik, steek in of swiep',
  );
  String get readerEnterPin => _t(
    'Entrez votre NIP',
    'Enter your PIN',
    'Escribe tu PIN',
    'Bitte PIN eingeben',
    'Voer u PIN in',
  );
  String get readerAddTip => _t(
    'Ajouter un pourboire',
    'Add a tip',
    'Agregar propina',
    'Trinkgeld hinzufügen',
    'Voeg ’n fooitjie by',
  );
  String get readerNoTip => _t(
    'Sans pourboire',
    'No tip',
    'Sin propina',
    'Kein Trinkgeld',
    'Geen fooitjie',
  );
  String get readerReady => _t(
    'Prêt pour le prochain paiement',
    'Ready for the next payment',
    'Listo para el siguiente pago',
    'Bereit für die nächste Zahlung',
    'Gereed vir die volgende betaling',
  );
  String get readerApprovedBig =>
      _t('APPROUVÉE', 'APPROVED', 'APROBADA', 'GENEHMIGT', 'GOEDGEKEUR');
  String get readerDeclinedBig =>
      _t('REFUSÉE', 'DECLINED', 'RECHAZADA', 'ABGELEHNT', 'GEWEIER');
  String get readerCancelledBig =>
      _t('ANNULÉE', 'CANCELLED', 'CANCELADA', 'ABGEBROCHEN', 'GEKANSELLEER');
  String get readerTimeoutBig => _t(
    'DÉLAI DÉPASSÉ',
    'TIMED OUT',
    'TIEMPO AGOTADO',
    'ZEIT ABGELAUFEN',
    'TYD VERSTREKE',
  );
  String get readerNoRealCards => _t(
    'Cartes de test seulement — aucune vraie carte',
    'Test cards only — no real card data',
    'Solo tarjetas de prueba: ningún dato real',
    'Nur Testkarten – keine echten Kartendaten',
    'Slegs toetskaarte — geen regte kaartdata nie',
  );

  // settings: the card terminal
  String get sectionCardTerminal => _t(
    'Terminal de carte',
    'Card terminal',
    'Terminal de tarjetas',
    'Kartenterminal',
    'Kaartterminaal',
  );
  String terminalKindName(String kind) => switch (kind) {
    'simulator' => _t(
      'Simulateur',
      'Simulator',
      'Simulador',
      'Simulator',
      'Simulator',
    ),
    'jpmorgan' => _t(
      'J.P. Morgan (bac à sable)',
      'J.P. Morgan (sandbox)',
      'J.P. Morgan (entorno de pruebas)',
      'J.P. Morgan (Sandbox)',
      'J.P. Morgan (toetsomgewing)',
    ),
    'stripe' => cardStripe,
    'tap_to_pay' => _t(
      'Téléphone (Tap to Pay)',
      'Phone (Tap to Pay)',
      'Teléfono (Tap to Pay)',
      'Telefon (Tap to Pay)',
      'Foon (Tap to Pay)',
    ),
    'off' => _t('Aucun', 'None', 'Ninguno', 'Keins', 'Geen'),
    _ => _t(
      'Terminal externe',
      'External terminal',
      'Terminal externa',
      'Externes Terminal',
      'Eksterne terminaal',
    ),
  };
  String terminalStateName(String? state) => switch (state) {
    'idle' => _t('Prêt', 'Ready', 'Lista', 'Bereit', 'Gereed'),
    'busy' => _t('Occupé', 'Busy', 'Ocupada', 'Belegt', 'Besig'),
    'not_paired' => _t(
      'Non associé',
      'Not paired',
      'Sin vincular',
      'Nicht gekoppelt',
      'Nie gekoppel nie',
    ),
    'offline' => _t(
      'Injoignable',
      'Unreachable',
      'Sin conexión',
      'Nicht erreichbar',
      'Onbereikbaar',
    ),
    _ => _t(
      'Sur la tablette',
      'On the tablet',
      'En la tableta',
      'Auf dem Tablet',
      'Op die tablet',
    ),
  };
  String get terminalBuiltIn => _t(
    'Intégré à la caisse (page /terminal ou lecteur sur la tablette)',
    'Built into the POS (the /terminal page, or the reader on the tablet)',
    'Integrada en la caja (la página /terminal o el lector en la tableta)',
    'In die Kasse integriert (die Seite /terminal oder das Lesegerät am Tablet)',
    'Ingebou in die kasregister (die /terminal-bladsy, of die leser op die tablet)',
  );
  String get terminalPageQrLabel => _t(
    'Lecteur de carte de démonstration : ouvrez cette page sur un téléphone pour jouer le lecteur',
    'Practice card reader: open this page on a phone to play the card reader',
    'Lector de tarjetas de práctica: abre esta página en un teléfono para hacer de lector',
    'Übungs-Kartenleser: diese Seite auf einem Handy öffnen, um den Kartenleser zu spielen',
    'Oefen-kaartleser: maak hierdie bladsy op ’n foon oop om die kaartleser te speel',
  );
  String get pairTerminal => _t(
    'Associer un terminal',
    'Pair a terminal',
    'Vincular una terminal',
    'Terminal koppeln',
    'Koppel ’n terminaal',
  );
  String get terminalAddressLabel => _t(
    'Adresse IP du terminal (ex. 192.168.1.50:8090)',
    'Terminal IP address (e.g. 192.168.1.50:8090)',
    'Dirección IP de la terminal (p. ej. 192.168.1.50:8090)',
    'IP-Adresse des Terminals (z. B. 192.168.1.50:8090)',
    'Terminaal se IP-adres (bv. 192.168.1.50:8090)',
  );
  String get terminalCodeLabel => _t(
    'Code affiché sur le terminal',
    'Code shown on the terminal',
    'Código que muestra la terminal',
    'Am Terminal angezeigter Code',
    'Kode wat op die terminaal gewys word',
  );
  String get useBuiltInTerminal => _t(
    'Utiliser le terminal intégré',
    'Use the built-in terminal',
    'Usar la terminal integrada',
    'Integriertes Terminal verwenden',
    'Gebruik die ingeboude terminaal',
  );
  String get terminalPaired => _t(
    'Terminal associé',
    'Terminal paired',
    'Terminal vinculada',
    'Terminal gekoppelt',
    'Terminaal gekoppel',
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
            prefs.lang.toUpperCase(),
            style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
          ),
        ),
      ],
    );
  }
}

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
            prefs.lang.toUpperCase(),
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

/// A data name in [lang]: the two catalog slots for fr / en; for any other
/// language its translation in [extra], else English, else French.
String pickName(
  String lang,
  String fr,
  String en, [
  Map<String, String>? extra,
]) {
  if (lang == 'fr') return fr;
  if (lang == 'en') return en;
  final t = extra?[lang];
  if (t != null && t.trim().isNotEmpty) return t;
  return en.isNotEmpty ? en : fr;
}
