import 'package:flutter/widgets.dart';

import '../i18n.dart';

/// The quick-serve counter (Copper Lantern Express) on the POS: French,
/// English, Spanish, German and Afrikaans.
class Q {
  final String lang;
  const Q(this.lang);

  static Q of(BuildContext context) => Q(L.of(context).lang);

  String _t(String fr, String en, String es, String de, String af) =>
      switch (lang) {
        'fr' => fr,
        'es' => es,
        'de' => de,
        'af' => af,
        _ => en,
      };

  String get orders =>
      _t('Commandes', 'Orders', 'Pedidos', 'Bestellungen', 'Bestellings');
  String get dineIn =>
      _t('Sur place', 'Dine in', 'Para comer aquí', 'Hier essen', 'Eet hier');
  String get takeOut => _t(
    'Pour emporter',
    'Take out',
    'Para llevar',
    'Zum Mitnehmen',
    'Wegneem',
  );
  String orderNo(int n) => _t(
    'Commande n° $n',
    'Order #$n',
    'Pedido n.º $n',
    'Bestellung Nr. $n',
    'Bestelling #$n',
  );

  String status(String s) => switch (s) {
    'DRAFT' || 'WAITING' => waitingToPay,
    'PREPARING' => _t(
      'En préparation',
      'Preparing',
      'Preparando',
      'In Zubereitung',
      'Word voorberei',
    ),
    'READY' => _t('Prête', 'Ready', 'Lista', 'Fertig', 'Gereed'),
    _ => _t('Remise', 'Picked up', 'Entregada', 'Abgeholt', 'Afgehaal'),
  };

  String get paid => _t('Payée', 'Paid', 'Pagada', 'Bezahlt', 'Betaal');
  String get toPay =>
      _t('À payer', 'To pay', 'Por pagar', 'Offen', 'Te betaal');
  String get markReady => _t(
    'Marquer prête',
    'Mark ready',
    'Marcar lista',
    'Als fertig markieren',
    'Merk as gereed',
  );
  String get markPickedUp => _t(
    'Remise au client',
    'Picked up',
    'Entregada al cliente',
    'Abgeholt',
    'Afgehaal',
  );
  String get fromKiosk => _t(
    'Borne libre-service',
    'Self-order kiosk',
    'Quiosco de autoservicio',
    'Bestellterminal',
    'Selfbestel-kiosk',
  );
  String get pairKiosk => _t(
    'Jumeler une borne',
    'Pair a kiosk',
    'Vincular un quiosco',
    'Bestellterminal koppeln',
    'Koppel ’n kiosk',
  );
  String pairKioskBody(int minutes) => _t(
    'Entrez ce code sur la borne. Valide $minutes minutes, une seule fois.',
    'Type this code on the kiosk. Valid for $minutes minutes, once.',
    'Ingrese este código en el quiosco. Válido $minutes minutos, una sola vez.',
    'Diesen Code am Bestellterminal eingeben. $minutes Minuten gültig, einmalig.',
    'Tik hierdie kode op die kiosk in. Geldig vir $minutes minute, een keer.',
  );
  String get noOrders => _t(
    'Aucune commande payée aujourd’hui',
    'No paid orders today',
    'No hay pedidos pagados hoy',
    'Heute keine bezahlten Bestellungen',
    'Geen betaalde bestellings vandag nie',
  );
  String get waitingToPay => _t(
    'En attente de paiement',
    'Waiting to pay',
    'Por pagar',
    'Zahlung offen',
    'Wag om te betaal',
  );
  String get kiosk => _t('Borne', 'Kiosk', 'Quiosco', 'Terminal', 'Kiosk');
  String kioskOrder(String n) => _t(
    'Borne $n · à payer',
    'Kiosk $n · to pay',
    'Quiosco $n · por pagar',
    'Terminal $n · zu zahlen',
    'Kiosk $n · te betaal',
  );
  String get kioskTicket => _t(
    'Imprimer un billet pour les commandes de borne',
    'Print a ticket for kiosk orders',
    'Imprimir un ticket para los pedidos del quiosco',
    'Bon für Terminal-Bestellungen drucken',
    'Druk ’n kaartjie vir kioskbestellings',
  );
  String get kioskTicketHelp => _t(
    'Le client reçoit son numéro, ses articles et le total sur l’imprimante à reçus.',
    'The guest gets their number, items and total on the receipt printer.',
    'El cliente recibe su número, sus artículos y el total en la impresora de recibos.',
    'Der Gast erhält Nummer, Artikel und Summe auf dem Bondrucker.',
    'Die gas kry hul nommer, items en totaal op die kwitansiedrukker.',
  );
  String get noKioskOrders => _t(
    'Aucune commande de borne en attente',
    'No kiosk orders waiting',
    'No hay pedidos de quiosco en espera',
    'Keine wartenden Terminal-Bestellungen',
    'Geen kioskbestellings wag nie',
  );
  String get discard => _t(
    'Annuler la commande',
    'Clear order',
    'Anular el pedido',
    'Bestellung löschen',
    'Vee bestelling uit',
  );
  String get discardTitle => _t(
    'Annuler cette commande non payée ?',
    'Clear this unpaid order?',
    '¿Anular este pedido sin pagar?',
    'Diese unbezahlte Bestellung löschen?',
    'Vee hierdie onbetaalde bestelling uit?',
  );
  String get switchTitle => _t(
    'Annuler la commande en cours pour encaisser celle de la borne ?',
    'Clear the order being rung to take this kiosk order?',
    '¿Anular el pedido en curso para cobrar el del quiosco?',
    'Aktuelle Bestellung löschen und die Terminal-Bestellung kassieren?',
    'Vee die huidige bestelling uit om hierdie kioskbestelling te neem?',
  );
  String get recall =>
      _t('Rappeler', 'Recall', 'Recuperar', 'Zurückholen', 'Roep terug');
  String get reprint =>
      _t('Réimprimer', 'Reprint', 'Reimprimir', 'Nachdrucken', 'Druk weer');
  String get defaultMode => _t(
    'Commande au comptoir par défaut',
    'Counter orders start as',
    'Los pedidos del mostrador empiezan como',
    'Thekenbestellungen beginnen als',
    'Toonbankbestellings begin as',
  );
  String get serviceMode => _t(
    'Sur place ou pour emporter',
    'Dine in or take out',
    'Para comer aquí o para llevar',
    'Hier essen oder mitnehmen',
    'Eet hier of wegneem',
  );
  String get pickupBoard => _t(
    'Écran de retrait',
    'Pickup board',
    'Pantalla de entrega',
    'Abholanzeige',
    'Afhaalbord',
  );
  String get pickupBoardBody => _t(
    'Ouvrez cette adresse sur la télé ou dans un navigateur, ou balayez le code.',
    'Open this address on the TV or in any browser, or scan the code.',
    'Abra esta dirección en la tele o en cualquier navegador, o escanee el código.',
    'Öffnen Sie diese Adresse auf dem Fernseher oder in einem Browser, oder scannen Sie den Code.',
    'Maak hierdie adres oop op die TV of in enige blaaier, of skandeer die kode.',
  );

  /// Alcohol on an order: check the guest's ID against the store's legal age.
  String idCheck(int age) => _t(
    'Alcool — vérifier une pièce d’identité ($age ans et plus)',
    'Alcohol — check ID ($age+)',
    'Alcohol — verificar identificación ($age+)',
    'Alkohol — Ausweis prüfen (ab $age)',
    'Alkohol — kontroleer ID ($age+)',
  );
  String items(int n) => _t(
    n == 1 ? '1 article' : '$n articles',
    n == 1 ? '1 item' : '$n items',
    n == 1 ? '1 artículo' : '$n artículos',
    '$n Artikel',
    n == 1 ? '1 item' : '$n items',
  );
  String get all => _t('Tout', 'All', 'Todo', 'Alle', 'Alles');
  String get newOrder => _t(
    'Nouvelle commande',
    'New order',
    'Nuevo pedido',
    'Neue Bestellung',
    'Nuwe bestelling',
  );
  String dineInCount(int n) => _t(
    'Sur place : $n',
    'Dine in: $n',
    'Para comer aquí: $n',
    'Hier essen: $n',
    'Eet hier: $n',
  );
  String takeOutCount(int n) => _t(
    'Pour emporter : $n',
    'Take out: $n',
    'Para llevar: $n',
    'Zum Mitnehmen: $n',
    'Wegneem: $n',
  );
  String cashLine(String amount) => _t(
    'Comptant : $amount',
    'Cash: $amount',
    'Efectivo: $amount',
    'Bar: $amount',
    'Kontant: $amount',
  );
}
