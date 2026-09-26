import 'package:flutter/material.dart';

import '../api.dart';
import '../design/skin.dart';
import 'retail_i18n.dart';
import 'sp_theme.dart';

/// The pieces of the retail counter, drawn in the store's brand skin: product
/// tiles (quick keys, browse), product rows (top sellers, search results),
/// chips, the navigation rail and the basket's lines.

Color departmentHue(BuildContext context, String category) =>
    spDepartmentHues[category] ?? SpColors.of(context).sage;

/// Name without the size words it ends with ("Hazy IPA 6-pack 12 oz cans" →
/// "Hazy IPA"), since tiles and rows show the size on its own.
String shortName(Item item) {
  final n = item.nameEn;
  final size = item.size;
  if (size == null) return n;
  final i = n.toLowerCase().lastIndexOf(size.toLowerCase());
  if (i > 8) return n.substring(0, i).trim();
  return n;
}

const _brandSuffixes = {
  'brewing',
  'beer',
  'co.',
  'co',
  'ales',
  'brewery',
  'cellars',
  'vineyards',
  'winery',
  'estate',
  'wine',
};

/// The producer as a shelf label reads it: "Salt Marsh Brewing" → "Salt Marsh".
String brandShort(Item item) {
  final b = item.brand;
  if (b == null || b == 'House') return '';
  final words = b.split(' ');
  while (words.length > 1 &&
      _brandSuffixes.contains(words.last.toLowerCase())) {
    words.removeLast();
  }
  return words.join(' ');
}

/// A key's label: the product without its producer and size ("Salt Marsh
/// Electric Hollow Cider 4-pack 12 oz cans" → "Electric Hollow Cider"); the
/// tile prints the producer and the size on their own lines.
String keyLabel(Item item) {
  final name = shortName(item);
  final brand = brandShort(item);
  if (brand.isNotEmpty &&
      name.toLowerCase().startsWith('${brand.toLowerCase()} ')) {
    final rest = name.substring(brand.length).trim();
    if (rest.length >= 3) return rest;
  }
  return name;
}

/// A dense product tile: a department band, the product, its producer, the
/// size and the price. Built for a 6-across grid of quick keys.
class ProductTile extends StatelessWidget {
  final Item item;
  final VoidCallback onTap;
  final VoidCallback? onLongPress;
  final bool pinned;
  final bool editing;
  const ProductTile({
    super.key,
    required this.item,
    required this.onTap,
    this.onLongPress,
    this.pinned = false,
    this.editing = false,
  });

  @override
  Widget build(BuildContext context) {
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final r = R.of(context);
    final hue = departmentHue(context, item.category);
    final brand = brandShort(item);
    final marks = [
      if (item.ageRestricted)
        Text(
          r.agePill,
          style: s.text(size: 11, weight: FontWeight.w800, color: c.poppy),
        ),
      if (pinned || editing)
        Icon(
          pinned ? s.glyphs.pin : s.glyphs.pinOff,
          size: 15,
          color: pinned ? c.strong : c.textMuted,
        ),
    ];
    return Material(
      color: editing && pinned ? c.sageMist : c.surface,
      shape: RoundedRectangleBorder(
        borderRadius: s.tileRadius,
        side: editing
            ? BorderSide(color: pinned ? c.sage : c.border, width: 1.5)
            : BorderSide.none,
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        onLongPress: onLongPress,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(height: 4, color: hue),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(9, 6, 9, 7),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          child: Text(
                            brand.isEmpty
                                ? r.category(item.category, '')
                                : brand,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: s.text(
                              size: 11.5,
                              weight: FontWeight.w600,
                              color: c.textMuted,
                            ),
                          ),
                        ),
                        for (final m in marks) ...[const SizedBox(width: 4), m],
                      ],
                    ),
                    const SizedBox(height: 1),
                    Expanded(
                      child: Text(
                        keyLabel(item),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: s.text(
                          size: 13.5,
                          weight: FontWeight.w700,
                          color: c.text,
                          height: 1.18,
                        ),
                      ),
                    ),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        Expanded(
                          child: Text(
                            item.size ?? '',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: s.text(
                              size: 11.5,
                              weight: FontWeight.w600,
                              color: c.textMuted,
                            ),
                          ),
                        ),
                        const SizedBox(width: 4),
                        Text(
                          money(item.priceCents),
                          style: s.figures(
                            size: 15,
                            weight: FontWeight.w800,
                            color: c.text,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// A product row: department dot, name, brand · size, a note, the price, add.
class ProductRow extends StatelessWidget {
  final Item item;
  final VoidCallback onTap;
  final VoidCallback? onLongPress;
  final String? leading;
  final String? note;
  final bool noteStrong;
  const ProductRow({
    super.key,
    required this.item,
    required this.onTap,
    this.onLongPress,
    this.leading,
    this.note,
    this.noteStrong = false,
  });

  static const extent = 66.0;

  @override
  Widget build(BuildContext context) {
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final r = R.of(context);
    final sub = [
      item.brand,
      item.size,
      if (item.barcode == null) r.noBarcode,
    ].whereType<String>().join(' · ');
    return Material(
      color: c.surface,
      child: InkWell(
        onTap: onTap,
        onLongPress: onLongPress,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Row(
            children: [
              if (leading != null)
                SizedBox(
                  width: 44,
                  child: Text(
                    leading!,
                    style: s.figures(
                      size: 15,
                      weight: FontWeight.w700,
                      color: c.textMuted,
                    ),
                  ),
                ),
              Container(
                width: 10,
                height: 10,
                decoration: BoxDecoration(
                  color: departmentHue(context, item.category),
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      shortName(item),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: s.text(
                        size: 15.5,
                        weight: FontWeight.w600,
                        color: c.text,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      sub,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: s.text(size: 13, color: c.textMuted),
                    ),
                  ],
                ),
              ),
              if (note != null)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  child: Text(
                    note!,
                    style: s.text(
                      size: 13,
                      weight: noteStrong ? FontWeight.w700 : FontWeight.w500,
                      color: noteStrong ? c.sage : c.textMuted,
                    ),
                  ),
                ),
              if (item.ageRestricted)
                Padding(
                  padding: const EdgeInsets.only(right: 10),
                  child: Text(
                    r.agePill,
                    style: s.text(
                      size: 12,
                      weight: FontWeight.w800,
                      color: c.poppy,
                    ),
                  ),
                ),
              SizedBox(
                width: 78,
                child: Text(
                  money(item.priceCents),
                  textAlign: TextAlign.right,
                  style: s.figures(
                    size: 16,
                    weight: FontWeight.w800,
                    color: c.text,
                  ),
                ),
              ),
              const SizedBox(width: 10),
              SizedBox(
                width: 40,
                height: 40,
                child: IconButton.filledTonal(
                  padding: EdgeInsets.zero,
                  style: IconButton.styleFrom(
                    backgroundColor: c.sageMist,
                    foregroundColor: c.sage,
                    minimumSize: const Size(40, 40),
                  ),
                  tooltip: r.addToSale,
                  onPressed: onTap,
                  icon: Icon(s.glyphs.add, size: 22),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// A filter chip in the skin's shape, with an optional count.
class CounterChip extends StatelessWidget {
  final String label;
  final int? count;
  final bool selected;
  final VoidCallback onTap;
  final Color? dot;
  const CounterChip({
    super.key,
    required this.label,
    required this.selected,
    required this.onTap,
    this.count,
    this.dot,
  });

  @override
  Widget build(BuildContext context) {
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    final fg = selected ? c.onSelected : c.text;
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: Material(
        color: selected ? c.selectedFill : c.surface,
        shape: s.controlShape(),
        child: InkWell(
          customBorder: s.controlShape(),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 14),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (dot != null) ...[
                  Container(
                    width: 8,
                    height: 8,
                    decoration: BoxDecoration(
                      color: dot,
                      shape: BoxShape.circle,
                    ),
                  ),
                  const SizedBox(width: 7),
                ],
                Text(
                  label,
                  style: s.text(size: 14.5, weight: FontWeight.w700, color: fg),
                ),
                if (count != null) ...[
                  const SizedBox(width: 6),
                  Text(
                    '$count',
                    style: s.figures(
                      size: 13,
                      weight: FontWeight.w600,
                      color: selected
                          ? c.onSelected.withValues(alpha: .75)
                          : c.textMuted,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// One destination on the navigation rail.
class RailButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final bool selected;
  final VoidCallback? onTap;
  final Color? badge;
  const RailButton({
    super.key,
    required this.icon,
    required this.label,
    this.selected = false,
    this.onTap,
    this.badge,
  });

  @override
  Widget build(BuildContext context) {
    final c = SpColors.of(context);
    final s = BrandSkin.of(context);
    return Semantics(
      button: true,
      selected: selected,
      label: label,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Stack(
                clipBehavior: Clip.none,
                children: [
                  AnimatedContainer(
                    duration: s.normal,
                    curve: s.ease,
                    width: 56,
                    height: 34,
                    decoration: BoxDecoration(
                      color: selected ? c.sageMist : Colors.transparent,
                      borderRadius: BorderRadius.circular(40),
                    ),
                    child: Icon(
                      icon,
                      size: 24,
                      color: selected ? c.strong : c.textMuted,
                    ),
                  ),
                  if (badge != null)
                    Positioned(
                      right: 10,
                      top: 4,
                      child: Container(
                        width: 9,
                        height: 9,
                        decoration: BoxDecoration(
                          color: badge,
                          shape: BoxShape.circle,
                          border: Border.all(color: c.surface, width: 1.5),
                        ),
                      ),
                    ),
                ],
              ),
              const SizedBox(height: 4),
              Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: s.text(
                  size: 12,
                  weight: selected ? FontWeight.w800 : FontWeight.w600,
                  color: selected ? c.strong : c.textMuted,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
