import 'package:flutter/widgets.dart';

import '../i18n.dart';

/// Kitchen tickets and the kitchen screen: French, English and (so a screen
/// is never blank) Spanish.
class K {
  final String lang;
  const K(this.lang);

  static K of(BuildContext context) => K(L.of(context).lang);

  String _t(String fr, String en, String es) =>
      lang == 'fr' ? fr : (lang == 'es' ? es : en);

  String name(String fr, String en) => lang == 'fr' ? fr : en;

  // check screen
  String get send => _t('Envoyer', 'Send', 'Enviar');
  String sendCount(int n) => _t('Envoyer ($n)', 'Send ($n)', 'Enviar ($n)');
  String get sent => _t('Envoyé', 'Sent', 'Enviado');
  String get allSent => _t('Tout est envoyé', 'All sent', 'Todo enviado');
  String sentTo(int tickets) => _t(
    tickets == 1 ? '1 billet envoyé' : '$tickets billets envoyés',
    tickets == 1 ? '1 ticket sent' : '$tickets tickets sent',
    tickets == 1 ? '1 comanda enviada' : '$tickets comandas enviadas',
  );
  String get nothingToSend => _t(
    'Rien de nouveau à envoyer',
    'Nothing new to send',
    'Nada nuevo que enviar',
  );
  String get reprint =>
      _t('Réimprimer les billets', 'Reprint tickets', 'Reimprimir comandas');
  String reprinted(int tickets) => _t(
    '$tickets billet(s) réimprimé(s)',
    '$tickets ticket(s) reprinted',
    '$tickets comanda(s) reimpresa(s)',
  );
  String get nothingToReprint => _t(
    'Rien d’envoyé à réimprimer',
    'Nothing sent to reprint',
    'Nada enviado que reimprimir',
  );
  String get guests => _t('Couverts', 'Guests', 'Comensales');
  String guestsCount(int n) => _t('$n couv.', '$n guests', '$n com.');
  String get guestsTitle => _t(
    'Combien de personnes à la table?',
    'How many guests at the table?',
    '¿Cuántas personas en la mesa?',
  );
  String voidsWaiting(int n) => _t(
    '$n à annuler en cuisine',
    '$n to void in the kitchen',
    '$n por anular en cocina',
  );

  // queue banner
  String printerOffline(int n) => _t(
    n == 1
        ? 'Imprimante de cuisine hors ligne — 1 billet en attente'
        : 'Imprimante de cuisine hors ligne — $n billets en attente',
    n == 1
        ? 'Kitchen printer offline — 1 ticket waiting'
        : 'Kitchen printer offline — $n tickets waiting',
    n == 1
        ? 'Impresora de cocina desconectada — 1 comanda en espera'
        : 'Impresora de cocina desconectada — $n comandas en espera',
  );
  String ticketsWaiting(int n) => _t(
    n == 1 ? '1 billet en attente' : '$n billets en attente',
    n == 1 ? '1 ticket waiting' : '$n tickets waiting',
    n == 1 ? '1 comanda en espera' : '$n comandas en espera',
  );
  String get paperOut => _t(
    'Plus de papier dans l’imprimante de cuisine',
    'Kitchen printer is out of paper',
    'La impresora de cocina no tiene papel',
  );
  String get notConfigured => _t(
    'Aucune imprimante de cuisine configurée',
    'No kitchen printer set up',
    'No hay impresora de cocina configurada',
  );
  String get retry => _t('Réessayer', 'Retry', 'Reintentar');
  String get cancelTickets =>
      _t('Annuler les billets', 'Cancel tickets', 'Cancelar comandas');
  String get cancelConfirm => _t(
    'Les billets en attente ne seront pas imprimés. Prévenez la cuisine de vive voix.',
    'Waiting tickets won’t print. Tell the kitchen in person.',
    'Las comandas en espera no se imprimirán. Avise a la cocina en persona.',
  );

  // kitchen screen
  String get kitchen => _t('Cuisine', 'Kitchen', 'Cocina');
  String get storeUnreachable => _t(
    'Magasin injoignable — nouvel essai…',
    'Can’t reach the store — retrying…',
    'No se puede contactar la tienda; reintentando…',
  );
  String get allStations => _t('Toutes', 'All', 'Todas');
  String get recall => _t('Rappeler', 'Recall', 'Recuperar');
  String get nothingToRecall =>
      _t('Rien à rappeler', 'Nothing to recall', 'Nada que recuperar');
  String get noOrders => _t('Aucune commande', 'No orders', 'Sin pedidos');
  String get tapToFinish =>
      _t('Touchez pour terminer', 'Tap to finish', 'Toque para terminar');
  String get add => _t('AJOUT', 'ADD', 'AÑADIDO');
  String get voidTag => _t('ANNULÉ', 'VOID', 'ANULADO');
  String voidedPart(int n) => _t('−$n annulé', '−$n voided', '−$n anulado');
  String get server => _t('Serveur', 'Server', 'Mesero');
  String get soundOn => _t('Son activé', 'Sound on', 'Sonido activado');
  String get soundOff => _t('Son coupé', 'Sound off', 'Sonido desactivado');
  String get webScreen => _t(
    'Écran de cuisine sur un autre appareil',
    'Kitchen screen on another device',
    'Pantalla de cocina en otro dispositivo',
  );
  String get webScreenHint => _t(
    'Ouvrez cette adresse dans le navigateur d’une tablette ou d’un téléphone sur le Wi-Fi du resto, puis entrez un NIP.',
    'Open this address in a browser on a tablet or phone on the venue Wi-Fi, then enter a PIN.',
    'Abra esta dirección en el navegador de una tableta o teléfono en el Wi-Fi del local e ingrese un PIN.',
  );

  // station setup
  String get setupTitle =>
      _t('Billets de cuisine', 'Kitchen tickets', 'Comandas de cocina');
  String get stations => _t('Stations', 'Stations', 'Estaciones');
  String get addStation =>
      _t('Ajouter une station', 'Add a station', 'Agregar estación');
  String get editStation =>
      _t('Modifier la station', 'Edit station', 'Editar estación');
  String get nameFr =>
      _t('Nom (français)', 'Name (French)', 'Nombre (francés)');
  String get nameEn => _t('Nom (anglais)', 'Name (English)', 'Nombre (inglés)');
  String get output => _t('Sortie', 'Output', 'Salida');
  String get outputPrinter => _t('Imprimante', 'Printer', 'Impresora');
  String get outputScreen => _t('Écran', 'Screen', 'Pantalla');
  String get outputBoth => _t('Les deux', 'Both', 'Ambos');
  String outputName(String o) => o == 'screen'
      ? outputScreen
      : o == 'both'
      ? outputBoth
      : outputPrinter;
  String get printerHost => _t(
    'IP de l’imprimante (vide = imprimante à reçus)',
    'Printer IP (blank = receipt printer)',
    'IP de la impresora (vacío = impresora de recibos)',
  );
  String get receiptPrinter =>
      _t('Imprimante à reçus', 'Receipt printer', 'Impresora de recibos');
  String get port => _t('Port', 'Port', 'Puerto');
  String get paper => _t('Papier', 'Paper', 'Papel');
  String get testPrint =>
      _t('Impression d’essai', 'Test print', 'Impresión de prueba');
  String testOk(String target) => _t(
    'Billet d’essai envoyé à $target',
    'Test ticket sent to $target',
    'Comanda de prueba enviada a $target',
  );
  String get testFailed => _t(
    'Échec — imprimante injoignable',
    'Failed — printer unreachable',
    'Falló: impresora inaccesible',
  );
  String get deleteStation =>
      _t('Supprimer la station', 'Delete station', 'Eliminar estación');
  String get deleteStationConfirm => _t(
    'Les catégories de cette station iront à la station par défaut.',
    'Its categories will go to the default station.',
    'Sus categorías irán a la estación predeterminada.',
  );
  String get categories =>
      _t('Catégories du menu', 'Menu categories', 'Categorías del menú');
  String get itemOverrides => _t(
    'Exceptions par article',
    'Per-item overrides',
    'Excepciones por artículo',
  );
  String get addOverride =>
      _t('Ajouter une exception', 'Add an override', 'Agregar excepción');
  String get defaultStation => _t(
    'Station par défaut (catégories non attribuées)',
    'Default station (unmapped categories)',
    'Estación predeterminada (categorías sin asignar)',
  );
  String get useDefault =>
      _t('Station par défaut', 'Default station', 'Estación predeterminada');
  String get noTicket => _t('Aucun billet', 'No ticket', 'Sin comanda');
  String get ticketLanguage =>
      _t('Langue des billets', 'Ticket language', 'Idioma de las comandas');
  String get languageStore =>
      _t('Langue du magasin', 'Store language', 'Idioma de la tienda');
  String get languageBoth =>
      _t('Français et anglais', 'French and English', 'Francés e inglés');
  String get screenTimers => _t(
    'Minuteries de l’écran (minutes)',
    'Screen timers (minutes)',
    'Temporizadores de pantalla (minutos)',
  );
  String get warnAfter => _t('Jaune après', 'Yellow after', 'Amarillo tras');
  String get lateAfter => _t('Rouge après', 'Red after', 'Rojo tras');
  String get newOrderSound => _t(
    'Son à chaque nouvelle commande',
    'Sound on each new order',
    'Sonido con cada pedido nuevo',
  );
  String get pickItem =>
      _t('Choisir un article', 'Pick an item', 'Elegir artículo');
  String get saved => _t('Enregistré', 'Saved', 'Guardado');
}
