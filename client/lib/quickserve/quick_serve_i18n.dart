import 'package:flutter/widgets.dart';

import '../i18n.dart';

/// The quick-serve counter (Copper Lantern Express) on the POS: French,
/// English, Spanish and German.
class Q {
  final String lang;
  const Q(this.lang);

  static Q of(BuildContext context) => Q(L.of(context).lang);

  String _t(String fr, String en, String es, String de) => switch (lang) {
    'fr' => fr,
    'es' => es,
    'de' => de,
    _ => en,
  };

  String get orders => _t('Commandes', 'Orders', 'Pedidos', 'Bestellungen');
  String get dineIn =>
      _t('Sur place', 'Dine in', 'Para comer aquí', 'Hier essen');
  String get takeOut =>
      _t('Pour emporter', 'Take out', 'Para llevar', 'Zum Mitnehmen');
  String orderNo(int n) =>
      _t('Commande n° $n', 'Order #$n', 'Pedido n.º $n', 'Bestellung Nr. $n');

  String status(String s) => switch (s) {
    'DRAFT' || 'WAITING' => waitingToPay,
    'PREPARING' => _t(
      'En préparation',
      'Preparing',
      'Preparando',
      'In Zubereitung',
    ),
    'READY' => _t('Prête', 'Ready', 'Lista', 'Fertig'),
    _ => _t('Remise', 'Picked up', 'Entregada', 'Abgeholt'),
  };

  String get paid => _t('Payée', 'Paid', 'Pagada', 'Bezahlt');
  String get toPay => _t('À payer', 'To pay', 'Por pagar', 'Offen');
  String get markReady =>
      _t('Marquer prête', 'Mark ready', 'Marcar lista', 'Als fertig markieren');
  String get markPickedUp =>
      _t('Remise au client', 'Picked up', 'Entregada al cliente', 'Abgeholt');
  String get fromKiosk => _t(
    'Borne libre-service',
    'Self-order kiosk',
    'Quiosco de autoservicio',
    'Bestellterminal',
  );
  String get pairKiosk => _t(
    'Jumeler une borne',
    'Pair a kiosk',
    'Vincular un quiosco',
    'Bestellterminal koppeln',
  );
  String pairKioskBody(int minutes) => _t(
    'Entrez ce code sur la borne. Valide $minutes minutes, une seule fois.',
    'Type this code on the kiosk. Valid for $minutes minutes, once.',
    'Ingrese este código en el quiosco. Válido $minutes minutos, una sola vez.',
    'Diesen Code am Bestellterminal eingeben. $minutes Minuten gültig, einmalig.',
  );
  String get noOrders => _t(
    'Aucune commande payée aujourd’hui',
    'No paid orders today',
    'No hay pedidos pagados hoy',
    'Heute keine bezahlten Bestellungen',
  );
  String get waitingToPay => _t(
    'En attente de paiement',
    'Waiting to pay',
    'Por pagar',
    'Zahlung offen',
  );
  String get kiosk => _t('Borne', 'Kiosk', 'Quiosco', 'Terminal');
  String kioskOrder(String n) => _t(
    'Borne $n · à payer',
    'Kiosk $n · to pay',
    'Quiosco $n · por pagar',
    'Terminal $n · zu zahlen',
  );
  String get kioskTicket => _t(
    'Imprimer un billet pour les commandes de borne',
    'Print a ticket for kiosk orders',
    'Imprimir un ticket para los pedidos del quiosco',
    'Bon für Terminal-Bestellungen drucken',
  );
  String get kioskTicketHelp => _t(
    'Le client reçoit son numéro, ses articles et le total sur l’imprimante à reçus.',
    'The guest gets their number, items and total on the receipt printer.',
    'El cliente recibe su número, sus artículos y el total en la impresora de recibos.',
    'Der Gast erhält Nummer, Artikel und Summe auf dem Bondrucker.',
  );
  String get noKioskOrders => _t(
    'Aucune commande de borne en attente',
    'No kiosk orders waiting',
    'No hay pedidos de quiosco en espera',
    'Keine wartenden Terminal-Bestellungen',
  );
  String get discard => _t(
    'Annuler la commande',
    'Clear order',
    'Anular el pedido',
    'Bestellung löschen',
  );
  String get discardTitle => _t(
    'Annuler cette commande non payée ?',
    'Clear this unpaid order?',
    '¿Anular este pedido sin pagar?',
    'Diese unbezahlte Bestellung löschen?',
  );
  String get switchTitle => _t(
    'Annuler la commande en cours pour encaisser celle de la borne ?',
    'Clear the order being rung to take this kiosk order?',
    '¿Anular el pedido en curso para cobrar el del quiosco?',
    'Aktuelle Bestellung löschen und die Terminal-Bestellung kassieren?',
  );
  String get recall => _t('Rappeler', 'Recall', 'Recuperar', 'Zurückholen');
  String get reprint =>
      _t('Réimprimer', 'Reprint', 'Reimprimir', 'Nachdrucken');
  String get defaultMode => _t(
    'Commande au comptoir par défaut',
    'Counter orders start as',
    'Los pedidos del mostrador empiezan como',
    'Thekenbestellungen beginnen als',
  );
  String get serviceMode => _t(
    'Sur place ou pour emporter',
    'Dine in or take out',
    'Para comer aquí o para llevar',
    'Hier essen oder mitnehmen',
  );
  String get pickupBoard => _t(
    'Écran de retrait',
    'Pickup board',
    'Pantalla de entrega',
    'Abholanzeige',
  );
  String pickupBoardBody(String url) => _t(
    'Ouvrez $url sur la télé ou dans un navigateur.',
    'Open $url on the TV or in any browser.',
    'Abra $url en la tele o en cualquier navegador.',
    'Öffnen Sie $url auf dem Fernseher oder in einem Browser.',
  );
  String get idCheck => _t(
    'Alcool — vérifier une pièce d’identité',
    'Alcohol — check ID',
    'Alcohol — verificar identificación',
    'Alkohol — Ausweis prüfen',
  );
  String items(int n) => _t(
    n == 1 ? '1 article' : '$n articles',
    n == 1 ? '1 item' : '$n items',
    n == 1 ? '1 artículo' : '$n artículos',
    '$n Artikel',
  );
  String get all => _t('Tout', 'All', 'Todo', 'Alle');
  String get newOrder =>
      _t('Nouvelle commande', 'New order', 'Nuevo pedido', 'Neue Bestellung');
  String dineInCount(int n) => _t(
    'Sur place : $n',
    'Dine in: $n',
    'Para comer aquí: $n',
    'Hier essen: $n',
  );
  String takeOutCount(int n) => _t(
    'Pour emporter : $n',
    'Take out: $n',
    'Para llevar: $n',
    'Zum Mitnehmen: $n',
  );
  String cashLine(String amount) => _t(
    'Comptant : $amount',
    'Cash: $amount',
    'Efectivo: $amount',
    'Bar: $amount',
  );
}
