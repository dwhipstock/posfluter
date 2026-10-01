import 'package:flutter/material.dart';
import '../design/tokens.dart';

/// The server's fixed-width receipt text on the paper card: never wraps a
/// printed row. A larger system font size (or a wider monospace font) shrinks
/// the whole receipt to the card instead, so columns stay lined up.
class ReceiptText extends StatelessWidget {
  final String text;
  const ReceiptText(this.text, {super.key});

  @override
  Widget build(BuildContext context) => FittedBox(
    fit: BoxFit.scaleDown,
    alignment: Alignment.topLeft,
    child: Text(text, style: T.receipt(), softWrap: false),
  );
}
