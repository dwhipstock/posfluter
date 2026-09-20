import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/i18n.dart';

void main() {
  // fmtDate/fmtDateTime read the calendar preference off the shared Prefs
  // singleton, so each test sets `calendar` explicitly instead of assuming a
  // default.
  final prefs = Prefs.instance;

  group('Prefs.fmtDate', () {
    test('converts the year to the Gregorian Era () and zero-pads', () {
      prefs.calendar = 'CE';
      expect(prefs.fmtDate(DateTime(2026, 7, 8)), '08/07/2026');
    });

    test('leaves the year as-is under the Gregorian (CE) calendar', () {
      prefs.calendar = 'CE';
      expect(prefs.fmtDate(DateTime(2026, 7, 8)), '08/07/2026');
    });
  });

  group('Prefs.fmtDateTime', () {
    test('appends HH:mm to the CE date', () {
      prefs.calendar = 'CE';
      expect(prefs.fmtDateTime('2026-07-08T00:12:34'), '08/07/2026 00:12');
    });

    test('returns the raw string unchanged when it cannot be parsed', () {
      // edge case: DateTime.tryParse returns null, so the input is echoed back.
      expect(prefs.fmtDateTime('not-a-date'), 'not-a-date');
    });
  });
}
