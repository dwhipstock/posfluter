import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/api.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/widgets/floor_plan.dart';

/// Floor-plan captions follow the terminal's language, like zone names.
void main() {
  FloorObject obj(String type, Map<String, dynamic> labels) =>
      FloorObject.fromJson({
        'id': 'o',
        'type': type,
        'width': 500,
        'height': 280,
        ...labels,
      });

  Future<void> pump(WidgetTester t, FloorObject o) => t.pumpWidget(
    MaterialApp(
      home: Center(
        child: SizedBox(
          width: 500,
          height: 280,
          child: FloorObjectShape(object: o, scale: 1),
        ),
      ),
    ),
  );

  tearDown(() => Prefs.instance.lang = 'en');

  final pool = {'labelFr': 'Billard', 'labelEn': 'Pool'};

  testWidgets('a seeded caption reads in French and in English', (t) async {
    Prefs.instance.lang = 'fr';
    await pump(t, obj('POOL', pool));
    expect(find.text('Billard'), findsOneWidget);
    expect(find.text('Pool'), findsNothing);

    Prefs.instance.lang = 'en';
    await pump(
      t,
      obj('BAR_FRONT', {
        'labelFr': 'Comptoir à sushis',
        'labelEn': 'Sushi Counter',
      }),
    );
    expect(find.text('Sushi Counter'), findsOneWidget);
  });

  testWidgets('Spanish reads the English side, as the catalog does', (t) async {
    Prefs.instance.lang = 'es';
    await pump(t, obj('POOL', pool));
    expect(find.text('Pool'), findsOneWidget);
  });

  testWidgets('an older store\'s single label shows in both languages', (
    t,
  ) async {
    Prefs.instance.lang = 'fr';
    await pump(t, obj('POOL', {'label': 'Snooker'}));
    expect(find.text('Snooker'), findsOneWidget);
  });

  testWidgets('an unlabelled pool table gets a caption in the language', (
    t,
  ) async {
    Prefs.instance.lang = 'fr';
    await pump(t, obj('POOL', {'labelFr': null, 'labelEn': null}));
    expect(find.text('Billard'), findsOneWidget);
    Prefs.instance.lang = 'es';
    await pump(t, obj('POOL', {}));
    expect(find.text('Billar'), findsOneWidget);
  });
}
