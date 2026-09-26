import 'package:flutter/widgets.dart';

import '../i18n.dart';

/// Retail counter strings: English and US Spanish (the Sage & Poppy store's
/// languages), plus French so the screens never show a blank if a French-
/// speaking store ever runs the counter.
class R {
  final String lang;
  const R(this.lang);

  static R of(BuildContext context) => R(L.of(context).lang);

  String _t(String en, String es, String fr) =>
      lang == 'es' ? es : (lang == 'fr' ? fr : en);

  // header
  String get bottleShop => _t('Bottle Shop', 'Tienda de licores', 'Magasin');
  String get register1 => _t('Register 1', 'Caja 1', 'Caisse 1');
  String get scannerReady =>
      _t('Scanner ready', 'Escáner listo', 'Lecteur prêt');
  String get signOut => _t('Sign out', 'Cerrar sesión', 'Déconnexion');
  String get openRegister =>
      _t('Open the register', 'Abrir la caja', 'Ouvrir la caisse');
  String get closeRegister =>
      _t('Close the register', 'Cerrar la caja', 'Fermer la caisse');
  String get registerClosed => _t(
    'The register is closed. Open it to start selling.',
    'La caja está cerrada. Ábrela para empezar a vender.',
    'La caisse est fermée. Ouvrez-la pour vendre.',
  );
  String get openingFloat => _t(
    'Opening cash in the drawer',
    'Efectivo inicial en la caja',
    'Fonds de caisse initial',
  );
  String get managerPin =>
      _t('Manager PIN', 'PIN del gerente', 'NIP du gérant');
  String get open => _t('Open', 'Abrir', 'Ouvrir');
  String get cancel => _t('Cancel', 'Cancelar', 'Annuler');
  String get more => _t('More', 'Más', 'Plus');

  // catalog
  String get searchHint => _t(
    'Search or scan a product',
    'Busca o escanea un producto',
    'Chercher ou scanner un produit',
  );
  String get all => _t('All', 'Todo', 'Tout');
  String category(String id, String fallback) => switch (id) {
    'beer' => _t('Beer & Cider', 'Cerveza y sidra', 'Bières et cidres'),
    'wine' => _t('Wine', 'Vino', 'Vins'),
    'spirits' => _t('Spirits', 'Licores', 'Spiritueux'),
    'seltzers' => _t(
      'Seltzers & Coolers',
      'Seltzers y cocteles',
      'Seltzers et prêts-à-boire',
    ),
    'mixers' => _t('Mixers & Soda', 'Mezcladores y refrescos', 'Mélanges'),
    'snacks' => _t('Snacks', 'Botanas', 'Grignotines'),
    'ice' => _t('Ice', 'Hielo', 'Glace'),
    _ => fallback,
  };
  String get noMatch => _t(
    'No product matches',
    'Ningún producto coincide',
    'Aucun produit ne correspond',
  );
  String get agePill => '21+';
  String crvPill(String amount) => 'CRV $amount';
  String get noTax => _t('No tax', 'Sin impuesto', 'Non taxable');

  // basket
  String sale(int id) => _t('Sale #$id', 'Venta #$id', 'Vente #$id');
  String items(int n) => _t(
    n == 1 ? '1 item' : '$n items',
    n == 1 ? '1 artículo' : '$n artículos',
    n <= 1 ? '$n article' : '$n articles',
  );
  String get emptyBasketTitle =>
      _t('Scan to start a sale', 'Escanea para empezar', 'Scannez pour vendre');
  String get emptyBasketHint => _t(
    'Scan a barcode, or tap a product.',
    'Escanea un código de barras o toca un producto.',
    'Scannez un code-barres ou touchez un produit.',
  );
  String get itemsSubtotal => _t('Items', 'Artículos', 'Articles');
  String get crvLine =>
      _t('CRV (bottle deposit)', 'CRV (depósito de envases)', 'CRV (consigne)');
  String get total => _t('Total', 'Total', 'Total');
  String get pay => _t('Pay', 'Cobrar', 'Payer');
  String each(String price) => _t('$price each', '$price c/u', '$price chacun');
  String get removeLine => _t('Remove', 'Quitar', 'Retirer');
  String taxLine(String label, String rate) {
    final name = label == 'Sales Tax'
        ? _t('Sales Tax', 'Impuesto sobre la venta', 'Taxe de vente')
        : label;
    return '$name $rate%';
  }

  // age check
  String get idCheckNeeded => _t(
    'ID check needed (21+)',
    'Se requiere identificación (21+)',
    'Pièce d\'identité requise (21+)',
  );
  String idCheckedOk(int age) => _t(
    'ID checked · $age years old',
    'Identificación verificada · $age años',
    'Identité vérifiée · $age ans',
  );
  String get idCheckedShort =>
      _t('ID checked', 'Identificación verificada', 'Identité vérifiée');
  String get checkId => _t('Check ID', 'Verificar ID', 'Vérifier l\'ID');
  String ageCheckTitle(int legalAge) => _t(
    'Check ID ($legalAge+)',
    'Verificar identificación ($legalAge+)',
    'Vérifier l\'identité ($legalAge+)',
  );
  String get scanIdTitle =>
      _t('Scan the ID', 'Escanea la identificación', 'Scannez la pièce');
  String get scanIdHint => _t(
    'Scan the barcode on the back of the driver\'s licence or ID card.',
    'Escanea el código de barras al reverso de la licencia o identificación.',
    'Scannez le code-barres au dos du permis ou de la carte.',
  );
  String get listening =>
      _t('Waiting for the scan…', 'Esperando el escaneo…', 'En attente…');
  String get orEnterDob => _t(
    'Or enter the date of birth',
    'O ingresa la fecha de nacimiento',
    'Ou entrez la date de naissance',
  );
  String get month => _t('Month', 'Mes', 'Mois');
  String get day => _t('Day', 'Día', 'Jour');
  String get year => _t('Year', 'Año', 'Année');
  String get sawId => _t(
    'I have seen the customer\'s ID',
    'Vi la identificación del cliente',
    'J\'ai vu la pièce d\'identité',
  );
  String get verify => _t('Verify', 'Verificar', 'Vérifier');
  String passed(int age) => _t(
    'OK to sell · $age years old',
    'Se puede vender · $age años',
    'Vente permise · $age ans',
  );
  String failed(String? reason, int? age, int legalAge) => switch (reason) {
    'under_age' => _t(
      'Under $legalAge${age == null ? '' : ' ($age)'} — do not sell alcohol',
      'Menor de $legalAge${age == null ? '' : ' ($age)'}: no vendas alcohol',
      'Moins de $legalAge ans${age == null ? '' : ' ($age)'} : pas d\'alcool',
    ),
    'expired' => _t(
      'This ID has expired — ask for a valid one',
      'Esta identificación está vencida: pide una vigente',
      'Cette pièce est expirée : demandez-en une valide',
    ),
    'not_confirmed' => _t(
      'Confirm you have seen the ID',
      'Confirma que viste la identificación',
      'Confirmez avoir vu la pièce',
    ),
    _ => _t(
      'Couldn\'t read that ID — scan again or enter the date of birth',
      'No se pudo leer: escanea de nuevo o ingresa la fecha',
      'Illisible : scannez à nouveau ou entrez la date',
    ),
  };
  String get removeRestricted => _t(
    'Remove age-restricted items',
    'Quitar los artículos con restricción de edad',
    'Retirer les articles réservés aux adultes',
  );
  String get idFailedBanner => _t(
    'ID check failed — remove the age-restricted items to sell the rest',
    'No pasó la verificación: quita los artículos con restricción para vender el resto',
    'Vérification échouée : retirez les articles réservés pour vendre le reste',
  );
  String get tryAgain => _t('Try again', 'Intentar de nuevo', 'Réessayer');
  String get done => _t('Done', 'Listo', 'Terminé');
  String get privacyNote => _t(
    'Only the result is kept — never the name, date of birth or licence number.',
    'Solo se guarda el resultado, nunca el nombre, la fecha de nacimiento ni el número.',
    'Seul le résultat est conservé : jamais le nom, la date de naissance ou le numéro.',
  );
  List<String> get months => lang == 'es'
      ? const [
          'ene',
          'feb',
          'mar',
          'abr',
          'may',
          'jun',
          'jul',
          'ago',
          'sep',
          'oct',
          'nov',
          'dic',
        ]
      : lang == 'fr'
      ? const [
          'janv.',
          'févr.',
          'mars',
          'avr.',
          'mai',
          'juin',
          'juil.',
          'août',
          'sept.',
          'oct.',
          'nov.',
          'déc.',
        ]
      : const [
          'Jan',
          'Feb',
          'Mar',
          'Apr',
          'May',
          'Jun',
          'Jul',
          'Aug',
          'Sep',
          'Oct',
          'Nov',
          'Dec',
        ];

  // pay
  String get amountDue => _t('Amount due', 'Total a cobrar', 'Montant dû');
  String get cash => _t('Cash', 'Efectivo', 'Espèces');
  String get cardTerminal => _t(
    'Card (external terminal)',
    'Tarjeta (terminal externa)',
    'Carte (terminal externe)',
  );
  String get cashReceived =>
      _t('Cash received', 'Efectivo recibido', 'Espèces reçues');
  String get exact => _t('Exact', 'Exacto', 'Exact');
  String get takeCash => _t('Take cash', 'Cobrar en efectivo', 'Encaisser');
  String get cardHint => _t(
    'Run the card on the counter terminal for the amount due, then confirm.',
    'Pasa la tarjeta en la terminal por el total y luego confirma.',
    'Passez la carte au terminal pour le montant dû, puis confirmez.',
  );
  String get cardApproved => _t(
    'Terminal approved — record payment',
    'Terminal aprobó: registrar pago',
    'Terminal approuvé : enregistrer',
  );
  String change(String amount) =>
      _t('Change $amount', 'Cambio $amount', 'Monnaie $amount');
  String get paid => _t('Paid', 'Pagado', 'Payé');
  String get newSale => _t('New sale', 'Nueva venta', 'Nouvelle vente');
  String get receipt => _t('Receipt', 'Recibo', 'Reçu');
  String get noStripeNote => _t(
    'Card payments run on the counter\'s own terminal.',
    'Los pagos con tarjeta se hacen en la terminal del mostrador.',
    'Les cartes passent au terminal du comptoir.',
  );

  // unknown barcode / add product
  String unknownTitle(String code) => _t(
    'Unknown barcode $code',
    'Código desconocido $code',
    'Code-barres inconnu $code',
  );
  String get unknownBody => _t(
    'This product isn\'t in the catalog yet. A manager can add it now; the sale waits.',
    'Este producto aún no está en el catálogo. Un gerente puede agregarlo; la venta espera.',
    'Ce produit n\'est pas au catalogue. Un gérant peut l\'ajouter; la vente attend.',
  );
  String get addProduct => _t(
    'Add product (manager)',
    'Agregar producto (gerente)',
    'Ajouter (gérant)',
  );
  String get addProductTitle =>
      _t('Add product', 'Agregar producto', 'Ajouter un produit');
  String get barcode => _t('Barcode', 'Código de barras', 'Code-barres');
  String get name => _t('Name', 'Nombre', 'Nom');
  String get price => _t('Price (USD)', 'Precio (USD)', 'Prix (USD)');
  String get categoryLabel => _t('Category', 'Categoría', 'Catégorie');
  String get ageRestricted => _t(
    '21+ (age-restricted)',
    '21+ (restricción de edad)',
    '21+ (réservé aux adultes)',
  );
  String get taxable => _t('Taxable', 'Grava impuesto', 'Taxable');
  String get deposit =>
      _t('Bottle deposit (CRV)', 'Depósito de envases (CRV)', 'Consigne (CRV)');
  String get crvNone => _t('None', 'Ninguno', 'Aucune');
  String get crvSmall =>
      _t('Under 24 oz (5¢)', 'Menos de 24 oz (5¢)', 'Moins de 24 oz (5¢)');
  String get crvLarge =>
      _t('24 oz or more (10¢)', '24 oz o más (10¢)', '24 oz et plus (10¢)');
  String get packUnits =>
      _t('Containers in the pack', 'Envases en el paquete', 'Contenants');
  String get lookingUp => _t(
    'Looking the name up online…',
    'Buscando el nombre en línea…',
    'Recherche du nom en ligne…',
  );
  String get lookupFound => _t(
    'Name suggested from Open Food Facts — check it.',
    'Nombre sugerido por Open Food Facts: revísalo.',
    'Nom suggéré par Open Food Facts : vérifiez-le.',
  );
  String get lookupNone => _t(
    'No online match — type the name.',
    'Sin resultados en línea: escribe el nombre.',
    'Aucun résultat : tapez le nom.',
  );
  String get save => _t('Add and ring up', 'Agregar y cobrar', 'Ajouter');
  String added(String name) => _t(
    '$name added to the catalog',
    '$name agregado al catálogo',
    '$name ajouté au catalogue',
  );
  String get fillRequired => _t(
    'Name, price and category are required',
    'Nombre, precio y categoría son obligatorios',
    'Nom, prix et catégorie requis',
  );
}
