import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/screens/receipt_screen.dart';

void main() {
  test('the wordmark band replaces the star-framed store name', () {
    const text =
        '          **********\n'
        '  SAGE & POPPY — BOTTLE SHOP\n'
        '          **********\n'
        '1427 Poppy Field Ave\n'
        'Total    12.99';
    final out = withoutNameLine(text);
    expect(out.startsWith('1427 Poppy Field Ave'), isTrue, reason: out);
  });

  test('a pub receipt keeps its name', () {
    const text =
        '**********\nCopper Lantern — Vieux-Port\n**********\nTotal 10';
    expect(withoutNameLine(text), text);
  });
}
