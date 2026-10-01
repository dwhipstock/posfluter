import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/widgets/receipt_text.dart';

/// A 48-column printed row stays one row on screen, even with the tablet's
/// font size turned up (the Galaxy Tab ships at 115%): the receipt shrinks
/// to the card instead of wrapping "Bill #13" or "10:38 AM" onto a new line.
void main() {
  final row = '${'Open'.padRight(26)}10/01/2026 10:38 AM'.padRight(48, '-');
  final text = '$row\n$row\n$row';

  for (final scale in [1.0, 1.15, 1.5]) {
    testWidgets('three 48-col rows stay three rows at ${scale}x text', (t) async {
      await t.pumpWidget(MaterialApp(
        home: MediaQuery(
          data: MediaQueryData(textScaler: TextScaler.linear(scale)),
          child: Scaffold(
            body: Align(
              alignment: Alignment.topLeft,
              child: SizedBox(width: 300, child: ReceiptText(text)),
            ),
          ),
        ),
      ));
      final p = t.renderObject<RenderParagraph>(find.byType(RichText));
      final lines = p.getBoxesForSelection(
        TextSelection(baseOffset: 0, extentOffset: text.length),
      ).map((b) => b.top.round()).toSet();
      expect(lines.length, 3);
      expect(t.takeException(), isNull);
      expect(t.getSize(find.byType(ReceiptText)).width, lessThanOrEqualTo(300));
    });
  }
}
