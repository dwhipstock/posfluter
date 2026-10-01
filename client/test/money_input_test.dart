import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/money_input.dart';
import 'package:pos_client/text_utils.dart';

void main() {
  test('price boxes accept North American dollars and cents', () {
    const ok = {
      '12': 1200,
      '12.5': 1250,
      '12.99': 1299,
      r'$12.99': 1299,
      r' $ 12.99 ': 1299,
      '1,234.56': 123456,
      '1234.56': 123456,
      '100,000.00': 10000000,
      '.5': 50,
      '0': 0,
      '7.50': 750,
    };
    for (final MapEntry(:key, :value) in ok.entries) {
      expect(parseMoneyCents(key), value, reason: key);
    }
  });

  test('price boxes refuse junk, negatives and decimal commas', () {
    for (final s in [
      '',
      ' ',
      '.',
      r'$',
      '-5',
      r'$-1',
      '12,50',
      '1,23',
      '12.999',
      '1e3',
      'abc',
      '12.5.0',
      'NaN',
      '１２',
      '100000.01',
    ]) {
      expect(parseMoneyCents(s), isNull, reason: s);
    }
  });

  test('a saved price round-trips through the box unchanged', () {
    for (final c in [0, 5, 750, 2025, 1299, 123456, 10000000]) {
      expect(parseMoneyCents(centsToMoneyInput(c)), c);
    }
    expect(centsToMoneyInput(750), '7.50');
    expect(centsToMoneyInput(123456), '1,234.56');
  });

  test('initials take whole characters, emoji included', () {
    expect(initialsOf('Demo Manager'), 'DM');
    expect(initialsOf('🍔 Burger Bob'), '🍔B');
    expect(initialsOf('👨‍👩‍👧 Family'), '👨‍👩‍👧F');
    expect(initialsOf('élodie'), 'É');
    expect(initialsOf(''), '?');
    expect(initialsOf(null), '?');
  });

  test('oneLine flattens line breaks', () {
    expect(oneLine('VIP\nVIP\r\n\tVIP  '), 'VIP VIP VIP');
  });
}
