// Menu specials (store sdk/MenuSpecials.kt, CONTRACT §10 "Specials"), for
// any kind of store: a size can be cheaper on some days (maybe in a time
// window: happy hour), and an item can be sold only on some days.
//
// The store decides everything: GET /items says whether an item is on sale
// today (`availableNow`) and gives each size its price NOW (`priceCents`, the
// special's while one is in force) plus, then, the menu price it replaces
// (`regularPriceCents`). This file only reads and edits the rules and names
// them in the reader's language.

import 'specials_i18n.dart';

/// Day codes, Monday first (the store's canonical order).
const specialDays = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];

List<String> _days(dynamic j) {
  final raw = (j as List?)?.map((e) => '$e').toSet() ?? const <String>{};
  return [
    for (final d in specialDays)
      if (raw.contains(d)) d,
  ];
}

/// Which special a price (or a rung line) came from: days, window, own name.
class SpecialTag {
  final List<String> days;
  final String? from, to, label;
  const SpecialTag(this.days, {this.from, this.to, this.label});

  static SpecialTag? fromJson(dynamic j) {
    if (j is! Map) return null;
    return SpecialTag(
      _days(j['days']),
      from: j['from'] as String?,
      to: j['to'] as String?,
      label: (j['label'] as String?)?.trim().isEmpty == true
          ? null
          : j['label'] as String?,
    );
  }

  /// Its name in [lang]: its own label, else "Happy hour" (a time window),
  /// else "Tuesday special" (one day), else "Special" — the store's rule.
  String name(String lang) {
    final t = SpecialsText(lang);
    if (label != null && label!.isNotEmpty) return label!;
    if (from != null) return t.happyHour;
    if (days.length == 1) return t.daySpecial(days.first);
    return t.special;
  }
}

/// One special rule as the menu editor edits it.
class SpecialRule {
  final List<String> days;
  final String? from, to, label;

  /// Size id → special unit price (cents).
  final Map<String, int> prices;
  const SpecialRule({
    required this.days,
    this.from,
    this.to,
    this.label,
    this.prices = const {},
  });

  factory SpecialRule.fromJson(Map<String, dynamic> j) => SpecialRule(
    days: _days(j['days']),
    from: j['from'] as String?,
    to: j['to'] as String?,
    label: j['label'] as String?,
    prices: {
      for (final e in ((j['prices'] as Map?) ?? const {}).entries)
        '${e.key}': (e.value as num).toInt(),
    },
  );

  Map<String, dynamic> toJson() => {
    'days': days,
    if (from != null && from!.isNotEmpty) 'from': from,
    if (to != null && to!.isNotEmpty) 'to': to,
    if (label != null && label!.trim().isNotEmpty) 'label': label!.trim(),
    'prices': prices,
  };

  SpecialTag get tag => SpecialTag(days, from: from, to: to, label: label);
}

List<SpecialRule> specialRulesFromJson(dynamic j) => [
  for (final s in (j as List?) ?? const [])
    if (s is Map<String, dynamic>) SpecialRule.fromJson(s),
];

List<String> availableDaysFromJson(dynamic j) => _days(j);

/// "HH:mm" (24 h, zero-padded) or null when [raw] isn't one.
String? normalizeHhmm(String raw) {
  final m = RegExp(r'^\s*(\d{1,2})[:h.]?(\d{2})\s*$').firstMatch(raw);
  if (m == null) return null;
  final h = int.parse(m.group(1)!), mi = int.parse(m.group(2)!);
  if (h > 23 || mi > 59) return null;
  return '${h.toString().padLeft(2, '0')}:${mi.toString().padLeft(2, '0')}';
}
