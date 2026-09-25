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

  String lang = 'en'; // en | fr

  // Floor-plan editor toggles — device-local only (per-terminal habit, never
  // synced to the user row). Grid + snap default on (the old hardwired grid);
  // grid step defaults to 100 logical units (the original, largest spacing).
  bool editorShowGrid = true;
  bool editorSnap = true;
  int editorGridStep = 100; // visual dot spacing: 100 (large) | 50 | 25 (small)

  bool get isEn => lang == 'en';

  Future<void> load() async {
    final storedLang = await _storage.read(key: 'pref_lang');
    lang = storedLang == 'fr' ? 'fr' : 'en';
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
    lang = languageCode == 'fr' ? 'fr' : 'en';
    _persistLocal();
    notifyListeners();
  }

  Future<void> setLang(String value) async {
    lang = value == 'fr' ? 'fr' : 'en';
    notifyListeners();
    await _persist();
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

  /// Date-only with the standard Gregorian year.
  String fmtDate(DateTime d) {
    final year = d.year;
    String p2(int n) => n.toString().padLeft(2, '0');
    return '${p2(d.day)}/${p2(d.month)}/$year';
  }
}

/// Rebuild scope above MaterialApp: widgets that call [L.of] re-render
/// instantly on toggle, dialogs and pushed routes included.
class PrefsScope extends InheritedNotifier<Prefs> {
  PrefsScope({super.key, required super.child})
    : super(notifier: Prefs.instance);
}

Widget prefsScope({required Widget child}) => PrefsScope(child: child);

/// All UI strings, both locales inline — compile-checked, no key typos.
/// `L.of(context)` subscribes the caller to language changes.
class L {
  final bool en;
  const L(this.en);

  static L of(BuildContext context) {
    context.dependOnInheritedWidgetOfExactType<PrefsScope>();
    return L(Prefs.instance.isEn);
  }

  String _t(String fr, String enS) => en ? enS : fr;

  /// Data-driven names (items, zones, variants): user's language first.
  String name(String fr, String enS) => en ? enS : fr;

  /// The other language, shown as the small secondary line on menu tiles.
  String nameAlt(String fr, String enS) => en ? fr : enS;

  // common
  String get retry => _t('Essayer à nouveau', 'Retry');
  String get cancel => _t('Annuler', 'Cancel');
  String get ok => _t('accepter', 'OK');
  String get done => _t('finition', 'Done');
  String get close => _t('éteindre', 'Close');
  String get cannotReachServer => _t(
    'Restaurant temporairement indisponible',
    'Restaurant temporarily unavailable',
  );

  // login
  String get enterPin =>
      _t('Entrez votre code PIN pour y accéder.', 'Enter your PIN to sign in');
  String get loginInterrupted => _t(
    'Connexion interrompue — entrez de nouveau votre code PIN.',
    'Connection interrupted — enter your PIN again',
  );
  String get whoClockingIn => _t('Qui est en poste ?', "Who's clocking in?");
  String get switchLanguage => _t('Changer de langue', 'Switch language');
  String get staffTerminal => _t('Terminal du personnel', 'Staff terminal');

  // floor header: short labels under the action icons
  String get navMenu => _t('Menu', 'Menu');
  String get navReports => _t('Rapports', 'Reports');
  String get navRefunds => _t('Remboursements', 'Refunds');
  String get navLayout => _t('Plan de salle', 'Layout');
  String get navRefresh => _t('Actualiser', 'Refresh');
  String get navMore => _t('Plus', 'More');

  // floor legend + room list
  String get rooms => _t('Salles', 'Rooms');
  String get legendFree => _t('Libre', 'Free');
  String get legendOccupied => _t('Occupée', 'Occupied');
  String get legendPending => _t('Commande en attente', 'Order waiting');
  String tablesOccupied(int open, int total) =>
      _t('$open sur $total occupées', '$open of $total occupied');

  /// How long a check has been open, e.g. "25 min", "1 h 05".
  String openFor(Duration d) {
    if (d.inMinutes < 1) return _t('à l\'instant', 'just now');
    if (d.inHours < 1) return '${d.inMinutes} min';
    final m = (d.inMinutes % 60).toString().padLeft(2, '0');
    return '${d.inHours} h $m';
  }

  // check screen cart
  String itemCount(int n) => en
      ? (n == 1 ? '1 item' : '$n items')
      : (n <= 1 ? '$n article' : '$n articles');
  String get emptyBill =>
      _t('Rien sur cette facture pour l\'instant', 'Nothing on this bill yet');
  String get each => _t('l\'unité', 'each');
  String get tapToAdd => _t(
    'Touchez un article du menu pour l\'ajouter.',
    'Tap a menu item to add it.',
  );

  // zones
  String get zones => _t('zone', 'Zones');
  String get manageMenu => _t('Gérer les menus (86)', 'Manage menu (86)');
  String get shiftReports => _t('Changement/Rapport', 'Shift / Reports');
  String get logout => _t('Se déconnecter', 'Log out');

  // tables
  String get subTable => _t('Sous-tableau', 'Sub-table');
  String get free => _t('gratuit', 'Free');

  // floor plan
  String seatsShort(int n) => _t('$n sièges', '$n seats');
  String get emptyZoneOnboarding => _t(
    'Il n\'y a aucune table dans cette zone.\\nAppuyez sur l\'icône en forme de crayon pour organiser la disposition des tables.',
    'No tables in this zone yet.\nTap the pencil to build the layout.',
  );

  // floor plan editor (manager)
  String get editLayout =>
      _t('Organiser la disposition de la table', 'Edit layout');
  String editLayoutTitle(String zone) =>
      _t('Organiser la disposition de la table · $zone', 'Edit layout · $zone');
  String get saveLayout => _t('Enregistrez la mise en page', 'Save layout');
  String get layoutSaved =>
      _t('La mise en page a été enregistrée.', 'Layout saved');
  String get addTable => _t('Ajouter un tableau', 'Add table');
  String get deleteTable => _t('Supprimer le tableau', 'Remove table');
  String deleteTableConfirm(String label) => _t(
    'Supprimer la table "$label" de l\'arborescence ? Les anciennes factures ne disparaîtront pas. Mais ce tableau disparaîtra de tous les écrans.',
    'Remove table "$label" from the plan? Old bills keep it; it disappears from every screen.',
  );
  String get tableDeleted => _t('Tableau supprimé', 'Table removed');
  String get renameTable => _t('Changer le nom de la table', 'Rename table');
  String get tableLabelField =>
      _t('Nom de la table (par exemple L-5)', 'Table label (e.g. L-5)');

  /// Add-table dialog helper: labels are auto-assigned to the zone prefix.
  String autoLabelHint(String prefix) => _t(
    'Laissez vide pour obtenir le numéro suivant ($prefix-…)',
    'Leave blank for the next number ($prefix-…)',
  );
  String get vipNameField =>
      _t('Nom VIP (vide = aucun)', 'VIP name (blank = none)');
  String get rotate => _t('tourner', 'Rotate');
  String get undo => _t('rétrospective', 'Undo');
  String get editorGrid => _t('lignes de quadrillage', 'Grid');
  String get editorSnap => _t('instantané', 'Snap');
  String get gridOff => _t('éteindre', 'Off');
  String get gridLarge => _t('grand', 'Large');
  String get gridMedium => _t('centre', 'Medium');
  String get gridSmall => _t('petit', 'Small');
  String get seatsLabel => _t('siège', 'Seats');
  String get unsavedLayoutTitle =>
      _t('Je n\'ai pas encore enregistré le graphique.', 'Layout not saved');
  String get unsavedLayoutBody => _t(
    'L\'emplacement de la table déplacé sera perdu si vous quittez maintenant.',
    'Table positions you moved will be lost if you leave now.',
  );
  String get discard => _t('Quitter sans sauvegarder', 'Discard');
  String get tapTableToEditHint => _t(
    'Appuyez sur le tableau pour sélectionner · Faites glisser pour vous déplacer.',
    'Tap a table to select · drag to move',
  );

  // floor plan editor — structural objects (pool / bar front / pillar)
  String get addObject => _t('Ajouter un objet', 'Add object');
  String get objectPool => _t('Table de billard', 'Pool table');
  String get objectBarFront => _t('comptoir de bar', 'Bar front');
  String get objectPillar => _t('pôle', 'Pillar');
  String get deleteObject => _t('Supprimer des objets', 'Remove object');
  String deleteObjectConfirm(String name) => _t(
    'Supprimer "$name" de l\'arborescence ?',
    'Remove "$name" from the plan?',
  );
  String get objectDeleted => _t('Objet supprimé', 'Object removed');

  // check screen
  String get table => _t('tableau', 'Table');
  String get bill => _t('facture', 'Bill');
  String get corkage => _t('Frais de bouchon de bouteille', 'Corkage');
  String get voidBillManager =>
      _t('Annuler la facture (gestionnaire)', 'Void bill (manager)');
  String get noItemsYet => _t(
    'Il n\'y a pas encore d\'éléments — appuyez sur le menu pour les ajouter.',
    'No items yet — tap the menu to add',
  );
  String pendingFromPhone(int n) => _t(
    'Commande mobile — en attente de confirmation ($n)',
    'Ordered from phone — awaiting confirm ($n)',
  );
  String get acceptOrder => _t('Prendre les commandes', 'Accept');
  String get rejectOrder => _t('refuser', 'Reject');
  String get deleteLine => _t('Supprimer l\'élément', 'Remove');
  String get emptyBillClosed =>
      _t('La facture gratuite a été clôturée.', 'Empty bill closed');
  String get total => _t('Total', 'Total');
  String get pay => _t('Effectuer le paiement', 'Pay');
  String get ordersAwaiting => _t(
    'Il y a une commande en attente de confirmation.',
    'Orders awaiting confirm',
  );
  String sizesFrom(int n, String price) =>
      _t('$n formats $price+', '$n sizes $price+');
  String get qty => _t('quantité', 'Qty');
  String get noteHint =>
      _t('Notes (par exemple pas de glace)', 'Note (e.g. no ice)');
  String addToBill(String price) =>
      _t('Ajouter à la facture $price', 'Add to bill $price');

  // void flow
  String get voidApprovalTitle => _t(
    'Annuler la facture : le responsable approuve.',
    'Void bill — manager approval',
  );
  String get voidReasonTitle => _t('Motif d\'annulation', 'Void reason');
  List<String> get voidReasons => en
      ? const [
          'Wrong table',
          'Customer changed mind',
          'Wrong price',
          'System test',
        ]
      : const [
          'J\'ai commandé la mauvaise table',
          'Le client a changé d\'avis.',
          'Prix ​​mal calculé',
          'Tester le système',
        ];
  String get otherReason => _t('Autres raisons', 'Other reason');
  String voidedBill(int id) => _t('Facture #$id annulée.', 'Bill #$id voided');

  // corkage dialog
  String get corkageTitle =>
      _t('Frais d\'ouverture de bouteille (Bouchage)', 'Corkage');
  String get bottlesBrought => _t(
    'Nombre de bouteilles que les clients apportent',
    'Bottles brought by customer',
  );

  // tender screen
  String get outstanding => _t('en retard', 'Due');
  String paidOf(String paid, String total) =>
      _t('Payé $paid / $total', 'Paid $paid / $total');
  String get cash => _t('espèces', 'Cash');
  String get card => _t('Carte', 'Card');
  String get bankTransfer => _t('transfert', 'Transfer');
  String get cashInHint => _t(
    'Argent reçu (CAD) — certains ont reçu',
    'Cash received (CAD) — partial OK',
  );
  String get receive => _t('obtenir de l\'argent', 'Receive');
  String amountHint(String due) => _t(
    'Montant (CAD) — vide = totalité de $due',
    'Amount (CAD) — blank = full $due',
  );
  String get useCardTerminal =>
      _t('Utiliser le terminal de carte', 'Use card terminal');
  String get showBankAccount =>
      _t('Afficher les informations du compte', 'Show account details');
  String amountToPay(String amount) =>
      _t('Montant à payer $amount', 'Amount due $amount');
  String get confirmMoneyIn =>
      _t('Confirmé — L\'argent est arrivé.', 'Confirm — money received');
  String get cashReceivedTitle => _t('Espèces reçues ✓', 'Cash received ✓');
  String get cashReceived => _t('Obtenez de l\'argent', 'Cash received');
  String get rounding => _t('rond', 'Rounding');
  String get change => _t('changement', 'Change');
  String receivedToast(String amount, String due) =>
      _t('$amount reçu — $due à payer', 'Received $amount — $due outstanding');

  // table ops: move / merge
  String get moveMerge => _t('Déplacer/fusionner des factures', 'Move / merge');
  String moveMergeTitle(int billId) => _t(
    'Déplacer la facture #$billId vers la table…',
    'Move Bill #$billId to…',
  );
  String get thisBill => _t('Ce projet de loi', 'This bill');
  String get beingPaid => _t('Paiement en cours', 'Being paid');
  String get mergeConfirmTitle => _t('Facture totale', 'Merge bills');
  String mergeConfirmBody(int src, int dest, String destLabel) => _t(
    'Combiner la facture #$src avec la facture #$dest (table $destLabel) ? Tous les articles seront transférés sur la facture de destination.',
    'Merge Bill #$src into Bill #$dest (table $destLabel)? All items move to the destination bill.',
  );
  String get merge => _t('Facture totale', 'Merge');
  String movedToast(String label) =>
      _t('Déplacé vers la table $label.', 'Moved to table $label');
  String mergedToast(int dest) =>
      _t('Inclus dans la facture #$dest', 'Merged into Bill #$dest');

  // open / misc item
  String get openItem => _t('Articles spéciaux', 'Open item');
  String get openItemName => _t('Nom de l\'article', 'Item name');
  String get openItemPrice => _t('Prix ​​(CAD)', 'Price (CAD)');

  // split checks (settlement-time bill groups)
  String get splitBill => _t('Divisez la facture', 'Split bill');
  String get splitByItems => _t('Séparé par article', 'By item');
  String get splitEvenly => _t('Division égale', 'Split evenly');
  String get splitHowManyWays =>
      _t('Diviser par combien de parties ?', 'Split how many ways?');
  String groupTitle(int n) => _t('facture $n', 'Bill $n');
  String get addGroup => _t('Ajouter une facture', 'Add bill');
  String get unassignedItems => _t('Pas encore séparé', 'Unassigned');
  String get allItemsAssigned =>
      _t('Tous les éléments ont été séparés.', 'All items assigned');
  String get tapToAssignHint => _t(
    'Appuyez sur un élément pour accéder à la facture sélectionnée.',
    'Tap an item to move it to the selected bill',
  );
  String get paid => _t('Payant ✓', 'Paid ✓');
  String get clearSplit =>
      _t('Annuler le fractionnement des factures', 'Clear split');
  String get clearSplitConfirm => _t(
    'Regrouper tous les articles en une seule facture ?',
    'Merge everything back into one bill?',
  );
  String groupPaidToast(int n) => _t('Facture $n payée', 'Bill $n paid');
  String get moveCorkageTitle =>
      _t('Déplacez le droit de bouchon sur la facture...', 'Move corkage to…');
  String get deleteGroup => _t('Supprimer cette facture', 'Remove this bill');
  String get peelOne => _t('1 à la fois', '1 only');

  // receipt
  String get receipt => _t('reçu', 'Receipt');

  // provisional bill ("check please")
  String get printBill => _t('Vérifiez la facture', 'Print bill');
  String get customerBill => _t('Déclaration', 'Customer bill');
  String get notAReceipt => _t('Pas un reçu', 'Not a receipt');
  String get printedAt => _t('Tapez quand', 'Printed at');
  String get printAgain => _t('Tapez à nouveau', 'Print again');

  // shift screen
  String get noShiftOpen =>
      _t('Je n\'ai pas encore ouvert le poste.', 'No shift open');
  String get openingFloat =>
      _t('Changement initial (CAD)', 'Opening float (CAD)');
  String get openShift => _t('Poste ouvert', 'Open shift');
  String get openShiftApproval => _t(
    'Quart de travail ouvert — Le gestionnaire approuve',
    'Open shift — manager approval',
  );
  String get closeShiftApproval => _t(
    'Fermer l\'équipe (Z) – Le responsable approuve.',
    'Close shift (Z) — manager approval',
  );
  String shiftOpenTitle(int id) =>
      _t('shift #$id — ouvert', 'Shift #$id — open');
  String shiftOpenedLine(String at, String by, String float) => _t(
    'Ouvert le $at par $by • $float initial',
    'Opened $at by $by • float $float',
  );
  String get closeShiftZ =>
      _t('Equipe de fermeture (rapport Z)', 'Close shift (Z-Report)');
  String get countedCash =>
      _t('Peut compter l\'argent liquide (CAD)', 'Counted cash (CAD)');
  String get closeShift => _t('Fermer l\'équipe', 'Close shift');
  String get zReportDone =>
      _t('Equipe fermée — Rapport Z', 'Shift closed — Z-Report');
  String get revenue => _t('Ventes', 'Revenue');
  String get billCount => _t('Nombre de factures', 'Bills');
  String get avgPerBill => _t('Moyenne par facture', 'Avg per bill');
  String get byTender => _t('séparé par canal', 'By tender');
  String get topItems => _t('Menu le plus vendu', 'Top items');
  String get voidedBills => _t('facture annulée', 'Voided bills');
  String get expectedCash =>
      _t('L\'argent liquide que vous devriez avoir', 'Expected cash');
  String get countedActual => _t('Peut vraiment compter', 'Counted');
  String get overShort => _t('manque/excès', 'Over/short');
  String get xReport => _t('X-Report', 'X-Report');
  // shift reconciliation: cash movements + refunds
  String get paidInOut => _t('Entrée/sortie d\'argent', 'Paid in / out');
  String get cashRefunds => _t('remise en argent', 'Cash refunds');

  // refunds — return money on a finalized (CLOSED) bill
  String get refunds => _t('Remboursement', 'Refunds');
  String get salesTitle => _t('Facture fermée', 'Closed bills');
  String get refundTitle => _t('Rembourser la facture', 'Refund bill');
  String get refundApprovalTitle => _t(
    'Remboursement — Le gestionnaire approuve',
    'Refund — manager approval',
  );
  String get refundReasonTitle =>
      _t('Raison du remboursement', 'Refund reason');
  List<String> get refundReasons => en
      ? const [
          'Wrong item',
          'Customer changed mind',
          'Quality issue',
          'Overcharged',
        ]
      : const [
          'Mauvais produit',
          'Le client a changé d\'avis.',
          'Il y a un problème avec le produit.',
          'Surchargé',
        ];
  String get refundFull => _t('Remboursement intégral', 'Full refund');
  String get refundByLine => _t('Sélectionner un article', 'By item');
  String get refundByAmount => _t('Précisez le montant', 'By amount');
  String get refundAmountLabel =>
      _t('Montant du remboursement (CAD)', 'Refund amount (CAD)');
  String get refundTender => _t('renvoyé par', 'Refund via');
  String get refundableLabel =>
      _t('Vous pouvez le retourner à nouveau.', 'Refundable');
  String get refundedLabel => _t('Il a été restitué.', 'Refunded');
  String get fullyRefunded =>
      _t('L\'argent a été entièrement remboursé.', 'Fully refunded');
  String get confirmRefund =>
      _t('Confirmer le remboursement', 'Confirm refund');
  String refundDone(String amount) =>
      _t('$amount remboursé', 'Refunded $amount');
  String get noClosedBills =>
      _t('Il n’y a pas encore de facture close.', 'No closed bills yet');
  String get refundHistory =>
      _t('Historique des remboursements', 'Refund history');
  String get pickLinesHint => _t(
    'Appuyez sur l\'article que vous souhaitez retourner.',
    'Tap items to refund',
  );
  String get refundSlipTitle => _t('Bon de remboursement', 'Refund slip');
  String get vatReversed => _t('TVA restituée', 'VAT reversed');
  String get amountExceedsRefundable => _t(
    'dépassant le montant pouvant être restitué',
    'Exceeds the refundable amount',
  );
  String get pickAmountOrLines => _t(
    'Sélectionnez un article ou spécifiez un montant.',
    'Pick items or enter an amount',
  );

  // till — non-sale cash in / out
  String get till => _t('tiroir-caisse', 'Till');
  String get cashIn => _t('Argent entrant', 'Cash in');
  String get cashOut => _t('Argent sorti', 'Cash out');
  String get cashInApproval => _t(
    'Argent entrant — Le gestionnaire approuve',
    'Cash in — manager approval',
  );
  String get cashOutApproval => _t(
    'Dépôt d\'argent — Le gestionnaire approuve',
    'Cash out — manager approval',
  );
  String get cashAmountLabel => _t('Montant (CAD)', 'Amount (CAD)');
  String get cashReasonLabel => _t('raison', 'Reason');
  String get cashReasonRequired =>
      _t('La raison doit être précisée', 'A reason is required');
  String cashInDone(String amount) =>
      _t('Entrée de caisse de $amount enregistrée', 'Cash in $amount recorded');
  String cashOutDone(String amount) => _t(
    'Sortie de caisse de $amount enregistrée',
    'Cash out $amount recorded',
  );
  String get cashMovementsLabel =>
      _t('Entrée/sortie d\'argent (tiroir)', 'Cash movements');
  String get noCashMovements => _t(
    'Il n’y a pas encore d’entrée/sortie d’argent.',
    'No cash movements yet',
  );
  String get needOpenShiftForTill => _t(
    'Vous devez ouvrir une équipe avant de pouvoir enregistrer les entrées/sorties d\'argent.',
    'Open a shift before recording cash movements',
  );

  // report ranges
  String get rangeThisShift => _t('Ce changement', 'This shift');
  String get rangeToday => _t('aujourd\'hui', 'Today');
  String get rangeYesterday => _t('hier', 'Yesterday');
  String get rangeThisWeek => _t('Cette semaine', 'This week');
  String get rangeCustom => _t('Coutume…', 'Custom…');
  String rangeTitle(String from, String to) => from == to
      ? _t('Ventes du $from', 'Sales for $from')
      : _t('Ventes du $from au $to', 'Sales $from – $to');

  // menu management
  String get manageMenuTitle => _t(
    'Menu Gérer – ouvrir/clôturer des ventes',
    'Manage menu — on/off sale',
  );
  String get onSale => _t('Ouvert à la vente', 'On sale');
  String get offSale => _t('Fermé à la vente (86)', 'Off sale (86)');
  String enableSale(String name) =>
      _t('Mettre $name en vente', 'Put $name on sale');
  String disableSale(String name) =>
      _t('Retirer $name de la vente (86)', 'Take $name off sale (86)');

  // menu editing (owner catalog)
  String get addItem => _t('ajouter un menu', 'Add item');
  String get editItem => _t('Menu Modifier', 'Edit item');
  String get deleteItem => _t('Supprimer le menu', 'Delete item');
  String deleteItemConfirm(String name) => _t(
    'Supprimer « $name » du menu ? Les anciennes factures ne disparaîtront pas. Mais ce menu disparaîtra de tous les écrans.',
    'Remove "$name" from the menu? Old bills keep it; it disappears from every screen.',
  );
  String get itemDeleted => _t('Menu supprimé', 'Item removed');
  String get nameFrLabel => _t('Nom (français)', 'Name (French)');
  String get nameEnLabel => _t('Nom (anglais)', 'Name (English)');
  String get categoryLabel => _t('Catégorie', 'Category');
  String get abbrevLabel =>
      _t('Abréviations (1 à 4 caractères)', 'Tile badge (1–4 chars)');
  String get isAlcoholLabel => _t('alcool', 'Alcohol');
  String get activeLabel => _t('Ouvert à la vente', 'On sale');
  String get sizesLabel => _t('Taille/Prix', 'Sizes / prices');
  String get addSize => _t('augmenter la taille', 'Add size');
  String get sizeLabelFr => _t('Format (français)', 'Size (French)');
  String get sizeLabelEn => _t('Taille (anglais)', 'Size (English)');
  String get priceCAD => _t('Prix ​​(CAD)', 'Price (CAD)');
  String get fillAllFields =>
      _t('Informations complètes', 'Fill in all fields');
  String get editCategories => _t('Gérer les catégories', 'Edit categories');
  String get addCategory => _t('Ajouter une catégorie', 'Add category');
  String get deleteCategory => _t('Supprimer la catégorie', 'Delete category');
  String get dragToReorder =>
      _t('Faites glisser pour réorganiser', 'Drag to reorder');
  String get saved => _t('Enregistré', 'Saved');

  // slips
  String get printSlips =>
      _t('Imprimez des panneaux QR sur chaque table.', 'Print all table slips');

  // photos
  String get uploadPhoto => _t('Télécharger une photo', 'Upload photo');
  String uploadPhotoFor(String name) =>
      _t('Télécharger l\'image de $name', 'Upload photo for $name');
  String get photoUploaded => _t('Photo téléchargée', 'Photo uploaded');

  // pin pad / manager approval
  String get managerPinTitle =>
      _t('Le responsable saisit le code PIN', 'Manager: enter PIN');
  String get managerApproval =>
      _t('Le gestionnaire approuve', 'Manager approval');

  // change PIN
  String get changePin => _t('Changer le code PIN', 'Change PIN');
  String get currentPin => _t('Code PIN actuel', 'Current PIN');
  String get newPin => _t('Nouveau code PIN', 'New PIN');
  String get confirmNewPin =>
      _t('Confirmer le nouveau code PIN', 'Confirm new PIN');
  String get pinChanged => _t('Code PIN modifié', 'PIN changed');
  String get pinMismatch =>
      _t('Le nouveau code PIN ne correspond pas.', 'New PINs do not match');

  // staff administration (the tablet owns its staff; the portal only shows them)
  String get staffTitle => _t('Personnel', 'Staff');
  String get staffAdd => _t('Ajouter un membre', 'Add staff');
  String get staffEdit => _t('Modifier le membre', 'Edit staff');
  String get staffName => _t('Nom', 'Name');
  String get staffRole => _t('Poste', 'Role');
  String get roleManager => _t('Gérant', 'Manager');
  String get roleServer => _t('Serveur', 'Server');
  String get staffActive => _t('Actif', 'Active');
  String get staffInactive => _t('Désactivé', 'Inactive');
  String get staffPin4 => _t('NIP (4 chiffres)', 'PIN (4 digits)');
  String get staffResetPin => _t('Nouveau NIP', 'Reset PIN');
  String get staffPinInvalid =>
      _t('Le NIP doit comporter 4 chiffres.', 'PIN must be 4 digits');
  String get staffNameRequired => _t('Entrez un nom.', 'A name is required');
  String get staffDelete => _t('Supprimer', 'Delete');
  String staffDeleteConfirm(String name) => _t(
    'Supprimer $name ? L’historique des ventes est conservé.',
    'Delete $name? Sales history is kept.',
  );
  String get staffSaved => _t('Enregistré', 'Saved');
  String get staffSyncHint => _t(
    'Les changements s’appliquent tout de suite sur cette tablette et sont envoyés au portail à la prochaine connexion.',
    'Changes apply on this tablet at once and are sent to the owner portal when it next connects.',
  );

  // settings
  String get settings => _t('Créer une boutique', 'Venue settings');
  String get sectionPayments => _t('Paiement', 'Payments');
  String get sectionFees => _t('frais', 'Fees');
  String get sectionReceipt => _t('reçu', 'Receipt');
  String get sectionSecurity => _t('sécurité', 'Security');
  String get sessionIdleLabel => _t(
    'Déconnexion automatique lorsqu\'il n\'est pas utilisé (minutes)',
    'Auto-logout when idle (minutes)',
  );
  String get cardProcessorLabel =>
      _t('Nom du terminal de carte', 'Card terminal label');
  String get bankNameLabel => _t('banque', 'Bank');
  String get bankAccountNumberLabel => _t('Numéro de compte', 'Account number');
  String get bankAccountNameLabel => _t('Nom du compte', 'Account holder name');
  String get serviceChargeLabel =>
      _t('Frais de service (%) — 0 = Fermer', 'Service charge (%) — 0 = off');
  String get corkageRateLabel => _t(
    'Frais d\'ouverture de bouteille (CAD/bouteille) — 0 = fermeture',
    'Corkage (CAD/bottle) — 0 = off',
  );
  String get receiptFooterLabel =>
      _t('Message à la fin du reçu', 'Receipt footer text');
  String get venuePhoneLabel =>
      _t('Numéro de téléphone de la boutique', 'Venue phone');
  String get venueAddressLabel => _t('Adresse du magasin', 'Venue address');
  String get save => _t('enregistrer', 'Save');
  String get savedTakesEffectNext => _t(
    'Enregistré – en vigueur sur la prochaine facture.',
    'Saved — applies from the next bill',
  );

  // store server URL (device-local; auto-discovered on the LAN)
  String get sectionServer =>
      _t('Connexion du restaurant', 'Restaurant connection');
  String get serverUrlLabel =>
      _t('Adresse de connexion avancée', 'Advanced connection address');
  String get scanForServer =>
      _t('Trouver le restaurant sur le Wi-Fi', 'Find restaurant on Wi-Fi');
  String get scanningForServer =>
      _t('Recherche du restaurant…', 'Finding restaurant…');
  String get connectingToServer =>
      _t('Recherche du restaurant…', 'Finding your restaurant…');
  String get findingRestaurant =>
      _t('Recherche du restaurant…', 'Finding your restaurant…');
  String get startingThisTablet =>
      _t('Démarrage de cette tablette…', 'Starting this tablet…');
  String get tabletStoreFailed => _t(
    'Le service local de cette tablette n’a pas démarré.',
    'This tablet’s local store did not start.',
  );
  String get staffAppNeedsWifi => _t(
    'Connectez la tablette au Wi-Fi pour afficher le code du personnel.',
    'Connect this tablet to Wi-Fi to show the staff app code.',
  );
  String get restaurantUnavailable => _t(
    'Restaurant indisponible sur ce réseau Wi-Fi.',
    'Restaurant is not available on this Wi-Fi',
  );
  String get connectionHelp => _t('Aide de connexion', 'Connection help');
  String serverFoundAt(String url) =>
      _t('Restaurant trouvé', 'Restaurant found');
  String get serverNotFound => _t(
    'Restaurant introuvable sur ce Wi-Fi.',
    'Restaurant not found on this Wi-Fi',
  );
  String currentlyUsing(String url) =>
      _t('Utilise actuellement : $url', 'Currently using: $url');
  String get serverSavedRestart => _t(
    'Enregistré : redémarrez l\'application pour l\'utiliser.',
    'Saved — restart the app to apply',
  );
  String get setServerUrl => _t('Connexion avancée', 'Advanced connection');
  String get restaurantConnectionReady =>
      _t('Restaurant disponible', 'Restaurant available');
  String get advancedConnection =>
      _t('Dépannage avancé', 'Advanced troubleshooting');

  // terminal pairing (cloud venues)
  String get pairTerminalTitle =>
      _t('Associez cette machine au magasin.', 'Pair this terminal');
  String get pairTerminalIntro => _t(
    'Générez un code d\'appariement à partir du portail du propriétaire du magasin. alors remplis ici',
    'Mint a pairing code in the owner portal, then enter it here.',
  );
  String get pairVenueAddressLabel =>
      _t('Adresse du serveur de stockage', 'Venue address');
  String get pairingCodeLabel => _t('Code d\'appariement', 'Pairing code');
  String get deviceNameLabel =>
      _t('Nom de l\'appareil (par exemple, barre)', 'Device name (e.g. Bar)');
  String get pairAction => _t('paire', 'Pair');
  String get enterVenueAddress =>
      _t('Entrez l\'adresse du serveur du magasin.', 'Enter the venue address');
  String get enterPairingCode =>
      _t('Entrez le code d\'appairage.', 'Enter the pairing code');
  String get pairNetworkError => _t(
    'Impossible de se connecter au magasin – vérifiez l\'adresse et le réseau',
    'Cannot reach the venue — check the address and your network',
  );

  // global reconnecting overlay
  String get reconnectingToServer =>
      _t('Reconnexion au restaurant…', 'Reconnecting to your restaurant…');
  String get reconnectingToRestaurant =>
      _t('Reconnexion au restaurant…', 'Reconnecting to your restaurant…');
  String get reconnectChangeServer =>
      _t('Aide de connexion', 'Connection help');

  // on-screen QR codes: table scan-to-order (long-press a table) + staff app
  String tableQrTitle(String label) => _t(
    'Scannez pour commander de la nourriture · $label',
    'Scan to order · $label',
  );
  String get tableQrHint => _t(
    'Les clients scannent avec leur téléphone mobile (même Wi-Fi que le magasin)',
    'Guests scan with a phone on the venue Wi-Fi',
  );
  String get tableQrNeedsWifi => _t(
    'Connectez la tablette au Wi-Fi pour afficher un code QR utilisable.',
    'Connect the tablet to Wi-Fi to show a scannable QR code.',
  );
  String get showQrCode => _t('Afficher le code QR', 'Show QR code');
  String get printQrCode => _t('Imprimer le code QR', 'Print QR code');
  String get regenerateTableLink =>
      _t('Régénérer le lien de la table', 'Regenerate table link');
  String regenerateTableLinkConfirm(String label) => _t(
    'Créer un nouveau lien pour $label ? Les codes QR déjà imprimés pour cette table cesseront de fonctionner; réimprimez sa fiche.',
    'Create a new link for $label? QR slips already printed for this table stop working; reprint its slip.',
  );
  String get regenerate => _t('Régénérer', 'Regenerate');
  String get tableLinkRegenerated => _t(
    'Nouveau lien créé. Réimprimez la fiche QR de cette table.',
    'New link created. Reprint this table\'s QR slip.',
  );
  String get slipSentToPrinter => _t(
    'L\'étiquette QR a été envoyée à l\'imprimeur.',
    'QR slip sent to the printer',
  );
  String get sectionStaffApp =>
      _t('Application pour les employés', 'Staff app');
  String get staffAppQrLabel => _t(
    'Application de commande de nourriture pour les employés : scannez pour ouvrir',
    'Staff ordering app — scan to open',
  );
  String get sectionReportsPortal => _t('rapport', 'Reports');
  String get reportsPortalQrLabel => _t(
    'Portail de rapport sur les propriétaires de boutique – Scanner pour ouvrir',
    'Owner reporting portal — scan to open',
  );
  String get cloudNotConfigured => _t(
    'Le système cloud n\'a pas encore été configuré.',
    'Cloud not configured',
  );

  // pending-order alerts (settings + banner)
  String get sectionAlerts =>
      _t('Notification des commandes clients', 'Customer order alerts');
  String get alertsEnabledLabel => _t(
    'Alerte sonore lorsqu\'il y a une nouvelle commande d\'un client',
    'Chime on new customer orders',
  );
  String get alertEscalateLabel => _t(
    'Avertissement urgent si non traité dans les (secondes)',
    'Escalate if un-actioned after (seconds)',
  );
  String get alertVolumeLabel => _t('Volume d\'alerte', 'Alert volume');

  // receipt printer (settings)
  String get sectionPrinter => _t('imprimante de reçus', 'Receipt printer');
  String get printerIpLabel =>
      _t('IP de l\'imprimante (vide = éteint)', 'Printer IP (blank = off)');
  String get printerPortLabel => _t('port', 'Port');
  String get scanForPrinter => _t(
    'Rechercher des imprimantes sur le réseau',
    'Scan network for printer',
  );
  String get scanningForPrinter =>
      _t('À la recherche d\'une imprimante...', 'Scanning for printer…');
  String printerFoundAt(String ip) =>
      _t('Imprimante trouvée : $ip', 'Found printer: $ip');
  String printersFound(int n, String ip) => _t(
    '$n machines trouvées — utilisez $ip',
    'Found $n printers — using $ip',
  );
  String get printerNotFound => _t(
    'L\'imprimante n\'a pas été trouvée sur ce réseau.',
    'No printer found on this network',
  );
  String get testPrint => _t('Test d\'impression', 'Test print');
  String get printerTestSent => _t(
    'La page de test a été envoyée à l\'imprimeur.',
    'Test page sent to the printer',
  );
  String get printerNotConfigured => _t(
    'L\'adresse IP de l\'imprimante n\'a pas été définie.',
    'Printer IP not set',
  );
  String get printerOffline => _t(
    'Échec de l\'impression : l\'imprimante est hors ligne.',
    'Print failed — printer offline',
  );
  String get printAllTableQr =>
      _t('Imprimez un code QR à chaque table.', 'Print all table QR codes');
  String printAllTableQrConfirm(int n) => _t(
    'Imprimer l\'étiquette QR pour la table $n ? Utiliser beaucoup de papier',
    'Print QR slips for all $n tables? This uses a lot of paper.',
  );
  String slipsPrinted(int n) => _t('Étiquette $n saisie', 'Printed $n slips');

  // guest Wi-Fi (settings): the join slip and step 1 of the table slips
  String get sectionGuestWifi => _t('Wi-Fi invités', 'Guest Wi-Fi');
  String get guestWifiHint => _t(
    'Imprimé sur une fiche Wi-Fi et en première étape des fiches QR des tables.',
    'Printed on a Wi-Fi slip and as step 1 on the table QR slips.',
  );
  String get wifiSsidLabel =>
      _t('Nom du réseau (vide = aucun)', 'Network name (blank = none)');
  String get wifiPasswordLabel => _t('Mot de passe', 'Password');
  String get wifiShowPassword =>
      _t('Afficher le mot de passe', 'Show password');
  String get wifiHidePassword => _t('Masquer le mot de passe', 'Hide password');
  String get wifiSecurityLabel => _t('Sécurité', 'Security');
  String get wifiSecurityNone => _t('Aucune (réseau ouvert)', 'None (open)');
  String get wifiHiddenLabel => _t('Réseau masqué', 'Hidden network');
  String get printWifiSlip => _t('Imprimer la fiche Wi-Fi', 'Print Wi-Fi slip');
  String get wifiCopiesLabel => _t('Copies', 'Copies');
  String wifiSlipsPrinted(int n) => _t(
    n == 1 ? 'Fiche Wi-Fi imprimée' : '$n fiches Wi-Fi imprimées',
    n == 1 ? 'Wi-Fi slip printed' : 'Printed $n Wi-Fi slips',
  );
  String get wifiNotConfigured => _t(
    'Entrez d\'abord le nom et le mot de passe du réseau Wi-Fi.',
    'Set the Wi-Fi network name and password first',
  );
  String get saveFirstToTest => _t(
    'Enregistrez-le avant de pouvoir tester l\'impression.',
    'Save first, then test print',
  );
  String ordersWaiting(int n) => _t(
    '$n Commande en attente de confirmation',
    n == 1 ? '1 order waiting' : '$n orders waiting',
  );

  /// Compact age for the alert banner: seconds under a minute, else minutes.
  String alertAge(Duration d) =>
      d.inMinutes < 1 ? '${d.inSeconds}s' : elapsedShort(d);

  // zone open/closed
  String get zoneClosed => _t('éteindre', 'Closed');
  String get zoneClosedBanner => _t(
    'Cette zone est temporairement fermée. Veuillez contacter le personnel.',
    'This section is temporarily closed. Please ask staff.',
  );
  String get zoneCloseAction => _t('Zone fermée', 'Close zone');
  String get zoneReopenAction => _t('zone ouverte', 'Reopen zone');
  String get newCheckBlockedZoneClosed =>
      _t('Cette zone est fermée.', 'Zone is closed');

  // zone (room) management — add / rename / delete / reorder
  String get addRoom => _t('Ajouter une zone', 'Add room');
  String get manageRoom => _t('Gérer les zones', 'Manage room');
  String get renameRoom => _t('Changer le nom de la zone', 'Rename room');
  String get deleteRoom => _t('Supprimer une zone', 'Delete room');
  String deleteRoomConfirm(String name) => _t(
    'Supprimer la zone « $name » ? Une zone doit être vide (pas de tables ni d\'objets) pour être supprimée.',
    'Delete room "$name"? A room must be empty (no tables or objects) to delete.',
  );
  String get roomDeleted => _t('Zone supprimée', 'Room deleted');
  String get roomCreated => _t('Zone créée', 'Room created');
  String get moveRoomLeft => _t('se déplacer vers la gauche', 'Move left');
  String get moveRoomRight => _t('aller à droite', 'Move right');
  String get roomNameFrField =>
      _t('Nom de la zone (français)', 'Room name (French)');
  String get roomNameEnField =>
      _t('Nom de la zone (anglais)', 'Room name (English)');

  // tables / time
  String elapsedShort(Duration d) => d.inHours > 0
      ? '${d.inHours}h ${(d.inMinutes % 60).toString().padLeft(2, '0')}m'
      : '${d.inMinutes}m';

  /// Server error codes → local language. Fallback: raw server message.
  String? apiError(String? code) => switch (code) {
    'invalid_pin' => _t('Code PIN invalide', 'Invalid PIN'),
    'wifi_not_configured' => wifiNotConfigured,
    'pin_in_use' => _t(
      'Ce NIP est déjà utilisé par un autre membre du personnel.',
      'That PIN is already used by another staff member',
    ),
    'bad_pin' => _t(
      'Le NIP doit comporter 4 chiffres.',
      'PIN must be 4 digits',
    ),
    'last_manager' => _t(
      'Gardez au moins un gérant actif pouvant gérer le personnel.',
      'Keep at least one active manager who can manage staff',
    ),
    'login_required' => _t('Veuillez vous reconnecter.', 'Please log in again'),
    'manager_approval_required' => _t(
      'Doit être approuvé par le gestionnaire',
      'Manager approval required',
    ),
    'check_not_open' => _t(
      'Ce projet de loi est désormais clos.',
      'This bill is no longer open',
    ),
    'zone_closed' => _t('Cette zone est fermée.', 'Zone is closed'),
    'bill_locked' => _t(
      'La facture est en cours de paiement – ​​Contactez le personnel.',
      'Bill is being paid — ask staff',
    ),
    'already_paid' => _t(
      'Cette facture a été entièrement payée.',
      'Bill already fully paid',
    ),
    'pending_lines_unresolved' => _t(
      'Il y a une commande d’un client en attente de confirmation – prenez-en soin avant de collecter de l’argent.',
      'Customer orders awaiting confirm — resolve before payment',
    ),
    'outstanding_balance' => _t(
      'Pas encore entièrement payé',
      'Balance still outstanding',
    ),
    'void_has_tenders' => _t(
      'Cette facture a été reçue et ne peut pas être annulée ; un remboursement sera utilisé à la place.',
      'Bill has payments; void not allowed — refund instead',
    ),
    'refund_not_closed' => _t(
      'Les remboursements ne peuvent être effectués que pour les factures déjà clôturées.',
      'Only a closed bill can be refunded',
    ),
    'refund_exceeds_total' => _t(
      'Remboursement au-delà du solde restant',
      'Refund exceeds the remaining refundable amount',
    ),
    'refund_non_positive' => _t(
      'Le montant du remboursement doit être supérieur à 0.',
      'Refund amount must be positive',
    ),
    'refund_no_amount' => _t(
      'Saisissez le montant ou sélectionnez un article.',
      'Enter an amount or pick items',
    ),
    'refund_bad_tender' => _t(
      'La méthode de remboursement est incorrecte.',
      'Invalid refund tender',
    ),
    'refund_bad_line' => _t(
      'L\'élément sélectionné n\'est pas valide.',
      'Selected item is not on this bill',
    ),
    'refund_qty_too_high' => _t(
      'Le montant restitué dépasse le montant figurant sur la facture.',
      'Refund quantity exceeds the bill',
    ),
    'cash_bad_direction' => _t('Mauvaise direction', 'Invalid direction'),
    'cash_non_positive' => _t(
      'Le montant doit être supérieur à 0.',
      'Amount must be positive',
    ),
    'no_open_shift' => _t(
      'Je n\'ai pas encore ouvert le poste.',
      'No shift open',
    ),
    'shift_already_open' => _t(
      'Vous avez déjà un quart de travail ouvert',
      'A shift is already open',
    ),
    'tender_type_not_accepted' => _t(
      'Ce mode de paiement n\'est pas accepté.',
      'Payment method not accepted',
    ),
    'no_receipt_yet' => _t('Toujours pas de reçu', 'No receipt yet'),
    'check_not_billable' => _t(
      'Cette facture est fermée — Impossible d\'imprimer le relevé.',
      'This bill is closed — cannot print',
    ),
    'bad_pairing_code' => _t(
      'Le code d\'appairage est invalide ou a expiré.',
      'Invalid or expired pairing code',
    ),
    'pairing_unavailable' => _t(
      'Le système de mise en relation est temporairement indisponible – réessayez.',
      'Pairing is temporarily unavailable — try again',
    ),
    'cloud_unreachable' => _t(
      'La boutique ne parvient pas à se connecter au cloud : réessayez plus tard.',
      'The store cannot reach the cloud — try again shortly',
    ),
    'device_required' => _t(
      'Cet appareil n\'a pas encore été associé au magasin.',
      'This terminal is not paired with the store',
    ),
    'device_revoked' => _t(
      'Le jumelage de cette unité a été annulé. — Associez à nouveau.',
      'This terminal\'s pairing was revoked — pair again',
    ),
    'not_found' => _t('Aucune information trouvée', 'Not found'),
    'bad_request' => _t('Informations incorrectes', 'Invalid request'),
    'rate_limited' => _t(
      'Vous avez saisi le mauvais code PIN à plusieurs reprises. Réessayez plus tard.',
      'Too many failed attempts — try again later',
    ),
    'variant_in_use' => _t(
      'Cette taille figure sur une facture ouverte : fermez ou annulez la facture avant de pouvoir la supprimer.',
      'This size is on an open order — close or void that check first',
    ),
    'item_in_use' => _t(
      'Ce produit figure sur une facture ouverte : fermez ou annulez la facture avant de pouvoir la supprimer.',
      'This item is on an open order — close or void that check first',
    ),
    'last_variant' => _t(
      'Il s\'agit de la seule taille pour ce produit ; supprimez plutôt l\'intégralité du produit.',
      'This is the item\'s only size — delete the whole item instead',
    ),
    'category_not_empty' => _t(
      'Cette catégorie contient encore des produits : déplacez ou supprimez d\'abord les produits de la catégorie.',
      'This category still has items — move or remove them first',
    ),
    'split_locked' => _t(
      'Ce projet de loi a été reçu – la séparation des factures ne peut pas être résolue.',
      'Money already taken — split is locked',
    ),
    'split_exists' => _t(
      'Ce projet de loi est déjà distinct.',
      'Bill is already split',
    ),
    'no_split' => _t(
      'Ce projet de loi n\'a pas encore été séparé.',
      'Bill is not split',
    ),
    'split_unassigned_lines' => _t(
      'Il y a encore des objets qui n’ont pas été séparés – séparez-les complètement avant de collecter de l’argent.',
      'Some items are still unassigned — assign everything before payment',
    ),
    'group_required' => _t(
      'Cette facture est distincte — Choisissez la facture à payer.',
      'Bill is split — pay a specific bill',
    ),
    'group_already_paid' => _t(
      'Cette petite facture a été entièrement payée.',
      'This bill is already paid',
    ),
    'group_not_found' => _t(
      'Cette sous-facture n\'est plus trouvée — Actualiser la répartition de la facture',
      'That split bill no longer exists — refresh the split',
    ),
    'group_outstanding' => _t(
      'Il y a encore des factures mineures en retard.',
      'Some bills still owe',
    ),
    'qty_below_allocated' => _t(
      'Cet élément a été séparé en une sous-facture : supprimez-le d\'abord de la sous-facture.',
      'Item is assigned to a split bill — unassign it first',
    ),
    'qty_exceeds_unassigned' => _t(
      'Montant excédentaire restant',
      'More than the unassigned quantity',
    ),
    'qty_exceeds_allocated' => _t(
      'Le montant dépasse le montant réservé.',
      'More than the assigned quantity',
    ),
    'even_split' => _t(
      'Ce projet de loi est divisé également : les articles ne peuvent pas être déplacés.',
      'Split is even ÷N — items cannot move',
    ),
    'empty_check' => _t(
      'Il n\'y a aucun élément à séparer.',
      'Nothing to split',
    ),
    'last_group' => _t(
      'Il doit rester au moins 1 facture.',
      'At least one bill must remain',
    ),
    'same_table' => _t(
      'Le projet de loi est déjà sur cette table.',
      'Bill is already on this table',
    ),
    'same_check' => _t(
      'Je ne peux pas combiner mes factures avec moi-même',
      'Cannot merge a bill into itself',
    ),
    'table_occupied' => _t(
      'Ce tableau contient des factures ouvertes : utilisez-le plutôt comme une collection de factures.',
      'Table has an open bill — merge instead',
    ),
    'table_in_use' => _t(
      'Ce tableau contient des factures ouvertes : fermez ou déplacez les factures avant de pouvoir les supprimer.',
      'Table has an open bill — close or move it before removing',
    ),
    'has_sub_tables' => _t(
      'Ce tableau comporte des sous-tableaux : supprimez d\'abord les sous-tableaux.',
      'Table has sub-tables — remove them first',
    ),
    'label_taken' => _t(
      'Il existe déjà une table portant ce nom dans la zone.',
      'A table with this label already exists in this zone',
    ),
    'table_not_in_zone' => _t(
      'La disposition ne correspond pas à cette zone : actualisez et réessayez.',
      'Layout does not match this zone — refresh and retry',
    ),
    'zone_not_empty' => _t(
      'Cette zone contient toujours des tables ou des objets : déplacez-les ou supprimez-les avant de pouvoir supprimer la zone.',
      'This room still has tables or objects — clear them before deleting it',
    ),
    'clear_split_first' => _t(
      'Ce projet de loi est une ségrégation – abolissez d’abord la ségrégation.',
      'Bill is split — clear the split first',
    ),
    'conflict' => _t(
      'Impossible d\'effectuer des transactions dans cet état.',
      'Cannot do that right now',
    ),
    'internal' => _t(
      'Erreur système : réessayez.',
      'Something went wrong — try again',
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
          onPressed: () => prefs.setLang(prefs.isEn ? 'fr' : 'en'),
          child: Text(
            prefs.isEn ? 'EN' : 'FR',
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
          onPressed: () => prefs.setLang(prefs.isEn ? 'fr' : 'en'),
          icon: Icon(Icons.language, size: 18, color: fg),
          label: Text(
            prefs.isEn ? 'EN' : 'FR',
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
