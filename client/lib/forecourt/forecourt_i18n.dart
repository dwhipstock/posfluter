import 'package:flutter/widgets.dart';

import '../i18n.dart';

/// Forecourt strings: English and US Spanish (Pronghorn's languages), with
/// French so a screen is never blank.
class F {
  final String lang;
  const F(this.lang);

  static F of(BuildContext context) => F(L.of(context).lang);

  String _t(String en, String es, String fr) =>
      lang == 'es' ? es : (lang == 'fr' ? fr : en);

  String get pumps => _t('Pumps', 'Bombas', 'Pompes');
  String pump(int n) => _t('Pump $n', 'Bomba $n', 'Pompe $n');
  String get forecourtOffline => _t(
    'Pumps offline — the shop keeps selling',
    'Bombas sin conexión — la tienda sigue vendiendo',
    'Pompes hors ligne — la boutique continue de vendre',
  );
  String get stopAll => _t('STOP ALL', 'PARAR TODO', 'TOUT ARRÊTER');
  String get stopAllTitle => _t(
    'Emergency stop every pump?',
    '¿Parada de emergencia en todas las bombas?',
    'Arrêt d’urgence de toutes les pompes\u00A0?',
  );
  String get stopAllBody => _t(
    'Fuel stops flowing at every dispenser now. Each pump must be reset before it can be used again.',
    'El combustible deja de salir en todos los surtidores. Cada bomba debe reiniciarse antes de volver a usarse.',
    'Le carburant s’arrête à toutes les pompes. Chaque pompe doit être réinitialisée avant d’être réutilisée.',
  );
  String get stopNow => _t('Stop now', 'Parar ahora', 'Arrêter');
  String get cancel => _t('Cancel', 'Cancelar', 'Annuler');

  // tile states
  String state(String s) => switch (s) {
    'IDLE' => _t('READY', 'LISTA', 'PRÊTE'),
    'CALLING' => _t('CALLING', 'LLAMANDO', 'APPEL'),
    'AUTHORISED' => _t('AUTH', 'AUTORIZ.', 'AUTOR.'),
    'FUELLING' => _t('FUELLING', 'DESPACHANDO', 'EN COURS'),
    'SUSPENDED' => _t('STOPPED', 'DETENIDA', 'ARRÊTÉE'),
    'EMERGENCY_STOP' => _t('E-STOP', 'PARO', 'ARRÊT URG.'),
    'ERROR' => _t('ERROR', 'ERROR', 'ERREUR'),
    'OFFLINE' => _t('OFFLINE', 'SIN CONEXIÓN', 'HORS LIGNE'),
    _ => s,
  };
  String get payNow => _t('PAY', 'COBRAR', 'À PAYER');
  String prepaid(String amount) =>
      _t('Prepaid $amount', 'Prepagado $amount', 'Prépayé $amount');
  String get prepayOnSale => _t(
    'Prepay on the sale',
    'Prepago en la venta',
    'Prépaiement sur la vente',
  );
  String get waitingForPump => _t(
    'Paid — waiting for the pump',
    'Pagado — esperando la bomba',
    'Payé — en attente de la pompe',
  );
  String get postpay => _t('Pay after', 'Pago después', 'Payer après');
  String change(String amount) =>
      _t('CHANGE $amount', 'CAMBIO $amount', 'MONNAIE $amount');
  String gal(String g) => '$g gal';
  String get limitReached =>
      _t('Limit reached', 'Límite alcanzado', 'Limite atteinte');
  String more(int n) => _t('+$n more', '+$n más', '+$n autres');
  String get nozzleUp => _t('Nozzle up', 'Pistola levantada', 'Pistolet levé');

  // the pump sheet
  String addToSale(String amount) => _t(
    'Add $amount to the sale',
    'Agregar $amount a la venta',
    'Ajouter $amount à la vente',
  );
  String get authorise => _t(
    'Authorise · pay after',
    'Autorizar · pago después',
    'Autoriser · payer après',
  );
  String get prepay => _t('Prepay…', 'Prepago…', 'Prépaiement…');
  String get stopPump =>
      _t('Stop the pump', 'Detener la bomba', 'Arrêter la pompe');
  String get resumePump => _t('Resume', 'Reanudar', 'Reprendre');
  String get resetPump =>
      _t('Reset the pump', 'Reiniciar la bomba', 'Réinitialiser la pompe');
  String cancelPrepay(String amount) => _t(
    'Cancel prepay · refund $amount',
    'Cancelar prepago · reembolsar $amount',
    'Annuler le prépaiement · rembourser $amount',
  );
  String changeGiven(String amount) => _t(
    'Change given · $amount',
    'Cambio entregado · $amount',
    'Monnaie rendue · $amount',
  );
  String changeNote(String dispensed, String prepaid) => _t(
    'Pumped $dispensed of $prepaid prepaid. The difference is refunded on the sale.',
    'Despachó $dispensed de $prepaid prepagados. La diferencia se reembolsó en la venta.',
    'Distribué $dispensed sur $prepaid prépayés. La différence est remboursée sur la vente.',
  );
  String get emergencyStopPump =>
      _t('Emergency stop', 'Parada de emergencia', 'Arrêt d’urgence');
  String get notReachable => _t(
    'The pump controller is not answering. In-store sales are not affected.',
    'El controlador de bombas no responde. Las ventas de la tienda no se ven afectadas.',
    'Le contrôleur des pompes ne répond pas. Les ventes en boutique ne sont pas touchées.',
  );
  String get onAnotherSale =>
      _t('On a sale already', 'Ya está en una venta', 'Déjà sur une vente');

  // prepay dialog
  String prepayTitle(int n) =>
      _t('Prepay · pump $n', 'Prepago · bomba $n', 'Prépaiement · pompe $n');
  String get prepayHint => _t(
    'The pump starts when the sale is paid, and stops at this amount. Anything not pumped is refunded.',
    'La bomba arranca cuando se paga la venta y se detiene en este monto. Lo que no se despache se reembolsa.',
    'La pompe démarre au paiement de la vente et s’arrête à ce montant. Le reste est remboursé.',
  );
  String get otherAmount => _t('Other amount', 'Otro monto', 'Autre montant');
  String addPrepay(String amount) =>
      _t('Add $amount prepay', 'Agregar prepago de $amount', 'Ajouter $amount');

  // basket
  String fuelLine(int pump, String grade) =>
      _t('Pump $pump · $grade', 'Bomba $pump · $grade', 'Pompe $pump · $grade');
  String prepayLine(int pump) => _t(
    'Prepay · pump $pump',
    'Prepago · bomba $pump',
    'Prépaiement · pompe $pump',
  );
  String perGallon(String gal, String price) => '$gal gal @ $price/gal';
  String get taxIncluded => _t('Tax incl.', 'Impuestos incl.', 'Taxes incl.');
  String get startsWhenPaid => _t(
    'Pump starts when paid',
    'La bomba arranca al pagar',
    'La pompe démarre au paiement',
  );
  String pumpOn(int n, String amount) => _t(
    'Pump $n is on: $amount prepaid',
    'Bomba $n lista: $amount prepagados',
    'Pompe $n prête\u00A0: $amount prépayés',
  );

  // the counter's food & drink panel
  String get foodPanel => _t(
    'Fountain & hot food',
    'Refrescos y comida caliente',
    'Fontaine et mets chauds',
  );
  String from(String price) => _t('from $price', 'desde $price', 'dès $price');
  String get size => _t('Size', 'Tamaño', 'Format');
  String get flavour => _t('Flavor', 'Sabor', 'Saveur');
  String get addOns => _t('Add-ons', 'Extras', 'Suppléments');
  String addFood(String amount) =>
      _t('Add · $amount', 'Agregar · $amount', 'Ajouter · $amount');

  /// A cup size label as the store names it ("Medium 32 oz", "Refill").
  String cupSize(String label) {
    if (lang == 'en') return label;
    const es = {
      'Small': 'Chico',
      'Medium': 'Mediano',
      'Large': 'Grande',
      'Jumbo': 'Jumbo',
      'Refill': 'Rellenado',
    };
    const fr = {
      'Small': 'Petit',
      'Medium': 'Moyen',
      'Large': 'Grand',
      'Jumbo': 'Géant',
      'Refill': 'Remplissage',
    };
    final words = label.split(' ');
    final map = lang == 'es' ? es : fr;
    return [map[words.first] ?? words.first, ...words.skip(1)].join(' ');
  }

  String flavourName(String en) => lang == 'en'
      ? en
      : (lang == 'es'
                ? const {
                    'House Blend': 'Mezcla de la casa',
                    'Dark Roast': 'Tostado oscuro',
                    'Decaf': 'Descafeinado',
                    'French Vanilla': 'Vainilla francesa',
                    'Hazelnut': 'Avellana',
                    'Plain': 'Natural',
                    'Vanilla': 'Vainilla',
                    'Caramel': 'Caramelo',
                    'Mocha': 'Moca',
                    'Diet Cola': 'Cola light',
                    'Lemon-Lime': 'Limón-lima',
                    'Root Beer': 'Root beer',
                    'Orange': 'Naranja',
                    'Spiced Cherry': 'Cereza especiada',
                    'Lemonade': 'Limonada',
                    'Cherry': 'Cereza',
                    'Blue Raspberry': 'Frambuesa azul',
                    'Mango': 'Mango',
                    'Watermelon': 'Sandía',
                    'Mixed': 'Mixto',
                  }
                : const <String, String>{})[en] ??
            en;

  /// The panel's dishes in Spanish; the store's own (English) name otherwise.
  String dish(String id, String fallback) => lang != 'es'
      ? fallback
      : const {
              'ph-coffee': 'Café',
              'ph-fountain-drink': 'Refresco de máquina',
              'ph-frozen-slush': 'Granizado',
              'ph-hot-dog': 'Hot dog',
              'ph-taquito-beef': 'Taquito de res',
              'ph-pizza-slice-pepperoni': 'Pizza de pepperoni',
              'ph-nachos-with-pump-cheese': 'Nachos con queso',
              'ph-breakfast-sandwich-sausage-egg-cheese':
                  'Sándwich de desayuno',
              'ph-kolache-sausage-cheese': 'Kolache de salchicha',
              'ph-breakfast-taco-egg-bacon': 'Taco de huevo y tocino',
              'ph-iced-coffee': 'Café helado',
              'ph-sweet-tea': 'Té dulce',
              'ph-add-jalapenos': 'Jalapeños extra',
              'ph-add-chili': 'Chili extra',
            }[id] ??
            fallback;

  String grade(String code, String fallback) => switch (code) {
    'REG' => _t('Regular', 'Regular', 'Ordinaire'),
    'MID' => _t('Mid-Grade', 'Intermedia', 'Intermédiaire'),
    'PRE' => _t('Premium', 'Premium', 'Super'),
    'DSL' => _t('Diesel', 'Diésel', 'Diesel'),
    _ => fallback,
  };
}
