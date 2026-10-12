import 'package:flutter/widgets.dart';

import '../i18n.dart';

/// The quick-serve counter (Copper Lantern Express) on the POS: French,
/// English, Spanish, German and Afrikaans.
class Q {
  final String lang;
  const Q(this.lang);

  static Q of(BuildContext context) => Q(L.of(context).lang);

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

  String get orders => _t(
    'Commandes',
    'Orders',
    'Pedidos',
    'Bestellungen',
    'Bestellings',
    th: 'ออร์เดอร์',
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
  String orderNo(int n) => _t(
    'Commande n° $n',
    'Order #$n',
    'Pedido n.º $n',
    'Bestellung Nr. $n',
    'Bestelling #$n',
    th: 'ออร์เดอร์ #$n',
  );

  String status(String s) => switch (s) {
    'DRAFT' || 'WAITING' => waitingToPay,
    'PREPARING' => _t(
      'En préparation',
      'Preparing',
      'Preparando',
      'In Zubereitung',
      'Word voorberei',
      th: 'กำลังเตรียม',
    ),
    'READY' => _t(
      'Prête',
      'Ready',
      'Lista',
      'Fertig',
      'Gereed',
      th: 'พร้อมแล้ว',
    ),
    _ => _t(
      'Remise',
      'Picked up',
      'Entregada',
      'Abgeholt',
      'Afgehaal',
      th: 'รับแล้ว',
    ),
  };

  String get paid =>
      _t('Payée', 'Paid', 'Pagada', 'Bezahlt', 'Betaal', th: 'ชำระแล้ว');
  String get toPay =>
      _t('À payer', 'To pay', 'Por pagar', 'Offen', 'Te betaal', th: 'รอชำระ');
  String get markReady => _t(
    'Marquer prête',
    'Mark ready',
    'Marcar lista',
    'Als fertig markieren',
    'Merk as gereed',
    th: 'แจ้งว่าพร้อม',
  );
  String get markPickedUp => _t(
    'Remise au client',
    'Picked up',
    'Entregada al cliente',
    'Abgeholt',
    'Afgehaal',
    th: 'รับแล้ว',
  );
  String get fromKiosk => _t(
    'Borne libre-service',
    'Self-order kiosk',
    'Quiosco de autoservicio',
    'Bestellterminal',
    'Selfbestel-kiosk',
    th: 'ตู้สั่งอาหาร',
  );
  String get pairKiosk => _t(
    'Jumeler une borne',
    'Pair a kiosk',
    'Vincular un quiosco',
    'Bestellterminal koppeln',
    'Koppel ’n kiosk',
    th: 'จับคู่ตู้สั่งอาหาร',
  );
  String get kiosks => _t(
    'Bornes',
    'Kiosks',
    'Quioscos',
    'Bestellterminals',
    'Kioske',
    th: 'ตู้สั่งอาหาร',
  );
  String get noKiosks => _t(
    'Aucune borne jumelée',
    'No kiosk is paired',
    'No hay ningún quiosco vinculado',
    'Kein Bestellterminal gekoppelt',
    'Geen kiosk is gekoppel nie',
    th: 'ยังไม่มีตู้สั่งอาหารที่จับคู่',
  );
  String get unpair => _t(
    'Dissocier',
    'Unpair',
    'Desvincular',
    'Entkoppeln',
    'Ontkoppel',
    th: 'ยกเลิกการจับคู่',
  );
  String unpairTitle(String name) => _t(
    'Dissocier «\u00A0$name\u00A0»\u00A0?',
    'Unpair “$name”?',
    '¿Desvincular «$name»?',
    '„$name“ entkoppeln?',
    'Ontkoppel “$name”?',
    th: 'ยกเลิกการจับคู่ “$name”?',
  );
  String get unpairBody => _t(
    'Elle cesse aussitôt de prendre des commandes et revient à l’écran de jumelage. '
        'Pour la réutiliser, jumelez-la avec un nouveau code.',
    'It stops taking orders at once and goes back to its pairing screen. '
        'To use it again, pair it with a new code.',
    'Deja de tomar pedidos al instante y vuelve a su pantalla de vinculación. '
        'Para usarlo de nuevo, vincúlelo con un código nuevo.',
    'Es nimmt sofort keine Bestellungen mehr an und zeigt wieder den Kopplungsbildschirm. '
        'Zum erneuten Einsatz mit einem neuen Code koppeln.',
    'Dit neem dadelik geen bestellings meer nie en gaan terug na sy koppelskerm. '
        'Koppel dit met ’n nuwe kode om dit weer te gebruik.',
    th: 'ตู้จะหยุดรับออร์เดอร์ทันทีและกลับไปที่หน้าจับคู่ หากต้องการใช้อีกครั้ง ให้จับคู่ด้วยรหัสใหม่',
  );
  String unpaired(String name) => _t(
    '«\u00A0$name\u00A0» dissociée',
    '“$name” unpaired',
    '«$name» desvinculado',
    '„$name“ entkoppelt',
    '“$name” ontkoppel',
    th: 'ยกเลิกการจับคู่ “$name” แล้ว',
  );
  String pairKioskBody(int minutes) => _t(
    'Entrez ce code sur la borne. Valide $minutes minutes, une seule fois.',
    'Type this code on the kiosk. Valid for $minutes minutes, once.',
    'Ingrese este código en el quiosco. Válido $minutes minutos, una sola vez.',
    'Diesen Code am Bestellterminal eingeben. $minutes Minuten gültig, einmalig.',
    'Tik hierdie kode op die kiosk in. Geldig vir $minutes minute, een keer.',
    th: 'พิมพ์รหัสนี้ที่ตู้สั่งอาหาร ใช้ได้ $minutes นาที และใช้ได้ครั้งเดียว',
  );
  String get noOrders => _t(
    'Aucune commande payée aujourd’hui',
    'No paid orders today',
    'No hay pedidos pagados hoy',
    'Heute keine bezahlten Bestellungen',
    'Geen betaalde bestellings vandag nie',
    th: 'วันนี้ยังไม่มีออร์เดอร์ที่ชำระแล้ว',
  );
  String get waitingToPay => _t(
    'En attente de paiement',
    'Waiting to pay',
    'Por pagar',
    'Zahlung offen',
    'Wag om te betaal',
    th: 'รอชำระเงิน',
  );
  String get kiosk =>
      _t('Borne', 'Kiosk', 'Quiosco', 'Terminal', 'Kiosk', th: 'ตู้สั่งอาหาร');
  String kioskOrder(String n) => _t(
    'Borne $n · à payer',
    'Kiosk $n · to pay',
    'Quiosco $n · por pagar',
    'Terminal $n · zu zahlen',
    'Kiosk $n · te betaal',
    th: 'ตู้ $n · รอชำระ',
  );
  String get kioskTicket => _t(
    'Imprimer un billet pour les commandes de borne',
    'Print a ticket for kiosk orders',
    'Imprimir un ticket para los pedidos del quiosco',
    'Bon für Terminal-Bestellungen drucken',
    'Druk ’n kaartjie vir kioskbestellings',
    th: 'พิมพ์ใบสั่งสำหรับออร์เดอร์จากตู้',
  );
  String get kioskTicketHelp => _t(
    'Le client reçoit son numéro, ses articles et le total sur l’imprimante à reçus.',
    'The guest gets their number, items and total on the receipt printer.',
    'El cliente recibe su número, sus artículos y el total en la impresora de recibos.',
    'Der Gast erhält Nummer, Artikel und Summe auf dem Bondrucker.',
    'Die gas kry hul nommer, items en totaal op die kwitansiedrukker.',
    th: 'ลูกค้าจะได้รับหมายเลข รายการ และยอดรวมจากเครื่องพิมพ์ใบเสร็จ',
  );
  String get noKioskOrders => _t(
    'Aucune commande de borne en attente',
    'No kiosk orders waiting',
    'No hay pedidos de quiosco en espera',
    'Keine wartenden Terminal-Bestellungen',
    'Geen kioskbestellings wag nie',
    th: 'ไม่มีออร์เดอร์จากตู้ที่รออยู่',
  );
  String get discard => _t(
    'Annuler la commande',
    'Clear order',
    'Anular el pedido',
    'Bestellung löschen',
    'Vee bestelling uit',
    th: 'ล้างออร์เดอร์',
  );
  String get discardTitle => _t(
    'Annuler cette commande non payée ?',
    'Clear this unpaid order?',
    '¿Anular este pedido sin pagar?',
    'Diese unbezahlte Bestellung löschen?',
    'Vee hierdie onbetaalde bestelling uit?',
    th: 'ล้างออร์เดอร์ที่ยังไม่ชำระนี้?',
  );
  String get switchTitle => _t(
    'Annuler la commande en cours pour encaisser celle de la borne ?',
    'Clear the order being rung to take this kiosk order?',
    '¿Anular el pedido en curso para cobrar el del quiosco?',
    'Aktuelle Bestellung löschen und die Terminal-Bestellung kassieren?',
    'Vee die huidige bestelling uit om hierdie kioskbestelling te neem?',
    th: 'ล้างออร์เดอร์ที่กำลังคิดเงินอยู่เพื่อรับออร์เดอร์จากตู้นี้?',
  );
  String get recall => _t(
    'Rappeler',
    'Recall',
    'Recuperar',
    'Zurückholen',
    'Roep terug',
    th: 'เรียกคืน',
  );
  String get reprint => _t(
    'Réimprimer',
    'Reprint',
    'Reimprimir',
    'Nachdrucken',
    'Druk weer',
    th: 'พิมพ์ซ้ำ',
  );
  String get defaultMode => _t(
    'Commande au comptoir par défaut',
    'Counter orders start as',
    'Los pedidos del mostrador empiezan como',
    'Thekenbestellungen beginnen als',
    'Toonbankbestellings begin as',
    th: 'ออร์เดอร์ที่เคาน์เตอร์เริ่มต้นเป็น',
  );
  String get serviceMode => _t(
    'Sur place ou pour emporter',
    'Dine in or take out',
    'Para comer aquí o para llevar',
    'Hier essen oder mitnehmen',
    'Eet hier of wegneem',
    th: 'ทานที่ร้านหรือกลับบ้าน',
  );
  String get pickupBoard => _t(
    'Écran de retrait',
    'Pickup board',
    'Pantalla de entrega',
    'Abholanzeige',
    'Afhaalbord',
    th: 'จอแจ้งรับอาหาร',
  );
  String get pickupBoardBody => _t(
    'Ouvrez cette adresse sur la télé ou dans un navigateur, ou balayez le code.',
    'Open this address on the TV or in any browser, or scan the code.',
    'Abra esta dirección en la tele o en cualquier navegador, o escanee el código.',
    'Öffnen Sie diese Adresse auf dem Fernseher oder in einem Browser, oder scannen Sie den Code.',
    'Maak hierdie adres oop op die TV of in enige blaaier, of skandeer die kode.',
    th: 'เปิดที่อยู่นี้บนทีวีหรือในเบราว์เซอร์ใดก็ได้ หรือสแกนรหัส',
  );

  /// Alcohol on an order: check the guest's ID against the store's legal age.
  String idCheck(int age) => _t(
    'Alcool — vérifier une pièce d’identité ($age ans et plus)',
    'Alcohol — check ID ($age+)',
    'Alcohol — verificar identificación ($age+)',
    'Alkohol — Ausweis prüfen (ab $age)',
    'Alkohol — kontroleer ID ($age+)',
    th: 'แอลกอฮอล์ — ตรวจบัตรประชาชน ($age+)',
  );
  String items(int n) => _t(
    n == 1 ? '1 article' : '$n articles',
    n == 1 ? '1 item' : '$n items',
    n == 1 ? '1 artículo' : '$n artículos',
    '$n Artikel',
    n == 1 ? '1 item' : '$n items',
    th: '$n รายการ',
  );
  String get all => _t('Tout', 'All', 'Todo', 'Alle', 'Alles', th: 'ทั้งหมด');
  String get newOrder => _t(
    'Nouvelle commande',
    'New order',
    'Nuevo pedido',
    'Neue Bestellung',
    'Nuwe bestelling',
    th: 'ออร์เดอร์ใหม่',
  );
  String dineInCount(int n) => _t(
    'Sur place : $n',
    'Dine in: $n',
    'Para comer aquí: $n',
    'Hier essen: $n',
    'Eet hier: $n',
    th: 'ทานที่ร้าน: $n',
  );
  String takeOutCount(int n) => _t(
    'Pour emporter : $n',
    'Take out: $n',
    'Para llevar: $n',
    'Zum Mitnehmen: $n',
    'Wegneem: $n',
    th: 'กลับบ้าน: $n',
  );
  String cashLine(String amount) => _t(
    'Comptant : $amount',
    'Cash: $amount',
    'Efectivo: $amount',
    'Bar: $amount',
    'Kontant: $amount',
    th: 'เงินสด: $amount',
  );
}
