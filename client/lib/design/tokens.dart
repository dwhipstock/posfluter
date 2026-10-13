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
  /// The restaurant screens' colours: the pubs' ([PubPalette.copperLantern], the
  /// default) or another restaurant brand's ([PubPalette.barcode], dark).
  /// Set by the brand's theme builder ([buildPosTheme]) before the screens
  /// build; every colour below reads it.
  static PubPalette palette = PubPalette.copperLantern;

  // brand
  static Color get navy => palette.navy; // logo badge; white on it ≈ 9.9:1
  static Color get navyDeep => palette.navyDeep; // header bands, pressed states
  static Color get copper => palette.copper; // white on it ≈ 5.1:1

  // roles
  static Color get primary =>
      palette.primary; // default buttons, focus, selection
  static Color get onPrimary => palette.onPrimary;
  static Color get accent =>
      palette.accent; // Pay, selected category, occupied table
  static Color get onAccent => palette.onAccent;
  static Color get accentSoft => palette.accentSoft; // tint for selected rows

  // surfaces: warm cream, never cool grey (the pubs)
  static Color get background => palette.background;
  static Color get surface => palette.surface;
  static Color get surfaceAlt => palette.surfaceAlt; // selected/elevated cards
  static Color get border => palette.border; // 1px separators
  static Color get textPrimary => palette.textPrimary; // ≈ 13:1 on background
  static Color get textMuted => palette.textMuted; // ≈ 6.2:1 on background
  static Color get onNavyMuted => palette.onNavyMuted; // secondary text on navy

  /// The header bands (the floor's app bar, a check's panel header, the
  /// counter and carry-out bars) and their ink: navy and white at the pubs.
  static Color get band => palette.band;
  static Color get onBand => palette.onBand;
  static Color get onBandMuted => palette.onBandMuted;

  static Color get destructive =>
      palette.destructive; // void, delete, force-close
  static Color get onDestructive => palette.onDestructive;

  /// Dark amber for warning TEXT and icons on cream (≈ 5.6:1).
  static Color get attention => palette.attention;

  /// Guest orders waiting to be verified: a gold fill that reads apart from
  /// the copper of an ordinary occupied table, with dark text on it.
  static Color get pending => palette.pending;
  static Color get onPending => palette.onPending; // ≈ 8.9:1 on [pending]

  /// No-photo menu tiles: muted pub-sign hues, each ≥ 4.5:1 as text on its
  /// own light tint, so the two letters stay readable.
  static List<Color> get tilePalette => palette.tilePalette;

  /// "Paid", "Ready": a go colour for text and icons.
  static Color get success => palette.success;

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
  static const _fallback = fontFallback;

  /// Glyphs the brand's family lacks: Noto Sans (Latin, symbols), then Noto
  /// Sans Thai (no other bundled family has Thai).
  static const fontFallback = ['NotoSans', 'NotoSansThai'];

  /// The family [text] draws with: Inter, unless the store's brand skin
  /// brings its own (set by that brand's theme builder; the pubs' theme puts
  /// Inter back, so their screens are unchanged).
  static String family = fontFamily;

  /// Body text: Inter with Noto Sans fallback for any glyph Inter lacks.
  static TextStyle text({
    double size = bodySize,
    FontWeight weight = FontWeight.w400,
    Color? color,
  }) => TextStyle(
    fontFamily: family,
    fontFamilyFallback: _fallback,
    fontSize: size,
    fontWeight: weight,
    color: color ?? textPrimary,
  );

  static TextStyle small({Color? color, FontWeight weight = FontWeight.w400}) =>
      text(size: smallSize, color: color ?? textMuted, weight: weight);

  static TextStyle headline({Color? color}) =>
      text(size: headlineSize, weight: FontWeight.w600, color: color);

  /// Prices: tabular figures so columns of numbers align.
  static TextStyle price({
    double size = priceSize,
    FontWeight weight = FontWeight.w500,
    Color? color,
  }) => text(
    size: size,
    weight: weight,
    color: color,
  ).copyWith(fontFeatures: const [FontFeature.tabularFigures()]);

  /// Receipt/bill paper: the platform's monospace font, so the printed
  /// text's fixed-width columns (amounts right-aligned by padding, not a
  /// table) actually line up on screen the way they do on the 48-column
  /// thermal printout. Falls back through a couple of common monospace
  /// families in case a platform doesn't recognise the generic name.
  /// On-screen receipt / bill card width. The server lets a printed row run
  /// to 48 columns (80 mm paper); at 14 px monospace (0.6 em per column) that
  /// is 403 px of text, + 2 × 20 px padding = 443 px. 460 leaves headroom so
  /// every printed row is one screen row (narrower screens still shrink it).
  static const double receiptCardWidth = 460;

  static TextStyle receipt() => const TextStyle(
    fontFamily: 'monospace',
    fontFamilyFallback: ['RobotoMono', 'Courier New', 'Courier'],
    fontSize: 14,
    color: receiptInk,
    fontFeatures: [FontFeature.tabularFigures()],
  );
}

/// App-wide theme assembled from the tokens: navy primary, cream surfaces,
/// 1px borders instead of heavy shadows, 8px default radius, 56pt touch minimums.
///
/// [palette] picks the restaurant brand's colours (the pubs' by default, or
/// Barcode Pub's dark bar) and [skin] its tokens; both are in force before
/// any screen builds.
ThemeData buildPosTheme({
  PubPalette palette = PubPalette.copperLantern,
  BrandSkin? skin,
}) {
  T.palette = palette;
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

  final scheme = ColorScheme(
    brightness: palette.brightness,
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
    side: BorderSide(color: T.border),
  );

  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    // the pubs' skin: the same tokens as above (a no-op for their screens)
    extensions: [skin ?? BrandSkin.copper],
    fontFamily: T.fontFamily,
    fontFamilyFallback: T.fontFallback,
    scaffoldBackgroundColor: T.background,
    canvasColor: T.background,
    textTheme: textTheme,
    splashFactory: InkSparkle.splashFactory,
    iconTheme: IconThemeData(color: T.textMuted),
    // warm app bar: 1px bottom border, no shadow, navy title
    appBarTheme: AppBarTheme(
      backgroundColor: T.surface,
      foregroundColor: T.navy,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleTextStyle: T.headline(color: T.navy),
      shape: Border(bottom: BorderSide(color: T.border)),
    ),
    dividerTheme: DividerThemeData(color: T.border, thickness: 1, space: 1),
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
        side: BorderSide(color: T.border),
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
        side: WidgetStatePropertyAll(BorderSide(color: T.border)),
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
      side: BorderSide(color: T.border),
      shape: buttonShape,
      labelStyle: T.small(color: T.textPrimary, weight: FontWeight.w600),
      secondaryLabelStyle: T.small(color: T.onPrimary, weight: FontWeight.w600),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
    ),
    checkboxTheme: CheckboxThemeData(
      fillColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected) ? T.navy : Colors.transparent,
      ),
      checkColor: WidgetStatePropertyAll(T.onPrimary),
      side: BorderSide(color: T.textMuted, width: 1.5),
    ),
    radioTheme: RadioThemeData(
      fillColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected) ? T.navy : T.textMuted,
      ),
    ),
    sliderTheme: SliderThemeData(
      activeTrackColor: T.navy,
      thumbColor: T.navy,
      inactiveTrackColor: T.border,
    ),
    expansionTileTheme: ExpansionTileThemeData(
      iconColor: T.navy,
      collapsedIconColor: T.textMuted,
      textColor: T.textPrimary,
      collapsedTextColor: T.textPrimary,
    ),
    bottomSheetTheme: BottomSheetThemeData(
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
        borderSide: BorderSide(color: T.border),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: T.radiusMedium,
        borderSide: BorderSide(color: T.navy, width: 1.5),
      ),
      errorBorder: OutlineInputBorder(
        borderRadius: T.radiusMedium,
        borderSide: BorderSide(color: T.destructive),
      ),
      focusedErrorBorder: OutlineInputBorder(
        borderRadius: T.radiusMedium,
        borderSide: BorderSide(color: T.destructive),
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
      textStyle: T.small(color: T.onPrimary, weight: FontWeight.w500),
      waitDuration: const Duration(milliseconds: 300),
    ),
    progressIndicatorTheme: ProgressIndicatorThemeData(color: T.navy),
    // a brand's spark (Barcode: LED yellow) marks focus and the active tab
    focusColor: palette.spark?.withValues(alpha: .35),
    tabBarTheme: palette.spark == null
        ? null
        : TabBarThemeData(
            indicatorColor: palette.spark,
            labelColor: T.textPrimary,
            unselectedLabelColor: T.textMuted,
            dividerColor: T.border,
          ),
    popupMenuTheme: PopupMenuThemeData(
      color: T.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 3,
      shadowColor: const Color(0x3317456E),
      shape: RoundedRectangleBorder(
        borderRadius: T.radiusMedium,
        side: BorderSide(color: T.border),
      ),
      textStyle: T.text(),
    ),
  );
}

/// The restaurant screens' colours ([T.palette]). Every pair the screens
/// use meets WCAG AA (4.5:1): ink on a fill is always that fill's `on…`.
@immutable
class PubPalette {
  final Brightness brightness;
  final Color navy, navyDeep, copper;
  final Color primary, onPrimary, accent, onAccent, accentSoft;
  final Color background, surface, surfaceAlt, border;
  final Color textPrimary, textMuted, onNavyMuted;
  final Color band, onBand, onBandMuted;
  final Color destructive, onDestructive, attention, pending, onPending;

  /// "Paid", "Ready": a go colour as text and icons on the surfaces.
  final Color success;

  /// A small highlight only (focus rings, the active tab): never a fill.
  /// Null = the primary.
  final Color? spark;
  final List<Color> tilePalette;

  const PubPalette({
    required this.brightness,
    required this.navy,
    required this.navyDeep,
    required this.copper,
    required this.primary,
    required this.onPrimary,
    required this.accent,
    required this.onAccent,
    required this.accentSoft,
    required this.background,
    required this.surface,
    required this.surfaceAlt,
    required this.border,
    required this.textPrimary,
    required this.textMuted,
    required this.onNavyMuted,
    required this.band,
    required this.onBand,
    required this.onBandMuted,
    required this.destructive,
    required this.onDestructive,
    required this.attention,
    required this.pending,
    required this.onPending,
    required this.success,
    this.spark,
    required this.tilePalette,
  });

  bool get isDark => brightness == Brightness.dark;

  /// The Copper Lantern pubs: the logo's deep navy, warm cream, a copper accent.
  static const copperLantern = PubPalette(
    brightness: Brightness.light,
    navy: Color(0xFF17456E),
    navyDeep: Color(0xFF0F3150),
    copper: Color(0xFFA65A23),
    primary: Color(0xFF17456E),
    onPrimary: Colors.white,
    accent: Color(0xFFA65A23),
    onAccent: Colors.white,
    accentSoft: Color(0xFFF3E3D3),
    background: Color(0xFFF6F0E5),
    surface: Color(0xFFFFFCF7),
    surfaceAlt: Color(0xFFEFE6D6),
    border: Color(0xFFDCCFB9),
    textPrimary: Color(0xFF1C2733),
    textMuted: Color(0xFF62574B),
    onNavyMuted: Color(0xFFC9D6E3),
    band: Color(0xFF17456E),
    onBand: Colors.white,
    onBandMuted: Color(0xFFC9D6E3),
    destructive: Color(0xFFB3261E),
    onDestructive: Colors.white,
    attention: Color(0xFF8C5200),
    pending: Color(0xFFF2B53A),
    onPending: Color(0xFF2B1D0A),
    success: Color(0xFF1E7A4C),
    tilePalette: [
      Color(0xFF17456E), // navy
      Color(0xFF8A4A1C), // copper
      Color(0xFF2F5D3A), // bottle green
      Color(0xFF7A2E3A), // claret
      Color(0xFF285E61), // teal
      Color(0xFF5B5220), // olive
      Color(0xFF5A3D6B), // plum
      Color(0xFF4A5563), // slate
    ],
  );

  /// Barcode Pub, Bangkok: black letters lit from behind by a warm yellow
  /// LED, like the bar's wall sign. Warm near-black and neutral dark-grey
  /// surfaces with light neutral text; the sign's LED yellow (#F7CD46,
  /// sampled from the glow around the sign's letters) where the pubs use
  /// navy: buttons, selection, the active room, always with near-black ink
  /// on it (12.4:1; white on it would be 1.5:1). Pressed states are a deeper
  /// gold (#D9A92B). The pubs' copper accent (Pay, the selected category, an
  /// occupied table) is a lighter glow yellow. The header bands are the
  /// sign's near-black ([band]), not yellow: the yellow is an accent, never
  /// a large fill. Guest orders waiting ([pending]) and warnings take the
  /// rust of the bar's side walls so they read apart from the yellow.
  /// Measured on #24221F (the lightest surface): text 13.2:1, muted 6.3:1,
  /// yellow 10.4:1, rust 5.4:1, red 5.1:1, green 9.1:1.
  static const barcode = PubPalette(
    brightness: Brightness.dark,
    navy: Color(0xFFF7CD46),
    navyDeep: Color(
      0xFFD9A92B,
    ), // deeper gold: pressed states, ink on surfaceAlt
    copper: Color(0xFFFFDA6A),
    primary: Color(0xFFF7CD46),
    onPrimary: Color(0xFF121110),
    accent: Color(0xFFFFDA6A),
    onAccent: Color(0xFF121110),
    accentSoft: Color(0xFF2E2714), // a dark gold tint for selected rows
    background: Color(0xFF121110),
    surface: Color(0xFF1A1816),
    surfaceAlt: Color(0xFF24221F),
    border: Color(0xFF3A3733),
    textPrimary: Color(0xFFEDEAE4),
    textMuted: Color(0xFFA8A39B),
    onNavyMuted: Color(0xFF4A3A0A), // secondary ink on yellow fills (7.3:1)
    // calm header bands: the sign's own near-black, light ink, yellow only
    // in what sits on them (the logo, the buttons)
    band: Color(0xFF161513),
    onBand: Color(0xFFEDEAE4),
    onBandMuted: Color(0xFFA8A39B),
    destructive: Color(0xFFF26464),
    onDestructive: Color(0xFF121110),
    attention: Color(0xFFE07B45), // the side walls' rust, lit
    pending: Color(0xFFE07B45),
    onPending: Color(0xFF1A0E06),
    success: Color(0xFF4ADE80),
    spark: Color(0xFFF7CD46),
    tilePalette: [
      Color(0xFFF7CD46), // LED yellow
      Color(0xFFE07B45), // rust
      Color(0xFF4ADE80), // green
      Color(0xFFF28B82), // coral
      Color(0xFF2DD4BF), // teal
      Color(0xFFD9A92B), // gold
      Color(0xFF93C5FD), // sky
      Color(0xFFD6D3CD), // stone
    ],
  );
}
