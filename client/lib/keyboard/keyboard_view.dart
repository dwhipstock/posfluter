import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../design/tokens.dart';
import '../i18n.dart';
import 'keyboard_layouts.dart';
import 'pos_keyboard.dart';

/// The keys: letters in the app language's layout, two symbol pages, and a
/// big number pad for number and phone fields. Colours come from the
/// store's theme, so it matches every brand (and Sage & Poppy's dark mode).
///
/// Keys react on touch-down (no gesture arena, no tap delay, several
/// fingers at once) and type on release; backspace types on touch-down and
/// repeats while held; a held letter opens its accents, chosen by sliding.
class KeyboardView extends StatefulWidget {
  const KeyboardView({super.key, required this.control, required this.metrics});

  final PosKeyboardControl control;
  final KeyboardMetrics metrics;

  @override
  State<KeyboardView> createState() => _KeyboardViewState();
}

/// The accent row over a held key.
class _Popup {
  _Popup(this.keyRect, this.options);
  final Rect keyRect; // in the keyboard's coordinates
  final List<String> options;
  int selected = 0;
  double left = 0;
  double cell = 0;
}

class _KeyboardViewState extends State<KeyboardView> {
  final _stackKey = GlobalKey();
  _Popup? _popup;

  PosKeyboardControl get _kb => widget.control;

  // ---- accent popup ----

  void _openPopup(BuildContext keyContext, List<String> options) {
    final stack = _stackKey.currentContext?.findRenderObject() as RenderBox?;
    final key = keyContext.findRenderObject() as RenderBox?;
    if (stack == null || key == null) return;
    final origin = key.localToGlobal(Offset.zero, ancestor: stack);
    final popup = _Popup(origin & key.size, options);
    popup.cell = math.max(key.size.width, T.minTouch);
    final width = popup.cell * options.length;
    popup.left = (popup.keyRect.left)
        .clamp(0.0, math.max(0.0, stack.size.width - width))
        .toDouble();
    setState(() => _popup = popup);
  }

  void _movePopup(Offset global) {
    final popup = _popup;
    final stack = _stackKey.currentContext?.findRenderObject() as RenderBox?;
    if (popup == null || stack == null) return;
    final x = stack.globalToLocal(global).dx - popup.left;
    final i = (x / popup.cell).floor().clamp(0, popup.options.length - 1);
    if (i != popup.selected) setState(() => popup.selected = i);
  }

  void _closePopup({required bool commit}) {
    final popup = _popup;
    if (popup == null) return;
    setState(() => _popup = null);
    if (commit) _kb.type(popup.options[popup.selected]);
  }

  // ---- build ----

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    final scheme = Theme.of(context).colorScheme;
    final m = widget.metrics;
    return Material(
      color: scheme.surfaceContainerHigh,
      child: DecoratedBox(
        decoration: BoxDecoration(
          border: Border(top: BorderSide(color: scheme.outlineVariant)),
        ),
        child: ListenableBuilder(
          listenable: _kb,
          builder: (context, _) {
            final numbers = _kb.page == KeyboardPage.numbers;
            return Stack(
              key: _stackKey,
              clipBehavior: Clip.none,
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(
                    12,
                    KeyboardMetrics.padTop,
                    12,
                    KeyboardMetrics.padBottom,
                  ),
                  child: Center(
                    child: ConstrainedBox(
                      constraints: BoxConstraints(
                        maxWidth: numbers
                            ? KeyboardMetrics.numbersMaxWidth
                            : KeyboardMetrics.maxWidth,
                      ),
                      child: _KeyStyle(
                        scheme: scheme,
                        keyHeight: m.keyHeight,
                        child: numbers ? _numberPad(l) : _textPage(l, l.lang),
                      ),
                    ),
                  ),
                ),
                if (_popup != null) _popupRow(scheme),
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _rows(List<Widget> rows) => Column(
    mainAxisSize: MainAxisSize.min,
    children: [
      for (var i = 0; i < rows.length; i++) ...[
        if (i > 0) const SizedBox(height: KeyboardMetrics.gap),
        SizedBox(height: widget.metrics.keyHeight, child: rows[i]),
      ],
    ],
  );

  /// Keys side by side; [flex] is in tenths of a letter key.
  Widget _row(List<(int, Widget)> keys) => Row(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      for (var i = 0; i < keys.length; i++) ...[
        if (i > 0) const SizedBox(width: KeyboardMetrics.gap),
        Expanded(flex: keys[i].$1, child: keys[i].$2),
      ],
    ],
  );

  Widget _textPage(L l, String appLang) {
    final layout = _kb.layoutFor(appLang);
    final page = _kb.page;
    final letters = page == KeyboardPage.letters;
    final rows = switch (page) {
      KeyboardPage.symbols => symbolRows,
      KeyboardPage.moreSymbols => moreSymbolRows,
      _ => letterRows(layout),
    };
    final upper = letters && _kb.shift != ShiftState.off;

    (int, Widget) char(String k) {
      final shown = upper ? upperOf(k) : k;
      return (
        10,
        _Key(
          id: 'osk:$k',
          label: Text(shown),
          onTap: () => letters ? _kb.letter(k) : _kb.type(k),
          alternates: alternatesFor(k, layout, upper: upper),
          onPopupOpen: _openPopup,
          onPopupMove: _movePopup,
          onPopupClose: _closePopup,
        ),
      );
    }

    // every row spans the same width: the widest row sets the unit, and the
    // stretchy key of each row (backspace, enter, the shifts, space) takes up
    // the rest, so letter keys are one size everywhere
    final third = letters ? [...rows[2], ',', '.'] : rows[2];
    final units = [
      rows[0].length * 10 + 15,
      rows[1].length * 10 + 20,
      third.length * 10 + 30,
      120,
    ].reduce(math.max);

    final shiftFlex = (units - third.length * 10) ~/ 2;
    Widget pageToggle(String side) => letters
        ? _Key(
            id: 'osk:shift$side',
            kind: _KeyKind.special,
            selected: _kb.shift != ShiftState.off,
            semantic: l.kbShift,
            label: Icon(
              _kb.shift == ShiftState.locked
                  ? LucideIcons.arrowBigUpDash
                  : LucideIcons.arrowBigUp,
            ),
            onTap: _kb.tapShift,
          )
        : _Key(
            id: page == KeyboardPage.symbols
                ? 'osk:more$side'
                : 'osk:back123$side',
            kind: _KeyKind.special,
            label: Text(
              page == KeyboardPage.symbols ? '#+=' : '123',
              style: const TextStyle(fontSize: 19),
            ),
            onTap: () => _kb.showPage(
              page == KeyboardPage.symbols
                  ? KeyboardPage.moreSymbols
                  : KeyboardPage.symbols,
            ),
          );

    final extra = _kb.isEmail ? '@' : (_kb.isUrl ? '/' : null);
    final fixed4 = 18 + 14 + 18 + (extra != null ? 10 : 0);
    return _rows([
      _row([
        for (final k in rows[0]) char(k),
        (units - rows[0].length * 10, _backspace(l)),
      ]),
      _row([
        for (final k in rows[1]) char(k),
        (units - rows[1].length * 10, _enter(l)),
      ]),
      _row([
        (shiftFlex, pageToggle('')),
        for (final k in third) char(k),
        (units - third.length * 10 - shiftFlex, pageToggle('2')),
      ]),
      _row([
        (
          18,
          _Key(
            id: letters ? 'osk:123' : 'osk:abc',
            kind: _KeyKind.special,
            semantic: letters ? l.kbSymbols : l.kbLetters,
            label: Text(
              letters ? '?123' : 'ABC',
              style: const TextStyle(fontSize: 19),
            ),
            onTap: () => _kb.showPage(
              letters ? KeyboardPage.symbols : KeyboardPage.letters,
            ),
          ),
        ),
        (14, _globe(l, layout, appLang)),
        if (extra != null) char(extra),
        (
          units - fixed4,
          _Key(
            id: 'osk:space',
            semantic: l.kbSpace,
            label: Text(
              keyboardLayoutNames[layout]!,
              style: T.small(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
            onTap: () => _kb.type(' '),
          ),
        ),
        (18, _hide(l)),
      ]),
    ]);
  }

  Widget _numberPad(L l) {
    Widget digit(String d) => _Key(
      id: 'osk:$d',
      label: Text(d, style: const TextStyle(fontSize: 28)),
      onTap: () => _kb.type(d),
    );
    Widget extra(String? k) => k == null
        ? const SizedBox.shrink()
        : _Key(
            id: 'osk:$k',
            kind: _KeyKind.special,
            label: Text(k, style: const TextStyle(fontSize: 28)),
            onTap: () => _kb.type(k),
          );
    final left = _kb.isPhone ? '+' : (_kb.allowsDecimal ? '.' : null);
    final right = _kb.isPhone || _kb.allowsSign ? '-' : null;
    return _rows([
      _row([
        for (final d in ['1', '2', '3']) (10, digit(d)),
        (10, _backspace(l)),
      ]),
      _row([
        for (final d in ['4', '5', '6']) (10, digit(d)),
        (
          10,
          _Key(
            id: 'osk:abc',
            kind: _KeyKind.special,
            semantic: l.kbLetters,
            label: const Text('ABC', style: TextStyle(fontSize: 19)),
            onTap: () => _kb.showPage(KeyboardPage.letters),
          ),
        ),
      ]),
      _row([
        for (final d in ['7', '8', '9']) (10, digit(d)),
        (10, _hide(l)),
      ]),
      _row([
        (10, extra(left)),
        (10, digit('0')),
        (10, extra(right)),
        (10, _enter(l)),
      ]),
    ]);
  }

  Widget _backspace(L l) => _Key(
    id: 'osk:backspace',
    kind: _KeyKind.special,
    repeats: true,
    semantic: l.kbBackspace,
    label: const Icon(LucideIcons.delete),
    onTap: _kb.backspace,
  );

  Widget _hide(L l) => _Key(
    id: 'osk:hide',
    kind: _KeyKind.special,
    semantic: l.kbHide,
    label: const Icon(LucideIcons.keyboardOff),
    onTap: _kb.dismiss,
  );

  Widget _globe(L l, String layout, String appLang) => _Key(
    id: 'osk:globe',
    kind: _KeyKind.special,
    semantic: l.kbLayout,
    label: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        const Icon(LucideIcons.globe, size: 22),
        const SizedBox(width: 6),
        Text(layout.toUpperCase(), style: const TextStyle(fontSize: 15)),
      ],
    ),
    onTap: () => _kb.nextLayout(appLang),
  );

  Widget _enter(L l) {
    final action = _kb.enterAction;
    final label = switch (action) {
      TextInputAction.newline => null,
      TextInputAction.next => l.kbNext,
      TextInputAction.previous => l.kbPrevious,
      TextInputAction.go => l.kbGo,
      TextInputAction.search => l.kbSearch,
      TextInputAction.send => l.kbSend,
      _ => l.kbDone,
    };
    return _Key(
      id: 'osk:enter',
      kind: _KeyKind.action,
      semantic: label ?? l.kbNewLine,
      label: label == null
          ? const Icon(LucideIcons.cornerDownLeft)
          : Text(
              label,
              maxLines: 1,
              overflow: TextOverflow.fade,
              softWrap: false,
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
            ),
      onTap: _kb.enter,
    );
  }

  Widget _popupRow(ColorScheme scheme) {
    final p = _popup!;
    final h = widget.metrics.keyHeight;
    return Positioned(
      left: p.left,
      top: p.keyRect.top - h - 10,
      height: h + 4,
      child: Material(
        elevation: 6,
        color: scheme.surface,
        shape: RoundedRectangleBorder(
          borderRadius: T.radiusLarge,
          side: BorderSide(color: scheme.outlineVariant),
        ),
        clipBehavior: Clip.antiAlias,
        child: Row(
          children: [
            for (var i = 0; i < p.options.length; i++)
              Container(
                key: ValueKey('osk:alt:${p.options[i]}'),
                width: p.cell,
                alignment: Alignment.center,
                color: i == p.selected ? scheme.primary : null,
                child: Text(
                  p.options[i],
                  style: T.text(
                    size: 26,
                    weight: FontWeight.w500,
                    color: i == p.selected
                        ? scheme.onPrimary
                        : scheme.onSurface,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// The colours and key size every key reads, from the theme.
class _KeyStyle extends InheritedWidget {
  const _KeyStyle({
    required this.scheme,
    required this.keyHeight,
    required super.child,
  });

  final ColorScheme scheme;
  final double keyHeight;

  static _KeyStyle of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<_KeyStyle>()!;

  @override
  bool updateShouldNotify(_KeyStyle old) =>
      old.scheme != scheme || old.keyHeight != keyHeight;
}

enum _KeyKind { char, special, action }

class _Key extends StatefulWidget {
  _Key({
    required this.id,
    required this.label,
    required this.onTap,
    this.kind = _KeyKind.char,
    this.repeats = false,
    this.selected = false,
    this.semantic,
    this.alternates = const [],
    this.onPopupOpen,
    this.onPopupMove,
    this.onPopupClose,
  }) : super(key: ValueKey(id));

  final String id;
  final Widget label;
  final VoidCallback onTap;
  final _KeyKind kind;

  /// Backspace: fires on touch-down, then again and again while held.
  final bool repeats;

  /// Shift while on.
  final bool selected;
  final String? semantic;
  final List<String> alternates;
  final void Function(BuildContext key, List<String> options)? onPopupOpen;
  final void Function(Offset global)? onPopupMove;
  final void Function({required bool commit})? onPopupClose;

  @override
  State<_Key> createState() => _KeyState();
}

class _KeyState extends State<_Key> {
  static const _holdForAccents = Duration(milliseconds: 380);
  static const _repeatAfter = Duration(milliseconds: 450);
  static const _repeatEvery = Duration(milliseconds: 55);

  int? _pointer;
  Timer? _timer;
  bool _popupOpen = false;

  void _down(PointerDownEvent e) {
    if (_pointer != null) return; // a second finger on the same key
    setState(() => _pointer = e.pointer);
    if (widget.repeats) {
      widget.onTap();
      _timer = Timer(_repeatAfter, () {
        _timer = Timer.periodic(_repeatEvery, (_) => widget.onTap());
      });
    } else if (widget.alternates.isNotEmpty && widget.onPopupOpen != null) {
      _timer = Timer(_holdForAccents, () {
        if (!mounted) return;
        _popupOpen = true;
        widget.onPopupOpen!(context, widget.alternates);
      });
    }
  }

  void _move(PointerMoveEvent e) {
    if (e.pointer == _pointer && _popupOpen) {
      widget.onPopupMove?.call(e.position);
    }
  }

  void _up(PointerUpEvent e) {
    if (e.pointer != _pointer) return;
    _end();
    if (_popupOpen) {
      _popupOpen = false;
      widget.onPopupClose?.call(commit: true);
    } else if (!widget.repeats) {
      widget.onTap();
    }
  }

  void _cancel(PointerCancelEvent e) {
    if (e.pointer != _pointer) return;
    _end();
    if (_popupOpen) {
      _popupOpen = false;
      widget.onPopupClose?.call(commit: false);
    }
  }

  void _end() {
    _timer?.cancel();
    _timer = null;
    setState(() => _pointer = null);
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final style = _KeyStyle.of(context);
    final s = style.scheme;
    final pressed = _pointer != null;
    // letter keys stand out from the board: lighter in both modes
    final face = s.brightness == Brightness.dark
        ? Color.alphaBlend(
            s.onSurface.withValues(alpha: .12),
            s.surfaceContainerHigh,
          )
        : s.surface;
    final (Color bg, Color fg) = switch (widget.kind) {
      _KeyKind.action => (
        pressed ? Color.alphaBlend(Colors.black26, s.primary) : s.primary,
        s.onPrimary,
      ),
      _ when widget.selected => (s.primary, s.onPrimary),
      _KeyKind.special => (
        Color.alphaBlend(
          s.onSurface.withValues(alpha: pressed ? .2 : .08),
          s.surfaceContainerHigh,
        ),
        s.onSurface,
      ),
      _KeyKind.char => (
        pressed
            ? Color.alphaBlend(s.primary.withValues(alpha: .22), face)
            : face,
        s.onSurface,
      ),
    };
    return Semantics(
      button: true,
      label: widget.semantic,
      child: Listener(
        behavior: HitTestBehavior.opaque,
        onPointerDown: _down,
        onPointerMove: _move,
        onPointerUp: _up,
        onPointerCancel: _cancel,
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: bg,
            borderRadius: T.radiusMedium,
            border: widget.kind == _KeyKind.char
                ? Border.all(color: s.outlineVariant)
                : null,
            boxShadow: pressed || widget.kind != _KeyKind.char
                ? null
                : [
                    BoxShadow(
                      color: s.shadow.withValues(alpha: .12),
                      offset: const Offset(0, 1.5),
                      blurRadius: 1,
                    ),
                  ],
          ),
          child: Center(
            child: IconTheme.merge(
              data: IconThemeData(color: fg, size: 26),
              child: DefaultTextStyle.merge(
                style: T.text(size: 24, weight: FontWeight.w500, color: fg),
                child: widget.label,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
