import 'package:flutter/material.dart';

/// Text that wraps only between words: when one word is wider than the space
/// ("Hausgemachte" on a narrow kiosk tile), the font shrinks until that word
/// fits on its line, so a name is never split inside a word
/// ("Hausgemach / te"). Up to [maxLines] lines, then an ellipsis.
class WordFitText extends StatelessWidget {
  final String text;
  final TextStyle style;
  final int maxLines;
  final TextAlign textAlign;
  const WordFitText(
    this.text, {
    super.key,
    required this.style,
    this.maxLines = 2,
    this.textAlign = TextAlign.start,
  });

  @override
  Widget build(BuildContext context) {
    final base = DefaultTextStyle.of(context).style.merge(style);
    final scaler = MediaQuery.textScalerOf(context);
    final dir = Directionality.of(context);
    return LayoutBuilder(
      builder: (context, box) {
        var fitted = base;
        if (box.maxWidth.isFinite) {
          final widest = wordWidth(text, base, scaler, dir);
          if (widest > box.maxWidth && widest > 0) {
            final size = base.fontSize ?? 14;
            // a hair under, so rounding never tips the word onto a new line
            // (no floor: a smaller word beats one cut in half)
            final scale = (box.maxWidth / widest * 0.98).clamp(0.0, 1.0);
            fitted = base.copyWith(fontSize: size * scale);
          }
        }
        return Text(
          text,
          maxLines: maxLines,
          overflow: TextOverflow.ellipsis,
          textAlign: textAlign,
          style: fitted,
        );
      },
    );
  }

  /// The width of the widest single word of [text] in [style].
  static double wordWidth(
    String text,
    TextStyle style,
    TextScaler scaler,
    TextDirection dir,
  ) {
    var widest = 0.0;
    for (final word in text.split(RegExp(r'\s+'))) {
      if (word.isEmpty) continue;
      final p = TextPainter(
        text: TextSpan(text: word, style: style),
        textDirection: dir,
        textScaler: scaler,
        maxLines: 1,
      )..layout();
      if (p.width > widest) widest = p.width;
      p.dispose();
    }
    return widest;
  }
}
