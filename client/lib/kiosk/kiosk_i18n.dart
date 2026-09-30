/// The self-order kiosk's own words: French, English, Spanish, German and
/// Afrikaans (the customer picks on the welcome screen; the store's first
/// language is the default).
class KioskText {
  final String lang;
  const KioskText(this.lang);

  /// The language buttons on the welcome screen, in each language's own name.
  static const languageNames = {
    'fr': 'Français',
    'en': 'English',
    'es': 'Español',
    'de': 'Deutsch',
    'af': 'Afrikaans',
  };

  String _t(String fr, String en, String es, String de, String af) =>
      switch (lang) {
        'fr' => fr,
        'es' => es,
        'de' => de,
        'af' => af,
        _ => en,
      };

  // welcome and dine in / take out
  String get touchToOrder => _t(
    'Touchez pour commander',
    'Touch to order',
    'Toque para ordenar',
    'Zum Bestellen berühren',
    'Raak om te bestel',
  );
  String get chooseMode => _t(
    'Sur place ou pour emporter ?',
    'Eating here or taking out?',
    '¿Para comer aquí o para llevar?',
    'Hier essen oder mitnehmen?',
    'Eet u hier of neem u weg?',
  );
  String get dineIn =>
      _t('Sur place', 'Dine in', 'Para comer aquí', 'Hier essen', 'Eet hier');
  String get takeOut => _t(
    'Pour emporter',
    'Take out',
    'Para llevar',
    'Zum Mitnehmen',
    'Wegneem',
  );

  // the menu
  String get chooseSize => _t(
    'Choisissez un format',
    'Choose a size',
    'Elija un tamaño',
    'Größe wählen',
    'Kies ’n grootte',
  );
  String addFor(String price) => _t(
    'Ajouter — $price',
    'Add — $price',
    'Agregar — $price',
    'Hinzufügen — $price',
    'Voeg by — $price',
  );
  String added(String name) => _t(
    '$name ajouté',
    '$name added',
    '$name agregado',
    '$name hinzugefügt',
    '$name bygevoeg',
  );
  String viewOrder(int n) => _t(
    'Ma commande ($n)',
    'My order ($n)',
    'Mi pedido ($n)',
    'Meine Bestellung ($n)',
    'My bestelling ($n)',
  );

  /// The alcohol note, naming the store's legal drinking age (21 in the US).
  String idNote(int age) => _t(
    'Alcool — $age ans et plus. Le personnel vérifiera une pièce d’identité au comptoir.',
    'Alcohol — $age+ only. Staff will check ID at the counter.',
    'Alcohol — solo mayores de $age. El personal verificará su identificación en el mostrador.',
    'Alkohol erst ab $age — das Personal prüft an der Kasse Ihren Ausweis.',
    'Alkohol — slegs $age+. Personeel sal u ID by die toonbank kontroleer.',
  );

  // "Add a drink?" (the store picks the rows; once per order)
  String get completeMeal => _t(
    'Pour compléter votre repas',
    'Complete your meal',
    'Complete su comida',
    'Ihr Menü vervollständigen',
    'Voltooi u ete',
  );
  String get addDrink => _t(
    'Ajouter une boisson ?',
    'Add a drink?',
    '¿Agregar una bebida?',
    'Ein Getränk dazu?',
    '’n Drankie daarby?',
  );
  String get addFries => _t(
    'Ajouter des frites ?',
    'Add fries?',
    '¿Agregar papas fritas?',
    'Pommes dazu?',
    'Skyfies daarby?',
  );
  String get addDessert => _t(
    'Un petit dessert ?',
    'Something sweet?',
    '¿Algo dulce?',
    'Etwas Süßes?',
    'Iets soets?',
  );
  String get noThanks => _t(
    'Non merci, continuer',
    'No thanks, continue',
    'No, gracias, continuar',
    'Nein danke, weiter',
    'Nee dankie, gaan voort',
  );

  /// A row's title by the store's reason; null for one this kiosk doesn't
  /// know (the category's name is shown instead).
  String? offerTitle(String reason) => switch (reason) {
    'drink' => addDrink,
    'side' => addFries,
    'dessert' => addDessert,
    _ => null,
  };

  // the cart
  String get yourOrder => _t(
    'Votre commande',
    'Your order',
    'Su pedido',
    'Ihre Bestellung',
    'U bestelling',
  );
  String get emptyOrder => _t(
    'Votre commande est vide',
    'Your order is empty',
    'Su pedido está vacío',
    'Ihre Bestellung ist leer',
    'U bestelling is leeg',
  );
  String get subtotal =>
      _t('Sous-total', 'Subtotal', 'Subtotal', 'Zwischensumme', 'Subtotaal');
  String get taxesAtCounter => _t(
    'Taxes en sus, payées au comptoir',
    'Taxes added at the counter',
    'Impuestos agregados en el mostrador',
    'Steuern kommen an der Kasse hinzu',
    'Belasting word by die toonbank bygevoeg',
  );
  String get placeOrder => _t(
    'Commander',
    'Place order',
    'Hacer el pedido',
    'Bestellen',
    'Plaas bestelling',
  );
  String get sending =>
      _t('Envoi…', 'Sending…', 'Enviando…', 'Wird gesendet…', 'Stuur tans…');
  String get sendFailed => _t(
    'La commande n’a pas pu être envoyée. Réessayez ou adressez-vous au comptoir.',
    'The order could not be sent. Try again or ask at the counter.',
    'No se pudo enviar el pedido. Inténtelo de nuevo o pregunte en el mostrador.',
    'Die Bestellung konnte nicht gesendet werden. Bitte erneut versuchen oder an der Kasse fragen.',
    'Die bestelling kon nie gestuur word nie. Probeer weer of vra by die toonbank.',
  );
  String get back => _t('Retour', 'Back', 'Volver', 'Zurück', 'Terug');
  String get startOver => _t(
    'Recommencer',
    'Start over',
    'Empezar de nuevo',
    'Neu beginnen',
    'Begin oor',
  );

  // the last screen
  String get yourNumber => _t(
    'Votre numéro de commande',
    'Your order number is',
    'Su número de pedido es',
    'Ihre Bestellnummer lautet',
    'U bestelnommer is',
  );
  String get payAtCounter => _t(
    'Veuillez payer au comptoir.',
    'Please pay at the counter.',
    'Por favor pague en el mostrador.',
    'Bitte an der Kasse bezahlen.',
    'Betaal asseblief by die toonbank.',
  );
  String get takeTicket => _t(
    'Apportez votre billet au comptoir pour payer.',
    'Take your ticket to the counter to pay.',
    'Lleve su ticket al mostrador para pagar.',
    'Bringen Sie Ihren Bon zum Bezahlen an die Kasse.',
    'Neem u kaartjie na die toonbank om te betaal.',
  );
  String get thanks => _t('Merci', 'Thank you', 'Gracias', 'Danke', 'Dankie');

  // setup (staff only)
  String get findingStore => _t(
    'Recherche du restaurant…',
    'Looking for the store…',
    'Buscando el restaurante…',
    'Restaurant wird gesucht…',
    'Soek die winkel…',
  );
  String get pairTitle => _t(
    'Jumeler cette borne',
    'Pair this kiosk',
    'Vincular este quiosco',
    'Dieses Bestellterminal koppeln',
    'Koppel hierdie kiosk',
  );
  String get pairHelp => _t(
    'Sur la caisse, ouvrez Commandes puis Jumeler une borne, et entrez le code affiché.',
    'On the POS, open Orders, then Pair a kiosk, and type the code it shows.',
    'En la caja, abra Pedidos y luego Vincular un quiosco, e ingrese el código.',
    'An der Kasse Bestellungen und dann Bestellterminal koppeln öffnen und den Code eingeben.',
    'Maak op die POS Bestellings oop, dan Koppel ’n kiosk, en tik die kode in wat dit wys.',
  );
  String get storeAddress => _t(
    'Adresse du restaurant (facultatif)',
    'Store address (optional)',
    'Dirección del restaurante (opcional)',
    'Adresse des Restaurants (optional)',
    'Winkeladres (opsioneel)',
  );
  String get code => _t(
    'Code à 6 chiffres',
    '6-digit code',
    'Código de 6 dígitos',
    '6-stelliger Code',
    '6-syferkode',
  );
  String get pair => _t('Jumeler', 'Pair', 'Vincular', 'Koppeln', 'Koppel');
  String get searchAgain => _t(
    'Chercher encore',
    'Search again',
    'Buscar de nuevo',
    'Erneut suchen',
    'Soek weer',
  );
  String get notFound => _t(
    'Aucun comptoir libre-service trouvé sur ce Wi-Fi. Entrez son adresse.',
    'No quick-serve store found on this Wi-Fi. Type its address.',
    'No se encontró ningún restaurante de autoservicio en este Wi-Fi. Ingrese su dirección.',
    'Kein Schnellrestaurant in diesem WLAN gefunden. Adresse eingeben.',
    'Geen kitsdienswinkel op hierdie Wi-Fi gevind nie. Tik sy adres in.',
  );
  String get pairFailed => _t(
    'Code refusé ou restaurant injoignable.',
    'Code refused, or the store can’t be reached.',
    'Código rechazado o restaurante inaccesible.',
    'Code abgelehnt oder Restaurant nicht erreichbar.',
    'Kode geweier, of die winkel kan nie bereik word nie.',
  );
  String get offline => _t(
    'Borne hors ligne — veuillez commander au comptoir.',
    'Kiosk offline — please order at the counter.',
    'Quiosco fuera de línea — pida en el mostrador.',
    'Bestellterminal offline — bitte an der Kasse bestellen.',
    'Kiosk vanlyn — bestel asseblief by die toonbank.',
  );
}
