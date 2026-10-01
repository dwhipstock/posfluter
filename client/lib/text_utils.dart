import 'package:characters/characters.dart';

/// Up to two initials from a person's name ("Demo Manager" → "DM",
/// "🍔 Burger Bob" → "🍔B"). Takes the first user-perceived character
/// (grapheme) of each word, so an emoji, a flag or an accented letter is
/// never cut in half. Empty name → [fallback].
String initialsOf(String? name, {String fallback = '?'}) {
  final out = (name ?? '')
      .split(RegExp(r'\s+'))
      .where((w) => w.isNotEmpty)
      .take(2)
      .map((w) => w.characters.first.toUpperCase())
      .join();
  return out.isEmpty ? fallback : out;
}

/// [text] on one line: line breaks, tabs and runs of spaces become single
/// spaces (a table name pasted with newlines). The server normalizes names
/// on save; this covers names saved before that.
String oneLine(String text) =>
    text.replaceAll(RegExp(r'[\s  ]+'), ' ').trim();
