import 'package:flutter/widgets.dart';

import '../i18n.dart';

/// Carry-out at a table-service restaurant: French, English, Spanish, German
/// and Afrikaans.
class C {
  final String lang;
  const C(this.lang);

  static C of(BuildContext context) => C(L.of(context).lang);

  String _t(String fr, String en, String es, String de, String af) =>
      switch (lang) {
        'fr' => fr,
        'es' => es,
        'de' => de,
        'af' => af,
        _ => en,
      };

  String get carryOut =>
      _t('À emporter', 'Carry-out', 'Para llevar', 'Zum Mitnehmen', 'Wegneem');

  String get newOrder => _t(
    'Nouvelle commande à emporter',
    'New carry-out order',
    'Nuevo pedido para llevar',
    'Neue Bestellung zum Mitnehmen',
    'Nuwe wegneembestelling',
  );

  String get noOrders => _t(
    'Aucune commande à emporter en cours',
    'No open carry-out orders',
    'No hay pedidos para llevar abiertos',
    'Keine offenen Bestellungen zum Mitnehmen',
    'Geen oop wegneembestellings nie',
  );

  /// The order's headline: on the check screen, the receipt screen.
  String headline(int n) => _t(
    'Commande n° $n · À emporter',
    'Order #$n · Carry-out',
    'Pedido n.º $n · Para llevar',
    'Bestellung Nr. $n · Zum Mitnehmen',
    'Bestelling #$n · Wegneem',
  );

  /// The spot on the floor: how many orders are open.
  String openCount(int n) =>
      _t('$n en cours', '$n open', '$n abiertos', '$n offen', '$n oop');

  /// The shift report: paid carry-out orders.
  String orderCount(int n) => _t(
    'À emporter : $n',
    'Carry-out: $n',
    'Para llevar: $n',
    'Zum Mitnehmen: $n',
    'Wegneem: $n',
  );

  String get customer => _t('Client', 'Customer', 'Cliente', 'Kunde', 'Klant');

  String get customerName => _t(
    'Nom du client (facultatif)',
    'Customer name (optional)',
    'Nombre del cliente (opcional)',
    'Name des Kunden (optional)',
    'Klant se naam (opsioneel)',
  );

  String get phone => _t(
    'Téléphone (facultatif)',
    'Phone (optional)',
    'Teléfono (opcional)',
    'Telefon (optional)',
    'Telefoon (opsioneel)',
  );

  String get start => _t(
    'Commencer la commande',
    'Start order',
    'Empezar pedido',
    'Bestellung beginnen',
    'Begin bestelling',
  );

  String get save =>
      _t('Enregistrer', 'Save', 'Guardar', 'Speichern', 'Stoor');

  String get paid => _t('Payée', 'Paid', 'Pagado', 'Bezahlt', 'Betaal');

  String get payAtPickup => _t(
    'Payer au retrait',
    'Pay at pickup',
    'Paga al recoger',
    'Zahlt bei Abholung',
    'Betaal by afhaal',
  );

  String get notSent => _t(
    'Pas envoyée',
    'Not sent yet',
    'Sin enviar',
    'Nicht gesendet',
    'Nog nie gestuur nie',
  );

  String get payFirst => _t(
    'Encaissez avant la remise',
    'Take the payment before pickup',
    'Cobre antes de entregar',
    'Vor der Abholung kassieren',
    'Neem betaling voor afhaal',
  );

  String get headerSetting => _t(
    'Bouton À emporter dans l’en-tête',
    'Carry-out button in the header',
    'Botón Para llevar en el encabezado',
    'Zum-Mitnehmen-Knopf in der Kopfzeile',
    'Wegneem-knoppie in die kopstrook',
  );

  String get headerSettingHelp => _t(
    'Affiche À emporter en haut de la salle, même sans emplacement À emporter sur le plan.',
    'Shows Carry-out at the top of the floor, even with no Carry-out spot on the floor plan.',
    'Muestra Para llevar arriba de la sala, aunque no haya un punto Para llevar en el plano.',
    'Zeigt Zum Mitnehmen oben im Saal, auch ohne Zum-Mitnehmen-Platz im Raumplan.',
    'Wys Wegneem bo-aan die vloer, selfs sonder ’n Wegneem-plek op die vloerplan.',
  );
}
