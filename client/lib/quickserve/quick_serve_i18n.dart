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
  String get newDineIn => _t(
    'Nouvelle — sur place',
    'New — dine in',
    'Nuevo — para comer aquí',
    'Neu — hier essen',
  );
  String get newTakeOut => _t(
    'Nouvelle — pour emporter',
    'New — take out',
    'Nuevo — para llevar',
    'Neu — zum Mitnehmen',
  );
  String get dineIn =>
      _t('Sur place', 'Dine in', 'Para comer aquí', 'Hier essen');
  String get takeOut =>
      _t('Pour emporter', 'Take out', 'Para llevar', 'Zum Mitnehmen');
  String orderNo(int n) =>
      _t('Commande n° $n', 'Order #$n', 'Pedido n.º $n', 'Bestellung Nr. $n');

  String status(String s) => switch (s) {
    'NEW' => _t(
      'En cours de saisie',
      'Being rung',
      'En captura',
      'Wird erfasst',
    ),
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
    'Aucune commande en cours',
    'No open orders',
    'No hay pedidos abiertos',
    'Keine offenen Bestellungen',
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
  String cashLine(String amount) => _t(
    'Comptant : $amount',
    'Cash: $amount',
    'Efectivo: $amount',
    'Bar: $amount',
  );
}
