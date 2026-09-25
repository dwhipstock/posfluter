import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/i18n.dart';

void main() {
  test('store display name splits into brand and location', () {
    expect(Api.splitVenueName('Copper Lantern — Vieux-Port'), (
      'Copper Lantern',
      'Vieux-Port',
    ));
    expect(Api.splitVenueName('Copper Lantern - Plateau'), (
      'Copper Lantern',
      'Plateau',
    ));
  });

  test('missing or single-part names fall back safely', () {
    expect(Api.splitVenueName(null), ('Copper Lantern', null));
    expect(Api.splitVenueName('  '), ('Copper Lantern', null));
    expect(Api.splitVenueName('Copper Lantern'), ('Copper Lantern', null));
  });

  test('seat counts pluralise in both languages', () {
    const en = L(true), fr = L(false);
    expect(en.seatsShort(1), '1 seat');
    expect(en.seatsShort(4), '4 seats');
    expect(fr.seatsShort(1), '1 place');
    expect(fr.seatsShort(4), '4 places');
  });
}
