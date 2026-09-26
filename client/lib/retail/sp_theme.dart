import 'package:flutter/material.dart';

import '../design/tokens.dart';

/// Sage & Poppy Bottle Shop — its own look, not the pub's: sage green with a
/// California-poppy orange accent on warm off-white, and a dark mode. No blue.
/// Every text/background pair used by the retail screens meets WCAG AA
/// (4.5:1); the poppy fill carries white bold text at ≥18pt (large-text AA).
@immutable
class SpColors extends ThemeExtension<SpColors> {
  final Color sage; // brand primary (fills, focus, selection)
  final Color sageDeep; // header band, pressed
  final Color onSage;
  final Color poppy; // the Pay button, the accent
  final Color onPoppy;
  final Color poppySoft; // tinted rows, chips
  final Color background, surface, surfaceAlt, border;
  final Color text, textMuted;
  final Color ok, okSoft, warn, warnSoft, bad, badSoft;
  final Color receiptPaper;

  const SpColors({
    required this.sage,
    required this.sageDeep,
    required this.onSage,
    required this.poppy,
    required this.onPoppy,
    required this.poppySoft,
    required this.background,
    required this.surface,
    required this.surfaceAlt,
    required this.border,
    required this.text,
    required this.textMuted,
    required this.ok,
    required this.okSoft,
    required this.warn,
    required this.warnSoft,
    required this.bad,
    required this.badSoft,
    required this.receiptPaper,
  });

  static const light = SpColors(
    sage: Color(0xFF52724F), // white on it ≈ 5.3:1 (tuned from #5E7F5A)
    sageDeep: Color(0xFF3C5639),
    onSage: Colors.white,
    poppy: Color(0xFFBF5317), // white on it ≈ 4.7:1 (tuned from #E8702A)
    onPoppy: Colors.white,
    poppySoft: Color(0xFFFBE3D2),
    background: Color(0xFFF8F4EB),
    surface: Color(0xFFFFFDF8),
    surfaceAlt: Color(0xFFEFE9DC),
    border: Color(0xFFDDD5C4),
    text: Color(0xFF1F261D), // ≈ 14:1 on background
    textMuted: Color(0xFF5B6356), // ≈ 5.9:1 on background
    ok: Color(0xFF2E6B35),
    okSoft: Color(0xFFDCEBD9),
    warn: Color(0xFF8A4B00),
    warnSoft: Color(0xFFFBEBD2),
    bad: Color(0xFFB0261C),
    badSoft: Color(0xFFF8DAD6),
    receiptPaper: Color(0xFFFFFEFB),
  );

  static const dark = SpColors(
    sage: Color(0xFF8FB388), // dark text on it ≈ 8:1
    sageDeep: Color(0xFF1B241A),
    onSage: Color(0xFF11180F),
    poppy: Color(0xFFF08A40), // dark text on it ≈ 7.6:1
    onPoppy: Color(0xFF1E1005),
    poppySoft: Color(0xFF3A2416),
    background: Color(0xFF131812),
    surface: Color(0xFF1B211A),
    surfaceAlt: Color(0xFF252D23),
    border: Color(0xFF34402F),
    text: Color(0xFFEDF1E9),
    textMuted: Color(0xFFAAB5A4),
    ok: Color(0xFF8FD199),
    okSoft: Color(0xFF1E3322),
    warn: Color(0xFFF2B86B),
    warnSoft: Color(0xFF3A2C16),
    bad: Color(0xFFFF9A8E),
    badSoft: Color(0xFF3D1E1A),
    receiptPaper: Color(0xFFFFFEFB), // paper stays paper
  );

  static SpColors of(BuildContext context) =>
      Theme.of(context).extension<SpColors>() ?? light;

  @override
  SpColors copyWith() => this;

  @override
  SpColors lerp(ThemeExtension<SpColors>? other, double t) =>
      other is SpColors && t >= .5 ? other : this;
}

/// The Material theme the terminal wears when the store is Sage & Poppy.
ThemeData buildSagePoppyTheme(Brightness brightness) {
  final c = brightness == Brightness.dark ? SpColors.dark : SpColors.light;
  TextStyle text({
    double size = T.bodySize,
    FontWeight weight = FontWeight.w400,
    Color? color,
  }) => T.text(size: size, weight: weight, color: color ?? c.text);
  final scheme = ColorScheme(
    brightness: brightness,
    primary: c.sage,
    onPrimary: c.onSage,
    primaryContainer: c.surfaceAlt,
    onPrimaryContainer: c.text,
    secondary: c.poppy,
    onSecondary: c.onPoppy,
    secondaryContainer: c.poppySoft,
    onSecondaryContainer: c.text,
    tertiary: c.poppy,
    onTertiary: c.onPoppy,
    surface: c.surface,
    onSurface: c.text,
    onSurfaceVariant: c.textMuted,
    surfaceContainerLowest: c.surface,
    surfaceContainerLow: c.surface,
    surfaceContainer: c.background,
    surfaceContainerHigh: c.surfaceAlt,
    surfaceContainerHighest: c.surfaceAlt,
    surfaceTint: Colors.transparent,
    error: c.bad,
    onError: brightness == Brightness.dark ? Colors.black : Colors.white,
    outline: c.border,
    outlineVariant: c.border,
  );
  final buttonShape = RoundedRectangleBorder(borderRadius: T.radiusMedium);
  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    colorScheme: scheme,
    extensions: [c],
    fontFamily: T.fontFamily,
    fontFamilyFallback: const ['NotoSans'],
    scaffoldBackgroundColor: c.background,
    canvasColor: c.background,
    textTheme: TextTheme(
      bodyLarge: text(),
      bodyMedium: text(),
      bodySmall: text(size: T.smallSize, color: c.textMuted),
      titleLarge: text(size: T.headlineSize, weight: FontWeight.w600),
      titleMedium: text(weight: FontWeight.w600),
      titleSmall: text(size: T.smallSize, weight: FontWeight.w600),
      labelLarge: text(weight: FontWeight.w500),
      labelMedium: text(size: T.smallSize, weight: FontWeight.w500),
    ),
    iconTheme: IconThemeData(color: c.textMuted),
    appBarTheme: AppBarTheme(
      backgroundColor: c.surface,
      foregroundColor: c.sage,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      titleTextStyle: text(
        size: T.headlineSize,
        weight: FontWeight.w600,
        color: c.sage,
      ),
      shape: Border(bottom: BorderSide(color: c.border)),
    ),
    dividerTheme: DividerThemeData(color: c.border, thickness: 1, space: 1),
    cardTheme: CardThemeData(
      color: c.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: T.radiusLarge,
        side: BorderSide(color: c.border),
      ),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: c.surface,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(borderRadius: T.radiusLarge),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: c.sage,
        foregroundColor: c.onSage,
        minimumSize: const Size(T.minTouch, T.minTouch),
        shape: buttonShape,
        textStyle: text(weight: FontWeight.w600),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: c.text,
        backgroundColor: c.surface,
        side: BorderSide(color: c.border),
        minimumSize: const Size(T.minTouch, T.minTouch),
        shape: buttonShape,
        textStyle: text(weight: FontWeight.w500),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: c.sage,
        minimumSize: const Size(T.minTouch, T.minTouch),
        shape: buttonShape,
        textStyle: text(weight: FontWeight.w500),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: c.surface,
      border: OutlineInputBorder(
        borderRadius: T.radiusMedium,
        borderSide: BorderSide(color: c.border),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: T.radiusMedium,
        borderSide: BorderSide(color: c.border),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: T.radiusMedium,
        borderSide: BorderSide(color: c.sage, width: 2),
      ),
      labelStyle: text(size: T.smallSize, color: c.textMuted),
      hintStyle: text(color: c.textMuted),
    ),
    chipTheme: ChipThemeData(
      backgroundColor: c.surface,
      selectedColor: c.sage,
      labelStyle: text(size: 16, weight: FontWeight.w500),
      secondaryLabelStyle: text(
        size: 16,
        weight: FontWeight.w600,
        color: c.onSage,
      ),
      side: BorderSide(color: c.border),
      shape: const StadiumBorder(),
      showCheckmark: false,
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: c.sageDeep,
      contentTextStyle: T.text(size: 16, color: Colors.white),
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected) ? c.onSage : c.textMuted,
      ),
      trackColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected) ? c.sage : c.surfaceAlt,
      ),
    ),
  );
}
