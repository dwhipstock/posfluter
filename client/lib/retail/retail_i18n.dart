import 'package:flutter/widgets.dart';

import '../i18n.dart';
import '../store_profile.dart';
import 'shelf_names.dart';

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
  String get bottleShop =>
      _t('Bottle Shop', 'Tienda de licores', 'Boutique d’alcools');
  String get register1 => _t('Register 1', 'Caja 1', 'Caisse 1');
  String get scannerReady =>
      _t('Scanner ready', 'Escáner listo', 'Lecteur prêt');
  String get signOut => _t('Sign out', 'Cerrar sesión', 'Se déconnecter');
  String get openRegister =>
      _t('Open the register', 'Abrir la caja', 'Ouvrir la caisse');
  String get closeRegister =>
      _t('Close the register', 'Cerrar la caja', 'Fermer la caisse');
  String get registerClosed => _t(
    'The register is closed. Open it to start selling.',
    'La caja está cerrada. Ábrela para empezar a vender.',
    'La caisse est fermée. Ouvrez-la pour commencer à vendre.',
  );
  String get openingFloat => _t(
    'Opening cash in the drawer',
    'Fondo inicial de caja',
    'Fonds de caisse initial',
  );
  String get managerPin =>
      _t('Manager PIN', 'PIN del gerente', 'NIP du gérant');
  String get open => _t('Open', 'Abrir', 'Ouvrir');
  String get cancel => _t('Cancel', 'Cancelar', 'Annuler');
  String get more => _t('More', 'Más', 'Plus');
  String get countStock =>
      _t('Count stock', 'Contar inventario', 'Faire l’inventaire');
  String get receiveDelivery =>
      _t('Receive a delivery', 'Recibir una entrega', 'Recevoir une livraison');
  String get scanWithCamera => _t(
    'Scan with the camera',
    'Escanear con la cámara',
    'Balayer avec la caméra',
  );

  // catalog
  String get searchHint => _t(
    'Search or scan a product',
    'Busca o escanea un producto',
    'Chercher ou balayer un produit',
  );
  String get all => _t('All', 'Todos', 'Tout');
  String category(String id, String fallback) => switch (id) {
    'beer' when StoreProfile.current.isPronghorn => _t(
      'Beer & Seltzer',
      'Cerveza y seltzer',
      'Bières et seltzers',
    ),
    'drinks' => _t('Cold Drinks', 'Bebidas frías', 'Boissons froides'),
    'candy' => _t('Candy & Gum', 'Dulces y chicles', 'Bonbons et gomme'),
    'hot-food' => _t(
      'Hot Food & Coffee',
      'Comida caliente y café',
      'Mets chauds et café',
    ),
    'grocery' => _t('Grocery & Dairy', 'Abarrotes y lácteos', 'Épicerie'),
    'automotive' => _t('Automotive', 'Automotriz', 'Automobile'),
    'health' => _t('Health & Beauty', 'Salud y belleza', 'Santé et beauté'),
    'general' => _t(
      'General Merchandise',
      'Mercancía general',
      'Articles divers',
    ),
    'tobacco' => _t('Tobacco & Vape', 'Tabaco y vapeo', 'Tabac et vapotage'),
    'fuel' => _t('Fuel', 'Combustible', 'Carburant'),
    'beer' => _t('Beer & Cider', 'Cerveza y sidra', 'Bières et cidres'),
    'wine' => _t('Wine', 'Vino', 'Vins'),
    'spirits' => _t('Spirits', 'Licores', 'Spiritueux'),
    'seltzers' => _t(
      'Seltzers & Coolers',
      'Seltzers y bebidas preparadas',
      'Seltzers et prêts-à-boire',
    ),
    'mixers' => _t(
      'Mixers & Soda',
      'Mezcladores y refrescos',
      'Mélanges et boissons gazeuses',
    ),
    'snacks' => _t('Snacks', 'Botanas', 'Grignotines'),
    'ice' => _t('Ice', 'Hielo', 'Glace'),
    'sundries' => _t('Sundries', 'Artículos varios', 'Divers'),
    _ => fallback,
  };

  /// A style / varietal / type ("Hazy IPA", "Red Blend") in the UI language;
  /// the catalog carries English only ([shelfNamesEs]).
  String shelfName(String en) => lang == 'es' ? (shelfNamesEs[en] ?? en) : en;

  /// A size or pack label ("6-pack", "12 oz can") in the UI language.
  String sizeName(String en) => lang == 'es' ? shelfSizeEs(en) : en;

  /// The store's own label ("House" in the catalog's brand field).
  String get houseBrand => _t('House', 'Marca de la casa', 'Marque maison');

  String get noMatch => _t(
    'No product matches',
    'Ningún producto coincide',
    'Aucun produit ne correspond',
  );
  String get agePill => '21+';
  String crvPill(String amount) => 'CRV $amount';
  String get noTax => _t('No tax', 'Sin impuesto', 'Non taxable');

  // basket
  String sale(int id) => _t('Sale #$id', 'Venta #$id', 'Vente n°\u00A0$id');
  String items(int n) => _t(
    n == 1 ? '1 item' : '$n items',
    n == 1 ? '1 artículo' : '$n artículos',
    n <= 1 ? '$n article' : '$n articles',
  );
  String get emptyBasketTitle => _t(
    'Scan to start a sale',
    'Escanea para empezar una venta',
    'Balayez pour commencer une vente',
  );
  String get emptyBasketHint => _t(
    'Scan a barcode, or tap a product.',
    'Escanea un código de barras o toca un producto.',
    'Balayez un code-barres ou touchez un produit.',
  );
  String get itemsSubtotal => _t('Items', 'Artículos', 'Articles');
  String get crvLine =>
      _t('CRV (bottle deposit)', 'CRV (depósito de envases)', 'CRV (consigne)');
  String get total => _t('Total', 'Total', 'Total');
  String get pay => _t('Pay', 'Cobrar', 'Encaisser');
  String each(String price) =>
      _t('$price each', '$price c/u', '$price l’unité');
  String get removeLine => _t('Remove', 'Quitar', 'Retirer');
  String taxLine(String label, String rate) {
    final name = label == 'Sales Tax'
        ? _t('Sales Tax', 'Impuesto sobre la venta', 'Taxe de vente')
        : label;
    return lang == 'fr'
        ? '$name ${rate.replaceAll('.', ',')}\u00A0%'
        : '$name $rate%';
  }

  // age check
  String get idCheckNeeded => _t(
    'ID check needed (21+)',
    'Se requiere identificación (21+)',
    'Pièce d’identité requise (21+)',
  );
  String idCheckedOk(int age) => _t(
    'ID checked · $age years old',
    'Identificación verificada · $age años',
    'Pièce vérifiée · $age ans',
  );
  String get idCheckedShort =>
      _t('ID checked', 'Identificación verificada', 'Pièce vérifiée');
  String get checkId =>
      _t('Check ID', 'Verificar identificación', 'Vérifier la pièce');
  String ageCheckTitle(int legalAge) => _t(
    'Check ID ($legalAge+)',
    'Verificar identificación ($legalAge+)',
    'Vérifier la pièce d’identité ($legalAge+)',
  );
  String get scanIdTitle => _t(
    'Scan the ID',
    'Escanea la identificación',
    'Balayez la pièce d’identité',
  );
  String get scanIdHint => _t(
    'Scan the barcode on the back of the driver’s license or ID card.',
    'Escanea el código de barras al reverso de la licencia de manejo o de la identificación.',
    'Balayez le code-barres au dos du permis de conduire ou de la carte d’identité.',
  );
  String get listening => _t(
    'Waiting for the scan…',
    'Esperando el escaneo…',
    'En attente de la lecture…',
  );
  String get orEnterDob => _t(
    'Or enter the date of birth',
    'O ingresa la fecha de nacimiento',
    'Ou entrez la date de naissance',
  );
  String get month => _t('Month', 'Mes', 'Mois');
  String get day => _t('Day', 'Día', 'Jour');
  String get year => _t('Year', 'Año', 'Année');
  String get sawId => _t(
    'I have seen the customer’s ID',
    'Vi la identificación del cliente',
    'J’ai vu la pièce d’identité du client',
  );
  String get verify => _t('Verify', 'Verificar', 'Vérifier');
  String passed(int age) => _t(
    'OK to sell · $age years old',
    'Se puede vender · $age años',
    'Vente autorisée · $age ans',
  );
  String failed(String? reason, int? age, int legalAge) => switch (reason) {
    'under_age' => _t(
      'Under $legalAge${age == null ? '' : ' ($age)'} — do not sell alcohol',
      'Menor de $legalAge${age == null ? '' : ' ($age)'}: no vendas alcohol',
      'Moins de $legalAge ans${age == null ? '' : ' ($age)'}\u00A0: pas d’alcool',
    ),
    'expired' => _t(
      'This ID has expired — ask for a valid one',
      'Esta identificación está vencida: pide una vigente',
      'Cette pièce est expirée\u00A0: demandez-en une valide',
    ),
    'not_confirmed' => _t(
      'Confirm you have seen the ID',
      'Confirma que viste la identificación',
      'Confirmez que vous avez vu la pièce',
    ),
    _ => _t(
      'Couldn’t read that ID — scan again or enter the date of birth',
      'No se pudo leer la identificación: escanéala de nuevo o ingresa la fecha de nacimiento',
      'Pièce illisible\u00A0: balayez-la de nouveau ou entrez la date de naissance',
    ),
  };
  String get removeRestricted => _t(
    'Remove age-restricted items',
    'Quitar los artículos con restricción de edad',
    'Retirer les articles réservés aux adultes',
  );
  String get idFailedBanner => _t(
    'ID check failed — remove the age-restricted items to sell the rest',
    'No pasó la verificación: quita los artículos con restricción de edad para vender el resto',
    'Vérification échouée\u00A0: retirez les articles réservés aux adultes pour vendre le reste',
  );
  String get tryAgain => _t('Try again', 'Intentar de nuevo', 'Réessayer');
  String get done => _t('Done', 'Listo', 'Terminé');
  String get privacyNote => _t(
    'Only the result is kept — never the name, date of birth or license number.',
    'Solo se guarda el resultado; nunca el nombre, la fecha de nacimiento ni el número de licencia.',
    'Seul le résultat est conservé, jamais le nom, la date de naissance ni le numéro de permis.',
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
  String get cash => _t('Cash', 'Efectivo', 'Comptant');
  String get cardTerminal => _t(
    'Card (external terminal)',
    'Tarjeta (terminal externa)',
    'Carte (terminal externe)',
  );
  String get cashReceived =>
      _t('Cash received', 'Efectivo recibido', 'Comptant reçu');
  String get exact => _t('Exact', 'Exacto', 'Montant exact');
  String get rounding => _t('Rounding', 'Redondeo', 'Arrondi');
  String get cashTotal =>
      _t('Cash total', 'Total en efectivo', 'Total comptant');
  String get takeCash =>
      _t('Take cash', 'Cobrar en efectivo', 'Encaisser en comptant');
  String get cardHint => _t(
    'Run the card on the counter terminal for the amount due, then confirm.',
    'Pasa la tarjeta en la terminal por el total y luego confirma.',
    'Passez la carte au terminal pour le montant dû, puis confirmez.',
  );
  String get cardApproved => _t(
    'Terminal approved — record payment',
    'Pago aprobado en la terminal: regístralo',
    'Approuvé au terminal\u00A0: enregistrer le paiement',
  );
  String change(String amount) =>
      _t('Change $amount', 'Cambio $amount', 'Monnaie $amount');
  String get paid => _t('Paid', 'Pagado', 'Payé');
  String get newSale => _t('New sale', 'Nueva venta', 'Nouvelle vente');
  String get receipt => _t('Receipt', 'Recibo', 'Reçu');
  String get noStripeNote => _t(
    'Card payments run on the counter’s own terminal.',
    'Los pagos con tarjeta se hacen en la terminal del mostrador.',
    'Les paiements par carte passent par le terminal du comptoir.',
  );

  // unknown barcode / add product
  String unknownTitle(String code) => _t(
    'Unknown barcode $code',
    'Código desconocido $code',
    'Code-barres inconnu $code',
  );
  String get unknownBody => _t(
    'This product isn’t in the catalog yet. A manager can add it now; the sale waits.',
    'Este producto aún no está en el catálogo. Un gerente puede agregarlo ahora; la venta espera.',
    'Ce produit n’est pas encore au catalogue. Un gérant peut l’ajouter maintenant\u00A0; la vente attend.',
  );
  String get addProduct => _t(
    'Add product (manager)',
    'Agregar producto (gerente)',
    'Ajouter un produit (gérant)',
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
  String get taxable => _t('Taxable', 'Sujeto a impuesto', 'Soumis aux taxes');
  String get deposit =>
      _t('Bottle deposit (CRV)', 'Depósito de envases (CRV)', 'Consigne (CRV)');
  String get crvNone => _t('None', 'Ninguno', 'Aucune');
  String get crvSmall => _t(
    'Under 24 oz (5¢)',
    'Menos de 24 oz (5¢)',
    'Moins de 24 oz (5\u00A0¢)',
  );
  String get crvLarge => _t(
    '24 oz or more (10¢)',
    '24 oz o más (10¢)',
    '24 oz et plus (10\u00A0¢)',
  );
  String get packUnits => _t(
    'Containers in the pack',
    'Envases en el paquete',
    'Contenants dans l’emballage',
  );
  String get lookingUp => _t(
    'Looking the name up online…',
    'Buscando el nombre en línea…',
    'Recherche du nom en ligne…',
  );
  String get lookupFound => _t(
    'Name suggested from Open Food Facts — check it.',
    'Nombre sugerido por Open Food Facts: revísalo.',
    'Nom suggéré par Open Food Facts\u00A0: vérifiez-le.',
  );
  String get lookupNone => _t(
    'No online match — type the name.',
    'Sin resultados en línea: escribe el nombre.',
    'Aucun résultat en ligne\u00A0: tapez le nom.',
  );
  String get save =>
      _t('Add and ring up', 'Agregar y cobrar', 'Ajouter et encaisser');
  String added(String name) => _t(
    '$name added to the catalog',
    '$name agregado al catálogo',
    '$name ajouté au catalogue',
  );
  String get fillRequired => _t(
    'Name, price and category are required',
    'Nombre, precio y categoría son obligatorios',
    'Le nom, le prix et la catégorie sont obligatoires',
  );

  // the counter at 5,000 products: quick keys, top sellers, browse, search
  String get sell => _t('Sell', 'Vender', 'Vendre');
  String get count => _t('Count', 'Contar', 'Compter');
  String get receive => _t('Receive', 'Recibir', 'Recevoir');
  String get register => _t('Register', 'Caja', 'Caisse');
  String get quickKeys => _t('Quick keys', 'Teclas rápidas', 'Touches rapides');
  String get topSellers =>
      _t('Top sellers', 'Más vendidos', 'Meilleurs vendeurs');
  String get browse => _t('Browse', 'Explorar', 'Parcourir');
  String searchCatalog(int n) => _t(
    'Scan a barcode or search ${_n(n)} products',
    'Escanea un código o busca entre ${_n(n)} productos',
    'Balayez un code-barres ou cherchez parmi ${_n(n)} produits',
  );
  String results(int n, String q) => _t(
    '${_n(n)} ${n == 1 ? 'match' : 'matches'} for “$q”',
    '${_n(n)} ${n == 1 ? 'resultado' : 'resultados'} para “$q”',
    '${_n(n)} résultat${n == 1 ? '' : 's'} pour «\u00A0$q\u00A0»',
  );
  String get resultsCapped => _t(
    'Showing the best 200. Type more to narrow.',
    'Se muestran los 200 mejores. Escribe más para acotar.',
    'Les 200 meilleurs résultats sont affichés. Précisez votre recherche.',
  );
  String sold(int n, int days) => _t(
    '$n sold · $days days',
    '$n vendidos · $days días',
    '$n vendus · $days jours',
  );
  String get notSoldYet =>
      _t('Popular pick', 'Selección popular', 'Choix populaire');
  String topSellersNote(int n, int total) => _t(
    'The ${_n(n)} best sellers of ${_n(total)} products, last 28 days',
    'Los ${_n(n)} más vendidos de ${_n(total)} productos, últimos 28 días',
    'Les ${_n(n)} meilleurs vendeurs sur ${_n(total)} produits, 28 derniers jours',
  );
  String products(int n) => _t(
    '${_n(n)} ${n == 1 ? 'product' : 'products'}',
    '${_n(n)} ${n == 1 ? 'producto' : 'productos'}',
    '${_n(n)} produit${n == 1 ? '' : 's'}',
  );
  String get allStyles =>
      _t('All styles', 'Todos los estilos', 'Tous les styles');
  String get allSizes =>
      _t('Any size', 'Todos los tamaños', 'Tous les formats');
  String get size => _t('Size', 'Tamaño', 'Format');
  String get reset => _t('Reset', 'Restablecer', 'Réinitialiser');
  String get pickDepartment => _t(
    'Pick a department to browse the shelf',
    'Elige un departamento para explorar el catálogo',
    'Choisissez un rayon pour parcourir les tablettes',
  );
  String get pinKey => _t(
    'Pin to quick keys',
    'Fijar en teclas rápidas',
    'Épingler aux touches rapides',
  );
  String get unpinKey => _t(
    'Unpin from quick keys',
    'Quitar de teclas rápidas',
    'Retirer des touches rapides',
  );
  String get editKeys =>
      _t('Edit keys', 'Editar teclas', 'Modifier les touches');
  String get doneEditing => _t('Done', 'Listo', 'Terminé');
  String get keysHint => _t(
    'Tap a key to pin or unpin it. Pinned keys stay; the rest follow the last 28 days of sales.',
    'Toca una tecla para fijarla o quitarla. Las fijas se quedan; las demás siguen las ventas de los últimos 28 días.',
    'Touchez une touche pour l’épingler ou la retirer. Les touches épinglées restent\u00A0; les autres suivent les ventes des 28 derniers jours.',
  );
  String get pinnedTag => _t('Pinned', 'Fija', 'Épinglée');
  String get noBarcode =>
      _t('No barcode', 'Sin código de barras', 'Sans code-barres');
  String get addToSale => _t('Add', 'Agregar', 'Ajouter');
  String get loadingShelf => _t(
    'Loading the shelf…',
    'Cargando el catálogo…',
    'Chargement du catalogue…',
  );
  String get registerOpenShort =>
      _t('Register open', 'Caja abierta', 'Caisse ouverte');
  String get registerClosedShort =>
      _t('Register closed', 'Caja cerrada', 'Caisse fermée');

  // sign-in (the store's own composition)
  String get welcome =>
      _t('Welcome back', 'Hola de nuevo', 'Bon retour parmi nous');
  String get signInHint => _t(
    'Tap your name, then enter your 4-digit PIN.',
    'Toca tu nombre y escribe tu PIN de 4 dígitos.',
    'Touchez votre nom, puis entrez votre NIP à 4 chiffres.',
  );
  String get enterPin =>
      _t('Enter your PIN', 'Escribe tu PIN', 'Entrez votre NIP');
  String hello(String name) => _t('Hi, $name', 'Hola, $name', 'Bonjour, $name');
  String role(String role) => switch (role) {
    'MANAGER' => _t('Manager', 'Gerente', 'Gérant'),
    _ => _t('Cashier', 'Cajero', 'Caissier'),
  };
  String get storeLine => StoreProfile.current.isPronghorn
      ? _t(
          'Fuel & Market · Dripping Springs, TX',
          'Gasolinera y tienda · Dripping Springs, TX',
          'Carburant et dépanneur · Dripping Springs, TX',
        )
      : _t(
          'Bottle Shop · Los Angeles',
          'Tienda de licores · Los Ángeles',
          'Boutique d’alcools · Los Angeles',
        );
  String looksOver(int n) => _t(
    'Clearly over $n: no ID needed',
    'Claramente mayor de $n: sin identificación',
    'Nettement plus de $n\u00A0ans\u00A0: sans pièce d’identité',
  );
  String get stockApp =>
      _t('Stock app', 'App de inventario', 'Application d’inventaire');
  String get registerLine => _t(
    'Register 1 · Staff only',
    'Caja 1 · Solo personal',
    'Caisse 1 · Personnel seulement',
  );

  String _n(int n) => n.toString().replaceAllMapped(
    RegExp(r'(\d)(?=(\d{3})+$)'),
    (m) => '${m[1]}${lang == 'fr' ? '\u00A0' : ','}',
  );
}
