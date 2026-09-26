import 'package:flutter/material.dart';

import '../api.dart';
import '../design/skin.dart';
import '../retail/sp_theme.dart';
import 'forecourt_i18n.dart';

/// The counter's own quick keys, next to the pumps: coffee, fountain and
/// frozen drinks, the roller grill, pizza and breakfast. Never on the shelf,
/// never barcoded: a tap opens the size / flavour picker (or rings a single
/// item straight up).
class FoodPanel extends StatelessWidget {
  final List<Item> items;
  final String Function(String categoryId) categoryName;
  final void Function(Item item) onPick;
  const FoodPanel({
    super.key,
    required this.items,
    required this.categoryName,
    required this.onPick,
  });

  /// The panel's tiles, in counter order (whatever of them the store stocks).
  static const tiles = [
    'ph-coffee',
    'ph-fountain-drink',
    'ph-frozen-slush',
    'ph-hot-dog',
    'ph-taquito-beef',
    'ph-pizza-slice-pepperoni',
    'ph-nachos-with-pump-cheese',
    'ph-breakfast-sandwich-sausage-egg-cheese',
    'ph-kolache-sausage-cheese',
    'ph-breakfast-taco-egg-bacon',
    'ph-iced-coffee',
    'ph-sweet-tea',
  ];

  @override
  Widget build(BuildContext context) {
    final f = F.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final byId = {for (final i in items) i.id: i};
    final shown = [
      for (final id in tiles)
        if (byId[id] != null && byId[id]!.active) byId[id]!,
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(
          height: 34,
          child: Align(
            alignment: Alignment.centerLeft,
            child: Text(
              f.foodPanel.toUpperCase(),
              style: s
                  .text(size: 15, weight: FontWeight.w800, color: c.textMuted)
                  .copyWith(letterSpacing: 1.6),
            ),
          ),
        ),
        const SizedBox(height: 8),
        SizedBox(
          height: 264,
          child: GridView.builder(
            padding: EdgeInsets.zero,
            physics: const NeverScrollableScrollPhysics(),
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 3,
              mainAxisExtent: 61,
              mainAxisSpacing: 6,
              crossAxisSpacing: 6,
            ),
            itemCount: shown.length,
            itemBuilder: (_, i) {
              final item = shown[i];
              final from = item.variants.isEmpty
                  ? 0
                  : item.variants
                        .map((v) => v.priceCents)
                        .reduce((a, b) => a < b ? a : b);
              final sized = item.variants.length > 1;
              return Material(
                color: c.surface,
                shape: RoundedRectangleBorder(
                  borderRadius: s.tileRadius,
                  side: BorderSide(color: c.border),
                ),
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                  key: Key('food-${item.id}'),
                  onTap: () => onPick(item),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Container(width: 5, color: _hue(item.subcategory)),
                      Expanded(
                        child: Padding(
                          padding: const EdgeInsets.fromLTRB(9, 6, 8, 6),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Text(
                                f.dish(item.id, item.nameEn),
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: s.text(
                                  size: 13,
                                  height: 1.05,
                                  weight: FontWeight.w800,
                                  color: c.text,
                                ),
                              ),
                              Text(
                                sized ? f.from(money(from)) : money(from),
                                style: s.figures(
                                  size: 12.5,
                                  weight: FontWeight.w600,
                                  color: c.textMuted,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }

  static Color _hue(String? sub) => switch (sub) {
    'Coffee' => const Color(0xFFB7791F),
    'Fountain' => const Color(0xFF2F7BFF),
    'Frozen' => const Color(0xFF2FB8C9),
    'Roller Grill' => const Color(0xFFFF6B3D),
    'Pizza' => const Color(0xFFE5484D),
    'Nachos' => const Color(0xFFFFC400),
    _ => const Color(0xFF8E8E99),
  };
}

/// One line to ring up: an item, its size, and a flavour for the note.
class FoodPick {
  final Item item;
  final Variant variant;
  final String? note;
  const FoodPick(this.item, this.variant, this.note);
}

/// Picks a cup size and flavour, or a dish's paid add-ons. Returns the lines
/// to ring up, or null when dismissed. A single-size dish with nothing to
/// choose comes straight back without a dialog.
class FoodPicker extends StatefulWidget {
  final Item item;
  final List<Item> addOns;
  const FoodPicker({super.key, required this.item, this.addOns = const []});

  /// Flavours offered per item (stored on the line as its note, in English).
  static const flavours = {
    'ph-coffee': [
      'House Blend',
      'Dark Roast',
      'Decaf',
      'French Vanilla',
      'Hazelnut',
    ],
    'ph-iced-coffee': ['Plain', 'Vanilla', 'Caramel'],
    'ph-cappuccino': ['French Vanilla', 'Mocha'],
    'ph-fountain-drink': [
      'Cola',
      'Diet Cola',
      'Lemon-Lime',
      'Root Beer',
      'Orange',
      'Spiced Cherry',
      'Lemonade',
    ],
    'ph-frozen-slush': [
      'Cherry',
      'Blue Raspberry',
      'Cola',
      'Mango',
      'Watermelon',
      'Mixed',
    ],
  };

  /// Dishes that take paid add-ons (items of the "Add-ons" style).
  static const takesAddOns = {'ph-nachos-with-pump-cheese'};

  static Future<List<FoodPick>?> pick(
    BuildContext context,
    Item item,
    List<Item> all,
  ) async {
    final addOns = takesAddOns.contains(item.id)
        ? [
            for (final i in all)
              if (i.subcategory == 'Add-ons' && i.active) i,
          ]
        : <Item>[];
    if (item.variants.length <= 1 &&
        flavours[item.id] == null &&
        addOns.isEmpty) {
      return item.variants.isEmpty
          ? null
          : [FoodPick(item, item.variants.first, null)];
    }
    return showDialog<List<FoodPick>>(
      context: context,
      builder: (_) => FoodPicker(item: item, addOns: addOns),
    );
  }

  @override
  State<FoodPicker> createState() => _FoodPickerState();
}

class _FoodPickerState extends State<FoodPicker> {
  late Variant _size = widget.item.variants.first;
  late String? _flavour = FoodPicker.flavours[widget.item.id]?.first;
  final _addOns = <String>{};

  int get _total =>
      _size.priceCents +
      widget.addOns
          .where((a) => _addOns.contains(a.id))
          .fold<int>(0, (n, a) => n + a.variants.first.priceCents);

  @override
  Widget build(BuildContext context) {
    final f = F.of(context);
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final flavours = FoodPicker.flavours[widget.item.id];
    Widget chip(
      String key,
      String label,
      bool on,
      VoidCallback onTap, {
      String? sub,
    }) => Material(
      color: on ? c.sage : c.surfaceAlt,
      shape: RoundedRectangleBorder(borderRadius: s.radiusMedium),
      child: InkWell(
        key: Key(key),
        customBorder: RoundedRectangleBorder(borderRadius: s.radiusMedium),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                label,
                style: s.text(
                  size: 16,
                  weight: FontWeight.w800,
                  color: on ? c.onSage : c.text,
                ),
              ),
              if (sub != null)
                Text(
                  sub,
                  style: s.figures(
                    size: 13,
                    weight: FontWeight.w600,
                    color: on ? c.onSage : c.textMuted,
                  ),
                ),
            ],
          ),
        ),
      ),
    );
    Widget section(String title) => Padding(
      padding: const EdgeInsets.only(top: 14, bottom: 8),
      child: Text(
        title.toUpperCase(),
        style: s
            .text(size: 13, weight: FontWeight.w800, color: c.textMuted)
            .copyWith(letterSpacing: 1.4),
      ),
    );
    return AlertDialog(
      title: Text(f.dish(widget.item.id, widget.item.nameEn)),
      content: SizedBox(
        width: 520,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (widget.item.variants.length > 1) ...[
              section(f.size),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (final v in widget.item.variants)
                    chip(
                      'size-${v.labelEn}',
                      f.cupSize(v.labelEn),
                      v.id == _size.id,
                      () => setState(() => _size = v),
                      sub: money(v.priceCents),
                    ),
                ],
              ),
            ],
            if (flavours != null) ...[
              section(f.flavour),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (final fl in flavours)
                    chip(
                      'flavour-$fl',
                      f.flavourName(fl),
                      fl == _flavour,
                      () => setState(() => _flavour = fl),
                    ),
                ],
              ),
            ],
            if (widget.addOns.isNotEmpty) ...[
              section(f.addOns),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (final a in widget.addOns)
                    chip(
                      'addon-${a.id}',
                      f.dish(a.id, a.nameEn),
                      _addOns.contains(a.id),
                      () => setState(
                        () => _addOns.contains(a.id)
                            ? _addOns.remove(a.id)
                            : _addOns.add(a.id),
                      ),
                      sub: '+${money(a.variants.first.priceCents)}',
                    ),
                ],
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: Text(f.cancel),
        ),
        FilledButton(
          key: const Key('food-add'),
          onPressed: () => Navigator.pop(context, [
            FoodPick(widget.item, _size, _flavour),
            for (final a in widget.addOns)
              if (_addOns.contains(a.id)) FoodPick(a, a.variants.first, null),
          ]),
          child: Text(f.addFood(money(_total))),
        ),
      ],
    );
  }
}
