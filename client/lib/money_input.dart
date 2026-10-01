import 'package:flutter/services.dart';

/// Dollars-and-cents typed by a manager (menu prices, corkage) → integer
/// cents. North American only, whatever the UI language: `12`, `12.5`,
/// `12.99`, `$12.99`, `1,234.56`, `.5`. Anything else — negatives, more than
/// two decimals, a decimal comma (`12,50`), letters, an amount over [max] —
/// is null, so the caller refuses it instead of guessing.
///
/// No floating point: the digits are joined as text (mirrors the portal's
/// `parseCents`).
int? parseMoneyCents(String input, {int max = 10000000}) {
  var s = input.trim();
  if (s.startsWith(r'$')) s = s.substring(1).trim();
  if (s.isEmpty) return null;
  final grouped = RegExp(r'^(\d{1,3}(?:,\d{3})+)(?:\.(\d{0,2}))?$');
  final plain = RegExp(r'^(\d*)(?:\.(\d{0,2}))?$');
  final m = grouped.firstMatch(s) ?? plain.firstMatch(s);
  if (m == null) return null;
  final wholeText = (m.group(1) ?? '').replaceAll(',', '');
  final fracText = m.group(2) ?? '';
  if (wholeText.isEmpty && fracText.isEmpty) return null; // "." alone
  if (wholeText.length > 12) return null;
  final whole = int.parse(wholeText.isEmpty ? '0' : wholeText);
  final frac = int.parse(fracText.padRight(2, '0'));
  final cents = whole * 100 + frac;
  return cents > max ? null : cents;
}

/// Cents → the text a price box starts with: `7.50`, `1,234.56`, `0.00`.
String centsToMoneyInput(int cents) {
  final abs = cents.abs();
  final whole = (abs ~/ 100).toString().replaceAllMapped(
    RegExp(r'(\d)(?=(\d{3})+$)'),
    (m) => '${m[1]},',
  );
  final frac = (abs % 100).toString().padLeft(2, '0');
  return '${cents < 0 ? '-' : ''}$whole.$frac';
}

/// Keeps a price box to the characters a North American amount can hold
/// (digits, one `$`, `,` and `.`); [parseMoneyCents] still has the last word.
final moneyInputFormatters = <TextInputFormatter>[
  FilteringTextInputFormatter.allow(RegExp(r'[0-9.,$]')),
];
