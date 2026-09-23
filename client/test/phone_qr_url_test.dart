import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';

void main() {
  test('phone QR uses the tablet LAN origin', () {
    expect(
      Api.phoneQrBaseUrl('http://192.168.1.149:8080/ignored'),
      'http://192.168.1.149:8080',
    );
  });

  test('phone QR never advertises tablet-only or invalid addresses', () {
    for (final address in [
      null,
      '',
      'http://localhost:8080',
      'http://127.0.0.1:8080',
      'http://127.1.2.3:8080',
      'http://0.0.0.0:8080',
      'http://10.0.2.2:8080',
      'file:///etc/passwd',
    ]) {
      expect(Api.phoneQrBaseUrl(address), isNull, reason: '$address');
    }
  });
}
