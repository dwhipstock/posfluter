import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';

void main() {
  test('CAD always shows two decimals', () {
    expect(cad(0), '\$0.00');
    expect(cad(100), '\$1.00');
    expect(cad(800), '\$8.00');
    expect(cad(1300), '\$13.00');
    expect(cad(101000), '\$1,010.00');
    expect(cad(21550), '\$215.50');
    expect(cad(-50), '-\$0.50');
    expect(cad(215000000), '\$2,150,000.00');
    expect(
      cad(-2550),
      '-\$25.50',
    ); // refund: negative, thousands + cents together
  });
}
