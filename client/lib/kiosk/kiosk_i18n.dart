/// The self-order kiosk's own words: French, English, Spanish and German
/// (the customer picks on the welcome screen; the store's first language is
/// the default).
class KioskText {
  final String lang;
  const KioskText(this.lang);

  /// The language buttons on the welcome screen, in each language's own name.
  static const languageNames = {
    'fr': 'Français',
    'en': 'English',
    'es': 'Español',
    'de': 'Deutsch',
  };

  String _t(String fr, String en, String es, String de) => switch (lang) {
    'fr' => fr,
    'es' => es,
    'de' => de,
    _ => en,
  };

  // welcome and dine in / take out
  String get touchToOrder => _t(
    'Touchez pour commander',
    'Touch to order',
    'Toque para ordenar',
    'Zum Bestellen berühren',
  );
  String get chooseMode => _t(
    'Sur place ou pour emporter ?',
    'Eating here or taking out?',
    '¿Para comer aquí o para llevar?',
    'Hier essen oder mitnehmen?',
  );
  String get dineIn =>
      _t('Sur place', 'Dine in', 'Para comer aquí', 'Hier essen');
  String get takeOut =>
      _t('Pour emporter', 'Take out', 'Para llevar', 'Zum Mitnehmen');

  // the menu
  String get chooseSize => _t(
    'Choisissez un format',
    'Choose a size',
    'Elija un tamaño',
    'Größe wählen',
  );
  String addFor(String price) => _t(
    'Ajouter — $price',
    'Add — $price',
    'Agregar — $price',
    'Hinzufügen — $price',
  );
  String added(String name) =>
      _t('$name ajouté', '$name added', '$name agregado', '$name hinzugefügt');
  String viewOrder(int n) => _t(
    'Ma commande ($n)',
    'My order ($n)',
    'Mi pedido ($n)',
    'Meine Bestellung ($n)',
  );
  String get idNote => _t(
    'Alcool — le personnel vérifiera une pièce d’identité au comptoir.',
    'Alcohol — staff will check ID at the counter.',
    'Alcohol — el personal verificará su identificación en el mostrador.',
    'Alkohol — das Personal prüft an der Kasse Ihren Ausweis.',
  );

  // the cart
  String get yourOrder =>
      _t('Votre commande', 'Your order', 'Su pedido', 'Ihre Bestellung');
  String get emptyOrder => _t(
    'Votre commande est vide',
    'Your order is empty',
    'Su pedido está vacío',
    'Ihre Bestellung ist leer',
  );
  String get subtotal =>
      _t('Sous-total', 'Subtotal', 'Subtotal', 'Zwischensumme');
  String get taxesAtCounter => _t(
    'Taxes en sus, payées au comptoir',
    'Taxes added at the counter',
    'Impuestos agregados en el mostrador',
    'Steuern kommen an der Kasse hinzu',
  );
  String get placeOrder =>
      _t('Commander', 'Place order', 'Hacer el pedido', 'Bestellen');
  String get sending => _t('Envoi…', 'Sending…', 'Enviando…', 'Wird gesendet…');
  String get sendFailed => _t(
    'La commande n’a pas pu être envoyée. Réessayez ou adressez-vous au comptoir.',
    'The order could not be sent. Try again or ask at the counter.',
    'No se pudo enviar el pedido. Inténtelo de nuevo o pregunte en el mostrador.',
    'Die Bestellung konnte nicht gesendet werden. Bitte erneut versuchen oder an der Kasse fragen.',
  );
  String get back => _t('Retour', 'Back', 'Volver', 'Zurück');
  String get startOver =>
      _t('Recommencer', 'Start over', 'Empezar de nuevo', 'Neu beginnen');

  // the last screen
  String get yourNumber => _t(
    'Votre numéro de commande',
    'Your order number is',
    'Su número de pedido es',
    'Ihre Bestellnummer lautet',
  );
  String get payAtCounter => _t(
    'Veuillez payer au comptoir.',
    'Please pay at the counter.',
    'Por favor pague en el mostrador.',
    'Bitte an der Kasse bezahlen.',
  );
  String get thanks => _t('Merci', 'Thank you', 'Gracias', 'Danke');

  // setup (staff only)
  String get findingStore => _t(
    'Recherche du restaurant…',
    'Looking for the store…',
    'Buscando el restaurante…',
    'Restaurant wird gesucht…',
  );
  String get pairTitle => _t(
    'Jumeler cette borne',
    'Pair this kiosk',
    'Vincular este quiosco',
    'Dieses Bestellterminal koppeln',
  );
  String get pairHelp => _t(
    'Sur la caisse, ouvrez Commandes puis Jumeler une borne, et entrez le code affiché.',
    'On the POS, open Orders, then Pair a kiosk, and type the code it shows.',
    'En la caja, abra Pedidos y luego Vincular un quiosco, e ingrese el código.',
    'An der Kasse Bestellungen und dann Bestellterminal koppeln öffnen und den Code eingeben.',
  );
  String get storeAddress => _t(
    'Adresse du restaurant (facultatif)',
    'Store address (optional)',
    'Dirección del restaurante (opcional)',
    'Adresse des Restaurants (optional)',
  );
  String get code => _t(
    'Code à 6 chiffres',
    '6-digit code',
    'Código de 6 dígitos',
    '6-stelliger Code',
  );
  String get pair => _t('Jumeler', 'Pair', 'Vincular', 'Koppeln');
  String get searchAgain =>
      _t('Chercher encore', 'Search again', 'Buscar de nuevo', 'Erneut suchen');
  String get notFound => _t(
    'Aucun comptoir libre-service trouvé sur ce Wi-Fi. Entrez son adresse.',
    'No quick-serve store found on this Wi-Fi. Type its address.',
    'No se encontró ningún restaurante de autoservicio en este Wi-Fi. Ingrese su dirección.',
    'Kein Schnellrestaurant in diesem WLAN gefunden. Adresse eingeben.',
  );
  String get pairFailed => _t(
    'Code refusé ou restaurant injoignable.',
    'Code refused, or the store can’t be reached.',
    'Código rechazado o restaurante inaccesible.',
    'Code abgelehnt oder Restaurant nicht erreichbar.',
  );
  String get offline => _t(
    'Borne hors ligne — veuillez commander au comptoir.',
    'Kiosk offline — please order at the counter.',
    'Quiosco fuera de línea — pida en el mostrador.',
    'Bestellterminal offline — bitte an der Kasse bestellen.',
  );
}
