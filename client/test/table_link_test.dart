import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';

void main() {
  test('table carries its tokenised customer link through edits', () {
    final t = TableInfo.fromJson({
      'id': 't5',
      'label': 'U-1',
      'menuPath': '/m/t/AbCdEfGhIjKlMnOpQrStUv',
    });
    expect(t.menuPath, '/m/t/AbCdEfGhIjKlMnOpQrStUv');
    expect(t.copyWith(x: 10).menuPath, t.menuPath);
    expect(TableInfo.fromJson({'id': 't5', 'label': 'U-1'}).menuPath, isNull);
  });
}
