import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

/// Design tokens — the single source of visual truth. Dark, flat, bordered:
/// what a real POS terminal looks like (reference: Toast), not a Material demo.
///
/// Rules encoded here and enforced by the widgets in this folder:
/// - flat surfaces + 1px borders, NO Material shadows
/// - radii 4/6/8 only, nothing rounder
/// - all animation ≤150ms
/// - touch targets ≥56pt (POS is finger-first)
abstract final class T {
  // colors
  static const background = Color(0xFF0A0A0A);
  static const surface = Color(0xFF141414);
  static const surfaceAlt = Color(0xFF1F1F1F); // elevated cards
  static const border = Color(0xFF2A2A2A); // 1px separators
  static const textPrimary = Color(0xFFE8ECEF);
  static const textMuted = Color(0xFF8A8A8A);
  static const accent = Color(
    0xFFFF1F8E,
  ); // confirm, pay, primary CTA — matches the customer web front's --accent
  static const onAccent =
      background; // text/icons on the pink accent (~5.8:1 vs ~3.6:1 for white — WCAG AA)
  static const destructive = Color(0xFFEF4444); // void, delete, force-close
  static const onDestructive = Colors.white;
  static const attention = Color(0xFFF59E0B); // pending badges, warnings

  // the one deliberately light surface in the app — physical receipt paper
  static const receiptPaper = Color(0xFFFAF8F5);
  static const receiptInk = Colors.black87;

  // radii — no 16–20px anywhere
  static const rSmall = Radius.circular(4);
  static const rMedium = Radius.circular(6);
  static const rLarge = Radius.circular(8);
  static final radiusSmall = BorderRadius.circular(4);
  static final radiusMedium = BorderRadius.circular(6);
  static final radiusLarge = BorderRadius.circular(8);

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

  /// Body text: Inter with Noto Sans fallback for French glyphs.
  static TextStyle text({
    double size = bodySize,
    FontWeight weight = FontWeight.w400,
    Color color = textPrimary,
  }) => GoogleFonts.inter(
    fontSize: size,
    fontWeight: weight,
    color: color,
  ).copyWith(fontFamilyFallback: [GoogleFonts.notoSans().fontFamily!]);

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
  }) => GoogleFonts.inter(
    fontSize: size,
    fontWeight: weight,
    color: color,
    fontFeatures: const [FontFeature.tabularFigures()],
  ).copyWith(fontFamilyFallback: [GoogleFonts.notoSans().fontFamily!]);
}

/// App-wide theme assembled from the tokens. Flat everything: zero elevation,
/// 1px borders instead of shadows, 6px default radius, 56pt touch minimums.
ThemeData buildPosTheme() {
  final textTheme = TextTheme(
    bodyLarge: T.text(),
    bodyMedium: T.text(),
    bodySmall: T.small(),
    titleLarge: T.headline(),
    titleMedium: T.text(weight: FontWeight.w600),
    titleSmall: T.small(weight: FontWeight.w600),
    labelLarge: T.text(weight: FontWeight.w500),
    headlineMedium: T.headline(),
    headlineSmall: T.headline(),
  );

  const scheme = ColorScheme.dark(
    surface: T.surface,
    onSurface: T.textPrimary,
    primary: T.accent,
    onPrimary: T.onAccent,
    secondary: T.textMuted,
    error: T.destructive,
    onError: T.onDestructive,
    outline: T.border,
    surfaceContainerHighest: T.surfaceAlt,
  );

  final buttonShape = RoundedRectangleBorder(borderRadius: T.radiusMedium);

  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: T.background,
    textTheme: textTheme,
    splashFactory: InkSparkle.splashFactory,
    // flat app bar: 1px bottom border, no shadow, no tint
    appBarTheme: AppBarTheme(
      backgroundColor: T.background,
      foregroundColor: T.textPrimary,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleTextStyle: T.headline(),
      shape: const Border(bottom: BorderSide(color: T.border)),
    ),
    dividerTheme: const DividerThemeData(
      color: T.border,
      thickness: 1,
      space: 1,
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: T.accent,
        foregroundColor: T.onAccent,
        minimumSize: const Size(T.minTouch, T.minTouch),
        shape: buttonShape,
        textStyle: T.text(weight: FontWeight.w600),
        animationDuration: T.dNormal,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: T.textPrimary,
        side: const BorderSide(color: T.border),
        minimumSize: const Size(T.minTouch, T.minTouch),
        shape: buttonShape,
        textStyle: T.text(weight: FontWeight.w500),
        animationDuration: T.dNormal,
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: T.textMuted,
        minimumSize: const Size(T.minTouch, T.minTouch),
        shape: buttonShape,
        textStyle: T.text(weight: FontWeight.w500),
        animationDuration: T.dNormal,
      ),
    ),
    iconButtonTheme: IconButtonThemeData(
      style: IconButton.styleFrom(
        foregroundColor: T.textMuted,
        minimumSize: const Size(T.minTouch, T.minTouch),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: T.surface,
      labelStyle: T.small(),
      hintStyle: T.small(),
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 16),
      enabledBorder: OutlineInputBorder(
        borderRadius: T.radiusMedium,
        borderSide: const BorderSide(color: T.border),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: T.radiusMedium,
        borderSide: const BorderSide(color: T.accent),
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
      backgroundColor: T.surfaceAlt,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: T.radiusLarge,
        side: const BorderSide(color: T.border),
      ),
      titleTextStyle: T.headline(),
      contentTextStyle: T.text(),
    ),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: T.surfaceAlt,
      contentTextStyle: T.text(),
      shape: RoundedRectangleBorder(
        borderRadius: T.radiusMedium,
        side: const BorderSide(color: T.border),
      ),
      behavior: SnackBarBehavior.floating,
      elevation: 0,
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected) ? T.accent : T.textMuted,
      ),
      trackColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected)
            ? T.accent.withValues(alpha: .35)
            : T.surface,
      ),
      trackOutlineColor: const WidgetStatePropertyAll(T.border),
    ),
    listTileTheme: ListTileThemeData(
      textColor: T.textPrimary,
      iconColor: T.textMuted,
      titleTextStyle: T.text(),
      subtitleTextStyle: T.small(),
    ),
    progressIndicatorTheme: const ProgressIndicatorThemeData(color: T.accent),
    popupMenuTheme: PopupMenuThemeData(
      color: T.surfaceAlt,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: T.radiusMedium,
        side: const BorderSide(color: T.border),
      ),
      textStyle: T.text(),
    ),
  );
}
