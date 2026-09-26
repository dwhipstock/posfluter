import 'package:flutter/material.dart';

import 'skin.dart';

/// Design tokens — the single source of visual truth for The Copper Lantern
/// Pub terminal: the logo's deep navy as the primary, warm cream surfaces, and
/// a copper accent kept for the few things that must pop (Pay, the selected
/// category, occupied tables).
///
/// Rules encoded here and enforced by the widgets in this folder:
/// - mostly flat: 1px borders, one soft shadow ([T.raised]) for touch tiles
/// - radii 6/8/12 only
/// - all animation ≤150ms
/// - touch targets ≥56pt (POS is finger-first)
/// - every text/background pair used below meets WCAG AA (4.5:1)
abstract final class T {
  // brand
  static const navy = Color(0xFF17456E); // logo badge; white on it ≈ 9.9:1
  static const navyDeep = Color(0xFF0F3150); // header bands, pressed states
  static const copper = Color(0xFFA65A23); // white on it ≈ 5.1:1

  // roles
  static const primary = navy; // default buttons, focus, selection
  static const onPrimary = Colors.white;
  static const accent = copper; // Pay, selected category, occupied table
  static const onAccent = Colors.white;
  static const accentSoft = Color(0xFFF3E3D3); // copper tint for selected rows

  // surfaces: warm cream, never cool grey
  static const background = Color(0xFFF6F0E5);
  static const surface = Color(0xFFFFFCF7);
  static const surfaceAlt = Color(0xFFEFE6D6); // selected/elevated cards
  static const border = Color(0xFFDCCFB9); // 1px separators
  static const textPrimary = Color(0xFF1C2733); // ≈ 13:1 on background
  static const textMuted = Color(0xFF62574B); // ≈ 6.2:1 on background
  static const onNavyMuted = Color(0xFFC9D6E3); // secondary text on navy

  static const destructive = Color(0xFFB3261E); // void, delete, force-close
  static const onDestructive = Colors.white;

  /// Dark amber for warning TEXT and icons on cream (≈ 5.6:1).
  static const attention = Color(0xFF8C5200);

  /// Guest orders waiting to be verified: a gold fill that reads apart from
  /// the copper of an ordinary occupied table, with dark text on it.
  static const pending = Color(0xFFF2B53A);
  static const onPending = Color(0xFF2B1D0A); // ≈ 8.9:1 on [pending]

  /// No-photo menu tiles: muted pub-sign hues, each ≥ 4.5:1 as text on its
  /// own light tint, so the two letters stay readable.
  static const tilePalette = [
    Color(0xFF17456E), // navy
    Color(0xFF8A4A1C), // copper
    Color(0xFF2F5D3A), // bottle green
    Color(0xFF7A2E3A), // claret
    Color(0xFF285E61), // teal
    Color(0xFF5B5220), // olive
    Color(0xFF5A3D6B), // plum
    Color(0xFF4A5563), // slate
  ];

  // the one deliberately light surface in the app — physical receipt paper
  static const receiptPaper = Color(0xFFFFFEFB);
  static const receiptInk = Colors.black87;

  // radii
  static const rSmall = Radius.circular(6);
  static const rMedium = Radius.circular(8);
  static const rLarge = Radius.circular(12);
  static final radiusSmall = BorderRadius.circular(6);
  static final radiusMedium = BorderRadius.circular(8);
  static final radiusLarge = BorderRadius.circular(12);

  /// The single elevation step: tiles that are meant to be tapped.
  static const raised = [
    BoxShadow(color: Color(0x1A17456E), blurRadius: 10, offset: Offset(0, 3)),
  ];

  /// Deeper drop shadow for the brand badge on the navy band.
  static const badgeShadow = [
    BoxShadow(color: Color(0x40000000), blurRadius: 24, offset: Offset(0, 8)),
  ];

  // motion — fast, never floaty
  static const dFast = Duration(milliseconds: 100);
  static const dNormal = Duration(milliseconds: 150);
  static const ease = Cubic(0.2, 0, 0, 1);

  // touch
  static const minTouch = 56.0;

  // type scale (pt sizes per the spec)
  static const bodySize = 18.0;
  static const smallSize = 14.0;
  static const priceSize = 20.0;
  static const priceBigSize = 32.0; // bill-panel total
  static const headlineSize = 24.0;

  /// Bundled families (pubspec `fonts:`) — nothing is fetched at runtime.
  static const fontFamily = 'Inter';
  static const _fallback = ['NotoSans'];

  /// The family [text] draws with: Inter, unless the store's brand skin
  /// brings its own (set by that brand's theme builder; the pubs' theme puts
  /// Inter back, so their screens are unchanged).
  static String family = fontFamily;

  /// Body text: Inter with Noto Sans fallback for any glyph Inter lacks.
  static TextStyle text({
    double size = bodySize,
    FontWeight weight = FontWeight.w400,
    Color color = textPrimary,
  }) => TextStyle(
    fontFamily: family,
    fontFamilyFallback: _fallback,
    fontSize: size,
    fontWeight: weight,
    color: color,
  );

  static TextStyle small({
    Color color = textMuted,
    FontWeight weight = FontWeight.w400,
  }) => text(size: smallSize, color: color, weight: weight);

  static TextStyle headline({Color color = textPrimary}) =>
      text(size: headlineSize, weight: FontWeight.w600, color: color);

  /// Prices: tabular figures so columns of numbers align.
  static TextStyle price({
    double size = priceSize,
    FontWeight weight = FontWeight.w500,
    Color color = textPrimary,
  }) => text(
    size: size,
    weight: weight,
    color: color,
  ).copyWith(fontFeatures: const [FontFeature.tabularFigures()]);

  /// Receipt/bill paper: Noto Sans with tabular figures.
  static TextStyle receipt() => const TextStyle(
    fontFamily: 'NotoSans',
    fontSize: 14,
    color: receiptInk,
    fontFeatures: [FontFeature.tabularFigures()],
  );
}

/// App-wide theme assembled from the tokens: navy primary, cream surfaces,
/// 1px borders instead of heavy shadows, 8px default radius, 56pt touch minimums.
ThemeData buildPosTheme() {
  T.family = T.fontFamily;
  final textTheme = TextTheme(
    bodyLarge: T.text(),
    bodyMedium: T.text(),
    bodySmall: T.small(),
    titleLarge: T.headline(),
    titleMedium: T.text(weight: FontWeight.w600),
    titleSmall: T.small(weight: FontWeight.w600),
    labelLarge: T.text(weight: FontWeight.w500),
    labelMedium: T.small(weight: FontWeight.w500),
    headlineMedium: T.headline(),
    headlineSmall: T.headline(),
  );

  const scheme = ColorScheme.light(
    primary: T.primary,
    onPrimary: T.onPrimary,
    primaryContainer: T.surfaceAlt,
    onPrimaryContainer: T.navyDeep,
    secondary: T.accent,
    onSecondary: T.onAccent,
    secondaryContainer: T.accentSoft,
    onSecondaryContainer: T.textPrimary,
    tertiary: T.pending,
    onTertiary: T.onPending,
    surface: T.surface,
    onSurface: T.textPrimary,
    onSurfaceVariant: T.textMuted,
    surfaceContainerLowest: T.surface,
    surfaceContainerLow: T.surface,
    surfaceContainer: T.background,
    surfaceContainerHigh: T.surfaceAlt,
    surfaceContainerHighest: T.surfaceAlt,
    surfaceTint: Colors.transparent,
    error: T.destructive,
    onError: T.onDestructive,
    outline: T.border,
    outlineVariant: T.border,
  );

  final buttonShape = RoundedRectangleBorder(borderRadius: T.radiusMedium);
  final panelShape = RoundedRectangleBorder(
    borderRadius: T.radiusLarge,
    side: const BorderSide(color: T.border),
  );

  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    // the pubs' skin: the same tokens as above (a no-op for their screens)
    extensions: [BrandSkin.copper],
    fontFamily: T.fontFamily,
    fontFamilyFallback: const ['NotoSans'],
    scaffoldBackgroundColor: T.background,
    canvasColor: T.background,
    textTheme: textTheme,
    splashFactory: InkSparkle.splashFactory,
    iconTheme: const IconThemeData(color: T.textMuted),
    // warm app bar: 1px bottom border, no shadow, navy title
    appBarTheme: AppBarTheme(
      backgroundColor: T.surface,
      foregroundColor: T.navy,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleTextStyle: T.headline(color: T.navy),
      shape: const Border(bottom: BorderSide(color: T.border)),
    ),
    dividerTheme: const DividerThemeData(
      color: T.border,
      thickness: 1,
      space: 1,
    ),
    cardTheme: CardThemeData(
      color: T.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      shape: panelShape,
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: T.primary,
        foregroundColor: T.onPrimary,
        minimumSize: const Size(T.minTouch, T.minTouch),
        shape: buttonShape,
        textStyle: T.text(weight: FontWeight.w600),
        animationDuration: T.dNormal,
      ),
    ),
    elevatedButtonTheme: ElevatedButtonThemeData(
      style: ElevatedButton.styleFrom(
        backgroundColor: T.surface,
        foregroundColor: T.navy,
        minimumSize: const Size(T.minTouch, T.minTouch),
        shape: buttonShape,
        elevation: 0,
        textStyle: T.text(weight: FontWeight.w600),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: T.textPrimary,
        backgroundColor: T.surface,
        side: const BorderSide(color: T.border),
        minimumSize: const Size(T.minTouch, T.minTouch),
        shape: buttonShape,
        textStyle: T.text(weight: FontWeight.w500),
        animationDuration: T.dNormal,
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: T.navy,
        minimumSize: const Size(T.minTouch, T.minTouch),
        shape: buttonShape,
        textStyle: T.text(weight: FontWeight.w500),
        animationDuration: T.dNormal,
      ),
    ),
    iconButtonTheme: IconButtonThemeData(
      style: IconButton.styleFrom(
        minimumSize: const Size(T.minTouch, T.minTouch),
      ),
    ),
    floatingActionButtonTheme: FloatingActionButtonThemeData(
      backgroundColor: T.primary,
      foregroundColor: T.onPrimary,
      elevation: 1,
      shape: buttonShape,
      extendedTextStyle: T.text(weight: FontWeight.w600),
    ),
    segmentedButtonTheme: SegmentedButtonThemeData(
      style: ButtonStyle(
        shape: WidgetStatePropertyAll(buttonShape),
        side: const WidgetStatePropertyAll(BorderSide(color: T.border)),
        backgroundColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected) ? T.navy : T.surface,
        ),
        foregroundColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected) ? T.onPrimary : T.textPrimary,
        ),
        iconColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected) ? T.onPrimary : T.textMuted,
        ),
        textStyle: WidgetStatePropertyAll(T.small(weight: FontWeight.w600)),
      ),
    ),
    chipTheme: ChipThemeData(
      backgroundColor: T.surface,
      selectedColor: T.navy,
      secondarySelectedColor: T.navy,
      checkmarkColor: T.onPrimary,
      side: const BorderSide(color: T.border),
      shape: buttonShape,
      labelStyle: T.small(color: T.textPrimary, weight: FontWeight.w600),
      secondaryLabelStyle: T.small(color: T.onPrimary, weight: FontWeight.w600),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
    ),
    checkboxTheme: CheckboxThemeData(
      fillColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected) ? T.navy : Colors.transparent,
      ),
      checkColor: const WidgetStatePropertyAll(T.onPrimary),
      side: const BorderSide(color: T.textMuted, width: 1.5),
    ),
    radioTheme: RadioThemeData(
      fillColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected) ? T.navy : T.textMuted,
      ),
    ),
    sliderTheme: const SliderThemeData(
      activeTrackColor: T.navy,
      thumbColor: T.navy,
      inactiveTrackColor: T.border,
    ),
    expansionTileTheme: const ExpansionTileThemeData(
      iconColor: T.navy,
      collapsedIconColor: T.textMuted,
      textColor: T.textPrimary,
      collapsedTextColor: T.textPrimary,
    ),
    bottomSheetTheme: const BottomSheetThemeData(
      backgroundColor: T.surface,
      surfaceTintColor: Colors.transparent,
      showDragHandle: false,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: T.rLarge),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: T.surface,
      labelStyle: T.small(),
      floatingLabelStyle: T.small(color: T.navy, weight: FontWeight.w600),
      hintStyle: T.small(),
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 16),
      enabledBorder: OutlineInputBorder(
        borderRadius: T.radiusMedium,
        borderSide: const BorderSide(color: T.border),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: T.radiusMedium,
        borderSide: const BorderSide(color: T.navy, width: 1.5),
      ),
      errorBorder: OutlineInputBorder(
        borderRadius: T.radiusMedium,
        borderSide: const BorderSide(color: T.destructive),
      ),
      focusedErrorBorder: OutlineInputBorder(
        borderRadius: T.radiusMedium,
        borderSide: const BorderSide(color: T.destructive),
      ),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: T.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 2,
      shadowColor: const Color(0x3317456E),
      shape: panelShape,
      titleTextStyle: T.headline(color: T.navy),
      contentTextStyle: T.text(),
    ),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: T.navyDeep,
      contentTextStyle: T.text(color: Colors.white),
      actionTextColor: T.pending,
      shape: RoundedRectangleBorder(borderRadius: T.radiusMedium),
      behavior: SnackBarBehavior.floating,
      elevation: 0,
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected) ? T.onPrimary : T.textMuted,
      ),
      trackColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected) ? T.navy : T.surfaceAlt,
      ),
      trackOutlineColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected) ? T.navy : T.border,
      ),
    ),
    listTileTheme: ListTileThemeData(
      textColor: T.textPrimary,
      iconColor: T.textMuted,
      selectedColor: T.navy,
      selectedTileColor: T.surfaceAlt,
      titleTextStyle: T.text(),
      subtitleTextStyle: T.small(),
    ),
    tooltipTheme: TooltipThemeData(
      decoration: BoxDecoration(color: T.navyDeep, borderRadius: T.radiusSmall),
      textStyle: T.small(color: Colors.white, weight: FontWeight.w500),
      waitDuration: const Duration(milliseconds: 300),
    ),
    progressIndicatorTheme: const ProgressIndicatorThemeData(color: T.navy),
    popupMenuTheme: PopupMenuThemeData(
      color: T.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 3,
      shadowColor: const Color(0x3317456E),
      shape: RoundedRectangleBorder(
        borderRadius: T.radiusMedium,
        side: const BorderSide(color: T.border),
      ),
      textStyle: T.text(),
    ),
  );
}
