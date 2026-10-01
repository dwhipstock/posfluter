import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/app_mode.dart';
import 'package:pos_client/i18n.dart';

void main() {
  test('store display name splits into brand and location', () {
    expect(Api.splitVenueName('Copper Lantern — Glenwood South'), (
      'Copper Lantern',
      'Glenwood South',
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

  test('the pairing title and fallback brand come from the brand config', () {
    addTearDown(() => StoreProfile.current = StoreProfile.pub);
    // Copper Lantern (the default build and the pubs' profile): unchanged
    StoreProfile.current = StoreProfile.pub;
    expect(AppMode.brandName, 'Copper Lantern');
    expect(Api.productName, 'Copper Lantern POS');
    expect(Api.splitVenueName(null), ('Copper Lantern', null));
    // a Sage & Poppy store, or a Sage & Poppy build before it has said
    StoreProfile.current = const StoreProfile(brand: 'sage-poppy');
    expect(Api.productName, 'Sage & Poppy POS');
    expect(Api.splitVenueName(null), ('Sage & Poppy', null));
    expect(AppMode.brandNameFor('sagepoppy'), 'Sage & Poppy');
    expect(AppMode.brandNameFor('pronghorn'), 'Pronghorn');
    expect(AppMode.brandNameFor('copperlantern'), 'Copper Lantern');
  });

  test('seat counts pluralise in both languages', () {
    const en = L(true), fr = L(false);
    expect(en.seatsShort(1), '1 seat');
    expect(en.seatsShort(4), '4 seats');
    expect(fr.seatsShort(1), '1 place');
    expect(fr.seatsShort(4), '4 places');
  });
}
