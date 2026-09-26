import 'package:flutter/material.dart';

import '../design/skin.dart';
import '../design/tokens.dart';

/// Sage & Poppy Bottle Shop — a different product on the same code, not a
/// recolour of the pub: bright and airy modern California retail. Near-white
/// surfaces with a breath of sage, sage green for navigation and selection, a
/// California-poppy orange kept for the one thing that must pop (Pay), and a
/// dark mode. No blue, no cream, no shadows.
///
/// Every text/background pair used by the retail screens meets WCAG AA
/// (4.5:1); the poppy fill carries white bold text at ≥18pt (large-text AA).
@immutable
class SpColors extends ThemeExtension<SpColors> {
  final Color sage; // brand primary (fills, focus, selection)
  final Color sageDeep; // rail, headings, pressed
  final Color onSage;
  final Color sageMist; // selected rail item, chips, tile hover
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
    required this.sageMist,
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
    sage: Color(0xFF4A6B47), // white on it ≈ 5.9:1
    sageDeep: Color(0xFF263D26),
    onSage: Colors.white,
    sageMist: Color(0xFFE5EEE1),
    poppy: Color(0xFFC2511A), // white on it ≈ 4.6:1
    onPoppy: Colors.white,
    poppySoft: Color(0xFFFDE9DC),
    background: Color(0xFFF4F6F1), // airy, a breath of sage
    surface: Color(0xFFFFFFFF),
    surfaceAlt: Color(0xFFEDF1E9),
    border: Color(0xFFDFE5DA),
    text: Color(0xFF172016), // ≈ 16:1 on background
    textMuted: Color(0xFF586255), // ≈ 6.1:1 on background
    ok: Color(0xFF2B6A34),
    okSoft: Color(0xFFDDEFDC),
    warn: Color(0xFF8A4B00),
    warnSoft: Color(0xFFFCEFD9),
    bad: Color(0xFFB0261C),
    badSoft: Color(0xFFFBE0DC),
    receiptPaper: Color(0xFFFFFFFF),
  );

  static const dark = SpColors(
    sage: Color(0xFF93BA8B), // dark text on it ≈ 8:1
    sageDeep: Color(0xFF0C110B),
    onSage: Color(0xFF0F170E),
    sageMist: Color(0xFF223020),
    poppy: Color(0xFFF28A44), // dark text on it ≈ 7.6:1
    onPoppy: Color(0xFF1E1005),
    poppySoft: Color(0xFF3A2416),
    background: Color(0xFF101410),
    surface: Color(0xFF171C16),
    surfaceAlt: Color(0xFF20271F),
    border: Color(0xFF2E382C),
    text: Color(0xFFEEF2EA),
    textMuted: Color(0xFFA9B4A4),
    ok: Color(0xFF8FD199),
    okSoft: Color(0xFF1E3322),
    warn: Color(0xFFF2B86B),
    warnSoft: Color(0xFF3A2C16),
    bad: Color(0xFFFF9A8E),
    badSoft: Color(0xFF3D1E1A),
    receiptPaper: Color(0xFFFFFFFF), // paper stays paper
  );

  /// Pronghorn Fuel & Market: the same roles on a near-black counter —
  /// signal yellow where Sage & Poppy has sage (selection, focus, the
  /// brand), a go-green Pay button where it has poppy. One palette, always
  /// dark: a cashier reads it at a glance under forecourt glare.
  /// Text pairs ≥ 7:1; black on the yellow ≈ 12:1, black on the green ≈ 9:1.
  static const pronghorn = SpColors(
    sage: Color(0xFFFFC400),
    sageDeep: Color(0xFF07080A),
    onSage: Color(0xFF111214),
    sageMist: Color(0xFF2B2612),
    poppy: Color(0xFF22C55E),
    onPoppy: Color(0xFF04130A),
    poppySoft: Color(0xFF3A2610),
    background: Color(0xFF0E1013),
    surface: Color(0xFF171A1F),
    surfaceAlt: Color(0xFF22262E),
    border: Color(0xFF30353E),
    text: Color(0xFFF4F6F8),
    textMuted: Color(0xFFA9B1BC),
    ok: Color(0xFF4ADE80),
    okSoft: Color(0xFF10291A),
    warn: Color(0xFFFFB020),
    warnSoft: Color(0xFF33270A),
    bad: Color(0xFFFF6B6B),
    badSoft: Color(0xFF3A1517),
    receiptPaper: Color(0xFFFFFFFF),
  );

  static SpColors of(BuildContext context) =>
      Theme.of(context).extension<SpColors>() ?? light;

  bool get isDark => background.computeLuminance() < .2;

  /// Brand green for text and icons that must read strongly (both modes).
  Color get strong => isDark ? sage : sageDeep;

  /// A selected chip / segment: deep sage with white (light), sage with
  /// near-black (dark).
  Color get selectedFill => isDark ? sage : sageDeep;
  Color get onSelected => isDark ? onSage : Colors.white;

  @override
  SpColors copyWith() => this;

  @override
  SpColors lerp(ThemeExtension<SpColors>? other, double t) =>
      other is SpColors && t >= .5 ? other : this;
}

/// Department colours: a thin band on each product tile, a dot on a row.
/// Muted, each ≥ 3:1 against the white tile (non-text contrast).
const spDepartmentHues = {
  'beer': Color(0xFFB7791F),
  'wine': Color(0xFF8C2F4B),
  'spirits': Color(0xFF7A5230),
  'seltzers': Color(0xFF2F7A78),
  'mixers': Color(0xFF3D6B8C),
  'snacks': Color(0xFFC2511A),
  'ice': Color(0xFF4E7FA0),
  'sundries': Color(0xFF6A5A8C),
};

/// The Material theme the terminal wears when the store is Sage & Poppy.
ThemeData buildSagePoppyTheme(Brightness brightness) => buildRetailTheme(
  brightness == Brightness.dark ? SpColors.dark : SpColors.light,
  BrandSkin.sagePoppy,
);

/// Pronghorn Fuel & Market: always its dark counter.
ThemeData buildPronghornTheme() =>
    buildRetailTheme(SpColors.pronghorn, BrandSkin.pronghorn);

/// A retail brand's Material theme from its palette roles and its skin.
ThemeData buildRetailTheme(SpColors c, BrandSkin skin) {
  final brightness = c.isDark ? Brightness.dark : Brightness.light;
  final pillControls = skin.pillControls;
  // the shared screens' T.text() draws in this brand's family too
  T.family = skin.fontFamily;
  TextStyle text({
    double size = T.bodySize,
    FontWeight weight = FontWeight.w400,
    Color? color,
  }) => skin.text(size: size, weight: weight, color: color ?? c.text);
  final scheme = ColorScheme(
    brightness: brightness,
    primary: c.sage,
    onPrimary: c.onSage,
    primaryContainer: c.sageMist,
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
  final OutlinedBorder pill = pillControls
      ? const StadiumBorder()
      : RoundedRectangleBorder(borderRadius: skin.radiusMedium);
  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    colorScheme: scheme,
    extensions: [c, skin],
    fontFamily: skin.fontFamily,
    fontFamilyFallback: skin.fontFallback,
    scaffoldBackgroundColor: c.background,
    canvasColor: c.background,
    splashFactory: InkRipple.splashFactory,
    textTheme: TextTheme(
      bodyLarge: text(),
      bodyMedium: text(),
      bodySmall: text(size: T.smallSize, color: c.textMuted),
      titleLarge: text(size: T.headlineSize, weight: FontWeight.w700),
      titleMedium: text(weight: FontWeight.w600),
      titleSmall: text(size: T.smallSize, weight: FontWeight.w600),
      labelLarge: text(weight: FontWeight.w600),
      labelMedium: text(size: T.smallSize, weight: FontWeight.w600),
    ),
    iconTheme: IconThemeData(color: c.textMuted),
    appBarTheme: AppBarTheme(
      backgroundColor: c.background,
      foregroundColor: c.text,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      titleTextStyle: text(size: 22, weight: FontWeight.w700),
    ),
    dividerTheme: DividerThemeData(color: c.border, thickness: 1, space: 1),
    cardTheme: CardThemeData(
      color: c.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      shape: RoundedRectangleBorder(borderRadius: skin.radiusMedium),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: c.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      shape: RoundedRectangleBorder(borderRadius: skin.radiusLarge),
      titleTextStyle: text(size: 22, weight: FontWeight.w700),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: c.sage,
        foregroundColor: c.onSage,
        minimumSize: const Size(T.minTouch, T.minTouch),
        shape: pill,
        textStyle: text(weight: FontWeight.w700),
        animationDuration: skin.normal,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: c.text,
        backgroundColor: c.surface,
        side: BorderSide(color: c.border, width: 1.5),
        minimumSize: const Size(T.minTouch, T.minTouch),
        shape: pill,
        textStyle: text(weight: FontWeight.w600),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: c.sage,
        minimumSize: const Size(T.minTouch, T.minTouch),
        shape: pill,
        textStyle: text(weight: FontWeight.w700),
      ),
    ),
    iconButtonTheme: IconButtonThemeData(
      style: IconButton.styleFrom(
        minimumSize: const Size(T.minTouch, T.minTouch),
      ),
    ),
    segmentedButtonTheme: SegmentedButtonThemeData(
      style: ButtonStyle(
        shape: WidgetStatePropertyAll(pill),
        side: WidgetStatePropertyAll(BorderSide(color: c.border)),
        backgroundColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected) ? c.sage : c.surface,
        ),
        foregroundColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected) ? c.onSage : c.text,
        ),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: c.surface,
      contentPadding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
      border: OutlineInputBorder(
        borderRadius: skin.radiusMedium,
        borderSide: BorderSide(color: c.border),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: skin.radiusMedium,
        borderSide: BorderSide(color: c.border),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: skin.radiusMedium,
        borderSide: BorderSide(color: c.sage, width: 2),
      ),
      labelStyle: text(size: T.smallSize, color: c.textMuted),
      hintStyle: text(color: c.textMuted),
    ),
    chipTheme: ChipThemeData(
      backgroundColor: c.surface,
      selectedColor: c.sageDeep,
      labelStyle: text(size: 15, weight: FontWeight.w600),
      secondaryLabelStyle: text(
        size: 15,
        weight: FontWeight.w700,
        color: Colors.white,
      ),
      side: BorderSide(color: c.border),
      shape: pill,
      showCheckmark: false,
    ),
    popupMenuTheme: PopupMenuThemeData(
      color: c.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 2,
      shape: RoundedRectangleBorder(borderRadius: skin.radiusMedium),
      textStyle: text(),
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: c.isDark ? c.surfaceAlt : c.sageDeep,
      contentTextStyle: skin.text(size: 16, color: Colors.white),
      shape: pill,
      elevation: 0,
    ),
    tooltipTheme: TooltipThemeData(
      decoration: BoxDecoration(
        color: c.sageDeep,
        borderRadius: BorderRadius.circular(40),
      ),
      textStyle: skin.text(
        size: 13,
        weight: FontWeight.w600,
        color: Colors.white,
      ),
    ),
    progressIndicatorTheme: ProgressIndicatorThemeData(color: c.sage),
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
