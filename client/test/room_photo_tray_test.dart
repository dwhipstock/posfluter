import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/design/tokens.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/widgets/room_photo_tray.dart';

/// "Set up from picture": the photo tray gathers 1–4 views of one room
/// (take, take another, pick several, remove) before one "Set up room".
RoomPhoto _photo(int n) =>
    (bytes: List.filled(16, n), contentType: 'image/jpeg');

void main() {
  setUp(() => Prefs.instance.lang = 'en');

  Future<Future<List<RoomPhoto>?>> open(
    WidgetTester t, {
    Future<RoomPhoto?> Function()? take,
    Future<List<RoomPhoto>> Function(int)? pick,
    bool files = false,
    Size size = const Size(1280, 800),
  }) async {
    t.view.physicalSize = size;
    t.view.devicePixelRatio = 1;
    addTearDown(t.view.reset);
    late BuildContext ctx;
    await t.pumpWidget(
      prefsScope(
        child: MaterialApp(
          theme: buildPosTheme(),
          home: Builder(
            builder: (c) {
              ctx = c;
              return const Scaffold();
            },
          ),
        ),
      ),
    );
    final result = showDialog<List<RoomPhoto>>(
      context: ctx,
      builder: (_) => RoomPhotoTray(
        takePhoto: take,
        pickPhotos: pick ?? (_) async => const [],
        filesLabel: files,
      ),
    );
    await t.pumpAndSettle();
    return result;
  }

  ButtonStyleButton go(WidgetTester t) =>
      t.widget<ButtonStyleButton>(find.byKey(const Key('room-photo-go')));

  testWidgets('one photo: take it, Set up room — the single-photo path', (
    t,
  ) async {
    var n = 0;
    final result = await open(t, take: () async => _photo(++n));
    expect(find.text('Take a photo'), findsOneWidget);
    expect(go(t).onPressed, isNull, reason: 'nothing to send yet');

    await t.tap(find.byKey(const Key('room-photo-take')));
    await t.pumpAndSettle();
    expect(find.byKey(const Key('room-photo-0')), findsOneWidget);
    expect(find.text('1 of 4 photos'), findsOneWidget);
    expect(find.text('Take another photo'), findsOneWidget);

    await t.tap(find.byKey(const Key('room-photo-go')));
    await t.pumpAndSettle();
    final photos = await result;
    expect(photos, hasLength(1));
    expect(photos!.single.bytes.first, 1);
  });

  testWidgets('take, pick several, capped at 4; remove one; send in order', (
    t,
  ) async {
    var n = 0;
    final limits = <int>[];
    final result = await open(
      t,
      take: () async => _photo(++n),
      pick: (limit) async {
        limits.add(limit);
        return [for (var i = 0; i < 6; i++) _photo(10 + i)];
      },
    );
    await t.tap(find.byKey(const Key('room-photo-take')));
    await t.pumpAndSettle();
    await t.tap(find.byKey(const Key('room-photo-pick')));
    await t.pumpAndSettle();
    expect(limits, [3], reason: 'the picker is told how many are left');
    expect(find.text('4 of 4 photos'), findsOneWidget);
    for (var i = 0; i < 4; i++) {
      expect(find.byKey(Key('room-photo-$i')), findsOneWidget);
    }
    // full: no more adding
    final take = t.widget<ButtonStyleButton>(
      find.byKey(const Key('room-photo-take')),
    );
    expect(take.onPressed, isNull);

    // remove the second (the first picked one)
    await t.tap(
      find.descendant(
        of: find.byKey(const Key('room-photo-1')),
        matching: find.byTooltip('Remove photo'),
      ),
    );
    await t.pumpAndSettle();
    expect(find.text('3 of 4 photos'), findsOneWidget);
    expect(find.byKey(const Key('room-photo-3')), findsNothing);

    await t.tap(find.byKey(const Key('room-photo-go')));
    await t.pumpAndSettle();
    expect((await result)!.map((p) => p.bytes.first), [1, 11, 12]);
  });

  testWidgets('no camera (picked files on a desktop): files button only', (
    t,
  ) async {
    final result = await open(
      t,
      files: true,
      pick: (limit) async => [_photo(1), _photo(2)],
    );
    expect(find.byKey(const Key('room-photo-take')), findsNothing);
    expect(find.text('Choose files'), findsOneWidget);
    await t.tap(find.byKey(const Key('room-photo-pick')));
    await t.pumpAndSettle();
    expect(find.text('2 of 4 photos'), findsOneWidget);
    await t.tap(find.text('Cancel'));
    await t.pumpAndSettle();
    expect(await result, isNull, reason: 'backed out: nothing is sent');
  });

  testWidgets('a cancelled camera adds nothing; a double tap opens it once', (
    t,
  ) async {
    var opened = 0;
    final shot = Completer<RoomPhoto?>();
    await open(
      t,
      take: () {
        opened++;
        return shot.future;
      },
    );
    await t.tap(find.byKey(const Key('room-photo-take')));
    await t.pump();
    await t.tap(find.byKey(const Key('room-photo-take')), warnIfMissed: false);
    await t.pump();
    expect(opened, 1);
    shot.complete(null);
    await t.pumpAndSettle();
    expect(find.byKey(const Key('room-photo-0')), findsNothing);
    expect(go(t).onPressed, isNull);
  });

  for (final lang in ['fr', 'en', 'es', 'de', 'af']) {
    testWidgets('fits a small landscape window in $lang', (t) async {
      Prefs.instance.lang = lang;
      await open(
        t,
        size: const Size(800, 480),
        take: () async => _photo(1),
        pick: (limit) async => [for (var i = 0; i < limit; i++) _photo(i)],
      );
      await t.tap(find.byKey(const Key('room-photo-pick')));
      await t.pumpAndSettle();
      expect(t.takeException(), isNull);
      expect(find.byKey(const Key('room-photo-3')), findsOneWidget);
      // everything on screen without scrolling, the Set up button included
      final go = t.getRect(find.byKey(const Key('room-photo-go')));
      expect(go.bottom, lessThanOrEqualTo(480));
      expect(go.right, lessThanOrEqualTo(800));
    });
  }
}
