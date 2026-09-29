import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/widgets/custom_object_dialog.dart';
import 'package:pos_client/widgets/floor_object_icons.dart';
import 'package:pos_client/widgets/floor_plan.dart';

/// The room's landmarks (entrance, kitchen…) and manager-made CUSTOM objects.
void main() {
  tearDown(() => Prefs.instance.lang = 'en');

  Future<void> pump(WidgetTester t, Widget child) => t.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: Center(child: SizedBox(width: 200, height: 120, child: child)),
      ),
    ),
  );

  testWidgets('a built-in landmark shows its icon and a caption', (t) async {
    Prefs.instance.lang = 'fr';
    await pump(
      t,
      FloorObjectShape(
        object: FloorObject.fromJson({
          'id': 'k',
          'type': 'RESTROOMS',
          'width': 200,
          'height': 120,
        }),
        scale: 1,
      ),
    );
    expect(find.byIcon(Icons.wc), findsOneWidget);
    expect(find.text('Toilettes'), findsOneWidget);
  });

  testWidgets('a custom object wears its icon and its own name', (t) async {
    await pump(
      t,
      FloorObjectShape(
        object: FloorObject.fromJson({
          'id': 'c',
          'type': 'CUSTOM',
          'width': 200,
          'height': 120,
          'labelFr': 'Juke-box',
          'labelEn': 'Jukebox',
          'icon': 'music',
          'shape': 'ROUND',
        }),
        scale: 1,
      ),
    );
    expect(find.byIcon(Icons.music_note), findsOneWidget);
    expect(find.text('Jukebox'), findsOneWidget);
  });

  test('every icon key has an icon; unknown keys fall back to the star', () {
    expect(floorObjectIcons.length, 20);
    expect(floorObjectTypeIcon('CUSTOM', 'dragon'), Icons.star);
    expect(floorObjectTypeIcon('POOL', null), isNull);
  });

  testWidgets('an AI suggestion pre-fills the dialog and can be edited', (
    t,
  ) async {
    Map<String, dynamic>? body;
    await t.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () async => body = await showDialog(
              context: context,
              builder: (_) => const CustomObjectDialog(
                suggestion: RoomObjectSuggestion(
                  labelEn: 'Jukebox',
                  labelFr: 'Juke-box',
                  icon: 'music',
                  shape: 'RECT',
                  width: 70,
                  height: 60,
                ),
              ),
            ),
            child: const Text('open'),
          ),
        ),
      ),
    );
    await t.tap(find.text('open'));
    await t.pumpAndSettle();
    expect(find.text('Jukebox'), findsOneWidget);
    await t.enterText(find.byKey(const Key('custom-object-en')), 'Old jukebox');
    await t.tap(find.byKey(const Key('custom-object-icon-speaker')));
    await t.tap(find.text('Round'));
    await t.pump();
    await t.tap(find.byKey(const Key('custom-object-place')));
    await t.pumpAndSettle();
    expect(body, {
      'type': 'CUSTOM',
      'labelFr': 'Juke-box',
      'labelEn': 'Old jukebox',
      'icon': 'speaker',
      'shape': 'ROUND',
      'width': 70,
      'height': 60,
    });
  });
}
