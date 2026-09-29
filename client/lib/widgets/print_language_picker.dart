import 'package:flutter/material.dart';

import '../api.dart';
import '../i18n.dart';

/// Long-press on a print button: print this one copy in another of the
/// store's languages. Each language is shown in its own name (Français,
/// English, Español, Deutsch). With one store language it just prints; the
/// owner's language preference is never changed.
Future<void> printInPickedLanguage(
  BuildContext context,
  Future<void> Function(String? lang) print,
) async {
  final locales = StoreProfile.current.locales;
  if (locales.length <= 1) return print(null);
  final lang = await showDialog<String>(
    context: context,
    builder: (ctx) => SimpleDialog(
      title: Text(L.of(ctx).printIn),
      children: [
        for (final code in locales)
          SimpleDialogOption(
            onPressed: () => Navigator.pop(ctx, code),
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
            child: Text(ownLanguageName(code)),
          ),
      ],
    ),
  );
  if (lang != null) await print(lang);
}

/// A language in its own name: "de" → "Deutsch".
String ownLanguageName(String code) {
  final n = L.forLang(code).langName(code);
  return n.isEmpty ? n : n[0].toUpperCase() + n.substring(1);
}
