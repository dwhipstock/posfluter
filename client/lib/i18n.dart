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
      'en'; // one of the store's languages: en | fr (pubs), en | es (US store)

  /// Every language the terminal has strings for; a store offers a subset.
  static const known = {'en', 'fr', 'es'};

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

  /// Date-only, dd/MM/yyyy.
  String fmtDate(DateTime d) {
    String p2(int n) => n.toString().padLeft(2, '0');
    return '${p2(d.day)}/${p2(d.month)}/${d.year}';
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
/// Every string is written three times: Québec French (the pubs; vous, and
/// French typography — a no-break space before : ; ! ? and inside « »),
/// English, and US Spanish (the Sage & Poppy counter; tú).
/// test/locale_coverage_test.dart checks that no `_t` call misses a language.
class L {
  /// en | fr | es
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

  /// French, English and US Spanish, in that order.
  String _t(String fr, String enS, String esS) =>
      lang == 'fr' ? fr : (lang == 'es' ? esS : enS);

  /// Data-driven names (items, zones, variants): user's language first.
  /// The catalog carries French and English; Spanish reads the English.
  String name(String fr, String enS) => lang == 'fr' ? fr : enS;

  /// The other language, shown as the small secondary line on menu tiles
  /// (none in Spanish: the catalog has no Spanish side).
  String nameAlt(String fr, String enS) {
    if (lang == 'es') return '';
    return lang == 'fr' ? enS : fr;
  }

  /// The store's currency code, for "($cur)" / "(USD)" field labels.
  String get cur => StoreProfile.current.currency;

  // common
  String get retry => _t('Réessayer', 'Retry', 'Reintentar');
  String get cancel => _t('Annuler', 'Cancel', 'Cancelar');
  String get ok => _t('OK', 'OK', 'OK');
  String get done => _t('Terminé', 'Done', 'Listo');
  String get close => _t('Fermer', 'Close', 'Cerrar');
  String get cannotReachServer => _t(
    'Restaurant temporairement injoignable',
    'Restaurant temporarily unavailable',
    'La tienda no está disponible por el momento',
  );

  // login
  String get enterPin => _t(
    'Entrez votre NIP pour vous connecter',
    'Enter your PIN to sign in',
    'Ingresa tu PIN para iniciar sesión',
  );
  String get loginInterrupted => _t(
    'Connexion interrompue — entrez votre NIP de nouveau',
    'Connection interrupted — enter your PIN again',
    'Se interrumpió la conexión: ingresa tu PIN otra vez',
  );
  String get whoClockingIn => _t(
    'Qui commence son quart ?',
    "Who's clocking in?",
    '¿Quién empieza su turno?',
  );
  String get switchLanguage =>
      _t('Changer de langue', 'Switch language', 'Cambiar idioma');
  String get staffTerminal =>
      _t('Terminal du personnel', 'Staff terminal', 'Terminal del personal');

  // floor header: short labels under the action icons
  String get navMenu => _t('Menu', 'Menu', 'Menú');
  String get navReports => _t('Rapports', 'Reports', 'Reportes');
  String get navRefunds => _t('Remboursements', 'Refunds', 'Reembolsos');
  String get navLayout => _t('Plan de salle', 'Layout', 'Plano');
  String get navRefresh => _t('Actualiser', 'Refresh', 'Actualizar');
  String get navMore => _t('Plus', 'More', 'Más');

  // floor legend + room list
  String get rooms => _t('Salles', 'Rooms', 'Salones');
  String get legendFree => _t('Libre', 'Free', 'Libre');
  String get legendOccupied => _t('Occupée', 'Occupied', 'Ocupada');
  String get legendPending =>
      _t('Commande en attente', 'Order waiting', 'Pedido en espera');
  String tablesOccupied(int open, int total) => _t(
    '$open sur $total occupées',
    '$open of $total occupied',
    '$open de $total ocupadas',
  );

  /// How long a check has been open, e.g. "25 min", "1 h 05".
  String openFor(Duration d) {
    if (d.inMinutes < 1) return _t('à l’instant', 'just now', 'ahora mismo');
    if (d.inHours < 1) return '${d.inMinutes} min';
    final m = (d.inMinutes % 60).toString().padLeft(2, '0');
    return '${d.inHours} h $m';
  }

  // check screen cart
  String itemCount(int n) {
    if (n == 1) return _t('1 article', '1 item', '1 artículo');
    if (n == 0) return _t('0 article', '0 items', '0 artículos');
    return _t('$n articles', '$n items', '$n artículos');
  }

  String get emptyBill => _t(
    'Rien sur cette addition pour l’instant',
    'Nothing on this bill yet',
    'Todavía no hay nada en esta cuenta',
  );
  String get each => _t('l’unité', 'each', 'c/u');
  String get tapToAdd => _t(
    'Touchez un article du menu pour l’ajouter.',
    'Tap a menu item to add it.',
    'Toca un artículo del menú para agregarlo.',
  );

  // zones
  String get zones => _t('Zones', 'Zones', 'Zonas');
  String get manageMenu =>
      _t('Gérer le menu (86)', 'Manage menu (86)', 'Administrar menú (86)');
  String get shiftReports =>
      _t('Quart / Rapports', 'Shift / Reports', 'Turno / Reportes');
  String get logout => _t('Se déconnecter', 'Log out', 'Cerrar sesión');

  // tables
  String get subTable => _t('Sous-table', 'Sub-table', 'Submesa');
  String get free => _t('Libre', 'Free', 'Libre');

  // floor plan
  String seatsShort(int n) {
    if (n == 1) return _t('1 place', '1 seat', '1 lugar');
    if (n == 0) return _t('0 place', '0 seats', '0 lugares');
    return _t('$n places', '$n seats', '$n lugares');
  }

  String get emptyZoneOnboarding => _t(
    'Aucune table dans cette zone pour l’instant.\nTouchez le crayon pour dessiner le plan de salle.',
    'No tables in this zone yet.\nTap the pencil to build the layout.',
    'Todavía no hay mesas en esta zona.\nToca el lápiz para armar el plano.',
  );

  // floor plan editor (manager)
  String get editLayout =>
      _t('Modifier le plan de salle', 'Edit layout', 'Editar plano');
  String editLayoutTitle(String zone) => _t(
    'Modifier le plan de salle · $zone',
    'Edit layout · $zone',
    'Editar plano · $zone',
  );
  String get saveLayout =>
      _t('Enregistrer le plan', 'Save layout', 'Guardar plano');
  String get layoutSaved =>
      _t('Plan de salle enregistré', 'Layout saved', 'Plano guardado');
  String get addTable => _t('Ajouter une table', 'Add table', 'Agregar mesa');
  String get deleteTable =>
      _t('Retirer la table', 'Remove table', 'Quitar mesa');
  String deleteTableConfirm(String label) => _t(
    'Retirer la table « $label » du plan ? Les anciennes additions la conservent, mais elle disparaît de tous les écrans.',
    'Remove table "$label" from the plan? Old bills keep it; it disappears from every screen.',
    '¿Quitar la mesa "$label" del plano? Las cuentas anteriores la conservan, pero desaparece de todas las pantallas.',
  );
  String get tableDeleted =>
      _t('Table retirée', 'Table removed', 'Mesa quitada');
  String get renameTable =>
      _t('Renommer la table', 'Rename table', 'Cambiar nombre de la mesa');
  String get tableLabelField => _t(
    'Nom de la table (ex. : L-5)',
    'Table label (e.g. L-5)',
    'Nombre de la mesa (p. ej., L-5)',
  );

  /// Add-table dialog helper: labels are auto-assigned to the zone prefix.
  String autoLabelHint(String prefix) => _t(
    'Laissez vide pour le numéro suivant ($prefix-…)',
    'Leave blank for the next number ($prefix-…)',
    'Déjalo vacío para el siguiente número ($prefix-…)',
  );
  String get vipNameField => _t(
    'Nom VIP (vide = aucun)',
    'VIP name (blank = none)',
    'Nombre VIP (vacío = ninguno)',
  );
  String get rotate => _t('Pivoter', 'Rotate', 'Girar');
  String get undo => _t('Annuler', 'Undo', 'Deshacer');
  String get editorGrid => _t('Grille', 'Grid', 'Cuadrícula');
  String get editorSnap => _t('Magnétisme', 'Snap', 'Ajustar');
  String get gridOff => _t('Désactivée', 'Off', 'Desactivada');
  String get gridLarge => _t('Grande', 'Large', 'Grande');
  String get gridMedium => _t('Moyenne', 'Medium', 'Mediana');
  String get gridSmall => _t('Petite', 'Small', 'Pequeña');
  String get seatsLabel => _t('Places', 'Seats', 'Lugares');
  String get unsavedLayoutTitle =>
      _t('Plan non enregistré', 'Layout not saved', 'Plano sin guardar');
  String get unsavedLayoutBody => _t(
    'Les tables déplacées reprendront leur place si vous quittez maintenant.',
    'Table positions you moved will be lost if you leave now.',
    'Si sales ahora, se perderán las posiciones de las mesas que moviste.',
  );
  String get discard => _t('Quitter sans enregistrer', 'Discard', 'Descartar');
  String get tapTableToEditHint => _t(
    'Touchez une table pour la sélectionner · glissez pour la déplacer',
    'Tap a table to select · drag to move',
    'Toca una mesa para seleccionarla · arrastra para moverla',
  );

  // floor plan editor — structural objects (pool / bar front / pillar)
  String get addObject =>
      _t('Ajouter un élément', 'Add object', 'Agregar elemento');
  String get objectPool =>
      _t('Table de billard', 'Pool table', 'Mesa de billar');
  String get objectBarFront => _t('Comptoir du bar', 'Bar front', 'Barra');
  String get objectPillar => _t('Colonne', 'Pillar', 'Columna');
  // the short caption on an unlabelled pool table / bar front on the plan
  String get objectPoolCaption => _t('Billard', 'Pool', 'Billar');
  String get objectBarCaption => _t('Bar', 'Bar', 'Barra');
  String get deleteObject =>
      _t('Retirer l’élément', 'Remove object', 'Quitar elemento');
  String deleteObjectConfirm(String name) => _t(
    'Retirer « $name » du plan ?',
    'Remove "$name" from the plan?',
    '¿Quitar "$name" del plano?',
  );
  String get objectDeleted =>
      _t('Élément retiré', 'Object removed', 'Elemento quitado');

  // check screen
  String get table => _t('Table', 'Table', 'Mesa');
  String get bill => _t('Addition', 'Bill', 'Cuenta');

  /// A document number: "n° 12" in French, "#12" in English and US Spanish.
  String numbered(int n) => _t('n°\u00A0$n', '#$n', '#$n');

  /// "Addition n° 12" / "Bill #12" / "Cuenta #12".
  String billNo(int id) => '$bill ${numbered(id)}';
  String get corkage => _t('Droit de bouchon', 'Corkage', 'Descorche');
  String get voidBillManager => _t(
    'Annuler l’addition (gérant)',
    'Void bill (manager)',
    'Anular cuenta (gerente)',
  );
  String get noItemsYet => _t(
    'Aucun article pour l’instant — touchez le menu pour en ajouter',
    'No items yet — tap the menu to add',
    'Todavía no hay artículos: toca el menú para agregar',
  );
  String pendingFromPhone(int n) => _t(
    'Commandé par téléphone — à confirmer ($n)',
    'Ordered from phone — awaiting confirm ($n)',
    'Pedido desde el celular: por confirmar ($n)',
  );
  String get acceptOrder => _t('Accepter', 'Accept', 'Aceptar');
  String get rejectOrder => _t('Refuser', 'Reject', 'Rechazar');
  String get deleteLine => _t('Retirer', 'Remove', 'Quitar');
  String get emptyBillClosed => _t(
    'Addition vide fermée',
    'Empty bill closed',
    'Se cerró la cuenta vacía',
  );
  String get total => _t('Total', 'Total', 'Total');
  String get subtotal => _t('Sous-total', 'Subtotal', 'Subtotal');

  /// A tax added on top, e.g. "GST 5%" / "TPS 5 %" (rate is a decimal string).
  /// Data, not a string table: the tax names come from the store.
  String taxLine(TaxLine tax) => lang == 'fr'
      ? '${tax.labelFr} ${tax.ratePercent.replaceAll('.', ',')} %'
      : '${tax.labelEn} ${tax.ratePercent}%';
  String get pay => _t('Payer', 'Pay', 'Cobrar');
  String get ordersAwaiting => _t(
    'Commandes à confirmer',
    'Orders awaiting confirm',
    'Pedidos por confirmar',
  );
  String sizesFrom(int n, String price) => _t(
    '$n formats, dès $price',
    '$n sizes $price+',
    '$n tamaños desde $price',
  );
  String get qty => _t('Qté', 'Qty', 'Cant.');
  String get noteHint => _t(
    'Note (ex. : sans glace)',
    'Note (e.g. no ice)',
    'Nota (p. ej., sin hielo)',
  );
  String addToBill(String price) => _t(
    'Ajouter à l’addition · $price',
    'Add to bill $price',
    'Agregar a la cuenta · $price',
  );

  // void flow
  String get voidApprovalTitle => _t(
    'Annuler l’addition — approbation du gérant',
    'Void bill — manager approval',
    'Anular cuenta: aprobación del gerente',
  );
  String get voidReasonTitle =>
      _t('Motif de l’annulation', 'Void reason', 'Motivo de la anulación');
  List<String> get voidReasons => [
    _t('Mauvaise table', 'Wrong table', 'Mesa equivocada'),
    _t(
      'Le client a changé d’idée',
      'Customer changed mind',
      'El cliente cambió de opinión',
    ),
    _t('Mauvais prix', 'Wrong price', 'Precio equivocado'),
    _t('Test du système', 'System test', 'Prueba del sistema'),
  ];
  String get otherReason => _t('Autre motif', 'Other reason', 'Otro motivo');
  String voidedBill(int id) =>
      _t('Addition n° $id annulée', 'Bill #$id voided', 'Cuenta #$id anulada');

  // corkage dialog
  String get corkageTitle => _t('Droit de bouchon', 'Corkage', 'Descorche');
  String get bottlesBrought => _t(
    'Bouteilles apportées par le client',
    'Bottles brought by customer',
    'Botellas que trajo el cliente',
  );

  // tender screen
  String get outstanding => _t('À payer', 'Due', 'Por pagar');
  String paidOf(String paid, String total) => _t(
    'Payé $paid sur $total',
    'Paid $paid / $total',
    'Pagado $paid de $total',
  );
  String get cash => _t('Comptant', 'Cash', 'Efectivo');
  String get card => _t('Carte', 'Card', 'Tarjeta');
  String get bankTransfer => _t('Virement', 'Transfer', 'Transferencia');
  String get cashInHint => _t(
    'Comptant reçu ($cur) — paiement partiel accepté',
    'Cash received ($cur) — partial OK',
    'Efectivo recibido ($cur): se acepta pago parcial',
  );
  String get receive => _t('Encaisser', 'Receive', 'Cobrar');
  String amountHint(String due) => _t(
    'Montant ($cur) — vide = la totalité, $due',
    'Amount ($cur) — blank = full $due',
    'Monto ($cur): vacío = el total, $due',
  );
  String get useCardTerminal => _t(
    'Utiliser le terminal de paiement',
    'Use card terminal',
    'Usar la terminal de tarjetas',
  );
  String get showBankAccount => _t(
    'Afficher les coordonnées bancaires',
    'Show account details',
    'Mostrar datos de la cuenta',
  );
  String amountToPay(String amount) => _t(
    'Montant à payer : $amount',
    'Amount due $amount',
    'Monto a pagar: $amount',
  );
  String get confirmMoneyIn => _t(
    'Confirmer — paiement reçu',
    'Confirm — money received',
    'Confirmar: dinero recibido',
  );
  String get cashReceivedTitle =>
      _t('Comptant reçu ✓', 'Cash received ✓', 'Efectivo recibido ✓');
  String get cashReceived =>
      _t('Comptant reçu', 'Cash received', 'Efectivo recibido');
  String get rounding => _t('Arrondi', 'Rounding', 'Redondeo');
  String get cashTotal =>
      _t('Total comptant', 'Cash total', 'Total en efectivo');
  String get change => _t('Monnaie', 'Change', 'Cambio');
  String receivedToast(String amount, String due) => _t(
    '$amount reçu — reste $due à payer',
    'Received $amount — $due outstanding',
    'Recibido $amount: faltan $due',
  );

  // Card (Stripe) — optional tender, test mode, simulated reader
  String get cardStripe =>
      _t('Carte (Stripe)', 'Card (Stripe)', 'Tarjeta (Stripe)');
  String get chargeCardStripe => _t(
    'Payer par carte (Stripe)',
    'Charge card (Stripe)',
    'Cobrar con tarjeta (Stripe)',
  );
  String get simulatedCardLabel => _t(
    'Carte simulée (mode test)',
    'Simulated card (test mode)',
    'Tarjeta simulada (modo de prueba)',
  );
  String get simApproved => _t('Approuvée', 'Approved', 'Aprobada');
  String get simDeclined => _t('Refusée', 'Declined', 'Rechazada');
  String get simInsufficient =>
      _t('Fonds insuffisants', 'Insufficient funds', 'Fondos insuficientes');
  String get stripeTitle => _t(
    'Paiement par carte (Stripe)',
    'Card payment (Stripe)',
    'Pago con tarjeta (Stripe)',
  );
  String get stripeTestMode => _t(
    'MODE TEST — lecteur simulé, aucun argent réel',
    'TEST MODE — simulated reader, no real money',
    'MODO DE PRUEBA: lector simulado, sin dinero real',
  );
  String get stripePreparing => _t(
    'Préparation du paiement…',
    'Preparing the payment…',
    'Preparando el pago…',
  );
  String get stripePermissions => _t(
    'Vérification des autorisations Bluetooth et de localisation…',
    'Checking Bluetooth and location permission…',
    'Revisando los permisos de Bluetooth y ubicación…',
  );
  String get stripeConnecting => _t(
    'Connexion au lecteur de carte (simulé)…',
    'Connecting to the card reader (simulated)…',
    'Conectando con el lector de tarjetas (simulado)…',
  );
  String get stripeTapCard => _t(
    'Présentez, insérez ou glissez la carte (simulé)',
    'Tap, insert or swipe the card (simulated)',
    'Acerca, inserta o desliza la tarjeta (simulado)',
  );
  String get stripeProcessing =>
      _t('Traitement du paiement…', 'Processing…', 'Procesando…');
  String get stripeApproved =>
      _t('Paiement approuvé', 'Payment approved', 'Pago aprobado');
  String get stripeDeclinedTitle =>
      _t('Carte refusée', 'Card declined', 'Tarjeta rechazada');
  String get stripeErrorTitle => _t(
    'Échec du paiement par carte',
    'Card payment failed',
    'Falló el pago con tarjeta',
  );
  String get nothingCharged => _t(
    'Rien n’a été débité. L’addition peut être réglée autrement.',
    'Nothing was charged. The bill can be paid another way.',
    'No se cobró nada. La cuenta se puede pagar de otra forma.',
  );

  /// Why "Card (Stripe)" is greyed out (hint under the tender tiles).
  String stripeUnavailableHint(String? reason) => switch (reason) {
    'stripe_unavailable' => _t(
      'Pas d’Internet — la carte (Stripe) est indisponible',
      'No internet — Card (Stripe) is unavailable',
      'Sin internet: la tarjeta (Stripe) no está disponible',
    ),
    'stripe_live_key_refused' => _t(
      'Stripe désactivé : seules les clés de test (sk_test_) sont acceptées',
      'Stripe is off: only test keys (sk_test_) are accepted',
      'Stripe está desactivado: solo se aceptan claves de prueba (sk_test_)',
    ),
    'stripe_permission_denied' => _t(
      'Autorisation Bluetooth ou localisation refusée — carte (Stripe) désactivée',
      'Bluetooth/location permission denied — Card (Stripe) disabled',
      'Se negó el permiso de Bluetooth o ubicación: tarjeta (Stripe) desactivada',
    ),
    'stripe_unsupported' => _t(
      'La carte (Stripe) fonctionne seulement sur la tablette Android',
      'Card (Stripe) works on the Android tablet only',
      'La tarjeta (Stripe) solo funciona en la tableta Android',
    ),
    'stripe_location_required' => _t(
      'Stripe : aucun emplacement Terminal (définissez STRIPE_LOCATION_ID)',
      'Stripe: no Terminal location (set STRIPE_LOCATION_ID)',
      'Stripe: no hay ubicación de Terminal (configura STRIPE_LOCATION_ID)',
    ),
    'stripe_currency_mismatch' => _t(
      'Stripe désactivé : le compte Stripe n’est pas en CAD',
      'Stripe is off: the Stripe account is not in CAD',
      'Stripe está desactivado: la cuenta de Stripe no está en CAD',
    ),
    _ => _t(
      'La carte (Stripe) est indisponible pour le moment',
      'Card (Stripe) is unavailable right now',
      'La tarjeta (Stripe) no está disponible por ahora',
    ),
  };

  /// Friendly text for a Stripe decline code.
  String stripeDeclineMessage(String? code) => switch (code) {
    'insufficient_funds' => _t(
      'Fonds insuffisants. Essayez une autre carte ou un autre mode de paiement.',
      'Insufficient funds. Try another card or another way to pay.',
      'Fondos insuficientes. Prueba con otra tarjeta u otra forma de pago.',
    ),
    'expired_card' => _t(
      'Carte expirée. Essayez une autre carte.',
      'The card has expired. Try another card.',
      'La tarjeta está vencida. Prueba con otra tarjeta.',
    ),
    'incorrect_pin' || 'incorrect_cvc' || 'invalid_pin' => _t(
      'NIP incorrect. Réessayez ou utilisez une autre carte.',
      'Incorrect PIN. Try again or use another card.',
      'PIN incorrecto. Inténtalo de nuevo o usa otra tarjeta.',
    ),
    'pin_try_exceeded' => _t(
      'Trop d’essais de NIP. Utilisez une autre carte.',
      'Too many PIN attempts. Use another card.',
      'Demasiados intentos de PIN. Usa otra tarjeta.',
    ),
    _ => _t(
      'La carte a été refusée. Essayez une autre carte ou un autre mode de paiement.',
      'The card was declined. Try another card or another way to pay.',
      'La tarjeta fue rechazada. Prueba con otra tarjeta u otra forma de pago.',
    ),
  };

  /// Friendly text for a reader-side failure kind (see CardReaderError).
  String stripeReaderError(String kind) => switch (kind) {
    'offline' => _t(
      'La tablette ne joint pas Stripe (réseau).',
      'The tablet can\'t reach Stripe (network).',
      'La tableta no puede conectarse con Stripe (red).',
    ),
    'tokenFailed' => _t(
      'Le magasin n’a pas pu obtenir de jeton de connexion Stripe.',
      'The store couldn\'t get a Stripe connection token.',
      'La tienda no pudo obtener un token de conexión de Stripe.',
    ),
    'stripeApi' => _t(
      'Stripe a refusé la demande du lecteur.',
      'Stripe refused the reader request.',
      'Stripe rechazó la solicitud del lector.',
    ),
    'permissionDenied' => _t(
      'L’autorisation « Position » ou « Appareils à proximité » a été refusée à l’application.',
      'The app\'s Location or Nearby devices permission was denied.',
      'Se negó el permiso de Ubicación o Dispositivos cercanos de la app.',
    ),
    'locationOff' => _t(
      'La localisation de l’appareil est désactivée. Activez-la, puis réessayez.',
      'Device Location is off. Turn it on, then try again.',
      'La ubicación del dispositivo está desactivada. Actívala e inténtalo de nuevo.',
    ),
    'bluetoothOff' => _t(
      'Le Bluetooth est désactivé. Activez-le, puis réessayez.',
      'Bluetooth is off. Turn it on, then try again.',
      'El Bluetooth está desactivado. Actívalo e inténtalo de nuevo.',
    ),
    'unsupported' => _t(
      'La carte (Stripe) fonctionne seulement sur la tablette Android.',
      'Card (Stripe) works on the Android tablet only.',
      'La tarjeta (Stripe) solo funciona en la tableta Android.',
    ),
    _ => _t(
      'Le lecteur de carte (simulé) a échoué.',
      'The card reader (simulated) failed.',
      'Falló el lector de tarjetas (simulado).',
    ),
  };
  String get openLocationSettings => _t(
    'Ouvrir les paramètres de localisation',
    'Open Location settings',
    'Abrir la configuración de ubicación',
  );
  String get openBluetoothSettings => _t(
    'Ouvrir les paramètres Bluetooth',
    'Open Bluetooth settings',
    'Abrir la configuración de Bluetooth',
  );
  String get openAppSettings => _t(
    'Ouvrir les autorisations de l’application',
    'Open app permissions',
    'Abrir los permisos de la app',
  );
  String errorCode(String code) =>
      _t('Code : $code', 'Code: $code', 'Código: $code');

  // table ops: move / merge
  String get moveMerge =>
      _t('Déplacer / fusionner', 'Move / merge', 'Mover / combinar');
  String moveMergeTitle(int billId) => _t(
    'Déplacer l’addition n° $billId vers…',
    'Move Bill #$billId to…',
    'Mover la cuenta #$billId a…',
  );
  String get thisBill => _t('Cette addition', 'This bill', 'Esta cuenta');
  String get beingPaid =>
      _t('Paiement en cours', 'Being paid', 'Pago en curso');
  String get mergeConfirmTitle =>
      _t('Fusionner les additions', 'Merge bills', 'Combinar cuentas');
  String mergeConfirmBody(int src, int dest, String destLabel) => _t(
    'Fusionner l’addition n° $src avec l’addition n° $dest (table $destLabel) ? Tous les articles passent sur l’addition de destination.',
    'Merge Bill #$src into Bill #$dest (table $destLabel)? All items move to the destination bill.',
    '¿Combinar la cuenta #$src con la cuenta #$dest (mesa $destLabel)? Todos los artículos pasan a la cuenta de destino.',
  );
  String get merge => _t('Fusionner', 'Merge', 'Combinar');
  String movedToast(String label) => _t(
    'Déplacée à la table $label',
    'Moved to table $label',
    'Se movió a la mesa $label',
  );
  String mergedToast(int dest) => _t(
    'Fusionnée avec l’addition n° $dest',
    'Merged into Bill #$dest',
    'Se combinó con la cuenta #$dest',
  );

  // open / misc item
  String get openItem => _t('Article libre', 'Open item', 'Artículo libre');
  String get openItemName =>
      _t('Nom de l’article', 'Item name', 'Nombre del artículo');
  String get openItemPrice =>
      _t('Prix ($cur)', 'Price ($cur)', 'Precio ($cur)');

  // split checks (settlement-time bill groups)
  String get splitBill =>
      _t('Séparer l’addition', 'Split bill', 'Dividir la cuenta');
  String get splitByItems => _t('Par article', 'By item', 'Por artículo');
  String get splitEvenly =>
      _t('Parts égales', 'Split evenly', 'Partes iguales');
  String get splitHowManyWays => _t(
    'En combien de parts ?',
    'Split how many ways?',
    '¿En cuántas partes?',
  );
  String groupTitle(int n) => _t('Addition $n', 'Bill $n', 'Cuenta $n');
  String get addGroup =>
      _t('Ajouter une addition', 'Add bill', 'Agregar cuenta');
  String get unassignedItems =>
      _t('Non attribués', 'Unassigned', 'Sin asignar');
  String get allItemsAssigned => _t(
    'Tous les articles sont attribués',
    'All items assigned',
    'Todos los artículos están asignados',
  );
  String get tapToAssignHint => _t(
    'Touchez un article pour le déplacer vers l’addition sélectionnée',
    'Tap an item to move it to the selected bill',
    'Toca un artículo para pasarlo a la cuenta seleccionada',
  );
  String get paid => _t('Payée ✓', 'Paid ✓', 'Pagada ✓');
  String get clearSplit =>
      _t('Annuler la séparation', 'Clear split', 'Deshacer la división');
  String get clearSplitConfirm => _t(
    'Tout regrouper sur une seule addition ?',
    'Merge everything back into one bill?',
    '¿Juntar todo otra vez en una sola cuenta?',
  );
  String groupPaidToast(int n) =>
      _t('Addition $n payée', 'Bill $n paid', 'Cuenta $n pagada');
  String get moveCorkageTitle => _t(
    'Déplacer le droit de bouchon vers…',
    'Move corkage to…',
    'Mover el descorche a…',
  );
  String get deleteGroup =>
      _t('Retirer cette addition', 'Remove this bill', 'Quitar esta cuenta');
  String get peelOne => _t('Un seul', '1 only', 'Solo 1');

  // receipt
  String get receipt => _t('Reçu', 'Receipt', 'Recibo');

  // provisional bill ("check please")
  String get printBill =>
      _t('Imprimer l’addition', 'Print bill', 'Imprimir la cuenta');
  String get customerBill =>
      _t('Addition du client', 'Customer bill', 'Cuenta del cliente');
  String get notAReceipt =>
      _t('Ceci n’est pas un reçu', 'Not a receipt', 'No es un recibo');
  String get printedAt => _t('Imprimée à', 'Printed at', 'Impresa a las');
  String get printAgain =>
      _t('Imprimer de nouveau', 'Print again', 'Volver a imprimir');

  // shift screen
  String get noShiftOpen =>
      _t('Aucun quart ouvert', 'No shift open', 'No hay turno abierto');
  String get openingFloat => _t(
    'Fonds de caisse ($cur)',
    'Opening float ($cur)',
    'Fondo inicial ($cur)',
  );
  String get openShift => _t('Ouvrir le quart', 'Open shift', 'Abrir turno');
  String get openShiftApproval => _t(
    'Ouvrir le quart — approbation du gérant',
    'Open shift — manager approval',
    'Abrir turno: aprobación del gerente',
  );
  String get closeShiftApproval => _t(
    'Fermer le quart (Z) — approbation du gérant',
    'Close shift (Z) — manager approval',
    'Cerrar turno (Z): aprobación del gerente',
  );
  String shiftOpenTitle(int id) =>
      _t('Quart n° $id — ouvert', 'Shift #$id — open', 'Turno #$id: abierto');
  String shiftOpenedLine(String at, String by, String float) => _t(
    'Ouvert le $at par $by • fonds de caisse $float',
    'Opened $at by $by • float $float',
    'Abierto el $at por $by • fondo $float',
  );
  String get closeShiftZ => _t(
    'Fermer le quart (rapport Z)',
    'Close shift (Z-Report)',
    'Cerrar turno (reporte Z)',
  );
  String get countedCash => _t(
    'Comptant compté ($cur)',
    'Counted cash ($cur)',
    'Efectivo contado ($cur)',
  );
  String get closeShift => _t('Fermer le quart', 'Close shift', 'Cerrar turno');
  String get zReportDone => _t(
    'Quart fermé — rapport Z',
    'Shift closed — Z-Report',
    'Turno cerrado: reporte Z',
  );
  String get revenue => _t('Ventes', 'Revenue', 'Ventas');
  String get billCount => _t('Additions', 'Bills', 'Cuentas');
  String get avgPerBill =>
      _t('Moyenne par addition', 'Avg per bill', 'Promedio por cuenta');
  String get byTender =>
      _t('Par mode de paiement', 'By tender', 'Por forma de pago');
  String get topItems => _t('Meilleurs vendeurs', 'Top items', 'Más vendidos');
  String get voidedBills =>
      _t('Additions annulées', 'Voided bills', 'Cuentas anuladas');
  String get expectedCash =>
      _t('Comptant attendu', 'Expected cash', 'Efectivo esperado');
  String get countedActual => _t('Compté', 'Counted', 'Contado');
  String get overShort =>
      _t('Surplus / manque', 'Over/short', 'Sobrante / faltante');
  String get xReport => _t('Rapport X', 'X-Report', 'Reporte X');
  // shift reconciliation: cash movements + refunds
  String get paidInOut => _t(
    'Entrées / sorties de caisse',
    'Paid in / out',
    'Entradas / salidas de caja',
  );
  String get cashRefunds => _t(
    'Remboursements en comptant',
    'Cash refunds',
    'Reembolsos en efectivo',
  );
  String get cashRounding =>
      _t('Arrondi du comptant', 'Cash rounding', 'Redondeo del efectivo');

  // refunds — return money on a finalized (CLOSED) bill
  String get refunds => _t('Remboursements', 'Refunds', 'Reembolsos');
  String get salesTitle =>
      _t('Additions fermées', 'Closed bills', 'Cuentas cerradas');
  String get refundTitle =>
      _t('Rembourser l’addition', 'Refund bill', 'Reembolsar la cuenta');
  String get refundApprovalTitle => _t(
    'Remboursement — approbation du gérant',
    'Refund — manager approval',
    'Reembolso: aprobación del gerente',
  );
  String get refundReasonTitle =>
      _t('Motif du remboursement', 'Refund reason', 'Motivo del reembolso');
  List<String> get refundReasons => [
    _t('Mauvais article', 'Wrong item', 'Artículo equivocado'),
    _t(
      'Le client a changé d’idée',
      'Customer changed mind',
      'El cliente cambió de opinión',
    ),
    _t('Problème de qualité', 'Quality issue', 'Problema de calidad'),
    _t('Surfacturation', 'Overcharged', 'Cobro de más'),
  ];
  String get refundFull =>
      _t('Remboursement complet', 'Full refund', 'Reembolso total');
  String get refundByLine => _t('Par article', 'By item', 'Por artículo');
  String get refundByAmount => _t('Par montant', 'By amount', 'Por monto');
  String get refundAmountLabel => _t(
    'Montant à rembourser ($cur)',
    'Refund amount ($cur)',
    'Monto del reembolso ($cur)',
  );
  String get refundTender =>
      _t('Rembourser par', 'Refund via', 'Reembolsar con');
  String get refundableLabel =>
      _t('Remboursable', 'Refundable', 'Reembolsable');
  String get refundedLabel => _t('Remboursé', 'Refunded', 'Reembolsado');
  String get fullyRefunded => _t(
    'Entièrement remboursée',
    'Fully refunded',
    'Reembolsada por completo',
  );
  String get confirmRefund =>
      _t('Confirmer le remboursement', 'Confirm refund', 'Confirmar reembolso');
  String refundDone(String amount) =>
      _t('$amount remboursé', 'Refunded $amount', 'Se reembolsaron $amount');
  String cashHandedBack(String amount) => _t(
    'Comptant remis : $amount',
    'Cash handed back $amount',
    'Efectivo devuelto: $amount',
  );
  String get noClosedBills => _t(
    'Aucune addition fermée pour l’instant',
    'No closed bills yet',
    'Todavía no hay cuentas cerradas',
  );
  String get refundHistory => _t(
    'Historique des remboursements',
    'Refund history',
    'Historial de reembolsos',
  );
  String get pickLinesHint => _t(
    'Touchez les articles à rembourser',
    'Tap items to refund',
    'Toca los artículos que vas a reembolsar',
  );
  String get refundSlipTitle =>
      _t('Bon de remboursement', 'Refund slip', 'Comprobante de reembolso');
  String get amountExceedsRefundable => _t(
    'Dépasse le montant remboursable',
    'Exceeds the refundable amount',
    'Supera el monto reembolsable',
  );
  String get pickAmountOrLines => _t(
    'Choisissez des articles ou entrez un montant',
    'Pick items or enter an amount',
    'Elige artículos o ingresa un monto',
  );

  // till — non-sale cash in / out
  String get till => _t('Caisse', 'Till', 'Caja');
  String get cashIn => _t('Entrée de caisse', 'Cash in', 'Entrada de efectivo');
  String get cashOut =>
      _t('Sortie de caisse', 'Cash out', 'Salida de efectivo');
  String get cashInApproval => _t(
    'Entrée de caisse — approbation du gérant',
    'Cash in — manager approval',
    'Entrada de efectivo: aprobación del gerente',
  );
  String get cashOutApproval => _t(
    'Sortie de caisse — approbation du gérant',
    'Cash out — manager approval',
    'Salida de efectivo: aprobación del gerente',
  );
  String get cashAmountLabel =>
      _t('Montant ($cur)', 'Amount ($cur)', 'Monto ($cur)');
  String get cashReasonLabel => _t('Motif', 'Reason', 'Motivo');
  String get cashReasonRequired => _t(
    'Un motif est requis',
    'A reason is required',
    'Se requiere un motivo',
  );
  String cashInDone(String amount) => _t(
    'Entrée de caisse de $amount enregistrée',
    'Cash in $amount recorded',
    'Entrada de efectivo de $amount registrada',
  );
  String cashOutDone(String amount) => _t(
    'Sortie de caisse de $amount enregistrée',
    'Cash out $amount recorded',
    'Salida de efectivo de $amount registrada',
  );
  String get cashMovementsLabel =>
      _t('Mouvements de caisse', 'Cash movements', 'Movimientos de efectivo');
  String get noCashMovements => _t(
    'Aucun mouvement de caisse pour l’instant',
    'No cash movements yet',
    'Todavía no hay movimientos de efectivo',
  );
  String get needOpenShiftForTill => _t(
    'Ouvrez un quart avant d’enregistrer des mouvements de caisse',
    'Open a shift before recording cash movements',
    'Abre un turno antes de registrar movimientos de efectivo',
  );

  // report ranges
  String get rangeThisShift => _t('Ce quart', 'This shift', 'Este turno');
  String get rangeToday => _t('Aujourd’hui', 'Today', 'Hoy');
  String get rangeYesterday => _t('Hier', 'Yesterday', 'Ayer');
  String get rangeThisWeek => _t('Cette semaine', 'This week', 'Esta semana');
  String get rangeCustom => _t('Personnalisé…', 'Custom…', 'Personalizado…');
  String rangeTitle(String from, String to) => from == to
      ? _t('Ventes du $from', 'Sales for $from', 'Ventas del $from')
      : _t(
          'Ventes du $from au $to',
          'Sales $from – $to',
          'Ventas del $from al $to',
        );

  // menu management
  String get manageMenuTitle => _t(
    'Gérer le menu — en vente / épuisé',
    'Manage menu — on/off sale',
    'Administrar menú: a la venta / agotado',
  );
  String get onSale => _t('En vente', 'On sale', 'A la venta');
  String get offSale => _t('Épuisé (86)', 'Off sale (86)', 'Agotado (86)');
  String enableSale(String name) => _t(
    'Remettre $name en vente',
    'Put $name on sale',
    'Poner $name a la venta',
  );
  String disableSale(String name) => _t(
    'Marquer $name épuisé (86)',
    'Take $name off sale (86)',
    'Marcar $name como agotado (86)',
  );

  // menu editing (owner catalog)
  String get addItem =>
      _t('Ajouter un article', 'Add item', 'Agregar artículo');
  String get editItem =>
      _t('Modifier l’article', 'Edit item', 'Editar artículo');
  String get deleteItem =>
      _t('Supprimer l’article', 'Delete item', 'Eliminar artículo');
  String deleteItemConfirm(String name) => _t(
    'Retirer « $name » du menu ? Les anciennes additions le conservent, mais il disparaît de tous les écrans.',
    'Remove "$name" from the menu? Old bills keep it; it disappears from every screen.',
    '¿Quitar "$name" del menú? Las cuentas anteriores lo conservan, pero desaparece de todas las pantallas.',
  );
  String get itemDeleted =>
      _t('Article retiré', 'Item removed', 'Artículo quitado');
  String get nameFrLabel =>
      _t('Nom (français)', 'Name (French)', 'Nombre (francés)');
  String get nameEnLabel =>
      _t('Nom (anglais)', 'Name (English)', 'Nombre (inglés)');
  String get categoryLabel => _t('Catégorie', 'Category', 'Categoría');
  String get abbrevLabel => _t(
    'Pastille (1 à 4 caractères)',
    'Tile badge (1–4 chars)',
    'Insignia (1 a 4 caracteres)',
  );
  String get isAlcoholLabel => _t('Alcool', 'Alcohol', 'Con alcohol');
  String get activeLabel => _t('En vente', 'On sale', 'A la venta');
  String get sizesLabel =>
      _t('Formats / prix', 'Sizes / prices', 'Tamaños / precios');
  String get addSize => _t('Ajouter un format', 'Add size', 'Agregar tamaño');
  String get sizeLabelFr =>
      _t('Format (français)', 'Size (French)', 'Tamaño (francés)');
  String get sizeLabelEn =>
      _t('Format (anglais)', 'Size (English)', 'Tamaño (inglés)');
  String get priceCAD => _t('Prix ($cur)', 'Price ($cur)', 'Precio ($cur)');
  String get fillAllFields => _t(
    'Remplissez tous les champs',
    'Fill in all fields',
    'Completa todos los campos',
  );
  String get editCategories =>
      _t('Gérer les catégories', 'Edit categories', 'Editar categorías');
  String get addCategory =>
      _t('Ajouter une catégorie', 'Add category', 'Agregar categoría');
  String get deleteCategory =>
      _t('Supprimer la catégorie', 'Delete category', 'Eliminar categoría');
  String get dragToReorder => _t(
    'Glissez pour réordonner',
    'Drag to reorder',
    'Arrastra para reordenar',
  );
  String get saved => _t('Enregistré', 'Saved', 'Guardado');

  // slips
  String get printSlips => _t(
    'Imprimer les fiches de toutes les tables',
    'Print all table slips',
    'Imprimir las hojas de todas las mesas',
  );

  // photos
  String get uploadPhoto =>
      _t('Téléverser une photo', 'Upload photo', 'Subir foto');
  String uploadPhotoFor(String name) => _t(
    'Téléverser une photo pour $name',
    'Upload photo for $name',
    'Subir foto de $name',
  );
  String get photoUploaded =>
      _t('Photo téléversée', 'Photo uploaded', 'Foto subida');

  // AI menu photos (paid add-on)
  String get aiGeneratePhoto =>
      _t('Générer une photo', 'Generate photo', 'Generar foto');
  String get aiSnapEnhance => _t(
    'Photographier et améliorer',
    'Snap and enhance',
    'Tomar foto y mejorar',
  );
  String aiGenerateFor(String name) => _t(
    'Générer une photo pour $name',
    'Generate a photo for $name',
    'Generar una foto de $name',
  );
  String aiEnhanceFor(String name) => _t(
    'Améliorer une photo de $name',
    'Enhance a photo of $name',
    'Mejorar una foto de $name',
  );
  String get aiTakePhoto =>
      _t('Prendre une photo', 'Take a photo', 'Tomar una foto');
  String get aiChooseFromGallery => _t(
    'Choisir dans la galerie',
    'Choose from gallery',
    'Elegir de la galería',
  );
  String get aiWorking => _t(
    'Création des photos… (jusqu’à une minute)',
    'Making photos… (up to a minute)',
    'Creando fotos… (hasta un minuto)',
  );
  String get aiPickOne => _t(
    'Touchez la photo à garder',
    'Tap the photo to keep',
    'Toca la foto que quieres conservar',
  );
  String get aiUseThis =>
      _t('Utiliser cette photo', 'Use this photo', 'Usar esta foto');
  String get aiRegenerate =>
      _t('Recommencer', 'Regenerate', 'Generar de nuevo');
  String get aiPhotoSaved =>
      _t('Photo IA enregistrée', 'AI photo saved', 'Foto con IA guardada');
  String get aiBadge => _t('IA', 'AI', 'IA');
  String get aiGeneratedLabel =>
      _t('Photo générée par IA', 'AI-generated photo', 'Foto generada con IA');
  String get aiEnhancedLabel => _t(
    'Photo réelle retouchée par IA',
    'Real photo, AI-enhanced',
    'Foto real mejorada con IA',
  );
  String get aiEnhanceHint => _t(
    'Les aliments restent tels quels : seuls l’éclairage, le fond et la présentation changent.',
    'The food stays as it is: only the lighting, background and presentation change.',
    'La comida queda igual: solo cambian la luz, el fondo y la presentación.',
  );

  /// Why the AI photo buttons are greyed out (a note under them).
  String aiUnavailableNote(String? reason) => switch (reason) {
    'image_offline' || 'image_unavailable' => _t(
      'Photos IA : connexion Internet requise. Tout le reste fonctionne normalement.',
      'AI photos need an internet connection. Everything else works as usual.',
      'Las fotos con IA necesitan internet. Todo lo demás funciona con normalidad.',
    ),
    _ => _t(
      'Photos IA pas encore configurées sur ce magasin.',
      'AI photos aren’t set up on this store yet.',
      'Las fotos con IA aún no están configuradas en esta tienda.',
    ),
  };

  // pin pad / manager approval
  String get managerPinTitle => _t(
    'Gérant : entrez votre NIP',
    'Manager: enter PIN',
    'Gerente: ingresa tu PIN',
  );
  String get managerApproval =>
      _t('Approbation du gérant', 'Manager approval', 'Aprobación del gerente');

  // change PIN
  String get changePin => _t('Changer de NIP', 'Change PIN', 'Cambiar PIN');
  String get currentPin => _t('NIP actuel', 'Current PIN', 'PIN actual');
  String get newPin => _t('Nouveau NIP', 'New PIN', 'PIN nuevo');
  String get confirmNewPin => _t(
    'Confirmer le nouveau NIP',
    'Confirm new PIN',
    'Confirmar el PIN nuevo',
  );
  String get pinChanged => _t('NIP modifié', 'PIN changed', 'PIN cambiado');
  String get pinMismatch => _t(
    'Les nouveaux NIP ne correspondent pas',
    'New PINs do not match',
    'Los PIN nuevos no coinciden',
  );

  // staff administration (the tablet owns its staff; the portal only shows them)
  String get staffTitle => _t('Personnel', 'Staff', 'Personal');
  String get staffAdd =>
      _t('Ajouter un employé', 'Add staff', 'Agregar empleado');
  String get staffEdit =>
      _t('Modifier l’employé', 'Edit staff', 'Editar empleado');
  String get staffName => _t('Nom', 'Name', 'Nombre');
  String get staffRole => _t('Rôle', 'Role', 'Puesto');
  String get roleManager => _t('Gérant', 'Manager', 'Gerente');
  String get roleServer => _t('Serveur', 'Server', 'Mesero');
  String get staffActive => _t('Actif', 'Active', 'Activo');
  String get staffInactive => _t('Inactif', 'Inactive', 'Inactivo');
  String get staffPin4 =>
      _t('NIP (4 chiffres)', 'PIN (4 digits)', 'PIN (4 dígitos)');
  String get staffResetPin => _t('Nouveau NIP', 'Reset PIN', 'Restablecer PIN');
  String get staffPinInvalid => _t(
    'Le NIP doit compter 4 chiffres',
    'PIN must be 4 digits',
    'El PIN debe tener 4 dígitos',
  );
  String get staffNameRequired =>
      _t('Un nom est requis', 'A name is required', 'Se requiere un nombre');
  String get staffDelete => _t('Supprimer', 'Delete', 'Eliminar');
  String staffDeleteConfirm(String name) => _t(
    'Supprimer $name ? L’historique des ventes est conservé.',
    'Delete $name? Sales history is kept.',
    '¿Eliminar a $name? El historial de ventas se conserva.',
  );
  String get staffSaved => _t('Enregistré', 'Saved', 'Guardado');
  String get staffSyncHint => _t(
    'Les changements s’appliquent tout de suite sur cette tablette et sont envoyés au portail à la prochaine connexion.',
    'Changes apply on this tablet at once and are sent to the owner portal when it next connects.',
    'Los cambios se aplican de inmediato en esta tableta y se envían al portal del dueño la próxima vez que se conecte.',
  );

  // settings
  String get settings => _t(
    'Paramètres de l’établissement',
    'Venue settings',
    'Configuración de la tienda',
  );
  String get sectionPayments => _t('Paiements', 'Payments', 'Pagos');
  String get sectionFees => _t('Frais', 'Fees', 'Cargos');
  String get sectionReceipt => _t('Reçu', 'Receipt', 'Recibo');
  String get sectionSecurity => _t('Sécurité', 'Security', 'Seguridad');
  String get sessionIdleLabel => _t(
    'Déconnexion automatique après inactivité (minutes)',
    'Auto-logout when idle (minutes)',
    'Cerrar sesión por inactividad (minutos)',
  );
  String get cardProcessorLabel => _t(
    'Nom du terminal de paiement',
    'Card terminal label',
    'Nombre de la terminal de tarjetas',
  );
  String get bankNameLabel => _t('Banque', 'Bank', 'Banco');
  String get bankAccountNumberLabel =>
      _t('Numéro de compte', 'Account number', 'Número de cuenta');
  String get bankAccountNameLabel =>
      _t('Titulaire du compte', 'Account holder name', 'Titular de la cuenta');
  String get serviceChargeLabel => _t(
    'Frais de service (%) — 0 = désactivés',
    'Service charge (%) — 0 = off',
    'Cargo por servicio (%): 0 = desactivado',
  );
  String get corkageRateLabel => _t(
    'Droit de bouchon ($cur/bouteille) — 0 = désactivé',
    'Corkage ($cur/bottle) — 0 = off',
    'Descorche ($cur/botella): 0 = desactivado',
  );
  String get receiptFooterLabel => _t(
    'Message au bas du reçu',
    'Receipt footer text',
    'Mensaje al pie del recibo',
  );
  String get venuePhoneLabel => _t(
    'Téléphone de l’établissement',
    'Venue phone',
    'Teléfono de la tienda',
  );
  String get venueAddressLabel => _t(
    'Adresse de l’établissement',
    'Venue address',
    'Dirección de la tienda',
  );
  String get save => _t('Enregistrer', 'Save', 'Guardar');
  String get savedTakesEffectNext => _t(
    'Enregistré — s’applique à partir de la prochaine addition',
    'Saved — applies from the next bill',
    'Guardado: se aplica desde la próxima cuenta',
  );

  // store server URL (device-local; auto-discovered on the LAN)
  String get sectionServer => _t(
    'Connexion au restaurant',
    'Restaurant connection',
    'Conexión con la tienda',
  );
  String get serverUrlLabel => _t(
    'Adresse de connexion avancée',
    'Advanced connection address',
    'Dirección de conexión avanzada',
  );
  String get scanForServer => _t(
    'Trouver le restaurant sur le Wi-Fi',
    'Find restaurant on Wi-Fi',
    'Buscar la tienda en el Wi-Fi',
  );
  String get scanningForServer => _t(
    'Recherche du restaurant…',
    'Finding restaurant…',
    'Buscando la tienda…',
  );
  String get connectingToServer => _t(
    'Recherche du restaurant…',
    'Finding your restaurant…',
    'Buscando tu tienda…',
  );
  String get findingRestaurant => _t(
    'Recherche du restaurant…',
    'Finding your restaurant…',
    'Buscando tu tienda…',
  );
  String get startingThisTablet => _t(
    'Démarrage de cette tablette…',
    'Starting this tablet…',
    'Iniciando esta tableta…',
  );
  String get tabletStoreFailed => _t(
    'Le service local de cette tablette n’a pas démarré.',
    'This tablet’s local store did not start.',
    'El servicio local de esta tableta no arrancó.',
  );
  String get staffAppNeedsWifi => _t(
    'Connectez la tablette au Wi-Fi pour afficher le code de l’application du personnel.',
    'Connect this tablet to Wi-Fi to show the staff app code.',
    'Conecta esta tableta al Wi-Fi para mostrar el código de la app del personal.',
  );
  String get restaurantUnavailable => _t(
    'Restaurant introuvable sur ce réseau Wi-Fi',
    'Restaurant is not available on this Wi-Fi',
    'La tienda no está disponible en este Wi-Fi',
  );
  String get connectionHelp =>
      _t('Aide à la connexion', 'Connection help', 'Ayuda con la conexión');
  String serverFoundAt(String url) =>
      _t('Restaurant trouvé', 'Restaurant found', 'Tienda encontrada');
  String get serverNotFound => _t(
    'Restaurant introuvable sur ce Wi-Fi',
    'Restaurant not found on this Wi-Fi',
    'No se encontró la tienda en este Wi-Fi',
  );
  String currentlyUsing(String url) => _t(
    'Adresse actuelle : $url',
    'Currently using: $url',
    'Dirección actual: $url',
  );
  String get serverSavedRestart => _t(
    'Enregistré — redémarrez l’application pour l’appliquer',
    'Saved — restart the app to apply',
    'Guardado: reinicia la app para aplicarlo',
  );
  String get setServerUrl =>
      _t('Connexion avancée', 'Advanced connection', 'Conexión avanzada');
  String get restaurantConnectionReady =>
      _t('Restaurant disponible', 'Restaurant available', 'Tienda disponible');
  String get advancedConnection => _t(
    'Dépannage avancé',
    'Advanced troubleshooting',
    'Solución de problemas avanzada',
  );

  // terminal pairing (cloud venues)
  String get pairTerminalTitle =>
      _t('Jumeler ce terminal', 'Pair this terminal', 'Vincular esta terminal');
  String get pairTerminalIntro => _t(
    'Créez un code de jumelage dans le portail du propriétaire, puis entrez-le ici.',
    'Mint a pairing code in the owner portal, then enter it here.',
    'Genera un código de vinculación en el portal del dueño y luego ingrésalo aquí.',
  );
  String get pairVenueAddressLabel => _t(
    'Adresse de l’établissement',
    'Venue address',
    'Dirección de la tienda',
  );
  String get pairingCodeLabel =>
      _t('Code de jumelage', 'Pairing code', 'Código de vinculación');
  String get deviceNameLabel => _t(
    'Nom de l’appareil (ex. : Bar)',
    'Device name (e.g. Bar)',
    'Nombre del dispositivo (p. ej., Caja)',
  );
  String get pairAction => _t('Jumeler', 'Pair', 'Vincular');
  String get enterVenueAddress => _t(
    'Entrez l’adresse de l’établissement',
    'Enter the venue address',
    'Ingresa la dirección de la tienda',
  );
  String get enterPairingCode => _t(
    'Entrez le code de jumelage',
    'Enter the pairing code',
    'Ingresa el código de vinculación',
  );
  String get pairNetworkError => _t(
    'Établissement injoignable — vérifiez l’adresse et le réseau',
    'Cannot reach the venue — check the address and your network',
    'No se puede conectar con la tienda: revisa la dirección y la red',
  );

  // global reconnecting overlay
  String get reconnectingToServer => _t(
    'Reconnexion au restaurant…',
    'Reconnecting to your restaurant…',
    'Reconectando con tu tienda…',
  );
  String get reconnectingToRestaurant => _t(
    'Reconnexion au restaurant…',
    'Reconnecting to your restaurant…',
    'Reconectando con tu tienda…',
  );
  String get reconnectChangeServer =>
      _t('Aide à la connexion', 'Connection help', 'Ayuda con la conexión');

  // on-screen QR codes: table scan-to-order (long-press a table) + staff app
  String tableQrTitle(String label) => _t(
    'Balayez pour commander · $label',
    'Scan to order · $label',
    'Escanea para pedir · $label',
  );
  String get tableQrHint => _t(
    'Les clients balaient le code avec un téléphone branché sur le Wi-Fi de l’établissement',
    'Guests scan with a phone on the venue Wi-Fi',
    'Los clientes escanean con un celular conectado al Wi-Fi de la tienda',
  );
  String get tableQrNeedsWifi => _t(
    'Connectez la tablette au Wi-Fi pour afficher un code QR utilisable.',
    'Connect the tablet to Wi-Fi to show a scannable QR code.',
    'Conecta la tableta al Wi-Fi para mostrar un código QR que funcione.',
  );
  String get showQrCode =>
      _t('Afficher le code QR', 'Show QR code', 'Mostrar código QR');
  String get printQrCode =>
      _t('Imprimer le code QR', 'Print QR code', 'Imprimir código QR');
  String get regenerateTableLink => _t(
    'Régénérer le lien de la table',
    'Regenerate table link',
    'Regenerar el enlace de la mesa',
  );
  String regenerateTableLinkConfirm(String label) => _t(
    'Créer un nouveau lien pour $label ? Les fiches QR déjà imprimées pour cette table cesseront de fonctionner ; réimprimez sa fiche.',
    'Create a new link for $label? QR slips already printed for this table stop working; reprint its slip.',
    '¿Crear un enlace nuevo para $label? Las hojas QR ya impresas para esta mesa dejarán de funcionar; vuelve a imprimir su hoja.',
  );
  String get regenerate => _t('Régénérer', 'Regenerate', 'Regenerar');
  String get tableLinkRegenerated => _t(
    'Nouveau lien créé. Réimprimez la fiche QR de cette table.',
    'New link created. Reprint this table\'s QR slip.',
    'Se creó un enlace nuevo. Vuelve a imprimir la hoja QR de esta mesa.',
  );
  String get slipSentToPrinter => _t(
    'Fiche QR envoyée à l’imprimante',
    'QR slip sent to the printer',
    'Hoja QR enviada a la impresora',
  );
  String get sectionStaffApp =>
      _t('Application du personnel', 'Staff app', 'App del personal');
  String get staffAppQrLabel => _t(
    'Application de commande du personnel — balayez pour l’ouvrir',
    'Staff ordering app — scan to open',
    'App de pedidos del personal: escanea para abrir',
  );
  String get sectionReportsPortal => _t('Rapports', 'Reports', 'Reportes');
  String get reportsPortalQrLabel => _t(
    'Portail de rapports du propriétaire — balayez pour l’ouvrir',
    'Owner reporting portal — scan to open',
    'Portal de reportes del dueño: escanea para abrir',
  );
  String get cloudNotConfigured => _t(
    'Nuage non configuré',
    'Cloud not configured',
    'La nube no está configurada',
  );

  // pending-order alerts (settings + banner)
  String get sectionAlerts => _t(
    'Alertes de commandes clients',
    'Customer order alerts',
    'Alertas de pedidos de clientes',
  );
  String get alertsEnabledLabel => _t(
    'Carillon à chaque nouvelle commande d’un client',
    'Chime on new customer orders',
    'Sonido con cada pedido nuevo de un cliente',
  );
  String get alertEscalateLabel => _t(
    'Alerte insistante si rien n’est fait après (secondes)',
    'Escalate if un-actioned after (seconds)',
    'Alerta insistente si nadie responde después de (segundos)',
  );
  String get alertVolumeLabel =>
      _t('Volume de l’alerte', 'Alert volume', 'Volumen de la alerta');

  // receipt printer (settings)
  String get sectionPrinter =>
      _t('Imprimante à reçus', 'Receipt printer', 'Impresora de recibos');
  String get printerIpLabel => _t(
    'IP de l’imprimante (vide = désactivée)',
    'Printer IP (blank = off)',
    'IP de la impresora (vacío = desactivada)',
  );
  String get printerPortLabel => _t('Port réseau', 'Port', 'Puerto');
  String get scanForPrinter => _t(
    'Chercher l’imprimante sur le réseau',
    'Scan network for printer',
    'Buscar la impresora en la red',
  );
  String get scanningForPrinter => _t(
    'Recherche de l’imprimante…',
    'Scanning for printer…',
    'Buscando la impresora…',
  );
  String printerFoundAt(String ip) => _t(
    'Imprimante trouvée : $ip',
    'Found printer: $ip',
    'Impresora encontrada: $ip',
  );
  String printersFound(int n, String ip) => _t(
    '$n imprimantes trouvées — utilisation de $ip',
    'Found $n printers — using $ip',
    'Se encontraron $n impresoras: usando $ip',
  );
  String get printerNotFound => _t(
    'Aucune imprimante trouvée sur ce réseau',
    'No printer found on this network',
    'No se encontró ninguna impresora en esta red',
  );
  String get testPrint =>
      _t('Impression d’essai', 'Test print', 'Impresión de prueba');
  String get printerTestSent => _t(
    'Page d’essai envoyée à l’imprimante',
    'Test page sent to the printer',
    'Página de prueba enviada a la impresora',
  );
  String get printerNotConfigured => _t(
    'IP de l’imprimante non définie',
    'Printer IP not set',
    'No se configuró la IP de la impresora',
  );
  String get printerOffline => _t(
    'Échec de l’impression — imprimante hors ligne',
    'Print failed — printer offline',
    'Falló la impresión: impresora desconectada',
  );
  String get printAllTableQr => _t(
    'Imprimer les codes QR de toutes les tables',
    'Print all table QR codes',
    'Imprimir los códigos QR de todas las mesas',
  );
  String printAllTableQrConfirm(int n) => _t(
    'Imprimer les fiches QR des $n tables ? Cela prend beaucoup de papier.',
    'Print QR slips for all $n tables? This uses a lot of paper.',
    '¿Imprimir las hojas QR de las $n mesas? Esto usa mucho papel.',
  );
  String slipsPrinted(int n) =>
      _t('$n fiches imprimées', 'Printed $n slips', 'Se imprimieron $n hojas');

  // guest Wi-Fi (settings): the join slip and step 1 of the table slips
  String get sectionGuestWifi =>
      _t('Wi-Fi invités', 'Guest Wi-Fi', 'Wi-Fi para clientes');
  String get guestWifiHint => _t(
    'Imprimé sur une fiche Wi-Fi et comme première étape des fiches QR des tables.',
    'Printed on a Wi-Fi slip and as step 1 on the table QR slips.',
    'Se imprime en una hoja de Wi-Fi y como paso 1 en las hojas QR de las mesas.',
  );
  String get wifiSsidLabel => _t(
    'Nom du réseau (vide = aucun)',
    'Network name (blank = none)',
    'Nombre de la red (vacío = ninguna)',
  );
  String get wifiPasswordLabel => _t('Mot de passe', 'Password', 'Contraseña');
  String get wifiShowPassword =>
      _t('Afficher le mot de passe', 'Show password', 'Mostrar contraseña');
  String get wifiHidePassword =>
      _t('Masquer le mot de passe', 'Hide password', 'Ocultar contraseña');
  String get wifiSecurityLabel => _t('Sécurité', 'Security', 'Seguridad');
  String get wifiSecurityNone =>
      _t('Aucune (réseau ouvert)', 'None (open)', 'Ninguna (abierta)');
  String get wifiHiddenLabel =>
      _t('Réseau masqué', 'Hidden network', 'Red oculta');
  String get printWifiSlip => _t(
    'Imprimer la fiche Wi-Fi',
    'Print Wi-Fi slip',
    'Imprimir hoja de Wi-Fi',
  );
  String get wifiCopiesLabel => _t('Exemplaires', 'Copies', 'Copias');
  String wifiSlipsPrinted(int n) => n == 1
      ? _t(
          'Fiche Wi-Fi imprimée',
          'Wi-Fi slip printed',
          'Hoja de Wi-Fi impresa',
        )
      : _t(
          '$n fiches Wi-Fi imprimées',
          'Printed $n Wi-Fi slips',
          'Se imprimieron $n hojas de Wi-Fi',
        );
  String get wifiNotConfigured => _t(
    'Entrez d’abord le nom et le mot de passe du réseau Wi-Fi',
    'Set the Wi-Fi network name and password first',
    'Primero configura el nombre y la contraseña de la red Wi-Fi',
  );
  String get saveFirstToTest => _t(
    'Enregistrez d’abord, puis lancez l’impression d’essai',
    'Save first, then test print',
    'Guarda primero y luego haz la impresión de prueba',
  );
  String ordersWaiting(int n) => n == 1
      ? _t('1 commande en attente', '1 order waiting', '1 pedido en espera')
      : _t(
          '$n commandes en attente',
          '$n orders waiting',
          '$n pedidos en espera',
        );

  /// Compact age for the alert banner: seconds under a minute, else minutes.
  String alertAge(Duration d) =>
      d.inMinutes < 1 ? '${d.inSeconds}s' : elapsedShort(d);

  // zone open/closed
  String get zoneClosed => _t('Fermée', 'Closed', 'Cerrada');
  String get zoneClosedBanner => _t(
    'Cette section est temporairement fermée. Adressez-vous au personnel.',
    'This section is temporarily closed. Please ask staff.',
    'Esta sección está cerrada por el momento. Pregunta al personal.',
  );
  String get zoneCloseAction =>
      _t('Fermer la zone', 'Close zone', 'Cerrar zona');
  String get zoneReopenAction =>
      _t('Rouvrir la zone', 'Reopen zone', 'Reabrir zona');
  String get newCheckBlockedZoneClosed =>
      _t('Zone fermée', 'Zone is closed', 'La zona está cerrada');

  // zone (room) management — add / rename / delete / reorder
  String get addRoom => _t('Ajouter une salle', 'Add room', 'Agregar salón');
  String get manageRoom =>
      _t('Gérer la salle', 'Manage room', 'Administrar salón');
  String get renameRoom =>
      _t('Renommer la salle', 'Rename room', 'Cambiar nombre del salón');
  String get deleteRoom =>
      _t('Supprimer la salle', 'Delete room', 'Eliminar salón');
  String deleteRoomConfirm(String name) => _t(
    'Supprimer la salle « $name » ? Elle doit être vide (ni tables ni éléments) pour être supprimée.',
    'Delete room "$name"? A room must be empty (no tables or objects) to delete.',
    '¿Eliminar el salón "$name"? Debe estar vacío (sin mesas ni elementos) para eliminarlo.',
  );
  String get roomDeleted =>
      _t('Salle supprimée', 'Room deleted', 'Salón eliminado');
  String get roomCreated => _t('Salle créée', 'Room created', 'Salón creado');
  String get moveRoomLeft =>
      _t('Déplacer à gauche', 'Move left', 'Mover a la izquierda');
  String get moveRoomRight =>
      _t('Déplacer à droite', 'Move right', 'Mover a la derecha');
  String get roomNameFrField => _t(
    'Nom de la salle (français)',
    'Room name (French)',
    'Nombre del salón (francés)',
  );
  String get roomNameEnField => _t(
    'Nom de la salle (anglais)',
    'Room name (English)',
    'Nombre del salón (inglés)',
  );

  // tables / time
  String elapsedShort(Duration d) => d.inHours > 0
      ? '${d.inHours}h ${(d.inMinutes % 60).toString().padLeft(2, '0')}m'
      : '${d.inMinutes}m';

  /// Server error codes → local language. Fallback: raw server message.
  String? apiError(String? code) => switch (code) {
    'invalid_pin' => _t('NIP invalide', 'Invalid PIN', 'PIN no válido'),
    'image_unavailable' ||
    'image_offline' => aiUnavailableNote('image_offline'),
    'image_disabled' => aiUnavailableNote('image_generation_off'),
    'image_timeout' => _t(
      'Le service de photos IA a mis trop de temps. Réessayez.',
      'The AI photo service took too long. Please try again.',
      'El servicio de fotos con IA tardó demasiado. Inténtalo de nuevo.',
    ),
    'image_rate_limited' => _t(
      'Trop de demandes de photos IA. Réessayez dans une minute.',
      'Too many AI photo requests. Try again in a minute.',
      'Demasiadas solicitudes de fotos con IA. Inténtalo en un minuto.',
    ),
    'image_quota' => _t(
      'Le compte de photos IA n’a plus de crédits.',
      'The AI photo account is out of credits.',
      'La cuenta de fotos con IA se quedó sin créditos.',
    ),
    'image_refused' => _t(
      'Le service de photos IA a refusé cette demande. Modifiez la description et réessayez.',
      'The AI photo service declined this request. Adjust the description and try again.',
      'El servicio de fotos con IA rechazó la solicitud. Ajusta la descripción e inténtalo de nuevo.',
    ),
    'image_auth' || 'image_error' => _t(
      'Le service de photos IA a renvoyé une erreur. Réessayez plus tard.',
      'The AI photo service returned an error. Try again later.',
      'El servicio de fotos con IA devolvió un error. Inténtalo más tarde.',
    ),
    'wifi_not_configured' => wifiNotConfigured,
    'pin_in_use' => _t(
      'Ce NIP est déjà utilisé par un autre employé',
      'That PIN is already used by another staff member',
      'Ese PIN ya lo usa otro empleado',
    ),
    'bad_pin' => _t(
      'Le NIP doit compter 4 chiffres',
      'PIN must be 4 digits',
      'El PIN debe tener 4 dígitos',
    ),
    'last_manager' => _t(
      'Gardez au moins un gérant actif pour gérer le personnel',
      'Keep at least one active manager who can manage staff',
      'Debe quedar al menos un gerente activo que administre al personal',
    ),
    'login_required' => _t(
      'Veuillez vous reconnecter',
      'Please log in again',
      'Inicia sesión de nuevo',
    ),
    'manager_approval_required' => _t(
      'Approbation du gérant requise',
      'Manager approval required',
      'Se requiere la aprobación del gerente',
    ),
    'check_not_open' => _t(
      'Cette addition n’est plus ouverte',
      'This bill is no longer open',
      'Esta cuenta ya no está abierta',
    ),
    'zone_closed' => _t(
      'Zone fermée',
      'Zone is closed',
      'La zona está cerrada',
    ),
    'bill_locked' => _t(
      'Paiement de l’addition en cours — adressez-vous au personnel',
      'Bill is being paid — ask staff',
      'La cuenta se está pagando: pregunta al personal',
    ),
    'already_paid' => _t(
      'Addition déjà entièrement payée',
      'Bill already fully paid',
      'La cuenta ya está pagada por completo',
    ),
    'pending_lines_unresolved' => _t(
      'Des commandes clients attendent une confirmation — réglez-les avant le paiement',
      'Customer orders awaiting confirm — resolve before payment',
      'Hay pedidos de clientes por confirmar: resuélvelos antes de cobrar',
    ),
    'outstanding_balance' => _t(
      'Il reste un solde à payer',
      'Balance still outstanding',
      'Todavía queda saldo pendiente',
    ),
    'void_has_tenders' => _t(
      'L’addition a des paiements : annulation impossible — faites plutôt un remboursement',
      'Bill has payments; void not allowed — refund instead',
      'La cuenta tiene pagos; no se puede anular: haz un reembolso',
    ),
    'refund_not_closed' => _t(
      'Seule une addition fermée peut être remboursée',
      'Only a closed bill can be refunded',
      'Solo se puede reembolsar una cuenta cerrada',
    ),
    'refund_exceeds_total' => _t(
      'Le remboursement dépasse le montant encore remboursable',
      'Refund exceeds the remaining refundable amount',
      'El reembolso supera el monto que queda por reembolsar',
    ),
    'refund_non_positive' => _t(
      'Le montant du remboursement doit être supérieur à zéro',
      'Refund amount must be positive',
      'El monto del reembolso debe ser mayor que cero',
    ),
    'refund_no_amount' => _t(
      'Entrez un montant ou choisissez des articles',
      'Enter an amount or pick items',
      'Ingresa un monto o elige artículos',
    ),
    'refund_bad_tender' => _t(
      'Mode de remboursement invalide',
      'Invalid refund tender',
      'Forma de reembolso no válida',
    ),
    'refund_bad_line' => _t(
      'L’article choisi n’est pas sur cette addition',
      'Selected item is not on this bill',
      'El artículo elegido no está en esta cuenta',
    ),
    'refund_qty_too_high' => _t(
      'La quantité dépasse celle de l’addition',
      'Refund quantity exceeds the bill',
      'La cantidad supera la de la cuenta',
    ),
    'cash_bad_direction' => _t(
      'Sens invalide',
      'Invalid direction',
      'Dirección no válida',
    ),
    'cash_non_positive' => _t(
      'Le montant doit être supérieur à zéro',
      'Amount must be positive',
      'El monto debe ser mayor que cero',
    ),
    'no_open_shift' => _t(
      'Aucun quart ouvert',
      'No shift open',
      'No hay turno abierto',
    ),
    'shift_already_open' => _t(
      'Un quart est déjà ouvert',
      'A shift is already open',
      'Ya hay un turno abierto',
    ),
    'tender_type_not_accepted' => _t(
      'Mode de paiement non accepté',
      'Payment method not accepted',
      'No se acepta esta forma de pago',
    ),
    'no_receipt_yet' => _t(
      'Pas encore de reçu',
      'No receipt yet',
      'Todavía no hay recibo',
    ),
    'check_not_billable' => _t(
      'Cette addition est fermée — impression impossible',
      'This bill is closed — cannot print',
      'Esta cuenta está cerrada: no se puede imprimir',
    ),
    'bad_pairing_code' => _t(
      'Code de jumelage invalide ou expiré',
      'Invalid or expired pairing code',
      'Código de vinculación no válido o vencido',
    ),
    'pairing_unavailable' => _t(
      'Jumelage temporairement indisponible — réessayez',
      'Pairing is temporarily unavailable — try again',
      'La vinculación no está disponible por ahora: inténtalo de nuevo',
    ),
    'cloud_unreachable' => _t(
      'L’établissement ne joint pas le nuage — réessayez sous peu',
      'The store cannot reach the cloud — try again shortly',
      'La tienda no puede conectarse con la nube: inténtalo en un momento',
    ),
    'device_required' => _t(
      'Ce terminal n’est pas jumelé à l’établissement',
      'This terminal is not paired with the store',
      'Esta terminal no está vinculada con la tienda',
    ),
    'device_revoked' => _t(
      'Le jumelage de ce terminal a été révoqué — jumelez-le de nouveau',
      'This terminal\'s pairing was revoked — pair again',
      'Se revocó la vinculación de esta terminal: vincúlala otra vez',
    ),
    'not_found' => _t('Introuvable', 'Not found', 'No se encontró'),
    'bad_request' => _t(
      'Demande invalide',
      'Invalid request',
      'Solicitud no válida',
    ),
    'rate_limited' => _t(
      'Trop de tentatives échouées — réessayez plus tard',
      'Too many failed attempts — try again later',
      'Demasiados intentos fallidos: inténtalo más tarde',
    ),
    'variant_in_use' => _t(
      'Ce format est sur une addition ouverte — fermez-la ou annulez-la d’abord',
      'This size is on an open order — close or void that check first',
      'Este tamaño está en una cuenta abierta: ciérrala o anúlala primero',
    ),
    'item_in_use' => _t(
      'Cet article est sur une addition ouverte — fermez-la ou annulez-la d’abord',
      'This item is on an open order — close or void that check first',
      'Este artículo está en una cuenta abierta: ciérrala o anúlala primero',
    ),
    'last_variant' => _t(
      'C’est le seul format de l’article — supprimez plutôt l’article au complet',
      'This is the item\'s only size — delete the whole item instead',
      'Es el único tamaño del artículo: mejor elimina el artículo completo',
    ),
    'category_not_empty' => _t(
      'Cette catégorie contient encore des articles — déplacez-les ou retirez-les d’abord',
      'This category still has items — move or remove them first',
      'Esta categoría todavía tiene artículos: muévelos o quítalos primero',
    ),
    'split_locked' => _t(
      'Un paiement a déjà été reçu — la séparation est verrouillée',
      'Money already taken — split is locked',
      'Ya se recibió un pago: la división está bloqueada',
    ),
    'split_exists' => _t(
      'L’addition est déjà séparée',
      'Bill is already split',
      'La cuenta ya está dividida',
    ),
    'no_split' => _t(
      'L’addition n’est pas séparée',
      'Bill is not split',
      'La cuenta no está dividida',
    ),
    'split_stale' => _t(
      'L’addition a changé depuis la séparation en parts égales — séparez-la de nouveau',
      'The bill changed since it was split evenly — split it again',
      'La cuenta cambió desde que se dividió en partes iguales: divídela otra vez',
    ),
    'split_unassigned_lines' => _t(
      'Des articles ne sont pas encore attribués — attribuez-les tous avant le paiement',
      'Some items are still unassigned — assign everything before payment',
      'Todavía hay artículos sin asignar: asígnalos todos antes de cobrar',
    ),
    'group_required' => _t(
      'L’addition est séparée — choisissez l’addition à payer',
      'Bill is split — pay a specific bill',
      'La cuenta está dividida: elige qué cuenta pagar',
    ),
    'group_already_paid' => _t(
      'Cette addition est déjà payée',
      'This bill is already paid',
      'Esta cuenta ya está pagada',
    ),
    'group_not_found' => _t(
      'Cette addition séparée n’existe plus — actualisez la séparation',
      'That split bill no longer exists — refresh the split',
      'Esa cuenta dividida ya no existe: actualiza la división',
    ),
    'group_outstanding' => _t(
      'Certaines additions ne sont pas encore payées',
      'Some bills still owe',
      'Algunas cuentas todavía tienen saldo',
    ),
    'qty_below_allocated' => _t(
      'L’article est attribué à une addition séparée — retirez-le de celle-ci d’abord',
      'Item is assigned to a split bill — unassign it first',
      'El artículo está asignado a una cuenta dividida: quítalo de ahí primero',
    ),
    'qty_exceeds_unassigned' => _t(
      'Plus que la quantité non attribuée',
      'More than the unassigned quantity',
      'Más que la cantidad sin asignar',
    ),
    'qty_exceeds_allocated' => _t(
      'Plus que la quantité attribuée',
      'More than the assigned quantity',
      'Más que la cantidad asignada',
    ),
    'even_split' => _t(
      'L’addition est séparée en parts égales (÷N) — les articles ne peuvent pas être déplacés',
      'Split is even ÷N — items cannot move',
      'La cuenta está dividida en partes iguales (÷N): no se pueden mover artículos',
    ),
    'empty_check' => _t(
      'Rien à séparer',
      'Nothing to split',
      'No hay nada que dividir',
    ),
    'last_group' => _t(
      'Il doit rester au moins une addition',
      'At least one bill must remain',
      'Debe quedar al menos una cuenta',
    ),
    'same_table' => _t(
      'L’addition est déjà à cette table',
      'Bill is already on this table',
      'La cuenta ya está en esta mesa',
    ),
    'same_check' => _t(
      'Impossible de fusionner une addition avec elle-même',
      'Cannot merge a bill into itself',
      'No se puede combinar una cuenta consigo misma',
    ),
    'table_occupied' => _t(
      'La table a une addition ouverte — fusionnez plutôt',
      'Table has an open bill — merge instead',
      'La mesa tiene una cuenta abierta: mejor combínalas',
    ),
    'table_in_use' => _t(
      'La table a une addition ouverte — fermez-la ou déplacez-la avant de retirer la table',
      'Table has an open bill — close or move it before removing',
      'La mesa tiene una cuenta abierta: ciérrala o muévela antes de quitar la mesa',
    ),
    'has_sub_tables' => _t(
      'La table a des sous-tables — retirez-les d’abord',
      'Table has sub-tables — remove them first',
      'La mesa tiene submesas: quítalas primero',
    ),
    'label_taken' => _t(
      'Une table porte déjà ce nom dans cette zone',
      'A table with this label already exists in this zone',
      'Ya existe una mesa con ese nombre en esta zona',
    ),
    'table_not_in_zone' => _t(
      'Le plan ne correspond pas à cette zone — actualisez et réessayez',
      'Layout does not match this zone — refresh and retry',
      'El plano no coincide con esta zona: actualiza e inténtalo de nuevo',
    ),
    'zone_not_empty' => _t(
      'Cette salle contient encore des tables ou des éléments — videz-la avant de la supprimer',
      'This room still has tables or objects — clear them before deleting it',
      'Este salón todavía tiene mesas o elementos: vacíalo antes de eliminarlo',
    ),
    'clear_split_first' => _t(
      'L’addition est séparée — annulez d’abord la séparation',
      'Bill is split — clear the split first',
      'La cuenta está dividida: deshaz la división primero',
    ),
    'conflict' => _t(
      'Action impossible pour le moment',
      'Cannot do that right now',
      'No se puede hacer eso ahora',
    ),
    'internal' => _t(
      'Une erreur est survenue — réessayez',
      'Something went wrong — try again',
      'Algo salió mal: inténtalo de nuevo',
    ),
    'stripe_unavailable' => _t(
      'Stripe est injoignable (Internet ?). Rien n’a été fait chez Stripe.',
      'Can\'t reach Stripe (internet?). Nothing was done at Stripe.',
      'No se puede conectar con Stripe (¿internet?). No se hizo nada en Stripe.',
    ),
    'stripe_declined' => stripeDeclineMessage(null),
    'stripe_not_configured' => _t(
      'La carte (Stripe) n’est pas configurée dans cet établissement',
      'Card (Stripe) is not set up on this store',
      'La tarjeta (Stripe) no está configurada en esta tienda',
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
    ),
    'stripe_amount_exceeds_due' => _t(
      'L’addition ne doit plus ce montant — la carte n’a pas été débitée',
      'The bill no longer owes that amount — the card was not charged',
      'La cuenta ya no debe ese monto: no se cobró a la tarjeta',
    ),
    'stripe_payment_reversed' => _t(
      'Le paiement par carte n’a pas pu être appliqué à cette addition et a été remboursé',
      'The card payment could not be applied to this bill and was refunded',
      'El pago con tarjeta no se pudo aplicar a esta cuenta y se reembolsó',
    ),
    'stripe_payment_canceled' => _t(
      'Ce paiement par carte a été annulé',
      'That card payment was canceled',
      'Se canceló ese pago con tarjeta',
    ),
    'stripe_not_ready' => _t(
      'Le paiement par carte n’est pas terminé — réessayez',
      'The card payment isn\'t finished yet — try again',
      'El pago con tarjeta todavía no termina: inténtalo de nuevo',
    ),
    'stripe_no_card_tender' => _t(
      'Cette addition n’a pas été payée par carte (Stripe)',
      'This bill wasn\'t paid by Card (Stripe)',
      'Esta cuenta no se pagó con tarjeta (Stripe)',
    ),
    'stripe_refund_exceeds_card' => _t(
      'Le remboursement dépasse le montant payé par carte (Stripe)',
      'The refund is more than was paid by Card (Stripe)',
      'El reembolso supera lo que se pagó con tarjeta (Stripe)',
    ),
    'stripe_refund_split_required' => _t(
      'Remboursez chaque paiement par carte séparément',
      'Refund each card payment separately',
      'Reembolsa cada pago con tarjeta por separado',
    ),
    'stripe_refund_failed' => _t(
      'Stripe n’a pas effectué le remboursement. Rien n’a été enregistré.',
      'Stripe did not make the refund. Nothing was recorded.',
      'Stripe no hizo el reembolso. No se registró nada.',
    ),
    'stripe_refund_via_stripe' => _t(
      'Les remboursements par carte (Stripe) passent par Stripe',
      'Card (Stripe) refunds go through Stripe',
      'Los reembolsos con tarjeta (Stripe) se hacen por Stripe',
    ),
    // retail counter
    'unknown_barcode' => _t(
      'Code-barres inconnu',
      'Unknown barcode',
      'Código de barras desconocido',
    ),
    'age_check_required' => _t(
      'Vérifiez la pièce d’identité du client avant le paiement',
      'Check the customer\'s ID before payment',
      'Verifica la identificación del cliente antes de cobrar',
    ),
    'age_check_failed' => _t(
      'Vérification de l’âge échouée — retirez les articles réservés aux adultes',
      'ID check failed — remove the age-restricted items',
      'No pasó la verificación de edad: quita los artículos con restricción de edad',
    ),
    'barcode_taken' => _t(
      'Ce code-barres appartient déjà à un produit',
      'That barcode already belongs to a product',
      'Ese código de barras ya pertenece a un producto',
    ),
    'item_inactive' => _t(
      'Ce produit n’est pas en vente',
      'That product is off sale',
      'Ese producto no está a la venta',
    ),
    _ => null,
  };
}

/// Show an API error as a snackbar — except session expiry, which already
/// navigated to login and needs no acknowledgement.
void showApiError(BuildContext context, Object error) {
  if (error is SessionExpiredException) return;
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$error')));
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
