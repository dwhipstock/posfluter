// The Windows tablet's on-screen keyboard (lib/keyboard/): it is Flutter's
// virtual keyboard, so these drive real TextFields through it — typing,
// backspace, shift, the number pad, the enter key's action, typing over a
// selection, accents, the per-language layouts and the globe key.
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_client/i18n.dart';
import 'package:pos_client/keyboard/keyboard_host.dart';
import 'package:pos_client/keyboard/pos_keyboard.dart';

const _screen = Size(1600, 1000);

Finder _key(String id) => find.byKey(ValueKey('osk:$id'));

Future<void> _tap(WidgetTester tester, String id) async {
  await tester.tap(_key(id));
  await tester.pump();
}

Future<void> _typeKeys(WidgetTester tester, String keys) async {
  for (final k in keys.split('')) {
    await _tap(tester, k == ' ' ? 'space' : k);
  }
}

/// A keyboard installed as the text input control, under an app whose home
/// is [home].
Future<PosKeyboardControl> _pumpApp(WidgetTester tester, Widget home) async {
  tester.view.physicalSize = _screen;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final kb = PosKeyboardControl();
  TextInput.setInputControl(kb);
  addTearDown(() {
    TextInput.restorePlatformInputControl();
    kb.dispose();
  });
  await tester.pumpWidget(
    MaterialApp(
      builder: (context, child) => KeyboardHost(control: kb, child: child!),
      home: home,
    ),
  );
  return kb;
}

/// One focused TextField with [controller].
Future<PosKeyboardControl> _pumpField(
  WidgetTester tester,
  TextEditingController controller, {
  TextInputType? keyboardType,
  TextInputAction? textInputAction,
  TextCapitalization capitalization = TextCapitalization.none,
  bool obscure = false,
  int maxLines = 1,
  ValueChanged<String>? onSubmitted,
}) async {
  final kb = await _pumpApp(
    tester,
    Scaffold(
      body: Center(
        child: SizedBox(
          width: 500,
          child: TextField(
            controller: controller,
            keyboardType: keyboardType,
            textInputAction: textInputAction,
            textCapitalization: capitalization,
            obscureText: obscure,
            maxLines: maxLines,
            onSubmitted: onSubmitted,
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.byType(TextField));
  await tester.pumpAndSettle();
  return kb;
}

void main() {
  tearDown(() => Prefs.instance.lang = 'en');

  testWidgets('slides up on focus and types into the field', (tester) async {
    final c = TextEditingController();
    final kb = await _pumpField(tester, c);
    expect(kb.visible.value, isTrue);
    expect(_key('q'), findsOneWidget);
    await _typeKeys(tester, 'hi there');
    expect(c.text, 'hi there');
    expect(c.selection, const TextSelection.collapsed(offset: 8));
  });

  testWidgets('the field stays above it: its height is the bottom inset', (
    tester,
  ) async {
    final c = TextEditingController();
    late double inset;
    await _pumpApp(
      tester,
      Builder(
        builder: (context) {
          inset = MediaQuery.viewInsetsOf(context).bottom;
          return Scaffold(
            body: Column(
              children: [
                const Spacer(),
                TextField(key: const ValueKey('bottom'), controller: c),
              ],
            ),
          );
        },
      ),
    );
    expect(inset, 0);
    await tester.tap(find.byType(TextField));
    await tester.pumpAndSettle();
    final height = KeyboardMetrics.of(_screen).height;
    expect(inset, height);
    final field = tester.getRect(find.byKey(const ValueKey('bottom')));
    expect(field.bottom, lessThanOrEqualTo(_screen.height - height));
    expect(tester.getRect(_key('q')).top, greaterThan(field.bottom));
  });

  testWidgets('backspace deletes one character, and repeats while held', (
    tester,
  ) async {
    final c = TextEditingController(text: 'abcdefghij');
    await _pumpField(tester, c);
    c.selection = const TextSelection.collapsed(offset: 10);
    await tester.pump();
    await _tap(tester, 'backspace');
    expect(c.text, 'abcdefghi');

    final hold = await tester.startGesture(tester.getCenter(_key('backspace')));
    await tester.pump(const Duration(milliseconds: 800));
    await hold.up();
    await tester.pump();
    expect(c.text.length, lessThan(8));
    expect('abcdefghi'.startsWith(c.text), isTrue);
  });

  testWidgets('shift types one capital; twice quickly is caps lock', (
    tester,
  ) async {
    final c = TextEditingController();
    final kb = await _pumpField(tester, c);
    await _tap(tester, 'shift');
    expect(kb.shift, ShiftState.once);
    expect(find.text('Q'), findsOneWidget); // the keys show capitals
    await _typeKeys(tester, 'ab');
    expect(c.text, 'Ab');
    expect(kb.shift, ShiftState.off);

    await _tap(tester, 'shift');
    await _tap(tester, 'shift');
    expect(kb.shift, ShiftState.locked);
    await _typeKeys(tester, 'cd');
    expect(c.text, 'AbCD');
    await _tap(tester, 'shift');
    await _typeKeys(tester, 'e');
    expect(c.text, 'AbCDe');
  });

  testWidgets('sentence capitalisation shifts the first letter', (
    tester,
  ) async {
    final c = TextEditingController();
    await _pumpField(tester, c, capitalization: TextCapitalization.sentences);
    await _typeKeys(tester, 'hi. ok');
    expect(c.text, 'Hi. Ok');
  });

  testWidgets('number fields get the big number pad', (tester) async {
    final c = TextEditingController();
    await _pumpField(
      tester,
      c,
      keyboardType: const TextInputType.numberWithOptions(decimal: true),
    );
    expect(_key('q'), findsNothing);
    expect(_key('7'), findsOneWidget);
    await _typeKeys(tester, '12.5');
    expect(c.text, '12.5');
    await _tap(tester, 'backspace');
    expect(c.text, '12.');
    // ABC switches to letters for an odd field
    await _tap(tester, 'abc');
    expect(_key('q'), findsOneWidget);
  });

  testWidgets('phone fields get the pad with + and -', (tester) async {
    final c = TextEditingController();
    await _pumpField(tester, c, keyboardType: TextInputType.phone);
    await _typeKeys(tester, '+1-514');
    expect(c.text, '+1-514');
  });

  testWidgets('a digits-only field keeps its formatter', (tester) async {
    final c = TextEditingController();
    final kb = await _pumpApp(
      tester,
      Scaffold(
        body: TextField(
          controller: c,
          keyboardType: TextInputType.number,
          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
        ),
      ),
    );
    await tester.tap(find.byType(TextField));
    await tester.pumpAndSettle();
    await _tap(tester, '4');
    kb.type('x'); // not a digit: the field's formatter drops it
    await tester.pump();
    await _tap(tester, '2');
    expect(c.text, '42');
  });

  testWidgets('Done submits the field and the keyboard goes down', (
    tester,
  ) async {
    final c = TextEditingController();
    String? submitted;
    final kb = await _pumpField(
      tester,
      c,
      textInputAction: TextInputAction.done,
      onSubmitted: (v) => submitted = v,
    );
    await _typeKeys(tester, 'ok');
    expect(find.text('Done'), findsOneWidget);
    await _tap(tester, 'enter');
    await tester.pumpAndSettle();
    expect(submitted, 'ok');
    expect(kb.visible.value, isFalse);
    expect(_key('q'), findsNothing);
  });

  testWidgets('Next moves to the next field', (tester) async {
    final a = TextEditingController(), b = TextEditingController();
    await _pumpApp(
      tester,
      Scaffold(
        body: Column(
          children: [
            TextField(controller: a, textInputAction: TextInputAction.next),
            TextField(controller: b),
          ],
        ),
      ),
    );
    await tester.tap(find.byType(TextField).first);
    await tester.pumpAndSettle();
    expect(find.text('Next'), findsOneWidget);
    await _typeKeys(tester, 'a');
    await _tap(tester, 'enter');
    await tester.pumpAndSettle();
    await _typeKeys(tester, 'b');
    expect(a.text, 'a');
    expect(b.text, 'b');
  });

  testWidgets('enter in a multi-line field is a new line', (tester) async {
    final c = TextEditingController();
    final kb = await _pumpField(tester, c, maxLines: 4);
    expect(kb.enterAction, TextInputAction.newline);
    await _typeKeys(tester, 'a');
    await _tap(tester, 'enter');
    await _typeKeys(tester, 'b');
    expect(c.text, 'a\nb');
    expect(kb.visible.value, isTrue);
  });

  testWidgets('typing replaces the selection; backspace deletes it', (
    tester,
  ) async {
    final c = TextEditingController(text: 'hello world');
    await _pumpField(tester, c);
    c.selection = const TextSelection(baseOffset: 0, extentOffset: 5);
    await tester.pump();
    await _typeKeys(tester, 'j');
    expect(c.text, 'j world');
    expect(c.selection, const TextSelection.collapsed(offset: 1));

    c.selection = const TextSelection(baseOffset: 2, extentOffset: 7);
    await tester.pump();
    await _tap(tester, 'backspace');
    expect(c.text, 'j ');

    c.selection = const TextSelection.collapsed(offset: 0);
    await tester.pump();
    await _typeKeys(tester, 'x');
    expect(c.text, 'xj ');
    expect(c.selection, const TextSelection.collapsed(offset: 1));
  });

  testWidgets('a PIN field types through it', (tester) async {
    final c = TextEditingController();
    await _pumpField(
      tester,
      c,
      obscure: true,
      keyboardType: TextInputType.number,
    );
    await _typeKeys(tester, '1234');
    expect(c.text, '1234');
    expect(
      tester.widget<TextField>(find.byType(TextField)).obscureText,
      isTrue,
    );
  });

  testWidgets('holding a letter offers its accents', (tester) async {
    final c = TextEditingController();
    await _pumpField(tester, c);
    final hold = await tester.startGesture(tester.getCenter(_key('e')));
    await tester.pump(const Duration(milliseconds: 500));
    expect(_key('alt:é'), findsOneWidget);
    await hold.moveTo(tester.getCenter(_key('alt:è')));
    await tester.pump();
    await hold.up();
    await tester.pump();
    expect(c.text, 'è');
    expect(_key('alt:é'), findsNothing);
  });

  testWidgets('German: QWERTZ with ü ö ä ß keys', (tester) async {
    Prefs.instance.lang = 'de';
    final c = TextEditingController();
    await _pumpField(tester, c, textInputAction: TextInputAction.next);
    final top = tester.getCenter(_key('q')).dy;
    expect(tester.getCenter(_key('z')).dy, top); // z on the top row
    expect(
      tester.getCenter(_key('z')).dx,
      greaterThan(tester.getCenter(_key('t')).dx),
    );
    expect(tester.getCenter(_key('y')).dy, greaterThan(top)); // y below
    for (final k in ['ü', 'ö', 'ä', 'ß']) {
      expect(_key(k), findsOneWidget);
    }
    await _typeKeys(tester, 'süß');
    expect(c.text, 'süß');
    expect(find.text('Weiter'), findsOneWidget); // the enter key, in German
    expect(find.text('Deutsch'), findsOneWidget); // the space bar
  });

  testWidgets('Spanish: a ñ key', (tester) async {
    Prefs.instance.lang = 'es';
    final c = TextEditingController();
    await _pumpField(tester, c);
    expect(_key('ñ'), findsOneWidget);
    await _typeKeys(tester, 'año');
    expect(c.text, 'año');
    expect(find.text('Listo'), findsOneWidget);
  });

  testWidgets('French: QWERTY with é à è ç keys', (tester) async {
    Prefs.instance.lang = 'fr';
    final c = TextEditingController();
    await _pumpField(tester, c);
    expect(
      tester.getCenter(_key('q')).dx,
      lessThan(tester.getCenter(_key('w')).dx),
    );
    for (final k in ['é', 'à', 'è', 'ç']) {
      expect(_key(k), findsOneWidget);
    }
    await _typeKeys(tester, 'ça');
    expect(c.text, 'ça');
  });

  testWidgets('the globe key cycles layouts, not the app language', (
    tester,
  ) async {
    final c = TextEditingController();
    final kb = await _pumpField(tester, c);
    expect(_key('ç'), findsNothing);
    await _tap(tester, 'globe');
    expect(_key('ç'), findsOneWidget); // French
    await _tap(tester, 'globe');
    expect(_key('ñ'), findsOneWidget); // Spanish
    await _tap(tester, 'globe');
    expect(_key('ß'), findsOneWidget); // German
    expect(Prefs.instance.lang, 'en');
    expect(find.text('Done'), findsOneWidget); // labels stay in the app's
    // a new app language wins over the pick
    expect(kb.layoutFor('fr'), 'fr');
  });

  testWidgets('symbols page types punctuation', (tester) async {
    final c = TextEditingController();
    await _pumpField(tester, c);
    await _tap(tester, '123');
    await _typeKeys(tester, r'5$');
    await _tap(tester, 'more');
    await _typeKeys(tester, '#');
    await _tap(tester, 'abc');
    await _typeKeys(tester, 'a');
    expect(c.text, r'5$#a');
  });

  testWidgets('hide takes the keyboard down, the field keeps focus', (
    tester,
  ) async {
    final c = TextEditingController();
    final kb = await _pumpField(tester, c);
    await _tap(tester, 'hide');
    await tester.pumpAndSettle();
    expect(kb.visible.value, isFalse);
    expect(_key('q'), findsNothing);
    expect(FocusManager.instance.primaryFocus?.context?.widget, isNotNull);
    await tester.tap(find.byType(TextField));
    await tester.pumpAndSettle();
    expect(kb.visible.value, isTrue);
  });

  testWidgets('it sits above dialogs and types into them', (tester) async {
    final c = TextEditingController();
    await _pumpApp(
      tester,
      Builder(
        builder: (context) => Scaffold(
          body: TextButton(
            onPressed: () => showDialog<void>(
              context: context,
              builder: (_) => AlertDialog(
                title: const Text('Room name'),
                content: TextField(controller: c, autofocus: true),
              ),
            ),
            child: const Text('open'),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(_key('q'), findsOneWidget);
    await _typeKeys(tester, 'patio');
    expect(c.text, 'patio');
    expect(find.byType(AlertDialog), findsOneWidget); // still open
    final dialog = tester.getRect(find.byType(TextField));
    expect(
      dialog.bottom,
      lessThanOrEqualTo(_screen.height - KeyboardMetrics.of(_screen).height),
    );
  });
}
