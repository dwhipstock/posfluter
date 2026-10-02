import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../design/widgets.dart';
import '../money_input.dart';
import '../specials_i18n.dart';

/// A small pill naming a special ("Happy hour", "Tuesday special") or a
/// selling-days limit ("Fri & Sat only"). Dark amber on a copper tint, never
/// light text on white.
class SpecialBadge extends StatelessWidget {
  final String text;
  final bool muted;
  const SpecialBadge(this.text, {super.key, this.muted = false});

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
    decoration: BoxDecoration(
      color: muted ? T.surfaceAlt : T.accentSoft,
      borderRadius: T.radiusSmall,
    ),
    child: Text(
      text,
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: T
          .small(
            color: muted ? T.textMuted : T.attention,
            weight: FontWeight.w700,
          )
          .copyWith(fontSize: 12),
    ),
  );
}

/// The price of a size on a menu tile: the special's, with the menu price
/// struck through beside it while a special is in force.
class SpecialPriceText extends StatelessWidget {
  final Variant variant;
  final String Function(int cents) money;
  final TextStyle style;

  /// After both prices: "+" for an item with sizes ("$5.00+ $7.50+", the
  /// "from" price, the regular one struck through).
  final String suffix;
  const SpecialPriceText(
    this.variant, {
    super.key,
    required this.money,
    required this.style,
    this.suffix = '',
  });

  @override
  Widget build(BuildContext context) {
    final reg = variant.regularPriceCents;
    if (reg == null) {
      return Text(
        '${money(variant.priceCents)}$suffix',
        maxLines: 1,
        style: style,
      );
    }
    return Text.rich(
      TextSpan(
        children: [
          TextSpan(text: '${money(variant.priceCents)}$suffix', style: style),
          const TextSpan(text: ' '),
          TextSpan(
            text: '${money(reg)}$suffix',
            style: style.copyWith(
              fontSize: (style.fontSize ?? 14) * 0.75,
              fontWeight: FontWeight.w400,
              color: T.textMuted,
              decoration: TextDecoration.lineThrough,
            ),
          ),
        ],
      ),
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
    );
  }
}

/// What a tile says about a special or selling days, or null for a plain item.
String? specialBadgeText(Item item, String lang) {
  final t = SpecialsText(lang);
  if (!item.availableNow) return t.onlyOn(item.availableDays);
  final sp = item.specialNow;
  if (sp != null) return sp.name(lang);
  return null;
}

/// Items for a "Today's specials" row: a special in force now, or a day-only
/// item that is on sale today.
List<Item> todaysSpecials(Iterable<Item> items) => [
  for (final i in items)
    if (i.active &&
        i.availableNow &&
        i.variants.isNotEmpty &&
        (i.specialNow != null || i.dayOnly))
      i,
];

// ---------------------------------------------------------------- editor

/// One special being edited.
class SpecialEdit {
  final Set<String> days;
  final TextEditingController from, to, label;

  /// Size id → the special price box.
  final Map<String, TextEditingController> prices;
  SpecialEdit({
    Set<String>? days,
    String from = '',
    String to = '',
    String label = '',
    Map<String, int> cents = const {},
    required List<String> variantIds,
  }) : days = days ?? <String>{},
       from = TextEditingController(text: from),
       to = TextEditingController(text: to),
       label = TextEditingController(text: label),
       prices = {
         for (final id in variantIds)
           id: TextEditingController(
             text: cents[id] == null ? '' : centsToMoneyInput(cents[id]!),
           ),
       };

  String snapshot() => [
    [
      for (final d in specialDays)
        if (days.contains(d)) d,
    ].join(','),
    from.text,
    to.text,
    label.text,
    for (final e in prices.entries) '${e.key}=${e.value.text}',
  ].join('\u0001');
}

/// The menu editor's specials and selling days for one item ([Item.specials],
/// [Item.availableDays]); [toPatch] is the item PATCH part (a full replace).
class SpecialsEditController {
  final Set<String> availableDays;
  final List<SpecialEdit> specials;
  final List<String> variantIds;
  SpecialsEditController._(this.availableDays, this.specials, this.variantIds);

  factory SpecialsEditController.of(Item? item) {
    final ids = [for (final v in item?.variants ?? const <Variant>[]) v.id];
    return SpecialsEditController._(
      {...?item?.availableDays},
      [
        for (final s in item?.specials ?? const <SpecialRule>[])
          SpecialEdit(
            days: {...s.days},
            from: s.from ?? '',
            to: s.to ?? '',
            label: s.label ?? '',
            cents: s.prices,
            variantIds: [
              ...ids,
              // a price for a size the editor doesn't list (deleted): kept
              for (final k in s.prices.keys)
                if (!ids.contains(k)) k,
            ],
          ),
      ],
      ids,
    );
  }

  void add() => specials.add(SpecialEdit(variantIds: variantIds));

  String snapshot() => [
    [
      for (final d in specialDays)
        if (availableDays.contains(d)) d,
    ].join(','),
    for (final s in specials) s.snapshot(),
  ].join('\u0002');

  /// The first problem in [lang], or null when every special is complete.
  String? validate(String lang) {
    final t = SpecialsText(lang);
    for (final s in specials) {
      if (s.days.isEmpty) return t.pickADay;
      final f = s.from.text.trim(), e = s.to.text.trim();
      if (f.isEmpty != e.isEmpty) return t.bothTimes;
      if (f.isNotEmpty) {
        final nf = normalizeHhmm(f), ne = normalizeHhmm(e);
        if (nf == null || ne == null) return t.badTime;
        if (nf == ne) return t.sameTimes;
      }
      final set = [
        for (final p in s.prices.values)
          if (p.text.trim().isNotEmpty) p,
      ];
      if (set.isEmpty) return t.setAPrice;
      if (set.any((p) => parseMoneyCents(p.text) == null)) return t.setAPrice;
    }
    return null;
  }

  /// `availableDays` and `specials` for PATCH /items/{id} (call [validate] first).
  Map<String, dynamic> toPatch() => {
    'availableDays': [
      for (final d in specialDays)
        if (availableDays.contains(d)) d,
    ],
    'specials': [
      for (final s in specials)
        SpecialRule(
          days: [
            for (final d in specialDays)
              if (s.days.contains(d)) d,
          ],
          from: s.from.text.trim().isEmpty ? null : normalizeHhmm(s.from.text),
          to: s.to.text.trim().isEmpty ? null : normalizeHhmm(s.to.text),
          label: s.label.text.trim().isEmpty ? null : s.label.text.trim(),
          prices: {
            for (final e in s.prices.entries)
              e.key: ?parseMoneyCents(e.value.text),
          },
        ).toJson(),
    ],
  };
}

/// Mon–Sun toggle chips.
class DayChips extends StatelessWidget {
  final Set<String> selected;
  final String lang;
  final ValueChanged<String>? onToggle;
  final String keyPrefix;
  const DayChips({
    super.key,
    required this.selected,
    required this.lang,
    required this.onToggle,
    this.keyPrefix = 'day',
  });

  @override
  Widget build(BuildContext context) {
    final t = SpecialsText(lang);
    return Wrap(
      spacing: 6,
      runSpacing: 6,
      children: [
        for (final d in specialDays)
          FilterChip(
            key: ValueKey('$keyPrefix-$d'),
            label: Text(t.dayShort(d)),
            selected: selected.contains(d),
            // selected is navy: white text and check on it, never dark on dark
            labelStyle: T.small(
              color: selected.contains(d) ? T.onPrimary : T.textPrimary,
              weight: FontWeight.w600,
            ),
            checkmarkColor: T.onPrimary,
            onSelected: onToggle == null ? null : (_) => onToggle!(d),
          ),
      ],
    );
  }
}

/// The item editor's "Specials" and "Available only on" sections.
class SpecialsEditor extends StatelessWidget {
  final SpecialsEditController c;
  final String lang;

  /// Size id → its label in the reader's language.
  final Map<String, String> sizeLabels;
  final bool busy;
  final VoidCallback onChanged;
  const SpecialsEditor({
    super.key,
    required this.c,
    required this.lang,
    required this.sizeLabels,
    required this.onChanged,
    this.busy = false,
  });

  void _toggle(Set<String> set, String d) {
    if (!set.remove(d)) set.add(d);
    onChanged();
  }

  Widget _box(
    TextEditingController ctl,
    String label, {
    Key? key,
    bool money = false,
  }) => TextField(
    key: key,
    controller: ctl,
    enabled: !busy,
    style: T.text(size: 16),
    keyboardType: money
        ? const TextInputType.numberWithOptions(decimal: true)
        : TextInputType.text,
    inputFormatters: money
        ? moneyInputFormatters
        : <TextInputFormatter>[LengthLimitingTextInputFormatter(40)],
    onChanged: (_) => onChanged(),
    decoration: InputDecoration(labelText: label, counterText: ''),
  );

  @override
  Widget build(BuildContext context) {
    final t = SpecialsText(lang);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SectionLabel(t.availableOnlyOn),
        DayChips(
          keyPrefix: 'available',
          selected: c.availableDays,
          lang: lang,
          onToggle: busy ? null : (d) => _toggle(c.availableDays, d),
        ),
        Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Text(t.everyDayHint, style: T.small(color: T.textMuted)),
        ),
        SectionLabel(t.specials),
        Text(t.specialsHint, style: T.small(color: T.textMuted)),
        for (final (i, s) in c.specials.indexed)
          Container(
            key: ValueKey('special-$i'),
            margin: const EdgeInsets.only(top: 10),
            padding: const EdgeInsets.fromLTRB(12, 8, 4, 10),
            decoration: BoxDecoration(
              border: Border.all(color: T.border),
              borderRadius: T.radiusMedium,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: DayChips(
                        keyPrefix: 'special-$i-day',
                        selected: s.days,
                        lang: lang,
                        onToggle: busy ? null : (d) => _toggle(s.days, d),
                      ),
                    ),
                    IconButton(
                      key: ValueKey('special-$i-remove'),
                      icon: const Icon(
                        LucideIcons.x,
                        size: 18,
                        color: T.destructive,
                      ),
                      tooltip: t.removeSpecial,
                      onPressed: busy
                          ? null
                          : () {
                              c.specials.removeAt(i);
                              onChanged();
                            },
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                Row(
                  children: [
                    Expanded(
                      child: _box(
                        s.from,
                        '${t.from} (HH:mm)',
                        key: ValueKey('special-$i-from'),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: _box(
                        s.to,
                        '${t.to} (HH:mm)',
                        key: ValueKey('special-$i-to'),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      flex: 2,
                      child: _box(
                        s.label,
                        t.nameOptional,
                        key: ValueKey('special-$i-label'),
                      ),
                    ),
                    const SizedBox(width: 8),
                  ],
                ),
                const SizedBox(height: 6),
                Wrap(
                  spacing: 8,
                  runSpacing: 6,
                  children: [
                    for (final e in s.prices.entries)
                      if (sizeLabels.containsKey(e.key))
                        SizedBox(
                          width: 160,
                          child: _box(
                            e.value,
                            '${t.specialPrice} · ${sizeLabels[e.key]}',
                            key: ValueKey('special-$i-price-${e.key}'),
                            money: true,
                          ),
                        ),
                  ],
                ),
              ],
            ),
          ),
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
            key: const ValueKey('special-add'),
            icon: const Icon(LucideIcons.plus, size: 18),
            label: Text(t.addSpecial),
            onPressed: busy
                ? null
                : () {
                    c.add();
                    onChanged();
                  },
          ),
        ),
      ],
    );
  }
}
