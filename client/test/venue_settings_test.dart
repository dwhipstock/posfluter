import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';

Map<String, dynamic> _base() => {
  'cardProcessor': '',
  'bankName': '',
  'bankAccountNumber': '',
  'bankAccountName': '',
  'serviceChargePercent': 0,
  'corkagePerBottleCents': 0,
  'receiptFooter': '',
  'venuePhone': '',
  'venueAddress': '',
  'sessionIdleMinutes': 5,
};

void main() {
  test('guest Wi-Fi settings parse, with safe defaults', () {
    final none = VenueSettings.fromJson(_base());
    expect(none.wifiSsid, '');
    expect(none.wifiSecurity, 'WPA');
    expect(none.wifiHidden, isFalse);

    final s = VenueSettings.fromJson({
      ..._base(),
      'wifiSsid': 'Lantern Guests',
      'wifiPassword': 'pass1234',
      'wifiSecurity': 'nopass',
      'wifiHidden': true,
    });
    expect(s.wifiSsid, 'Lantern Guests');
    expect(s.wifiPassword, 'pass1234');
    expect(s.wifiSecurity, 'nopass');
    expect(s.wifiHidden, isTrue);
  });

  test('a redacted password stays null (not an empty password)', () {
    final s = VenueSettings.fromJson({
      ..._base(),
      'wifiSsid': 'Lantern Guests',
      'wifiPassword': null,
    });
    expect(s.wifiPassword, isNull);
  });

  test('Wi-Fi slip result parses', () {
    final r = WifiSlipResult.fromJson({
      'configured': true,
      'online': true,
      'printed': 2,
      'copies': 2,
    });
    expect(r.printed, 2);
    expect(r.online, isTrue);
  });
}
