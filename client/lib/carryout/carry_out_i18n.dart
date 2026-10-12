import 'package:flutter/widgets.dart';

import '../i18n.dart';

/// Carry-out at a table-service restaurant: French, English, Spanish, German
/// and Afrikaans.
class C {
  final String lang;
  const C(this.lang);

  static C of(BuildContext context) => C(L.of(context).lang);

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

  String get carryOut => _t(
    'À emporter',
    'Carry-out',
    'Para llevar',
    'Zum Mitnehmen',
    'Wegneem',
    th: 'กลับบ้าน',
  );

  String get newOrder => _t(
    'Nouvelle commande à emporter',
    'New carry-out order',
    'Nuevo pedido para llevar',
    'Neue Bestellung zum Mitnehmen',
    'Nuwe wegneembestelling',
    th: 'ออร์เดอร์กลับบ้านใหม่',
  );

  String get noOrders => _t(
    'Aucune commande à emporter en cours',
    'No open carry-out orders',
    'No hay pedidos para llevar abiertos',
    'Keine offenen Bestellungen zum Mitnehmen',
    'Geen oop wegneembestellings nie',
    th: 'ไม่มีออร์เดอร์กลับบ้านที่เปิดอยู่',
  );

  /// The order's headline: on the check screen, the receipt screen.
  String headline(int n) => _t(
    'Commande n° $n · À emporter',
    'Order #$n · Carry-out',
    'Pedido n.º $n · Para llevar',
    'Bestellung Nr. $n · Zum Mitnehmen',
    'Bestelling #$n · Wegneem',
    th: 'ออร์เดอร์ #$n · กลับบ้าน',
  );

  /// The spot on the floor: how many orders are open.
  String openCount(int n) => _t(
    '$n en cours',
    '$n open',
    '$n abiertos',
    '$n offen',
    '$n oop',
    th: 'เปิดอยู่ $n',
  );

  /// The shift report: paid carry-out orders.
  String orderCount(int n) => _t(
    'À emporter : $n',
    'Carry-out: $n',
    'Para llevar: $n',
    'Zum Mitnehmen: $n',
    'Wegneem: $n',
    th: 'กลับบ้าน: $n',
  );

  String get customer =>
      _t('Client', 'Customer', 'Cliente', 'Kunde', 'Klant', th: 'ลูกค้า');

  String get customerName => _t(
    'Nom du client (facultatif)',
    'Customer name (optional)',
    'Nombre del cliente (opcional)',
    'Name des Kunden (optional)',
    'Klant se naam (opsioneel)',
    th: 'ชื่อลูกค้า (ไม่บังคับ)',
  );

  String get phone => _t(
    'Téléphone (facultatif)',
    'Phone (optional)',
    'Teléfono (opcional)',
    'Telefon (optional)',
    'Telefoon (opsioneel)',
    th: 'โทรศัพท์ (ไม่บังคับ)',
  );

  String get start => _t(
    'Commencer la commande',
    'Start order',
    'Empezar pedido',
    'Bestellung beginnen',
    'Begin bestelling',
    th: 'เริ่มออร์เดอร์',
  );

  String get save =>
      _t('Enregistrer', 'Save', 'Guardar', 'Speichern', 'Stoor', th: 'บันทึก');

  String get paid =>
      _t('Payée', 'Paid', 'Pagado', 'Bezahlt', 'Betaal', th: 'ชำระแล้ว');

  String get payAtPickup => _t(
    'Payer au retrait',
    'Pay at pickup',
    'Paga al recoger',
    'Zahlt bei Abholung',
    'Betaal by afhaal',
    th: 'ชำระตอนรับอาหาร',
  );

  String get notSent => _t(
    'Pas envoyée',
    'Not sent yet',
    'Sin enviar',
    'Nicht gesendet',
    'Nog nie gestuur nie',
    th: 'ยังไม่ได้ส่ง',
  );

  String get payFirst => _t(
    'Encaissez avant la remise',
    'Take the payment before pickup',
    'Cobre antes de entregar',
    'Vor der Abholung kassieren',
    'Neem betaling voor afhaal',
    th: 'รับชำระเงินก่อนให้ลูกค้ารับอาหาร',
  );

  String get headerSetting => _t(
    'Bouton À emporter dans l’en-tête',
    'Carry-out button in the header',
    'Botón Para llevar en el encabezado',
    'Zum-Mitnehmen-Knopf in der Kopfzeile',
    'Wegneem-knoppie in die kopstrook',
    th: 'ปุ่มกลับบ้านที่แถบด้านบน',
  );

  String get headerSettingHelp => _t(
    'Affiche À emporter en haut de la salle, même sans emplacement À emporter sur le plan.',
    'Shows Carry-out at the top of the floor, even with no Carry-out spot on the floor plan.',
    'Muestra Para llevar arriba de la sala, aunque no haya un punto Para llevar en el plano.',
    'Zeigt Zum Mitnehmen oben im Saal, auch ohne Zum-Mitnehmen-Platz im Raumplan.',
    'Wys Wegneem bo-aan die vloer, selfs sonder ’n Wegneem-plek op die vloerplan.',
    th: 'แสดงปุ่มกลับบ้านที่ด้านบนของผังร้าน แม้ไม่มีจุดกลับบ้านในผังร้าน',
  );
}
