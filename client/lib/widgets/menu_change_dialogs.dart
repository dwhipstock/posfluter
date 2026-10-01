import 'package:flutter/material.dart';

import '../api.dart';
import '../i18n.dart';

/// The add was cancelled on purpose (the staff member declined a new price):
/// nothing to show.
class AddCancelled implements Exception {
  const AddCancelled();
}

/// The store refused because the item's price changed since the screen
/// loaded: ask "Add at $X?" and, if yes, return the variant at the new price
/// (send it again with that as the expected price). Null = not a price
/// change (rethrow / show the error as usual).
Future<Variant?> confirmNewPrice(
  BuildContext context,
  Object error,
  String itemName,
  Variant variant,
) async {
  if (error is! ApiException || error.code != 'price_changed') return null;
  final price = error.priceCents;
  if (price == null) return null;
  final l = L.of(context);
  final ok = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(l.priceChangedTitle),
      content: Text(l.priceChangedTo(itemName, money(price))),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          child: Text(l.cancel),
        ),
        FilledButton(
          key: const Key('price-changed-confirm'),
          onPressed: () => Navigator.pop(context, true),
          child: Text(l.addAtPrice(money(price))),
        ),
      ],
    ),
  );
  if (ok != true) throw const AddCancelled();
  return Variant(variant.id, variant.labelFr, variant.labelEn, price);
}

/// An add the store refused as item_unavailable / price_changed: the menu on
/// screen is stale, so it should be reloaded.
bool isMenuChangeError(Object error) =>
    error is ApiException &&
    (error.code == 'item_unavailable' ||
        error.code == 'price_changed' ||
        error.code == 'lines_rejected');
