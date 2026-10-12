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
    'th': 'ไทย',
  };

  String _t(
    String fr,
    String en,
    String es,
    String de,
    String af, {
    required String th,
  }) => switch (lang) {
    'fr' => fr,
    'es' => es,
    'de' => de,
    'af' => af,
    'th' => th,
    _ => en,
  };

  // welcome and dine in / take out
  String get touchToOrder => _t(
    'Touchez pour commander',
    'Touch to order',
    'Toque para ordenar',
    'Zum Bestellen berühren',
    'Raak om te bestel',
    th: 'แตะเพื่อสั่งอาหาร',
  );
  String get chooseMode => _t(
    'Sur place ou pour emporter ?',
    'Eating here or taking out?',
    '¿Para comer aquí o para llevar?',
    'Hier essen oder mitnehmen?',
    'Eet u hier of neem u weg?',
    th: 'ทานที่ร้านหรือกลับบ้าน?',
  );
  String get dineIn => _t(
    'Sur place',
    'Dine in',
    'Para comer aquí',
    'Hier essen',
    'Eet hier',
    th: 'ทานที่ร้าน',
  );
  String get takeOut => _t(
    'Pour emporter',
    'Take out',
    'Para llevar',
    'Zum Mitnehmen',
    'Wegneem',
    th: 'กลับบ้าน',
  );

  // the menu
  String get chooseSize => _t(
    'Choisissez un format',
    'Choose a size',
    'Elija un tamaño',
    'Größe wählen',
    'Kies ’n grootte',
    th: 'กรุณาเลือกขนาด',
  );
  String addFor(String price) => _t(
    'Ajouter — $price',
    'Add — $price',
    'Agregar — $price',
    'Hinzufügen — $price',
    'Voeg by — $price',
    th: 'เพิ่ม — $price',
  );
  String added(String name) => _t(
    '$name ajouté',
    '$name added',
    '$name agregado',
    '$name hinzugefügt',
    '$name bygevoeg',
    th: 'เพิ่ม $name แล้ว',
  );
  String viewOrder(int n) => _t(
    'Ma commande ($n)',
    'My order ($n)',
    'Mi pedido ($n)',
    'Meine Bestellung ($n)',
    'My bestelling ($n)',
    th: 'ออร์เดอร์ของฉัน ($n)',
  );

  /// The alcohol note, naming the store's legal drinking age (21 in the US).
  String idNote(int age) => _t(
    'Alcool — $age ans et plus. Le personnel vérifiera une pièce d’identité au comptoir.',
    'Alcohol — $age+ only. Staff will check ID at the counter.',
    'Alcohol — solo mayores de $age. El personal verificará su identificación en el mostrador.',
    'Alkohol erst ab $age — das Personal prüft an der Kasse Ihren Ausweis.',
    'Alkohol — slegs $age+. Personeel sal u ID by die toonbank kontroleer.',
    th: 'เครื่องดื่มแอลกอฮอล์ — เฉพาะอายุ $age ปีขึ้นไป พนักงานจะตรวจบัตรประชาชนที่เคาน์เตอร์',
  );

  // "Add a drink?" (the store picks the rows; once per order)
  String get completeMeal => _t(
    'Pour compléter votre repas',
    'Complete your meal',
    'Complete su comida',
    'Ihr Menü vervollständigen',
    'Voltooi u ete',
    th: 'เพิ่มให้ครบมื้อ',
  );
  String get addDrink => _t(
    'Ajouter une boisson ?',
    'Add a drink?',
    '¿Agregar una bebida?',
    'Ein Getränk dazu?',
    '’n Drankie daarby?',
    th: 'รับเครื่องดื่มเพิ่มไหม?',
  );
  String get addFries => _t(
    'Ajouter des frites ?',
    'Add fries?',
    '¿Agregar papas fritas?',
    'Pommes dazu?',
    'Skyfies daarby?',
    th: 'รับเฟรนช์ฟรายส์เพิ่มไหม?',
  );
  String get addDessert => _t(
    'Un petit dessert ?',
    'Something sweet?',
    '¿Algo dulce?',
    'Etwas Süßes?',
    'Iets soets?',
    th: 'รับของหวานเพิ่มไหม?',
  );
  String get noThanks => _t(
    'Non merci, continuer',
    'No thanks, continue',
    'No, gracias, continuar',
    'Nein danke, weiter',
    'Nee dankie, gaan voort',
    th: 'ไม่เป็นไร ดำเนินการต่อ',
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
    th: 'ออร์เดอร์ของคุณ',
  );
  String get emptyOrder => _t(
    'Votre commande est vide',
    'Your order is empty',
    'Su pedido está vacío',
    'Ihre Bestellung ist leer',
    'U bestelling is leeg',
    th: 'ยังไม่มีรายการในออร์เดอร์',
  );
  String get subtotal => _t(
    'Sous-total',
    'Subtotal',
    'Subtotal',
    'Zwischensumme',
    'Subtotaal',
    th: 'ยอดรวมย่อย',
  );
  String get taxesAtCounter => _t(
    'Taxes en sus, payées au comptoir',
    'Taxes added at the counter',
    'Impuestos agregados en el mostrador',
    'Steuern kommen an der Kasse hinzu',
    'Belasting word by die toonbank bygevoeg',
    th: 'ภาษีจะคิดที่เคาน์เตอร์',
  );
  String get placeOrder => _t(
    'Commander',
    'Place order',
    'Hacer el pedido',
    'Bestellen',
    'Plaas bestelling',
    th: 'ยืนยันการสั่ง',
  );
  String get sending => _t(
    'Envoi…',
    'Sending…',
    'Enviando…',
    'Wird gesendet…',
    'Stuur tans…',
    th: 'กำลังส่ง…',
  );
  String get sendFailed => _t(
    'La commande n’a pas pu être envoyée. Réessayez ou adressez-vous au comptoir.',
    'The order could not be sent. Try again or ask at the counter.',
    'No se pudo enviar el pedido. Inténtelo de nuevo o pregunte en el mostrador.',
    'Die Bestellung konnte nicht gesendet werden. Bitte erneut versuchen oder an der Kasse fragen.',
    'Die bestelling kon nie gestuur word nie. Probeer weer of vra by die toonbank.',
    th: 'ส่งออร์เดอร์ไม่สำเร็จ กรุณาลองใหม่หรือติดต่อที่เคาน์เตอร์',
  );
  String get back =>
      _t('Retour', 'Back', 'Volver', 'Zurück', 'Terug', th: 'ย้อนกลับ');
  String get startOver => _t(
    'Recommencer',
    'Start over',
    'Empezar de nuevo',
    'Neu beginnen',
    'Begin oor',
    th: 'เริ่มใหม่',
  );

  // the last screen
  String get yourNumber => _t(
    'Votre numéro de commande',
    'Your order number is',
    'Su número de pedido es',
    'Ihre Bestellnummer lautet',
    'U bestelnommer is',
    th: 'หมายเลขออร์เดอร์ของคุณคือ',
  );
  String get payAtCounter => _t(
    'Veuillez payer au comptoir.',
    'Please pay at the counter.',
    'Por favor pague en el mostrador.',
    'Bitte an der Kasse bezahlen.',
    'Betaal asseblief by die toonbank.',
    th: 'กรุณาชำระเงินที่เคาน์เตอร์',
  );
  String get takeTicket => _t(
    'Apportez votre billet au comptoir pour payer.',
    'Take your ticket to the counter to pay.',
    'Lleve su ticket al mostrador para pagar.',
    'Bringen Sie Ihren Bon zum Bezahlen an die Kasse.',
    'Neem u kaartjie na die toonbank om te betaal.',
    th: 'กรุณานำใบสั่งไปชำระเงินที่เคาน์เตอร์',
  );
  String get thanks =>
      _t('Merci', 'Thank you', 'Gracias', 'Danke', 'Dankie', th: 'ขอบคุณ');

  // setup (staff only)
  String get findingStore => _t(
    'Recherche du restaurant…',
    'Looking for the store…',
    'Buscando el restaurante…',
    'Restaurant wird gesucht…',
    'Soek die winkel…',
    th: 'กำลังค้นหาร้าน…',
  );
  String get pairTitle => _t(
    'Jumeler cette borne',
    'Pair this kiosk',
    'Vincular este quiosco',
    'Dieses Bestellterminal koppeln',
    'Koppel hierdie kiosk',
    th: 'จับคู่ตู้สั่งอาหารนี้',
  );
  String get pairHelp => _t(
    'Sur la caisse, ouvrez Commandes puis Jumeler une borne, et entrez le code affiché.',
    'On the POS, open Orders, then Pair a kiosk, and type the code it shows.',
    'En la caja, abra Pedidos y luego Vincular un quiosco, e ingrese el código.',
    'An der Kasse Bestellungen und dann Bestellterminal koppeln öffnen und den Code eingeben.',
    'Maak op die POS Bestellings oop, dan Koppel ’n kiosk, en tik die kode in wat dit wys.',
    th: 'ที่ POS เปิด ออร์เดอร์ แล้วเลือก จับคู่ตู้สั่งอาหาร และพิมพ์รหัสที่แสดง',
  );
  String get storeAddress => _t(
    'Adresse du restaurant (facultatif)',
    'Store address (optional)',
    'Dirección del restaurante (opcional)',
    'Adresse des Restaurants (optional)',
    'Winkeladres (opsioneel)',
    th: 'ที่อยู่ร้าน (ไม่บังคับ)',
  );
  String get code => _t(
    'Code à 6 chiffres',
    '6-digit code',
    'Código de 6 dígitos',
    '6-stelliger Code',
    '6-syferkode',
    th: 'รหัส 6 หลัก',
  );
  String get pair =>
      _t('Jumeler', 'Pair', 'Vincular', 'Koppeln', 'Koppel', th: 'จับคู่');
  String get searchAgain => _t(
    'Chercher encore',
    'Search again',
    'Buscar de nuevo',
    'Erneut suchen',
    'Soek weer',
    th: 'ค้นหาอีกครั้ง',
  );
  String get notFound => _t(
    'Aucun comptoir libre-service trouvé sur ce Wi-Fi. Entrez son adresse.',
    'No quick-serve store found on this Wi-Fi. Type its address.',
    'No se encontró ningún restaurante de autoservicio en este Wi-Fi. Ingrese su dirección.',
    'Kein Schnellrestaurant in diesem WLAN gefunden. Adresse eingeben.',
    'Geen kitsdienswinkel op hierdie Wi-Fi gevind nie. Tik sy adres in.',
    th: 'ไม่พบร้านแบบเคาน์เตอร์ใน Wi-Fi นี้ กรุณาพิมพ์ที่อยู่ร้าน',
  );
  String get pairFailed => _t(
    'Code refusé ou restaurant injoignable.',
    'Code refused, or the store can’t be reached.',
    'Código rechazado o restaurante inaccesible.',
    'Code abgelehnt oder Restaurant nicht erreichbar.',
    'Kode geweier, of die winkel kan nie bereik word nie.',
    th: 'รหัสไม่ถูกต้อง หรือเชื่อมต่อร้านไม่ได้',
  );
  String get offline => _t(
    'Borne hors ligne — veuillez commander au comptoir.',
    'Kiosk offline — please order at the counter.',
    'Quiosco fuera de línea — pida en el mostrador.',
    'Bestellterminal offline — bitte an der Kasse bestellen.',
    'Kiosk vanlyn — bestel asseblief by die toonbank.',
    th: 'ตู้สั่งอาหารออฟไลน์ — กรุณาสั่งที่เคาน์เตอร์',
  );

  // the menu changed during the order (an item taken off, a new price)
  String get menuChanged => _t(
    'Le menu vient de changer',
    'The menu just changed',
    'El menú acaba de cambiar',
    'Die Speisekarte hat sich gerade geändert',
    'Die spyskaart het pas verander',
    th: 'เมนูเพิ่งมีการเปลี่ยนแปลง',
  );
  String removedFromOrder(String name) => _t(
    '$name n’est plus offert et a été retiré de votre commande.',
    '$name is no longer available and was removed from your order.',
    '$name ya no está disponible y se quitó de su pedido.',
    '$name ist nicht mehr erhältlich und wurde aus Ihrer Bestellung entfernt.',
    '$name is nie meer beskikbaar nie en is uit u bestelling verwyder.',
    th: '$name ไม่มีจำหน่ายแล้ว และถูกนำออกจากออร์เดอร์ของคุณ',
  );
  String priceNowIs(String name, String price) => _t(
    'Le prix de $name est maintenant $price.',
    'The price of $name is now $price.',
    'El precio de $name ahora es $price.',
    'Der Preis von $name beträgt jetzt $price.',
    'Die prys van $name is nou $price.',
    th: 'ราคาของ $name ตอนนี้คือ $price',
  );
  String get keepNewPrices => _t(
    'OK, garder les nouveaux prix',
    'OK, keep the new prices',
    'De acuerdo, mantener los precios nuevos',
    'OK, neue Preise übernehmen',
    'Goed, hou die nuwe pryse',
    th: 'ตกลง ใช้ราคาใหม่',
  );
  String get notInYourOrder => _t(
    'Pas dans votre commande :',
    'Not in your order:',
    'No incluido en su pedido:',
    'Nicht in Ihrer Bestellung:',
    'Nie in u bestelling nie:',
    th: 'ไม่อยู่ในออร์เดอร์ของคุณ:',
  );
  String noLongerAvailable(String name) => _t(
    '$name — n’est plus offert',
    '$name — no longer available',
    '$name — ya no está disponible',
    '$name — nicht mehr erhältlich',
    '$name — nie meer beskikbaar nie',
    th: '$name — ไม่มีจำหน่ายแล้ว',
  );
  String priceChangedAskCounter(String name, String price) => _t(
    '$name — nouveau prix $price, demandez au comptoir',
    '$name — new price $price, ask at the counter',
    '$name — nuevo precio $price, pregunte en el mostrador',
    '$name — neuer Preis $price, bitte an der Kasse fragen',
    '$name — nuwe prys $price, vra by die toonbank',
    th: '$name — ราคาใหม่ $price กรุณาสอบถามที่เคาน์เตอร์',
  );
}
