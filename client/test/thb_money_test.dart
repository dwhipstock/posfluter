import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/money_input.dart';

void main() {
  const thb = StoreProfile(
    venueId: 'thb-store',
    brand: 'copper-lantern',
    kind: 'restaurant',
    country: 'TH',
    currency: 'THB',
    locales: ['en'],
    legalAge: 20,
  );

  tearDown(() => StoreProfile.current = StoreProfile.pub);

  test('THB: baht symbol, comma thousands, satang only when non-zero', () {
    expect(formatMoney(123400, 'THB'), '฿1,234');
    expect(formatMoney(123450, 'THB'), '฿1,234.50');
    expect(formatMoney(-500, 'THB'), '-฿5');
    expect(formatMoney(0, 'THB'), '฿0');
    expect(formatMoney(5, 'THB'), '฿0.05');
    expect(formatMoney(215000000, 'THB'), '฿2,150,000');
    expect(formatMoney(123400, 'thb', lang: 'fr'), '฿1,234');
  });

  test('THB store: money(), signedMoney() and the input symbol follow it', () {
    StoreProfile.current = thb;
    expect(money(123400), '฿1,234');
    expect(money(99), '฿0.99');
    expect(signedMoney(100), '+฿1');
    expect(signedMoney(-50), '−฿0.50');
    expect(currencySymbol(), '฿');
  });

  test('USD / CAD / other unchanged', () {
    expect(formatMoney(123400, 'USD'), '\$1,234.00');
    expect(formatMoney(123450, 'CAD'), '\$1,234.50');
    expect(formatMoney(0, 'CAD'), '\$0.00');
    expect(formatMoney(-500, 'USD'), '-\$5.00');
    expect(formatMoney(123400, 'EUR'), 'EUR 1,234.00');
    expect(signedMoney(-2, currency: 'CAD'), '−\$0.02');
    expect(currencySymbol('USD'), '\$');
    expect(currencySymbol('CAD'), '\$');
    expect(currencySymbol('EUR'), 'EUR');
  });

  test('price boxes accept a leading baht sign', () {
    expect(parseMoneyCents('฿1,234'), 123400);
    expect(parseMoneyCents('฿ 12.50'), 1250);
    expect(parseMoneyCents('\$12.99'), 1299);
  });
}
