import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/retail/scan_detector.dart';

Duration ms(int n) => Duration(milliseconds: n);

void main() {
  /// Type [text] one key every [gap] ms from [start]; returns the next free time.
  int type(ScanBurstDetector d, String text, int start, int gap) {
    var t = start;
    for (final c in text.split('')) {
      d.char(c, ms(t));
      t += gap;
    }
    return t;
  }

  group('ScanBurstDetector', () {
    test('a fast burst ended by Enter is a scan', () {
      final d = ScanBurstDetector();
      final t = type(d, '487230001017', 1000, 8);
      expect(d.enter(ms(t)), '487230001017');
      expect(d.pending, ''); // consumed
    });

    test('a person typing, then Enter, is not a scan', () {
      final d = ScanBurstDetector();
      final t = type(d, 'club soda', 1000, 140);
      expect(d.enter(ms(t)), isNull);
    });

    test('hand-typed text before a scan does not leak into the code', () {
      final d = ScanBurstDetector();
      var t = type(d, 'ice', 1000, 180); // a person starts a search…
      t = type(d, '487230007017', t + 400, 6); // …then scans
      expect(d.enter(ms(t)), '487230007017');
    });

    test('too short to be a barcode is not a scan', () {
      final d = ScanBurstDetector();
      final t = type(d, '123', 1000, 5);
      expect(d.enter(ms(t)), isNull);
    });

    test('an Enter long after the burst is a person pressing Enter', () {
      final d = ScanBurstDetector();
      final t = type(d, '487230001017', 1000, 8);
      expect(d.enter(ms(t + 600)), isNull);
    });

    test('a hesitation mid-code splits it: neither half is a scan', () {
      final d = ScanBurstDetector();
      var t = type(d, '48723', 1000, 8);
      t = type(d, '0001', t + 300, 8);
      expect(d.enter(ms(t)), isNull); // only 4 keys in the last burst
    });

    test('HidScanner emits scans on its stream', () async {
      final s = HidScanner();
      final got = <String>[];
      final sub = s.scans.listen(got.add);
      var t = 0;
      for (final c in '012345678905'.split('')) {
        s.char(c, ms(t));
        t += 7;
      }
      expect(s.enter(ms(t)), '012345678905');
      await Future<void>.delayed(Duration.zero);
      expect(got, ['012345678905']);
      await sub.cancel();
      s.dispose();
    });
  });

  group('IdScanCapture', () {
    const card =
        '@\nANSI 636014090002DL00410278ZC03190024DLDAQD1234562\nDCSSAMPLE\nDBB07041998\nDBA07042030\nDCGUSA';

    test('collects a multi-line licence burst once it goes quiet', () {
      final c = IdScanCapture();
      var t = 0;
      for (final ch in card.split('')) {
        if (ch == '\n') {
          c.enter(ms(t));
        } else {
          c.key(ch, ms(t));
        }
        t += 4;
      }
      expect(c.complete(ms(t + 20)), isNull); // still arriving
      expect(c.complete(ms(t + 200)), card);
    });

    test('a product barcode is too short to be an ID', () {
      final c = IdScanCapture();
      var t = 0;
      for (final ch in '487230001017'.split('')) {
        c.key(ch, ms(t));
        t += 5;
      }
      expect(c.complete(ms(t + 200)), isNull);
    });
  });
}
