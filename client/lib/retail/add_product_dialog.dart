import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../api.dart';
import '../design/tokens.dart';
import 'retail_i18n.dart';
import 'sp_theme.dart';

/// "Unknown barcode": the sale waits while a manager adds the product. Resolves
/// true when the cashier chose to add it.
Future<bool> showUnknownBarcode(BuildContext context, String code) async {
  final r = R.of(context);
  final c = SpColors.of(context);
  final add = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Row(
        children: [
          Icon(LucideIcons.scanBarcode, color: c.poppy),
          const SizedBox(width: 10),
          Flexible(child: Text(r.unknownTitle(code))),
        ],
      ),
      content: SizedBox(
        width: 460,
        child: Text(r.unknownBody, style: T.text(color: c.text)),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(ctx, false),
          child: Text(r.cancel),
        ),
        FilledButton.icon(
          icon: const Icon(LucideIcons.packagePlus),
          onPressed: () => Navigator.pop(ctx, true),
          label: Text(r.addProduct),
        ),
      ],
    ),
  );
  return add == true;
}

/// A manager adds the product behind an unknown barcode: name, price,
/// category, 21+, taxable, and its bottle deposit (container size × units).
/// If the store is online, Open Food Facts may suggest the name; offline or
/// no match, the manager types it — never in the way of the sale.
class AddProductDialog extends StatefulWidget {
  final String barcode;
  final List<Category> categories;
  const AddProductDialog({
    super.key,
    required this.barcode,
    required this.categories,
  });

  static Future<bool> show(
    BuildContext context,
    String barcode,
    List<Category> categories,
  ) async =>
      await showDialog<bool>(
        context: context,
        builder: (_) =>
            AddProductDialog(barcode: barcode, categories: categories),
      ) ==
      true;

  @override
  State<AddProductDialog> createState() => _AddProductDialogState();
}

class _AddProductDialogState extends State<AddProductDialog> {
  final _name = TextEditingController();
  final _price = TextEditingController();
  final _pin = TextEditingController();
  String? _category;
  bool _age = true;
  bool _taxable = true;
  String _crv = 'NONE';
  int _pack = 1;
  bool _busy = false;
  String? _lookup; // 'busy' | 'found' | 'none'
  String? _error;

  bool get _needsPin => Api.currentUser?.can(Perm.editMenu) != true;

  @override
  void initState() {
    super.initState();
    _category = widget.categories.isEmpty ? null : widget.categories.first.id;
    _suggest();
  }

  @override
  void dispose() {
    _name.dispose();
    _price.dispose();
    _pin.dispose();
    super.dispose();
  }

  Future<void> _suggest() async {
    setState(() => _lookup = 'busy');
    final name = await Api.lookupBarcode(widget.barcode);
    if (!mounted) return;
    setState(() {
      if (name != null && _name.text.trim().isEmpty) _name.text = name;
      _lookup = name != null ? 'found' : 'none';
    });
  }

  int? get _priceCents {
    final v = double.tryParse(_price.text.trim().replaceAll(',', '.'));
    if (v == null || v <= 0) return null;
    return (v * 100).round();
  }

  Future<void> _save() async {
    final r = R.of(context);
    final cents = _priceCents;
    if (_name.text.trim().isEmpty || cents == null || _category == null) {
      setState(() => _error = r.fillRequired);
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await Api.addRetailProduct(
        barcode: widget.barcode,
        name: _name.text.trim(),
        priceCents: cents,
        categoryId: _category!,
        ageRestricted: _age,
        crvSize: _crv,
        packUnits: _pack,
        taxable: _taxable,
        managerPin: _needsPin ? _pin.text.trim() : null,
      );
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final r = R.of(context);
    final c = SpColors.of(context);
    return AlertDialog(
      title: Text(r.addProductTitle),
      content: SizedBox(
        width: 560,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              InputDecorator(
                decoration: InputDecoration(labelText: r.barcode),
                child: Text(
                  widget.barcode,
                  style: T.price(size: 18, color: c.text),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _name,
                autofocus: true,
                decoration: InputDecoration(labelText: r.name),
              ),
              Padding(
                padding: const EdgeInsets.only(top: 4, left: 4),
                child: Text(switch (_lookup) {
                  'busy' => r.lookingUp,
                  'found' => r.lookupFound,
                  'none' => r.lookupNone,
                  _ => '',
                }, style: T.text(size: 13, color: c.textMuted)),
              ),
              const SizedBox(height: 10),
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _price,
                      keyboardType: const TextInputType.numberWithOptions(
                        decimal: true,
                      ),
                      inputFormatters: [
                        FilteringTextInputFormatter.allow(RegExp(r'[0-9.,]')),
                      ],
                      decoration: InputDecoration(
                        labelText: r.price,
                        prefixText: '\$ ',
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: DropdownButtonFormField<String>(
                      initialValue: _category,
                      isExpanded: true,
                      decoration: InputDecoration(labelText: r.categoryLabel),
                      items: [
                        for (final cat in widget.categories)
                          DropdownMenuItem(
                            value: cat.id,
                            child: Text(r.category(cat.id, cat.nameEn)),
                          ),
                      ],
                      onChanged: (v) => setState(() => _category = v),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                value: _age,
                onChanged: (v) => setState(() => _age = v),
                title: Text(
                  r.ageRestricted,
                  style: T.text(size: 16, color: c.text),
                ),
              ),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                value: _taxable,
                onChanged: (v) => setState(() => _taxable = v),
                title: Text(r.taxable, style: T.text(size: 16, color: c.text)),
              ),
              const SizedBox(height: 6),
              Text(
                r.deposit,
                style: T.text(size: 15, weight: FontWeight.w600, color: c.text),
              ),
              const SizedBox(height: 8),
              SegmentedButton<String>(
                segments: [
                  ButtonSegment(value: 'NONE', label: Text(r.crvNone)),
                  ButtonSegment(value: 'SMALL', label: Text(r.crvSmall)),
                  ButtonSegment(value: 'LARGE', label: Text(r.crvLarge)),
                ],
                selected: {_crv},
                onSelectionChanged: (s) => setState(() => _crv = s.first),
              ),
              if (_crv != 'NONE') ...[
                const SizedBox(height: 10),
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        r.packUnits,
                        style: T.text(size: 16, color: c.text),
                      ),
                    ),
                    IconButton.outlined(
                      onPressed: _pack > 1
                          ? () => setState(() => _pack--)
                          : null,
                      icon: const Icon(LucideIcons.minus),
                    ),
                    SizedBox(
                      width: 48,
                      child: Text(
                        '$_pack',
                        textAlign: TextAlign.center,
                        style: T.price(color: c.text),
                      ),
                    ),
                    IconButton.outlined(
                      onPressed: _pack < 48
                          ? () => setState(() => _pack++)
                          : null,
                      icon: const Icon(LucideIcons.plus),
                    ),
                  ],
                ),
              ],
              if (_needsPin) ...[
                const SizedBox(height: 12),
                TextField(
                  controller: _pin,
                  obscureText: true,
                  keyboardType: TextInputType.number,
                  maxLength: 4,
                  decoration: InputDecoration(
                    labelText: r.managerPin,
                    counterText: '',
                  ),
                ),
              ],
              if (_error != null) ...[
                const SizedBox(height: 10),
                Text(
                  _error!,
                  style: T.text(
                    size: 15,
                    color: c.bad,
                    weight: FontWeight.w600,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          child: Text(r.cancel),
        ),
        FilledButton(onPressed: _busy ? null : _save, child: Text(r.save)),
      ],
    );
  }
}
