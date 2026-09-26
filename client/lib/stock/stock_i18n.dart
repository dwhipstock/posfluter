import 'package:flutter/widgets.dart';

import '../i18n.dart';

/// Stock app strings (Count / Receive): English and US Spanish — the retail
/// store's languages — plus French, like the rest of the terminal.
class S {
  final String lang;
  const S(this.lang);

  static S of(BuildContext context) => S(L.of(context).lang);

  String _t(String en, String es, String fr) =>
      lang == 'es' ? es : (lang == 'fr' ? fr : en);

  // home
  String get appTitle => _t('Stock', 'Inventario', 'Inventaire');
  String get count => _t('Count', 'Contar', 'Compter');
  String get countHint => _t(
    'Scan the shelf, enter what you see',
    'Escanea el estante e ingresa lo que ves',
    'Scannez le rayon, entrez ce que vous voyez',
  );
  String get receive => _t('Receive', 'Recibir', 'Réception');
  String get receiveHint => _t(
    'Check a delivery in',
    'Registra una entrega',
    'Enregistrer une livraison',
  );
  String get signOut => _t('Sign out', 'Cerrar sesión', 'Déconnexion');
  String get allSent =>
      _t('Everything is sent', 'Todo enviado', 'Tout est envoyé');
  String waiting(int n) => _t(
    n == 1 ? '1 change waiting to send' : '$n changes waiting to send',
    n == 1 ? '1 cambio por enviar' : '$n cambios por enviar',
    n == 1 ? '1 modification à envoyer' : '$n modifications à envoyer',
  );
  String get offlineSaved => _t(
    'No connection to the store — saved on this phone',
    'Sin conexión con la tienda: guardado en este teléfono',
    'Pas de connexion au magasin — gardé sur ce téléphone',
  );
  String get sending => _t('Sending…', 'Enviando…', 'Envoi…');
  String get sendNow => _t('Send now', 'Enviar ahora', 'Envoyer');
  String get needsAttention =>
      _t('Needs attention', 'Requiere atención', 'À vérifier');
  String get retry => _t('Try again', 'Reintentar', 'Réessayer');
  String get discard => _t('Discard', 'Descartar', 'Abandonner');
  String get notRetail => _t(
    'This store doesn\'t count stock.',
    'Esta tienda no lleva inventario.',
    'Ce magasin ne compte pas le stock.',
  );

  // sessions
  String get counts => _t('Counts', 'Conteos', 'Inventaires');
  String get startCount =>
      _t('Start a count', 'Empezar un conteo', 'Commencer un inventaire');
  String get countName => _t(
    'Name (e.g. Back room)',
    'Nombre (p. ej. Bodega)',
    'Nom (ex. Réserve)',
  );
  String get start => _t('Start', 'Empezar', 'Commencer');
  String get openCounts =>
      _t('Open counts', 'Conteos abiertos', 'Inventaires ouverts');
  String get onThisPhone =>
      _t('On this phone', 'En este teléfono', 'Sur ce téléphone');
  String get noOpenCounts => _t(
    'No open counts. Start one.',
    'No hay conteos abiertos. Empieza uno.',
    'Aucun inventaire ouvert. Commencez-en un.',
  );
  String get storeListUnavailable => _t(
    'Can\'t reach the store — you can still count here.',
    'No se puede conectar con la tienda; puedes contar aquí.',
    'Magasin injoignable — vous pouvez compter ici.',
  );
  String startedBy(String who) =>
      _t('Started by $who', 'Iniciado por $who', 'Commencé par $who');
  String products(int n) => _t(
    n == 1 ? '1 product' : '$n products',
    n == 1 ? '1 producto' : '$n productos',
    n <= 1 ? '$n produit' : '$n produits',
  );
  String units(int n) => _t(
    n == 1 ? '1 unit' : '$n units',
    n == 1 ? '1 unidad' : '$n unidades',
    n <= 1 ? '$n unité' : '$n unités',
  );

  // counting
  String get scanOrType => _t(
    'Scan or type a barcode',
    'Escanea o escribe un código',
    'Scannez ou tapez un code',
  );
  String get camera => _t('Camera', 'Cámara', 'Caméra');
  String get scanWithCamera =>
      _t('Scan with the camera', 'Escanear con la cámara', 'Scanner');
  String get pointAtBarcode => _t(
    'Point the camera at a barcode',
    'Apunta la cámara al código de barras',
    'Visez un code-barres',
  );
  String get cameraUnavailable => _t(
    'The camera isn\'t available — type the code or use a scanner.',
    'La cámara no está disponible: escribe el código o usa un escáner.',
    'Caméra indisponible — tapez le code ou utilisez un lecteur.',
  );
  String unknownCode(String code) => _t(
    'No product with barcode $code',
    'Ningún producto con el código $code',
    'Aucun produit avec le code $code',
  );
  String expected(int n) => _t('expected $n', 'esperado $n', 'attendu $n');
  String get noExpected =>
      _t('no expected qty', 'sin cantidad esperada', 'aucune qté attendue');
  String get nothingCounted => _t(
    'Nothing counted yet. Scan a product to start.',
    'Nada contado aún. Escanea un producto.',
    'Rien de compté. Scannez un produit.',
  );
  String get review => _t('Review', 'Revisar', 'Vérifier');
  String get qty => _t('Qty', 'Cant.', 'Qté');
  String get setQty => _t('Set quantity', 'Fijar cantidad', 'Quantité');
  String get remove => _t('Remove', 'Quitar', 'Retirer');
  String get cancel => _t('Cancel', 'Cancelar', 'Annuler');
  String get ok => _t('OK', 'OK', 'OK');
  String get discardCount => _t(
    'Discard this count',
    'Descartar este conteo',
    'Abandonner cet inventaire',
  );
  String get discardCountConfirm => _t(
    'Discard this count? Nothing is sent to the store.',
    '¿Descartar este conteo? No se envía nada a la tienda.',
    'Abandonner ? Rien n\'est envoyé au magasin.',
  );

  // review
  String get varianceReview =>
      _t('Review the count', 'Revisar el conteo', 'Vérifier l\'inventaire');
  String get counted => _t('Counted', 'Contado', 'Compté');
  String get expectedCol => _t('Expected', 'Esperado', 'Attendu');
  String get variance => _t('Variance', 'Diferencia', 'Écart');
  String varianceSummary(int lines) => _t(
    lines == 0
        ? 'No variances'
        : (lines == 1 ? '1 product differs' : '$lines products differ'),
    lines == 0
        ? 'Sin diferencias'
        : (lines == 1 ? '1 producto difiere' : '$lines productos difieren'),
    lines == 0
        ? 'Aucun écart'
        : (lines == 1 ? '1 produit diffère' : '$lines produits diffèrent'),
  );
  String get allPhones => _t(
    'All phones on this count, from the store',
    'Todos los teléfonos de este conteo, según la tienda',
    'Tous les téléphones de cet inventaire, selon le magasin',
  );
  String get thisPhoneOnly => _t(
    'This phone\'s counts (the store is out of reach)',
    'Conteos de este teléfono (la tienda no responde)',
    'Comptes de ce téléphone (magasin injoignable)',
  );
  String get submit => _t('Submit count', 'Enviar conteo', 'Soumettre');
  String get managerApproves => _t(
    'A manager approves variances',
    'Un gerente aprueba las diferencias',
    'Un gérant approuve les écarts',
  );
  String get managerPin =>
      _t('Manager PIN', 'PIN del gerente', 'NIP du gérant');
  String get submitted =>
      _t('Count submitted', 'Conteo enviado', 'Inventaire soumis');
  String get submittedOffline => _t(
    'Count saved — it goes to the store when the phone is back on the Wi-Fi.',
    'Conteo guardado: se envía a la tienda cuando vuelva el Wi-Fi.',
    'Inventaire gardé — envoyé au magasin au retour du Wi-Fi.',
  );

  // receiving
  String get receiveDelivery =>
      _t('Receive a delivery', 'Recibir una entrega', 'Recevoir une livraison');
  String get supplier => _t('Supplier', 'Proveedor', 'Fournisseur');
  String get reference =>
      _t('Invoice / reference', 'Factura / referencia', 'Facture / référence');
  String get optional => _t('optional', 'opcional', 'facultatif');
  String get nothingReceived => _t(
    'Scan each product in the delivery.',
    'Escanea cada producto de la entrega.',
    'Scannez chaque produit de la livraison.',
  );
  String get saveDelivery =>
      _t('Save delivery', 'Guardar entrega', 'Enregistrer');
  String get deliverySaved =>
      _t('Delivery saved', 'Entrega guardada', 'Livraison enregistrée');
  String get deliverySavedOffline => _t(
    'Delivery saved — it goes to the store when the phone is back on the Wi-Fi.',
    'Entrega guardada: se envía a la tienda cuando vuelva el Wi-Fi.',
    'Livraison gardée — envoyée au retour du Wi-Fi.',
  );

  /// Stock-specific store refusals; null → the terminal's generic text.
  String? error(String? code) => switch (code) {
    'manager_approval_required' => _t(
      'A manager must approve the variances',
      'Un gerente debe aprobar las diferencias',
      'Un gérant doit approuver les écarts',
    ),
    'count_closed' => _t(
      'That count is already closed',
      'Ese conteo ya está cerrado',
      'Cet inventaire est déjà fermé',
    ),
    'count_empty' => _t(
      'Nothing has been counted yet',
      'Aún no se ha contado nada',
      'Rien n\'a été compté',
    ),
    'unknown_item' || 'unknown_barcode' => _t(
      'That product is no longer in the catalog',
      'Ese producto ya no está en el catálogo',
      'Ce produit n\'est plus au catalogue',
    ),
    'not_retail' => notRetail,
    _ => null,
  };
}
