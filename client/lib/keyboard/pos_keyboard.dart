import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import 'keyboard_host.dart';
import 'keyboard_layouts.dart';

/// The app's own on-screen keyboard, for the Windows counter tablet (a
/// Surface used touch-only: the Windows touch keyboard is unreliable with
/// Flutter and does not match the app).
///
/// It plugs in as Flutter's virtual keyboard ([TextInput.setInputControl]),
/// so every TextField in the app types through it with no per-screen code;
/// [KeyboardHost] (the MaterialApp builder) docks it at the bottom and
/// reports its height as the bottom view inset, the way a system keyboard
/// does, so Scaffolds, dialogs and sheets move out of its way.
///
/// On Windows only. Android keeps its system keyboard. Opt out with
/// `--dart-define=POS_SYSTEM_KEYBOARD=1` or the environment variable
/// `POS_SYSTEM_KEYBOARD=1`. A debug build on another desktop can try it with
/// `--dart-define=POS_ONSCREEN_KEYBOARD=1`.
class PosKeyboard {
  PosKeyboard._();

  static const _channel = MethodChannel('dev.dwhipstock.pos_client/keyboard');

  static final bool enabled = _decide();

  static bool _decide() {
    if (kIsWeb) return false;
    if (const String.fromEnvironment('POS_SYSTEM_KEYBOARD') == '1' ||
        Platform.environment['POS_SYSTEM_KEYBOARD'] == '1') {
      return false;
    }
    if (Platform.isWindows) return true;
    return kDebugMode &&
        const String.fromEnvironment('POS_ONSCREEN_KEYBOARD') == '1';
  }

  /// The one keyboard the app uses.
  static final control = PosKeyboardControl();

  /// Called once from main(): route text input through [control], and ask
  /// the Windows runner to keep the system touch keyboard from popping up on
  /// top of it.
  static Future<void> install() async {
    if (!enabled) return;
    TextInput.setInputControl(control);
    if (!Platform.isWindows) return;
    try {
      await _channel.invokeMethod<void>('suppressSystemKeyboard', true);
    } catch (e) {
      debugPrint('[keyboard] system keyboard suppression unavailable: $e');
      return;
    }
    // and if Windows put its keyboard up anyway, take it down with ours
    control.visible.addListener(() {
      if (!control.visible.value) return;
      _channel.invokeMethod<void>('hideSystemKeyboard').catchError((_) {});
    });
  }

  /// MaterialApp.builder: the app with the keyboard docked under it.
  static Widget wrap(BuildContext context, Widget? child) {
    final app = child ?? const SizedBox.shrink();
    if (!enabled) return app;
    return KeyboardHost(control: control, child: app);
  }
}

/// Which keys are showing.
enum KeyboardPage { letters, symbols, moreSymbols, numbers }

/// Shift: off, for the next letter only, or caps lock.
enum ShiftState { off, once, locked }

/// The keyboard's state and its edits to the focused field.
///
/// Flutter calls the [TextInputControl] half (attach, show, hide, the
/// field's value); the keys call the editing half ([type], [backspace],
/// [enter]), which writes through [TextInput.updateEditingValue], so the
/// field's formatters, length limits and onChanged all run as usual.
class PosKeyboardControl extends ChangeNotifier with TextInputControl {
  /// Whether the keyboard is up. The host slides it and sets the inset.
  final ValueNotifier<bool> visible = ValueNotifier(false);

  TextInputClient? _client;
  TextInputConfiguration _config = const TextInputConfiguration();
  TextEditingValue _value = TextEditingValue.empty;

  KeyboardPage _page = KeyboardPage.letters;
  ShiftState _shift = ShiftState.off;
  DateTime _lastShiftTap = DateTime(0);

  // globe key: a layout picked by hand, kept while the app language is the
  // one it was picked under (changing the app language resets it)
  String? _layoutPick;
  String? _layoutPickBase;

  KeyboardPage get page => _page;
  ShiftState get shift => _shift;
  TextInputConfiguration get configuration => _config;

  /// The field has asked for digits (number, phone or date input).
  bool get wantsNumbers {
    final i = _config.inputType.index;
    return i == TextInputType.number.index ||
        i == TextInputType.phone.index ||
        i == TextInputType.datetime.index;
  }

  bool get isPhone => _config.inputType.index == TextInputType.phone.index;
  bool get isMultiline =>
      _config.inputType.index == TextInputType.multiline.index;
  bool get isEmail =>
      _config.inputType.index == TextInputType.emailAddress.index;
  bool get isUrl => _config.inputType.index == TextInputType.url.index;
  bool get allowsDecimal => _config.inputType.decimal ?? false;
  bool get allowsSign => _config.inputType.signed ?? false;

  /// What the enter key does: a new line in a multi-line field, else the
  /// field's action (Done unless it asked for Next, Search, Send…).
  TextInputAction get enterAction {
    final a = _config.inputAction;
    if (a == TextInputAction.none || a == TextInputAction.unspecified) {
      return isMultiline ? TextInputAction.newline : TextInputAction.done;
    }
    return a;
  }

  /// The letter layout for [appLang]: the app's language unless the globe key
  /// picked another.
  String layoutFor(String appLang) {
    if (_layoutPick != null && _layoutPickBase == appLang) return _layoutPick!;
    return keyboardLayouts.contains(appLang) ? appLang : 'en';
  }

  /// Globe key: the next layout, without changing the app's language.
  void nextLayout(String appLang) {
    final i = keyboardLayouts.indexOf(layoutFor(appLang));
    _layoutPick = keyboardLayouts[(i + 1) % keyboardLayouts.length];
    _layoutPickBase = appLang;
    notifyListeners();
  }

  // ---- TextInputControl ------------------------------------------------

  @override
  void attach(TextInputClient client, TextInputConfiguration configuration) {
    _client = client;
    _config = configuration;
    _value = client.currentTextEditingValue ?? TextEditingValue.empty;
    _page = wantsNumbers ? KeyboardPage.numbers : KeyboardPage.letters;
    _shift = _autoShift(_value);
    notifyListeners();
  }

  @override
  void detach(TextInputClient client) {
    if (_client == client) _client = null;
  }

  @override
  void updateConfig(TextInputConfiguration configuration) {
    final numbersBefore = wantsNumbers;
    _config = configuration;
    if (wantsNumbers != numbersBefore) {
      _page = wantsNumbers ? KeyboardPage.numbers : KeyboardPage.letters;
    }
    notifyListeners();
  }

  @override
  void setEditingState(TextEditingValue value) {
    _value = value;
    if (_shift != ShiftState.locked) {
      final next = _autoShift(value);
      if (next != _shift) {
        _shift = next;
        notifyListeners();
      }
    }
  }

  @override
  void show() {
    if (_client != null) visible.value = true;
  }

  @override
  void hide() => visible.value = false;

  // ---- keys ------------------------------------------------------------

  TextEditingValue get _current => _client?.currentTextEditingValue ?? _value;

  void _apply(TextEditingValue next) {
    if (_client == null) return;
    _value = next;
    TextInput.updateEditingValue(next);
    _value = _current; // what the field kept, after its formatters
  }

  /// Types [text] over the selection (at the caret when nothing is selected).
  void type(String text) {
    final v = _current;
    final sel = v.selection;
    final start = sel.isValid ? sel.start : v.text.length;
    final end = sel.isValid ? sel.end : v.text.length;
    _apply(
      TextEditingValue(
        text: v.text.replaceRange(start, end, text),
        selection: TextSelection.collapsed(offset: start + text.length),
      ),
    );
    if (_shift == ShiftState.once) _shift = ShiftState.off;
    if (_shift != ShiftState.locked) _shift = _autoShift(_value);
    notifyListeners();
  }

  /// A letter key: upper-cased while shift is on.
  void letter(String key) =>
      type(_shift == ShiftState.off ? key : upperOf(key));

  /// Deletes the selection, or the character before the caret.
  void backspace() {
    final v = _current;
    final sel = v.selection;
    final start = sel.isValid ? sel.start : v.text.length;
    final end = sel.isValid ? sel.end : v.text.length;
    if (start == end && start == 0) return;
    final String before;
    if (start != end) {
      before = v.text.substring(0, start);
    } else {
      // one whole character, even an emoji or a combined accent
      before = v.text.substring(0, start).characters.skipLast(1).toString();
    }
    _apply(
      TextEditingValue(
        text: before + v.text.substring(end),
        selection: TextSelection.collapsed(offset: before.length),
      ),
    );
    if (_shift != ShiftState.locked) _shift = _autoShift(_value);
    notifyListeners();
  }

  /// Enter: a new line, or the field's action (which may move focus on or
  /// close the keyboard).
  void enter() {
    final action = enterAction;
    if (action == TextInputAction.newline && isMultiline) {
      type('\n');
    } else {
      _client?.performAction(action);
    }
  }

  /// Shift: once, then off; tapped twice quickly, caps lock.
  void tapShift() {
    final now = DateTime.now();
    final quick =
        now.difference(_lastShiftTap) < const Duration(milliseconds: 350);
    _lastShiftTap = now;
    _shift = switch (_shift) {
      ShiftState.off => ShiftState.once,
      ShiftState.once => quick ? ShiftState.locked : ShiftState.off,
      ShiftState.locked => ShiftState.off,
    };
    notifyListeners();
  }

  void showPage(KeyboardPage page) {
    _page = page;
    notifyListeners();
  }

  /// The hide key: the keyboard goes down, the field keeps focus (a tap on
  /// the field brings it back).
  void dismiss() => hide();

  /// Capitalisation the field asked for: the first letter of a sentence or
  /// word starts shifted. Never in a password or PIN.
  ShiftState _autoShift(TextEditingValue v) {
    if (_config.obscureText) return ShiftState.off;
    final sel = v.selection;
    final caret = sel.isValid ? sel.start : v.text.length;
    final before = v.text.substring(0, caret.clamp(0, v.text.length));
    final on = switch (_config.textCapitalization) {
      TextCapitalization.characters => true,
      TextCapitalization.words =>
        before.isEmpty || before.endsWith(' ') || before.endsWith('\n'),
      TextCapitalization.sentences =>
        before.trim().isEmpty ||
            RegExp(r'[.!?]\s+$').hasMatch(before) ||
            before.endsWith('\n'),
      TextCapitalization.none => false,
    };
    return on ? ShiftState.once : ShiftState.off;
  }

  @override
  void dispose() {
    visible.dispose();
    super.dispose();
  }
}

/// The keyboard's size for a screen: big keys on the tablet, never more than
/// about two fifths of a short window.
class KeyboardMetrics {
  const KeyboardMetrics._(this.keyHeight);

  factory KeyboardMetrics.of(Size screen) =>
      KeyboardMetrics._((screen.height * 0.075).clamp(46.0, 72.0));

  final double keyHeight;
  static const gap = 8.0;
  static const padTop = 10.0;
  static const padBottom = 12.0;
  static const maxWidth = 1180.0;
  static const numbersMaxWidth = 620.0;

  /// Four rows of keys, gaps and padding: the bottom inset while it is up.
  double get height => padTop + keyHeight * 4 + gap * 3 + padBottom;
}
