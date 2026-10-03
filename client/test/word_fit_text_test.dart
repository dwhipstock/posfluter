// Kiosk tiles (menu and "Add a drink?"): an item name wraps only between
// words; a word too long for the tile shrinks the font instead of splitting
// ("Hausgemach / te Limonade").
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/widgets/word_fit_text.dart';

Future<void> _loadFonts() async {
  final manifest =
      jsonDecode(await rootBundle.loadString('FontManifest.json')) as List;
  for (final entry in manifest) {
    final loader = FontLoader(entry['family'] as String);
    for (final font in entry['fonts'] as List) {
      loader.addFont(rootBundle.load(font['asset'] as String));
    }
    await loader.load();
  }
}

// the kiosk's own type (Inter), as on the kiosk
const _style = TextStyle(
  fontFamily: 'Inter',
  fontSize: 20,
  fontWeight: FontWeight.w700,
);

/// Each word of the laid-out text sits on one line.
void _expectWholeWords(RenderParagraph p, String why) {
  final text = p.text.toPlainText();
  var from = 0;
  for (final word in text.split(' ')) {
    final start = text.indexOf(word, from);
    from = start + word.length;
    final tops = p
        .getBoxesForSelection(
          TextSelection(baseOffset: start, extentOffset: start + word.length),
        )
        .map((b) => b.top.round())
        .toSet();
    expect(tops, hasLength(1), reason: '$why: "$word" split across lines');
  }
}

void main() {
  setUpAll(_loadFonts);

  Future<RenderParagraph> pump(
    WidgetTester tester,
    String text,
    double width,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Align(
          alignment: Alignment.topLeft,
          child: SizedBox(
            width: width,
            child: WordFitText(text, key: const Key('t'), style: _style),
          ),
        ),
      ),
    );
    return tester.renderObject<RenderParagraph>(
      find.descendant(
        of: find.byKey(const Key('t')),
        matching: find.byType(RichText),
      ),
    );
  }

  for (final width in [90.0, 110.0, 130.0, 160.0, 220.0]) {
    testWidgets('"Hausgemachte Limonade" at $width wide: whole words', (
      tester,
    ) async {
      final p = await pump(tester, 'Hausgemachte Limonade', width);
      _expectWholeWords(p, '$width');
      expect(p.didExceedMaxLines, isFalse);
    });
  }

  testWidgets('a name that fits keeps the full size', (tester) async {
    final p = await pump(tester, 'Hausgemachte Limonade', 400);
    final span = p.text as TextSpan;
    expect(span.style!.fontSize, 20);
  });

  testWidgets('a word too wide shrinks the font, not split', (tester) async {
    final p = await pump(tester, 'Hausgemachte Limonade', 100);
    final span = p.text as TextSpan;
    expect(span.style!.fontSize, lessThan(20));
  });
}
