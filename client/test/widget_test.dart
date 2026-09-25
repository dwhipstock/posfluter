import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';

void main() {
  test('CAD formats cents in the house style', () {
    expect(cad(0), '\$0');
    expect(cad(100), '\$1');
    expect(cad(101000), '\$1,010');
    expect(cad(21550), '\$215.50');
    expect(cad(-50), '-\$0.50');
    expect(cad(215000000), '\$2,150,000');
    expect(
      cad(-2550),
      '-\$25.50',
    ); // refund: negative, thousands + cents together
  });
}
