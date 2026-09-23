import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';

void main() {
  group('isLocalVenueUrl', () {
    test('accepts private, loopback, link-local, and mDNS origins', () {
      expect(Api.isLocalVenueUrl('http://192.168.1.2:8080'), isTrue);
      expect(Api.isLocalVenueUrl('http://10.0.0.5:8080'), isTrue);
      expect(Api.isLocalVenueUrl('http://172.20.4.8:8080'), isTrue);
      expect(Api.isLocalVenueUrl('http://127.0.0.1:8080'), isTrue);
      expect(Api.isLocalVenueUrl('http://copper-lantern.local:8080'), isTrue);
      expect(Api.isLocalVenueUrl('http://169.254.10.2:8080'), isTrue);
    });

    test('rejects Internet-hosted and malformed origins', () {
      expect(
        Api.isLocalVenueUrl('https://copperlantern.lostmindllc.com'),
        isFalse,
      );
      expect(Api.isLocalVenueUrl('https://8.8.8.8'), isFalse);
      expect(Api.isLocalVenueUrl('not a url'), isFalse);
    });
  });

  group('normalizeVenueAddress', () {
    test('bare host gets https (cloud venue)', () {
      expect(
        Api.normalizeVenueAddress('demo.example.com'),
        'https://demo.example.com',
      );
    });

    test('host with explicit port and no scheme gets http (LAN box)', () {
      expect(
        Api.normalizeVenueAddress('192.168.1.50:8080'),
        'http://192.168.1.50:8080',
      );
    });

    test('explicit :443 stays https, not plaintext http', () {
      // review F38: a cloud venue typed with its TLS port must not be forced to http
      expect(
        Api.normalizeVenueAddress('demo.example.com:443'),
        'https://demo.example.com:443',
      );
    });

    test('explicit schemes are kept as typed', () {
      expect(
        Api.normalizeVenueAddress('http://demo.example.com'),
        'http://demo.example.com',
      );
      expect(
        Api.normalizeVenueAddress('https://venue.example:8443'),
        'https://venue.example:8443',
      );
    });

    test('trims whitespace and trailing slashes', () {
      expect(
        Api.normalizeVenueAddress('  venue.example.com/  '),
        'https://venue.example.com',
      );
      expect(
        Api.normalizeVenueAddress('https://venue.example.com///'),
        'https://venue.example.com',
      );
    });

    test('blank or hostless input is rejected', () {
      expect(Api.normalizeVenueAddress(null), isNull);
      expect(Api.normalizeVenueAddress(''), isNull);
      expect(Api.normalizeVenueAddress('   '), isNull);
      expect(Api.normalizeVenueAddress('https://'), isNull);
    });
  });

  group('normalizePairingCode', () {
    test('uppercases and keeps the canonical dash', () {
      expect(Api.normalizePairingCode('abcd-efgh'), 'ABCD-EFGH');
      expect(Api.normalizePairingCode('ABCD-EFGH'), 'ABCD-EFGH');
    });

    test('tolerates spaces and missing dashes', () {
      expect(Api.normalizePairingCode('abcdefgh'), 'ABCD-EFGH');
      expect(Api.normalizePairingCode('ABCD EFGH'), 'ABCD-EFGH');
      expect(Api.normalizePairingCode(' ab cd-EF gh '), 'ABCD-EFGH');
    });

    test('digits survive; stray punctuation is dropped', () {
      expect(Api.normalizePairingCode('a1b2-c3d4'), 'A1B2-C3D4');
      expect(Api.normalizePairingCode('a1b2.c3d4!'), 'A1B2-C3D4');
    });

    test('blank input is rejected', () {
      expect(Api.normalizePairingCode(null), isNull);
      expect(Api.normalizePairingCode(''), isNull);
      expect(Api.normalizePairingCode(' - '), isNull);
    });
  });
}
