import 'package:flutter/widgets.dart';

import '../i18n.dart';

/// Kitchen tickets and the kitchen screen: French, English, German,
/// Afrikaans and (so a screen is never blank) Spanish.
class K {
  final String lang;
  const K(this.lang);

  static K of(BuildContext context) => K(L.of(context).lang);

  String _t(String fr, String en, String es, String de, String af) =>
      switch (lang) {
        'fr' => fr,
        'es' => es,
        'de' => de,
        'af' => af,
        _ => en,
      };

  String name(String fr, String en) => lang == 'fr' ? fr : en;

  // check screen
  String get send => _t('Envoyer', 'Send', 'Enviar', 'Senden', 'Stuur');
  String sendCount(int n) => _t(
    'Envoyer ($n)',
    'Send ($n)',
    'Enviar ($n)',
    'Senden ($n)',
    'Stuur ($n)',
  );
  String get sent => _t('Envoyé', 'Sent', 'Enviado', 'Gesendet', 'Gestuur');
  String get allSent => _t(
    'Tout est envoyé',
    'All sent',
    'Todo enviado',
    'Alles gesendet',
    'Alles gestuur',
  );
  String sentTo(int tickets) => _t(
    tickets == 1 ? '1 billet envoyé' : '$tickets billets envoyés',
    tickets == 1 ? '1 ticket sent' : '$tickets tickets sent',
    tickets == 1 ? '1 comanda enviada' : '$tickets comandas enviadas',
    tickets == 1 ? '1 Bon gesendet' : '$tickets Bons gesendet',
    tickets == 1 ? '1 kaartjie gestuur' : '$tickets kaartjies gestuur',
  );
  String get nothingToSend => _t(
    'Rien de nouveau à envoyer',
    'Nothing new to send',
    'Nada nuevo que enviar',
    'Nichts Neues zu senden',
    'Niks nuuts om te stuur nie',
  );
  String get reprint => _t(
    'Réimprimer les billets',
    'Reprint tickets',
    'Reimprimir comandas',
    'Bons nachdrucken',
    'Druk kaartjies weer',
  );
  String reprinted(int tickets) => _t(
    '$tickets billet(s) réimprimé(s)',
    '$tickets ticket(s) reprinted',
    '$tickets comanda(s) reimpresa(s)',
    '$tickets Bon(s) nachgedruckt',
    '$tickets kaartjie(s) weer gedruk',
  );
  String get nothingToReprint => _t(
    'Rien d’envoyé à réimprimer',
    'Nothing sent to reprint',
    'Nada enviado que reimprimir',
    'Nichts gesendet, nichts nachzudrucken',
    'Niks gestuur om weer te druk nie',
  );
  String get guests => _t('Couverts', 'Guests', 'Comensales', 'Gäste', 'Gaste');
  String guestsCount(int n) =>
      _t('$n couv.', '$n guests', '$n com.', '$n Gäste', '$n gaste');
  String get guestsTitle => _t(
    'Combien de personnes à la table ?',
    'How many guests at the table?',
    '¿Cuántas personas en la mesa?',
    'Wie viele Gäste am Tisch?',
    'Hoeveel gaste by die tafel?',
  );
  String voidsWaiting(int n) => _t(
    '$n à annuler en cuisine',
    '$n to void in the kitchen',
    '$n por anular en cocina',
    '$n in der Küche zu stornieren',
    '$n om in die kombuis te kanselleer',
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
    n == 1
        ? 'Küchendrucker offline — 1 Bon wartet'
        : 'Küchendrucker offline — $n Bons warten',
    n == 1
        ? 'Kombuisdrukker vanlyn — 1 kaartjie wag'
        : 'Kombuisdrukker vanlyn — $n kaartjies wag',
  );
  String ticketsWaiting(int n) => _t(
    n == 1 ? '1 billet en attente' : '$n billets en attente',
    n == 1 ? '1 ticket waiting' : '$n tickets waiting',
    n == 1 ? '1 comanda en espera' : '$n comandas en espera',
    n == 1 ? '1 Bon wartet' : '$n Bons warten',
    n == 1 ? '1 kaartjie wag' : '$n kaartjies wag',
  );
  String get paperOut => _t(
    'Plus de papier dans l’imprimante de cuisine',
    'Kitchen printer is out of paper',
    'La impresora de cocina no tiene papel',
    'Küchendrucker hat kein Papier',
    'Kombuisdrukker se papier is op',
  );
  String get notConfigured => _t(
    'Aucune imprimante de cuisine configurée',
    'No kitchen printer set up',
    'No hay impresora de cocina configurada',
    'Kein Küchendrucker eingerichtet',
    'Geen kombuisdrukker opgestel nie',
  );
  String get retry => _t(
    'Réessayer',
    'Retry',
    'Reintentar',
    'Erneut versuchen',
    'Probeer weer',
  );
  String get cancelTickets => _t(
    'Annuler les billets',
    'Cancel tickets',
    'Cancelar comandas',
    'Bons verwerfen',
    'Kanselleer kaartjies',
  );
  String get cancelConfirm => _t(
    'Les billets en attente ne seront pas imprimés. Prévenez la cuisine de vive voix.',
    'Waiting tickets won’t print. Tell the kitchen in person.',
    'Las comandas en espera no se imprimirán. Avise a la cocina en persona.',
    'Wartende Bons werden nicht gedruckt. Bitte der Küche direkt Bescheid geben.',
    'Wagtende kaartjies sal nie druk nie. Sê vir die kombuis persoonlik.',
  );

  // kitchen screen
  String get kitchen => _t('Cuisine', 'Kitchen', 'Cocina', 'Küche', 'Kombuis');
  String get storeUnreachable => _t(
    'Magasin injoignable — nouvel essai…',
    'Can’t reach the store — retrying…',
    'No se puede contactar la tienda; reintentando…',
    'Kasse nicht erreichbar — neuer Versuch…',
    'Kan nie die winkel bereik nie — probeer weer…',
  );
  String get allStations => _t('Toutes', 'All', 'Todas', 'Alle', 'Alles');
  String get recall =>
      _t('Rappeler', 'Recall', 'Recuperar', 'Zurückholen', 'Roep terug');
  String get nothingToRecall => _t(
    'Rien à rappeler',
    'Nothing to recall',
    'Nada que recuperar',
    'Nichts zum Zurückholen',
    'Niks om terug te roep nie',
  );
  String get noOrders => _t(
    'Aucune commande',
    'No orders',
    'Sin pedidos',
    'Keine Bestellungen',
    'Geen bestellings nie',
  );
  String get tapToFinish => _t(
    'Touchez pour terminer',
    'Tap to finish',
    'Toque para terminar',
    'Tippen zum Abschließen',
    'Tik om klaar te maak',
  );
  String get add => _t('AJOUT', 'ADD', 'AÑADIDO', 'NACHTRAG', 'BYVOEG');
  String get voidTag => _t('ANNULÉ', 'VOID', 'ANULADO', 'STORNO', 'KANSELLEER');
  String voidedPart(int n) => _t(
    '−$n annulé',
    '−$n voided',
    '−$n anulado',
    '−$n storniert',
    '−$n gekanselleer',
  );
  String get server => _t('Serveur', 'Server', 'Mesero', 'Bedienung', 'Kelner');
  String get soundOn =>
      _t('Son activé', 'Sound on', 'Sonido activado', 'Ton an', 'Klank aan');
  String get soundOff =>
      _t('Son coupé', 'Sound off', 'Sonido desactivado', 'Ton aus', 'Klank af');
  String get webScreen => _t(
    'Écran de cuisine sur un autre appareil',
    'Kitchen screen on another device',
    'Pantalla de cocina en otro dispositivo',
    'Küchenmonitor auf einem anderen Gerät',
    'Kombuisskerm op ’n ander toestel',
  );
  String get webScreenHint => _t(
    'Ouvrez cette adresse dans le navigateur d’une tablette ou d’un téléphone sur le Wi-Fi du resto, puis entrez un NIP.',
    'Open this address in a browser on a tablet or phone on the venue Wi-Fi, then enter a PIN.',
    'Abra esta dirección en el navegador de una tableta o teléfono en el Wi-Fi del local e ingrese un PIN.',
    'Diese Adresse im Browser eines Tablets oder Handys im WLAN des Lokals öffnen, dann PIN eingeben.',
    'Maak hierdie adres oop in ’n blaaier op ’n tablet of foon op die plek se Wi-Fi, en voer dan ’n PIN in.',
  );

  // station setup
  String get setupTitle => _t(
    'Billets de cuisine',
    'Kitchen tickets',
    'Comandas de cocina',
    'Küchenbons',
    'Kombuiskaartjies',
  );
  String get stations =>
      _t('Stations', 'Stations', 'Estaciones', 'Stationen', 'Stasies');
  String get addStation => _t(
    'Ajouter une station',
    'Add a station',
    'Agregar estación',
    'Station hinzufügen',
    'Voeg ’n stasie by',
  );
  String get editStation => _t(
    'Modifier la station',
    'Edit station',
    'Editar estación',
    'Station bearbeiten',
    'Wysig stasie',
  );
  String get nameFr => _t(
    'Nom (français)',
    'Name (French)',
    'Nombre (francés)',
    'Name (Französisch)',
    'Naam (Frans)',
  );
  String get nameEn => _t(
    'Nom (anglais)',
    'Name (English)',
    'Nombre (inglés)',
    'Name (Englisch)',
    'Naam (Engels)',
  );
  String get output => _t('Sortie', 'Output', 'Salida', 'Ausgabe', 'Uitvoer');
  String get outputPrinter =>
      _t('Imprimante', 'Printer', 'Impresora', 'Drucker', 'Drukker');
  String get outputScreen =>
      _t('Écran', 'Screen', 'Pantalla', 'Monitor', 'Skerm');
  String get outputBoth => _t('Les deux', 'Both', 'Ambos', 'Beides', 'Albei');
  String outputName(String o) => o == 'screen'
      ? outputScreen
      : o == 'both'
      ? outputBoth
      : outputPrinter;
  String get printerHost => _t(
    'IP de l’imprimante (vide = imprimante à reçus)',
    'Printer IP (blank = receipt printer)',
    'IP de la impresora (vacío = impresora de recibos)',
    'Drucker-IP (leer = Belegdrucker)',
    'Drukker-IP (leeg = kwitansiedrukker)',
  );
  String get receiptPrinter => _t(
    'Imprimante à reçus',
    'Receipt printer',
    'Impresora de recibos',
    'Belegdrucker',
    'Kwitansiedrukker',
  );
  String get port => _t('Port', 'Port', 'Puerto', 'Port', 'Poort');
  String get paper => _t('Papier', 'Paper', 'Papel', 'Papier', 'Papier');
  String get testPrint => _t(
    'Impression d’essai',
    'Test print',
    'Impresión de prueba',
    'Testdruck',
    'Toetsdruk',
  );
  String testOk(String target) => _t(
    'Billet d’essai envoyé à $target',
    'Test ticket sent to $target',
    'Comanda de prueba enviada a $target',
    'Testbon an $target gesendet',
    'Toetskaartjie na $target gestuur',
  );
  String get testFailed => _t(
    'Échec — imprimante injoignable',
    'Failed — printer unreachable',
    'Falló: impresora inaccesible',
    'Fehlgeschlagen — Drucker nicht erreichbar',
    'Misluk — drukker onbereikbaar',
  );
  String get deleteStation => _t(
    'Supprimer la station',
    'Delete station',
    'Eliminar estación',
    'Station löschen',
    'Verwyder stasie',
  );
  String get deleteStationConfirm => _t(
    'Les catégories de cette station iront à la station par défaut.',
    'Its categories will go to the default station.',
    'Sus categorías irán a la estación predeterminada.',
    'Ihre Kategorien gehen an die Standardstation.',
    'Sy kategorieë gaan na die verstekstasie.',
  );
  String get categories => _t(
    'Catégories du menu',
    'Menu categories',
    'Categorías del menú',
    'Kategorien der Speisekarte',
    'Spyskaartkategorieë',
  );
  String get itemOverrides => _t(
    'Exceptions par article',
    'Per-item overrides',
    'Excepciones por artículo',
    'Ausnahmen pro Artikel',
    'Uitsonderings per item',
  );
  String get addOverride => _t(
    'Ajouter une exception',
    'Add an override',
    'Agregar excepción',
    'Ausnahme hinzufügen',
    'Voeg ’n uitsondering by',
  );
  String get defaultStation => _t(
    'Station par défaut (catégories non attribuées)',
    'Default station (unmapped categories)',
    'Estación predeterminada (categorías sin asignar)',
    'Standardstation (nicht zugeordnete Kategorien)',
    'Verstekstasie (ongekoppelde kategorieë)',
  );
  String get useDefault => _t(
    'Station par défaut',
    'Default station',
    'Estación predeterminada',
    'Standardstation',
    'Verstekstasie',
  );
  String get noTicket => _t(
    'Aucun billet',
    'No ticket',
    'Sin comanda',
    'Kein Bon',
    'Geen kaartjie',
  );
  String get ticketLanguage => _t(
    'Langue des billets',
    'Ticket language',
    'Idioma de las comandas',
    'Sprache der Bons',
    'Kaartjietaal',
  );
  String get languageStore => _t(
    'Langue du magasin',
    'Store language',
    'Idioma de la tienda',
    'Sprache des Lokals',
    'Winkeltaal',
  );
  String get languageBoth => _t(
    'Français et anglais',
    'French and English',
    'Francés e inglés',
    'Französisch und Englisch',
    'Frans en Engels',
  );
  String get screenTimers => _t(
    'Minuteries de l’écran (minutes)',
    'Screen timers (minutes)',
    'Temporizadores de pantalla (minutos)',
    'Monitor-Timer (Minuten)',
    'Skermtydhouers (minute)',
  );
  String get warnAfter => _t(
    'Jaune après',
    'Yellow after',
    'Amarillo tras',
    'Gelb nach',
    'Geel na',
  );
  String get lateAfter =>
      _t('Rouge après', 'Red after', 'Rojo tras', 'Rot nach', 'Rooi na');
  String get newOrderSound => _t(
    'Son à chaque nouvelle commande',
    'Sound on each new order',
    'Sonido con cada pedido nuevo',
    'Ton bei jeder neuen Bestellung',
    'Klank by elke nuwe bestelling',
  );
  String get pickItem => _t(
    'Choisir un article',
    'Pick an item',
    'Elegir artículo',
    'Artikel wählen',
    'Kies ’n item',
  );
  String get saved =>
      _t('Enregistré', 'Saved', 'Guardado', 'Gespeichert', 'Gestoor');
}
